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
    fun externalToolPreviewDoesNotRequireAnInputFile() {
        val (code, output) = capture { Cli.run(listOf("tools", "--run", "primer3", "--preview")) }
        assertEquals(0, code)
        assertTrue(output.contains("primer3_core"))
        assertTrue(!output.contains("<input.fasta>"))
    }

    @Test
    fun advancedBuiltInPrimerDesignPrintsBackendAndCandidates() {
        val dir = createTempDirectory("cli-primers").toFile()
        try {
            val fasta = File(dir, "template.fa").apply { writeText(">template\n${"ACGT".repeat(100)}\n") }
            val (code, output) = capture {
                Cli.run(listOf("primers", "--from", "1", "--to", "400", "--advanced", "--backend", "builtin", fasta.absolutePath))
            }
            assertEquals(0, code)
            assertTrue(output.contains("Backend: BUILTIN"))
            assertTrue(output.contains("Amplicon 1..400"))
        } finally {
            dir.deleteRecursively()
        }
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
            assertTrue(infoOut.contains("19") || infoOut.lowercase().contains("bp"))

            val (rcCode, rcOut) = capture { Cli.run(listOf("revcomp", fa.absolutePath)) }
            assertEquals(0, rcCode)
            assertTrue(rcOut.contains("AAGCTT") || rcOut.contains("aagctt") || rcOut.contains(">"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun infoJsonIncludesIdentityAndSourceProvenance() {
        val dir = createTempDirectory("cli-json").toFile()
        try {
            val fa = File(dir, "s.fa").apply { writeText(">s\nACGT\n") }
            val (code, output) = capture { Cli.run(listOf("info", "--json", fa.absolutePath)) }
            assertEquals(0, code)
            assertTrue(output.contains("\"identity\""))
            assertTrue(output.contains("\"sourceSha256\""))
            assertTrue(output.contains("\"length\""))
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
                Cli.run(listOf("digest", "--enzymes", "EcoRI,HindIII", fa.absolutePath))
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
    fun goldenGateCommandAssemblesParts() {
        val dir = createTempDirectory("cli-golden-gate").toFile()
        try {
            val first = File(dir, "first.fa").apply { writeText(">first\nAAAA\n") }
            val second = File(dir, "second.fa").apply { writeText(">second\nCCCC\n") }
            val (code, out) = capture {
                Cli.run(listOf(
                    "golden-gate",
                    "--parts", "${first.absolutePath},${second.absolutePath}",
                    "--overhangs", "A,B,A",
                    "--to", "fasta",
                ))
            }
            assertEquals(0, code)
            assertTrue(out.contains("AAAACCCC"))
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

    @Test
    fun envFileSuppliesDefaultsAndCommandLineWins() {
        val dir = createTempDirectory("cli-env").toFile()
        try {
            val env = File(dir, "defaults.env")
            env.writeText("# default enzyme filter\nfilter=eco\n")
            val (defCode, defOut) = capture { Cli.run(listOf("enzymes", "--env", env.absolutePath)) }
            assertEquals(0, defCode)
            assertTrue(defOut.contains("EcoRI", ignoreCase = true))
            assertTrue(!defOut.contains("BamHI"))

            val (winCode, winOut) = capture {
                Cli.run(listOf("enzymes", "--env", env.absolutePath, "--filter", "bam"))
            }
            assertEquals(0, winCode)
            assertTrue(winOut.contains("BamHI", ignoreCase = true))
            assertTrue(!winOut.contains("EcoRI"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun missingEnvFileFailsCleanly() {
        val (code, out) = capture { Cli.run(listOf("info", "--env", "/no/such/env/file")) }
        assertEquals(1, code)
        assertTrue(out.contains("env file not found"))
    }

    @Test
    fun versionReportsBundledBuildVersion() {
        val (code, out) = capture { Cli.run(listOf("--version", "--no-colors")) }
        assertEquals(0, code)
        assertTrue(out.trim().matches(Regex("""InstaGene \d+(\.\d+)+(\+[\w-]+)?""")))
        assertTrue(!out.contains("\u001b["))
    }

    @Test
    fun guiForwardsFilesAndExitCode() {
        val dir = createTempDirectory("cli-gui").toFile()
        try {
            val argsFile = File(dir, "args.txt")
            val fakeGui = File(dir, "fake-gui.sh")
            fakeGui.writeText($$"#!/bin/sh\necho \"$@\" > \"$$argsFile\"\nexit 42\n")
            fakeGui.setExecutable(true)
            val (code, _) = capture { Cli.run(listOf("gui", "--launcher", fakeGui.absolutePath, "plasmid.gb", "gfp.fa")) }
            assertEquals(42, code)
            val received = argsFile.readText().trim()
            assertTrue(received.contains("plasmid.gb"))
            assertTrue(received.contains("gfp.fa"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun guiWithMissingLauncherFailsCleanly() {
        val (code, out) = capture { Cli.run(listOf("gui", "--launcher", "/no/such/gui", "x.fa")) }
        assertEquals(1, code)
        assertTrue(out.contains("instagene:"))
    }

    @Test
    fun guiResolutionHonorsInstageneGuiEnv() {
        val dir = createTempDirectory("cli-gui-env").toFile()
        try {
            val fakeGui = File(dir, "fake-gui.sh")
            fakeGui.writeText("#!/bin/sh\nexit 0\n")
            fakeGui.setExecutable(true)
            val resolved = Cli.resolveGuiLauncher(
                env = mapOf("INSTAGENE_GUI" to fakeGui.absolutePath, "PATH" to "/bin"),
                pathVar = "/bin",
            )
            assertEquals(fakeGui.absolutePath, resolved)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun guiResolutionFindsLauncherOnPath() {
        val dir = createTempDirectory("cli-gui-path").toFile()
        try {
            val fakeGui = File(dir, "instagene")
            fakeGui.writeText("#!/bin/sh\nexit 0\n")
            fakeGui.setExecutable(true)
            val resolved = Cli.resolveGuiLauncher(env = mapOf(), pathVar = dir.absolutePath)
            assertEquals(fakeGui.absolutePath, resolved)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun guiResolutionReturnsNullWhenNothingFound() {
        val resolved = Cli.resolveGuiLauncher(
            env = mapOf(),
            pathVar = "/nonexistent-dir-that-does-not-exist",
            installCandidates = emptyList(),
        )
        assertEquals(null, resolved)
    }

    @Test
    fun guiResolutionFallsBackToInstallLocation() {
        val dir = createTempDirectory("cli-gui-install").toFile()
        try {
            val installed = File(dir, "InstaGene")
            installed.writeText("#!/bin/sh\nexit 0\n")
            installed.setExecutable(true)
            val resolved = Cli.resolveGuiLauncher(
                env = mapOf(),
                pathVar = "/nonexistent-dir",
                installCandidates = listOf(installed.absolutePath),
            )
            assertEquals(installed.absolutePath, resolved)
        } finally {
            dir.deleteRecursively()
        }
    }
}
