package io.qorche.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.qorche.agent.ClaudeCodeAdapter
import io.qorche.core.AgentEvent
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

class QorcheCommand : CliktCommand(name = "qorche") {
    override fun run() = Unit

    init {
        subcommands(RunCommand(), VersionCommand())
    }
}

class RunCommand : CliktCommand(name = "run") {
    private val instruction by argument()
    private val verbose by option("--verbose", "-v").flag()

    override fun run() {
        val workDir = Path.of(System.getProperty("user.dir"))
        val runner = ClaudeCodeAdapter()
        val startTime = System.currentTimeMillis()

        echo("Starting: $instruction")

        runBlocking {
            runner.run(instruction, workDir) { line ->
                if (verbose) echo("[agent] $line")
            }.onEach { event ->
                when (event) {
                    is AgentEvent.Output -> echo(event.text)
                    is AgentEvent.FileModified -> echo("  Modified: ${event.path}")
                    is AgentEvent.Completed -> {
                        val elapsed = System.currentTimeMillis() - startTime
                        echo("Completed (exit ${event.exitCode}) in ${elapsed}ms")
                    }
                    is AgentEvent.Error -> echo("Error: ${event.message}", err = true)
                }
            }.collect()
        }
    }
}

class VersionCommand : CliktCommand(name = "version") {
    override fun run() {
        echo("qorche 0.1.0-SNAPSHOT")
    }
}
