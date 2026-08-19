package org.instagene.core

/** Raised when parts cannot be joined, with an explanation a bench scientist can act on. */
class AssemblyException(message: String) : Exception(message)

/** Ligation, Gibson assembly and the one-shot "cut and paste" plasmid builder. */
object Assembly {

    /** Signed distance between the top and bottom cuts that produced this end. */
    private fun StickyEnd.signedOverhang(): Int = when (type) {
        EndType.BLUNT -> 0
        EndType.FIVE_PRIME_OVERHANG -> overhang.length
        EndType.THREE_PRIME_OVERHANG -> -overhang.length
    }

    private fun revComp(s: String): String = Alphabet.reverseComplement(s)

    /**
     * Flips a fragment end-for-end. The molecule is unchanged; it is simply read
     * from the other strand, so the ends swap and their overhangs are complemented.
     */
    fun reverseComplement(f: Fragment): Fragment {
        val dL = f.leftEnd.signedOverhang()
        val dR = f.rightEnd.signedOverhang()
        val core = f.bases.substring(
            if (dL > 0) dL else 0,
            f.bases.length - if (dR < 0) -dR else 0,
        )
        val leftPart = if (dL < 0) f.leftEnd.overhang else ""
        val rightPart = if (dR > 0) f.rightEnd.overhang else ""
        val flipped = revComp(leftPart + core + rightPart)
        // A base at position p in the original top strand sits at p - dL in `leftPart + core + rightPart`.
        val mirrored = f.features.mapNotNull {
            val start = flipped.length - (it.end - dL)
            val end = flipped.length - (it.start - dL)
            if (start < 0 || end > flipped.length || end <= start) null
            else it.copy(start = start, end = end, strand = it.strand.flipped())
        }
        return Fragment(
            bases = flipped,
            leftEnd = f.rightEnd.copy(overhang = revComp(f.rightEnd.overhang)),
            rightEnd = f.leftEnd.copy(overhang = revComp(f.leftEnd.overhang)),
            sourceName = f.sourceName + " (rc)",
            start = 0,
            features = mirrored.sortedBy { it.start },
        )
    }

    /** True when [a]'s right end can anneal to [b]'s left end. */
    fun canLigate(a: Fragment, b: Fragment): Boolean = a.rightEnd.isCompatibleWith(b.leftEnd)

    /** Joins two fragments 5'->3'. Throws when their ends are incompatible. */
    fun ligate(a: Fragment, b: Fragment): Fragment {
        if (!canLigate(a, b)) {
            throw AssemblyException(
                "Incompatible ends: ${a.sourceName} ends with [${a.rightEnd}] but " +
                    "${b.sourceName} starts with [${b.leftEnd}]"
            )
        }
        val shifted = b.features.map { it.copy(start = it.start + a.length, end = it.end + a.length) }
        return Fragment(
            bases = a.bases + b.bases,
            leftEnd = a.leftEnd,
            rightEnd = b.rightEnd,
            sourceName = "${a.sourceName}+${b.sourceName}",
            start = 0,
            features = a.features + shifted,
        )
    }

    /** Ligates a list of fragments in order. */
    fun ligate(fragments: List<Fragment>): Fragment {
        require(fragments.isNotEmpty()) { "Nothing to ligate" }
        return fragments.reduce { acc, f -> ligate(acc, f) }
    }

    /** Closes a fragment into a circle. Its two ends must be compatible. */
    fun circularize(f: Fragment, name: String = f.sourceName): Seq {
        if (!f.rightEnd.isCompatibleWith(f.leftEnd)) {
            throw AssemblyException(
                "Cannot circularise ${f.sourceName}: [${f.rightEnd}] will not anneal to [${f.leftEnd}]"
            )
        }
        return Seq(name, f.bases, SeqKind.DNA, Topology.CIRCULAR, f.features.sortedBy { it.start })
    }

    // ------------------------------------------------------------ plasmid builder

    /** The completed [plasmid], its vector and insert fragments, and the progress log. */
    data class BuildResult(
        val plasmid: Seq,
        val vectorFragment: Fragment,
        val insertFragment: Fragment,
        val insertWasFlipped: Boolean,
        val log: List<String>,
    )

