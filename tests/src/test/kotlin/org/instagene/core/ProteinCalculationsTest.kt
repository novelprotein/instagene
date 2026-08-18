package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProteinCalculationsTest {

    @Test
    fun extinctionCoefficientIncreasesWithTryptophan() {
        val prot1 = Seq(name = "noTrp", bases = "ACDEF", kind = SeqKind.PROTEIN)
        val prot2 = Seq(name = "oneTrp", bases = "ACDEFW", kind = SeqKind.PROTEIN)
        val ec1 = MolecularCalculators.extinctionCoefficient(prot1)
        val ec2 = MolecularCalculators.extinctionCoefficient(prot2)
        assertTrue(ec2 > ec1, "Adding Trp should increase e280")
    }

    @Test
    fun extinctionCoefficientIncreasesWithTyrosine() {
        val prot1 = Seq(name = "noTyr", bases = "ACDEF", kind = SeqKind.PROTEIN)
        val prot2 = Seq(name = "oneTyr", bases = "ACDEFY", kind = SeqKind.PROTEIN)
        val ec1 = MolecularCalculators.extinctionCoefficient(prot1)
        val ec2 = MolecularCalculators.extinctionCoefficient(prot2)
        assertTrue(ec2 > ec1, "Adding Tyr should increase e280")
    }

    @Test
    fun extinctionCoefficientReducesWithDisulfideBonds() {
        val prot = Seq(name = "twoCys", bases = "ACDEFCC", kind = SeqKind.PROTEIN)
        val ec0 = MolecularCalculators.extinctionCoefficient(prot, disulfideBonds = 0)
        val ec1 = MolecularCalculators.extinctionCoefficient(prot, disulfideBonds = 1)
        assertTrue(ec0 > ec1, "Disulfide bonds should reduce e280")
    }

    @Test
    fun absorbanceAt1PercentIsPositive() {
        val prot = Seq(
            name = "GFP",
            bases = "MSKGEELFTGVVPILVELDGDVNGHKFSVRGEGEGDATIGKLTLKFICTTGKLPVPWPTLVTTLTYGVQCFSRYPDHMKQHDFFKSAMPEGYVQERTIFFKDDGNYKTRAEVKFEGDTLVNRIELKGIDFKEDGNILGHKLEYNYNSHNVYIMADKQKNGIKVNFKIRHNIEDGSVQLADHYQQNTPIGDGPVLLPDNHYLSTQSALSKDPNEKRDHMVLLEFVTAAGITHGMDELYK",
            kind = SeqKind.PROTEIN,
        )
        val a = MolecularCalculators.absorbanceAt1Percent(prot)
        assertTrue(a > 0.0, "A(1%, 280) should be positive")
    }

    @Test
    fun extinctionCoefficientRequiresProtein() {
        val dna = Seq(name = "dna", bases = "ATGATG", kind = SeqKind.DNA)
        assertFailsWith<IllegalArgumentException> { MolecularCalculators.extinctionCoefficient(dna) }
    }
}
