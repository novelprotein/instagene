package org.instagene.core.project

import java.io.File
import java.nio.file.Files
import kotlin.test.*

class CollectionStoreTest {
    private fun withStore(test: (CollectionStore, File, SeqProject) -> Unit) {
        val root = Files.createTempDirectory("instagene-collections-test").toFile()
        try {
            val project = SeqProject.create(root)
            test(CollectionStore(project), File(root, "${SeqProject.MANIFEST_DIR}/collections.json"), project)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun missingFileLoadsEmptyAndSupportsRepeatedSaves() = withStore { store, _, project ->
        assertEquals(CollectionDocument(), store.load())
        val document = CollectionDocument(collections = listOf(SequenceCollection("Plasmids α")))
        store.save(document)
        assertEquals(document, CollectionStore(project).load())
        store.save(CollectionDocument())
        assertEquals(CollectionDocument(), store.load())
    }

    @Test
    fun failedLoadsPreventSavingAndPreserveTheFile() = withStore { store, file, _ ->
        file.parentFile.mkdirs()
        for (text in listOf("broken json", """{"schemaVersion":99,"collections":[]}""")) {
            file.writeText(text)
            assertFails { store.load() }
            assertFails { store.save(CollectionDocument()) }
            assertEquals(text, file.readText())
        }
        // A failure also invalidates a previous successful load.
        file.delete()
        store.load()
        file.writeText("broken again")
        assertFails { store.load() }
        assertFails { store.save(CollectionDocument()) }
        assertEquals("broken again", file.readText())
    }

    @Test
    fun externalEditsAndUnsupportedDocumentsCannotBeSaved() = withStore { store, file, _ ->
        assertFails { store.save(CollectionDocument()) }
        store.load()
        store.save(CollectionDocument())
        val original = file.readText()
        assertFails { store.save(CollectionDocument(schemaVersion = 99)) }
        assertEquals(original, file.readText())
        file.writeText("external changes")
        assertFails { store.save(CollectionDocument()) }
        assertEquals("external changes", file.readText())
    }
}
