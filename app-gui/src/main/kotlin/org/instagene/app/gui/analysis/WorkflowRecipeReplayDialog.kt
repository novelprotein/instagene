package org.instagene.app.gui.analysis

import org.instagene.app.gui.row
import org.instagene.core.Reports
import org.instagene.core.Seq
import org.instagene.core.WorkflowReplayAuthorization
import org.instagene.core.WorkflowReplayResult
import org.instagene.core.WorkflowRecipes
import org.instagene.core.WorkflowReplays
import org.instagene.core.io.SeqIO
import java.awt.BorderLayout
import java.awt.Dialog
import java.awt.Window
import java.io.File
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingWorker

/**
 * Desktop front end for local workflow-recipe replay. Inputs are intentionally
 * selected again rather than embedded in a recipe, then checked by CDSEGUID
 * before any construct is accepted. External and online execution are off
 * until the researcher explicitly approves each class of dependency.
 */
internal class WorkflowRecipeReplayDialog(
    owner: Window?,
    private val onOpenSequence: (Seq) -> Unit,
) : JDialog(owner, "Replay workflow recipe", ModalityType.APPLICATION_MODAL) {
    private val recipePath = JTextField(42).apply { isEditable = false }
    private val inputsPath = JTextField(42).apply { isEditable = false }
    private val allowExternal = JCheckBox("I approve external-tool execution for this replay")
    private val allowOnline = JCheckBox("I approve recorded online-source access for this replay")
    private val output = JTextArea(15, 88).apply {
        isEditable = false
        font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12)
        border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
    }
    private val run = JButton("Replay with identity checks")
    private val openProduct = JButton("Open verified product").apply { isEnabled = false }
    private var recipeFile: File? = null
    private var inputFiles: List<File> = emptyList()
    private var completed: WorkflowReplayResult? = null

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        val chooseRecipe = JButton("Choose recipe…").apply { addActionListener { chooseRecipe() } }
        val chooseInputs = JButton("Choose inputs…").apply { addActionListener { chooseInputs() } }
        run.toolTipText = "Check every selected input by sequence identity before replaying the typed operation."
        run.addActionListener { replay() }
        openProduct.toolTipText = "Open the product only after its recorded output identity matches."
        openProduct.addActionListener { completed?.product?.let(onOpenSequence) }

        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(row(chooseRecipe, recipePath))
            add(row(chooseInputs, inputsPath))
            add(allowExternal)
            add(allowOnline)
            add(row(run, openProduct, JLabel("Recipes never embed input sequence data.")))
        }, BorderLayout.NORTH)
        add(JScrollPane(output), BorderLayout.CENTER)
        pack()
        setLocationRelativeTo(owner)
    }

    private fun chooseRecipe() {
        val chooser = JFileChooser().apply { dialogTitle = "Choose workflow recipe" }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        recipeFile = chooser.selectedFile
        recipePath.text = chooser.selectedFile.absolutePath
        resetCompleted()
    }

    private fun chooseInputs() {
        val chooser = JFileChooser().apply {
            dialogTitle = "Choose identity-matched recipe inputs"
            isMultiSelectionEnabled = true
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        inputFiles = chooser.selectedFiles.toList()
        inputsPath.text = inputFiles.joinToString("; ") { it.name }
        resetCompleted()
    }

    private fun replay() {
        val selectedRecipe = recipeFile ?: return showError("Choose a workflow recipe first.")
        if (inputFiles.isEmpty()) return showError("Choose the input sequence files required by the recipe.")
        run.isEnabled = false
        openProduct.isEnabled = false
        output.text = "Reading recipe and checking selected input identities…"
        object : SwingWorker<WorkflowReplayResult, Unit>() {
            override fun doInBackground(): WorkflowReplayResult {
                val recipe = WorkflowRecipes.decode(selectedRecipe.readText())
                val inputs = inputFiles.map(SeqIO::read)
                return WorkflowReplays.replay(
                    recipe,
                    inputs,
                    WorkflowReplayAuthorization(
                        allowExternalTools = allowExternal.isSelected,
                        allowOnlineSources = allowOnline.isSelected,
                    ),
                )
            }

            override fun done() {
                run.isEnabled = true
                completed = runCatching(::get).getOrElse { error ->
                    output.text = "Replay failed before a product was created:\n${error.message ?: error.javaClass.simpleName}"
                    return
                }
                val result = completed ?: return
                output.text = buildString {
                    appendLine("Status: ${result.status}")
                    appendLine("Operation: ${result.operation.operationType}")
                    result.product?.let { product ->
                        appendLine("Product: ${product.name} (${product.length} bp)")
                        appendLine("Identity: ${Reports.workflowReplayReport(result).productIdentity}")
                    }
                    appendLine()
                    result.messages.forEach(::appendLine)
                }
                openProduct.isEnabled = result.succeeded
            }
        }.execute()
    }

    private fun resetCompleted() {
        completed = null
        openProduct.isEnabled = false
    }

    private fun showError(message: String) {
        JOptionPane.showMessageDialog(this, message, "Replay workflow recipe", JOptionPane.ERROR_MESSAGE)
    }
}
