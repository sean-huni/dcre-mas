package za.co.fnb.dcre.mas.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * R-08 per-client threshold resolution. thresholdFor falls to the configured
 * default (or the 600 code fallback) for an unmapped or blank client, strips the
 * token, and honours a per-client override.
 */
class MasThresholdPropertiesTest {

    @Test
    void resolvesDefaultAndPerClientOverride() {
        final MasThresholdProperties props = new MasThresholdProperties();
        props.getThreshold().put("default", 600);
        props.getThreshold().put("FNBRF01", 700);

        assertEquals(600, props.thresholdFor("FNBCC01"), "unmapped -> default");
        assertEquals(600, props.thresholdFor(""), "blank -> default");
        assertEquals(600, props.thresholdFor(null), "null -> default");
        assertEquals(700, props.thresholdFor("FNBRF01"), "per-client override");
        assertEquals(700, props.thresholdFor(" FNBRF01 "), "stripped");
    }

    @Test
    void fallsBackToSixHundredWhenNoDefaultConfigured() {
        final MasThresholdProperties props = new MasThresholdProperties();
        assertEquals(600, props.thresholdFor("FNBCC01"), "code fallback for the clean-clone");
    }
}
