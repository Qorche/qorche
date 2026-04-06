package io.qorche.core

/**
 * Standard CLI exit codes for structured error reporting.
 * CI pipelines can switch on the exit code to handle each case differently.
 */
enum class ExitCode(val code: Int) {
    /** All tasks completed successfully (exit 0). */
    SUCCESS(0),
    /** One or more tasks failed during execution (exit 1). */
    TASK_FAILURE(1),
    /** Invalid configuration, YAML parse error, or missing runner (exit 2). */
    CONFIG_ERROR(2),
    /** Unresolved MVCC conflict after exhausting retries (exit 3). */
    CONFLICT(3)
}
