package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.MaintenanceTicketDTO;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.MaintenanceTicket;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.SettlementDeductionAttachment;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.TicketCategory;
import com.datagami.rentaxis.domain.entity.enums.TicketPriority;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.MaintenanceTicketRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.SettlementDeductionAttachmentRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reads that run <em>outside</em> a transaction, where the Hibernate tenant filter
 * is not enabled.
 *
 * <p>{@code TenantAspect} turns the filter on around {@code domain.repository..*}
 * calls, and a Spring Data method called from a non-transactional service opens its
 * own short transaction — so whether a bare read is filtered is not something to
 * reason about from the annotations. These tests answer it by running it, for the
 * two methods the plan-4 audit found without {@code @Transactional}.</p>
 */
@SpringBootTest
class CrossTenantReadGuardIT extends AbstractPostgresIT {

    @Autowired DeductionAttachmentService attachments;
    @Autowired MaintenanceTicketService tickets;
    @Autowired SettlementDeductionAttachmentRepository attachmentRepo;
    @Autowired MaintenanceTicketRepository ticketRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired JdbcTemplate jdbc;

    @Value("${rentaxis.assets.storage-path:./data/assets}") String storagePath;

    UUID tenantA, tenantB;
    Path uploaded;

    @BeforeEach
    void setUp() {
        tenantA = tenant("A");
        tenantB = tenant("B");
    }

    @AfterEach
    void tearDown() throws Exception {
        TenantContextHolder.clear();
        if (uploaded != null) Files.deleteIfExists(uploaded);
    }

    private UUID tenant(String label) {
        LandlordOrg org = new LandlordOrg();
        org.setName("XT-" + label + "-" + UUID.randomUUID());
        return orgRepo.save(org).getId();
    }

    private UUID propertyIn(UUID tenantId) {
        TenantContextHolder.setTenantId(tenantId);
        Property p = new Property();
        p.setNameEn("Tower " + UUID.randomUUID());
        p.setEmirate(Emirate.DUBAI);
        return propertyRepo.save(p).getId();
    }

    /**
     * A settlement-deduction attachment of tenant A, downloaded by tenant B.
     *
     * <p>{@code downloadAttachmentStream} was the one attachment method with no
     * {@code @Transactional}: its repository read is therefore unfiltered and the
     * row of another landlord does come back, leaving the explicit tenant
     * comparison as the only thing between a caller and someone else's evidence
     * file. Both layers are asserted here — see the report's mutation table, which
     * removes them one at a time.</p>
     */
    @Test
    void anotherTenantsDeductionAttachmentCannotBeDownloaded() throws Exception {
        TenantContextHolder.setTenantId(tenantA);
        Path dir = Path.of(storagePath, "settlement-deductions", "xt-test");
        Files.createDirectories(dir);
        uploaded = dir.resolve(UUID.randomUUID() + ".txt");
        Files.writeString(uploaded, "tenant A evidence", StandardCharsets.UTF_8);

        SettlementDeductionAttachment a = new SettlementDeductionAttachment();
        a.setDeductionId(deductionIn(tenantA));
        a.setName("Damage photo");
        a.setFileUrl("/api/v1/assets/serve/settlement-deductions/xt-test/" + uploaded.getFileName());
        a.setFileType("text/plain");
        a.setFileSize(Files.size(uploaded));
        UUID attachmentId = attachmentRepo.save(a).getId();

        try (InputStream own = attachments.downloadAttachmentStream(attachmentId)) {
            assertThat(new String(own.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("tenant A evidence");
        }

        TenantContextHolder.setTenantId(tenantB);
        assertThatThrownBy(() -> attachments.downloadAttachmentStream(attachmentId))
                .isInstanceOf(NotFoundException.class);
    }

    /**
     * {@code MaintenanceTicketService.getTickets} answers a TENANT_ADMIN with
     * {@code ticketRepository.findAll()} and carries no {@code @Transactional} of
     * its own — so "every ticket" has to mean every ticket <em>of this tenant</em>,
     * and this is the test that says which.
     */
    @Test
    void aTenantAdminSeesOnlyTheirOwnTenantsTickets() {
        UUID ticketA = ticketIn(tenantA, "Lift stuck on 12");
        UUID ticketB = ticketIn(tenantB, "Leak in 401");

        TenantContextHolder.setTenantId(tenantB);
        List<MaintenanceTicketDTO> visible = tickets.getTickets(UUID.randomUUID(), "TENANT_ADMIN", null);

        assertThat(visible).extracting(MaintenanceTicketDTO::getId).contains(ticketB).doesNotContain(ticketA);
    }

    /**
     * The FK chain a deduction hangs off — property, unit, renter, lease,
     * settlement — inserted directly, the way {@code RecognitionSchemaIT} does it.
     * The subject here is one unfiltered read, not the settlement domain.
     */
    private UUID deductionIn(UUID tenantId) {
        UUID property = UUID.randomUUID(), unit = UUID.randomUUID(), renter = UUID.randomUUID();
        UUID lease = UUID.randomUUID(), settlement = UUID.randomUUID(), deduction = UUID.randomUUID();
        jdbc.update("INSERT INTO properties (id, tenant_id, name_en, emirate) VALUES (?,?,?,?)",
                property, tenantId, "P-" + property, "DUBAI");
        jdbc.update("INSERT INTO units (id, tenant_id, property_id, unit_number) VALUES (?,?,?,?)",
                unit, tenantId, property, "U-" + unit);
        jdbc.update("INSERT INTO renters (id, tenant_id, name_en) VALUES (?,?,?)", renter, tenantId, "R-" + renter);
        jdbc.update("INSERT INTO leases (id, tenant_id, unit_id, renter_id, start_date, end_date, status,"
                        + " rent_amount, deposit_amount) VALUES (?,?,?,?,?,?,?,?,?)",
                lease, tenantId, unit, renter, java.time.LocalDate.of(2026, 1, 1), java.time.LocalDate.of(2026, 12, 31),
                "ACTIVE", new java.math.BigDecimal("1200.00"), new java.math.BigDecimal("0.00"));
        jdbc.update("INSERT INTO lease_settlements (id, tenant_id, lease_id) VALUES (?,?,?)",
                settlement, tenantId, lease);
        jdbc.update("INSERT INTO lease_settlement_deductions (id, settlement_id, tenant_id, category, amount)"
                        + " VALUES (?,?,?,?,?)",
                deduction, settlement, tenantId, "DAMAGE", new java.math.BigDecimal("100.00"));
        return deduction;
    }

    private UUID ticketIn(UUID tenantId, String title) {
        UUID propertyId = propertyIn(tenantId);
        TenantContextHolder.setTenantId(tenantId);
        MaintenanceTicket t = new MaintenanceTicket();
        t.setProperty(propertyRepo.findById(propertyId).orElseThrow());
        t.setReportedBy(UUID.randomUUID());
        t.setTitle(title);
        t.setCategory(TicketCategory.PLUMBING);
        t.setPriority(TicketPriority.MEDIUM);
        return ticketRepo.save(t).getId();
    }
}
