package za.co.fnb.dcre.mas.common;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * A-59 SYNTHETIC affordability provider: a deterministic bureau-score stand-in
 * derived purely from the debtor account digits, active by default behind
 * {@code dcre.mas.provider=stub}. The same account always yields the same score
 * (idempotent + reproducible), so an MRV-validated CREATE row scores identically
 * on every run and a resume never drifts.
 *
 * <p>The idempotency key is unused here (a deterministic function needs no
 * dedup), but it is the port contract every real bureau adapter honours. The
 * stub is always available; the R-12 provider-unavailable carry-over path is
 * exercised by a controllable provider in the tests.
 */
@Component
@ConditionalOnProperty(name = "dcre.mas.provider", havingValue = "stub", matchIfMissing = true)
public class StubAffordabilityProvider implements AffordabilityProvider {

    /** Score band floor and span: scores land in [300, 850]. */
    static final int FLOOR = 300;
    static final int SPAN = 551;

    @Override
    public Optional<Integer> enquire(final String idempotencyKey, final String debtorAccount) {
        return Optional.of(score(debtorAccount));
    }

    /** Deterministic digit-sum score; an account with no digits scores 0 (always declines). */
    public static int score(final String debtorAccount) {
        if (debtorAccount == null) {
            return 0;
        }
        int digitSum = 0;
        for (int i = 0; i < debtorAccount.length(); i++) {
            final char c = debtorAccount.charAt(i);
            if (c >= '0' && c <= '9') {
                digitSum += c - '0';
            }
        }
        if (digitSum == 0) {
            return 0;
        }
        return FLOOR + (digitSum * 13) % SPAN;
    }
}
