package io.qorche.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/**
 * Persists snapshots to .qorche/snapshots/ as individual JSON files.
 * Each snapshot is stored as {id}.json.
 */
class SnapshotStore(private val snapshotsDir: Path) {

    private val json = Json { prettyPrint = true }

    init {
        snapshotsDir.createDirectories()
    }

    /** Persists a snapshot to disk as a JSON file named by its ID. */
    fun save(snapshot: Snapshot) {
        val file = snapshotsDir.resolve("${snapshot.id}.json")
        Files.writeString(file, json.encodeToString(snapshot))
    }

    /** Loads a snapshot by its ID, returning null if the file does not exist or is corrupt. */
    fun load(id: String): Snapshot? {
        val file = snapshotsDir.resolve("$id.json")
        if (!file.exists()) return null
        return try {
            json.decodeFromString<Snapshot>(file.readText())
        } catch (_: Exception) {
            null
        }
    }

    /** Returns all stored snapshots, ordered by timestamp descending (most recent first). */
    fun list(): List<Snapshot> =
        if (!snapshotsDir.exists()) emptyList()
        else snapshotsDir.listDirectoryEntries("*.json")
            .mapNotNull { file ->
                try {
                    json.decodeFromString<Snapshot>(file.readText())
                } catch (_: Exception) {
                    null
                }
            }
            .sortedByDescending { it.timestamp }

    /** Returns the most recent snapshot by timestamp, or null if the store is empty. */
    fun latest(): Snapshot? = list().firstOrNull()
}
