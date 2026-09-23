package org.instagene.core

import java.nio.file.Files
import kotlin.test.*

class WorkflowLibraryTest {
    @Test
    fun roundTripPreservesIdentityTextAndStepOrder() {
        val file = Files.createTempDirectory("workflow-library").resolve("library.json").toFile()
        val store = WorkflowLibraryStore(file)
        assertEquals(WorkflowLibrary(), store.load())
        val protocol = WorkflowLibraryEntry(kind = WorkflowEntryKind.PROTOCOL, title = "Protocol α",
            instructions = "# Notes\nUnicode: μ", steps = listOf(
                WorkflowLibraryStep("Second", "line one\nline two"), WorkflowLibraryStep("First", "")))
        val procedure = WorkflowLibraryEntry(kind = WorkflowEntryKind.PROCEDURE, title = "General instructions")
        val library = WorkflowLibrary(entries = listOf(protocol, procedure))
        store.save(library)
        assertEquals(library, WorkflowLibraryStore(file).load())
        store.save(library.copy(entries = listOf(procedure)))
        assertEquals(listOf(procedure), WorkflowLibraryStore(file).load().entries)
    }

    @Test
    fun unreadableOrExternallyChangedFilesAreNeverOverwritten() {
        val file = Files.createTempDirectory("workflow-library").resolve("library.json").toFile()
        file.writeText("broken json")
        val store = WorkflowLibraryStore(file)
        assertFails { store.load() }
        assertFails { store.save(WorkflowLibrary()) }
        assertEquals("broken json", file.readText())
        file.writeText("""{"schemaVersion":99,"entries":[]}""")
        assertFails { store.load() }
        assertFails { store.save(WorkflowLibrary()) }
        file.writeText("""{"schemaVersion":1,"entries":[]}""")
        store.load()
        file.writeText("external changes")
        assertFails { store.save(WorkflowLibrary()) }
        assertEquals("external changes", file.readText())
    }

    @Test
    fun invalidEntriesDoNotReplaceSavedData() {
        val file = Files.createTempDirectory("workflow-library").resolve("library.json").toFile()
        val store = WorkflowLibraryStore(file)
        store.load()
        store.save(WorkflowLibrary())
        val before = file.readText()
        assertFails { store.save(WorkflowLibrary(entries = listOf(WorkflowLibraryEntry(kind = WorkflowEntryKind.PROTOCOL)))) }
        assertEquals(before, file.readText())
    }
}
