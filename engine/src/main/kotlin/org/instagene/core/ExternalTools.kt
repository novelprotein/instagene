package org.instagene.core

import org.instagene.core.io.Fasta
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A command-line bioinformatics program InstaGene knows how to drive.
 *
 * [argsTemplate] may contain the placeholders `{in}` (path to a temporary FASTA
 * holding the current sequence) and `{out}` (path the tool should write to).
 * When neither appears the FASTA is piped in on stdin instead.
 */
data class ExternalTool(
    val id: String,
    val displayName: String,
    val executable: String,
    val argsTemplate: List<String>,
    val description: String,
    val installHint: String,
    val builtinEquivalent: String,
    val readsStdin: Boolean = argsTemplate.none { it.contains("{in}") },
    val producesOutFile: Boolean = argsTemplate.any { it.contains("{out}") },
)

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
        ),
        ExternalTool(
            id = "seqkit-locate",
            displayName = "seqkit locate",
            executable = "seqkit",
            argsTemplate = listOf("locate", "-d", "-i", "-p", "{pattern}"),
            description = "Locates a (degenerate) pattern on both strands.",
            installHint = "conda install -c bioconda seqkit",
            builtinEquivalent = "instagene find --pattern ...",
        ),
        ExternalTool(
            id = "emboss-revseq",
            displayName = "EMBOSS revseq",
            executable = "revseq",
            argsTemplate = listOf("-filter"),
            description = "Reverse complement.",
            installHint = "apt install emboss",
            builtinEquivalent = "instagene revcomp",
        ),
        ExternalTool(
            id = "emboss-transeq",
            displayName = "EMBOSS transeq",
            executable = "transeq",
            argsTemplate = listOf("-filter", "-frame", "1"),
            description = "Six-frame-capable translation.",
            installHint = "apt install emboss",
            builtinEquivalent = "instagene translate",
        ),
        ExternalTool(
            id = "emboss-restrict",
            displayName = "EMBOSS restrict",
            executable = "restrict",
            argsTemplate = listOf("-filter", "-sitelen", "6"),
            description = "Restriction map against the full REBASE enzyme set.",
            installHint = "apt install emboss",
            builtinEquivalent = "instagene digest --enzymes ...",
        ),
        ExternalTool(
            id = "emboss-needle",
            displayName = "EMBOSS needle",
            executable = "needle",
            argsTemplate = listOf("-filter", "-bsequence", "{other}", "-gapopen", "10", "-gapextend", "0.5"),
            description = "Needleman-Wunsch global alignment against a second sequence.",
            installHint = "apt install emboss",
            builtinEquivalent = "(no built-in aligner)",
        ),
        ExternalTool(
            id = "primer3",
            displayName = "primer3_core",
            executable = "primer3_core",
            argsTemplate = listOf("{in}"),
            description = "Thermodynamically rigorous primer design.",
            installHint = "apt install primer3",
            builtinEquivalent = "instagene primers --from --to",
        ),
        ExternalTool(
            id = "blastn",
            displayName = "blastn",
            executable = "blastn",
            argsTemplate = listOf("-subject", "{other}", "-outfmt", "7"),
            description = "Nucleotide BLAST against a second sequence.",
            installHint = "apt install ncbi-blast+",
            builtinEquivalent = "(no built-in aligner)",
        ),
        ExternalTool(
            id = "muscle",
            displayName = "MUSCLE",
            executable = "muscle",
            argsTemplate = listOf("-align", "{in}", "-output", "{out}"),
            description = "Multiple sequence alignment.",
            installHint = "apt install muscle",
            builtinEquivalent = "(no built-in aligner)",
        ),
    )

    private val pathCache = HashMap<String, String?>()

    /** Absolute path of [executable] on `PATH`, or null when it is not installed. */
    fun locate(executable: String): String? = pathCache.getOrPut(executable) {
        val pathEnv = System.getenv("PATH") ?: return@getOrPut null
        pathEnv.split(File.pathSeparator)
            .asSequence()
            .map { File(it, executable) }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.absolutePath
    }

    fun isAvailable(tool: ExternalTool): Boolean = locate(tool.executable) != null

    /** Every catalog entry that is actually installed on this machine. */
    fun available(): List<ExternalTool> = CATALOG.filter(::isAvailable)

    fun missing(): List<ExternalTool> = CATALOG.filterNot(::isAvailable)

    /** Clears the `PATH` lookup cache, for when a tool is installed mid-session. */
    fun rescan() = pathCache.clear()

    /**
     * Runs [tool] over [seq].
     *
     * [placeholders] fills template slots such as `{pattern}` or `{other}`;
     * [extraArgs] is appended verbatim, so the GUI can expose a free-text
     * argument box. Never throws for a non-zero exit — inspect [ToolResult].
     */
    fun run(
        tool: ExternalTool,
        seq: Seq,
        placeholders: Map<String, String> = emptyMap(),
        extraArgs: List<String> = emptyList(),
        timeoutSeconds: Long = 60,
        workingDir: File? = null,
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

        val fasta = Fasta.write(seq)
        val inFile = File.createTempFile("instagene-", ".fasta").apply {
            writeText(fasta)
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

            if (tool.readsStdin) {
                process.outputStream.bufferedWriter().use { it.write(fasta) }
            } else {
                process.outputStream.close()
            }

            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
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

    /** One-line availability report, used by `instagene tools` and the GUI panel. */
    fun report(): String = buildString {
        appendLine("External CLI tools (optional — InstaGene works without them):")
        appendLine()
        for (tool in CATALOG) {
            val path = locate(tool.executable)
            val status = if (path != null) "FOUND    $path" else "missing  install: ${tool.installHint}"
            appendLine("  %-18s %s".format(tool.displayName, status))
            appendLine("  %-18s %s".format("", tool.description))
            if (path == null) appendLine("  %-18s built-in: %s".format("", tool.builtinEquivalent))
            appendLine()
        }
        val found = available().size
        appendLine("$found of ${CATALOG.size} tools available.")
    }
}
