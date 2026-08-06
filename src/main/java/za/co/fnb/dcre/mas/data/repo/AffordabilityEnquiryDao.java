package za.co.fnb.dcre.mas.data.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import za.co.fnb.dcre.mas.data.model.ManAffordabilityEnquiryEntity;

import java.util.UUID;

/**
 * Sole writer of man_affordability_enquiry (R-04, R-08): the write-ahead enquiry
 * ledger. {@link #insertIntent} is the crash-safety linchpin: it persists the
 * intent row (with the DCRE-minted idempotency key) BEFORE the bureau call, and
 * the guarded INSERT ... ON CONFLICT (arrival_id, sequence) DO NOTHING makes it
 * first-write-wins on the FULL business identity, so a resume is a zero-duplicate
 * no-op (never UPSERT on the PK; CRDB resolves UPSERT on PK only, persistence.md).
 * {@link #settle} records the decided outcome/score and stamps completed_at;
 * {@link #hold} marks the R-12 carry-over (bureau unavailable) leaving
 * completed_at NULL so the row stays open for the next run.
 */
@Component
public class AffordabilityEnquiryDao {

    private static final String INSERT_INTENT = """
            INSERT INTO man_affordability_enquiry (arrival_id, sequence, intent_key, requested_at)
            VALUES (?,?,?, now())
            ON CONFLICT (arrival_id, sequence) DO NOTHING""";

    private static final String SETTLE = """
            UPDATE man_affordability_enquiry
            SET outcome = ?, score = ?, completed_at = now()
            WHERE arrival_id = ? AND sequence = ?""";

    private static final String HOLD = """
            UPDATE man_affordability_enquiry
            SET outcome = ?, score = NULL, completed_at = NULL
            WHERE arrival_id = ? AND sequence = ?""";

    private final JdbcTemplate jdbc;

    public AffordabilityEnquiryDao(final JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Write-ahead: persist the enquiry intent before the (billed) bureau call. */
    public void insertIntent(final ManAffordabilityEnquiryEntity intent) {
        jdbc.update(INSERT_INTENT, intent.getArrivalId(), intent.getSequence(), intent.getIntentKey());
    }

    /** Settle the decided verdict (PASS / FAIL_SCORE_BELOW_THRESHOLD) with the score. */
    public void settle(final UUID arrivalId, final int sequence, final String outcome, final int score) {
        jdbc.update(SETTLE, outcome, score, arrivalId, sequence);
    }

    /** R-12 carry-over: bureau unavailable; record the hold, leave the row open (completed_at NULL). */
    public void hold(final UUID arrivalId, final int sequence, final String outcome) {
        jdbc.update(HOLD, outcome, arrivalId, sequence);
    }
}
