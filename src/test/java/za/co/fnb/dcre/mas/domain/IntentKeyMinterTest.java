package za.co.fnb.dcre.mas.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The idempotency-key minter is the crash-safety linchpin (R-08): it MUST be
 * deterministic so a resume re-issues the IDENTICAL bureau request for the same
 * spine entry (exactly-one enquiry), and distinct per entry so two records never
 * collide on one key.
 */
class IntentKeyMinterTest {

    private static final UUID ARRIVAL = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void sameEntryMintsIdenticalKey() {
        assertEquals(IntentKeyMinter.mint(ARRIVAL, 3, "MREF-A"),
                IntentKeyMinter.mint(ARRIVAL, 3, "MREF-A"),
                "a resume must re-mint the identical key (exactly-one enquiry)");
    }

    @Test
    void differentSequenceMintsDifferentKey() {
        assertNotEquals(IntentKeyMinter.mint(ARRIVAL, 3, "MREF-A"),
                IntentKeyMinter.mint(ARRIVAL, 4, "MREF-A"));
    }

    @Test
    void differentArrivalMintsDifferentKey() {
        assertNotEquals(IntentKeyMinter.mint(ARRIVAL, 3, "MREF-A"),
                IntentKeyMinter.mint(UUID.randomUUID(), 3, "MREF-A"));
    }

    @Test
    void keyCarriesTheMafPrefixAndFitsTheColumn() {
        final String key = IntentKeyMinter.mint(ARRIVAL, 3, "MREF-A");
        assertTrue(key.startsWith("MAS-"), "key: " + key);
        assertTrue(key.length() <= 64, "key must fit VARCHAR(64), was " + key.length());
    }
}
