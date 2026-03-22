package io.qorche.agent

import io.qorche.core.AgentEvent
import io.qorche.core.ConflictDetector
import io.qorche.core.FileIndex
import io.qorche.core.SnapshotCreator
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Benchmarks measuring Qorche's real overhead and the net benefit of
 * MVCC-based parallel execution.
 *
 * Methodology:
 *
 * 1. MVCC OVERHEAD — How much does Qorche's snapshot/conflict detection cost?
 *    Measures snapshot (cold & warm) and conflict detection independently,
 *    expressed as absolute time and as % of a realistic step duration.
 *
 * 2. END-TO-END COMPARISON — Is parallel + MVCC overhead still faster than sequential raw?
 *    Compares:
 *      - Sequential raw:       steps run one after another, no snapshots
 *      - Parallel + MVCC:      steps run concurrently, with before/after snapshots + conflict detection
 *      - Net speedup:          (sequential raw) / (parallel + all MVCC overhead)
 *    This answers: "Even after paying for snapshots, do I come out ahead?"
 *
 * 3. SCALING — How do these numbers change with more files and more steps?
 */
class BenchmarkTest {

    companion object {
        private val FILE_COUNTS = listOf(100, 1_000, 5_000, 10_000, 20_000)
        private val STEP_COUNTS = listOf(3, 5, 8, 12)
        private val LARGE_FILE_COUNTS = listOf(50_000, 100_000)

        // Simulated step durations — representative of real CI steps
        private const val STEP_DELAY_MS = 250L
    }

    private fun createTestRepo(root: Path, fileCount: Int) {
        val dirs = listOf("src", "test", "docs", "dist", "coverage", "config", "scripts", "assets")
        for (dir in dirs) {
            root.resolve(dir).createDirectories()
        }
        for (i in 1..fileCount) {
            val dir = dirs[i % dirs.size]
            root.resolve("$dir/file_$i.txt").writeText("content of file $i\n".repeat(10))
        }
    }

