# Testing

## Overview

Qorche uses Kotlin Test (JUnit 5 platform) with `kotlinx-coroutines-test` for async
testing. The suite contains **394 tests** across 32 test files in three modules, covering
DAG scheduling, MVCC snapshots, conflict detection, CLI output, and full orchestrator
integration. No external mocking frameworks are used -- all test doubles are hand-written.

## Running tests

### Standard test suite

```bash
./gradlew test
```

Excludes benchmarks and large-scale tests. JVM is constrained to `-Xmx64m` to catch
memory regressions early.

### With linting (recommended)

```bash
./gradlew test detekt
```

Detekt runs static analysis and uploads SARIF reports. Always run both before committing.

### Benchmarks

```bash
./gradlew :agent:benchmark          # Standard benchmarks (100-20k files, -Xmx128m)
./gradlew :agent:largeBenchmark     # Large-scale (50k-100k files, -Xmx512m)
```

Benchmarks are tagged and excluded from `./gradlew test`. Run them manually to measure
MVCC overhead, snapshot scaling, and conflict detection throughput.

## Test architecture

### Module breakdown

| Module   | Test files | Tests | Focus |
|----------|-----------|-------|-------|
| `core/`  | 13        | 168   | DAG scheduling, MVCC snapshots, WAL serialization, conflict detection, file index caching, ignore patterns, YAML parsing, serialization round-trips, snapshot store persistence, runner config loading |
| `agent/` | 11        | 92    | Orchestrator integration, parallel execution, conflict retry/rollback, scope audit, shell runner process spawning, runner registry, benchmarks, cleanup |
| `cli/`   | 8         | 134   | End-to-end CLI pipeline, init command project detection, validate command ANSI output, config command, logs command, status command, JSON output formatting |

### Key test patterns

- **MockAgentRunner**: Custom mock (`agent/src/main/kotlin/io/qorche/agent/MockAgentRunner.kt`) that simulates file mutations with configurable write behavior. No external mocking framework needed.
- **Fixture YAML files**: Realistic task graphs in `cli/src/test/resources/fixtures/` (`parallel-no-conflict.yaml`, `diamond-dag.yaml`, `scope-overlap.yaml`, `cycle-error.yaml`).
- **Temp directory isolation**: Every test creates a fresh temp directory, cleaned up in `finally` blocks. No test depends on another test's filesystem state.
- **Cross-platform**: CI runs on Ubuntu and Windows. Path normalization (forward slashes, line ending normalization) is tested explicitly in `SnapshotTest`.
- **Coroutine testing**: Uses `kotlinx-coroutines-test` and `runBlocking` for async tests. No raw threads.
- **Memory-constrained execution**: Standard tests run with `-Xmx64m`, benchmarks with `-Xmx128m` or `-Xmx512m`, matching the project's memory discipline targets.

## Test categories

### Unit tests (core/)

The core module tests cover the foundational data structures and algorithms with no
dependency on the orchestrator or agent infrastructure:

- **TaskGraphTest** (6 tests): Topological sort, cycle detection, dependency resolution, execution group ordering.
- **SnapshotTest** (27 tests): File hashing (SHA-256, SHA-1, CRC32C), line ending normalization, directory traversal, scoped snapshots, hash algorithm configuration, diff detection for added/modified/deleted files.
- **ConflictDetectorTest** (13 tests): Group conflict detection for overlapping file modifications, conflict resolution (earlier task wins), scope violation detection for undeclared writes, write-write conflict detection between snapshots.
- **FileIndexTest** (4 tests): Mtime-based cache hits/misses, persistence save/load round-trips.
- **WALTest** (3 tests): Write-ahead log append and read-back in JSON Lines format.
- **TaskYamlParserTest** (13 tests): YAML parsing validation, runner references, scope declarations, error cases.
- **SerializationRoundTripTest** (9 tests): JSON round-trip for all persistent data classes (RunResult, TaskOutcome, GraphResult, TaskConflict, ConflictResolution, ConflictReport, ConflictRetryPolicy, ScopeViolation).
- **SnapshotStoreTest** (8 tests): Snapshot save/load, listing with sort order, corrupted file handling, latest snapshot retrieval.
- **IgnorePatternsTest** (9 tests): Default ignore patterns (`.git/`, `build/`, OS artifacts), `.qorignore` file parsing, reset behavior, snapshot integration.

