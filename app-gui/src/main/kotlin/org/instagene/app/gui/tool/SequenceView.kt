package org.instagene.app.gui.tool

import org.instagene.app.gui.ContextMenus
import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.edit.SequenceEditService
import org.instagene.app.gui.theme.Palette
import org.instagene.app.gui.theme.ThemeRefreshable
import org.instagene.core.*
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.event.*
import javax.swing.*
import javax.swing.Timer as SwingTimer

/** The sequence canvas uses cached row geometry for rendering and input. */
class SequenceView(initial: SeqDocument) : JComponent(), Scrollable, ThemeRefreshable {
    private var doc = initial
    var interaction: SequenceInteraction? = null
        set(value) {
            field = value
            value?.addListener { selectedFeature = (value.selected as? SequenceObject.Annotation)?.feature; repaint() }
        }
    private var docListener: SeqDocument.Listener? = null
    private var cachedSeqIdentity: Any? = null
    private var cachedGcPct = Double.NaN
    var graphicsMode = SequenceGraphics.DETAILED
        set(value) { field = value; relayout() }
    var showComplement = true
        set(value) { field = value; relayout() }
    var showTranslation = false
        set(value) { field = value; relayout() }
    var showHistoryColors = false
        set(value) { field = value; repaint() }
    var featureLabelMode = FeatureLabelMode.ALL
        set(value) { field = value; relayout() }
    var translationFrame = 0
        set(value) { field = value.coerceIn(0, 2); repaint() }
    var codonTable = CodonTable.STANDARD
        set(value) { field = value; repaint() }
    var primerPreview: List<PrimerAnnotation> = emptyList()
        set(value) { field = value; relayout() }
    var previewRange: Pair<Int, Int>? = null
        set(value) { field = value; repaint() }
    private var preferenceFont = UIManager.getFont("InstaGene.sequenceFont") ?: Font(Font.MONOSPACED, Font.PLAIN, 14)
    private var baseFont = preferenceFont
    private val labelFont get() = (UIManager.getFont("Label.font") ?: Font(Font.SANS_SERIF, Font.PLAIN, 12)).deriveFont(maxOf(12f, baseFont.size2D - 2))
    private var caretVisible = true
    private val caretBlinkTimer = SwingTimer(530) { caretVisible = !caretVisible; repaint() }
    private var geometry: SequenceLayout? = null
    private val layoutModel get() = geometry ?: createLayout().also { geometry = it }
    private val basesPerLine get() = layoutModel.columns
    private var lastPaintedRowCount = 0
    private val paintedCutSiteLabels = mutableListOf<String>()
    private data class Hit(val bounds: Rectangle, val item: SequenceObject, val label: Boolean = false)
    private val hits = mutableListOf<Hit>()
    private val overflow = mutableListOf<Pair<Rectangle, List<SequenceObject>>>()
    private var hover: SequenceObject? = null
    private var draggingBases = false
    var selectedFeature: Feature? = null
        private set

