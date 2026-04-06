package io.qorche.cli

import io.qorche.core.AgentRunner
import io.qorche.core.CycleDetectedException
import io.qorche.core.ExitCode
import io.qorche.core.HashAlgorithm
import io.qorche.core.Orchestrator
import io.qorche.core.RunnerConfig
import io.qorche.core.Snapshot
import io.qorche.core.SnapshotCreator
import io.qorche.core.SnapshotDiff
import io.qorche.core.TaskGraph
import io.qorche.core.TaskParseException
import io.qorche.core.TaskProject
import io.qorche.core.TaskStatus
import io.qorche.core.TaskYamlParser
import io.qorche.core.VerifyConfig
import io.qorche.core.WALEntry
import java.nio.file.Path

// --- Utilities ---

/**
 * Formats a duration in milliseconds into a human-readable string.
 *
 * @param ms elapsed time in milliseconds
 * @return formatted string, e.g. "1.2s" for >= 1000ms or "450ms" otherwise
 */
internal fun formatElapsed(ms: Long): String = when {
    ms >= 1000 -> "%.1fs".format(ms / 1000.0)
    else -> "${ms}ms"
}

/**
 * Reads the CLI version from the embedded resource file.
 *
 * @return the version string, or "dev" if the resource is not found
 */
internal fun cliVersion(): String =
    object {}.javaClass.getResourceAsStream("/io/qorche/cli/version.txt")
        ?.bufferedReader()?.readText()?.trim() ?: "dev"

/**
 * Parses a user-provided hash algorithm string into a [HashAlgorithm].
 * Defaults to [HashAlgorithm.SHA1] for unrecognized or null input.
 *
 * @param raw the algorithm name (e.g. "crc32c", "sha256"), case-insensitive
 * @return the corresponding [HashAlgorithm]
 */
internal fun parseHashAlgorithm(raw: String?): HashAlgorithm = when (raw?.lowercase()) {
    "crc32c", "crc32" -> HashAlgorithm.CRC32C
    "sha256", "sha-256" -> HashAlgorithm.SHA256
    else -> HashAlgorithm.SHA1
}

/**
 * Checks whether a path string ends with a YAML file extension (.yaml or .yml).
 *
 * @param path the file path or name to check
 * @return true if the path has a YAML extension
 */
internal fun isYamlFile(path: String): Boolean =
    path.endsWith(".yaml") || path.endsWith(".yml")

// --- Shared: YAML loading ---

/**
 * Result of loading and parsing a YAML task file into a [TaskGraph].
 *
 * - [Success] contains the parsed [TaskProject] and constructed [TaskGraph].
 * - [ParseError] contains the error message when parsing fails (syntax, cycle, or validation errors).
 */
sealed class TaskGraphLoadResult {
    /** Successfully parsed task file with the resulting project and dependency graph. */
    data class Success(val project: TaskProject, val graph: TaskGraph) : TaskGraphLoadResult()

    /** Parsing failed due to invalid YAML, cycles, or constraint violations. */
    data class ParseError(val message: String) : TaskGraphLoadResult()
}

/**
 * Loads a YAML task file and builds the task dependency graph.
 *
 * @param filePath path to the YAML task file
 * @return [TaskGraphLoadResult.Success] with the project and graph, or [TaskGraphLoadResult.ParseError]
 */
fun loadTaskGraph(filePath: Path): TaskGraphLoadResult = try {
    val (project, graph) = TaskYamlParser.parseFileToGraph(filePath)
    TaskGraphLoadResult.Success(project, graph)
} catch (e: TaskParseException) {
    TaskGraphLoadResult.ParseError(e.message ?: "Unknown parse error")
} catch (e: CycleDetectedException) {
    TaskGraphLoadResult.ParseError(e.message ?: "Cycle detected")
} catch (e: IllegalArgumentException) {
    TaskGraphLoadResult.ParseError(e.message ?: "Invalid task file")
}

// --- Verify ---

