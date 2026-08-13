package org.instagene.app.gui.prefs

import org.instagene.core.SeqKind
import org.instagene.core.Strand
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PrefsStore round-trips [UserPrefs] to disk atomically, tolerates missing or
 * corrupt files, and stores the library of saved primers, fragments, and features.
 */
class PrefsStoreTest {

    private fun tempFile(): File {
        val dir = Files.createTempDirectory("instagene-prefs").toFile()
        dir.deleteOnExit()
        return File(dir, "prefs.json")
    }

    @Test
    fun defaultsWhenFileIsMissingAndFileIsNotCreated() {
        val file = tempFile()
        val store = PrefsStore(file)
        assertEquals(UserPrefs(), store.load())
        assertTrue(!file.exists(), "load() must not create the file")
    }

    @Test
    fun defaultsWhenFileIsCorrupt() {
        val file = tempFile()
        file.writeText("{ not valid json !!!")
        val store = PrefsStore(file)
        assertEquals(UserPrefs(), store.load())
    }

    @Test
    fun saveRoundTripsAllFields() {
        val file = tempFile()
        val store = PrefsStore(file)
        val prefs = UserPrefs(
            windowX = 40,
            windowY = 60,
            windowWidth = 1024,
            windowHeight = 768,
            windowMaximized = true,
            recentFiles = listOf("/a/x.fasta", "/b/y.gb"),
            recentProjects = listOf("/p/proj1", "/p/proj2"),
            customEnzymes = emptyList(),
            enzymeOverrides = mapOf("ecori" to EnzymeOverride("EcoX", "CCGG", 0, 2)),
            enzymeDescriptions = mapOf("ecori" to "Useful for routine cloning"),
            enabledEnzymes = listOf("EcoRI", "BamHI"),
            digestFilter = "RI",
            digestCuttersOnly = false,
            digestUniqueOnly = true,
            selectedEnzymes = listOf("EcoRI"),
            primerDefaultTm = 62.5,
            activeTab = 3,
            library = listOf(
                SavedItem(
                    kind = SavedKind.PRIMER,
                    name = "fwd",
                    bases = "ACGTACGT",
                    context = SavedContext("pUC19", 10, 30, tm = 60.0),
                    description = "Forward verification primer",
                ),
                SavedItem(
                    kind = SavedKind.FRAGMENT,
                    name = "frag",
                    bases = "NNN",
                    context = SavedContext("x", 0, 3, enzymes = listOf("EcoRI")),
                ),
                SavedItem(
                    kind = SavedKind.FEATURE,
                    name = "rev_gene",
                    bases = "AUGC",
                    context = SavedContext("rna_source", 4, 8),
                    description = "Conserved reverse-strand gene",
                    sequenceKind = SeqKind.RNA,
                    feature = SavedFeatureMetadata(
                        type = "gene",
                        strand = Strand.REVERSE,
                        qualifiers = mapOf("gene" to listOf("revA"), "pseudo" to listOf("")),
                    ),
                ),
            ),
        )
        store.save(prefs)

        // A fresh store over the same file reloads the exact same values.
        val reloaded = PrefsStore(file).load()
        assertEquals(prefs, reloaded)
        assertEquals(SavedKind.PRIMER, reloaded.library[0].kind)
        assertEquals("Forward verification primer", reloaded.library[0].description)
        assertEquals("Useful for routine cloning", reloaded.enzymeDescriptions["ecori"])
        assertEquals("EcoX", reloaded.enzymeOverrides["ecori"]?.name)
        assertEquals(60.0, reloaded.library[0].context.tm)
        assertEquals(listOf("EcoRI"), reloaded.library[1].context.enzymes)
        assertEquals(SavedKind.FEATURE, reloaded.library[2].kind)
        assertEquals(SeqKind.RNA, reloaded.library[2].sequenceKind)
        assertEquals(Strand.REVERSE, reloaded.library[2].feature?.strand)
        assertEquals("gene", reloaded.library[2].feature?.type)
        assertEquals(listOf("revA"), reloaded.library[2].feature?.qualifiers?.get("gene"))
    }

    @Test
    fun legacyPrimerAndFragmentEntriesLoadWithNewDefaults() {
        val file = tempFile()
        file.writeText(
            """
            {
              "library": [
                { "kind": "PRIMER", "name": "legacy_primer", "bases": "ACGT" },
                {
                  "kind": "FRAGMENT",
                  "name": "legacy_fragment",
                  "bases": "TTAA",
                  "context": { "sourceName": "old", "start": 1, "end": 5 }
                }
              ]
            }
            """.trimIndent()
        )

        val library = PrefsStore(file).load().library
        assertEquals(listOf(SavedKind.PRIMER, SavedKind.FRAGMENT), library.map { it.kind })
        assertTrue(library.all { it.sequenceKind == SeqKind.DNA })
        assertTrue(library.all { it.feature == null })
    }

    @Test
    fun saveIsAtomicAndDoesNotLeaveTempFile() {
        val file = tempFile()
        val store = PrefsStore(file)
        store.save(UserPrefs(activeTab = 1))
        val leftover = file.parentFile.listFiles { f -> f.name.contains(".tmp") }
        assertTrue(leftover == null || leftover.isEmpty(), "temp file left behind after save")
    }

    @Test
    fun loadIgnoresUnknownKeysFromNewerAppVersions() {
        val file = tempFile()
        file.writeText(
            """
            {
              "windowX": 12,
              "futureFeatureFlag": true,
              "library": []
            }
            """.trimIndent()
        )
        val store = PrefsStore(file)
        assertEquals(12, store.load().windowX)
        assertEquals(emptyList(), store.load().library)
    }
}
