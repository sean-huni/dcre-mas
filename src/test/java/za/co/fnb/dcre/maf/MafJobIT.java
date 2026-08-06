package za.co.fnb.dcre.maf;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;
import za.co.fnb.dcre.maf.common.AffordabilityProvider;
import za.co.fnb.dcre.maf.common.StubAffordabilityProvider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MAF job over the real gate + CockroachDB (Testcontainers, fleet pattern). Every
 * R-08 path is exercised end to end through the job: the deterministic-stub PASS
 * and DECLINE threshold verdicts (real {@link StubAffordabilityProvider} scoring,
 * reached via the recording provider's delegate), the per-client threshold, the
 * action-code scoping (AMEND/CANCEL a valid no-op, invalid rows never billed), the
 * R-12 provider-unavailable carry-over, and the R-08 crash-safety
 * (write-ahead-persisted intent + resume with no double enquiry). FNBCC01 uses the
 * default 600 threshold; FNBRF01 is mapped to 700.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false",
        "dcre.maf.provider=test",
        "dcre.maf.threshold.[FNBRF01]=700"})
@Import(MafJobIT.ProviderConfig.class)
class MafJobIT {

    /** score(6200000099) = 638 (passes 600, declines 700); score(6200000021) = 443 (declines 600). */
    private static final String ACCT_PASS = "6200000099";
    private static final String ACCT_DECLINE = "6200000021";

