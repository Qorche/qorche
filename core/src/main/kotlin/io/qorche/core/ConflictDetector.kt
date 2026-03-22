package io.qorche.core

/**
 * Detects write-write conflicts between two concurrent agent runs.
 *
 * Given a base snapshot and two modified snapshots (from two agents),
 * identifies files that were modified by both agents since the base.
 */
object ConflictDetector {

    data class ConflictReport(
        val conflicts: Set<String>,
        val agentAOnly: Set<String>,
        val agentBOnly: Set<String>
    ) {
        val hasConflicts: Boolean get() = conflicts.isNotEmpty()
    }

    fun detectConflicts(
        base: Snapshot,
        agentA: Snapshot,
        agentB: Snapshot
    ): ConflictReport {
        val diffA = SnapshotCreator.diff(base, agentA)
        val diffB = SnapshotCreator.diff(base, agentB)

        val changedByA = diffA.added + diffA.modified + diffA.deleted
        val changedByB = diffB.added + diffB.modified + diffB.deleted

        val conflicts = changedByA.intersect(changedByB)

        return ConflictReport(
            conflicts = conflicts,
            agentAOnly = changedByA - conflicts,
            agentBOnly = changedByB - conflicts
        )
    }
}