/**
 * Result of loading the verify configuration from a task YAML file.
 *
 * - [Success] contains the extracted [VerifyConfig].
 * - [ParseError] indicates the YAML file could not be parsed.
 * - [NoVerifySection] indicates the file parsed successfully but has no `verify:` section.
 */
sealed class VerifyLoadResult {
    /** Verify configuration was successfully extracted. */
    data class Success(val config: VerifyConfig) : VerifyLoadResult()

    /** The YAML file could not be parsed. */
    data class ParseError(val message: String) : VerifyLoadResult()

    /** The file has no `verify` section. */
    data class NoVerifySection(val fileName: String) : VerifyLoadResult()
}

/**
 * Loads the verify configuration from a YAML task file.
 *
 * @param filePath path to the YAML task file
 * @return [VerifyLoadResult.Success] with the config, [VerifyLoadResult.NoVerifySection] if absent,
 *   or [VerifyLoadResult.ParseError] on parse failure
 */
fun loadVerifyConfig(filePath: Path): VerifyLoadResult = try {
    val project = TaskYamlParser.parseFile(filePath)
    val config = project.verify
    if (config != null) {
        VerifyLoadResult.Success(config)
    } else {
        VerifyLoadResult.NoVerifySection(filePath.fileName.toString())
    }
} catch (e: TaskParseException) {
    VerifyLoadResult.ParseError(e.message ?: "Unknown parse error")
}

/**
 * Outcome of executing a verification command.
 *
 * - [Passed] -- the command exited with code 0.
 * - [Failed] -- the command exited with a non-zero code.
 * - [Timeout] -- the command did not complete within the configured timeout.
 * - [Error] -- an I/O or interruption error prevented execution.
 */
sealed class VerifyOutcome {
    /** Verification passed (exit code 0). */
    data class Passed(val elapsedMs: Long) : VerifyOutcome()

    /** Verification failed with a non-zero exit code. */
    data class Failed(val exitCode: Int, val elapsedMs: Long) : VerifyOutcome()

    /** Verification timed out before completing. */
    data class Timeout(val timeoutSeconds: Long) : VerifyOutcome()

    /** An I/O or system error prevented the verification from running. */
    data class Error(val message: String) : VerifyOutcome()
}

/**
 * Executes the verification command defined in a [VerifyConfig] as a subprocess.
 *
 * On Windows, the command is wrapped with `cmd /c`; on Unix, with `sh -c`.
 *
 * @param config the verification configuration containing the command and timeout
 * @param workDir the working directory for the subprocess
 * @param onOutput callback invoked for each line of combined stdout/stderr output
 * @return the [VerifyOutcome] describing how the command completed
 */
fun executeVerification(
    config: VerifyConfig,
    workDir: Path,
    onOutput: (String) -> Unit = {}
): VerifyOutcome {
    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    val command = if (isWindows) {
        listOf("cmd", "/c", config.command)
    } else {
        listOf("sh", "-c", config.command)
    }

    val startTime = System.currentTimeMillis()
    return try {
        val process = ProcessBuilder(command)
            .directory(workDir.toFile())
            .redirectErrorStream(true)
            .start()

        process.inputStream.bufferedReader().use { reader ->
            reader.lineSequence().forEach { line -> onOutput(line) }
        }

        val completed = process.waitFor(
            config.timeoutSeconds,
            java.util.concurrent.TimeUnit.SECONDS
        )
        val elapsed = System.currentTimeMillis() - startTime

        if (!completed) {
            process.destroyForcibly()
            return VerifyOutcome.Timeout(config.timeoutSeconds)
        }

        val exitCode = process.exitValue()
        if (exitCode == 0) {
            VerifyOutcome.Passed(elapsed)
        } else {
            VerifyOutcome.Failed(exitCode, elapsed)
        }
    } catch (e: java.io.IOException) {
        VerifyOutcome.Error(e.message ?: "I/O error")
    } catch (e: InterruptedException) {
        VerifyOutcome.Error(e.message ?: "Interrupted")
    }
}

