package io.qorche.core

import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime

@Serializable
data class FileIndexEntry(
    val relativePath: String,
    val size: Long,
    val lastModifiedEpochMs: Long,
    val hash: String
)

class FileIndex {

    private val entries = mutableMapOf<String, FileIndexEntry>()

    fun getOrComputeHash(file: Path, relativePath: String): String {
        val size = file.fileSize()
        val mtime = file.getLastModifiedTime().toMillis()

        val cached = entries[relativePath]
        if (cached != null && cached.size == size && cached.lastModifiedEpochMs == mtime) {
            return cached.hash
        }

        val hash = hashFile(file)
        entries[relativePath] = FileIndexEntry(relativePath, size, mtime, hash)
        return hash
    }

    fun allEntries(): Collection<FileIndexEntry> = entries.values

    fun loadFrom(saved: List<FileIndexEntry>) {
        entries.clear()
        for (entry in saved) {
            entries[entry.relativePath] = entry
        }
    }

    fun exportEntries(): List<FileIndexEntry> = entries.values.toList()
}
