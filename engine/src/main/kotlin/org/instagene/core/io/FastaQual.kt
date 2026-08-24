package org.instagene.core.io

import java.io.File

/** One named record from a FASTA-QUAL (Phred score) sidecar. */
data class FastaQualRecord(
    val name: String,
    val scores: List<Int>,
    val description: String = "",
) {
    init {
        require(name.isNotBlank()) { "FASTA-QUAL record name cannot be blank" }
        require(scores.all { it >= 0 }) { "FASTA-QUAL scores must be non-negative" }
    }
}

/**
 * Parser for the whitespace-separated Phred scores stored in FASTA-QUAL
 * sidecars. These files carry no bases, so callers explicitly decide how a
 * record is mapped or aligned to a reference sequence.
 */
object FastaQual {
    fun read(file: File): List<FastaQualRecord> = parse(file.readText(), file.nameWithoutExtension)

    fun parse(text: String, defaultName: String = "qualities"): List<FastaQualRecord> {
        val records = mutableListOf<FastaQualRecord>()
        var name: String? = null
        var description = ""
        val scores = mutableListOf<Int>()

        fun flush(line: Int) {
            val recordName = name ?: if (scores.isNotEmpty()) defaultName else return
            if (scores.isEmpty()) throw SeqIOException("FASTA-QUAL record '$recordName' has no scores", line.takeIf { it > 0 })
            records += FastaQualRecord(recordName, scores.toList(), description)
            scores.clear()
            description = ""
        }

        text.lineSequence().forEachIndexed { zeroBasedLine, raw ->
            val lineNumber = zeroBasedLine + 1
            var line = raw.trim()
            if (lineNumber == 1) line = line.removePrefix("\uFEFF").trim()
            when {
                line.isEmpty() || line.startsWith(";") || line.startsWith("#") -> Unit
                line.startsWith(">") -> {
                    flush(lineNumber - 1)
                    val header = line.drop(1).trim()
                    name = header.substringBefore(' ').ifBlank { defaultName }
                    description = header.substringAfter(' ', "").trim()
                }
                else -> line.split(Regex("\\s+")).forEach { token ->
                    val score = token.toIntOrNull()
                        ?: throw SeqIOException("Invalid FASTA-QUAL score '$token'", lineNumber)
                    if (score < 0) throw SeqIOException("FASTA-QUAL score must be non-negative: '$token'", lineNumber)
                    scores += score
                }
            }
        }
        flush(text.lineSequence().count())
        if (records.isEmpty()) throw SeqIOException("No FASTA-QUAL records found")
        return records
    }
}
