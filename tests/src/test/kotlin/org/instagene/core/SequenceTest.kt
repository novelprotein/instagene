package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SequenceTest {

    @Test
    fun featureRejectsInvalidRanges() {
        assertFailsWith<IllegalArgumentException> { Feature("bad", start = -1, end = 2) }
        assertFailsWith<IllegalArgumentException> { Feature("bad", start = 5, end = 3) }
    }

    @Test
    fun featureDisplayRangeIsOneBasedInclusive() {
        val f = Feature("cds", start = 0, end = 3)
        assertEquals(3, f.length)
        assertEquals("1..3", f.displayRange())
    }

    @Test
    fun strandFlipped() {
        assertEquals(Strand.REVERSE, Strand.FORWARD.flipped())
        assertEquals(Strand.FORWARD, Strand.REVERSE.flipped())
    }

    @Test
    fun linearSubAndBaseAt() {
        val seq = Seq(bases = "ACGTAC")
        assertEquals('G', seq.baseAt(2))
        assertEquals("CGTA", seq.sub(1, 5))
        assertEquals("", seq.sub(3, 3))
        assertFailsWith<IllegalArgumentException> { seq.sub(4, 2) }
    }

    @Test
    fun circularWrapsBaseAtAndSub() {
        val seq = Seq(bases = "ACGT", topology = Topology.CIRCULAR)
        assertEquals('T', seq.baseAt(-1))
        assertEquals('A', seq.baseAt(4))
        assertEquals("TACG", seq.sub(3, 7))
        assertEquals("", Seq(bases = "", topology = Topology.CIRCULAR).sub(0, 0))
    }

    @Test
    fun insertAtShiftsAndExtendsFeatures() {
        val feat = Feature("x", start = 2, end = 5)
        val seq = Seq(bases = "AAAAAA", features = listOf(feat))
        val upstream = seq.insertAt(2, "TT")
        assertEquals("AATTAAAA", upstream.bases)
        assertEquals(Feature("x", start = 4, end = 7), upstream.features.single())

        val inside = seq.insertAt(3, "GG")
        assertEquals("AAAGGAAA", inside.bases)
        assertEquals(Feature("x", start = 2, end = 7), inside.features.single())
    }

    @Test
    fun deleteRangeClipsAndDropsFeatures() {
        val features = listOf(
            Feature("up", start = 0, end = 2),
            Feature("mid", start = 2, end = 5),
            Feature("down", start = 5, end = 8),
            Feature("span", start = 1, end = 6),
        )
        val seq = Seq(bases = "ABCDEFGH", features = features)
        val deleted = seq.deleteRange(2, 5)
        assertEquals("ABFGH", deleted.bases)
        assertEquals(listOf("up", "down", "span"), deleted.features.map { it.name })
        assertEquals(Feature("down", start = 2, end = 5), deleted.features.first { it.name == "down" })
        assertEquals(Feature("span", start = 1, end = 3), deleted.features.first { it.name == "span" })
        assertEquals(seq.bases, seq.deleteRange(3, 3).bases)
    }

    @Test
    fun replaceRangeComposesDeleteAndInsert() {
        val seq = Seq(bases = "AAAAAA", features = listOf(Feature("x", start = 1, end = 4)))
        val replaced = seq.replaceRange(1, 4, "TT")
        assertEquals("ATTAA", replaced.bases)
        assertTrue(replaced.features.isEmpty() || replaced.features.all { it.end <= replaced.length })
    }

    @Test
    fun subSeqClipsFeaturesAndForcesLinear() {
        val seq = Seq(
            bases = "ACGTACGT",
            topology = Topology.CIRCULAR,
            features = listOf(Feature("f", start = 2, end = 6)),
        )
        val sub = seq.subSeq(1, 5)
        assertEquals("CGTA", sub.bases)
        assertEquals(Topology.LINEAR, sub.topology)
        assertEquals(Feature("f", start = 1, end = 4), sub.features.single())
    }

    @Test
    fun rotateOriginSplitsStraddlers() {
        val seq = Seq(
            bases = "ABCDEF",
            topology = Topology.CIRCULAR,
            features = listOf(
                Feature("keep", start = 3, end = 5),
                Feature("straddle", start = 4, end = 6),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            Seq(bases = "ACGT", topology = Topology.LINEAR).rotateOrigin(1)
        }
        assertEquals(seq, seq.rotateOrigin(0))
        val rotated = seq.rotateOrigin(3)
        assertEquals("DEFABC", rotated.bases)
        // [3,5) moves to [0,2); [4,6) ("EF") did not wrap the origin and moves to [1,3).
        assertEquals(listOf("keep", "straddle"), rotated.features.map { it.name })
        assertEquals(Feature("keep", start = 0, end = 2), rotated.features.first { it.name == "keep" })
        assertEquals(Feature("straddle", start = 1, end = 3), rotated.features.first { it.name == "straddle" })
        // A feature crossing the new origin is retained as two spans.
        val wrapping = seq.rotateOrigin(4)
        assertEquals("EFABCD", wrapping.bases)
        assertEquals(listOf("keep", "straddle", "keep"), wrapping.features.map { it.name })
        assertEquals(Feature("keep", start = 0, end = 1), wrapping.features[0])
        assertEquals(Feature("straddle", start = 0, end = 2), wrapping.features[1])
        assertEquals(Feature("keep", start = 5, end = 6), wrapping.features[2])
    }

    @Test
    fun reverseComplementMirrorsFeatures() {
        val seq = Seq(
            bases = "GAATTC",
            features = listOf(Feature("eco", start = 0, end = 6, strand = Strand.FORWARD)),
        )
        val rc = seq.reverseComplement("rc")
        assertEquals("rc", rc.name)
        assertEquals("GAATTC", rc.bases) // EcoRI site is palindromic
        assertEquals(Strand.REVERSE, rc.features.single().strand)
        assertEquals(0, rc.features.single().start)
        assertEquals(6, rc.features.single().end)
    }

    @Test
    fun complementWithoutReverse() {
        assertEquals("TGCA", Seq(bases = "ACGT").complement().bases)
    }

    @Test
    fun plusShiftsFeaturesAndRejectsCircular() {
        val a = Seq(bases = "AA", features = listOf(Feature("a", start = 0, end = 2)))
        val b = Seq(bases = "TT", features = listOf(Feature("b", start = 0, end = 2)))
        val joined = a + b
        assertEquals("AATT", joined.bases)
        assertEquals(Feature("b", start = 2, end = 4), joined.features.first { it.name == "b" })
        assertFailsWith<IllegalArgumentException> {
            Seq(bases = "AA", topology = Topology.CIRCULAR) + b
        }
    }

    @Test
    fun withFeatureKeepsSortedByStart() {
        val seq = Seq(bases = "AAAAAA")
            .withFeature(Feature("late", start = 4, end = 5))
            .withFeature(Feature("early", start = 1, end = 2))
        assertEquals(listOf("early", "late"), seq.features.map { it.name })
    }

    @Test
    fun rotateOriginKeepsFeatureEndingAtOldOrigin() {
        val seq = Seq(
            bases = "ABCDEF",
            topology = Topology.CIRCULAR,
            features = listOf(
                Feature("tail", start = 4, end = 6),
                Feature("origin", start = 0, end = 2),
            ),
        )
        val rotated = seq.rotateOrigin(4)
        assertEquals("EFABCD", rotated.bases)
        assertEquals(listOf("tail", "origin"), rotated.features.map { it.name })
        assertEquals(Feature("tail", start = 0, end = 2), rotated.features.first { it.name == "tail" })
        assertEquals(Feature("origin", start = 2, end = 4), rotated.features.first { it.name == "origin" })
    }

    @Test
    fun validateAcceptsWellFormedSequence() {
        val seq = Seq(
            name = "valid",
            bases = "ACGTAC",
            features = listOf(Feature("gene", start = 0, end = 4)),
            primers = listOf(PrimerAnnotation("pcr", "ACGT", bindingStart = 1, bindingEnd = 5)),
        )
        assertTrue(seq.validate().isEmpty())
    }

    @Test
    fun validateReportsUnsupportedCharactersAndOutOfBoundsCoordinates() {
        val seq = Seq(
            name = "bad",
            bases = "ACGTZ",
            features = listOf(Feature("overflow", start = 0, end = 6)),
            primers = listOf(PrimerAnnotation("bad-primer", "ACGT", bindingStart = 2, bindingEnd = 7)),
        )
        val issues = seq.validate()
        assertTrue(issues.any { it.message.contains("unsupported characters") })
        assertTrue(issues.any { it.message.contains("Feature") && it.message.contains("overflow") })
        assertTrue(issues.any { it.message.contains("Primer") && it.message.contains("bad-primer") })
    }

    @Test
    fun sourceAuditAddsMetadataAndProvenance() {
        val seq = Seq(bases = "ACGT").withSourceAudit(
            source = "example.gb",
            operation = "IMPORT",
            summary = "Imported synthetic record",
            warnings = listOf("Retained as a simple sequence record"),
            fileHash = "abc123",
        )
        assertEquals("example.gb", seq.metadata["SOURCE"])
        assertEquals("abc123", seq.metadata["SOURCE_HASH"])
        assertTrue(seq.provenance.any { it.operation == "IMPORT" && it.summary == "Imported synthetic record" })
    }

    @Test
    fun recordMetadataConvenienceMethodsMirrorBiopythonStyleAnnotations() {
        val seq = Seq(bases = "ACGT")
            .withSource("Escherichia coli")
            .withOrganism("Escherichia coli", listOf("Bacteria", "Proteobacteria"))
            .withTaxonomy("Gammaproteobacteria")
            .withComment("synthetic construct")
            .withDatabaseReference("taxon:562")
            .withHeaderField("ACCESSION", "ABC123")
        assertEquals("Escherichia coli", seq.recordMetadata.source)
        assertEquals("Escherichia coli", seq.recordMetadata.organism)
        assertTrue(seq.recordMetadata.taxonomy.contains("Bacteria"))
        assertTrue(seq.recordMetadata.taxonomy.contains("Gammaproteobacteria"))
        assertTrue(seq.recordMetadata.comments.contains("synthetic construct"))
        assertTrue(seq.recordMetadata.databaseReferences.contains("taxon:562"))
        assertTrue(seq.recordMetadata.headerFields.any { it.key == "ACCESSION" && it.value == "ABC123" })
        assertEquals("Escherichia coli", seq.metadata["SOURCE"])
        assertEquals("Escherichia coli", seq.metadata["ORGANISM"])
    }

    @Test
    fun provenanceSummaryFormatsRecordHistoryLikeBioinformaticsAnnotations() {
        val seq = Seq(bases = "ACGT")
            .withSourceAudit("example.gb", operation = "IMPORT", summary = "Imported example")
            .withProcedure(ProcedureRecord("EDIT", "trimmed 2 bases", timestamp = 42))
        val summary = seq.provenanceSummary(2)
        assertTrue(summary.contains("IMPORT:Imported example"))
        assertTrue(summary.contains("EDIT:trimmed 2 bases"))
    }

    @Test
    fun emptyCircularBaseAtRejects() {
        val empty = Seq(bases = "", topology = Topology.CIRCULAR)
        assertFailsWith<IllegalArgumentException> { empty.baseAt(0) }
        assertFailsWith<IllegalArgumentException> { empty.baseAt(3) }
        assertEquals(empty, empty.rotateOrigin(2))
    }
}
