package org.instagene.core

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ElnExchangeTest {

    @Test
    fun genericBundleIncludesStandardAttachmentsHashesAndProvenance() {
        val sequence = Seq(
            name = "p,Demo",
            bases = "ACGT".repeat(20),
            topology = Topology.CIRCULAR,
            features = listOf(Feature("ampR", "CDS", 4, 40)),
            primers = listOf(PrimerAnnotation("Fwd, primer", "ACGT", 2, 6, extension = "GG", description = "quoted \"note\"")),
            metadata = mapOf("SOURCE_SHA256" to "source-hash"),
            provenance = listOf(ProcedureRecord("assemble", "joined vector and insert", inputs = listOf("vector"), timestamp = 42)),
        )
        val bundle = File.createTempFile("instagene-eln", ".zip")
        try {
            val manifest = ElnAdapters.GENERIC_ZIP.export(
                bundle,
                ElnBundleRequest(
                    title = "Demo handoff",
                    sequence = sequence,
                    reports = listOf(ElnReport("reports/custom.md", "# Custom report")),
                    attachments = listOf(ElnAttachment("maps/p-demo.svg", "<svg/>".encodeToByteArray(), "image/svg+xml", ElnArtifactRole.MAP_SVG)),
                    provenance = mapOf("project" to "example", "workflow" to "restriction-cloning"),
                    createdAt = "2026-08-23T00:00:00Z",
                ),
            )

            assertEquals(GenericZipElnAdapter.ID, manifest.bundleType)
            assertEquals(1, manifest.schemaVersion)
            assertEquals(SequenceIdentity.cdseguid(sequence), manifest.sequence?.cdseguid)
            assertEquals(listOf("assemble"), manifest.procedures.map { it.operation })
            assertTrue(manifest.artifacts.any { it.role == ElnArtifactRole.SEQUENCE_FASTA })
            assertTrue(manifest.artifacts.any { it.role == ElnArtifactRole.SEQUENCE_GENBANK })
            assertTrue(manifest.artifacts.any { it.role == ElnArtifactRole.PRIMER_CSV })
            assertTrue(manifest.artifacts.any { it.role == ElnArtifactRole.REPORT_MARKDOWN })
            assertTrue(manifest.artifacts.any { it.role == ElnArtifactRole.MAP_SVG })
            assertEquals(manifest.artifacts.map { it.path }.sorted(), manifest.artifacts.map { it.path })
            assertTrue(manifest.artifacts.all { it.sha256.length == 64 && it.bytes >= 0 })
            assertTrue(GenericZipElnAdapter.verify(bundle).valid)

            ZipFile(bundle).use { zip ->
                assertTrue(zip.getEntry(GenericZipElnAdapter.MANIFEST_NAME) != null)
                val csv = zip.getInputStream(zip.entries().asSequence().first { it.name.endsWith("-primers.csv") }).readBytes().decodeToString()
                assertTrue(csv.contains("\"Fwd, primer\""))
                assertTrue(csv.contains("\"quoted \"\"note\"\"\""))
            }
        } finally {
            bundle.delete()
        }
    }

    @Test
    fun adapterIsOfflineAndRejectsUnsafeOrDuplicateAttachmentPaths() {
        assertFalse(ElnAdapters.GENERIC_ZIP.supportsLiveSync)
        val bundle = File.createTempFile("instagene-eln", ".zip")
        try {
            kotlin.test.assertFailsWith<IllegalArgumentException> {
                ElnAdapters.GENERIC_ZIP.export(
                    bundle,
                    ElnBundleRequest("unsafe", attachments = listOf(ElnAttachment("../outside.txt", byteArrayOf(1), "application/octet-stream"))),
                )
            }
            kotlin.test.assertFailsWith<IllegalArgumentException> {
                ElnAdapters.GENERIC_ZIP.export(
                    bundle,
                    ElnBundleRequest(
                        "duplicate",
                        attachments = listOf(
                            ElnAttachment("same.txt", byteArrayOf(1), "text/plain"),
                            ElnAttachment("same.txt", byteArrayOf(2), "text/plain"),
                        ),
                    ),
                )
            }
        } finally {
            bundle.delete()
        }
    }

    @Test
    fun verificationRejectsAnUndeclaredZipAttachment() {
        val original = File.createTempFile("instagene-eln", ".zip")
        val tampered = File.createTempFile("instagene-eln-tampered", ".zip")
        try {
            ElnAdapters.GENERIC_ZIP.export(original, ElnBundleRequest("handoff", sequence = Seq("p", "ACGT")))
            ZipFile(original).use { source ->
                ZipOutputStream(tampered.outputStream()).use { target ->
                    source.entries().asSequence().forEach { entry ->
                        target.putNextEntry(ZipEntry(entry.name))
                        source.getInputStream(entry).use { it.copyTo(target) }
                        target.closeEntry()
                    }
                    target.putNextEntry(ZipEntry("unexpected.txt"))
                    target.write("not recorded in manifest".encodeToByteArray())
                    target.closeEntry()
                }
            }

            val verification = GenericZipElnAdapter.verify(tampered)
            assertFalse(verification.valid)
            assertTrue(verification.problems.any { it.contains("Undeclared attachment") })
        } finally {
            original.delete()
            tampered.delete()
        }
    }
}
