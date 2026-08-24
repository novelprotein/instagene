package org.instagene.core

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

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
        val image = PngCanvas(layout.width, layout.height)
        image.fill(WHITE_RGB)
        val view = result.view()
        layout.forEachBlock { offset, end, top ->
            if (options.showCoordinates) {
                image.drawText(
                    options.margin,
                    textTop(top, options),
                    coordinateLabel(view, offset, end),
                    LABEL_RGB,
                    textScale(options),
                    bold = true,
                )
            }
            var row = 0
            if (options.showConsensus) {
                drawRowPng(image, "consensus", view.consensus, offset, end, layout.rowTop(top, row++), options)
            }
            result.sequences.forEach { sequence ->
                drawRowPng(image, sequence.name, sequence.bases, offset, end, layout.rowTop(top, row++), options)
            }
        }
        return PngEncoder.encode(image.width, image.height, image.pixels)
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
        image: PngCanvas,
        name: String,
        bases: String,
        offset: Int,
        end: Int,
        top: Int,
        options: AlignmentImageOptions,
    ) {
        val scale = textScale(options)
        image.drawText(options.margin, textTop(top, options), name, TEXT_RGB, scale, bold = name == "consensus")
        for (index in offset until end) {
            val x = options.margin + options.nameWidth + (index - offset) * options.cellWidth
            val base = bases[index]
            image.fillRect(x, top, options.cellWidth, options.rowHeight - 1, pngColor(base))
            image.drawText(
                x + (options.cellWidth - GLYPH_WIDTH * scale) / 2,
                textTop(top, options),
                base.toString(),
                TEXT_RGB,
                scale,
            )
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

    private fun pngColor(base: Char): Int = when (base.uppercaseChar()) {
        'A' -> 0xd8f0dc
        'C' -> 0xd7e8ff
        'G' -> 0xffe8bd
        'T', 'U' -> 0xffd8dc
        '-' -> 0xeef0f3
        else -> 0xeee7ff
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

    private class PngCanvas(val width: Int, val height: Int) {
        val pixels = IntArray(width * height)

        fun fill(color: Int) {
            pixels.fill(color)
        }

        fun fillRect(x: Int, y: Int, rectWidth: Int, rectHeight: Int, color: Int) {
            val left = x.coerceIn(0, width)
            val top = y.coerceIn(0, height)
            val right = (x + rectWidth).coerceIn(0, width)
            val bottom = (y + rectHeight).coerceIn(0, height)
            for (row in top until bottom) {
                val offset = row * width
                for (column in left until right) pixels[offset + column] = color
            }
        }

        fun drawText(x: Int, y: Int, text: String, color: Int, scale: Int, bold: Boolean = false) {
            var cursor = x
            text.forEach { character ->
                val normalized = normalizeGlyph(character)
                drawGlyph(cursor, y, normalized, color, scale)
                if (bold && normalized != ' ') drawGlyph(cursor + 1, y, normalized, color, scale)
                cursor += (GLYPH_WIDTH + 1) * scale
            }
        }

        private fun drawGlyph(x: Int, y: Int, character: Char, color: Int, scale: Int) {
            val glyph = GLYPHS[character] ?: GLYPHS.getValue('?')
            glyph.forEachIndexed { row, pattern ->
                pattern.forEachIndexed { column, bit ->
                    if (bit == '1') fillRect(x + column * scale, y + row * scale, scale, scale, color)
                }
            }
        }
    }

    private object PngEncoder {
        private val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

        fun encode(width: Int, height: Int, pixels: IntArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(signature)
            writeChunk(out, "IHDR", ByteArrayOutputStream().apply {
                writeInt(width)
                writeInt(height)
                write(8)
                write(2)
                write(0)
                write(0)
                write(0)
            }.toByteArray())
            writeChunk(out, "IDAT", compressedScanlines(width, height, pixels))
            writeChunk(out, "IEND", ByteArray(0))
            return out.toByteArray()
        }

        private fun compressedScanlines(width: Int, height: Int, pixels: IntArray): ByteArray {
            val raw = ByteArrayOutputStream((width * 3 + 1) * height)
            for (y in 0 until height) {
                raw.write(0)
                for (x in 0 until width) {
                    val color = pixels[y * width + x]
                    raw.write((color ushr 16) and 0xff)
                    raw.write((color ushr 8) and 0xff)
                    raw.write(color and 0xff)
                }
            }
            val compressed = ByteArrayOutputStream()
            DeflaterOutputStream(compressed).use { it.write(raw.toByteArray()) }
            return compressed.toByteArray()
        }

        private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
            val typeBytes = type.toByteArray(StandardCharsets.US_ASCII)
            out.writeInt(data.size)
            out.write(typeBytes)
            out.write(data)
            val crc = CRC32()
            crc.update(typeBytes)
            crc.update(data)
            out.writeInt(crc.value.toInt())
        }

        private fun ByteArrayOutputStream.writeInt(value: Int) {
            write((value ushr 24) and 0xff)
            write((value ushr 16) and 0xff)
            write((value ushr 8) and 0xff)
            write(value and 0xff)
        }
    }

    private fun textScale(options: AlignmentImageOptions): Int = maxOf(1, (options.fontSize + 6) / GLYPH_HEIGHT)

    private fun textTop(rowTop: Int, options: AlignmentImageOptions): Int {
        val height = GLYPH_HEIGHT * textScale(options)
        return rowTop + ((options.rowHeight - height) / 2).coerceAtLeast(0)
    }

    private fun normalizeGlyph(character: Char): Char = when {
        character == '–' || character == '—' -> '-'
        character.lowercaseChar() in 'a'..'z' -> character.uppercaseChar()
        character in GLYPHS -> character
        else -> '?'
    }

    private const val MAX_DIMENSION = 32_000
    private const val MAX_RASTER_PIXELS = 20_000_000L
    private const val WHITE_RGB = 0xffffff
    private const val TEXT_RGB = 0x111827
    private const val LABEL_RGB = 0x4b5563
    private const val GLYPH_WIDTH = 5
    private const val GLYPH_HEIGHT = 7
    private val GLYPHS = mapOf(
        ' ' to listOf("00000", "00000", "00000", "00000", "00000", "00000", "00000"),
        '?' to listOf("01110", "10001", "00001", "00010", "00100", "00000", "00100"),
        '-' to listOf("00000", "00000", "00000", "11111", "00000", "00000", "00000"),
        '_' to listOf("00000", "00000", "00000", "00000", "00000", "00000", "11111"),
        '.' to listOf("00000", "00000", "00000", "00000", "00000", "01100", "01100"),
        ',' to listOf("00000", "00000", "00000", "00000", "01100", "00100", "01000"),
        ':' to listOf("00000", "01100", "01100", "00000", "01100", "01100", "00000"),
        '/' to listOf("00001", "00010", "00010", "00100", "01000", "01000", "10000"),
        '\\' to listOf("10000", "01000", "01000", "00100", "00010", "00010", "00001"),
        '(' to listOf("00010", "00100", "01000", "01000", "01000", "00100", "00010"),
        ')' to listOf("01000", "00100", "00010", "00010", "00010", "00100", "01000"),
        '[' to listOf("01110", "01000", "01000", "01000", "01000", "01000", "01110"),
        ']' to listOf("01110", "00010", "00010", "00010", "00010", "00010", "01110"),
        '=' to listOf("00000", "11111", "00000", "11111", "00000", "00000", "00000"),
        '+' to listOf("00000", "00100", "00100", "11111", "00100", "00100", "00000"),
        '0' to listOf("01110", "10001", "10011", "10101", "11001", "10001", "01110"),
        '1' to listOf("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
        '2' to listOf("01110", "10001", "00001", "00010", "00100", "01000", "11111"),
        '3' to listOf("11110", "00001", "00001", "01110", "00001", "00001", "11110"),
        '4' to listOf("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
        '5' to listOf("11111", "10000", "10000", "11110", "00001", "00001", "11110"),
        '6' to listOf("01110", "10000", "10000", "11110", "10001", "10001", "01110"),
        '7' to listOf("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
        '8' to listOf("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
        '9' to listOf("01110", "10001", "10001", "01111", "00001", "00001", "01110"),
        'A' to listOf("01110", "10001", "10001", "11111", "10001", "10001", "10001"),
        'B' to listOf("11110", "10001", "10001", "11110", "10001", "10001", "11110"),
        'C' to listOf("01110", "10001", "10000", "10000", "10000", "10001", "01110"),
        'D' to listOf("11110", "10001", "10001", "10001", "10001", "10001", "11110"),
        'E' to listOf("11111", "10000", "10000", "11110", "10000", "10000", "11111"),
        'F' to listOf("11111", "10000", "10000", "11110", "10000", "10000", "10000"),
        'G' to listOf("01110", "10001", "10000", "10111", "10001", "10001", "01110"),
        'H' to listOf("10001", "10001", "10001", "11111", "10001", "10001", "10001"),
        'I' to listOf("01110", "00100", "00100", "00100", "00100", "00100", "01110"),
        'J' to listOf("00001", "00001", "00001", "00001", "10001", "10001", "01110"),
        'K' to listOf("10001", "10010", "10100", "11000", "10100", "10010", "10001"),
        'L' to listOf("10000", "10000", "10000", "10000", "10000", "10000", "11111"),
        'M' to listOf("10001", "11011", "10101", "10101", "10001", "10001", "10001"),
        'N' to listOf("10001", "11001", "10101", "10011", "10001", "10001", "10001"),
        'O' to listOf("01110", "10001", "10001", "10001", "10001", "10001", "01110"),
        'P' to listOf("11110", "10001", "10001", "11110", "10000", "10000", "10000"),
        'Q' to listOf("01110", "10001", "10001", "10001", "10101", "10010", "01101"),
        'R' to listOf("11110", "10001", "10001", "11110", "10100", "10010", "10001"),
        'S' to listOf("01111", "10000", "10000", "01110", "00001", "00001", "11110"),
        'T' to listOf("11111", "00100", "00100", "00100", "00100", "00100", "00100"),
        'U' to listOf("10001", "10001", "10001", "10001", "10001", "10001", "01110"),
        'V' to listOf("10001", "10001", "10001", "10001", "10001", "01010", "00100"),
        'W' to listOf("10001", "10001", "10001", "10101", "10101", "10101", "01010"),
        'X' to listOf("10001", "10001", "01010", "00100", "01010", "10001", "10001"),
        'Y' to listOf("10001", "10001", "01010", "00100", "00100", "00100", "00100"),
        'Z' to listOf("11111", "00001", "00010", "00100", "01000", "10000", "11111"),
    )
}
