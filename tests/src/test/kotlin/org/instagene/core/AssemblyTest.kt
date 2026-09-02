package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssemblyTest {

    @Test
    fun findOverlapExactAndCaseInsensitive() {
        assertEquals(4, Assembly.findOverlap("XXXXACGT", "ACGTYYYY", 3))
        assertEquals(4, Assembly.findOverlap("xxxxacgt", "ACGTYYYY", 3))
        assertNull(Assembly.findOverlap("AAAA", "TTTT", 3))
        // Self-overlap excludes full identity
        assertEquals(3, Assembly.findOverlap("AAATTTAAA", "AAATTTAAA", 3))
        val same = "ACGTACGT"
        assertNull(Assembly.findOverlap(same, same, same.length))
    }

    @Test
    fun ligateCompatibleBluntFragments() {
        val a = Fragment("AAA", StickyEnd.BLUNT, StickyEnd.BLUNT, sourceName = "a")
        val b = Fragment("TTT", StickyEnd.BLUNT, StickyEnd.BLUNT, sourceName = "b",
            features = listOf(Feature("tag", start = 0, end = 3)))
        assertTrue(Assembly.canLigate(a, b))
        val joined = Assembly.ligate(a, b)
        assertEquals("AAATTT", joined.bases)
        assertEquals(Feature("tag", start = 3, end = 6), joined.features.single())
        assertEquals("AAATTT", Assembly.ligate(listOf(a, b)).bases)
        assertFailsWith<IllegalArgumentException> { Assembly.ligate(emptyList()) }
    }

    @Test
    fun ligateIncompatibleThrows() {
        val a = Fragment("AAA", StickyEnd.BLUNT, StickyEnd(EndType.FIVE_PRIME_OVERHANG, "AATT"), "a")
        val b = Fragment("TTT", StickyEnd(EndType.FIVE_PRIME_OVERHANG, "GATC"), StickyEnd.BLUNT, "b")
        assertFalse(Assembly.canLigate(a, b))
        assertFailsWith<AssemblyException> { Assembly.ligate(a, b) }
    }

    @Test
    fun circularizeCompatibleEnds() {
        val f = Fragment(
            "ACGT",
            StickyEnd(EndType.FIVE_PRIME_OVERHANG, "AATT"),
            StickyEnd(EndType.FIVE_PRIME_OVERHANG, "AATT"),
            sourceName = "circ",
        )
        val plasmid = Assembly.circularize(f, "pTest")
        assertEquals(Topology.CIRCULAR, plasmid.topology)
        assertEquals("pTest", plasmid.name)
        assertFailsWith<AssemblyException> {
            Assembly.circularize(
                Fragment("ACGT", StickyEnd.BLUNT, StickyEnd(EndType.FIVE_PRIME_OVERHANG, "AATT"), "bad"),
            )
        }
    }

    @Test
    fun reverseComplementFragmentSwapsEnds() {
        val f = Fragment(
            bases = "GAATTC",
            leftEnd = StickyEnd(EndType.FIVE_PRIME_OVERHANG, "AATT", "EcoRI"),
            rightEnd = StickyEnd.BLUNT,
            sourceName = "f",
            features = listOf(Feature("x", start = 1, end = 3)),
        )
        val rc = Assembly.reverseComplement(f)
        assertTrue(rc.sourceName.contains("rc"))
        assertTrue(rc.leftEnd.isBlunt)
        assertEquals("AATT", rc.rightEnd.overhang.uppercase())
        assertEquals(EndType.FIVE_PRIME_OVERHANG, rc.rightEnd.type)
    }

    @Test
    fun buildPlasmidFromMcsAndGfp() {
        val enzymes = listOf(Enzymes.require("EcoRI"), Enzymes.require("HindIII"))
        val result = Assembly.buildPlasmid(
            backbone = TestSequenceFixtures.restrictionBackbone.copy(topology = Topology.CIRCULAR),
            insert = TestSequenceFixtures.restrictionInsert,
            enzymes = enzymes,
            name = "pGFP",
        )
        assertEquals("pGFP", result.plasmid.name)
        assertEquals(Topology.CIRCULAR, result.plasmid.topology)
        assertTrue(result.plasmid.length > 0)
        assertTrue(result.log.isNotEmpty())
    }

    @Test
    fun buildPlasmidRejectsEmptyEnzymes() {
        assertFailsWith<IllegalArgumentException> {
            Assembly.buildPlasmid(TestSequenceFixtures.restrictionBackbone, TestSequenceFixtures.restrictionInsert, emptyList())
        }
    }

    @Test
    fun gibsonLinearJoin() {
        val a = Seq(name = "a", bases = "AAAAAAAAAAGGGGGGGGGG")
        val b = Seq(name = "b", bases = "GGGGGGGGGCCCCCCCCCC")
        val result = Assembly.gibson(listOf(a, b), minOverlap = 8, circular = false, name = "lin")
        assertEquals(Topology.LINEAR, result.product.topology)
        assertEquals("lin", result.product.name)
        assertTrue(result.product.bases.startsWith("AAAAAAAAAA"))
        assertTrue(result.product.bases.endsWith("CCCCCCCCCC"))
        assertEquals(1, result.overlaps.size)
    }

    @Test
    fun gibsonCircularClose() {
        val a = Seq(name = "a", bases = "TTTTTTTTTTAAAAAAAAAA")
        val b = Seq(name = "b", bases = "AAAAAAAAAAGGGGGGGGGG")
        val c = Seq(name = "c", bases = "GGGGGGGGGGTTTTTTTTTT")
        val result = Assembly.gibson(listOf(a, b, c), minOverlap = 8, circular = true, name = "circ")
        assertEquals(Topology.CIRCULAR, result.product.topology)
        assertTrue(result.overlaps.size >= 3)
    }

    @Test
    fun gibsonTooFewPartsThrows() {
        assertFailsWith<IllegalArgumentException> {
            Assembly.gibson(listOf(Seq(bases = "ACGT")), minOverlap = 4, circular = false)
        }
    }
}
