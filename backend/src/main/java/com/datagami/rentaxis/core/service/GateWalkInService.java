package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.notification.NotificationMessage;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GateWalkInService {

    private final GateVisitorProfileRepository profileRepository;
    private final GateVisitorUnitRegistrationRepository registrationRepository;
    private final GateAccessPolicyRepository policyRepository;
    private final GatePassService gatePassService;
    private final LeaseRepository leaseRepository;
    private final UnitRepository unitRepository;
    private final NotificationService notificationService;
    private final BlobStorageService blobStorageService;

    public record VisitorLookup(UUID id, String name, String phone, GateVisitorType visitorType,
                                String photoUrl, String vehicleNumber, UUID lastUnitId,
                                Instant lastVisitedAt, boolean registeredForSelectedUnit) {}

    public record EffectivePolicy(boolean requireUnregisteredApproval,
                                  boolean requireRegisteredApproval,
                                  boolean notifyRegisteredEntry,
                                  boolean requireFreshPhoto,
                                  int approvalTimeoutMinutes) {}

    @Transactional(readOnly = true)
    public Optional<VisitorLookup> lookup(UUID tenantId, UUID propertyId, String phone, UUID unitId) {
        String normalized = normalizePhone(phone);
        return profileRepository.findByTenantIdAndPropertyIdAndPhoneNormalized(
                tenantId, propertyId, normalized).map(profile -> new VisitorLookup(
                        profile.getId(), profile.getDisplayName(), profile.getPhoneNormalized(),
                        profile.getVisitorType(), profile.getPhotoUrl(), profile.getLastVehicleNumber(),
                        profile.getLastUnitId(), profile.getLastVisitedAt(),
                        unitId != null && isRegistered(tenantId, profile.getId(), unitId, Instant.now())));
    }

    @Transactional(readOnly = true)
    public EffectivePolicy effectivePolicy(UUID tenantId, UUID propertyId, UUID buildingId) {
        GateAccessPolicy policy = buildingId == null ? null
                : policyRepository.findByTenantIdAndPropertyIdAndBuildingId(
                        tenantId, propertyId, buildingId).orElse(null);
        if (policy == null) {
            policy = policyRepository.findByTenantIdAndPropertyIdAndBuildingIdIsNull(
                    tenantId, propertyId).orElse(null);
        }
        return policy == null
                ? new EffectivePolicy(true, false, true, true, 15)
                : new EffectivePolicy(policy.isRequireUnregisteredApproval(),
                        policy.isRequireRegisteredApproval(), policy.isNotifyRegisteredEntry(),
                        policy.isRequireFreshPhoto(), policy.getApprovalTimeoutMinutes());
    }

    @Transactional
    public GatePass create(UUID tenantId, UUID guardId, Unit unit, String name, String phone,
                           GateVisitorType visitorType, String purpose, String vehicleNumber,
                           MultipartFile freshPhoto) {
        String normalized = normalizePhone(phone);
        GateVisitorProfile profile = profileRepository
                .findByTenantIdAndPropertyIdAndPhoneNormalized(
                        tenantId, unit.getProperty().getId(), normalized)
                .orElseGet(() -> {
                    GateVisitorProfile created = new GateVisitorProfile();
                    created.setTenantId(tenantId);
                    created.setPropertyId(unit.getProperty().getId());
                    created.setPhoneNormalized(normalized);
                    return created;
                });
        profile.setDisplayName(requireMax(name, "Visitor name", 160));
        profile.setVisitorType(visitorType == null ? GateVisitorType.OTHER : visitorType);
        String cleanVehicle = optionalMax(vehicleNumber, "Vehicle number", 32);
        String cleanPurpose = optionalMax(purpose, "Purpose", 240);
        profile.setLastVehicleNumber(cleanVehicle);
        profile.setLastUnitId(unit.getId());
        profile.setLastVisitedAt(Instant.now());
        profile = profileRepository.saveAndFlush(profile);

        EffectivePolicy policy = effectivePolicy(tenantId, unit.getProperty().getId(),
                unit.getBuilding() == null ? null : unit.getBuilding().getId());
        if (policy.requireFreshPhoto() && (freshPhoto == null || freshPhoto.isEmpty())) {
            throw new BusinessRuleViolationException("A fresh visitor photo is required at this gate");
        }
        if (freshPhoto != null && !freshPhoto.isEmpty()) {
            BlobStorageService.UploadResult uploaded =
                    blobStorageService.uploadGateVisitor(tenantId, profile.getId(), freshPhoto);
            profile.setPhotoUrl(uploaded.url());
            profile.setPhotoBlobPath(uploaded.blobPath());
            profileRepository.save(profile);
        }

        boolean registered = isRegistered(tenantId, profile.getId(), unit.getId(), Instant.now());
        boolean approvalRequired = registered
                ? policy.requireRegisteredApproval() : policy.requireUnregisteredApproval();
        Instant now = Instant.now();
        GatePass pass = gatePassService.create(tenantId, guardId, unit.getProperty().getId(), unit.getId(),
                profile.getDisplayName(), normalized, cleanPurpose, cleanVehicle,
                GatePassType.SINGLE_USE, now, now.plus(policy.approvalTimeoutMinutes(), ChronoUnit.MINUTES),
                approvalRequired ? GatePassStatus.PENDING_APPROVAL : GatePassStatus.ACTIVE,
                GatePassOrigin.GUARD_WALK_IN, profile.getVisitorType(), profile.getId(),
                profile.getPhotoUrl(), profile.getPhotoBlobPath());

        if (approvalRequired) {
            notifyResidents(tenantId, unit, pass, approvalRequired);
        }
        return pass;
    }

    @Transactional
    public void notifyRegisteredAdmission(UUID tenantId, GatePass pass) {
        if (pass.getVisitorProfileId() == null) return;
        Unit unit = unitRepository.findById(pass.getUnitId())
                .filter(row -> tenantId.equals(row.getTenantId()))
                .orElse(null);
        if (unit == null) return;
        EffectivePolicy policy = effectivePolicy(tenantId, pass.getPropertyId(),
                unit.getBuilding() == null ? null : unit.getBuilding().getId());
        if (policy.notifyRegisteredEntry()
                && isRegistered(tenantId, pass.getVisitorProfileId(), unit.getId(), Instant.now())) {
            notifyResidents(tenantId, unit, pass, false);
        }
    }

    @Transactional
    public void registerForUnit(UUID tenantId, UUID profileId, UUID unitId,
                                Instant validFrom, Instant validTo, boolean active) {
        GateVisitorProfile profile = profileRepository.findById(profileId)
                .filter(p -> tenantId.equals(p.getTenantId()))
                .orElseThrow(() -> new BusinessRuleViolationException("Visitor profile not found"));
        Unit unit = unitRepository.findById(unitId)
                .filter(u -> tenantId.equals(u.getTenantId()))
                .orElseThrow(() -> new BusinessRuleViolationException("Unit not found"));
        if (!profile.getPropertyId().equals(unit.getProperty().getId())) {
            throw new BusinessRuleViolationException(
                    "Registered visitor and unit must belong to the same property");
        }
        if (validFrom != null && validTo != null && !validTo.isAfter(validFrom)) {
            throw new BusinessRuleViolationException("validTo must be after validFrom");
        }
        GateVisitorUnitRegistration registration = registrationRepository
                .findByTenantIdAndVisitorProfileIdAndUnitId(tenantId, profileId, unitId)
                .orElseGet(GateVisitorUnitRegistration::new);
        registration.setTenantId(tenantId);
        registration.setVisitorProfileId(profileId);
        registration.setUnitId(unitId);
        registration.setValidFrom(validFrom);
        registration.setValidTo(validTo);
        registration.setActive(active);
        registrationRepository.save(registration);
    }

    @Transactional
    public GateVisitorProfile upsertRegisteredVisitor(UUID tenantId, UUID propertyId, UUID unitId,
                                                      String name, String phone,
                                                      GateVisitorType visitorType,
                                                      Instant validFrom, Instant validTo,
                                                      boolean active) {
        Unit unit = unitRepository.findById(unitId)
                .filter(u -> tenantId.equals(u.getTenantId())
                        && propertyId.equals(u.getProperty().getId()))
                .orElseThrow(() -> new BusinessRuleViolationException("Unit not found at property"));
        String normalized = normalizePhone(phone);
        GateVisitorProfile profile = profileRepository
                .findByTenantIdAndPropertyIdAndPhoneNormalized(tenantId, propertyId, normalized)
                .orElseGet(() -> {
                    GateVisitorProfile created = new GateVisitorProfile();
                    created.setTenantId(tenantId);
                    created.setPropertyId(propertyId);
                    created.setPhoneNormalized(normalized);
                    return created;
                });
        profile.setDisplayName(requireMax(name, "Visitor name", 160));
        profile.setVisitorType(visitorType == null ? GateVisitorType.OTHER : visitorType);
        profile = profileRepository.saveAndFlush(profile);
        registerForUnit(tenantId, profile.getId(), unit.getId(), validFrom, validTo, active);
        return profile;
    }

    private boolean isRegistered(UUID tenantId, UUID profileId, UUID unitId, Instant now) {
        return registrationRepository.findActiveRegistration(
                tenantId, profileId, unitId, now).isPresent();
    }

    private void notifyResidents(UUID tenantId, Unit unit, GatePass pass, boolean approvalRequired) {
        List<Lease> leases = leaseRepository.findByUnitIdAndStatus(unit.getId(), LeaseStatus.ACTIVE);
        for (Lease lease : leases) {
            UUID userId = lease.getRenter() == null ? null : lease.getRenter().getUserId();
            if (userId == null) continue;
            String unitNumber = unit.getUnitNumber();
            notificationService.notifyInApp(tenantId, userId,
                    approvalRequired ? "GATE_VISITOR_APPROVAL_REQUIRED" : "GATE_REGISTERED_VENDOR_ARRIVED",
                    approvalRequired ? "Visitor waiting at the gate" : "Registered vendor checked in",
                    pass.getGuestName() + " is visiting unit " + unitNumber
                            + (approvalRequired ? ". Approve or reject the entry request." : "."),
                    "GATE_PASS", pass.getId(),
                    NotificationMessage.of(
                            approvalRequired ? "GATE_VISITOR_APPROVAL_REQUIRED" : "GATE_REGISTERED_VENDOR_ARRIVED",
                            "guestName", pass.getGuestName(), "unit", unitNumber));
        }
    }

    public static String normalizePhone(String phone) {
        String value = requireText(phone, "Mobile number").replaceAll("[^0-9+]", "");
        if (value.startsWith("00")) value = "+" + value.substring(2);
        if (!value.startsWith("+") || value.length() < 8 || value.length() > 16
                || !value.substring(1).matches("\\d+")) {
            throw new BusinessRuleViolationException(
                    "Mobile number must include a valid country code, for example +971501234567");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        String trimmed = trimToNull(value);
        if (trimmed == null) throw new BusinessRuleViolationException(field + " is required");
        return trimmed;
    }

    private static String requireMax(String value, String field, int max) {
        String trimmed = requireText(value, field);
        if (trimmed.length() > max) {
            throw new BusinessRuleViolationException(field + " must be " + max + " characters or fewer");
        }
        return trimmed;
    }

    private static String optionalMax(String value, String field, int max) {
        String trimmed = trimToNull(value);
        if (trimmed != null && trimmed.length() > max) {
            throw new BusinessRuleViolationException(field + " must be " + max + " characters or fewer");
        }
        return trimmed;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
