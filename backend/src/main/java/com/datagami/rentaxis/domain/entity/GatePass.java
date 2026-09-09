package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.GatePassStatus;
import com.datagami.rentaxis.domain.entity.enums.GatePassType;
import com.datagami.rentaxis.domain.entity.enums.GatePassOrigin;
import com.datagami.rentaxis.domain.entity.enums.GateVisitorType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "gate_passes")
@Getter
@Setter
public class GatePass extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "property_id", nullable = false)
    private UUID propertyId;

    @Column(name = "unit_id", nullable = false)
    private UUID unitId;

    // Nullable since 79-account-deletion-detach: the pass outlives the account
    // that created it, with the personal link severed rather than the record lost.
    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private GatePassOrigin origin = GatePassOrigin.RENTER;

    @Column(name = "visitor_profile_id")
    private UUID visitorProfileId;

    @Column(name = "guest_name", nullable = false, length = 160)
    private String guestName;

    @Column(name = "guest_phone", nullable = false, length = 32)
    private String guestPhone;

    @Column(length = 240)
    private String purpose;

    @Column(name = "vehicle_number", length = 32)
    private String vehicleNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "visitor_type", nullable = false, length = 32)
    private GateVisitorType visitorType = GateVisitorType.GUEST;

    @Column(name = "guest_photo_url", length = 1024)
    private String guestPhotoUrl;

    /** Immutable snapshot path for this visit; profiles may receive newer photos. */
    @Column(name = "guest_photo_blob_path", length = 512)
    private String guestPhotoBlobPath;

    @Enumerated(EnumType.STRING)
    @Column(name = "pass_type", nullable = false, length = 20)
    private GatePassType passType;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_to", nullable = false)
    private Instant validTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private GatePassStatus status;

    @Column(name = "qr_token", nullable = false, unique = true, length = 64)
    private String qrToken;

    @Column(name = "numeric_code", nullable = false, length = 8)
    private String numericCode;

    @Column(name = "approved_by_user_id")
    private UUID approvedByUserId;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void onPreUpdate() {
        this.updatedAt = Instant.now();
    }
}
