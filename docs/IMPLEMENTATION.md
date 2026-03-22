# Qorche — Implementation Progress

**Current milestone**: M2 (in progress)
**Last updated**: 2026-03-22

---

## M0: Project scaffold + single agent runner [COMPLETE]

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
- [x] Benchmark harness with MockAgentRunner (concurrent vs sequential comparison)

### Remaining (deferred to later)
- [ ] End-to-end test: CLI → MockAgentRunner → result
- [ ] Agent process cleanup on Ctrl+C (shutdown hook in ClaudeCodeAdapter)
- [ ] Memory validation: < 30MB RSS idle with -Xmx64m

---

## M1: File snapshot system [COMPLETE]

### Context from M0 benchmarks
Benchmarks revealed that **full-repo snapshots are the primary bottleneck**. At 5k+ files,
walking and hashing the entire repo twice per step cancels out parallelism gains. Conflict
detection itself is near-zero cost (0.1–2.7ms). This means M1 must prioritise:
1. **Scoped snapshots** — hash only files relevant to each task (use `files` field)
2. **Pipeline-level snapshots** — one before + one after the whole pipeline, not per-step
3. **Parallel file hashing** — use Dispatchers.IO instead of single-threaded Files.walk
4. **FileIndex persistence** — warm cache on startup so first snapshot is fast too

### Done
- [x] Parallel file hashing via Dispatchers.IO (coroutine-based batched hashing)
- [x] Scoped snapshots using `createScoped()` — hash only relevant paths/directories
- [x] `SnapshotDiff.summary()` — human-readable diff report ("+3 added, ~1 modified, -2 deleted")
- [x] `SnapshotStore` — persist snapshots to `.qorche/snapshots/{id}.json`
- [x] FileIndex persistence — `saveTo()` / `loadFrom()` for `.qorche/file-index.json`
- [x] `Orchestrator` — coordinates agent runs with snapshot lifecycle and WAL logging
- [x] CLI `run` command wired to Orchestrator (takes snapshots, shows diff report)
- [x] CLI `history` command — lists past snapshots with timestamps and file counts
- [x] CLI `diff` command — shows file changes between two snapshots
- [x] `SnapshotTest` — 9 tests: hashing, line-ending normalisation, scoped snapshots, diffs
- [x] `WALTest` — append/read-back, empty file, timestamp preservation
- [x] `FileIndexTest` — cache hit/miss, persistence save/load
- [x] `OrchestratorTest` — 5 tests: full lifecycle, failed tasks, scoped tasks, persistence
- [x] Re-run benchmarks — parallel hashing improved warm snapshots ~2x across all file counts

### Benchmark results (M1 vs M0)
| Files  | M0 Warm Snap | M1 Warm Snap | Improvement |
|--------|-------------|-------------|-------------|
| 1,000  | 89ms        | 41ms        | 2.2x faster |
| 5,000  | 387ms       | 197ms       | 2.0x faster |
| 10,000 | 773ms       | 409ms       | 1.9x faster |
| 20,000 | 1,579ms     | 789ms       | 2.0x faster |

End-to-end crossover (parallel+MVCC vs sequential) moved from ~1k to ~8k files.
At 5k files: M0 was 0.6x (slower), M1 is 1.4x (faster).

### Remaining (deferred to later)
- [ ] Performance target: 10k files < 2s cold, < 500ms warm (currently 632ms cold, 409ms warm)

---

## M2: Task graph + dependency model [COMPLETE]

### Done
- [x] `TaskYamlParser` — parse YAML task definitions via kaml into TaskProject/TaskGraph
- [x] `Orchestrator.runGraph()` — execute TaskGraph sequentially in topological order
- [x] Failed task propagation — dependents automatically skipped with reason
- [x] `qorche plan <file>` — dry-run showing execution order + parallel groups
- [x] `qorche run <file.yaml>` — execute task graph from YAML file
- [x] WAL records for each task execution (start + complete/fail per task)
- [x] Cycle detection with clear error messages (from M0 TaskGraph)
- [x] `TaskYamlParserTest` — 10 tests: parsing, dependencies, cycles, errors, parallel groups
- [x] `OrchestratorGraphTest` — 5 tests: 5-task graph, failure propagation, order, scoping, WAL history

### Also added (beyond original M2 scope)
- [x] `ShellRunner` — secure shell command executor with allowlist, timeout, env filtering
- [x] `ShellRunnerTest` — 8 tests: allowlist rejection, tokenisation, cross-platform, env filtering

### Remaining (deferred to later)
- [ ] End-to-end CLI test: `qorche plan` + `qorche run` with YAML file
- [ ] Error reporting polish (line numbers in YAML parse errors)

---

## M3: Parallel execution + MVCC conflict detection (not started)

### Tasks
- [ ] Execute parallel groups concurrently via coroutines
- [ ] MVCC conflict detection after parallel task completion
- [ ] Conflict resolution strategy (fail-fast or merge)
- [ ] Update benchmarks to measure real parallel execution (not just simulated)
- [ ] WAL entries for conflict events
- [ ] Test: two agents modify same file → conflict detected
- [ ] Test: two agents modify different files → no conflict, both succeed

---

## Future: Native shared library (post-M3)

### Tasks
- [ ] Spike: GraalVM `--shared` build with one exported function
- [ ] Design C API header (function signatures, callback typedefs for async monitoring)
- [ ] Create `native/` module with @CEntryPoint wrappers
- [ ] Handle sqlite-jdbc JNI registration for native-image
- [ ] Build + test on Windows, Linux, macOS
- [ ] Example consumers: Python ctypes, Node ffi-napi
- [ ] CI matrix build for all platforms
