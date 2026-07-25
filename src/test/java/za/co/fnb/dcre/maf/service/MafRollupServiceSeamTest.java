package za.co.fnb.dcre.maf.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * T16 seam-vocabulary regression: MAF's domain rollup verdict (SCORE_*) must map
 * to a canonical AGT Outcome name before it is written to the stage->AGT seam
 * file. Writing the raw SCORE_* token reads as present-but-invalid at the seam and
 * AGT classes the stage TECH_FAILED (arbiter clause, R-33), even on a clean
 * exit-0 completion.
 */
class MafRollupServiceSeamTest {

    @Test
    void completeMapsToBusinessAccepted() {
        assertEquals(MafRollupService.BUSINESS_ACCEPTED,
                MafRollupService.seamOutcome(MafRollupService.SCORE_COMPLETE));
    }

    @Test
    void carriedMapsToBusinessPartial() {
        assertEquals(MafRollupService.BUSINESS_PARTIAL,
                MafRollupService.seamOutcome(MafRollupService.SCORE_CARRIED));
    }

    @Test
    void unmappedVerdictFailsClosed() {
        assertThrows(IllegalStateException.class, () -> MafRollupService.seamOutcome("SCORE_COMPLETE_BUT_TYPOED"));
    }
}
