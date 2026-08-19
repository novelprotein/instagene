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
        add(row(JLabel("Workflow"), mode, choose, parts), BorderLayout.NORTH)
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(row(JLabel("Enzymes"), enzymes, JLabel("Overhangs"), overhangs, JLabel("Homology arm"), arm))
            add(row(JLabel("Gateway left,right"), gatewaySites, JLabel("Name"), productName, circular, run, open, save))
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
                0 -> CloningWorkflows.restriction(doc.seq, firstInsert(), Enzymes.parseList(enzymes.text), productName.text)
                1 -> gatewaySites.text.split(',').map(String::trim).let { sites ->
                    require(sites.size == 2) { "Enter left and right Gateway recombination sites" }
                    CloningWorkflows.gateway(doc.seq, firstInsert(), sites[0], sites[1], productName.text)
                }
                2 -> CloningWorkflows.overlapAssembly(CloningMethod.GIBSON, orderedParts, productName.text, circular.isSelected, (arm.value as Number).toInt())
                3 -> CloningWorkflows.overlapAssembly(CloningMethod.NEBUILDER_HIFI, orderedParts, productName.text, circular.isSelected, (arm.value as Number).toInt())
                4 -> CloningWorkflows.overlapAssembly(CloningMethod.IN_FUSION, orderedParts, productName.text, circular.isSelected, (arm.value as Number).toInt())
                5 -> CloningWorkflows.terminalClone(CloningMethod.TA, doc.seq, firstInsert(), productName.text)
                6 -> CloningWorkflows.terminalClone(CloningMethod.GC, doc.seq, firstInsert(), productName.text)
                7 -> CloningWorkflows.terminalClone(CloningMethod.TOPO_TA, doc.seq, firstInsert(), productName.text)
                8 -> CloningWorkflows.terminalClone(CloningMethod.TOPO_DIRECTIONAL, doc.seq, firstInsert(), productName.text)
                9 -> CloningWorkflows.terminalClone(CloningMethod.TOPO_BLUNT, doc.seq, firstInsert(), productName.text)
                10 -> CloningWorkflows.goldenGate(
                    orderedParts,
                    overhangs.text.split(',').map(String::trim),
                    productName.text,
                    circular.isSelected,
                )
                else -> {
                    val donor = firstInsert()
                    val candidates = Recombination.candidates(doc.seq, donor, (arm.value as Number).toInt())
                    require(candidates.isNotEmpty()) { "No matching homology-arm candidate found" }
                    val raw = Recombination.recombine(doc.seq, donor, candidates.first(), productName.text).product
                    MolecularWorkflowResult(
                        CloningMethod.GATEWAY,
                        raw.withProcedure(ProcedureRecord("HOMOLOGY_RECOMBINATION", "Recombined ${donor.name} into ${doc.seq.name}", listOf(doc.seq.name, donor.name), timestamp = System.currentTimeMillis())),
                        listOf(ProtocolStep("Homology recombination", "Used ${(arm.value as Number).toInt()} bp arms")),
                    )
                }
            }
        }.onSuccess { result ->
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
        }.onFailure { product = null; output.text = it.message ?: "Assembly failed" }
    }
}
