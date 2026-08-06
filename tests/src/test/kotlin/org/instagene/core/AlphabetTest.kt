package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlphabetTest {

    @Test
    fun isNucleotideAcceptsIupacAndGap() {
        for (c in Alphabet.NUCLEOTIDES) {
            assertTrue(Alphabet.isNucleotide(c), "expected $c")
            assertTrue(Alphabet.isNucleotide(c.lowercaseChar()), "expected lowercase $c")
        }
        assertFalse(Alphabet.isNucleotide('E'))
        assertFalse(Alphabet.isNucleotide('F'))
        assertFalse(Alphabet.isNucleotide(' '))
    }

    @Test
    fun complementPreservesCaseAndKind() {
        assertEquals('T', Alphabet.complement('A', SeqKind.DNA))
        assertEquals('U', Alphabet.complement('A', SeqKind.RNA))
        assertEquals('a', Alphabet.complement('t', SeqKind.DNA).lowercaseChar())
        assertEquals('t', Alphabet.complement('a', SeqKind.DNA))
        assertEquals('N', Alphabet.complement('Z', SeqKind.DNA))
        assertEquals('n', Alphabet.complement('z', SeqKind.DNA))
        assertEquals('Y', Alphabet.complement('R', SeqKind.DNA))
        assertEquals('S', Alphabet.complement('S', SeqKind.DNA))
        assertEquals('-', Alphabet.complement('-', SeqKind.DNA))
    }

    @Test
    fun matchesExactDegenerateAndUtEquivalence() {
        assertTrue(Alphabet.matches('A', 'A'))
        assertTrue(Alphabet.matches('R', 'A'))
        assertTrue(Alphabet.matches('R', 'G'))
        assertFalse(Alphabet.matches('R', 'C'))
        assertTrue(Alphabet.matches('A', 'R'))
        assertTrue(Alphabet.matches('U', 'T'))
        assertTrue(Alphabet.matches('T', 'U'))
        assertTrue(Alphabet.matches('N', 'G'))
        assertFalse(Alphabet.matches('E', 'A'))
        assertFalse(Alphabet.matches('A', '-'))
    }

    @Test
    fun cleanStripsWhitespaceAndDigits() {
        assertEquals("ACGT", Alphabet.clean("A C\nG\tT 12"))
        assertEquals("ACGT-", Alphabet.clean("ACGT-"))
        assertEquals("N", Alphabet.clean("N"))
    }

    @Test
    fun invalidCharactersReportsOffenders() {
        assertTrue(Alphabet.invalidCharacters("ACGT").isEmpty())
        assertEquals(setOf('E', 'F'), Alphabet.invalidCharacters("AECFGT"))
    }
}
