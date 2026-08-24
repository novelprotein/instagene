package org.instagene.core

import org.instagene.core.io.SeqIO

data class PlasmidRecord(
    val name: String,
    val sizeBp: Int,
    val organism: String,
    val markers: List<String>,
    val origin: String,
    val description: String,
    val sampleName: String? = null,
)

data class PlasmidSearchResult(val results: List<PlasmidRecord>)

object PlasmidDatabase {

    private val BUILT_IN = listOf(
        PlasmidRecord("pUC19", 2686, "E. coli", listOf("AmpR"), "pMB1", "High-copy cloning vector"),
        PlasmidRecord(
            "pBR322",
            4361,
            "E. coli",
            listOf("AmpR", "TetR"),
            "pMB1",
            "Classic cloning vector; BLAST-verified bundled sequence from NCBI GenBank J01749.1",
            sampleName = SeqIO.Samples.PBR322_NCBI.name,
        ),
        PlasmidRecord("pET-28a", 5369, "E. coli", listOf("KanR"), "pBR322", "T7 expression vector with N-terminal His-tag"),
        PlasmidRecord("pcDNA3.1", 5471, "Mammalian", listOf("AmpR", "NeoR"), "SV40", "Mammalian expression vector"),
        PlasmidRecord("pGEX-4T-1", 4969, "E. coli", listOf("AmpR"), "pBR322", "GST fusion expression vector"),
        PlasmidRecord("pBlueScript II KS", 2961, "E. coli", listOf("AmpR"), "ColE1", "General cloning and in vitro transcription"),
        PlasmidRecord("pYES2", 6210, "S. cerevisiae", listOf("AmpR", "URA3"), "2 micron", "Yeast expression vector"),
        PlasmidRecord("pFastBac1", 4774, "E. coli", listOf("KanR"), "pUC ori", "Baculovirus expression system shuttle"),
        PlasmidRecord("pLenti-CMV", 7947, "Mammalian", listOf("AmpR", "PuroR"), "HIV-1", "Lentiviral expression vector"),
    )

    fun search(query: String): PlasmidSearchResult {
        val q = query.trim().lowercase()
        val results = BUILT_IN.filter { p ->
            p.name.lowercase().contains(q) ||
                p.markers.any { it.lowercase().contains(q) } ||
                p.organism.lowercase().contains(q) ||
                p.description.lowercase().contains(q)
        }
        return PlasmidSearchResult(results)
    }

    fun getByName(name: String): PlasmidRecord? =
        BUILT_IN.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun sequenceFor(record: PlasmidRecord): Seq? =
        record.sampleName?.let { sample -> SeqIO.Samples.ALL.firstOrNull { it.name.equals(sample, ignoreCase = true) } }

    fun sequenceFor(name: String): Seq? =
        getByName(name)?.let(::sequenceFor)

    fun all(): List<PlasmidRecord> = BUILT_IN.toList()
}
