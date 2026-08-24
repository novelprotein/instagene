package org.instagene.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Whether two matching regions have the same or reverse-complement orientation. */
@Serializable
enum class RepeatOrientation { DIRECT, INVERTED }

/** One word-level match rendered in a dot plot; positions are zero-based. */
@Serializable
data class DotPlotPoint(
    val horizontalPosition: Int,
    val verticalPosition: Int,
    val orientation: RepeatOrientation,
)

/** Bounded, deterministic dot-plot data that front ends can render without recomputing matches. */
@Serializable
data class DotPlotResult(
    val horizontalName: String,
    val horizontalLength: Int,
    val verticalName: String,
    val verticalLength: Int,
    val wordSize: Int,
    val points: List<DotPlotPoint>,
    val truncated: Boolean = false,
)

/** A maximal seed-extended pair of matching regions, stored in half-open coordinates. */
@Serializable
data class SequenceRepeat(
    val orientation: RepeatOrientation,
    val firstStart: Int,
    val firstEnd: Int,
    val secondStart: Int,
    val secondEnd: Int,
    val sequence: String,
) {
    val length: Int get() = firstEnd - firstStart
}

/** Direct and inverted repeat calls for one sequence. */
@Serializable
data class RepeatAnalysisResult(
    val sequenceName: String,
    val sequenceLength: Int,
    val minimumLength: Int,
    val repeats: List<SequenceRepeat>,
    val truncated: Boolean = false,
) {
    val directRepeats: List<SequenceRepeat> get() = repeats.filter { it.orientation == RepeatOrientation.DIRECT }
    val invertedRepeats: List<SequenceRepeat> get() = repeats.filter { it.orientation == RepeatOrientation.INVERTED }
}

/**
 * Fast, local dot-plot and repeat analysis. Both algorithms index fixed-length
 * words, then extend only matching seeds, giving predictable bounded output in
 * contrast to a dense all-by-all comparison matrix.
 */
object RepeatAnalysis {
    private val exportJson = Json { prettyPrint = true; encodeDefaults = true }

    fun dotPlot(
        horizontal: Seq,
        vertical: Seq = horizontal,
        wordSize: Int = 11,
        includeInverted: Boolean = false,
        maxPoints: Int = 20_000,
    ): DotPlotResult {
        require(wordSize > 0) { "Dot-plot word size must be positive" }
        require(maxPoints > 0) { "Dot-plot maximum point count must be positive" }
        if (includeInverted) {
            require(horizontal.kind != SeqKind.PROTEIN && vertical.kind != SeqKind.PROTEIN) {
                "Inverted dot plots require nucleotide sequences"
            }
        }
        val h = horizontal.bases.uppercase()
        val v = vertical.bases.uppercase()
        if (h.length < wordSize || v.length < wordSize) {
            return DotPlotResult(horizontal.name, h.length, vertical.name, v.length, wordSize, emptyList())
        }
        val directIndex = wordIndex(v, wordSize)
        val reverseIndex = if (includeInverted) wordIndex(Alphabet.reverseComplement(v), wordSize) else emptyMap()
        val points = ArrayList<DotPlotPoint>(minOf(maxPoints, 4_096))
        var truncated = false

        fun add(point: DotPlotPoint): Boolean {
            if (points.size >= maxPoints) {
                truncated = true
                return false
            }
            points += point
            return true
        }

        outer@ for (x in 0..h.length - wordSize) {
            val word = h.substring(x, x + wordSize)
            for (y in directIndex[word].orEmpty()) {
                if (!add(DotPlotPoint(x, y, RepeatOrientation.DIRECT))) break@outer
            }
            if (includeInverted) {
                for (reversePosition in reverseIndex[word].orEmpty()) {
                    // A word at r in reverseComplement(v) maps to this original v coordinate.
                    val y = v.length - reversePosition - wordSize
                    if (!add(DotPlotPoint(x, y, RepeatOrientation.INVERTED))) break@outer
                }
            }
        }
        return DotPlotResult(horizontal.name, h.length, vertical.name, v.length, wordSize, points, truncated)
    }

