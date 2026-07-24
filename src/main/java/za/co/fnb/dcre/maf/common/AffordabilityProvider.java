package za.co.fnb.dcre.maf.common;

import java.util.Optional;

/**
 * Bureau affordability-score port (R-08). Injectable strategy (common/, the
 * RandomSource pattern) so the deterministic {@link StubAffordabilityProvider}
 * (A-59 SYNTHETIC) stands in until a real bureau adapter arrives.
 *
 * <p>{@code enquire} carries a DCRE-minted, DETERMINISTIC idempotency key: a
 * resume after a crash between the write-ahead intent persist and the (billed)
 * call re-issues the IDENTICAL key for the same instruction record, so the
 * bureau bills at most once (exactly-one enquiry, R-08).
 *
 * <p>An EMPTY result means the provider is UNAVAILABLE (R-12): this is NEVER a
 * decline. The row stays SCORE_PENDING for the next MAF run to re-attempt; only
 * a returned score below the client threshold is a SCORE_DECLINED.
 */
public interface AffordabilityProvider {

    Optional<Integer> enquire(String idempotencyKey, String debtorAccount);
}
