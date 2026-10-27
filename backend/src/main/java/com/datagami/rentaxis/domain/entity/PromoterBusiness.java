package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A promo business granted to a PROMOTER (changeset 183). */
@Entity
@Table(name = "promoter_business")
@IdClass(PromoterBusiness.Key.class)
@Getter
@Setter
public class PromoterBusiness extends BaseTenantEntity {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Id
    @Column(name = "business_id", nullable = false, updatable = false)
    private UUID businessId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public static PromoterBusiness of(UUID tenantId, UUID userId, UUID businessId) {
        PromoterBusiness row = new PromoterBusiness();
        row.setTenantId(tenantId);
        row.setUserId(userId);
        row.setBusinessId(businessId);
        return row;
    }

    public static class Key implements Serializable {
        private UUID userId;
        private UUID businessId;

        public Key() {
        }

        public Key(UUID userId, UUID businessId) {
            this.userId = userId;
            this.businessId = businessId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(userId, k.userId) && Objects.equals(businessId, k.businessId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, businessId);
        }
    }
}
