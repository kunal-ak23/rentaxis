package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An ACCOUNTANT has to be able to read the operational entities every ledger
 * row is tagged with — the property, the unit and the renter behind a journal
 * line — or the finance screens render ids. The role was added in plan 1 with
 * finance-only read access; this is the operational half of it.
 *
 * <p>Auth context normally injected by the Next.js proxy is simulated with the
 * legacy X-User-* / X-Tenant-* headers {@code ApiSecurityFilter} reads, as in
 * {@link JournalControllerIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AccountantOperationalReadAccessIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @LocalServerPort int port;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;

    private User accountant;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("ARA-" + UUID.randomUUID());
        UUID tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
        try {
            User u = new User();
            u.setEmail("accountant-" + UUID.randomUUID() + "@t.io");
            u.setName("Accountant");
            u.setRole(UserRole.ACCOUNTANT);
            u.setStatus(UserStatus.ACTIVE);
            u.setPasswordHash("x");
            u.setTenantId(tenantId);
            accountant = userRepo.save(u);
        } finally {
            TenantContextHolder.clear();
        }
    }

    private ResponseEntity<List> listAs(User caller, String uri) {
        return RestClient.builder().baseUrl("http://localhost:" + port).build()
                .get().uri(uri)
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", caller.getTenantId().toString())
                .header("X-User-Tenant-Id", caller.getTenantId().toString())
                .retrieve().toEntity(List.class);
    }

    @Test
    void accountantCanListProperties() {
        assertThat(listAs(accountant, "/api/v1/properties").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void accountantCanListUnits() {
        assertThat(listAs(accountant, "/api/v1/units").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void accountantCanListRenters() {
        assertThat(listAs(accountant, "/api/v1/renters").getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
