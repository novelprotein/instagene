@file:Suppress("DuplicatedCode")

package org.instagene.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.instagene.core.io.Fasta
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A command-line bioinformatics program InstaGene knows how to drive.
 *
 * [ExternalTool.argsTemplate] may contain the placeholders `{in}` (path to a temporary FASTA
 * holding the current sequence) and `{out}` (path the tool should write to).
 * When neither appears, the FASTA is piped to standard input instead.
 */
enum class ToolCapability { PRIMER_DESIGN, LOCAL_SEARCH, ALIGNMENT, MULTIPLE_ALIGNMENT, ASSEMBLY, TRANSLATION, RESTRICTION_ANALYSIS, SECONDARY_STRUCTURE, SEQUENCE_STATS }

data class ExternalTool(
    val id: String,
    val displayName: String,
    val executable: String,
    val argsTemplate: List<String>,
    val description: String,
    val installHint: String,
    val builtinEquivalent: String,
    val capabilities: Set<ToolCapability> = emptySet(),
    val minimumVersion: String? = null,
    /** Arguments used for a harmless, short version probe. */
    val versionArgs: List<String> = listOf("--version"),
    val readsStdin: Boolean = argsTemplate.none { it.contains("{in}") },
    val producesOutFile: Boolean = argsTemplate.any { it.contains("{out}") },
)

/** The command, exit code, captured output, and optional file produced by running [tool]. */
data class ToolResult(
    val tool: ExternalTool,
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val outputFile: String? = null,
) {
    val succeeded: Boolean get() = exitCode == 0

    /** What the tool produced: its output file when it wrote one, otherwise stdout. */
    fun payload(): String = outputFile?.let { File(it).takeIf(File::exists)?.readText() } ?: stdout
}

@Serializable
enum class ToolHealthStatus { AVAILABLE, MISSING, VERSION_CHECK_FAILED, INCOMPATIBLE }

/** A concrete health observation, including the next safe action for the researcher. */
data class ToolHealth(
    val tool: ExternalTool,
    val available: Boolean,
    val path: String? = null,
    val version: String? = null,
    val error: String? = null,
    val compatible: Boolean = available && error == null,
    val status: ToolHealthStatus = when {
        !available -> ToolHealthStatus.MISSING
        !compatible -> ToolHealthStatus.INCOMPATIBLE
        else -> ToolHealthStatus.AVAILABLE
    },
) {
    /** An install, upgrade, or fallback instruction that can be displayed verbatim in CLI/GUI diagnostics. */
    val recommendedAction: String
        get() = when (status) {
            ToolHealthStatus.AVAILABLE -> "Ready to use."
            ToolHealthStatus.MISSING -> "Install with: ${tool.installHint}. Built-in fallback: ${tool.builtinEquivalent}."
            ToolHealthStatus.VERSION_CHECK_FAILED ->
                "The executable was found but could not answer its version check. Verify it runs from a terminal, then rescan. " +
                    "Built-in fallback: ${tool.builtinEquivalent}."
            ToolHealthStatus.INCOMPATIBLE -> {
                val requirement = tool.minimumVersion?.let { "Update to version $it or newer." } ?: "Check the installed version and command-line support."
                "$requirement Built-in fallback: ${tool.builtinEquivalent}."
            }
        }
}

/** Stable JSON shape for automation and help-desk diagnostics; it does not expose environment variables. */
@Serializable
data class ToolHealthDiagnostic(
    val id: String,
    val displayName: String,
    val executable: String,
    val status: ToolHealthStatus,
    val path: String? = null,
    val version: String? = null,
    val minimumVersion: String? = null,
    val error: String? = null,
    val action: String,
    val installHint: String,
    val builtinEquivalent: String,
    val capabilities: List<String>,
)

/** A reproducible command preview that never creates temporary files or executes a process. */
data class ToolCommandPreview(
    val tool: ExternalTool,
    val command: List<String>,
    val missingPlaceholders: List<String> = emptyList(),
) {
    val runnable: Boolean get() = missingPlaceholders.isEmpty()
    fun render(): String = command.joinToString(" ")
}

/**
 * Discovery and invocation of external CLI tools.
 *
 * Nothing here is required: InstaGene's own engine does every operation in pure
 * Kotlin. These bridges just let you hand the sequence you are editing to the
 * tools already installed on the machine.
 */
object ExternalTools {

