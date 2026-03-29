package io.qorche.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class InitCommand : CliktCommand(name = "init") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "Initialize a new Qorche project in the current directory"

    private val force by option("--force", help = "Overwrite existing tasks.yaml").flag()

    override fun run() {
        val workDir = Path.of(System.getProperty("user.dir"))
        val projectType = detectProjectType(workDir)

        // Create .qorche/ directory
        val qorcheDir = workDir.resolve(".qorche")
        if (!Files.exists(qorcheDir)) {
            Files.createDirectories(qorcheDir)
            echo("Created .qorche/")
        }

        // Create tasks.yaml
        val tasksFile = workDir.resolve("tasks.yaml")
        if (Files.exists(tasksFile) && !force) {
            echo("${Terminal.yellow("Skipped:")} tasks.yaml already exists (use --force to overwrite)")
        } else {
            val yaml = generateTasksYaml(projectType, workDir)
            Files.writeString(tasksFile, yaml)
            echo("Created tasks.yaml (${projectType.label} project)")
        }

        // Create .qorignore if not present
        val qorignoreFile = workDir.resolve(".qorignore")
        if (!Files.exists(qorignoreFile)) {
            val ignoreContent = generateQorignore(projectType)
            Files.writeString(qorignoreFile, ignoreContent)
            echo("Created .qorignore")
        }

        // Add .qorche/ to .gitignore if git repo
        val gitDir = workDir.resolve(".git")
        if (Files.isDirectory(gitDir)) {
            val gitignore = workDir.resolve(".gitignore")
            val alreadyIgnored = if (Files.exists(gitignore)) {
                Files.readString(gitignore).lines().any { it.trim() == ".qorche/" || it.trim() == ".qorche" }
            } else {
                false
            }
            if (!alreadyIgnored) {
                val entry = if (Files.exists(gitignore)) "\n.qorche/\n" else ".qorche/\n"
                Files.writeString(gitignore, entry, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                echo("Added .qorche/ to .gitignore")
            }
        }

        echo("")
        echo("Next steps:")
        echo("  qorche validate tasks.yaml   # Check your task file")
        echo("  qorche plan tasks.yaml       # Preview execution plan")
        echo("  qorche run tasks.yaml        # Execute tasks")
    }
}

enum class ProjectType(val label: String) {
    GRADLE_KOTLIN("Kotlin/Gradle"),
    GRADLE_JAVA("Java/Gradle"),
    MAVEN("Java/Maven"),
    NODE("JavaScript/TypeScript"),
    PYTHON("Python"),
    RUST("Rust"),
    GO("Go"),
    GENERIC("generic")
}

internal fun detectProjectType(workDir: Path): ProjectType = when {
    Files.exists(workDir.resolve("build.gradle.kts")) -> ProjectType.GRADLE_KOTLIN
    Files.exists(workDir.resolve("build.gradle")) -> ProjectType.GRADLE_JAVA
    Files.exists(workDir.resolve("pom.xml")) -> ProjectType.MAVEN
    Files.exists(workDir.resolve("package.json")) -> ProjectType.NODE
    Files.exists(workDir.resolve("pyproject.toml")) -> ProjectType.PYTHON
    Files.exists(workDir.resolve("requirements.txt")) -> ProjectType.PYTHON
    Files.exists(workDir.resolve("Cargo.toml")) -> ProjectType.RUST
    Files.exists(workDir.resolve("go.mod")) -> ProjectType.GO
    else -> ProjectType.GENERIC
}

