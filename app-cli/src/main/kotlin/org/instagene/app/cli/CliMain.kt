package org.instagene.app.cli

import kotlin.system.exitProcess

/** Standalone entry point for the command-line front-end (`./gradlew :app-cli:runCli`). */
fun main(argv: Array<String>) {
    exitProcess(Cli.run(argv.toList()))
}
