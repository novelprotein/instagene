package org.instagene.app.gui.tool

import org.instagene.app.gui.TableLabels
import org.instagene.app.gui.ContextMenus
import org.instagene.app.gui.dialog.AnalysisDialogs
import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.prefs.Prefs
import org.instagene.app.gui.installRowContextMenu
import org.instagene.app.gui.enzyme.EnzymeElementDialog
import org.instagene.app.gui.enzyme.enzymeDescriptionFor
import org.instagene.app.gui.enzyme.enzymePool
import org.instagene.core.CutSite
import org.instagene.core.Digest
import org.instagene.core.Enzyme
import org.instagene.core.Enzymes
import org.instagene.core.Fragment
import org.instagene.core.Seq
import org.instagene.core.SeqKind
import org.instagene.app.gui.prefs.SavedContext
import org.instagene.app.gui.prefs.SavedItem
import org.instagene.app.gui.prefs.SavedKind
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPopupMenu
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel

/**
 * Restriction mapping: tick enzymes to map them onto the sequence, then read
 * off the resulting fragments. The enzyme list comes from the preferences-backed
 * catalog of built-in and custom enzymes, filtered to the active working set.
 * Panel state is persisted through [prefs].
 */
class DigestPanel(
    initial: SeqDocument,
    private val onExtractFragment: (Seq) -> Unit,
    private val onReveal: (Int, Int) -> Unit,
    private val prefs: Prefs = Prefs(),
) : JPanel(BorderLayout(0, 6)) {

    /** The displayed document, rebound when the active tab changes. */
    private var doc = initial
    private var docListener: SeqDocument.Listener? = null

    private val checked = LinkedHashSet<Enzyme>()
    private val enzymeModel = EnzymeTableModel()
    private val digestModel = DigestTableModel()
    private val enzymeTable = JTable(enzymeModel)
    private val digestTable = JTable(digestModel)
    private val filterField = JTextField()
    private val cuttersOnly = JCheckBox("Only enzymes that cut", true)
    private val uniqueOnly = JCheckBox("Only unique cutters", false)
    private val editElementButton = JButton("Edit Element...")
    private val summary = JLabel(" ")
    private val extractButton = JButton("Open fragment as new sequence")
    private val saveFragmentButton = JButton("Save fragment to library")
    private val exportCsvButton = JButton("Export CSV")

    private var visibleEnzymes: List<Enzyme> = emptyList()
    private var fragments: List<Fragment> = emptyList()
    private var matches: List<CutSite> = emptyList()
    private var mergedRows: List<MergedDigestRow> = emptyList()
    private var restoringEnzymeSelection = false
    private var restoringFragmentSelection = false

    /** The full catalog (built-in + custom), used for the cut-count scan and lookups. */
    private var pool: List<Enzyme> = prefs.value.enzymePool()

    /** The active subset of the enzyme catalog. */
    private var enabledPool: List<Enzyme> = Enzymes.enzymesFor(pool, prefs.value.enabledEnzymes)

    /** Per-enzyme cut counts for the current sequence; null until the asynchronous scan completes. */
    private var countsCache: Map<Enzyme, Int>? = null

    /** Per-enzyme cut sites for the current sequence, populated during the background scan. */
    private val cutSitesCache = ConcurrentHashMap<Enzyme, List<CutSite>>()

    /** Distinct sequence-specific overhangs observed for each enzyme. */
    private var overhangCache: Map<Enzyme, List<String>> = emptyMap()

    /** True while the cached counts do not match [SeqDocument.seq] (a recompute is in flight). */
    private var countsStale = false

    /** Bumped on every recompute so stale background results are discarded. */
    private var countsVersion = 0

    /** Bumped on every selection change so stale background digests are discarded. */
    private var digestVersion = 0

    /** Sequences at least this long have their site scan + digest computed off the EDT. */
    private val asyncDigestThreshold = 1_000_000

    /** Runs the per-enzyme cut-count scans in parallel; owned by this panel. */
    private val countPool: ExecutorService =
        Executors.newFixedThreadPool(countThreads) { r -> Thread(r).apply { isDaemon = true } }

    companion object {
        // Multiple panels can coexist in separate windows and tests. Letting each
        // one use every CPU would exhaust threads on high-core workstations, while
        // four workers are enough to keep the independent enzyme scans responsive.
        private val countThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        private const val SEQUENCE_DEBOUNCE_MS = 150
        private const val FILTER_DEBOUNCE_MS = 100
    }

    /** Debounces rapid sequence changes to avoid redundant full-catalog scans. */
    private val sequenceDebounceTimer = Timer(SEQUENCE_DEBOUNCE_MS) { debouncedRefresh() }.apply {
        isCoalesce = true
    }

    /** Debounces rapid filter keystrokes to avoid redundant table rebuilds. */
    private val filterDebounceTimer = Timer(FILTER_DEBOUNCE_MS) { debouncedFilterRefresh() }.apply {
        isCoalesce = true
    }

    /** Releases the background cut-count workers owned by this panel. */
    fun dispose() {
        sequenceDebounceTimer.stop()
        filterDebounceTimer.stop()
        countPool.shutdownNow()
    }

    /** Exposed for lifecycle tests. */
    fun isDisposed(): Boolean = countPool.isShutdown

    /** Called by the sequence-change debounce timer; runs the full refresh after a quiet period. */
    private fun debouncedRefresh() {
        if (countPool.isShutdown) return
        refresh()
    }

    /** Called by the filter debounce timer; rebuilds the enzyme table without re-scanning. */
    private fun debouncedFilterRefresh() {
        if (countPool.isShutdown) return
        rebuildEnzymeTable()
    }

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        enzymeTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        enzymeTable.rowHeight = 20
        enzymeTable.columnModel.getColumn(0).apply {
            minWidth = 44
            maxWidth = 44
            preferredWidth = 44
        }
        enzymeTable.columnModel.getColumn(3).maxWidth = 50
        enzymeTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting && !restoringEnzymeSelection) {
                showMatchesForSelectedEnzyme()
                revealFirstSiteOfSelectedEnzyme()
                refreshEditElementActionState()
            }
        }
        enzymeTable.installRowContextMenu { row -> enzymePopup(row) }

        digestTable.rowHeight = 20
        digestTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        digestTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                if (!restoringFragmentSelection) revealSelectedFragment()
                updateFragmentActionState()
            }
        }
        digestTable.installRowContextMenu { row -> digestPopup(row) }

        add(buildTop(), BorderLayout.NORTH)

        // Enzyme catalog on top and the merged digest/match table beneath it.
        val split = JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            JScrollPane(enzymeTable),
            JPanel(BorderLayout(0, 4)).apply {
                add(
                    JLabel("Digest fragments and cut sites").apply { border = BorderFactory.createEmptyBorder(4, 2, 2, 2) },
                    BorderLayout.NORTH
                )
                add(JScrollPane(digestTable), BorderLayout.CENTER)
                add(buildFragmentButtons(), BorderLayout.SOUTH)
            },
        ).apply {
            resizeWeight = 0.62
            border = null
        }
        add(split, BorderLayout.CENTER)
        add(summary, BorderLayout.SOUTH)

        // Restore the persisted panel state before wiring the change listeners,
        // so the initial values do not get re-recorded as edits.
        filterField.text = prefs.value.digestFilter
        cuttersOnly.isSelected = prefs.value.digestCuttersOnly
        uniqueOnly.isSelected = prefs.value.digestUniqueOnly
        // Restore the ticked set by name. Empty means "nothing ticked" (unlike
        // enzymesFor, whose empty set means the whole pool); selecting everything
        // by default would map the full pool onto every loaded sequence and force
        // a whole-genome cut-site scan on the EDT during load.
        val savedNames = prefs.value.selectedEnzymes.mapTo(HashSet()) { it.lowercase() }
        checked += enabledPool.filter { it.name.lowercase() in savedNames }

        filterField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) {
                filterDebounceTimer.restart()
                prefs.update { it.copy(digestFilter = filterField.text) }
            }

            override fun removeUpdate(e: DocumentEvent) {
                filterDebounceTimer.restart()
                prefs.update { it.copy(digestFilter = filterField.text) }
            }

            override fun changedUpdate(e: DocumentEvent) {
                filterDebounceTimer.restart()
                prefs.update { it.copy(digestFilter = filterField.text) }
            }
        })
        cuttersOnly.addActionListener {
            refresh()
            prefs.update { it.copy(digestCuttersOnly = cuttersOnly.isSelected) }
        }
        uniqueOnly.addActionListener {
            refresh()
            prefs.update { it.copy(digestUniqueOnly = uniqueOnly.isSelected) }
        }

        prefs.addListener { onPrefsChanged() }

        docListener = SeqDocument.Listener { _, reason ->
            if (reason == SeqDocument.Reason.SEQUENCE) {
                digestVersion++
                cutSitesCache.clear()
                sequenceDebounceTimer.restart()
            }
        }
        doc.addListener(docListener!!)
        refresh()
    }

    /**
     * Binds this panel to another document. The selected enzyme set is reconstructed
     * from the document's mapped enzymes, which are per-document state and therefore
     * survive a tab switch.
     */
    fun bindDocument(newDoc: SeqDocument) {
        val switched = newDoc !== doc
        if (switched) {
            docListener?.let { doc.removeListener(it) }
            doc = newDoc
            if (docListener != null) doc.addListener(docListener!!)
        }
        if (docListener == null) {
            docListener = SeqDocument.Listener { _, reason ->
                if (reason == SeqDocument.Reason.SEQUENCE) {
                    digestVersion++
                    cutSitesCache.clear()
                    sequenceDebounceTimer.restart()
                }
            }
            doc.addListener(docListener!!)
        }
        if (switched) {
            countsVersion++ // invalidate any in-flight scan against the old sequence
            digestVersion++
            countsCache = null
            cutSitesCache.clear()
            countsStale = false
            val mapped = newDoc.mappedEnzymes.mapTo(HashSet()) { it.name.lowercase() }
            checked.clear()
            checked += enabledPool.filter { it.name.lowercase() in mapped }
        }
        refresh()
    }

    /** Reacts to a changed catalog or working set (e.g. the Enzyme Manager dialog). */
    private fun onPrefsChanged() {
        val nextPool = prefs.value.enzymePool()
        val nextEnabled = Enzymes.enzymesFor(nextPool, prefs.value.enabledEnzymes)
        if (nextPool == pool && nextEnabled == enabledPool) {
            rebuildEnzymeTable()
            return
        }
        pool = nextPool
        enabledPool = nextEnabled
        val selectedNames = prefs.value.selectedEnzymes.mapTo(HashSet()) { it.lowercase() }
        checked.clear()
        checked += enabledPool.filter { it.name.lowercase() in selectedNames }
        countsVersion++ // invalidate any in-flight scan against the old pool
        countsCache = null
        cutSitesCache.clear()
        countsStale = false
        refresh()
    }

    private fun buildTop(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(JPanel(BorderLayout(6, 0)).apply {
            add(JLabel("Filter"), BorderLayout.WEST)
            add(filterField, BorderLayout.CENTER)
            maximumSize = Dimension(Int.MAX_VALUE, 28)
        })
        add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(cuttersOnly)
            add(uniqueOnly)
            add(JButton("Diagnostic sites").apply {
                addActionListener { AnalysisDialogs.showDiagnostic(null, doc, checked.toList()) }
            })
            add(editElementButton.apply {
                addActionListener { editEnzymeElement(enzymeTable.selectedRow) }
            })
            add(JButton("Clear").apply {
                addActionListener {
                    checked.clear()
                    applySelection()
                }
            })
        })
    }

    private fun buildFragmentButtons(): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
        add(extractButton.apply {
            addActionListener { openSelectedFragment() }
        })
        add(saveFragmentButton.apply {
            addActionListener { saveSelectedFragment() }
        })
        add(exportCsvButton.apply {
            addActionListener { exportDigestCsv() }
        })
        add(Box.createHorizontalStrut(4))
    }

    /**
     * Recomputes cut counts for the current sequence and repopulates the tables.
     *
     * The counts scan can be expensive for large sequences (every enzyme in the
     * pool over the whole genome), so it runs on a background thread and the
     * table is repopulated only after the result is available. The EDT never waits for it.
     */
    fun refresh() {
        val seq = doc.seq
        val dnaOnly = seq.kind == SeqKind.DNA
        setInteractive(dnaOnly)
        if (!dnaOnly) {
            if (checked.isNotEmpty()) {
                checked.clear()
                doc.setMappedEnzymes(emptyList())
            }
            countsVersion++ // invalidate any in-flight scan
            countsCache = null
            cutSitesCache.clear()
            overhangCache = emptyMap()
            countsStale = false
            visibleEnzymes = emptyList()
            fragments = emptyList()
            matches = emptyList()
            enzymeModel.fireTableDataChanged()
            digestModel.fireTableDataChanged()
            restoreFragmentSelection(null)
            mergedRows = emptyList()
            summary.text = "Restriction digestion applies to double-stranded DNA" +
                    (if (seq.kind == SeqKind.PROTEIN) " (this is a protein sequence)." else ".")
            return
        }
        countsStale = true
        overhangCache = emptyMap()
        cutSitesCache.clear()
        rebuildEnzymeTable()
        scheduleCutCounts(seq)
        applySelection()
    }

    /** Repopulates the enzyme rows from the cached counts (possibly stale).
     * Rows are ordered so the enzymes that cut the sequence come first, by
     * number of cuts descending, so the relevant enzymes are always identified
     * at the top of the table. */
    private fun rebuildEnzymeTable() {
        val selectedEnzyme = visibleEnzymes.getOrNull(enzymeTable.selectedRow)
        val selectedMatch = mergedRows.getOrNull(digestTable.selectedRow)?.site
        val counts = countsCache.orEmpty()
        val needle = filterField.text.trim().lowercase()
        visibleEnzymes = enabledPool.filter { enzyme ->
            val n = counts[enzyme] ?: 0
            (needle.isEmpty() || enzyme.name.lowercase().contains(needle) ||
                    enzyme.site.lowercase().contains(needle)) &&
                    (!cuttersOnly.isSelected || n > 0) &&
                    (!uniqueOnly.isSelected || n == 1)
        }.sortedWith(
            compareByDescending<Enzyme> { counts[it] ?: 0 }
                .thenBy { it.name.lowercase() }
        )
        enzymeModel.counts = counts
        enzymeModel.overhangs = overhangCache
        enzymeModel.fireTableDataChanged()
        restoreEnzymeSelection(selectedEnzyme)
        showMatchesForSelectedEnzyme(selectedMatch?.takeIf { it.enzyme == selectedEnzyme })
        refreshEditElementActionState()
    }

    private fun enzymePopup(row: Int?): JPopupMenu = JPopupMenu().apply {
        val enzyme = row?.let { visibleEnzymes.getOrNull(it) }
        val hasEnzyme = enzyme != null && enzymeTable.isEnabled
        val checkedLabel = if (enzyme != null && enzyme in checked) "Uncheck Enzyme" else "Check Enzyme"
        add(ContextMenus.item(
            checkedLabel,
            "Toggle whether this enzyme is included in the active digest.",
            hasEnzyme,
        ) { toggleEnzyme(row ?: -1) })
        add(ContextMenus.item(
            "Edit Element…",
            "Edit this enzyme's recognition site, cuts, enabled state, and description.",
            hasEnzyme,
        ) { editEnzymeElement(row ?: -1) })
        add(ContextMenus.item(
            "Reveal First Cut Site",
            "Select the first cut site for this enzyme in the sequence viewer.",
            hasEnzyme && (enzymeModel.counts[enzyme] ?: 0) > 0,
        ) { revealFirstSiteOfEnzyme(row ?: -1) })
        addSeparator()
        add(ContextMenus.item(
            "Diagnostic sites",
            "Open diagnostic site analysis for the currently checked enzymes.",
            checked.isNotEmpty(),
        ) { AnalysisDialogs.showDiagnostic(null, doc, checked.toList()) })
        add(ContextMenus.item(
            "Clear",
            "Uncheck all enzymes and clear the current digest.",
            checked.isNotEmpty(),
        ) {
            checked.clear()
            applySelection()
        })
    }

    private fun digestPopup(row: Int?): JPopupMenu = JPopupMenu().apply {
        val merged = row?.let { mergedRows.getOrNull(it) }
        val fragmentIndex = merged?.fragment?.let { fragments.indexOf(it) } ?: -1
        val hasFragment = digestTable.isEnabled && fragmentIndex >= 0
        val hasCutSite = digestTable.isEnabled && merged?.site != null
        add(ContextMenus.item(
            "Reveal Fragment",
            "Select this fragment's bases in the sequence viewer.",
            hasFragment,
        ) { revealFragment(fragmentIndex) })
        add(ContextMenus.item(
            "Reveal Cut Site",
            "Select this row's recognition sequence in the sequence viewer.",
            hasCutSite,
        ) { revealCutSite(merged?.site) })
        addSeparator()
        add(ContextMenus.item(
            "Open fragment as new sequence",
            "Open this digest fragment as a standalone sequence tab.",
            hasFragment,
        ) { extractFragment(fragmentIndex) })
        add(ContextMenus.item(
            "Save fragment to library",
            "Save this digest fragment and its source context to the reusable library.",
            hasFragment,
        ) { saveFragment(fragmentIndex) })
    }

    /** Restores the selected enzyme after a table refresh without revealing the sequence again. */
    private fun restoreEnzymeSelection(selectedEnzyme: Enzyme?) {
        val row = selectedEnzyme?.let { selected -> visibleEnzymes.indexOfFirst { it == selected } } ?: -1
        restoringEnzymeSelection = true
        try {
            if (row >= 0) enzymeTable.selectionModel.setSelectionInterval(row, row)
            else enzymeTable.clearSelection()
        } finally {
            restoringEnzymeSelection = false
        }
    }

    /**
     * Lists every individual match of the enzyme selected in the enzyme table,
     * or clears the list when nothing is selected. Uses the background-computed
     * cutSitesCache for small sequences; falls back to on-demand computation
     * for large sequences.
     */
    private fun showMatchesForSelectedEnzyme(matchToRestore: CutSite? = null) {
        val row = enzymeTable.selectedRow
        val enzyme = visibleEnzymes.getOrNull(row)
        if (enzyme == null || doc.seq.kind != SeqKind.DNA) {
            matches = emptyList()
            rebuildMergedRows()
            return
        }
        matches = cutSitesCache[enzyme]
            ?: doc.cutSites.filter { it.enzyme == enzyme }.takeIf { it.isNotEmpty() }
            ?: Digest.cutSites(doc.seq, enzyme)
        rebuildMergedRows()
        restoreMergedSelection(matchToRestore)
    }

    private fun rebuildMergedRows() {
        val previousRow = mergedRows.getOrNull(digestTable.selectedRow)
        val previewFragments = if (matches.isNotEmpty() && matches.none { site -> fragments.any { it.start == site.topCut } }) {
            Digest.digest(doc.seq, matches.map { it.enzyme }.distinct())
        } else {
            fragments
        }
        val rowsByStart = previewFragments.groupBy { it.start }
        val matched = matches.mapNotNull { site ->
            rowsByStart[site.topCut]?.firstOrNull()?.let { MergedDigestRow(it, site) }
        }
        val matchedFragments = matched.mapTo(HashSet()) { it.fragment }
        val fragmentOnly = previewFragments.filterNot { it in matchedFragments }.map { MergedDigestRow(it, null) }
        mergedRows = (matched + fragmentOnly).sortedWith(compareBy<MergedDigestRow> { it.fragment.start }.thenBy { it.site == null })
        digestModel.fireTableDataChanged()
        val selectedRow = when {
            previousRow?.site != null -> mergedRows.indexOfFirst { it.site == previousRow.site }
            previousRow != null -> mergedRows.indexOfFirst { it.fragment == previousRow.fragment }
            else -> -1
        }.takeIf { it >= 0 } ?: mergedRows.indices.firstOrNull() ?: -1
        restoringFragmentSelection = true
        try {
            if (selectedRow >= 0) digestTable.selectionModel.setSelectionInterval(selectedRow, selectedRow)
            else digestTable.clearSelection()
        } finally {
            restoringFragmentSelection = false
        }
    }

    /** Restores a particular cut site after the merged table is rebuilt. */
    private fun restoreMergedSelection(matchToRestore: CutSite?) {
        val row = matchToRestore?.let { saved -> mergedRows.indexOfFirst { it.site == saved } } ?: -1
        restoringFragmentSelection = true
        try {
            if (row >= 0) digestTable.selectionModel.setSelectionInterval(row, row)
            else digestTable.clearSelection()
        } finally {
            restoringFragmentSelection = false
        }
    }

    /** Reveals the match at [row] in the sequence view, handling origin-wrapping sites. */
    fun revealMatch(row: Int) {
        val site = matches.getOrNull(row) ?: return
        val wraps = doc.seq.isCircular && site.recognitionEnd > doc.seq.length
        if (wraps) onReveal(0, doc.seq.length) else onReveal(site.recognitionStart, site.recognitionEnd)
    }

    /** Scans [seq] on background threads; stale results for an older sequence are dropped. */
    private fun scheduleCutCounts(seq: Seq) {
        val version = ++countsVersion
        val enzymes = pool.toList()
        if (enzymes.isEmpty()) {
            SwingUtilities.invokeLater {
                if (version != countsVersion) return@invokeLater
                countsCache = emptyMap()
                overhangCache = emptyMap()
                countsStale = false
                rebuildEnzymeTable()
            }
            return
        }
        // The per-enzyme scans are independent, so they run on a shared pool and
        // the partial maps are merged on the event thread after every chunk completes.
        val perTask = (enzymes.size + countThreads - 1) / countThreads
        val partial = ConcurrentHashMap<Enzyme, Int>(enzymes.size)
        val partialOverhangs = ConcurrentHashMap<Enzyme, List<String>>(enzymes.size)
        val pending = AtomicInteger(enzymes.chunked(perTask).size)
        for (chunk in enzymes.chunked(perTask)) {
            countPool.submit {
                for (enzyme in chunk) {
                    val count = Digest.countSites(seq, enzyme)
                    partial[enzyme] = count
                    // For manageable sequences, also cache cut sites so the
                    // enzyme-selection handler can serve from cache instead of
                    // rescanning on the EDT.
                    if (seq.length < asyncDigestThreshold && count > 0) {
                        val sites = Digest.cutSites(seq, enzyme)
                        cutSitesCache[enzyme] = sites
                        partialOverhangs[enzyme] = sites
                            .map { Digest.stickyEnd(seq, it).overhang }
                            .filter { it.isNotBlank() }
                            .distinct()
                    }
                }
                if (pending.decrementAndGet() == 0) {
                    SwingUtilities.invokeLater {
                        if (version != countsVersion) return@invokeLater
                        countsCache = partial
                        overhangCache = partialOverhangs
                        countsStale = false
                        rebuildEnzymeTable()
                    }
                }
            }
        }
    }

    private fun setInteractive(enabled: Boolean) {
        filterField.isEnabled = enabled
        cuttersOnly.isEnabled = enabled
        uniqueOnly.isEnabled = enabled
        enzymeTable.isEnabled = enabled
        refreshEditElementActionState()
        digestTable.isEnabled = enabled
        updateFragmentActionState()
    }

    /** Exposed for tests: whether digestion is available for the current sample. */
    fun isDigestEnabled(): Boolean = enzymeTable.isEnabled

    /** Exposed for tests: the cut counts for the current sequence, or null while stale/unknown. */
    fun computedCutCounts(): Map<Enzyme, Int>? = if (countsStale) null else countsCache

    /** Exposed for tests: the enzymes currently ticked in the table, in order. */
    fun selectedEnzymes(): List<Enzyme> = checked.toList()

    /** Exposed for tests: the enzymes displayed in the table, in row order (cutters first). */
    fun displayedEnzymes(): List<Enzyme> = visibleEnzymes.toList()

    /** Exposed for tests: selects the row for [enzyme] in the table (if visible), showing its matches. */
    fun selectEnzymeInTable(enzyme: Enzyme) {
        val row = visibleEnzymes.indexOfFirst { it.name.equals(enzyme.name, ignoreCase = true) }
        if (row >= 0) enzymeTable.selectionModel.setSelectionInterval(row, row)
    }

    /** Exposed for tests: the enzyme currently selected in the displayed table. */
    fun selectedEnzymeInTable(): Enzyme? = visibleEnzymes.getOrNull(enzymeTable.selectedRow)

    /** Exposed for tests: the individual matches listed for the selected enzyme, in order. */
    fun displayedMatches(): List<CutSite> = matches.toList()

    /** Exposed for tests: the individual cut site selected in the matches table. */
    fun selectedMatchInTable(): CutSite? = mergedRows.getOrNull(digestTable.selectedRow)?.site

    /** Exposed for tests: selects a displayed individual cut site. */
    fun selectMatchInTable(site: CutSite) {
        val row = mergedRows.indexOfFirst { it.site == site }
        if (row >= 0) digestTable.selectionModel.setSelectionInterval(row, row)
    }

    /** Exposed for tests: the fragments displayed for the active digest. */
    fun displayedFragments(): List<Fragment> = fragments.toList()

    /** Exposed for tests: the fragment currently selected in the table. */
    fun selectedFragmentInTable(): Fragment? = mergedRows.getOrNull(digestTable.selectedRow)?.fragment

    /** Exposed for tests: selects [fragment] when it is part of the active digest. */
    fun selectFragmentInTable(fragment: Fragment) {
        val row = mergedRows.indexOfFirst { it.fragment == fragment }
        if (row >= 0) digestTable.selectionModel.setSelectionInterval(row, row)
    }

    /** Exposed for tests: whether the Open and Save actions have a valid target. */
    fun areFragmentActionsEnabled(): Boolean = extractButton.isEnabled && saveFragmentButton.isEnabled

    /** Exposed for tests: the inline digest or fragment-action status. */
    fun summaryText(): String = summary.text

    /** Exposed for tests and the GUI: the saved description for an enzyme row. */
    fun enzymeDescription(row: Int): String = visibleEnzymes.getOrNull(row)?.let(::descriptionFor).orEmpty()

    /** Saves a user-authored description for the displayed enzyme at [row]. */
    fun updateEnzymeDescription(row: Int, description: String): Boolean {
        val enzyme = visibleEnzymes.getOrNull(row) ?: return false
        val key = enzyme.name.lowercase()
        prefs.update { current ->
            val next = current.enzymeDescriptions.toMutableMap()
            if (description.isBlank()) next.remove(key) else next[key] = description
            current.copy(enzymeDescriptions = next)
        }
        return true
    }

    /** Maps [enzymes] through the panel, keeping tables and the document in sync. */
    fun selectEnzymes(enzymes: List<Enzyme>) {
        if (doc.seq.kind != SeqKind.DNA) return
        checked.clear()
        checked += enzymes.filter { it in enabledPool }
        applySelection()
    }

    private fun descriptionFor(enzyme: Enzyme): String = prefs.value.enzymeDescriptionFor(enzyme)

    private fun refreshEditElementActionState() {
        editElementButton.isEnabled = enzymeTable.isEnabled && enzymeTable.selectedRow in visibleEnzymes.indices
    }

    private fun toggleEnzyme(row: Int) {
        val enzyme = visibleEnzymes.getOrNull(row) ?: return
        if (enzyme in checked) checked -= enzyme else checked += enzyme
        applySelection()
    }

    /** Opens the visible GUI editor for every editable field of the selected enzyme. */
    private fun editEnzymeElement(row: Int) {
        val enzyme = visibleEnzymes.getOrNull(row) ?: return
        EnzymeElementDialog(prefs, enzyme).isVisible = true
    }

    /** Hands the fragment at [row] to [onExtractFragment] as a standalone sequence. */
    fun extractFragment(row: Int) {
        if (row !in fragments.indices) return
        val f = fragments[row]
        onExtractFragment(f.toSeq("${doc.seq.name}_frag${row + 1}"))
    }

    /** Opens the fragment selected in the table as a standalone sequence. */
    fun openSelectedFragment() {
        val row = digestTable.selectedRow
        if (row >= 0) extractFragment(mergedRows.getOrNull(row)?.fragment?.let { fragments.indexOf(it) } ?: -1)
    }

    /** Stores the fragment at [row] in the library with its source context. */
    fun saveFragment(row: Int) {
        if (row !in fragments.indices) return
        val f = fragments[row]
        val item = SavedItem(
            kind = SavedKind.FRAGMENT,
            name = "${doc.seq.name}_${f.start + 1}-${f.end}",
            bases = f.bases,
            context = SavedContext(
                sourceName = doc.seq.name,
                start = f.start,
                end = f.end,
                enzymes = checked.map { it.name },
            ),
            sequenceKind = doc.seq.kind,
        )
        prefs.update { it.copy(library = it.library + item) }
        summary.text = "Saved ${item.name} to Library."
    }

    /** Saves the fragment selected in the table to the Library. */
    fun saveSelectedFragment() {
        val row = digestTable.selectedRow
        if (row >= 0) saveFragment(mergedRows.getOrNull(row)?.fragment?.let { fragments.indexOf(it) } ?: -1)
    }

    /** Exports the current digest results as a CSV file. */
    private fun exportDigestCsv() {
        if (mergedRows.isEmpty()) {
            javax.swing.JOptionPane.showMessageDialog(this, "No digest results to export.", "Export CSV", javax.swing.JOptionPane.INFORMATION_MESSAGE)
            return
        }
        val chooser = javax.swing.JFileChooser().apply { dialogTitle = "Export Digest as CSV" }
        if (chooser.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return
        var file = chooser.selectedFile
        if (!file.name.endsWith(".csv")) file = java.io.File(file.parentFile, file.name + ".csv")
        val csv = buildString {
            appendLine("Fragment #,Length,Start,End,Enzyme,Recognition Site,Strand,Cut Type,Overhang")
            for ((i, row) in mergedRows.withIndex()) {
                val f = row.fragment
                val site = row.site
                val fragNum = i + 1
                val enzyme = site?.enzyme?.name ?: ""
                val recogSite = site?.let { doc.seq.sub(it.recognitionStart, it.recognitionStart + it.enzyme.siteLength) } ?: ""
                val strand = site?.strand?.symbol ?: ""
                val cutType = site?.enzyme?.endType?.label ?: ""
                val overhang = site?.let { overhangLabel(it.enzyme, listOf(Digest.stickyEnd(doc.seq, it).overhang)) } ?: ""
                appendLine("$fragNum,${f.length},${f.start + 1},${f.end},$enzyme,$recogSite,$strand,$cutType,$overhang")
            }
        }
        file.writeText(csv)
        summary.text = "Exported ${mergedRows.size} rows to ${file.name}"
    }

    private fun applySelection() {
        val active = checked.toList()
        if (active.isEmpty()) {
            digestVersion++
            doc.setMappedEnzymes(emptyList())
            fragments = emptyList()
            mergedRows = emptyList()
            digestModel.fireTableDataChanged()
            restoreFragmentSelection(null)
            enzymeModel.fireTableDataChanged()
            prefs.update { it.copy(selectedEnzymes = emptyList()) }
            summary.text = "Tick enzymes to map their sites."
            return
        }
        // Small sequences are digested synchronously (fast enough not to block,
        // and tests rely on fragments being ready immediately); large ones are
        // scanned and cut on a background thread so the EDT never stalls.
        if (doc.seq.length < asyncDigestThreshold) {
            val sites = Digest.cutSites(doc.seq, active)
            val frags = Digest.digest(doc.seq, active)
            doc.applyMappedEnzymes(active, sites)
            applyDigestResult(frags, active)
        } else {
            val version = ++digestVersion
            val sourceDoc = doc
            val sourceSeq = sourceDoc.seq
            countPool.submit {
                val sites = Digest.cutSites(sourceSeq, active)
                val frags = Digest.digest(sourceSeq, active)
                SwingUtilities.invokeLater {
                    if (version != digestVersion || doc !== sourceDoc || sourceDoc.seq !== sourceSeq) return@invokeLater
                    sourceDoc.applyMappedEnzymes(active, sites)
                    applyDigestResult(frags, active)
                }
            }
        }
    }

    /** Applies a finished digest (fragments + summary) to the tables on the EDT. */
    private fun applyDigestResult(frags: List<Fragment>, active: List<Enzyme>) {
        val selectedFragment = selectedFragmentInTable()
        fragments = frags
        rebuildMergedRows()
        restoreFragmentSelection(selectedFragment)
        enzymeModel.fireTableDataChanged()
        prefs.update { it.copy(selectedEnzymes = active.map { enzyme -> enzyme.name }) }
        val total = fragments.sumOf { it.length }
        summary.text = "${active.joinToString(", ") { it.name }}  ->  ${doc.cutSites.size} site(s), " +
                "${fragments.size} fragment(s), total $total bp"
    }

    private fun revealFirstSiteOfSelectedEnzyme() {
        val row = enzymeTable.selectedRow
        revealFirstSiteOfEnzyme(row)
    }

    private fun revealFirstSiteOfEnzyme(row: Int) {
        val enzyme = visibleEnzymes.getOrNull(row) ?: return
        val site = (cutSitesCache[enzyme] ?: Digest.cutSites(doc.seq, enzyme)).firstOrNull() ?: return
        onReveal(site.recognitionStart, site.recognitionStart + enzyme.siteLength)
    }

    private fun revealCutSite(site: CutSite?) {
        site ?: return
        val wraps = doc.seq.isCircular && site.recognitionEnd > doc.seq.length
        if (wraps) onReveal(0, doc.seq.length) else onReveal(site.recognitionStart, site.recognitionEnd)
    }

    private fun revealSelectedFragment() {
        val row = digestTable.selectedRow
        val fragment = mergedRows.getOrNull(row)?.fragment ?: return
        revealFragment(fragment)
    }

    /** Restores a stable fragment selection, defaulting to the first result. */
    private fun restoreFragmentSelection(fragmentToRestore: Fragment?) {
        val row = fragmentToRestore?.let { saved -> mergedRows.indexOfFirst { it.fragment == saved } }
            ?.takeIf { it >= 0 }
            ?: mergedRows.indices.firstOrNull()
            ?: -1
        restoringFragmentSelection = true
        try {
            if (row >= 0) digestTable.selectionModel.setSelectionInterval(row, row)
            else digestTable.clearSelection()
        } finally {
            restoringFragmentSelection = false
        }
        updateFragmentActionState()
    }

    /** Keeps fragment actions honest: an enabled button always has a target row. */
    private fun updateFragmentActionState() {
        val hasRow = digestTable.isEnabled && digestTable.selectedRow in mergedRows.indices &&
                mergedRows[digestTable.selectedRow].fragment in fragments
        extractButton.isEnabled = hasRow
        saveFragmentButton.isEnabled = hasRow
    }

    /** Reveals the fragment at [row], handling origin-wrapping ones. */
    fun revealFragment(row: Int) {
        if (row !in fragments.indices) return
        val f = fragments[row]
        revealFragment(f)
    }

    private fun revealFragment(f: Fragment) {
        val wraps = doc.seq.isCircular && f.start + f.length > doc.seq.length
        if (wraps) {
            onReveal(0, doc.seq.length)
        } else {
            onReveal(f.start, f.start + f.length)
        }
    }

    // ------------------------------------------------------------ table models

    private inner class EnzymeTableModel : AbstractTableModel() {
        var counts: Map<Enzyme, Int> = emptyMap()
        var overhangs: Map<Enzyme, List<String>> = emptyMap()

        private val columns = arrayOf(
            TableLabels.USE,
            TableLabels.ENZYME,
            TableLabels.RECOGNITION_SITE,
            TableLabels.OVERHANG,
            TableLabels.CUT_COUNT,
            TableLabels.DESCRIPTION,
        )

        override fun getRowCount(): Int = visibleEnzymes.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]

        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == 0) Boolean::class.javaObjectType else String::class.java

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex == 0

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val enzyme = visibleEnzymes[rowIndex]
            return when (columnIndex) {
                0 -> enzyme in checked
                1 -> enzyme.name
                2 -> enzyme.notation()
                3 -> overhangLabel(enzyme, overhangs[enzyme].orEmpty())
                4 -> (counts[enzyme] ?: 0).toString()
                else -> descriptionFor(enzyme)
            }
        }

        override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
            if (columnIndex != 0) return
            val enzyme = visibleEnzymes[rowIndex]
            if (value == true) checked += enzyme else checked -= enzyme
            applySelection()
        }
    }

    private inner class DigestTableModel : AbstractTableModel() {
        private val columns = arrayOf(
            TableLabels.LENGTH,
            TableLabels.START,
            TableLabels.END,
            TableLabels.STRAND,
            "Overhang",
            TableLabels.RECOGNITION_SEQUENCE,
            TableLabels.CUT_TYPE,
        )

        override fun getRowCount(): Int = mergedRows.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val row = mergedRows[rowIndex]
            val f = row.fragment
            val site = row.site
            val wraps = doc.seq.isCircular && f.end > doc.seq.length
            return when (columnIndex) {
                0 -> TableLabels.length(f.length, SeqKind.DNA)
                1 -> if (wraps) "${f.start + 1} (wraps)" else f.start + 1
                2 -> if (wraps) "${f.end} (wraps)" else f.end
                3 -> site?.strand?.symbol ?: TableLabels.NOT_APPLICABLE
                4 -> site?.let { overhangLabel(it.enzyme, listOf(Digest.stickyEnd(doc.seq, it).overhang)) }
                    ?: TableLabels.NOT_APPLICABLE
                5 -> site?.let { doc.seq.sub(it.recognitionStart, it.recognitionStart + it.enzyme.siteLength) }
                    ?: TableLabels.NOT_APPLICABLE
                else -> site?.enzyme?.endType?.label ?: TableLabels.NOT_APPLICABLE
            }
        }
    }

    private fun overhangLabel(enzyme: Enzyme, observed: List<String>): String {
        val geometry = if (enzyme.overhangLength == 0) {
            "blunt"
        } else {
            "${enzyme.endType.label} (${kotlin.math.abs(enzyme.overhangLength)} bp)"
        }
        val distinct = observed.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) return geometry
        val shown = distinct.take(4).joinToString(", ")
        val more = distinct.size - 4
        return "$geometry: $shown" + if (more > 0) " (+$more more)" else ""
    }

    private data class MergedDigestRow(val fragment: Fragment, val site: CutSite?)
}
