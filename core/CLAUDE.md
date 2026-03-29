# core/ module — Pure domain logic

**Package**: io.qorche.core

## Purpose

The orchestrator's core domain: snapshots, file indexing, task graph, conflict
detection, WAL, and all data models. Everything here is deterministic, testable,
and domain-agnostic — no references to AI, LLMs, or specific agent implementations.

This module could serve CI/CD, build systems, or any domain that needs concurrent
filesystem mutation coordination. The AI-specific code lives only in agent/.

## Dependency rule (ABSOLUTE)

This module depends on NOTHING except:
- Kotlin stdlib
- kotlinx.coroutines
- kotlinx.serialization (JSON)
- kotlinx.datetime
- kaml (YAML parsing for task definitions)

It MUST NOT depend on agent/ or cli/. It MUST NOT import process management,
CLI frameworks, or IO-specific code. If something needs an external system,
define an interface here and implement it in agent/ or cli/.

## Key classes and responsibilities

### Snapshot.kt
Immutable point-in-time record of file hashes in the working directory.
- SHA-256 via java.security.MessageDigest
- File contents streamed through digest (never fully loaded into memory)
- Line endings normalised to `\n` before hashing (cross-platform consistency)
- Paths stored with forward slashes as canonical form
- Immutable once created

### FileIndex.kt
Mtime-based cache that avoids re-hashing unchanged files.
- Compares file size + lastModified to cached entry
- Match: reuse cached hash (fast path)
- Mismatch: re-hash and update cache (slow path)
- Persisted to disk between runs for fast startup
- Same optimisation Git uses for `git status`

### TaskGraph.kt
DAG (Directed Acyclic Graph) of tasks with dependency edges.
- Topological sort for execution order
- Cycle detection via DFS with three-color marking
- parallelGroups() identifies tasks that can run concurrently (Phase 2)
- readyTasks() returns tasks whose dependencies are all completed
- Hand-rolled adjacency list — no graph library needed

### Task.kt
Data models: TaskDefinition (@Serializable, loaded from YAML), TaskType enum,
TaskStatus enum, TaskNode (runtime state with mutable status and snapshot refs).

### ConflictDetector.kt
Compares two snapshots to find added/modified/deleted files (SnapshotDiff).
Used after agent runs to generate diffs, and in Phase 2+ for MVCC conflict
detection at commit time.

### WAL.kt
Write-ahead log — append-only JSON Lines file.
Sealed class WALEntry: TaskStarted, TaskCompleted, TaskFailed.
Every action logged before state changes. Enables replay, audit, debugging.

### AgentRunner.kt (INTERFACE ONLY)
The AgentRunner interface and AgentEvent sealed class live in core/ so that
core/ code can reference them without depending on agent/. The implementations
(MockAgentRunner, ClaudeCodeAdapter) live in agent/.

## Serialization rules
- ALL persistent data classes MUST have @Serializable
- Use kotlinx.serialization only (GraalVM compatible)
- Timestamps: kotlinx.datetime.Instant (not java.time)
- Paths: stored as String (forward-slash canonical), not java.nio.file.Path

## Testing
- All tests use MockAgentRunner or pure unit tests
- Test file hashing with known content and expected SHA-256 values
- Test TaskGraph with various DAG shapes: linear, diamond, wide fan-out
- Test cycle detection with intentionally cyclic graphs
- Test ConflictDetector with overlapping and non-overlapping modifications
- Test cross-platform path normalisation
- Test FileIndex cache hit/miss behaviour
