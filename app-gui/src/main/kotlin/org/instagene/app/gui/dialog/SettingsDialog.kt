package org.instagene.app.gui.dialog

import org.instagene.app.gui.prefs.Prefs
import org.instagene.core.ExternalTools
import org.instagene.core.io.FormatSupport
import org.instagene.core.io.SequenceFormatCatalog
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.*
import javax.swing.table.DefaultTableModel

/** Persisted general/analysis/file settings corresponding to ApE's Settings dialog. */
object SettingsDialog {
    fun show(frame: JFrame?, prefs: Prefs) {
        val current = prefs.value
        val inline = JCheckBox("Inline feature mode", current.inlineFeatureMode)
        val second = JCheckBox("Show second strand", current.showSecondStrand)
        val transparency = JSpinner(SpinnerNumberModel(current.featureTransparency, 0, 100, 5))
        val width = JSpinner(SpinnerNumberModel(current.defaultSequenceWidth, 10, 500, 10))
        val code = JComboBox(arrayOf("1 - Standard", "11 - Bacterial / Plasmid"))
        code.selectedIndex = if (current.geneticCode == 11) 1 else 0
        val dam = JCheckBox("Assume Dam/Dcm methylation", current.damMethylationDefault)
        val autosave = JCheckBox("Enable autosave", current.autosaveEnabled)
        val frequency = JSpinner(SpinnerNumberModel(current.autosaveFrequencyMinutes, 1, 120, 1))
        val versions = JSpinner(SpinnerNumberModel(current.autosaveMaxVersions, 1, 100, 1))

        val tabs = JTabbedPane().apply {
            addTab("General", JPanel(GridLayout(0, 2, 8, 8)).apply {
                border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
                add(inline); add(JLabel(""))
                add(second); add(JLabel(""))
                add(JLabel("Feature transparency (%)")); add(transparency)
                add(JLabel("Bases per sequence row")); add(width)
            })
            addTab("Analysis", JPanel(GridLayout(0, 2, 8, 8)).apply {
                border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
                add(JLabel("Genetic code")); add(code)
                add(dam); add(JLabel(""))
            })
            addTab("Files", JPanel(GridLayout(0, 2, 8, 8)).apply {
                border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
                add(autosave); add(JLabel(""))
                add(JLabel("Autosave frequency (minutes)")); add(frequency)
                add(JLabel("Autosave versions")); add(versions)
            })
            addTab("External Tools", externalToolsPanel())
        }
        val result = JOptionPane.showConfirmDialog(frame, tabs, "Settings", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        if (result != JOptionPane.OK_OPTION) return
        prefs.update {
            it.copy(
                inlineFeatureMode = inline.isSelected,
                showSecondStrand = second.isSelected,
                featureTransparency = (transparency.value as Number).toInt(),
                defaultSequenceWidth = (width.value as Number).toInt(),
                geneticCode = if (code.selectedIndex == 1) 11 else 1,
                damMethylationDefault = dam.isSelected,
                autosaveEnabled = autosave.isSelected,
                autosaveFrequencyMinutes = (frequency.value as Number).toInt(),
                autosaveMaxVersions = (versions.value as Number).toInt(),
            )
        }
    }

    fun externalToolsPanel(): JPanel {
        val toolModel = object : DefaultTableModel(arrayOf("Tool", "Status", "Install / Built-in"), 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }
        val converterModel = object : DefaultTableModel(arrayOf("Format", "Extensions", "Converter variable"), 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }
        val toolTable = JTable(toolModel).apply {
            rowHeight = 22
            toolTipText = "Optional command-line tools used by alignment and structure workflows."
        }
        val converterTable = JTable(converterModel).apply {
            rowHeight = 22
            toolTipText = "Converter-backed sequence formats require an environment variable command."
        }

        fun reloadTools() {
            ExternalTools.rescan()
            toolModel.rowCount = 0
            ExternalTools.CATALOG.forEach { tool ->
                val path = ExternalTools.locate(tool.executable)
                val status = if (path == null) "Missing" else "Available: $path"
                val hint = if (path == null) tool.installHint else tool.builtinEquivalent
                toolModel.addRow(arrayOf(tool.displayName, status, hint))
            }
        }

        converterModel.rowCount = 0
        SequenceFormatCatalog.FORMATS
            .filter { it.support == FormatSupport.CONVERTER }
            .forEach { format ->
                converterModel.addRow(arrayOf(format.displayName, format.extensions.joinToString(", "), converterEnvironmentKey(format.id)))
            }
        reloadTools()

        val rescan = JButton("Rescan").apply {
            toolTipText = "Rescan PATH for optional external tools."
            addActionListener { reloadTools() }
        }

        return JPanel(BorderLayout(8, 8)).apply {
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                add(JLabel("Optional tools and converter-backed formats"))
                add(rescan)
            }, BorderLayout.NORTH)
            add(JTabbedPane().apply {
                addTab("Tools", JScrollPane(toolTable).apply { preferredSize = Dimension(720, 220) })
                addTab("Converters", JScrollPane(converterTable).apply { preferredSize = Dimension(720, 220) })
            }, BorderLayout.CENTER)
        }
    }

    private fun converterEnvironmentKey(formatId: String): String =
        "INSTAGENE_CONVERTER_" + formatId.uppercase().replace(Regex("[^A-Z0-9]"), "_")
}
