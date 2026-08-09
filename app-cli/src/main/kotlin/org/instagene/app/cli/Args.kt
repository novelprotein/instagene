package org.instagene.app.cli

/**
 * A tiny hand-rolled option parser — enough for `--flag`, `--key value`,
 * `--key=value` and positional arguments, with no third-party dependency.
 */
class Args(argv: List<String>) {

    private val options = LinkedHashMap<String, String>()
    private val flags = LinkedHashSet<String>()
    val positionals = ArrayList<String>()

    init {
        var i = 0
        while (i < argv.size) {
            val arg = argv[i]
            when {
                arg.startsWith("--") && arg.contains('=') -> {
                    options[arg.substring(2).substringBefore('=')] = arg.substringAfter('=')
                }

                arg.startsWith("--") -> {
                    val key = arg.substring(2)
                    val next = argv.getOrNull(i + 1)
                    if (next != null && !next.startsWith("--")) {
                        options[key] = next
                        i++
                    } else {
                        flags += key
                    }
                }

                arg == "-o" -> {
                    val next = argv.getOrNull(i + 1)
                    if (next != null && !next.startsWith("--")) {
                        options["out"] = next
                        i++
                    }
                }

                else -> positionals += arg
            }
            i++
        }
    }

    /** True when [key] was given, either as a flag or as an option. */
    fun has(key: String): Boolean = key in flags || key in options

    /** True when [key] was given as a bare flag or with a truthy value (`true`, `yes` or `1`). */
    fun flag(key: String): Boolean = key in flags || options[key]?.lowercase() in setOf("true", "yes", "1")

    /** The value of option [key], or null when it was not given. */
    fun opt(key: String): String? = options[key]

    /** The value of option [key], or [default] when it was not given. */
    fun opt(key: String, default: String): String = options[key] ?: default

    /** The value of option [key], throwing [CliException] when it was not given. */
    fun require(key: String): String = options[key]
        ?: throw CliException("Missing required option --$key")

    /** The integer value of option [key], or [default]; [CliException] when it is not a whole number. */
    fun int(key: String, default: Int): Int = options[key]?.let {
        it.toIntOrNull() ?: throw CliException("--$key expects a whole number, got '$it'")
    } ?: default

    /** The required integer value of option [key]; [CliException] when missing or not a whole number. */
    fun requireInt(key: String): Int = int(key, Int.MIN_VALUE).also {
        if (it == Int.MIN_VALUE) throw CliException("Missing required option --$key")
    }

    /** The numeric value of option [key], or [default]; [CliException] when it is not a number. */
    fun double(key: String, default: Double): Double = options[key]?.let {
        it.toDoubleOrNull() ?: throw CliException("--$key expects a number, got '$it'")
    } ?: default

    /** The [index]-th positional argument, or null when there are not that many. */
    fun positional(index: Int): String? = positionals.getOrNull(index)
}

/** A user-facing error: printed as a clean message rather than a stack trace. */
class CliException(message: String) : Exception(message)
