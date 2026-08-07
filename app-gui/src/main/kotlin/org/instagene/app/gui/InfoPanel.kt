package org.instagene.app.gui

import org.instagene.core.SeqKind
import org.instagene.core.SeqOps
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * Sequence properties and statistics: name, kind, topology, length, GC, Tm,
 * molecular weight, description and feature count. The name field is editable
 * (undoable); everything else is derived from the document.
 *
 * When no file is loaded the File row offers an "Open File..." button, wired to
 * [onOpen] by the caller (usually the same chooser flow as the File menu).
 */
class InfoPanel(
    private val doc: SeqDocument,
    private val onOpen: () -> Unit = {},
) : JPanel(BorderLayout(0, 6)) {

    val nameField = JTextField(28)
    private val nameApply = JButton("Apply name")
    val descriptionField = JTextField(28)
    private val descriptionApply = JButton("Apply description")

    val kindLabel = JLabel("-")
    val topologyLabel = JLabel("-")
    val lengthLabel = JLabel("-")
    val gcLabel = JLabel("-")
    val tmLabel = JLabel("-")
    val mwLabel = JLabel("-")
    val featuresLabel = JLabel("-")
    val fileLabel = JLabel("-")
    val openFileButton = JButton("Open File...")

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        nameApply.addActionListener { rename() }
        descriptionApply.addActionListener { setDescription() }
        openFileButton.addActionListener { onOpen() }

        val properties = JPanel(GridBagLayout()).apply {
            border = BorderFactory.createTitledBorder("Properties")
            var y = 0
            fun labelRow(title: String, component: JPanel) {
                add(JLabel(title), constraints(0, y))
                add(component, constraints(1, y, weightX = 1.0))
                y++
            }
            labelRow("Name", JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                add(nameField)
                add(nameApply)
            })
            labelRow("Description", JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                add(descriptionField)
                add(descriptionApply)
            })
            labelRow("Kind", JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { add(kindLabel) })
            labelRow("Topology", JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { add(topologyLabel) })
            labelRow("Length", JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { add(lengthLabel) })
            labelRow("File", JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                add(fileLabel)
                add(openFileButton)
            })
        }

        val statistics = JPanel(GridBagLayout()).apply {
            border = BorderFactory.createTitledBorder("Statistics")
            var y = 0
            fun statRow(title: String, value: JLabel) {
                add(JLabel(title), constraints(0, y))
                add(value, constraints(1, y, weightX = 1.0))
                y++
            }
            statRow("GC content", gcLabel)
            statRow("Melting temp", tmLabel)
            statRow("Mol. weight", mwLabel)
            statRow("Features", featuresLabel)
        }

        add(properties, BorderLayout.NORTH)
        add(statistics, BorderLayout.CENTER)

        doc.addListener { _, reason ->
            if (reason == SeqDocument.Reason.SEQUENCE) refresh()
        }
        refresh()
    }

    fun refresh() {
        val seq = doc.seq
        if (!nameField.hasFocus()) nameField.text = seq.name
        if (!descriptionField.hasFocus()) descriptionField.text = seq.description
        kindLabel.text = seq.kind.name.lowercase()
        topologyLabel.text = seq.topology.name.lowercase()
        val unit = if (seq.kind == SeqKind.PROTEIN) "aa" else "bp"
        lengthLabel.text = "${seq.length} $unit"
        val hasFile = doc.file != null
        fileLabel.text = doc.file?.absolutePath ?: ""
        fileLabel.isVisible = hasFile
        openFileButton.isVisible = !hasFile
        gcLabel.text = "%.1f %%".format(SeqOps.gcContent(seq))
        tmLabel.text = if (seq.kind == SeqKind.PROTEIN) {
            "n/a (protein)"
        } else {
            "%.1f C".format(SeqOps.meltingTemp(seq.bases))
        }
        mwLabel.text = if (seq.kind == SeqKind.PROTEIN) "n/a" else "%.0f Da".format(SeqOps.molecularWeightDaltons(seq))
        featuresLabel.text = seq.features.size.toString()
    }

    private fun constraints(x: Int, y: Int, weightX: Double = 0.0): GridBagConstraints =
        GridBagConstraints().apply {
            gridx = x
            gridy = y
            weightx = weightX
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(3, 6, 3, 6)
        }

    private fun rename() {
        val newName = nameField.text.trim()
        if (newName.isNotEmpty() && newName != doc.seq.name) {
            doc.mutate("rename") { it.withName(newName) }
        }
    }

    /** Sets the name field and applies it (undoable); used by tests. */
    fun renameTo(newName: String) {
        nameField.text = newName
        rename()
    }

    private fun setDescription() {
        val text = descriptionField.text
        if (text != doc.seq.description) {
            doc.mutate("edit description") { it.copy(description = text) }
        }
    }
}
