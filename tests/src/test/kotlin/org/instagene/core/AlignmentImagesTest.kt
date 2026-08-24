package org.instagene.core

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AlignmentImagesTest {

    private val alignment = MultipleAlignmentResult(
        MultipleAlignmentAlgorithm.BUILTIN,
        listOf(
            Seq("reference", "AC-GT"),
            Seq("sample&check", "ATAGT"),
        ),
    )

    @Test
    fun svgIsEscapedAndIncludesConsensusRows() {
        val svg = AlignmentImages.svg(alignment, AlignmentImageOptions(columnsPerBlock = 3))

        assertTrue(svg.startsWith("<?xml"))
        assertTrue(svg.contains("<svg"))
        assertTrue(svg.contains("consensus"))
        assertTrue(svg.contains("sample&amp;check"))
    }

    @Test
    fun pngIsAReadableHeadlessRasterImage() {
        val png = AlignmentImages.png(alignment)
        val image = assertNotNull(ImageIO.read(ByteArrayInputStream(png)))

        assertTrue(image.width > 0)
        assertTrue(image.height > 0)
    }
}
