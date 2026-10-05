package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A property manager opening a draft contract was answered 403 by the cheque grid's
 * and lines grid's account picker (GET /finance/accounts is finance's). The picker
 * now reads {@code /finance/accounts/pickable}: the whole chart for finance roles,
 * the tenant-wide accounts plus their own buildings' for a manager — never another
 * building's, never another organisation's — and nothing at all for a renter.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountPickableRoleIT extends AbstractPostgresIT {

    private static final String PICKABLE = "/api/v1/finance/accounts/pickable";

    @LocalServerPort int port;

    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired PropertyService propertyService;
    @Autowired UserPropertyAssignmentRepository assignmentRepo;

    private CrossTenantHttp http;
    private UUID tenant;
    private UUID otherTenant;
    private Property mine;
    private Property theirs;
    private UUID mineLeaf;
    private UUID theirsLeaf;
    private UUID otherOrgLeaf;

    @BeforeEach
    void setUp() {
        http = new CrossTenantHttp(port, orgRepo, userRepo, accountService, propertyAccountService, propertyService);
        otherTenant = http.tenant("PickOther-");
        otherOrgLeaf = accountService.createLeaf("Other org bank", bankGroup(), null).getId();

        tenant = http.tenant("Pick-");
        mine = http.property("Pick Mine");
        theirs = http.property("Pick Theirs");
        mineLeaf = accountService.createLeaf("Mine bank", bankGroup(), mine.getId()).getId();
        theirsLeaf = accountService.createLeaf("Theirs bank", bankGroup(), theirs.getId()).getId();
        TenantContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void aManagerPicksFromTenantWideAccountsAndTheirOwnBuildingsOnly() {
        User pm = http.user(tenant, UserRole.PROPERTY_MANAGER);
        UserPropertyAssignment a = new UserPropertyAssignment();
        a.setUserId(pm.getId());
        a.setPropertyId(mine.getId());
        assignmentRepo.save(a);

        List<Map<String, Object>> rows = list(pm);
        List<String> ids = rows.stream().map(r -> (String) r.get("id")).toList();
        assertThat(ids).contains(mineLeaf.toString()).doesNotContain(theirsLeaf.toString(), otherOrgLeaf.toString());
        assertThat(rows).anyMatch(r -> r.get("propertyId") == null);
        assertThat(rows.stream().map(r -> (String) r.get("propertyId")).filter(Objects::nonNull).distinct())
                .containsOnly(mine.getId().toString());
    }

    @Test
    void aManagerWithNoBuildingsStillGetsTheTenantWideAccounts() {
        List<Map<String, Object>> rows = list(http.user(tenant, UserRole.PROPERTY_MANAGER));
        assertThat(rows).isNotEmpty().allMatch(r -> r.get("propertyId") == null);
    }

    @Test
    void financeRolesGetTheWholeChartOfTheirOwnOrganisation() {
        for (UserRole role : List.of(UserRole.TENANT_ADMIN, UserRole.ACCOUNTANT, UserRole.SUPER_ADMIN)) {
            List<String> ids = list(http.user(tenant, role)).stream().map(r -> (String) r.get("id")).toList();
            assertThat(ids).as(role.name())
                    .contains(mineLeaf.toString(), theirsLeaf.toString())
                    .doesNotContain(otherOrgLeaf.toString());
        }
    }

    @Test
    void rentersAndTheChartItselfStayRefused() {
        assertThat(http.status(http.user(tenant, UserRole.RENTER), HttpMethod.GET, PICKABLE)).isEqualTo(403);
        // The full chart and every write stay finance's.
        User pm = http.user(tenant, UserRole.PROPERTY_MANAGER);
        assertThat(http.status(pm, HttpMethod.GET, "/api/v1/finance/accounts")).isEqualTo(403);
        assertThat(http.status(pm, HttpMethod.GET, "/api/v1/finance/accounts/tree")).isEqualTo(403);
    }

    private Account bankGroup() {
        return accountService.getAllAccounts().stream()
                .filter(Account::isGroup)
                .filter(x -> x.getAccountSubType() != null && x.getAccountSubType().name().equals("BANK"))
                .findFirst()
                .orElseGet(() -> accountService.getAllAccounts().stream().filter(Account::isGroup)
                        .filter(x -> x.getAccountType().name().equals("ASSET")).findFirst().orElseThrow());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Map<String, Object>> list(User caller) {
        List body = http.request(caller, HttpMethod.GET, PICKABLE).retrieve().body(List.class);
        return (List<Map<String, Object>>) body;
    }
}
