package io.qorche.core

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.nio.file.Path

/**
 * Interface for external workers that execute task instructions.
 *
 * Implementations wrap any process that modifies files — LLM agents, build tools,
 * formatters, code generators, CI steps, etc. The orchestrator treats all runners
 * identically: it takes before/after snapshots around the run and detects changes
 * via filesystem diffing, never trusting the runner's self-reported modifications.
 *
 * @see AgentEvent for the event stream contract
 */
interface AgentRunner {
    /**
     * Execute an instruction in the given working directory.
     *
     * @param instruction Free-text instruction describing the work to perform.
     * @param workingDirectory Root directory for filesystem operations.
     * @param onOutput Callback for real-time stdout/stderr streaming.
     * @return Flow of [AgentEvent]s representing the worker's lifecycle.
     */
    fun run(
        instruction: String,
        workingDirectory: Path,
        onOutput: (String) -> Unit = {}
    ): Flow<AgentEvent>
}

/**
 * Lifecycle events emitted by an [AgentRunner] during execution.
 *
 * These events are hints for logging and progress reporting. The orchestrator
 * does NOT rely on them for correctness — filesystem snapshots are ground truth.
 */
sealed class AgentEvent {
    /** Raw text output from the worker's stdout/stderr. */
    data class Output(val text: String) : AgentEvent()
    /** Worker reports modifying a file (used as a performance hint, not trusted). */
    data class FileModified(val path: String) : AgentEvent()
    /** Worker process has exited with the given code. */
    data class Completed(val exitCode: Int) : AgentEvent()
    /** Worker encountered an error. */
    data class Error(val message: String) : AgentEvent()
}

/**
 * Serializable result of a completed agent execution.
 *
 * Note: [filesModified] is the agent's self-reported list and may be incomplete
 * or inaccurate. The orchestrator uses snapshot diffs for correctness.
 */
@Serializable
data class AgentResult(
    val exitCode: Int,
    val filesModified: List<String> = emptyList(),
    val durationMs: Long = 0,
    val output: String = ""
)
