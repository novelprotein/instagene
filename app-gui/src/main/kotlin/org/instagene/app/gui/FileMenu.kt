package org.instagene.app.gui

import org.instagene.core.Seq
import org.instagene.core.io.SeqFormat
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

/** The File menu: new, open, open-recent, project, close-tab, save, save-as and exit, with background-thread file I/O and unsaved-changes prompts. */
class FileMenu(
    private val frame: JFrame?,
    private val doc: SeqDocument,
    private val prefs: Prefs = Prefs(),
    private val onNewDocument: () -> Unit = {},
    private val onOpenDocument: () -> Unit = {},
    private val onOpenRecent: (File) -> Unit = {},
    private val onNewProject: () -> Unit = {},
    private val onOpenProject: () -> Unit = {},
    private val onCloseTab: () -> Unit = {},
    private val onExit: () -> Unit = {},
    private val onTitleChanged: () -> Unit = {},
) {

    private val recentMenu = JMenu("Open Recent")

    init {
        prefs.addListener { rebuildRecentMenu() }
        rebuildRecentMenu()
    }

    fun create(): JMenu {
        return JMenu("File").apply {
            mnemonic = KeyEvent.VK_F

            add(createNewItem())
            add(createOpenItem())
            add(recentMenu)
            addSeparator()
            add(createNewProjectItem())
            add(createOpenProjectItem())
            addSeparator()
            add(createCloseTabItem())
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
            addActionListener { onNewDocument() }
        }
    }

    private fun createOpenItem(): JMenuItem {
        return JMenuItem("Open...", KeyEvent.VK_O).apply {
            accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_O, java.awt.event.InputEvent.CTRL_DOWN_MASK)
            addActionListener { onOpenDocument() }
        }
    }

    private fun createNewProjectItem(): JMenuItem {
        return JMenuItem("New Project...").apply {
            addActionListener { onNewProject() }
        }
    }

    private fun createOpenProjectItem(): JMenuItem {
        return JMenuItem("Open Project...", KeyEvent.VK_P).apply {
            accelerator = KeyStroke.getKeyStroke(
                KeyEvent.VK_P,
                java.awt.event.InputEvent.CTRL_DOWN_MASK or java.awt.event.InputEvent.SHIFT_DOWN_MASK,
            )
            addActionListener { onOpenProject() }
        }
    }

    private fun createCloseTabItem(): JMenuItem {
        return JMenuItem("Close Tab", KeyEvent.VK_W).apply {
            accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_W, java.awt.event.InputEvent.CTRL_DOWN_MASK)
            addActionListener { onCloseTab() }
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
            addActionListener { onExit() }
        }
    }

    /**
     * Parses [file] on a background thread (never blocking the EDT) and delivers
     * the result to [onResult] back on the event thread. Shared by the in-place
     * [loadFromFile] and by callers that open into a new tab. Parse errors and
     * out-of-memory conditions are reported to the user here.
     */
    fun readAsync(file: File, onResult: (Seq) -> Unit) {
        Thread {
            try {
                val seq = SeqIO.read(file)
                SwingUtilities.invokeLater { onResult(seq) }
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

    /**
     * Loads [file] without a file chooser: parses on a background thread and
     * applies the result on the EDT. Shared by the menu action and tests.
     */
    fun loadFromFile(file: File) {
        readAsync(file) { seq ->
            doc.loadSequence(seq, file)
            updateTitle()
            addRecent(file)
        }
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
        val fastaFilter = javax.swing.filechooser.FileNameExtensionFilter("FASTA", "fasta", "fa", "fna")
        val genbankFilter = javax.swing.filechooser.FileNameExtensionFilter("GenBank", "gb", "gbk", "genbank")
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.FILES_ONLY
            addChoosableFileFilter(fastaFilter)
            addChoosableFileFilter(genbankFilter)
            // Annotated or circular documents default to GenBank: FASTA would lose the plasmid map.
            fileFilter = if (SeqIO.preferredSaveFormat(doc.seq) == SeqFormat.GENBANK) genbankFilter else fastaFilter
        }

        if (chooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
            var file = chooser.selectedFile
            val chosen = chooser.fileFilter
            if (!file.name.contains(".")) {
                val ext = if (chosen == genbankFilter) "gb" else "fasta"
                file = File(file.parentFile, file.name + "." + ext)
            }
            if (wouldLosePlasmidData(file)) {
                when (promptPlasmidLoss(file)) {
                    JOptionPane.YES_OPTION -> saveToFile(
                        File(file.parentFile, file.name.substringBeforeLast('.') + ".gb")
                    )
                    JOptionPane.NO_OPTION -> saveToFile(file)
                    else -> return
                }
                return
            }
            if (file.exists() && JOptionPane.showConfirmDialog(frame, "File exists. Overwrite?") != JOptionPane.YES_OPTION) {
                return
            }
            writeToFile(file)
        }
    }

    /** Saves the document to [file] on a background thread, then marks it saved on the EDT. */
    fun saveToFile(file: File) {
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

    /**
     * Writes the document to [file], warning first when the target would drop
     * the plasmid map: FASTA cannot hold features or circular topology.
     */
    private fun writeToFile(file: File) {
        if (wouldLosePlasmidData(file)) {
            when (promptPlasmidLoss(file)) {
                JOptionPane.YES_OPTION -> {
                    saveFileAs()
                    return
                }
                JOptionPane.NO_OPTION -> {}
                else -> return
            }
        }
        saveToFile(file)
    }

    /** True when [file] is FASTA but the document carries a plasmid map that FASTA cannot store. */
    private fun wouldLosePlasmidData(file: File): Boolean =
        SeqIO.preferredSaveFormat(doc.seq) == SeqFormat.GENBANK && SeqIO.formatOf(file) == SeqFormat.FASTA

    /** Asks how to proceed when a save target cannot store the plasmid map. */
    private fun promptPlasmidLoss(file: File): Int = JOptionPane.showConfirmDialog(
        frame,
        "${file.name} is FASTA and cannot store the plasmid map.\n" +
            "Features and circular topology would be lost when it is reopened.",
        "Plasmid Data Would Be Lost",
        JOptionPane.YES_NO_CANCEL_OPTION,
        JOptionPane.WARNING_MESSAGE,
    )

    private fun updateTitle() {
        onTitleChanged()
    }

    /** Records [file] in the recent-files list (most recent first, capped at 10). */
    fun addRecent(file: File) {
        val path = file.absolutePath
        prefs.update { prefs ->
            prefs.copy(recentFiles = (listOf(path) + prefs.recentFiles.filter { it != path }).take(10))
        }
    }

    /** Rebuilds the Open Recent submenu from the persisted list, skipping missing files. */
    fun rebuildRecentMenu() {
        recentMenu.removeAll()
        val paths = prefs.value.recentFiles.filter { File(it).exists() }
        if (paths.isEmpty()) {
            recentMenu.add(JMenuItem("(no recent files)").apply { isEnabled = false })
            return
        }
        for (path in paths) {
            recentMenu.add(JMenuItem(File(path).name).apply {
                toolTipText = path
                addActionListener { onOpenRecent(File(path)) }
            })
        }
    }
}

/** Prompts to discard unsaved changes; returns true when it is safe to proceed. */
fun confirmDiscardChanges(frame: JFrame?, doc: SeqDocument): Boolean {
    if (!doc.isDirty) return true
    val result = JOptionPane.showConfirmDialog(
        frame,
        "${doc.file?.name?: "Sequence"} has unsaved changes. Discard?",
        "Unsaved Changes",
        JOptionPane.YES_NO_CANCEL_OPTION,
        JOptionPane.WARNING_MESSAGE
    )
    return result == JOptionPane.YES_OPTION
}
