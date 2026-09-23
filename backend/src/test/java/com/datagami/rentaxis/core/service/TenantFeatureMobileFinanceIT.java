package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves MOBILE_FINANCE works against a real schema without a migration.
 *
 * <p>Accounting v2 is web-only (spec D7). The Flutter apps still call the v1 finance
 * and payments endpoints, which plans 1 and 2 delete, so their finance, lease and
 * cheque screens are hidden until plan 6 rewrites them. The switch has to be a
 * per-tenant flag rather than a build constant, because the demo tenant and the App
 * Store reviewer's tenant are the same tenant and it will be flipped back on first.
 *
 * <p>The claim under test is the same one {@code TenantFeatureGatePassIT} makes for
 * GATEPASS: adding an enum constant needs no changeset and no backfill.
 * {@code tenant_feature.feature} is a plain {@code varchar(100)} with no CHECK and no
 * Postgres enum type (changeset 38), and {@code upsert} is a native INSERT that
 * validates nothing. This test is what fails if someone later adds a constraint.
 */
@SpringBootTest
class TenantFeatureMobileFinanceIT extends AbstractPostgresIT {

    @Autowired TenantFeatureService service;
    @Autowired LandlordOrgRepository orgRepo;

    private UUID makeTenant() {
        LandlordOrg org = new LandlordOrg();
        org.setName("MobileFinance-" + UUID.randomUUID());
        return orgRepo.save(org).getId();
    }

    /**
     * <b>No backfill needed.</b> Every existing tenant is off the moment the constant
     * exists, with nothing written for them — which is the safe default, because an
     * app pointed at a v2 backend must not show a screen that cannot load.
     */
    @Test
    void mobileFinanceIsOffForATenantWithNoFeatureRow() {
        assertThat(service.isEnabled(makeTenant(), TenantFeature.MOBILE_FINANCE)).isFalse();
    }

    /** <b>No migration needed.</b> The round trip through the real column and upsert. */
    @Test
    void mobileFinanceCanBeFlippedOnAndBackOff() {
        UUID tenant = makeTenant();
        service.setEnabled(tenant, TenantFeature.MOBILE_FINANCE, true);
        assertThat(service.isEnabled(tenant, TenantFeature.MOBILE_FINANCE)).isTrue();
        service.setEnabled(tenant, TenantFeature.MOBILE_FINANCE, false);
        assertThat(service.isEnabled(tenant, TenantFeature.MOBILE_FINANCE)).isFalse();
    }

    /** The toggle list the superadmin screen renders must carry a readable label. */
    @Test
    void mobileFinanceAppearsInTheToggleListWithALabel() {
        UUID tenant = makeTenant();
        service.setEnabled(tenant, TenantFeature.MOBILE_FINANCE, true);

        assertThat(service.getAll(tenant))
                .filteredOn(f -> f.feature() == TenantFeature.MOBILE_FINANCE)
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.enabled()).isTrue();
                    assertThat(f.defaultEnabled()).isFalse();
                    assertThat(f.label()).isNotBlank();
                });
        assertThat(service.isEnabled(tenant, TenantFeature.GATEPASS)).isFalse();
    }
}
