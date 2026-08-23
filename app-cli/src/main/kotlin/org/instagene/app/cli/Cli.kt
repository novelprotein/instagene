package org.instagene.app.cli

import org.instagene.core.Assembly
import org.instagene.core.AssemblyException
import org.instagene.core.AssemblyWorkflows
import org.instagene.core.AdvancedSearch
import org.instagene.core.Alignment
import org.instagene.core.AlignmentParameters
import org.instagene.core.CodonDesign
import org.instagene.core.CodonTable
import org.instagene.core.Digest
import org.instagene.core.EnzymeAnalysis
import org.instagene.core.Enzymes
import org.instagene.core.ExternalTools
import org.instagene.core.Feature
import org.instagene.core.GelLane
import org.instagene.core.MasterMixComponent
import org.instagene.core.MolecularCalculators
import org.instagene.core.NcbiClient
import org.instagene.core.PrimerThermodynamics
import org.instagene.core.PrimerDesign
import org.instagene.core.PrimerDesignBackend
import org.instagene.core.PrimerDesignParameters
import org.instagene.core.Recombination
import org.instagene.core.Reports
import org.instagene.core.SearchMode
import org.instagene.core.SearchRequest
import org.instagene.core.Seq
import org.instagene.core.SeqOps
import org.instagene.core.SequenceIdentity
import org.instagene.core.SequenceStatistics
import org.instagene.core.SiteDomestication
import org.instagene.core.Topology
import org.instagene.core.VirtualGel
import org.instagene.core.Version
import org.instagene.core.io.SeqFormat
import org.instagene.core.io.SeqIO
import java.io.File
import java.io.IOException
import kotlin.system.measureNanoTime

