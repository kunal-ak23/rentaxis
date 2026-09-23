package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.RenewalTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * #75: when a contract genuinely cannot be produced, its renter gets a 409 that
 * says so, not a 500. The renderer is made to fail; the data is the ordinary kind.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContractRenderFailureIT extends AbstractPostgresIT {

    @LocalServerPort int port;
    @MockitoSpyBean ContractGenerationService contracts;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired LeaseRepository leaseRepo;

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void aContractThatCannotBeRenderedIsA409NotA500() {
        LandlordOrg org = new LandlordOrg();
        org.setName("RenderFail-" + UUID.randomUUID());
        UUID tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
        Lease lease = RenewalTestFixtures.createActiveLease(orgRepo, userRepo, renterRepo, propertyRepo,
                unitRepo, leaseRepo, tenantId, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        lease.setPostedAt(Instant.now());
        leaseRepo.save(lease);
        TenantContextHolder.clear();
        doThrow(new RuntimeException("renderer exploded")).when(contracts).renderPdf(anyString());

        ResponseEntity<byte[]> res = RestClient.builder().baseUrl("http://localhost:" + port).build()
                .get().uri("/api/v1/leases/" + lease.getId() + "/contract")
                .header("X-User-Id", lease.getRenter().getUserId().toString())
                .header("X-User-Role", "RENTER")
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Tenant-Id", tenantId.toString())
                .retrieve().onStatus(s -> true, (rq, rs) -> { })
                .toEntity(byte[].class);

        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(new String(res.getBody(), StandardCharsets.UTF_8)).contains("could not be produced")
                .doesNotContain("renderer exploded");
    }
}