    static final CockroachContainer CRDB =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));

    static final Path EXCHANGE = freshExchangeRoot();

    static {
        CRDB.start();
    }

    static Path freshExchangeRoot() {
        try {
            return Files.createTempDirectory("maf-seam-it");
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void props(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", CRDB::getUsername);
        registry.add("spring.datasource.password", CRDB::getPassword);
        registry.add("dcre.exchange-root", EXCHANGE::toString);
    }

    @Autowired
    Job mafJob;
    @Autowired
    JobOperator jobOperator;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    RecordingAffordabilityProvider provider;

    @BeforeEach
    void seedSpine() {
        ManTestTables.createSpine(jdbc);
        provider.reset();
    }

    private JobExecution run(final UUID arrival, final String attempt) throws Exception {
        final JobParametersBuilder b = new JobParametersBuilder()
                .addString("arrival.id", arrival.toString(), true);
        if (attempt != null) {
            b.addString("attempt", attempt, true);
        }
        final JobParameters params = b.toJobParameters();
        return jobOperator.start(mafJob, params);
    }

    private String spineState(final UUID arrival, final int seq) {
        return jdbc.queryForObject(
                "SELECT spine_state FROM mandate_request_entry WHERE arrival_id=? AND sequence=?",
                String.class, arrival, seq);
    }

    private int enquiryCount(final UUID arrival) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM man_affordability_enquiry WHERE arrival_id=?", Integer.class, arrival);
    }

    private int distinctEnquiryCount(final UUID arrival) {
        return jdbc.queryForObject(
                "SELECT count(DISTINCT (arrival_id, sequence)) FROM man_affordability_enquiry WHERE arrival_id=?",
                Integer.class, arrival);
    }

    private String enquiryOutcome(final UUID arrival, final int seq) {
        return jdbc.queryForObject(
                "SELECT outcome FROM man_affordability_enquiry WHERE arrival_id=? AND sequence=?",
                String.class, arrival, seq);
    }

    private Integer enquiryScore(final UUID arrival, final int seq) {
        return jdbc.queryForObject(
                "SELECT score FROM man_affordability_enquiry WHERE arrival_id=? AND sequence=?",
                Integer.class, arrival, seq);
    }

    private String intentKey(final UUID arrival, final int seq) {
        return jdbc.queryForObject(
                "SELECT intent_key FROM man_affordability_enquiry WHERE arrival_id=? AND sequence=?",
                String.class, arrival, seq);
    }

    private boolean completed(final UUID arrival, final int seq) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT completed_at IS NOT NULL FROM man_affordability_enquiry WHERE arrival_id=? AND sequence=?",
                Boolean.class, arrival, seq));
    }

    @Test
    void cleanCreateAboveThresholdIsScorePassed() throws Exception {
        final UUID arrival = UUID.randomUUID();
        ManTestTables.insertHeader(jdbc, arrival, "FNBCC01", 1);
        ManTestTables.insertEntry(jdbc, arrival, 1, "CREATE", "MREF-A", ACCT_PASS, "VALIDATED");

        final JobExecution run = run(arrival, null);
        assertEquals(BatchStatus.COMPLETED, run.getStatus());
        assertEquals("SCORE_COMPLETE", run.getExitStatus().getExitCode());
        assertEquals("SCORE_PASSED", spineState(arrival, 1));
        assertEquals("PASS", enquiryOutcome(arrival, 1));
        assertEquals(638, enquiryScore(arrival, 1));
        assertTrue(completed(arrival, 1), "a settled enquiry stamps completed_at");
        // T16 seam regression: the outcome FILE must carry a canonical AGT Outcome
        // name (BUSINESS_ACCEPTED), not the raw SCORE_COMPLETE domain token, or AGT
        // reads it as present-but-invalid and classes the stage TECH_FAILED.
        final Path seam = EXCHANGE.resolve("outcomes").resolve("local-maf-" + run.getId());
        assertEquals("BUSINESS_ACCEPTED", Files.readString(seam).strip(),
                "seam file speaks canonical Outcome vocabulary, not SCORE_COMPLETE");
    }

    @Test
    void cleanCreateBelowThresholdIsScoreDeclined() throws Exception {
        final UUID arrival = UUID.randomUUID();
        ManTestTables.insertHeader(jdbc, arrival, "FNBCC01", 1);
        ManTestTables.insertEntry(jdbc, arrival, 1, "CREATE", "MREF-B", ACCT_DECLINE, "VALIDATED");

        final JobExecution run = run(arrival, null);
        assertEquals("SCORE_COMPLETE", run.getExitStatus().getExitCode());
        assertEquals("SCORE_DECLINED", spineState(arrival, 1));
        assertEquals("FAIL_SCORE_BELOW_THRESHOLD", enquiryOutcome(arrival, 1));
        assertEquals(443, enquiryScore(arrival, 1));
        assertTrue(completed(arrival, 1));
    }

    @Test
    void perClientHigherThresholdDeclinesAMidScore() throws Exception {
        // Same 638 account that PASSES under FNBCC01's default 600 DECLINES under FNBRF01's 700.
        final UUID arrival = UUID.randomUUID();
        ManTestTables.insertHeader(jdbc, arrival, "FNBRF01", 1);
        ManTestTables.insertEntry(jdbc, arrival, 1, "CREATE", "MREF-C", ACCT_PASS, "VALIDATED");

        run(arrival, null);
        assertEquals("SCORE_DECLINED", spineState(arrival, 1), "638 < per-client 700");
        assertEquals("FAIL_SCORE_BELOW_THRESHOLD", enquiryOutcome(arrival, 1));
    }

    @Test
    void amendAndCancelAreValidNoOpLeftValidated() throws Exception {
        final UUID arrival = UUID.randomUUID();
        ManTestTables.insertHeader(jdbc, arrival, "FNBCC01", 2);
        ManTestTables.insertEntry(jdbc, arrival, 1, "AMEND", "MREF-D", ACCT_PASS, "VALIDATED");
        ManTestTables.insertEntry(jdbc, arrival, 2, "CANCEL", "MREF-E", ACCT_DECLINE, "VALIDATED");

        final JobExecution run = run(arrival, null);
        assertEquals("SCORE_COMPLETE", run.getExitStatus().getExitCode());
        assertEquals(List.of("VALIDATED", "VALIDATED"),
                List.of(spineState(arrival, 1), spineState(arrival, 2)),
                "AMEND/CANCEL are a valid no-op left at VALIDATED for MIT");
        assertEquals(0, enquiryCount(arrival), "no billed enquiry for a non-CREATE action");
        assertEquals(0, provider.countFor(ACCT_PASS) + provider.countFor(ACCT_DECLINE),
                "the bureau is never asked for AMEND/CANCEL rows");
    }

    @Test
    void invalidRejectedRowsAreNeverEnquired() throws Exception {
        // MAF runs AFTER MRV: a REJECTED row must never trigger a billed enquiry.
        final UUID arrival = UUID.randomUUID();
        ManTestTables.insertHeader(jdbc, arrival, "FNBCC01", 1);
        ManTestTables.insertEntry(jdbc, arrival, 1, "CREATE", "MREF-F", ACCT_PASS, "REJECTED");

        final JobExecution run = run(arrival, null);
        assertEquals("SCORE_COMPLETE", run.getExitStatus().getExitCode());
        assertEquals("REJECTED", spineState(arrival, 1), "an invalid row is untouched");
        assertEquals(0, enquiryCount(arrival));
        assertEquals(0, provider.countFor(ACCT_PASS), "billed enquiries never fire for invalid rows");
    }

    @Test
    void providerUnavailableLeavesScorePendingThenNextRunScores() throws Exception {
        // R-12 carry-over: an unavailable bureau is NEVER a decline; the row waits for the next run.
        final UUID arrival = UUID.randomUUID();
        ManTestTables.insertHeader(jdbc, arrival, "FNBCC01", 1);
        ManTestTables.insertEntry(jdbc, arrival, 1, "CREATE", "MREF-G", ACCT_PASS, "VALIDATED");
        provider.markUnavailable(ACCT_PASS);

        final JobExecution first = run(arrival, "1");
        assertEquals(BatchStatus.COMPLETED, first.getStatus());
        assertEquals("SCORE_CARRIED", first.getExitStatus().getExitCode());
        assertEquals("SCORE_PENDING", spineState(arrival, 1), "unavailable -> stays pending, never declined");
        assertEquals("HOLD_BUREAU_UNAVAILABLE", enquiryOutcome(arrival, 1));
        assertNull(enquiryScore(arrival, 1), "no score while carried");
        assertFalse(completed(arrival, 1), "a carried enquiry is not completed");

        provider.clearUnavailable();
        final JobExecution second = run(arrival, "2");
        assertEquals("SCORE_COMPLETE", second.getExitStatus().getExitCode());
        assertEquals("SCORE_PASSED", spineState(arrival, 1), "the next run's READY scan picks up the pending row");
        assertEquals(1, enquiryCount(arrival), "still exactly one enquiry row (no duplicate on carry-over)");
        assertEquals("PASS", enquiryOutcome(arrival, 1));
        assertEquals(638, enquiryScore(arrival, 1));
        assertTrue(completed(arrival, 1));
    }

    @Test
    void crashBetweenIntentPersistAndProviderCallDoesNotDoubleEnquireOnResume() throws Exception {
        // R-08 crash-safety: the intent is committed WRITE-AHEAD of the bureau call, so a crash
        // between persist and call leaves a durable SCORE_PENDING marker, and the resume re-issues
        // the IDENTICAL deterministic key -> exactly-one enquiry.
        final UUID arrival = UUID.randomUUID();
        ManTestTables.insertHeader(jdbc, arrival, "FNBCC01", 1);
        ManTestTables.insertEntry(jdbc, arrival, 1, "CREATE", "MREF-H", ACCT_PASS, "VALIDATED");
        provider.throwOnce(ACCT_PASS); // simulate a pod kill AFTER the intent commit, before the call settles

        final JobExecution crashed = run(arrival, "1");
        assertEquals(BatchStatus.FAILED, crashed.getStatus(), "the crashing run fails the job");
        // Write-ahead durability: the intent survived the crash even though the step did not settle.
        assertEquals(1, enquiryCount(arrival), "the enquiry intent is durable across the crash (write-ahead)");
        assertEquals("SCORE_PENDING", spineState(arrival, 1), "the spine marker is durable across the crash");
        assertFalse(completed(arrival, 1), "the crashed enquiry is unsettled");
        final String keyAfterCrash = intentKey(arrival, 1);

        final JobExecution resumed = run(arrival, "2");
        assertEquals("SCORE_COMPLETE", resumed.getExitStatus().getExitCode());
        assertEquals("SCORE_PASSED", spineState(arrival, 1));
        assertTrue(completed(arrival, 1));
        assertEquals(638, enquiryScore(arrival, 1));
        // Zero-duplicate audit: exactly one enquiry row on the full business identity.
        assertEquals(1, enquiryCount(arrival));
        assertEquals(enquiryCount(arrival), distinctEnquiryCount(arrival), "count == distinct (arrival_id, sequence)");
        // The resume re-mints the IDENTICAL idempotency key, so the bureau would bill at most once.
        assertEquals(keyAfterCrash, intentKey(arrival, 1), "intent_key is stable across the resume");
        final Set<String> keysForAccount = Set.copyOf(provider.keysFor(ACCT_PASS));
        assertEquals(1, keysForAccount.size(),
                "every bureau call for the account carried the same idempotency key (exactly-one enquiry)");
    }

    @Test
    void reRunIsIdempotentZeroDuplicateAndSpineStable() throws Exception {
        final UUID arrival = UUID.randomUUID();
        ManTestTables.insertHeader(jdbc, arrival, "FNBCC01", 2);
        ManTestTables.insertEntry(jdbc, arrival, 1, "CREATE", "MREF-I", ACCT_PASS, "VALIDATED");
        ManTestTables.insertEntry(jdbc, arrival, 2, "CREATE", "MREF-J", ACCT_DECLINE, "VALIDATED");

        assertEquals(BatchStatus.COMPLETED, run(arrival, "1").getStatus());
        assertEquals(List.of("SCORE_PASSED", "SCORE_DECLINED"),
                List.of(spineState(arrival, 1), spineState(arrival, 2)));
        final int billedFirstRun = provider.total();

        // Fresh JobInstance re-processing the same arrival: settled rows are excluded from the READY scan.
        assertEquals(BatchStatus.COMPLETED, run(arrival, "2").getStatus());

        assertEquals(2, enquiryCount(arrival));
        assertEquals(enquiryCount(arrival), distinctEnquiryCount(arrival),
                "zero-duplicate audit: count == distinct business identity");
        assertEquals(List.of("SCORE_PASSED", "SCORE_DECLINED"),
                List.of(spineState(arrival, 1), spineState(arrival, 2)),
                "re-run does not re-transition (guarded spine states)");
        assertEquals(billedFirstRun, provider.total(),
                "settled rows are never re-billed on a resume");
    }

    /** The test AffordabilityProvider: delegates to the real stub scoring, with per-account controls. */
    static final class RecordingAffordabilityProvider implements AffordabilityProvider {

        private final Set<String> unavailable = ConcurrentHashMap.newKeySet();
        private final Set<String> throwOnce = ConcurrentHashMap.newKeySet();
        private final List<String[]> invocations = new CopyOnWriteArrayList<>(); // {key, account}

        @Override
        public Optional<Integer> enquire(final String idempotencyKey, final String debtorAccount) {
            invocations.add(new String[]{idempotencyKey, debtorAccount});
            if (throwOnce.remove(debtorAccount)) {
                throw new IllegalStateException("simulated pod crash mid-enquiry: " + debtorAccount);
            }
            if (unavailable.contains(debtorAccount)) {
                return Optional.empty();
            }
            return Optional.of(StubAffordabilityProvider.score(debtorAccount));
        }

        void markUnavailable(final String account) {
            unavailable.add(account);
        }

        void clearUnavailable() {
            unavailable.clear();
        }

        void throwOnce(final String account) {
            throwOnce.add(account);
        }

        long countFor(final String account) {
            return invocations.stream().filter(i -> account.equals(i[1])).count();
        }

        List<String> keysFor(final String account) {
            return invocations.stream().filter(i -> account.equals(i[1])).map(i -> i[0]).toList();
        }

        int total() {
            return invocations.size();
        }

        void reset() {
            unavailable.clear();
            throwOnce.clear();
            invocations.clear();
        }
    }

    @TestConfiguration
    static class ProviderConfig {
        @Bean
        RecordingAffordabilityProvider recordingProvider() {
            return new RecordingAffordabilityProvider();
        }
    }
}
