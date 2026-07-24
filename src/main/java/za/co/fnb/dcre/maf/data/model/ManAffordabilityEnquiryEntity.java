package za.co.fnb.dcre.maf.data.model;

import org.springframework.data.relational.core.mapping.Table;
import za.co.fnb.dcre.platform.persistence.BaseEntity;

import java.util.UUID;

/**
 * The write-ahead intent DTO for {@code man_affordability_enquiry} (R-08).
 * Written only through {@link za.co.fnb.dcre.maf.data.repo.AffordabilityEnquiryDao}
 * (guarded INSERT ... ON CONFLICT DO NOTHING for the intent, then a guarded
 * settle UPDATE), never via a Spring Data repository, so it carries only the
 * three write-ahead fields the intent insert needs (MRV's man_validation_log DTO
 * pattern). {@code outcome}/{@code score}/{@code completed_at} are set later by
 * the settle, not on this DTO.
 */
@Table("man_affordability_enquiry")
public class ManAffordabilityEnquiryEntity extends BaseEntity {

    private UUID arrivalId;
    private Integer sequence;
    private String intentKey;

    public static ManAffordabilityEnquiryEntity intent(final UUID arrivalId, final int sequence,
                                                       final String intentKey) {
        final ManAffordabilityEnquiryEntity e = new ManAffordabilityEnquiryEntity();
        e.arrivalId = arrivalId;
        e.sequence = sequence;
        e.intentKey = intentKey;
        return e;
    }

    public UUID getArrivalId() {
        return arrivalId;
    }

    public Integer getSequence() {
        return sequence;
    }

    public String getIntentKey() {
        return intentKey;
    }
}
