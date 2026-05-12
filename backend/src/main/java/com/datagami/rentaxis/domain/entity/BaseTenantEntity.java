package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.util.UUID;

@MappedSuperclass
// applyToLoadByKey = true makes the Hibernate filter apply to find() /
// findById() / getReference() too — by default in Hibernate 7, @Filter only
// applies to JPQL/Criteria queries and is bypassed by direct primary-key
// loads, leaving every Spring Data findById call as a cross-tenant data
// leak when a caller controls the id. With this flag, any code path that
// fetches a tenant-scoped entity by id is gated by the currently-enabled
// tenant filter (set by TenantAspect from TenantContextHolder).
@FilterDef(
        name = "tenantFilter",
        parameters = { @ParamDef(name = "tenantId", type = java.util.UUID.class) },
        applyToLoadByKey = true
)
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public abstract class BaseTenantEntity {

    @JsonIgnore
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
