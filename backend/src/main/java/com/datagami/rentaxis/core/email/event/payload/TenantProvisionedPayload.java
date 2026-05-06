package com.datagami.rentaxis.core.email.event.payload;
import java.util.UUID;
public record TenantProvisionedPayload(UUID tenantId, String tenantName, UUID adminUserId, String adminName) {}
