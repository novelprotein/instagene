package org.instagene.app.gui.menu

import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.tool.SequenceView
import org.instagene.core.SeqKind
import java.awt.event.KeyEvent
import javax.swing.JCheckBoxMenuItem
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.KeyStroke

class ViewMenu(
    private val doc: SeqDocument,
    private val sequenceView: SequenceView,
    private val isFileBrowserVisible: () -> Boolean = { true },
    private val onFileBrowserVisible: (Boolean) -> Unit = {},
    private val onSelectToolTab: ((String) -> Unit)? = null,
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
            add(createPanelsMenu())
            addSeparator()
            syncFileBrowser()
            add(fileBrowserItem)
            addSeparator()
            add(createFullScreenItem())
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

    /** Navigation to each of the main tool tabs below the sequence editor. */
    private fun createPanelsMenu(): JMenu = JMenu("Panels").apply {
        listOf(
            "Info", "Map", "Sequence", "Enzyme", "Analysis",
            "Features", "Primers", "Library", "History",
        ).forEach { name ->
            add(JMenuItem(name).apply {
                addActionListener { onSelectToolTab?.invoke(name) }
            })
        }
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

    private fun createFullScreenItem(): JMenuItem {
        return JMenuItem("Full Screen", KeyEvent.VK_F11).apply {
            accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_F11, 0)
            addActionListener {
                JOptionPane.showMessageDialog(
                    null,
                    "Full screen mode is not yet implemented.",
                    "Full Screen",
                    JOptionPane.INFORMATION_MESSAGE,
                )
            }
        }
    }
}
