package za.co.fnb.dcre.maf.service;

import org.springframework.stereotype.Service;
import za.co.fnb.dcre.maf.data.repo.ManRequestEntryRepo;

import java.util.UUID;

/**
 * Business tier: the MAF seam verdict for the arrival (SCRUM-58 observability).
 * MAF never fails on carry-over, so the verdict is not a flow gate: it records
 * whether every CREATE row settled ({@code SCORE_COMPLETE}) or one or more stayed
 * SCORE_PENDING for the next run ({@code SCORE_CARRIED}, R-12). The spine holds
 * the per-row truth; this is the file-level rollup read off it.
 */
@Service
public class MafRollupService {

    public static final String SCORE_COMPLETE = "SCORE_COMPLETE";
    public static final String SCORE_CARRIED = "SCORE_CARRIED";

    private final ManRequestEntryRepo entries;

    public MafRollupService(final ManRequestEntryRepo entries) {
        this.entries = entries;
    }

    public String rollup(final UUID arrivalId) {
        final int pending = entries.countByArrivalIdAndActionCodeAndSpineState(
                arrivalId, ManAffordabilityService.CREATE, "SCORE_PENDING");
        return pending > 0 ? SCORE_CARRIED : SCORE_COMPLETE;
    }
}
