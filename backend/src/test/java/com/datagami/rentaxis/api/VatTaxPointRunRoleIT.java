package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.CrossTenantHttp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #92: "Run tax points to date" refused a SUPER_ADMIN acting in an organisation
 * (403), though every sibling finance endpoint — journals, vouchers, fiscal
 * settings, recognition — admits one. Found live on production.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VatTaxPointRunRoleIT extends AbstractPostgresIT {

    private static final String RUN = "/api/v1/finance/vat/tax-points/run?dryRun=true";

    @LocalServerPort int port;

    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired PropertyService propertyService;

    private CrossTenantHttp http;
    private UUID tenant;

    @BeforeEach
    void setUp() {
        http = new CrossTenantHttp(port, orgRepo, userRepo, accountService, propertyAccountService, propertyService);
        tenant = http.tenant("VatRun-");
        TenantContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void aSuperAdminActingInAnOrganisationCanRunTaxPoints() {
        assertThat(http.status(http.user(tenant, UserRole.SUPER_ADMIN), HttpMethod.POST, RUN)).isEqualTo(200);
    }

    @Test
    void tenantAdminAndAccountantStillCan() {
        assertThat(http.status(http.user(tenant, UserRole.TENANT_ADMIN), HttpMethod.POST, RUN)).isEqualTo(200);
        assertThat(http.status(http.user(tenant, UserRole.ACCOUNTANT), HttpMethod.POST, RUN)).isEqualTo(200);
    }

    @Test
    void managersAndRentersStillCannot() {
        assertThat(http.status(http.user(tenant, UserRole.PROPERTY_MANAGER), HttpMethod.POST, RUN)).isEqualTo(403);
        assertThat(http.status(http.user(tenant, UserRole.RENTER), HttpMethod.POST, RUN)).isEqualTo(403);
    }
}
