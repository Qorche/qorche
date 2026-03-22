package io.qorche.core

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.nio.file.Path

/**
 * Interface for running agents. Defined in core/ so that core code can
 * reference it without depending on agent/. Implementations live in agent/.
 */
interface AgentRunner {
    fun run(
        instruction: String,
        workingDirectory: Path,
        onOutput: (String) -> Unit = {}
    ): Flow<AgentEvent>
}

sealed class AgentEvent {
    data class Output(val text: String) : AgentEvent()
    data class FileModified(val path: String) : AgentEvent()
    data class Completed(val exitCode: Int) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
}

@Serializable
data class AgentResult(
    val exitCode: Int,
    val filesModified: List<String> = emptyList(),
    val durationMs: Long = 0,
    val output: String = ""
)
