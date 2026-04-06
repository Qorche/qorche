package io.qorche.cli

/**
 * Terminal color support utility. Wraps text in ANSI escape codes when color output is enabled.
 *
 * Color is auto-detected based on environment variables (`NO_COLOR`, `TERM`, `COLORTERM`,
 * `WT_SESSION`) and console availability. Override with [forceColor] to explicitly enable or disable.
 */
object Terminal {
    /** Overrides auto-detection. Set to `true` to force color, `false` to disable, or `null` for auto. */
    var forceColor: Boolean? = null

    private val colorEnabled: Boolean by lazy {
        forceColor ?: (
            System.getenv("NO_COLOR") == null
            && System.getenv("TERM") != "dumb"
            && (System.console() != null
                || System.getenv("TERM") != null
                || System.getenv("WT_SESSION") != null
                || System.getenv("COLORTERM") != null)
        )
    }

    private fun ansi(code: String, text: String): String =
        if (forceColor ?: colorEnabled) "\u001b[${code}m$text\u001b[0m" else text

    /** Wraps [text] in green ANSI color (used for success indicators). */
    fun green(text: String) = ansi("32", text)

    /** Wraps [text] in red ANSI color (used for errors and failures). */
    fun red(text: String) = ansi("31", text)

    /** Wraps [text] in yellow ANSI color (used for warnings). */
    fun yellow(text: String) = ansi("33", text)

    /** Wraps [text] in cyan ANSI color (used for informational labels). */
    fun cyan(text: String) = ansi("36", text)

    /** Wraps [text] in dim ANSI styling (used for secondary information like elapsed time). */
    fun dim(text: String) = ansi("2", text)

    /** Wraps [text] in bold ANSI styling. */
    fun bold(text: String) = ansi("1", text)
}
