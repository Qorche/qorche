package io.qorche.core

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RunnerConfigLoaderTest {

    // --- mergeRunners ---

    @Test
    fun `mergeRunners returns empty when both empty`() {
        val result = RunnerConfigLoader.mergeRunners(emptyMap(), emptyMap())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `mergeRunners preserves base when no override`() {
        val base = mapOf("shell" to RunnerConfig(type = "shell", timeoutSeconds = 60))
        val result = RunnerConfigLoader.mergeRunners(base, emptyMap())
        assertEquals(60, result["shell"]?.timeoutSeconds)
    }

    @Test
    fun `mergeRunners override replaces base`() {
        val base = mapOf("shell" to RunnerConfig(type = "shell", timeoutSeconds = 60))
        val override = mapOf("shell" to RunnerConfig(type = "shell", timeoutSeconds = 120))
        val result = RunnerConfigLoader.mergeRunners(base, override)
        assertEquals(120, result["shell"]?.timeoutSeconds)
    }

    @Test
    fun `mergeRunners combines disjoint runner names`() {
        val base = mapOf("shell" to RunnerConfig(type = "shell"))
        val override = mapOf("claude" to RunnerConfig(type = "claude-code"))
        val result = RunnerConfigLoader.mergeRunners(base, override)
        assertEquals(2, result.size)
        assertEquals("shell", result["shell"]?.type)
        assertEquals("claude-code", result["claude"]?.type)
    }

    // --- mergeConfig ---

    @Test
    fun `mergeConfig override type always wins`() {
        val base = RunnerConfig(type = "shell")
        val override = RunnerConfig(type = "claude-code")
        val result = RunnerConfigLoader.mergeConfig(base, override)
        assertEquals("claude-code", result.type)
    }

    @Test
    fun `mergeConfig inherits model from base when override is null`() {
        val base = RunnerConfig(type = "claude-code", model = "sonnet")
        val override = RunnerConfig(type = "claude-code")
        val result = RunnerConfigLoader.mergeConfig(base, override)
        assertEquals("sonnet", result.model)
    }

    @Test
    fun `mergeConfig override model replaces base`() {
        val base = RunnerConfig(type = "claude-code", model = "sonnet")
        val override = RunnerConfig(type = "claude-code", model = "opus")
        val result = RunnerConfigLoader.mergeConfig(base, override)
        assertEquals("opus", result.model)
    }

    @Test
    fun `mergeConfig inherits endpoint from base`() {
        val base = RunnerConfig(type = "shell", endpoint = "http://localhost:8080")
        val override = RunnerConfig(type = "shell")
        val result = RunnerConfigLoader.mergeConfig(base, override)
        assertEquals("http://localhost:8080", result.endpoint)
    }

    @Test
    fun `mergeConfig inherits allowedCommands from base when override is empty`() {
        val base = RunnerConfig(type = "shell", allowedCommands = listOf("npm", "gradle"))
        val override = RunnerConfig(type = "shell")
        val result = RunnerConfigLoader.mergeConfig(base, override)
        assertEquals(listOf("npm", "gradle"), result.allowedCommands)
    }

    @Test
    fun `mergeConfig override allowedCommands replaces base`() {
        val base = RunnerConfig(type = "shell", allowedCommands = listOf("npm"))
        val override = RunnerConfig(type = "shell", allowedCommands = listOf("pytest"))
        val result = RunnerConfigLoader.mergeConfig(base, override)
        assertEquals(listOf("pytest"), result.allowedCommands)
    }

    @Test
    fun `mergeConfig merges env maps with override winning`() {
        val base = RunnerConfig(type = "shell", env = mapOf("CI" to "true", "NODE_ENV" to "dev"))
        val override = RunnerConfig(type = "shell", env = mapOf("NODE_ENV" to "production"))
        val result = RunnerConfigLoader.mergeConfig(base, override)
        assertEquals(mapOf("CI" to "true", "NODE_ENV" to "production"), result.env)
    }

    @Test
    fun `mergeConfig inherits env from base when override env is empty`() {
        val base = RunnerConfig(type = "shell", env = mapOf("CI" to "true", "NODE_ENV" to "dev"))
        val override = RunnerConfig(type = "shell")
        val result = RunnerConfigLoader.mergeConfig(base, override)
        assertEquals(mapOf("CI" to "true", "NODE_ENV" to "dev"), result.env)
    }

    // --- applyEnvOverrides ---

    @Test
    fun `applyEnvOverrides overrides model from env`() {
        val runners = mapOf("claude" to RunnerConfig(type = "claude-code", model = "sonnet"))
        val env = mapOf("QORCHE_RUNNER_CLAUDE_MODEL" to "opus")
        val result = RunnerConfigLoader.applyEnvOverrides(runners) { env[it] }
        assertEquals("opus", result["claude"]?.model)
    }

    @Test
    fun `applyEnvOverrides overrides endpoint from env`() {
        val runners = mapOf("custom" to RunnerConfig(type = "shell", endpoint = "http://old"))
        val env = mapOf("QORCHE_RUNNER_CUSTOM_ENDPOINT" to "http://new:8080")
        val result = RunnerConfigLoader.applyEnvOverrides(runners) { env[it] }
        assertEquals("http://new:8080", result["custom"]?.endpoint)
    }

    @Test
    fun `applyEnvOverrides overrides type from env`() {
        val runners = mapOf("claude" to RunnerConfig(type = "shell"))
        val env = mapOf("QORCHE_RUNNER_CLAUDE_TYPE" to "claude-code")
        val result = RunnerConfigLoader.applyEnvOverrides(runners) { env[it] }
        assertEquals("claude-code", result["claude"]?.type)
    }

    @Test
    fun `applyEnvOverrides overrides timeout from env`() {
        val runners = mapOf("shell" to RunnerConfig(type = "shell", timeoutSeconds = 300))
        val env = mapOf("QORCHE_RUNNER_SHELL_TIMEOUT_SECONDS" to "60")
        val result = RunnerConfigLoader.applyEnvOverrides(runners) { env[it] }
        assertEquals(60, result["shell"]?.timeoutSeconds)
    }

    @Test
    fun `applyEnvOverrides overrides allowed_commands from env`() {
        val runners = mapOf("shell" to RunnerConfig(type = "shell", allowedCommands = listOf("npm")))
        val env = mapOf("QORCHE_RUNNER_SHELL_ALLOWED_COMMANDS" to "gradle,pytest")
        val result = RunnerConfigLoader.applyEnvOverrides(runners) { env[it] }
        assertEquals(listOf("gradle", "pytest"), result["shell"]?.allowedCommands)
    }

    @Test
    fun `applyEnvOverrides preserves fields not in env`() {
        val runners = mapOf(
            "claude" to RunnerConfig(
                type = "claude-code",
                model = "sonnet",
                timeoutSeconds = 300
            )
        )
        val env = mapOf("QORCHE_RUNNER_CLAUDE_TIMEOUT_SECONDS" to "60")
        val result = RunnerConfigLoader.applyEnvOverrides(runners) { env[it] }
        assertEquals("sonnet", result["claude"]?.model)
        assertEquals(60, result["claude"]?.timeoutSeconds)
    }

    // --- resolveEnvVars ---

    @Test
    fun `resolveEnvVars resolves dollar-brace references`() {
        val runners = mapOf(
            "claude" to RunnerConfig(
                type = "claude-code",
                env = mapOf("API_KEY" to "\${MY_SECRET}")
            )
        )
        val env = mapOf("MY_SECRET" to "sk-1234")
        val result = RunnerConfigLoader.resolveEnvVars(runners) { env[it] }
        assertEquals("sk-1234", result["claude"]?.env?.get("API_KEY"))
    }

    @Test
    fun `resolveEnvVars resolves in model`() {
        val runners = mapOf(
            "claude" to RunnerConfig(type = "claude-code", model = "\${MODEL_NAME}")
        )
        val env = mapOf("MODEL_NAME" to "opus")
        val result = RunnerConfigLoader.resolveEnvVars(runners) { env[it] }
        assertEquals("opus", result["claude"]?.model)
    }

    @Test
    fun `resolveEnvVars resolves in endpoint`() {
        val runners = mapOf(
            "custom" to RunnerConfig(type = "shell", endpoint = "https://\${API_HOST}/v1")
        )
        val env = mapOf("API_HOST" to "api.example.com")
        val result = RunnerConfigLoader.resolveEnvVars(runners) { env[it] }
        assertEquals("https://api.example.com/v1", result["custom"]?.endpoint)
    }

    @Test
    fun `resolveEnvVars throws on missing env var`() {
        val runners = mapOf(
            "claude" to RunnerConfig(type = "claude-code", env = mapOf("KEY" to "\${MISSING_VAR}"))
        )
        val ex = assertFailsWith<IllegalArgumentException> {
            RunnerConfigLoader.resolveEnvVars(runners) { null }
        }
        assertTrue(ex.message!!.contains("MISSING_VAR"))
        assertTrue(ex.message!!.contains("not set"))
    }

    @Test
    fun `resolveEnvVars passes through strings without references`() {
        val runners = mapOf(
            "shell" to RunnerConfig(type = "shell", env = mapOf("CI" to "true"))
        )
        val result = RunnerConfigLoader.resolveEnvVars(runners) { null }
        assertEquals("true", result["shell"]?.env?.get("CI"))
    }

    @Test
    fun `resolveEnvVars handles multiple references in one string`() {
        val runners = mapOf(
            "custom" to RunnerConfig(
                type = "shell",
                endpoint = "\${PROTO}://\${HOST}"
            )
        )
        val env = mapOf("PROTO" to "https", "HOST" to "example.com")
        val result = RunnerConfigLoader.resolveEnvVars(runners) { env[it] }
        assertEquals("https://example.com", result["custom"]?.endpoint)
    }

    // --- loadProjectRunners ---

    @Test
    fun `loadProjectRunners returns empty when file missing`() {
        val tmpDir = Files.createTempDirectory("qorche-test")
        try {
            val result = RunnerConfigLoader.loadProjectRunners(tmpDir)
            assertTrue(result.isEmpty())
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `loadProjectRunners parses runners yaml`() {
        val tmpDir = Files.createTempDirectory("qorche-test")
        try {
            val qorcheDir = tmpDir.resolve(".qorche")
            Files.createDirectories(qorcheDir)
            qorcheDir.resolve("runners.yaml").writeText(
                """
                runners:
                  shell:
                    type: shell
                    allowed_commands: [npm, gradle]
                    timeout_seconds: 120
                  claude:
                    type: claude-code
                    model: sonnet
                """.trimIndent()
            )
            val result = RunnerConfigLoader.loadProjectRunners(tmpDir)
            assertEquals(2, result.size)
            assertEquals("shell", result["shell"]?.type)
            assertEquals(listOf("npm", "gradle"), result["shell"]?.allowedCommands)
            assertEquals("claude-code", result["claude"]?.type)
            assertEquals("sonnet", result["claude"]?.model)
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    // --- load (full pipeline) ---

    @Test
    fun `load merges all layers with correct priority`() {
        val tmpDir = Files.createTempDirectory("qorche-test")
        try {
            // Layer 1: project defaults
            val qorcheDir = tmpDir.resolve(".qorche")
            Files.createDirectories(qorcheDir)
            qorcheDir.resolve("runners.yaml").writeText(
                """
                runners:
                  shell:
                    type: shell
                    allowed_commands: [npm]
                    timeout_seconds: 60
                """.trimIndent()
            )

            // Layer 2: inline (higher priority)
            val inlineRunners = mapOf(
                "shell" to RunnerConfig(type = "shell", timeoutSeconds = 120)
            )

            // Layer 3: env vars (highest priority)
            val env = mapOf("QORCHE_RUNNER_SHELL_TIMEOUT_SECONDS" to "30")

            val result = RunnerConfigLoader.load(tmpDir, inlineRunners) { env[it] }

            // timeout should be 30 (env wins)
            assertEquals(30, result["shell"]?.timeoutSeconds)
            // allowed_commands should be [npm] (inherited from project defaults via inline empty)
            assertEquals(listOf("npm"), result["shell"]?.allowedCommands)
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    // --- check ---

    @Test
    fun `check returns empty when all runners configured`() {
        val merged = mapOf("shell" to RunnerConfig(type = "shell"))
        val result = RunnerConfigLoader.check(merged, setOf("shell"))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `check reports missing runner`() {
        val merged = mapOf("shell" to RunnerConfig(type = "shell"))
        val result = RunnerConfigLoader.check(merged, setOf("shell", "claude"))
        assertEquals(1, result.size)
        assertTrue(result[0].message.contains("claude"))
    }

    @Test
    fun `check returns empty when no runners referenced`() {
        val merged = mapOf("shell" to RunnerConfig(type = "shell"))
        val result = RunnerConfigLoader.check(merged, emptySet())
        assertTrue(result.isEmpty())
    }

    // --- envTemplate ---

    @Test
    fun `envTemplate generates correct format`() {
        val runners = mapOf(
            "shell" to RunnerConfig(
                type = "shell",
                allowedCommands = listOf("npm", "gradle"),
                timeoutSeconds = 120
            )
        )
        val template = RunnerConfigLoader.envTemplate(runners)
        assertTrue(template.contains("QORCHE_RUNNER_SHELL_TYPE=shell"))
        assertTrue(template.contains("QORCHE_RUNNER_SHELL_ALLOWED_COMMANDS=npm,gradle"))
        assertTrue(template.contains("QORCHE_RUNNER_SHELL_TIMEOUT_SECONDS=120"))
    }

    @Test
    fun `envTemplate sorts runners alphabetically`() {
        val runners = mapOf(
            "zulu" to RunnerConfig(type = "shell", allowedCommands = listOf("echo")),
            "alpha" to RunnerConfig(type = "claude-code"),
            "middle" to RunnerConfig(type = "shell", allowedCommands = listOf("npm"))
        )
        val template = RunnerConfigLoader.envTemplate(runners)
        val alphaIdx = template.indexOf("QORCHE_RUNNER_ALPHA")
        val middleIdx = template.indexOf("QORCHE_RUNNER_MIDDLE")
        val zuluIdx = template.indexOf("QORCHE_RUNNER_ZULU")
        assertTrue(alphaIdx < middleIdx, "alpha should come before middle")
        assertTrue(middleIdx < zuluIdx, "middle should come before zulu")
    }

    @Test
    fun `envTemplate includes env entries`() {
        val runners = mapOf(
            "claude" to RunnerConfig(
                type = "claude-code",
                env = mapOf("ANTHROPIC_API_KEY" to "sk-123")
            )
        )
        val template = RunnerConfigLoader.envTemplate(runners)
        assertTrue(template.contains("QORCHE_RUNNER_CLAUDE_ENV_ANTHROPIC_API_KEY=sk-123"))
    }

    // --- round-trip: envTemplate -> applyEnvOverrides ---

    @Test
    fun `envTemplate output can be parsed back as env overrides`() {
        val runners = mapOf(
            "shell" to RunnerConfig(
                type = "shell",
                allowedCommands = listOf("npm"),
                timeoutSeconds = 120,
                env = mapOf("CI" to "true", "NODE_ENV" to "production")
            )
        )
        // Generate the template
        val template = RunnerConfigLoader.envTemplate(runners)

        // Parse template lines into an env map (simulating what CI would set)
        val envMap = template.lines()
            .filter { it.contains("=") }
            .associate { line ->
                val (key, value) = line.split("=", limit = 2)
                key to value
            }

        // Apply those env vars as overrides to a base config that already declares the keys
        // (env var overrides can only replace existing keys, not add new ones)
        val base = mapOf(
            "shell" to RunnerConfig(
                type = "shell",
                env = mapOf("CI" to "false", "NODE_ENV" to "dev")
            )
        )
        val result = RunnerConfigLoader.applyEnvOverrides(base) { envMap[it] }

        assertEquals("shell", result["shell"]?.type)
        assertEquals(listOf("npm"), result["shell"]?.allowedCommands)
        assertEquals(120, result["shell"]?.timeoutSeconds)
        // Env vars should be applied via QORCHE_RUNNER_SHELL_ENV_CI and QORCHE_RUNNER_SHELL_ENV_NODE_ENV
        assertEquals("true", result["shell"]?.env?.get("CI"))
        assertEquals("production", result["shell"]?.env?.get("NODE_ENV"))
    }
}
