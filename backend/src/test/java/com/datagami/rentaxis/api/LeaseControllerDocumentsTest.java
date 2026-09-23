package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.*;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.RenewalTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LeaseControllerDocumentsTest extends AbstractPostgresIT {

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
    @Autowired UserPropertyAssignmentRepository assignmentRepo;

    private UUID otherManagerUserId;

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

        // Assign the lease's property to the manager.
        //
        // This fixture predates object-level authorization, when a manager's
        // assignments did not affect what they could read — so it never modelled
        // one. Now they do: an unassigned manager is refused, which is the whole
        // point of #167. Assigning here keeps this test on its actual subject
        // (the role gate does not 403 a manager) and the negative case below
        // covers the new boundary.
        com.datagami.rentaxis.domain.entity.UserPropertyAssignment assignment =
                new com.datagami.rentaxis.domain.entity.UserPropertyAssignment();
        assignment.setUserId(managerUserId);
        assignment.setPropertyId(lease.getUnit().getProperty().getId());
        assignmentRepo.save(assignment);

        // A second manager, assigned to a DIFFERENT property. Modelling the
        // real scenario — assigned to one building, reaching for another's
        // lease — rather than deleting the first manager's assignment, which
        // needs a transaction the test does not have.
        User otherManager = new User();
        otherManager.setEmail("pm-other-" + UUID.randomUUID() + "@test.local");
        otherManager.setName("Other Manager");
        otherManager.setRole(UserRole.PROPERTY_MANAGER);
        otherManager.setStatus(UserStatus.ACTIVE);
        otherManager.setPasswordHash("ph");
        otherManager.setTenantId(tenantId);
        otherManagerUserId = userRepo.save(otherManager).getId();

        Property elsewhere = new Property();
        elsewhere.setNameEn("Unassigned-Building-" + UUID.randomUUID());
        elsewhere.setEmirate(Emirate.DUBAI);
        elsewhere.setTenantId(tenantId);
        elsewhere = propertyRepo.save(elsewhere);

        com.datagami.rentaxis.domain.entity.UserPropertyAssignment elsewhereAssignment =
                new com.datagami.rentaxis.domain.entity.UserPropertyAssignment();
        elsewhereAssignment.setUserId(otherManagerUserId);
        elsewhereAssignment.setPropertyId(elsewhere.getId());
        assignmentRepo.save(elsewhereAssignment);

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
        return clientFor(managerUserId);
    }

    private RestClient clientFor(UUID userId) {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("X-User-Id", userId.toString())
                .defaultHeader("X-User-Role", "PROPERTY_MANAGER")
                .defaultHeader("X-Tenant-Id", tenantId.toString())
                .defaultHeader("X-User-Tenant-Id", tenantId.toString())
                .build();
    }

    /**
     * The boundary the assignment above exists to prove. A manager with no
     * assignment covering this lease's property must not be able to read its
     * contract — that was readable tenant-wide before #167.
     */
    @Test
    void unassigned_pm_cannot_list_lease_documents() {
        assertThatThrownBy(() -> clientFor(otherManagerUserId).get()
                .uri("/api/v1/leases/" + lease.getId() + "/documents")
                .retrieve()
                .body(List.class))
                .isInstanceOf(org.springframework.web.client.HttpClientErrorException.NotFound.class);
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
