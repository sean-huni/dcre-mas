package za.co.fnb.dcre.mas.service;

import org.springframework.stereotype.Service;
import za.co.fnb.dcre.mas.data.repo.ManRequestEntryRepo;

import java.util.UUID;

/**
 * Business tier: the MAS seam verdict for the arrival (SCRUM-58 observability).
 * MAS never fails on carry-over, so the verdict is not a flow gate: it records
 * whether every CREATE row settled ({@code SCORE_COMPLETE}) or one or more stayed
 * SCORE_PENDING for the next run ({@code SCORE_CARRIED}, R-12). The spine holds
 * the per-row truth; this is the file-level rollup read off it.
 */
@Service
public class MasRollupService {

    public static final String SCORE_COMPLETE = "SCORE_COMPLETE";
    public static final String SCORE_CARRIED = "SCORE_CARRIED";

    /**
     * Canonical AGT seam Outcome names (per-module convention, mirrors
     * ManRollupService). The stage->AGT seam file must carry an
     * {@code za.co.fnb.dcre.agt.domain.Outcome} name; every other stage writes
     * one. The SCORE_* tokens above are MAS's DOMAIN rollup, not seam vocabulary:
     * writing them raw reads as present-but-invalid and AGT classes the stage
     * TECH_FAILED (arbiter clause, R-33) even on a clean exit-0 completion.
     */
    public static final String BUSINESS_ACCEPTED = "BUSINESS_ACCEPTED";
    public static final String BUSINESS_PARTIAL = "BUSINESS_PARTIAL";

    private final ManRequestEntryRepo entries;

    public MasRollupService(final ManRequestEntryRepo entries) {
        this.entries = entries;
    }

    public String rollup(final UUID arrivalId) {
        final int pending = entries.countByArrivalIdAndActionCodeAndSpineState(
                arrivalId, ManAffordabilityService.CREATE, "SCORE_PENDING");
        return pending > 0 ? SCORE_CARRIED : SCORE_COMPLETE;
    }

    /**
     * Translate the domain rollup verdict to the canonical seam Outcome AGT reads.
     * MAS never fails on carry-over: a full settle is BUSINESS_ACCEPTED, a
     * carry-over advances the DAG as BUSINESS_PARTIAL (the spine holds the per-row
     * SCORE_PENDING truth). Fail closed on an unmapped verdict rather than emit a
     * token AGT cannot parse.
     */
    public static String seamOutcome(final String rollupVerdict) {
        return switch (rollupVerdict) {
            case SCORE_COMPLETE -> BUSINESS_ACCEPTED;
            case SCORE_CARRIED -> BUSINESS_PARTIAL;
            default -> throw new IllegalStateException("unmapped MAS rollup verdict: " + rollupVerdict);
        };
    }
}
