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
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

class FileMenu(private val frame: JFrame?, private val doc: SeqDocument) {

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
                if (confirmDiscardChanges(frame, doc)) {
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
                if (confirmDiscardChanges(frame, doc)) {
                    exitProcess(0)
                }
            }
        }
    }

    /**
     * Opens a file picker, then hands the chosen file to [loadFromFile], which
     * parses on a background thread so the UI never blocks on big files.
     */
    fun openFile() {
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.FILES_ONLY
            setFileFilter(javax.swing.filechooser.FileNameExtensionFilter("Sequence Files", "fasta", "fa", "fna", "gb", "gbk", "gp", "txt"))
        }

        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            val file = chooser.selectedFile
            if (!confirmDiscardChanges(frame, doc)) return
            loadFromFile(file)
        }
    }

    /**
     * Loads [file] without a file chooser: parses on a background thread and
     * applies the result on the EDT. Shared by the menu action and tests.
     */
    fun loadFromFile(file: File) {
        Thread {
            try {
                val seq = SeqIO.read(file)
                SwingUtilities.invokeLater {
                    doc.loadSequence(seq, file)
                    updateTitle()
                }
            } catch (_: OutOfMemoryError) {
                SwingUtilities.invokeLater {
                    val message = buildString {
                        append("File is too large to load in memory.\n\n")
                        append("File: ${file.name}\n")
                        append("Size: ${String.format("%.2f MB", file.length() / 1024.0 / 1024.0)}\n\n")
                        append("Try splitting the file into smaller parts.")
                    }
                    JOptionPane.showMessageDialog(frame, message, "Out of Memory", JOptionPane.ERROR_MESSAGE)
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    JOptionPane.showMessageDialog(
                        frame,
                        "Error opening file:\n\n${e.message ?: "Unknown error"}\n\nFile: ${file.name}",
                        "Error",
                        JOptionPane.ERROR_MESSAGE,
                    )
                }
            }
        }.apply { isDaemon = false; name = "FileReader-${file.name}" }.start()
    }

    fun saveFile() {
        val file = doc.file
        if (file != null) {
            writeToFile(file)
        } else {
            saveFileAs()
        }
    }

    fun saveFileAs() {
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
            writeToFile(file)
        }
    }

    /** Writes the sequence to [file] on a background thread, then marks it saved on the EDT. */
    private fun writeToFile(file: File) {
        val seq = doc.seq
        Thread {
            try {
                SeqIO.write(file, seq)
                SwingUtilities.invokeLater {
                    doc.markSaved(file)
                    updateTitle()
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    JOptionPane.showMessageDialog(frame, "Error saving file: ${e.message}", "Error", JOptionPane.ERROR_MESSAGE)
                }
            }
        }.start()
    }

    private fun updateTitle() {
        val filename = doc.file?.name ?: "Untitled"
        val dirty = if (doc.isDirty) "*" else ""
        frame?.title = "InstaGene - $filename$dirty"
    }
}

/** Prompts to discard unsaved changes; returns true when it is safe to proceed. */
fun confirmDiscardChanges(frame: JFrame?, doc: SeqDocument): Boolean {
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
