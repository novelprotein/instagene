package org.instagene.app.cli

import kotlinx.serialization.json.Json
import org.instagene.core.*
import org.instagene.core.io.*
import java.io.File
import java.io.IOException
import kotlin.system.measureNanoTime

/**
 * The command-line half of InstaGene. Every operation the GUI offers is also
 * reachable here, so sequences can be piped through shell pipelines.
 */
private fun out(value: Any? = "") {
    print(value)
    print('\n')
}

object Cli {

    fun run(argv: List<String>): Int {
        val command = argv.firstOrNull()?.lowercase() ?: "help"
        val colors = Colors.enabled(argv.drop(1).contains("--no-colors"))
        return try {
            val args = Args(envDefaults(argv.drop(1)) + argv.drop(1))
            if (command == "gui") {
                gui(args)
            } else {
                dispatch(command, args)
                0
            }
        } catch (e: CliException) {
            System.err.println(Colors.red("instagene: ${e.message}", colors))
            1
        } catch (e: AssemblyException) {
            System.err.println(Colors.red("instagene: ${e.message}", colors))
            1
        } catch (e: SeqIOException) {
            System.err.println(Colors.red("instagene: ${e.message}", colors))
            1
        } catch (e: IOException) {
            System.err.println(Colors.red("instagene: ${e.message ?: "I/O error"}", colors))
            1
        } catch (e: IllegalArgumentException) {
            System.err.println(Colors.red("instagene: ${e.message}", colors))
            1
        }
    }

    private fun dispatch(command: String, args: Args) {
        when (command) {
            "help", "--help", "-h" -> out(usage(args.colors))
            "version", "--version" -> out(Colors.bold("InstaGene ${Version.VERSION}", args.colors))
            "enzymes" -> listEnzymes(args)
            "tools" -> externalTools(args)
            "sample" -> sample(args)

            "info", "stats" -> info(load(args), args)
            "revcomp", "rc" -> emit(load(args).reverseComplement(), args)
            "complement" -> emit(load(args).complement(), args)
            "transcribe" -> emit(SeqOps.transcribe(load(args)), args)
            "backtranscribe" -> emit(SeqOps.backTranscribe(load(args)), args)
            "translate" -> translate(load(args), args)
            "gc" -> gc(load(args))
            "tm" -> tm(load(args))
            "orf" -> orfs(load(args), args)
            "find" -> find(load(args), args)
            "align" -> align(load(args), args)
            "dotplot", "dot-plot" -> dotPlot(load(args), args)
            "repeats", "repeat" -> repeats(load(args), args)
            "gel" -> gel(load(args), args)
            "identity" -> identity(load(args), args)
            "dilute" -> dilute(args)
            "mix" -> mix(args)
            "blast-url" -> blastUrl(load(args), args)
            "ncbi-search" -> ncbiSearch(args)
            "ncbi-fetch" -> ncbiFetch(args)
            "bench", "benchmark" -> benchmark(args)
            "eln-bundle", "eln" -> elnBundle(load(args), args)
            "digest" -> digest(load(args), args)
            "sites" -> uniqueSites(load(args))
            "edit" -> emit(edit(load(args), args), args)
            "extract" -> extract(load(args), args)
            "topology" -> topology(load(args), args)
            "annotate" -> annotate(load(args), args)
            "primers" -> primers(load(args), args)
            "convert" -> emit(load(args), args)
            "plasmid" -> plasmid(args)
            "gibson" -> gibson(args)
            "golden-gate", "goldengate" -> goldenGate(args)
            "recombine" -> recombine(args)
            "recipe", "recipes" -> recipe(args)

            else -> throw CliException("Unknown command '$command'. Run `instagene help`.")
        }
    }

    // ------------------------------------------------------------------- input

    /** Loads the subject sequence from a positional path, `--in`, or stdin. */
    private fun load(args: Args): Seq {
        val path = args.opt("in") ?: args.positional(0)
        val seq = when {
            path != null -> {
                val file = File(path)
                if (!file.exists()) throw CliException("No such file: $path")
                SequenceIdentity.withSourceFile(SeqIO.read(file), file)
            }

            else -> {
                val text = System.`in`.readBytes().decodeToString()
                if (text.isBlank()) {
                    throw CliException("No input. Pass a file path, or pipe FASTA/GenBank on stdin.")
                }
                SeqIO.parse(text)
            }
        }
        return if (args.flag("circular")) seq.withTopology(Topology.CIRCULAR) else seq
    }

    private fun loadFile(path: String, label: String): Seq {
        val file = File(path)
        if (!file.exists()) throw CliException("$label file not found: $path")
        return SeqIO.read(file)
    }

    // ----------------------------------------------------------------- options

    /**
     * Expands `--env FILE` into option tokens (`--key value` pairs) that are
     * placed *before* the command line, so flag values given directly on the
     * command line always win. Lines are `KEY=VALUE`, with `#` comments and
     * bare keys treated as flags (`true`).
     */
    private fun envDefaults(argv: List<String>): List<String> {
        val path = envFile(argv) ?: return emptyList()
        val file = File(path)
        if (!file.isFile) throw CliException("env file not found: $path")
        return file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .flatMap { line ->
                val eq = line.indexOf('=')
                if (eq < 0) listOf("--$line", "true")
                else listOf("--${line.substring(0, eq).trim().lowercase()}", line.substring(eq + 1).trim())
            }
    }

    private fun envFile(argv: List<String>): String? {
        var i = 0
        while (i < argv.size) {
            val arg = argv[i]
            if (arg.startsWith("--env=")) return arg.substringAfter('=')
            if (arg == "--env") {
                val next = argv.getOrNull(i + 1)
                if (next != null && !next.startsWith("--")) return next
            }
            i++
        }
        return null
    }

    // ------------------------------------------------------------------ output

    private fun emit(seq: Seq, args: Args) {
        val outPath = args.opt("out")
        val format = when (val requested = args.opt("to")?.lowercase()) {
            null -> outPath?.let { SeqIO.formatOf(File(it)) } ?: SeqFormat.FASTA
            "fasta", "fa" -> SeqFormat.FASTA
            "genbank", "gb", "gbk" -> SeqFormat.GENBANK
            "gff", "gff3" -> SeqFormat.GFF3
            else -> throw CliException("Unknown format '$requested' (use fasta, genbank, or gff3)")
        }
        val text = SeqIO.write(seq, format)
        if (outPath != null) {
            File(outPath).writeText(text)
            System.err.println(Colors.green("Wrote ${seq.length} bp to $outPath (${format.displayName})", args.colors))
        } else {
            print(text)
        }
    }

