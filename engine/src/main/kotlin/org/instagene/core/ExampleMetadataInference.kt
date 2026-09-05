package org.instagene.core

/** Deterministic metadata enrichment for the bundled NCBI example records. */
object ExampleMetadataInference {
    fun apply(seq: Seq): Seq {
        val evidence = listOf(
            seq.name,
            seq.description,
            seq.recordMetadata.source.orEmpty(),
            seq.recordMetadata.organism.orEmpty(),
            seq.recordMetadata.taxonomy.joinToString(" "),
            seq.metadata["KEYWORDS"].orEmpty(),
        ).joinToString(" ").lowercase()

        val vector = evidence.contains("cloning vector") ||
            evidence.contains("plasmid") ||
            evidence.contains("vector")
        val transcript = seq.kind == SeqKind.RNA ||
            evidence.contains(" mrna") ||
            evidence.contains(" mrna ") ||
            evidence.contains("transcript")
        val organism = seq.recordMetadata.organism.orEmpty().lowercase()
        val sourceOrganism = when {
            organism.contains("aequorea victoria") -> "Aequorea victoria"
            organism.contains("escherichia coli") -> "Escherichia coli"
            vector -> "Escherichia coli"
            else -> seq.recordMetadata.organism
        }
        val sequenceClass = when {
            vector -> "Plasmid or vector"
            transcript -> "mRNA or transcript"
            seq.kind == SeqKind.PROTEIN -> "Protein sequence"
            else -> "Unknown or unannotated"
        }
        val origin = when {
            vector || evidence.contains("synthetic") || evidence.contains("artificial sequences") -> SequenceOrigin.SYNTHETIC
            sourceOrganism != null -> SequenceOrigin.NATURAL
            else -> SequenceOrigin.UNKNOWN
        }
        val hostType = if (vector) "Bacterial" else null
        val host = if (vector) "E. coli" else null
        val methylation = inferMethylation(seq, vector)
        val inferenceSummary = buildList {
            add("sequence class=$sequenceClass")
            add("host=${host ?: "not specified"}")
            add("origin=${origin.name.lowercase()}")
        }
        return seq.copy(
            molecule = methylation ?: seq.molecule,
            recordMetadata = seq.recordMetadata.copy(
                nucleicAcidCategory = seq.recordMetadata.nucleicAcidCategory ?: sequenceClass,
                labHostType = seq.recordMetadata.labHostType ?: hostType,
                hostStrain = seq.recordMetadata.hostStrain ?: host,
                origin = if (seq.recordMetadata.origin == SequenceOrigin.UNKNOWN) origin else seq.recordMetadata.origin,
                organism = seq.recordMetadata.organism ?: sourceOrganism,
            ),
            metadata = seq.metadata.toMutableMap().apply {
                putIfAbsent("INFERENCE_SOURCE", "InstaGene example metadata inference")
                putIfAbsent("INFERENCE_STATUS", "inferred")
                putIfAbsent("INFERENCE_SUMMARY", inferenceSummary.joinToString("; "))
            },
        )
    }

    /**
     * Vector records are commonly propagated in Dam+/Dcm+ E. coli.  This is
     * an example-specific inference, not a claim about every E. coli strain.
     */
    private fun inferMethylation(seq: Seq, vector: Boolean): MoleculeProperties? {
        if (seq.molecule.methylationSource != MethylationSource.UNKNOWN) return null
        if (!vector || seq.kind != SeqKind.DNA) return null
        return seq.molecule.withMethylation(
            dam = true,
            dcm = true,
            cpg = null,
            source = MethylationSource.INFERRED,
        )
    }
}
