package za.co.fnb.dcre.mas.data.model;

import org.springframework.data.relational.core.mapping.Table;
import za.co.fnb.dcre.platform.persistence.BaseEntity;

import java.util.UUID;

/**
 * MAS read model over the MRR-owned {@code mandate_request_entry} (shared
 * dcre_man): the fields the affordability gate needs. MAS writes only the
 * {@code spine_state} column it owns (ruling note 2) via the guarded transitions
 * on {@code ManSpineTransitionRepo}, never through this entity.
 */
@Table("mandate_request_entry")
public class ManRequestEntryView extends BaseEntity {

    private UUID arrivalId;
    private Integer sequence;
    private String actionCode;
    private String mandateRef;
    private String debtorAccount;
    private String spineState;

    public UUID getArrivalId() {
        return arrivalId;
    }

    public Integer getSequence() {
        return sequence;
    }

    public String getActionCode() {
        return actionCode;
    }

    public String getMandateRef() {
        return mandateRef;
    }

    public String getDebtorAccount() {
        return debtorAccount;
    }

    public String getSpineState() {
        return spineState;
    }
}
