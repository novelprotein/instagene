package org.instagene.app.gui

import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.tool.InfoPanel
import org.instagene.core.MethylationState
import org.instagene.core.Seq
import org.instagene.core.SequenceRecordMetadata
import org.instagene.core.SequenceReference
import org.instagene.core.SequenceOrigin
import org.instagene.core.SequenceClassCatalog
import java.net.URI
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InfoPanelMetadataTest {

    @Test
    fun infoPanelShowsResolvedAuthorAndActualStructuredReferences() {
        onEdt {
            val document = SeqDocument(
                Seq(
                    name = "imported",
                    bases = "ACGT",
                    recordMetadata = SequenceRecordMetadata(
                        author = null,
                        references = listOf(
                            SequenceReference(
                                reference = "[1]",
                                authors = "Researcher A",
                                title = "The source plasmid",
                                journal = "Journal",
                                pubMed = "12345678",
                            ),
                        ),
                    ),
                ),
            )
            val panel = InfoPanel(document)

            assertEquals("Researcher A", panel.authorField.text)
            assertEquals(1, panel.referencesTable.model.rowCount)
            assertEquals("The source plasmid", panel.referencesTable.model.getValueAt(0, 2))
        }
    }

    @Test
    fun infoPanelLeavesNewRecordsBlankWithoutProvenance() {
        onEdt {
            val document = SeqDocument(Seq(name = "new", bases = "ACGT"))
            val panel = InfoPanel(document)

            assertEquals("", panel.authorField.text)
            assertEquals(0, panel.referencesTable.model.rowCount)
            assertEquals("-", panel.ncbiSourceLabel.text)
            assertEquals(false, panel.openNcbiSourceButton.isEnabled)
        }
    }

    @Test
    fun infoPanelShowsAndOpensOnlyAnExplicitNcbiSourceUrl() {
        onEdt {
            val sourceUrl = "https://www.ncbi.nlm.nih.gov/nuccore/J01749.1"
            val opened = mutableListOf<URI>()
            val panel = InfoPanel(
                SeqDocument(Seq(name = "source", bases = "ACGT", metadata = mapOf("ONLINE_URL" to sourceUrl))),
                {},
                null,
                { opened += it },
                {},
            )

            assertEquals(sourceUrl, panel.ncbiSourceLabel.text)
            assertTrue(panel.openNcbiSourceButton.isEnabled)
            panel.openNcbiSourceButton.doClick()
            assertEquals(listOf(URI.create(sourceUrl)), opened)
        }
    }

    @Test
    fun infoPanelRejectsNonNcbiSourceUrls() {
        onEdt {
            val panel = InfoPanel(
                SeqDocument(Seq(name = "source", bases = "ACGT", metadata = mapOf("ONLINE_URL" to "https://example.org/record"))),
            )

            assertEquals("-", panel.ncbiSourceLabel.text)
            assertEquals(false, panel.openNcbiSourceButton.isEnabled)
        }
    }

    @Test
    fun infoPanelAppliesEditableRecordMetadataAndHostInference() {
        onEdt {
            val document = SeqDocument(Seq(name = "record", bases = "AAGATCAA"))
            val panel = InfoPanel(document)

            panel.authorField.text = "Record author"
            panel.nucleicAcidCategoryCombo.selectedItem = "Bacterial"
            panel.labHostTypeCombo.selectedItem = "Bacterial"
            panel.hostStrainField.text = "DH5α"
            panel.originCombo.selectedItem = SequenceOrigin.SYNTHETIC
            panel.originLockCheck.isSelected = true
            panel.commentsArea.text = "first comment\nsecond comment"
            panel.freeformReferencesArea.text = "10.1000/example"
            panel.applyMetadataButton.doClick()

            assertEquals("Record author", document.seq.recordMetadata.author)
            assertEquals("Bacterial", document.seq.recordMetadata.nucleicAcidCategory)
            assertEquals("Bacterial", document.seq.recordMetadata.labHostType)
            assertEquals("DH5α", document.seq.recordMetadata.hostStrain)
            assertEquals(SequenceOrigin.SYNTHETIC, document.seq.recordMetadata.origin)
            assertEquals(true, document.seq.recordMetadata.originLocked)
            assertEquals(listOf("first comment", "second comment"), document.seq.recordMetadata.comments)
            assertEquals(listOf("10.1000/example"), document.seq.recordMetadata.freeformReferences)
            assertEquals(MethylationState.METHYLATED, document.seq.molecule.damState)
            assertEquals(MethylationState.METHYLATED, document.seq.molecule.dcmState)
            assertEquals(MethylationState.UNKNOWN, document.seq.molecule.cpgState)
            assertNotNull(document.seq.recordMetadata.createdAt)
            assertNotNull(document.seq.recordMetadata.modifiedAt)
        }
    }

    @Test
    fun sequenceClassUsesGroupedChoicesButStoresOneExplicitValue() {
        onEdt {
            val document = SeqDocument(Seq(name = "record", bases = "ACGT"))
            val panel = InfoPanel(document)

            assertEquals("Sequence class", panel.sequenceClassLabel.text)
            assertEquals(SequenceClassCatalog.dropdownItems, (0 until panel.sequenceClassCombo.itemCount).map(panel.sequenceClassCombo::getItemAt))
            assertTrue(SequenceClassCatalog.dropdownItems.contains("Plant (PLT)"))
            assertTrue(SequenceClassCatalog.dropdownItems.contains("Expressed sequence tag (EST)"))

            panel.sequenceClassCombo.selectedItem = "Biological source"
            assertEquals("", panel.sequenceClassCombo.selectedItem)

            panel.sequenceClassCombo.editor.item = "Biological source"
            panel.applyMetadataButton.doClick()
            assertEquals(null, document.seq.recordMetadata.nucleicAcidCategory)

            panel.sequenceClassCombo.selectedItem = "Expressed sequence tag"
            assertEquals("EST", panel.sequenceClassCodeLabel.text)
            panel.applyMetadataButton.doClick()
            assertEquals("Expressed sequence tag", document.seq.recordMetadata.nucleicAcidCategory)

            panel.sequenceClassCombo.selectedItem = "Bacterial"
            assertEquals("BCT", panel.sequenceClassCodeLabel.text)

            panel.sequenceClassCombo.selectedItem = "Plant"
            assertEquals("PLT", panel.sequenceClassCodeLabel.text)
        }
    }

    @Test
    fun sequenceClassPreservesStoredCustomValues() {
        onEdt {
            val document = SeqDocument(
                Seq(
                    name = "record",
                    bases = "ACGT",
                    recordMetadata = SequenceRecordMetadata(nucleicAcidCategory = "Legacy classification"),
                ),
            )
            val panel = InfoPanel(document)

            assertEquals("Legacy classification", panel.sequenceClassCombo.editor.item)
            panel.applyMetadataButton.doClick()
            assertEquals("Legacy classification", document.seq.recordMetadata.nucleicAcidCategory)
        }
    }

    @Test
    fun copyAndApplyIdentityUseReliableInteractionSeamsAndDescriptionStartsCompact() {
        onEdt {
            val document = SeqDocument(Seq(name = "record", bases = "ACGT"))
            val statuses = mutableListOf<String>()
            val copied = mutableListOf<String>()
            val panel = InfoPanel(
                document,
                {},
                null,
                {},
                { statuses += it },
                { copied += it },
            )

            assertEquals(2, panel.descriptionField.rows)
            panel.copyIdentityButton.doClick()
            assertEquals(1, copied.size)
            assertTrue(copied.single().startsWith("cdseguid-"))
            assertEquals("Copied sequence identity", statuses.last())

            panel.applyIdentityButton.doClick()
            assertEquals(copied.single(), document.seq.uniqueIdentifier)
            assertEquals("Sequence identity applied", statuses.last())
            assertTrue(panel.descriptionField.lineWrap)
        }
    }

    private fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: T? = null
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait {
            try {
                result = block()
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
