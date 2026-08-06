package za.co.fnb.dcre.maf.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import za.co.fnb.dcre.maf.common.AffordabilityProvider;
import za.co.fnb.dcre.maf.config.MafThresholdProperties;
import za.co.fnb.dcre.maf.data.model.ManRequestEntryView;
import za.co.fnb.dcre.maf.data.model.ManRequestHeaderView;
import za.co.fnb.dcre.maf.data.repo.ManRequestEntryRepo;
import za.co.fnb.dcre.maf.data.repo.ManRequestHeaderRepo;
import za.co.fnb.dcre.maf.domain.IntentKeyMinter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Business tier (configuration.md point 21): the MAF affordability gate over one
 * arrival. Tier 1 ({@link #clientTokenFor}) resolves the per-client threshold
 * identity; tier 2 ({@link #score}) scans the READY CREATE rows (VALIDATED fresh
 * + SCORE_PENDING carry-overs), and for each one writes the intent AHEAD of the
 * bureau call, calls the provider with the deterministic idempotency key, then
 * settles. Runs AFTER MRV, so only VALIDATED rows are ever scored (a billed
 * enquiry never fires for an invalid row); AMEND/CANCEL are excluded by the READY
 * scan, a valid no-op left at VALIDATED for MIT.
 */
@Service
public class ManAffordabilityService {

    private static final Logger log = LoggerFactory.getLogger(ManAffordabilityService.class);

    static final String CREATE = "CREATE";
    /** READY = still to score: fresh VALIDATED rows and SCORE_PENDING carry-overs (R-12). */
    static final List<String> READY_STATES = List.of("VALIDATED", "SCORE_PENDING");

    private final ManRequestHeaderRepo headers;
    private final ManRequestEntryRepo entries;
    private final AffordabilityProvider provider;
    private final ScoreWriteService writes;
    private final MafThresholdProperties thresholds;

    public ManAffordabilityService(final ManRequestHeaderRepo headers, final ManRequestEntryRepo entries,
                                   final AffordabilityProvider provider, final ScoreWriteService writes,
                                   final MafThresholdProperties thresholds) {
        this.headers = headers;
        this.entries = entries;
        this.provider = provider;
        this.writes = writes;
        this.thresholds = thresholds;
    }

    /** Tier 1: the client token that selects the R-08 threshold. */
    public String clientTokenFor(final UUID arrivalId) {
        final ManRequestHeaderView header = headers.findByArrivalId(arrivalId).orElseThrow(
                () -> new IllegalStateException("no mandate_request_header for arrival " + arrivalId
                        + ": MRR must ingest and MRV validate before MAF scores"));
        return header.getClientToken() != null && !header.getClientToken().isBlank()
                ? header.getClientToken().strip()
                : String.valueOf(header.getDestinationId()).strip();
    }

    /** Tier 2: score every READY CREATE row of the arrival against the client threshold. */
    public void score(final UUID arrivalId, final String clientToken) {
        final int threshold = thresholds.thresholdFor(clientToken);
        final List<ManRequestEntryView> ready = entries
                .findByArrivalIdAndActionCodeAndSpineStateInOrderBySequence(arrivalId, CREATE, READY_STATES);
        for (final ManRequestEntryView row : ready) {
            scoreRow(arrivalId, row, threshold);
        }
    }

    private void scoreRow(final UUID arrivalId, final ManRequestEntryView row, final int threshold) {
        final int seq = row.getSequence();
        final String intentKey = IntentKeyMinter.mint(arrivalId, seq, row.getMandateRef());
        writes.persistIntentPending(arrivalId, seq, intentKey); // write-ahead of the billed call (R-08)
        final Optional<Integer> score = provider.enquire(intentKey, row.getDebtorAccount());
        if (score.isEmpty()) {
            // R-12: bureau unavailable is NEVER a decline; the row stays SCORE_PENDING for the next run.
            writes.hold(arrivalId, seq);
            log.warn("carry-over stage=MAF arrival={} seq={} ref={} reason=HOLD_BUREAU_UNAVAILABLE",
                    arrivalId, seq, row.getMandateRef());
            return;
        }
        final int value = score.get();
        if (value >= threshold) {
            writes.settlePassed(arrivalId, seq, value);
            return;
        }
        // R-38 exclusion visibility: WARN at decision time; man_affordability_enquiry is the durable record.
        log.warn("excluded stage=MAF arrival={} seq={} ref={} score={} threshold={} reason=FAIL_SCORE_BELOW_THRESHOLD",
                arrivalId, seq, row.getMandateRef(), value, threshold);
        writes.settleDeclined(arrivalId, seq, value);
    }
}