    // ---------------------------------------------------------------- commands

    private fun info(seq: Seq, args: Args) {
        if (args.flag("json")) out(Reports.sequenceSummaryJson(seq))
        else print(Reports.seqSummary(seq))
    }

    private fun benchmark(args: Args) {
        val seq = try {
            load(args)
        } catch (_: CliException) {
            out("No input file. Fetching U03453 from NCBI...")
            out()
            NcbiClient().fetchGenBank("U03453")
        }
        val len = seq.length
        val unit = if (seq.kind == SeqKind.PROTEIN) "aa" else "bp"
        out("== Benchmark: ${seq.name} ($len $unit) ==")
        out()

        val repeats = 3
        fun bench(label: String, block: () -> Unit): Double {
            var total = 0L
            repeat(repeats) { total += measureNanoTime { block() } }
            val ms = (total / repeats) / 1_000_000.0
            out("  %-40s %8.1f ms".format(label, ms))
            return ms
        }

        if (seq.kind != SeqKind.PROTEIN) {
            // --- SequenceStatistics ---
            out("--- SequenceStatistics ---")
            bench("computeStats") { SequenceStatistics.computeStats(seq) }
            if (len >= 200) {
                bench("gcContentProfile(100, 50)") { SequenceStatistics.gcContentProfile(seq, 100, 50) }
                bench("gcSkewProfile(100, 50)") { SequenceStatistics.gcSkewProfile(seq, 100, 50) }
                bench("cumulativeGcSkew(100, 50)") { SequenceStatistics.cumulativeGcSkew(seq, 100, 50) }
            }
            if (len >= 40) {
                bench("meltingTempProfile(20, 10)") { SequenceStatistics.meltingTempProfile(seq, 20, 10) }
            }
            bench("nucleotideComposition") { SequenceStatistics.nucleotideComposition(seq) }
            bench("codonUsage") { SequenceStatistics.codonUsage(seq) }
            bench("dinucleotideFrequencies") { SequenceStatistics.dinucleotideFrequencies(seq) }
            bench("tandemRepeats(1..10, minRepeats=3)") {
                SequenceStatistics.tandemRepeats(seq, 1, 10, 3)
            }
            out()

            // --- SeqOps ---
            out("--- SeqOps ---")
            bench("transcribe") { SeqOps.transcribe(seq) }
            bench("backTranscribe") { SeqOps.backTranscribe(seq) }
            bench("translateBases(frame=0)") { SeqOps.translateBases(seq.bases, 0) }
            bench("baseCounts") { SeqOps.baseCounts(seq.bases) }
            bench("find(ATGCATGC)") { SeqOps.find(seq, "ATGCATGC") }
            out()

            // --- Digest ---
            out("--- Digest ---")
            val eco = Enzymes.require("EcoRI")
            bench("cutSites(EcoRI)") { Digest.cutSites(seq, eco) }
            bench("cutCounts(ALL ${Enzymes.ALL.size} enzymes)") { Digest.cutCounts(seq, Enzymes.ALL) }
            bench("digest(EcoRI + HindIII)") {
                Digest.digest(seq, listOf(eco, Enzymes.require("HindIII")))
            }
            out()

            // --- Enzyme Analysis ---
            if (len >= 10_000) {
                out("--- Enzyme Analysis ---")
                bench("reports(ALL)") { EnzymeAnalysis.reports(seq, Enzymes.ALL) }
                bench("unique(ALL)") { EnzymeAnalysis.unique(seq, Enzymes.ALL) }
                bench("absent(ALL)") { EnzymeAnalysis.absent(seq, Enzymes.ALL) }
                val regionEnd = (100_000).coerceAtMost(len - 1)
                bench("diagnosticSites(0..$regionEnd)") {
                    EnzymeAnalysis.diagnosticSites(seq, 0..regionEnd, Enzymes.ALL)
                }
                bench("silentSites(0..$regionEnd)") {
                    EnzymeAnalysis.silentSites(seq, 0..regionEnd, Enzymes.ALL)
                }
                out()
            }

            // --- ORFs and Search ---
            out("--- ORFs and Search ---")
            bench("findOrfs(30aa)") { SeqOps.findOrfs(seq, 30) }
            bench("orfDensity(200, 100)") { SequenceStatistics.orfDensity(seq, 200, 100) }
            bench("AdvancedSearch.find(ATGC, DNA, 2strands)") {
                AdvancedSearch.find(seq, SearchRequest("ATGC", SearchMode.DNA_DEGENERATE, bothStrands = true))
            }
            bench("AdvancedSearch.find(MVSK, AA, 2strands)") {
                AdvancedSearch.find(seq, SearchRequest("MVSK", SearchMode.AMINO_ACID, bothStrands = true))
            }
            out()

            // --- Primer Thermodynamics (on a 30-nt primer extracted from the sequence) ---
            if (len >= 30) {
                out("--- Primer Thermodynamics (30 nt) ---")
                val primer = seq.bases.substring(0, 30)
                val rc = Alphabet.reverseComplement(primer)
                bench("thermodynamicResult") { PrimerThermodynamics.thermodynamicResult(primer) }
                bench("selfDimer") { PrimerThermodynamics.selfDimer(primer) }
                bench("heteroDimer(primer, rc)") { PrimerThermodynamics.heteroDimer(primer, rc) }
                bench("assessHairpin") { PrimerThermodynamics.assessHairpin(primer) }
                bench("fullScreen") { PrimerThermodynamics.fullScreen(primer) }
                out()
            }

            // --- Site Domestication ---
            if (len >= 100) {
                out("--- Site Domestication ---")
                bench("findInternalSites(8 enzymes)") { SiteDomestication.findInternalSites(seq) }
                bench("suggestEnzyme(8 enzymes)") { SiteDomestication.suggestEnzyme(seq) }
                bench("domesticate(8 enzymes)") {
                    SiteDomestication.domesticate(seq, SiteDomestication.GOLDEN_GATE_ENZYMES)
                }
                out()
            }

            // --- Codon Optimization ---
            out("--- Codon Optimization ---")
            bench("reverseTranslate(1000 aa)") {
                val aaBases = seq.bases.take(3000).chunked(3).joinToString("") {
                    SeqOps.translateBases(it, 0).first().toString()
                }
                CodonDesign.reverseTranslate(Seq(bases = aaBases, name = "protein", kind = SeqKind.PROTEIN))
            }
            bench("optimize(codon usage)") { CodonDesign.optimize(seq) }
            out()
        } else {
            out("--- Amino Acid Composition ---")
            bench("aminoAcidComposition") { SequenceStatistics.aminoAcidComposition(seq) }
            out()
        }

        out("Done. (${repeats} repeats, averaged)")
    }

