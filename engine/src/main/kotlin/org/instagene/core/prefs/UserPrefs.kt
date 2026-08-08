package org.instagene.core.prefs

import kotlinx.serialization.Serializable
import org.instagene.core.Enzyme

/** What kind of molecule a [SavedItem] holds. */
@Serializable
enum class SavedKind { PRIMER, FRAGMENT }

/**
 * Where a saved primer or fragment came from, so it can be restored via a
 * "jump to source" action when the originating sequence is still open.
 */
@Serializable
data class SavedContext(
    val sourceName: String = "",
    val start: Int = 0,
    val end: Int = 0,
    val tm: Double? = null,
    val enzymes: List<String> = emptyList(),
)

/** A reusable primer or restriction fragment kept in the library. */
@Serializable
data class SavedItem(
    val kind: SavedKind,
    val name: String,
    val bases: String,
    val context: SavedContext = SavedContext(),
) {
    val length: Int get() = bases.length
}

/**
 * Everything InstaGene remembers between launches. Field-level defaults make
 * every value optional, so an empty or partial prefs file deserializes cleanly.
 */
@Serializable
data class UserPrefs(
    val windowX: Int? = null,
    val windowY: Int? = null,
    val windowWidth: Int = 1400,
    val windowHeight: Int = 800,
    val windowMaximized: Boolean = false,
    val recentFiles: List<String> = emptyList(),
    val customEnzymes: List<Enzyme> = emptyList(),
    val enabledEnzymes: List<String> = emptyList(),
    val digestFilter: String = "",
    val digestCuttersOnly: Boolean = true,
    val digestUniqueOnly: Boolean = false,
    val selectedEnzymes: List<String> = emptyList(),
    val primerDefaultTm: Double = 60.0,
    val activeTab: Int = 0,
    val library: List<SavedItem> = emptyList(),
)
