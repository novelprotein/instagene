package org.instagene.core

/** Stable identifiers for cloning procedures exposed by the engine and desktop UI. */
enum class CloningMethod {
    RESTRICTION,
    GATEWAY,
    GIBSON,
    NEBUILDER_HIFI,
    IN_FUSION,
    TA,
    GC,
    TOPO_TA,
    TOPO_DIRECTIONAL,
    TOPO_BLUNT,
    GOLDEN_GATE,
    HOMOLOGY_RECOMBINATION,
}

enum class DiagnosticSeverity { INFO, WARNING, ERROR }

data class WorkflowDiagnostic(
    val severity: DiagnosticSeverity,
    val message: String,
    val position: Int? = null,
)

data class ProtocolStep(val title: String, val detail: String)

/** A scientific product plus the information needed to review and reproduce it. */
data class MolecularWorkflowResult(
    val method: CloningMethod,
    val product: Seq,
    val steps: List<ProtocolStep>,
    val diagnostics: List<WorkflowDiagnostic> = emptyList(),
    val primers: List<PrimerAnnotation> = emptyList(),
    /** Normalized invocation values retained independently from display-oriented protocol text. */
    val parameters: Map<String, String> = emptyMap(),
)

/** Cloning workflows built on the shared assembly primitives. */
object CloningWorkflows {

    fun restriction(
        backbone: Seq,
        insert: Seq,
        enzymes: List<Enzyme>,
        name: String = "${backbone.name}_${insert.name}",
    ): MolecularWorkflowResult {
        val built = Assembly.buildPlasmid(backbone, insert, enzymes, name)
        return result(
            CloningMethod.RESTRICTION,
            built.plasmid,
            built.log.map { ProtocolStep("Restriction cloning", it) },
            inputs = listOf(backbone.name, insert.name),
            parameters = mapOf(
                "enzymeNames" to enzymes.joinToString(",") { it.name },
                "productName" to name,
            ),
        )
    }

    fun overlapAssembly(
        method: CloningMethod,
        parts: List<Seq>,
        name: String,
        circular: Boolean = true,
        minOverlap: Int = defaultOverlap(method),
    ): MolecularWorkflowResult {
        require(method in setOf(CloningMethod.GIBSON, CloningMethod.NEBUILDER_HIFI, CloningMethod.IN_FUSION)) {
            "$method is not an overlap-assembly method"
        }
        val assembled = Assembly.gibson(parts, minOverlap, name, circular)
        val diagnostics = assembled.overlaps.mapIndexedNotNull { index, overlap ->
            if (overlap >= minOverlap + 5) null
            else WorkflowDiagnostic(
                DiagnosticSeverity.WARNING,
                "Junction ${index + 1} has only $overlap bp of homology; $minOverlap bp is the configured minimum.",
            )
        }
        return result(
            method,
            assembled.product,
            assembled.log.map { ProtocolStep(method.displayName(), it) },
            diagnostics,
            inputs = parts.map { it.name },
            parameters = mapOf(
                "circular" to circular.toString(),
                "minimumOverlap" to minOverlap.toString(),
                "productName" to name,
            ),
        )
    }

    /** Replaces the cassette between explicit recombination sites with the insert between the same sites. */
    fun gateway(
        destination: Seq,
        insert: Seq,
        leftSite: String,
        rightSite: String,
        name: String = "${destination.name}_${insert.name}_gateway",
    ): MolecularWorkflowResult {
        val left = cleanDna(leftSite, "Left recombination site")
        val right = cleanDna(rightSite, "Right recombination site")
        val vectorLeft = uniqueSite(destination, left, "destination left site")
        val vectorRight = uniqueSite(destination, right, "destination right site")
        val insertLeft = uniqueSite(insert, left, "insert left site")
        val insertRight = uniqueSite(insert, right, "insert right site")
        require(vectorLeft + left.length <= vectorRight) { "Destination recombination sites are reversed or overlapping" }
        require(insertLeft + left.length <= insertRight) { "Insert recombination sites are reversed or overlapping" }

        val payload = insert.bases.substring(insertLeft + left.length, insertRight)
        val product = destination.replaceRange(vectorLeft + left.length, vectorRight, payload).copy(name = name)
        return result(
            CloningMethod.GATEWAY,
            product,
            listOf(
                ProtocolStep("Identify recombination sites", "Found unique left and right sites in both molecules."),
                ProtocolStep("Exchange cassette", "Replaced ${vectorRight - vectorLeft - left.length} bp with ${payload.length} bp."),
            ),
            inputs = listOf(destination.name, insert.name),
            parameters = mapOf(
                "leftSite" to left,
                "productName" to name,
                "rightSite" to right,
            ),
        )
    }

