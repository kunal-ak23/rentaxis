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

/**
 * A property a PROMOTER may see (by name) and target (changeset 183). Its own
 * table on purpose: {@code user_property_assignments} drives PROPERTY_MANAGER
 * scoping and email recipient resolution, and must never hold a promoter.
 */
@Entity
@Table(name = "promoter_property")
@IdClass(PromoterProperty.Key.class)
@Getter
@Setter
public class PromoterProperty extends BaseTenantEntity {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Id
    @Column(name = "property_id", nullable = false, updatable = false)
    private UUID propertyId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public static PromoterProperty of(UUID tenantId, UUID userId, UUID propertyId) {
        PromoterProperty row = new PromoterProperty();
        row.setTenantId(tenantId);
        row.setUserId(userId);
        row.setPropertyId(propertyId);
        return row;
    }

    public static class Key implements Serializable {
        private UUID userId;
        private UUID propertyId;

        public Key() {
        }

        public Key(UUID userId, UUID propertyId) {
            this.userId = userId;
            this.propertyId = propertyId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(userId, k.userId) && Objects.equals(propertyId, k.propertyId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, propertyId);
        }
    }
}
