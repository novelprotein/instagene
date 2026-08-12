package org.instagene.app.gui.enzyme

import org.instagene.app.gui.prefs.Prefs
import org.instagene.core.Enzyme
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Frame
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel

/** Modal editor for every user-editable property of one effective enzyme row. */
class EnzymeElementDialog(
    prefs: Prefs,
    private val enzyme: Enzyme,
    owner: Frame? = null,
) : JDialog(owner, "Edit Enzyme", true) {

    private val model = EnzymeManagerModel(prefs)
    private val nameField = JTextField(enzyme.name, 18)
    private val siteField = JTextField(enzyme.site, 18)
    private val topCutSpinner = JSpinner(SpinnerNumberModel(enzyme.topCut, 0, 40, 1))
    private val bottomCutSpinner = JSpinner(SpinnerNumberModel(enzyme.bottomCut, 0, 40, 1))
    private val enabledBox = JCheckBox("Enabled in working set", model.isEnabled(enzyme))
    private val initialDescription = model.working.enzymeDescriptionFor(enzyme)
    private val hadDescriptionOverride = model.working.enzymeDescriptions.containsKey(enzyme.name.lowercase())
    private val descriptionField = JTextArea(initialDescription, 6, 36)
    private val validationLabel = JLabel(" ")

    init {
        contentPane.layout = BorderLayout(0, 8)
        contentPane.add(buildForm(), BorderLayout.CENTER)
        contentPane.add(buildButtons(), BorderLayout.SOUTH)
        minimumSize = Dimension(480, 390)
        pack()
        setLocationRelativeTo(owner)
    }

    private fun buildForm(): JPanel = JPanel(BorderLayout(0, 8)).apply {
        border = BorderFactory.createEmptyBorder(10, 10, 0, 10)
        add(JPanel(GridLayout(5, 2, 8, 8)).apply {
            add(JLabel("Name")); add(nameField)
            add(JLabel("Recognition site")); add(siteField)
            add(JLabel("Top cut")); add(topCutSpinner)
            add(JLabel("Bottom cut")); add(bottomCutSpinner)
            add(JLabel("")); add(enabledBox)
        }, BorderLayout.NORTH)
        add(JPanel(BorderLayout(0, 4)).apply {
            add(JLabel("Description"), BorderLayout.NORTH)
            descriptionField.lineWrap = true
            descriptionField.wrapStyleWord = true
            add(JScrollPane(descriptionField), BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        add(validationLabel, BorderLayout.SOUTH)
    }

    private fun buildButtons(): JPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 8)).apply {
        add(JButton("Cancel").apply { addActionListener { dispose() } })
        add(JButton("Save").apply { addActionListener { save() } })
    }

    private fun save() {
        // Do not turn a shipped default into a user override merely by opening
        // and saving the dialog. This lets future built-in descriptions update.
        val description = if (!hadDescriptionOverride && descriptionField.text == initialDescription) "" else descriptionField.text
        val error = model.editEnzyme(
            enzyme = enzyme,
            name = nameField.text,
            site = siteField.text,
            topCut = (topCutSpinner.value as Number).toInt(),
            bottomCut = (bottomCutSpinner.value as Number).toInt(),
            enabled = enabledBox.isSelected,
            description = description,
        )
        if (error == null) {
            model.commit()
            dispose()
        } else {
            validationLabel.foreground = Color(0xC0, 0x39, 0x2B)
            validationLabel.text = error
        }
    }
}