    fun goldenGate(
        parts: List<Seq>,
        overhangs: List<String>,
        name: String = "golden_gate_product",
        circular: Boolean = true,
    ): MolecularWorkflowResult {
        val normalized = overhangs.map { cleanDna(it, "Golden Gate overhang") }
        require(normalized.all { it.length == 4 }) { "Golden Gate overhangs must be exactly 4 bases" }
        val duplicate = normalized.groupBy { it }.entries.firstOrNull { it.value.size > 1 }?.key
        val assembled = AssemblyWorkflows.goldenGate(parts, normalized, name, circular)
        val diagnostics = buildList {
            if (duplicate != null) add(WorkflowDiagnostic(DiagnosticSeverity.WARNING, "Overhang $duplicate is reused and may permit an unintended ligation."))
            normalized.forEach { overhang ->
                if (overhang == Alphabet.reverseComplement(overhang)) {
                    add(WorkflowDiagnostic(DiagnosticSeverity.WARNING, "Palindromic overhang $overhang can ligate in either orientation."))
                }
            }
        }
        return result(
            CloningMethod.GOLDEN_GATE,
            assembled.product,
            assembled.log.map { ProtocolStep("Golden Gate assembly", it) },
            diagnostics,
            inputs = parts.map { it.name },
            parameters = mapOf(
                "circular" to circular.toString(),
                "overhangs" to normalized.joinToString(","),
                "productName" to name,
            ),
        )
    }

    /** Models TA/GC/TOPO insertion at the displayed vector origin. */
    fun terminalClone(
        method: CloningMethod,
        vector: Seq,
        insert: Seq,
        name: String = "${vector.name}_${insert.name}_${method.name.lowercase()}",
    ): MolecularWorkflowResult {
        require(method in setOf(
            CloningMethod.TA,
            CloningMethod.GC,
            CloningMethod.TOPO_TA,
            CloningMethod.TOPO_DIRECTIONAL,
            CloningMethod.TOPO_BLUNT,
        )) { "$method is not a terminal cloning method" }
        require(vector.kind != SeqKind.PROTEIN && insert.kind != SeqKind.PROTEIN) { "Cloning requires nucleotide molecules" }
        val diagnostics = buildList {
            if (method in setOf(CloningMethod.TA, CloningMethod.TOPO_TA) && !insert.bases.endsWith("A", true)) {
                add(WorkflowDiagnostic(DiagnosticSeverity.WARNING, "The insert does not end in A; add 3' A overhangs before TA cloning."))
            }
            if (method == CloningMethod.GC && !insert.bases.endsWith("G", true)) {
                add(WorkflowDiagnostic(DiagnosticSeverity.WARNING, "The insert does not end in G; add 3' G overhangs before GC cloning."))
            }
            if (method == CloningMethod.TOPO_DIRECTIONAL && !insert.bases.startsWith("CACC", true)) {
                add(WorkflowDiagnostic(DiagnosticSeverity.ERROR, "Directional TOPO cloning requires a 5' CACC motif on the insert."))
            }
        }
        require(diagnostics.none { it.severity == DiagnosticSeverity.ERROR }) { diagnostics.first { it.severity == DiagnosticSeverity.ERROR }.message }
        val backbone = vector.copy(topology = Topology.LINEAR)
        val product = (backbone + insert.copy(topology = Topology.LINEAR)).copy(name = name, topology = Topology.CIRCULAR)
        return result(
            method,
            product,
            listOf(
                ProtocolStep("Prepare ends", method.displayName()),
                ProtocolStep("Insert fragment", "Inserted ${insert.name} (${insert.length} bp) at the vector origin."),
                ProtocolStep("Circularize", "Product length ${product.length} bp."),
            ),
            diagnostics,
            inputs = listOf(vector.name, insert.name),
            parameters = mapOf("productName" to name),
        )
    }

