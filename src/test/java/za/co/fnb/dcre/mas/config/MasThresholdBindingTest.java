package za.co.fnb.dcre.mas.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SCRUM-107: the {@code dcre.mas} prefix and the matching {@code application.yml}
 * key are load-bearing configuration, and nothing exercised them. Renaming MAF to
 * MAS moved the prefix; with the yml key left behind, binding silently produces an
 * EMPTY threshold map and every client falls to the {@code FALLBACK} constant, with
 * no exception, no log line and a green suite. Verified: reverting the yml key to
 * {@code maf:} against the renamed prefix left the whole build at exit 0.
 *
 * <p>Note the trap these assertions are shaped around: the committed default is
 * 600 and {@code FALLBACK} is also 600, so asserting that
 * {@code thresholdFor("anything") == 600} passes whether the map bound or not. The
 * discriminator is whether the MAP ITSELF is populated, so that is what is asserted.
 */
class MasThresholdBindingTest {

    @Configuration
    @EnableConfigurationProperties(MasThresholdProperties.class)
    static class Cfg {
    }

    /** The prefix binds; both values differ from FALLBACK so a wrong prefix cannot pass. */
    @Test
    void thePrefixBindsPerClientOverrides() {
        new ApplicationContextRunner()
                .withUserConfiguration(Cfg.class)
                .withPropertyValues("dcre.mas.threshold.default=655",
                        "dcre.mas.threshold.FNBRF01=742")
                .run(ctx -> {
                    final MasThresholdProperties props = ctx.getBean(MasThresholdProperties.class);
                    assertEquals(742, props.thresholdFor("FNBRF01"),
                            "per-client override must arrive through the dcre.mas prefix");
                    assertEquals(655, props.thresholdFor("FNBCC01"),
                            "configured default must arrive through the dcre.mas prefix");
                });
    }

    /** The committed application.yml key matches the prefix. */
    @Test
    void theCommittedYamlPopulatesTheThresholdMap() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(Cfg.class)
                .run(ctx -> {
                    final MasThresholdProperties props = ctx.getBean(MasThresholdProperties.class);
                    assertFalse(props.getThreshold().isEmpty(),
                            "application.yml must nest the threshold under the dcre.mas prefix;"
                                    + " an empty map means the key and the prefix have drifted apart");
                    assertTrue(props.getThreshold().containsKey("default"),
                            "the committed clean-clone default must bind");
                    assertEquals(600, props.getThreshold().get("default"),
                            "committed default");
                });
    }
}
