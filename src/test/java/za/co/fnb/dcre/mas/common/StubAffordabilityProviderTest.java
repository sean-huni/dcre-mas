package za.co.fnb.dcre.mas.common;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A-59 SYNTHETIC stub: the score is a pure, deterministic function of the debtor
 * account digits, so a CREATE row scores identically on every run (a resume never
 * drifts). The two pinned accounts are the fixtures the job IT reuses to land
 * either side of the default 600 threshold.
 */
class StubAffordabilityProviderTest {

    private final StubAffordabilityProvider stub = new StubAffordabilityProvider();

    @Test
    void scoreIsDeterministicPerAccount() {
        assertEquals(StubAffordabilityProvider.score("6200000099"),
                StubAffordabilityProvider.score("6200000099"));
    }

    @Test
    void pinnedAccountsStraddleTheDefaultThreshold() {
        assertEquals(638, StubAffordabilityProvider.score("6200000099"), "digitSum 26 -> passes 600");
        assertEquals(443, StubAffordabilityProvider.score("6200000021"), "digitSum 11 -> declines 600");
    }

    @Test
    void scoreStaysInBand() {
        final int score = StubAffordabilityProvider.score("6200000099");
        assertTrue(score >= StubAffordabilityProvider.FLOOR
                && score < StubAffordabilityProvider.FLOOR + StubAffordabilityProvider.SPAN, "band: " + score);
    }

    @Test
    void differentAccountsCanScoreDifferently() {
        assertNotEquals(StubAffordabilityProvider.score("6200000099"),
                StubAffordabilityProvider.score("6200000021"));
    }

    @Test
    void accountWithNoDigitsScoresZero() {
        assertEquals(0, StubAffordabilityProvider.score("N/A"));
        assertEquals(0, StubAffordabilityProvider.score(null));
    }

    @Test
    void enquireIsAlwaysAvailableAndReturnsTheDigitScore() {
        final Optional<Integer> result = stub.enquire("MAS-key", "6200000099");
        assertEquals(Optional.of(638), result, "the stub never signals unavailable");
    }
}
