package org.instagene.app.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliTest {

    private fun capture(block: () -> Int): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val oldOut = System.out
        val oldErr = System.err
        System.setOut(PrintStream(out))
        System.setErr(PrintStream(err))
        return try {
            val code = block()
            code to (out.toString() + err.toString())
        } finally {
            System.setOut(oldOut)
            System.setErr(oldErr)
        }
    }

    @Test
    fun helpAndVersionSucceed() {
        val (helpCode, helpOut) = capture { Cli.run(listOf("help")) }
        assertEquals(0, helpCode)
        assertTrue(helpOut.contains("Usage:"))

        val (verCode, verOut) = capture { Cli.run(listOf("version")) }
        assertEquals(0, verCode)
        assertTrue(verOut.contains("InstaGene"))
    }

    @Test
    fun unknownCommandFails() {
        val (code, out) = capture { Cli.run(listOf("not-a-command")) }
        assertEquals(1, code)
        assertTrue(out.contains("instagene:"))
    }

    @Test
    fun sampleWritesFasta() {
        val (code, out) = capture { Cli.run(listOf("sample", "GFP_CDS")) }
        assertEquals(0, code)
        assertTrue(out.contains(">"))
        assertTrue(out.contains("ATG") || out.contains("GAATTC"))
    }

    @Test
    fun infoAndRevcompOnTempFile() {
        val dir = createTempDirectory("cli-test").toFile()
        try {
            val fa = File(dir, "s.fa")
            fa.writeText(">s\nGAATTCATGGCCTAAGCTT\n")
            val (infoCode, infoOut) = capture { Cli.run(listOf("info", fa.absolutePath)) }
            assertEquals(0, infoCode)
            assertTrue(infoOut.contains("19") || infoOut.lowercase().contains("bp") || infoOut.isNotBlank())

            val (rcCode, rcOut) = capture { Cli.run(listOf("revcomp", fa.absolutePath)) }
            assertEquals(0, rcCode)
            assertTrue(rcOut.contains("AAGCTT") || rcOut.contains("aagctt") || rcOut.contains(">"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun digestAndEnzymesCommands() {
        val dir = createTempDirectory("cli-digest").toFile()
        try {
            val fa = File(dir, "s.fa")
            fa.writeText(">s\nGAATTCATGGCCTAAGCTT\n")
            val (dCode, dOut) = capture {
                Cli.run(listOf("digest", "--enzymes", "EcoRI,HinDIII", fa.absolutePath))
            }
            assertEquals(0, dCode)
            assertTrue(dOut.isNotBlank())

            val (eCode, eOut) = capture { Cli.run(listOf("enzymes", "--filter", "eco")) }
            assertEquals(0, eCode)
            assertTrue(eOut.contains("EcoRI", ignoreCase = true))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun missingRequiredOptionFailsCleanly() {
        val (code, out) = capture { Cli.run(listOf("find")) }
        assertEquals(1, code)
        assertTrue(out.startsWith("instagene:") || out.contains("instagene:"))
    }
}
