# Qorche — Implementation Progress

**Current milestone**: M0 (in progress)
**Last updated**: 2026-03-22

---

## M0: Project scaffold + single agent runner

### Done
- [x] Gradle multi-module setup (core, agent, cli)
- [x] Kotlin 2.1.10, JDK 21, GraalVM native-image plugin configured
- [x] Dependencies: kotlinx-coroutines, kotlinx-serialization, kotlinx-datetime, clikt, kaml, sqlite-jdbc
- [x] `AgentRunner` interface + `AgentEvent` sealed class in core/
- [x] `TaskDefinition`, `TaskStatus`, `TaskType`, `TaskNode` data models
- [x] `TaskGraph` — DAG with topological sort, cycle detection, `readyTasks()`, `parallelGroups()`
- [x] `Snapshot` + `SnapshotCreator` — file hashing with line-ending normalisation
- [x] `FileIndex` — mtime-based hash cache
- [x] `ConflictDetector` — MVCC write-write conflict detection
- [x] `WAL` — append-only JSON Lines log
- [x] `MockAgentRunner` — configurable test double
- [x] `ClaudeCodeAdapter` — cross-platform process spawning
- [x] CLI entry point with `run` and `version` commands
- [x] `TaskGraphTest` — linear, diamond, cycle detection, readyTasks, parallelGroups
- [x] `.qorche/` in .gitignore

### Remaining
- [x] Benchmark harness with MockAgentRunner (concurrent vs sequential comparison)
- [ ] `SnapshotTest` — hash verification, cross-platform path normalisation
- [ ] `ConflictDetectorTest` — overlapping and non-overlapping modifications
- [ ] `WALTest` — append, read-back, corruption resilience
- [ ] End-to-end test: CLI → MockAgentRunner → result
- [ ] Agent process cleanup on Ctrl+C (shutdown hook in ClaudeCodeAdapter)
- [ ] Memory validation: < 30MB RSS idle with -Xmx64m

### Definition of done
- `./qorche run "list all files in src/"` works on Windows, macOS, Linux
- Agent process cleaned up on Ctrl+C
- All core logic tested against MockAgentRunner
- Memory: < 30MB RSS idle with -Xmx64m

---

## M1: File snapshot system (not started)

### Context from M0 benchmarks
Benchmarks revealed that **full-repo snapshots are the primary bottleneck**. At 5k+ files,
walking and hashing the entire repo twice per step cancels out parallelism gains. Conflict
detection itself is near-zero cost (0.1–2.7ms). This means M1 must prioritise:
1. **Scoped snapshots** — hash only files relevant to each task (use `files` field)
2. **Pipeline-level snapshots** — one before + one after the whole pipeline, not per-step
3. **Parallel file hashing** — use Dispatchers.IO instead of single-threaded Files.walk
4. **FileIndex persistence** — warm cache on startup so first snapshot is fast too

### Tasks
- [ ] Scoped snapshots using task `files` field (critical for performance)
- [ ] Parallel file hashing via Dispatchers.IO
- [ ] Snapshot before/after each agent run
- [ ] Diff report: "3 files modified, 1 file added"
- [ ] `qorche history` — show past snapshots
- [ ] `qorche diff <id1> <id2>` — show changes between snapshots
- [ ] FileIndex persistence between runs (serialise to .qorche/file-index.json)
- [ ] Performance: 10k files < 2s first run, < 200ms cached
- [ ] Re-run benchmarks to validate optimisation impact

---

## M2: Task graph + dependency model (not started)

### Tasks
- [ ] Parse YAML task definitions via kaml
- [ ] Build DAG from task file, execute in topological order (sequential)
- [ ] `qorche plan tasks.yaml` — dry-run showing execution order + parallel groups
- [ ] WAL records for each task execution
- [ ] Cycle detection with clear error messages
- [ ] Execute 5-task graph against MockAgentRunner