    /** Replaces a target interval with a donor bounded by matching homology arms. */
    fun homologyRecombination(
        target: Seq,
        donor: Seq,
        armLength: Int = 20,
        candidateIndex: Int = 0,
        name: String = "${target.name}_recombined",
    ): MolecularWorkflowResult {
        val candidates = Recombination.candidates(target, donor, armLength)
        val selected = candidates.getOrNull(candidateIndex)
            ?: throw IllegalArgumentException(
                "No matching recombination candidate found at index ${candidateIndex + 1}; " +
                    "${candidates.size} candidate(s) are available.",
            )
        val recombined = Recombination.recombine(target, donor, selected, name)
        return result(
            CloningMethod.HOMOLOGY_RECOMBINATION,
            recombined.product,
            listOf(
                ProtocolStep("Find homology arms", "Selected candidate ${candidateIndex + 1} with $armLength bp left and right arms."),
                ProtocolStep("Replace target interval", "Recombined ${donor.name} into ${target.name} at ${selected.targetLeft + 1}..${selected.targetRight + armLength}."),
            ),
            inputs = listOf(target.name, donor.name),
            parameters = mapOf(
                "armLength" to armLength.toString(),
                "candidateIndex" to candidateIndex.toString(),
                "productName" to name,
            ),
        )
    }

    private fun result(
        method: CloningMethod,
        rawProduct: Seq,
        steps: List<ProtocolStep>,
        diagnostics: List<WorkflowDiagnostic> = emptyList(),
        inputs: List<String>,
        parameters: Map<String, String> = emptyMap(),
    ): MolecularWorkflowResult {
        val product = rawProduct.withProcedure(
            ProcedureRecord(
                operation = method.name,
                summary = steps.joinToString(" ") { it.detail },
                inputs = inputs,
                warnings = diagnostics.filter { it.severity != DiagnosticSeverity.INFO }.map { it.message },
                timestamp = System.currentTimeMillis(),
            )
        )
        return MolecularWorkflowResult(method, product, steps, diagnostics, parameters = parameters.toSortedMap())
    }

    private fun uniqueSite(seq: Seq, site: String, label: String): Int {
        val hits = Regex(Regex.escape(site), RegexOption.IGNORE_CASE).findAll(seq.bases).map { it.range.first }.toList()
        require(hits.size == 1) { "Expected one $label in '${seq.name}', found ${hits.size}" }
        return hits.single()
    }

    private fun cleanDna(value: String, label: String): String {
        val cleaned = Alphabet.normalizeDna(value)
        require(cleaned.isNotEmpty() && cleaned.all { it in "ACGT" }) { "$label must contain only A, C, G, and T" }
        return cleaned
    }

    private fun defaultOverlap(method: CloningMethod): Int = when (method) {
        CloningMethod.IN_FUSION -> 15
        CloningMethod.GIBSON -> 20
        CloningMethod.NEBUILDER_HIFI -> 20
        else -> error("No overlap default for $method")
    }

    private fun CloningMethod.displayName(): String = name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
}

data class PcrPrimer(
    val name: String,
    val hybridizingSequence: String,
    val extension: String = "",
)

enum class PcrMode { STANDARD, INVERSE, OVERLAP_EXTENSION, MUTAGENESIS }

data class PcrResult(
    val mode: PcrMode,
    val product: Seq,
    val forwardBindingStart: Int,
    val reverseBindingStart: Int,
    val diagnostics: List<WorkflowDiagnostic> = emptyList(),
)

/** PCR, oligo annealing, and primer-directed sequence editing. */
object PcrWorkflows {