### Integration tests (agent/)

The agent module tests verify the full orchestrator pipeline -- parse, snapshot, execute,
snapshot, detect:

- **OrchestratorTest** (9 tests): Single-task execution, WAL recording, file index persistence, scoped snapshots, snapshot diffing, elapsed time tracking, per-task runner dispatch.
- **OrchestratorGraphTest** (5 tests): Multi-task DAG execution, topological ordering, WAL history, scoped file paths.
- **OrchestratorEdgeCaseTest** (8 tests): Agent exceptions, non-zero exit codes, cascading failures, single-task groups skipping conflict detection, event callbacks.
- **OrchestratorCleanTest** (7 tests): Cleanup of snapshots/logs/WAL/cache, selective clean, `keepLast` retention, clean-then-run workflow.
- **ParallelExecutionTest** (18 tests): Concurrent task execution, conflict detection and retry, rollback before retry, scope audit, WAL retry entries, stubborn retry exhaustion, mixed conflict scenarios.
- **ShellRunnerTest** (8 tests): Real process spawning with conflict detection, parallel shell execution.
- **RunnerRegistryTest** (6 tests): Registry instantiation from config, runner lookup, validation.

### End-to-end tests (cli/)

- **CliEndToEndTest** (9 tests): Full CLI pipeline from YAML fixtures to JSON output. Tests `plan` (DAG grouping, scope overlap, cycle errors) and `run` (parallel execution, diamond DAG ordering, conflict output, status reporting).
- **InitCommandTest** (8 tests): Project type detection (Gradle/Kotlin, Node, Python, Rust, Go, generic), YAML parseability of generated files, `.qorignore` header format.
- **ValidateCommandTest** (4 tests): Elapsed time formatting, `--no-color` flag, ANSI terminal output.

### Benchmarks

Benchmark tests live in `agent/src/test/kotlin/io/qorche/agent/BenchmarkTest.kt` (11 tests)
and are excluded from normal `./gradlew test` runs via JUnit 5 tags:

- **`@Tag("benchmark")`** (9 tests): MVCC overhead across file counts (100-20k), sequential vs parallel execution, scaling with step count, conflict detection at scale, realistic file sizes, DAG propagation overhead, cold/warm start comparison, diamond DAG parallel throughput.
- **`@Tag("large-scale")`** (2 tests): 50k and 100k file scenarios for MVCC overhead and end-to-end execution.

## CI integration

GitHub Actions (`.github/workflows/ci.yml`) runs on every push and PR to `main`/`develop`:

- **Platform matrix**: Ubuntu and Windows.
- **Detekt first**: Static analysis runs before tests. SARIF report uploaded to GitHub Code Scanning (Ubuntu only).
- **Test reports**: Uploaded as artifacts on failure for debugging.
- **Benchmarks not in CI**: Tagged tests are excluded. Run benchmarks locally to avoid CI timeout and flakiness from variable runner performance.
- **JDK 21**: Temurin distribution via `actions/setup-java`.

## Testing perspectives

Beyond writing individual unit tests, apply these perspectives when reviewing
any new feature. Each one catches a different class of bug that isolated "inside-out"
tests miss.

### Round-trip testing

Generate output, feed it back as input, verify the result matches. This catches
format mismatches between producers and consumers that unit tests miss because
they test each side in isolation.

**Example from PR #65**: `envTemplate()` generated `QORCHE_RUNNER_SHELL_ENV_CI=true`
but `applyEnvOverrides()` expected a comma-separated `QORCHE_RUNNER_SHELL_ENV=CI=true,...`
format. Each function passed its own unit tests, but the round-trip failed. The fix
was a round-trip test: generate → parse → apply → verify.

**When to apply**: Any pair of functions where one produces output the other consumes.
Serialization/deserialization, template generation/parsing, config export/import.

### Contract testing

Verify that configuration values actually reach the systems they're supposed to
configure. Config is only useful if it has an effect.

**Example from PR #65**: `RunnerConfig.env` was defined and loaded correctly, but
neither `ClaudeCodeAdapter` nor `ShellRunner` passed those env vars to their
`ProcessBuilder`. The config existed but had no effect.

