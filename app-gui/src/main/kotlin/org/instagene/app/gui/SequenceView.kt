package org.instagene.app.gui

import org.instagene.core.Alphabet
import org.instagene.core.CodonTable
import org.instagene.core.CutSite
import org.instagene.core.Feature
import org.instagene.core.SeqKind
import org.instagene.core.SeqOps
import org.instagene.core.Strand
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.JComponent
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager

/**
 * The sequence editor proper: a directly painted base grid with a position
 * gutter, per-base colouring, feature bars, restriction-site marks and optional
 * complement and translation tracks.
 *
 * Painting by hand (rather than driving a JTextPane) keeps the on-screen
 * coordinates and the [org.instagene.core.Seq] coordinates identical, which is
 * what every other panel needs in order to highlight the same region.
 */
class SequenceView(private val doc: SeqDocument) : JComponent(), Scrollable {

    var showComplement: Boolean = true
        set(value) {
            field = value; relayout()
        }

    var showTranslation: Boolean = false
        set(value) {
            field = value; relayout()
        }

    var translationFrame: Int = 0
        set(value) {
            field = value; repaint()
        }

    var codonTable: CodonTable = CodonTable.STANDARD
        set(value) {
            field = value; repaint()
        }

    private var baseFont = Font(Font.MONOSPACED, Font.PLAIN, 14)
    private val labelFont = Font(Font.SANS_SERIF, Font.PLAIN, 10)

    private var charWidth = 9
    private var lineHeight = 17
    private var gutterWidth = 80
    private var basesPerLine = 60
    private var featureLanes = 0
    private val laneOf = HashMap<Feature, Int>()
    private var runBuffer = CharArray(0)

    private fun runBuffer(): CharArray {
        if (runBuffer.size < basesPerLine) runBuffer = CharArray(basesPerLine)
        return runBuffer
    }

    private val padding = 10
    private val markHeight = 14
    private val laneHeight = 8
    private val rowGap = 10

    init {
        isOpaque = true
        background = Palette.BACKGROUND
        isFocusable = true
        cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
        ToolTipManager.sharedInstance().registerComponent(this)
        installMouseHandlers()
        installKeyHandlers()
        doc.addListener { _, reason ->
            if (reason == SeqDocument.Reason.SEQUENCE) relayout() else repaint()
            if (reason == SeqDocument.Reason.SELECTION) scrollCaretIntoView()
        }
        relayout()
    }

    /** Re-picks the theme background when the look-and-feel changes. */
    override fun updateUI() {
        super.updateUI()
        background = Palette.BACKGROUND
    }

    /** Sets the base-grid font size in points (clamped to 8..28) and re-lays the view out. */
    fun setFontSize(points: Int) {
        baseFont = Font(Font.MONOSPACED, Font.PLAIN, points.coerceIn(8, 28))
        relayout()
    }

    /** The current base-grid font size in points. */
    fun fontSize(): Int = baseFont.size

    // ------------------------------------------------------------------ layout

    private fun relayout() {
        val fm = getFontMetrics(baseFont)
        charWidth = maxOf(1, fm.charWidth('A'))
        lineHeight = fm.height
        gutterWidth = charWidth * (maxOf(8, "%,d".format(doc.seq.length).length) + 2)
        val usable = (width.takeIf { it > 0 } ?: 900) - gutterWidth - padding * 2
        basesPerLine = ((usable / charWidth) / 10 * 10).coerceIn(10, 240)
        assignFeatureLanes()
        revalidate()
        repaint()
    }

    override fun setBounds(x: Int, y: Int, w: Int, h: Int) {
        val changed = w != width
        super.setBounds(x, y, w, h)
        if (changed) relayout()
    }

    /** Greedy interval packing so overlapping features get their own row of bars. */
    private fun assignFeatureLanes() {
        laneOf.clear()
        val ends = ArrayList<Int>()
        for (f in doc.seq.features.sortedBy { it.start }) {
            var lane = ends.indexOfFirst { it <= f.start }
            if (lane < 0) {
                lane = ends.size
                ends += f.end
            } else {
                ends[lane] = f.end
            }
            laneOf[f] = lane
        }
        featureLanes = ends.size
    }

    /** The complement track only makes sense for nucleotide sequences. */
    private fun complementTrack(): Boolean = showComplement && doc.seq.kind != SeqKind.PROTEIN

    /** Translation likewise needs codons; a protein sequence has none. */
    private fun translationTrack(): Boolean = showTranslation && doc.seq.kind != SeqKind.PROTEIN

