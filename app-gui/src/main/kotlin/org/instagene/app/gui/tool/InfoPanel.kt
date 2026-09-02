package org.instagene.app.gui.tool

import org.instagene.app.gui.ContextMenus
import org.instagene.app.gui.document.SeqDocument
import org.instagene.core.HostMethylationInferenceRules
import org.instagene.core.MethylationSource
import org.instagene.core.MethylationState
import org.instagene.core.NcbiClient
import org.instagene.core.Seq
import org.instagene.core.SeqKind
import org.instagene.core.SeqOps
import org.instagene.core.SequenceIdentity
import org.instagene.core.SequenceClassCatalog
import org.instagene.core.SequenceOrigin
import org.instagene.core.SequenceReference
import org.instagene.core.SequenceStatistics
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Insets
import java.net.URI
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JOptionPane
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingWorker
import javax.swing.table.DefaultTableModel

private class SequenceClassComboBoxModel(items: Array<String>) : DefaultComboBoxModel<String>(items) {
    override fun setSelectedItem(anItem: Any?) {
        if (anItem is String && SequenceClassCatalog.isGroupLabel(anItem)) return
        super.setSelectedItem(anItem)
    }
}

/**
 * The Info tab: editable record metadata on the left and expanded sequence
 * statistics/references on the right. Both columns are independently
 * scrollable so a long description or reference list does not consume the
 * entire window.
 */
