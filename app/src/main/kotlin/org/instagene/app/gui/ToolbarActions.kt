package org.instagene.app.gui

import org.instagene.core.io.SeqIO
import java.awt.Dimension
import java.io.File
import javax.swing.AbstractButton
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.JSpinner
import javax.swing.JToolBar
import javax.swing.SpinnerNumberModel

object ToolbarActions {

    fun createFileOpenButton(frame: JFrame, doc: SeqDocument): AbstractButton {
        return JButton("Open").apply {
            toolTipText = "Open sequence file (Ctrl+O)"
            addActionListener {
                val chooser = JFileChooser().apply {
                    setFileFilter(javax.swing.filechooser.FileNameExtensionFilter("Sequence Files", "fasta", "fa", "fna", "gb", "gbk"))
                }
                if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                    try {
                        val seq = SeqIO.read(chooser.selectedFile)
                        doc.replaceSequence(seq)
                        doc.file = chooser.selectedFile
                    } catch (e: Exception) {
                        JOptionPane.showMessageDialog(frame, "Error opening file: ${e.message}", "Error", JOptionPane.ERROR_MESSAGE)
                    }
                }
            }
        }
    }

    fun createFileSaveButton(frame: JFrame, doc: SeqDocument): AbstractButton {
        return JButton("Save").apply {
            toolTipText = "Save sequence (Ctrl+S)"
            addActionListener {
                val file = doc.file
                if (file != null) {
                    try {
                        SeqIO.write(file, doc.seq)
                        doc.markSaved(file)
                    } catch (e: Exception) {
                        JOptionPane.showMessageDialog(frame, "Error saving file: ${e.message}", "Error", JOptionPane.ERROR_MESSAGE)
                    }
                } else {
                    JOptionPane.showMessageDialog(frame, "No file selected. Use 'Save As...' first.", "No File", JOptionPane.WARNING_MESSAGE)
                }
            }
        }
    }

    fun createUndoButton(doc: SeqDocument): AbstractButton {
        return JButton("Undo").apply {
            toolTipText = "Undo last action (Ctrl+Z)"
            addActionListener { doc.undo() }
        }
    }

    fun createRedoButton(doc: SeqDocument): AbstractButton {
        return JButton("Redo").apply {
            toolTipText = "Redo last action (Ctrl+Y)"
            addActionListener { doc.redo() }
        }
    }

    fun createSelectAllButton(doc: SeqDocument): AbstractButton {
        return JButton("Select All").apply {
            toolTipText = "Select entire sequence (Ctrl+A)"
            addActionListener { doc.selectAll() }
        }
    }

    fun createCopyButton(sequenceView: SequenceView): AbstractButton {
        return JButton("Copy").apply {
            toolTipText = "Copy selection (Ctrl+C)"
            addActionListener { sequenceView.copySelection() }
        }
    }

    fun createPasteButton(sequenceView: SequenceView): AbstractButton {
        return JButton("Paste").apply {
            toolTipText = "Paste from clipboard (Ctrl+V)"
            addActionListener { sequenceView.paste() }
        }
    }

    fun createFontSizeControls(sequenceView: SequenceView): JToolBar {
        return JToolBar().apply {
            isFloatable = false
            add(javax.swing.JLabel("Size:"))
            add(JSpinner(SpinnerNumberModel(14, 8, 28, 1)).apply {
                preferredSize = Dimension(50, 25)
                addChangeListener {
                    val size = (value as Number).toInt()
                    sequenceView.setFontSize(size)
                }
            })
        }
    }
}