    private fun trackCount(): Int = 1 + (if (complementTrack()) 1 else 0) + (if (translationTrack()) 1 else 0)

    private fun rowHeight(): Int =
        markHeight + lineHeight * trackCount() + featureLanes * laneHeight + rowGap

    private fun rowCount(): Int =
        maxOf(1, (doc.seq.length + basesPerLine - 1) / basesPerLine)

    override fun getPreferredSize(): Dimension =
        Dimension(gutterWidth + basesPerLine * charWidth + padding * 2, rowCount() * rowHeight() + padding * 2)

    private fun xOf(column: Int) = padding + gutterWidth + column * charWidth
    private fun yOfRow(row: Int) = padding + row * rowHeight()

    /** Exposed for tests: the x-coordinate at which [column] is painted. */
    fun xCoordinate(column: Int): Int = xOf(column)

    /** Sequence index under a point, clamped to the sequence. */
    fun indexAt(px: Int, py: Int): Int {
        val row = ((py - padding) / rowHeight()).coerceIn(0, rowCount() - 1)
        val col = ((px - padding - gutterWidth + charWidth / 2) / charWidth).coerceIn(0, basesPerLine)
        return (row * basesPerLine + col).coerceIn(0, doc.seq.length)
    }

    // ---------------------------------------------------------------- painting

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.color = background
        g2.fillRect(0, 0, width, height)

        val seq = doc.seq
        if (seq.length == 0) {
            g2.color = Palette.MUTED
            g2.font = baseFont
            g2.drawString("Empty sequence - type bases, or use File > Open.", padding + 4, padding + lineHeight)
            return
        }

        val clip = g2.clipBounds ?: Rectangle(0, 0, width, height)
        val firstRow = ((clip.y - padding) / rowHeight()).coerceAtLeast(0)
        val lastRow = ((clip.y + clip.height - padding) / rowHeight()).coerceAtMost(rowCount() - 1)

