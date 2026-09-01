package org.instagene.app.gui.analysis

import org.instagene.app.gui.ContextMenus
import org.instagene.app.gui.document.SeqDocument
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.table.DefaultTableModel

internal abstract class BoundAnalysisPanel : JPanel(BorderLayout()) {
    internal lateinit var doc: SeqDocument
    open fun bindDocument(value: SeqDocument) {
        doc = value
        refreshDocument()
    }
    protected open fun refreshDocument() {}
    protected fun row(vararg components: java.awt.Component): JPanel =
        org.instagene.app.gui.row(*components)
    protected fun copyRowToClipboard(model: DefaultTableModel, row: Int?) {
        if (row != null) {
            val sb = StringBuilder()
            for (c in 0 until model.columnCount) { if (c > 0) sb.append("\t"); sb.append(model.getValueAt(row, c)) }
            ContextMenus.copyToClipboard(sb.toString())
        }
    }
    protected fun output(): JTextArea = org.instagene.app.gui.monospacedTextArea()

}
