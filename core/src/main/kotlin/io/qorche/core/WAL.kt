package io.qorche.core

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * Sealed hierarchy of write-ahead log entries.
 *
 * Every state-changing action is logged as a WAL entry before the change is applied.
 * Entries are serialized as JSON Lines (one JSON object per line, append-only) to
 * `.qorche/wal.jsonl`. This enables replay, audit, and post-mortem debugging without
 * relying on agent self-reports.
 */
@Serializable
sealed class WALEntry {
    abstract val timestamp: Instant
    abstract val taskId: String

    @Serializable
    @SerialName("task_started")
    data class TaskStarted(
        override val timestamp: Instant = Clock.System.now(),
        override val taskId: String,
        val instruction: String,
        val snapshotId: String
    ) : WALEntry()

    @Serializable
    @SerialName("task_completed")
    data class TaskCompleted(
        override val timestamp: Instant = Clock.System.now(),
        override val taskId: String,
        val snapshotId: String,
        val exitCode: Int,
        val filesModified: List<String> = emptyList()
    ) : WALEntry()

    @Serializable
    @SerialName("task_failed")
    data class TaskFailed(
        override val timestamp: Instant = Clock.System.now(),
        override val taskId: String,
        val error: String
    ) : WALEntry()

    @Serializable
    @SerialName("scope_violation")
    data class ScopeViolation(
        override val timestamp: Instant = Clock.System.now(),
        override val taskId: String,
        val undeclaredFiles: List<String>,
        val suspectTaskIds: List<String>
    ) : WALEntry()

    @Serializable
    @SerialName("task_retry_scheduled")
    data class TaskRetryScheduled(
        override val timestamp: Instant = Clock.System.now(),
        override val taskId: String,
        val attempt: Int,
        val conflictWith: String,
        val conflictingFiles: List<String>
    ) : WALEntry()

    @Serializable
    @SerialName("task_retried")
    data class TaskRetried(
        override val timestamp: Instant = Clock.System.now(),
        override val taskId: String,
        val attempt: Int,
        val snapshotId: String
    ) : WALEntry()

    @Serializable
    @SerialName("conflict_detected")
    data class ConflictDetected(
        override val timestamp: Instant = Clock.System.now(),
        override val taskId: String,
        val conflictingTaskId: String,
        val conflictingFiles: List<String>,
        val baseSnapshotId: String
    ) : WALEntry()
}

/**
 * Append-only writer for the write-ahead log.
 *
 * Each [WALEntry] is serialized as a single JSON line and appended to the WAL file.
 * Thread-safe for single-writer use; concurrent parallel tasks use a mutex externally.
 */
class WALWriter(private val walFile: Path) {

    private val json = Json { prettyPrint = false }

    /** Serialize and append a single entry to the WAL file. */
    fun append(entry: WALEntry) {
        walFile.createParentDirectories()
        val line = json.encodeToString(entry) + "\n"
        Files.writeString(
            walFile,
            line,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        )
    }

    /** Read and deserialize all entries from the WAL file. */
    fun readAll(): List<WALEntry> {
        if (!walFile.exists()) return emptyList()
        return walFile.readLines()
            .filter { it.isNotBlank() }
            .map { json.decodeFromString<WALEntry>(it) }
    }
}