    val CATALOG: List<ExternalTool> = listOf(
        ExternalTool(
            id = "seqkit-stats",
            displayName = "seqkit stats",
            executable = "seqkit",
            argsTemplate = listOf("stats", "-a"),
            description = "Detailed sequence statistics (N50, GC, length distribution).",
            installHint = "conda install -c bioconda seqkit",
            builtinEquivalent = "instagene stats",
            capabilities = setOf(ToolCapability.SEQUENCE_STATS),
            versionArgs = listOf("version"),
        ),
        ExternalTool(
            id = "seqkit-locate",
            displayName = "seqkit locate",
            executable = "seqkit",
            argsTemplate = listOf("locate", "-d", "-i", "-p", "{pattern}"),
            description = "Locates a (degenerate) pattern on both strands.",
            installHint = "conda install -c bioconda seqkit",
            builtinEquivalent = "instagene find --pattern ...",
            capabilities = setOf(ToolCapability.LOCAL_SEARCH),
            versionArgs = listOf("version"),
        ),
        ExternalTool(
            id = "emboss-revseq",
            displayName = "EMBOSS revseq",
            executable = "revseq",
            argsTemplate = listOf("-filter"),
            description = "Reverse complement.",
            installHint = "apt install emboss",
            builtinEquivalent = "instagene revcomp",
            capabilities = setOf(ToolCapability.TRANSLATION),
            versionArgs = listOf("-version"),
        ),
        ExternalTool(
            id = "emboss-transeq",
            displayName = "EMBOSS transeq",
            executable = "transeq",
            argsTemplate = listOf("-filter", "-frame", "1"),
            description = "Six-frame-capable translation.",
            installHint = "apt install emboss",
            builtinEquivalent = "instagene translate",
            capabilities = setOf(ToolCapability.TRANSLATION),
            versionArgs = listOf("-version"),
        ),
        ExternalTool(
            id = "emboss-restrict",
            displayName = "EMBOSS restrict",
            executable = "restrict",
            argsTemplate = listOf("-filter", "-sitelen", "6"),
            description = "Restriction map against the full REBASE enzyme set.",
            installHint = "apt install emboss",
            builtinEquivalent = "instagene digest --enzymes ...",
            capabilities = setOf(ToolCapability.RESTRICTION_ANALYSIS),
            versionArgs = listOf("-version"),
        ),
        ExternalTool(
            id = "emboss-needle",
            displayName = "EMBOSS needle",
            executable = "needle",
            argsTemplate = listOf("-filter", "-bsequence", "{other}", "-gapopen", "10", "-gapextend", "0.5"),
            description = "Needleman-Wunsch global alignment against a second sequence.",
            installHint = "apt install emboss",
            builtinEquivalent = "(no built-in aligner)",
            capabilities = setOf(ToolCapability.ALIGNMENT),
            versionArgs = listOf("-version"),
        ),
        ExternalTool(
            id = "primer3",
            displayName = "primer3_core",
            executable = "primer3_core",
            // primer3_core speaks Boulder-IO on standard input; passing a FASTA
            // path here works with neither the packaged binary nor Homebrew.
            argsTemplate = emptyList(),
            description = "Thermodynamically rigorous primer design.",
            installHint = "apt install primer3",
            builtinEquivalent = "instagene primers --from --to",
            capabilities = setOf(ToolCapability.PRIMER_DESIGN),
            minimumVersion = "2.6.0",
        ),
        ExternalTool(
            id = "blastn",
            displayName = "blastn",
            executable = "blastn",
            argsTemplate = listOf("-subject", "{other}", "-outfmt", "7"),
            description = "Nucleotide BLAST against a second sequence.",
            installHint = "apt install ncbi-blast+",
            builtinEquivalent = "(no built-in aligner)",
            capabilities = setOf(ToolCapability.LOCAL_SEARCH, ToolCapability.ALIGNMENT),
            versionArgs = listOf("-version"),
        ),
        ExternalTool(
            id = "muscle",
            displayName = "MUSCLE",
            executable = "muscle",
            argsTemplate = listOf("-align", "{in}", "-output", "{out}"),
            description = "Multiple sequence alignment.",
            installHint = "apt install muscle",
            builtinEquivalent = "(no built-in aligner)",
            capabilities = setOf(ToolCapability.MULTIPLE_ALIGNMENT),
            minimumVersion = "5.0.0",
            versionArgs = listOf("-version"),
        ),
        ExternalTool(
            id = "clustalo",
            displayName = "Clustal Omega",
            executable = "clustalo",
            argsTemplate = listOf("-i", "{in}", "-o", "{out}", "--outfmt", "fa", "--force"),
            description = "Multiple DNA, RNA, or protein sequence alignment.",
            installHint = "conda install -c bioconda clustalo",
            builtinEquivalent = "InstaGene pairwise/reference alignment",
            capabilities = setOf(ToolCapability.MULTIPLE_ALIGNMENT),
            minimumVersion = "1.2.0",
        ),
        ExternalTool(
            id = "mafft",
            displayName = "MAFFT",
            executable = "mafft",
            argsTemplate = listOf("--auto", "{in}"),
            description = "Fast multiple sequence alignment for DNA, RNA, and protein.",
            installHint = "conda install -c bioconda mafft",
            builtinEquivalent = "InstaGene pairwise/reference alignment",
            capabilities = setOf(ToolCapability.MULTIPLE_ALIGNMENT),
        ),
        ExternalTool(
            id = "tcoffee",
            displayName = "T-Coffee",
            executable = "t_coffee",
            argsTemplate = listOf("-in", "{in}", "-output", "fasta_aln", "-outfile", "{out}"),
            description = "Consistency-based multiple sequence alignment.",
            installHint = "conda install -c bioconda t_coffee",
            builtinEquivalent = "InstaGene pairwise/reference alignment",
            capabilities = setOf(ToolCapability.MULTIPLE_ALIGNMENT),
            versionArgs = listOf("-version"),
        ),
        ExternalTool(
            id = "cap3",
            displayName = "CAP3",
            executable = "cap3",
            argsTemplate = listOf("{in}"),
            description = "De novo assembly of Sanger sequencing reads into contigs.",
            installHint = "conda install -c bioconda cap3",
            builtinEquivalent = "InstaGene reference alignment",
            capabilities = setOf(ToolCapability.ASSEMBLY),
        ),
        ExternalTool(
            id = "rnafold",
            displayName = "ViennaRNA RNAfold",
            executable = "RNAfold",
            argsTemplate = listOf("--noPS"),
            description = "Predicts RNA and single-stranded nucleic-acid secondary structure.",
            installHint = "conda install -c bioconda viennarna",
            builtinEquivalent = "InstaGene Nussinov secondary-structure prediction",
            capabilities = setOf(ToolCapability.SECONDARY_STRUCTURE),
        ),
    )

