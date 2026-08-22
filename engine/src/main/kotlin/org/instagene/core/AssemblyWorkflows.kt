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
        val fragments = parts.mapIndexed { index, part ->
            Fragment(part.bases, StickyEnd(EndType.FIVE_PRIME_OVERHANG, overhangs[index].uppercase(), "Type IIS"), StickyEnd(EndType.FIVE_PRIME_OVERHANG, overhangs[index + 1].uppercase(), "Type IIS"), part.name, 0, part.features)
        }
        return restrictionLigation(fragments.map { TreatedFragment(it) }, name, circular)
    }
}