    fun findRepeats(
        sequence: Seq,
        minimumLength: Int = 12,
        maxResults: Int = 2_000,
        includeDirect: Boolean = true,
        includeInverted: Boolean = true,
    ): RepeatAnalysisResult {
        require(minimumLength > 0) { "Minimum repeat length must be positive" }
        require(maxResults > 0) { "Maximum repeat count must be positive" }
        require(includeDirect || includeInverted) { "Choose direct repeats, inverted repeats, or both" }
        if (includeInverted) require(sequence.kind != SeqKind.PROTEIN) { "Inverted-repeat analysis requires a nucleotide sequence" }
        val bases = sequence.bases.uppercase()
        if (bases.length < minimumLength) {
            return RepeatAnalysisResult(sequence.name, bases.length, minimumLength, emptyList())
        }

        val calls = ArrayList<SequenceRepeat>(minOf(maxResults, 512))
        val seen = linkedSetOf<String>()
        var truncated = false
        fun add(call: SequenceRepeat): Boolean {
            val key = "${call.orientation}:${call.firstStart}:${call.firstEnd}:${call.secondStart}:${call.secondEnd}"
            if (!seen.add(key)) return true
            if (calls.size >= maxResults) {
                truncated = true
                return false
            }
            calls += call
            return true
        }

        if (includeDirect) {
            val previous = HashMap<String, MutableList<Int>>()
            direct@ for (secondStart in 0..bases.length - minimumLength) {
                val seed = bases.substring(secondStart, secondStart + minimumLength)
                for (firstStart in previous[seed].orEmpty()) {
                    val call = extendDirect(bases, firstStart, secondStart, minimumLength)
                    if (!add(call)) break@direct
                }
                previous.getOrPut(seed) { mutableListOf() } += secondStart
            }
        }

        if (includeInverted && !truncated) {
            val reverse = Alphabet.reverseComplement(bases)
            val reverseIndex = wordIndex(reverse, minimumLength)
            inverted@ for (firstStart in 0..bases.length - minimumLength) {
                val seed = bases.substring(firstStart, firstStart + minimumLength)
                for (reverseStart in reverseIndex[seed].orEmpty()) {
                    val secondStart = bases.length - reverseStart - minimumLength
                    if (firstStart >= secondStart) continue
                    val call = extendInverted(sequence.kind, bases, firstStart, secondStart, minimumLength)
                    if (!add(call)) break@inverted
                }
            }
        }

        return RepeatAnalysisResult(
            sequence.name,
            bases.length,
            minimumLength,
            calls.sortedWith(compareBy<SequenceRepeat>({ it.orientation.ordinal }, { it.firstStart }, { it.secondStart }, { -it.length })),
            truncated,
        )
    }

    fun dotPlotTsv(result: DotPlotResult): String = buildString {
        appendLine("horizontal_name\tvertical_name\tword_size\torientation\thorizontal_position\tvertical_position")
        result.points.forEach { point ->
            appendLine(
                listOf(
                    result.horizontalName,
                    result.verticalName,
                    result.wordSize,
                    point.orientation.name,
                    point.horizontalPosition + 1,
                    point.verticalPosition + 1,
                ).joinToString("\t"),
            )
        }
    }

    fun dotPlotJson(result: DotPlotResult): String = exportJson.encodeToString(result)

