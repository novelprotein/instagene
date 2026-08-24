@file:Suppress("DuplicatedCode")

package org.instagene.core

import kotlinx.serialization.Serializable

/** The kind of alphabet a [Seq] uses. */
enum class SeqKind { DNA, RNA, PROTEIN }

/** Whether a molecule is an open line or a closed circle. */
enum class Topology { LINEAR, CIRCULAR }

/** Whether a nucleic-acid molecule is represented as one or two strands. */
enum class Strandedness { SINGLE, DOUBLE }

/** Persisted molecule chemistry used to validate restriction and cloning workflows. */
@Serializable
data class MoleculeProperties(
    val strandedness: Strandedness = Strandedness.DOUBLE,
    val damMethylated: Boolean = false,
    val dcmMethylated: Boolean = false,
    val cpgMethylated: Boolean = false,
    val fivePrimePhosphorylated: Boolean = true,
    val threePrimePhosphorylated: Boolean = false,
)


/** Which strand of the double helix a coordinate, cut or feature refers to. */

enum class Strand(val sign: Int, val symbol: String) {
    FORWARD(1, "+"),
    REVERSE(-1, "-");

    /** The opposite strand. */
    fun flipped(): Strand = if (this == FORWARD) REVERSE else FORWARD
}

/** One contiguous span of a possibly discontinuous annotation. */
@Serializable
data class FeatureSegment(val start: Int, val end: Int) {
    init {
        require(start >= 0) { "Feature segment starts before position 0" }
        require(end >= start) { "Feature segment ends before it starts" }
    }
}

/**
 * An annotated region, in half-open 0-based coordinates: `[start, end)`.
 *
 * Features never wrap the origin; a region that would wrap is stored as two
 * features sharing a name (which is how GenBank `join()` records wrap around).
 *
 * [name] and [notes] are conveniences derived when reading GenBank (from the
 * `label`/`gene`/`product`/`note` qualifiers); the full qualifier table as it
 * appeared in the flat file lives in [qualifiers], keyed by qualifier name with
 * one entry per occurrence (bare flags such as `/pseudo` hold an empty value).
 */
@Serializable
data class Feature(
    val name: String,
    val type: String = "misc_feature",
    val start: Int,
    val end: Int,
    val strand: Strand = Strand.FORWARD,
    val notes: String = "",
    val qualifiers: Map<String, List<String>> = emptyMap(),
    /** Optional discontinuous spans; empty means the legacy [start]..[end] span. */
    val segments: List<FeatureSegment> = emptyList(),
    /** Optional display color in #RRGGBB form. */
    val color: String? = null,
    /** Whether the annotation is shown without deleting it from the record. */
    val visible: Boolean = true,
    /** Stable drawing priority; higher values are drawn in front. */
    val displayOrder: Int = 0,
    /** NCBI genetic-code table used for translating this feature. */
    val geneticCodeId: Int = 1,
    /** Displayed amino-acid number assigned to the first translated residue. */
    val translationNumberingStart: Int = 1,
    /** Number of leading bases skipped before translation (GenBank codon_start - 1). */
    val translationStartOffset: Int = 0,
    /** Optional signed base shift at a programmed ribosomal-slippage position. */
    val ribosomalSlippage: Int = 0,
) {
    /** Span in bases: [end] - [start]. */
    val length: Int get() = end - start

    /** 1-based inclusive coordinates, the convention biologists actually read. */
    fun displayRange(): String = locationSegments.joinToString(",") { "${it.start + 1}..${it.end}" }

    /** All spans in biological order, including the legacy contiguous span. */
    val locationSegments: List<FeatureSegment>
        get() = segments.ifEmpty { listOf(FeatureSegment(start, end)) }

    init {
        require(start >= 0) { "Feature '$name' starts before position 0" }
        require(end >= start) { "Feature '$name' ends before it starts" }
        require(segments.all { it.end >= it.start }) { "Feature '$name' has an invalid segment" }
        require(segments.isEmpty() || (segments.minOf { it.start } == start && segments.maxOf { it.end } == end)) {
            "Feature '$name' bounding coordinates do not contain its segments"
        }
    }
}

