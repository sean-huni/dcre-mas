package za.co.fnb.dcre.mas.service;

import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Thin entry adapter: scores every READY CREATE row of the arrival against the
 * client threshold resolved by the header step, persisting each enquiry intent
 * ahead of the bureau call.
 */
@Component
public class ScoreTasklet implements Tasklet {

    private final ManAffordabilityService service;

    public ScoreTasklet(final ManAffordabilityService service) {
        this.service = service;
    }

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        final UUID arrivalId = UUID.fromString(
                (String) chunkContext.getStepContext().getJobParameters().get("arrival.id"));
        final var context = chunkContext.getStepContext().getStepExecution()
                .getJobExecution().getExecutionContext();
        service.score(arrivalId, context.getString("clientToken", ""));
        return RepeatStatus.FINISHED;
    }
}
