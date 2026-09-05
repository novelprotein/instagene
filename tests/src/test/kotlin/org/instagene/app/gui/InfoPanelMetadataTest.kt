package org.instagene.app.gui

import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.edit.SequenceEditService
import org.instagene.app.gui.prefs.Prefs
import org.instagene.app.gui.tool.InfoPanel
import org.instagene.core.MethylationState
import org.instagene.core.MethylationSource
import org.instagene.core.Seq
import org.instagene.core.io.SeqIO
import org.instagene.core.SequenceRecordMetadata
import org.instagene.core.SequenceReference
import org.instagene.core.SequenceOrigin
import org.instagene.core.SequenceClassCatalog
import java.net.URI
import java.awt.event.FocusEvent
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.border.TitledBorder
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
            assertEquals("", panel.ncbiSourceLabel.text)
            assertEquals(false, panel.openNcbiSourceButton.isEnabled)
        }

    }

    @Test
    fun infoPanelUsesResponsiveSizingWithoutExcessiveMargins() {
        onEdt {
            val panel = InfoPanel(SeqDocument(Seq(name = "sized", bases = "ACGT")))

            assertTrue(panel.preferredSize.width <= 800)
            assertTrue(panel.preferredSize.height <= 500)
            assertTrue(panel.minimumSize.width < panel.preferredSize.width)
            assertTrue(panel.minimumSize.height < panel.preferredSize.height)
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

            assertEquals("https://example.org/record", panel.ncbiSourceLabel.text)
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
            assertEquals(false, document.seq.recordMetadata.originLocked)
            assertEquals(listOf("first comment", "second comment"), document.seq.recordMetadata.comments)
            assertEquals(listOf("10.1000/example"), document.seq.recordMetadata.freeformReferences)
            assertEquals(MethylationState.METHYLATED, document.seq.molecule.damState)
            assertEquals(MethylationState.METHYLATED, document.seq.molecule.dcmState)
            assertEquals(MethylationState.UNKNOWN, document.seq.molecule.cpgState)
            assertEquals(MethylationSource.INFERRED, document.seq.molecule.methylationSource)
            assertNotNull(document.seq.recordMetadata.createdAt)
            assertNotNull(document.seq.recordMetadata.modifiedAt)
        }
    }

    @Test
    fun unknownHostClearsStaleMethylationInference() {
        onEdt {
            val panel = InfoPanel(SeqDocument(Seq(name = "record", bases = "AAGATCAA")))
            panel.labHostTypeCombo.selectedItem = "Bacterial"
            panel.hostStrainField.text = "DH5α"
            panel.inferMethylationButton.doClick()
            assertEquals(MethylationState.METHYLATED, panel.damMethylationCombo.selectedItem)

            panel.hostStrainField.text = "unknown host"
            panel.inferMethylationButton.doClick()
            assertEquals(MethylationState.UNKNOWN, panel.damMethylationCombo.selectedItem)
            assertEquals(MethylationState.UNKNOWN, panel.dcmMethylationCombo.selectedItem)
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
    fun infoPanelConsolidatesPropertiesAndReferencesAndKeepsOnlyRecordDates() {
        onEdt {
            val document = SeqDocument(Seq(name = "record", bases = "ACGT"))
            val panel = InfoPanel(document)

            val titles = titledPanels(panel)
            assertTrue("Properties" in titles)
            assertTrue("Record metadata" in titles)
            assertTrue("Statistics" in titles)
            assertTrue("References" in titles)
            assertTrue("Methylation" !in titles)
            assertTrue("Scientific references" !in titles)
            assertTrue("File" !in titles)
            assertTrue(panel.damMethylationCombo.isVisible)
            assertTrue(panel.commentsArea.closestTitledPanel("Record metadata"))
            assertTrue(!panel.commentsArea.closestTitledPanel("References"))
            assertTrue(panel.freeformReferencesArea.closestTitledPanel("References"))
            assertEquals("5′→3′ / 3′→5′; 5′ phosphorylated", panel.orientationAndEndChemistryLabel.text)
            assertEquals("-", panel.createdDateLabel.text)
            assertEquals("-", panel.modifiedDateLabel.text)
            assertTrue(!panel.openFileButton.isVisible)
            assertTrue(!panel.fileLabel.isVisible)
        }
    }

    @Test
    fun bundledExamplesExposeBothRecordDates() {
        onEdt {
            SeqIO.Samples.ALL.forEach { sample ->
                val panel = InfoPanel(SeqDocument(sample))
                assertTrue(panel.createdDateLabel.text != "-", sample.name)
                assertTrue(panel.modifiedDateLabel.text != "-", sample.name)
                assertNotNull(sample.recordMetadata.createdAt, sample.name)
            }
        }
    }

    @Test
    fun infoPanelAppliesAndOpensEditableNcbiAccession() {
        onEdt {
            val document = SeqDocument(Seq(name = "record", bases = "ACGT"))
            val opened = mutableListOf<URI>()
            val panel = InfoPanel(document, {}, null, { opened += it }, {}, {}, Prefs())

            panel.ncbiSourceField.text = "J01749.1"
            assertTrue(panel.openNcbiSourceButton.isEnabled)
            panel.openNcbiSourceButton.doClick()
            panel.applyMetadataButton.doClick()

            val expected = "https://www.ncbi.nlm.nih.gov/nuccore/J01749.1"
            assertEquals(listOf(URI.create(expected)), opened)
            assertEquals(expected, document.seq.metadata["ONLINE_URL"])
            assertEquals("J01749.1", document.seq.metadata["ONLINE_ACCESSION"])

            panel.ncbiSourceField.text = "https://example.org/not-ncbi"
            panel.applyMetadataButton.doClick()
            assertEquals(expected, document.seq.metadata["ONLINE_URL"])
        }
    }

    @Test
    fun hostSuggestionsIncludeCommonValuesAndRememberCustomStrains() {
        onEdt {
            val prefs = Prefs()
            val document = SeqDocument(Seq(name = "record", bases = "ACGT"))
            val panel = InfoPanel(document, {}, null, {}, {}, {}, prefs)

            assertTrue((0 until panel.labHostTypeCombo.itemCount).map(panel.labHostTypeCombo::getItemAt).contains("Plant"))
            assertTrue((0 until panel.hostStrainCombo.itemCount).map(panel.hostStrainCombo::getItemAt).contains("DH5α"))
            assertTrue(panel.hostStrainCombo.itemCount > 5)
            panel.hostStrainField.text = "My local host"
            panel.hostStrainField.dispatchEvent(FocusEvent(panel.hostStrainField, FocusEvent.FOCUS_GAINED))
            assertEquals("My local host", panel.hostStrainField.text)
            panel.applyMetadataButton.doClick()

            assertTrue(prefs.value.hostStrainSuggestions.contains("My local host"))
            assertTrue((0 until panel.hostStrainCombo.itemCount).map(panel.hostStrainCombo::getItemAt).contains("My local host"))
        }

        @Test
        fun focusingHostFieldRanksTaxonomyMatchedHostsInDropdown() {
            onEdt {
                val document = SeqDocument(
                    Seq(
                        name = "record",
                        bases = "ACGT",
                        recordMetadata = SequenceRecordMetadata(organism = "Escherichia coli"),
                    ),
                )
                val panel = InfoPanel(document)

                panel.hostStrainField.dispatchEvent(FocusEvent(panel.hostStrainField, FocusEvent.FOCUS_GAINED))

                assertEquals("DH5α", panel.hostStrainCombo.getItemAt(1))
            }
        }
    }

    @Test
    fun naturalSequenceLockBlocksBasesButAllowsMetadataAndAnnotations() {
        onEdt {
            val document = SeqDocument(
                Seq(
                    name = "natural",
                    bases = "ACGT",
                    recordMetadata = SequenceRecordMetadata(origin = SequenceOrigin.NATURAL),
                ),
            )
            val panel = InfoPanel(document)

            assertTrue(panel.originLockCheck.isVisible)
            assertEquals(false, panel.originLockCheck.isSelected)
            panel.originLockCheck.doClick()
            assertTrue(document.sequenceEditingLocked)
            assertEquals(false, SequenceEditService.insert(document, "A"))
            assertEquals("ACGT", document.seq.bases)
            assertTrue(document.mutate("add annotation") { it.withComment("annotated") })
            assertEquals(listOf("annotated"), document.seq.recordMetadata.comments)

            panel.originCombo.selectedItem = SequenceOrigin.SYNTHETIC
            assertTrue(!panel.originLockCheck.isVisible)
            assertTrue(!document.sequenceEditingLocked)
            assertTrue(SequenceEditService.insert(document, "A"))
            assertEquals("AACGT", document.seq.bases)
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

    private fun titledPanels(root: JComponent): List<String> = buildList {
        fun visit(component: java.awt.Component) {
            val titled = (component as? JComponent)?.border as? TitledBorder
            titled?.title?.let(::add)
            if (component is java.awt.Container) component.components.forEach(::visit)
        }
        visit(root)
    }

    private fun JComponent.closestTitledPanel(title: String): Boolean {
        var current: java.awt.Container? = this
        while (current != null) {
            val titled = (current as? JComponent)?.border as? TitledBorder
            if (titled?.title == title) return true
            current = current.parent
        }
        return false
    }
}
