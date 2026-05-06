package com.datagami.rentaxis.core.email.event.payload;
import java.util.UUID;
public record StaffRoleChangedPayload(UUID tenantId, UUID userId, String userName, String oldRole, String newRole) {}
