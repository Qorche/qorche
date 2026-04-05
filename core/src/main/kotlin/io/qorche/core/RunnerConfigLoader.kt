package io.qorche.core

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.nio.file.Files
import java.nio.file.Path

/**
 * Layered runner configuration loader.
 *
 * Merges runner configs from three layers (higher wins):
 * 1. Project defaults: `.qorche/runners.yaml` (gitignored, secrets live here)
 * 2. Task inline: `tasks.yaml → runners:` (checked in, the contract)
 * 3. Environment: `QORCHE_RUNNER_{NAME}_{FIELD}` env vars (CI/CD injection)
 *
 * After merging, `${VAR}` references in string values are resolved from the
 * system environment.
 */
object RunnerConfigLoader {

    private val envVarPattern = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)}""")
    private val runnersYaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    /**
     * YAML structure for the standalone `.qorche/runners.yaml` file.
     */
    @Serializable
    data class RunnersFile(
        val runners: Map<String, RunnerConfig> = emptyMap()
    )

    /**
     * Load and merge runner configs from all layers.
     *
     * @param workDir Working directory containing `.qorche/`
     * @param inlineRunners Runners defined inline in `tasks.yaml`
     * @param envProvider Function to read environment variables (testable)
     * @return Merged runner map with env vars resolved
     */
    fun load(
        workDir: Path,
        inlineRunners: Map<String, RunnerConfig>,
        envProvider: (String) -> String? = System::getenv
    ): Map<String, RunnerConfig> {
        // Layer 1: .qorche/runners.yaml (lowest priority)
        val projectRunners = loadProjectRunners(workDir)

        // Layer 2: tasks.yaml inline (medium priority) — overrides project defaults
        val merged = mergeRunners(projectRunners, inlineRunners)

        // Layer 3: QORCHE_RUNNER_* env vars (highest priority)
        val withEnvOverrides = applyEnvOverrides(merged, envProvider)

        // Resolve ${VAR} references in all string values
        return resolveEnvVars(withEnvOverrides, envProvider)
    }

    /**
     * Check which runners referenced in tasks are missing or incomplete.
     *
     * @return List of human-readable diagnostic messages. Empty = all good.
     */
    fun check(
        mergedRunners: Map<String, RunnerConfig>,
        referencedRunners: Set<String>
    ): List<ConfigDiagnostic> {
        val diagnostics = mutableListOf<ConfigDiagnostic>()
        for (name in referencedRunners) {
            if (name !in mergedRunners) {
                diagnostics.add(ConfigDiagnostic(name, "Runner '$name' is referenced but not configured."))
            }
        }
        return diagnostics
    }

    /**
     * Generate env var template for all runners in the merged config.
     */
    fun envTemplate(runners: Map<String, RunnerConfig>): String = buildString {
        for ((name, config) in runners.entries.sortedBy { it.key }) {
            val prefix = "QORCHE_RUNNER_${name.uppercase()}"
            appendLine("${prefix}_TYPE=${config.type}")
            config.model?.let { appendLine("${prefix}_MODEL=$it") }
            config.endpoint?.let { appendLine("${prefix}_ENDPOINT=$it") }
            if (config.extraArgs.isNotEmpty()) {
                appendLine("${prefix}_EXTRA_ARGS=${config.extraArgs.joinToString(",")}")
            }
            if (config.allowedCommands.isNotEmpty()) {
                appendLine("${prefix}_ALLOWED_COMMANDS=${config.allowedCommands.joinToString(",")}")
            }
            appendLine("${prefix}_TIMEOUT_SECONDS=${config.timeoutSeconds}")
            for ((envKey, envVal) in config.env) {
                appendLine("${prefix}_ENV_${envKey}=$envVal")
            }
        }
    }.trimEnd()

    data class ConfigDiagnostic(val runnerName: String, val message: String)

    /**
     * Parse a runners YAML string directly into a runner config map.
     * Useful for testing generated templates.
     */
    fun loadRunnersFromContent(content: String): Map<String, RunnerConfig> =
        runnersYaml.decodeFromString<RunnersFile>(content).runners

    // --- Internal ---

    internal fun loadProjectRunners(workDir: Path): Map<String, RunnerConfig> {
        val runnersPath = workDir.resolve(".qorche").resolve("runners.yaml")
        if (!Files.exists(runnersPath)) return emptyMap()
        val content = Files.readString(runnersPath)
        return runnersYaml.decodeFromString<RunnersFile>(content).runners
    }

    /**
     * Merge two runner maps. Fields from [override] replace fields from [base]
     * for the same runner name. New runners in either map are preserved.
     */
    internal fun mergeRunners(
        base: Map<String, RunnerConfig>,
        override: Map<String, RunnerConfig>
    ): Map<String, RunnerConfig> {
        val allNames = base.keys + override.keys
        return allNames.associateWith { name ->
            val b = base[name]
            val o = override[name]
            when {
                b == null -> o!!
                o == null -> b
                else -> mergeConfig(b, o)
            }
        }
    }

    /**
     * Field-level merge: override's non-default fields replace base's fields.
     */
    internal fun mergeConfig(base: RunnerConfig, override: RunnerConfig): RunnerConfig {
        return RunnerConfig(
            type = override.type,
            model = override.model ?: base.model,
            endpoint = override.endpoint ?: base.endpoint,
            extraArgs = override.extraArgs.ifEmpty { base.extraArgs },
            allowedCommands = override.allowedCommands.ifEmpty { base.allowedCommands },
            timeoutSeconds = if (override.timeoutSeconds != RunnerConfig.DEFAULT_TIMEOUT_SECONDS) {
                override.timeoutSeconds
            } else {
                base.timeoutSeconds
            },
            env = if (override.env.isNotEmpty()) base.env + override.env else base.env
        )
    }

    /**
     * Parse QORCHE_RUNNER_{NAME}_{FIELD} env vars and apply as overrides.
     */
    internal fun applyEnvOverrides(
        runners: Map<String, RunnerConfig>,
        envProvider: (String) -> String?
    ): Map<String, RunnerConfig> {
        val result = runners.toMutableMap()

        // Also discover new runners from env vars
        val envRunnerNames = discoverEnvRunnerNames()
        val allNames = runners.keys + envRunnerNames

        for (name in allNames) {
            val prefix = "QORCHE_RUNNER_${name.uppercase()}_"
            val base = result[name] ?: RunnerConfig(type = "shell")

            val type = envProvider("${prefix}TYPE") ?: base.type
            val model = envProvider("${prefix}MODEL") ?: base.model
            val endpoint = envProvider("${prefix}ENDPOINT") ?: base.endpoint
            val extraArgs = envProvider("${prefix}EXTRA_ARGS")
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: base.extraArgs
            val allowedCommands = envProvider("${prefix}ALLOWED_COMMANDS")
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: base.allowedCommands
            val timeoutSeconds = envProvider("${prefix}TIMEOUT_SECONDS")
                ?.toLongOrNull() ?: base.timeoutSeconds

            // Merge env vars from QORCHE_RUNNER_{NAME}_ENV_{KEY}
            val envMap = base.env.toMutableMap()
            for (envKey in base.env.keys) {
                envProvider("${prefix}ENV_$envKey")?.let { envMap[envKey] = it }
            }

            result[name] = RunnerConfig(
                type = type,
                model = model,
                endpoint = endpoint,
                extraArgs = extraArgs,
                allowedCommands = allowedCommands,
                timeoutSeconds = timeoutSeconds,
                env = envMap
            )
        }

        return result
    }

    /**
     * Resolve `${VAR}` references in all string values of runner configs.
     *
     * @throws IllegalArgumentException if a referenced env var is not set.
     */
    internal fun resolveEnvVars(
        runners: Map<String, RunnerConfig>,
        envProvider: (String) -> String?
    ): Map<String, RunnerConfig> = runners.mapValues { (name, config) ->
        config.copy(
            model = config.model?.let { resolveString(it, name, envProvider) },
            endpoint = config.endpoint?.let { resolveString(it, name, envProvider) },
            env = config.env.mapValues { (_, v) -> resolveString(v, name, envProvider) }
        )
    }

    private fun resolveString(value: String, runnerName: String, envProvider: (String) -> String?): String {
        return envVarPattern.replace(value) { match ->
            val varName = match.groupValues[1]
            envProvider(varName)
                ?: throw IllegalArgumentException(
                    "Runner '$runnerName': environment variable '$varName' is not set " +
                        "(referenced as \${$varName})"
                )
        }
    }

    /**
     * Discover runner names from QORCHE_RUNNER_*_TYPE env vars.
     *
     * We can't enumerate env vars portably, so this is a no-op.
     * Env var overrides only apply to runners already declared in YAML or project config.
     * To add a runner purely via env vars, define it in .qorche/runners.yaml first.
     */
    private fun discoverEnvRunnerNames(): Set<String> = emptySet()
}
