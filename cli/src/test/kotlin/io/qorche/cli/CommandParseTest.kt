package io.qorche.cli

import com.github.ajalt.clikt.core.MissingArgument
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.testing.test
import io.qorche.core.Orchestrator
import io.qorche.core.WALEntry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * CLI command tests using Clikt's test() helper and parse() method.
 *
 * test() captures echo output and returns a CliktCommandTestResult.
 * parse() throws exceptions instead of calling exitProcess(), making it
 * safe for unit testing. We test argument parsing, error handling, and
 * command wiring without killing the JVM.
 */
@Tag("smoke")
class CommandParseTest {

    // --- ReplayCommand ---

    @Test
    fun `replay with no WAL shows empty message`() {
        val root = Files.createTempDirectory("qorche-cmd-test")
        try {
            val origDir = System.getProperty("user.dir")
            System.setProperty("user.dir", root.toString())
            try {
                val result = ReplayCommand().test(emptyList())
                assertTrue(
                    result.output.contains("No WAL entries found"),
                    "Should indicate empty WAL: ${result.output}"
                )
            } finally {
                System.setProperty("user.dir", origDir)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `replay shows task entries after execution`() {
        val root = Files.createTempDirectory("qorche-cmd-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val orchestrator = Orchestrator(root)
            val runner = io.qorche.agent.MockAgentRunner(
                filesToTouch = listOf("src/output.txt"), delayMs = 10
            )
            runBlocking {
                orchestrator.runTask("test-task", "create output", runner)
            }

            val origDir = System.getProperty("user.dir")
            System.setProperty("user.dir", root.toString())
            try {
                val result = ReplayCommand().test(emptyList())
                assertTrue(result.output.contains("test-task"), "Should show task ID: ${result.output}")
                assertTrue(result.output.contains("Started"), "Should show started entry: ${result.output}")
                assertTrue(
                    result.output.contains("Completed") || result.output.contains("Failed"),
                    "Should show completion: ${result.output}"
                )
                assertTrue(result.output.contains("Summary:"), "Should show summary: ${result.output}")
            } finally {
                System.setProperty("user.dir", origDir)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `replay --check detects consistent state`() {
        val root = Files.createTempDirectory("qorche-cmd-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.kt").writeText("fun main() {}")

            val orchestrator = Orchestrator(root)
            val runner = io.qorche.agent.MockAgentRunner(
                filesToTouch = listOf("src/output.txt"), delayMs = 10
            )
            runBlocking {
                orchestrator.runTask("test-task", "create output", runner)
            }

            val origDir = System.getProperty("user.dir")
            System.setProperty("user.dir", root.toString())
            try {
                val result = ReplayCommand().test(listOf("--check"))
                assertTrue(result.output.contains("CONSISTENT"), "Should show consistent: ${result.output}")
            } finally {
                System.setProperty("user.dir", origDir)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    // --- VerifyCommand ---

    @Test
    fun `verify with missing file argument throws MissingArgument`() {
        assertFailsWith<MissingArgument> {
            val cmd = VerifyCommand()
            cmd.parse(emptyList())
        }
    }

    @Test
    fun `verify with no verify section in YAML exits with error`() {
        val root = Files.createTempDirectory("qorche-cmd-test")
        try {
            val yamlFile = root.resolve("tasks.yaml")
            yamlFile.writeText("""
                project: test
                tasks:
                  - id: task1
                    instruction: do thing
            """.trimIndent())

            val origDir = System.getProperty("user.dir")
            System.setProperty("user.dir", root.toString())
            try {
                val result = VerifyCommand().test(listOf(yamlFile.toString()))
                assertTrue(
                    result.output.contains("No 'verify' section"),
                    "Should indicate missing verify: ${result.output}"
                )
                assertEquals(2, result.statusCode, "Should exit with CONFIG_ERROR")
            } finally {
                System.setProperty("user.dir", origDir)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `verify runs command and reports success`() {
        val root = Files.createTempDirectory("qorche-cmd-test")
        try {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val echoCmd = if (isWindows) "cmd /c echo ok" else "echo ok"

            val yamlFile = root.resolve("tasks.yaml")
            yamlFile.writeText("""
                project: test
                verify:
                  command: "$echoCmd"
                  timeout_seconds: 30
                tasks:
                  - id: task1
                    instruction: do thing
            """.trimIndent())

            val origDir = System.getProperty("user.dir")
            System.setProperty("user.dir", root.toString())
            try {
                val result = VerifyCommand().test(listOf(yamlFile.toString()))
                assertTrue(result.output.contains("PASSED"), "Should show passed: ${result.output}")
                assertEquals(0, result.statusCode, "Should exit with SUCCESS")
            } finally {
                System.setProperty("user.dir", origDir)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    // --- PlanCommand ---

    @Test
    fun `plan with missing file argument throws MissingArgument`() {
        assertFailsWith<MissingArgument> {
            val cmd = PlanCommand()
            cmd.parse(emptyList())
        }
    }

    // --- SchemaCommand ---

    @Test
    fun `schema outputs valid JSON schema`() {
        val result = SchemaCommand().test(emptyList())
        assertTrue(result.output.contains("\"\$schema\""), "Should contain schema key: ${result.output}")
        assertTrue(result.output.contains("qorche"), "Should reference qorche: ${result.output}")
        assertTrue(result.output.contains("verify"), "Should include verify config: ${result.output}")
    }
}
