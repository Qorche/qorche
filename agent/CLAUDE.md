# agent/ module — Agent runners and adapters

**Package**: io.qorche.agent

## Purpose

Concrete implementations of the AgentRunner interface defined in core/.
Each adapter spawns and manages an external agent process, translating
Qorche's task model into the agent's native interface.

## Dependency rule

Depends on:
- core/ (AgentRunner interface, AgentEvent, AgentResult, data models)
- Kotlin stdlib, kotlinx.coroutines

MUST NOT depend on cli/. MUST NOT contain CLI formatting, terminal output,
or user-facing text. Reports events through AgentEvent sealed class — cli/
handles presentation.

## Key classes

### MockAgentRunner.kt (BUILD THIS FIRST)
Simulates an agent for testing. Configurable:
- Touch specified files (create, modify, delete)
- Return success or failure after configurable delay
- Emit FileModified events for each touched file

Used by ALL core logic tests. Build and test the full pipeline
(CLI -> orchestrator -> mock agent -> result) BEFORE implementing real adapters.

### ClaudeCodeAdapter.kt
Spawns Claude Code CLI as a child process via ProcessBuilder.
- Cross-platform: `claude` (macOS/Linux) vs `claude.exe` / `claude.cmd` (Windows)
- Detect OS: `System.getProperty("os.name")`
- Stream stdout/stderr line-by-line into Flow<AgentEvent>
- Parse output to detect file modifications where possible
- Handle lifecycle: start, monitor, timeout, kill
- Shutdown hook ensures child process killed on Ctrl+C / JVM exit
- ALWAYS destroy process in a finally block
- On Windows: may need `cmd /c claude.cmd` for PATH resolution

### AgentResult.kt
What an agent returns: exit code, files modified, duration, raw output.

## Cross-platform process management
- ProcessBuilder is cross-platform but commands differ per OS
- Always set working directory on ProcessBuilder
- Redirect stderr to stdout for unified streaming (or handle separately)
- Set reasonable timeouts — agents can hang
- Test cleanup: spawn process, kill orchestrator, verify no orphans

## Future adapters (Phase 4+)
- CodexAdapter (OpenAI)
- GeminiAdapter (Google)
- JunieAdapter (JetBrains)
- KoogAdapter (programmatic, in-process — no child process needed)
- GenericMCPAdapter (any MCP-speaking agent)
- AgentFSAdapter (wraps agent execution in AgentFS sandbox)

Each adapter in its own file, no dependencies on other adapters.
