package org.instagene.core

enum class EndTreatment { NONE, FILL_OR_REMOVE_TO_BLUNT, A_OVERHANG, T_OVERHANG, C_OVERHANG, G_OVERHANG }

data class TreatedFragment(val fragment: Fragment, val leftTreatment: EndTreatment = EndTreatment.NONE, val rightTreatment: EndTreatment = EndTreatment.NONE) {
    fun apply(): Fragment = fragment.copy(leftEnd = treat(fragment.leftEnd, leftTreatment), rightEnd = treat(fragment.rightEnd, rightTreatment))

    private fun treat(end: StickyEnd, treatment: EndTreatment): StickyEnd = when (treatment) {
        EndTreatment.NONE -> end
        EndTreatment.FILL_OR_REMOVE_TO_BLUNT -> StickyEnd.BLUNT
        EndTreatment.A_OVERHANG -> StickyEnd(EndType.FIVE_PRIME_OVERHANG, "A")
        EndTreatment.T_OVERHANG -> StickyEnd(EndType.FIVE_PRIME_OVERHANG, "T")
        EndTreatment.C_OVERHANG -> StickyEnd(EndType.FIVE_PRIME_OVERHANG, "C")
        EndTreatment.G_OVERHANG -> StickyEnd(EndType.FIVE_PRIME_OVERHANG, "G")
    }
}

data class AssemblyWorkflowResult(val product: Seq, val fragments: List<Fragment>, val log: List<String>)

object AssemblyWorkflows {
    fun restrictionLigation(fragments: List<TreatedFragment>, name: String, circular: Boolean = false): AssemblyWorkflowResult {
        require(fragments.isNotEmpty()) { "At least one fragment is required" }
        val treated = fragments.map { it.apply() }
        treated.zipWithNext().forEachIndexed { index, (left, right) ->
            if (!Assembly.canLigate(left, right)) throw AssemblyException("Junction ${index + 1} cannot ligate: ${left.rightEnd} vs ${right.leftEnd}")
        }
        val joined = Assembly.ligate(treated)
        val product = if (circular) Assembly.circularize(joined, name) else joined.toSeq(name)
        return AssemblyWorkflowResult(product, treated, listOf("Joined ${treated.size} fragment(s)", "Product ${product.length} bp"))
    }

    fun goldenGate(
        parts: List<Seq>,
        overhangs: List<String>,
        name: String = "golden_gate_product",
        circular: Boolean = true,
        forbiddenEnzymes: List<Enzyme> = emptyList(),
    ): AssemblyWorkflowResult {
        require(parts.isNotEmpty() && overhangs.size == parts.size + 1) { "Need one left/right overhang around every part" }
        val internalSites = parts.flatMap { part ->
            forbiddenEnzymes.filter { enzyme -> Digest.cutSites(part, listOf(enzyme)).isNotEmpty() }.map { it.name to part.name }
        }
        require(internalSites.isEmpty()) {
            "Forbidden internal Type IIS sites: ${internalSites.joinToString { (enzyme, part) -> "$enzyme in $part" }}"
        }
        val orderDiagnostics = goldenGateOrderDiagnostics(parts, overhangs, circular)
        require(orderDiagnostics.none { it.severity == DiagnosticSeverity.ERROR }) {
            orderDiagnostics.filter { it.severity == DiagnosticSeverity.ERROR }.joinToString("; ") { it.message }
        }
        val fragments = parts.mapIndexed { index, part ->
            Fragment(part.bases, StickyEnd(EndType.FIVE_PRIME_OVERHANG, overhangs[index].uppercase(), "Type IIS"), StickyEnd(EndType.FIVE_PRIME_OVERHANG, overhangs[index + 1].uppercase(), "Type IIS"), part.name, 0, part.features)
        }
        return restrictionLigation(fragments.map { TreatedFragment(it) }, name, circular).let { result ->
            result.copy(log = orderDiagnostics.filter { it.severity != DiagnosticSeverity.INFO }.map { it.message } + result.log)
        }
    }

    /** Reports ambiguous or impossible Golden Gate ordering before assembly. */
    fun goldenGateOrderDiagnostics(
        parts: List<Seq>,
        overhangs: List<String>,
        circular: Boolean = true,
    ): List<WorkflowDiagnostic> {
        if (parts.isEmpty()) return listOf(WorkflowDiagnostic(DiagnosticSeverity.ERROR, "At least one part is required"))
        if (overhangs.size != parts.size + 1) {
            return listOf(WorkflowDiagnostic(DiagnosticSeverity.ERROR, "Need one left/right overhang around every part"))
        }
        val diagnostics = mutableListOf<WorkflowDiagnostic>()
        val normalized = overhangs.map { it.uppercase().trim() }
        normalized.forEachIndexed { index, overhang ->
            if (overhang.length != 4 || overhang.any { it !in "ACGT" }) {
                diagnostics += WorkflowDiagnostic(DiagnosticSeverity.WARNING, "Non-standard Golden Gate overhang '$overhang' at boundary ${index + 1}", index)
            }
        }
        val internal = normalized.drop(1).dropLast(1)
        internal.groupBy { it }.filterValues { it.size > 1 }.keys.forEach { overhang ->
            diagnostics += WorkflowDiagnostic(DiagnosticSeverity.WARNING, "Internal overhang '$overhang' is repeated; assembly order may be ambiguous")
        }
        parts.map { it.name }.groupBy { it }.filterValues { it.size > 1 }.keys.forEach { name ->
            diagnostics += WorkflowDiagnostic(DiagnosticSeverity.WARNING, "Part name '$name' is repeated; review the intended assembly order")
        }
        if (circular && normalized.first() != normalized.last()) {
            diagnostics += WorkflowDiagnostic(DiagnosticSeverity.ERROR, "Circular assembly requires matching outer overhangs")
        }
        return diagnostics
    }
}
