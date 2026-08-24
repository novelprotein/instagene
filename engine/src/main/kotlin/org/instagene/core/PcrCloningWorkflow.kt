package org.instagene.core

/**
 * A restriction-cloning request in which the insert is first amplified from a
 * template. Coordinates are zero-based and half-open (`[insertStart, insertEnd)`).
 *
 * The workflow deliberately models the complete construct-producing path:
 * endpoint primer selection, 5' restriction-site tails, simulated PCR, digest
 * compatibility checks, and ligation into a circular backbone. This keeps the
 * desktop wizard, CLI, and headless callers on the same reproducible path.
 */
data class PcrCloningRequest(
    val backbone: Seq,
    val insertTemplate: Seq,
    val insertStart: Int = 0,
    val insertEnd: Int = insertTemplate.length,
    /** One enzyme creates a non-directional clone; two distinct enzymes create a directional clone. */
    val enzymes: List<Enzyme>,
    val productName: String = "${backbone.name}_${insertTemplate.name}_pcr_clone",
    val primerParameters: PrimerDesignParameters = PrimerDesignParameters(),
    val primerBackend: PrimerDesignBackend = PrimerDesignBackend.BUILTIN,
    /** Extra 5' bases before the left restriction site, retained in the PCR product but removed by digestion. */
    val leftClamp: String = "GCGC",
    /** Extra 3' bases after the right restriction site, represented as a 5' tail on the reverse primer. */
    val rightClamp: String = "GCGC",
)

/** A named, half-open coordinate span with a biologist-facing display range. */
data class WorkflowCoordinates(val start: Int, val end: Int) {
    init {
        require(start >= 0) { "Coordinate start must not be negative" }
        require(end >= start) { "Coordinate end must not precede the start" }
    }

    val length: Int get() = end - start

    fun displayRange(): String = "${start + 1}..$end"
}

/** Coordinates carried through PCR and into the restriction-cloning product. */
data class PcrCloningCoordinates(
    /** Selected region on [PcrCloningRequest.insertTemplate]. */
    val templateTarget: WorkflowCoordinates,
    /** The target portion of the amplicon, excluding the added clamps and restriction sites. */
    val pcrTarget: WorkflowCoordinates,
    /** The fragment in the final circular product that came from the digested amplicon. */
    val productInsert: WorkflowCoordinates,
)

/** Reviewable checks run before a PCR-cloning product is returned. */
data class PcrCloningValidation(
    val passed: Boolean,
    val targetMatchesAmplicon: Boolean,
    val restrictionSitesAreUniqueInAmplicon: Boolean,
    val productContainsTarget: Boolean,
    val insertWasFlipped: Boolean,
    val coordinates: PcrCloningCoordinates,
    val diagnostics: List<WorkflowDiagnostic> = emptyList(),
)

/**
 * The complete PCR-cloning record. [recipe] intentionally captures input
 * identities rather than input bases; it is safe to attach to a project or
 * electronic notebook and can be replayed only with matching inputs.
 */
data class PcrCloningResult(
    val request: PcrCloningRequest,
    val primerDesign: PrimerDesignResult,
    val forwardPrimer: PcrPrimer,
    val reversePrimer: PcrPrimer,
    val amplification: PcrResult,
    val cloning: MolecularWorkflowResult,
    val validation: PcrCloningValidation,
    val recipe: WorkflowRecipe,
) {
    val product: Seq get() = cloning.product
}

/** PCR-amplified, restriction-enzyme cloning with deterministic product validation. */
object PcrCloningWorkflows {

