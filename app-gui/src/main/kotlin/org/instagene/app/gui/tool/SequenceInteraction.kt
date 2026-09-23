package org.instagene.app.gui.tool

import org.instagene.app.gui.document.SeqDocument
import org.instagene.core.CutSite
import org.instagene.core.Feature
import org.instagene.core.PrimerAnnotation
import java.util.WeakHashMap

/** Object inspection is deliberately independent of the editable base selection. */
sealed interface SequenceObject {
    val name: String
    val tab: String
    val start: Int
    val end: Int
    data class Annotation(val feature: Feature) : SequenceObject {
        override val name get() = FeatureLabelOptions.text(feature)
        override val tab = "Features"
        override val start get() = feature.start
        override val end get() = feature.end
    }
    data class Site(val site: CutSite) : SequenceObject {
        override val name get() = site.enzyme.name
        override val tab = "Enzyme"
        override val start get() = site.recognitionStart
        override val end get() = site.recognitionEnd
    }
    data class Primer(val primer: PrimerAnnotation, val preview: Boolean = false) : SequenceObject {
        override val name get() = primer.name
        override val tab = "Primers"
        override val start get() = primer.bindingStart
        override val end get() = primer.bindingEnd
    }
}

class SequenceInteraction(initial: SeqDocument) {
    private data class State(var selected: SequenceObject? = null, var back: String? = null)
    private val states = WeakHashMap<SeqDocument, State>()
    var document = initial
        private set
    private val listeners = mutableListOf<() -> Unit>()
    private val state get() = states.getOrPut(document) { State() }
    val selected get() = state.selected
    val backTab get() = state.back
    var onNavigate: (String) -> Unit = {}
    var onReveal: (Int, Int) -> Unit = { _, _ -> }
    private val documentListener = SeqDocument.Listener { _, reason ->
        if (reason == SeqDocument.Reason.SEQUENCE || reason == SeqDocument.Reason.ENZYMES) {
            val valid = when (val item = selected) {
                is SequenceObject.Annotation -> item.feature in document.seq.features
                is SequenceObject.Site -> item.site in document.cutSites
                is SequenceObject.Primer -> item.preview || item.primer in document.seq.primers
                null -> true
            }
            if (!valid) select(null)
        }
    }
    init { document.addListener(documentListener) }
    fun addListener(listener: () -> Unit) { listeners += listener }
    private fun changed() { listeners.toList().forEach { it() } }
    fun bindDocument(next: SeqDocument) {
        if (document === next) return
        document.removeListener(documentListener)
        document = next
        document.addListener(documentListener)
        changed()
    }
    fun select(item: SequenceObject?) {
        if (selected == item) return
        state.selected = item
        changed()
    }
    fun show(item: SequenceObject, source: String = item.tab) {
        select(item)
        state.back = source
        onNavigate("Sequence")
        onReveal(item.start, item.end)
        changed()
    }
    fun openSelected() { selected?.let { onNavigate(it.tab) } }
    fun back() { backTab?.let(onNavigate) }
    fun selectBases() {
        selected?.let { document.select(it.start, it.end.coerceAtMost(document.seq.length)) }
    }
    fun dispose() { document.removeListener(documentListener); listeners.clear(); states.clear() }
}
