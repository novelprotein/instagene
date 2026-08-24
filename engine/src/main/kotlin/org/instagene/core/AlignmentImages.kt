package org.instagene.core

import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Rendering choices shared by the portable SVG and PNG alignment exports. */
data class AlignmentImageOptions(
    val columnsPerBlock: Int = 80,
    val cellWidth: Int = 12,
    val rowHeight: Int = 20,
    val margin: Int = 18,
    val nameWidth: Int = 160,
    val fontSize: Int = 13,
    val showConsensus: Boolean = true,
    val showCoordinates: Boolean = true,
)

/**
 * Portable, headless-safe image rendering for an already aligned set of rows.
 * Long alignments are split into repeated fixed-width blocks so the exported
 * image remains legible in a paper supplement or electronic lab notebook.
 */
object AlignmentImages {

    /** Exports a vector image that remains sharp in manuscripts and slide decks. */
    fun svg(result: MultipleAlignmentResult, options: AlignmentImageOptions = AlignmentImageOptions()): String {
        val layout = Layout.create(result, options)
        val view = result.view()
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${layout.width}\" height=\"${layout.height}\" viewBox=\"0 0 ${layout.width} ${layout.height}\">\n")
            append("<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>\n")
            layout.forEachBlock { offset, end, top ->
                val coordinateY = top + options.fontSize
                if (options.showCoordinates) {
                    val label = coordinateLabel(view, offset, end)
                    appendText(options.margin, coordinateY, label, "#4b5563", options.fontSize, anchor = "start", bold = true)
                }
                var row = 0
                if (options.showConsensus) {
                    appendRowSvg("consensus", view.consensus, offset, end, layout.rowTop(top, row++), options)
                }
                result.sequences.forEach { sequence ->
                    appendRowSvg(sequence.name, sequence.bases, offset, end, layout.rowTop(top, row++), options)
                }
            }
            append("</svg>\n")
        }
    }

    /** Exports a lossless PNG without requiring a display server or native renderer. */
    fun png(result: MultipleAlignmentResult, options: AlignmentImageOptions = AlignmentImageOptions()): ByteArray {
        val layout = Layout.create(result, options)
        require(layout.width.toLong() * layout.height <= MAX_RASTER_PIXELS) {
            "Alignment PNG would contain more than $MAX_RASTER_PIXELS pixels; export SVG or use fewer rows/columns per image"
        }
        val image = BufferedImage(layout.width, layout.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, layout.width, layout.height)
            graphics.font = Font(Font.MONOSPACED, Font.PLAIN, options.fontSize)
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val view = result.view()
            layout.forEachBlock { offset, end, top ->
                if (options.showCoordinates) {
                    graphics.color = LABEL_COLOR
                    graphics.font = Font(Font.MONOSPACED, Font.BOLD, options.fontSize)
                    graphics.drawString(coordinateLabel(view, offset, end), options.margin, top + options.fontSize)
                    graphics.font = Font(Font.MONOSPACED, Font.PLAIN, options.fontSize)
                }
                var row = 0
                if (options.showConsensus) {
                    drawRowPng(graphics, "consensus", view.consensus, offset, end, layout.rowTop(top, row++), options)
                }
                result.sequences.forEach { sequence ->
                    drawRowPng(graphics, sequence.name, sequence.bases, offset, end, layout.rowTop(top, row++), options)
                }
            }
        } finally {
            graphics.dispose()
        }
        return ByteArrayOutputStream().use { bytes ->
            check(ImageIO.write(image, "png", bytes)) { "No PNG image writer is available" }
            bytes.toByteArray()
        }
    }

    private fun StringBuilder.appendRowSvg(
        name: String,
        bases: String,
        offset: Int,
        end: Int,
        top: Int,
        options: AlignmentImageOptions,
    ) {
        appendText(options.margin, top + options.fontSize, name, "#111827", options.fontSize, anchor = "start", bold = name == "consensus")
        for (index in offset until end) {
            val x = options.margin + options.nameWidth + (index - offset) * options.cellWidth
            val base = bases[index]
            append("<rect x=\"$x\" y=\"$top\" width=\"${options.cellWidth}\" height=\"${options.rowHeight - 1}\" fill=\"${svgColor(base)}\"/>\n")
            appendText(
                x + options.cellWidth / 2,
                top + options.fontSize,
                base.toString(),
                "#111827",
                options.fontSize,
                anchor = "middle",
            )
        }
    }

    private fun StringBuilder.appendText(
        x: Int,
        y: Int,
        value: String,
        color: String,
        size: Int,
        anchor: String,
        bold: Boolean = false,
    ) {
        append("<text x=\"$x\" y=\"$y\" fill=\"$color\" font-family=\"monospace\" font-size=\"$size\" text-anchor=\"$anchor\"")
        if (bold) append(" font-weight=\"bold\"")
        append(">")
        append(xmlEscape(value))
        append("</text>\n")
    }

    private fun drawRowPng(
        graphics: Graphics2D,
        name: String,
        bases: String,
        offset: Int,
        end: Int,
        top: Int,
        options: AlignmentImageOptions,
    ) {
        graphics.color = Color(0x11, 0x18, 0x27)
        graphics.font = Font(Font.MONOSPACED, if (name == "consensus") Font.BOLD else Font.PLAIN, options.fontSize)
        graphics.drawString(name, options.margin, top + options.fontSize)
        graphics.font = Font(Font.MONOSPACED, Font.PLAIN, options.fontSize)
        for (index in offset until end) {
            val x = options.margin + options.nameWidth + (index - offset) * options.cellWidth
            val base = bases[index]
            graphics.color = awtColor(base)
            graphics.fillRect(x, top, options.cellWidth, options.rowHeight - 1)
            graphics.color = Color(0x11, 0x18, 0x27)
            graphics.drawString(base.toString(), x + (options.cellWidth - options.fontSize) / 2 + 1, top + options.fontSize)
        }
    }

    private fun coordinateLabel(view: AlignmentView, offset: Int, end: Int): String {
        val positions = view.referencePositions.subList(offset, end).filterNotNull()
        return if (positions.isEmpty()) "columns ${offset + 1}–$end" else "reference ${positions.first()}–${positions.last()}"
    }

    private fun svgColor(base: Char): String = when (base.uppercaseChar()) {
        'A' -> "#d8f0dc"
        'C' -> "#d7e8ff"
        'G' -> "#ffe8bd"
        'T', 'U' -> "#ffd8dc"
        '-' -> "#eef0f3"
        else -> "#eee7ff"
    }

    private fun awtColor(base: Char): Color = when (base.uppercaseChar()) {
        'A' -> Color(0xd8, 0xf0, 0xdc)
        'C' -> Color(0xd7, 0xe8, 0xff)
        'G' -> Color(0xff, 0xe8, 0xbd)
        'T', 'U' -> Color(0xff, 0xd8, 0xdc)
        '-' -> Color(0xee, 0xf0, 0xf3)
        else -> Color(0xee, 0xe7, 0xff)
    }

    private fun xmlEscape(value: String): String = buildString(value.length) {
        value.forEach { character ->
            append(
                when (character) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '\"' -> "&quot;"
                    '\'' -> "&apos;"
                    else -> character
                },
            )
        }
    }

    private class Layout private constructor(
        val width: Int,
        val height: Int,
        private val blocks: Int,
        private val blockHeight: Int,
        private val blockGap: Int,
        private val resultWidth: Int,
        private val options: AlignmentImageOptions,
    ) {
        fun forEachBlock(action: (offset: Int, end: Int, top: Int) -> Unit) {
            repeat(blocks) { block ->
                val offset = block * options.columnsPerBlock
                val end = minOf(resultWidth, offset + options.columnsPerBlock)
                action(offset, end, options.margin + block * (blockHeight + blockGap))
            }
        }

        fun rowTop(blockTop: Int, row: Int): Int =
            blockTop + (if (options.showCoordinates) options.rowHeight else 0) + row * options.rowHeight

        companion object {
            fun create(result: MultipleAlignmentResult, options: AlignmentImageOptions): Layout {
                require(result.sequences.isNotEmpty()) { "Alignment contains no sequences" }
                require(options.columnsPerBlock > 0) { "columnsPerBlock must be positive" }
                require(options.cellWidth >= 6) { "cellWidth must be at least 6" }
                require(options.rowHeight >= options.fontSize + 2) { "rowHeight must fit the selected fontSize" }
                require(options.margin >= 0 && options.nameWidth > 0 && options.fontSize > 0) { "Image dimensions must be positive" }
                val resultWidth = result.sequences.first().length
                require(result.sequences.all { it.length == resultWidth }) { "Alignment rows have different lengths" }
                val blocks = maxOf(1, (resultWidth + options.columnsPerBlock - 1) / options.columnsPerBlock)
                val rowCount = result.sequences.size + if (options.showConsensus) 1 else 0
                val blockHeight = (rowCount + if (options.showCoordinates) 1 else 0) * options.rowHeight
                val blockGap = options.rowHeight / 2
                val width = options.margin * 2 + options.nameWidth + options.columnsPerBlock * options.cellWidth
                val height = options.margin * 2 + blocks * blockHeight + (blocks - 1) * blockGap
                require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) {
                    "Alignment image would be ${width}×${height}; use fewer rows or a wider block export"
                }
                return Layout(width, height, blocks, blockHeight, blockGap, resultWidth, options)
            }
        }
    }

    private const val MAX_DIMENSION = 32_000
    private const val MAX_RASTER_PIXELS = 20_000_000L
    private val LABEL_COLOR = Color(0x4b, 0x55, 0x63)
}