// --- Replay ---

/** Categorization of WAL entry types for formatted replay output. */
enum class WalEntryType {
    STARTED, COMPLETED, FAILED, CONFLICT,
    RETRY_SCHEDULED, RETRIED, SCOPE_VIOLATION, VERIFY
}

/**
 * A WAL entry formatted for human-readable terminal output.
 *
 * @property type the category of this entry (started, completed, conflict, etc.)
 * @property taskId the associated task ID or a pseudo-ID like "SCOPE" or "VERIFY"
 * @property headline a single-line summary of the event
 * @property details additional detail lines shown in verbose mode
 */
data class FormattedWalEntry(
    val type: WalEntryType,
    val taskId: String,
    val headline: String,
    val details: List<String> = emptyList()
)

/**
 * Aggregated summary of a WAL replay, including counts by event type and formatted entries.
 *
 * @property totalEntries total number of WAL entries processed
 * @property taskCount number of task-started events
 * @property completedCount number of task-completed events
 * @property failedCount number of task-failed events
 * @property retryCount number of retry-scheduled events
 * @property conflictCount number of conflict-detected events
 * @property verifyCount number of verification events
 * @property formattedEntries the formatted entries ready for display
 */
data class ReplaySummary(
    val totalEntries: Int,
    val taskCount: Int,
    val completedCount: Int,
    val failedCount: Int,
    val retryCount: Int,
    val conflictCount: Int,
    val verifyCount: Int,
    val formattedEntries: List<FormattedWalEntry>
)

/**
 * Processes a list of WAL entries into a [ReplaySummary] with formatted display entries.
 *
 * @param entries the raw WAL entries to process
 * @param verbose whether to include detailed information in each formatted entry
 * @return a summary with event counts and formatted entries
 */
fun summarizeReplay(entries: List<WALEntry>, verbose: Boolean): ReplaySummary {
    var taskCount = 0
    var completedCount = 0
    var failedCount = 0
    var retryCount = 0
    var conflictCount = 0
    var verifyCount = 0
    val formatted = mutableListOf<FormattedWalEntry>()

    for (entry in entries) {
        formatted.add(formatWalEntry(entry, verbose))
        when (entry) {
            is WALEntry.TaskStarted -> taskCount++
            is WALEntry.TaskCompleted -> completedCount++
            is WALEntry.TaskFailed -> failedCount++
            is WALEntry.TaskRetryScheduled -> retryCount++
            is WALEntry.ConflictDetected -> conflictCount++
            is WALEntry.VerifyCompleted -> verifyCount++
            is WALEntry.TaskRetried -> {}
            is WALEntry.ScopeViolation -> {}
        }
    }

    return ReplaySummary(
        totalEntries = entries.size,
        taskCount = taskCount,
        completedCount = completedCount,
        failedCount = failedCount,
        retryCount = retryCount,
        conflictCount = conflictCount,
        verifyCount = verifyCount,
        formattedEntries = formatted
    )
}

private fun formatWalEntry(entry: WALEntry, verbose: Boolean): FormattedWalEntry = when (entry) {
    is WALEntry.TaskStarted -> formatTaskStarted(entry, verbose)
    is WALEntry.TaskCompleted -> formatTaskCompleted(entry, verbose)
    is WALEntry.TaskFailed -> FormattedWalEntry(WalEntryType.FAILED, entry.taskId, "Failed: ${entry.error}")
    is WALEntry.ConflictDetected -> formatConflict(entry, verbose)
    is WALEntry.TaskRetryScheduled -> formatRetryScheduled(entry, verbose)
    is WALEntry.TaskRetried -> formatRetried(entry, verbose)
    is WALEntry.ScopeViolation -> formatScopeViolation(entry, verbose)
    is WALEntry.VerifyCompleted -> formatVerifyCompleted(entry, verbose)
}

