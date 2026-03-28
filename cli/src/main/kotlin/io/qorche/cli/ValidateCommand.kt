package io.qorche.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import io.qorche.core.CycleDetectedException
import io.qorche.core.TaskParseException
import io.qorche.core.TaskYamlParser
import java.nio.file.Path
import kotlin.system.exitProcess

class ValidateCommand : CliktCommand(name = "validate") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "Validate a YAML task file without running"

    private val file by argument(help = "Path to a YAML task file")

    override fun run() {
        val workDir = Path.of(System.getProperty("user.dir"))
        val filePath = workDir.resolve(file)

        val (project, graph) = try {
            TaskYamlParser.parseFileToGraph(filePath)
        } catch (e: TaskParseException) {
            echo("${Terminal.red("Invalid:")} ${e.message}", err = true)
            exitProcess(2)
        } catch (e: CycleDetectedException) {
            echo("${Terminal.red("Invalid:")} ${e.message}", err = true)
            exitProcess(2)
        } catch (e: IllegalArgumentException) {
            echo("${Terminal.red("Invalid:")} ${e.message}", err = true)
            exitProcess(2)
        }

        val taskCount = project.tasks.size
        val depCount = project.tasks.sumOf { it.dependsOn.size }
        val groups = graph.parallelGroups()

        echo("${Terminal.green("Valid:")} $taskCount tasks, $depCount dependencies, ${groups.size} execution groups")

        val warnings = detectScopeOverlaps(project.tasks)
        for (w in warnings) {
            echo("  ${Terminal.yellow("Warning:")} ${w.taskA} and ${w.taskB} overlap on ${w.overlappingFiles.joinToString(", ")}")
        }
    }
}
