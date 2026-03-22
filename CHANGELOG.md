# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - 2026-03-22

### Added
- Multi-module Gradle project setup (core, agent, cli) with strict dependency boundaries
- Core domain models: TaskDefinition, TaskStatus, TaskType, TaskNode, AgentEvent, AgentResult
- TaskGraph — DAG with topological sort, cycle detection (DFS three-color), readyTasks(), parallelGroups()
- Snapshot system — SHA-256 file hashing with line-ending normalisation and cross-platform path handling
- FileIndex — mtime+size cache to skip re-hashing unchanged files (same optimisation as git status)
- ConflictDetector — MVCC write-write conflict detection between concurrent agents
- WAL — append-only JSON Lines write-ahead log (TaskStarted, TaskCompleted, TaskFailed)
- MockAgentRunner — configurable test double for pipeline testing without LLM calls
- ClaudeCodeAdapter — cross-platform Claude Code CLI process spawning
- CLI entry point with `run` and `version` commands via Clikt
- Benchmark suite comparing sequential vs parallel execution with MVCC overhead analysis
- Large-scale benchmarks (50k, 100k files) as opt-in via `./gradlew :agent:largeBenchmark`
- docs/IMPLEMENTATION.md for tracking milestone progress
- docs/PHASE1_PLAN.md with full roadmap and architecture decisions

### Benchmark findings (informing M1 optimisation priorities)
- **Conflict detection is near-zero cost**: 0.1ms (1k files) to 2.7ms (20k files)
- **Snapshot caching (FileIndex) provides ~3.5x speedup** across all file counts
- **Parallel execution speedup scales with step count**: 2x (3 steps) → 7.9x (12 steps)
- **Full-repo snapshots are the bottleneck**: at 5k+ files, walking and hashing the entire
  repo twice per step dominates total time, cancelling out parallelism gains
- **Crossover point ~1k files**: below this, parallel + MVCC is a clear win (4.3x at 100 files);
  above this, naive full-repo snapshots eat the gain

#### M1 optimisation priorities (derived from benchmarks)
1. **Scoped snapshots** — use task `files` field to hash only relevant paths, not the entire repo
2. **One snapshot per pipeline** — snapshot before all steps + after all steps, not per-step
3. **Parallel file hashing** — Files.walk is single-threaded; use Dispatchers.IO for concurrent hashing
4. **Incremental snapshots** — only re-walk directories that changed (inotify/WatchService in Phase 2+)
