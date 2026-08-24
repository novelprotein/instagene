package org.instagene.core.io

import org.instagene.core.Seq
import org.instagene.core.view

/** Interchange formats for a pre-aligned multiple-sequence alignment. */
enum class AlignmentFormat(val displayName: String) {
    FASTA("Aligned FASTA"),
    CLUSTAL("Clustal"),
    STOCKHOLM("Stockholm"),
    PHYLIP("PHYLIP"),
}

/**
 * Lightweight, standards-oriented alignment readers and writers. The parser
 * preserves row order, names, gaps, and blocks; the caller decides whether
 * unequal rows should be treated as an unaligned file or a partial import.
 */
object AlignmentIO {

    fun detectFormat(text: String): AlignmentFormat? {
        val first = text.lineSequence().firstOrNull { it.isNotBlank() }?.trimStart()?.removePrefix("\uFEFF") ?: return null
        return when {
            first.startsWith("CLUSTAL", ignoreCase = true) || first.startsWith("MUSCLE", ignoreCase = true) -> AlignmentFormat.CLUSTAL
            first.startsWith("# STOCKHOLM", ignoreCase = true) -> AlignmentFormat.STOCKHOLM
            Regex("^\\s*\\d+\\s+\\d+(?:\\s+.*)?$").matches(first) -> AlignmentFormat.PHYLIP
            first.startsWith(">") -> AlignmentFormat.FASTA
            else -> null
        }
    }

    fun parse(text: String, defaultName: String = "alignment", format: AlignmentFormat? = null): List<Seq> {
        val detected = format ?: detectFormat(text)
            ?: throw SeqIOException("Could not recognize an alignment format; expected aligned FASTA, Clustal, Stockholm, or PHYLIP")
        val rows = when (detected) {
            AlignmentFormat.FASTA -> Fasta.parseAll(text, defaultName)
            AlignmentFormat.CLUSTAL -> parseClustal(text, defaultName)
            AlignmentFormat.STOCKHOLM -> parseStockholm(text, defaultName)
            AlignmentFormat.PHYLIP -> parsePhylip(text, defaultName)
        }
        if (rows.isEmpty()) throw SeqIOException("No aligned sequence rows found")
        try {
            validateRows(rows)
        } catch (error: IllegalArgumentException) {
            throw SeqIOException("Alignment rows have different lengths: ${rows.map(Seq::length).distinct().joinToString()}", cause = error)
        }
        return rows
    }

    fun write(sequences: List<Seq>, format: AlignmentFormat, lineWidth: Int = 60): String {
        require(sequences.isNotEmpty()) { "An alignment needs at least one sequence" }
        require(lineWidth > 0) { "Alignment line width must be positive" }
        validateRows(sequences)
        return when (format) {
            AlignmentFormat.FASTA -> Fasta.writeAll(sequences, lineWidth)
            AlignmentFormat.CLUSTAL -> writeClustal(sequences, lineWidth)
            AlignmentFormat.STOCKHOLM -> writeStockholm(sequences, lineWidth)
            AlignmentFormat.PHYLIP -> writePhylip(sequences)
        }
    }

