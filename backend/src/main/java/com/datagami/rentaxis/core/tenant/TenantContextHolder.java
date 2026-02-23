package com.datagami.rentaxis.core.tenant;

import java.util.UUID;

public class TenantContextHolder {

    private static final ThreadLocal<UUID> TENANT_CONTEXT = new ThreadLocal<>();

    public static void setTenantId(UUID tenantId) {
        TENANT_CONTEXT.set(tenantId);
    }

    public static UUID getTenantId() {
        return TENANT_CONTEXT.get();
    }

    public static void clear() {
        TENANT_CONTEXT.remove();
    }
}
