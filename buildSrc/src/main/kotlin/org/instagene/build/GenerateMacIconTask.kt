package org.instagene.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import javax.imageio.ImageIO

/** Builds a PNG-backed multi-resolution ICNS file without requiring macOS-only tooling. */
abstract class GenerateMacIconTask : DefaultTask() {
    @get:InputFile
    abstract val sourceIcon: RegularFileProperty

    @get:OutputFile
    abstract val outputIcon: RegularFileProperty

    @TaskAction
    fun generate() {
        val source = ImageIO.read(sourceIcon.get().asFile)
            ?: error("Unable to read application icon: ${sourceIcon.get().asFile}")
        val chunks = listOf(
            "icp4" to 16,
            "icp5" to 32,
            "icp6" to 64,
            "ic07" to 128,
            "ic08" to 256,
            "ic09" to 512,
            "ic10" to 1024,
        )
        val body = ByteArrayOutputStream()
        DataOutputStream(body).use { out ->
            chunks.forEach { (type, size) ->
                val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
                val graphics = image.createGraphics()
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                    graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                    graphics.drawImage(source, 0, 0, size, size, null)
                } finally {
                    graphics.dispose()
                }
                val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
                out.writeBytes(type)
                out.writeInt(png.size + 8)
                out.write(png)
            }
        }
        val target = outputIcon.get().asFile
        target.parentFile.mkdirs()
        DataOutputStream(target.outputStream()).use { out ->
            out.writeBytes("icns")
            out.writeInt(body.size() + 8)
            out.write(body.toByteArray())
        }
    }
}
