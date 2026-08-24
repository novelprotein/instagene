package org.instagene.core

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LabLibraryFilesTest {

    @Test
    fun featureLibraryRoundTripsWithSchemaAndFullRuleMetadata() {
        val source = LabLibraryFiles.featureLibrary(
            name = "Lab annotation rules",
            description = "Reviewed local patterns",
            definitions = listOf(
                FeatureDefinition("Exclusion", "AAAAAAAA", "misc_feature", Strand.REVERSE, "#123456", uppercaseOnly = true, exclude = true),
                FeatureDefinition("Promoter", "tatAAA", "promoter"),
            ),
        )

        val decoded = LabLibraryFiles.decodeFeatureLibrary(LabLibraryFiles.encode(source))

        assertEquals(LabLibraryFiles.SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals("Lab annotation rules", decoded.name)
        assertEquals(listOf("Exclusion", "Promoter"), decoded.definitions.map { it.name })
        assertEquals("TATAAA", decoded.definitions.single { it.name == "Promoter" }.pattern)
        assertTrue(decoded.definitions.single { it.name == "Exclusion" }.exclude)
        assertTrue(decoded.definitions.single { it.name == "Exclusion" }.uppercaseOnly)
    }

    @Test
    fun enzymeSetRoundTripsAtomicallyAndRetainsCustomDefinitions() {
        val root = Files.createTempDirectory("instagene-enzyme-set").toFile().apply { deleteOnExit() }
        val output = root.resolve("lab${LabLibraryFiles.ENZYME_SET_SUFFIX}")
        val source = LabLibraryFiles.enzymeSet(
            "Lab cloning set",
            listOf(Enzyme("LabI", "GATC", 1, 3, supplier = "Lab stock"), Enzymes.require("EcoRI")),
        )

        LabLibraryFiles.write(output, source)
        val decoded = LabLibraryFiles.readEnzymeSet(output)

        assertTrue(output.isFile)
        assertEquals(listOf("EcoRI", "LabI"), decoded.enzymes.map { it.name })
        assertEquals("Lab stock", decoded.enzymes.single { it.name == "LabI" }.supplier)
        assertContains(output.readText(StandardCharsets.UTF_8), "schemaVersion")
    }

    @Test
    fun mergesAreStableAndFutureSchemaIsRejected() {
        val existing = listOf(FeatureDefinition("promoter", "TATAAA", "promoter"))
        val imported = listOf(
            FeatureDefinition("Promoter", "TATAAA", "promoter"),
            FeatureDefinition("tag", "CACCACCACC", "CDS"),
        )

        val merged = LabLibraryFiles.mergeDefinitions(existing, imported, LibraryImportMode.MERGE)

        assertEquals(listOf("promoter", "tag"), merged.map { it.name })
        val future = FeatureLibraryFile(schemaVersion = LabLibraryFiles.SCHEMA_VERSION + 1, definitions = existing)
        assertFailsWith<IllegalArgumentException> { LabLibraryFiles.decodeFeatureLibrary(LabLibraryFiles.encode(future)) }
    }
}
