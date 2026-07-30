package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.core.service.GateWalkInService.EffectivePolicy;
import com.datagami.rentaxis.core.service.GateWalkInService.VisitorLookup;
import com.datagami.rentaxis.domain.entity.GateAccessPolicy;
import com.datagami.rentaxis.domain.entity.GatePass;
import com.datagami.rentaxis.domain.entity.enums.GatePassStatus;
import com.datagami.rentaxis.domain.entity.enums.GateVisitorType;

import java.time.Instant;
import java.util.UUID;

public final class GateWalkInDtos {
    private GateWalkInDtos() {}

    public record Destination(UUID unitId, String unitNumber, UUID propertyId,
                              UUID buildingId, String buildingName) {}

    public record WalkInPass(UUID id, UUID propertyId, UUID unitId, String unitNumber,
                             String guestName, String guestPhone, GateVisitorType visitorType,
                             String purpose, String vehicleNumber, String guestPhotoUrl,
                             GatePassStatus status, Instant validTo, Instant createdAt) {
        public static WalkInPass of(GatePass pass, String unitNumber) {
            return new WalkInPass(pass.getId(), pass.getPropertyId(), pass.getUnitId(), unitNumber,
                    pass.getGuestName(), pass.getGuestPhone(), pass.getVisitorType(), pass.getPurpose(),
                    pass.getVehicleNumber(), pass.getGuestPhotoUrl(), pass.getStatus(),
                    pass.getValidTo(), pass.getCreatedAt());
        }
    }

    public record VisitorLookupResponse(UUID id, String name, String phone,
                                        GateVisitorType visitorType, String photoUrl,
                                        String vehicleNumber, UUID lastUnitId,
                                        Instant lastVisitedAt, boolean registeredForSelectedUnit) {
        public static VisitorLookupResponse of(VisitorLookup lookup) {
            return new VisitorLookupResponse(lookup.id(), lookup.name(), lookup.phone(),
                    lookup.visitorType(), lookup.photoUrl(), lookup.vehicleNumber(),
                    lookup.lastUnitId(), lookup.lastVisitedAt(), lookup.registeredForSelectedUnit());
        }
    }

    public record PolicyRequest(boolean requireUnregisteredApproval,
                                boolean requireRegisteredApproval,
                                boolean notifyRegisteredEntry,
                                boolean requireFreshPhoto,
                                int approvalTimeoutMinutes) {}

    public record PolicyResponse(UUID id, UUID propertyId, UUID buildingId,
                                 boolean inherited, boolean requireUnregisteredApproval,
                                 boolean requireRegisteredApproval, boolean notifyRegisteredEntry,
                                 boolean requireFreshPhoto, int approvalTimeoutMinutes) {
        public static PolicyResponse effective(UUID propertyId, UUID buildingId, EffectivePolicy policy) {
            return new PolicyResponse(null, propertyId, buildingId, true,
                    policy.requireUnregisteredApproval(), policy.requireRegisteredApproval(),
                    policy.notifyRegisteredEntry(), policy.requireFreshPhoto(),
                    policy.approvalTimeoutMinutes());
        }

        public static PolicyResponse explicit(GateAccessPolicy policy) {
            return new PolicyResponse(policy.getId(), policy.getPropertyId(), policy.getBuildingId(), false,
                    policy.isRequireUnregisteredApproval(), policy.isRequireRegisteredApproval(),
                    policy.isNotifyRegisteredEntry(), policy.isRequireFreshPhoto(),
                    policy.getApprovalTimeoutMinutes());
        }
    }

    public record RegistrationRequest(UUID unitId, Instant validFrom, Instant validTo, boolean active) {}

    public record ManagedRegistrationRequest(UUID propertyId, UUID unitId, String name,
                                             String phone, GateVisitorType visitorType,
                                             Instant validFrom, Instant validTo, boolean active) {}
}
