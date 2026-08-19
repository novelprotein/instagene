package org.instagene.app.gui.analysis

import org.instagene.app.gui.ContextMenus
import org.instagene.core.*
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.DefaultTableModel

internal class NcbiAnalysisPanel(
    private val onOpenSequence: (Seq) -> Unit,
    private val client: NcbiClient,
    pollIntervalMillis: Long,
) : BoundAnalysisPanel() {
    private val pollIntervalMillis = pollIntervalMillis.coerceAtLeast(0L)
    private val term = JTextField(24).apply {
        name = "ncbiSharedQuery"
        toolTipText = "Enter an NCBI search term, nucleotide sequence, or GenBank accession."
    }
    private val querySource = JComboBox(arrayOf("Typed term", "Selected bases", "Whole sequence"))
    private val nucleotideModel = tableModel("Accession", "Title", "Organism", "Length", "Molecule type")
    private val nucleotideTable = JTable(nucleotideModel)
    private val blastModel = tableModel(
        "Accession", "% identity", "Alignment length", "Mismatches", "Gaps", "E-value", "Bit score",
    )
    private val blastTable = JTable(blastModel)
    private val resultTabs = JTabbedPane()
    private val output = output()
    private val queryStatus = JLabel().apply { name = "ncbiQueryStatus" }
    private val searchButton = JButton("Search NCBI")
    private val fetchButton = JButton("Fetch GenBank")
    private val blastButton = JButton("Run BLAST")
    private val nucleotidePopup = JPopupMenu()
    private val fetchNucleotideMenuItem = JMenuItem("Fetch GenBank")
    private val copyNucleotideAccessionMenuItem = JMenuItem("Copy accession")
    private val blastPopup = JPopupMenu()
    private val openBlastHitMenuItem = JMenuItem("Open BLAST Hit")
    private val copyBlastAccessionMenuItem = JMenuItem("Copy accession")
    private var hits = emptyList<NcbiHit>()
    private var blastHits = emptyList<BlastHit>()
    private var searchWorker: SwingWorker<*, *>? = null
    private var fetchWorker: SwingWorker<*, *>? = null
    private var blastWorker: SwingWorker<*, *>? = null
    private var searchGeneration = 0
    private var blastGeneration = 0
    private var searchRunning = false
    private var blastRunning = false
    private var lastQueryState: Pair<Int, String?>? = null

    init {
        searchButton.addActionListener { search() }
        fetchButton.addActionListener { fetch() }
        blastButton.addActionListener { if (blastRunning) cancelBlast() else runBlast() }
        fetchNucleotideMenuItem.toolTipText = "Fetch the selected nucleotide record and open it as a sequence tab."
        fetchNucleotideMenuItem.addActionListener { fetch() }
        copyNucleotideAccessionMenuItem.toolTipText = "Copy the selected nucleotide accession to the clipboard."
        copyNucleotideAccessionMenuItem.addActionListener { copySelectedNucleotideAccession() }
        nucleotidePopup.add(fetchNucleotideMenuItem)
        nucleotidePopup.add(copyNucleotideAccessionMenuItem)
        openBlastHitMenuItem.toolTipText = "Fetch the selected BLAST hit accession and open it as a sequence tab."
        openBlastHitMenuItem.addActionListener { openSelectedBlastHit() }
        copyBlastAccessionMenuItem.toolTipText = "Copy the selected BLAST hit accession to the clipboard."
        copyBlastAccessionMenuItem.addActionListener { copySelectedBlastAccession() }
        querySource.addActionListener {
            queryChanged()
        }
        term.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = queryChanged()
            override fun removeUpdate(e: DocumentEvent) = queryChanged()
            override fun changedUpdate(e: DocumentEvent) = queryChanged()
        })
        fetchButton.toolTipText = "Fetch a typed GenBank accession or the selected nucleotide search result."
        openBlastHitMenuItem.isEnabled = false
        copyBlastAccessionMenuItem.isEnabled = false
        blastPopup.add(openBlastHitMenuItem)
        blastPopup.add(copyBlastAccessionMenuItem)
        nucleotideTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        nucleotideTable.selectionModel.addListSelectionListener {
            updateFetchButton()
        }
        nucleotideTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) fetch()
            }

            override fun mousePressed(e: MouseEvent) {
                maybeShowNucleotidePopup(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                maybeShowNucleotidePopup(e)
            }
        })
        blastTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        blastTable.selectionModel.addListSelectionListener {
            updateOpenBlastHitAction()
        }
        blastTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) openSelectedBlastHit()
            }

            override fun mousePressed(e: MouseEvent) {
                maybeShowBlastPopup(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                maybeShowBlastPopup(e)
            }
        })
        resultTabs.addTab("Nucleotide records", JScrollPane(nucleotideTable))
        resultTabs.addTab("BLAST results", JScrollPane(blastTable))
        add(ncbiControls(), BorderLayout.NORTH)
        add(resultTabs, BorderLayout.CENTER)
        add(JScrollPane(output).apply { preferredSize = java.awt.Dimension(10, 80) }, BorderLayout.SOUTH)
    }

    private fun ncbiControls(): JPanel =
        row(JLabel("Query source"), querySource, term, queryStatus, searchButton, fetchButton, blastButton)

    override fun refreshDocument() {
        val state = currentQueryState()
        if (lastQueryState != null && state != lastQueryState) clearNucleotideResults()
        lastQueryState = state
        refreshQueryControls()
    }

    private fun refreshQueryControls() {
        updateSearchControls()
        updateFetchButton()
        updateBlastButtons()
    }

    private fun queryChanged() {
        clearNucleotideResults()
        lastQueryState = currentQueryState()
        refreshQueryControls()
    }

    private fun clearNucleotideResults() {
        searchGeneration++
        searchWorker?.cancel(true)
        searchWorker = null
        searchRunning = false
        hits = emptyList()
        nucleotideTable.clearSelection()
        nucleotideModel.rowCount = 0
    }

    private fun currentQueryState(): Pair<Int, String?> = querySource.selectedIndex to nucleotideQuery()

    private fun search() {
        val query = nucleotideQuery() ?: run {
            showMissingQueryGuidance()
            return
        }
        searchGeneration++
        val generation = searchGeneration
        searchWorker?.cancel(true)
        searchRunning = true
        updateSearchControls()
        hits = emptyList()
        nucleotideTable.clearSelection()
        nucleotideModel.rowCount = 0
        updateFetchButton()
        resultTabs.selectedIndex = 0
        output.text = "Searching NCBI..."
        searchWorker = object : SwingWorker<NcbiSearchResult, Unit>() {
            override fun doInBackground() = client.searchNucleotide(query)

            override fun done() {
                if (generation != searchGeneration || isCancelled) return
                runCatching { get() }.onSuccess { result ->
                    hits = result.hits
                    nucleotideModel.rowCount = 0
                    hits.forEach {
                        nucleotideModel.addRow(arrayOf<Any?>(it.accession, it.title, it.organism, it.length, it.moleculeType))
                    }
                    resultTabs.selectedIndex = 0
                    if (hits.isNotEmpty() && nucleotideModel.rowCount > 0) nucleotideTable.setRowSelectionInterval(0, 0)
                    updateFetchButton()
                    output.text = "${hits.size} result(s) shown${if (result.totalCount > hits.size) " of ${result.totalCount}" else ""}."
                }.onFailure { output.text = it.message ?: "NCBI search failed" }
                searchRunning = false
                updateSearchControls()
            }
        }.also { it.execute() }
    }

    private fun fetch() {
        if (fetchWorker != null) return
        val typed = term.text.trim()
        val typedAccession = typed.takeIf { querySource.selectedIndex == 0 && NCBI_ACCESSION_TOKEN.matches(it) }
        val accession = typedAccession ?: selectedNucleotideHit()?.accession
        if (accession == null) {
            output.text = when (querySource.selectedIndex) {
                0 -> if (typed.isBlank()) {
                    "Enter a GenBank accession, or run Search NCBI and select a result."
                } else {
                    "'$typed' is not a GenBank accession. Run Search NCBI and select a result."
                }
                1 -> if (!doc.hasSelection) {
                    "Select bases in the Sequence view, run Search NCBI, then select a result to fetch GenBank."
                } else {
                    "Run Search NCBI with the selected query source, then select a result to fetch GenBank."
                }
                else ->
                    "Run Search NCBI with the selected query source, then select a result to fetch GenBank."
            }
            if (querySource.selectedIndex == 0) term.requestFocusInWindow()
            return
        }
        fetchAccession(accession)
    }

    private fun fetchAccession(accession: String) {
        if (fetchWorker != null) {
            output.text = "A GenBank fetch is already running."
            return
        }
        output.text = "Fetching $accession..."
        val worker = object : SwingWorker<Seq, Unit>() {
            override fun doInBackground() = client.fetchGenBank(accession)

            override fun done() {
                if (fetchWorker !== this) return
                fetchWorker = null
                runCatching { get() }
                    .onSuccess {
                        onOpenSequence(it)
                        output.text = "Opened $accession in a new sequence tab."
                    }
                    .onFailure { output.text = it.message ?: "NCBI fetch failed" }
                updateFetchButton()
                updateOpenBlastHitAction()
            }
        }
        fetchWorker = worker
        updateFetchButton()
        worker.execute()
    }

    private fun runBlast() {
        val input = blastInput() ?: run {
            showMissingQueryGuidance()
            return
        }
        blastGeneration++
        val generation = blastGeneration
        blastWorker?.cancel(true)
        blastRunning = true
        updateBlastButtons()
        resultTabs.selectedIndex = 1
        blastHits = emptyList()
        blastModel.rowCount = 0
        blastTable.clearSelection()
        updateOpenBlastHitAction()
        output.text = "Submitting BLASTN to NCBI..."
        blastWorker = object : SwingWorker<BlastSearchResult, String>() {
            override fun doInBackground(): BlastSearchResult {
                val submission = client.submitBlastN(input.seq, input.selection)
                publish("BLAST request ${submission.rid} submitted; waiting for NCBI...")
                var status = client.blastStatus(submission.rid)
                var delayMillis = pollIntervalMillis.coerceAtMost(MAX_BLAST_POLL_INTERVAL_MILLIS)
                while (status.status == BlastStatus.WAITING) {
                    check(!isCancelled) { "BLAST request cancelled" }
                    publish("BLAST request ${submission.rid} is still running...")
                    if (delayMillis > 0) Thread.sleep(delayMillis)
                    delayMillis = (delayMillis * 2).coerceAtMost(MAX_BLAST_POLL_INTERVAL_MILLIS)
                    status = client.blastStatus(submission.rid)
                }
                if (status.status != BlastStatus.READY) error(status.message.ifBlank { "NCBI BLAST failed" })
                return client.fetchBlastResults(submission.rid)
            }

            override fun process(chunks: MutableList<String>) {
                if (chunks.isNotEmpty()) output.text = chunks.last()
            }

            override fun done() {
                if (generation != blastGeneration || isCancelled) return
                runCatching { get() }.onSuccess { result ->
                    blastHits = result.hits
                    blastModel.rowCount = 0
                    blastHits.forEach {
                        val accession = accessionFromBlastSubject(it.subjectId) ?: it.subjectId
                        blastModel.addRow(arrayOf<Any?>(
                            accession, it.percentIdentity, it.alignmentLength, it.mismatches,
                            it.gapOpenings, it.eValue, it.bitScore,
                        ))
                    }
                    if (blastHits.isNotEmpty()) blastTable.setRowSelectionInterval(0, 0)
                    updateOpenBlastHitAction()
                    output.text = if (result.hits.isEmpty()) "BLAST completed with no hits." else "${result.hits.size} BLAST hit(s) shown."
                }.onFailure { output.text = it.message ?: "NCBI BLAST failed" }
                blastRunning = false
                updateBlastButtons()
            }
        }.also { it.execute() }
    }

    private fun cancelBlast() {
        if (!blastRunning) return
        blastGeneration++
        blastWorker?.cancel(true)
        blastRunning = false
        output.text = "BLAST cancelled."
        updateBlastButtons()
    }

    private fun maybeShowNucleotidePopup(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val row = nucleotideTable.rowAtPoint(e.point)
        if (row >= 0) {
            nucleotideTable.selectionModel.setSelectionInterval(row, row)
        } else {
            nucleotideTable.clearSelection()
        }
        updateFetchButton()
        nucleotideTable.componentPopupMenu = nucleotidePopup
        if (nucleotideTable.isShowing) nucleotidePopup.show(nucleotideTable, e.x, e.y)
    }

    private fun maybeShowBlastPopup(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val row = blastTable.rowAtPoint(e.point)
        if (row >= 0) {
            blastTable.selectionModel.setSelectionInterval(row, row)
        } else {
            blastTable.clearSelection()
        }
        updateOpenBlastHitAction()
        blastTable.componentPopupMenu = blastPopup
        if (blastTable.isShowing) blastPopup.show(blastTable, e.x, e.y)
    }

    private fun openSelectedBlastHit() {
        val hit = selectedBlastHit() ?: return
        val accession = accessionFromBlastSubject(hit.subjectId)
        if (accession == null) {
            output.text = "Cannot open BLAST hit '${hit.subjectId}': no GenBank accession was found."
            return
        }
        fetchAccession(accession)
    }

    private fun copySelectedNucleotideAccession() {
        val hit = selectedNucleotideHit() ?: return
        ContextMenus.copyToClipboard(hit.accession)
    }

    private fun copySelectedBlastAccession() {
        val hit = selectedBlastHit() ?: return
        val accession = accessionFromBlastSubject(hit.subjectId) ?: hit.subjectId
        ContextMenus.copyToClipboard(accession)
    }

    private fun selectedNucleotideHit(): NcbiHit? {
        if (hits.isEmpty()) return null
        val selected = nucleotideTable.selectedRow
        val row = if (selected >= 0) nucleotideTable.convertRowIndexToModel(selected) else return null
        return hits.getOrNull(row)
    }

    private fun selectedBlastHit(): BlastHit? {
        if (blastHits.isEmpty()) return null
        val selected = blastTable.selectedRow
        val row = if (selected >= 0) blastTable.convertRowIndexToModel(selected) else return null
        return blastHits.getOrNull(row)
    }

    private fun updateFetchButton() {
        val idle = fetchWorker == null
        fetchButton.isEnabled = idle
        val rowEnabled = idle && selectedNucleotideHit() != null
        fetchNucleotideMenuItem.isEnabled = rowEnabled
        copyNucleotideAccessionMenuItem.isEnabled = rowEnabled
    }

    private fun updateOpenBlastHitAction() {
        openBlastHitMenuItem.isEnabled = selectedBlastHit()?.let { accessionFromBlastSubject(it.subjectId) != null } == true
        copyBlastAccessionMenuItem.isEnabled = selectedBlastHit() != null
    }

    private fun updateSearchControls() {
        val typed = querySource.selectedIndex == 0
        term.isEnabled = typed
        term.isVisible = typed
        queryStatus.isVisible = !typed
        queryStatus.text = when (querySource.selectedIndex) {
            1 -> when {
                doc.seq.kind == SeqKind.PROTEIN -> "Nucleotide sequence required"
                doc.hasSelection -> "${doc.selectionEnd - doc.selectionStart} bases selected"
                else -> "No bases selected"
            }
            2 -> when {
                doc.seq.kind == SeqKind.PROTEIN -> "Nucleotide sequence required"
                doc.seq.bases.isBlank() -> "No sequence available"
                else -> "${doc.seq.length} bases in sequence"
            }
            else -> ""
        }
        searchButton.isEnabled = !searchRunning
    }

    private fun updateBlastButtons() {
        blastButton.text = if (blastRunning) "Cancel BLAST" else "Run BLAST"
        blastButton.isEnabled = blastRunning || querySource.selectedIndex == 1 || blastInput() != null
    }

    private fun showMissingQueryGuidance() {
        output.text = when (querySource.selectedIndex) {
            0 -> "Enter an NCBI search term or nucleotide sequence, then try again."
            1 -> if (doc.seq.kind == SeqKind.PROTEIN) {
                "Selected-bases searches require a nucleotide sequence."
            } else {
                "Select bases in the Sequence view, then try again."
            }
            else -> if (doc.seq.kind == SeqKind.PROTEIN) {
                "Whole-sequence searches require a nucleotide sequence."
            } else {
                "The current sequence is empty."
            }
        }
    }

    private fun nucleotideQuery(): String? = when (querySource.selectedIndex) {
        0 -> term.text.trim()
        1 -> if (doc.seq.kind != SeqKind.PROTEIN && doc.hasSelection) doc.selectedBases else ""
        else -> if (doc.seq.kind != SeqKind.PROTEIN) doc.seq.bases else ""
    }.takeIf { it.isNotBlank() }

    private fun blastInput(): BlastInput? {
        return when (querySource.selectedIndex) {
            0 -> typedBlastSequence()?.let { BlastInput(it, null) } ?: documentBlastInput()
            1 -> if (doc.seq.kind != SeqKind.PROTEIN && doc.hasSelection) {
                BlastInput(doc.seq, doc.selectionStart until doc.selectionEnd)
            } else {
                null
            }
            else -> if (doc.seq.kind != SeqKind.PROTEIN && doc.seq.bases.isNotBlank()) BlastInput(doc.seq, null) else null
        }
    }

    private fun documentBlastInput(): BlastInput? {
        if (doc.seq.kind == SeqKind.PROTEIN || doc.seq.bases.isBlank()) return null
        val selection = if (doc.hasSelection) doc.selectionStart until doc.selectionEnd else null
        return BlastInput(doc.seq, selection)
    }

    private fun typedBlastSequence(): Seq? {
        val bases = Alphabet.clean(term.text).uppercase()
        if (bases.isBlank() || Alphabet.invalidCharacters(bases).isNotEmpty()) return null
        return Seq(name = "typed_query", bases = bases, kind = SeqKind.DNA)
    }

    private fun accessionFromBlastSubject(subject: String): String? {
        val trimmed = subject.trim()
        if (NCBI_ACCESSION_TOKEN.matches(trimmed)) return trimmed
        return trimmed.split('|')
            .map { it.trim() }
            .firstOrNull { NCBI_ACCESSION_TOKEN.matches(it) }
    }

    companion object {
        private const val MAX_BLAST_POLL_INTERVAL_MILLIS = 15_000L
        private val NCBI_ACCESSION_TOKEN = Regex("[A-Za-z]{1,4}_[A-Za-z0-9]+(?:\\.\\d+)?|[A-Za-z]+\\d+(?:\\.\\d+)?")
    }

    private data class BlastInput(val seq: Seq, val selection: IntRange?)

    private fun tableModel(vararg columns: String): DefaultTableModel = object : DefaultTableModel(columns, 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
}
