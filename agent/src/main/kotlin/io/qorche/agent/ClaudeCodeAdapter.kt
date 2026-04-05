package io.qorche.agent

import io.qorche.core.AgentEvent
import io.qorche.core.AgentRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Runs Claude Code CLI as a child process.
 *
 * Unlike [ShellRunner], this adapter inherits all parent-process environment
 * variables and merges [env] on top. This is intentional: Claude Code typically
 * needs inherited vars like `ANTHROPIC_API_KEY` and `PATH`. [ShellRunner] uses
 * an [envFilter][ShellRunner] to restrict inherited vars for tighter sandboxing.
 *
 * @param timeoutSeconds Maximum time to wait for the process to complete.
 * @param extraArgs Additional CLI arguments passed to claude (e.g. "--dangerously-skip-permissions").
 * @param env Environment variables to set on the runner process.
 */
class ClaudeCodeAdapter(
    private val timeoutSeconds: Long = 300,
    private val extraArgs: List<String> = emptyList(),
    private val env: Map<String, String> = emptyMap()
) : AgentRunner {

    override fun run(
        instruction: String,
        workingDirectory: Path,
        onOutput: (String) -> Unit
    ): Flow<AgentEvent> = flow {
        val command = buildCommand(instruction)
        val process = try {
            val pb = ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
            if (env.isNotEmpty()) {
                pb.environment().putAll(env)
            }
            pb.start()
        } catch (e: Exception) {
            emit(AgentEvent.Error("Failed to start process: ${e.message}"))
            emit(AgentEvent.Completed(exitCode = 2))
            return@flow
        }

        try {
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    onOutput(line)
                    emit(AgentEvent.Output(line))
                }
            }

            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                emit(AgentEvent.Error("Process timed out after ${timeoutSeconds}s"))
                emit(AgentEvent.Completed(exitCode = 124))
                return@flow
            }
            emit(AgentEvent.Completed(process.exitValue()))
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
        return listOf("claude", "--print") + extraArgs + listOf(instruction)
    }
}