        for (row in firstRow..lastRow) paintRow(g2, row)
        paintCaret(g2)
    }

    private fun paintRow(g2: Graphics2D, row: Int) {
        val seq = doc.seq
        val from = row * basesPerLine
        val to = minOf(from + basesPerLine, seq.length)
        if (from >= to) return
        val top = yOfRow(row)
        val baseY = top + markHeight + lineHeight - getFontMetrics(baseFont).descent

        paintSelection(g2, from, to, top)
        paintCutMarks(g2, from, to, top)

        // Position gutter.
        g2.font = baseFont
        g2.color = Palette.GUTTER
        g2.drawString("%,8d".format(from + 1), padding, baseY)

        // Top strand, coloured per base, with a faint decade grid. Consecutive
        // same-colour bases are batched into a single drawChars run so a visible
        // row costs a handful of text calls instead of one per base. Grid lines
        // are drawn first so the batched glyphs land on top of them, matching the
        // original per-column paint order.
        if (to - from >= 10) {
            g2.color = Palette.GRID
            var col = 10
            while (col < to - from) {
                val x = xOf(col)
                g2.drawLine(x, top + markHeight, x, top + markHeight + lineHeight * trackCount())
                col += 10
            }
        }
        val buffer = runBuffer()
        var runStart = 0
        var runColor: Color = Palette.charColor(seq.bases[from], seq.kind)
        for (i in from until to) {
            val col = i - from
            val color = Palette.charColor(seq.bases[i], seq.kind)
            if (color != runColor) {
                g2.color = runColor
                g2.drawChars(buffer, runStart, col - runStart, xOf(runStart), baseY)
                runColor = color
                runStart = col
            }
            buffer[col] = seq.bases[i].uppercaseChar()
        }
        g2.color = runColor
        g2.drawChars(buffer, runStart, to - from - runStart, xOf(runStart), baseY)

        var trackY = baseY
        if (complementTrack()) {
            trackY += lineHeight
            for (i in from until to) buffer[i - from] = Alphabet.complement(seq.bases[i], seq.kind).uppercaseChar()
            g2.color = Palette.MUTED
            g2.drawChars(buffer, 0, to - from, xOf(0), trackY)
        }
        if (translationTrack()) {
            trackY += lineHeight
            paintTranslation(g2, from, to, trackY)
        }

        paintFeatures(g2, from, to, top + markHeight + lineHeight * trackCount())
    }

    private fun paintTranslation(g2: Graphics2D, from: Int, to: Int, y: Int) {
        val seq = doc.seq
        g2.color = Palette.TEXT
        // Amino acids are centred on the middle base of each codon.
        var codonStart = translationFrame + ((from - translationFrame) / 3) * 3
        if (codonStart < translationFrame) codonStart = translationFrame
        while (codonStart + 3 <= seq.length) {
            if (codonStart >= to) break
            val aa = codonTable.translate(seq.bases.substring(codonStart, codonStart + 3))
            val middle = codonStart + 1
            if (middle in from until to) {
                g2.color = if (aa == '*') Palette.CUT_MARK else Palette.TEXT
                g2.drawString(aa.toString(), xOf(middle - from), y)
            }
            codonStart += 3
        }
    }

    private fun paintSelection(g2: Graphics2D, from: Int, to: Int, top: Int) {
        if (!doc.hasSelection) return
        val s = maxOf(doc.selectionStart, from)
        val e = minOf(doc.selectionEnd, to)
        if (e <= s) return
        g2.color = Palette.SELECTION
        g2.fillRect(
            xOf(s - from),
            top + markHeight,
            (e - s) * charWidth,
            lineHeight * trackCount(),
        )
    }

    private fun paintCutMarks(g2: Graphics2D, from: Int, to: Int, top: Int) {
        val cutSites = doc.cutSites
        if (cutSites.isEmpty()) return
        g2.font = labelFont
        val fm = getFontMetrics(labelFont)
        var lastLabelEnd = -1
        // cutSites is already sorted by topCut; jump straight to the first site
        // in the visible window instead of scanning (and re-sorting) the whole
        // list for every painted row.
        var i = lowerBound(cutSites, from)
        while (i < cutSites.size && cutSites[i].topCut < to) {
            val site = cutSites[i]
            val pos = site.topCut
            val x = xOf(pos - from)
            g2.color = Palette.CUT_MARK
            g2.drawLine(x, top + 2, x, top + markHeight)
            g2.fillPolygon(intArrayOf(x - 3, x + 3, x), intArrayOf(top + 2, top + 2, top + 7), 3)
            // Only label a site when the previous label has cleared the space.
            if (x > lastLabelEnd + 4) {
                val label = site.enzyme.name
                g2.drawString(label, x + 4, top + markHeight - 3)
                lastLabelEnd = x + 4 + fm.stringWidth(label)
            }
            i++
        }
    }

    /** Index of the first site whose topCut is >= [pos], assuming topCut-sorted input. */
    private fun lowerBound(cutSites: List<CutSite>, pos: Int): Int {
        var lo = 0
        var hi = cutSites.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cutSites[mid].topCut < pos) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun paintFeatures(g2: Graphics2D, from: Int, to: Int, top: Int) {
        if (doc.seq.features.isEmpty()) return
        g2.font = labelFont
        val fm = getFontMetrics(labelFont)
        doc.seq.features.forEachIndexed { index, f ->
            val s = maxOf(f.start, from)
            val e = minOf(f.end, to)
            if (e <= s) return@forEachIndexed
            val lane = laneOf[f] ?: 0
            val y = top + lane * laneHeight + 1
            val x = xOf(s - from)
            val w = maxOf(3, (e - s) * charWidth)
            val color = Palette.featureColor(index)
            g2.color = Palette.translucent(color, 0x66)
            g2.fillRoundRect(x, y, w, laneHeight - 2, 4, 4)
            g2.color = color
            g2.drawRoundRect(x, y, w, laneHeight - 2, 4, 4)
            // Strand arrowhead at the leading edge.
            if (f.strand == Strand.FORWARD && f.end in s..e) {
                g2.fillPolygon(
                    intArrayOf(x + w, x + w, x + w + 4),
                    intArrayOf(y, y + laneHeight - 2, y + (laneHeight - 2) / 2),
                    3,
                )
            } else if (f.strand == Strand.REVERSE && f.start in s..e) {
                g2.fillPolygon(
                    intArrayOf(x, x, x - 4),
                    intArrayOf(y, y + laneHeight - 2, y + (laneHeight - 2) / 2),
                    3,
                )
            }
            if (w > fm.stringWidth(f.name) + 8) {
                g2.color = Palette.TEXT
                g2.drawString(f.name, x + 4, y + laneHeight - 3)
            }
        }
    }

    private fun paintCaret(g2: Graphics2D) {
        if (doc.hasSelection) return
        val row = doc.caret / basesPerLine
        val col = doc.caret % basesPerLine
        val top = yOfRow(row) + markHeight
        g2.color = Palette.CARET
        val x = xOf(col)
        g2.drawLine(x, top, x, top + lineHeight * trackCount())
    }

    private fun scrollCaretIntoView() {
        val row = doc.caret / basesPerLine
        scrollRectToVisible(Rectangle(0, yOfRow(row), width, rowHeight()))
    }

    /** Scrolls to and selects [start, end). */
    fun revealRange(start: Int, end: Int) {
        doc.select(start, end)
        val row = start / basesPerLine
        scrollRectToVisible(Rectangle(0, maxOf(0, yOfRow(row) - rowHeight()), width, rowHeight() * 3))
        requestFocusInWindow()
    }

    // ------------------------------------------------------------------ input

    private fun installMouseHandlers() {
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                requestFocusInWindow()
                if (SwingUtilities.isLeftMouseButton(e)) {
                    val hit = featureAt(e.x, e.y)
                    if (hit != null && e.clickCount == 2) {
                        doc.select(hit.start, hit.end)
                    } else {
                        doc.moveCaret(indexAt(e.x, e.y), e.isShiftDown)
                    }
                }
            }
        })
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                doc.moveCaret(indexAt(e.x, e.y), extendSelection = true)
            }
        })
    }

    private fun featureAt(px: Int, py: Int): Feature? {
        val row = ((py - padding) / rowHeight()).coerceIn(0, rowCount() - 1)
        val bandTop = yOfRow(row) + markHeight + lineHeight * trackCount()
        if (py < bandTop) return null
        val lane = (py - bandTop) / laneHeight
        val index = indexAt(px, py)
        return doc.seq.features.firstOrNull { laneOf[it] == lane && index in it.start until it.end }
    }

    override fun getToolTipText(event: MouseEvent): String {
        val index = indexAt(event.x, event.y)
        val parts = ArrayList<String>()
        parts += "position ${index + 1}"
        doc.seq.features.filter { index in it.start until it.end }.forEach {
            parts += "${it.name} (${it.type} ${it.displayRange()} ${it.strand.symbol})"
        }
        doc.cutSites.filter { kotlin.math.abs(it.topCut - index) <= 1 }.forEach {
            parts += "${it.enzyme.name} cuts here (${it.enzyme.notation()})"
        }
        return "<html>" + parts.joinToString("<br>") + "</html>"
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
                    doc.mutate("delete base") { it.deleteRange(at - 1, at) }
                    doc.moveCaret(at - 1)
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
        val clean = when (doc.seq.kind) {
            SeqKind.PROTEIN -> Alphabet.clean(text).uppercase()
                .filter { Alphabet.isAminoAcid(it) && it != '-' && it != '*' }

            else -> Alphabet.clean(text).uppercase().filter { Alphabet.isNucleotide(it) }
        }
        if (clean.isEmpty()) return
        val start = doc.selectionStart
        if (doc.hasSelection) {
            val end = doc.selectionEnd
            doc.mutate("replace ${end - start} bases") { it.replaceRange(start, end, clean) }
        } else {
            doc.mutate("insert ${clean.length} bases") { it.insertAt(start, clean) }
        }
        doc.moveCaret(start + clean.length)
    }

    /** Deletes the current selection, if any. */
    fun deleteSelection() {
        if (!doc.hasSelection) return
        val start = doc.selectionStart
        val end = doc.selectionEnd
        doc.mutate("delete ${end - start} bases") { it.deleteRange(start, end) }
        doc.moveCaret(start)
    }

    /** Copies the selection to the system clipboard, or the whole sequence when nothing is selected. */
    fun copySelection() {
        val text = if (doc.hasSelection) doc.selectedBases else doc.seq.bases
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
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
                append("   GC ${"%.1f".format(SeqOps.gcContent(seq))}%")
            }
            if (doc.hasSelection) {
                val sel = doc.selectedBases
                append("   |  selection ${doc.selectionStart + 1}..${doc.selectionEnd}")
                append(" (${sel.length} $unit")
                if (seq.kind != SeqKind.PROTEIN) {
                    append(", GC ${"%.1f".format(SeqOps.gcContent(sel))}%")
                    if (sel.length in 1..60) append(", Tm ${"%.1f".format(SeqOps.meltingTemp(sel))} C")
                }
                append(")")
            } else {
                append("   |  caret ${doc.caret + 1}")
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