    fun designAndClone(
        request: PcrCloningRequest,
        cancellationRequested: () -> Boolean = { false },
        /** Injectable for deterministic integrations/tests; production uses [ExternalTools]. */
        primer3Runner: ((String) -> ToolResult)? = null,
    ): PcrCloningResult {
        val enzymes = validateRequest(request)
        val parameters = request.primerParameters.copy(mode = PrimerDesignMode.PCR)
        val design = PrimerDesign.design(
            request.insertTemplate,
            request.insertStart,
            request.insertEnd,
            parameters,
            request.primerBackend,
            cancellationRequested,
            primer3Runner,
        )
        val (forwardCandidate, reverseCandidate) = selectEndpointPair(
            request.insertTemplate,
            request.insertStart,
            request.insertEnd,
            design.candidates,
        )

        val leftEnzyme = enzymes.first()
        val rightEnzyme = enzymes.last()
        val forward = PcrPrimer(
            name = "${request.productName}_forward",
            hybridizingSequence = forwardCandidate.primer.bases,
            extension = cleanTail(request.leftClamp, "Left clamp") + leftEnzyme.site.uppercase(),
        )
        val reverse = PcrPrimer(
            name = "${request.productName}_reverse",
            hybridizingSequence = reverseCandidate.primer.bases,
            extension = Alphabet.reverseComplement(rightEnzyme.site.uppercase() + cleanTail(request.rightClamp, "Right clamp")),
        )
        val amplification = PcrWorkflows.amplify(
            request.insertTemplate,
            forward,
            reverse,
            "${request.productName}_amplicon",
        )

        val target = request.insertTemplate.sub(request.insertStart, request.insertEnd).uppercase()
        val pcrTargetStart = forward.extension.length
        val pcrTargetEnd = amplification.product.length - reverse.extension.length
        val amplifiedTarget = amplification.product.bases.substring(pcrTargetStart, pcrTargetEnd).uppercase()
        val expectedCutCounts = enzymes.associateWith { if (enzymes.size == 1) 2 else 1 }
        val actualCutCounts = enzymes.associateWith { enzyme -> Digest.countSites(amplification.product, enzyme) }
        require(actualCutCounts.all { (enzyme, count) -> count == expectedCutCounts.getValue(enzyme) }) {
            actualCutCounts.entries.joinToString(
                prefix = "The generated amplicon does not contain exactly the planned restriction sites: ",
            ) { (enzyme, count) -> "${enzyme.name}=$count (expected ${expectedCutCounts.getValue(enzyme)})" }
        }
        require(amplifiedTarget == target) {
            "The simulated PCR product does not preserve the selected insert target; review the primer-binding coordinates."
        }

        val rawCloning = CloningWorkflows.restriction(
            request.backbone,
            amplification.product,
            enzymes,
            request.productName,
        )
        val insertedFeature = rawCloning.product.features.lastOrNull {
            it.name == amplification.product.name && it.notes.startsWith("Inserted with")
        } ?: error("The restriction-cloning product is missing its inserted-fragment annotation")
        val productInsert = rawCloning.product.sub(insertedFeature.start, insertedFeature.end).uppercase()
        val insertWasFlipped = productInsert.contains(Alphabet.reverseComplement(target)) && !productInsert.contains(target)
        val productContainsTarget = productInsert.contains(target) || productInsert.contains(Alphabet.reverseComplement(target))
        require(productContainsTarget) {
            "The final product does not contain the selected insert target after restriction cloning."
        }

        val diagnostics = buildList {
            addAll(design.warnings.map { WorkflowDiagnostic(DiagnosticSeverity.WARNING, it) })
            if (enzymes.size == 1) {
                add(WorkflowDiagnostic(DiagnosticSeverity.WARNING, "One enzyme was selected, so insert orientation is not directionally constrained."))
            }
            if (insertWasFlipped) {
                add(WorkflowDiagnostic(DiagnosticSeverity.WARNING, "The insert was ligated in reverse orientation."))
            }
            add(WorkflowDiagnostic(DiagnosticSeverity.INFO, "PCR target ${request.insertStart + 1}..${request.insertEnd} matches the simulated amplicon."))
            add(WorkflowDiagnostic(DiagnosticSeverity.INFO, "Restriction-site counts in the amplicon were validated before ligation."))
        }
        // Keep both stages of provenance: the final molecule was made from a PCR
        // product and then restriction-cloned, not merely ligated in isolation.
        val cloning = rawCloning.copy(
            product = rawCloning.product.copy(provenance = amplification.product.provenance + rawCloning.product.provenance),
            diagnostics = rawCloning.diagnostics + diagnostics,
        )
        val coordinates = PcrCloningCoordinates(
            templateTarget = WorkflowCoordinates(request.insertStart, request.insertEnd),
            pcrTarget = WorkflowCoordinates(pcrTargetStart, pcrTargetEnd),
            productInsert = WorkflowCoordinates(insertedFeature.start, insertedFeature.end),
        )
        val validation = PcrCloningValidation(
            passed = true,
            targetMatchesAmplicon = true,
            restrictionSitesAreUniqueInAmplicon = true,
            productContainsTarget = true,
            insertWasFlipped = insertWasFlipped,
            coordinates = coordinates,
            diagnostics = diagnostics,
        )
        val recipe = Reports.workflowRecipe(
            operation = "PCR_RESTRICTION_CLONING",
            product = cloning.product,
            inputs = listOf(request.backbone, request.insertTemplate),
            parameters = recipeParameters(request, enzymes, forward, reverse, coordinates, design),
        )
        return PcrCloningResult(request, design, forward, reverse, amplification, cloning, validation, recipe)
    }