private fun formatTaskStarted(entry: WALEntry.TaskStarted, verbose: Boolean) = FormattedWalEntry(
    type = WalEntryType.STARTED, taskId = entry.taskId,
    headline = "Started at ${entry.timestamp}",
    details = if (verbose) listOf("Instruction: ${entry.instruction}", "Snapshot: ${entry.snapshotId.take(8)}") else emptyList()
)

private fun formatTaskCompleted(entry: WALEntry.TaskCompleted, verbose: Boolean) = FormattedWalEntry(
    type = WalEntryType.COMPLETED, taskId = entry.taskId,
    headline = "Completed (exit ${entry.exitCode})",
    details = if (verbose) buildList {
        add("Snapshot: ${entry.snapshotId.take(8)}")
        if (entry.filesModified.isNotEmpty()) add("Files: ${entry.filesModified.joinToString(", ")}")
    } else emptyList()
)

private fun formatConflict(entry: WALEntry.ConflictDetected, verbose: Boolean) = FormattedWalEntry(
    type = WalEntryType.CONFLICT, taskId = entry.taskId,
    headline = "${entry.taskId} <-> ${entry.conflictingTaskId}",
    details = if (verbose) listOf(
        "Files: ${entry.conflictingFiles.joinToString(", ")}", "Base: ${entry.baseSnapshotId.take(8)}"
    ) else emptyList()
)

private fun formatRetryScheduled(entry: WALEntry.TaskRetryScheduled, verbose: Boolean) = FormattedWalEntry(
    type = WalEntryType.RETRY_SCHEDULED, taskId = entry.taskId,
    headline = "Retry #${entry.attempt} (conflict with ${entry.conflictWith})",
    details = if (verbose) listOf("Files: ${entry.conflictingFiles.joinToString(", ")}") else emptyList()
)

private fun formatRetried(entry: WALEntry.TaskRetried, verbose: Boolean) = FormattedWalEntry(
    type = WalEntryType.RETRIED, taskId = entry.taskId,
    headline = "Retried #${entry.attempt}",
    details = if (verbose) listOf("Snapshot: ${entry.snapshotId.take(8)}") else emptyList()
)

private fun formatScopeViolation(entry: WALEntry.ScopeViolation, verbose: Boolean) = FormattedWalEntry(
    type = WalEntryType.SCOPE_VIOLATION, taskId = "SCOPE",
    headline = "Undeclared: ${entry.undeclaredFiles.joinToString(", ")}",
    details = if (verbose) listOf("Suspects: ${entry.suspectTaskIds.joinToString(", ")}") else emptyList()
)

private fun formatVerifyCompleted(entry: WALEntry.VerifyCompleted, verbose: Boolean) = FormattedWalEntry(
    type = WalEntryType.VERIFY, taskId = "VERIFY",
    headline = if (entry.success) "Group ${entry.groupIndex} passed"
        else "Group ${entry.groupIndex} failed (exit ${entry.exitCode})",
    details = if (verbose) listOf("Command: ${entry.command}") else emptyList()
)

// --- Consistency check ---

/**
 * Result of checking whether the current filesystem is consistent with the latest snapshot.
 *
 * - [Consistent] -- the filesystem matches the latest snapshot exactly.
 * - [Diverged] -- the filesystem has changed since the latest snapshot, with the diff details.
 * - [NoSnapshots] -- no snapshots exist to compare against.
 */
sealed class ConsistencyResult {
    /** Filesystem matches the latest snapshot. */
    data class Consistent(val snapshotIdPrefix: String) : ConsistencyResult()

    /** Filesystem has diverged from the latest snapshot. */
    data class Diverged(
        val snapshotIdPrefix: String,
        val diff: SnapshotDiff
    ) : ConsistencyResult()

    /** No snapshots are available for comparison. */
    object NoSnapshots : ConsistencyResult()
}

/**
 * Compares the current filesystem state against the most recent snapshot.
 *
 * @param snapshots list of snapshots ordered most-recent-first
 * @param workDir the working directory to snapshot for comparison
 * @return [ConsistencyResult] indicating whether the filesystem is consistent
 */
