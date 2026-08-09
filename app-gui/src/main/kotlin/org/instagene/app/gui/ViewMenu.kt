package org.instagene.app.gui

import org.instagene.core.SeqKind
import java.awt.event.KeyEvent
import javax.swing.ButtonGroup
import javax.swing.JCheckBoxMenuItem
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JRadioButtonMenuItem

class ViewMenu(
    private val doc: SeqDocument,
    private val sequenceView: SequenceView,
    private val prefs: Prefs = Prefs(),
) {

    private val complementItem = JCheckBoxMenuItem("Show Complement Strand", true)
    private val translationItem = JCheckBoxMenuItem("Show Translation", false)

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
            addSeparator()
            add(createZoomInItem())
            add(createZoomOutItem())
            add(createResetZoomItem())
            addSeparator()
            add(createThemesMenu())
        }
    }

    private fun createThemesMenu(): JMenu {
        val group = ButtonGroup()
        return JMenu("Theme").apply {
            mnemonic = KeyEvent.VK_T
            ThemeManager.themes.forEach { theme ->
                val item = JRadioButtonMenuItem(theme.displayName)
                group.add(item)
                item.isSelected = theme.id == prefs.value.theme
                item.addActionListener {
                    if (item.isSelected) selectTheme(theme.id)
                }
                add(item)
            }
        }
    }

    /** Switches the running look-and-feel and persists the choice for next launch. */
    private fun selectTheme(id: String) {
        if (ThemeManager.current() == id || !ThemeManager.apply(id)) return
        prefs.update { it.copy(theme = id) }
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
