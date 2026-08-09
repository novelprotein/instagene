package org.instagene.core

import kotlinx.serialization.Serializable

/** The kind of alphabet a [Seq] uses. */
enum class SeqKind { DNA, RNA, PROTEIN }

/** Whether a molecule is an open line or a closed circle. */
enum class Topology { LINEAR, CIRCULAR }

/** Which strand of the double helix a coordinate, cut or feature refers to. */
enum class Strand(val sign: Int, val symbol: String) {
    FORWARD(1, "+"),
    REVERSE(-1, "-");

    /** The opposite strand. */
    fun flipped(): Strand = if (this == FORWARD) REVERSE else FORWARD
}

/**
 * An annotated region, in half-open 0-based coordinates: `[start, end)`.
 *
 * Features never wrap the origin; a region that would wrap is stored as two
 * features sharing a name (which is how GenBank `join()` records wrap around).
 */
@Serializable
data class Feature(
    val name: String,
    val type: String = "misc_feature",
    val start: Int,
    val end: Int,
    val strand: Strand = Strand.FORWARD,
    val notes: String = "",
) {
    /** Span in bases: [end] - [start]. */
    val length: Int get() = end - start

    /** 1-based inclusive coordinates, the convention biologists actually read. */
    fun displayRange(): String = "${start + 1}..$end"

    init {
        require(start >= 0) { "Feature '$name' starts before position 0" }
        require(end >= start) { "Feature '$name' ends before it starts" }
    }
}

/**
 * An immutable nucleotide (or protein) sequence with annotations.
 *
 * Every editing operation returns a new [Seq] and carries the feature table
 * along, shifting and clipping coordinates so annotations survive edits.
 */
@Serializable
data class Seq(
    val name: String = "unnamed",
    val bases: String = "",
    val kind: SeqKind = SeqKind.DNA,
    val topology: Topology = Topology.LINEAR,
    val features: List<Feature> = emptyList(),
    val description: String = "",
) {
    val length: Int get() = bases.length

    val isCircular: Boolean get() = topology == Topology.CIRCULAR

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

    /** A copy with [feature] removed from the feature table (by structural equality). */
    fun withoutFeature(feature: Feature): Seq = copy(features = features - feature)

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
        return copy(bases = bases.substring(0, at) + insert + bases.substring(at), features = moved)
    }

    /** Deletes `[start, end)`, shifting and clipping features. */
    fun deleteRange(start: Int, end: Int): Seq {
        val (s, e) = normalizeRange(start, end)
        val removed = e - s
        if (removed == 0) return this
        val moved = features.mapNotNull { f -> clipAfterDeletion(f, s, e, removed) }
        return copy(bases = bases.substring(0, s) + bases.substring(e), features = moved)
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
        return Seq(newName, slice, kind, Topology.LINEAR, kept, description)
    }

    /**
     * Rotates a circular sequence so that position [newOrigin] becomes position 0.
     * Features that would straddle the new origin are dropped from the wrap point.
     */
    fun rotateOrigin(newOrigin: Int): Seq {
        require(isCircular) { "Only circular sequences have a movable origin" }
        if (length == 0) return this
        val o = Math.floorMod(newOrigin, length)
        if (o == 0) return this
        val rotated = bases.substring(o) + bases.substring(0, o)
        val moved = features.mapNotNull { f ->
            val s = f.start - o
            val e = f.end - o
            when {
                s >= 0 -> f.copy(start = s, end = e)
                e <= 0 -> f.copy(start = s + length, end = e + length)
                else -> null // straddles the new origin
            }
        }
        return copy(bases = rotated, features = moved.sortedBy { it.start })
    }

    /** Reverse complement; features are mirrored and their strands flipped. */
    fun reverseComplement(newName: String = name): Seq {
        val rc = buildString(length) {
            for (i in bases.indices.reversed()) append(Alphabet.complement(bases[i], kind))
        }
        val mirrored = features.map { f ->
            f.copy(start = length - f.end, end = length - f.start, strand = f.strand.flipped())
        }.sortedBy { it.start }
        return copy(name = newName, bases = rc, features = mirrored)
    }

    /** Plain complement, without reversing. */
    fun complement(): Seq =
        copy(bases = bases.map { Alphabet.complement(it, kind) }.joinToString(""))

    /** Joins another sequence onto the 3' end; both must be linear. */
    operator fun plus(other: Seq): Seq {
        require(!isCircular && !other.isCircular) { "Cannot concatenate circular sequences" }
        val shifted = other.features.map { it.copy(start = it.start + length, end = it.end + length) }
        return copy(bases = bases + other.bases, features = features + shifted)
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

    override fun toString(): String =
        "$name (${length} bp, ${kind.name.lowercase()}, ${topology.name.lowercase()})"
}