/** A primer bound to a nucleotide sequence, with any non-hybridizing 5' extension retained. */
@Serializable
data class PrimerAnnotation(
    val name: String,
    val bases: String,
    val bindingStart: Int,
    val bindingEnd: Int,
    val strand: Strand = Strand.FORWARD,
    val extension: String = "",
    val description: String = "",
    val visible: Boolean = true,
) {
    val fullSequence: String get() = extension + bases
    val length: Int get() = fullSequence.length

    init {
        require(bindingStart >= 0) { "Primer '$name' starts before position 0" }
        require(bindingEnd >= bindingStart) { "Primer '$name' ends before it starts" }
    }
}

/** One reconstructable scientific operation that produced or changed a sequence. */
@Serializable
data class ProcedureRecord(
    val operation: String,
    val summary: String,
    val inputs: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val timestamp: Long = 0L,
)

/**
 * An immutable nucleotide (or protein) sequence with annotations.
 *
 * Every editing operation returns a new [Seq] and carries the feature table
 * along, shifting and clipping coordinates so annotations survive edits.
 *
 * [metadata] holds the record-level fields that a flat file such as GenBank
 * carries beyond the molecule itself (accession, version, source organism,
 * comment, date, division, molecule type, ...), keyed by field name. It is
 * free-form so round-tripping a file never drops fields the model does not
 * otherwise know about.
 */