    /** Parameters deliberately use normalized strings so report, recipe, CLI, and GUI agree byte-for-byte. */
    fun recipeParameters(
        request: PcrCloningRequest,
        enzymes: List<Enzyme> = request.enzymes,
        forward: PcrPrimer? = null,
        reverse: PcrPrimer? = null,
        coordinates: PcrCloningCoordinates? = null,
        design: PrimerDesignResult? = null,
    ): Map<String, String> = linkedMapOf(
        "enzymeNames" to enzymes.joinToString(",") { it.name },
        "insertTarget" to (coordinates?.templateTarget?.displayRange() ?: "${request.insertStart + 1}..${request.insertEnd}"),
        "leftClamp" to cleanTail(request.leftClamp, "Left clamp"),
        "primerBackend" to (design?.backend ?: request.primerBackend).name,
        "productName" to request.productName,
        "rightClamp" to cleanTail(request.rightClamp, "Right clamp"),
    ).also { parameters ->
        forward?.let { parameters["forwardPrimer"] = it.extension + it.hybridizingSequence }
        reverse?.let { parameters["reversePrimer"] = it.extension + it.hybridizingSequence }
    }.toSortedMap()

    private fun validateRequest(request: PcrCloningRequest): List<Enzyme> {
        require(request.backbone.kind != SeqKind.PROTEIN && request.insertTemplate.kind != SeqKind.PROTEIN) {
            "PCR cloning requires nucleotide sequences"
        }
        require(request.backbone.isCircular) { "PCR cloning requires a circular plasmid backbone" }
        require(request.insertStart in 0 until request.insertTemplate.length && request.insertEnd in (request.insertStart + 1)..request.insertTemplate.length) {
            "Insert target must be a non-empty range inside the template"
        }
        require(request.enzymes.size in 1..2) { "Choose one enzyme for a non-directional clone or two enzymes for directional cloning" }
        val enzymes = request.enzymes.distinctBy { it.name.lowercase() }
        require(enzymes.size == request.enzymes.size) { "Choose each restriction enzyme only once" }
        enzymes.forEach { enzyme ->
            require(enzyme.site.uppercase().all { it in "ACGT" }) {
                "${enzyme.name} has an ambiguous recognition site and cannot be added as an unambiguous PCR-primer tail"
            }
            require(Digest.countSites(request.backbone, enzyme) == 1) {
                "${enzyme.name} must cut the backbone exactly once"
            }
        }
        val target = Seq("selected_insert_target", request.insertTemplate.sub(request.insertStart, request.insertEnd))
        enzymes.forEach { enzyme ->
            require(Digest.countSites(target, enzyme) == 0) {
                "Selected insert target contains an internal ${enzyme.name} site; choose another enzyme or domesticate the target"
            }
        }
        cleanTail(request.leftClamp, "Left clamp")
        cleanTail(request.rightClamp, "Right clamp")
        return enzymes
    }

    private fun selectEndpointPair(
        template: Seq,
        start: Int,
        end: Int,
        candidates: List<PrimerCandidate>,
    ): Pair<PrimerCandidate, PrimerCandidate> {
        val forward = candidates.filter { candidate ->
            candidate.start == start &&
                candidate.end <= end &&
                candidate.primer.bases.equals(template.sub(candidate.start, candidate.end), ignoreCase = true)
        }.minByOrNull { it.score }
        val reverse = candidates.filter { candidate ->
            candidate.end == end &&
                candidate.start >= start &&
                candidate.primer.bases.equals(
                    Alphabet.reverseComplement(template.sub(candidate.start, candidate.end)),
                    ignoreCase = true,
                )
        }.minByOrNull { it.score }
        require(forward != null && reverse != null) {
            "No valid endpoint primer pair meets the selected length, Tm, GC, self-complementarity, and quality constraints."
        }
        return forward to reverse
    }

    private fun cleanTail(value: String, label: String): String {
        val cleaned = Alphabet.normalizeDna(value)
        require(cleaned.all { it in "ACGT" }) { "$label must contain only A, C, G, and T" }
        return cleaned
    }
}