    fun amplify(
        template: Seq,
        forward: PcrPrimer,
        reverse: PcrPrimer,
        name: String = "${template.name}_amplicon",
        inverse: Boolean = false,
    ): PcrResult {
        require(template.kind != SeqKind.PROTEIN) { "PCR requires a nucleotide template" }
        val f = clean(forward.hybridizingSequence)
        val r = clean(reverse.hybridizingSequence)
        val reverseTarget = Alphabet.reverseComplement(r)
        val fStart = uniqueBinding(template, f, "forward primer")
        val rStart = uniqueBinding(template, reverseTarget, "reverse primer")
        val core = if (!inverse) {
            require(fStart < rStart) { "Forward primer must bind upstream of the reverse primer for standard PCR" }
            template.sub(fStart, rStart + reverseTarget.length)
        } else {
            require(template.isCircular) { "Inverse PCR requires a circular template" }
            template.sub(rStart, fStart + template.length + f.length)
        }
        val productBases = clean(forward.extension) + core + Alphabet.reverseComplement(clean(reverse.extension))
        val forwardAnnotation = PrimerAnnotation(forward.name, f, 0, f.length, Strand.FORWARD, clean(forward.extension))
        val reverseStartInProduct = productBases.length - r.length
        val reverseAnnotation = PrimerAnnotation(reverse.name, r, reverseStartInProduct, productBases.length, Strand.REVERSE, clean(reverse.extension))
        val product = Seq(
            name = name,
            bases = productBases,
            kind = SeqKind.DNA,
            topology = Topology.LINEAR,
            primers = listOf(forwardAnnotation, reverseAnnotation),
            provenance = listOf(
                ProcedureRecord(
                    operation = if (inverse) PcrMode.INVERSE.name else PcrMode.STANDARD.name,
                    summary = "Amplified ${productBases.length} bp from ${template.name}",
                    inputs = listOf(template.name, forward.name, reverse.name),
                    timestamp = System.currentTimeMillis(),
                )
            ),
        )
        return PcrResult(if (inverse) PcrMode.INVERSE else PcrMode.STANDARD, product, fStart, rStart)
    }

    fun overlapExtension(first: Seq, second: Seq, minOverlap: Int = 15, name: String = "overlap_extension_product"): PcrResult {
        val assembly = Assembly.gibson(listOf(first, second), minOverlap, name, circular = false)
        val product = assembly.product.withProcedure(
            ProcedureRecord(PcrMode.OVERLAP_EXTENSION.name, assembly.log.joinToString(" "), listOf(first.name, second.name), timestamp = System.currentTimeMillis())
        )
        return PcrResult(PcrMode.OVERLAP_EXTENSION, product, 0, first.length - assembly.overlaps.first())
    }

    fun mutagenize(
        template: Seq,
        original: String,
        replacement: String,
        name: String = "${template.name}_mutant",
    ): PcrResult {
        val old = clean(original)
        val next = clean(replacement)
        val start = uniqueBinding(template, old, "mutagenesis target")
        val product = template.replaceRange(start, start + old.length, next).copy(name = name).withProcedure(
            ProcedureRecord(
                PcrMode.MUTAGENESIS.name,
                "Replaced ${start + 1}..${start + old.length} ($old) with $next",
                listOf(template.name),
                timestamp = System.currentTimeMillis(),
            )
        )
        return PcrResult(PcrMode.MUTAGENESIS, product, start, start + next.length)
    }

    fun anneal(first: String, second: String, name: String = "annealed_oligos"): Seq {
        val a = clean(first)
        val b = clean(second)
        val bReverse = Alphabet.reverseComplement(b)
        val overlap = bestOffsetOverlap(a, bReverse)
        require(overlap.third > 0) { "The oligos have no complementary overlap" }
        val left = minOf(0, overlap.first)
        val right = maxOf(a.length, overlap.first + bReverse.length)
        val bases = CharArray(right - left) { 'N' }
        a.forEachIndexed { index, c -> bases[index - left] = c }
        bReverse.forEachIndexed { index, c ->
            val at = overlap.first + index - left
            if (bases[at] == 'N') bases[at] = c else require(bases[at] == c) { "Oligos disagree in their overlap" }
        }
        return Seq(
            name = name,
            bases = bases.concatToString(),
            kind = SeqKind.DNA,
            molecule = MoleculeProperties(strandedness = Strandedness.DOUBLE),
            provenance = listOf(ProcedureRecord("OLIGO_ANNEAL", "Annealed two oligos across ${overlap.third} bp", timestamp = System.currentTimeMillis())),
        )
    }

