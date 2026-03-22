package io.qorche.core

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo

@Serializable
data class Snapshot(
    val id: String,
    val timestamp: Instant,
    val fileHashes: Map<String, String>,
    val description: String,
    val parentId: String? = null
)

@Serializable
data class SnapshotDiff(
    val added: Set<String>,
    val modified: Set<String>,
    val deleted: Set<String>,
    val beforeId: String,
    val afterId: String
)

object SnapshotCreator {

    fun create(
        directory: Path,
        description: String,
        parentId: String? = null,
        fileIndex: FileIndex? = null
    ): Snapshot {
        val hashes = mutableMapOf<String, String>()

        Files.walk(directory).use { stream ->
            stream
                .filter { it.isRegularFile() }
                .filter { !isIgnored(directory.relativize(it).toString().replace("\\", "/")) }
                .forEach { file ->
                    val relativePath = file.relativeTo(directory).toString().replace("\\", "/")
                    val hash = fileIndex?.getOrComputeHash(file, relativePath)
                        ?: hashFile(file)
                    hashes[relativePath] = hash
                }
        }

        return Snapshot(
            id = generateId(),
            timestamp = Clock.System.now(),
            fileHashes = hashes,
            description = description,
            parentId = parentId
        )
    }

    fun diff(before: Snapshot, after: Snapshot): SnapshotDiff {
        val added = after.fileHashes.keys - before.fileHashes.keys
        val deleted = before.fileHashes.keys - after.fileHashes.keys
        val common = before.fileHashes.keys.intersect(after.fileHashes.keys)
        val modified = common.filter { before.fileHashes[it] != after.fileHashes[it] }.toSet()

        return SnapshotDiff(
            added = added,
            modified = modified,
            deleted = deleted,
            beforeId = before.id,
            afterId = after.id
        )
    }

    private fun isIgnored(relativePath: String): Boolean {
        val ignoredPrefixes = listOf(".git/", ".gradle/", ".idea/", ".qorche/", "build/")
        return ignoredPrefixes.any { relativePath.startsWith(it) }
    }
}

fun hashFile(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8192)
    Files.newInputStream(file).use { input ->
        var previousByte: Byte = 0
        while (true) {
            val bytesRead = input.read(buffer)
            if (bytesRead == -1) break
            // Normalise line endings: replace \r\n with \n, skip lone \r
            for (i in 0 until bytesRead) {
                val b = buffer[i]
                if (b == '\r'.code.toByte()) continue
                digest.update(b)
                previousByte = b
            }
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun generateId(): String =
    java.util.UUID.randomUUID().toString()
