package org.instagene.app.gui

import org.instagene.app.gui.prefs.EnzymeOverride
import org.instagene.app.gui.prefs.UserPrefs
import org.instagene.core.Enzyme
import org.instagene.core.Enzymes

/**
 * The effective GUI enzyme catalog. Built-in overrides deliberately live here
 * rather than in the core catalog, keeping command-line and engine defaults
 * canonical while allowing a user to tailor the desktop application.
 */
fun UserPrefs.enzymePool(): List<Enzyme> {
    val builtIns = Enzymes.ALL.map { original ->
        enzymeOverrides[original.name.lowercase()]?.toEnzyme() ?: original
    }
    val seen = HashSet<String>()
    return (builtIns + customEnzymes).filter { seen.add(it.name.lowercase()) }
}

/** Finds an enzyme in the effective GUI catalog, case-insensitively. */
fun UserPrefs.findEnzyme(name: String): Enzyme? =
    enzymePool().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

/** The immutable built-in identity backing [enzyme], if it is a built-in row. */
fun UserPrefs.builtInKeyFor(enzyme: Enzyme): String? {
    Enzymes.ALL.firstOrNull { it == enzyme }?.let { return it.name.lowercase() }
    return enzymeOverrides.entries.firstOrNull { (_, override) -> override.toEnzyme() == enzyme }?.key
}

/** True when [enzyme] is a user-created catalog entry. */
fun UserPrefs.isCustomEnzyme(enzyme: Enzyme): Boolean =
    customEnzymes.any { it == enzyme }

/**
 * The built-in description for [enzyme], or a fresh concise summary when a
 * built-in has been edited into a user-specific override. Custom enzymes have
 * no shipped description.
 */
fun UserPrefs.defaultEnzymeDescription(enzyme: Enzyme): String {
    val builtInKey = builtInKeyFor(enzyme) ?: return ""
    val canonical = Enzymes.ALL.firstOrNull { it.name.equals(builtInKey, ignoreCase = true) } ?: return ""
    return if (enzyme == canonical) {
        Enzymes.BUILTIN_DESCRIPTIONS[builtInKey].orEmpty()
    } else {
        Enzymes.simpleDescription(enzyme)
    }
}

/** A user description wins; otherwise resolve the appropriate shipped default. */
fun UserPrefs.enzymeDescriptionFor(enzyme: Enzyme): String =
    enzymeDescriptions[enzyme.name.lowercase()] ?: defaultEnzymeDescription(enzyme)

/** Converts an effective enzyme definition to its serializable override shape. */
fun Enzyme.toOverride(): EnzymeOverride = EnzymeOverride(name, site, topCut, bottomCut)