    /** Standalone SVG using direct matches in blue and inverted matches in magenta. */
    fun dotPlotSvg(result: DotPlotResult, width: Int = 900, height: Int = 900): String {
        require(width >= 160 && height >= 160) { "Dot-plot SVG dimensions must be at least 160 px" }
        val margin = 58.0
        val plotWidth = width - margin * 1.5
        val plotHeight = height - margin * 1.5
        fun x(position: Int): Double = margin + position.toDouble() / result.horizontalLength.coerceAtLeast(1) * plotWidth
        fun y(position: Int): Double = margin + position.toDouble() / result.verticalLength.coerceAtLeast(1) * plotHeight
        return buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$width\" height=\"$height\" viewBox=\"0 0 $width $height\">")
            appendLine("<rect width=\"100%\" height=\"100%\" fill=\"white\"/>")
            appendLine("<text x=\"$margin\" y=\"25\" font-family=\"sans-serif\" font-size=\"16\">Dot plot: ${escape(result.horizontalName)} × ${escape(result.verticalName)}</text>")
            appendLine("<rect x=\"$margin\" y=\"$margin\" width=\"$plotWidth\" height=\"$plotHeight\" fill=\"#fafafa\" stroke=\"#455a64\"/>")
            appendLine("<text x=\"${margin + plotWidth / 2}\" y=\"${height - 12}\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"12\">${escape(result.horizontalName)} (${result.horizontalLength})</text>")
            appendLine("<text x=\"14\" y=\"${margin + plotHeight / 2}\" transform=\"rotate(-90 14 ${margin + plotHeight / 2})\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"12\">${escape(result.verticalName)} (${result.verticalLength})</text>")
            result.points.forEach { point ->
                val color = if (point.orientation == RepeatOrientation.DIRECT) "#1565c0" else "#ad1457"
                appendLine("<circle cx=\"${format(x(point.horizontalPosition))}\" cy=\"${format(y(point.verticalPosition))}\" r=\"1.2\" fill=\"$color\"/>")
            }
            appendLine("<text x=\"$margin\" y=\"${height - 30}\" font-family=\"sans-serif\" font-size=\"11\" fill=\"#1565c0\">● direct</text>")
            appendLine("<text x=\"${margin + 85}\" y=\"${height - 30}\" font-family=\"sans-serif\" font-size=\"11\" fill=\"#ad1457\">● inverted</text>")
            if (result.truncated) appendLine("<text x=\"${margin + 190}\" y=\"${height - 30}\" font-family=\"sans-serif\" font-size=\"11\" fill=\"#b71c1c\">output capped at ${result.points.size} points</text>")
            appendLine("</svg>")
        }
    }

    fun repeatsTsv(result: RepeatAnalysisResult): String = buildString {
        appendLine("orientation\tfirst_start\tfirst_end\tsecond_start\tsecond_end\tlength\tsequence")
        result.repeats.forEach { repeat ->
            appendLine(
                listOf(
                    repeat.orientation.name,
                    repeat.firstStart + 1,
                    repeat.firstEnd,
                    repeat.secondStart + 1,
                    repeat.secondEnd,
                    repeat.length,
                    repeat.sequence,
                ).joinToString("\t"),
            )
        }
    }

    fun repeatsJson(result: RepeatAnalysisResult): String = exportJson.encodeToString(result)

    private fun wordIndex(bases: String, wordSize: Int): Map<String, List<Int>> {
        if (bases.length < wordSize) return emptyMap()
        val index = HashMap<String, MutableList<Int>>()
        for (position in 0..bases.length - wordSize) {
            index.getOrPut(bases.substring(position, position + wordSize)) { mutableListOf() } += position
        }
        return index
    }

    private fun extendDirect(bases: String, first: Int, second: Int, seedLength: Int): SequenceRepeat {
        var firstStart = first
        var secondStart = second
        var length = seedLength
        while (firstStart > 0 && secondStart > 0 && bases[firstStart - 1] == bases[secondStart - 1]) {
            firstStart--
            secondStart--
            length++
        }
        while (firstStart + length < bases.length && secondStart + length < bases.length &&
            bases[firstStart + length] == bases[secondStart + length]
        ) length++
        return SequenceRepeat(
            RepeatOrientation.DIRECT,
            firstStart,
            firstStart + length,
            secondStart,
            secondStart + length,
            bases.substring(firstStart, firstStart + length),
        )
    }

    private fun extendInverted(kind: SeqKind, bases: String, first: Int, second: Int, seedLength: Int): SequenceRepeat {
        var firstStart = first
        var secondStart = second
        var length = seedLength
        // Extending left in the first copy means extending right in its reverse-complement partner.
        while (firstStart > 0 && secondStart + length < bases.length &&
            bases[firstStart - 1] == Alphabet.complement(bases[secondStart + length], kind)
        ) {
            firstStart--
            length++
        }
        // Extending right in the first copy means extending left in the partner.
        while (firstStart + length < bases.length && secondStart > 0 &&
            bases[firstStart + length] == Alphabet.complement(bases[secondStart - 1], kind)
        ) {
            secondStart--
            length++
        }
        return SequenceRepeat(
            RepeatOrientation.INVERTED,
            firstStart,
            firstStart + length,
            secondStart,
            secondStart + length,
            bases.substring(firstStart, firstStart + length),
        )
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun format(value: Double): String = "%.2f".format(java.util.Locale.ROOT, value)
}
