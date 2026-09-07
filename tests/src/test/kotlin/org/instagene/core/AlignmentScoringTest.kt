package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertEquals

class AlignmentScoringTest {
    @Test
    fun pam250MatchesReferenceSentinels() {
        assertEquals(1.0, AlignmentScores.score(SeqKind.PROTEIN, 'A', 'T', AlignmentScoring.PAM250))
        assertEquals(2.0, AlignmentScores.score(SeqKind.PROTEIN, 'N', 'D', AlignmentScoring.PAM250))
        assertEquals(-6.0, AlignmentScores.score(SeqKind.PROTEIN, 'W', 'A', AlignmentScoring.PAM250))
        assertEquals(17.0, AlignmentScores.score(SeqKind.PROTEIN, 'W', 'W', AlignmentScoring.PAM250))
    }
}