internal fun generateTasksYaml(type: ProjectType, workDir: Path): String {
    val projectName = workDir.fileName?.toString() ?: "my-project"
    return when (type) {
        ProjectType.GRADLE_KOTLIN, ProjectType.GRADLE_JAVA -> """
            |project: $projectName
            |tasks:
            |  - id: lint
            |    instruction: "Run linter and fix style issues"
            |    files: [src/]
            |
            |  - id: test
            |    instruction: "Run the test suite"
            |    files: [src/, test/]
            |
            |  - id: build
            |    instruction: "Build the project"
            |    depends_on: [lint, test]
        """.trimMargin() + "\n"

        ProjectType.MAVEN -> """
            |project: $projectName
            |tasks:
            |  - id: lint
            |    instruction: "Run linter and fix style issues"
            |    files: [src/]
            |
            |  - id: test
            |    instruction: "Run the test suite"
            |    files: [src/]
            |
            |  - id: package
            |    instruction: "Package the application"
            |    depends_on: [lint, test]
        """.trimMargin() + "\n"

        ProjectType.NODE -> """
            |project: $projectName
            |tasks:
            |  - id: lint
            |    instruction: "Run linter and fix issues"
            |    files: [src/]
            |
            |  - id: test
            |    instruction: "Run the test suite"
            |    files: [src/, test/]
            |
            |  - id: build
            |    instruction: "Build the project"
            |    depends_on: [lint, test]
            |    files: [dist/]
        """.trimMargin() + "\n"

        ProjectType.PYTHON -> """
            |project: $projectName
            |tasks:
            |  - id: lint
            |    instruction: "Run linter and fix style issues"
            |    files: [src/]
            |
            |  - id: test
            |    instruction: "Run the test suite"
            |    files: [src/, tests/]
        """.trimMargin() + "\n"

        ProjectType.RUST -> """
            |project: $projectName
            |tasks:
            |  - id: lint
            |    instruction: "Run clippy and fix warnings"
            |    files: [src/]
            |
            |  - id: test
            |    instruction: "Run the test suite"
            |    files: [src/]
            |
            |  - id: build
            |    instruction: "Build the project"
            |    depends_on: [lint, test]
        """.trimMargin() + "\n"

        ProjectType.GO -> """
            |project: $projectName
            |tasks:
            |  - id: lint
            |    instruction: "Run go vet and staticcheck"
            |    files: [.]
            |
            |  - id: test
            |    instruction: "Run the test suite"
            |    files: [.]
        """.trimMargin() + "\n"

        ProjectType.GENERIC -> """
            |project: $projectName
            |tasks:
            |  - id: task-a
            |    instruction: "First task"
            |    files: [src/]
            |
            |  - id: task-b
            |    instruction: "Second task"
            |    files: [src/]
            |
            |  - id: finalize
            |    instruction: "Final task"
            |    depends_on: [task-a, task-b]
        """.trimMargin() + "\n"
    }
}

internal fun generateQorignore(type: ProjectType): String {
    val extras = when (type) {
        ProjectType.GRADLE_KOTLIN, ProjectType.GRADLE_JAVA -> listOf(
            "# Gradle/JVM extras",
            ".gradle/",
            "build/",
            ".kotlin/",
            "*.class"
        )
        ProjectType.MAVEN -> listOf(
            "# Maven extras",
            "target/",
            "*.class"
        )
        ProjectType.NODE -> listOf(
            "# Node extras",
            "node_modules/",
            ".next/",
            ".nuxt/",
            "coverage/"
        )
        ProjectType.PYTHON -> listOf(
            "# Python extras",
            ".venv/",
            "venv/",
            "__pycache__/",
            "*.pyc",
            ".mypy_cache/",
            ".ruff_cache/"
        )
        ProjectType.RUST -> listOf(
            "# Rust extras",
            "target/"
        )
        ProjectType.GO -> listOf(
            "# Go extras",
            "vendor/"
        )
        ProjectType.GENERIC -> emptyList()
    }

    return buildString {
        appendLine("# Qorche ignore patterns")
        appendLine("# Each line is a path prefix to exclude from snapshots")
        appendLine("# Lines starting with # are comments")
        appendLine("# Use !reset as the first line to clear default patterns")
        appendLine()
        for (line in extras) {
            appendLine(line)
        }
    }
}