/**
 * The command-line half of InstaGene. Every operation the GUI offers is also
 * reachable here, so sequences can be piped through shell pipelines.
 */
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
        } catch (e: org.instagene.core.io.SeqIOException) {
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
            "help", "--help", "-h" -> println(usage(args.colors))
            "version", "--version" -> println(Colors.bold("InstaGene ${Version.VERSION}", args.colors))
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
            "gel" -> gel(load(args), args)
            "identity" -> identity(load(args), args)
            "dilute" -> dilute(args)
            "mix" -> mix(args)
            "blast-url" -> blastUrl(load(args), args)
            "ncbi-search" -> ncbiSearch(args)
            "ncbi-fetch" -> emit(NcbiClient().fetchGenBank(args.require("accession")), args)
            "bench", "benchmark" -> benchmark(args)
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
        if (args.flag("json")) println(Reports.sequenceSummaryJson(seq))
        else print(Reports.seqSummary(seq))
    }

    private fun benchmark(args: Args) {
        val seq = try {
            load(args)
        } catch (_: CliException) {
            println("No input file. Fetching U03453 from NCBI...")
            println()
            NcbiClient().fetchGenBank("U03453")
        }
        val len = seq.length
        val unit = if (seq.kind == org.instagene.core.SeqKind.PROTEIN) "aa" else "bp"
        println("== Benchmark: ${seq.name} ($len $unit) ==")
        println()

        val repeats = 3
        fun bench(label: String, block: () -> Unit): Double {
            var total = 0L
            repeat(repeats) { total += measureNanoTime { block() } }
            val ms = (total / repeats) / 1_000_000.0
            println("  %-40s %8.1f ms".format(label, ms))
            return ms
        }

        if (seq.kind != org.instagene.core.SeqKind.PROTEIN) {
            // --- SequenceStatistics ---
            println("--- SequenceStatistics ---")
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
            println()

            // --- SeqOps ---
            println("--- SeqOps ---")
            bench("transcribe") { SeqOps.transcribe(seq) }
            bench("backTranscribe") { SeqOps.backTranscribe(seq) }
            bench("translateBases(frame=0)") { SeqOps.translateBases(seq.bases, 0) }
            bench("baseCounts") { SeqOps.baseCounts(seq.bases) }
            bench("find(ATGCATGC)") { SeqOps.find(seq, "ATGCATGC") }
            println()

            // --- Digest ---
            println("--- Digest ---")
            val eco = Enzymes.require("EcoRI")
            bench("cutSites(EcoRI)") { Digest.cutSites(seq, eco) }
            bench("cutCounts(ALL ${Enzymes.ALL.size} enzymes)") { Digest.cutCounts(seq, Enzymes.ALL) }
            bench("digest(EcoRI + HindIII)") {
                Digest.digest(seq, listOf(eco, Enzymes.require("HindIII")))
            }
            println()

            // --- Enzyme Analysis ---
            if (len >= 10_000) {
                println("--- Enzyme Analysis ---")
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
                println()
            }

            // --- ORFs and Search ---
            println("--- ORFs and Search ---")
            bench("findOrfs(30aa)") { SeqOps.findOrfs(seq, 30) }
            bench("orfDensity(200, 100)") { SequenceStatistics.orfDensity(seq, 200, 100) }
            bench("AdvancedSearch.find(ATGC, DNA, 2strands)") {
                AdvancedSearch.find(seq, SearchRequest("ATGC", SearchMode.DNA_DEGENERATE, bothStrands = true))
            }
            bench("AdvancedSearch.find(MVSK, AA, 2strands)") {
                AdvancedSearch.find(seq, SearchRequest("MVSK", SearchMode.AMINO_ACID, bothStrands = true))
            }
            println()

            // --- Primer Thermodynamics (on a 30-nt primer extracted from the sequence) ---
            if (len >= 30) {
                println("--- Primer Thermodynamics (30 nt) ---")
                val primer = seq.bases.substring(0, 30)
                val rc = org.instagene.core.Alphabet.reverseComplement(primer)
                bench("thermodynamicResult") { PrimerThermodynamics.thermodynamicResult(primer) }
                bench("selfDimer") { PrimerThermodynamics.selfDimer(primer) }
                bench("heteroDimer(primer, rc)") { PrimerThermodynamics.heteroDimer(primer, rc) }
                bench("assessHairpin") { PrimerThermodynamics.assessHairpin(primer) }
                bench("fullScreen") { PrimerThermodynamics.fullScreen(primer) }
                println()
            }

            // --- Site Domestication ---
            if (len >= 100) {
                println("--- Site Domestication ---")
                bench("findInternalSites(8 enzymes)") { SiteDomestication.findInternalSites(seq) }
                bench("suggestEnzyme(8 enzymes)") { SiteDomestication.suggestEnzyme(seq) }
                bench("domesticate(8 enzymes)") {
                    SiteDomestication.domesticate(seq, SiteDomestication.GOLDEN_GATE_ENZYMES)
                }
                println()
            }

            // --- Codon Optimization ---
            println("--- Codon Optimization ---")
            bench("reverseTranslate(1000 aa)") {
                val aaBases = seq.bases.take(3000).chunked(3).joinToString("") {
                    SeqOps.translateBases(it, 0).first().toString()
                }
                CodonDesign.reverseTranslate(Seq(bases = aaBases, name = "protein", kind = org.instagene.core.SeqKind.PROTEIN))
            }
            bench("optimize(codon usage)") { CodonDesign.optimize(seq) }
            println()
        } else {
            println("--- Amino Acid Composition ---")
            bench("aminoAcidComposition") { SequenceStatistics.aminoAcidComposition(seq) }
            println()
        }

        println("Done. (${repeats} repeats, averaged)")
    }

    private fun translate(seq: Seq, args: Args) {
        val table = CodonTable.byId(args.int("table", 1))
        val frame = args.int("frame", 1) - 1
        if (args.flag("all-frames")) {
            val stopAtStop = args.flag("stop-at-stop")
            for (f in 0..2) {
                val p = SeqOps.translate(seq, f, table, stopAtStop)
                println(">${seq.name}_frame${f + 1}")
                println(p.bases.chunked(60).joinToString("\n"))
            }
            return
        }
        emit(SeqOps.translate(seq, frame, table, args.flag("stop-at-stop")), args)
    }

    private fun gc(seq: Seq) = println("${round(SeqOps.gcContent(seq))} %")

    private fun tm(seq: Seq) = println("${round(SeqOps.meltingTemp(seq.bases))} C")

    private fun orfs(seq: Seq, args: Args) {
        val table = CodonTable.byId(args.int("table", 1))
        val minAa = args.int("min-aa", 30)
        val orfs = SeqOps.findOrfs(seq, minAa, table, !args.flag("forward-only"))
        if (orfs.isEmpty()) {
            println("No ORFs of at least $minAa aa found.")
            return
        }
        println(Reports.orfReport(seq, table, minAa, !args.flag("forward-only")))
    }

    private fun find(seq: Seq, args: Args) {
        val pattern = args.require("pattern")
        val mode = when (args.opt("mode", "dna").lowercase()) {
            "dna", "degenerate" -> SearchMode.DNA_DEGENERATE
            "literal" -> SearchMode.LITERAL
            "aa", "amino", "protein" -> SearchMode.AMINO_ACID
            else -> throw CliException("--mode expects dna, literal, or amino")
        }
        println(Reports.searchReport(seq, pattern, mode, !args.flag("forward-only"), args.int("mismatches", 0)))
    }

    private fun align(reference: Seq, args: Args) {
        val paths = args.require("query").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (paths.isEmpty()) throw CliException("--query needs at least one comma-separated file")
        val result = Alignment.align(
            reference,
            paths.map { loadFile(it, "Query") },
            AlignmentParameters(
                mismatchPenalty = -args.double("mismatch", 0.1),
                gapPenalty = -args.double("gap", 1.5),
                gapExtensionPenalty = -args.double("gap-extension", 0.5),
                lineWidth = args.int("line-width", 60),
            ),
        )
        println(">reference ${reference.name}")
        println(result.reference.sequence)
        result.queries.forEach {
            println(">${it.name}\tscore=${round(it.score)}\tmatches=${it.matches}\tmismatches=${it.mismatches}\tgaps=${it.gaps}")
            println(it.sequence)
        }
    }

    private fun gel(seq: Seq, args: Args) {
        val enzymes = Enzymes.parseList(args.require("enzymes"))
        val result = VirtualGel.run(listOf(GelLane.Dna(seq.name, seq, enzymes, args.int("completion", 100))))
        val lane = result.lanes.single()
        println("${lane.name} virtual digest")
        lane.bands.forEach { println("${it.sizeBp}\tintensity=${round(it.relativeIntensity)}\tmigration=${round(result.migration(it.sizeBp))}") }
    }

    private fun identity(seq: Seq, args: Args) {
        if (args.flag("json")) {
            println("{\"identity\":\"${SequenceIdentity.cdseguid(seq)}\",\"verified\":${SequenceIdentity.verify(seq)}}")
        } else {
            println("${SequenceIdentity.cdseguid(seq)}\tverified=${SequenceIdentity.verify(seq)}")
        }
    }

    private fun dilute(args: Args) {
        val result = MolecularCalculators.dilution(args.double("stock", 0.0), args.double("final", 0.0), args.double("volume", 0.0))
        println("stock=${round(result.stockVolumeUl)} ul\tdiluent=${round(result.diluentVolumeUl)} ul\ttotal=${round(result.finalVolumeUl)} ul")
    }

    private fun mix(args: Args) {
        val components = args.require("components").split(',').mapNotNull { token ->
            val parts = token.split('=', limit = 2)
            if (parts.size != 2) null else MasterMixComponent(parts[0].trim(), parts[1].trim().toDoubleOrNull() ?: 0.0)
        }
        val result = MolecularCalculators.masterMix(components, args.int("reactions", 1), args.double("overhead", 0.0))
        result.components.forEach { println("${it.name}\t${round(it.volumeUl)} ul") }
        println("total\t${round(result.totalVolumeUl)} ul")
    }

    private fun blastUrl(seq: Seq, args: Args) {
        println(NcbiClient().blastUrl(seq, args.opt("program", "blastn"), args.double("expect", 100.0)))
    }

    private fun ncbiSearch(args: Args) {
        val result = NcbiClient().searchNucleotide(args.require("term"), args.int("max-hits", 20))
        result.hits.forEach { println("${it.accession}\t${it.title}") }
        println("${result.hits.size} hit(s)")
    }

    private fun digest(seq: Seq, args: Args) {
        val enzymes = when {
            args.has("enzymes") -> Enzymes.parseList(args.require("enzymes"))
            args.flag("all") -> Enzymes.ALL
            else -> throw CliException("Specify --enzymes EcoRI,BamHI (or --all)")
        }
        println(Reports.digestReport(seq, enzymes))
        if (args.flag("fasta")) {
            println()
            val fragments = Digest.digest(seq, enzymes)
            fragments.forEachIndexed { i, f -> print(SeqIO.write(f.toSeq("${seq.name}_frag${i + 1}"), SeqFormat.FASTA)) }
        }
    }

    private fun uniqueSites(seq: Seq) {
        val counts = Digest.cutCounts(seq)
        val unique = counts.filterValues { it == 1 }.keys.sortedBy { it.name }
        val absent = counts.filterValues { it == 0 }.keys.sortedBy { it.name }
        println("Unique cutters (${unique.size}):")
        unique.forEach { e ->
            println("  %-10s %-12s at %d".format(e.name, e.notation(), Digest.cutSites(seq, e).first().topCut + 1))
        }
        println()
        println("Non-cutters (${absent.size}): ${absent.joinToString(", ") { it.name }}")
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
        if (args.flag("advanced") || requestedBackend == PrimerDesignBackend.PRIMER3) {
            val result = PrimerDesign.design(
                seq, from, to,
                PrimerDesignParameters(targetTm = targetTm),
                requestedBackend,
            )
            println("Amplicon ${from + 1}..$to (${to - from} bp) from ${seq.name}")
            println("Backend: ${result.backend}")
            result.warnings.forEach { System.err.println("warning: $it") }
            result.command?.let { System.err.println("command: $it") }
            if (result.candidates.isEmpty()) println("No candidates passed the filters.")
            result.candidates.take(100).forEach { candidate ->
                println("${candidate.primer.name}\t${candidate.start + 1}..${candidate.end}\t${candidate.primer.bases}\tTm=${"%.1f".format(candidate.primer.tm)}\tGC=${"%.1f".format(candidate.primer.gc)}\tscore=${"%.2f".format(candidate.score)}")
            }
            return
        }
        val (fwd, rev) = SeqOps.designPrimers(seq, from, to, targetTm)
        println("Amplicon ${from + 1}..$to (${to - from} bp) from ${seq.name}")
        println("  $fwd")
        println("  $rev")
    }

    private fun plasmid(args: Args) {
        val backbone = loadFile(args.require("backbone"), "Backbone").let {
            if (args.flag("linear-backbone")) it else it.withTopology(Topology.CIRCULAR)
        }
        val insert = loadFile(args.require("insert"), "Insert")
        val enzymes = Enzymes.parseList(args.require("enzymes"))
        val name = args.opt("name", "${backbone.name}_${insert.name}")

        val result = Assembly.buildPlasmid(backbone, insert, enzymes, name)
        result.log.forEach { System.err.println("  $it") }
        System.err.println("Constructed $name: ${result.plasmid.length} bp circular")
        emit(result.plasmid, args)
    }

    private fun gibson(args: Args) {
        val paths = args.require("parts").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (paths.size < 2) throw CliException("--parts needs at least two comma-separated files")
        val parts = paths.map { loadFile(it, "Part") }
        val result = Assembly.gibson(
            parts = parts,
            minOverlap = args.int("min-overlap", 15),
            name = args.opt("name", "gibson_assembly"),
            circular = !args.flag("linear"),
        )
        result.log.forEach { System.err.println("  $it") }
        emit(result.product, args)
    }

    private fun goldenGate(args: Args) {
        val paths = args.require("parts").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (paths.isEmpty()) throw CliException("--parts needs at least one comma-separated file")
        val overhangs = args.require("overhangs").split(',').map { it.trim() }
        if (overhangs.size != paths.size + 1) {
            throw CliException("--overhangs needs exactly one more item than --parts (left, between each part, right)")
        }
        val result = AssemblyWorkflows.goldenGate(
            paths.map { loadFile(it, "Part") },
            overhangs,
            name = args.opt("name", "golden_gate_product"),
            circular = !args.flag("linear"),
        )
        result.log.forEach { System.err.println("  $it") }
        emit(result.product, args)
    }

    private fun recombine(args: Args) {
        val target = loadFile(args.require("target"), "Target")
        val donor = loadFile(args.require("donor"), "Donor")
        val candidates = Recombination.candidates(target, donor, args.int("arm", 20))
        val selected = candidates.getOrNull(args.int("candidate", 1) - 1)
            ?: throw CliException("No matching recombination candidate found (or --candidate is out of range)")
        val result = Recombination.recombine(target, donor, selected, args.opt("name", "${target.name}_recombined"))
        System.err.println("Recombined ${target.name} with ${donor.name}: target ${selected.targetLeft + 1}..${selected.targetRight + selected.armLength}")
        emit(result.product, args)
    }

    private fun listEnzymes(args: Args) {
        val filter = args.opt("filter")?.lowercase()
        val shown = Enzymes.ALL.filter { filter == null || it.name.lowercase().contains(filter) }
        println("%-12s %-12s %-14s %s".format("name", "site", "cut", "ends"))
        shown.forEach {
            println("%-12s %-12s %-14s %s".format(it.name, it.site, it.notation(), it.endType.label))
        }
        println("${shown.size} enzyme(s).")
    }

    private fun externalTools(args: Args) {
        val toolId = args.opt("run")
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
            println("$ ${preview.render()}")
            if (preview.missingPlaceholders.isNotEmpty()) {
                println("Missing required values: ${preview.missingPlaceholders.joinToString(", ")}")
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
            println("Bundled samples: ${SeqIO.Samples.ALL.joinToString(", ") { it.name }}")
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
          align --query read.fa[,read2.fa] reference.fa
          gel --enzymes EcoRI,BamHI sequence.fa [--completion 50]
          identity FILE [--json]                print a stable sequence identity
          dilute --stock 100 --final 10 --volume 100
          mix --components buffer=2,water=5 --reactions 10 [--overhead 0.1]
          blast-url FILE [--program blastn] [--expect 100]
          ncbi-search --term "gene name" [--max-hits 20]
          ncbi-fetch --accession ACCESSION [--to genbank]
          enzymes [--filter eco]          the built-in restriction enzyme list
          sites FILE                      unique cutters and non-cutters
          bench | benchmark FILE           time all engine operations on the input

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
          plasmid --backbone vec.gb --insert gene.fa --enzymes EcoRI,HindIII [--name pMyGene]
          gibson --parts a.fa,b.fa,c.fa [--min-overlap 20] [--linear]
          golden-gate --parts a.fa,b.fa --overhangs A,G,A [--linear]
          recombine --target target.fa --donor donor.fa --arm 20 [--candidate 1]
          digest --enzymes EcoRI,BamHI [--fasta]   cut sites and fragments
          primers --from 100 --to 400 [--tm 60] [--advanced] [--backend builtin|primer3]

        External CLI tools (optional)
          tools                           what is installed, and how to install the rest
          tools --run primer3 --preview   show a reproducible command without running it
          tools --run seqkit-stats FILE
          tools --run emboss-restrict FILE
          tools --run seqkit-locate --pattern GGATCC FILE

        Desktop
          gui [FILE ...]                  launch the desktop GUI (opens FILE if given)
          gui --launcher PATH             run a specific GUI launcher instead of auto-detecting
          ${INSTAGENE_GUI}           environment variable pointing at the GUI launcher

        Other
          sample [pUC19_MCS|GFP_CDS]      write a bundled example sequence
          --circular                      treat the input as a circular molecule

        Examples
          instagene sample GFP_CDS > gfp.fa
          cat gfp.fa | instagene translate --stop-at-stop
          instagene digest --enzymes EcoRI,HindIII gfp.fa
          instagene plasmid --backbone puc19.gb --insert gfp.fa --enzymes EcoRI,HindIII -o pGFP.gb
    """.trimIndent()
    }
}
