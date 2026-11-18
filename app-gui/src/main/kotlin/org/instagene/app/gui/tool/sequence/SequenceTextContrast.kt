package org.instagene.app.gui.tool.sequence

import java.awt.Color
import kotlin.math.pow

/** Contrast policy local to the sequence canvas; biological colors are left intact. */
internal object SequenceTextContrast {
    fun over(fill: Color, background: Color): Color {
        val alpha = fill.alpha
        fun channel(foreground: Int, behind: Int) = (foreground * alpha + behind * (255 - alpha) + 127) / 255
        return Color(channel(fill.red, background.red), channel(fill.green, background.green), channel(fill.blue, background.blue))
    }

    private fun luminance(color: Color): Double {
        fun linear(channel: Int): Double {
            val value = channel / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    }

    private fun ratio(foreground: Color, background: Color): Double {
        val first = luminance(foreground)
        val second = luminance(background)
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }

    fun readable(preferred: Color, background: Color): Color {
        val opaque = Color(preferred.red, preferred.green, preferred.blue)
        if (ratio(opaque, background) >= 4.5) return opaque
        return if (ratio(Color.WHITE, background) > ratio(Color.BLACK, background)) Color.WHITE else Color.BLACK
    }
}