    /** Writes an offline, vendor-neutral ELN/LIMS ZIP handoff for a sequence and optional attachments. */
    private fun elnBundle(seq: Seq, args: Args) {
        val destination = File(args.require("out"))
        fun suppliedPaths(key: String): List<File> = args.opt(key)?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.map(::File)
            .orEmpty()
        val reports = suppliedPaths("report").map { file ->
            if (!file.isFile) throw CliException("Report file not found: ${file.path}")
            ElnReport("reports/${file.nameWithoutExtension}.md", file.readText(), "Supplied report: ${file.name}")
        }
        val attachments = suppliedPaths("attachment").map { file ->
            if (!file.isFile) throw CliException("Attachment file not found: ${file.path}")
            val extension = file.extension.lowercase()
            val (mediaType, role) = when (extension) {
                "svg" -> "image/svg+xml" to ElnArtifactRole.MAP_SVG
                "png" -> "image/png" to ElnArtifactRole.MAP_PNG
                "pdf" -> "application/pdf" to ElnArtifactRole.ATTACHMENT
                "csv" -> "text/csv" to ElnArtifactRole.ATTACHMENT
                "md", "txt" -> "text/plain" to ElnArtifactRole.ATTACHMENT
                else -> "application/octet-stream" to ElnArtifactRole.ATTACHMENT
            }
            ElnAttachment("attachments/${file.name}", file.readBytes(), mediaType, role, "Supplied attachment: ${file.name}")
        }
        val input = args.opt("in") ?: args.positional(0) ?: "stdin"
        val manifest = ElnAdapters.GENERIC_ZIP.export(
            destination,
            ElnBundleRequest(
                title = args.opt("title", seq.name.ifBlank { "InstaGene sequence" }),
                sequence = seq,
                reports = reports,
                attachments = attachments,
                provenance = mapOf("input" to input, "exportedBy" to "instagene CLI"),
            ),
        )
        if (args.flag("json")) {
            out(Json { prettyPrint = true; encodeDefaults = true }.encodeToString(manifest))
        } else {
            out("Wrote generic ELN/LIMS bundle: ${destination.path} (${manifest.artifacts.size} attachment(s))")
        }
    }

    private fun translate(seq: Seq, args: Args) {
        val table = CodonTable.byId(args.int("table", 1))
        val frame = args.int("frame", 1) - 1
        if (args.flag("all-frames")) {
            val stopAtStop = args.flag("stop-at-stop")
            for (f in 0..2) {
                val p = SeqOps.translate(seq, f, table, stopAtStop)
                out(">${seq.name}_frame${f + 1}")
                out(p.bases.chunked(60).joinToString("\n"))
            }
            return
        }
        emit(SeqOps.translate(seq, frame, table, args.flag("stop-at-stop")), args)
    }

    private fun gc(seq: Seq) = out("${round(SeqOps.gcContent(seq))} %")

    private fun tm(seq: Seq) = out("${round(SeqOps.meltingTemp(seq.bases))} C")

    private fun orfs(seq: Seq, args: Args) {
        val table = CodonTable.byId(args.int("table", 1))
        val minAa = args.int("min-aa", 30)
        val orfs = SeqOps.findOrfs(seq, minAa, table, !args.flag("forward-only"))
        if (orfs.isEmpty()) {
            out("No ORFs of at least $minAa aa found.")
            return
        }
        out(Reports.orfReport(seq, table, minAa, !args.flag("forward-only")))
    }

    private fun find(seq: Seq, args: Args) {
        val pattern = args.require("pattern")
        val mode = when (args.opt("mode", "dna").lowercase()) {
            "dna", "degenerate" -> SearchMode.DNA_DEGENERATE
            "literal" -> SearchMode.LITERAL
            "aa", "amino", "protein" -> SearchMode.AMINO_ACID
            else -> throw CliException("--mode expects dna, literal, or amino")
        }
        out(Reports.searchReport(seq, pattern, mode, !args.flag("forward-only"), args.int("mismatches", 0)))
    }

    private fun align(reference: Seq, args: Args) {
        val paths = args.require("query").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (paths.isEmpty()) throw CliException("--query needs at least one comma-separated file")
        val queries = paths.map { loadFile(it, "Query") }
        val result = Alignment.align(
            reference,
            queries,
            AlignmentParameters(
                mismatchPenalty = -args.double("mismatch", 0.1),
                gapPenalty = -args.double("gap", 1.5),
                gapExtensionPenalty = -args.double("gap-extension", 0.5),
                lineWidth = args.int("line-width", 60),
            ),
        )
        val alignment = MultipleAlignmentResult(
            algorithm = MultipleAlignmentAlgorithm.BUILTIN,
            sequences = listOf(reference.copy(bases = result.reference.sequence)) +
                result.queries.mapIndexed { index, row -> queries[index].copy(bases = row.sequence) },
        )
        if (args.has("out") || args.has("format")) {
            emitAlignment(alignment, args)
            return
        }
        out(">reference ${reference.name}")
        out(result.reference.sequence)
        result.queries.forEach {
            out(">${it.name}\tscore=${round(it.score)}\tmatches=${it.matches}\tmismatches=${it.mismatches}\tgaps=${it.gaps}")
            out(it.sequence)
        }
    }

    /** Writes a multiple alignment in a standard interchange format or a portable image. */
    private fun emitAlignment(result: MultipleAlignmentResult, args: Args) {
        val requested = (args.opt("format") ?: args.opt("out")?.substringAfterLast('.', missingDelimiterValue = "") ?: "fasta").lowercase()
        val format = when (requested) {
            "fa", "fasta", "afa", "msa" -> "fasta"
            "aln", "clustal", "clustalw" -> "clustal"
            "sto", "stockholm" -> "stockholm"
            "phy", "phylip", "ph" -> "phylip"
            "svg" -> "svg"
            "png" -> "png"
            else -> throw CliException("--format expects fasta, clustal, stockholm, phylip, svg, or png")
        }
        val outPath = args.opt("out")
        if (format == "png" && outPath == null) throw CliException("PNG alignment export requires --out FILE")
        val text = when (format) {
            "fasta" -> AlignmentIO.write(result.sequences, AlignmentFormat.FASTA)
            "clustal" -> AlignmentIO.write(result.sequences, AlignmentFormat.CLUSTAL)
            "stockholm" -> AlignmentIO.write(result.sequences, AlignmentFormat.STOCKHOLM)
            "phylip" -> AlignmentIO.write(result.sequences, AlignmentFormat.PHYLIP)
            "svg" -> AlignmentImages.svg(result)
            else -> null
        }
        if (outPath == null) {
            print(text.orEmpty())
            return
        }
        val destination = File(outPath)
        if (format == "png") destination.writeBytes(AlignmentImages.png(result)) else destination.writeText(text.orEmpty())
        System.err.println(Colors.green("Wrote alignment $format to $outPath", args.colors))
    }

