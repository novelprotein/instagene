package org.instagene.app.cli

/**
 * Minimal ANSI styling for terminal output.
 *
 * Colors are applied only when stdout/stderr is a real terminal (so piped
 * output stays plain and tests stay stable) and neither `NO_COLOR` nor
 * `--no-colors` was given.
 */
object Colors {

    private const val RESET = "\u001b[0m"

    /** True when styling is possible and was not disabled via [noColors] or `NO_COLOR`. */
    fun enabled(noColors: Boolean = false): Boolean =
        !noColors && System.getenv("NO_COLOR") == null && System.console() != null

    fun bold(s: String, on: Boolean): String = wrap(s, 1, on)

    fun red(s: String, on: Boolean): String = wrap(s, 31, on)

    fun green(s: String, on: Boolean): String = wrap(s, 32, on)

    private fun wrap(s: String, code: Int, on: Boolean): String =
        if (on) "\u001b[${code}m$s$RESET" else s
}