    private val pathCache = HashMap<String, String?>()
    private val diagnosticsJson = Json { prettyPrint = true; encodeDefaults = true }

    /** Absolute path of [executable] on `PATH`, or null when it is not installed. */
    fun locate(executable: String): String? = synchronized(pathCache) {
        pathCache.getOrPut(executable) {
            val pathEnv = System.getenv("PATH") ?: return@getOrPut null
            pathEnv.split(File.pathSeparator)
                .asSequence()
                .map { File(it, executable) }
                .firstOrNull { it.isFile && it.canExecute() }
                ?.absolutePath
        }
    }

    /** True when [tool]'s executable is installed on `PATH`. */
    fun isAvailable(tool: ExternalTool): Boolean = locate(tool.executable) != null

    /**
     * Checks availability and runs the tool's documented version probe. The optional
     * [executablePath] makes this usable by bundled-tool launchers and deterministic tests.
     */
    fun healthCheck(tool: ExternalTool, timeoutSeconds: Long = 5, executablePath: String? = locate(tool.executable)): ToolHealth {
        require(timeoutSeconds > 0) { "Version-check timeout must be positive" }
        val path = executablePath ?: return ToolHealth(tool, false, error = "${tool.executable} is not on PATH", status = ToolHealthStatus.MISSING)
        return try {
            val process = ProcessBuilder(listOf(path) + tool.versionArgs).redirectErrorStream(true).start()
            val outputFuture = java.util.concurrent.CompletableFuture.supplyAsync {
                process.inputStream.bufferedReader().readText()
            }
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
                ToolHealth(
                    tool = tool,
                    available = true,
                    path = path,
                    error = "version check timed out after ${timeoutSeconds}s",
                    compatible = false,
                    status = ToolHealthStatus.VERSION_CHECK_FAILED,
                )
            } else {
                val output = outputFuture.get(5, TimeUnit.SECONDS).trim()
                val succeeded = process.exitValue() == 0
                val compatible = succeeded && isVersionCompatible(output, tool.minimumVersion)
                val status = when {
                    !succeeded -> ToolHealthStatus.VERSION_CHECK_FAILED
                    !compatible -> ToolHealthStatus.INCOMPATIBLE
                    else -> ToolHealthStatus.AVAILABLE
                }
                ToolHealth(
                    tool = tool,
                    available = true,
                    path = path,
                    version = conciseVersion(output),
                    error = when {
                        !succeeded -> "${tool.versionArgs.joinToString(" ")} exited with ${process.exitValue()}"
                        !compatible -> "requires version ${tool.minimumVersion} or newer; detected ${conciseVersion(output) ?: "an unreadable version"}"
                        else -> null
                    },
                    compatible = compatible,
                    status = status,
                )
            }
        } catch (e: Exception) {
            ToolHealth(
                tool = tool,
                available = true,
                path = path,
                error = e.message ?: "version check failed",
                compatible = false,
                status = ToolHealthStatus.VERSION_CHECK_FAILED,
            )
        }
    }

    /** Checks catalog entries concurrently, preserving catalog order for display and JSON consumers. */
    fun healthChecks(tools: List<ExternalTool> = CATALOG, timeoutSeconds: Long = 5): List<ToolHealth> {
        require(timeoutSeconds > 0) { "Version-check timeout must be positive" }
        val futures = tools.map { tool ->
            java.util.concurrent.CompletableFuture.supplyAsync { healthCheck(tool, timeoutSeconds) }
        }
        return futures.map { future -> future.get(timeoutSeconds + 7, TimeUnit.SECONDS) }
    }

    fun healthDiagnostics(checks: List<ToolHealth>): List<ToolHealthDiagnostic> = checks.map { health ->
        ToolHealthDiagnostic(
            id = health.tool.id,
            displayName = health.tool.displayName,
            executable = health.tool.executable,
            status = health.status,
            path = health.path,
            version = health.version,
            minimumVersion = health.tool.minimumVersion,
            error = health.error,
            action = health.recommendedAction,
            installHint = health.tool.installHint,
            builtinEquivalent = health.tool.builtinEquivalent,
            capabilities = health.tool.capabilities.map(ToolCapability::name).sorted(),
        )
    }

    /** Machine-readable diagnostics for CI, issue reports, and scripted workstation setup. */
    fun healthJson(checks: List<ToolHealth> = healthChecks()): String =
        diagnosticsJson.encodeToString(healthDiagnostics(checks))

    /** Human-readable diagnostics that always say what to install, upgrade, or use instead. */
    fun healthReport(checks: List<ToolHealth> = healthChecks()): String = buildString {
        appendLine("External CLI tool health (all tools are optional):")
        appendLine()
        checks.forEach { health ->
            appendLine("  ${health.tool.displayName}: ${health.status.name.lowercase().replace('_', ' ')}")
            health.path?.let { appendLine("    path: $it") }
            health.version?.let { appendLine("    version: $it") }
            health.tool.minimumVersion?.let { appendLine("    required: >= $it") }
            health.error?.let { appendLine("    detail: $it") }
            appendLine("    action: ${health.recommendedAction}")
            appendLine()
        }
        val ready = checks.count { it.status == ToolHealthStatus.AVAILABLE }
        appendLine("$ready of ${checks.size} tools ready. Use `instagene tools --health --json` for structured diagnostics.")
    }

    /** Every catalog entry that is actually installed on this machine. */
    fun available(): List<ExternalTool> = CATALOG.filter(::isAvailable)

    /** Every catalog entry that is not installed on this machine. */
    fun missing(): List<ExternalTool> = CATALOG.filterNot(::isAvailable)

    /** Clears the `PATH` lookup cache, for when a tool is installed mid-session. */
    fun rescan() = synchronized(pathCache) { pathCache.clear() }

    /** Renders a safe, reproducible command before the user runs an external executable. */
    fun commandPreview(
        tool: ExternalTool,
        placeholders: Map<String, String> = emptyMap(),
        extraArgs: List<String> = emptyList(),
    ): ToolCommandPreview {
        val defaults = mapOf("in" to "<input.fasta>", "out" to "<output.txt>") + placeholders
        val args = tool.argsTemplate.map { template ->
            defaults.entries.fold(template) { value, (key, replacement) -> value.replace("{$key}", replacement) }
        } + extraArgs
        val missing = args.flatMap { Regex("\\{([a-z]+)}").findAll(it).map { match -> match.groupValues[1] } }.distinct()
        return ToolCommandPreview(tool, listOf(locate(tool.executable) ?: tool.executable) + args, missing)
    }

    /**
     * Runs [tool] over [seq].
     *
     * [placeholders] fill template slots such as `{pattern}` or `{other}`;
     * [extraArgs] are appended verbatim, so a caller can expose a free-text
     * argument box. Never throws for a non-zero exit — inspect [ToolResult].
     */
    fun run(
        tool: ExternalTool,
        seq: Seq,
        placeholders: Map<String, String> = emptyMap(),
        extraArgs: List<String> = emptyList(),
        timeoutSeconds: Long = 60,
        workingDir: File? = null,
        inputFasta: String = Fasta.write(seq),
        cancellationRequested: () -> Boolean = { false },
    ): ToolResult {
        val exe = locate(tool.executable)
            ?: return ToolResult(
                tool = tool,
                command = tool.executable,
                exitCode = 127,
                stdout = "",
                stderr = "'${tool.executable}' is not on PATH. Install it with: ${tool.installHint}\n" +
                    "Or use the built-in equivalent: ${tool.builtinEquivalent}",
            )

        val inFile = File.createTempFile("instagene-", ".fasta").apply {
            writeText(inputFasta)
            deleteOnExit()
        }
        val outFile = if (tool.producesOutFile) {
            File.createTempFile("instagene-out-", ".txt").apply { deleteOnExit() }
        } else {
            null
        }

        val resolved = tool.argsTemplate.map { arg ->
            var a = arg.replace("{in}", inFile.absolutePath)
            outFile?.let { a = a.replace("{out}", it.absolutePath) }
            placeholders.forEach { (k, v) -> a = a.replace("{$k}", v) }
            a
        }
        val unresolved = resolved.filter { it.contains(Regex("\\{[a-z]+}")) }
        if (unresolved.isNotEmpty()) {
            return ToolResult(
                tool, "$exe ${resolved.joinToString(" ")}", 2, "",
                "Missing value for ${unresolved.joinToString(", ")}",
            )
        }

        val command = listOf(exe) + resolved + extraArgs
        return try {
            val process = ProcessBuilder(command)
                .directory(workingDir ?: inFile.parentFile)
                .start()

            // Drain stdout and stderr concurrently: reading them in sequence can
            // deadlock once a pipe buffer fills, which hangs both the reader and
            // the child.
            fun drain(stream: java.io.InputStream): String = stream.bufferedReader().readText()
            val stdoutFuture = java.util.concurrent.CompletableFuture.supplyAsync { drain(process.inputStream) }
            val stderrFuture = java.util.concurrent.CompletableFuture.supplyAsync { drain(process.errorStream) }

            try {
                if (tool.readsStdin) {
                    process.outputStream.bufferedWriter().use { it.write(inputFasta) }
                } else {
                    process.outputStream.close()
                }
            } catch (_: java.io.IOException) {
                // The child closed stdin before the FASTA was fully written.
            }

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            var finished = false
            while (!finished && System.nanoTime() < deadline && !cancellationRequested()) {
                finished = process.waitFor(200, TimeUnit.MILLISECONDS)
            }
            val cancelled = cancellationRequested()
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
            val stdout = stdoutFuture.get(5, TimeUnit.SECONDS)
            val stderr = stderrFuture.get(5, TimeUnit.SECONDS)
            if (cancelled) {
                ToolResult(tool, command.joinToString(" "), 130, stdout, "Cancelled")
            } else if (!finished) {
                ToolResult(tool, command.joinToString(" "), 124, stdout, "Timed out after ${timeoutSeconds}s")
            } else {
                ToolResult(
                    tool, command.joinToString(" "), process.exitValue(), stdout, stderr,
                    outFile?.absolutePath,
                )
            }
        } catch (e: Exception) {
            ToolResult(tool, command.joinToString(" "), 1, "", "Failed to run: ${e.message}")
        }
    }

    /**
     * Runs an optional tool whose native protocol is text on standard input.
     *
     * This is deliberately separate from [run]: its caller owns the input
     * format (for example Primer3 Boulder-IO), while [run] always supplies
     * FASTA. Like [run], it never throws for a process failure.
     */
    fun runText(
        tool: ExternalTool,
        input: String,
        timeoutSeconds: Long = 60,
        cancellationRequested: () -> Boolean = { false },
    ): ToolResult {
        val exe = locate(tool.executable)
            ?: return ToolResult(
                tool, tool.executable, 127, "",
                "'${tool.executable}' is not on PATH. Install it with: ${tool.installHint}\n" +
                    "Or use the built-in equivalent: ${tool.builtinEquivalent}",
            )
        val unresolved = tool.argsTemplate.filter { it.contains(Regex("\\{[a-z]+}")) }
        if (unresolved.isNotEmpty()) {
            return ToolResult(tool, "$exe ${tool.argsTemplate.joinToString(" ")}", 2, "", "Missing value for ${unresolved.joinToString(", ")}")
        }
        val command = listOf(exe) + tool.argsTemplate
        return try {
            val process = ProcessBuilder(command).start()
            fun drain(stream: java.io.InputStream): String = stream.bufferedReader().readText()
            val stdoutFuture = java.util.concurrent.CompletableFuture.supplyAsync { drain(process.inputStream) }
            val stderrFuture = java.util.concurrent.CompletableFuture.supplyAsync { drain(process.errorStream) }
            try {
                process.outputStream.bufferedWriter().use { it.write(input) }
            } catch (_: java.io.IOException) {
                // A tool may reject input and close stdin before all data arrives.
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            var finished = false
            while (!finished && System.nanoTime() < deadline && !cancellationRequested()) {
                finished = process.waitFor(200, TimeUnit.MILLISECONDS)
            }
            val cancelled = cancellationRequested()
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
            val stdout = stdoutFuture.get(5, TimeUnit.SECONDS)
            val stderr = stderrFuture.get(5, TimeUnit.SECONDS)
            when {
                cancelled -> ToolResult(tool, command.joinToString(" "), 130, stdout, "Cancelled")
                !finished -> ToolResult(tool, command.joinToString(" "), 124, stdout, "Timed out after ${timeoutSeconds}s")
                else -> ToolResult(tool, command.joinToString(" "), process.exitValue(), stdout, stderr)
            }
        } catch (e: Exception) {
            ToolResult(tool, command.joinToString(" "), 1, "", "Failed to run: ${e.message}")
        }
    }

    /** One-line availability report, as used by `instagene tools`. */
    fun report(): String = buildString {
        appendLine("External CLI tools (optional — InstaGene works without them):")
        appendLine("Run `instagene tools --health` to probe installed versions and see recovery actions.")
        appendLine()
        for ((_, displayName, executable, _, description, installHint, builtinEquivalent, capabilities) in CATALOG) {
            val path = locate(executable)
            val status = if (path != null) "FOUND    $path" else "missing  install: $installHint"
            appendLine("  %-18s %s".format(displayName, status))
            appendLine("  %-18s %s".format("", description))
            appendLine("  %-18s capabilities: %s".format("", capabilities.joinToString().ifBlank { "unspecified" }))
            if (path == null) appendLine("  %-18s built-in: %s".format("", builtinEquivalent))
            appendLine()
        }
        val found = available().size
        appendLine("$found of ${CATALOG.size} tools available.")
    }

    /** True when a detectable semantic version in [versionOutput] satisfies [minimumVersion]. */
    fun isVersionCompatible(versionOutput: String, minimumVersion: String?): Boolean {
        if (minimumVersion == null) return true
        val actual = Regex("\\d+(?:\\.\\d+)+").find(versionOutput)?.value ?: return false
        fun pieces(value: String) = value.split('.').map { it.toIntOrNull() ?: 0 }
        val found = pieces(actual)
        val required = pieces(minimumVersion)
        return (0 until maxOf(found.size, required.size)).firstOrNull { index ->
            (found.getOrElse(index) { 0 }) != (required.getOrElse(index) { 0 })
        }?.let { index -> found.getOrElse(index) { 0 } > required.getOrElse(index) { 0 } } ?: true
    }

    /** Keeps diagnostics readable while retaining the most useful first version line. */
    private fun conciseVersion(output: String): String? = output.lineSequence()
        .map(String::trim)
        .firstOrNull(String::isNotBlank)
        ?.take(240)
}
