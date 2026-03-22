package io.qorche.agent

import io.qorche.core.AgentEvent
import io.qorche.core.AgentRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.nio.file.Path

class ClaudeCodeAdapter : AgentRunner {

    override fun run(
        instruction: String,
        workingDirectory: Path,
        onOutput: (String) -> Unit
    ): Flow<AgentEvent> = flow {
        val command = buildCommand(instruction)
        val process = ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .redirectErrorStream(true)
            .start()

        try {
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    onOutput(line)
                    emit(AgentEvent.Output(line))
                }
            }

            val exitCode = process.waitFor()
            emit(AgentEvent.Completed(exitCode))
        } catch (e: Exception) {
            emit(AgentEvent.Error(e.message ?: "Unknown error"))
            emit(AgentEvent.Completed(exitCode = 1))
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun buildCommand(instruction: String): List<String> {
        val os = System.getProperty("os.name", "").lowercase()
        val claudeBinary = when {
            os.contains("win") -> "claude.cmd"
            else -> "claude"
        }
        return listOf(claudeBinary, "--print", instruction)
    }
}
