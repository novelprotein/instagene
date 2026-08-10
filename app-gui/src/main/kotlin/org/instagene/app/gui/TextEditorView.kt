package org.instagene.app.gui

import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * The plain-text editor for [TextDocument] tabs: a monospaced, word-wrapping
 * [JTextArea] that pushes edits back into the document (so they are undoable
 * and dirty-tracked) and re-reads the document when it changes elsewhere.
 */
class TextEditorView(initial: TextDocument) : JPanel(BorderLayout()) {

    val area = JTextArea()

    /** The document currently displayed; re-pointed when the active tab changes. */
    var document: TextDocument = initial
        private set

    private var docListener: Doc.Listener? = null

    /** True while [refreshFromDoc] is writing programmatically; guards the echo loop. */
    private var updating = false

    private val areaListener = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = pushFromArea()
        override fun removeUpdate(e: DocumentEvent) = pushFromArea()
        override fun changedUpdate(e: DocumentEvent) = pushFromArea()
    }

    init {
        area.lineWrap = true
        area.wrapStyleWord = true
        area.font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        area.document.addDocumentListener(areaListener)
        add(JScrollPane(area), BorderLayout.CENTER)
        bindDocument(initial)
    }

    /** Re-points this view at another text document and shows its content. */
    fun bindDocument(newDoc: TextDocument) {
        if (newDoc !== document) {
            docListener?.let { document.removeDocListener(it) }
            document = newDoc
            docListener?.let { document.addDocListener(it) }
        }
        if (docListener == null) {
            docListener = Doc.Listener { refreshFromDoc() }
            document.addDocListener(docListener!!)
        }
        refreshFromDoc()
    }

    /** Copies the document buffer into the text area when it has changed elsewhere. */
    private fun refreshFromDoc() {
        if (updating) return
        updating = true
        try {
            if (area.text != document.text) area.text = document.text
        } finally {
            updating = false
        }
    }

    private fun pushFromArea() {
        if (updating) return
        document.setText(area.text)
    }
}
