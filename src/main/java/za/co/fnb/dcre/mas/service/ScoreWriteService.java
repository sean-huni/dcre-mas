package za.co.fnb.dcre.mas.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import za.co.fnb.dcre.mas.data.model.ManAffordabilityEnquiryEntity;
import za.co.fnb.dcre.mas.data.repo.AffordabilityEnquiryDao;
import za.co.fnb.dcre.mas.data.repo.ManSpineTransitionRepo;
import za.co.fnb.dcre.platform.model.MandateOutcome;

import java.util.UUID;

/**
 * Business tier: the durable commit boundaries of the affordability gate. Each
 * method is a single atomic unit so the enquiry ledger and the spine advance
 * together per row.
 *
 * <p>Each method runs in its OWN {@code REQUIRES_NEW} transaction, so it commits
 * independently of the Batch step transaction: this is what makes
 * {@link #persistIntentPending} a true WRITE-AHEAD step (R-08). It commits the
 * enquiry intent + the SCORE_PENDING spine mark BEFORE the (billed) bureau call,
 * so a pod kill between persist and call leaves a durable marker the next run
 * resumes from (a same-transaction write would roll back with the failed step and
 * be lost). {@link #settlePassed}/{@link #settleDeclined} commit the decided
 * verdict; {@link #hold} records the R-12 carry-over (bureau unavailable), which
 * leaves the row SCORE_PENDING for the next run. Every spine mutation is guarded
 * on the prior state, so a resume is idempotent and non-clobbering.
 */
@Service
public class ScoreWriteService {

    private final AffordabilityEnquiryDao enquiries;
    private final ManSpineTransitionRepo spine;

    public ScoreWriteService(final AffordabilityEnquiryDao enquiries, final ManSpineTransitionRepo spine) {
        this.enquiries = enquiries;
        this.spine = spine;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistIntentPending(final UUID arrivalId, final int sequence, final String intentKey) {
        enquiries.insertIntent(ManAffordabilityEnquiryEntity.intent(arrivalId, sequence, intentKey));
        spine.markPending(arrivalId, sequence);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settlePassed(final UUID arrivalId, final int sequence, final int score) {
        enquiries.settle(arrivalId, sequence, MandateOutcome.PASS.name(), score);
        spine.markPassed(arrivalId, sequence);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settleDeclined(final UUID arrivalId, final int sequence, final int score) {
        enquiries.settle(arrivalId, sequence, MandateOutcome.FAIL_SCORE_BELOW_THRESHOLD.name(), score);
        spine.markDeclined(arrivalId, sequence);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void hold(final UUID arrivalId, final int sequence) {
        enquiries.hold(arrivalId, sequence, MandateOutcome.HOLD_BUREAU_UNAVAILABLE.name());
    }
}
