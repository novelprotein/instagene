package org.instagene.app.gui.analysis

import org.instagene.core.*
import org.instagene.core.io.SeqIO
import java.awt.BorderLayout
import java.io.File
import javax.swing.*

internal class AssemblyAnalysisPanel(private val onOpenSequence: (Seq) -> Unit) : BoundAnalysisPanel() {
    private val mode = JComboBox(arrayOf(
        "Restriction cloning", "Gateway cloning", "Gibson assembly", "NEBuilder HiFi", "In-Fusion cloning",
        "TA cloning", "GC cloning", "TA TOPO", "Directional TOPO", "Blunt TOPO", "Golden Gate", "Homology recombination",
    ))
    private val parts = JTextField(36)
    private val enzymes = JTextField("EcoRI", 12)
    private val overhangs = JTextField("A,G,A", 12)
    private val arm = JSpinner(SpinnerNumberModel(15, 1, 1000, 1))
    private val gatewaySites = JTextField("GGGGACAAGTTTGTACAAAAAAGCAGGCT,GGGGACCACTTTGTACAAGAAAGCTGGGT", 28)
    private val productName = JTextField("assembly_product", 18)
    private val circular = JCheckBox("Circular product", true)
    private val output = output()
    private var product: Seq? = null
    private var lastWorkflow: MolecularWorkflowResult? = null
    private var lastInputs: List<Seq> = emptyList()

