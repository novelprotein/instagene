package org.instagene.app.gui.analysis

import org.instagene.app.gui.row
import org.instagene.core.*
import org.instagene.core.io.SeqIO
import java.awt.BorderLayout
import java.awt.Dialog
import java.awt.Window
import javax.swing.*

/**
 * Researcher-facing front end for [PcrCloningWorkflows]. The active sequence is
 * intentionally the circular backbone; a separate template is chosen for the
 * insert so it is difficult to accidentally model the wrong molecule.
 */
internal class PcrCloningWizardDialog(
    owner: Window?,
    private val backbone: Seq,
    private val onOpenSequence: (Seq) -> Unit,
) : JDialog(owner, "PCR-cloning wizard", Dialog.ModalityType.APPLICATION_MODAL) {
    private val templatePath = JTextField(34)
    private val targetStart = JSpinner(SpinnerNumberModel(1, 1, 1, 1))
    private val targetEnd = JSpinner(SpinnerNumberModel(1, 1, 1, 1))
    private val enzymes = JTextField("EcoRI,HindIII", 18)
    private val leftClamp = JTextField("GCGC", 6)
    private val rightClamp = JTextField("GCGC", 6)
    private val productName = JTextField("${backbone.name}_pcr_clone", 24)
    private val output = JTextArea(18, 86).apply { isEditable = false; font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12) }
    private val openProduct = JButton("Open product").apply { isEnabled = false }
    private val saveReport = JButton("Save report").apply { isEnabled = false }
    private val saveRecipe = JButton("Save recipe").apply { isEnabled = false }
    private var template: Seq? = null
    private var result: PcrCloningResult? = null

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        val chooseTemplate = JButton("Choose insert template…").apply {
            toolTipText = "Choose the sequence that contains the PCR insert."
            addActionListener { chooseTemplate() }
        }
        val run = JButton("Design and validate").apply {
            toolTipText = "Design endpoint primers, simulate PCR, validate restriction sites, and build the product."
            addActionListener { execute() }
        }
        openProduct.toolTipText = "Open the validated circular product in a new sequence tab."
        openProduct.addActionListener { result?.product?.let(onOpenSequence) }
        saveReport.toolTipText = "Save the normalized cloning report as Markdown, JSON, HTML, or PDF."
        saveReport.addActionListener { saveReport() }
        saveRecipe.toolTipText = "Save the portable identity-matched workflow recipe as JSON."
        saveRecipe.addActionListener { saveRecipe() }

        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(row(JLabel("Backbone"), JLabel("${backbone.name} (${backbone.length} bp, ${backbone.topology.name.lowercase()})")))
            add(row(chooseTemplate, templatePath))
            add(row(JLabel("Target (1-based inclusive)"), targetStart, JLabel("to"), targetEnd, JLabel("Enzymes"), enzymes))
            add(row(JLabel("5' clamps"), leftClamp, rightClamp, JLabel("Product name"), productName, run, openProduct))
            add(row(saveReport, saveRecipe, JLabel("One enzyme permits either insert orientation; two distinct enzymes make a directional clone.")))
        }, BorderLayout.NORTH)
        add(JScrollPane(output), BorderLayout.CENTER)
        pack()
        setLocationRelativeTo(owner)
    }

    private fun chooseTemplate() {
        val chooser = JFileChooser().apply { dialogTitle = "Choose PCR insert template" }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        runCatching { SeqIO.read(chooser.selectedFile) }.onSuccess { selected ->
            template = selected
            templatePath.text = chooser.selectedFile.absolutePath
            targetStart.model = SpinnerNumberModel(1, 1, selected.length.coerceAtLeast(1), 1)
            targetEnd.model = SpinnerNumberModel(selected.length.coerceAtLeast(1), 1, selected.length.coerceAtLeast(1), 1)
            productName.text = "${backbone.name}_${selected.name}_pcr_clone"
            result = null
            openProduct.isEnabled = false
            saveReport.isEnabled = false
            saveRecipe.isEnabled = false
        }.onFailure { error -> showError(error.message ?: "Unable to read the selected insert template") }
    }

    private fun execute() {
        val selectedTemplate = template ?: run {
            showError("Choose an insert template before designing the clone.")
            return
        }
        runCatching {
            val start = (targetStart.value as Number).toInt() - 1
            val end = (targetEnd.value as Number).toInt()
            require(start < end) { "The target end must be after the target start." }
            PcrCloningWorkflows.designAndClone(
                PcrCloningRequest(
                    backbone = backbone,
                    insertTemplate = selectedTemplate,
                    insertStart = start,
                    insertEnd = end,
                    enzymes = Enzymes.parseList(enzymes.text),
                    productName = productName.text.trim().ifBlank { "${backbone.name}_${selectedTemplate.name}_pcr_clone" },
                    leftClamp = leftClamp.text,
                    rightClamp = rightClamp.text,
                ),
            )
        }.onSuccess { completed ->
            result = completed
            output.text = render(completed)
            openProduct.isEnabled = true
            saveReport.isEnabled = true
            saveRecipe.isEnabled = true
        }.onFailure { error ->
            result = null
            openProduct.isEnabled = false
            saveReport.isEnabled = false
            saveRecipe.isEnabled = false
            output.text = "PCR cloning could not be validated:\n${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun saveReport() {
        val completed = result ?: return
        val chooser = JFileChooser().apply { dialogTitle = "Save PCR-cloning report" }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        runCatching {
            val report = Reports.pcrCloningReport(completed)
            when (chooser.selectedFile.extension.lowercase()) {
                "json" -> chooser.selectedFile.writeText(Reports.pcrCloningJson(report))
                "html", "htm" -> chooser.selectedFile.writeText(Reports.pcrCloningHtml(report))
                "pdf" -> chooser.selectedFile.writeBytes(Reports.pcrCloningPdf(report))
                else -> chooser.selectedFile.writeText(Reports.pcrCloningMarkdown(report))
            }
        }.onFailure { error -> showError(error.message ?: "Unable to build report") }
    }

    private fun saveRecipe() {
        val completed = result ?: return
        val chooser = JFileChooser().apply { dialogTitle = "Save PCR-cloning recipe" }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        runCatching { chooser.selectedFile.writeText(org.instagene.core.WorkflowRecipes.encode(completed.recipe)) }
            .onFailure { error -> showError(error.message ?: "Unable to save recipe") }
    }

    private fun render(completed: PcrCloningResult): String = buildString {
        appendLine("Validated PCR restriction clone: ${completed.product.name}")
        appendLine("Product: ${completed.product.length} bp, ${completed.product.topology.name.lowercase()}")
        appendLine("Forward primer (5'→3'): ${completed.forwardPrimer.extension}${completed.forwardPrimer.hybridizingSequence}")
        appendLine("Reverse primer (5'→3'): ${completed.reversePrimer.extension}${completed.reversePrimer.hybridizingSequence}")
        appendLine("Template target: ${completed.validation.coordinates.templateTarget.displayRange()}")
        appendLine("Amplicon target: ${completed.validation.coordinates.pcrTarget.displayRange()}")
        appendLine("Product insert: ${completed.validation.coordinates.productInsert.displayRange()}")
        appendLine("Validation: target match=${completed.validation.targetMatchesAmplicon}; restriction sites=${completed.validation.restrictionSitesAreUniqueInAmplicon}; product target=${completed.validation.productContainsTarget}")
        completed.validation.diagnostics.forEach { appendLine("${it.severity}: ${it.message}") }
        appendLine()
        append(completed.product.bases.chunked(80).joinToString("\n"))
    }

    private fun showError(message: String) {
        JOptionPane.showMessageDialog(this, message, "PCR-cloning wizard", JOptionPane.ERROR_MESSAGE)
    }
}
