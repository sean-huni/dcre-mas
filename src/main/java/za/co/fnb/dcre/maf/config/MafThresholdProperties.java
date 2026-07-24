package za.co.fnb.dcre.maf.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * R-08 per-client affordability threshold. {@code dcre.maf.threshold.<client>}
 * overrides {@code dcre.maf.threshold.default} (600 code fallback for the
 * clean-clone). A CREATE row scoring below its client threshold is
 * SCORE_DECLINED; at/above it is SCORE_PASSED. Unmapped or blank clients fall to
 * the default (same resolution shape as MRV's AcceptanceModeProperties).
 */
@ConfigurationProperties(prefix = "dcre.maf")
public class MafThresholdProperties {

    static final String DEFAULT_KEY = "default";
    static final int FALLBACK = 600;

    /** client token -> threshold, plus the special {@code default} key. */
    private Map<String, Integer> threshold = new HashMap<>();

    public Map<String, Integer> getThreshold() {
        return threshold;
    }

    public void setThreshold(final Map<String, Integer> threshold) {
        this.threshold = threshold;
    }

    public int thresholdFor(final String clientToken) {
        final int def = threshold.getOrDefault(DEFAULT_KEY, FALLBACK);
        if (clientToken == null || clientToken.isBlank()) {
            return def;
        }
        return threshold.getOrDefault(clientToken.strip(), def);
    }
}
