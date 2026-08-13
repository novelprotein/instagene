package org.instagene.app.gui.prefs

import kotlinx.serialization.Serializable
import org.instagene.core.Enzyme
import org.instagene.core.SeqKind
import org.instagene.core.Strand

/** A per-user replacement for the biochemical fields of one built-in enzyme. */
@Serializable
data class EnzymeOverride(
    val name: String,
    val site: String,
    val topCut: Int,
    val bottomCut: Int,
) {
    fun toEnzyme(): Enzyme = Enzyme(name, site, topCut, bottomCut)
}

/** What kind of molecule a [SavedItem] holds. */
@Serializable
enum class SavedKind { PRIMER, FRAGMENT, FEATURE }

/** Annotation details retained when a sequence feature is saved to the library. */
@Serializable
data class SavedFeatureMetadata(
    val type: String = "misc_feature",
    val strand: Strand = Strand.FORWARD,
    val qualifiers: Map<String, List<String>> = emptyMap(),
)

/**
 * Where a saved library item came from, so it can be restored via a
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

/** A reusable primer, restriction fragment, or annotated feature kept in the library. */
@Serializable
data class SavedItem(
    val kind: SavedKind,
    val name: String,
    val bases: String,
    val context: SavedContext = SavedContext(),
    val description: String = "",
    val sequenceKind: SeqKind = SeqKind.DNA,
    val feature: SavedFeatureMetadata? = null,
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
    val recentProjects: List<String> = emptyList(),
    val customEnzymes: List<Enzyme> = emptyList(),
    /** Built-in enzyme overrides, keyed by the original lowercase built-in name. */
    val enzymeOverrides: Map<String, EnzymeOverride> = emptyMap(),
    /** User-authored descriptions for built-in and custom enzymes, keyed by lowercase name. */
    val enzymeDescriptions: Map<String, String> = emptyMap(),
    val enabledEnzymes: List<String> = emptyList(),
    val digestFilter: String = "",
    val digestCuttersOnly: Boolean = true,
    val digestUniqueOnly: Boolean = false,
    val selectedEnzymes: List<String> = emptyList(),
    val primerDefaultTm: Double = 60.0,
    val activeTab: Int = 0,
    val library: List<SavedItem> = emptyList(),
    val theme: String = "FlatDraculaIJTheme",
    val fileBrowserVisible: Boolean = true,
)