    private fun dotPlot(horizontal: Seq, args: Args) {
        val vertical = args.opt("query")?.let { loadFile(it, "Dot-plot query") } ?: horizontal
        val result = RepeatAnalysis.dotPlot(
            horizontal = horizontal,
            vertical = vertical,
            wordSize = args.int("word-size", 11),
            includeInverted = args.flag("inverted"),
            maxPoints = args.int("max-points", 20_000),
        )
        val format = args.opt("format", if (args.flag("json")) "json" else "tsv").lowercase()
        val output = when (format) {
            "tsv", "csv", "text" -> RepeatAnalysis.dotPlotTsv(result)
            "json" -> RepeatAnalysis.dotPlotJson(result)
            "svg" -> RepeatAnalysis.dotPlotSvg(
                result,
                width = args.int("width", 900),
                height = args.int("height", 900),
            )
            else -> throw CliException("--format expects tsv, json, or svg")
        }
        emitAnalysis(output, "dot-plot $format", args)
        if (result.truncated) System.err.println("warning: dot-plot output was capped at ${result.points.size} point(s); increase --max-points to inspect more matches")
    }

    private fun repeats(seq: Seq, args: Args) {
        val result = RepeatAnalysis.findRepeats(
            sequence = seq,
            minimumLength = args.int("min-length", 12),
            maxResults = args.int("max-results", 2_000),
            includeDirect = !args.flag("inverted-only"),
            includeInverted = !args.flag("direct-only"),
        )
        val format = args.opt("format", if (args.flag("json")) "json" else "tsv").lowercase()
        val output = when (format) {
            "tsv", "csv", "text" -> RepeatAnalysis.repeatsTsv(result)
            "json" -> RepeatAnalysis.repeatsJson(result)
            else -> throw CliException("--format expects tsv or json")
        }
        emitAnalysis(output, "repeat analysis $format", args)
        if (result.truncated) System.err.println("warning: repeat output was capped at ${result.repeats.size} call(s); increase --max-results to inspect more matches")
    }

    private fun emitAnalysis(text: String, label: String, args: Args) {
        val outPath = args.opt("out")
        if (outPath == null) {
            print(text)
        } else {
            File(outPath).writeText(text)
            System.err.println(Colors.green("Wrote $label to $outPath", args.colors))
        }
    }

    private fun gel(seq: Seq, args: Args) {
        val enzymes = Enzymes.parseList(args.require("enzymes"))
        val result = VirtualGel.run(listOf(GelLane.Dna(seq.name, seq, enzymes, args.int("completion", 100))))
        val lane = result.lanes.single()
        out("${lane.name} virtual digest")
        lane.bands.forEach { out("${it.sizeBp}\tintensity=${round(it.relativeIntensity)}\tmigration=${round(result.migration(it.sizeBp))}") }
    }

    private fun identity(seq: Seq, args: Args) {
        if (args.flag("json")) {
            out("{\"identity\":\"${SequenceIdentity.cdseguid(seq)}\",\"verified\":${SequenceIdentity.verify(seq)}}")
        } else {
            out("${SequenceIdentity.cdseguid(seq)}\tverified=${SequenceIdentity.verify(seq)}")
        }
    }

    private fun dilute(args: Args) {
        val result = MolecularCalculators.dilution(args.double("stock", 0.0), args.double("final", 0.0), args.double("volume", 0.0))
        out("stock=${round(result.stockVolumeUl)} ul\tdiluent=${round(result.diluentVolumeUl)} ul\ttotal=${round(result.finalVolumeUl)} ul")
    }

    private fun mix(args: Args) {
        val components = args.require("components").split(',').mapNotNull { token ->
            val parts = token.split('=', limit = 2)
            if (parts.size != 2) null else MasterMixComponent(parts[0].trim(), parts[1].trim().toDoubleOrNull() ?: 0.0)
        }
        val result = MolecularCalculators.masterMix(components, args.int("reactions", 1), args.double("overhead", 0.0))
        result.components.forEach { out("${it.name}\t${round(it.volumeUl)} ul") }
        out("total\t${round(result.totalVolumeUl)} ul")
    }

    private fun blastUrl(seq: Seq, args: Args) {
        out(NcbiClient().blastUrl(seq, args.opt("program", "blastn"), args.double("expect", 100.0)))
    }

    private fun ncbiSearch(args: Args) {
        val result = ncbiClient(args).searchNucleotide(args.require("term"), args.int("max-hits", 20))
        result.hits.forEach { out("${it.accession}\t${it.title}") }
        out("${result.hits.size} hit(s)")
        result.provenance.takeIf { it.isNotEmpty() }?.let { responses ->
            System.err.println("NCBI provenance: ${responses.joinToString(",") { it.origin.name.lowercase().replace('_', '-') }}")
        }
    }

    private fun ncbiFetch(args: Args) {
        val seq = ncbiClient(args).fetchGenBank(args.require("accession"))
        System.err.println("NCBI provenance: ${seq.metadata["ONLINE_ORIGINS"].orEmpty().ifBlank { "network" }}")
        emit(seq, args)
    }

    /**
     * Caching is opt-in: without --cache-dir, NCBI commands are network-only.
     * Supplying a directory explicitly selects cache reuse unless the caller
     * chooses another --cache-mode.
     */
    private fun ncbiClient(args: Args): NcbiClient {
        val directory = args.opt("cache-dir")
        if (args.has("cache-dir") && directory.isNullOrBlank()) {
            throw CliException("--cache-dir requires a directory path")
        }
        val modeValue = args.opt("cache-mode")
        if (args.has("cache-mode") && modeValue.isNullOrBlank()) {
            throw CliException("--cache-mode requires network-only, prefer-cache, network-then-cache, or cache-only")
        }
        val mode = try {
            modeValue?.let(OnlineCacheMode::parse)
                ?: if (directory != null) OnlineCacheMode.PREFER_CACHE else OnlineCacheMode.NETWORK_ONLY
        } catch (error: IllegalArgumentException) {
            throw CliException(error.message.orEmpty())
        }
        if (directory == null) {
            if (mode != OnlineCacheMode.NETWORK_ONLY) {
                throw CliException("--cache-mode ${mode.name.lowercase().replace('_', '-')} requires --cache-dir DIR")
            }
            return NcbiClient()
        }
        return NcbiClient(onlineCache = OnlineCache(File(directory)), onlineCacheMode = mode)
    }

