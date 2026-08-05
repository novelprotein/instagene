package org.instagene.app.gui

import org.instagene.core.io.SeqIO
import java.awt.Dimension
import javax.swing.AbstractButton
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.JSpinner
import javax.swing.JToolBar
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities

object ToolbarActions {

    fun createFileOpenButton(frame: JFrame, doc: SeqDocument): AbstractButton {
        return JButton("Open").apply {
            toolTipText = "Open sequence file (Ctrl+O)"
            addActionListener {
                val chooser = JFileChooser().apply {
                    setFileFilter(javax.swing.filechooser.FileNameExtensionFilter("Sequence Files", "fasta", "fa", "fna", "gb", "gbk", "gp", "txt"))
                }
                if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                    val file = chooser.selectedFile
                    println("DEBUG [Toolbar]: User selected file: ${file.name} (${file.length()} bytes)")

                    // Read file AND update UI in background to avoid blocking EDT
                    Thread {
                        try {
                            println("DEBUG [Toolbar]: Starting to read ${file.name}")
                            val startTime = System.currentTimeMillis()
                            val seq = SeqIO.read(file)
                            val readTime = System.currentTimeMillis() - startTime
                            println("DEBUG [Toolbar]: Finished reading ${file.name} in ${readTime}ms, ${seq.length} bases")

                            // Load sequence with batch updates to avoid expensive listener notifications
                            println("DEBUG [Toolbar]: Beginning batch load")
                            val loadStart = System.currentTimeMillis()
                            doc.loadSequence(seq, file)
                            val loadTime = System.currentTimeMillis() - loadStart
                            println("DEBUG [Toolbar]: Batch load complete in ${loadTime}ms")

                            val totalTime = System.currentTimeMillis() - startTime
                            println("DEBUG [Toolbar]: Total time (read + load): ${totalTime}ms")
                        } catch (e: OutOfMemoryError) {
                            println("DEBUG [Toolbar]: OutOfMemoryError: ${e.message}")
                            e.printStackTrace()
                            SwingUtilities.invokeLater {
                                JOptionPane.showMessageDialog(
                                    frame,
                                    "❌ File is too large to load in memory!\n\nFile: ${file.name}\nSize: ${String.format("%.2f MB", file.length() / 1024.0 / 1024.0)}\n\nTry splitting the file into smaller parts.",
                                    "Out of Memory",
                                    JOptionPane.ERROR_MESSAGE
                                )
                            }
                        } catch (e: Exception) {
                            val errorMsg = e.message ?: "Unknown error"
                            println("DEBUG [Toolbar]: Exception: $errorMsg")
                            e.printStackTrace()
                            SwingUtilities.invokeLater {
                                JOptionPane.showMessageDialog(
                                    frame,
                                    "❌ Error opening file:\n\n$errorMsg\n\nFile: ${file.name}\n\nCheck the console for details.",
                                    "Error",
                                    JOptionPane.ERROR_MESSAGE
                                )
                            }
                        }
                    }.apply { isDaemon = false; name = "FileReader-Toolbar-${file.name}" }.start()
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
                    // Write file in background thread
                    Thread {
                        try {
                            SeqIO.write(file, doc.seq)
                            SwingUtilities.invokeLater {
                                doc.markSaved(file)
                            }
                        } catch (e: Exception) {
                            SwingUtilities.invokeLater {
                                JOptionPane.showMessageDialog(frame, "Error saving file: ${e.message}", "Error", JOptionPane.ERROR_MESSAGE)
                            }
                        }
                    }.start()
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
