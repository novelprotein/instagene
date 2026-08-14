package org.instagene.app.gui.dialog

import org.instagene.app.gui.prefs.Prefs
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTabbedPane
import javax.swing.SpinnerNumberModel

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
}
