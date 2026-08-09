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

    fun has(key: String): Boolean = key in flags || key in options

    fun flag(key: String): Boolean = key in flags || options[key]?.lowercase() in setOf("true", "yes", "1")

    fun opt(key: String): String? = options[key]

    fun opt(key: String, default: String): String = options[key] ?: default

    fun require(key: String): String = options[key]
        ?: throw CliException("Missing required option --$key")

    fun int(key: String, default: Int): Int = options[key]?.let {
        it.toIntOrNull() ?: throw CliException("--$key expects a whole number, got '$it'")
    } ?: default

    fun requireInt(key: String): Int = int(key, Int.MIN_VALUE).also {
        if (it == Int.MIN_VALUE) throw CliException("Missing required option --$key")
    }

    fun double(key: String, default: Double): Double = options[key]?.let {
        it.toDoubleOrNull() ?: throw CliException("--$key expects a number, got '$it'")
    } ?: default

    fun positional(index: Int): String? = positionals.getOrNull(index)
}

/** A user-facing error: printed as a clean message rather than a stack trace. */
class CliException(message: String) : Exception(message)
