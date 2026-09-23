package com.datagami.rentaxis.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.datagami.rentaxis.api.dto.BulkAttachChequeItem;
import com.datagami.rentaxis.api.dto.BulkAttachChequesRequest;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.*;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Attaching a stack of scans to the lease's register rows.
 *
 * <p>The target is a cheque, not a payment schedule: the register row <em>is</em>
 * the instrument, so the scan of the paper belongs on it. Nothing here posts —
 * the row's receivable was raised when it registered and is the same size
 * afterwards — which is why a DEPOSITED row is refused rather than quietly
 * renumbered while the bank is processing it.</p>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class LeaseChequeBulkAttachControllerTest extends AbstractPostgresIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired LeaseRepository leaseRepo;
    @Autowired ChequeRepository chequeRepo;
    @Autowired TransactionTemplate tx;

    private UUID tenantId;
    private Lease lease;
    private Property property;
    private Unit unit;
    private Renter renter;

    @BeforeEach
    void setUp() {
        LandlordOrg org = orgRepo.save(buildOrg());
        tenantId = org.getId();
        TenantContextHolder.setTenantId(tenantId);

        User u = new User();
        u.setEmail("r+" + UUID.randomUUID() + "@test");
        u.setName("Renter");
        u.setRole(UserRole.RENTER);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("ph");
        u.setTenantId(tenantId);
        u = userRepo.save(u);

        Renter r = new Renter();
        r.setUserId(u.getId());
        r.setNameEn("Test Renter");
        r.setTenantId(tenantId);
        renter = renterRepo.save(r);

        Property p = new Property();
        p.setNameEn("Test Property");
        p.setEmirate(Emirate.DUBAI);
        p.setTenantId(tenantId);
        property = propertyRepo.save(p);

        Unit un = new Unit();
        un.setProperty(property);
        un.setUnitNumber("A1");
        un.setTenantId(tenantId);
        unit = unitRepo.save(un);

        lease = new Lease();
        lease.setUnit(unit);
        lease.setRenter(renter);
        lease.setTenantId(tenantId);
        lease.setStatus(LeaseStatus.ACTIVE);
        lease.setStartDate(LocalDate.of(2026, 6, 1));
        lease.setEndDate(LocalDate.of(2027, 5, 31));
        lease.setRentAmount(BigDecimal.valueOf(60000)); // 5,000 x 12 months
        lease = leaseRepo.save(lease);

        for (int i = 1; i <= 2; i++) {
            saveCheque(i, LocalDate.of(2026, 5 + i, 5), ChequeStatus.REGISTERED);
        }
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private Cheque saveCheque(int seqNo, LocalDate chequeDate, ChequeStatus status) {
        Cheque c = new Cheque();
        c.setTenantId(tenantId);
        c.setLease(lease);
        c.setUnit(unit);
        c.setProperty(property);
        c.setRenter(renter);
        c.setSeqNo(seqNo);
        c.setPostingDate(LocalDate.of(2026, 5, 20));
        c.setChequeDate(chequeDate);
        c.setAmount(BigDecimal.valueOf(5000));
        c.setMode(ChequeMode.PDC);
        c.setStatus(status);
        return chequeRepo.save(c);
    }

    private List<Cheque> register() {
        return tx.execute(s -> chequeRepo.findByLease_IdOrderBySeqNoAsc(lease.getId()));
    }

    private LandlordOrg buildOrg() {
        LandlordOrg o = new LandlordOrg();
        o.setName("Org-" + UUID.randomUUID());
        return o;
    }

    @Test
    @WithMockUser(roles = "TENANT_ADMIN")
    void happyPath_writesNumberBankAndDateOntoTheRegisteredRows() throws Exception {
        BulkAttachChequesRequest req = new BulkAttachChequesRequest();
        req.setItems(register().stream()
                .map(c -> buildItem(c.getId(), "C-" + c.getSeqNo(), c.getChequeDate()))
                .toList());

        mvc.perform(post("/api/v1/leases/" + lease.getId() + "/cheques/bulk-attach")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cheques", org.hamcrest.Matchers.hasSize(2)));

        assertThat(register())
                .extracting(Cheque::getChequeNumber, Cheque::getPayeeBank, Cheque::getStatus)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("C-1", "Bank", ChequeStatus.REGISTERED),
                        org.assertj.core.groups.Tuple.tuple("C-2", "Bank", ChequeStatus.REGISTERED));
        // No journal: attaching a scan is not a transition.
        assertThat(register()).allSatisfy(c -> assertThat(c.getCrtJournalId()).isNull());
    }

    /**
     * The old wire name still binds. The dashboard's upload screen sends
     * {@code scheduleId}, and it now carries a cheque id.
     */
    @Test
    @WithMockUser(roles = "TENANT_ADMIN")
    void legacyScheduleIdFieldStillBinds() throws Exception {
        Cheque first = register().getFirst();
        String body = """
                {"items":[{"scheduleId":"%s","chequeNumber":"C-legacy","chequeDate":"2026-06-05",
                "bankName":"ENBD","payerName":"R","imageUrl":"https://blob/x.jpg",
                "imageBlobPath":"t/x.jpg","imageUploadedAt":"2026-05-20T10:00:00Z"}]}"""
                .formatted(first.getId());

        mvc.perform(post("/api/v1/leases/" + lease.getId() + "/cheques/bulk-attach")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        assertThat(register().getFirst().getChequeNumber()).isEqualTo("C-legacy");
    }

    /**
     * A banked cheque's number is on a deposit slip. Rewriting it here would leave
     * the register describing a different instrument from the one the bank holds.
     */
    @Test
    @WithMockUser(roles = "TENANT_ADMIN")
    void depositedRow_returns409AndChangesNothing() throws Exception {
        Cheque first = register().getFirst();
        first.setStatus(ChequeStatus.DEPOSITED);
        chequeRepo.save(first);

        BulkAttachChequesRequest req = new BulkAttachChequesRequest();
        req.setItems(List.of(buildItem(first.getId(), "C-X", LocalDate.now())));

        mvc.perform(post("/api/v1/leases/" + lease.getId() + "/cheques/bulk-attach")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isConflict());

        assertThat(register().getFirst().getChequeNumber()).isNull();
    }

    @Test
    @WithMockUser(roles = "TENANT_ADMIN")
    void chequeOfAnotherLease_returns400() throws Exception {
        BulkAttachChequesRequest req = new BulkAttachChequesRequest();
        req.setItems(List.of(buildItem(UUID.randomUUID(), "C-X", LocalDate.now())));

        mvc.perform(post("/api/v1/leases/" + lease.getId() + "/cheques/bulk-attach")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "TENANT_ADMIN")
    void emptyItems_returns400() throws Exception {
        BulkAttachChequesRequest req = new BulkAttachChequesRequest();
        req.setItems(List.of());

        mvc.perform(post("/api/v1/leases/" + lease.getId() + "/cheques/bulk-attach")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "RENTER")
    void renterRole_returns403() throws Exception {
        BulkAttachChequesRequest req = new BulkAttachChequesRequest();
        req.setItems(List.of(buildItem(UUID.randomUUID(), "C", LocalDate.now())));

        mvc.perform(post("/api/v1/leases/" + lease.getId() + "/cheques/bulk-attach")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    private BulkAttachChequeItem buildItem(UUID chequeId, String num, LocalDate date) {
        BulkAttachChequeItem it = new BulkAttachChequeItem();
        it.setChequeId(chequeId);
        it.setChequeNumber(num);
        it.setChequeDate(date);
        it.setBankName("Bank");
        it.setPayerName("Payer");
        it.setImageUrl("https://blob/x.jpg");
        it.setImageBlobPath("t/x.jpg");
        it.setImageUploadedAt(OffsetDateTime.now());
        return it;
    }
}
