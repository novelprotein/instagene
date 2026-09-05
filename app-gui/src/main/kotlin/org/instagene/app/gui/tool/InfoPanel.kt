package org.instagene.app.gui.tool

import org.instagene.app.gui.ContextMenus
import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.prefs.Prefs
import org.instagene.core.*
import java.awt.*
import java.awt.event.ActionListener
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
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
    private val prefs: Prefs = Prefs(),
) : JPanel(BorderLayout(0, 6)) {

    constructor(initial: SeqDocument) : this(initial, {}, null, { uri -> openInBrowser(uri) }, {})
    constructor(initial: SeqDocument, onOpen: () -> Unit) : this(initial, onOpen, null, { uri -> openInBrowser(uri) }, {})
    constructor(initial: SeqDocument, onOpen: () -> Unit, ncbiClient: NcbiClient) :
        this(initial, onOpen, ncbiClient, { uri -> openInBrowser(uri) }, {})
    constructor(initial: SeqDocument, onOpen: () -> Unit, ncbiClient: NcbiClient, prefs: Prefs) :
        this(initial, onOpen, ncbiClient, { uri -> openInBrowser(uri) }, {}, ContextMenus::copyToClipboard, prefs)

    private var doc = initial
    private var docListener: SeqDocument.Listener? = null
    private var loadingFields = false
    private var methylationManuallyEdited = false
    private var statsGeneration = 0
    private var filteringHostSuggestions = false
    private var hostFilterScheduled = false
    private var taxonomyLookupOrganism: String? = null

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
    val orientationAndEndChemistryLabel = JLabel("-")
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
    /** Kept under its historical name for callers; it is now an editable source field. */
    val ncbiSourceLabel = JTextField(24)
    val ncbiSourceField get() = ncbiSourceLabel
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
    val hostStrainCombo = JComboBox<String>()
    val originCombo = JComboBox(SequenceOrigin.entries.toTypedArray())
    val originLockCheck = JCheckBox("Lock sequence editing")
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

    private val originLockRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        add(originLockCheck)
    }

    private val labHostTypeSuggestions = listOf(
        "",
        "E. coli",
        "Bacterial",
        "Mammalian",
        "Insect",
        "Yeast",
        "Plant",
        "Fungal",
        "Archaeal",
        "Cell-free",
        "Environmental",
        "Other",
        "Custom...",
    )

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
        toolTipText = "One citation, DOI, or URL per line."
    }

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        nucleicAcidCategoryCombo.model = SequenceClassComboBoxModel(SequenceClassCatalog.dropdownItems.toTypedArray())
        nucleicAcidCategoryCombo.isEditable = true
        nucleicAcidCategoryCombo.renderer = sequenceClassRenderer()
        nucleicAcidCategoryCombo.addActionListener { if (!loadingFields) updateSequenceClassCode() }
        labHostTypeCombo.model = DefaultComboBoxModel(labHostTypeSuggestions.toTypedArray())
        labHostTypeCombo.isEditable = false
        customLabHostTypeField.isVisible = false
        originLockCheck.toolTipText = "Temporarily prevents changes to the sequence bases while annotating a natural record."
        originLockRow.isVisible = false
        openFileButton.isVisible = false
        fileLabel.isVisible = false
        fileCreatedDateLabel.isVisible = false
        fileModifiedDateLabel.isVisible = false

        hostStrainCombo.isEditable = true
        hostStrainCombo.editor = hostStrainEditor()
        hostStrainCombo.maximumRowCount = 8
        hostStrainCombo.toolTipText = "Type to search host strains, or choose a suggestion."
        refreshHostStrainSuggestions()
        hostStrainCombo.addActionListener {
            if (!loadingFields && !filteringHostSuggestions) {
                hostStrainCombo.selectedItem?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    hostStrainField.text = it
                    rememberHostStrain(it)
                    inferMethylationFromHost()
                }
            }
        }
        prefs.addListener { refreshHostStrainSuggestions() }
        hostStrainField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusGained(e: java.awt.event.FocusEvent) {
                suggestHostsOnFocus()
            }

            override fun focusLost(e: java.awt.event.FocusEvent) {
                rememberHostStrain(hostStrainField.text)
            }
        })
        hostStrainField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = filterHostSuggestions()
            override fun removeUpdate(e: DocumentEvent) = filterHostSuggestions()
            override fun changedUpdate(e: DocumentEvent) = filterHostSuggestions()
        })

        ncbiSourceLabel.toolTipText = "Enter an NCBI accession or nuccore URL, then apply metadata."
        ncbiSourceLabel.addActionListener { openNcbiSource() }
        ncbiSourceLabel.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = updateNcbiSourceActions()
            override fun removeUpdate(e: DocumentEvent) = updateNcbiSourceActions()
            override fun changedUpdate(e: DocumentEvent) = updateNcbiSourceActions()
        })

        nameApply.addActionListener { rename() }
        openFileButton.addActionListener { onOpen() }
        openNcbiSourceButton.addActionListener {
            openNcbiSource()
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
        originLockCheck.addActionListener {
            if (!loadingFields && originCombo.selectedItem == SequenceOrigin.NATURAL) {
                doc.setSequenceEditingLocked(originLockCheck.isSelected)
            }
        }
        originCombo.addActionListener { if (!loadingFields) updateOriginLockUi() }
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
            preferredSize = Dimension(360, 480)
            minimumSize = Dimension(0, 0)
        }
        val right = JScrollPane(buildStatisticsColumn()).apply {
            border = null
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = Dimension(400, 480)
            minimumSize = Dimension(0, 0)
        }
        val columns = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right).apply {
            resizeWeight = 0.46
            setDividerLocation(0.46)
            border = null
            minimumSize = Dimension(0, 0)
            preferredSize = Dimension(760, 480)
        }
        add(columns, BorderLayout.CENTER)
        bindDocument(doc)
    }

    private fun buildMetadataColumn(): JPanel = JPanel().apply {
        layout = GridLayout(0, 1, 0, 8)
        add(propertiesPanel())
        add(recordMetadataPanel())
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
        row("Orientation / end chemistry", orientationAndEndChemistryLabel)
        row("Methylation", JPanel(GridLayout(1, 3, 6, 0)).apply {
            add(methylationChoice("Dam", damCombo))
            add(methylationChoice("Dcm", dcmCombo))
            add(methylationChoice("CpG", cpgCombo))
        })
        row("Methylation source", methylationSourceLabel)
        row("", JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { add(inferMethylationButton) })
        row("Length", lengthLabel)
        row("CD-SEGUID", JPanel(BorderLayout(6, 0)).apply {
            identityLabel.toolTipText = "Stable content identity computed from molecule type and sequence bases."
            add(identityLabel, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 3, 0)).apply {
                add(copyIdentityButton)
                add(applyIdentityButton)
            }, BorderLayout.EAST)
        })
        row("Created", createdDateLabel)
        row("Modified", modifiedDateLabel)
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
        row("Host strain", JPanel(BorderLayout(6, 0)).apply {
            add(hostStrainCombo, BorderLayout.CENTER)
        })
        row("Origin", originCombo)
        row("", originLockRow)
        row("Record comments", JScrollPane(commentsArea).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            preferredSize = Dimension(300, 78)
        })
        row("", JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { add(applyMetadataButton) })
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

    private fun referencesPanel(): JPanel = JPanel(GridBagLayout()).apply {
        border = BorderFactory.createTitledBorder("References")
        var y = 0
        fun row(title: String, component: JComponent, preferredHeight: Int? = null) {
            add(JLabel(title), constraints(0, y, anchor = GridBagConstraints.NORTHWEST))
            if (preferredHeight != null) component.preferredSize = Dimension(420, preferredHeight)
            add(component, constraints(1, y, weightX = 1.0, weightY = 1.0, anchor = GridBagConstraints.NORTHWEST))
            y++
        }
        row("Structured references", JScrollPane(referencesTable).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        }, 150)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(addReferenceButton)
            add(removeReferenceButton)
            add(resolveReferenceButton)
            add(openReferenceButton)
            add(copyReferenceButton)
        }, constraints(1, y, weightX = 1.0, anchor = GridBagConstraints.WEST))
        y++
        row("Additional references", JScrollPane(freeformReferencesArea).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        }, 78)
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
                when (reason) {
                    SeqDocument.Reason.SEQUENCE -> refresh()
                    SeqDocument.Reason.EDITABILITY -> updateOriginLockUi()
                    else -> Unit
                }
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
                Strandedness.DOUBLE -> "double"
                Strandedness.SINGLE -> "single"
            }
            strandOrientationLabel.text = when (seq.molecule.strandedness) {
                Strandedness.DOUBLE -> "5′→3′ / 3′→5′"
                Strandedness.SINGLE -> "5′→3′"
            }
            orientationAndEndChemistryLabel.text = listOf(
                strandOrientationLabel.text,
                buildList {
                    if (seq.molecule.fivePrimePhosphorylated) add("5′ phosphorylated")
                    if (seq.molecule.threePrimePhosphorylated) add("3′ phosphorylated")
                }.joinToString(", ").ifBlank { "no phosphorylation" },
            ).joinToString("; ")
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
            fileLabel.text = doc.file?.absolutePath ?: ""
            featuresLabel.text = seq.features.size.toString()
            primersLabel.text = seq.primers.size.toString()
            historyLabel.text = seq.provenance.size.toString()

            val metadata = seq.recordMetadata
            ncbiSourceLabel.text = seq.metadata["ONLINE_URL"] ?: seq.metadata["ONLINE_ACCESSION"].orEmpty()
            updateNcbiSourceActions()
            authorField.text = metadata.resolvedAuthor().orEmpty()
            selectComboValue(nucleicAcidCategoryCombo, metadata.nucleicAcidCategory)
            updateSequenceClassCode()
            setHostTypeUi(metadata.labHostType)
            hostStrainField.text = metadata.hostStrain.orEmpty()
            selectHostStrainSuggestion(metadata.hostStrain)
            originCombo.selectedItem = metadata.origin
            updateOriginLockUi()
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

    private fun openNcbiSource() {
        parseNcbiSourceInput(ncbiSourceLabel.text)?.url?.let { openExternal(URI.create(it)) }
    }

    private fun updateNcbiSourceActions() {
        val sourceUrl = parseNcbiSourceInput(ncbiSourceLabel.text)?.url
        ncbiSourceLabel.toolTipText = sourceUrl ?: "Enter an NCBI accession or nuccore URL, then apply metadata."
        openNcbiSourceButton.isEnabled = sourceUrl != null
    }

    private fun parseNcbiSourceInput(value: String?): NcbiSource? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return null
        val canonical = NcbiClient.canonicalReferenceUrl(raw)
        val uri = runCatching { URI.create(canonical) }.getOrNull() ?: return null
        if (
            !uri.scheme.equals("https", ignoreCase = true) ||
            !uri.host.equals("www.ncbi.nlm.nih.gov", ignoreCase = true) ||
            !uri.path.startsWith("/nuccore/")
        ) return null
        val accession = uri.path.removePrefix("/nuccore/").trim('/').takeIf { it.isNotBlank() } ?: return null
        if (!accession.matches(Regex("[A-Za-z0-9_.-]+"))) return null
        return NcbiSource(accession, NcbiClient.nuccoreUrl(accession))
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

    private fun openHostSuggestions() {
        if (GraphicsEnvironment.isHeadless()) return
        SwingUtilities.invokeLater {
            if (hostStrainField.isFocusOwner && hostStrainCombo.isShowing && !hostStrainCombo.isPopupVisible) {
                hostStrainCombo.showPopup()
            }
        }
    }

    private fun renderStats(seq: Seq, stats: SequenceStats) {
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
        val origin = selectedOrigin
        val ncbiSource = parseNcbiSourceInput(ncbiSourceLabel.text)
        if (ncbiSourceLabel.text.isNotBlank() && ncbiSource == null) {
            onStatus("Enter a valid NCBI accession or nuccore URL")
            return
        }
        val inferred = HostMethylationInferenceRules.infer(hostTypeValue(), hostStrainField.text)
        val molecule = if (!methylationManuallyEdited && inferred.profile != MethylationProfile.from(before.molecule)) {
            before.molecule.withMethylation(inferred.profile.dam, inferred.profile.dcm, inferred.profile.cpg, MethylationSource.INFERRED)
        } else {
            before.molecule.withMethylation(selectedMethylation(damCombo), selectedMethylation(dcmCombo), selectedMethylation(cpgCombo), if (methylationManuallyEdited) MethylationSource.MANUAL else before.molecule.methylationSource)
        }
        val comments = commentsArea.text.split('\n').map(String::trim).filter(String::isNotEmpty)
        val freeform = freeformReferencesArea.text.split('\n').map(String::trim).filter(String::isNotEmpty)
        val nextSeqMetadata = before.metadata.toMutableMap().apply {
            if (ncbiSource == null) {
                remove("ONLINE_URL")
                remove("ONLINE_ACCESSION")
            } else {
                this["ONLINE_URL"] = ncbiSource.url
                this["ONLINE_ACCESSION"] = ncbiSource.accession
            }
        }
        val hostStrain = hostStrainField.text.trim().ifBlank { null }
        doc.mutate("edit record metadata") {
            it.copy(
                metadata = nextSeqMetadata,
                molecule = molecule,
                recordMetadata = metadata.copy(
                    author = authorField.text.trim().ifBlank { metadata.resolvedAuthor() },
                    comments = comments,
                    freeformReferences = freeform,
                    nucleicAcidCategory = selectedSequenceClass(),
                    labHostType = hostTypeValue()?.ifBlank { null },
                    hostStrain = hostStrain,
                    origin = origin,
                ),
            )
        }
        if (origin == SequenceOrigin.NATURAL) {
            doc.setSequenceEditingLocked(originLockCheck.isSelected)
        } else {
            doc.setSequenceEditingLocked(false)
        }
        rememberHostStrain(hostStrain)
        onStatus("Metadata applied")
    }

    private fun inferMethylationFromHost() {
        val inference = HostMethylationInferenceRules.infer(hostTypeValue(), hostStrainField.text)
        if (inference.matchedHost == null) {
            loadingFields = true
            try {
                damCombo.selectedItem = MethylationState.UNKNOWN
                dcmCombo.selectedItem = MethylationState.UNKNOWN
                cpgCombo.selectedItem = MethylationState.UNKNOWN
                methylationManuallyEdited = false
            } finally {
                loadingFields = false
            }
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
        object : SwingWorker<NcbiPublication, Unit>() {
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

    private fun methylationChoice(label: String, combo: JComboBox<MethylationState>): JPanel = JPanel(BorderLayout(2, 0)).apply {
        add(JLabel(label), BorderLayout.WEST)
        add(combo, BorderLayout.CENTER)
    }

    private fun refreshHostStrainSuggestions() {
        val current = hostStrainField.text.ifBlank { hostStrainCombo.selectedItem?.toString().orEmpty() }
        val values = hostSuggestionValues()
            .distinctBy { it.trim().lowercase() }
            .toTypedArray()
        loadingFields = true
        try {
            hostStrainCombo.model = DefaultComboBoxModel(values)
            selectHostStrainSuggestion(current ?: hostStrainField.text)
        } finally {
            loadingFields = false
        }
    }

    private fun selectHostStrainSuggestion(value: String?) {
        val match = value?.trim()?.takeIf { it.isNotEmpty() }?.let { requested ->
            (0 until hostStrainCombo.itemCount)
                .map(hostStrainCombo::getItemAt)
                .firstOrNull { it.equals(requested, ignoreCase = true) }
        }
        if (match != null) {
            hostStrainCombo.selectedItem = match
        } else {
            hostStrainCombo.editor.item = value.orEmpty()
        }
    }

    private fun filterHostSuggestions() {
        if (loadingFields || filteringHostSuggestions || !hostStrainField.hasFocus() || hostFilterScheduled) return
        hostFilterScheduled = true
        SwingUtilities.invokeLater {
            hostFilterScheduled = false
            if (loadingFields || filteringHostSuggestions || !hostStrainField.hasFocus()) return@invokeLater
            val query = hostStrainField.text.trim()
            val values = hostSuggestionValues().filter {
                query.isBlank() || it.contains(query, ignoreCase = true)
            }.distinctBy { it.trim().lowercase() }
            filteringHostSuggestions = true
            loadingFields = true
            try {
                val current = hostStrainField.text
                hostStrainCombo.model = DefaultComboBoxModel(values.toTypedArray())
                hostStrainCombo.editor.item = current
            } finally {
                loadingFields = false
                filteringHostSuggestions = false
            }
        }
    }

    private fun hostStrainEditor(): ComboBoxEditor = object : ComboBoxEditor {
        private val listeners = mutableListOf<ActionListener>()

        override fun getEditorComponent() = hostStrainField
        override fun setItem(anObject: Any?) {
            val value = anObject?.toString().orEmpty()
            if (hostStrainField.text != value) hostStrainField.text = value
        }
        override fun getItem(): Any = hostStrainField.text
        override fun selectAll() = hostStrainField.selectAll()
        override fun addActionListener(listener: ActionListener) { listeners += listener }
        override fun removeActionListener(listener: ActionListener) { listeners -= listener }
    }

    private fun rememberHostStrain(value: String?) {
        val strain = value?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val suggestions = (listOf(strain) + prefs.value.hostStrainSuggestions)
            .distinctBy { it.lowercase() }
            .take(50)
        prefs.update { it.copy(hostStrainSuggestions = suggestions) }
    }

    private fun hostSuggestionValues(taxonomy: List<String> = doc.seq.recordMetadata.taxonomy): List<String> {
        val metadata = doc.seq.recordMetadata
        val ranked = HostStrainSuggestionEngine.suggest(
            HostStrainSuggestionInput(
                sequenceKind = doc.seq.kind,
                hostType = hostTypeValue(),
                hostStrain = hostStrainField.text,
                organism = metadata.organism,
                taxonomy = taxonomy,
                description = doc.seq.description,
            ),
            limit = 20,
        ).map { it.strain }
        return (listOf("") + ranked + prefs.value.hostStrainSuggestions)
            .distinctBy { it.trim().lowercase() }
    }

    private fun suggestHostsOnFocus() {
        refreshHostStrainSuggestions()
        openHostSuggestions()
        val organism = doc.seq.recordMetadata.organism?.trim().orEmpty()
        val client = ncbiClient
        if (client == null || organism.isBlank() || taxonomyLookupOrganism == organism) return
        taxonomyLookupOrganism = organism
        val input = HostStrainSuggestionInput(
            sequenceKind = doc.seq.kind,
            hostType = hostTypeValue(),
            hostStrain = hostStrainField.text,
            organism = organism,
            taxonomy = doc.seq.recordMetadata.taxonomy,
            description = doc.seq.description,
        )
        val savedHosts = prefs.value.hostStrainSuggestions
        object : SwingWorker<List<String>, Unit>() {
            override fun doInBackground(): List<String> {
                val taxonomy = client.fetchTaxonomy(organism)
                val ranked = HostStrainSuggestionEngine.suggest(
                    input.copy(organism = taxonomy.scientificName, taxonomy = input.taxonomy + taxonomy.lineage),
                    limit = 20,
                ).map { it.strain }
                return (listOf("") + ranked + savedHosts).distinctBy { it.trim().lowercase() }
            }

            override fun done() {
                runCatching { get() }.onSuccess { values ->
                    loadingFields = true
                    try {
                        hostStrainCombo.model = DefaultComboBoxModel(values.toTypedArray())
                        selectHostStrainSuggestion(hostStrainField.text)
                    } finally {
                        loadingFields = false
                    }
                }.onFailure {
                    taxonomyLookupOrganism = null
                    onStatus("NCBI taxonomy lookup failed: ${it.message ?: "unknown error"}")
                }
            }
        }.execute()
    }

    private fun updateOriginLockUi() {
        val natural = originCombo.selectedItem == SequenceOrigin.NATURAL
        originLockRow.isVisible = natural
        originLockCheck.isVisible = natural
        originLockCheck.isEnabled = natural
        if (!natural) {
            originLockCheck.isSelected = false
            if (doc.sequenceEditingLocked) doc.setSequenceEditingLocked(false)
        } else {
            originLockCheck.isSelected = doc.sequenceEditingLocked
        }
        originLockRow.parent?.revalidate()
        originLockRow.parent?.repaint()
    }

    private fun setHostTypeUi(value: String?) {
        val known = labHostTypeSuggestions.any { it.equals(value, ignoreCase = true) }
        if (known) {
            labHostTypeCombo.selectedItem = labHostTypeSuggestions.first { it.equals(value, ignoreCase = true) }
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
            list: JList<*>,
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

    private fun constraints(
        x: Int,
        y: Int,
        weightX: Double = 0.0,
        weightY: Double = 0.0,
        anchor: Int = GridBagConstraints.WEST,
    ): GridBagConstraints =
        GridBagConstraints().apply {
            gridx = x
            gridy = y
            this.weightx = weightX
            this.weighty = weightY
            this.anchor = anchor
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(3, 6, 3, 6)
        }

    private data class SequenceStatsHolder(val seq: Seq, val stats: SequenceStats)

    private data class NcbiSource(val accession: String, val url: String)

    private companion object {
        fun openInBrowser(uri: URI) {
            if (Desktop.isDesktopSupported()) runCatching { Desktop.getDesktop().browse(uri) }
        }
    }
}