class InfoPanel(
    initial: SeqDocument,
    private val onOpen: () -> Unit,
    private val ncbiClient: NcbiClient?,
    private val openExternal: (URI) -> Unit,
    private val onStatus: (String) -> Unit,
    private val copyToClipboard: (String) -> Unit = ContextMenus::copyToClipboard,
) : JPanel(BorderLayout(0, 6)) {

    constructor(initial: SeqDocument) : this(initial, {}, null, { uri -> openInBrowser(uri) }, {})
    constructor(initial: SeqDocument, onOpen: () -> Unit) : this(initial, onOpen, null, { uri -> openInBrowser(uri) }, {})
    constructor(initial: SeqDocument, onOpen: () -> Unit, ncbiClient: NcbiClient) :
        this(initial, onOpen, ncbiClient, { uri -> openInBrowser(uri) }, {})

    private var doc = initial
    private var docListener: SeqDocument.Listener? = null
    private var loadingFields = false
    private var methylationManuallyEdited = false
    private var statsGeneration = 0

    private val statsAsyncThreshold = 50_000_000
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")
        .withZone(ZoneId.systemDefault())

    val nameField = JTextField(24)
    private val nameApply = JButton("Apply name")
    val descriptionField = JTextArea(2, 24).apply {
        lineWrap = true
        wrapStyleWord = true
        addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent) = setDescription()
        })
    }
    private val descriptionScroll = JScrollPane(descriptionField).apply {
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        preferredSize = Dimension(300, 52)
    }

    /** Kept for callers and tests; the visible label now says Type. */
    val kindLabel = JLabel("-")
    val typeLabel get() = kindLabel
    val topologyLabel = JLabel("-")
    val strandednessLabel = JLabel("-")
    val strandOrientationLabel = JLabel("-")
    val methylationLabel = JLabel("-")
    val phosphorylationLabel = JLabel("-")
    val lengthLabel = JLabel("-")
    val gcLabel = JLabel("-")
    val tmLabel = JLabel("-")
    val mwLabel = JLabel("-")
    val featuresLabel = JLabel("-")
    val primersLabel = JLabel("-")
    val historyLabel = JLabel("-")
    val fileLabel = JLabel("-")
    val openFileButton = JButton("Open File...")
    val identityLabel = JLabel("-")
    val copyIdentityButton = JButton("Copy")
    val applyIdentityButton = JButton("Apply")

    val authorField = JTextField(24)
    val ncbiSourceLabel = JLabel("-")
    val openNcbiSourceButton = JButton("Open")
    val commentsArea = JTextArea(4, 24).apply {
        lineWrap = true
        wrapStyleWord = true
        toolTipText = "Record comments from the source file or entered by the researcher; source text is not generated."
    }
    val nucleicAcidCategoryCombo = JComboBox<String>()
    val sequenceClassCombo get() = nucleicAcidCategoryCombo
    val sequenceClassLabel = JLabel("Sequence class")
    val sequenceClassCodeLabel = JLabel("").apply {
        toolTipText = "Three-letter sequence-class code; NCBI where documented, otherwise InstaGene-defined"
    }
    val labHostTypeCombo = JComboBox<String>()
    val customLabHostTypeField = JTextField(16)
    val hostStrainField = JTextField(24)
    val originCombo = JComboBox(SequenceOrigin.entries.toTypedArray())
    val originLockCheck = JCheckBox("Lock origin classification")
    val createdDateLabel = JLabel("-")
    val modifiedDateLabel = JLabel("-")
    val fileCreatedDateLabel = JLabel("-")
    val fileModifiedDateLabel = JLabel("-")
    val applyMetadataButton = JButton("Apply metadata")
    val inferMethylationButton = JButton("Infer from host")
    private val methylationSourceLabel = JLabel("-")
    private val damCombo = methylationCombo()
    private val dcmCombo = methylationCombo()
    private val cpgCombo = methylationCombo()
    val damMethylationCombo get() = damCombo
    val dcmMethylationCombo get() = dcmCombo
    val cpgMethylationCombo get() = cpgCombo

    private val compositionLabel = JLabel("-")
    private val homopolymerLabel = JLabel("-")
    private val gcSkewLabel = JLabel("-")
    private val atSkewLabel = JLabel("-")
    private val ambiguityLabel = JLabel("-")
    private val complexityLabel = JLabel("-")
    private val entropyLabel = JLabel("-")
    private val diversityLabel = JLabel("-")
    private val dinucleotideLabel = JLabel("-")
    private val trinucleotideLabel = JLabel("-")

    private val referencesModel = object : DefaultTableModel(arrayOf("Reference", "Authors", "Title", "Journal", "NCBI/PubMed"), 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    val referencesTable = JTable(referencesModel).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        setShowGrid(false)
        rowHeight = 24
    }
    private val addReferenceButton = JButton("Add")
    private val removeReferenceButton = JButton("Remove")
    private val resolveReferenceButton = JButton("Resolve NCBI")
    private val openReferenceButton = JButton("Open link")
    private val copyReferenceButton = JButton("Copy link")
    val freeformReferencesArea = JTextArea(3, 24).apply {
        lineWrap = true
        wrapStyleWord = true
        toolTipText = "One citation or DOI/URL per line; structured NCBI references are shown above."
    }

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        nucleicAcidCategoryCombo.model = SequenceClassComboBoxModel(SequenceClassCatalog.dropdownItems.toTypedArray())
        nucleicAcidCategoryCombo.isEditable = true
        nucleicAcidCategoryCombo.renderer = sequenceClassRenderer()
        nucleicAcidCategoryCombo.addActionListener { if (!loadingFields) updateSequenceClassCode() }
        labHostTypeCombo.model = DefaultComboBoxModel(
            (listOf("") + HostMethylationInferenceRules.hostTypeSuggestions + "Custom...").toTypedArray(),
        )
        labHostTypeCombo.isEditable = false
        customLabHostTypeField.isVisible = false
        originLockCheck.toolTipText = "Prevents automatic metadata edits from changing Natural/Synthetic/Unknown."

        nameApply.addActionListener { rename() }
        openFileButton.addActionListener { onOpen() }
        openNcbiSourceButton.addActionListener {
            ncbiSourceUrl()?.let { openExternal(URI.create(it)) }
        }
        copyIdentityButton.addActionListener {
            copyText(SequenceIdentity.cdseguid(doc.seq), "sequence identity")
        }
        applyIdentityButton.addActionListener {
            val identity = SequenceIdentity.cdseguid(doc.seq)
            if (doc.seq.uniqueIdentifier != identity) {
                doc.mutate("apply sequence identity") { it.withUniqueIdentifier(identity) }
                onStatus("Sequence identity applied")
            } else {
                onStatus("Sequence identity is already applied")
            }
        }
        applyMetadataButton.addActionListener { applyMetadata() }
        inferMethylationButton.addActionListener { inferMethylationFromHost() }
        labHostTypeCombo.addActionListener {
            if (!loadingFields) {
                customLabHostTypeField.isVisible = labHostTypeCombo.selectedItem == "Custom..."
                inferMethylationFromHost()
            }
        }
        hostStrainField.addActionListener { if (!loadingFields) inferMethylationFromHost() }
        listOf(damCombo, dcmCombo, cpgCombo).forEach { combo ->
            combo.addActionListener { if (!loadingFields) methylationManuallyEdited = true }
        }

        addReferenceButton.addActionListener { addReference() }
        removeReferenceButton.addActionListener { removeReference() }
        resolveReferenceButton.addActionListener { resolveReference() }
        openReferenceButton.addActionListener { selectedReferenceUrl()?.let { openExternal(URI.create(it)) } }
        copyReferenceButton.addActionListener { selectedReferenceUrl()?.let { copyText(it, "reference link") } }
        referencesTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) updateReferenceActions()
        }

        val left = JScrollPane(buildMetadataColumn()).apply {
            border = null
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = Dimension(440, 560)
        }
        val right = JScrollPane(buildStatisticsColumn()).apply {
            border = null
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = Dimension(560, 560)
        }
        val columns = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right).apply {
            resizeWeight = 0.46
            setDividerLocation(0.46)
            border = null
        }
        add(columns, BorderLayout.CENTER)
        bindDocument(doc)
    }

    private fun buildMetadataColumn(): JPanel = JPanel().apply {
        layout = GridLayout(0, 1, 0, 8)
        add(propertiesPanel())
        add(recordMetadataPanel())
        add(chemistryPanel())
    }

    private fun propertiesPanel(): JPanel = JPanel(GridBagLayout()).apply {
        border = BorderFactory.createTitledBorder("Properties")
        var y = 0
        fun row(title: String, component: JComponent) {
            add(JLabel(title), constraints(0, y, anchor = GridBagConstraints.NORTHWEST))
            add(component, constraints(1, y, weightX = 1.0, anchor = GridBagConstraints.NORTHWEST))
            y++
        }
        row("Name", JPanel(BorderLayout(6, 0)).apply {
            add(nameField, BorderLayout.CENTER)
            add(nameApply, BorderLayout.EAST)
        })
        row("Description", descriptionScroll)
        row("Type", kindLabel)
        row("Topology", topologyLabel)
        row("Strandedness", strandednessLabel)
        row("Orientation", strandOrientationLabel)
        row("Methylation", methylationLabel)
        row("End chemistry", phosphorylationLabel)
        row("Length", lengthLabel)
        row("CD-SEGUID", JPanel(BorderLayout(6, 0)).apply {
            identityLabel.toolTipText = "Stable content identity computed from molecule type and sequence bases."
            add(identityLabel, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 3, 0)).apply {
                add(copyIdentityButton)
                add(applyIdentityButton)
            }, BorderLayout.EAST)
        })
        row("File", JPanel(BorderLayout(6, 0)).apply {
            add(fileLabel, BorderLayout.CENTER)
            add(openFileButton, BorderLayout.EAST)
        })
        row("Created", createdDateLabel)
        row("Modified", modifiedDateLabel)
        row("File created", fileCreatedDateLabel)
        row("File modified", fileModifiedDateLabel)
    }

    private fun recordMetadataPanel(): JPanel = JPanel(GridBagLayout()).apply {
        border = BorderFactory.createTitledBorder("Record metadata")
        var y = 0
        fun row(title: String, component: JComponent) {
            add(JLabel(title), constraints(0, y, anchor = GridBagConstraints.NORTHWEST))
            add(component, constraints(1, y, weightX = 1.0, anchor = GridBagConstraints.NORTHWEST))
            y++
        }
        row("NCBI source", JPanel(BorderLayout(6, 0)).apply {
            add(ncbiSourceLabel, BorderLayout.CENTER)
            add(openNcbiSourceButton, BorderLayout.EAST)
        })
        row("Author", authorField)
        add(sequenceClassLabel, constraints(0, y, anchor = GridBagConstraints.NORTHWEST))
        add(JPanel(BorderLayout(6, 0)).apply {
            add(nucleicAcidCategoryCombo, BorderLayout.CENTER)
            add(sequenceClassCodeLabel, BorderLayout.EAST)
        }, constraints(1, y, weightX = 1.0, anchor = GridBagConstraints.NORTHWEST))
        y++
        row("Lab host", labHostTypeCombo)
        row("Custom host", customLabHostTypeField)
        row("Host strain", hostStrainField)
        row("Origin", originCombo)
        row("", originLockCheck)
        row("Record comments", JScrollPane(commentsArea).apply { verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED })
        row("References", JLabel("Original structured references; manage in the right column"))
        row("Other references", JScrollPane(freeformReferencesArea).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            preferredSize = Dimension(300, 75)
        })
        row("", JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { add(applyMetadataButton) })
    }

    private fun chemistryPanel(): JPanel = JPanel(GridBagLayout()).apply {
        border = BorderFactory.createTitledBorder("Methylation")
        var y = 0
        fun row(title: String, component: JComponent) {
            add(JLabel(title), constraints(0, y, anchor = GridBagConstraints.NORTHWEST))
            add(component, constraints(1, y, weightX = 1.0, anchor = GridBagConstraints.NORTHWEST))
            y++
        }
        row("Dam", damCombo)
        row("Dcm", dcmCombo)
        row("CpG", cpgCombo)
        row("Source", methylationSourceLabel)
        row("", JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { add(inferMethylationButton) })
    }

    private fun buildStatisticsColumn(): JPanel = JPanel().apply {
        layout = GridLayout(0, 1, 0, 8)
        add(statisticsPanel())
        add(referencesPanel())
    }

    private fun statisticsPanel(): JPanel = JPanel(GridBagLayout()).apply {
        border = BorderFactory.createTitledBorder("Statistics")
        var y = 0
        fun row(title: String, value: JLabel) {
            add(JLabel(title), constraints(0, y, anchor = GridBagConstraints.NORTHWEST))
            add(value, constraints(1, y, weightX = 1.0, anchor = GridBagConstraints.NORTHWEST))
            y++
        }
        row("GC content", gcLabel)
        row("Melting temp", tmLabel)
        row("Mol. weight", mwLabel)
        row("Composition", compositionLabel)
        row("Longest homopolymer", homopolymerLabel)
        row("GC skew", gcSkewLabel)
        row("AT skew", atSkewLabel)
        row("Ambiguous bases", ambiguityLabel)
        row("Complexity", complexityLabel)
        row("Shannon entropy", entropyLabel)
        row("Simpson diversity", diversityLabel)
        row("Dinucleotide counts", dinucleotideLabel)
        row("Trinucleotide counts", trinucleotideLabel)
        row("Features", featuresLabel)
        row("Primers", primersLabel)
        row("Recorded procedures", historyLabel)
    }

    private fun referencesPanel(): JPanel = JPanel(BorderLayout(4, 4)).apply {
        border = BorderFactory.createTitledBorder("Scientific references")
        add(JScrollPane(referencesTable).apply {
            preferredSize = Dimension(420, 125)
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        }, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(addReferenceButton)
            add(removeReferenceButton)
            add(resolveReferenceButton)
            add(openReferenceButton)
            add(copyReferenceButton)
        }, BorderLayout.SOUTH)
    }

    /** Binds this panel to another document and refreshes every field. */
    fun bindDocument(newDoc: SeqDocument) {
        if (newDoc !== doc) {
            docListener?.let { doc.removeListener(it) }
            doc = newDoc
            docListener?.let { doc.addListener(it) }
        }
        if (docListener == null) {
            val listener = SeqDocument.Listener { _, reason ->
                if (reason == SeqDocument.Reason.SEQUENCE) refresh()
            }
            docListener = listener
            doc.addListener(listener)
        }
        refresh()
    }

    /** Refreshes every field from the document without overwriting active edits. */
    fun refresh() {
        val seq = doc.seq
        loadingFields = true
        try {
            if (!nameField.hasFocus()) nameField.text = seq.name
            if (!descriptionField.hasFocus()) descriptionField.text = seq.description
            kindLabel.text = seq.kind.name.lowercase()
            topologyLabel.text = seq.topology.name.lowercase()
            strandednessLabel.text = when (seq.molecule.strandedness) {
                org.instagene.core.Strandedness.DOUBLE -> "double"
                org.instagene.core.Strandedness.SINGLE -> "single"
            }
            strandOrientationLabel.text = when (seq.molecule.strandedness) {
                org.instagene.core.Strandedness.DOUBLE -> "5′→3′ / 3′→5′"
                org.instagene.core.Strandedness.SINGLE -> "5′→3′"
            }
            methylationLabel.text = methylationSummary(seq)
            methylationSourceLabel.text = seq.molecule.methylationSource.name.lowercase()
            phosphorylationLabel.text = buildList {
                if (seq.molecule.fivePrimePhosphorylated) add("5′ phosphorylated")
                if (seq.molecule.threePrimePhosphorylated) add("3′ phosphorylated")
            }.joinToString(", ").ifBlank { "none" }
            val unit = if (seq.kind == SeqKind.PROTEIN) "aa" else "bp"
            lengthLabel.text = "${seq.length} $unit"
            val identity = SequenceIdentity.cdseguid(seq)
            identityLabel.text = "$identity (${if (SequenceIdentity.verify(seq)) "stored" else "computed"})"
            applyIdentityButton.isEnabled = seq.uniqueIdentifier != identity
            val hasFile = doc.file != null
            fileLabel.text = doc.file?.absolutePath ?: ""
            fileLabel.isVisible = hasFile
            openFileButton.isVisible = !hasFile
            featuresLabel.text = seq.features.size.toString()
            primersLabel.text = seq.primers.size.toString()
            historyLabel.text = seq.provenance.size.toString()

            val metadata = seq.recordMetadata
            val sourceUrl = ncbiSourceUrl(seq)
            ncbiSourceLabel.text = sourceUrl ?: "-"
            ncbiSourceLabel.toolTipText = sourceUrl
            openNcbiSourceButton.isEnabled = sourceUrl != null
            authorField.text = metadata.resolvedAuthor().orEmpty()
            selectComboValue(nucleicAcidCategoryCombo, metadata.nucleicAcidCategory)
            updateSequenceClassCode()
            setHostTypeUi(metadata.labHostType)
            hostStrainField.text = metadata.hostStrain.orEmpty()
            originCombo.selectedItem = metadata.origin
            originLockCheck.isSelected = metadata.originLocked
            commentsArea.text = metadata.comments.joinToString("\n")
            freeformReferencesArea.text = metadata.freeformReferences.joinToString("\n")
            damCombo.selectedItem = seq.molecule.damState
            dcmCombo.selectedItem = seq.molecule.dcmState
            cpgCombo.selectedItem = seq.molecule.cpgState
            methylationManuallyEdited = seq.molecule.methylationSource == MethylationSource.MANUAL
            updateDateLabels(seq)
            refreshReferences()
        } finally {
            loadingFields = false
        }
        refreshStats(seq)
    }

    private fun refreshReferences() {
        referencesModel.rowCount = 0
        doc.seq.recordMetadata.references.forEach { reference ->
            referencesModel.addRow(arrayOf(reference.reference, reference.authors, reference.title, reference.journal, reference.pubMed ?: reference.sourceUrl.orEmpty()))
        }
        updateReferenceActions()
    }

    private fun updateReferenceActions() {
        val selected = referencesTable.selectedRow >= 0
        val link = selectedReferenceUrl() != null
        removeReferenceButton.isEnabled = selected
        resolveReferenceButton.isEnabled = selected && ncbiClient != null
        openReferenceButton.isEnabled = link
        copyReferenceButton.isEnabled = link
    }

    private fun ncbiSourceUrl(): String? = ncbiSourceUrl(doc.seq)

    private fun ncbiSourceUrl(seq: Seq): String? {
        val value = seq.metadata["ONLINE_URL"] ?: return null
        val uri = runCatching { URI.create(value) }.getOrNull() ?: return null
        return value.takeIf {
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host.equals("www.ncbi.nlm.nih.gov", ignoreCase = true) &&
                uri.path.startsWith("/nuccore/") &&
                uri.path.removePrefix("/nuccore/").isNotBlank()
        }
    }

    private fun refreshStats(seq: Seq) {
        val generation = ++statsGeneration
        if (seq.length <= statsAsyncThreshold) {
            renderStats(seq, SequenceStatistics.computeStats(seq))
            return
        }
        setStatsPending()
        object : SwingWorker<SequenceStatsHolder, Unit>() {
            override fun doInBackground(): SequenceStatsHolder = SequenceStatsHolder(seq, SequenceStatistics.computeStats(seq))
            override fun done() {
                if (generation != statsGeneration) return
                runCatching { get() }.onSuccess { renderStats(it.seq, it.stats) }
            }
        }.execute()
    }

    private fun renderStats(seq: Seq, stats: org.instagene.core.SequenceStats) {
        gcLabel.text = "%.1f %%".format(SeqOps.gcContent(seq))
        tmLabel.text = if (seq.kind == SeqKind.PROTEIN) "n/a (protein)" else "%.1f C".format(SeqOps.meltingTemp(seq.bases))
        mwLabel.text = if (seq.kind == SeqKind.PROTEIN) "n/a" else "%.0f Da".format(SeqOps.molecularWeightDaltons(seq))
        compositionLabel.text = if (seq.kind == SeqKind.PROTEIN) {
            SequenceStatistics.aminoAcidComposition(seq).take(8).joinToString(", ") { "${it.label}: ${it.value}%" }.ifBlank { "-" }
        } else {
            stats.nucleotideComposition.entries.joinToString(" ") { "${it.key}:${it.value}" }.ifBlank { "-" }
        }
        homopolymerLabel.text = if (stats.longestHomopolymer.second == 0) "-" else "${stats.longestHomopolymer.first} × ${stats.longestHomopolymer.second}"
        gcSkewLabel.text = "%.3f".format(stats.gcSkew)
        atSkewLabel.text = "%.3f".format(stats.atSkew)
        ambiguityLabel.text = stats.ambigCount.toString()
        complexityLabel.text = "%.2f".format(stats.complexityScore)
        entropyLabel.text = "%.3f bits".format(stats.shannonEntropy)
        diversityLabel.text = "%.3f".format(stats.simpsonDiversity)
        dinucleotideLabel.text = stats.dinucleotideCounts.entries.joinToString(" ") { "${it.key}:${it.value}" }.ifBlank { "-" }
        trinucleotideLabel.text = stats.trinucleotideCounts.entries.take(16).joinToString(" ") { "${it.key}:${it.value}" }.ifBlank { "-" }
    }

    private fun setStatsPending() {
        listOf(gcLabel, tmLabel, mwLabel, compositionLabel, homopolymerLabel, gcSkewLabel, atSkewLabel, ambiguityLabel, complexityLabel, entropyLabel, diversityLabel, dinucleotideLabel, trinucleotideLabel).forEach { it.text = "..." }
    }

    private fun applyMetadata() {
        val before = doc.seq
        val metadata = before.recordMetadata
        val selectedOrigin = originCombo.selectedItem as? SequenceOrigin ?: SequenceOrigin.UNKNOWN
        val origin = if (metadata.originLocked) metadata.origin else selectedOrigin
        val inferred = HostMethylationInferenceRules.infer(hostTypeValue(), hostStrainField.text)
        val molecule = if (!methylationManuallyEdited && inferred.profile != org.instagene.core.MethylationProfile.from(before.molecule)) {
            before.molecule.withMethylation(inferred.profile.dam, inferred.profile.dcm, inferred.profile.cpg, MethylationSource.INFERRED)
        } else {
            before.molecule.withMethylation(selectedMethylation(damCombo), selectedMethylation(dcmCombo), selectedMethylation(cpgCombo), if (methylationManuallyEdited) MethylationSource.MANUAL else before.molecule.methylationSource)
        }
        val comments = commentsArea.text.split('\n').map(String::trim).filter(String::isNotEmpty)
        val freeform = freeformReferencesArea.text.split('\n').map(String::trim).filter(String::isNotEmpty)
        doc.mutate("edit record metadata") {
            it.copy(
                molecule = molecule,
                recordMetadata = metadata.copy(
                    author = authorField.text.trim().ifBlank { metadata.resolvedAuthor() },
                    comments = comments,
                    freeformReferences = freeform,
                    nucleicAcidCategory = selectedSequenceClass(),
                    labHostType = hostTypeValue()?.ifBlank { null },
                    hostStrain = hostStrainField.text.trim().ifBlank { null },
                    origin = origin,
                    originLocked = originLockCheck.isSelected,
                ),
            )
        }
        onStatus("Metadata applied")
    }

    private fun inferMethylationFromHost() {
        val inference = HostMethylationInferenceRules.infer(hostTypeValue(), hostStrainField.text)
        if (inference.matchedHost == null) {
            onStatus("Host methylation is unknown; choose states manually")
            return
        }
        loadingFields = true
        try {
            damCombo.selectedItem = inference.profile.dam.asMethylationState()
            dcmCombo.selectedItem = inference.profile.dcm.asMethylationState()
            cpgCombo.selectedItem = inference.profile.cpg.asMethylationState()
            methylationManuallyEdited = false
        } finally {
            loadingFields = false
        }
        onStatus(inference.matchedHost?.let { "Inferred methylation from $it" } ?: "Applied host methylation inference")
    }

    private fun addReference() {
        val reference = JOptionPane.showInputDialog(this, "Citation, DOI, PMID, or URL:", "Add reference", JOptionPane.PLAIN_MESSAGE)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val pubMed = NcbiClient.extractPubMedId(reference)
        val canonical = NcbiClient.canonicalReferenceUrl(reference)
        val isLink = canonical.startsWith("http://") || canonical.startsWith("https://")
        doc.mutate("add reference") { seq ->
            seq.withRecordMetadata {
                copy(references = references + SequenceReference(
                    reference = reference,
                    pubMed = pubMed,
                    sourceUrl = canonical.takeIf { isLink },
                ))
            }
        }
    }

    private fun removeReference() {
        val row = referencesTable.selectedRow
        if (row < 0) return
        val references = doc.seq.recordMetadata.references.toMutableList()
        if (row < references.size) {
            val removed = references.removeAt(row)
            doc.mutate("remove reference") { seq -> seq.withRecordMetadata { copy(references = references) } }
            onStatus("Removed ${removed.reference.ifBlank { "reference" }}")
        }
    }

    private fun resolveReference() {
        val row = referencesTable.selectedRow
        if (row < 0) return
        val reference = doc.seq.recordMetadata.references.getOrNull(row) ?: return
        val id = reference.pubMed?.takeIf { it.isNotBlank() } ?: NcbiClient.extractPubMedId(reference.reference)
        if (id == null) {
            onStatus("Select a reference containing a PubMed ID or NCBI link")
            return
        }
        val client = ncbiClient
        if (client == null) {
            onStatus("NCBI is not configured")
            return
        }
        resolveReferenceButton.isEnabled = false
        object : SwingWorker<org.instagene.core.NcbiPublication, Unit>() {
            override fun doInBackground() = client.fetchPublication(id)
            override fun done() {
                resolveReferenceButton.isEnabled = true
                runCatching { get() }.onSuccess { publication ->
                    val references = doc.seq.recordMetadata.references.toMutableList()
                    if (row in references.indices) {
                        references[row] = references[row].copy(
                            pubMed = publication.pubMed,
                            authors = publication.authors.ifBlank { references[row].authors },
                            title = publication.title.ifBlank { references[row].title },
                            journal = publication.journal.ifBlank { references[row].journal },
                            sourceUrl = publication.sourceUrl,
                        )
                        doc.mutate("resolve NCBI reference") { seq -> seq.withRecordMetadata { copy(references = references) } }
                        onStatus("Resolved PubMed ${publication.pubMed}")
                    }
                }.onFailure { error -> onStatus("NCBI reference lookup failed: ${error.message ?: "unknown error"}") }
            }
        }.execute()
    }

    private fun selectedReferenceUrl(): String? {
        val row = referencesTable.selectedRow
        val reference = doc.seq.recordMetadata.references.getOrNull(row) ?: return null
        return reference.sourceUrl ?: reference.pubMed?.let(NcbiClient::pubMedUrl)
            ?: NcbiClient.canonicalReferenceUrl(reference.reference).takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun setHostTypeUi(value: String?) {
        val known = HostMethylationInferenceRules.hostTypeSuggestions.any { it.equals(value, ignoreCase = true) }
        if (known) {
            labHostTypeCombo.selectedItem = HostMethylationInferenceRules.hostTypeSuggestions.first { it.equals(value, ignoreCase = true) }
            customLabHostTypeField.text = ""
            customLabHostTypeField.isVisible = false
        } else if (value.isNullOrBlank()) {
            labHostTypeCombo.selectedItem = ""
            customLabHostTypeField.text = ""
            customLabHostTypeField.isVisible = false
        } else {
            labHostTypeCombo.selectedItem = "Custom..."
            customLabHostTypeField.text = value
            customLabHostTypeField.isVisible = true
        }
    }

    private fun hostTypeValue(): String? = if (labHostTypeCombo.selectedItem == "Custom...") {
        customLabHostTypeField.text.trim().ifBlank { null }
    } else {
        labHostTypeCombo.selectedItem?.toString()?.trim()?.ifBlank { null }
    }

    private fun updateDateLabels(seq: Seq) {
        createdDateLabel.text = formatDate(seq.recordMetadata.createdAt)
        modifiedDateLabel.text = formatDate(seq.recordMetadata.modifiedAt)
        val file = doc.file
        if (file == null || !file.exists()) {
            fileCreatedDateLabel.text = "-"
            fileModifiedDateLabel.text = "-"
            return
        }
        runCatching {
            val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            fileCreatedDateLabel.text = formatDate(attributes.creationTime().toMillis())
            fileModifiedDateLabel.text = formatDate(attributes.lastModifiedTime().toMillis())
        }.onFailure {
            fileCreatedDateLabel.text = "unavailable"
            fileModifiedDateLabel.text = "unavailable"
        }
    }

    private fun formatDate(value: Long?): String = value?.let { dateFormatter.format(Instant.ofEpochMilli(it)) } ?: "-"

    private fun copyText(text: String, label: String) {
        runCatching { copyToClipboard(text) }
            .onSuccess { onStatus("Copied $label") }
            .onFailure { onStatus("Copy failed: ${it.message ?: "clipboard unavailable"}") }
    }

    private fun methylationSummary(seq: Seq): String = buildList {
        if (seq.molecule.damState == MethylationState.METHYLATED) add("Dam")
        if (seq.molecule.dcmState == MethylationState.METHYLATED) add("Dcm")
        if (seq.molecule.cpgState == MethylationState.METHYLATED) add("CpG")
        if (seq.molecule.damState == MethylationState.UNKNOWN) add("Dam?")
        if (seq.molecule.dcmState == MethylationState.UNKNOWN) add("Dcm?")
        if (seq.molecule.cpgState == MethylationState.UNKNOWN) add("CpG?")
    }.joinToString(", ").ifBlank { "none" }

    private fun rename() {
        val newName = nameField.text.trim()
        if (newName.isNotEmpty() && newName != doc.seq.name) doc.mutate("rename") { it.withName(newName) }
    }

    /** Sets the name field and applies it (undoable); used by tests. */
    fun renameTo(newName: String) {
        nameField.text = newName
        rename()
    }

    private fun setDescription() {
        val text = descriptionField.text
        if (text != doc.seq.description) doc.mutate("edit description") { it.copy(description = text) }
    }

    private fun sequenceClassRenderer(): DefaultListCellRenderer = object : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: javax.swing.JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val rendered = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val label = rendered as? JLabel ?: return rendered
            val classLabel = value as? String
            when {
                SequenceClassCatalog.isGroupLabel(classLabel) -> {
                    label.font = label.font.deriveFont(Font.BOLD)
                    label.isEnabled = false
                }
                index >= 0 -> SequenceClassCatalog.option(classLabel)?.let { option ->
                    label.text = option.displayLabel
                }
            }
            return rendered
        }
    }

    private fun selectedSequenceClass(): String? = (nucleicAcidCategoryCombo.editor.item as? String)
        ?.trim()
        ?.ifBlank { null }
        ?.let { SequenceClassCatalog.option(it)?.label ?: it }
        ?.takeUnless(SequenceClassCatalog::isGroupLabel)

    private fun updateSequenceClassCode() {
        sequenceClassCodeLabel.text = SequenceClassCatalog.option(selectedSequenceClass())?.sequenceCode.orEmpty()
    }

    private fun selectComboValue(combo: JComboBox<String>, value: String?) {
        if (value == null && combo.itemCount > 0) combo.selectedItem = combo.getItemAt(0) else combo.selectedItem = value
    }

    private fun methylationCombo(): JComboBox<MethylationState> = JComboBox(MethylationState.entries.toTypedArray())

    private fun selectedMethylation(combo: JComboBox<MethylationState>): Boolean? = when (combo.selectedItem as? MethylationState) {
        MethylationState.METHYLATED -> true
        MethylationState.UNMETHYLATED -> false
        else -> null
    }

    private fun Boolean?.asMethylationState(): MethylationState = when (this) {
        true -> MethylationState.METHYLATED
        false -> MethylationState.UNMETHYLATED
        null -> MethylationState.UNKNOWN
    }

    private fun constraints(x: Int, y: Int, weightX: Double = 0.0, anchor: Int = GridBagConstraints.WEST): GridBagConstraints =
        GridBagConstraints().apply {
            gridx = x
            gridy = y
            this.weightx = weightX
            this.anchor = anchor
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(3, 6, 3, 6)
        }

    private data class SequenceStatsHolder(val seq: Seq, val stats: org.instagene.core.SequenceStats)

    private companion object {
        fun openInBrowser(uri: URI) {
            if (Desktop.isDesktopSupported()) runCatching { Desktop.getDesktop().browse(uri) }
        }
    }
}
