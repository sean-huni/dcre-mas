package za.co.fnb.dcre.maf.data.repo;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import za.co.fnb.dcre.maf.data.model.ManRequestEntryView;

import java.util.UUID;

/**
 * Single writer of the {@code spine_state} column MAF owns (ruling note 2): the
 * per-row VALIDATED -> SCORE_PENDING -> SCORE_PASSED|SCORE_DECLINED transitions.
 * Every mutation is a GUARDED atomic UPDATE on the FULL entry identity
 * ({@code arrival_id, sequence}) AND the expected prior state (persistence.md),
 * so it is:
 * <ul>
 *   <li>idempotent + resumable: a re-run touches zero rows already advanced,</li>
 *   <li>non-clobbering: SCORE_PENDING -> terminal only fires from SCORE_PENDING,
 *       and VALIDATED -> SCORE_PENDING is CREATE-scoped, so an AMEND/CANCEL row
 *       (a valid no-op) and a downstream MIT INITIALIZED are never touched.</li>
 * </ul>
 * Native @Query per the guarded-mutation canon (QueryDSL cannot express these).
 */
public interface ManSpineTransitionRepo extends Repository<ManRequestEntryView, UUID> {

    /** Write-ahead of the bureau call: a CREATE row entering scoring. */
    @Modifying
    @Query("""
            UPDATE mandate_request_entry SET spine_state = 'SCORE_PENDING', updated_at = now()
            WHERE arrival_id = :arrivalId AND sequence = :sequence
              AND action_code = 'CREATE' AND spine_state = 'VALIDATED'""")
    int markPending(@Param("arrivalId") UUID arrivalId, @Param("sequence") int sequence);

    /** Settle: score at/above the client threshold. */
    @Modifying
    @Query("""
            UPDATE mandate_request_entry SET spine_state = 'SCORE_PASSED', updated_at = now()
            WHERE arrival_id = :arrivalId AND sequence = :sequence AND spine_state = 'SCORE_PENDING'""")
    int markPassed(@Param("arrivalId") UUID arrivalId, @Param("sequence") int sequence);

    /** Settle: score below the client threshold (reportable via MIR). */
    @Modifying
    @Query("""
            UPDATE mandate_request_entry SET spine_state = 'SCORE_DECLINED', updated_at = now()
            WHERE arrival_id = :arrivalId AND sequence = :sequence AND spine_state = 'SCORE_PENDING'""")
    int markDeclined(@Param("arrivalId") UUID arrivalId, @Param("sequence") int sequence);
}
