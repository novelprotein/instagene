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
            if (!confirmDiscardChanges()) return

            println("DEBUG: User selected file: ${file.name} (${file.length()} bytes)")

            // Read file AND update UI in background to avoid blocking EDT
            Thread {
                try {
                    println("DEBUG: Starting to read ${file.name}")
                    val startTime = System.currentTimeMillis()
                    val seq = SeqIO.read(file)
                    val readTime = System.currentTimeMillis() - startTime
                    println("DEBUG: Finished reading ${file.name} in ${readTime}ms, ${seq.length} bases")

                    // Load sequence with batch updates to avoid expensive listener notifications
                    println("DEBUG: Beginning batch load")
                    val loadStart = System.currentTimeMillis()
                    doc.loadSequence(seq, file)
                    val loadTime = System.currentTimeMillis() - loadStart
                    println("DEBUG: Batch load complete in ${loadTime}ms")

                    val totalTime = System.currentTimeMillis() - startTime
                    println("DEBUG: Total time (read + load + update): ${totalTime}ms")

                    // Show success message on EDT
                    SwingUtilities.invokeLater {
                        updateTitle()
                        val message = buildString {
                            append("✓ Successfully loaded!\n\n")
                            append("Bases: ${seq.length}\n")
                            append("Read time: ${readTime}ms\n")
                            append("Load time: ${loadTime}ms\n")
                            append("Total time: ${totalTime}ms\n")
                            append("Name: ${seq.name}\n")
                            append("Type: ${seq.kind}\n")
                            append("Topology: ${seq.topology}")
                        }
                        println("DEBUG: Showing success dialog")
                        JOptionPane.showMessageDialog(
                            frame,
                            message,
                            "File Loaded",
                            JOptionPane.INFORMATION_MESSAGE
                        )
                        println("DEBUG: Success dialog closed")
                    }
                } catch (e: OutOfMemoryError) {
                    println("DEBUG: OutOfMemoryError: ${e.message}")
                    e.printStackTrace()
                    SwingUtilities.invokeLater {
                        val message = buildString {
                            append("❌ File is too large to load in memory!\n\n")
                            append("File: ${file.name}\n")
                            append("Size: ${String.format("%.2f MB", file.length() / 1024.0 / 1024.0)}\n\n")
                            append("Try splitting the file into smaller parts.")
                        }
                        JOptionPane.showMessageDialog(
                            frame,
                            message,
                            "Out of Memory",
                            JOptionPane.ERROR_MESSAGE
                        )
                    }
                } catch (e: Exception) {
                    val errorMsg = e.message ?: "Unknown error"
                    println("DEBUG: Exception: $errorMsg")
                    e.printStackTrace()
                    SwingUtilities.invokeLater {
                        val message = buildString {
                            append("❌ Error opening file:\n\n")
                            append("$errorMsg\n\n")
                            append("File: ${file.name}\n")
                            append("Size: ${String.format("%.2f MB", file.length() / 1024.0 / 1024.0)}\n\n")
                            append("Check the console for details.")
                        }
                        JOptionPane.showMessageDialog(
                            frame,
                            message,
                            "Error",
                            JOptionPane.ERROR_MESSAGE
                        )
                    }
                }
            }.apply { isDaemon = false; name = "FileReader-${file.name}" }.start()
        }
    }

    private fun saveFile() {
        val file = doc.file
        if (file != null) {
            // Write file in background thread
            Thread {
                try {
                    SeqIO.write(file, doc.seq)
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
            // Write file in background thread
            Thread {
                try {
                    SeqIO.write(file, doc.seq)
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
