package org.instagene.app.web

import org.instagene.core.Version
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.system.exitProcess

/** Standalone entry point for the HTML5 web front-end (`./gradlew :app-web:runWeb`). */
fun main(argv: Array<String>) {
    val args = argv.toList()
    if (args.contains("--help") || args.contains("-h")) {
        printUsage()
        return
    }
    val port = option(args, "--port")?.toIntOrNull() ?: 8080
    val share = args.contains("--share")
    val host = if (share) "0.0.0.0" else option(args, "--listen") ?: "127.0.0.1"
    try {
        WebServer.start(host, port)
        Runtime.getRuntime().addShutdownHook(Thread { WebServer.stop() })
        val shownHost = if (share) lanAddress() ?: "0.0.0.0" else host
        @Suppress("HttpUrlsUsage")
        println("InstaGene ${Version.VERSION} web front-end running at http://$shownHost:$port (Ctrl+C to stop)")
    } catch (e: Exception) {
        System.err.println("instagene-web: ${e.message}")
        exitProcess(1)
    }
}

private fun option(args: List<String>, flag: String): String? {
    val i = args.indexOf(flag)
    if (i < 0) return null
    return args.getOrNull(i + 1)?.takeIf { !it.startsWith("--") }
}

/** A best-effort LAN address, used only to print a friendlier URL with `--share`. */
private fun lanAddress(): String? = NetworkInterface.getNetworkInterfaces()
    .asSequence()
    .filter { it.isUp && !it.isLoopback }
    .flatMap { it.inetAddresses.asSequence() }
    .firstOrNull { it is Inet4Address }
    ?.hostAddress

private fun printUsage() {
    println(
        """
        InstaGene ${Version.VERSION} - HTML5 web front-end.

        Usage: instagene-web [--port N] [--listen HOST] [--share]

          --port N         TCP port to bind (default 8080)
          --listen HOST    address to bind (default 127.0.0.1, this machine only)
          --share          bind all interfaces (0.0.0.0) for other machines on the LAN
        """.trimIndent()
    )
}
