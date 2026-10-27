package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A PROMOTER's standing in its one organisation (changeset 183): the "can create
 * businesses" switch. Which businesses and properties it may touch are
 * {@link PromoterBusiness} and {@link PromoterProperty} rows.
 */
@Entity
@Table(name = "promoter_access")
@Getter
@Setter
public class PromoterAccess extends BaseTenantEntity {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "can_create_businesses", nullable = false)
    private boolean canCreateBusinesses;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void onPreUpdate() {
        this.updatedAt = Instant.now();
    }
}
