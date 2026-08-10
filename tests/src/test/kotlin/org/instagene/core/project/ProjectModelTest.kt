package org.instagene.core.project

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The engine project model: manifest load/save round-trips, path resolution
 * safety and the open/active/layout bookkeeping.
 */
class ProjectModelTest {

    private fun tempRoot(): File {
        val dir = File.createTempFile("instagene-project", "").let {
            it.delete()
            File(it.absolutePath).apply { mkdirs() }
        }
        return dir
    }

    @Test
    fun openOnBareFolderYieldsEmptyManifestAndIsNotYetAProjectRoot() {
        val root = tempRoot()
        val project = SeqProject.open(root)
        assertTrue(project.manifest.openDocs.isEmpty())
        assertNull(project.manifest.activeDoc)
        assertFalse(SeqProject.isProjectRoot(root))
        root.deleteRecursively()
    }

    @Test
    fun savingWritesManifestAndMarksTheFolderAsAProject() {
        val root = tempRoot()
        val plasmid = File(root, "pMini.gb").apply { writeText(">pMini\nACGT\n") }
        val project = SeqProject.open(root)
        project.addDocument(plasmid)
        project.setActive(plasmid)
        project.setLayout(ProjectLayout(activeToolTab = 3, treeSplitRatio = 0.4))
        project.save()

        assertTrue(SeqProject.isProjectRoot(root))
        val manifest = File(root, ".instagene/project.json")
        assertTrue(manifest.isFile)
        assertTrue(manifest.readText().contains("pMini.gb"))
        root.deleteRecursively()
    }

    @Test
    fun reloadRestoresDocsActiveTabAndLayout() {
        val root = tempRoot()
        val a = File(root, "a.fasta").apply { writeText(">a\nAAAA\n") }
        val b = File(root, "b.gb").apply { writeText(">b\nCCCC\n") }
        SeqProject.open(root).apply {
            addDocument(a)
            addDocument(b)
            setActive(b)
            setLayout(ProjectLayout(activeToolTab = 5, treeSplitRatio = 0.35))
            save()
        }

        val reloaded = SeqProject.open(root)
        assertEquals(listOf("a.fasta", "b.gb"), reloaded.manifest.openDocs)
        assertEquals("b.gb", reloaded.manifest.activeDoc)
        assertEquals(5, reloaded.manifest.layout.activeToolTab)
        assertEquals(0.35, reloaded.manifest.layout.treeSplitRatio)
        assertEquals(listOf(a.canonicalFile, b.canonicalFile), reloaded.openDocuments().map { it.canonicalFile })
        assertEquals(b.canonicalFile, reloaded.activeDocument()?.canonicalFile)
        root.deleteRecursively()
    }

    @Test
    fun corruptManifestFallsBackToDefaults() {
        val root = tempRoot()
        val manifest = SeqProject.manifestFile(root)
        manifest.parentFile.mkdirs()
        manifest.writeText("{ this is not valid json [")
        assertTrue(SeqProject.isProjectRoot(root))

        val project = SeqProject.open(root)
        assertTrue(project.manifest.openDocs.isEmpty())
        assertNull(project.manifest.activeDoc)
        root.deleteRecursively()
    }

    @Test
    fun resolvePathRefusesToEscapeTheProject() {
        val root = tempRoot()
        val project = SeqProject.open(root)
        assertNull(project.resolvePath("../outside.fasta"))
        assertNull(project.resolvePath("../../etc/passwd"))
        assertNull(project.resolvePath(""))
        assertEquals(
            File(root, "sub/file.fasta").absoluteFile.normalize(),
            project.resolvePath("sub/file.fasta"),
        )
        root.deleteRecursively()
    }

    @Test
    fun missingDocsAreSkippedByAccessors() {
        val root = tempRoot()
        val present = File(root, "here.fasta").apply { writeText(">here\nGG\n") }
        val project = SeqProject.open(root).apply {
            addDocument(present)
            addDocument(File(root, "gone.fasta"))
            setActive(File(root, "gone.fasta"))
        }
        assertEquals(listOf("here.fasta", "gone.fasta"), project.manifest.openDocs)
        assertEquals(listOf(present.canonicalFile), project.openDocuments().map { it.canonicalFile })
        assertNull(project.activeDocument())
        root.deleteRecursively()
    }

    @Test
    fun addDocumentIsIdempotentAndRemoveClearsActive() {
        val root = tempRoot()
        val doc = File(root, "x.fasta").apply { writeText(">x\nT\n") }
        val project = SeqProject.open(root)
        project.addDocument(doc)
        project.addDocument(doc)
        assertEquals(1, project.manifest.openDocs.size)

        project.setActive(doc)
        project.removeDocument(doc)
        assertTrue(project.manifest.openDocs.isEmpty())
        assertNull(project.manifest.activeDoc)
        root.deleteRecursively()
    }

    @Test
    fun setOpenDocumentsReplacesTheSetAndKeepsOnlyInProjectFiles() {
        val root = tempRoot()
        val a = File(root, "a.fasta").apply { writeText(">a\nAA\n") }
        val b = File(root, "b.fasta").apply { writeText(">b\nCC\n") }
        val outside = File.createTempFile("outside", ".fasta")
        val project = SeqProject.open(root).apply {
            addDocument(a)
            setActive(a)
        }

        project.setOpenDocuments(listOf(b, outside, a))
        assertEquals(listOf("b.fasta", "a.fasta"), project.manifest.openDocs)
        assertEquals("a.fasta", project.manifest.activeDoc)

        project.setOpenDocuments(listOf(b))
        assertEquals(listOf("b.fasta"), project.manifest.openDocs)
        assertNull(project.manifest.activeDoc)
        outside.delete()
        root.deleteRecursively()
    }

    @Test
    fun documentsOutsideTheProjectAreRejected() {
        val root = tempRoot()
        val outside = File.createTempFile("outside", ".fasta")
        val project = SeqProject.open(root)
        try {
            assertNull(project.relativePath(outside))
            kotlin.test.assertFailsWith<IllegalArgumentException> {
                project.addDocument(outside)
            }
        } finally {
            outside.delete()
            root.deleteRecursively()
        }
    }
}
