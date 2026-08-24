package org.instagene.core

import org.instagene.core.io.SeqIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkflowReplayTest {

    @Test
    fun replaysEveryBuiltInCloningOperationWithIdentityMatchedInputs() {
        val backbone = SeqIO.Samples.PUC19_MCS.copy(topology = Topology.CIRCULAR)
        val insert = SeqIO.Samples.GFP_CDS
        assertReplays(
            CloningWorkflows.restriction(backbone, insert, listOf(Enzymes.require("EcoRI"), Enzymes.require("HindIII")), "pGFP"),
            listOf(backbone, insert),
        )

        val overlapParts = listOf(
            Seq("part_a", "AAAAAAAAAAGGGGGGGGGG"),
            Seq("part_b", "GGGGGGGGGGCCCCCCCCCC"),
        )
        listOf(CloningMethod.GIBSON, CloningMethod.NEBUILDER_HIFI, CloningMethod.IN_FUSION).forEach { method ->
            assertReplays(
                CloningWorkflows.overlapAssembly(method, overlapParts, "${method.name.lowercase()}_product", circular = false, minOverlap = 8),
                overlapParts,
            )
        }

        val goldenParts = listOf(Seq("golden_a", "AAAA"), Seq("golden_b", "CCCC"))
        assertReplays(
            CloningWorkflows.goldenGate(goldenParts, listOf("GGAG", "AATG", "GGAG"), "golden_product"),
            goldenParts,
        )

        val leftSite = "AACCGG"
        val rightSite = "TTGGCC"
        val destination = Seq("destination", "AAAA${leftSite}CCCC${rightSite}TTTT")
        val gatewayInsert = Seq("gateway_insert", "GGGG${leftSite}ATGC${rightSite}CCCC")
        assertReplays(
            CloningWorkflows.gateway(destination, gatewayInsert, leftSite, rightSite, "gateway_product"),
            listOf(destination, gatewayInsert),
        )

        listOf(
            CloningMethod.TA to Seq("ta_insert", "ATGCA"),
            CloningMethod.GC to Seq("gc_insert", "ATGCG"),
            CloningMethod.TOPO_TA to Seq("topo_ta_insert", "ATGCA"),
            CloningMethod.TOPO_DIRECTIONAL to Seq("topo_directional_insert", "CACCATGC"),
            CloningMethod.TOPO_BLUNT to Seq("topo_blunt_insert", "ATGC"),
        ).forEach { (method, terminalInsert) ->
            val vector = Seq("${method.name.lowercase()}_vector", "GGGG")
            assertReplays(CloningWorkflows.terminalClone(method, vector, terminalInsert, "${method.name.lowercase()}_product"), listOf(vector, terminalInsert))
        }

        val target = Seq("target", "AAAACCCCGGGGTTTT")
        val donor = Seq("donor", "CCCCAAAA" + "GGGG")
        assertReplays(CloningWorkflows.homologyRecombination(target, donor, armLength = 4, name = "recombined"), listOf(target, donor))
    }

    @Test
    fun replaysPcrRestrictionCloningAndRestoresCapturedBackboneTopology() {
        val backbone = SeqIO.Samples.PUC19_MCS.copy(topology = Topology.CIRCULAR)
        val template = Seq(
            "gfp_without_cloning_sites",
            SeqIO.Samples.GFP_CDS.bases.removePrefix("GAATTC").removeSuffix("AAGCTT"),
        )
        val result = PcrCloningWorkflows.designAndClone(
            PcrCloningRequest(
                backbone,
                template,
                enzymes = listOf(Enzymes.require("EcoRI"), Enzymes.require("HindIII")),
                productName = "pGFP_replay",
            ),
        )

        val replay = WorkflowReplays.replay(
            result.recipe,
            listOf(backbone.copy(name = "renamed_backbone", topology = Topology.LINEAR), template.copy(name = "renamed_template")),
        )

        assertTrue(replay.succeeded, replay.messages.joinToString())
        assertEquals(SequenceIdentity.cdseguid(result.product), SequenceIdentity.cdseguid(replay.product!!))
    }

    @Test
    fun replayRequiresExactInputIdentityAndExplicitDependencyApproval() {
        val parts = listOf(
            Seq("part_a", "AAAAAAAAAAGGGGGGGGGG"),
            Seq("part_b", "GGGGGGGGGGCCCCCCCCCC"),
        )
        val result = CloningWorkflows.overlapAssembly(CloningMethod.GIBSON, parts, "product", circular = false, minOverlap = 8)
        val baseRecipe = Reports.workflowRecipe(result.method.name, result.product, parts, result.parameters)

        val wrongInput = WorkflowReplays.replay(baseRecipe, listOf(parts[0], Seq("changed", "AAAAAAAAAA")))
        assertEquals(WorkflowReplayStatus.INPUT_MISMATCH, wrongInput.status)

        val externalRecipe = baseRecipe.copy(externalTools = mapOf("primer3" to "2.6.1"))
        assertEquals(WorkflowReplayStatus.EXTERNAL_OPT_IN_REQUIRED, WorkflowReplays.replay(externalRecipe, parts).status)
        assertTrue(WorkflowReplays.replay(externalRecipe, parts, WorkflowReplayAuthorization(allowExternalTools = true)).succeeded)

        val onlineRecipe = baseRecipe.copy(onlineSources = mapOf("ncbi" to "NM_000000"))
        assertEquals(WorkflowReplayStatus.ONLINE_OPT_IN_REQUIRED, WorkflowReplays.replay(onlineRecipe, parts).status)
        assertTrue(WorkflowReplays.replay(onlineRecipe, parts, WorkflowReplayAuthorization(allowOnlineSources = true)).succeeded)

        val changedExpectedOutput = baseRecipe.copy(outputCdseguid = "cdseguid-not-the-product")
        assertEquals(WorkflowReplayStatus.OUTPUT_IDENTITY_MISMATCH, WorkflowReplays.replay(changedExpectedOutput, parts).status)
    }

    private fun assertReplays(result: MolecularWorkflowResult, inputs: List<Seq>) {
        val recipe = Reports.workflowRecipe(result.method.name, result.product, inputs, result.parameters)
        val replay = WorkflowReplays.replay(
            recipe,
            inputs.mapIndexed { index, input -> input.copy(name = "renamed_${index + 1}", topology = Topology.LINEAR) },
        )

        assertTrue(replay.succeeded, "${result.method}: ${replay.status}; ${replay.messages.joinToString()}")
        assertEquals(recipe.outputCdseguid, SequenceIdentity.cdseguid(replay.product!!))
    }
}