fun checkConsistency(snapshots: List<Snapshot>, workDir: Path): ConsistencyResult {
    if (snapshots.isEmpty()) return ConsistencyResult.NoSnapshots

    val latest = snapshots.first()
    val currentSnapshot = SnapshotCreator.create(workDir, "consistency-check")
    val diff = SnapshotCreator.diff(latest, currentSnapshot)

    return if (diff.totalChanges == 0) {
        ConsistencyResult.Consistent(latest.id.take(8))
    } else {
        ConsistencyResult.Diverged(latest.id.take(8), diff)
    }
}

// --- Plan ---

/**
 * A single task in the execution plan, formatted for display.
 *
 * @property index 1-based position in topological execution order
 * @property id the task identifier
 * @property type the task type (e.g. "mutate", "read_only")
 * @property dependencies human-readable dependency description
 * @property files the declared file scopes, or empty string if none
 */
data class PlanTaskEntry(
    val index: Int,
    val id: String,
    val type: String,
    val dependencies: String,
    val files: String
)

/**
 * Complete execution plan summary for a task project.
 *
 * @property projectName the project name from the task YAML
 * @property taskCount total number of tasks
 * @property executionOrder tasks in topological order
 * @property parallelGroups groups of tasks that can execute concurrently (only groups with 2+ tasks)
 * @property warnings scope overlap warnings between independent tasks
 */
data class PlanSummary(
    val projectName: String,
    val taskCount: Int,
    val executionOrder: List<PlanTaskEntry>,
    val parallelGroups: List<List<String>>,
    val warnings: List<PlanWarning>
)

/**
 * Builds a [PlanSummary] from a parsed project and its task graph.
 *
 * @param project the parsed task project
 * @param graph the constructed dependency graph
 * @return a summary containing execution order, parallel groups, and scope overlap warnings
 */
fun buildPlanSummary(project: TaskProject, graph: TaskGraph): PlanSummary {
    val order = graph.topologicalSort()
    val entries = order.mapIndexed { i, taskId ->
        val def = graph[taskId]?.definition ?: return@mapIndexed null
        val deps = if (def.dependsOn.isEmpty()) "no dependencies"
            else "depends on: ${def.dependsOn.joinToString(", ")}"
        val files = if (def.files.isEmpty()) ""
            else " [${def.files.joinToString(", ")}]"
        PlanTaskEntry(
            index = i + 1,
            id = def.id,
            type = def.type.name.lowercase(),
            dependencies = deps,
            files = files
        )
    }.filterNotNull()

    val groups = graph.parallelGroups()

    return PlanSummary(
        projectName = project.project,
        taskCount = project.tasks.size,
        executionOrder = entries,
        parallelGroups = groups.filter { it.size > 1 },
        warnings = detectScopeOverlaps(project.tasks)
    )
}

// --- Run result interpretation ---

/**
 * Maps an orchestrator graph result to a CLI exit code.
 *
 * @param result the completed graph execution result
 * @return [ExitCode.SUCCESS], [ExitCode.CONFLICT], or [ExitCode.TASK_FAILURE]
 */
fun interpretGraphResult(result: Orchestrator.GraphResult): ExitCode = when {
    result.success -> ExitCode.SUCCESS
    result.hasConflicts -> ExitCode.CONFLICT
    else -> ExitCode.TASK_FAILURE
}

/**
 * Formats a graph execution result as a multi-line text summary for terminal output.
 *
 * @param result the completed graph execution result
 * @param elapsedMs total wall-clock time in milliseconds
 * @return lines of text summarizing conflicts, verifications, and task outcomes
 */
