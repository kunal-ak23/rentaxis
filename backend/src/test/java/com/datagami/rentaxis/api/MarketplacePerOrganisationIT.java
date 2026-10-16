package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.CrossTenantHttp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S18-01 ruling: the marketplace is per landlord (per organisation). A signed-in renter
 * browsing another organisation's slug gets a 404 (like the detail and interest
 * endpoints), not an empty 200 list; their own organisation's marketplace and wishlist
 * work. Public pages are separate and stay open.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MarketplacePerOrganisationIT extends AbstractPostgresIT {

    @LocalServerPort int port;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired PropertyService propertyService;
    @Autowired TenantFeatureService features;

    @AfterEach
    void clear() { TenantContextHolder.clear(); }

    private String slugOf(UUID tenantId) {
        String slug = "mp-" + UUID.randomUUID().toString().substring(0, 8);
        LandlordOrg org = orgRepo.findById(tenantId).orElseThrow();
        org.setSlug(slug);
        orgRepo.save(org);
        features.setEnabled(tenantId, TenantFeature.LISTINGS, true);
        return slug;
    }

    @Test
    void anotherOrganisationsMarketplaceIs404AndYourOwnWorks() {
        CrossTenantHttp http = new CrossTenantHttp(port, orgRepo, userRepo, accountService,
                propertyAccountService, propertyService);
        UUID a = http.tenant("MpA-");
        String slugA = slugOf(a);
        User renterA = http.user(a, UserRole.RENTER);
        UUID b = http.tenant("MpB-");
        String slugB = slugOf(b);
        User renterB = http.user(b, UserRole.RENTER);
        TenantContextHolder.clear();

        assertThat(http.status(renterA, HttpMethod.GET, "/api/marketplace/" + slugA + "/listings")).isEqualTo(200);
        assertThat(http.status(renterB, HttpMethod.GET, "/api/marketplace/" + slugB + "/listings")).isEqualTo(200);

        assertThat(http.status(renterA, HttpMethod.GET, "/api/marketplace/" + slugB + "/listings")).isEqualTo(404);
        assertThat(http.status(renterB, HttpMethod.GET, "/api/marketplace/" + slugA + "/listings")).isEqualTo(404);
        assertThat(http.status(renterA, HttpMethod.GET, "/api/marketplace/" + slugB + "/listings/any-unit")).isEqualTo(404);

        assertThat(http.status(renterA, HttpMethod.GET, "/api/marketplace/me/wishlist")).isEqualTo(200);
        // Public pages are not tied to a session's organisation.
        assertThat(http.status(renterA, HttpMethod.GET, "/public/l/" + slugB)).isEqualTo(200);
    }
}
