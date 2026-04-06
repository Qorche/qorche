package io.qorche.cli

import io.qorche.core.ConflictDetector
import io.qorche.core.Orchestrator
import io.qorche.core.TaskGraph
import io.qorche.core.TaskDefinition
import io.qorche.core.VerifyResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { prettyPrint = true }

/** Top-level JSON output structure for a `qorche run` execution with `--output json`. */
@Serializable
data class RunOutput(
    val version: String,
    val project: String,
    val success: Boolean,
    val wallTimeMs: Long,
    val tasks: List<TaskOutput>,
    val conflicts: List<ConflictOutput>,
    val scopeViolations: List<ScopeViolationOutput>,
    val verifyResults: List<VerifyResultOutput> = emptyList(),
    val retriedTasks: Int,
    val groups: List<GroupOutput>
)

/** JSON representation of a single task's outcome within [RunOutput]. */
@Serializable
data class TaskOutput(
    val id: String,
    val status: String,
    val retryCount: Int = 0,
    val filesChanged: List<String> = emptyList(),
    val skipReason: String? = null,
    @SerialName("elapsed_ms")
    val elapsedMs: Long = 0
)

/** JSON representation of a detected conflict between two tasks that modified the same files. */
@Serializable
data class ConflictOutput(
    val taskA: String,
    val taskB: String,
    val files: List<String>
)

/** JSON representation of a scope violation where tasks modified files outside their declared scope. */
@Serializable
data class ScopeViolationOutput(
    val undeclaredFiles: List<String>,
    val suspectTaskIds: List<String>
)

/** JSON representation of a verification command result for a parallel execution group. */
@Serializable
data class VerifyResultOutput(
    val success: Boolean,
    @SerialName("exit_code")
    val exitCode: Int,
    @SerialName("elapsed_ms")
    val elapsedMs: Long,
    @SerialName("group_index")
    val groupIndex: Int
)

/** JSON representation of a parallel execution group containing one or more task IDs. */
@Serializable
data class GroupOutput(
    val index: Int,
    val taskIds: List<String>,
    val parallel: Boolean
)

/** Top-level JSON output structure for `qorche plan --output json`. */
@Serializable
data class PlanOutput(
    val version: String,
    val project: String,
    val tasks: Int,
    val groups: List<GroupOutput>,
    val warnings: List<PlanWarning>
)

/** JSON representation of a scope overlap warning between two independent tasks. */
@Serializable
data class PlanWarning(
    val type: String,
    val taskA: String,
    val taskB: String,
    val overlappingFiles: List<String>,
    val message: String
)

/**
 * Serializes a [Orchestrator.GraphResult] to a JSON string for `--output json` mode.
 *
 * @param project the project name
 * @param version the CLI version string
 * @param wallTimeMs total wall-clock time in milliseconds
 * @return pretty-printed JSON string
 */
fun Orchestrator.GraphResult.toJson(project: String, version: String, wallTimeMs: Long): String {
    val output = RunOutput(
        version = version,
        project = project,
        success = success,
        wallTimeMs = wallTimeMs,
        tasks = taskResults.values.map { outcome ->
            val changed = outcome.runResult?.diff?.let { diff ->
                (diff.added + diff.modified).sorted()
            } ?: emptyList()
            TaskOutput(
                id = outcome.taskId,
                status = outcome.status.name,
                retryCount = outcome.retryCount,
                filesChanged = changed,
                skipReason = outcome.skipReason,
                elapsedMs = outcome.elapsedMs
            )
        },
        conflicts = conflicts.map { conflict ->
            ConflictOutput(
                taskA = conflict.taskA,
                taskB = conflict.taskB,
                files = conflict.conflictingFiles.sorted()
            )
        },
        scopeViolations = scopeViolations.map { violation ->
            ScopeViolationOutput(
                undeclaredFiles = violation.undeclaredFiles.sorted(),
                suspectTaskIds = violation.suspectTaskIds
            )
        },
        verifyResults = verifyResults.map { v ->
            VerifyResultOutput(
                success = v.success,
                exitCode = v.exitCode,
                elapsedMs = v.elapsedMs,
                groupIndex = v.groupIndex
            )
        },
        retriedTasks = retriedTasks,
        groups = emptyList()
    )
    return json.encodeToString(output)
}

/**
 * Builds the JSON output string for the `plan` command.
 *
 * @param project the project name
 * @param version the CLI version string
 * @param graph the task dependency graph, used to compute parallel groups
 * @param definitions the task definitions, used to detect scope overlaps
 * @return pretty-printed JSON string
 */
fun buildPlanJson(
    project: String,
    version: String,
    graph: TaskGraph,
    definitions: List<TaskDefinition>
): String {
    val groups = graph.parallelGroups()
    val warnings = detectScopeOverlaps(definitions)

    val output = PlanOutput(
        version = version,
        project = project,
        tasks = definitions.size,
        groups = groups.mapIndexed { index, taskIds ->
            GroupOutput(
                index = index,
                taskIds = taskIds,
                parallel = taskIds.size > 1
            )
        },
        warnings = warnings
    )
    return json.encodeToString(output)
}

/**
 * Detects file scope overlaps between pairs of independent (non-dependent) tasks.
 * Two tasks with overlapping file scopes and no dependency between them may conflict at runtime.
 *
 * @param definitions the task definitions to check
 * @return a list of [PlanWarning] for each pair with overlapping scopes
 */
fun detectScopeOverlaps(definitions: List<TaskDefinition>): List<PlanWarning> {
    val warnings = mutableListOf<PlanWarning>()
    for (i in definitions.indices) {
        for (j in i + 1 until definitions.size) {
            val a = definitions[i]
            val b = definitions[j]
            if (a.files.isEmpty() || b.files.isEmpty()) continue
            if (a.dependsOn.contains(b.id) || b.dependsOn.contains(a.id)) continue

            val overlap = a.files.intersect(b.files.toSet())
            if (overlap.isNotEmpty()) {
                warnings.add(PlanWarning(
                    type = "scope_overlap",
                    taskA = a.id,
                    taskB = b.id,
                    overlappingFiles = overlap.sorted(),
                    message = "These tasks may conflict — consider splitting file scopes"
                ))
            }
        }
    }
    return warnings
}
