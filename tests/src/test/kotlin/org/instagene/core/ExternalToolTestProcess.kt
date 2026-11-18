package org.instagene.core

import java.nio.file.Files
import java.nio.file.Path

/** A real child process with deterministic pipe behavior; requires no installed tool. */
object ExternalToolTestProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        Files.writeString(Path.of(args[1]), ProcessHandle.current().pid().toString())
        when (args[0]) {
            "blocked" -> Thread.sleep(30_000)
            "echo" -> {
                System.`in`.transferTo(System.out)
                System.err.print("diagnostic")
            }
            "file" -> Files.writeString(Path.of(args[2]), "file payload")
            else -> throw IllegalArgumentException(args[0])
        }
    }
}
