package org.instagene.app.gui

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dialog.ModalityType as DialogModalityType
import java.awt.Dimension
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** One searchable action in the application-wide command palette. */
data class CommandPaletteCommand(
    val id: String,
    val label: String,
    val detail: String = "",
    val keywords: List<String> = emptyList(),
    val action: () -> Unit,
)

/**
 * Small, dependency-free command palette used for keyboard navigation of the
 * desktop application. Keeping filtering separate from the dialog gives GUI
 * tests and future front ends the same predictable command matching behavior.
 */
object CommandPalette {
    fun filter(commands: List<CommandPaletteCommand>, query: String): List<CommandPaletteCommand> {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
        if (terms.isEmpty()) return commands
        return commands.filter { command ->
            val haystack = buildString {
                append(command.label).append(' ')
                append(command.detail).append(' ')
                append(command.id).append(' ')
                append(command.keywords.joinToString(" "))
            }.lowercase()
            terms.all { term -> term in haystack || isSubsequence(term, haystack) }
        }
    }

    private fun isSubsequence(query: String, text: String): Boolean {
        var position = 0
        query.forEach { wanted ->
            position = text.indexOf(wanted, position)
            if (position < 0) return false
            position++
        }
        return true
    }

    fun show(parent: Component?, commands: List<CommandPaletteCommand>) {
        if (commands.isEmpty()) return
        val owner = parent?.let(SwingUtilities::getWindowAncestor)
        val dialog = JDialog(owner, "Command Palette", DialogModalityType.MODELESS).apply {
            defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
        }
        val query = JTextField()
        val model = DefaultListModel<CommandPaletteCommand>()
        val list = JList(model).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
                ): Component {
                    val command = value as CommandPaletteCommand
                    return super.getListCellRendererComponent(
                        list,
                        if (command.detail.isBlank()) command.label else "${command.label}  —  ${command.detail}",
                        index,
                        isSelected,
                        cellHasFocus,
                    ).apply {
                        border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
                    }
                }
            }
        }

        fun refresh() {
            val matches = filter(commands, query.text)
            model.removeAllElements()
            matches.forEach(model::addElement)
            if (model.size > 0) list.selectedIndex = 0
        }
        fun executeSelected() {
            val command = list.selectedValue ?: return
            dialog.dispose()
            command.action()
        }

        query.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = refresh()
            override fun removeUpdate(e: DocumentEvent) = refresh()
            override fun changedUpdate(e: DocumentEvent) = refresh()
        })
        list.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(event: java.awt.event.MouseEvent) {
                if (event.clickCount == 2) executeSelected()
            }
        })

        val root = JPanel(BorderLayout(8, 8)).apply {
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
            add(JLabel("Type a command"), BorderLayout.NORTH)
            add(query, BorderLayout.CENTER)
        }
        val content = JPanel(BorderLayout(8, 8)).apply {
            add(root, BorderLayout.NORTH)
            add(JScrollPane(list).apply { preferredSize = Dimension(620, 320) }, BorderLayout.CENTER)
            add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0)).apply {
                add(JButton("Run").apply { addActionListener { executeSelected() } })
            }, BorderLayout.SOUTH)
        }
        dialog.contentPane = content
        dialog.rootPane.inputMap.put(KeyStroke.getKeyStroke("ENTER"), "run-command")
        dialog.rootPane.actionMap.put("run-command", object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) = executeSelected()
        })
        dialog.rootPane.inputMap.put(KeyStroke.getKeyStroke("ESCAPE"), "close-command-palette")
        dialog.rootPane.actionMap.put("close-command-palette", object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) = dialog.dispose()
        })
        refresh()
        dialog.pack()
        dialog.setLocationRelativeTo(parent)
        dialog.isVisible = true
        query.requestFocusInWindow()
    }
}
