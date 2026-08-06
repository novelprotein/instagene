package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalToolsTest {

    @Test
    fun catalogIsNonEmptyWithUniqueIds() {
        assertTrue(ExternalTools.CATALOG.isNotEmpty())
        val ids = ExternalTools.CATALOG.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun stdinAndOutfileFlagsDerivedFromTemplate() {
        val stdinTool = ExternalTools.CATALOG.first { it.id == "seqkit-stats" }
        assertTrue(stdinTool.readsStdin)
        assertFalse(stdinTool.producesOutFile)

        val patternTool = ExternalTools.CATALOG.first { it.id == "seqkit-locate" }
        assertTrue(patternTool.argsTemplate.any { it.contains("{pattern}") })
    }

    @Test
    fun toolResultSucceededAndPayload() {
        val tool = ExternalTools.CATALOG.first()
        val ok = ToolResult(tool, "cmd", 0, "out", "")
        assertTrue(ok.succeeded)
        assertEquals("out", ok.payload())

        val fail = ToolResult(tool, "cmd", 1, "", "err")
        assertFalse(fail.succeeded)
    }

    @Test
    fun reportMentionsCatalog() {
        val report = ExternalTools.report()
        assertTrue(report.contains("External CLI tools"))
        assertTrue(report.contains("of ${ExternalTools.CATALOG.size} tools"))
        assertTrue(ExternalTools.CATALOG.any { report.contains(it.displayName) })
    }

    @Test
    fun unresolvedPatternPlaceholderDoesNotSucceed() {
        val tool = ExternalTools.CATALOG.first { it.id == "seqkit-locate" }
        val result = ExternalTools.run(tool, Seq(bases = "ACGTACGTACGT"), placeholders = emptyMap())
        assertTrue(result.exitCode != 0)
    }
}