    /**
     * The everyday subcloning workflow: cut backbone and insert with the same
     * pair of enzymes, drop the stuffer, ligate the insert into the vector and
     * close the circle.
     *
     * The largest backbone fragment is taken as the vector; the insert fragment
     * is whichever piece has ends matching it, trying both orientations.
     */
    fun buildPlasmid(
        backbone: Seq,
        insert: Seq,
        enzymes: List<Enzyme>,
        name: String = "${backbone.name}_${insert.name}",
        insertFeatureName: String = insert.name,
    ): BuildResult {
        require(enzymes.isNotEmpty()) { "Choose at least one enzyme" }
        val log = ArrayList<String>()

        val backboneFragments = Digest.digest(backbone, enzymes)
        if (backboneFragments.size < 2 && !backbone.isCircular) {
            throw AssemblyException(
                "${enzymes.joinToString("/") { it.name }} does not cut the backbone '${backbone.name}'"
            )
        }
        val vector = backboneFragments.maxByOrNull { it.length }
            ?: throw AssemblyException("Backbone '${backbone.name}' produced no fragments")
        log += "Backbone cut into ${backboneFragments.size} fragment(s); " +
            "vector = ${vector.length} bp [${vector.leftEnd}] .. [${vector.rightEnd}]"

        val insertFragments = Digest.digest(insert, enzymes)
        log += "Insert cut into ${insertFragments.size} fragment(s)"

        // Prefer the largest compatible piece, in either orientation.
        data class Candidate(val fragment: Fragment, val flipped: Boolean)

        val candidates = insertFragments.flatMap {
            listOf(Candidate(it, false), Candidate(reverseComplement(it), true))
        }.filter { canLigate(vector, it.fragment) && canLigate(it.fragment, vector) }

        val chosen = candidates.maxByOrNull { it.fragment.length }
            ?: throw AssemblyException(
                buildString {
                    append("No fragment of '${insert.name}' has ends compatible with the vector.\n")
                    append("Vector needs [${vector.rightEnd}] ... [${vector.leftEnd}].\n")
                    append("Available insert fragments:\n")
                    insertFragments.forEach { append("  - $it\n") }
                    append("Hint: cut the insert with the same enzymes as the backbone.")
                }
            )

        if (chosen.flipped) log += "Insert used in reverse orientation"
        log += "Insert = ${chosen.fragment.length} bp [${chosen.fragment.leftEnd}] .. [${chosen.fragment.rightEnd}]"

        val insertStart = vector.length
        val joined = ligate(vector, chosen.fragment)
        val annotated = joined.copy(
            features = joined.features + Feature(
                name = insertFeatureName,
                type = "misc_feature",
                start = insertStart,
                end = insertStart + chosen.fragment.length,
                strand = if (chosen.flipped) Strand.REVERSE else Strand.FORWARD,
                notes = "Inserted with ${enzymes.joinToString("/") { it.name }}",
            )
        )
        val plasmid = circularize(annotated, name)
        log += "Closed circle: ${plasmid.length} bp"

        return BuildResult(plasmid, vector, chosen.fragment, chosen.flipped, log)
    }

    // ------------------------------------------------------------ Gibson assembly

    /** The assembled [product], the overlap length at each junction, and the progress log. */
    data class GibsonResult(val product: Seq, val overlaps: List<Int>, val log: List<String>)

    /**
     * Overlap-directed (Gibson / HiFi) assembly: parts are joined wherever the
     * 3' end of one is identical to the 5' end of the next, then the product is
     * circularised if its own ends overlap too.
     */
    fun gibson(
        parts: List<Seq>,
        minOverlap: Int = 15,
        name: String = "gibson_assembly",
        circular: Boolean = true,
    ): GibsonResult {
        require(parts.size >= 2) { "Gibson assembly needs at least two parts" }
        val log = ArrayList<String>()
        val overlaps = ArrayList<Int>()

        var acc = parts.first()
        for (i in 1 until parts.size) {
            val next = parts[i]
            val overlap = findOverlap(acc.bases, next.bases, minOverlap)
                ?: throw AssemblyException(
                    "'${acc.name}' and '${next.name}' share no terminal overlap of at least " +
                        "$minOverlap bp. Add homology arms to your primers."
                )
            overlaps += overlap
            log += "Joined '${next.name}' on a $overlap bp overlap"
            val shifted = next.features.map {
                it.copy(start = it.start + acc.length - overlap, end = it.end + acc.length - overlap)
            }
            acc = acc.copy(
                bases = acc.bases + next.bases.substring(overlap),
                features = acc.features + shifted,
            )
        }

        if (!circular) {
            log += "Linear product: ${acc.length} bp"
            return GibsonResult(acc.copy(name = name), overlaps, log)
        }

        val closing = findOverlap(acc.bases, acc.bases, minOverlap)
            ?: throw AssemblyException(
                "The assembled product cannot circularise: its ends share no overlap of at " +
                    "least $minOverlap bp. Use --linear, or add homology between the first and last part."
            )
        overlaps += closing
        val circularBases = acc.bases.substring(0, acc.length - closing)
        log += "Circularised on a $closing bp overlap: ${circularBases.length} bp"
        val product = acc.copy(
            name = name,
            bases = circularBases,
            topology = Topology.CIRCULAR,
            features = acc.features.filter { it.end <= circularBases.length },
        )
        return GibsonResult(product, overlaps, log)
    }

    /** Longest suffix of [a] that is also a prefix of [b], at least [minOverlap] long. */
    fun findOverlap(a: String, b: String, minOverlap: Int): Int? {
        val max = minOf(a.length, b.length) - if (a == b) 1 else 0
        for (len in max downTo minOverlap) {
            if (len <= 0) break
            if (a.regionMatches(a.length - len, b, 0, len, ignoreCase = true)) return len
        }
        return null
    }
}
