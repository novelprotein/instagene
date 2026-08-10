package org.instagene.app.gui

import java.awt.Desktop
import java.io.File
import javax.swing.JOptionPane

/**
 * The seam through which files enter the window: each opener declares whether
 * it handles a file and how to bring it up. The built-in openers cover
 * sequences, text/notes and everything else (images, PDFs, unknown binaries —
 * handed to the system app). A future SequenceFormatPlugin phase can register
 * additional openers ahead of these.
 */
interface DocumentOpener {
    fun canOpen(file: File): Boolean
    fun open(content: InstaGeneContent, file: File)
}

/** The built-in [DocumentOpener]s, tried in order. */
object Openers {

    /** The default dispatch list. Plugins may prepend their own openers. */
    val all: List<DocumentOpener> = listOf(SequenceOpener, TextOpener, SystemAppOpener)

    /** Picks the first opener that claims [file]; always matches via [SystemAppOpener]. */
    fun forFile(file: File): DocumentOpener = all.first { it.canOpen(file) }

    /** FASTA, GenBank and bare-bases molecules. */
    object SequenceOpener : DocumentOpener {
        override fun canOpen(file: File): Boolean = FileTypes.classify(file) == FileType.SEQUENCE

        override fun open(content: InstaGeneContent, file: File) {
            content.activeFileMenu().readAsync(file) { seq ->
                content.noteRecent(content.openSequence(seq, file), file)
            }
        }
    }

    /** Plain-text and notes files, opened in the in-app text editor. */
    object TextOpener : DocumentOpener {
        override fun canOpen(file: File): Boolean = FileTypes.classify(file) == FileType.TEXT

        override fun open(content: InstaGeneContent, file: File) {
            content.activeFileMenu().readTextAsync(file) { text ->
                content.openText(text, file)
            }
        }
    }

    /** Images, PDFs and unrecognized files: open with the system application. */
    object SystemAppOpener : DocumentOpener {
        override fun canOpen(file: File): Boolean = true

        override fun open(content: InstaGeneContent, file: File) {
            val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
            if (desktop != null && desktop.isSupported(Desktop.Action.OPEN)) {
                val result = runCatching { desktop.open(file) }
                result.exceptionOrNull()?.let {
                    JOptionPane.showMessageDialog(
                        content.parentWindow,
                        "Cannot open ${file.name}:\n\n${it.message ?: "Unknown error"}",
                        "Error",
                        JOptionPane.ERROR_MESSAGE,
                    )
                }
            } else {
                JOptionPane.showMessageDialog(
                    content.parentWindow,
                    "Cannot open ${file.name}:\n\nDesktop integration is not available in this environment.",
                    "Error",
                    JOptionPane.ERROR_MESSAGE,
                )
            }
        }
    }
}
