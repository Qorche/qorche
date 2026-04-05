package io.qorche.cli

import com.github.ajalt.clikt.testing.test
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigCommandTest {

    @Test
    fun `config shows no runners when none configured`() {
        val tmpDir = Files.createTempDirectory("qorche-config-test")
        try {
            val cmd = ConfigCommand(workDirProvider = { tmpDir })
            val result = cmd.test(emptyList())
            assertContains(result.output, "No runners configured")
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `config shows merged runners from tasks yaml`() {
        val tmpDir = Files.createTempDirectory("qorche-config-test")
        try {
            val tasksFile = tmpDir.resolve("tasks.yaml")
            tasksFile.writeText(
                """
                project: test
                runners:
                  shell:
                    type: shell
                    allowed_commands: [npm]
                    timeout_seconds: 60
                tasks:
                  - id: test
                    instruction: "npm test"
                    runner: shell
                """.trimIndent()
            )

            val cmd = ConfigCommand(workDirProvider = { tmpDir })
            val result = cmd.test(listOf(tasksFile.toString()))
            assertContains(result.output, "shell:")
            assertContains(result.output, "type: shell")
            assertContains(result.output, "timeout_seconds: 60")
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `config check passes when all runners present`() {
        val tmpDir = Files.createTempDirectory("qorche-config-test")
        try {
            val tasksFile = tmpDir.resolve("tasks.yaml")
            tasksFile.writeText(
                """
                project: test
                runners:
                  shell:
                    type: shell
                tasks:
                  - id: test
                    instruction: "echo hi"
                    runner: shell
                """.trimIndent()
            )

            val cmd = ConfigCommand(workDirProvider = { tmpDir })
            val result = cmd.test(listOf(tasksFile.toString(), "--check"))
            assertEquals(0, result.statusCode)
            assertContains(result.output, "All referenced runners are configured")
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `config check fails when runner missing`() {
        val tmpDir = Files.createTempDirectory("qorche-config-test")
        try {
            val tasksFile = tmpDir.resolve("tasks.yaml")
            tasksFile.writeText(
                """
                project: test
                tasks:
                  - id: test
                    instruction: "echo hi"
                    runner: missing-runner
                """.trimIndent()
            )

            val cmd = ConfigCommand(workDirProvider = { tmpDir })
            val result = cmd.test(listOf(tasksFile.toString(), "--check"))
            assertEquals(2, result.statusCode)
            assertContains(result.output, "missing-runner")
            assertContains(result.output, "QORCHE_RUNNER_MISSING-RUNNER_TYPE")
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `config env-template outputs env vars`() {
        val tmpDir = Files.createTempDirectory("qorche-config-test")
        try {
            val tasksFile = tmpDir.resolve("tasks.yaml")
            tasksFile.writeText(
                """
                project: test
                runners:
                  shell:
                    type: shell
                    allowed_commands: [npm, gradle]
                    timeout_seconds: 120
                tasks:
                  - id: test
                    instruction: "npm test"
                """.trimIndent()
            )

            val cmd = ConfigCommand(workDirProvider = { tmpDir })
            val result = cmd.test(listOf(tasksFile.toString(), "--env-template"))
            assertContains(result.output, "QORCHE_RUNNER_SHELL_TYPE=shell")
            assertContains(result.output, "QORCHE_RUNNER_SHELL_ALLOWED_COMMANDS=npm,gradle")
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `config json outputs valid json`() {
        val tmpDir = Files.createTempDirectory("qorche-config-test")
        try {
            val tasksFile = tmpDir.resolve("tasks.yaml")
            tasksFile.writeText(
                """
                project: test
                runners:
                  shell:
                    type: shell
                tasks:
                  - id: test
                    instruction: "echo hi"
                """.trimIndent()
            )

            val cmd = ConfigCommand(workDirProvider = { tmpDir })
            val result = cmd.test(listOf(tasksFile.toString(), "--json"))
            assertContains(result.output, "\"shell\"")
            assertContains(result.output, "\"type\": \"shell\"")
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `config merges project runners yaml with inline`() {
        val tmpDir = Files.createTempDirectory("qorche-config-test")
        try {
            // Create .qorche/runners.yaml
            val qorcheDir = tmpDir.resolve(".qorche")
            Files.createDirectories(qorcheDir)
            qorcheDir.resolve("runners.yaml").writeText(
                """
                runners:
                  shell:
                    type: shell
                    allowed_commands: [npm, gradle]
                    timeout_seconds: 60
                """.trimIndent()
            )

            // tasks.yaml with inline override
            val tasksFile = tmpDir.resolve("tasks.yaml")
            tasksFile.writeText(
                """
                project: test
                runners:
                  shell:
                    type: shell
                    timeout_seconds: 120
                tasks:
                  - id: test
                    instruction: "npm test"
                    runner: shell
                """.trimIndent()
            )

            val cmd = ConfigCommand(workDirProvider = { tmpDir })
            val result = cmd.test(listOf(tasksFile.toString()))
            // inline timeout (120) should win over project (60)
            assertContains(result.output, "timeout_seconds: 120")
            // allowed_commands from project should be inherited
            assertContains(result.output, "npm, gradle")
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `config env var override wins over all`() {
        val tmpDir = Files.createTempDirectory("qorche-config-test")
        try {
            val tasksFile = tmpDir.resolve("tasks.yaml")
            tasksFile.writeText(
                """
                project: test
                runners:
                  shell:
                    type: shell
                    timeout_seconds: 120
                tasks:
                  - id: test
                    instruction: "npm test"
                """.trimIndent()
            )

            val envVars = mapOf("QORCHE_RUNNER_SHELL_TIMEOUT_SECONDS" to "30")
            val cmd = ConfigCommand(
                workDirProvider = { tmpDir },
                envProvider = { envVars[it] }
            )
            val result = cmd.test(listOf(tasksFile.toString()))
            assertContains(result.output, "timeout_seconds: 30")
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `config fails on invalid yaml`() {
        val tmpDir = Files.createTempDirectory("qorche-config-test")
        try {
            val tasksFile = tmpDir.resolve("tasks.yaml")
            tasksFile.writeText("invalid: yaml: [broken")

            val cmd = ConfigCommand(workDirProvider = { tmpDir })
            val result = cmd.test(listOf(tasksFile.toString()))
            assertEquals(2, result.statusCode)
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `config fails on missing env var reference`() {
        val tmpDir = Files.createTempDirectory("qorche-config-test")
        try {
            val qorcheDir = tmpDir.resolve(".qorche")
            Files.createDirectories(qorcheDir)
            qorcheDir.resolve("runners.yaml").writeText("""
                runners:
                  claude:
                    type: claude-code
                    env:
                      API_KEY: ${'$'}{MISSING_SECRET}
            """.trimIndent())

            val tasksFile = tmpDir.resolve("tasks.yaml")
            tasksFile.writeText("""
                project: test
                tasks:
                  - id: t1
                    instruction: "do stuff"
            """.trimIndent())

            val cmd = ConfigCommand(
                workDirProvider = { tmpDir },
                envProvider = { null }
            )
            val result = cmd.test(listOf(tasksFile.toString()))
            assertEquals(2, result.statusCode)
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `config masks env values in yaml display`() {
        val tmpDir = Files.createTempDirectory("qorche-config-test")
        try {
            val tasksFile = tmpDir.resolve("tasks.yaml")
            tasksFile.writeText("""
                project: test
                runners:
                  claude:
                    type: claude-code
                    env:
                      ANTHROPIC_API_KEY: sk-ant-super-secret-key
                      OTHER_SECRET: password123
                tasks:
                  - id: t1
                    instruction: "do stuff"
            """.trimIndent())

            val cmd = ConfigCommand(workDirProvider = { tmpDir })
            val result = cmd.test(listOf(tasksFile.toString()))
            assertEquals(0, result.statusCode)
            // Env values should be fully masked
            assertContains(result.output, "ANTHROPIC_API_KEY: ****")
            assertContains(result.output, "OTHER_SECRET: ****")
            // Secret values must NOT appear in output
            assertFalse(result.output.contains("sk-ant"), "Secret prefix must not leak")
            assertFalse(result.output.contains("password123"), "Secret value must not leak")
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `config check passes with multiple runners all present`() {
        val tmpDir = Files.createTempDirectory("qorche-config-test")
        try {
            val tasksFile = tmpDir.resolve("tasks.yaml")
            tasksFile.writeText("""
                project: test
                runners:
                  shell:
                    type: shell
                    allowed_commands: [npm]
                  claude:
                    type: claude-code
                tasks:
                  - id: lint
                    instruction: "npm run lint"
                    runner: shell
                  - id: review
                    instruction: "review code"
                    runner: claude
            """.trimIndent())

            val cmd = ConfigCommand(workDirProvider = { tmpDir })
            val result = cmd.test(listOf(tasksFile.toString(), "--check"))
            assertEquals(0, result.statusCode)
            assertContains(result.output, "All referenced runners are configured")
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `config json includes runner fields`() {
        val tmpDir = Files.createTempDirectory("qorche-config-test")
        try {
            val tasksFile = tmpDir.resolve("tasks.yaml")
            tasksFile.writeText("""
                project: test
                runners:
                  shell:
                    type: shell
                    allowed_commands: [npm, gradle]
                    timeout_seconds: 120
                tasks:
                  - id: t1
                    instruction: "do stuff"
            """.trimIndent())

            val cmd = ConfigCommand(workDirProvider = { tmpDir })
            val result = cmd.test(listOf(tasksFile.toString(), "--json"))
            assertEquals(0, result.statusCode)
            assertContains(result.output, "\"timeout_seconds\": 120")
            assertContains(result.output, "\"npm\"")
            assertContains(result.output, "\"gradle\"")
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `config displays multiple runners sorted by name`() {
        val tmpDir = Files.createTempDirectory("qorche-config-test")
        try {
            val tasksFile = tmpDir.resolve("tasks.yaml")
            tasksFile.writeText("""
                project: test
                runners:
                  zulu:
                    type: shell
                    allowed_commands: [echo]
                  alpha:
                    type: claude-code
                tasks:
                  - id: t1
                    instruction: "do stuff"
            """.trimIndent())

            val cmd = ConfigCommand(workDirProvider = { tmpDir })
            val result = cmd.test(listOf(tasksFile.toString()))
            assertEquals(0, result.statusCode)
            // alpha should appear before zulu in output
            val alphaIdx = result.output.indexOf("alpha:")
            val zuluIdx = result.output.indexOf("zulu:")
            assertTrue(alphaIdx < zuluIdx, "Runners should be sorted alphabetically")
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }
}
