package org.instagene.app.gui.enzyme

import org.instagene.app.gui.prefs.Prefs
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
    val pool: List<Enzyme> get() = working.enzymePool()

    /** True when [enzyme] belongs to the working set (empty enabled list = all active). */
    fun isEnabled(enzyme: Enzyme): Boolean {
        val enabled = working.enabledEnzymes.map { it.lowercase() }
        return enabled.isEmpty() || enzyme.name.lowercase() in enabled
    }

    /** Adds or removes [enzyme] from the active working set. */
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
    fun isCustom(enzyme: Enzyme): Boolean = working.isCustomEnzyme(enzyme)

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

    /**
     * Replaces every editable field of [enzyme]. Built-ins receive a per-user
     * override; custom definitions are replaced in the custom catalog. Returns
     * a validation error, or null after updating this dialog's working copy.
     */
    fun editEnzyme(
        enzyme: Enzyme,
        name: String,
        site: String,
        topCut: Int,
        bottomCut: Int,
        enabled: Boolean,
        description: String,
    ): String? {
        Enzymes.validateNew(name, site, topCut, bottomCut)?.let { return it }
        val nextEnzyme = Enzyme(name.trim(), site.trim().uppercase(), topCut, bottomCut)
        if (pool.any { it != enzyme && it.name.equals(nextEnzyme.name, ignoreCase = true) }) {
            return "An enzyme named '${nextEnzyme.name}' already exists."
        }

        val oldKey = enzyme.name.lowercase()
        var next = if (isCustom(enzyme)) {
            working.copy(customEnzymes = working.customEnzymes.map {
                if (it == enzyme) nextEnzyme else it
            })
        } else {
            val builtInKey = working.builtInKeyFor(enzyme)
                ?: return "Unable to identify the built-in enzyme being edited."
            val overrides = working.enzymeOverrides.toMutableMap()
            val canonical = Enzymes.ALL.first { it.name.equals(builtInKey, ignoreCase = true) }
            if (nextEnzyme == canonical) overrides.remove(builtInKey) else overrides[builtInKey] = nextEnzyme.toOverride()
            working.copy(enzymeOverrides = overrides)
        }

        val newKey = nextEnzyme.name.lowercase()
        val descriptions = next.enzymeDescriptions.toMutableMap()
        if (oldKey != newKey) descriptions.remove(oldKey)
        if (description.isBlank()) descriptions.remove(newKey) else descriptions[newKey] = description
        next = next.copy(
            enzymeDescriptions = descriptions,
            enabledEnzymes = renamePreference(next.enabledEnzymes, oldKey, nextEnzyme.name),
            selectedEnzymes = renamePreference(next.selectedEnzymes, oldKey, nextEnzyme.name),
        )
        next = next.copy(enabledEnzymes = updateEnabled(next, nextEnzyme.name, enabled))
        working = next
        notifyChanged()
        return null
    }

    private fun renamePreference(values: List<String>, oldKey: String, newName: String): List<String> {
        if (values.isEmpty()) return values
        val seen = HashSet<String>()
        return values.map { if (it.equals(oldKey, ignoreCase = true)) newName else it }
            .filter { seen.add(it.lowercase()) }
    }

    /** Applies one enabled checkbox while preserving the empty-list-means-all convention. */
    private fun updateEnabled(prefs: UserPrefs, enzymeName: String, enabled: Boolean): List<String> {
        val pool = prefs.enzymePool()
        val all = pool.map { it.name.lowercase() }.toSet()
        val current = prefs.enabledEnzymes.map { it.lowercase() }.filter { it in all }.toMutableSet()
        if (current.isEmpty() && !enabled) current.addAll(all)
        if (enabled) current += enzymeName.lowercase() else current -= enzymeName.lowercase()
        return if (current == all) emptyList() else pool.filter { it.name.lowercase() in current }.map { it.name }
    }

    /** Removes a custom enzyme (built-ins cannot be removed). */
    fun removeEnzyme(enzyme: Enzyme) {
        if (!isCustom(enzyme)) return
        working = working.copy(
            customEnzymes = working.customEnzymes.filterNot { it.name.equals(enzyme.name, ignoreCase = true) },
            enabledEnzymes = working.enabledEnzymes.filterNot { it.equals(enzyme.name, ignoreCase = true) },
            selectedEnzymes = working.selectedEnzymes.filterNot { it.equals(enzyme.name, ignoreCase = true) },
            enzymeDescriptions = working.enzymeDescriptions - enzyme.name.lowercase(),
        )
        notifyChanged()
    }

    /** Pushes the working snapshot into the shared prefs (OK). */
    fun commit() {
        prefs.update { working }
    }
}
