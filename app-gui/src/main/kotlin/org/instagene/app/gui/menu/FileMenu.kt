package org.instagene.app.gui.menu

import org.instagene.app.gui.dialog.SettingsDialog
import org.instagene.app.gui.document.Doc
import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.document.TextDocument
import org.instagene.app.gui.prefs.Prefs
import org.instagene.core.Seq
import org.instagene.core.io.SeqFormat
import org.instagene.core.io.SeqIO
import java.awt.event.KeyEvent
import java.io.File
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * The File menu, including document and project creation, opening, saving,
 * tab closure, and exit. File I/O runs in the background, and destructive
 * actions prompt when a document has unsaved changes.
 */
class FileMenu(
    private val frame: JFrame?,
    private val doc: Doc,
    private val prefs: Prefs = Prefs(),
    private val onNewDocument: () -> Unit = {},
    private val onNewTextDocument: () -> Unit = {},
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
            add(createNewTextItem())
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
            add(createPreferencesItem())
            add(createSystemSettingsItem())
            addSeparator()
            add(createExitItem())
        }
    }

    private fun createNewItem(): JMenuItem {
        return JMenuItem("New", KeyEvent.VK_N).apply {
            accelerator = menuShortcut(KeyEvent.VK_N)
            addActionListener { onNewDocument() }
        }
    }

    private fun createNewTextItem(): JMenuItem {
        return JMenuItem("New Text File", KeyEvent.VK_T).apply {
            accelerator = menuShortcutWithShift(KeyEvent.VK_T)
            addActionListener { onNewTextDocument() }
        }
    }

    private fun createOpenItem(): JMenuItem {
        return JMenuItem("Open...", KeyEvent.VK_O).apply {
            accelerator = menuShortcut(KeyEvent.VK_O)
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
            accelerator = menuShortcutWithShift(KeyEvent.VK_P)
            addActionListener { onOpenProject() }
        }
    }

    private fun createCloseTabItem(): JMenuItem {
        return JMenuItem("Close Tab", KeyEvent.VK_W).apply {
            accelerator = menuShortcut(KeyEvent.VK_W)
            addActionListener { onCloseTab() }
        }
    }

    private fun createSaveItem(): JMenuItem {
        return JMenuItem("Save", KeyEvent.VK_S).apply {
            accelerator = menuShortcut(KeyEvent.VK_S)
            addActionListener { saveFile() }
        }
    }

    private fun createSaveAsItem(): JMenuItem {
        return JMenuItem("Save As...", KeyEvent.VK_A).apply {
            accelerator = menuShortcutWithShift(KeyEvent.VK_S)
            addActionListener { saveFileAs() }
        }
    }

    private fun createExitItem(): JMenuItem {
        return JMenuItem("Exit", KeyEvent.VK_X).apply {
            addActionListener { onExit() }
        }
    }

    /** User options open in the same direct-dialog style as system Settings. */
    private fun createPreferencesItem(): JMenuItem = JMenuItem("Preferences...").apply {
        addActionListener { SettingsDialog.showPreferences(frame, prefs) }
    }

    private fun createSystemSettingsItem(): JMenuItem = JMenuItem("Settings...").apply {
        addActionListener { SettingsDialog.showSystemSettings(frame) }
    }

    /**
     * Parses [file] on a background thread without blocking the EDT, then delivers
     * the result to [onResult] on the event thread. Shared by the in-place
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
     * applies the result on the EDT. Only meaningful for sequence documents.
     * Shared by the menu action and tests.
     */
    fun loadFromFile(file: File) {
        val seqDoc = doc as? SeqDocument ?: return
        readAsync(file) { seq ->
            seqDoc.loadSequence(seq, file)
            updateTitle()
            addRecent(file)
        }
    }

    /**
     * Reads [file] as plain text on a background thread without blocking the
     * EDT, then delivers the content to [onResult] on the event thread.
     * I/O errors are reported to the user here.
     */
    fun readTextAsync(file: File, onResult: (String) -> Unit) {
        Thread {
            try {
                val text = file.readText()
                SwingUtilities.invokeLater { onResult(text) }
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
        }.apply { isDaemon = false; name = "TextReader-${file.name}" }.start()
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
        val seqDoc = doc as? SeqDocument
        if (seqDoc == null) {
            saveTextAs()
            return
        }
        val fastaFilter = FileNameExtensionFilter("FASTA", "fasta", "fa", "fna")
        val genbankFilter = FileNameExtensionFilter("GenBank", "gb", "gbk", "genbank")
        val gffFilter = FileNameExtensionFilter("GFF3", "gff", "gff3")
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.FILES_ONLY
            addChoosableFileFilter(fastaFilter)
            addChoosableFileFilter(genbankFilter)
            addChoosableFileFilter(gffFilter)
            // Annotated or circular documents default to GenBank: FASTA would lose the plasmid map.
            fileFilter = if (SeqIO.preferredSaveFormat(seqDoc.seq) == SeqFormat.GENBANK) genbankFilter else fastaFilter
        }

        if (chooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
            var file = chooser.selectedFile
            val chosen = chooser.fileFilter
            if (!file.name.contains(".")) {
                val ext = when (chosen) {
                    genbankFilter -> "gb"
                    gffFilter -> "gff3"
                    else -> "fasta"
                }
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

    /** Saves a text document through a plain-text chooser. */
    private fun saveTextAs() {
        val textFilter = FileNameExtensionFilter("Text", "txt", "md", "notes")
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.FILES_ONLY
            addChoosableFileFilter(textFilter)
            fileFilter = textFilter
        }
        if (chooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
            var file = chooser.selectedFile
            if (!file.name.contains(".")) file = File(file.parentFile, file.name + ".txt")
            if (file.exists() && JOptionPane.showConfirmDialog(frame, "File exists. Overwrite?") != JOptionPane.YES_OPTION) {
                return
            }
            writeToFile(file)
        }
    }

    /** Saves the document to [file] on a background thread, then marks it saved on the EDT. */
    fun saveToFile(file: File) {
        Thread {
            try {
                val seqDoc = doc as? SeqDocument
                val textDoc = doc as? TextDocument
                when {
                    seqDoc != null -> SeqIO.write(file, seqDoc.seq)
                    textDoc != null -> file.writeText(textDoc.text)
                    else -> throw IllegalStateException("Unknown document type")
                }
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
    private fun wouldLosePlasmidData(file: File): Boolean {
        val seqDoc = doc as? SeqDocument ?: return false
        return SeqIO.preferredSaveFormat(seqDoc.seq) == SeqFormat.GENBANK && SeqIO.formatOf(file) == SeqFormat.FASTA
    }

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
fun confirmDiscardChanges(frame: JFrame?, doc: Doc): Boolean {
    if (!doc.isDirty) return true
    val result = JOptionPane.showConfirmDialog(
        frame,
        "${doc.file?.name ?: doc.displayName} has unsaved changes. Discard?",
        "Unsaved Changes",
        JOptionPane.YES_NO_CANCEL_OPTION,
        JOptionPane.WARNING_MESSAGE
    )
    return result == JOptionPane.YES_OPTION
}
