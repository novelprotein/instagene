package org.instagene.core

/**
 * A concrete SpCas9 target. Coordinates are zero-based and half-open, and
 * [start] and [end] are ordered genomic bounds. [coordinates] retains the
 * exact guide traversal order for origin-spanning circular targets.
 */
data class GuideRNA(
    val sequence: String,
    val strand: Strand,
    val pam: String,
    val start: Int,
    val end: Int,
    val pamStart: Int,
    val pamEnd: Int,
    val gcContent: Double,
    val warnings: List<String> = emptyList(),
    val coordinates: List<Int> = emptyList(),
) {
    /** Kept as a read-only compatibility alias for callers that used the PAM start. */
    @Deprecated("Use pamStart")
    val pamPosition: Int get() = pamStart
}

data class CrisprDesignResult(
    val guides: List<GuideRNA>,
    val warnings: List<String> = emptyList(),
)

object CrisprDesign {
    private const val GUIDE_LEN = 20
    private const val PAM_LEN = 3

    private fun complement(base: Char): Char = when (base) {
        'A' -> 'T'
        'C' -> 'G'
        'G' -> 'C'
        'T' -> 'A'
        else -> base
    }

    private fun reverseComplement(sequence: String): String =
        sequence.reversed().map(::complement).joinToString("")

    private fun circularBase(sequence: String, index: Int): Char =
        sequence[Math.floorMod(index, sequence.length)]

    private fun circularSlice(sequence: String, start: Int, length: Int): String =
        (0 until length).joinToString("") { circularBase(sequence, start + it).toString() }

    private fun guideWarnings(sequence: String, originSpanning: Boolean): List<String> = buildList {
        val gc = sequence.count { it == 'G' || it == 'C' } / GUIDE_LEN.toDouble()
        if (gc < 0.4 || gc > 0.6) add("GC content is outside the typical 40–60% range")
        if (sequence.contains("TTTT")) add("Contains a poly-T run")
        if (originSpanning) add("Guide spans the circular sequence origin")
    }

    fun design(target: Seq, maxGuides: Int = 10): CrisprDesignResult {
        require(maxGuides >= 0) { "maxGuides must not be negative" }
        val sequence = target.bases.uppercase().replace('U', 'T')
        if (sequence.isEmpty() || target.kind == SeqKind.PROTEIN) {
            return CrisprDesignResult(
                emptyList(),
                listOf("SpCas9 design requires a non-empty DNA or RNA sequence"),
            )
        }

        val invalid = sequence.filter { it !in "ACGT" }.toSet()
        val resultWarnings = buildList {
            if (invalid.isNotEmpty()) {
                add("Ambiguous or unsupported bases were ignored while scanning: ${invalid.sorted().joinToString("")}")
            }
            if (!target.isCircular && sequence.length < GUIDE_LEN + PAM_LEN) {
                add("Sequence is shorter than a 20-base guide plus NGG PAM")
            }
        }

        val circular = target.isCircular
        if (circular && sequence.length < GUIDE_LEN + PAM_LEN) {
            return CrisprDesignResult(
                emptyList(),
                resultWarnings + "Circular sequence is shorter than a 20-base guide plus NGG PAM",
            )
        }
        val pamStarts = if (circular) 0 until sequence.length
        else 0..(sequence.length - PAM_LEN).coerceAtLeast(-1)
        val guides = mutableListOf<GuideRNA>()

        for (pamStart in pamStarts) {
            val pam = circularSlice(sequence, pamStart, PAM_LEN).takeIf { it.length == PAM_LEN } ?: continue
            if (!(pam[0] in "ACGT" && pam[1] == 'G' && pam[2] == 'G')) {
                // On the opposite strand, a forward-recorded PAM is CCN.
                if (!(pam[0] == 'C' && pam[1] == 'C' && pam[2] in "ACGT")) continue
                if (!circular && pamStart + GUIDE_LEN + PAM_LEN > sequence.length) continue
                val guideStart = pamStart + PAM_LEN
                val genomicCoordinates = (0 until GUIDE_LEN).map { index ->
                    if (circular) Math.floorMod(guideStart + index, sequence.length) else guideStart + index
                }
                val coordinates = genomicCoordinates.asReversed()
                val protospacer = genomicCoordinates.joinToString("") { sequence[it].toString() }
                val guide = reverseComplement(protospacer)
                if (guide.any { it !in "ACGT" }) continue
                val start = coordinates.minOrNull() ?: continue
                val end = (coordinates.maxOrNull() ?: -1) + 1
                guides += GuideRNA(
                    sequence = guide,
                    strand = Strand.REVERSE,
                    pam = pam,
                    start = start,
                    end = end,
                    pamStart = pamStart,
                    pamEnd = if (circular) Math.floorMod(pamStart + PAM_LEN, sequence.length) else pamStart + PAM_LEN,
                    gcContent = guide.count { it == 'G' || it == 'C' } / GUIDE_LEN.toDouble(),
                    warnings = guideWarnings(guide, circular && genomicCoordinates.zipWithNext().any { it.second != it.first + 1 }),
                    coordinates = coordinates,
                )
                continue
            }
            if (!circular && pamStart - GUIDE_LEN < 0) continue
            val coordinates = (0 until GUIDE_LEN).map { index ->
                if (circular) Math.floorMod(pamStart - GUIDE_LEN + index, sequence.length)
                else pamStart - GUIDE_LEN + index
            }
            val guide = coordinates.joinToString("") { sequence[it].toString() }
            if (guide.any { it !in "ACGT" }) continue
            val start = coordinates.minOrNull() ?: continue
            val end = (coordinates.maxOrNull() ?: -1) + 1
            guides += GuideRNA(
                sequence = guide,
                strand = Strand.FORWARD,
                pam = pam,
                start = start,
                end = end,
                pamStart = pamStart,
                pamEnd = if (circular) Math.floorMod(pamStart + PAM_LEN, sequence.length) else pamStart + PAM_LEN,
                gcContent = guide.count { it == 'G' || it == 'C' } / GUIDE_LEN.toDouble(),
                warnings = guideWarnings(guide, circular && coordinates.zipWithNext().any { it.second != it.first + 1 }),
                coordinates = coordinates,
            )
        }

        val sorted = guides.sortedWith(compareBy<GuideRNA> { it.start }.thenBy { it.end }.thenBy { it.strand.sign })
        return CrisprDesignResult(sorted.take(maxGuides), resultWarnings)
    }
}
