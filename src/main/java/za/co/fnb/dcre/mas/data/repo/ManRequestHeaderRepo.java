package za.co.fnb.dcre.mas.data.repo;

import org.springframework.data.repository.CrudRepository;
import za.co.fnb.dcre.mas.data.model.ManRequestHeaderView;

import java.util.Optional;
import java.util.UUID;

/** Read side of the MRR-owned mandate_request_header; MAS never writes it. */
public interface ManRequestHeaderRepo extends CrudRepository<ManRequestHeaderView, UUID> {

    Optional<ManRequestHeaderView> findByArrivalId(UUID arrivalId);
}
