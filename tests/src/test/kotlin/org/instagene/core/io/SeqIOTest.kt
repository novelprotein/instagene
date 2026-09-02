package org.instagene.core.io

import org.instagene.core.Feature
import org.instagene.core.Seq
import org.instagene.core.SeqKind
import org.instagene.core.Topology
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeqIOTest {

    @Test
    fun formatOfByExtension() {
        assertEquals(SeqFormat.GENBANK, SeqIO.formatOf(File("x.gb")))
        assertEquals(SeqFormat.GENBANK, SeqIO.formatOf(File("x.gbk")))
        assertEquals(SeqFormat.FASTA, SeqIO.formatOf(File("x.fa")))
        assertEquals(SeqFormat.FASTA, SeqIO.formatOf(File("x.unknown")))
    }

    @Test
    fun detectAndParseFormats() {
        assertEquals(SeqFormat.GENBANK, SeqIO.detectFormat("LOCUS       x\nORIGIN\n//"))
        assertEquals(SeqFormat.FASTA, SeqIO.detectFormat(">x\nACGT"))
        assertEquals("ACGT", SeqIO.parse("acgt").bases)
        assertEquals("named", SeqIO.parse(">named\nACGT").name)
    }

    @Test
    fun parseAllMultiFasta() {
        val all = SeqIO.parseAll(">a\nAA\n>b\nTT")
        assertEquals(2, all.size)
    }

    @Test
    fun fileRecordCallbackStreamsMultiRecordGenBank() {
        val file = kotlin.io.path.createTempFile("instagene-records", ".gb").toFile()
        file.deleteOnExit()
        file.writeText(
            """
            LOCUS       one                        4 bp    DNA     linear
            ORIGIN
                     1 acgt
            //
            LOCUS       two                        4 bp    DNA     linear
            ORIGIN
                     1 tgca
            //
            """.trimIndent(),
        )
        val names = ArrayList<String>()
        assertEquals(2, SeqIO.forEachRecord(file) { names += it.name })
        assertEquals(listOf("one", "two"), names)
    }

    @Test
    fun rawSequence() {
        val seq = SeqIO.rawSequence("a c g t 12")
        assertEquals("ACGT", seq.bases)
        assertEquals(Topology.LINEAR, seq.topology)
    }

    @Test
    fun bundledSamplesAreCompleteSourceRecords() {
        assertEquals(4, SeqIO.Samples.ALL.size)
        assertTrue(SeqIO.Samples.ALL.all { it.metadata["ONLINE_ACCESSION"].orEmpty().isNotBlank() })
        assertTrue(SeqIO.Samples.ALL.all { it.metadata["ONLINE_URL"].orEmpty().startsWith("https://www.ncbi.nlm.nih.gov/nuccore/") })
        assertTrue(SeqIO.Samples.ALL.all { it.metadata["SOURCE_SHA256"].orEmpty().length == 64 })
        assertTrue(SeqIO.Samples.ALL.all { it.recordMetadata.references.isNotEmpty() })
        assertTrue(SeqIO.Samples.ALL.all { it.recordMetadata.author.orEmpty().isNotBlank() })
        assertFalse(SeqIO.Samples.ALL.any { it.name.contains("synthetic", ignoreCase = true) })
        assertFalse(SeqIO.Samples.ALL.any { it.name.contains("demo", ignoreCase = true) })
    }

    @Test
    fun bundledExamplesCarryOnlyOriginalSourceCitations() {
        val samples = SeqIO.Samples.ALL
        samples.forEach { sample ->
            val source = sample.metadata[SeqIO.Samples.SOURCE_METADATA_KEY].orEmpty()
            assertTrue(source.isNotBlank(), "${sample.name} is missing a source citation")
            assertTrue(source.contains(sample.metadata.getValue("ONLINE_ACCESSION")), "${sample.name} citation must identify its accession")
            assertTrue(sample.recordMetadata.references.any { reference ->
                reference.authors.isNotBlank() && reference.title.isNotBlank() && reference.journal.isNotBlank()
            }, "${sample.name} has no complete original reference")
        }
        assertFalse(samples.any { it.metadata.values.any { value -> value.contains("BLAST", ignoreCase = true) } })
    }

    @Test
    fun bundledSourceChecksumsMatchTheCheckedInRecords() {
        val resources = mapOf(
            "pBR322_NCBI" to "/org/instagene/core/samples/pBR322_J01749.1.gb",
            "pUC19_NCBI_reference" to "/org/instagene/core/samples/pUC19_M77789.2.gb",
            "GFP_Aequorea_NCBI_reference" to "/org/instagene/core/samples/GFP_L29345.1.gb",
            "pGFPuv_NCBI_reference" to "/org/instagene/core/samples/pGFPuv_U62636.1.gb",
        )
        resources.forEach { (name, resource) ->
            val bytes = requireNotNull(SeqIO::class.java.getResourceAsStream(resource)) { resource }.readBytes()
            val checksum = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val sample = SeqIO.Samples.ALL.first { it.name == name }
            assertEquals(checksum, sample.metadata["SOURCE_SHA256"], name)
        }
    }

    @Test
    fun bundledSamplesKeepSourceCommentsAndDoNotTurnProvenanceIntoComments() {
        val resources = mapOf(
            "pBR322_NCBI" to "/org/instagene/core/samples/pBR322_J01749.1.gb",
            "pUC19_NCBI_reference" to "/org/instagene/core/samples/pUC19_M77789.2.gb",
            "GFP_Aequorea_NCBI_reference" to "/org/instagene/core/samples/GFP_L29345.1.gb",
            "pGFPuv_NCBI_reference" to "/org/instagene/core/samples/pGFPuv_U62636.1.gb",
        )
        resources.forEach { (name, resource) ->
            val sourceText = requireNotNull(SeqIO::class.java.getResourceAsStream(resource)) { resource }
                .bufferedReader()
                .use { it.readText() }
            val source = GenBank.parse(sourceText)
            val sample = SeqIO.Samples.ALL.first { it.name == name }

            assertEquals(source.recordMetadata.comments, sample.recordMetadata.comments, name)
            assertEquals(source.recordMetadata.author, sample.recordMetadata.author, name)
            assertEquals(source.recordMetadata.locusDivision, sample.recordMetadata.locusDivision, name)
            assertTrue(sample.recordMetadata.comments.none { it.contains("Complete source record bundled") }, name)
            assertTrue(
                GenBank.write(sample).lineSequence()
                    .filter { it.trimStart().startsWith("COMMENT") }
                    .none { it.contains("Complete source record bundled") },
                name,
            )
        }
    }

    @Test
    fun realPbr322SampleComesFromNcbiGenBankWithFeatures() {
        val sample = SeqIO.Samples.PBR322_NCBI
        assertEquals("pBR322_NCBI", sample.name)
        assertEquals(4361, sample.length)
        assertEquals(Topology.CIRCULAR, sample.topology)
        assertEquals("J01749.1", sample.metadata["VERSION"])
        assertEquals("J01749.1", sample.metadata["ONLINE_ACCESSION"])
        assertTrue(sample.metadata[SeqIO.Samples.SOURCE_METADATA_KEY].orEmpty().contains("NCBI GenBank"))
        assertEquals("https://www.ncbi.nlm.nih.gov/nuccore/J01749.1", sample.metadata["ONLINE_URL"])
        assertEquals("02cc9962c0600186d4e1e4055b0de44f2fb0c6a0512e52cf9e5f788f21c180ee", sample.metadata["SOURCE_SHA256"])
        assertTrue(sample.metadata["ANNOTATION_SOURCE"].orEmpty().contains("GenBank feature table"))
        assertEquals("Sutcliffe,J.G.", sample.recordMetadata.author)
        assertTrue(sample.recordMetadata.references.any { it.pubMed == "383387" })
        assertTrue(sample.recordMetadata.references.all { it.sourceUrl == sample.metadata["ONLINE_URL"] })
        assertTrue(sample.features.size >= 48, "expected at least 48 features after source filtering, got ${sample.features.size}")
        val types = sample.features.map { it.type }.toSet()
        assertTrue("CDS" in types)
        assertTrue("gene" in types)
        assertTrue("rep_origin" in types)
        assertTrue("regulatory" in types)
    }

    @Test
    fun bundledExamplesDocsExplainOriginalSourceAttribution() {
        val docs = sequenceOf(File("docs/examples.md"), File("../docs/examples.md"))
            .first(File::isFile)
            .readText()
            .replace(Regex("\\s+"), " ")
        assertTrue(docs.contains("Complete NCBI GenBank record"))
        assertFalse(docs.contains("BLAST-verified"))
    }

    @Test
    fun completeReferenceSamplesRetainSourceRecordsAndDoNotLookLikeSyntheticSamples() {
        val pUC19 = SeqIO.Samples.PUC19_NCBI_REFERENCE
        assertEquals("pUC19_NCBI_reference", pUC19.name)
        assertEquals(2686, pUC19.length)
        assertEquals(Topology.CIRCULAR, pUC19.topology)
        assertEquals("M77789.2", pUC19.metadata["VERSION"])
        assertEquals("M77789.2", pUC19.metadata["ONLINE_ACCESSION"])
        assertEquals("Cloning vector pUC19", pUC19.recordMetadata.organism)
        assertEquals("Norrander,J., Kempe,T. and Messing,J.", pUC19.recordMetadata.author)
        assertEquals("https://www.ncbi.nlm.nih.gov/nuccore/M77789.2", pUC19.metadata["ONLINE_URL"])
        assertEquals("b2651308eedffa54dfb3a1b6307cacdda959e7164a799bc813c883117a1b40d5", pUC19.metadata["SOURCE_SHA256"])
        assertEquals(4, pUC19.recordMetadata.references.size)
        assertTrue(pUC19.recordMetadata.references.any { it.pubMed == "6323249" })
        assertTrue(pUC19.recordMetadata.references.all { it.sourceUrl == pUC19.metadata["ONLINE_URL"] })
        assertTrue(pUC19.features.any { it.notes.startsWith("polylinker of M13mp19;") && it.notes.contains("HindIII-SphI-PstI") })

        val gfp = SeqIO.Samples.GFP_AEQUOREA_NCBI_REFERENCE
        assertEquals("GFP_Aequorea_NCBI_reference", gfp.name)
        assertEquals(922, gfp.length)
        assertEquals(SeqKind.RNA, gfp.kind)
        assertEquals("L29345.1", gfp.metadata["VERSION"])
        assertEquals("Aequorea victoria", gfp.recordMetadata.organism)
        assertEquals("Inouye,S. and Tsuji,F.I.", gfp.recordMetadata.author)
        assertEquals(2, gfp.recordMetadata.references.size)
        assertTrue(gfp.recordMetadata.references.any { it.pubMed == "8137953" })
        assertTrue(gfp.features.any { it.type == "gene" && it.name == "GFP" })
        assertTrue(gfp.features.any { it.type == "CDS" && it.qualifiers["protein_id"] == listOf("AAA58246.1") })

        val pgfpUv = SeqIO.Samples.PGFPUV_NCBI_REFERENCE
        assertEquals("pGFPuv_NCBI_reference", pgfpUv.name)
        assertEquals(3337, pgfpUv.length)
        assertEquals(SeqKind.DNA, pgfpUv.kind)
        assertEquals(Topology.LINEAR, pgfpUv.topology)
        assertEquals("U62636.1", pgfpUv.metadata["VERSION"])
        assertEquals("Cloning vector pGFPuv", pgfpUv.recordMetadata.organism)
        assertEquals("Kitts,P.A.", pgfpUv.recordMetadata.author)
        assertEquals(2, pgfpUv.recordMetadata.references.size)
        assertTrue(pgfpUv.recordMetadata.references.all { it.authors == "Kitts,P.A." })
        assertTrue(pgfpUv.features.any { it.type == "gene" && it.name == "gfpuv" })
        assertTrue(pgfpUv.features.any { it.type == "CDS" && it.qualifiers["protein_id"] == listOf("AAB06048.1") })
    }

    @Test
    fun completeReferenceRecordsRoundTripStructuredProvenance() {
        SeqIO.Samples.ALL.forEach { original ->
            val restored = SeqIO.parse(SeqIO.write(original, SeqFormat.GENBANK))
            assertEquals(original.recordMetadata.author, restored.recordMetadata.author)
            assertEquals(original.recordMetadata.comments, restored.recordMetadata.comments)
            assertEquals(original.recordMetadata.references, restored.recordMetadata.references)
            assertEquals(original.recordMetadata.source, restored.recordMetadata.source)
            assertEquals(original.recordMetadata.organism, restored.recordMetadata.organism)
            assertEquals(original.recordMetadata.locusDivision, restored.recordMetadata.locusDivision)
            assertEquals(original.metadata["VERSION"], restored.metadata["VERSION"])
            assertEquals(original.metadata[SeqIO.Samples.SOURCE_METADATA_KEY], restored.metadata[SeqIO.Samples.SOURCE_METADATA_KEY])
        }
    }

    @Test
    fun sourceRecordExportsRetainOriginalDescriptionAndReferences() {
        val fasta = Fasta.write(SeqIO.Samples.PGFPUV_NCBI_REFERENCE)
        assertTrue(fasta.lineSequence().first().contains(SeqIO.Samples.PGFPUV_NCBI_REFERENCE.description))

        val genBank = GenBank.write(SeqIO.Samples.PGFPUV_NCBI_REFERENCE)
        assertTrue(genBank.contains("DEFINITION  Cloning vector pGFPuv, complete sequence."))
        assertTrue(genBank.contains("AUTHORS      Kitts,P.A."))
        assertTrue(genBank.contains("TITLE        pGFPuv complete sequence"))
    }

    @Test
    fun fileRoundTrip() {
        val dir = createTempDirectory("instagene-test").toFile()
        try {
            val fa = File(dir, "s.fasta")
            val gb = File(dir, "s.gb")
            val seq = Seq("s", "ATGCATGC", SeqKind.DNA, Topology.LINEAR)
            SeqIO.write(fa, seq)
            SeqIO.write(gb, seq, SeqFormat.GENBANK)
            assertEquals(seq.bases, SeqIO.read(fa).bases)
            assertEquals(seq.bases, SeqIO.read(gb).bases)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun annotatedCircularGenBankFileRoundTripPreservesEverything() {
        val dir = createTempDirectory("instagene-test").toFile()
        try {
            val original = Seq(
                name = "pMini",
                bases = "ATGCATGCATGCATGC",
                kind = SeqKind.DNA,
                topology = Topology.CIRCULAR,
                features = listOf(
                    Feature("oris", "rep_origin", 0, 4, org.instagene.core.Strand.FORWARD, ""),
                    Feature("ampR", "CDS", 4, 16, org.instagene.core.Strand.REVERSE, "beta-lactamase"),
                    Feature("MCS", "misc_feature", 12, 16),
                ),
                description = "mini plasmid with a map",
            )
            val file = File(dir, "pMini.gb")
            SeqIO.write(file, original)
            val reloaded = SeqIO.read(file)
            assertEquals(original.name, reloaded.name)
            assertEquals(original.description, reloaded.description)
            assertEquals(original.bases, reloaded.bases)
            assertEquals(Topology.CIRCULAR, reloaded.topology)
            assertEquals(original.features.map { it.copy(qualifiers = mapOf("label" to listOf(it.name)).let { qualifiers ->
                if (it.notes.isBlank()) qualifiers else qualifiers + ("note" to listOf(it.notes))
            }) }, reloaded.features)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun preferredSaveFormatKeepsAnnotatedAndCircularDocumentsInGenBank() {
        assertEquals(SeqFormat.FASTA, SeqIO.preferredSaveFormat(Seq("plain", "ACGT")))
        assertEquals(SeqFormat.GENBANK, SeqIO.preferredSaveFormat(Seq("plasmid", "ACGT", topology = Topology.CIRCULAR)))
        assertEquals(
            SeqFormat.GENBANK,
            SeqIO.preferredSaveFormat(Seq("anno", "ACGTACGT", features = listOf(Feature("f", "misc_feature", 0, 4))))
        )
    }

    @Test
    fun samplesAreNonEmptyAndNamed() {
        assertTrue(SeqIO.Samples.ALL.all { it.length > 100 })
        assertEquals(
            listOf("pBR322_NCBI", "pUC19_NCBI_reference", "GFP_Aequorea_NCBI_reference", "pGFPuv_NCBI_reference"),
            SeqIO.Samples.ALL.map { it.name },
        )
        assertEquals(setOf("J01749.1", "M77789.2", "L29345.1", "U62636.1"), SeqIO.Samples.ALL.map { it.metadata["VERSION"] }.toSet())
    }

    @Test
    fun readReturnsOnlyTheFirstRecordOfAMultiContigGenome() {
        val dir = createTempDirectory("instagene-test").toFile()
        try {
            val genome = File(dir, "genome.fna")
            genome.writeText(
                (1..3).joinToString("") { i ->
                    ">chr$i synthetic contig $i\n" + "ACGT".repeat(250) + "\n"
                }
            )
            val seq = SeqIO.read(genome)
            assertEquals("chr1", seq.name)
            assertEquals(1000, seq.length)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun readStreamsGenBankFromFile() {
        val dir = createTempDirectory("instagene-test").toFile()
        try {
            val gb = File(dir, "genome.gb")
            gb.writeText(
                """
                LOCUS       chr1                1000 bp    DNA     linear
                DEFINITION  synthetic chromosome 1
                FEATURES             Location/Qualifiers
                     source          1..1000
                                     /mol_type="genomic DNA"
                ORIGIN
                    1 gattaca
                //
                """.trimIndent()
            )
            val seq = SeqIO.read(gb)
            assertEquals("chr1", seq.name)
            assertEquals(7, seq.length)
            assertEquals("GATTACA", seq.bases)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun readGenBankFileWithBom() {
        val dir = createTempDirectory("instagene-test").toFile()
        try {
            val gb = File(dir, "bom.gbk")
            gb.writeText(
                "\uFEFF" + """
                LOCUS       bom                      4 bp    DNA     linear
                ORIGIN
                        1 atgc
                //
                """.trimIndent()
            )

            val seq = SeqIO.read(gb)

            assertEquals("bom", seq.name)
            assertEquals("ATGC", seq.bases)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun genBankExtensionDoesNotFallBackToFastaForMalformedContent() {
        val dir = createTempDirectory("instagene-test").toFile()
        try {
            val gb = File(dir, "bad.gb")
            gb.writeText("not a genbank record\nACGT\n")

            val error = assertFailsWith<SeqIOException> { SeqIO.read(gb) }

            assertTrue(error.message.orEmpty().contains("LOCUS"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun readAllParsesMultipleGenBankRecords() {
        val dir = createTempDirectory("instagene-test").toFile()
        try {
            val file = File(dir, "records.gb")
            file.writeText(
                """
                LOCUS       first                    4 bp    DNA     linear
                ORIGIN
                        1 atgc
                //
                LOCUS       second                   4 bp    DNA     linear
                ORIGIN
                        1 gcat
                //
                """.trimIndent()
            )
            assertEquals(listOf("first", "second"), SeqIO.readAll(file).map { it.name })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun extensionHelpers() {
        val seq = Seq("x", "ACGT")
        assertTrue(seq.toFasta().startsWith(">x"))
        assertTrue(seq.toGenBank().contains("LOCUS"))
        assertTrue(seq.isNucleotide())
        assertTrue(!Seq("p", "MEEK", SeqKind.PROTEIN).isNucleotide())
    }
}
