package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Global per-app, per-platform version floor for the mobile apps.
 *
 * <p>Deliberately a plain {@code @Entity} — modelled on {@link PaymentGateway},
 * NOT {@link BaseTenantEntity}. App-version policy is global infrastructure
 * config, identical for every landlord org; giving it a {@code tenant_id} would
 * subject it to {@code TenantAspect}'s Hibernate tenant filter and make the
 * public, unauthenticated version check (which has no tenant context) return
 * nothing. There is exactly one row per (app, platform), enforced by a unique
 * constraint.
 *
 * <p>Comparison is on the integer {@link #latestBuild}/{@link #minSupportedBuild}
 * (the {@code +N} in a Flutter pubspec, monotonic), never the semver name. The
 * client rule: installed build {@code <} minSupportedBuild hard-blocks;
 * minSupportedBuild {@code <=} build {@code <} latestBuild soft-nudges.
 * A seed of {@code minSupportedBuild = 0} gates nothing — the safe default that
 * keeps this feature inert until an admin raises the floor.
 */
@Entity
@Table(
        name = "app_versions",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_app_versions_app_platform",
                columnNames = {"app", "platform"}))
@Getter
@Setter
public class AppVersion {

    /** Which app the floor applies to. Persisted as the enum name (a varchar). */
    public enum App {
        RENTER, MANAGER, SECURITY
    }

    /** Store/distribution platform. Persisted as the enum name (a varchar). */
    public enum Platform {
        ANDROID, IOS
    }

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "app", nullable = false, length = 20)
    private String app;

    @Column(name = "platform", nullable = false, length = 20)
    private String platform;

    @Column(name = "min_supported_build", nullable = false)
    private int minSupportedBuild;

    @Column(name = "latest_build", nullable = false)
    private int latestBuild;

    @Column(name = "latest_version_name", length = 40)
    private String latestVersionName;

    @Column(name = "store_url", length = 512)
    private String storeUrl;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