    private fun createPipelineRunners(stepCount: Int, delayMs: Long = STEP_DELAY_MS): List<Pair<String, MockAgentRunner>> {
        val steps = listOf(
            "lint" to listOf("src/lint-report.txt"),
            "test" to listOf("coverage/report.txt"),
            "build" to listOf("dist/bundle.txt"),
            "docs" to listOf("docs/generated.txt"),
            "security" to listOf("config/security-report.txt"),
            "format" to listOf("src/format-report.txt"),
            "typecheck" to listOf("dist/typecheck.txt"),
            "license" to listOf("docs/license-check.txt"),
            "bundle-analysis" to listOf("dist/bundle-stats.txt"),
            "e2e-prep" to listOf("test/e2e-setup.txt"),
            "asset-hash" to listOf("assets/manifest.txt"),
            "script-validate" to listOf("scripts/validate-report.txt"),
        )
        return steps.take(stepCount).map { (name, files) ->
            name to MockAgentRunner(filesToTouch = files, delayMs = delayMs)
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  1. MVCC OVERHEAD — What does Qorche's safety cost?
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `mvcc overhead across file counts`() = runBlocking {
        println()
        println("═══════════════════════════════════════════════════════════════════════════════")
        println("  MVCC OVERHEAD — cost of snapshots & conflict detection (step = ${STEP_DELAY_MS}ms)")
        println("═══════════════════════════════════════════════════════════════════════════════")
        println("  %-12s │ %10s │ %10s │ %10s │ %12s │ %12s".format(
            "Files", "Cold Snap", "Warm Snap", "Conflict", "Overhead/step", "% of step"))
        println("  ─────────────┼────────────┼────────────┼────────────┼──────────────┼──────────────")

        for (fileCount in FILE_COUNTS) {
            val root = Files.createTempDirectory("qorche-overhead-$fileCount")
            try {
                createTestRepo(root, fileCount)
                val fileIndex = FileIndex()

                // Cold snapshot
                val coldStart = System.currentTimeMillis()
                val snap1 = SnapshotCreator.create(root, "cold", fileIndex = fileIndex)
                val coldMs = System.currentTimeMillis() - coldStart

                // Warm snapshot (what you'd pay on subsequent runs)
                val warmStart = System.currentTimeMillis()
                val snap2 = SnapshotCreator.create(root, "warm", fileIndex = fileIndex)
                val warmMs = System.currentTimeMillis() - warmStart

                // Conflict detection (comparing two snapshots)
                val conflictStart = System.currentTimeMillis()
                // Run 100 times to get measurable number, then average
                repeat(100) { SnapshotCreator.diff(snap1, snap2) }
                val conflictMs = (System.currentTimeMillis() - conflictStart) / 100.0

                // Per-step overhead = 2 warm snapshots (before + after) + 1 conflict check
                val overheadPerStep = warmMs * 2 + conflictMs
                val overheadPct = overheadPerStep / STEP_DELAY_MS * 100

                println("  %-12s │ %8dms │ %8dms │ %7.1fms │ %9.0fms │ %9.1f%%".format(
                    "%,d".format(fileCount), coldMs, warmMs, conflictMs, overheadPerStep, overheadPct
                ))
            } finally {
                root.toFile().deleteRecursively()
            }
        }
        println()
        println("  Overhead/step = 2× warm snapshot (before + after) + conflict detection")
        println("  % of step     = overhead relative to a ${STEP_DELAY_MS}ms step (lower = better)")
        println()
    }

    // ─────────────────────────────────────────────────────────────
    //  2. END-TO-END — Sequential raw vs Parallel + MVCC
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `end-to-end sequential raw vs parallel with mvcc`() = runBlocking {
        println()
        println("═══════════════════════════════════════════════════════════════════════════════════════════")
        println("  END-TO-END — 5 steps × ${STEP_DELAY_MS}ms each: sequential (no snapshots) vs parallel (with MVCC)")
        println("═══════════════════════════════════════════════════════════════════════════════════════════")
        println("  %-12s │ %10s │ %10s │ %12s │ %10s │ %8s".format(
            "Files", "Seq (raw)", "Par+MVCC", "MVCC portion", "Time saved", "Speedup"))
        println("  ─────────────┼────────────┼────────────┼──────────────┼────────────┼──────────")

        for (fileCount in FILE_COUNTS) {
            val root = Files.createTempDirectory("qorche-e2e-$fileCount")
            try {
                createTestRepo(root, fileCount)

                // --- Sequential raw: no snapshots, just run steps one by one ---
                val seqPipeline = createPipelineRunners(5)
                val seqStart = System.currentTimeMillis()
                for ((name, runner) in seqPipeline) {
                    runner.run(name, root) {}.collect()
                }
                val seqRawMs = System.currentTimeMillis() - seqStart

                // --- Parallel + full MVCC: snapshots before, run all, snapshot after, detect conflicts ---
                val parPipeline = createPipelineRunners(5)
                val fileIndex = FileIndex()

                val parTotalStart = System.currentTimeMillis()

                // Before snapshot (warm — assume FileIndex populated from prior run)
                SnapshotCreator.create(root, "pre-warmup", fileIndex = fileIndex)
                val beforeSnap = SnapshotCreator.create(root, "before", fileIndex = fileIndex)

                // Run all steps in parallel
                val mvccStart = System.currentTimeMillis()
                val stepResults = parPipeline.map { (name, runner) ->
                    async {
                        val files = mutableListOf<String>()
                        runner.run(name, root) {}.onEach { event ->
                            if (event is AgentEvent.FileModified) files.add(event.path)
                        }.collect()
                        name to files
                    }
                }.awaitAll()

                // After snapshot
                val afterSnap = SnapshotCreator.create(root, "after", fileIndex = fileIndex)

                // Conflict detection
                SnapshotCreator.diff(beforeSnap, afterSnap)

                val parTotalMs = System.currentTimeMillis() - parTotalStart
                val mvccOverhead = parTotalMs - (System.currentTimeMillis() - mvccStart - (System.currentTimeMillis() - parTotalStart - parTotalMs).coerceAtLeast(0))

                // Simpler: just measure the MVCC portion
                val parStepsOnlyMs = System.currentTimeMillis() - mvccStart
                val mvccPortionMs = parTotalMs - STEP_DELAY_MS.coerceAtMost(parTotalMs) // approximate

                val timeSaved = seqRawMs - parTotalMs
                val speedup = if (parTotalMs > 0) "%.1fx".format(seqRawMs.toDouble() / parTotalMs) else "∞"
                val mvccPortion = parTotalMs - STEP_DELAY_MS // rough: total - max(step delay)

                println("  %-12s │ %8dms │ %8dms │ %8dms │ %7dms │ %8s".format(
                    "%,d".format(fileCount), seqRawMs, parTotalMs,
                    mvccPortion.coerceAtLeast(0), timeSaved, speedup
                ))
            } finally {
                root.toFile().deleteRecursively()
            }
        }
        println()
        println("  Seq (raw)     = steps run sequentially, zero overhead (baseline)")
        println("  Par+MVCC      = parallel steps + before/after snapshots + conflict detection")
        println("  MVCC portion  = approximate snapshot & conflict overhead within Par+MVCC")
        println("  Time saved    = Seq (raw) - Par+MVCC (positive = faster)")
        println()
    }

    // ─────────────────────────────────────────────────────────────
    //  3. SCALING — More steps = more benefit
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `scaling with step count`() = runBlocking {
        val fileCount = 5_000
        println()
        println("═══════════════════════════════════════════════════════════════════════════════════════════")
        println("  SCALING — %,d files, ${STEP_DELAY_MS}ms/step, varying step count".format(fileCount))
        println("═══════════════════════════════════════════════════════════════════════════════════════════")
        println("  %-8s │ %10s │ %10s │ %10s │ %8s │ %14s".format(
            "Steps", "Seq (raw)", "Par+MVCC", "Time saved", "Speedup", "MVCC amortised"))
        println("  ─────────┼────────────┼────────────┼────────────┼──────────┼────────────────")

        for (stepCount in STEP_COUNTS) {
            val root = Files.createTempDirectory("qorche-scale-$stepCount")
            try {
                createTestRepo(root, fileCount)

                // Sequential raw
                val seqPipeline = createPipelineRunners(stepCount)
                val seqStart = System.currentTimeMillis()
                for ((name, runner) in seqPipeline) {
                    runner.run(name, root) {}.collect()
                }
                val seqRawMs = System.currentTimeMillis() - seqStart

                // Parallel + MVCC
                val parPipeline = createPipelineRunners(stepCount)
                val fileIndex = FileIndex()
                val parStart = System.currentTimeMillis()

                SnapshotCreator.create(root, "warmup", fileIndex = fileIndex)
                val beforeSnap = SnapshotCreator.create(root, "before", fileIndex = fileIndex)

                parPipeline.map { (name, runner) ->
                    async { runner.run(name, root) {}.collect() }
                }.awaitAll()

                val afterSnap = SnapshotCreator.create(root, "after", fileIndex = fileIndex)
                SnapshotCreator.diff(beforeSnap, afterSnap)

                val parTotalMs = System.currentTimeMillis() - parStart

                val timeSaved = seqRawMs - parTotalMs
                val speedup = if (parTotalMs > 0) "%.1fx".format(seqRawMs.toDouble() / parTotalMs) else "∞"
                // MVCC cost amortised per step
                val mvccTotal = (parTotalMs - STEP_DELAY_MS).coerceAtLeast(0)
                val mvccPerStep = if (stepCount > 0) mvccTotal / stepCount else 0

                println("  %-8d │ %8dms │ %8dms │ %7dms │ %8s │ %10dms/step".format(
                    stepCount, seqRawMs, parTotalMs, timeSaved, speedup, mvccPerStep
                ))
            } finally {
                root.toFile().deleteRecursively()
            }
        }
        println()
        println("  MVCC amortised = (Par+MVCC - max step delay) / step count")
        println("  As steps increase, MVCC cost per step drops — overhead is mostly fixed")
        println()
    }

    // ─────────────────────────────────────────────────────────────
    //  4. CONFLICT DETECTION — Correctness
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `conflict detection catches overlapping writes`() = runBlocking {
        val root = Files.createTempDirectory("qorche-bench-conflict")
        try {
            createTestRepo(root, 50)
            val fileIndex = FileIndex()

            val base = SnapshotCreator.create(root, "base", fileIndex = fileIndex)

            val agentA = MockAgentRunner(filesToTouch = listOf("src/file_1.txt"), delayMs = 50)
            agentA.run("agent A", root) {}.collect()
            val snapshotA = SnapshotCreator.create(root, "after agent A", fileIndex = fileIndex)

            root.resolve("src/file_1.txt").writeText("content of file 1\n".repeat(10))

            val agentB = MockAgentRunner(filesToTouch = listOf("src/file_1.txt", "test/file_2.txt"), delayMs = 50)
            agentB.run("agent B", root) {}.collect()
            val snapshotB = SnapshotCreator.create(root, "after agent B", fileIndex = fileIndex)

            val report = ConflictDetector.detectConflicts(base, snapshotA, snapshotB)

            println()
            println("═══════════════════════════════════════════════════════════════")
            println("  CONFLICT DETECTION — correctness verification")
            println("═══════════════════════════════════════════════════════════════")
            println("  Agent A modified:  [src/file_1.txt]")
            println("  Agent B modified:  [src/file_1.txt, test/file_2.txt]")
            println("  ───────────────────────────────────────────")
            println("  Conflicts found:   ${report.conflicts}")
            println("  Agent A only:      ${report.agentAOnly}")
            println("  Agent B only:      ${report.agentBOnly}")
            println("  Correct:           ${report.hasConflicts && "src/file_1.txt" in report.conflicts && "test/file_2.txt" !in report.conflicts}")
            println()

            assertTrue(report.hasConflicts, "Should detect conflict on src/file_1.txt")
            assertTrue("src/file_1.txt" in report.conflicts)
            assertFalse("test/file_2.txt" in report.conflicts)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  5. LARGE-SCALE (opt-in) — 50k and 100k files
    // ─────────────────────────────────────────────────────────────

    @Test
    @Tag("large-scale")
    fun `large-scale mvcc overhead`() = runBlocking {
        println()
        println("═══════════════════════════════════════════════════════════════════════════════")
        println("  LARGE-SCALE: MVCC OVERHEAD (step = ${STEP_DELAY_MS}ms)")
        println("═══════════════════════════════════════════════════════════════════════════════")
        println("  %-12s │ %10s │ %10s │ %10s │ %12s │ %12s │ %10s".format(
            "Files", "Cold Snap", "Warm Snap", "Conflict", "Overhead/step", "% of step", "Setup"))
        println("  ─────────────┼────────────┼────────────┼────────────┼──────────────┼──────────────┼────────────")

        for (fileCount in LARGE_FILE_COUNTS) {
            val root = Files.createTempDirectory("qorche-large-overhead-$fileCount")
            try {
                val setupStart = System.currentTimeMillis()
                createTestRepo(root, fileCount)
                val setupMs = System.currentTimeMillis() - setupStart

                val fileIndex = FileIndex()

                val coldStart = System.currentTimeMillis()
                val snap1 = SnapshotCreator.create(root, "cold", fileIndex = fileIndex)
                val coldMs = System.currentTimeMillis() - coldStart

                val warmStart = System.currentTimeMillis()
                val snap2 = SnapshotCreator.create(root, "warm", fileIndex = fileIndex)
                val warmMs = System.currentTimeMillis() - warmStart

                val conflictStart = System.currentTimeMillis()
                repeat(100) { SnapshotCreator.diff(snap1, snap2) }
                val conflictMs = (System.currentTimeMillis() - conflictStart) / 100.0

                val overheadPerStep = warmMs * 2 + conflictMs
                val overheadPct = overheadPerStep / STEP_DELAY_MS * 100

                println("  %-12s │ %8dms │ %8dms │ %7.1fms │ %9.0fms │ %9.1f%% │ %8dms".format(
                    "%,d".format(fileCount), coldMs, warmMs, conflictMs, overheadPerStep, overheadPct, setupMs
                ))
            } finally {
                root.toFile().deleteRecursively()
            }
        }
        println()
    }

    @Test
    @Tag("large-scale")
    fun `large-scale end-to-end`() = runBlocking {
        println()
        println("═══════════════════════════════════════════════════════════════════════════════════════════")
        println("  LARGE-SCALE: END-TO-END — 5 steps × ${STEP_DELAY_MS}ms")
        println("═══════════════════════════════════════════════════════════════════════════════════════════")
        println("  %-12s │ %10s │ %10s │ %10s │ %8s │ %10s".format(
            "Files", "Seq (raw)", "Par+MVCC", "Time saved", "Speedup", "Setup"))
        println("  ─────────────┼────────────┼────────────┼────────────┼──────────┼────────────")

        for (fileCount in LARGE_FILE_COUNTS) {
            val root = Files.createTempDirectory("qorche-large-e2e-$fileCount")
            try {
                val setupStart = System.currentTimeMillis()
                createTestRepo(root, fileCount)
                val setupMs = System.currentTimeMillis() - setupStart

                // Sequential raw
                val seqPipeline = createPipelineRunners(5)
                val seqStart = System.currentTimeMillis()
                for ((name, runner) in seqPipeline) {
                    runner.run(name, root) {}.collect()
                }
                val seqRawMs = System.currentTimeMillis() - seqStart

                // Parallel + MVCC
                val parPipeline = createPipelineRunners(5)
                val fileIndex = FileIndex()
                val parStart = System.currentTimeMillis()

                SnapshotCreator.create(root, "warmup", fileIndex = fileIndex)
                val beforeSnap = SnapshotCreator.create(root, "before", fileIndex = fileIndex)

                parPipeline.map { (name, runner) ->
                    async { runner.run(name, root) {}.collect() }
                }.awaitAll()

                val afterSnap = SnapshotCreator.create(root, "after", fileIndex = fileIndex)
                SnapshotCreator.diff(beforeSnap, afterSnap)

                val parTotalMs = System.currentTimeMillis() - parStart

                val timeSaved = seqRawMs - parTotalMs
                val speedup = if (parTotalMs > 0) "%.1fx".format(seqRawMs.toDouble() / parTotalMs) else "∞"

                println("  %-12s │ %8dms │ %8dms │ %7dms │ %8s │ %8dms".format(
                    "%,d".format(fileCount), seqRawMs, parTotalMs, timeSaved, speedup, setupMs
                ))
            } finally {
                root.toFile().deleteRecursively()
            }
        }
        println()
    }
}
