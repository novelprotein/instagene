package org.instagene.core.integration

import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Supervises all three process pipes under one timeout and cancellation policy. */
internal object ToolProcessRunner {
    fun run(
        tool: ExternalTool,
        command: List<String>,
        input: String?,
        workingDirectory: File? = null,
        timeoutSeconds: Long,
        cancellationRequested: () -> Boolean,
        outputFile: String? = null,
    ): ToolResult {
        val renderedCommand = command.joinToString(" ")
        var process: Process? = null
        var interrupted = false
        // Blocking pipe I/O must not occupy the shared CPU/common pools.
        val io = Executors.newFixedThreadPool(3) { task ->
            Thread(task, "instagene-tool-io").apply { isDaemon = true }
        }
        try {
            require(timeoutSeconds > 0) { "Tool timeout must be positive" }
            if (cancellationRequested()) return ToolResult(tool, renderedCommand, 130, "", "Cancelled")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            val child = ProcessBuilder(command).directory(workingDirectory).start()
            process = child
            val stdout = io.submit<String> { child.inputStream.bufferedReader().use { it.readText() } }
            val stderr = io.submit<String> { child.errorStream.bufferedReader().use { it.readText() } }
            io.submit {
                try {
                    child.outputStream.bufferedWriter().use { writer -> input?.let(writer::write) }
                } catch (_: IOException) {
                    // Early input rejection and supervisor termination both close this pipe.
                }
            }
            var finished = false
            var cancelled = false
            while (!finished) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                cancelled = cancellationRequested()
                if (cancelled || System.nanoTime() >= deadline) break
                finished = child.waitFor(100, TimeUnit.MILLISECONDS)
            }
            if (!finished) terminate(child)
            val capturedOutput = stdout.get(5, TimeUnit.SECONDS)
            val capturedError = stderr.get(5, TimeUnit.SECONDS)
            return when {
                cancelled -> ToolResult(tool, renderedCommand, 130, capturedOutput, "Cancelled")
                !finished -> ToolResult(tool, renderedCommand, 124, capturedOutput, "Timed out after ${timeoutSeconds}s")
                else -> ToolResult(tool, renderedCommand, child.exitValue(), capturedOutput, capturedError, outputFile)
            }
        } catch (_: InterruptedException) {
            interrupted = true
            return ToolResult(tool, renderedCommand, 130, "", "Cancelled")
        } catch (error: Exception) {
            return ToolResult(tool, renderedCommand, 1, "", "Failed to run: ${error.message}")
        } finally {
            process?.let { child ->
                try {
                    if (child.isAlive) terminate(child)
                } catch (_: InterruptedException) {
                    interrupted = true
                } finally {
                    // Kill before closing stdin: a writer can hold its monitor while blocked.
                    runCatching { child.outputStream.close() }
                    runCatching { child.inputStream.close() }
                    runCatching { child.errorStream.close() }
                }
            }
            io.shutdownNow()
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun terminate(process: Process) {
        process.descendants().use { descendants ->
            descendants.toList().asReversed().forEach { it.destroyForcibly() }
        }
        process.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS)
    }
}