    private fun digest(seq: Seq, args: Args) {
        val enzymes = when {
            args.has("enzymes") -> Enzymes.parseList(args.require("enzymes"))
            args.flag("all") -> Enzymes.ALL
            else -> throw CliException("Specify --enzymes EcoRI,BamHI (or --all)")
        }
        out(Reports.digestReport(seq, enzymes))
        if (args.flag("fasta")) {
            out()
            val fragments = Digest.digest(seq, enzymes)
            fragments.forEachIndexed { i, f -> print(SeqIO.write(f.toSeq("${seq.name}_frag${i + 1}"), SeqFormat.FASTA)) }
        }
    }

    private fun uniqueSites(seq: Seq) {
        val counts = Digest.cutCounts(seq)
        val unique = counts.filterValues { it == 1 }.keys.sortedBy { it.name }
        val absent = counts.filterValues { it == 0 }.keys.sortedBy { it.name }
        out("Unique cutters (${unique.size}):")
        unique.forEach { e ->
            out("  %-10s %-12s at %d".format(e.name, e.notation(), Digest.cutSites(seq, e).first().topCut + 1))
        }
        out()
        out("Non-cutters (${absent.size}): ${absent.joinToString(", ") { it.name }}")
    }

    private fun edit(seq: Seq, args: Args): Seq = when {
        args.has("insert") -> {
            val at = args.int("at", seq.length + 1) - 1
            seq.insertAt(at.coerceIn(0, seq.length), args.require("insert").uppercase())
        }

        args.has("delete") || (args.has("from") && args.has("to") && args.flag("delete")) -> {
            val (from, to) = range(args, seq)
            seq.deleteRange(from, to)
        }

        args.has("replace") -> {
            val (from, to) = range(args, seq)
            seq.replaceRange(from, to, args.require("replace").uppercase())
        }

        else -> throw CliException(
            "Choose an edit: --insert ACGT --at 10 | --delete --from 5 --to 20 | --replace ACGT --from 5 --to 8"
        )
    }

    private fun range(args: Args, seq: Seq): Pair<Int, Int> {
        val from = args.int("from", 1) - 1
        val to = args.int("to", seq.length)
        if (to < from) throw CliException("--to ($to) must not precede --from (${from + 1})")
        return from to to
    }

    private fun extract(seq: Seq, args: Args) {
        val (from, to) = range(args, seq)
        var piece = seq.subSeq(from, to)
        if (args.flag("revcomp")) piece = piece.reverseComplement()
        emit(piece, args)
    }

    private fun topology(seq: Seq, args: Args) {
        val target = when (args.opt("set", "circular").lowercase()) {
            "circular", "c" -> Topology.CIRCULAR
            "linear", "l" -> Topology.LINEAR
            else -> throw CliException("--set expects circular or linear")
        }
        var result = seq.withTopology(target)
        args.opt("origin")?.let {
            val origin = it.toIntOrNull() ?: throw CliException("--origin expects a position")
            result = result.rotateOrigin(origin - 1)
        }
        emit(result, args)
    }

    private fun annotate(seq: Seq, args: Args) {
        val (from, to) = range(args, seq)
        val feature = Feature(
            name = args.opt("label", "feature"),
            type = args.opt("type", "misc_feature"),
            start = from,
            end = to,
            notes = args.opt("note", ""),
        )
        emit(seq.withFeature(feature), args)
    }

    private fun primers(seq: Seq, args: Args) {
        val (from, to) = range(args, seq)
        val targetTm = args.double("tm", 60.0)
        val requestedBackend = when (args.opt("backend", "builtin").lowercase()) {
            "builtin", "built-in" -> PrimerDesignBackend.BUILTIN
            "primer3" -> PrimerDesignBackend.PRIMER3
            else -> throw CliException("--backend expects builtin or primer3")
        }
        val mode = when (args.opt("mode", "pcr").lowercase()) {
            "pcr" -> PrimerDesignMode.PCR
            "sequencing", "sequence" -> PrimerDesignMode.SEQUENCING
            else -> throw CliException("--mode expects pcr or sequencing")
        }
        val direction = when (args.opt("direction", "both").lowercase()) {
            "forward", "f" -> SequencingPrimerDirection.FORWARD
            "reverse", "r" -> SequencingPrimerDirection.REVERSE
            "both" -> SequencingPrimerDirection.BOTH
            else -> throw CliException("--direction expects forward, reverse, or both")
        }
        val quality = primerQualityContext(seq, args)
        val parameters = PrimerDesignParameters(
            targetTm = targetTm,
            mode = mode,
            sequencingDirection = direction,
            qualityContext = quality,
        )
        val useAdvanced = args.flag("advanced") || requestedBackend == PrimerDesignBackend.PRIMER3 ||
            mode != PrimerDesignMode.PCR || quality != null || args.flag("json") || args.has("report")
        if (useAdvanced) {
            val result = PrimerDesign.design(seq, from, to, parameters, requestedBackend)
            val report = Reports.primerDesignReport(seq, from, to, parameters, result)
            args.opt("report")?.let { path ->
                val file = File(path)
                file.writeText(if (file.extension.equals("json", ignoreCase = true)) Reports.primerDesignJson(report) else Reports.primerDesignMarkdown(report))
                System.err.println("Wrote primer design report to ${file.absolutePath}")
            }
            if (args.flag("json")) {
                out(Reports.primerDesignJson(report))
                return
            }
            out("Amplicon ${from + 1}..$to (${to - from} bp) from ${seq.name}")
            out("Mode: ${parameters.mode}")
            out("Backend: ${result.backend}")
            result.warnings.forEach { System.err.println("warning: $it") }
            result.command?.let { System.err.println("command: $it") }
            result.qualitySummary?.let { summary ->
                out(
                    "Quality: Q${summary.minimumPhred}; observed ${summary.observedPositions.size}/${seq.length}; " +
                        "low-quality ${QualityRegions.oneBased(summary.lowQualityRegions).ifBlank { "none" }}; " +
                        "uncovered ${QualityRegions.oneBased(summary.uncoveredRegions).ifBlank { "none" }}",
                )
            }
            if (result.candidates.isEmpty()) out("No candidates passed the filters.")
            result.candidates.take(100).forEach { candidate ->
                out("${candidate.primer.name}\t${candidate.start + 1}..${candidate.end}\t${candidate.primer.bases}\tTm=${"%.1f".format(candidate.primer.tm)}\tGC=${"%.1f".format(candidate.primer.gc)}\tscore=${"%.2f".format(candidate.score)}")
            }
            return
        }
        val (fwd, rev) = SeqOps.designPrimers(seq, from, to, targetTm)
        out("Amplicon ${from + 1}..$to (${to - from} bp) from ${seq.name}")
        out("  $fwd")
        out("  $rev")
    }

