package org.instagene.app.gui

import org.instagene.core.Version
import java.awt.BorderLayout
import java.awt.GraphicsEnvironment
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.JFrame

/**
 * Main application window for the InstaGene sequence editor.
 *
 * The editor UI itself lives in [InstaGeneContent]; this class only wraps it in
 * a `JFrame` (menus, toolbar, sequence editor, and tool panels). Closing the
 * window prompts for unsaved changes in every open tab before it goes away.
 * Window geometry is remembered in [prefs] and restored on the next launch.
 */
class InstaGeneWindow(
    openPath: String? = null,
    private val prefs: Prefs = Prefs(),
) : JFrame("InstaGene ${Version.VERSION} - Sequence Editor") {

    val content: InstaGeneContent

    init {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                if (content.confirmCloseAll(this@InstaGeneWindow)) {
                    content.persistProject()
                    rememberGeometry()
                    dispose()
                }
            }
        })

        val saved = prefs.value
        setSize(saved.windowWidth, saved.windowHeight)
        val savedX = saved.windowX
        val savedY = saved.windowY
        if (savedX != null && savedY != null) {
            setLocation(savedX, savedY)
        } else {
            setLocationRelativeTo(null)
        }
        if (saved.windowMaximized) extendedState = MAXIMIZED_BOTH
        isResizable = true

        content = InstaGeneContent(openPath, this, prefs)
        jMenuBar = content.menuBar
        contentPane.add(content, BorderLayout.CENTER)

        installFileDrop()
    }

    /**
     * Dragging a file from the OS file manager onto the window opens it in a
     * new tab (or hands non-editable files to the system app). No-op on
     * headless systems, which have no native drag source.
     */
    private fun installFileDrop() {
        if (GraphicsEnvironment.isHeadless()) return
        content.dropTarget = DropTarget(content, object : DropTargetAdapter() {
            override fun drop(e: DropTargetDropEvent) {
                val flavor = DataFlavor.javaFileListFlavor
                if (!e.isDataFlavorSupported(flavor)) {
                    e.rejectDrop()
                    return
                }
                e.acceptDrop(DnDConstants.ACTION_COPY)
                val transferable = e.transferable
                @Suppress("UNCHECKED_CAST")
                val files = transferable.getTransferData(flavor) as? List<File> ?: emptyList()
                files.forEach { content.openFileInTab(it) }
                e.dropComplete(true)
            }
        })
    }

    /** Persists the current bounds so the next launch opens in the same place. */
    private fun rememberGeometry() {
        val maximized = extendedState and MAXIMIZED_BOTH != 0
        prefs.update {
            it.copy(
                windowX = if (maximized) it.windowX else x,
                windowY = if (maximized) it.windowY else y,
                windowWidth = width,
                windowHeight = height,
                windowMaximized = maximized,
            )
        }
        prefs.save()
    }
}
