package org.instagene.app.gui.tool

import org.instagene.app.gui.prefs.AppDirs
import org.instagene.core.*
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.util.UUID
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** Written instructions are independent of sequence documents and executable recipes. */
class WorkflowLibraryPanel(
    private val store: WorkflowLibraryStore,
    private val askUnsaved: (Component) -> Int = {
        JOptionPane.showOptionDialog(it, "Save changes to this entry?", "Unsaved changes",
            JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
            arrayOf("Save", "Discard", "Cancel"), "Save")
    },
    private val confirmDelete: (Component) -> Boolean = {
        JOptionPane.showConfirmDialog(it, "Delete the selected entry?", "Delete entry",
            JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION
    },
    private val showError: (Component, String) -> Unit = { parent, message ->
        JOptionPane.showMessageDialog(parent, message, "Workflow Library", JOptionPane.ERROR_MESSAGE)
    },
) : JPanel(BorderLayout(8, 8)) {
    private var library = WorkflowLibrary()
    private var baseline: WorkflowLibraryEntry? = null
    private var refreshing = false
    private var available = false
    private val entries = DefaultListModel<WorkflowLibraryEntry>()
    private val list = JList(entries)
    private val search = JTextField(18).apply { name = "workflowSearch" }
    private val filter = JComboBox(arrayOf("All", "Protocol", "Procedure"))
    private val title = JTextField().apply { name = "workflowTitle" }
    private val body = JTextArea(10, 40).apply {
        name = "workflowInstructions"; lineWrap = true; wrapStyleWord = true
    }
    private val stepModel = DefaultListModel<WorkflowLibraryStep>()
    private val steps = JList(stepModel)
    private val stepPreview = JTextArea(4, 30).apply {
        isEditable = false; lineWrap = true; wrapStyleWord = true
    }
    private val editor = JPanel(BorderLayout(8, 8))
    private val stepPanel = JPanel(BorderLayout(4, 4))
    private val status = JLabel(" ")

    init {
        border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(l: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean): Component =
                super.getListCellRendererComponent(l, (value as? WorkflowLibraryEntry)?.let { "${it.title} (${it.kind})" }, index, selected, focus)
        }
        steps.selectionMode = ListSelectionModel.SINGLE_SELECTION
        steps.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(l: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean): Component =
                super.getListCellRendererComponent(l, "${index + 1}. ${(value as? WorkflowLibraryStep)?.title.orEmpty().ifBlank { "Untitled step" }}", index, selected, focus)
        }
        val browser = JPanel(BorderLayout(4, 4)).apply {
            preferredSize = Dimension(280, 500)
            add(JPanel(BorderLayout(4, 4)).apply {
                add(JLabel("Search"), BorderLayout.WEST); add(search); add(filter, BorderLayout.SOUTH)
            }, BorderLayout.NORTH)
            add(JScrollPane(list))
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(button("New Protocol") { createEntry(WorkflowEntryKind.PROTOCOL) })
                add(button("New Procedure") { createEntry(WorkflowEntryKind.PROCEDURE) })
            }, BorderLayout.SOUTH)
        }
        editor.add(JPanel(BorderLayout(4, 4)).apply {
            add(JLabel("Title"), BorderLayout.WEST); add(title)
        }, BorderLayout.NORTH)
        val notes = JPanel(BorderLayout(4, 4)).apply {
            add(JLabel("Instructions / notes (plain text or Markdown)"), BorderLayout.NORTH)
            add(JScrollPane(body))
        }
        stepPanel.border = BorderFactory.createTitledBorder("Protocol steps")
        stepPanel.add(JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(steps), JScrollPane(stepPreview)).apply { resizeWeight = 0.5 })
        steps.addListSelectionListener {
            stepPreview.text = steps.selectedValue?.instructions.orEmpty()
            stepPreview.caretPosition = 0
        }
        stepPanel.add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(button("Add step") { editStep(true) })
            add(button("Edit step") { editStep(false) })
            add(button("Remove step") { if (steps.selectedIndex >= 0) stepModel.remove(steps.selectedIndex) })
            add(button("Move up") { moveStep(-1) })
            add(button("Move down") { moveStep(1) })
        }, BorderLayout.SOUTH)
        editor.add(JPanel(java.awt.GridLayout(0, 1, 4, 4)).apply { add(notes); add(stepPanel) })
        editor.add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(button("Save") { saveEntry() })
            add(button("Cancel") { if (canLeave()) display(baseline?.let { selected -> library.entries.find { it.id == selected.id } }) })
            add(button("Duplicate") { duplicateEntry() })
            add(button("Delete") { deleteEntry() })
        }, BorderLayout.SOUTH)
        add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, browser, editor).apply { resizeWeight = 0.3 })
        add(status, BorderLayout.SOUTH)
        list.addListSelectionListener {
            if (!it.valueIsAdjusting && !refreshing) {
                val next = list.selectedValue
                if (next?.id != baseline?.id) {
                    if (canLeave()) display(next) else selectCurrent()
                }
            }
        }
        search.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = refreshList()
            override fun removeUpdate(e: DocumentEvent) = refreshList()
            override fun changedUpdate(e: DocumentEvent) = refreshList()
        })
        filter.addActionListener { refreshList() }
        try {
            library = store.load()
            available = true
            status.text = "Shared across all projects on this computer."
        } catch (e: Exception) {
            status.text = "Library could not be loaded. Close and reopen after fixing the file."
            showError(this, "Could not load the workflow library: ${e.message}")
        }
        refreshList()
        display(null)
        if (!available) setEnabledRecursively(this, false)
    }

    private fun button(text: String, action: () -> Unit) = JButton(text).apply { addActionListener { action() } }

    private fun draft(): WorkflowLibraryEntry? = baseline?.copy(
        title = title.text, instructions = body.text, steps = stepModel.elements().toList(),
    )

    fun canLeave(): Boolean {
        val current = draft()
        val unsavedCopy = current != null && library.entries.none { it.id == current.id } &&
            (current.title.isNotBlank() || current.instructions.isNotEmpty() || current.steps.isNotEmpty())
        if (current == baseline && !unsavedCopy) return true
        return when (askUnsaved(this)) { 0 -> saveEntry(); 1 -> true; else -> false }
    }

    fun createEntry(kind: WorkflowEntryKind) {
        if (!available || !canLeave()) return
        display(WorkflowLibraryEntry(kind = kind))
        selectCurrent()
        title.requestFocusInWindow()
    }

    fun saveEntry(): Boolean {
        val draft = draft() ?: return true
        if (draft.title.isBlank()) {
            showError(this, "Enter a title before saving.")
            title.requestFocusInWindow()
            return false
        }
        val saved = draft.copy(title = draft.title.trim(), updatedAt = System.currentTimeMillis())
        val next = library.copy(entries = library.entries.filterNot { it.id == saved.id } + saved)
        if (!persist(next)) return false
        display(saved)
        refreshList()
        status.text = "Saved ${saved.title}."
        return true
    }

    private fun duplicateEntry() {
        if (!canLeave()) return
        val entry = baseline?.let { selected -> library.entries.find { it.id == selected.id } } ?: return
        val now = System.currentTimeMillis()
        display(entry.copy(id = UUID.randomUUID().toString(), title = "${entry.title} (copy)", createdAt = now, updatedAt = now))
        selectCurrent()
    }

    private fun deleteEntry() {
        val id = baseline?.id ?: return
        if (!confirmDelete(this)) return
        if (!persist(library.copy(entries = library.entries.filterNot { it.id == id }))) return
        display(null)
        refreshList()
    }

    private fun persist(next: WorkflowLibrary): Boolean = try {
        store.save(next)
        library = next
        true
    } catch (e: Exception) {
        showError(this, "Could not save the workflow library: ${e.message}")
        false
    }

    private fun display(entry: WorkflowLibraryEntry?) {
        baseline = entry
        title.text = entry?.title.orEmpty()
        body.text = entry?.instructions.orEmpty()
        stepModel.clear()
        entry?.steps?.forEach(stepModel::addElement)
        setEnabledRecursively(editor, entry != null && available)
        stepPanel.isVisible = entry?.kind == WorkflowEntryKind.PROTOCOL
        editor.revalidate()
    }

    private fun refreshList() {
        refreshing = true
        entries.clear()
        val query = search.text.trim()
        library.entries.filter { entry ->
            (filter.selectedIndex == 0 || entry.kind.toString() == filter.selectedItem) &&
                (listOf(entry.title, entry.instructions) + entry.steps.flatMap { listOf(it.title, it.instructions) })
                    .any { it.contains(query, ignoreCase = true) }
        }.sortedBy { it.title.lowercase() }.forEach(entries::addElement)
        refreshing = false
        selectCurrent()
    }

    private fun selectCurrent() {
        refreshing = true
        list.selectedIndex = (0 until entries.size()).firstOrNull { entries[it].id == baseline?.id } ?: -1
        refreshing = false
    }

    private fun editStep(add: Boolean) {
        val index = steps.selectedIndex
        if (!add && index < 0) return
        val old = if (add) WorkflowLibraryStep() else stepModel[index]
        val heading = JTextField(old.title, 30)
        val detail = JTextArea(old.instructions, 10, 40).apply { lineWrap = true; wrapStyleWord = true }
        val fields = JPanel(BorderLayout(4, 4)).apply {
            add(JPanel(BorderLayout()).apply { add(JLabel("Step title"), BorderLayout.NORTH); add(heading) }, BorderLayout.NORTH)
            add(JScrollPane(detail))
        }
        if (JOptionPane.showConfirmDialog(this, fields, if (add) "Add step" else "Edit step",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return
        val updated = WorkflowLibraryStep(heading.text, detail.text)
        if (add) { stepModel.addElement(updated); steps.selectedIndex = stepModel.size() - 1 }
        else stepModel.set(index, updated)
        stepPreview.text = updated.instructions
    }

    private fun moveStep(delta: Int) {
        val index = steps.selectedIndex
        val target = index + delta
        if (index < 0 || target !in 0 until stepModel.size()) return
        val step = stepModel.remove(index)
        stepModel.add(target, step)
        steps.selectedIndex = target
    }

    private fun setEnabledRecursively(component: Component, enabled: Boolean) {
        component.isEnabled = enabled
        if (component is java.awt.Container) component.components.forEach { setEnabledRecursively(it, enabled) }
    }

    companion object {
        fun show(owner: Window?) {
            val dialog = JDialog(owner, "Workflow Library", java.awt.Dialog.ModalityType.APPLICATION_MODAL)
            val panel = WorkflowLibraryPanel(WorkflowLibraryStore(File(AppDirs.configDir(), "workflow-library.json")))
            dialog.contentPane = panel
            dialog.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
            dialog.addWindowListener(object : WindowAdapter() {
                override fun windowClosing(e: WindowEvent) { if (panel.canLeave()) dialog.dispose() }
            })
            dialog.minimumSize = Dimension(900, 600)
            dialog.setSize(1050, 720)
            dialog.setLocationRelativeTo(owner)
            dialog.isVisible = true
        }
    }
}
