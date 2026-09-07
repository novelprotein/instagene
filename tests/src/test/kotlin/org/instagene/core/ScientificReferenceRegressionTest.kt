package org.instagene.core

import kotlin.test.*

class ScientificReferenceRegressionTest {
    private fun fixture(name: String) = javaClass.getResource("/scientific/$name")!!.readText()
        .lineSequence().filter { it.isNotBlank() && !it.startsWith('#') }.toList()

    @Test fun allProteinMatrixEntriesMatchPublishedMatrices() {
        for (preset in listOf(AlignmentScoring.PAM250, AlignmentScoring.BLOSUM62)) {
        val rows = fixture("${preset.name}.txt").map { it.trim().split(Regex("\\s+")) }
        val columns = rows.first()
        for (row in rows.drop(1).filter { it[0].single() in "ARNDCQEGHILKMFPSTWYV" }) {
            for ((index, column) in columns.withIndex()) {
                if (column.single() !in "ARNDCQEGHILKMFPSTWYV") continue
                assertEquals(row[index + 1].toDouble(), AlignmentScores.score(SeqKind.PROTEIN,
                    row[0].single(), column.single(), preset), "${row[0]} / $column")
            }
        }
    }

    }

    @Test fun allBundledCodonsAndInitiatorsMatchNcbi() {
        val order = "TCAG".flatMap { a -> "TCAG".flatMap { b -> "TCAG".map { c -> "$a$b$c" } } }
        val fixtures = fixture("ncbi-codes.tsv")
        assertEquals(CodonTable.ALL.map { it.id }.toSet(), fixtures.map { it.substringBefore('\t').toInt() }.toSet())
        for (line in fixtures) {
            val (id, aminoAcids, starts) = line.split('\t')
            val table = CodonTable.byId(id.toInt())
            order.forEachIndexed { i, codon ->
                assertEquals(aminoAcids[i], table.translate(codon), "table $id $codon")
                assertEquals(starts[i] == 'M', table.isStart(codon), "table $id initiation $codon")
            }
        }
    }

    @Test fun meltingTemperaturesMatchBiopython186() {
        for (line in fixture("primer-tm.tsv")) {
            val c = line.split('\t')
            val actual = PrimerThermodynamics.thermodynamicResult(c[0], c[1].toDouble(), c[2].toDouble(),
                c[3].toDouble(), c[4].toDouble())
            assertEquals(c[5].toDouble(), actual.tm, 1e-8, line)
        }
        assertFailsWith<IllegalArgumentException> {
            PrimerThermodynamics.thermodynamicResult("ACGT", strandConcentration = Double.NaN)
        }
    }

    @Test fun duplexMassIsIndependentOfWhichStrandIsRecordedAndCountsBothEnds() {
        val single = Seq(bases = "AAAAAC", molecule = MoleculeProperties(strandedness = Strandedness.SINGLE,
            fivePrimePhosphorylated = false, threePrimePhosphorylated = false))
        val complement = single.copy(bases = "GTTTTT")
        val duplex = single.copy(molecule = single.molecule.copy(strandedness = Strandedness.DOUBLE))
        val mass = SeqOps.molecularWeightDaltons(duplex)
        assertEquals(SeqOps.molecularWeightDaltons(single) + SeqOps.molecularWeightDaltons(complement), mass, 1e-8)
        assertEquals(mass, SeqOps.molecularWeightDaltons(duplex.copy(bases = complement.bases)), 1e-8)
        for (five in listOf(false, true)) for (three in listOf(false, true)) {
            val modified = duplex.copy(molecule = duplex.molecule.copy(fivePrimePhosphorylated = five,
                threePrimePhosphorylated = three))
            assertEquals(mass + 2 * 79.97 * listOf(five, three).count { it }, SeqOps.molecularWeightDaltons(modified), 1e-8)
            val circular = modified.copy(topology = Topology.CIRCULAR)
            assertEquals(SeqOps.molecularWeightDaltons(duplex.copy(topology = Topology.CIRCULAR)),
                SeqOps.molecularWeightDaltons(circular), 1e-8)
        }
    }

    @Test fun reverseGuideCoordinatesReconstructGuideWithoutFalseOriginWarning() {
        val seq = Seq(bases = "CCA" + "ATGACATGACATGACATGAC")
        val guide = CrisprDesign.design(seq.copy(topology = Topology.CIRCULAR), 100).guides
            .first { it.strand == Strand.REVERSE && it.pamStart == 0 }
        assertEquals(guide.sequence, guide.coordinates.joinToString("") {
            Alphabet.complement(seq.bases[it], SeqKind.DNA).toString()
        })
        assertEquals((22 downTo 3).toList(), guide.coordinates)
        assertFalse(guide.warnings.any { "origin" in it })
        assertTrue(CrisprDesign.design(Seq(bases = "GG", topology = Topology.CIRCULAR)).guides.isEmpty())
    }

    @Test fun sangerOrientationUsesAlignmentScoreRatherThanIdentity() {
        val reference = Seq(bases = "TGCCTGGTACATCCGCG" + "N".repeat(20) + "TGCTTTTGGCGGTTGTAGCAGGCA")
        val read = SangerRead("read", "TGCCTGGTACATCCGCGAAATGCA")
        val result = SangerAlignment.align(reference, listOf(read)).reads.single()
        assertEquals(SangerOrientation.REVERSE, result.orientation)
        assertEquals(36, result.alignmentScore) // Biopython PairwiseAligner, +2/-1/-2, local.
    }

    @Test fun cumulativeSkewAccumulatesWindowValues() {
        val values = SequenceStatistics.cumulativeGcSkew(Seq(bases = "GGGGCCCC"), 4, 2)
        assertEquals(listOf(1.0, 1.0, 0.0), values.map { it.y })
    }

    @Test fun referenceInsertionColumnsAreSharedAcrossAllRows() {
        val reference = Seq(name = "reference", bases = "ACGT")
        val result = Alignment.align(reference, listOf(Seq(bases = "ATCGT"), Seq(bases = "ACGAT")),
            AlignmentParameters(matchScore = 3.0, mismatchPenalty = -4.0, gapPenalty = -2.0))
        assertEquals("A-CG-T", result.reference.sequence)
        assertEquals(listOf("ATCG-T", "A-CGAT"), result.queries.map { it.sequence })
    }

    @Test fun structureHeuristicDoesNotInventEnergyAndRnaWobbleIsSymmetric() {
        val first = SecondaryStructure.predict(Seq(bases = "GAAAU", kind = SeqKind.RNA), preferVienna = false)
        val second = SecondaryStructure.predict(Seq(bases = "UAAAG", kind = SeqKind.RNA), preferVienna = false)
        assertEquals(1, first.pairedBases)
        assertEquals(first.pairedBases, second.pairedBases)
        assertNull(first.estimatedDeltaG)
        assertEquals(-1.2, SecondaryStructure.parseVienna("GAAAU\n(...) ( -1.20)\n", "GAAAU")?.estimatedDeltaG)
        assertNull(SecondaryStructure.parseVienna("GAAAU\n(...) (missing)\n", "GAAAU"))
        assertEquals(0, SecondaryStructure.predict(Seq(bases = "GAAAT"), preferVienna = false).pairedBases)
    }
}
