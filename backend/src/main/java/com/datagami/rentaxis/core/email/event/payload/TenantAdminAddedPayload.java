package com.datagami.rentaxis.core.email.event.payload;
import java.util.UUID;
public record TenantAdminAddedPayload(UUID tenantId, UUID newAdminUserId, String newAdminName, String addedByName) {}
