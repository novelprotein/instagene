package org.instagene.app.gui.menu

import org.instagene.app.gui.prefs.Prefs
import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.tool.SequenceView
import org.instagene.core.SeqKind
import java.awt.event.KeyEvent
import javax.swing.JCheckBoxMenuItem
import javax.swing.JMenu
import javax.swing.JMenuItem

class ViewMenu(
    private val doc: SeqDocument,
    private val sequenceView: SequenceView,
    private val prefs: Prefs = Prefs(),
    private val isFileBrowserVisible: () -> Boolean = { true },
    private val onFileBrowserVisible: (Boolean) -> Unit = {},
) {

    private val complementItem = JCheckBoxMenuItem("Show Complement Strand", true)
    private val translationItem = JCheckBoxMenuItem("Show Translation", false)
    private val historyColorsItem = JCheckBoxMenuItem("Show Recent Change Color", false)

    init {
        doc.addListener { _, reason ->
            if (reason == SeqDocument.Reason.SEQUENCE) updateEnabled()
        }
        updateEnabled()
    }

    /** Complement and translation tracks only exist for DNA/RNA sequences. */
    private fun updateEnabled() {
        val nucleotide = doc.seq.kind != SeqKind.PROTEIN
        complementItem.isEnabled = nucleotide
        translationItem.isEnabled = nucleotide
    }

    fun create(): JMenu {
        return JMenu("View").apply {
            mnemonic = KeyEvent.VK_V

            add(complementItem.apply {
                addActionListener {
                    sequenceView.showComplement = isSelected
                }
            })
            add(translationItem.apply {
                addActionListener {
                    sequenceView.showTranslation = isSelected
                }
            })
            add(historyColorsItem.apply {
                toolTipText = "Highlight bases changed by the most recent edit, undo, or redo."
                addActionListener { sequenceView.showHistoryColors = isSelected }
            })
            addSeparator()
            add(createZoomInItem())
            add(createZoomOutItem())
            add(createResetZoomItem())
            addSeparator()
            add(createThemeMenu(prefs))
            addSeparator()
            syncFileBrowser()
            add(fileBrowserItem)
        }
    }

    /**
     * The "Show File Browser" toggle. A single persistent instance, kept in
     * step with the browser's actual (minimized/expanded) state via
     * [syncFileBrowser].
     */
    private val fileBrowserItem = JCheckBoxMenuItem("Show File Browser", isFileBrowserVisible()).apply {
        accelerator = menuShortcut(KeyEvent.VK_B)
        addActionListener { onFileBrowserVisible(isSelected) }
    }

    /** Reflects the project browser's current state in [fileBrowserItem]. */
    fun syncFileBrowser() {
        fileBrowserItem.isSelected = isFileBrowserVisible()
    }

    private fun createZoomInItem(): JMenuItem {
        return JMenuItem("Zoom In", KeyEvent.VK_PLUS).apply {
            accelerator = menuShortcut(KeyEvent.VK_PLUS)
            addActionListener {
                val current = sequenceView.fontSize()
                sequenceView.setFontSize(current + 1)
            }
        }
    }

    private fun createZoomOutItem(): JMenuItem {
        return JMenuItem("Zoom Out", KeyEvent.VK_MINUS).apply {
            accelerator = menuShortcut(KeyEvent.VK_MINUS)
            addActionListener {
                val current = sequenceView.fontSize()
                sequenceView.setFontSize(current - 1)
            }
        }
    }

    private fun createResetZoomItem(): JMenuItem {
        return JMenuItem("Reset Zoom", KeyEvent.VK_0).apply {
            accelerator = menuShortcut(KeyEvent.VK_0)
            addActionListener {
                sequenceView.setFontSize(14)
            }
        }
    }
}