    init {
        isOpaque = true
        background = Palette.BACKGROUND
        isFocusable = true
        cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
        ToolTipManager.sharedInstance().registerComponent(this)
        caretBlinkTimer.start()
        installMouseHandlers()
        installKeyHandlers()
        bindDocument(doc)
    }
    fun bindDocument(newDoc: SeqDocument) {
        if (newDoc !== doc) {
            docListener?.let { doc.removeListener(it) }
            doc = newDoc
            selectedFeature = null
            primerPreview = emptyList()
            previewRange = null
            docListener?.let { doc.addListener(it) }
        }
        if (docListener == null) {
            docListener = SeqDocument.Listener { _, reason ->
                if (reason == SeqDocument.Reason.SEQUENCE || reason == SeqDocument.Reason.ENZYMES) {
                    if (selectedFeature !in doc.seq.features) selectedFeature = null
                    relayout()
                } else repaint()
                if (reason == SeqDocument.Reason.SELECTION && isShowing && isFocusOwner) scrollCaretIntoView()
            }
            doc.addListener(docListener!!)
        }
        relayout()
    }
    fun dispose() {
        caretBlinkTimer.stop()
        docListener?.let { doc.removeListener(it) }
        docListener = null
        ToolTipManager.sharedInstance().unregisterComponent(this)
    }
    override fun updateUI() { super.updateUI(); background = Palette.BACKGROUND }
    override fun refreshTheme() {
        background = Palette.BACKGROUND
        val next = UIManager.getFont("InstaGene.sequenceFont") ?: Font(Font.MONOSPACED, Font.PLAIN, 14)
        if (next != preferenceFont) { preferenceFont = next; baseFont = next }
        relayout()
    }
    fun setFontSize(points: Int) { baseFont = baseFont.deriveFont(points.coerceIn(8, 32).toFloat()); relayout() }
    fun resetZoom() { baseFont = preferenceFont; relayout() }
    fun fontSize() = baseFont.size
    private fun complementTrack() = showComplement && doc.seq.kind != SeqKind.PROTEIN
    private fun translationTrack() = showTranslation && doc.seq.kind != SeqKind.PROTEIN
    private fun createLayout(): SequenceLayout {
        val fm = getFontMetrics(baseFont)
        val cw = maxOf(1, fm.charWidth('A'))
        val gutter = cw * (maxOf(6, "%,d".format(doc.seq.length).length) + 2)
        val columns = ((((width.takeIf { it > 0 } ?: 900) - gutter - 20) / cw) / 10 * 10).coerceIn(10, 240)
        val primers = if (doc.seq.kind == SeqKind.PROTEIN) emptyList() else doc.seq.primers.filter { it.visible }
        val objects = buildList<SequenceObject> {
            doc.seq.features.filter { f ->
                f.visible && FeatureLabelOptions.include(f, featureLabelMode) &&
                    !(f.type == "primer_bind" && primers.any { it.name == f.name && it.bindingStart == f.start && it.bindingEnd == f.end })
            }.sortedBy { it.displayOrder }.forEach { add(SequenceObject.Annotation(it)) }
            primers.forEach { add(SequenceObject.Primer(it)) }
            primerPreview.filter { preview -> primers.none { it == preview } }.forEach { add(SequenceObject.Primer(it, true)) }
            doc.cutSites.forEach { add(SequenceObject.Site(it)) }
            (interaction?.selected as? SequenceObject.Site)?.let { if (it !in this) add(it) }
        }
        return SequenceLayout(doc.seq.length, columns, cw, fm.height, gutter,
            1 + (if (complementTrack()) 1 else 0) + (if (translationTrack()) 1 else 0),
            if (graphicsMode == SequenceGraphics.DETAILED) getFontMetrics(labelFont).height + 8 else 10,
            graphicsMode == SequenceGraphics.DETAILED, objects)
    }
    private var layoutVersion = 0
    private fun relayout() {
        val anchor = geometry?.rowAt(visibleRect.y)?.let { it.index * (geometry?.columns ?: 60) }
        geometry = createLayout()
        hits.clear(); overflow.clear()
        revalidate(); repaint()
        val version = ++layoutVersion
        if (anchor != null && isShowing) SwingUtilities.invokeLater {
            if (version == layoutVersion) scrollRectToVisible(Rectangle(0, layoutModel.rowFor(anchor).top, 1, visibleRect.height))
        }
    }
    override fun setBounds(x: Int, y: Int, w: Int, h: Int) {
        val changed = w != width
        super.setBounds(x, y, w, h)
        if (changed) relayout()
    }
    override fun getPreferredSize() = Dimension(layoutModel.x(layoutModel.columns) + 10, layoutModel.height)
    fun xCoordinate(column: Int) = layoutModel.x(column)
    fun indexAt(px: Int, py: Int) = layoutModel.indexAt(px, py)
    private fun rowHeight() = layoutModel.rowAt(visibleRect.y).height
    fun lastViewportPaintedRowCount() = lastPaintedRowCount
    fun cutSiteLabelsForTest(): List<String> = paintedCutSiteLabels.toList()
    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.color = background
            g2.fillRect(0, 0, width, height)
            hits.clear(); overflow.clear(); paintedCutSiteLabels.clear()
            val layout = layoutModel
            if (doc.seq.length == 0) {
                lastPaintedRowCount = 0
                g2.font = baseFont; g2.color = Palette.MUTED
                g2.drawString("Empty sequence — type bases, or use File > Open.", 14, 30)
                return
            }
            val clip = g2.clipBounds ?: Rectangle(0, 0, width, height)
            val first = layout.rowAt(clip.y).index
            val last = layout.rowAt(clip.y + clip.height).index
            lastPaintedRowCount = last - first + 1
            for (r in first..last) paintRow(g2, layout.rows[r])
            if (!doc.hasSelection && caretVisible && isFocusOwner) {
                val row = layout.rowFor(doc.caret)
                val x = layout.x(doc.caret - row.index * layout.columns)
                g2.color = Palette.CARET
                g2.drawLine(x, row.top + row.marks, x, row.top + row.marks + layout.textHeight * layout.tracks)
            }
        } finally { g2.dispose() }
    }
    private fun paintRow(g: Graphics2D, row: SequenceLayout.Row) {
        val l = layoutModel
        val from = row.index * l.columns
        val to = minOf(doc.seq.length, from + l.columns)
        val top = row.top + row.marks
        val fm = getFontMetrics(baseFont)
        val baseY = top + fm.ascent
        if (l.detailed) {
            g.color = if (l.rowFor(doc.caret).index == row.index) Palette.EDITOR_ACTIVE_ROW else Palette.EDITOR_ROW_ALT
            g.fillRect(l.x(0) - 2, top, (to - from) * l.cellWidth + 4, l.tracks * l.textHeight)
        }
        fun shade(start: Int, end: Int, color: Color) {
            val s = maxOf(start, from); val e = minOf(end, to)
            if (e > s) { g.color = color; g.fillRect(l.x(s - from), top, (e - s) * l.cellWidth, l.tracks * l.textHeight) }
        }
        if (showHistoryColors) doc.recentChangeRange?.let { shade(it.first, it.last + 1, Palette.translucent(Palette.ACCENT, 35)) }
        previewRange?.let { shade(it.first, it.second, Palette.translucent(Palette.ACCENT, 24)) }
        interaction?.selected?.let { selected ->
            shade(selected.start, minOf(selected.end, doc.seq.length), Palette.translucent(Palette.ACCENT, 38))
            if (selected.end > doc.seq.length) shade(0, selected.end - doc.seq.length, Palette.translucent(Palette.ACCENT, 38))
        }
        if (doc.hasSelection) shade(doc.selectionStart, doc.selectionEnd, Palette.SELECTION)
        g.font = baseFont; g.color = Palette.GUTTER
        g.drawString("%,d".format(from + 1), 10, baseY)
        if (l.detailed && complementTrack()) {
            g.font = labelFont
            g.drawString("5′ → 3′", 10, baseY + l.textHeight)
        }
        g.color = Palette.GRID
        for (col in 10 until to - from step 10) g.drawLine(l.x(col), top, l.x(col), top + l.tracks * l.textHeight)
        g.font = baseFont
        for (i in from until to) {
            g.color = Palette.charColor(doc.seq.bases[i], doc.seq.kind)
            g.drawString(doc.seq.bases[i].uppercaseChar().toString(), l.x(i - from), baseY)
            if (complementTrack()) {
                g.color = Palette.MUTED
                g.drawString(Alphabet.complement(doc.seq.bases[i], doc.seq.kind).uppercaseChar().toString(), l.x(i - from), baseY + l.textHeight)
            }
        }
        if (translationTrack()) {
            val y = baseY + (l.tracks - 1) * l.textHeight
            var c = maxOf(translationFrame, translationFrame + Math.floorDiv(from - translationFrame, 3) * 3)
            while (c < to && c + 3 <= doc.seq.length) {
                val codon = doc.seq.bases.substring(c, c + 3).uppercase()
                val aa = codonTable.translate(codon)
                if (aa == '*' || codon == "ATG") {
                    g.color = if (aa == '*') Palette.STOP_CODON else Palette.START_CODON
                    g.fillRect(l.x(maxOf(c, from) - from), y - fm.ascent, (minOf(c + 3, to) - maxOf(c, from)) * l.cellWidth, l.textHeight)
                }
                if (c + 1 in from until to) { g.color = Palette.TEXT; g.drawString(aa.toString(), l.x(c + 1 - from), y) }
                c += 3
            }
        }
        paintSites(g, row)
        g.font = labelFont
        row.bars.forEach { bar ->
            val bounds = l.bounds(row, bar)
            val item = bar.item
            val selected = item == interaction?.selected || (item is SequenceObject.Annotation && item.feature == selectedFeature)
            val color = when (item) {
                is SequenceObject.Annotation -> item.feature.color?.let { runCatching { Color.decode(it) }.getOrNull() }
                    ?: Palette.featureColor(item.feature.name.hashCode())
                is SequenceObject.Primer -> if (item.primer.strand == Strand.REVERSE) Palette.CUT_MARK else Palette.ACCENT
                else -> Palette.ACCENT
            }
            g.color = Palette.translucent(color, if (selected) 90 else 38)
            g.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 5, 5)
            g.color = if (selected || item == hover) Palette.ACCENT else color
            val originalStroke = g.stroke
            if (item is SequenceObject.Primer && item.preview) g.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(4f, 3f), 0f)
            g.drawRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 5, 5)
            g.stroke = originalStroke
            val strand = when (item) { is SequenceObject.Annotation -> item.feature.strand; is SequenceObject.Primer -> item.primer.strand; else -> Strand.FORWARD }
            val arrow = minOf(6, bounds.width / 2)
            val mid = bounds.y + bounds.height / 2
            if (strand == Strand.FORWARD && bar.end == item.end) {
                g.fillPolygon(intArrayOf(bounds.x + bounds.width - arrow, bounds.x + bounds.width, bounds.x + bounds.width - arrow), intArrayOf(bounds.y, mid, bounds.y + bounds.height), 3)
            } else if (strand == Strand.REVERSE && bar.start == item.start) {
                g.fillPolygon(intArrayOf(bounds.x + arrow, bounds.x, bounds.x + arrow), intArrayOf(bounds.y, mid, bounds.y + bounds.height), 3)
            }
            hits += Hit(bounds, item)
            if (l.detailed && bounds.width > 24) {
                val metrics = g.fontMetrics
                val available = bounds.width - 18
                var text = item.name
                while (text.isNotEmpty() && metrics.stringWidth(text) > available) text = text.dropLast(1)
                if (text != item.name && text.length > 1) text = text.dropLast(1) + "…"
                g.color = Palette.TEXT
                val labelBounds = Rectangle(bounds.x + 8, bounds.y + 2, minOf(available, metrics.stringWidth(text)), bounds.height - 4)
                g.drawString(text, labelBounds.x, bounds.y + (bounds.height - metrics.height) / 2 + metrics.ascent)
                hits += Hit(labelBounds, item, true)
            }
        }
        g.color = Palette.GRID
        g.drawLine(l.x(0), row.top + row.height - 7, width - 10, row.top + row.height - 7)
    }
    private fun paintSites(g: Graphics2D, row: SequenceLayout.Row) {
        val l = layoutModel
        val from = row.index * l.columns
        val occupied = mutableListOf<Rectangle>()
        val hidden = mutableListOf<SequenceObject>()
        g.font = labelFont
        val metrics = g.fontMetrics
        row.sites.sortedByDescending { it == interaction?.selected }.forEach { item ->
            val x = l.x(item.site.topCut - from)
            val y = row.top + row.marks
            g.color = if (item == interaction?.selected) Palette.ACCENT else Palette.CUT_MARK
            g.drawLine(x, y - 8, x, y)
            hits += Hit(Rectangle(x - 4, y - 9, 9, 10), item)
            if (l.detailed || item == interaction?.selected || item == hover) {
                val textWidth = metrics.stringWidth(item.name)
                val lx = (x + 4).coerceAtMost(maxOf(l.x(0), width - textWidth - 65)).coerceAtLeast(l.x(0))
                val bounds = (0..1).map { lane -> Rectangle(lx, row.top + lane * l.laneHeight, textWidth + 6, metrics.height + 2) }
                    .firstOrNull { candidate -> occupied.none { it.intersects(candidate) } }
                if (bounds == null || !l.detailed) hidden += item else {
                    occupied += bounds
                    g.color = Palette.TEXT
                    g.drawString(item.name, bounds.x + 3, bounds.y + metrics.ascent)
                    hits += Hit(bounds, item, true)
                    paintedCutSiteLabels += item.name
                }
            }
            if (l.detailed && item == interaction?.selected) {
                g.color = Palette.ACCENT
                val bottom = item.site.bottomCut
                if (bottom in from..minOf(from + l.columns, doc.seq.length)) {
                    val bx = l.x(bottom - from)
                    g.drawLine(bx, y + l.textHeight, bx, y + l.textHeight + 5)
                }
            }
        }
        if (hidden.isNotEmpty()) {
            val bounds = Rectangle(maxOf(l.x(0), width - 62), row.top, 52, metrics.height + 4)
            g.color = Palette.ACCENT
            g.drawString("+${hidden.size} sites", bounds.x, bounds.y + metrics.ascent)
            overflow += bounds to hidden
        }
    }
    fun revealRange(start: Int, end: Int) { doc.select(start, end); revealObjectRange(start, end); requestFocusInWindow() }
    fun revealObjectRange(start: Int, end: Int) {
        val row = layoutModel.rowFor(start)
        scrollRectToVisible(Rectangle(0, maxOf(0, row.top - 10), maxOf(1, width), minOf(row.height * 2, maxOf(1, visibleRect.height))))
    }
    private fun scrollCaretIntoView() { val row = layoutModel.rowFor(doc.caret); scrollRectToVisible(Rectangle(0, row.top, 1, row.height)) }
    fun selectFeature(feature: Feature) { selectedFeature = feature; interaction?.select(SequenceObject.Annotation(feature)); repaint() }
    fun clearFeatureSelection() { selectedFeature = null; interaction?.select(null); repaint() }
    fun refreshAnnotations() = relayout()
    private fun hitAt(x: Int, y: Int) = hits.lastOrNull { it.bounds.contains(x, y) }
    fun featureHitCenterForTest(name: String) = center(name, null)
    fun featureLabelHitCenterForTest(name: String) = center(name, true)
    fun featureBarHitCenterForTest(name: String) = center(name, false)
    private fun center(name: String, label: Boolean?): Pair<Int, Int>? {
        val hit = hits.firstOrNull { it.item is SequenceObject.Annotation && it.item.name == name && (label == null || it.label == label) } ?: return null
        return hit.bounds.x + hit.bounds.width / 2 to hit.bounds.y + hit.bounds.height / 2
    }
    private fun installMouseHandlers() {
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) { popup(e); return }
                if (!SwingUtilities.isLeftMouseButton(e)) return
                requestFocusInWindow()
                overflow.firstOrNull { it.first.contains(e.point) }?.let { entry ->
                    val menu = JPopupMenu()
                    entry.second.forEach { item -> menu.add(JMenuItem("${item.name} (${item.start + 1}–${item.end})").apply { addActionListener { interaction?.select(item); repaint() } }) }
                    menu.show(this@SequenceView, e.x, e.y)
                    return
                }
                val hit = hitAt(e.x, e.y)
                draggingBases = hit == null
                if (hit != null) {
                    selectedFeature = (hit.item as? SequenceObject.Annotation)?.feature
                    interaction?.select(hit.item)
                    if (e.clickCount == 2) interaction?.openSelected()
                    repaint()
                } else {
                    clearFeatureSelection()
                    doc.moveCaret(indexAt(e.x, e.y), e.isShiftDown)
                }
            }
            override fun mouseReleased(e: MouseEvent) { draggingBases = false; if (e.isPopupTrigger) popup(e) }
        })
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) { if (draggingBases) doc.moveCaret(indexAt(e.x, e.y), true) }
            override fun mouseMoved(e: MouseEvent) {
                val next = hitAt(e.x, e.y)?.item
                if (hover != next) { hover = next; repaint() }
                cursor = Cursor.getPredefinedCursor(if (next == null) Cursor.TEXT_CURSOR else Cursor.HAND_CURSOR)
            }
        })
    }
    private fun popup(e: MouseEvent) {
        val item = hitAt(e.x, e.y)?.item ?: return
        interaction?.select(item)
        JPopupMenu().apply {
            add(JMenuItem("Open in ${item.tab}").apply { addActionListener { interaction?.openSelected() } })
            add(JMenuItem("Select bases").apply { addActionListener { if (interaction != null) interaction?.selectBases() else doc.select(item.start, item.end) } })
        }.show(this, e.x, e.y)
    }
    override fun getToolTipText(event: MouseEvent): String {
        val item = hitAt(event.x, event.y)?.item
        return when (item) {
            is SequenceObject.Site -> "${item.name}: ${item.site.enzyme.notation()} · top ${item.site.topCut}, bottom ${item.site.bottomCut}"
            is SequenceObject.Annotation -> "${item.name} · ${item.feature.type} · ${item.feature.displayRange()} · ${item.feature.strand.symbol}"
            is SequenceObject.Primer -> "${if (item.preview) "Preview: " else ""}${item.name} · ${item.start + 1}–${item.end} · ${item.primer.strand.symbol} · ${item.primer.fullSequence}"
            null -> "Position ${(indexAt(event.x, event.y) + 1).coerceAtMost(maxOf(1, doc.seq.length))}"
        }
    }
    private fun installKeyHandlers() {
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) = handleKeyPressed(e)
            override fun keyTyped(e: KeyEvent) = handleKeyTyped(e)
        })
    }

    private fun handleKeyPressed(e: KeyEvent) {
        val shift = e.isShiftDown
        val menuMask = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        val withMenu = (e.modifiersEx and menuMask) != 0
        when {
            withMenu && e.keyCode == KeyEvent.VK_A -> doc.selectAll()
            withMenu && e.keyCode == KeyEvent.VK_C -> copySelection()
            withMenu && e.keyCode == KeyEvent.VK_X -> {
                copySelection(); deleteSelection()
            }

            withMenu && e.keyCode == KeyEvent.VK_V -> paste()
            e.keyCode == KeyEvent.VK_LEFT -> doc.moveCaret(doc.caret - 1, shift)
            e.keyCode == KeyEvent.VK_RIGHT -> doc.moveCaret(doc.caret + 1, shift)
            e.keyCode == KeyEvent.VK_UP -> doc.moveCaret(doc.caret - basesPerLine, shift)
            e.keyCode == KeyEvent.VK_DOWN -> doc.moveCaret(doc.caret + basesPerLine, shift)
            e.keyCode == KeyEvent.VK_HOME -> doc.moveCaret(doc.caret / basesPerLine * basesPerLine, shift)
            e.keyCode == KeyEvent.VK_END ->
                doc.moveCaret((doc.caret / basesPerLine + 1) * basesPerLine - 1, shift)

            e.keyCode == KeyEvent.VK_PAGE_UP -> doc.moveCaret(doc.caret - basesPerLine * 10, shift)
            e.keyCode == KeyEvent.VK_PAGE_DOWN -> doc.moveCaret(doc.caret + basesPerLine * 10, shift)
            e.keyCode == KeyEvent.VK_BACK_SPACE -> {
                if (doc.hasSelection) deleteSelection() else if (doc.caret > 0) {
                    val at = doc.caret
                    if (doc.mutate("delete base") { it.deleteRange(at - 1, at) }) {
                        doc.moveCaret(at - 1)
                    }
                }
            }

            e.keyCode == KeyEvent.VK_DELETE -> {
                if (doc.hasSelection) deleteSelection() else if (doc.caret < doc.seq.length) {
                    val at = doc.caret
                    doc.mutate("delete base") { it.deleteRange(at, at + 1) }
                }
            }

            else -> return
        }
        e.consume()
    }

    private fun handleKeyTyped(e: KeyEvent) {
        if (e.isControlDown || e.isMetaDown || e.isAltDown) return
        val c = e.keyChar.uppercaseChar()
        val valid = if (doc.seq.kind == SeqKind.PROTEIN) {
            Alphabet.isAminoAcid(c) && c != '-' && c != '*'
        } else {
            Alphabet.isNucleotide(c) && c != '-'
        }
        if (!valid) return
        insertBases(c.toString())
        e.consume()
    }

    /** Types [text] into the document, replacing the selection when there is one, otherwise inserting at the caret. */
    fun insertBases(text: String) {
        SequenceEditService.insert(doc, text)
    }

    /** Deletes the current selection, if any. */
    fun deleteSelection() {
        SequenceEditService.deleteSelection(doc)
    }

    /** Copies the selection to the system clipboard, or the whole sequence when nothing is selected. */
    fun copySelection() {
        val text = if (doc.hasSelection) doc.selectedBases else doc.seq.bases
        ContextMenus.copyToClipboard(text)
    }

    /** Pastes text from the system clipboard at the caret (via [insertBases]). */
    fun paste() {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val text = runCatching { clipboard.getData(DataFlavor.stringFlavor) as? String }.getOrNull() ?: return
        insertBases(text)
    }

    /** One-line summary for the window's status bar. */
    fun statusText(): String {
        val seq = doc.seq
        val unit = if (seq.kind == SeqKind.PROTEIN) "aa" else "bp"
        return buildString {
            append("${seq.length} $unit  ${seq.kind.name.lowercase()}  ${seq.topology.name.lowercase()}")
            if (seq.kind != SeqKind.PROTEIN) {
                val gc = if (seq.bases === cachedSeqIdentity) cachedGcPct else {
                    SeqOps.gcContent(seq).also { cachedGcPct = it; cachedSeqIdentity = seq.bases }
                }
                append("   GC ${"%.1f".format(gc)}%")
            }
            if (doc.hasSelection) {
                val sel = doc.selectedBases
                append("   |  Selected range ${doc.selectionStart + 1}–${doc.selectionEnd}")
                append(" (${sel.length} $unit")
                if (seq.kind != SeqKind.PROTEIN) {
                    append(", GC ${"%.1f".format(SeqOps.gcContent(sel))}%")
                    if (sel.length in 1..60) append(", Tm ${"%.1f".format(SeqOps.meltingTemp(sel))} C")
                }
                append(")")
            } else {
                append("   |  Caret ${doc.caret + 1}")
            }
            if (doc.cutSites.isNotEmpty()) append("   |  ${doc.cutSites.size} cut site(s)")
        }
    }

    // ------------------------------------------------------------- Scrollable

    override fun getPreferredScrollableViewportSize(): Dimension = Dimension(900, 600)

    override fun getScrollableUnitIncrement(r: Rectangle, orientation: Int, direction: Int): Int = rowHeight()

    override fun getScrollableBlockIncrement(r: Rectangle, orientation: Int, direction: Int): Int =
        rowHeight() * 5

    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun getScrollableTracksViewportHeight(): Boolean = false
}

/**
 * Greedy interval packing so overlapping [features] each get their own lane,
 * recording the lane of every feature in [laneOf]. Returns the number of lanes
 * used (shared by the sequence track bars and the plasmid map's rings).
 */
internal fun packLanes(features: List<Feature>, laneOf: MutableMap<Feature, Int>): Int {
    laneOf.clear()
    val ends = ArrayList<Int>()
    for (f in features.sortedBy { it.start }) {
        var lane = ends.indexOfFirst { it <= f.start }
        if (lane < 0) {
            lane = ends.size
            ends += f.end
        } else {
            ends[lane] = f.end
        }
        laneOf[f] = lane
    }
    return ends.size
}
