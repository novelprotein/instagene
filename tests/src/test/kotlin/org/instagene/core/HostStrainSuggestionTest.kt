package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostStrainSuggestionTest {
    @Test
    fun ranksEColiCloningHostsForEColiRecord() {
        val suggestions = HostStrainSuggestionEngine.suggest(
            HostStrainSuggestionInput(
                hostType = "E. coli",
                organism = "Escherichia coli",
                taxonomy = listOf("Bacteria", "Proteobacteria", "Enterobacteriaceae"),
            ),
        )
        assertEquals("DH5α", suggestions.first().strain)
        assertTrue(suggestions.first().rationale.contains("organism/taxonomy"))
    }

    @Test
    fun ranksYeastHostsFromNcbiLineage() {
        val suggestions = HostStrainSuggestionEngine.suggest(
            HostStrainSuggestionInput(
                organism = "Saccharomyces cerevisiae",
                taxonomy = listOf("Eukaryota", "Fungi", "Saccharomycetaceae"),
            ),
            limit = 2,
        )
        assertEquals(listOf("BY4741", "W303"), suggestions.map { it.strain })
    }
}
