package org.instagene.core

import org.instagene.core.io.SeqIO

/**
 * Test-only inputs derived from the bundled source records. These are not
 * product examples. The restriction-site adapters are explicit test inputs
 * around source-derived sequence and are never exposed through Samples.ALL.
 */
object TestSequenceFixtures {
    /** pUC19 MCS, NCBI M77789.2 coordinates 233..289, read in the opposite orientation. */
    val restrictionBackbone: Seq = SeqIO.Samples.PUC19_NCBI_REFERENCE
        .subSeq(232, 289, "test_pUC19_MCS")
        .reverseComplement("test_pUC19_MCS")

    /** pGFPuv gfpuv CDS, NCBI U62636.1 coordinates 289..1005. */
    val sourceGfpCds: Seq = SeqIO.Samples.PGFPUV_NCBI_REFERENCE
        .subSeq(288, 1005, "test_gfpuv_CDS")

    /** Source-derived CDS with explicit EcoRI/HindIII adapters for cloning tests. */
    val restrictionInsert: Seq = Seq(
        name = "test_gfpuv_insert",
        bases = "GAATTC" + sourceGfpCds.bases + "AAGCTT",
    )

    val insertTemplate: Seq = Seq(
        name = "test_gfpuv_template",
        bases = sourceGfpCds.bases,
    )
}
