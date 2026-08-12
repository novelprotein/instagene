package org.instagene.app.gui

import org.instagene.app.gui.enzyme.EnzymeManagerModel
import org.instagene.app.gui.enzyme.enzymeDescriptionFor
import org.instagene.app.gui.enzyme.enzymePool
import org.instagene.app.gui.enzyme.findEnzyme
import org.instagene.app.gui.file.Prefs
import org.instagene.app.gui.prefs.UserPrefs
import org.instagene.app.gui.prefs.EnzymeOverride
import org.instagene.core.Enzyme
import org.instagene.core.Enzymes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnzymeManagerModelTest {

    @Test
    fun editingBuiltinCreatesGuiOnlyOverrideAndMigratesPreferences() {
        val prefs = Prefs()
        prefs.update {
            it.copy(
                enabledEnzymes = listOf("EcoRI"),
                selectedEnzymes = listOf("EcoRI"),
                enzymeDescriptions = mapOf("ecori" to "Old description"),
            )
        }
        val model = EnzymeManagerModel(prefs)
        val ecoRi = model.pool.first { it.name == "EcoRI" }

        assertNull(model.editEnzyme(ecoRi, "EcoCustom", "GCGC", 1, 3, false, "Edited enzyme"))
        model.commit()

        val edited = prefs.value.findEnzyme("EcoCustom")
        assertEquals(Enzyme("EcoCustom", "GCGC", 1, 3), edited)
        assertEquals("Edited enzyme", prefs.value.enzymeDescriptions["ecocustom"])
        assertFalse(prefs.value.enzymeDescriptions.containsKey("ecori"))
        assertEquals(listOf("EcoCustom"), prefs.value.selectedEnzymes)
        assertFalse(prefs.value.enabledEnzymes.contains("EcoRI"))
        assertTrue(prefs.value.enzymeOverrides.containsKey("ecori"))

        // The bundled engine catalog is never modified by GUI personalization.
        assertEquals(Enzymes.require("EcoRI"), Enzyme("EcoRI", "GAATTC", 1, 5))
    }

    @Test
    fun editingCustomEnzymeReplacesDefinitionAndRejectsNameCollisions() {
        val custom = Enzyme("NovelI", "GATC", 1, 3)
        val prefs = Prefs()
        prefs.update {
            it.copy(
                customEnzymes = listOf(custom),
                enabledEnzymes = listOf("NovelI"),
                selectedEnzymes = listOf("NovelI"),
            )
        }
        val model = EnzymeManagerModel(prefs)

        assertNull(model.editEnzyme(custom, "RenamedI", "CCGG", 0, 2, true, "Custom annotation"))
        assertTrue(model.working.customEnzymes.none { it.name == "NovelI" })
        assertEquals(Enzyme("RenamedI", "CCGG", 0, 2), model.working.customEnzymes.single())
        assertEquals(listOf("RenamedI"), model.working.selectedEnzymes)
        assertEquals("Custom annotation", model.working.enzymeDescriptions["renamedi"])

        val renamed = model.pool.first { it.name == "RenamedI" }
        val error = model.editEnzyme(renamed, "BamHI", "GGATCC", 1, 5, true, "")
        assertTrue(error!!.contains("already exists"))
        assertEquals("RenamedI", model.pool.first { it.name == "RenamedI" }.name)
    }

    @Test
    fun effectiveCatalogAppliesPersistedBuiltinOverrides() {
        val prefs = UserPrefs(enzymeOverrides = mapOf("ecori" to EnzymeOverride("EcoX", "CCGG", 0, 2)))
        assertEquals(Enzyme("EcoX", "CCGG", 0, 2), prefs.findEnzyme("EcoX"))
        assertFalse(prefs.enzymePool().any { it.name == "EcoRI" })
    }

    @Test
    fun enzymeDescriptionResolverUsesDefaultsOverridesAndEffectiveDefinitions() {
        val ecoRi = Enzymes.require("EcoRI")
        assertTrue(UserPrefs().enzymeDescriptionFor(ecoRi).contains("MfeI"))

        val userDescription = UserPrefs(enzymeDescriptions = mapOf("ecori" to "Preferred cloning enzyme."))
        assertEquals("Preferred cloning enzyme.", userDescription.enzymeDescriptionFor(ecoRi))

        val edited = UserPrefs(
            enzymeOverrides = mapOf("ecori" to EnzymeOverride("EcoEdited", "CCGG", 0, 2)),
        )
        assertEquals(
            "EcoEdited is a DNA restriction enzyme that recognizes CCGG and creates 5' sticky ends.",
            edited.enzymeDescriptionFor(edited.findEnzyme("EcoEdited")!!),
        )
        assertEquals("", UserPrefs().enzymeDescriptionFor(Enzyme("CustomI", "GATC", 1, 3)))
    }
}
