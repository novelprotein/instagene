package org.instagene.app.gui

import org.instagene.core.Enzyme
import org.instagene.core.Enzymes
import org.instagene.app.gui.prefs.UserPrefs

/**
 * Working state behind the Enzyme Manager dialog. Edits accumulate here against
 * a snapshot of [Prefs.value] and only reach the shared prefs (and thus the rest
 * of the UI) when [commit] is called — the "persists on OK" behaviour.
 *
 * Kept free of Swing so it can be exercised headlessly by tests.
 */
class EnzymeManagerModel(private val prefs: Prefs) {

    var working: UserPrefs = prefs.value
        private set

    private val listeners = ArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    private fun notifyChanged() {
        listeners.toList().forEach { it() }
    }

    /** The working catalog: built-ins plus any novel enzymes added here. */
    val pool: List<Enzyme> get() = Enzymes.pool(working.customEnzymes)

    /** True when [enzyme] belongs to the working set (empty enabled list = all active). */
    fun isEnabled(enzyme: Enzyme): Boolean {
        val enabled = working.enabledEnzymes.map { it.lowercase() }
        return enabled.isEmpty() || enzyme.name.lowercase() in enabled
    }

    /** Adds/removes [enzyme] in the required-only working set. */
    fun setEnabled(enzyme: Enzyme, enabled: Boolean) {
        val all = pool.map { it.name.lowercase() }.toSet()
        val current = working.enabledEnzymes.map { it.lowercase() }.toMutableSet()
        // The empty set means "all active": enabling anything is a no-op.
        if (current.isEmpty() && enabled) return
        if (current.isEmpty()) current.addAll(all)
        if (enabled) {
            current += enzyme.name.lowercase()
        } else {
            current -= enzyme.name.lowercase()
        }
        // A fully enabled set is normalised back to the empty list (= all active).
        val next = if (current == all) emptyList() else current.toList()
        working = working.copy(enabledEnzymes = next)
        notifyChanged()
    }

    /** True when [enzyme] was defined by the user (i.e. is removable). */
    fun isCustom(enzyme: Enzyme): Boolean =
        working.customEnzymes.any { it.name.equals(enzyme.name, ignoreCase = true) }

    /**
     * Validates and adds a novel enzyme to the working set. Returns an error
     * message, or null when the enzyme was added.
     */
    fun addEnzyme(name: String, site: String, topCut: Int, bottomCut: Int): String? {
        Enzymes.validateNew(name, site, topCut, bottomCut)?.let { return it }
        val trimmedName = name.trim()
        if (pool.any { it.name.equals(trimmedName, ignoreCase = true) }) {
            return "An enzyme named '$trimmedName' already exists."
        }
        val enzyme = Enzyme(trimmedName, site.trim().uppercase(), topCut, bottomCut)
        var next = working.copy(customEnzymes = working.customEnzymes + enzyme)
        // A novel enzyme must appear in the working set even when only a subset is enabled.
        if (next.enabledEnzymes.isNotEmpty() &&
            next.enabledEnzymes.none { it.equals(trimmedName, ignoreCase = true) }
        ) {
            next = next.copy(enabledEnzymes = next.enabledEnzymes + trimmedName)
        }
        working = next
        notifyChanged()
        return null
    }

    /** Removes a custom enzyme (built-ins cannot be removed). */
    fun removeEnzyme(enzyme: Enzyme) {
        if (!isCustom(enzyme)) return
        working = working.copy(
            customEnzymes = working.customEnzymes.filterNot { it.name.equals(enzyme.name, ignoreCase = true) },
            enabledEnzymes = working.enabledEnzymes.filterNot { it.equals(enzyme.name, ignoreCase = true) },
        )
        notifyChanged()
    }

    /** Pushes the working snapshot into the shared prefs (OK). */
    fun commit() {
        prefs.update { working }
    }

    /** Discards the working snapshot (Cancel). */
    fun reset() {
        working = prefs.value
        notifyChanged()
    }
}