    private fun uniqueBinding(template: Seq, pattern: String, label: String): Int {
        val source = if (template.isCircular) template.bases + template.bases.take((pattern.length - 1).coerceAtLeast(0)) else template.bases
        val hits = Regex(Regex.escape(pattern), RegexOption.IGNORE_CASE).findAll(source)
            .map { it.range.first }
            .filter { it < template.length }
            .distinct()
            .toList()
        require(hits.size == 1) { "Expected one $label binding site, found ${hits.size}" }
        return hits.single()
    }

    private fun bestOffsetOverlap(a: String, b: String): Triple<Int, Int, Int> {
        var best = Triple(0, 0, 0)
        for (offset in -b.length..a.length) {
            val from = maxOf(0, offset)
            val to = minOf(a.length, offset + b.length)
            if (to <= from) continue
            var matches = 0
            var valid = true
            for (i in from until to) {
                if (a[i] != b[i - offset]) { valid = false; break }
                matches++
            }
            if (valid && matches > best.third) best = Triple(offset, from, matches)
        }
        return best
    }

    private fun clean(value: String): String {
        val cleaned = Alphabet.normalizeDna(value)
        require(cleaned.all { it in "ACGTN" }) { "Sequence contains non-DNA characters" }
        return cleaned
    }

}

data class CodonUsageProfile(val name: String, val preferredCodons: Map<Char, String>)

/** Reverse translation and deterministic codon replacement for construct design. */
object CodonDesign {
    val ECOLI = CodonUsageProfile("E. coli", mapOf(
        'A' to "GCG", 'R' to "CGT", 'N' to "AAC", 'D' to "GAT", 'C' to "TGC",
        'Q' to "CAG", 'E' to "GAA", 'G' to "GGT", 'H' to "CAC", 'I' to "ATT",
        'L' to "CTG", 'K' to "AAA", 'M' to "ATG", 'F' to "TTT", 'P' to "CCG",
        'S' to "AGC", 'T' to "ACC", 'W' to "TGG", 'Y' to "TAT", 'V' to "GTG", '*' to "TAA",
    ))

    val HUMAN = CodonUsageProfile("Human", mapOf(
        'A' to "GCC", 'R' to "CGG", 'N' to "AAC", 'D' to "GAC", 'C' to "TGC",
        'Q' to "CAG", 'E' to "GAG", 'G' to "GGC", 'H' to "CAC", 'I' to "ATC",
        'L' to "CTG", 'K' to "AAG", 'M' to "ATG", 'F' to "TTC", 'P' to "CCC",
        'S' to "AGC", 'T' to "ACC", 'W' to "TGG", 'Y' to "TAC", 'V' to "GTG", '*' to "TGA",
    ))

    /** Yeast (S. cerevisiae) preferred codons — from Kazusa CUTG database. */
    val YEAST = CodonUsageProfile("Yeast (S. cerevisiae)", mapOf(
        'A' to "GCT", 'R' to "AGA", 'N' to "AAC", 'D' to "GAC", 'C' to "TGT",
        'Q' to "CAA", 'E' to "GAA", 'G' to "GGT", 'H' to "CAT", 'I' to "ATT",
        'L' to "TTG", 'K' to "AAA", 'M' to "ATG", 'F' to "TTT", 'P' to "CCT",
        'S' to "AGT", 'T' to "ACT", 'W' to "TGG", 'Y' to "TAT", 'V' to "GTT", '*' to "TAA",
    ))

