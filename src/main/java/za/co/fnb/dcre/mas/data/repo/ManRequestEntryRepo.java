package za.co.fnb.dcre.mas.data.repo;

import org.springframework.data.repository.CrudRepository;
import za.co.fnb.dcre.mas.data.model.ManRequestEntryView;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Read side of the MRR-owned mandate_request_entry spine. MAS's READY scan is
 * action-code-scoped (CREATE only; AMEND/CANCEL are a valid no-op left at
 * VALIDATED for MIT) and picks up rows still to be scored: fresh VALIDATED rows
 * plus SCORE_PENDING carry-overs (R-12) a prior run could not settle. All
 * spine_state WRITES go through {@link ManSpineTransitionRepo} (single-column
 * single-writer, R-04).
 */
public interface ManRequestEntryRepo extends CrudRepository<ManRequestEntryView, UUID> {

    List<ManRequestEntryView> findByArrivalIdAndActionCodeAndSpineStateInOrderBySequence(
            UUID arrivalId, String actionCode, Collection<String> spineStates);

    int countByArrivalIdAndActionCodeAndSpineState(UUID arrivalId, String actionCode, String spineState);
}