fun formatGraphTextSummary(result: Orchestrator.GraphResult, elapsedMs: Long): List<String> =
    buildList {
        add("")
        if (result.hasConflicts) {
            add("Conflicts: ${result.conflicts.size} detected")
        }
        if (result.verifyResults.isNotEmpty()) {
            val passed = result.verifyResults.count { it.success }
            val failed = result.verifyResults.size - passed
            add("Verify: $passed passed, $failed failed")
        }
        add("Results: ${result.completedTasks} completed, ${result.failedTasks} failed, ${result.skippedTasks} skipped")
        add("Logs: .qorche/logs/")
        add("Total time: ${elapsedMs}ms")
    }

// --- Single-task result formatting ---

/**
 * Formats a single-task run result as text lines for terminal output.
 * Shows file changes (added/modified/deleted) and the completion status.
 *
 * @param result the single-task run result
 * @param elapsedMs total wall-clock time in milliseconds
 * @return lines of text describing the result
 */
fun formatSingleTaskText(result: Orchestrator.RunResult, elapsedMs: Long): List<String> = buildList {
    add("")
    val diff = result.diff
    if (diff.totalChanges > 0) {
        add("Changes: ${diff.summary()}")
        for (f in diff.added) add("  + $f")
        for (f in diff.modified) add("  ~ $f")
        for (f in diff.deleted) add("  - $f")
    } else {
        add("No file changes detected")
    }
    add("Completed (exit ${result.agentResult.exitCode}) in ${elapsedMs}ms")
}

/**
 * Wraps a single-task [Orchestrator.RunResult] into a [Orchestrator.GraphResult]
 * so it can be serialized using the same JSON output format as graph runs.
 *
 * @param result the single-task run result
 * @return a [Orchestrator.GraphResult] containing one task outcome
 */
fun buildSingleTaskGraphResult(
    result: Orchestrator.RunResult
): Orchestrator.GraphResult {
    val succeeded = result.agentResult.exitCode == 0
    return Orchestrator.GraphResult(
        project = "cli-run",
        taskResults = mapOf(
            "cli-run" to Orchestrator.TaskOutcome(
                taskId = "cli-run",
                status = if (succeeded) TaskStatus.COMPLETED else TaskStatus.FAILED,
                runResult = result
            )
        ),
        totalTasks = 1,
        completedTasks = if (succeeded) 1 else 0,
        failedTasks = if (succeeded) 0 else 1,
        skippedTasks = 0
    )
}

// --- Graph run preparation ---

/**
 * Result of preparing a graph run: parsing the task file, building runners, and resolving defaults.
 *
 * - [Ready] contains all resources needed to execute the graph.
 * - [Failed] contains the error message and appropriate exit code.
 */
sealed class GraphRunSetup {
    /** All resources are prepared and the graph is ready to execute. */
    data class Ready(
        val project: TaskProject,
        val graph: TaskGraph,
        val runners: Map<String, AgentRunner>,
        val defaultRunner: AgentRunner
    ) : GraphRunSetup()

    /** Preparation failed due to parse errors or invalid runner configuration. */
    data class Failed(
        val message: String,
        val exitCode: ExitCode
    ) : GraphRunSetup()
}

/**
 * Parses a task YAML file, builds the runner registry, and resolves the default runner.
 *
 * @param filePath path to the YAML task file
 * @param buildRunners factory that creates [AgentRunner] instances from runner configs
 * @param fallbackRunner factory for the default runner when none is specified in the project
 * @return [GraphRunSetup.Ready] if all preparation succeeds, or [GraphRunSetup.Failed] with error details
 */
fun prepareGraphRun(
    filePath: Path,
    buildRunners: (Map<String, RunnerConfig>) -> Map<String, AgentRunner>,
    fallbackRunner: () -> AgentRunner
): GraphRunSetup {
    val (project, graph) = when (val loaded = loadTaskGraph(filePath)) {
        is TaskGraphLoadResult.Success -> loaded.project to loaded.graph
        is TaskGraphLoadResult.ParseError -> return GraphRunSetup.Failed(loaded.message, ExitCode.CONFIG_ERROR)
    }

    val runners = try {
        buildRunners(project.runners)
    } catch (e: IllegalArgumentException) {
        return GraphRunSetup.Failed(e.message ?: "Invalid runner configuration", ExitCode.CONFIG_ERROR)
    }

    val defaultRunner = if (project.defaultRunner != null) {
        runners[project.defaultRunner]
            ?: return GraphRunSetup.Failed(
                "default_runner '${project.defaultRunner}' not found in runners",
                ExitCode.CONFIG_ERROR
            )
    } else {
        fallbackRunner()
    }

    return GraphRunSetup.Ready(project, graph, runners, defaultRunner)
}

