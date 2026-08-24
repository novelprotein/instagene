package org.instagene.core.io

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.instagene.core.CloningMethod
import org.instagene.core.RecipeOperation
import org.instagene.core.WorkflowRecipes
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Guards the small, public synthetic corpus used by deterministic workflow and format tests. */
class FixtureManifestTest {

    private val manifest = Json.parseToJsonElement(resourceText("manifest.json")).jsonObject

    @Test
    fun fixtureManifestIsVersionedLicensedAndHashVerified() {
        assertEquals(1, manifest.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertEquals("MIT", manifest.getValue("license").jsonPrimitive.content)
        assertTrue(manifest.getValue("originPolicy").jsonPrimitive.content.contains("synthetic", ignoreCase = true))

        val fixtures = manifest.getValue("fixtures").jsonArray
        assertTrue(fixtures.size >= 4)
        fixtures.forEach { element ->
            val entry = element.jsonObject
            val path = entry.getValue("path").jsonPrimitive.content
            assertTrue(!path.startsWith('/') && ".." !in path, "Fixture path must stay inside the corpus: $path")
            assertEquals("MIT", entry.getValue("license").jsonPrimitive.content)
            assertTrue(entry.getValue("origin").jsonPrimitive.content.contains("Synthetic", ignoreCase = true))
            assertEquals(entry.getValue("sha256").jsonPrimitive.content, sha256(resourceBytes(path)), "Hash mismatch for $path")
        }
    }

    @Test
    fun alignmentFixturesExerciseEveryNativeInterchangeParser() {
        val fixtures = manifest.getValue("fixtures").jsonArray.map { it.jsonObject }
        val expected = listOf(AlignmentFormat.CLUSTAL, AlignmentFormat.STOCKHOLM, AlignmentFormat.PHYLIP)
        expected.forEach { format ->
            val entry = fixtures.first { it.getValue("format").jsonPrimitive.content.equals(format.name, ignoreCase = true) }
            val rows = AlignmentIO.parse(resourceText(entry.getValue("path").jsonPrimitive.content))
            assertEquals(listOf("reference", "sample"), rows.map { it.name })
            assertEquals(listOf("AC-GTT", "ATAGTT"), rows.map { it.bases })
        }
    }

    @Test
    fun legacyRecipeFixtureMigratesToTheTypedRestrictionOperation() {
        val recipe = WorkflowRecipes.decode(resourceText("workflows/restriction-v1.json"))

        assertEquals(WorkflowRecipes.SCHEMA_VERSION, recipe.schemaVersion)
        assertEquals(
            RecipeOperation.RestrictionCloning(listOf("EcoRI", "HindIII"), "synthetic_clone"),
            recipe.operationSpec,
        )
        assertEquals(CloningMethod.RESTRICTION.name, recipe.operation)
    }

    private fun resourceText(path: String): String = resourceBytes(path).decodeToString()

    private fun resourceBytes(path: String): ByteArray = requireNotNull(
        javaClass.getResourceAsStream("/fixtures/$path"),
    ) { "Missing test fixture /fixtures/$path" }.use { it.readBytes() }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
