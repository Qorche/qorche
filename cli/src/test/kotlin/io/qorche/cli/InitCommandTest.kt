package io.qorche.cli

import io.qorche.core.RunnerConfigLoader
import io.qorche.core.TaskYamlParser
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InitCommandTest {

    @Test
    fun `detects Gradle Kotlin project`() {
        val root = Files.createTempDirectory("qorche-init-test")
        try {
            root.resolve("build.gradle.kts").writeText("plugins { kotlin(\"jvm\") }")
            assertEquals(ProjectType.GRADLE_KOTLIN, detectProjectType(root))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `detects Node project`() {
        val root = Files.createTempDirectory("qorche-init-test")
        try {
            root.resolve("package.json").writeText("{}")
            assertEquals(ProjectType.NODE, detectProjectType(root))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `detects Python project via pyproject`() {
        val root = Files.createTempDirectory("qorche-init-test")
        try {
            root.resolve("pyproject.toml").writeText("[project]")
            assertEquals(ProjectType.PYTHON, detectProjectType(root))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `detects Python project via requirements txt`() {
        val root = Files.createTempDirectory("qorche-init-test")
        try {
            root.resolve("requirements.txt").writeText("flask>=2.0")
            assertEquals(ProjectType.PYTHON, detectProjectType(root))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `detects Rust project`() {
        val root = Files.createTempDirectory("qorche-init-test")
        try {
            root.resolve("Cargo.toml").writeText("[package]")
            assertEquals(ProjectType.RUST, detectProjectType(root))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `detects Go project`() {
        val root = Files.createTempDirectory("qorche-init-test")
        try {
            root.resolve("go.mod").writeText("module example.com/foo")
            assertEquals(ProjectType.GO, detectProjectType(root))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `falls back to generic`() {
        val root = Files.createTempDirectory("qorche-init-test")
        try {
            assertEquals(ProjectType.GENERIC, detectProjectType(root))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `generated YAML is parseable for all project types`() {
        val root = Files.createTempDirectory("qorche-init-test")
        try {
            for (type in ProjectType.entries) {
                val yaml = generateTasksYaml(type, root)
                val project = TaskYamlParser.parse(yaml)
                assertTrue(project.tasks.isNotEmpty(), "Generated YAML for ${type.label} should have tasks")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `qorignore has comment header`() {
        for (type in ProjectType.entries) {
            val content = generateQorignore(type)
            assertTrue(content.startsWith("# Qorche ignore patterns"), "Should start with header comment")
        }
    }

    @Test
    fun `runners example yaml has instructions and commented examples`() {
        val content = generateRunnersExample()
        assertTrue(content.contains("Copy this file to .qorche/runners.yaml"))
        assertTrue(content.contains("runners:"))
        assertTrue(content.contains("# shell:"))
        assertTrue(content.contains("# claude:"))
        assertTrue(content.contains("\${ANTHROPIC_API_KEY}"))
    }

    @Test
    fun `runners example yaml is parseable after uncommenting`() {
        val template = generateRunnersExample()

        // Uncomment the runner examples: lines starting with "  # " (indented comments)
        // are runner definitions. Header comments start at column 0.
        val uncommented = template.lines().joinToString("\n") { line ->
            val stripped = line.trimStart()
            val isHeaderComment = line == line.trimStart() && stripped.startsWith("#")
            if (stripped.startsWith("#") && !isHeaderComment) {
                line.replaceFirst("# ", "")
            } else {
                line
            }
        }

        // Replace ${ANTHROPIC_API_KEY} with a real value so it's valid YAML
        val withValues = uncommented.replace("\${ANTHROPIC_API_KEY}", "sk-test-key")

        // Parse as a runners.yaml file through RunnerConfigLoader's RunnersFile schema
        val parsed = RunnerConfigLoader.loadRunnersFromContent(withValues)
        assertTrue(parsed.isNotEmpty(), "Should parse at least one runner")
        assertTrue("shell" in parsed || "claude" in parsed, "Should contain shell or claude runner")
    }

    @Test
    fun `generated tasks yaml round-trips through encode and parse`() {
        val root = Files.createTempDirectory("qorche-init-test")
        try {
            for (type in ProjectType.entries) {
                val yaml = generateTasksYaml(type, root)
                val project = TaskYamlParser.parse(yaml)

                // Re-encode and parse again — should produce equivalent structure
                val reEncoded = TaskYamlParser.encode(project)
                val reParsed = TaskYamlParser.parse(reEncoded)

                assertEquals(
                    project.tasks.size, reParsed.tasks.size,
                    "Round-trip should preserve task count for $type"
                )
                assertEquals(
                    project.tasks.map { it.id }, reParsed.tasks.map { it.id },
                    "Round-trip should preserve task IDs for $type"
                )
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
