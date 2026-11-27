package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/** A business placed in a directory category (changeset 195). A business can be in several. */
@Entity
@Table(name = "promo_directory_category_businesses")
@Getter
@Setter
public class PromoDirectoryLink extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "category_id", nullable = false, updatable = false)
    private UUID categoryId;

    @Column(name = "business_id", nullable = false, updatable = false)
    private UUID businessId;

    public static PromoDirectoryLink of(UUID tenantId, UUID categoryId, UUID businessId) {
        PromoDirectoryLink row = new PromoDirectoryLink();
        row.setTenantId(tenantId);
        row.setCategoryId(categoryId);
        row.setBusinessId(businessId);
        return row;
    }
}
