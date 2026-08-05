package org.instagene.app.gui

import org.instagene.core.Seq
import org.instagene.core.io.SeqIO
import java.awt.event.KeyEvent
import java.io.File
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.KeyStroke

class FileMenu(private val frame: JFrame, private val doc: SeqDocument) {

    fun create(): JMenu {
        return JMenu("File").apply {
            mnemonic = KeyEvent.VK_F

            add(createNewItem())
            add(createOpenItem())
            addSeparator()
            add(createSaveItem())
            add(createSaveAsItem())
            addSeparator()
            add(createExitItem())
        }
    }

    private fun createNewItem(): JMenuItem {
        return JMenuItem("New", KeyEvent.VK_N).apply {
            accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_N, java.awt.event.InputEvent.CTRL_DOWN_MASK)
            addActionListener {
                if (confirmDiscardChanges()) {
                    doc.replaceSequence(Seq(""))
                    doc.file = null
                    updateTitle()
                }
            }
        }
    }

    private fun createOpenItem(): JMenuItem {
        return JMenuItem("Open...", KeyEvent.VK_O).apply {
            accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_O, java.awt.event.InputEvent.CTRL_DOWN_MASK)
            addActionListener { openFile() }
        }
    }

    private fun createSaveItem(): JMenuItem {
        return JMenuItem("Save", KeyEvent.VK_S).apply {
            accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_S, java.awt.event.InputEvent.CTRL_DOWN_MASK)
            addActionListener { saveFile() }
        }
    }

    private fun createSaveAsItem(): JMenuItem {
        return JMenuItem("Save As...", KeyEvent.VK_A).apply {
            accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_S, java.awt.event.InputEvent.CTRL_DOWN_MASK or java.awt.event.InputEvent.SHIFT_DOWN_MASK)
            addActionListener { saveFileAs() }
        }
    }

    private fun createExitItem(): JMenuItem {
        return JMenuItem("Exit", KeyEvent.VK_X).apply {
            addActionListener {
                if (confirmDiscardChanges()) {
                    System.exit(0)
                }
            }
        }
    }

    private fun openFile() {
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.FILES_ONLY
            setFileFilter(javax.swing.filechooser.FileNameExtensionFilter("Sequence Files", "fasta", "fa", "fna", "gb", "gbk", "gp", "txt"))
        }

        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            val file = chooser.selectedFile
            try {
                val seq = SeqIO.read(file)
                if (confirmDiscardChanges()) {
                    doc.replaceSequence(seq)
                    doc.file = file
                    updateTitle()
                }
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(frame, "Error opening file: ${e.message}", "Error", JOptionPane.ERROR_MESSAGE)
            }
        }
    }

    private fun saveFile() {
        val file = doc.file
        if (file != null) {
            try {
                SeqIO.write(file, doc.seq)
                doc.markSaved(file)
                updateTitle()
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(frame, "Error saving file: ${e.message}", "Error", JOptionPane.ERROR_MESSAGE)
            }
        } else {
            saveFileAs()
        }
    }

    private fun saveFileAs() {
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.FILES_ONLY
            setFileFilter(javax.swing.filechooser.FileNameExtensionFilter("FASTA", "fasta", "fa", "fna"))
        }

        if (chooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
            var file = chooser.selectedFile
            if (!file.name.contains(".")) {
                file = File(file.parentFile, file.name + ".fasta")
            }
            if (file.exists() && JOptionPane.showConfirmDialog(frame, "File exists. Overwrite?") != JOptionPane.YES_OPTION) {
                return
            }
            try {
                SeqIO.write(file, doc.seq)
                doc.markSaved(file)
                updateTitle()
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(frame, "Error saving file: ${e.message}", "Error", JOptionPane.ERROR_MESSAGE)
            }
        }
    }

    private fun confirmDiscardChanges(): Boolean {
        if (!doc.isDirty) return true
        val result = JOptionPane.showConfirmDialog(
            frame,
            "Sequence has unsaved changes. Discard?",
            "Unsaved Changes",
            JOptionPane.YES_NO_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        return result == JOptionPane.YES_OPTION
    }

    private fun updateTitle() {
        val filename = doc.file?.name ?: "Untitled"
        val dirty = if (doc.isDirty) "*" else ""
        frame.title = "InstaGene - $filename$dirty"
    }
}