    /** Builds the optional quality context used by `primers --advanced`. */
    private fun primerQualityContext(seq: Seq, args: Args): PrimerQualityContext? {
        val requested = args.has("qual") || args.has("traces") || args.has("low-quality") ||
            args.has("quality-threshold") || args.has("exclude-uncovered")
        if (!requested) return null
        val threshold = args.int("quality-threshold", 20)
        if (threshold < 0) throw CliException("--quality-threshold must be non-negative")
        val evidence = mutableListOf<QualityEvidence>()

        args.opt("qual")?.let { path ->
            val file = File(path)
            if (!file.isFile) throw CliException("FASTA-QUAL sidecar not found: $path")
            val records = FastaQual.read(file)
            val requestedRecord = args.opt("qual-record")
            val record = when {
                requestedRecord != null -> records.firstOrNull { it.name == requestedRecord }
                    ?: throw CliException("FASTA-QUAL sidecar has no record named '$requestedRecord'")
                records.size == 1 -> records.single()
                else -> records.firstOrNull { it.name == seq.name }
                    ?: throw CliException("FASTA-QUAL sidecar has multiple records; use --qual-record NAME")
            }
            val offset = args.int("qual-offset", 1) - 1
            if (offset < 0) throw CliException("--qual-offset is one-based and must be at least 1")
            evidence += PrimerQualityContext.evidenceFromFastaQual(record, seq.length, offset, file.absolutePath)
        }

        args.opt("traces")?.let { rawPaths ->
            val traces = rawPaths.split(',').map(String::trim).filter(String::isNotEmpty).map { path ->
                val file = File(path)
                if (!file.isFile) throw CliException("Chromatogram not found: $path")
                val header = file.inputStream().use { it.readNBytes(4) }
                when {
                    ChromatogramReader.looksLikeAbi(header) -> ChromatogramReader.readAbi(file)
                    ChromatogramReader.looksLikeScf(header) -> ChromatogramReader.readScf(file)
                    else -> throw CliException("'${file.name}' is not a readable ABI/AB1 or SCF chromatogram")
                }
            }
            val alignment = SangerAlignment.alignChromatograms(
                seq,
                traces,
                SangerOptions(minQuality = threshold, trimQuality = 0),
            )
            val pathsByRead = traces.associateBy({ it.name }, { it.source })
            evidence += PrimerQualityContext.evidenceFromSangerAlignment(alignment).map { item ->
                item.copy(source = item.source.copy(sourceId = pathsByRead[item.source.label] ?: item.source.sourceId))
            }
        }

        val manual = QualityRegions.parseOneBased(args.opt("low-quality", ""), seq.length)
        return PrimerQualityContext(
            templateLength = seq.length,
            minimumPhred = threshold,
            evidence = evidence,
            manualExcludedRegions = manual,
            excludeUncoveredPositions = args.flag("exclude-uncovered"),
        )
    }

    private fun plasmid(args: Args) {
        val backbone = loadFile(args.require("backbone"), "Backbone").let {
            if (args.flag("linear-backbone")) it else it.withTopology(Topology.CIRCULAR)
        }
        val insert = loadFile(args.require("insert"), "Insert")
        val enzymes = Enzymes.parseList(args.require("enzymes"))
        val name = args.opt("name", "${backbone.name}_${insert.name}")

        val result = CloningWorkflows.restriction(backbone, insert, enzymes, name)
        result.steps.forEach { System.err.println("  ${it.detail}") }
        System.err.println("Constructed $name: ${result.product.length} bp circular")
        saveRecipe(args, result.method.name, result.product, listOf(backbone, insert), result.parameters)
        emit(result.product, args)
    }

    private fun gibson(args: Args) {
        val paths = args.require("parts").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (paths.size < 2) throw CliException("--parts needs at least two comma-separated files")
        val parts = paths.map { loadFile(it, "Part") }
        val result = CloningWorkflows.overlapAssembly(
            method = CloningMethod.GIBSON,
            parts = parts,
            minOverlap = args.int("min-overlap", 15),
            name = args.opt("name", "gibson_assembly"),
            circular = !args.flag("linear"),
        )
        result.steps.forEach { System.err.println("  ${it.detail}") }
        saveRecipe(args, result.method.name, result.product, parts, result.parameters)
        emit(result.product, args)
    }

    private fun goldenGate(args: Args) {
        val paths = args.require("parts").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (paths.isEmpty()) throw CliException("--parts needs at least one comma-separated file")
        val overhangs = args.require("overhangs").split(',').map { it.trim() }
        if (overhangs.size != paths.size + 1) {
            throw CliException("--overhangs needs exactly one more item than --parts (left, between each part, right)")
        }
        val parts = paths.map { loadFile(it, "Part") }
        val result = CloningWorkflows.goldenGate(
            parts,
            overhangs,
            name = args.opt("name", "golden_gate_product"),
            circular = !args.flag("linear"),
        )
        result.steps.forEach { System.err.println("  ${it.detail}") }
        result.diagnostics.forEach { System.err.println("${it.severity}: ${it.message}") }
        saveRecipe(args, result.method.name, result.product, parts, result.parameters)
        emit(result.product, args)
    }

    private fun recombine(args: Args) {
        val target = loadFile(args.require("target"), "Target")
        val donor = loadFile(args.require("donor"), "Donor")
        val armLength = args.int("arm", 20)
        val candidateIndex = args.int("candidate", 1) - 1
        val result = CloningWorkflows.homologyRecombination(
            target,
            donor,
            armLength,
            candidateIndex,
            args.opt("name", "${target.name}_recombined"),
        )
        result.steps.forEach { System.err.println("  ${it.detail}") }
        saveRecipe(args, result.method.name, result.product, listOf(target, donor), result.parameters)
        emit(result.product, args)
    }

