# Qorche — Active Work Plan

> **Phase 1 (M0–M2), parallel execution (M3), and CLI roadmap are complete.**
> Qorche is positioned as an **infrastructure/orchestration layer** — a library
> that other tools embed, not an end-user CLI product. The CLI serves as a
> reference implementation.

---

## Priority: Library Distribution

### 1. Maven publish
**Status:** TODO
**Effort:** Small

Publish `io.qorche:core` to Maven Local (then Central). This is the highest-priority
distribution channel — lets any JVM project (agent frameworks, build tools, CI plugins)
embed qorche's conflict detection and DAG scheduling directly.

### 2. GraalVM shared library spike
**Status:** TODO
**Effort:** Medium
**Files:** New `native/` module

Export core API via `--shared` for Python/Node/Go FFI consumers. Enables non-JVM
ecosystems to use qorche without a JVM dependency. Design in
`memory/project_graalvm_shared_library.md`.

### 3. `.qorignore` file
**Status:** TODO
**Effort:** Medium
**Files:** `Snapshot.kt`

User-configurable ignore patterns. Consider reading `.gitignore` as baseline.
Current hardcoded list misses `.kotlin/`, `node_modules/`, etc.

---

## Lower Priority

### `qorche ci` / GitHub Action
**Status:** TODO
**Effort:** Large

Run CI tasks (lint, format, test) concurrently on a single checkout instead of
isolated matrix jobs. Compelling on self-hosted runners with persistent workspaces.

### More dogfood testing
**Status:** TODO
**Effort:** Medium

- Retry with real agents (`max_retries: 1`)
- Diamond DAG with real agents
- Scope violation with real agents

### CLI distribution (Homebrew, npm)
**Status:** Deferred

Not the focus. CLI is a reference implementation. Revisit if end-user demand materialises.

---

## Done

- `qorche status` command (v0.8.x)
- `qorche logs` command (v0.8.x)
- Exit codes: 0 success, 1 failure, 2 orchestrator error (v0.8.x)
- JSON output with `--json` flag (v0.8.x)
- Per-task log files (v0.7.2)
- Cold-start benchmark (v0.7.2)
- Native binary optimization 55MB → 20MB (v0.7.2)
- UPX compression Linux/Windows (v0.7.2)
- Remove unused sqlite-jdbc (v0.7.2)
- Dogfood with Claude Code — parallel + conflict detection (v0.7.0)
- Retry-on-conflict with rollback (v0.6.0)
- Scope audit with group-level attribution (v0.4.0)
- Parallel execution + MVCC (v0.4.0)
- CI/CD pipeline (v0.5.0)
- Semantic-release + git-cliff (v0.5.0)
- KDocs on public API (v0.8.0)
- Error handling hardening (v0.8.1)
- ConflictDetectorTest + SnapshotStoreTest (v0.8.1)
