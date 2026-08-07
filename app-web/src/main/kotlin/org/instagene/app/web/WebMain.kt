package org.instagene.app.web

/** Standalone entry point for the HTML5 web front-end (`./gradlew :app-web:runWeb`). */
fun main(argv: Array<String>) {
    val args = argv.toList()
    val port = args.getOrNull(args.indexOf("--port") + 1)?.toIntOrNull() ?: 8080
    WebServer.start(port)
    Runtime.getRuntime().addShutdownHook(Thread { WebServer.stop() })
    println("InstaGene web front-end running at http://localhost:$port (Ctrl+C to stop)")
}