    /** Saves a portable, identity-checked recipe alongside any cloning output when requested. */
    private fun saveRecipe(
        args: Args,
        operation: String,
        product: Seq,
        inputs: List<Seq>,
        parameters: Map<String, String>,
    ) {
        val path = args.opt("recipe") ?: return
        val file = File(path)
        file.writeText(WorkflowRecipes.encode(Reports.workflowRecipe(operation, product, inputs, parameters)))
        System.err.println("Wrote reproducibility recipe to ${file.absolutePath}")
    }

    private fun recipe(args: Args) {
        when (args.positional(0)?.lowercase() ?: "replay") {
            "replay" -> replayRecipe(args)
            else -> throw CliException("Recipe action must be 'replay'. Example: instagene recipe replay --file workflow.json --inputs vector.gb,insert.fa")
        }
    }

    private fun replayRecipe(args: Args) {
        val recipeFile = File(args.require("file"))
        if (!recipeFile.isFile) throw CliException("Recipe file not found: ${recipeFile.path}")
        val recipe = WorkflowRecipes.decode(recipeFile.readText())
        val paths = args.require("inputs").split(',').map(String::trim).filter(String::isNotEmpty)
        val inputs = paths.map { loadFile(it, "Recipe input") }
        val result = WorkflowReplays.replay(
            recipe,
            inputs,
            WorkflowReplayAuthorization(
                allowExternalTools = args.flag("allow-external"),
                allowOnlineSources = args.flag("allow-online"),
            ),
        )
        if (!result.succeeded) throw CliException(result.messages.joinToString(" ").ifBlank { "Recipe replay failed: ${result.status}" })
        val product = result.product ?: throw CliException("Recipe replay completed without a product")
        if (args.flag("json")) {
            // A named output may still be written; stdout remains a JSON contract for automation.
            if (args.opt("out") != null) emit(product, args)
            out(Reports.workflowReplayJson(result))
        } else {
            result.messages.forEach { System.err.println(it) }
            emit(product, args)
        }
    }

    private fun listEnzymes(args: Args) {
        val filter = args.opt("filter")?.lowercase()
        val shown = Enzymes.ALL.filter { filter == null || it.name.lowercase().contains(filter) }
        out("%-12s %-12s %-14s %s".format("name", "site", "cut", "ends"))
        shown.forEach {
            out("%-12s %-12s %-14s %s".format(it.name, it.site, it.notation(), it.endType.label))
        }
        out("${shown.size} enzyme(s).")
    }

    private fun externalTools(args: Args) {
        val toolId = args.opt("run")
        if (args.flag("rescan")) ExternalTools.rescan()
        if (args.flag("health") || (args.flag("json") && toolId == null)) {
            val timeout = args.int("timeout", 5)
            if (timeout !in 1..30) throw CliException("--timeout for tool health must be between 1 and 30 seconds")
            val selected = toolId?.let { id ->
                listOf(
                    ExternalTools.CATALOG.firstOrNull { it.id == id || it.executable == id }
                        ?: throw CliException("Unknown tool '$id'. Available: ${ExternalTools.CATALOG.joinToString(", ") { it.id }}"),
                )
            } ?: ExternalTools.CATALOG
            val health = ExternalTools.healthChecks(selected, timeout.toLong())
            if (args.flag("json")) out(ExternalTools.healthJson(health)) else print(ExternalTools.healthReport(health))
            return
        }
        if (toolId == null) {
            print(ExternalTools.report())
            return
        }
        val tool = ExternalTools.CATALOG.firstOrNull { it.id == toolId || it.executable == toolId }
            ?: throw CliException(
                "Unknown tool '$toolId'. Available: ${ExternalTools.CATALOG.joinToString(", ") { it.id }}"
            )
        val placeholders = buildMap {
            args.opt("pattern")?.let { put("pattern", it) }
            args.opt("other")?.let { put("other", File(it).absolutePath) }
        }
        val extra = args.opt("args")?.split(' ')?.filter { it.isNotBlank() } ?: emptyList()
        if (args.flag("preview")) {
            val preview = ExternalTools.commandPreview(tool, placeholders, extra)
            out("$ ${preview.render()}")
            if (preview.missingPlaceholders.isNotEmpty()) {
                out("Missing required values: ${preview.missingPlaceholders.joinToString(", ")}")
            }
            return
        }
        val seq = load(args)
        val result = ExternalTools.run(tool, seq, placeholders, extra)
        System.err.println("$ ${result.command}")
        print(result.payload())
        if (result.stderr.isNotBlank()) System.err.print(result.stderr)
        if (!result.succeeded) throw CliException("${tool.displayName} exited with ${result.exitCode}")
    }

    private fun sample(args: Args) {
        val requested = args.positional(0) ?: args.opt("name")
        if (requested == null) {
            out("Bundled samples:")
            SeqIO.Samples.ALL.forEach { seq ->
                val source = seq.metadata[SeqIO.Samples.SOURCE_METADATA_KEY].orEmpty()
                val url = seq.metadata["ONLINE_URL"].orEmpty()
                out("- ${seq.name}: $source $url".trim())
            }
            return
        }
        val seq = SeqIO.Samples.ALL.firstOrNull { it.name.equals(requested, ignoreCase = true) }
            ?: throw CliException("No sample named '$requested'")
        emit(seq, args)
    }

    // ------------------------------------------------------------------- gui

    /**
     * Hands off to the desktop GUI: resolves the InstaGene launcher and starts
     * it in a separate process, forwarding any positional file paths so
     * `instagene gui plasmid.gb` opens the editor with that file. The CLI must
     * not link the GUI module (separation rule), so the launcher is found the
     * way a user would: an explicit [--launcher], `$INSTAGENE_GUI`, the GUI on
     * PATH, or the standard jpackage install location. The child process
     * inherits the terminal, and its exit code becomes the CLI's exit code.
     */
    private fun gui(args: Args): Int {
        val launcher = args.opt("launcher") ?: resolveGuiLauncher()
            ?: throw CliException(
                "Could not find the InstaGene desktop GUI. Install it, add its launcher " +
                    "to PATH, or set INSTAGENE_GUI to the GUI launcher " +
                    "(e.g. /opt/instagene/bin/InstaGene)."
            )
        val command = if (launcher.endsWith(".jar")) {
            buildList {
                add(javaBin())
                add("-jar")
                add(launcher)
                addAll(args.positionals)
            }
        } else {
            buildList {
                add(launcher)
                addAll(args.positionals)
            }
        }
        val process = ProcessBuilder(command).inheritIO().start()
        return process.waitFor()
    }

