package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.util.UUID;

@MappedSuperclass
@FilterDef(name = "tenantFilter", parameters = { @ParamDef(name = "tenantId", type = java.util.UUID.class) })
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public abstract class BaseTenantEntity {

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    @PrePersist
    public void onPrePersist() {
        if (this.tenantId == null) {
            this.tenantId = TenantContextHolder.getTenantId();
        }
    }
}
