package org.instagene.core

data class GcWindow(val start: Int, val end: Int, val gcPercent: Double)

object SequenceProfiles {
    fun gcWindows(seq: Seq, windowSize: Int = 100, step: Int = windowSize / 2): List<GcWindow> {
        require(seq.kind != SeqKind.PROTEIN) { "GC profile requires DNA or RNA" }
        require(windowSize > 0 && step > 0) { "Window and step must be positive" }
        if (seq.length == 0) return emptyList()
        return buildList {
            var start = 0
            while (start < seq.length) {
                val end = minOf(seq.length, start + windowSize)
                add(GcWindow(start, end, SeqOps.gcContent(seq.bases.substring(start, end))))
                if (end == seq.length) break
                start += step
            }
        }
    }
}
