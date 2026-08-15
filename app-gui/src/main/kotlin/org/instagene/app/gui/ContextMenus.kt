package org.instagene.app.gui

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.tree.TreePath

/** Shared helpers for row-aware Swing context menus. */
internal object ContextMenus {

    fun item(label: String, tooltip: String, enabled: Boolean = true, action: () -> Unit): JMenuItem =
        JMenuItem(label).apply {
            toolTipText = tooltip
            isEnabled = enabled
            addActionListener { action() }
        }

    fun copyToClipboard(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}

internal fun JTable.installRowContextMenu(popupForRow: (modelRow: Int?) -> JPopupMenu?) {
    fun maybeShow(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val viewRow = rowAtPoint(e.point)
        val modelRow = if (viewRow >= 0) {
            selectionModel.setSelectionInterval(viewRow, viewRow)
            convertRowIndexToModel(viewRow)
        } else {
            clearSelection()
            null
        }
        val popup = popupForRow(modelRow) ?: return
        componentPopupMenu = popup
        if (isShowing && popup.componentCount > 0) popup.show(this, e.x, e.y)
    }
    addMouseListener(object : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) = maybeShow(e)
        override fun mouseReleased(e: MouseEvent) = maybeShow(e)
    })
}

internal fun JTree.installPathContextMenu(popupForPath: (path: TreePath?) -> JPopupMenu?) {
    fun maybeShow(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val path = getClosestPathForLocation(e.x, e.y)
        if (path != null) selectionPath = path else clearSelection()
        val popup = popupForPath(path) ?: return
        componentPopupMenu = popup
        if (isShowing && popup.componentCount > 0) popup.show(this, e.x, e.y)
    }
    addMouseListener(object : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) = maybeShow(e)
        override fun mouseReleased(e: MouseEvent) = maybeShow(e)
    })
}