    private fun parseClustal(text: String, defaultName: String): List<Seq> {
        val rows = linkedMapOf<String, StringBuilder>()
        var sawHeader = false
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trimEnd()
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEachIndexed
            if (!sawHeader) {
                if (trimmed.startsWith("CLUSTAL", ignoreCase = true) || trimmed.startsWith("MUSCLE", ignoreCase = true)) {
                    sawHeader = true
                    return@forEachIndexed
                }
                throw SeqIOException("Clustal header expected on line ${index + 1}", line = index + 1)
            }
            // Conservation rows are indented and contain only standard symbols.
            if (line.firstOrNull()?.isWhitespace() == true && trimmed.all { it in "*:. " }) return@forEachIndexed
            val tokens = trimmed.split(Regex("\\s+"))
            if (tokens.size < 2) return@forEachIndexed
            val name = tokens.first().ifBlank { defaultName }
            val fragment = alignmentFragment(tokens[1])
            if (fragment.isNotEmpty()) rows.getOrPut(name) { StringBuilder() }.append(fragment)
        }
        return rows.toSeqs()
    }

    private fun parseStockholm(text: String, defaultName: String): List<Seq> {
        val rows = linkedMapOf<String, StringBuilder>()
        var sawHeader = false
        var ended = false
        text.lineSequence().forEachIndexed { index, raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return@forEachIndexed
            if (!sawHeader) {
                if (trimmed.startsWith("# STOCKHOLM", ignoreCase = true)) {
                    sawHeader = true
                    return@forEachIndexed
                }
                throw SeqIOException("Stockholm header expected on line ${index + 1}", line = index + 1)
            }
            if (trimmed == "//") {
                ended = true
                return@forEachIndexed
            }
            if (trimmed.startsWith("#")) return@forEachIndexed
            val tokens = trimmed.split(Regex("\\s+"))
            if (tokens.size < 2) throw SeqIOException("Stockholm row expected on line ${index + 1}", line = index + 1)
            rows.getOrPut(tokens.first().ifBlank { defaultName }) { StringBuilder() }.append(alignmentFragment(tokens[1]))
        }
        if (!sawHeader) throw SeqIOException("Stockholm header expected")
        if (!ended) throw SeqIOException("Stockholm alignment is missing the terminating // line")
        return rows.toSeqs()
    }

    /** Accepts sequential and common interleaved PHYLIP layouts, including relaxed identifiers. */
    private fun parsePhylip(text: String, defaultName: String): List<Seq> {
        val lines = text.lineSequence().toList()
        val headerIndex = lines.indexOfFirst { it.isNotBlank() }
        if (headerIndex < 0) throw SeqIOException("PHYLIP header expected")
        val header = lines[headerIndex].trim().split(Regex("\\s+"))
        val expectedRows = header.getOrNull(0)?.toIntOrNull()
            ?: throw SeqIOException("PHYLIP sequence count expected on line ${headerIndex + 1}", line = headerIndex + 1)
        val expectedWidth = header.getOrNull(1)?.toIntOrNull()
            ?: throw SeqIOException("PHYLIP alignment width expected on line ${headerIndex + 1}", line = headerIndex + 1)
        require(expectedRows > 0 && expectedWidth >= 0) { "PHYLIP header values must be non-negative" }

        val rows = linkedMapOf<String, StringBuilder>()
        var continuation = 0
        lines.drop(headerIndex + 1).forEachIndexed { offset, raw ->
            val lineNumber = headerIndex + offset + 2
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                continuation = 0
                return@forEachIndexed
            }
            if (rows.size < expectedRows) {
                val (name, fragment) = parsePhylipNamedRow(trimmed, defaultName, lineNumber)
                if (rows.containsKey(name)) throw SeqIOException("Duplicate PHYLIP row '$name' on line $lineNumber", line = lineNumber)
                rows[name] = StringBuilder(fragment)
                return@forEachIndexed
            }
            // In later blocks a row may retain its name, or consist solely of its next fragment.
            val tokens = trimmed.split(Regex("\\s+"))
            val named = tokens.size >= 2 && rows.containsKey(tokens.first())
            val name = if (named) tokens.first() else rows.keys.elementAt(continuation % expectedRows)
            val fragment = alignmentFragment(if (named) tokens.drop(1).joinToString("") else tokens.joinToString(""))
            rows.getValue(name).append(fragment)
            continuation++
        }
        if (rows.size != expectedRows) throw SeqIOException("PHYLIP declares $expectedRows rows but contains ${rows.size}")
        val parsed = rows.toSeqs()
        require(parsed.all { it.length == expectedWidth }) {
            "PHYLIP declares $expectedWidth aligned columns, but parsed ${parsed.map { it.length }.distinct().joinToString()}"
        }
        return parsed
    }

    private fun parsePhylipNamedRow(line: String, defaultName: String, lineNumber: Int): Pair<String, String> {
        val tokens = line.split(Regex("\\s+"))
        if (tokens.size < 2) throw SeqIOException("PHYLIP name and sequence expected on line $lineNumber", line = lineNumber)
        return tokens.first().ifBlank { defaultName } to alignmentFragment(tokens.drop(1).joinToString(""))
    }

    private fun writeClustal(sequences: List<Seq>, lineWidth: Int): String {
        val nameWidth = sequences.maxOf { it.name.length }.coerceAtLeast(8)
        val view = org.instagene.core.MultipleAlignmentResult(org.instagene.core.MultipleAlignmentAlgorithm.BUILTIN, sequences).view()
        return buildString {
            appendLine("CLUSTAL InstaGene multiple sequence alignment")
            appendLine()
            for (offset in sequences.first().bases.indices step lineWidth) {
                val end = minOf(offset + lineWidth, sequences.first().length)
                sequences.forEach { sequence ->
                    append(sequence.name.padEnd(nameWidth)).append("  ").append(sequence.bases.substring(offset, end)).append('\n')
                }
                append(" ".repeat(nameWidth + 2))
                append(view.conservation.subList(offset, end).joinToString("") { conservation -> if (conservation == 1.0) "*" else " " })
                appendLine()
                appendLine()
            }
        }
    }

    private fun writeStockholm(sequences: List<Seq>, lineWidth: Int): String = buildString {
        appendLine("# STOCKHOLM 1.0")
        appendLine("#=GF ID InstaGene_alignment")
        val nameWidth = sequences.maxOf { it.name.length }
        for (offset in sequences.first().bases.indices step lineWidth) {
            val end = minOf(offset + lineWidth, sequences.first().length)
            sequences.forEach { sequence ->
                append(sequence.name.padEnd(nameWidth)).append(' ').append(sequence.bases.substring(offset, end)).append('\n')
            }
            appendLine()
        }
        appendLine("//")
    }

    /** Relaxed PHYLIP keeps full names (spaces become underscores) instead of silently truncating identifiers. */
    private fun writePhylip(sequences: List<Seq>): String = buildString {
        append(sequences.size).append(' ').append(sequences.first().length).append('\n')
        sequences.forEach { sequence ->
            append(sequence.name.replace(Regex("\\s+"), "_")).append(' ').append(sequence.bases).append('\n')
        }
    }

    private fun validateRows(rows: List<Seq>) {
        val widths = rows.map(Seq::length).distinct()
        require(widths.size == 1) { "Alignment rows have different lengths: ${widths.joinToString()}" }
    }

    private fun Map<String, StringBuilder>.toSeqs(): List<Seq> = entries.map { (name, bases) ->
        val normalized = bases.toString().uppercase()
        Seq(name, normalized, Fasta.detectKind(normalized))
    }

    private fun alignmentFragment(value: String): String = value
        .filterNot { it.isWhitespace() || it.isDigit() }
        .replace('.', '-')
        .uppercase()
}
