package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.GatePass;
import com.datagami.rentaxis.domain.entity.enums.GatePassStatus;
import com.datagami.rentaxis.domain.entity.enums.GatePassType;
import com.datagami.rentaxis.domain.entity.enums.GatePassOrigin;
import com.datagami.rentaxis.domain.entity.enums.GateVisitorType;
import com.datagami.rentaxis.domain.repository.GatePassRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;

/**
 * Creation, approval and cancellation of {@link GatePass}es.
 *
 * <p>Scanning / redemption at the gate lives in {@code GatePassScanService}
 * (Task 4). Enforced here: validity-window ordering, status transitions,
 * numeric-code / QR-token uniqueness, tenant isolation, and the
 * creator-only rule on {@link #cancel}. Role-based access (who may approve)
 * and the unit-belongs-to-the-renter's-lease check remain the controller's
 * job (Task 7).
 */
@Service
public class GatePassService {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Statuses that still "occupy" a numeric code — collision checks must avoid these. */
    private static final EnumSet<GatePassStatus> NON_TERMINAL =
            EnumSet.of(GatePassStatus.PENDING_APPROVAL, GatePassStatus.ACTIVE);

    private static final int MAX_CODE_ATTEMPTS = 10;

    private final GatePassRepository gatePassRepository;
    private final NotificationService notificationService;

    public GatePassService(GatePassRepository gatePassRepository, NotificationService notificationService) {
        this.gatePassRepository = gatePassRepository;
        this.notificationService = notificationService;
    }

    @Transactional
    public GatePass create(UUID tenantId, UUID createdBy, UUID propertyId, UUID unitId,
                            String guestName, String guestPhone, String purpose, String vehicleNumber,
                            GatePassType type, Instant validFrom, Instant validTo) {
        return create(tenantId, createdBy, propertyId, unitId, guestName, guestPhone,
                purpose, vehicleNumber, type, validFrom, validTo,
                type == GatePassType.RECURRING
                        ? GatePassStatus.PENDING_APPROVAL : GatePassStatus.ACTIVE,
                GatePassOrigin.RENTER, GateVisitorType.GUEST, null, null, null);
    }

    @Transactional
    public GatePass create(UUID tenantId, UUID createdBy, UUID propertyId, UUID unitId,
                           String guestName, String guestPhone, String purpose, String vehicleNumber,
                           GatePassType type, Instant validFrom, Instant validTo,
                           GatePassStatus initialStatus, GatePassOrigin origin,
                           GateVisitorType visitorType, UUID visitorProfileId,
                           String guestPhotoUrl, String guestPhotoBlobPath) {
        if (validFrom == null || validTo == null || !validTo.isAfter(validFrom)) {
            throw new IllegalArgumentException("validTo must be after validFrom");
        }

        GatePass pass = new GatePass();
        pass.setTenantId(tenantId);
        pass.setPropertyId(propertyId);
        pass.setUnitId(unitId);
        pass.setCreatedByUserId(createdBy);
        pass.setOrigin(origin);
        pass.setVisitorProfileId(visitorProfileId);
        pass.setGuestName(guestName);
        pass.setGuestPhone(guestPhone);
        pass.setPurpose(purpose);
        pass.setVehicleNumber(vehicleNumber);
        pass.setVisitorType(visitorType);
        pass.setGuestPhotoUrl(guestPhotoUrl);
        pass.setGuestPhotoBlobPath(guestPhotoBlobPath);
        pass.setPassType(type);
        pass.setValidFrom(validFrom);
        pass.setValidTo(validTo);
        pass.setStatus(initialStatus);
        // 32 hex chars of UUID + a zero-padded 16-hex-char random suffix = fixed 48 chars.
        pass.setQrToken(UUID.randomUUID().toString().replace("-", "") + String.format("%016x", RANDOM.nextLong()));
        pass.setNumericCode(uniqueNumericCode(tenantId));

        return gatePassRepository.save(pass);
    }

    @Transactional
    public GatePass approve(UUID tenantId, UUID passId, UUID approverId, boolean approved) {
        GatePass pass = findInTenant(tenantId, passId);

        if (pass.getStatus() != GatePassStatus.PENDING_APPROVAL) {
            throw new BusinessRuleViolationException("Gate pass is not pending approval");
        }

        pass.setStatus(approved ? GatePassStatus.ACTIVE : GatePassStatus.CANCELLED);
        pass.setApprovedByUserId(approverId);
        pass.setApprovedAt(Instant.now());

        // notifyInApp, not notify: NotificationService.mapLegacyType has no GATE_PASS_*
        // entry, so notify() would publish no EmailEvent anyway. Say what we mean.
        notificationService.notifyInApp(tenantId, pass.getCreatedByUserId(),
                approved ? "GATE_PASS_APPROVED" : "GATE_PASS_REJECTED",
                approved ? "Gate pass approved" : "Gate pass rejected",
                "Pass for " + pass.getGuestName(),
                "GATE_PASS", pass.getId());

        return gatePassRepository.save(pass);
    }

    @Transactional
    public GatePass cancel(UUID tenantId, UUID passId, UUID requesterId) {
        GatePass pass = findInTenant(tenantId, passId);
        // Creator-only. 404 rather than 403 so a non-creator cannot probe for pass existence.
        if (!requesterId.equals(pass.getCreatedByUserId())) {
            throw new NotFoundException("Gate pass not found");
        }

        if (pass.getStatus() == GatePassStatus.USED) {
            throw new BusinessRuleViolationException("Gate pass has already been used");
        }

        pass.setStatus(GatePassStatus.CANCELLED);
        return gatePassRepository.save(pass);
    }

    private GatePass findInTenant(UUID tenantId, UUID passId) {
        GatePass pass = gatePassRepository.findById(passId)
                .orElseThrow(() -> new NotFoundException("Gate pass not found"));
        if (!tenantId.equals(pass.getTenantId())) {
            throw new NotFoundException("Gate pass not found");
        }
        return pass;
    }

    private String uniqueNumericCode(UUID tenantId) {
        // The DB partial unique index uq_gate_pass_numeric_active (tenant_id, numeric_code)
        // WHERE status IN ('PENDING_APPROVAL','ACTIVE') is the real authority; this
        // pre-check is an optimization to avoid the common insert-conflict round trip.
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            String code = String.format("%08d", RANDOM.nextInt(100_000_000));
            if (!gatePassRepository.existsByTenantIdAndNumericCodeAndStatusIn(tenantId, code, NON_TERMINAL)) {
                return code;
            }
        }
        throw new IllegalStateException("Unable to generate a unique gate pass code");
    }
}