@Serializable
data class Seq(
    val name: String = "unnamed",
    val bases: String = "",
    val kind: SeqKind = SeqKind.DNA,
    val topology: Topology = Topology.LINEAR,
    val features: List<Feature> = emptyList(),
    val description: String = "",
    val metadata: Map<String, String> = emptyMap(),
    val primers: List<PrimerAnnotation> = emptyList(),
    val molecule: MoleculeProperties = MoleculeProperties(
        strandedness = if (kind == SeqKind.PROTEIN) Strandedness.SINGLE else Strandedness.DOUBLE,
    ),
    val provenance: List<ProcedureRecord> = emptyList(),
) {
    val length: Int get() = bases.length

    val isCircular: Boolean get() = topology == Topology.CIRCULAR

    /** A persisted sequence identity, when one has been applied. */
    val uniqueIdentifier: String? get() = metadata["CDSEGUID"]?.takeIf { it.isNotBlank() }

    fun withUniqueIdentifier(value: String?): Seq = copy(
        metadata = if (value.isNullOrBlank()) metadata - "CDSEGUID" else metadata + ("CDSEGUID" to value)
    )

    /** Base at [index], wrapping around the origin when the sequence is circular. */
    fun baseAt(index: Int): Char {
        require(length > 0) { "Cannot index into an empty sequence" }
        val i = if (isCircular) Math.floorMod(index, length) else index
        return bases[i]
    }

    /**
     * Region `[start, end)`. For circular sequences the range may exceed the
     * length or start negative, and wraps around the origin.
     */
    fun sub(start: Int, end: Int): String {
        require(end >= start) { "end ($end) must not precede start ($start)" }
        if (!isCircular) return bases.substring(start.coerceIn(0, length), end.coerceIn(0, length))
        if (length == 0) return ""
        val sb = StringBuilder(end - start)
        for (i in start until end) sb.append(baseAt(i))
        return sb.toString()
    }

    /** A copy named [newName], with everything else unchanged. */
    fun withName(newName: String): Seq = copy(name = newName)

    /** A copy with topology [newTopology], with everything else unchanged. */
    fun withTopology(newTopology: Topology): Seq = copy(topology = newTopology)

    /** A copy with [feature] added to the feature table, kept sorted by start. */
    fun withFeature(feature: Feature): Seq = copy(features = (features + feature).sortedBy { it.start })

    /** A copy with [primer] persisted and sorted by its binding coordinate. */
    fun withPrimer(primer: PrimerAnnotation): Seq =
        copy(primers = (primers + primer).sortedBy { it.bindingStart })

    /** A copy with [feature] removed from the feature table (by structural equality). */
    fun withoutFeature(feature: Feature): Seq = copy(features = features - feature)

    /** Appends a reconstructable operation to this sequence's embedded provenance. */
    fun withProcedure(record: ProcedureRecord): Seq = copy(provenance = provenance + record)

    /** Inserts [insert] before position [at], shifting downstream features. */
    fun insertAt(at: Int, insert: String): Seq {
        require(at in 0..length) { "Insert position $at is outside 0..$length" }
        val added = insert.length
        val moved = features.map { f ->
            when {
                f.end <= at -> f                                              // entirely upstream
                f.start >= at -> f.copy(start = f.start + added, end = f.end + added)
                else -> f.copy(end = f.end + added)                           // insertion lands inside
            }
        }
        val movedPrimers = primers.map { p ->
            when {
                p.bindingEnd <= at -> p
                p.bindingStart >= at -> p.copy(bindingStart = p.bindingStart + added, bindingEnd = p.bindingEnd + added)
                else -> p.copy(bindingEnd = p.bindingEnd + added)
            }
        }
        return copy(
            bases = bases.substring(0, at) + insert + bases.substring(at),
            features = moved,
            primers = movedPrimers,
        )
    }

    /** Deletes `[start, end)`, shifting and clipping features. */
    fun deleteRange(start: Int, end: Int): Seq {
        val (s, e) = normalizeRange(start, end)
        val removed = e - s
        if (removed == 0) return this
        val moved = features.mapNotNull { f -> clipAfterDeletion(f, s, e, removed) }
        val movedPrimers = primers.mapNotNull { p -> clipPrimerAfterDeletion(p, s, e, removed) }
        return copy(
            bases = bases.substring(0, s) + bases.substring(e),
            features = moved,
            primers = movedPrimers,
        )
    }

    /** Replaces `[start, end)` with [replacement]. */
    fun replaceRange(start: Int, end: Int, replacement: String): Seq {
        val (s, e) = normalizeRange(start, end)
        return deleteRange(s, e).insertAt(s, replacement)
    }

    /** Extracts `[start, end)` as a standalone linear sequence, keeping contained features. */
    fun subSeq(start: Int, end: Int, newName: String = "$name[${start + 1}..$end]"): Seq {
        val slice = sub(start, end)
        val kept = features.mapNotNull { f ->
            val s = f.start - start
            val e = f.end - start
            if (e <= 0 || s >= slice.length) null
            else f.copy(start = s.coerceAtLeast(0), end = e.coerceAtMost(slice.length))
        }
        val keptPrimers = primers.mapNotNull { p ->
            val s = p.bindingStart - start
            val e = p.bindingEnd - start
            if (e <= 0 || s >= slice.length) null
            else p.copy(bindingStart = s.coerceAtLeast(0), bindingEnd = e.coerceAtMost(slice.length))
        }
        return copy(
            name = newName,
            bases = slice,
            topology = Topology.LINEAR,
            features = kept,
            primers = keptPrimers,
        )
    }

    /**
     * Rotates a circular sequence so that position [newOrigin] becomes position 0.
     * Features crossing the new origin are represented as two spans, matching
     * the GenBank convention for origin-wrapping annotations.
     */
    fun rotateOrigin(newOrigin: Int): Seq {
        require(isCircular) { "Only circular sequences have a movable origin" }
        if (length == 0) return this
        val o = Math.floorMod(newOrigin, length)
        if (o == 0) return this
        val rotated = bases.substring(o) + bases.substring(0, o)
        val moved = features.flatMap { f ->
            val s = f.start - o
            val e = f.end - o
            when {
                s >= 0 -> listOf(f.copy(start = s, end = e))
                e <= 0 -> listOf(f.copy(start = s + length, end = e + length))
                else -> listOf(
                    f.copy(start = 0, end = e),
                    f.copy(start = s + length, end = length),
                )
            }
        }
        val movedPrimers = primers.map { p ->
            val span = p.bindingEnd - p.bindingStart
            val start = Math.floorMod(p.bindingStart - o, length)
            p.copy(bindingStart = start, bindingEnd = (start + span).coerceAtMost(length))
        }.sortedBy { it.bindingStart }
        return copy(bases = rotated, features = moved.sortedBy { it.start }, primers = movedPrimers)
    }

    /** Reverse complement; features are mirrored and their strands flipped. */
    fun reverseComplement(newName: String = name): Seq {
        val rc = buildString(length) {
            for (i in bases.indices.reversed()) append(Alphabet.complement(bases[i], kind))
        }
        val mirrored = features.map { f ->
            f.copy(start = length - f.end, end = length - f.start, strand = f.strand.flipped())
        }.sortedBy { it.start }
        val mirroredPrimers = primers.map { p ->
            p.copy(
                bindingStart = length - p.bindingEnd,
                bindingEnd = length - p.bindingStart,
                strand = p.strand.flipped(),
            )
        }.sortedBy { it.bindingStart }
        return copy(name = newName, bases = rc, features = mirrored, primers = mirroredPrimers)
    }

    /** Plain complement, without reversing. */
    fun complement(): Seq = copy(
        bases = buildString(length) { for (c in bases) append(Alphabet.complement(c, kind)) }
    )

    /** Joins another sequence onto the 3' end; both must be linear. */
    operator fun plus(other: Seq): Seq {
        require(!isCircular && !other.isCircular) { "Cannot concatenate circular sequences" }
        val shifted = other.features.map { it.copy(start = it.start + length, end = it.end + length) }
        val shiftedPrimers = other.primers.map {
            it.copy(bindingStart = it.bindingStart + length, bindingEnd = it.bindingEnd + length)
        }
        return copy(
            bases = bases + other.bases,
            features = features + shifted,
            primers = primers + shiftedPrimers,
            provenance = provenance + other.provenance,
        )
    }

    private fun normalizeRange(start: Int, end: Int): Pair<Int, Int> {
        val s = start.coerceIn(0, length)
        val e = end.coerceIn(0, length)
        require(e >= s) { "end ($end) must not precede start ($start)" }
        return s to e
    }

    private fun clipAfterDeletion(f: Feature, s: Int, e: Int, removed: Int): Feature? = when {
        f.end <= s -> f                                                        // upstream of the cut
        f.start >= e -> f.copy(start = f.start - removed, end = f.end - removed)
        f.start >= s && f.end <= e -> null                                     // fully deleted
        else -> {
            val newStart = if (f.start < s) f.start else s
            val newEnd = (if (f.end > e) f.end - removed else s).coerceAtLeast(newStart)
            if (newEnd <= newStart) null else f.copy(start = newStart, end = newEnd)
        }
    }

    private fun clipPrimerAfterDeletion(p: PrimerAnnotation, s: Int, e: Int, removed: Int): PrimerAnnotation? = when {
        p.bindingEnd <= s -> p
        p.bindingStart >= e -> p.copy(bindingStart = p.bindingStart - removed, bindingEnd = p.bindingEnd - removed)
        p.bindingStart >= s && p.bindingEnd <= e -> null
        else -> {
            val newStart = if (p.bindingStart < s) p.bindingStart else s
            val newEnd = (if (p.bindingEnd > e) p.bindingEnd - removed else s).coerceAtLeast(newStart)
            if (newEnd <= newStart) null else p.copy(bindingStart = newStart, bindingEnd = newEnd)
        }
    }

    override fun toString(): String =
        "$name (${length} bp, ${kind.name.lowercase()}, ${topology.name.lowercase()})"
}