// --- History formatting ---

/**
 * A single line in the snapshot history display.
 *
 * @property idPrefix the first 8 characters of the snapshot ID
 * @property timestamp the snapshot creation timestamp as a string
 * @property description the snapshot description (typically the task or project name)
 * @property fileCount number of files tracked in the snapshot
 */
data class HistoryLine(
    val idPrefix: String,
    val timestamp: String,
    val description: String,
    val fileCount: Int
)

/**
 * Formatted snapshot history with optional truncation.
 *
 * @property lines the visible history entries
 * @property truncatedCount number of entries omitted due to the limit (0 if all shown)
 */
data class HistoryOutput(
    val lines: List<HistoryLine>,
    val truncatedCount: Int
)

/**
 * Formats a list of snapshots into a [HistoryOutput] for display, optionally limiting the count.
 *
 * @param snapshots all available snapshots, ordered most-recent-first
 * @param limit maximum number of entries to show, or null for all
 * @return formatted history with truncation information
 */
fun formatHistory(snapshots: List<Snapshot>, limit: Int?): HistoryOutput {
    val shown = if (limit != null) snapshots.take(limit) else snapshots
    val lines = shown.map { snap ->
        HistoryLine(
            idPrefix = snap.id.take(8),
            timestamp = snap.timestamp.toString(),
            description = snap.description,
            fileCount = snap.fileHashes.size
        )
    }
    val truncated = if (limit != null && snapshots.size > limit) snapshots.size - limit else 0
    return HistoryOutput(lines, truncated)
}

// --- Diff resolution ---

/**
 * Result of resolving snapshot ID prefixes to full snapshot IDs for diffing.
 *
 * - [Resolved] contains the two full snapshot IDs to compare.
 * - [NoComparison] indicates that one or both IDs could not be resolved.
 */
sealed class DiffResolution {
    /** Both snapshot IDs were resolved to their full values. */
    data class Resolved(val fullId1: String, val fullId2: String) : DiffResolution()

    /** One or both snapshot IDs could not be found. */
    data class NoComparison(val message: String) : DiffResolution()
}

/**
 * Resolves snapshot ID prefixes to full IDs for snapshot comparison.
 * If [id2] is null, attempts to use the parent of the snapshot matching [id1].
 *
 * @param id1 first snapshot ID or prefix
 * @param id2 second snapshot ID or prefix, or null to auto-resolve from parent
 * @param snapshots all available snapshots
 * @return [DiffResolution.Resolved] with full IDs, or [DiffResolution.NoComparison] if resolution fails
 */
fun resolveSnapshotIds(
    id1: String,
    id2: String?,
    snapshots: List<Snapshot>
): DiffResolution {
    val resolvedId2 = id2 ?: run {
        val snap = snapshots.find { it.id.startsWith(id1) }
        snap?.parentId ?: return DiffResolution.NoComparison(
            "Cannot determine comparison snapshot. Provide two IDs."
        )
    }

    val fullId1 = snapshots.find { it.id.startsWith(id1) }?.id
        ?: return DiffResolution.NoComparison("Snapshot with prefix '$id1' not found.")
    val fullId2 = snapshots.find { it.id.startsWith(resolvedId2) }?.id
        ?: return DiffResolution.NoComparison("Snapshot with prefix '$resolvedId2' not found.")

    return DiffResolution.Resolved(fullId1, fullId2)
}
