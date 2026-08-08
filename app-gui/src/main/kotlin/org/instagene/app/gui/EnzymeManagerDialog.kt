package org.instagene.app.gui

import org.instagene.core.Enzyme
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Frame
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SpinnerNumberModel
import javax.swing.table.AbstractTableModel

/**
 * Modal dialog for managing the enzyme catalog: toggling the required-only
 * working set and adding/removing novel ("custom") enzymes. Edits happen
 * against an [EnzymeManagerModel] and only persist when the user hits Done.
 */
class EnzymeManagerDialog(
    prefs: Prefs,
    owner: Frame? = null,
    initialName: String? = null,
) : JDialog(owner, "Manage Enzymes", true) {

    private val model = EnzymeManagerModel(prefs)
    private val tableModel = EnzymeTableModel(model)
    private val enzymeTable = JTable(tableModel)
    private val nameField = JTextField(12)
    private val siteField = JTextField(10)
    private val topCutSpinner = JSpinner(SpinnerNumberModel(1, 0, 40, 1))
    private val bottomCutSpinner = JSpinner(SpinnerNumberModel(1, 0, 40, 1))
    private val validationLabel = JLabel(" ")

    init {
        enzymeTable.rowHeight = 20
        enzymeTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        enzymeTable.columnModel.getColumn(0).maxWidth = 30
        enzymeTable.columnModel.getColumn(4).maxWidth = 70
        model.addListener { tableModel.fireTableDataChanged() }

        contentPane.layout = BorderLayout(0, 6)
        contentPane.add(buildForm(), BorderLayout.NORTH)
        contentPane.add(JScrollPane(enzymeTable), BorderLayout.CENTER)
        contentPane.add(buildButtons(), BorderLayout.SOUTH)

        if (initialName != null) nameField.text = initialName
        pack()
        setLocationRelativeTo(owner)
        minimumSize = Dimension(620, 380)
    }

    private fun buildForm(): JPanel = JPanel().apply {
        border = BorderFactory.createTitledBorder("Add novel enzyme")
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(JLabel("Name"))
            add(nameField)
            add(JLabel("Site"))
            add(siteField)
            add(JLabel("Top cut"))
            add(topCutSpinner)
            add(JLabel("Bottom cut"))
            add(bottomCutSpinner)
            add(JButton("Add").apply {
                addActionListener { addFromForm() }
            })
        })
        add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(validationLabel)
        })
    }

    private fun buildButtons(): JPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 6)).apply {
        add(JButton("Remove selected").apply {
            addActionListener { removeSelected() }
        })
        add(JButton("Done").apply {
            addActionListener {
                this@EnzymeManagerDialog.model.commit()
                dispose()
            }
        })
    }

    private fun addFromForm() {
        val error = model.addEnzyme(
            name = nameField.text,
            site = siteField.text,
            topCut = (topCutSpinner.value as Number).toInt(),
            bottomCut = (bottomCutSpinner.value as Number).toInt(),
        )
        if (error == null) {
            validationLabel.text = " "
            nameField.text = ""
            siteField.text = ""
            topCutSpinner.value = 1
            bottomCutSpinner.value = 1
        } else {
            validationLabel.foreground = java.awt.Color(0xC0, 0x39, 0x2B)
            validationLabel.text = error
        }
    }

    private fun removeSelected() {
        val row = enzymeTable.selectedRow
        if (row !in 0 until tableModel.rowCount) return
        val enzyme = tableModel.enzymeAt(row)
        if (!model.isCustom(enzyme)) {
            JOptionPane.showMessageDialog(
                this,
                "'${enzyme.name}' is a built-in enzyme and cannot be removed.",
                "Cannot Remove",
                JOptionPane.INFORMATION_MESSAGE,
            )
            return
        }
        model.removeEnzyme(enzyme)
    }

    private class EnzymeTableModel(private val model: EnzymeManagerModel) : AbstractTableModel() {

        private val columns = arrayOf("", "Enzyme", "Site", "Cuts", "Source")

        fun enzymeAt(row: Int): Enzyme = model.pool[row]

        override fun getRowCount(): Int = model.pool.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]
        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == 0) Boolean::class.java else String::class.java
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex == 0

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val enzyme = enzymeAt(rowIndex)
            return when (columnIndex) {
                0 -> model.isEnabled(enzyme)
                1 -> enzyme.name
                2 -> enzyme.site
                3 -> "${enzyme.topCut}/${enzyme.bottomCut}"
                else -> if (model.isCustom(enzyme)) "custom" else "built-in"
            }
        }

        override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
            if (columnIndex != 0) return
            model.setEnabled(enzymeAt(rowIndex), value == true)
        }
    }
}
