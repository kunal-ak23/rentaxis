package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the GATEPASS flag works against a real schema without a migration.
 *
 * <p>{@link TenantFeatureServiceTest} mocks the repository, so it can only show the
 * service's own logic — it would pass identically if the database rejected the new
 * value outright. That gap matters here specifically: the claim being made about
 * this change is "adding an enum constant needs no migration and no backfill", and
 * that claim is about the DDL, not about Java. {@code tenant_feature.feature} is a
 * plain {@code varchar(100)} with no CHECK and no Postgres enum type (changeset 38),
 * and {@code upsert} is a native INSERT that validates nothing — so a new constant
 * simply stores. This test is what makes that checkable rather than asserted, and
 * what fails if someone later adds a constraint that quietly breaks the next flag.
 */
@SpringBootTest
@Testcontainers
class TenantFeatureGatePassIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired TenantFeatureService service;
    @Autowired LandlordOrgRepository orgRepo;

    private UUID makeTenant() {
        LandlordOrg org = new LandlordOrg();
        org.setName("Feature-" + UUID.randomUUID());
        return orgRepo.save(org).getId();
    }

    /**
     * <b>No backfill needed.</b> A tenant that has never had a {@code tenant_feature}
     * row reads the enum's own default — GATEPASS is {@code false}, so every existing
     * tenant is off the moment this constant exists, with nothing written for them.
     */
    @Test
    void gatePassIsOffForATenantWithNoFeatureRow() {
        assertThat(service.isEnabled(makeTenant(), TenantFeature.GATEPASS)).isFalse();
    }

    /**
     * <b>No migration needed.</b> The round trip through the real column and the real
     * native upsert: the string 'GATEPASS' stores, reads back, and maps to the enum.
     */
    @Test
    void gatePassCanBeFlippedOnAndBackOff() {
        UUID tenantId = makeTenant();

        service.setEnabled(tenantId, TenantFeature.GATEPASS, true);
        assertThat(service.isEnabled(tenantId, TenantFeature.GATEPASS)).isTrue();

        // ON CONFLICT DO UPDATE, not a second row — and the cache invalidates.
        service.setEnabled(tenantId, TenantFeature.GATEPASS, false);
        assertThat(service.isEnabled(tenantId, TenantFeature.GATEPASS)).isFalse();
    }

    /** Flags are independent: turning GATEPASS on must not disturb its neighbours. */
    @Test
    void flippingGatePassLeavesOtherFlagsAtTheirDefaults() {
        UUID tenantId = makeTenant();
        service.setEnabled(tenantId, TenantFeature.GATEPASS, true);

        assertThat(service.isEnabled(tenantId, TenantFeature.LISTINGS)).isFalse();
        assertThat(service.isEnabled(tenantId, TenantFeature.LEASE_RENEWALS)).isFalse();
        // getAll must describe the new flag too — it is what an admin UI would render.
        assertThat(service.getAll(tenantId))
                .filteredOn(f -> f.feature() == TenantFeature.GATEPASS)
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.enabled()).isTrue();
                    assertThat(f.label()).isNotBlank();
                });
    }
}