    init {
        mode.selectedIndex = 2
        val choose = JButton("Choose parts...")
        choose.addActionListener {
            val chooser = JFileChooser().apply { isMultiSelectionEnabled = true }
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) parts.text = chooser.selectedFiles.joinToString(",") { it.absolutePath }
        }
        val run = JButton("Preview")
        run.addActionListener { execute() }
        val open = JButton("Open product")
        open.addActionListener { product?.let(onOpenSequence) }
        val save = JButton("Save product")
        save.addActionListener {
            val result = product ?: return@addActionListener
            val chooser = JFileChooser().apply { dialogTitle = "Save assembly product" }
            if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                runCatching { chooser.selectedFile.writeText(SeqIO.write(result, SeqIO.formatOf(chooser.selectedFile))) }
                    .onFailure { JOptionPane.showMessageDialog(this, it.message ?: "Unable to save product", "Assembly", JOptionPane.ERROR_MESSAGE) }
            }
        }
        val saveReport = JButton("Save report")
        saveReport.toolTipText = "Export the reproducible workflow summary as Markdown, JSON, HTML, or PDF."
        saveReport.addActionListener { saveWorkflowReport() }
        val saveRecipe = JButton("Save recipe")
        saveRecipe.toolTipText = "Save the typed, identity-matched workflow recipe as JSON."
        saveRecipe.addActionListener { saveWorkflowRecipe() }
        val replayRecipe = JButton("Replay recipe…").apply {
            toolTipText = "Choose a workflow recipe and identity-matched inputs to reproduce its product."
            addActionListener {
                WorkflowRecipeReplayDialog(SwingUtilities.getWindowAncestor(this@AssemblyAnalysisPanel), onOpenSequence).isVisible = true
            }
        }
        val pcrCloning = JButton("PCR-cloning wizard…").apply {
            toolTipText = "Design PCR primers for an insert, validate restriction cloning, and save a report or recipe."
            addActionListener {
                if (!doc.seq.isCircular) {
                    JOptionPane.showMessageDialog(
                        this@AssemblyAnalysisPanel,
                        "Open or select a circular plasmid backbone before starting PCR cloning.",
                        "PCR-cloning wizard",
                        JOptionPane.INFORMATION_MESSAGE,
                    )
                } else {
                    PcrCloningWizardDialog(SwingUtilities.getWindowAncestor(this@AssemblyAnalysisPanel), doc.seq, onOpenSequence)
                        .isVisible = true
                }
            }
        }
        add(row(JLabel("Workflow"), mode, choose, parts, pcrCloning, replayRecipe), BorderLayout.NORTH)
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(row(JLabel("Enzymes"), enzymes, JLabel("Overhangs"), overhangs, JLabel("Homology arm"), arm))
            add(row(JLabel("Gateway left,right"), gatewaySites, JLabel("Name"), productName, circular, run, open, save, saveReport, saveRecipe))
        }, BorderLayout.SOUTH)
        add(JScrollPane(output), BorderLayout.CENTER)
    }

    private fun execute() {
        val files = parts.text.split(',').map(String::trim).filter(String::isNotEmpty)
        runCatching {
            val loaded = files.map { SeqIO.read(File(it)) }
            val orderedParts = if (doc.seq.bases.isBlank()) loaded else listOf(doc.seq) + loaded
            fun firstInsert(): Seq = loaded.firstOrNull() ?: error("Choose at least one insert sequence")
            when (mode.selectedIndex) {
                0 -> firstInsert().let { insert ->
                    CloningWorkflows.restriction(doc.seq, insert, Enzymes.parseList(enzymes.text), productName.text) to listOf(doc.seq, insert)
                }
                1 -> gatewaySites.text.split(',').map(String::trim).let { sites ->
                    require(sites.size == 2) { "Enter left and right Gateway recombination sites" }
                    firstInsert().let { insert ->
                        CloningWorkflows.gateway(doc.seq, insert, sites[0], sites[1], productName.text) to listOf(doc.seq, insert)
                    }
                }
                2 -> CloningWorkflows.overlapAssembly(CloningMethod.GIBSON, orderedParts, productName.text, circular.isSelected, (arm.value as Number).toInt()) to orderedParts
                3 -> CloningWorkflows.overlapAssembly(CloningMethod.NEBUILDER_HIFI, orderedParts, productName.text, circular.isSelected, (arm.value as Number).toInt()) to orderedParts
                4 -> CloningWorkflows.overlapAssembly(CloningMethod.IN_FUSION, orderedParts, productName.text, circular.isSelected, (arm.value as Number).toInt()) to orderedParts
                5 -> firstInsert().let { insert -> CloningWorkflows.terminalClone(CloningMethod.TA, doc.seq, insert, productName.text) to listOf(doc.seq, insert) }
                6 -> firstInsert().let { insert -> CloningWorkflows.terminalClone(CloningMethod.GC, doc.seq, insert, productName.text) to listOf(doc.seq, insert) }
                7 -> firstInsert().let { insert -> CloningWorkflows.terminalClone(CloningMethod.TOPO_TA, doc.seq, insert, productName.text) to listOf(doc.seq, insert) }
                8 -> firstInsert().let { insert -> CloningWorkflows.terminalClone(CloningMethod.TOPO_DIRECTIONAL, doc.seq, insert, productName.text) to listOf(doc.seq, insert) }
                9 -> firstInsert().let { insert -> CloningWorkflows.terminalClone(CloningMethod.TOPO_BLUNT, doc.seq, insert, productName.text) to listOf(doc.seq, insert) }
                10 -> CloningWorkflows.goldenGate(
                    orderedParts,
                    overhangs.text.split(',').map(String::trim),
                    productName.text,
                    circular.isSelected,
                ) to orderedParts
                else -> {
                    val donor = firstInsert()
                    CloningWorkflows.homologyRecombination(doc.seq, donor, (arm.value as Number).toInt(), name = productName.text) to listOf(doc.seq, donor)
                }
            }
        }.onSuccess { (result, inputs) ->
            lastWorkflow = result
            lastInputs = inputs
            product = result.product
            output.text = buildString {
                append("Product: ${result.product.name}\nLength: ${result.product.length}\nTopology: ${result.product.topology}\n")
                result.diagnostics.forEach { append("${it.severity}: ${it.message}\n") }
                append('\n')
                result.steps.forEachIndexed { index, step -> append("${index + 1}. ${step.title}: ${step.detail}\n") }
                append("\n${result.product.bases.chunked(80).joinToString("\n")}")
                if (mode.selectedIndex == 10) {
                    val overhangList = overhangs.text.split(',').map(String::trim).filter(String::isNotEmpty)
                    if (overhangList.isNotEmpty()) {
                        val fidelity = GoldenGateFidelity.score(overhangList)
                        append("\n\n--- Golden Gate Fidelity Report ---\n")
                        append("Set fidelity: ${"%.4f".format(fidelity.setFidelity * 100)}%\n")
                        append("Weakest overhang: ${fidelity.weakestOverhang ?: "none (all >= 99%)"}\n")
                        fidelity.perOverhangFidelity.forEach { (oh, fi) ->
                            append("  $oh: ${"%.4f".format(fi * 100)}%\n")
                        }
                        if (fidelity.warnings.isNotEmpty()) {
                            append("\nWarnings:\n")
                            fidelity.warnings.forEach { append("  \u26a0 $it\n") }
                        }
                    }
                }
            }
        }.onFailure { lastWorkflow = null; lastInputs = emptyList(); product = null; output.text = it.message ?: "Assembly failed" }
    }

    private fun saveWorkflowReport() {
        val result = lastWorkflow ?: run {
            JOptionPane.showMessageDialog(this, "Run a workflow before exporting its report.", "Assembly report", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        val chooser = JFileChooser().apply { dialogTitle = "Save assembly report" }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val file = chooser.selectedFile
        runCatching {
            val report = Reports.workflowReport(
                result,
                inputs = lastInputs,
                parameters = mapOf(
                    "workflow" to mode.selectedItem.toString(),
                    "circular" to circular.isSelected.toString(),
                    "minimum overlap" to arm.value.toString(),
                ),
            )
            when (file.extension.lowercase()) {
                "json" -> file.writeText(Reports.workflowJson(report))
                "html", "htm" -> file.writeText(Reports.workflowHtml(report))
                "pdf" -> file.writeBytes(Reports.workflowPdf(report))
                else -> file.writeText(Reports.workflowMarkdown(report))
            }
        }.onFailure {
            JOptionPane.showMessageDialog(this, it.message ?: "Unable to save report", "Assembly report", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun saveWorkflowRecipe() {
        val result = lastWorkflow ?: run {
            JOptionPane.showMessageDialog(this, "Run a workflow before saving its recipe.", "Assembly recipe", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        val chooser = JFileChooser().apply { dialogTitle = "Save assembly recipe" }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        runCatching {
            val recipe = Reports.workflowRecipe(result.method.name, result.product, lastInputs, result.parameters)
            chooser.selectedFile.writeText(WorkflowRecipes.encode(recipe))
        }.onFailure {
            JOptionPane.showMessageDialog(this, it.message ?: "Unable to save recipe", "Assembly recipe", JOptionPane.ERROR_MESSAGE)
        }
    }
}
