package org.instagene.app.cli

import org.instagene.core.Assembly
import org.instagene.core.AssemblyException
import org.instagene.core.CodonTable
import org.instagene.core.Digest
import org.instagene.core.Enzymes
import org.instagene.core.ExternalTools
import org.instagene.core.Feature
import org.instagene.core.Seq
import org.instagene.core.SeqOps
import org.instagene.core.Topology
import org.instagene.core.Version
import org.instagene.core.io.SeqFormat
import org.instagene.core.io.SeqIO
import java.io.File
import kotlin.math.roundToInt

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
            dispatch(command, args)
            0
        } catch (e: CliException) {
            System.err.println(Colors.red("instagene: ${e.message}", colors))
            1
        } catch (e: AssemblyException) {
            System.err.println(Colors.red("instagene: ${e.message}", colors))
            1
        } catch (e: org.instagene.core.io.SeqIOException) {
            System.err.println(Colors.red("instagene: ${e.message}", colors))
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

            "info", "stats" -> info(load(args))
            "revcomp", "rc" -> emit(load(args).reverseComplement(), args)
            "complement" -> emit(load(args).complement(), args)
            "transcribe" -> emit(SeqOps.transcribe(load(args)), args)
            "backtranscribe" -> emit(SeqOps.backTranscribe(load(args)), args)
            "translate" -> translate(load(args), args)
            "gc" -> gc(load(args))
            "tm" -> tm(load(args))
            "orf" -> orfs(load(args), args)
            "find" -> find(load(args), args)
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
                SeqIO.read(file)
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
            else -> throw CliException("Unknown format '$requested' (use fasta or genbank)")
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

    private fun info(seq: Seq) {
        val counts = SeqOps.baseCounts(seq.bases)
        println("Name        ${seq.name}")
        if (seq.description.isNotBlank()) println("Description ${seq.description}")
        println("Type        ${seq.kind.name.lowercase()}")
        println("Topology    ${seq.topology.name.lowercase()}")
        println("Length      ${seq.length} ${if (seq.kind.name == "PROTEIN") "aa" else "bp"}")
        println("GC content  ${round(SeqOps.gcContent(seq))} %")
        println("Tm          ${round(SeqOps.meltingTemp(seq.bases))} C")
        println("MW          ${round(SeqOps.molecularWeightDaltons(seq) / 1000.0)} kDa")
        println("Composition ${counts.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
        if (seq.features.isNotEmpty()) {
            println("Features")
            seq.features.forEach {
                println("  %-20s %-14s %s %s".format(it.name.take(20), it.type, it.displayRange(), it.strand.symbol))
            }
        }
        val cutters = Digest.enzymesCutting(seq, 1)
        if (cutters.isNotEmpty()) {
            println("Unique cutters (${cutters.size}): ${cutters.joinToString(", ") { it.name }}")
        }
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
        println("%-10s %-10s %-7s %-6s %s".format("start", "end", "strand", "aa", "protein"))
        orfs.forEach {
            println(
                "%-10d %-10d %-7s %-6d %s".format(
                    it.start + 1, it.end, it.strand.symbol, it.lengthAa, it.protein.take(60)
                )
            )
        }
        println("${orfs.size} ORF(s).")
    }

    private fun find(seq: Seq, args: Args) {
        val pattern = args.require("pattern")
        val hits = SeqOps.find(seq, pattern, !args.flag("forward-only"))
        hits.forEach { (pos, strand) -> println("${pos + 1}\t${strand.symbol}\t$pattern") }
        println("${hits.size} hit(s) for $pattern in ${seq.name}")
    }

    private fun digest(seq: Seq, args: Args) {
        val enzymes = when {
            args.has("enzymes") -> Enzymes.parseList(args.require("enzymes"))
            args.flag("all") -> Enzymes.ALL
            else -> throw CliException("Specify --enzymes EcoRI,BamHI (or --all)")
        }
        val sites = Digest.cutSites(seq, enzymes)
        println("Cut sites in ${seq.name} (${seq.length} bp, ${seq.topology.name.lowercase()}):")
        if (sites.isEmpty()) {
            println("  none")
        } else {
            sites.forEach {
                println(
                    "  %-10s at %-8d site %-10s %s".format(
                        it.enzyme.name, it.topCut + 1, it.enzyme.notation(), it.enzyme.endType.label
                    )
                )
            }
        }
        val fragments = Digest.digest(seq, enzymes)
        println()
        println("Fragments (${fragments.size}):")
        println("  %-6s %-10s %-10s %-22s %s".format("#", "length", "start", "left end", "right end"))
        fragments.forEachIndexed { i, f ->
            println("  %-6d %-10d %-10d %-22s %s".format(i + 1, f.length, f.start + 1, f.leftEnd, f.rightEnd))
        }
        if (args.flag("fasta")) {
            println()
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
        val seq = load(args)
        val placeholders = buildMap {
            args.opt("pattern")?.let { put("pattern", it) }
            args.opt("other")?.let { put("other", File(it).absolutePath) }
        }
        val extra = args.opt("args")?.split(' ')?.filter { it.isNotBlank() } ?: emptyList()
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

    private fun round(v: Double): Double = (v * 10).roundToInt() / 10.0

    fun usage(colors: Boolean = false): String {
        val heading = Colors.bold("InstaGene ${Version.VERSION} - DNA/RNA editing and plasmid construction.", colors)
        return """
        $heading

        Usage: instagene <command> [options] [file]

        Input is read from the given file, from --in, or from stdin (FASTA, GenBank or bare bases).
        Output goes to stdout, or to --out FILE. Use --to fasta|genbank to pick the format.

        Global options
          --env FILE        apply defaults from a KEY=VALUE file (command line wins)
          --no-colors       plain output, no ANSI colors
          --version         print the InstaGene version and exit

        Inspecting
          info FILE                       summary: length, GC, Tm, features, unique cutters
          gc / tm FILE                    single numbers, handy in scripts
          orf [--min-aa 30] [--table 11]  open reading frames on both strands
          find --pattern GGATCC [--forward-only]
          enzymes [--filter eco]          the built-in restriction enzyme list
          sites FILE                      unique cutters and non-cutters

        Editing
          revcomp | complement | transcribe | backtranscribe
          translate [--frame 1] [--table 1|11] [--all-frames] [--stop-at-stop]
          edit --insert ACGT --at 10
          edit --delete --from 5 --to 20
          edit --replace ACGT --from 5 --to 8
          extract --from 100 --to 400 [--revcomp]
          annotate --from 1 --to 60 --label promoter [--type promoter]
          topology --set circular|linear [--origin 500]
          convert --to genbank

        Building plasmids
          plasmid --backbone vec.gb --insert gene.fa --enzymes EcoRI,HinDIII [--name pMyGene]
          gibson --parts a.fa,b.fa,c.fa [--min-overlap 20] [--linear]
          digest --enzymes EcoRI,BamHI [--fasta]   cut sites and fragments
          primers --from 100 --to 400 [--tm 60]

        External CLI tools (optional)
          tools                           what is installed, and how to install the rest
          tools --run seqkit-stats FILE
          tools --run emboss-restrict FILE
          tools --run seqkit-locate --pattern GGATCC FILE

        Other
          sample [pUC19_MCS|GFP_CDS]      write a bundled example sequence
          --circular                      treat the input as a circular molecule

        Examples
          instagene sample GFP_CDS > gfp.fa
          cat gfp.fa | instagene translate --stop-at-stop
          instagene digest --enzymes EcoRI,HinDIII gfp.fa
          instagene plasmid --backbone puc19.gb --insert gfp.fa --enzymes EcoRI,HinDIII -o pGFP.gb
    """.trimIndent()
    }
}
