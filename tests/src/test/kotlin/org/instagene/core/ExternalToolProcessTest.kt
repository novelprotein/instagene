package org.instagene.core

import org.instagene.core.integration.ExternalTool
import org.instagene.core.integration.ExternalTools
import org.instagene.core.integration.ToolResult
import org.instagene.core.sequence.Seq
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*

class ExternalToolProcessTest {
    private fun tool(mode: String, pidFile: File, outputFile: Boolean = false): ExternalTool {
        val executable = File(System.getProperty("java.home"), "bin/${if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"}")
        val classpath = listOf(ExternalToolTestProcess::class.java, Unit::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).absolutePath }
            .distinct()
            .joinToString(File.pathSeparator)
        return ExternalTool(
            id = "test-process", displayName = "Test process", executable = executable.absolutePath,
            argsTemplate = listOf("-cp", classpath, ExternalToolTestProcess::class.java.name, mode, pidFile.absolutePath) +
                if (outputFile) listOf("{out}") else emptyList(),
            description = "test", installHint = "none", builtinEquivalent = "none",
            readsStdin = !outputFile,
        )
    }

    private fun withDirectory(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("instagene-process-test").toFile()
        try { test(directory) } finally {
            // Ensure a regression cannot leave the fake tool running after the test.
            directory.listFiles().orEmpty().filter { it.extension == "pid" }.forEach { file ->
                file.readText().toLongOrNull()?.let { ProcessHandle.of(it).orElse(null)?.destroyForcibly() }
            }
            directory.deleteRecursively()
        }
    }

    private fun assertStopped(pidFile: File) {
        assertTrue(pidFile.isFile, "The child should have started before supervision stopped it")
        val pid = pidFile.readText().toLong()
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "Child $pid is still running")
    }

    @Test
    fun timeoutCoversBlockedStdinForBothProtocols() = withDirectory { directory ->
        val input = "A".repeat(2 * 1024 * 1024)
        for (textProtocol in listOf(false, true)) {
            val pidFile = File(directory, "$textProtocol.pid")
            val tool = tool("blocked", pidFile)
            val start = System.nanoTime()
            val result = if (textProtocol) ExternalTools.runText(tool, input, timeoutSeconds = 3) else
                ExternalTools.run(tool, Seq(bases = "ACGT"), inputFasta = input, timeoutSeconds = 3)
            assertEquals(124, result.exitCode, result.stderr)
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 15)
            assertStopped(pidFile)
        }
    }

    @Test
    fun cancellationStopsAChildBlockedOnInput() = withDirectory { directory ->
        val pidFile = File(directory, "cancel.pid")
        val result = ExternalTools.runText(
            tool("blocked", pidFile), "A".repeat(2 * 1024 * 1024), timeoutSeconds = 15,
            cancellationRequested = { pidFile.length() > 0 },
        )
        assertEquals(130, result.exitCode, result.stderr)
        assertStopped(pidFile)
    }

    @Test
    fun interruptionStopsTheChildAndPreservesTheInterruptFlag() = withDirectory { directory ->
        val pidFile = File(directory, "interrupt.pid")
        val result = AtomicReference<ToolResult>()
        val interrupted = AtomicReference(false)
        val worker = thread {
            result.set(ExternalTools.runText(tool("blocked", pidFile), "A".repeat(2 * 1024 * 1024), timeoutSeconds = 15))
            interrupted.set(Thread.currentThread().isInterrupted)
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (pidFile.length() == 0L && worker.isAlive && System.nanoTime() < deadline) Thread.sleep(20)
            worker.interrupt()
            worker.join(10_000)
            assertFalse(worker.isAlive, "Interrupted runner did not return")
            assertEquals(130, result.get().exitCode)
            assertTrue(interrupted.get())
            assertStopped(pidFile)
        } finally {
            worker.interrupt()
            worker.join(10_000)
        }
    }

    @Test
    fun successfulProcessesPreserveStdoutStderrAndFilePayloads() = withDirectory { directory ->
        val input = "ACGT".repeat(20_000)
        val result = ExternalTools.runText(tool("echo", File(directory, "echo.pid")), input)
        assertEquals(0, result.exitCode, result.stderr)
        assertEquals(input, result.stdout)
        assertEquals("diagnostic", result.stderr)
        val fileResult = ExternalTools.run(tool("file", File(directory, "file.pid"), outputFile = true), Seq(bases = "ACGT"))
        try {
            assertEquals(0, fileResult.exitCode, fileResult.stderr)
            assertEquals("file payload", fileResult.payload())
        } finally { fileResult.outputFile?.let { File(it).delete() } }
    }
}