    private fun javaBin(): String {
        val home = System.getProperty("java.home")
        val exe = if (File.separatorChar == '\\') "java.exe" else "java"
        return File(home, "bin").resolve(exe).absolutePath
    }

    /**
     * Locates the InstaGene desktop launcher in order of preference:
     * `$INSTAGENE_GUI`, an `instagene`/`InstaGene` executable on PATH, then the
     * jpackage Linux install locations. Injectable for tests.
     */
    fun resolveGuiLauncher(
        env: Map<String, String> = System.getenv(),
        pathVar: String = env["PATH"] ?: "",
        instageneGui: String? = env["INSTAGENE_GUI"],
        installCandidates: List<String> = listOf(
            "/opt/instagene/bin/instagene",
            "/opt/instagene/bin/InstaGene",
        ),
    ): String? {
        instageneGui?.takeIf { it.isNotBlank() }?.let { return it }
        findOnPath("instagene", pathVar)?.let { return it }
        findOnPath("InstaGene", pathVar)?.let { return it }
        return installCandidates.firstOrNull { File(it).canExecute() }
    }

    fun findOnPath(name: String, pathVar: String): String? =
        pathVar.split(File.pathSeparator).firstNotNullOfOrNull { dir ->
            val candidate = File(dir, name)
            if (candidate.canExecute()) candidate.absolutePath else null
        }

    private fun round(v: Double): Double = Reports.round1(v)

    fun usage(colors: Boolean = false): String {
        val heading = Colors.bold("InstaGene ${Version.VERSION} - DNA/RNA editing and plasmid construction.", colors)
        return $$"""
        $$heading

        Usage: instagene <command> [options] [file]

        Input is read from the given file, from --in, or from stdin (FASTA, GenBank or bare bases).
        Output goes to stdout, or to --out FILE. Use --to fasta|genbank to pick the format.

        Global options
          --env FILE        apply defaults from a KEY=VALUE file (command line wins)
          --no-colors       plain output, no ANSI colors
          --json            machine-readable JSON for commands that support it
          --version         print the InstaGene version and exit

        Inspecting
          info FILE [--json]              summary: length, GC, Tm, features, unique cutters
          gc / tm FILE                    single numbers, handy in scripts
          orf [--min-aa 30] [--table 11]  open reading frames on both strands
          find --pattern GGATCC [--forward-only]
          find --pattern GGATCC [--mismatches 1] [--three-prime 4] [--mode dna|literal|amino]
          align --query read.fa[,read2.fa] reference.fa [--out alignment.aln] [--format fasta|clustal|stockholm|phylip|svg|png]
          dotplot [--query other.fa] [--word-size 11] [--inverted] [--format tsv|json|svg] [--out plot.svg]
          repeats [--min-length 12] [--direct-only|--inverted-only] [--format tsv|json] [--out repeats.tsv]
          gel --enzymes EcoRI,BamHI sequence.fa [--completion 50]
          identity FILE [--json]                print a stable sequence identity
          dilute --stock 100 --final 10 --volume 100
          mix --components buffer=2,water=5 --reactions 10 [--overhead 0.1]
          blast-url FILE [--program blastn] [--expect 100]
          ncbi-search --term "gene name" [--max-hits 20] [--cache-dir DIR] [--cache-mode MODE]
          ncbi-fetch --accession ACCESSION [--to genbank] [--cache-dir DIR] [--cache-mode MODE]
            MODE is network-only, prefer-cache, network-then-cache, or cache-only.
          enzymes [--filter eco]          the built-in restriction enzyme list
          sites FILE                      unique cutters and non-cutters
          bench | benchmark FILE           time all engine operations on the input
          eln-bundle --out handoff.zip FILE [--report report.md] [--attachment map.svg,pdf]
                                        write an offline generic ELN/LIMS ZIP with hashes and provenance

        Editing
          revcomp | complement | transcribe | backtranscribe
          translate [--frame 1] [--table 1|11] [--all-frames] [--stop-at-stop]
          edit --insert ACGT --at 10
          edit --delete --from 5 --to 20
          edit --replace ACGT --from 5 --to 8
          extract --from 100 --to 400 [--revcomp]
          annotate --from 1 --to 60 --label promoter [--type promoter]
          topology --set circular|linear [--origin 500]
          convert --to genbank|gff3

        Building plasmids
          plasmid --backbone vec.gb --insert gene.fa --enzymes EcoRI,HindIII [--name pMyGene] [--recipe clone.json]
          gibson --parts a.fa,b.fa,c.fa [--min-overlap 20] [--linear] [--recipe assembly.json]
          golden-gate --parts a.fa,b.fa --overhangs GGAG,AATG,GGAG [--linear] [--recipe assembly.json]
          recombine --target target.fa --donor donor.fa --arm 20 [--candidate 1] [--recipe recombination.json]
          recipe replay --file workflow.json --inputs vector.gb,insert.fa [--allow-external] [--allow-online] [--json]
          digest --enzymes EcoRI,BamHI [--fasta]   cut sites and fragments
          primers --from 100 --to 400 [--tm 60] [--advanced] [--backend builtin|primer3]
          primers --mode sequencing --direction forward --from 100 --to 220 [--advanced]
          primers --qual read.qual [--qual-record NAME] [--qual-offset 1] [--low-quality 1-20,45]
                  [--traces read1.ab1,read2.scf] [--quality-threshold 20] [--exclude-uncovered]
                  [--report primers.md|json] [--json]

        External CLI tools (optional)
          tools [--health] [--json]       optional-tool availability, versions, and recovery actions
          tools --health --run primer3    inspect one optional tool without running it
          tools --run primer3 --preview   show a reproducible command without running it
          tools --run seqkit-stats FILE
          tools --run emboss-restrict FILE
          tools --run seqkit-locate --pattern GGATCC FILE

        Desktop
          gui [FILE ...]                  launch the desktop GUI (opens FILE if given)
          gui --launcher PATH             run a specific GUI launcher instead of auto-detecting
          ${INSTAGENE_GUI}           environment variable pointing at the GUI launcher

        Other
          sample [NAME]                    write a bundled example sequence
          --circular                      treat the input as a circular molecule

        Examples
          instagene sample pBR322_NCBI --to genbank > pbr322.gb
          instagene sample GFP_Aequorea_NCBI_reference > gfp.fa
          cat gfp.fa | instagene translate --stop-at-stop
          instagene digest --enzymes EcoRI,HindIII gfp.fa
          instagene plasmid --backbone puc19.gb --insert gfp.fa --enzymes EcoRI,HindIII -o pGFP.gb
    """.trimIndent()
    }
}
