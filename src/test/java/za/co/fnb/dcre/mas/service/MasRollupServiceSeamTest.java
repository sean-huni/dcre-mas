package za.co.fnb.dcre.mas.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * T16 seam-vocabulary regression: MAS's domain rollup verdict (SCORE_*) must map
 * to a canonical AGT Outcome name before it is written to the stage->AGT seam
 * file. Writing the raw SCORE_* token reads as present-but-invalid at the seam and
 * AGT classes the stage TECH_FAILED (arbiter clause, R-33), even on a clean
 * exit-0 completion.
 */
class MasRollupServiceSeamTest {

    @Test
    void completeMapsToBusinessAccepted() {
        assertEquals(MasRollupService.BUSINESS_ACCEPTED,
                MasRollupService.seamOutcome(MasRollupService.SCORE_COMPLETE));
    }

    @Test
    void carriedMapsToBusinessPartial() {
        assertEquals(MasRollupService.BUSINESS_PARTIAL,
                MasRollupService.seamOutcome(MasRollupService.SCORE_CARRIED));
    }

    @Test
    void unmappedVerdictFailsClosed() {
        assertThrows(IllegalStateException.class, () -> MasRollupService.seamOutcome("SCORE_COMPLETE_BUT_TYPOED"));
    }
}
