package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseDocument;
import com.datagami.rentaxis.domain.entity.enums.DocumentType;
import com.datagami.rentaxis.domain.repository.*;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.RenewalTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #38: a renter downloads their current contract, and only their own.
 *
 * <p>{@code GET /leases/{id}/contract} serves the stored contract, or renders
 * the posted lease when none was generated (a renewal is posted without one,
 * which is what left a second-year renter with nothing to download).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RenterContractDownloadIT extends AbstractPostgresIT {

    private static final byte[] PDF_BYTES = "%PDF-1.4 stored contract".getBytes(StandardCharsets.UTF_8);

    @LocalServerPort int port;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired LeaseRepository leaseRepo;
    @Autowired LeaseDocumentRepository leaseDocumentRepo;

    private UUID tenantId;
    private Lease withStoredContract;   // renter A
    private Lease renewedWithoutDoc;    // renter B, posted, nothing generated
    private Lease draftWithoutDoc;      // renter C, never posted
    private Lease numberlessRenewal;    // renter D: #75, a posted renewal with no contract number

    @BeforeEach
    void setUp() throws Exception {
        LandlordOrg org = new LandlordOrg();
        org.setName("ContractDl-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);

        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 12, 31);
        withStoredContract = RenewalTestFixtures.createActiveLease(orgRepo, userRepo, renterRepo, propertyRepo,
                unitRepo, leaseRepo, tenantId, start, end);
        renewedWithoutDoc = RenewalTestFixtures.createActiveLease(orgRepo, userRepo, renterRepo, propertyRepo,
                unitRepo, leaseRepo, tenantId, start, end);
        renewedWithoutDoc.setPostedAt(Instant.now());
        renewedWithoutDoc.setContractNumber(42L);
        leaseRepo.save(renewedWithoutDoc); // keep the instance whose renter is loaded
        draftWithoutDoc = RenewalTestFixtures.createActiveLease(orgRepo, userRepo, renterRepo, propertyRepo,
                unitRepo, leaseRepo, tenantId, start, end);
        // #75 as it was on prod: a renewal posted with no generated contract, so no
        // contract number (rendered as "—"), no agreement date, and a renter name
        // with a typographic apostrophe. Both used to become HTML-4 named entities
        // (&mdash;, &rsquo;) that the PDF renderer's XML parser rejects: a 500.
        numberlessRenewal = RenewalTestFixtures.createActiveLease(orgRepo, userRepo, renterRepo, propertyRepo,
                unitRepo, leaseRepo, tenantId, end.plusDays(1), end.plusYears(1));
        numberlessRenewal.setPostedAt(Instant.now());
        numberlessRenewal.setContractNumber(null);
        numberlessRenewal.setAgreementDate(null);
        numberlessRenewal.getRenter().setNameEn("Ahmed O’Brien — Café");
        renterRepo.save(numberlessRenewal.getRenter());
        leaseRepo.save(numberlessRenewal);

        Path pdf = Files.createTempFile("renter-contract-", ".pdf");
        Files.write(pdf, PDF_BYTES);
        pdf.toFile().deleteOnExit();
        LeaseDocument doc = new LeaseDocument();
        doc.setLease(withStoredContract);
        doc.setDocumentUrl(pdf.toString());
        doc.setType(DocumentType.CONTRACT);
        doc.setTenantId(tenantId);
        leaseDocumentRepo.save(doc);

        TenantContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private ResponseEntity<byte[]> getAsRenterOf(Lease owner, Lease target) {
        UUID userId = owner.getRenter().getUserId();
        return RestClient.builder().baseUrl("http://localhost:" + port).build()
                .get().uri("/api/v1/leases/" + target.getId() + "/contract")
                .header("X-User-Id", userId.toString())
                .header("X-User-Role", "RENTER")
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Tenant-Id", tenantId.toString())
                .retrieve().onStatus(s -> true, (rq, rs) -> { })
                .toEntity(byte[].class);
    }

    @Test
    void aRenterDownloadsTheirStoredContract() {
        ResponseEntity<byte[]> res = getAsRenterOf(withStoredContract, withStoredContract);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo(PDF_BYTES);
    }

    @Test
    void anotherRentersContractIsNotFound() {
        ResponseEntity<byte[]> res = getAsRenterOf(renewedWithoutDoc, withStoredContract);

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(new String(res.getBody() == null ? new byte[0] : res.getBody(), StandardCharsets.UTF_8))
                .doesNotContain("stored contract");
    }

    /** The rendered path has no stored document to re-check, so the lease guard is all there is. */
    @Test
    void anotherRentersRenderedContractIsNotFound() {
        ResponseEntity<byte[]> res = getAsRenterOf(withStoredContract, renewedWithoutDoc);

        assertThat(res.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void aPostedLeaseWithNoGeneratedContractIsRenderedForItsRenter() {
        ResponseEntity<byte[]> res = getAsRenterOf(renewedWithoutDoc, renewedWithoutDoc);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(new String(res.getBody(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void anUnpostedLeaseWithNoContractHasNothingToDownload() {
        assertThat(getAsRenterOf(draftWithoutDoc, draftWithoutDoc).getStatusCode().value()).isEqualTo(404);
    }

    /** #75: the renewal with no contract number, as its renter, and as staff previewing it. */
    @Test
    void aRenewalWithNoContractNumberRendersInsteadOf500() {
        ResponseEntity<byte[]> res = getAsRenterOf(numberlessRenewal, numberlessRenewal);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(new String(res.getBody(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        ResponseEntity<byte[]> preview = RestClient.builder().baseUrl("http://localhost:" + port).build()
                .post().uri("/api/v1/leases/" + numberlessRenewal.getId() + "/generate-contract/preview")
                .header("X-User-Id", UUID.randomUUID().toString())
                .header("X-User-Role", "TENANT_ADMIN")
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Tenant-Id", tenantId.toString())
                .retrieve().onStatus(st -> true, (rq, rs) -> { })
                .toEntity(byte[].class);
        assertThat(preview.getStatusCode().value()).isEqualTo(200);
    }
}
