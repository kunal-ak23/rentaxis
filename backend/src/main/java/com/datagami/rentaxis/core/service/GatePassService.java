package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.GatePass;
import com.datagami.rentaxis.domain.entity.enums.GatePassStatus;
import com.datagami.rentaxis.domain.entity.enums.GatePassType;
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
 * (Task 4). RBAC and unit-belongs-to-renter's-lease checks are the
 * controller's job (Task 7) — this service only enforces invariants that
 * hold regardless of caller: validity window ordering, status transitions,
 * and numeric-code / QR-token uniqueness.
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
        if (validFrom == null || validTo == null || !validTo.isAfter(validFrom)) {
            throw new IllegalArgumentException("validTo must be after validFrom");
        }

        GatePass pass = new GatePass();
        pass.setTenantId(tenantId);
        pass.setPropertyId(propertyId);
        pass.setUnitId(unitId);
        pass.setCreatedByUserId(createdBy);
        pass.setGuestName(guestName);
        pass.setGuestPhone(guestPhone);
        pass.setPurpose(purpose);
        pass.setVehicleNumber(vehicleNumber);
        pass.setPassType(type);
        pass.setValidFrom(validFrom);
        pass.setValidTo(validTo);
        pass.setStatus(type == GatePassType.RECURRING ? GatePassStatus.PENDING_APPROVAL : GatePassStatus.ACTIVE);
        pass.setQrToken(UUID.randomUUID().toString().replace("-", "") + Long.toHexString(RANDOM.nextLong()));
        pass.setNumericCode(uniqueNumericCode(tenantId));

        return gatePassRepository.save(pass);
    }

    @Transactional
    public GatePass approve(UUID tenantId, UUID passId, UUID approverId, boolean approved) {
        GatePass pass = findInTenant(tenantId, passId);

        if (pass.getStatus() != GatePassStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Gate pass is not pending approval");
        }

        pass.setStatus(approved ? GatePassStatus.ACTIVE : GatePassStatus.CANCELLED);
        pass.setApprovedByUserId(approverId);
        pass.setApprovedAt(Instant.now());

        notificationService.notify(tenantId, pass.getCreatedByUserId(),
                approved ? "GATE_PASS_APPROVED" : "GATE_PASS_REJECTED",
                approved ? "Gate pass approved" : "Gate pass rejected",
                "Pass for " + pass.getGuestName(),
                "GATE_PASS", pass.getId());

        return gatePassRepository.save(pass);
    }

    @Transactional
    public GatePass cancel(UUID tenantId, UUID passId, UUID requesterId) {
        GatePass pass = gatePassRepository.findById(passId)
                .orElseThrow(() -> new NotFoundException("Gate pass not found"));
        if (!tenantId.equals(pass.getTenantId()) || !requesterId.equals(pass.getCreatedByUserId())) {
            throw new NotFoundException("Gate pass not found");
        }

        if (pass.getStatus() == GatePassStatus.USED) {
            throw new IllegalStateException("Gate pass has already been used");
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
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            String code = String.format("%08d", RANDOM.nextInt(100_000_000));
            if (!gatePassRepository.existsByTenantIdAndNumericCodeAndStatusIn(tenantId, code, NON_TERMINAL)) {
                return code;
            }
        }
        throw new IllegalStateException("Unable to generate a unique gate pass code");
    }
}
