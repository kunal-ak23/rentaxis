package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.*;
import com.datagami.rentaxis.testsupport.RenewalTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class LeaseControllerDocumentsTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    // Minimal fake PDF payload written to a local file; getDocumentContent
    // treats any non-Azure documentUrl as a local file path.
    private static final byte[] PDF_BYTES = "%PDF-1.4 test contract".getBytes(StandardCharsets.UTF_8);

    @LocalServerPort
    int port;

    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired LeaseRepository leaseRepo;
    @Autowired LeaseDocumentRepository leaseDocumentRepo;

    private UUID tenantId;
    private UUID managerUserId;
    private Lease lease;
    private UUID documentId;

    @BeforeEach
    void setUp() throws Exception {
        LandlordOrg org = new LandlordOrg();
        org.setName("TestOrg-" + UUID.randomUUID());
        org = orgRepo.save(org);
        tenantId = org.getId();
        TenantContextHolder.setTenantId(tenantId);

        User manager = new User();
        manager.setEmail("pm+" + UUID.randomUUID() + "@test");
        manager.setName("Property Manager");
        manager.setRole(UserRole.PROPERTY_MANAGER);
        manager.setStatus(UserStatus.ACTIVE);
        manager.setPasswordHash("ph");
        manager.setTenantId(tenantId);
        manager = userRepo.save(manager);
        managerUserId = manager.getId();

        lease = RenewalTestFixtures.createActiveLease(
                orgRepo, userRepo, renterRepo, propertyRepo, unitRepo, leaseRepo,
                tenantId,
                LocalDate.of(2025, 6, 1),
                LocalDate.of(2026, 5, 31));

        Path pdfFile = Files.createTempFile("lease-doc-test-", ".pdf");
        Files.write(pdfFile, PDF_BYTES);
        pdfFile.toFile().deleteOnExit();

        LeaseDocument doc = new LeaseDocument();
        doc.setLease(lease);
        doc.setDocumentUrl(pdfFile.toString());
        doc.setType(DocumentType.CONTRACT);
        doc.setTenantId(tenantId);
        doc = leaseDocumentRepo.save(doc);
        documentId = doc.getId();

        TenantContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private RestClient pmClient() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("X-User-Id", managerUserId.toString())
                .defaultHeader("X-User-Role", "PROPERTY_MANAGER")
                .defaultHeader("X-Tenant-Id", tenantId.toString())
                .defaultHeader("X-User-Tenant-Id", tenantId.toString())
                .build();
    }

    @Test
    void pm_can_list_lease_documents() {
        // Regression: getDocuments previously omitted PROPERTY_MANAGER from
        // @PreAuthorize, 403-ing the manager app's lease detail screen for
        // PROPERTY_MANAGER users.
        List<?> body = pmClient().get()
                .uri("/api/v1/leases/" + lease.getId() + "/documents")
                .retrieve()
                .body(List.class);

        assertThat(body).isNotNull().hasSize(1);
        Map<?, ?> doc = (Map<?, ?>) body.get(0);
        assertThat(doc.get("id")).isEqualTo(documentId.toString());
        assertThat(doc.get("leaseId")).isEqualTo(lease.getId().toString());
        assertThat(doc.get("type")).isEqualTo("CONTRACT");
    }

    @Test
    void pm_can_download_lease_document() {
        // Regression: downloadDocument previously omitted PROPERTY_MANAGER
        // from @PreAuthorize.
        byte[] content = pmClient().get()
                .uri("/api/v1/leases/documents/" + documentId + "/download")
                .retrieve()
                .body(byte[].class);

        assertThat(content).isEqualTo(PDF_BYTES);
    }
}
