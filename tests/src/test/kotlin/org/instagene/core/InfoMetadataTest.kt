package org.instagene.core

import org.instagene.core.io.GenBank
import org.instagene.core.io.Embl
import org.instagene.core.io.SeqFormat
import org.instagene.core.io.SeqIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InfoMetadataTest {

    @Test
    fun newRecordsStayUnattributedButImportedReferenceAuthorsProvideAttribution() {
        val newRecord = Seq(name = "new-record", bases = "ACGT")
        assertEquals(null, newRecord.recordMetadata.author)
        assertTrue(newRecord.recordMetadata.references.isEmpty())

        val source = Seq(
            name = "imported-record",
            bases = "ACGT",
            recordMetadata = SequenceRecordMetadata(
                author = null,
                references = listOf(
                    SequenceReference(
                        reference = "[1]",
                        authors = "Researcher A",
                        title = "The source plasmid",
                        journal = "Journal of Plasmid Research",
                        pubMed = "12345678",
                    ),
                ),
            ),
        )

        val restoredGenBank = GenBank.parse(GenBank.write(source))
        val restoredEmbl = Embl.parse(Embl.write(source))
        listOf(restoredGenBank, restoredEmbl).forEach { restored ->
            assertEquals("Researcher A", restored.recordMetadata.author)
            assertEquals(source.recordMetadata.references, restored.recordMetadata.references)
        }
    }

    @Test
    fun bundledPlasmidKeepsRealSourceReferencesAndResearcherAttribution() {
        val plasmid = SeqIO.Samples.PBR322_NCBI

        assertEquals("Sutcliffe,J.G.", plasmid.recordMetadata.author)
        assertTrue(plasmid.recordMetadata.references.any { it.pubMed == "383387" })
        assertTrue(plasmid.recordMetadata.references.any { it.title.contains("Complete nucleotide sequence") })
    }

    @Test
    fun hostInferenceRecognizesCommonBacterialStrainsAndLeavesUnknownHostsConservative() {
        val positive = HostMethylationInferenceRules.infer("E. coli", "DH5α")
        assertEquals(true, positive.profile.dam)
        assertEquals(true, positive.profile.dcm)
        assertEquals("DH5α", positive.matchedHost)

        val strainOnly = HostMethylationInferenceRules.infer(null, "DH5α")
        assertEquals(true, strainOnly.profile.dam)
        assertEquals(true, strainOnly.profile.dcm)

        val negative = HostMethylationInferenceRules.infer("Bacterial", "JM110")
        assertEquals(false, negative.profile.dam)
        assertEquals(false, negative.profile.dcm)

        val unknown = HostMethylationInferenceRules.infer("Mammalian", "HEK293")
        assertEquals(null, unknown.matchedHost)
        assertTrue(unknown.profile.hasUnknown)
    }

    @Test
    fun methylationChangesRestrictionAvailabilityAndPreservesMspIException() {
        val dpn = Seq(name = "dpn", bases = "AAGATCAA")
        val dpnI = Enzymes.ALL.first { it.name == "DpnI" }
        assertEquals(0, Digest.countSites(dpn, dpnI, MethylationProfile(dam = false, dcm = false, cpg = false)))
        assertEquals(1, Digest.countSites(dpn, dpnI, MethylationProfile(dam = true, dcm = false, cpg = false)))

        val cpg = Seq(name = "cpg", bases = "ACCGGA")
        val hpaII = Enzymes.ALL.first { it.name == "HpaII" }
        val mspI = Enzymes.ALL.first { it.name == "MspI" }
        val methylated = MethylationProfile(dam = false, dcm = false, cpg = true)
        assertEquals(0, Digest.countSites(cpg, hpaII, methylated))
        assertEquals(1, Digest.countSites(cpg, mspI, methylated))
    }

    @Test
    fun genBankRoundTripPersistsInfoTabMetadataAndChemistry() {
        val original = Seq(
            name = "record",
            bases = "ACGTACGT",
            description = "A record with metadata",
            molecule = MoleculeProperties(
                damMethylated = true,
                dcmMethylated = false,
                cpgMethylated = true,
                methylationSource = MethylationSource.MANUAL,
            ),
            recordMetadata = SequenceRecordMetadata(
                comments = listOf("first comment", "second comment"),
                references = listOf(SequenceReference(
                    reference = "[1]",
                    authors = "Author A",
                    title = "A paper",
                    journal = "Journal",
                    pubMed = "12345678",
                    sourceUrl = "https://example.org/paper",
                )),
                freeformReferences = listOf("10.1000/example"),
                author = "Record author",
                nucleicAcidCategory = "Bacterial",
                labHostType = "Bacterial",
                hostStrain = "DH5α",
                origin = SequenceOrigin.SYNTHETIC,
                originLocked = true,
                createdAt = 1_700_000_000_000,
                modifiedAt = 1_700_000_100_000,
            ),
        )

        val restored = GenBank.parse(GenBank.write(original))
        assertEquals(original.recordMetadata.author, restored.recordMetadata.author)
        assertEquals(original.recordMetadata.comments, restored.recordMetadata.comments)
        assertEquals(original.recordMetadata.freeformReferences, restored.recordMetadata.freeformReferences)
        assertEquals(original.recordMetadata.references, restored.recordMetadata.references)
        assertEquals(original.recordMetadata.nucleicAcidCategory, restored.recordMetadata.nucleicAcidCategory)
        assertEquals(original.recordMetadata.labHostType, restored.recordMetadata.labHostType)
        assertEquals(original.recordMetadata.hostStrain, restored.recordMetadata.hostStrain)
        assertEquals(original.recordMetadata.origin, restored.recordMetadata.origin)
        assertEquals(original.recordMetadata.originLocked, restored.recordMetadata.originLocked)
        assertEquals(original.recordMetadata.createdAt, restored.recordMetadata.createdAt)
        assertEquals(original.recordMetadata.modifiedAt, restored.recordMetadata.modifiedAt)
        assertEquals(original.molecule.damState, restored.molecule.damState)
        assertEquals(original.molecule.dcmState, restored.molecule.dcmState)
        assertEquals(original.molecule.cpgState, restored.molecule.cpgState)
    }

    @Test
    fun sequenceClassRoundTripsThroughGenBankAndEmblCompatibilityField() {
        val original = Seq(
            name = "record",
            bases = "ACGT",
            recordMetadata = SequenceRecordMetadata(nucleicAcidCategory = "Expressed sequence tag"),
        )

        assertEquals("Expressed sequence tag", GenBank.parse(GenBank.write(original)).recordMetadata.nucleicAcidCategory)
        assertEquals("Expressed sequence tag", Embl.parse(Embl.write(original)).recordMetadata.nucleicAcidCategory)
    }

    @Test
    fun unknownMethylationStatesRemainUnknownThroughManualEditsAndFlatFileRoundTrips() {
        val unknown = MoleculeProperties().withMethylation(null, null, null)
        assertEquals(MethylationState.UNKNOWN, unknown.damState)
        assertEquals(MethylationState.UNKNOWN, unknown.dcmState)
        assertEquals(MethylationState.UNKNOWN, unknown.cpgState)

        val original = Seq(
            name = "unknown-chemistry",
            bases = "AAGATCAA",
            molecule = unknown,
            recordMetadata = SequenceRecordMetadata(
                author = "Record author",
                comments = listOf("A comment"),
                freeformReferences = listOf("https://example.org/source"),
                nucleicAcidCategory = "Bacterial",
                labHostType = "Custom lab host",
                hostStrain = "strain-x",
                origin = SequenceOrigin.SYNTHETIC,
                originLocked = true,
                createdAt = 1_700_000_000_000,
                modifiedAt = 1_700_000_100_000,
            ),
        )
        val restoredGenBank = GenBank.parse(GenBank.write(original))
        val restoredEmbl = Embl.parse(Embl.write(original))
        assertEquals(original.recordMetadata.author, restoredEmbl.recordMetadata.author)
        assertEquals(original.recordMetadata.comments, restoredEmbl.recordMetadata.comments)
        assertEquals(original.recordMetadata.freeformReferences, restoredEmbl.recordMetadata.freeformReferences)
        assertEquals(original.recordMetadata.nucleicAcidCategory, restoredEmbl.recordMetadata.nucleicAcidCategory)
        assertEquals(original.recordMetadata.labHostType, restoredEmbl.recordMetadata.labHostType)
        assertEquals(original.recordMetadata.hostStrain, restoredEmbl.recordMetadata.hostStrain)
        assertEquals(original.recordMetadata.origin, restoredEmbl.recordMetadata.origin)
        assertEquals(original.recordMetadata.originLocked, restoredEmbl.recordMetadata.originLocked)
        assertEquals(original.recordMetadata.createdAt, restoredEmbl.recordMetadata.createdAt)
        assertEquals(original.recordMetadata.modifiedAt, restoredEmbl.recordMetadata.modifiedAt)
        listOf(restoredGenBank, restoredEmbl).forEach { restored ->
            assertEquals(MethylationState.UNKNOWN, restored.molecule.damState)
            assertEquals(MethylationState.UNKNOWN, restored.molecule.dcmState)
            assertEquals(MethylationState.UNKNOWN, restored.molecule.cpgState)
        }

        @Test
        fun explicitMethylationWithoutSourceIsMarkedImported() {
            val original = Seq(
                name = "imported-chemistry",
                bases = "AAGATCAA",
                molecule = MoleculeProperties(
                    damMethylated = true,
                    dcmMethylated = false,
                    cpgMethylated = true,
                    methylationSource = MethylationSource.UNKNOWN,
                ),
            )
            val genBank = GenBank.write(original).replace("IG_MSRC", "IG_NO_SRC")
            val embl = Embl.write(original).replace("IG_METHYL_SRC", "IG_NO_SRC")
            assertEquals(MethylationSource.IMPORTED, GenBank.parse(genBank).molecule.methylationSource)
            assertEquals(MethylationSource.IMPORTED, Embl.parse(embl).molecule.methylationSource)
        }

        val dpnI = Enzymes.require("DpnI")
        val profile = MethylationProfile.from(original.molecule)
        assertEquals(1, Digest.countSites(original, dpnI, profile))
        val warning = MethylationRules.uncertainty(profile).orEmpty()
        assertTrue(warning.contains("Dam"))
        assertTrue(warning.contains("Dcm"))
        assertTrue(warning.contains("CpG"))
    }

    @Test
    fun metadataRichRecordsPreferGenBankForSaving() {
        val record = Seq(
            bases = "ACGT",
            recordMetadata = SequenceRecordMetadata(author = "Author"),
        )
        assertEquals(SeqFormat.GENBANK, SeqIO.preferredSaveFormat(record))
    }
}
