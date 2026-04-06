# Module cli

Command-line interface built with [Clikt](https://ajalt.github.io/clikt/). Provides the
`qorche` command with subcommands for running tasks, inspecting snapshots, viewing history,
and generating JSON Schema for editor integration.

## Commands

| Command | Description |
|---------|-------------|
| `run` | Execute a task instruction or YAML task graph |
| `plan` | Preview execution order and parallel groups without running |
| `init` | Initialize a new Qorche project in the current directory |
| `config` | Show merged runner configuration from all layers |
| `validate` | Validate a YAML task file without running |
| `verify` | Run the verification step from a YAML task file |
| `replay` | Replay WAL history and verify snapshot consistency |
| `history` | List past snapshots with timestamps and file counts |
| `diff` | Show file changes between two snapshots |
| `logs` | List task logs or view a specific task's output |
| `status` | Show current task graph state |
| `schema` | Print JSON Schema for `tasks.yaml` |
| `clean` | Remove stored data from the `.qorche/` directory |
| `version` | Print version info |

# Package io.qorche.cli

CLI commands and output formatting for the `qorche` tool.
