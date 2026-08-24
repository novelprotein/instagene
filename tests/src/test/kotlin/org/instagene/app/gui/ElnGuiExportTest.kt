package org.instagene.app.gui

import org.instagene.core.ElnArtifactRole
import org.instagene.core.Feature
import org.instagene.core.GenericZipElnAdapter
import org.instagene.core.Seq
import org.instagene.core.Topology
import java.io.File
import java.nio.file.Files
import javax.swing.JMenu
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue

class ElnGuiExportTest {

    private fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: T? = null
        var error: Throwable? = null
        SwingUtilities.invokeAndWait {
            try {
                result = block()
            } catch (failure: Throwable) {
                error = failure
            }
        }
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    @Test
    fun activeSequenceExportsCopyableSummaryAndMapContainingGenericElnBundle() {
        val directory = Files.createTempDirectory("instagene-gui-eln").toFile()
        val bundle = File(directory, "handoff.zip")
        val content = onEdt { InstaGeneContent() }
        try {
            onEdt {
                content.openSequence(
                    Seq(
                        name = "pELN",
                        bases = "ACGT".repeat(80),
                        topology = Topology.CIRCULAR,
                        features = listOf(Feature("reporter", "CDS", 10, 120)),
                    ),
                )
            }

            val summary = onEdt { content.activeElnSummary() }
            assertTrue(summary.orEmpty().contains("Stable identity"))
            onEdt {
                val projectMenu = (0 until content.menuBar.menuCount)
                    .mapNotNull(content.menuBar::getMenu)
                    .first { it.text == "Project" }
                val elnMenu = projectMenu.menuComponents.filterIsInstance<JMenu>().single { it.text == "ELN / Lab Notebook" }
                assertTrue(elnMenu.isEnabled)
                assertTrue((0 until elnMenu.itemCount).mapNotNull { elnMenu.getItem(it)?.text }.contains("Export Generic ELN/LIMS Bundle..."))
            }
            val manifest = onEdt { content.exportActiveSequenceElnBundle(bundle) }
            assertTrue(manifest.artifacts.any { it.role == ElnArtifactRole.MAP_SVG })
            assertTrue(manifest.artifacts.any { it.role == ElnArtifactRole.PRIMER_CSV })
            assertTrue(GenericZipElnAdapter.verify(bundle).valid)
        } finally {
            onEdt { content.dispose() }
            directory.deleteRecursively()
        }
    }
}
