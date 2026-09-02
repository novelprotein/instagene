package org.instagene.app.cli

import org.instagene.core.Topology
import org.instagene.core.TestSequenceFixtures
import org.instagene.core.WorkflowRecipes
import org.instagene.core.GenericZipElnAdapter
import org.instagene.core.io.SeqFormat
import org.instagene.core.io.SeqIO
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun elnBundleCliWritesVerifiedVendorNeutralBundleAndJsonManifest() {
        val dir = createTempDirectory("cli-eln").toFile()
        try {
            val fasta = File(dir, "plasmid.fa").apply { writeText(">pDemo\nACGTACGT\n") }
            val bundle = File(dir, "handoff.zip")
            val (code, output) = capture {
                Cli.run(listOf("eln-bundle", "--out", bundle.absolutePath, "--json", fasta.absolutePath))
            }
            assertEquals(0, code)
            assertTrue(bundle.isFile)
            assertTrue(output.contains("\"bundleType\": \"instagene-generic-eln-zip\""))
            assertTrue(GenericZipElnAdapter.verify(bundle).valid)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun externalToolPreviewDoesNotRequireAnInputFile() {
        val (code, output) = capture { Cli.run(listOf("tools", "--run", "primer3", "--preview")) }
        assertEquals(0, code)
        assertTrue(output.contains("primer3_core"))
        assertTrue(!output.contains("<input.fasta>"))
    }

    @Test
    fun externalToolHealthCliReturnsStructuredRecoveryGuidanceWithoutAnInputFile() {
        val (code, output) = capture {
            Cli.run(listOf("tools", "--health", "--json", "--run", "primer3", "--timeout", "1"))
        }

        assertEquals(0, code)
        assertTrue(output.contains("\"id\": \"primer3\""))
        assertTrue(output.contains("\"action\""))
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
    fun qualityAwarePrimerCliAcceptsFastaQualManualRegionsAndJsonReports() {
        val dir = createTempDirectory("cli-quality-primers").toFile()
        try {
            val fasta = File(dir, "template.fa").apply { writeText(">template\n${"ACGT".repeat(50)}\n") }
            val qual = File(dir, "template.qual").apply {
                writeText(">template\n${List(200) { if (it == 4) 5 else 40 }.joinToString(" ")}\n")
            }
            val report = File(dir, "primer-report.json")
            val (code, output) = capture {
                Cli.run(
                    listOf(
                        "primers", "--from", "1", "--to", "200", "--qual", qual.absolutePath,
                        "--low-quality", "40-45", "--report", report.absolutePath, "--advanced", "--json", fasta.absolutePath,
                    ),
                )
            }

            assertEquals(0, code)
            assertTrue(output.contains("\"minimumPhred\""))
            assertTrue(output.contains("template.qual"))
            assertTrue(report.isFile)
            assertTrue(report.readText().contains("manualExcludedRegions"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sequencingPrimerCliOffersDirectionSpecificCandidates() {
        val dir = createTempDirectory("cli-sequencing-primers").toFile()
        try {
            val fasta = File(dir, "template.fa").apply { writeText(">template\n${"GATTACAGC".repeat(30)}\n") }
            val (code, output) = capture {
                Cli.run(
                    listOf(
                        "primers", "--from", "20", "--to", "80", "--mode", "sequencing", "--direction", "forward",
                        "--advanced", fasta.absolutePath,
                    ),
                )
            }

            assertEquals(0, code)
            assertTrue(output.contains("Mode: SEQUENCING"))
            assertTrue(output.contains("candidate_SEQ_F_"))
            assertTrue(!output.contains("candidate_SEQ_R_"))
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
        val (code, out) = capture { Cli.run(listOf("sample", "GFP_Aequorea_NCBI_reference")) }
        assertEquals(0, code)
        assertTrue(out.contains(">"))
        assertTrue(out.contains("ATG") || out.contains("GAATTC"))
    }

    @Test
    fun sampleListingIncludesSourceCitations() {
        val (code, out) = capture { Cli.run(listOf("sample")) }
        assertEquals(0, code)
        assertFalse(out.contains("pInstaGene_demo"))
        assertFalse(out.contains("synthetic_chromatogram"))
        assertFalse(out.contains("alignment_demo"))
        assertTrue(out.contains("pBR322_NCBI"))
        assertTrue(out.contains("J01749.1"))
        assertTrue(out.contains("pUC19_NCBI_reference"))
        assertTrue(out.contains("M77789.2"))
        assertTrue(out.contains("GFP_Aequorea_NCBI_reference"))
        assertTrue(out.contains("L29345.1"))
        assertTrue(out.contains("pGFPuv_NCBI_reference"))
        assertTrue(out.contains("U62636.1"))
    }

    @Test
    fun realPbr322SampleCanBeWrittenAsGenBankWithFeatures() {
        val (code, out) = capture { Cli.run(listOf("sample", "pBR322_NCBI", "--to", "genbank")) }
        assertEquals(0, code)
        assertTrue(out.contains("VERSION      J01749.1"))
        assertTrue(out.contains("DEFINITION  Cloning vector pBR322, complete sequence."))
        assertTrue(out.contains("FEATURES"))
        assertTrue(out.contains("/product=\"beta-lactamase\""))
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
    fun offlineNcbiCacheModeFailsWithoutContactingTheNetworkWhenNoEntryExists() {
        val directory = createTempDirectory("cli-ncbi-cache").toFile()
        try {
            val (code, output) = capture {
                Cli.run(
                    listOf(
                        "ncbi-fetch", "--accession", "J01636.1",
                        "--cache-dir", directory.absolutePath,
                        "--cache-mode", "cache-only",
                    ),
                )
            }

            assertEquals(1, code)
            assertTrue(output.contains("No verified cached response"))
        } finally {
            directory.deleteRecursively()
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
                        "--overhangs", "GGAG,AATG,GGAG",
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
    fun dotPlotAndRepeatCommandsExportResearcherReadableArtifacts() {
        val dir = createTempDirectory("cli-repeat-analysis").toFile()
        try {
            val sequence = File(dir, "repeat.fa").apply { writeText(">repeat\nAAAAATGCAAAAGCATTTT\n") }
            val plot = File(dir, "plot.svg")
            val repeats = File(dir, "repeats.json")

            val (plotCode, plotOutput) = capture {
                Cli.run(
                    listOf(
                        "dotplot", "--word-size", "4", "--inverted", "--format", "svg",
                        "--out", plot.absolutePath, sequence.absolutePath,
                    ),
                )
            }
            assertEquals(0, plotCode)
            assertTrue(plotOutput.contains("Wrote dot-plot svg"))
            assertTrue(plot.readText().contains("<svg"))

            val (repeatCode, repeatOutput) = capture {
                Cli.run(
                    listOf(
                        "repeats", "--min-length", "4", "--format", "json",
                        "--out", repeats.absolutePath, sequence.absolutePath,
                    ),
                )
            }
            assertEquals(0, repeatCode)
            assertTrue(repeatOutput.contains("Wrote repeat analysis json"))
            assertTrue(repeats.readText().contains("INVERTED"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun alignmentCommandExportsInterchangeFormatsAndPortableImages() {
        val dir = createTempDirectory("cli-alignment-export").toFile()
        try {
            val reference = File(dir, "reference.fa").apply { writeText(">reference\nACGTACGT\n") }
            val query = File(dir, "query.fa").apply { writeText(">query\nACGTTACGT\n") }
            val clustal = File(dir, "result.aln")
            val stockholm = File(dir, "result.sto")
            val phylip = File(dir, "result.phy")
            val svg = File(dir, "result.svg")
            val png = File(dir, "result.png")

            listOf(clustal, stockholm, phylip, svg, png).forEach { output ->
                val (code, text) = capture {
                    Cli.run(listOf("align", "--query", query.absolutePath, "--out", output.absolutePath, reference.absolutePath))
                }
                assertEquals(0, code, text)
                assertTrue(text.contains("Wrote alignment"))
                assertTrue(output.isFile && output.length() > 0L)
            }
            assertEquals(2, SeqIO.readAll(clustal).size)
            assertEquals(2, SeqIO.readAll(stockholm).size)
            assertEquals(2, SeqIO.readAll(phylip).size)
            assertTrue(svg.readText().contains("<svg"))
            assertTrue(ImageIO.read(ByteArrayInputStream(png.readBytes())).width > 0)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cloningRecipesCanBeCapturedAndReplayedThroughTheCli() {
        val dir = createTempDirectory("cli-recipe").toFile()
        try {
            val backbone = File(dir, "vector.gb").apply {
                writeText(SeqIO.write(TestSequenceFixtures.restrictionBackbone.copy(topology = Topology.CIRCULAR), SeqFormat.GENBANK))
            }
            val insert = File(dir, "insert.fa").apply { writeText(SeqIO.write(TestSequenceFixtures.restrictionInsert, SeqFormat.FASTA)) }
            val recipe = File(dir, "clone.recipe.json")
            val firstProduct = File(dir, "first.gb")
            val replayedProduct = File(dir, "replayed.gb")

            val (captureCode, captureOutput) = capture {
                Cli.run(
                    listOf(
                        "plasmid", "--backbone", backbone.absolutePath, "--insert", insert.absolutePath,
                        "--enzymes", "EcoRI,HindIII", "--name", "pGFP_recipe", "--recipe", recipe.absolutePath,
                        "--out", firstProduct.absolutePath,
                    ),
                )
            }
            assertEquals(0, captureCode)
            assertTrue(recipe.isFile)
            assertTrue(captureOutput.contains("reproducibility recipe"))

            val (replayCode, replayOutput) = capture {
                Cli.run(
                    listOf(
                        "recipe", "replay", "--file", recipe.absolutePath,
                        "--inputs", "${backbone.absolutePath},${insert.absolutePath}",
                        "--out", replayedProduct.absolutePath, "--json",
                    ),
                )
            }
            assertEquals(0, replayCode)
            assertTrue(replayOutput.contains("\"status\": \"SUCCEEDED\""))
            assertEquals(SeqIO.read(firstProduct).bases, SeqIO.read(replayedProduct).bases)

            val externalRecipe = WorkflowRecipes.decode(recipe.readText()).copy(externalTools = mapOf("primer3" to "2.6.1"))
            recipe.writeText(WorkflowRecipes.encode(externalRecipe))
            val (blockedCode, blockedOutput) = capture {
                Cli.run(listOf("recipe", "replay", "--file", recipe.absolutePath, "--inputs", "${backbone.absolutePath},${insert.absolutePath}"))
            }
            assertEquals(1, blockedCode)
            assertTrue(blockedOutput.contains("explicit external-tool approval"))

            val (approvedCode, _) = capture {
                Cli.run(
                    listOf(
                        "recipe", "replay", "--file", recipe.absolutePath,
                        "--inputs", "${backbone.absolutePath},${insert.absolutePath}", "--allow-external",
                    ),
                )
            }
            assertEquals(0, approvedCode)
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
