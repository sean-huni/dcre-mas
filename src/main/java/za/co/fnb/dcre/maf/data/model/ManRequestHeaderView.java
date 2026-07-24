package za.co.fnb.dcre.maf.data.model;

import org.springframework.data.relational.core.mapping.Table;
import za.co.fnb.dcre.platform.persistence.BaseEntity;

import java.util.UUID;

/**
 * MAF read model over the MRR-owned {@code mandate_request_header} (shared
 * dcre_man): only the client identity MAF needs for the R-08 per-client
 * threshold lookup. MAF never writes the header.
 */
@Table("mandate_request_header")
public class ManRequestHeaderView extends BaseEntity {

    private UUID arrivalId;
    private String clientToken;
    private String destinationId;

    public UUID getArrivalId() {
        return arrivalId;
    }

    public String getClientToken() {
        return clientToken;
    }

    public String getDestinationId() {
        return destinationId;
    }
}
