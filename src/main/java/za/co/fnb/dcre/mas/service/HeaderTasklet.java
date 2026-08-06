package za.co.fnb.dcre.mas.service;

import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Thin entry adapter (3-tier, configuration.md point 21): resolves the client
 * token that selects the R-08 per-client threshold and stashes it in the job
 * execution context for the score step.
 */
@Component
public class HeaderTasklet implements Tasklet {

    private final ManAffordabilityService service;

    public HeaderTasklet(final ManAffordabilityService service) {
        this.service = service;
    }

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        final UUID arrivalId = UUID.fromString(
                (String) chunkContext.getStepContext().getJobParameters().get("arrival.id"));
        final var context = chunkContext.getStepContext().getStepExecution()
                .getJobExecution().getExecutionContext();
        context.putString("clientToken", service.clientTokenFor(arrivalId));
        return RepeatStatus.FINISHED;
    }
}
