package za.co.fnb.dcre.maf.config;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import za.co.fnb.dcre.maf.service.HeaderTasklet;
import za.co.fnb.dcre.maf.service.MafRollupService;
import za.co.fnb.dcre.maf.service.RollupTasklet;
import za.co.fnb.dcre.maf.service.ScoreTasklet;
import za.co.fnb.dcre.platform.batch.CrdbRetryExceptionHandler;
import za.co.fnb.dcre.platform.batch.HeartbeatWriter;
import za.co.fnb.dcre.platform.batch.OutcomeSeamListener;

/**
 * MAF job shape (MRV skeleton clone, un-partitioned: instruction books are low
 * volume, so the whole arrival is one scoring pass, KISS): header (resolve the
 * R-08 client threshold token) -> score (READY-scan the VALIDATED + SCORE_PENDING
 * CREATE rows, write each enquiry intent AHEAD of the bureau call, settle) ->
 * rollup (seam verdict SCORE_COMPLETE|SCORE_CARRIED). Identifying JobParameter:
 * arrival.id (R-16). Runs on the default SERIALIZABLE isolation (only PRG carries
 * READ COMMITTED, SCRUM-90).
 */
@Configuration
@EnableConfigurationProperties(MafThresholdProperties.class)
public class MafJobConfig {

    /**
     * CRDB 40001 retry for the score step, which WRITES the enquiry ledger + the
     * guarded spine transitions: the aborts hit the chunk-commit boundary, which
     * only a stepOperations-level handler sees. The read-only header + rollup
     * steps stay without it.
     */
    private final CrdbRetryExceptionHandler crdbRetry = new CrdbRetryExceptionHandler("MAF");

    @Bean
    public Step headerStep(final JobRepository repo, final PlatformTransactionManager tx,
                           final HeaderTasklet tasklet) {
        return new StepBuilder("headerStep", repo).tasklet(tasklet, tx).build();
    }

    @Bean
    public Step scoreStep(final JobRepository repo, final PlatformTransactionManager tx,
                          final ScoreTasklet tasklet) {
        return new StepBuilder("scoreStep", repo).tasklet(tasklet, tx).exceptionHandler(crdbRetry).build();
    }

    @Bean
    public Step rollupStep(final JobRepository repo, final PlatformTransactionManager tx,
                           final RollupTasklet tasklet) {
        return new StepBuilder("rollupStep", repo).tasklet(tasklet, tx).build();
    }

    @Bean
    public Job mafJob(final JobRepository repo, final Step headerStep, final Step scoreStep,
                      final Step rollupStep, final HeartbeatWriter heartbeatWriter,
                      @Value("${dcre.exchange-root}") final String exchangeRoot) {
        return new JobBuilder("mafJob", repo)
                .listener(new OutcomeSeamListener("maf", exchangeRoot, MafJobConfig::seamVerdict))
                .listener(heartbeatWriter)
                .start(headerStep)
                    .on("FAILED").fail()
                .from(headerStep).on("*").to(scoreStep)
                .from(scoreStep).on("FAILED").fail()
                .from(scoreStep).on("*").to(rollupStep)
                .from(rollupStep).on(MafRollupService.SCORE_COMPLETE).end(MafRollupService.SCORE_COMPLETE)
                .from(rollupStep).on(MafRollupService.SCORE_CARRIED).end(MafRollupService.SCORE_CARRIED)
                .from(rollupStep).on("*").fail()
                .end()
                .build();
    }

    /**
     * Seam verdict (supplied to the shared OutcomeSeamListener, SCRUM-58): the
     * rollup's carry-over status. MAF has no whole-file FATAL of its own; an
     * unavailable-bureau carry-over is a normal SCORE_CARRIED, not a failure.
     */
    private static String seamVerdict(final JobExecution execution) {
        return execution.getExecutionContext().getString("seamVerdict", MafRollupService.SCORE_COMPLETE);
    }
}
