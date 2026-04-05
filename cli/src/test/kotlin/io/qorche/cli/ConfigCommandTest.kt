package io.qorche.cli

import com.github.ajalt.clikt.testing.test
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

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
}
