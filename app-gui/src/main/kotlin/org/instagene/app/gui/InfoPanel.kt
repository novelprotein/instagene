package org.instagene.app.gui

import org.instagene.core.Seq
import org.instagene.core.SeqKind
import org.instagene.core.SeqOps
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities

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

    /** Full-sequence scans (GC, Tm, MW) run off the EDT above this size, so a multi-GB genome load doesn't freeze the UI. */
    private val statsAsyncThreshold = 50_000_000
    private var statsGeneration = 0

    val nameField = JTextField(28)
    private val nameApply = JButton("Apply name")
    val descriptionField = JTextArea(3, 28).apply {
        lineWrap = true
        wrapStyleWord = true
        // The description applies itself once the box loses focus.
        addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) = setDescription()
        })
    }
    private val descriptionScroll = JScrollPane(descriptionField).apply {
        preferredSize = Dimension(280, 72)
    }

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
        openFileButton.addActionListener { onOpen() }

        val properties = JPanel(GridBagLayout()).apply {
            border = BorderFactory.createTitledBorder("Properties")
            var y = 0
            fun labelRow(title: String, component: javax.swing.JComponent) {
                add(JLabel(title), constraints(0, y, anchor = GridBagConstraints.NORTHWEST))
                add(component, constraints(1, y, weightX = 1.0, anchor = GridBagConstraints.NORTHWEST))
                y++
            }
            labelRow("Name", JPanel(BorderLayout(6, 0)).apply {
                add(nameField, BorderLayout.CENTER)
                add(nameApply, BorderLayout.EAST)
            })
            labelRow("Description", descriptionScroll)
            labelRow("Kind", kindLabel)
            labelRow("Topology", topologyLabel)
            labelRow("Length", lengthLabel)
            labelRow("File", JPanel(BorderLayout(6, 0)).apply {
                add(fileLabel, BorderLayout.CENTER)
                add(openFileButton, BorderLayout.EAST)
            })
        }

        val statistics = JPanel(GridBagLayout()).apply {
            border = BorderFactory.createTitledBorder("Statistics")
            var y = 0
            fun statRow(title: String, value: JLabel) {
                add(JLabel(title), constraints(0, y, anchor = GridBagConstraints.NORTHWEST))
                add(value, constraints(1, y, weightX = 1.0, anchor = GridBagConstraints.NORTHWEST))
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
        featuresLabel.text = seq.features.size.toString()
        refreshStats(seq)
    }

    /** GC/Tm/MW are O(length) scans, so big sequences compute them off the EDT. */
    private fun refreshStats(seq: Seq) {
        val generation = ++statsGeneration
        if (seq.length <= statsAsyncThreshold) {
            gcLabel.text = "%.1f %%".format(SeqOps.gcContent(seq))
            tmLabel.text =
                if (seq.kind == SeqKind.PROTEIN) "n/a (protein)" else "%.1f C".format(SeqOps.meltingTemp(seq.bases))
            mwLabel.text =
                if (seq.kind == SeqKind.PROTEIN) "n/a" else "%.0f Da".format(SeqOps.molecularWeightDaltons(seq))
            return
        }
        gcLabel.text = "..."
        tmLabel.text = "..."
        mwLabel.text = "..."
        Thread {
            val gc = SeqOps.gcContent(seq)
            val tm = if (seq.kind == SeqKind.PROTEIN) null else SeqOps.meltingTemp(seq.bases)
            val mw = if (seq.kind == SeqKind.PROTEIN) null else SeqOps.molecularWeightDaltons(seq)
            SwingUtilities.invokeLater {
                if (generation == statsGeneration) {
                    gcLabel.text = "%.1f %%".format(gc)
                    tmLabel.text = if (tm == null) "n/a (protein)" else "%.1f C".format(tm)
                    mwLabel.text = if (mw == null) "n/a" else "%.0f Da".format(mw)
                }
            }
        }.apply { name = "InfoPanel-stats"; isDaemon = true }.start()
    }

    private fun constraints(
        x: Int,
        y: Int,
        weightX: Double = 0.0,
        anchor: Int = GridBagConstraints.WEST,
    ): GridBagConstraints =
        GridBagConstraints().apply {
            gridx = x
            gridy = y
            weightx = weightX
            this.anchor = anchor
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
