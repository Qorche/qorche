package io.qorche.agent

import io.qorche.core.AgentEvent
import io.qorche.core.RunnerConfig
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RunnerRegistryTest {

    @Test
    fun `builds claude-code runner from config`() {
        val configs = mapOf(
            "claude" to RunnerConfig(
                type = "claude-code",
                extraArgs = listOf("--dangerously-skip-permissions"),
                timeoutSeconds = 600
            )
        )

        val runners = RunnerRegistry.build(configs)
        assertTrue(runners["claude"] is ClaudeCodeAdapter)
    }

    @Test
    fun `builds shell runner from config`() {
        val configs = mapOf(
            "shell" to RunnerConfig(
                type = "shell",
                allowedCommands = listOf("npm", "gradle")
            )
        )

        val runners = RunnerRegistry.build(configs)
        assertTrue(runners["shell"] is ShellRunner)
    }

    @Test
    fun `rejects shell runner without allowed commands`() {
        val configs = mapOf(
            "bad-shell" to RunnerConfig(type = "shell")
        )

        assertFailsWith<IllegalArgumentException> {
            RunnerRegistry.build(configs)
        }
    }

    @Test
    fun `rejects unknown runner type`() {
        val configs = mapOf(
            "unknown" to RunnerConfig(type = "gpt-99")
        )

        assertFailsWith<IllegalArgumentException> {
            RunnerRegistry.build(configs)
        }
    }

    @Test
    fun `builds multiple runners from mixed config`() {
        val configs = mapOf(
            "claude" to RunnerConfig(type = "claude-code"),
            "shell" to RunnerConfig(type = "shell", allowedCommands = listOf("npm"))
        )

        val runners = RunnerRegistry.build(configs)
        assertTrue(runners.size == 2)
        assertTrue(runners["claude"] is ClaudeCodeAdapter)
        assertTrue(runners["shell"] is ShellRunner)
    }

    @Test
    fun `empty config returns empty map`() {
        val runners = RunnerRegistry.build(emptyMap())
        assertTrue(runners.isEmpty())
    }

    @Test
    fun `builds claude-code runner with env from config`() {
        val configs = mapOf(
            "claude" to RunnerConfig(
                type = "claude-code",
                model = "opus",
                extraArgs = listOf("--dangerously-skip-permissions"),
                timeoutSeconds = 600,
                env = mapOf("ANTHROPIC_API_KEY" to "sk-test-key")
            )
        )
        val runners = RunnerRegistry.build(configs)
        assertTrue(runners["claude"] is ClaudeCodeAdapter)
    }

    @Test
    fun `builds shell runner with all config fields`() {
        val configs = mapOf(
            "shell" to RunnerConfig(
                type = "shell",
                allowedCommands = listOf("npm", "gradle", "pytest"),
                timeoutSeconds = 120,
                env = mapOf("CI" to "true", "NODE_ENV" to "test")
            )
        )
        val runners = RunnerRegistry.build(configs)
        assertTrue(runners["shell"] is ShellRunner)
    }

    // --- Field propagation: verify config fields reach the constructed runner ---

    @Test
    fun `shell runner enforces allowedCommands from config`() = runBlocking {
        val configs = mapOf(
            "shell" to RunnerConfig(
                type = "shell",
                allowedCommands = listOf("echo")
            )
        )
        val runners = RunnerRegistry.build(configs)
        val runner = runners["shell"]!!
        val dir = Files.createTempDirectory("qorche-registry-test")

        try {
            // "rm" is not in allowedCommands — should be rejected
            val events = runner.run("rm -rf /", dir).toList()
            val error = events.filterIsInstance<AgentEvent.Error>().firstOrNull()
            assertTrue(error != null, "Should reject command not in allowlist")
            assertTrue(error.message.contains("not in the allowlist"))
        } finally {
            Files.deleteIfExists(dir)
        }
    }

    @Test
    fun `shell runner env from config reaches child process`() = runBlocking {
        val os = System.getProperty("os.name", "").lowercase()
        val (cmd, allowedCmd) = if (os.contains("win")) {
            // cmd /c set prints all env vars; we grep the output for our var
            "cmd /c set QORCHE_TEST_VAR" to "cmd"
        } else {
            "printenv QORCHE_TEST_VAR" to "printenv"
        }

        val configs = mapOf(
            "shell" to RunnerConfig(
                type = "shell",
                allowedCommands = listOf(allowedCmd),
                env = mapOf("QORCHE_TEST_VAR" to "propagated_value")
            )
        )
        val runners = RunnerRegistry.build(configs)
        val runner = runners["shell"]!!
        val dir = Files.createTempDirectory("qorche-registry-test")

        try {
            val events = runner.run(cmd, dir).toList()
            val completed = events.filterIsInstance<AgentEvent.Completed>().first()
            assertEquals(0, completed.exitCode)

            val output = events.filterIsInstance<AgentEvent.Output>()
                .joinToString("\n") { it.text.trim() }
            assertTrue(
                output.contains("propagated_value"),
                "Config env should reach the child process, got: $output"
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `shell runner env overrides inherited env`() = runBlocking {
        val os = System.getProperty("os.name", "").lowercase()
        val (cmd, allowedCmd) = if (os.contains("win")) {
            "cmd /c echo %QORCHE_OVERRIDE_TEST%" to "cmd"
        } else {
            "printenv QORCHE_OVERRIDE_TEST" to "printenv"
        }

        val configs = mapOf(
            "shell" to RunnerConfig(
                type = "shell",
                allowedCommands = listOf(allowedCmd),
                env = mapOf("QORCHE_OVERRIDE_TEST" to "from_config")
            )
        )
        val runners = RunnerRegistry.build(configs)
        val runner = runners["shell"]!!
        val dir = Files.createTempDirectory("qorche-registry-test")

        try {
            val events = runner.run(cmd, dir).toList()
            val output = events.filterIsInstance<AgentEvent.Output>()
                .joinToString("\n") { it.text.trim() }
            assertTrue(
                output.contains("from_config"),
                "Config env should override inherited env, got: $output"
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // Note: timeout propagation cannot be tested with the current ShellRunner
    // design. ShellRunner reads inputStream to EOF before calling waitFor(timeout),
    // so processes that outlive the timeout keep the stream open and the timeout
    // check is never reached. This requires a refactor to use concurrent stream
    // reading with coroutine-based timeout. Tracked separately.
}
