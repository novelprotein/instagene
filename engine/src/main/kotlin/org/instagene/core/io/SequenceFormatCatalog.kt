package org.instagene.core.io

import org.instagene.core.Seq
import java.io.File
import java.util.concurrent.TimeUnit

enum class FormatSupport { NATIVE, CONVERTER }

data class SequenceFormatDescriptor(
    val id: String,
    val displayName: String,
    val extensions: Set<String>,
    val support: FormatSupport,
)

/** Import families supported natively or through configured external converters. */
object SequenceFormatCatalog {
    val FORMATS = listOf(
        SequenceFormatDescriptor("alignment-fasta", "Aligned FASTA", setOf("afa", "msa"), FormatSupport.NATIVE),
        SequenceFormatDescriptor("alignment-clustal", "Clustal alignment", setOf("aln", "clustal"), FormatSupport.NATIVE),
        SequenceFormatDescriptor("alignment-stockholm", "Stockholm alignment", setOf("sto", "stockholm"), FormatSupport.NATIVE),
        SequenceFormatDescriptor("alignment-phylip", "PHYLIP alignment", setOf("phy", "phylip", "ph"), FormatSupport.NATIVE),
        SequenceFormatDescriptor("ape", "ApE", setOf("ape"), FormatSupport.NATIVE),
        SequenceFormatDescriptor("embl", "EMBL / ENA", setOf("embl", "ena"), FormatSupport.NATIVE),
        SequenceFormatDescriptor("genbank", "GenBank / DDBJ", setOf("gb", "gbk", "genbank"), FormatSupport.NATIVE),
        SequenceFormatDescriptor("swissprot", "Swiss-Prot", setOf("swiss", "sprot", "dat"), FormatSupport.NATIVE),
        SequenceFormatDescriptor("clcbio", "CLC Bio", setOf("clc", "clcbio"), FormatSupport.CONVERTER),
        SequenceFormatDescriptor("clonemanager", "Clone Manager", setOf("cm4", "cm5", "cmf"), FormatSupport.CONVERTER),
        SequenceFormatDescriptor("dnastRider", "DNA Strider", setOf("str", "strider"), FormatSupport.CONVERTER),
        SequenceFormatDescriptor("dnadynamo", "DNADynamo", setOf("ddna", "dynamo"), FormatSupport.CONVERTER),
        SequenceFormatDescriptor("dnasis", "DNASIS", setOf("dnasis"), FormatSupport.CONVERTER),
        SequenceFormatDescriptor("dnassist", "DNAssist", setOf("dnas", "dnassist"), FormatSupport.CONVERTER),
        SequenceFormatDescriptor("lasergene", "DNASTAR Lasergene", setOf("lasergene", "proseq"), FormatSupport.CONVERTER),
        SequenceFormatDescriptor("dsgene", "DS Gene", setOf("dsg", "dsgene"), FormatSupport.CONVERTER),
        SequenceFormatDescriptor("enzymex", "EnzymeX", setOf("enzx", "enzymex"), FormatSupport.CONVERTER),
        SequenceFormatDescriptor("gck", "Gene Construction Kit", setOf("gck", "gck2"), FormatSupport.CONVERTER),
        SequenceFormatDescriptor("geneious", "Geneious", setOf("geneious"), FormatSupport.CONVERTER),
        SequenceFormatDescriptor("genetool", "GeneTool", setOf("gtl", "genetool"), FormatSupport.CONVERTER),
        SequenceFormatDescriptor("genomecompiler", "Genome Compiler", setOf("gcproj", "genomecompiler"), FormatSupport.CONVERTER),
        SequenceFormatDescriptor("jellyfish", "Jellyfish", setOf("jelly", "jellyfish"), FormatSupport.CONVERTER),
        SequenceFormatDescriptor("macvector", "MacVector", setOf("mac", "macvector"), FormatSupport.CONVERTER),
        SequenceFormatDescriptor("pdraw32", "pDRAW32", setOf("pdw", "pdraw"), FormatSupport.CONVERTER),
        SequenceFormatDescriptor("serialcloner", "Serial Cloner", setOf("xdna"), FormatSupport.CONVERTER),
        SequenceFormatDescriptor("vectornti", "Vector NTI", setOf("ma4", "vnti"), FormatSupport.CONVERTER),
        SequenceFormatDescriptor("visualcloning", "Visual Cloning", setOf("vct", "visualcloning"), FormatSupport.CONVERTER),
    )

    val allExtensions: Set<String> = FORMATS.flatMap { it.extensions }.toSet()

    fun forFile(file: File): SequenceFormatDescriptor? =
        FORMATS.firstOrNull { file.extension.lowercase() in it.extensions }
}

/** Converter-backed import for closed legacy formats without embedding proprietary parsers. */
object ExternalSequenceFormats {
    private val commands = LinkedHashMap<String, List<String>>()

    /** Registers a command whose arguments may contain `{in}` and `{out}` placeholders. */
    fun register(formatId: String, command: List<String>) {
        require(command.isNotEmpty()) { "Converter command cannot be empty" }
        require(command.any { "{in}" in it }) { "Converter command must contain {in}" }
        commands[formatId.lowercase()] = command.toList()
    }

    fun read(file: File, descriptor: SequenceFormatDescriptor): Seq {
        val template = commandFor(descriptor.id) ?: throw SeqIOException(
            "${descriptor.displayName} requires a converter. Configure ${environmentKey(descriptor.id)} " +
                "with a command containing {in} and optionally {out}; the converter must emit FASTA, GenBank, EMBL, or GFF3."
        )
        val out = File.createTempFile("instagene-converted-", ".txt").apply { deleteOnExit() }
        val command = template.map { it.replace("{in}", file.absolutePath).replace("{out}", out.absolutePath) }
        val process = ProcessBuilder(command).start()
        val stdoutFuture = java.util.concurrent.CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readText() }
        val stderrFuture = java.util.concurrent.CompletableFuture.supplyAsync { process.errorStream.bufferedReader().readText() }
        val finished = process.waitFor(120, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw SeqIOException("${descriptor.displayName} converter timed out")
        }
        val stdout = stdoutFuture.get(5, TimeUnit.SECONDS)
        val stderr = stderrFuture.get(5, TimeUnit.SECONDS)
        if (process.exitValue() != 0) throw SeqIOException(
            "${descriptor.displayName} converter failed: ${stderr.ifBlank { "exit ${process.exitValue()}" }}"
        )
        val converted = if (out.length() > 0L) out.readText() else stdout
        if (converted.isBlank()) throw SeqIOException("${descriptor.displayName} converter returned no sequence")
        return SeqIO.parse(converted, file.nameWithoutExtension)
    }

    private fun commandFor(formatId: String): List<String>? = commands[formatId.lowercase()] ?: run {
        val value = System.getenv(environmentKey(formatId))?.trim().orEmpty()
        if (value.isBlank()) null else value.split(Regex("\\s+")).also { register(formatId, it) }
    }

    private fun environmentKey(formatId: String): String =
        "INSTAGENE_CONVERTER_" + formatId.uppercase().replace(Regex("[^A-Z0-9]"), "_")
}
