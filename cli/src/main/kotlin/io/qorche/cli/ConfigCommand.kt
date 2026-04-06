package io.qorche.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.qorche.core.RunnerConfig
import io.qorche.core.RunnerConfigLoader
import io.qorche.core.TaskParseException
import io.qorche.core.TaskYamlParser
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Displays the merged runner configuration from all layers (project file, inline YAML, environment variables).
 * Supports validation mode (`--check`) to verify all referenced runners are configured, and
 * CI setup mode (`--env-template`) to generate environment variable templates.
 *
 * Usage: `qorche config [tasks.yaml] [--check] [--env-template] [--json]`
 */
class ConfigCommand(
    internal val workDirProvider: () -> Path = { Path.of(System.getProperty("user.dir")) },
    internal val envProvider: (String) -> String? = System::getenv
) : CliktCommand(name = "config") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Show merged runner configuration from all layers"

    private val yamlFile by argument(help = "Path to tasks.yaml file").optional()
    private val check by option("--check", help = "Validate runner configuration completeness").flag()
    private val envTemplate by option(
        "--env-template",
        help = "Output environment variable template for CI"
    ).flag()
    private val json by option("--json", help = "Output as JSON").flag()

    /** JSON serializer configured with pretty printing for `--json` output. */
    private val prettyJson = Json { prettyPrint = true }

    override fun run() {
        val workDir = workDirProvider()
        val yamlPath = yamlFile?.let { Path.of(it) }

        // Parse tasks.yaml leniently — runners may come from external sources
        val parsedProject = if (yamlPath != null && Files.exists(yamlPath)) {
            try {
                TaskYamlParser.parseFileLenient(yamlPath)
            } catch (e: TaskParseException) {
                echo("${Terminal.red("Error:")} Failed to parse ${yamlPath}: ${e.message}", err = true)
                throw ProgramResult(2)
            } catch (e: IllegalArgumentException) {
                echo("${Terminal.red("Error:")} Failed to parse ${yamlPath}: ${e.message}", err = true)
                throw ProgramResult(2)
            } catch (e: IOException) {
                echo("${Terminal.red("Error:")} Cannot read ${yamlPath}: ${e.message}", err = true)
                throw ProgramResult(2)
            }
        } else {
            null
        }

        val inlineRunners = parsedProject?.runners ?: emptyMap()
        val referencedRunners = parsedProject?.let { project ->
            project.tasks.mapNotNull { it.runner }.toSet() + listOfNotNull(project.defaultRunner)
        } ?: emptySet()

        val merged = try {
            RunnerConfigLoader.load(workDir, inlineRunners, envProvider)
        } catch (e: IllegalArgumentException) {
            echo("${Terminal.red("Error:")} ${e.message}", err = true)
            throw ProgramResult(2)
        }

        when {
            envTemplate -> {
                val template = RunnerConfigLoader.envTemplate(merged)
                if (template.isEmpty()) {
                    echo("No runners configured.")
                } else {
                    echo(template)
                }
            }
            check -> runCheck(merged, referencedRunners, workDir)
            else -> showMergedConfig(merged, workDir)
        }
    }

    private fun runCheck(
        merged: Map<String, RunnerConfig>,
        referencedRunners: Set<String>,
        workDir: Path
    ) {
        val diagnostics = RunnerConfigLoader.check(merged, referencedRunners)
        val projectFile = workDir.resolve(".qorche").resolve("runners.yaml")
        val hasProjectFile = Files.exists(projectFile)

        if (diagnostics.isEmpty()) {
            echo("${Terminal.green("✓")} All referenced runners are configured.")
            showLayerSummary(merged, workDir)
        } else {
            for (diag in diagnostics) {
                echo("${Terminal.yellow("⚠")} ${diag.message}")
                echo("  Set up via:")
                if (!hasProjectFile) {
                    echo("    .qorche/runners.yaml    → add '${diag.runnerName}' runner entry")
                }
                echo(
                    "    Environment variables   → QORCHE_RUNNER_${diag.runnerName.uppercase()}_TYPE=<type>"
                )
                echo("")
            }
            throw ProgramResult(2)
        }
    }

    private fun showMergedConfig(
        merged: Map<String, RunnerConfig>,
        workDir: Path
    ) {
        if (merged.isEmpty()) {
            echo("No runners configured.")
            return
        }

        if (json) {
            echo(prettyJson.encodeToString(merged))
        } else {
            showLayerSummary(merged, workDir)
            echo("")
            for ((name, config) in merged.entries.sortedBy { it.key }) {
                formatRunnerConfig(name, config)
            }
        }
    }

    private fun formatRunnerConfig(name: String, config: RunnerConfig) {
        echo("${Terminal.bold(name)}:")
        echo("  type: ${config.type}")
        config.model?.let { echo("  model: $it") }
        config.endpoint?.let { echo("  endpoint: $it") }
        if (config.extraArgs.isNotEmpty()) {
            echo("  extra_args: [${config.extraArgs.joinToString(", ")}]")
        }
        if (config.allowedCommands.isNotEmpty()) {
            echo("  allowed_commands: [${config.allowedCommands.joinToString(", ")}]")
        }
        echo("  timeout_seconds: ${config.timeoutSeconds}")
        formatRunnerEnv(config.env)
        echo("")
    }

    private fun formatRunnerEnv(env: Map<String, String>) {
        if (env.isEmpty()) return
        echo("  env:")
        for ((k, _) in env) {
            echo("    $k: ****")
        }
    }

    private fun showLayerSummary(
        merged: Map<String, RunnerConfig>,
        workDir: Path
    ) {
        val projectFile = workDir.resolve(".qorche").resolve("runners.yaml")
        echo("Layers:")
        if (Files.exists(projectFile)) {
            echo("  ${Terminal.green("●")} .qorche/runners.yaml (project)")
        } else {
            echo("  ${Terminal.dim("○")} .qorche/runners.yaml (not found)")
        }
        if (yamlFile != null) {
            echo("  ${Terminal.green("●")} $yamlFile (inline)")
        }
        echo("  ${Terminal.dim("●")} QORCHE_RUNNER_* env vars (runtime)")
        echo("Runners: ${merged.size} configured")
    }
}