**When to apply**: Any new config field. Trace the field from YAML through the
loader, into the adapter, and confirm it reaches the external system (process env,
CLI args, HTTP headers, etc.).

### Sentinel/magic number testing

When using default values as "not explicitly set" signals, verify the sentinel
matches the actual default. Magic numbers that drift from their constants cause
silent bugs.

**Example from PR #65**: `mergeConfig()` compared `override.timeoutSeconds != 300L`
to detect explicit overrides. If the constant or default ever changed, this
comparison would silently break. The fix was `RunnerConfig.DEFAULT_TIMEOUT_SECONDS`.

**When to apply**: Any field with a default value that's also used in conditional
logic. Extract to a named constant and reference it in both places.

### Template usability testing

If your code generates a template file, actually try using it. Parse the template,
uncomment the examples, verify it produces valid input.

**Example from PR #65**: `generateRunnersExample()` produced `runners: {}` followed
by commented-out entries. Uncommenting the entries created a second `runners:` key
that conflicted with the `{}`. The fix was `runners:` (no `{}`).

**When to apply**: Any generated config, scaffold, or template file. Write a test
that uncomments the example sections and parses the result.

### Boundary testing between modules

When a feature spans multiple modules (core → agent → cli), test the boundaries
explicitly. Each module may work in isolation but fail when connected.

**Example from PR #65**: `ConfigCommand` called `TaskYamlParser.parseFile()` which
validates runner references. But in the config command context, runners come from
external sources (`.qorche/runners.yaml`, env vars), so validation was too strict.
The fix was `parseFileLenient()`.

**When to apply**: Any CLI command or API that composes multiple core functions.
Test with realistic inputs that exercise the full path, not just each function.

### Secret safety testing

Any code that displays, logs, or serializes configuration must be tested for
information leakage. Partial masking is often worse than no masking because it
creates a false sense of security.

**Example from PR #65**: `formatRunnerEnv()` showed `${v.take(4)}****` for long
values, leaking the first 4 characters of secrets. For API keys with common
prefixes (e.g., `sk-ant-...`), this reveals the provider and key type.

**When to apply**: Any display/logging of config values that might contain secrets.
Always use full masking (`****`) unless you have a specific reason not to.

### Cross-platform input testing

CLI tools receive input differently on each platform. Test with the actual input
mechanism, not just the parsed values.

**Example from PR #65**: `ConfigCommandTest` used `cmd.test(path.toString())` where
Clikt's `test(String)` interprets backslashes as escape characters. On Windows,
`C:\Users\...` became `C:Users...`. The fix was `test(listOf(path.toString()))`.

**When to apply**: Any CLI test that passes file paths. Use `test(listOf(...))` to
bypass string parsing. Test on both Unix and Windows CI.

### Perspective checklist for new features

Before merging a feature, ask:

1. **Round-trip**: Do producers and consumers agree on format?
2. **Contract**: Does config actually reach the target system?
3. **Sentinel**: Are default-value comparisons using named constants?
4. **Template**: Can generated templates be used without editing (beyond uncommenting)?
5. **Boundary**: Does the feature work when modules are composed, not just in isolation?
6. **Secrets**: Is sensitive data fully masked in all display paths?
7. **Platform**: Do tests work on both Unix and Windows?

## Adding new tests

### Conventions

1. **Location**: Place test files in `{module}/src/test/kotlin/io/qorche/{module}/` following the `{ClassName}Test.kt` naming pattern.
2. **Use MockAgentRunner**: For any test that needs agent execution, use `MockAgentRunner` with a configured write lambda. Never call real LLM APIs in unit/integration tests.
3. **Temp directories**: Create a fresh temp directory per test. Clean up in a `finally` block or use `@AfterEach`. Never rely on shared filesystem state.
4. **Coroutines**: Use `runBlocking` for coroutine tests. Import from `kotlinx.coroutines.test` when you need `runTest` or test dispatchers.
5. **No mocking frameworks**: Write hand-rolled test doubles. This keeps the dependency graph clean and GraalVM-compatible.
6. **Tags**: If writing a benchmark, annotate with `@Tag("benchmark")` or `@Tag("large-scale")`. These are automatically excluded from `./gradlew test`.
7. **Run both**: Always verify with `./gradlew test detekt` before committing.