    /** CHO (Chinese Hamster Ovary) — shares mammalian codon bias with Human. */
    val CHO = CodonUsageProfile("CHO (Chinese Hamster Ovary)", HUMAN.preferredCodons)

    /** Drosophila (Insect) — from Kazusa CUTG database. */
    val DROSOPHILA = CodonUsageProfile("Drosophila", mapOf(
        'A' to "GCC", 'R' to "CGC", 'N' to "AAC", 'D' to "GAC", 'C' to "TGC",
        'Q' to "CAG", 'E' to "GAG", 'G' to "GGC", 'H' to "CAC", 'I' to "ATC",
        'L' to "CTG", 'K' to "AAG", 'M' to "ATG", 'F' to "TTC", 'P' to "CCC",
        'S' to "AGC", 'T' to "ACC", 'W' to "TGG", 'Y' to "TAC", 'V' to "GTG", '*' to "TGA",
    ))

    /** Arabidopsis (Plant) — from Kazusa CUTG database. */
    val ARABIDOPSIS = CodonUsageProfile("Arabidopsis", mapOf(
        'A' to "GCT", 'R' to "AGG", 'N' to "AAC", 'D' to "GAT", 'C' to "TGT",
        'Q' to "CAA", 'E' to "GAA", 'G' to "GGT", 'H' to "CAT", 'I' to "ATC",
        'L' to "CTG", 'K' to "AAA", 'M' to "ATG", 'F' to "TTC", 'P' to "CCT",
        'S' to "AGC", 'T' to "ACC", 'W' to "TGG", 'Y' to "TAT", 'V' to "GTT", '*' to "TGA",
    ))

    val PROFILES = listOf(ECOLI, HUMAN, YEAST, CHO, DROSOPHILA, ARABIDOPSIS)

    fun reverseTranslate(protein: Seq, profile: CodonUsageProfile = ECOLI, name: String = "${protein.name}_dna"): Seq {
        require(protein.kind == SeqKind.PROTEIN) { "Reverse translation requires a protein sequence" }
        val dna = protein.bases.uppercase().map { amino ->
            profile.preferredCodons[amino] ?: throw IllegalArgumentException("No codon configured for amino acid '$amino'")
        }.joinToString("")
        return Seq(name, dna, SeqKind.DNA, description = "Reverse translated from ${protein.name} using ${profile.name}")
            .withProcedure(ProcedureRecord("REVERSE_TRANSLATE", "Used ${profile.name} preferred codons", listOf(protein.name), timestamp = System.currentTimeMillis()))
    }

    fun optimize(dna: Seq, profile: CodonUsageProfile = ECOLI, frame: Int = 0, name: String = "${dna.name}_optimized"): Seq {
        require(dna.kind != SeqKind.PROTEIN) { "Codon optimization requires DNA or RNA" }
        require(frame in 0..2) { "Frame must be 0, 1, or 2" }
        val prefix = dna.bases.take(frame)
        val codingLength = ((dna.length - frame) / 3) * 3
        val coding = dna.bases.substring(frame, frame + codingLength)
        val optimized = coding.chunked(3).joinToString("") { codon ->
            val amino = CodonTable.STANDARD.translate(codon)
            profile.preferredCodons[amino] ?: codon.uppercase().replace('U', 'T')
        }
        val suffix = dna.bases.drop(frame + codingLength)
        return dna.copy(name = name, bases = prefix + optimized + suffix).withProcedure(
            ProcedureRecord("CODON_OPTIMIZE", "Optimized frame ${frame + 1} using ${profile.name}", listOf(dna.name), timestamp = System.currentTimeMillis())
        )
    }

    fun makeProtein(dna: Seq, frame: Int = 0, table: CodonTable = CodonTable.STANDARD, name: String = "${dna.name}_protein"): Seq {
        val protein = SeqOps.translate(dna, frame, table).copy(name = name)
        return protein.withProcedure(ProcedureRecord("MAKE_PROTEIN", "Translated frame ${frame + 1} with ${table.displayName}", listOf(dna.name), timestamp = System.currentTimeMillis()))
    }
}
