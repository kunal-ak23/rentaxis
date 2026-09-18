package com.datagami.rentaxis.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.datagami.rentaxis.api.dto.BulkAttachChequeItem;
import com.datagami.rentaxis.api.dto.BulkAttachChequesRequest;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class LeaseChequeBulkAttachControllerTest {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired LeaseRepository leaseRepo;
    @Autowired PaymentScheduleRepository scheduleRepo;

    private UUID tenantId;
    private Lease lease;

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
        r = renterRepo.save(r);

        Property p = new Property();
        p.setNameEn("Test Property");
        p.setEmirate(Emirate.DUBAI);
        p.setTenantId(tenantId);
        p = propertyRepo.save(p);

        Unit unit = new Unit();
        unit.setProperty(p);
        unit.setUnitNumber("A1");
        unit.setTenantId(tenantId);
        unit = unitRepo.save(unit);

        lease = new Lease();
        lease.setUnit(unit);
        lease.setRenter(r);
        lease.setTenantId(tenantId);
        lease.setStartDate(LocalDate.of(2026, 6, 1));
        lease.setEndDate(LocalDate.of(2027, 5, 31));
        lease.setRentAmount(BigDecimal.valueOf(60000)); // 5,000 x 12 months
        lease = leaseRepo.save(lease);

        for (int i = 1; i <= 2; i++) {
            PaymentSchedule ps = new PaymentSchedule();
            ps.setTenantId(tenantId);
            ps.setLease(lease);
            ps.setUnit(unit);
            ps.setProperty(p);
            ps.setInstallmentNumber(i);
            ps.setDueDate(LocalDate.of(2026, 5 + i, 5));
            ps.setAmount(BigDecimal.valueOf(5000));
            ps.setStatus(PaymentStatus.PENDING);
            scheduleRepo.save(ps);
        }
    }

    private LandlordOrg buildOrg() {
        LandlordOrg o = new LandlordOrg();
        o.setName("Org-" + UUID.randomUUID());
        return o;
    }

    @Test
    @WithMockUser(roles = "TENANT_ADMIN")
    void happyPath_returns200WithSchedules() throws Exception {
        BulkAttachChequesRequest req = new BulkAttachChequesRequest();
        req.setItems(scheduleRepo.findByLeaseId(lease.getId()).stream()
                .map(ps -> {
                    BulkAttachChequeItem it = new BulkAttachChequeItem();
                    it.setScheduleId(ps.getId());
                    it.setChequeNumber("C-" + ps.getInstallmentNumber());
                    it.setChequeDate(ps.getDueDate());
                    it.setBankName("ENBD");
                    it.setPayerName("R");
                    it.setImageUrl("https://blob/x.jpg");
                    it.setImageBlobPath("t/x.jpg");
                    it.setImageUploadedAt(OffsetDateTime.now());
                    return it;
                })
                .toList());

        mvc.perform(post("/api/v1/leases/" + lease.getId() + "/cheques/bulk-attach")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedules", org.hamcrest.Matchers.hasSize(2)));
    }

    @Test
    @WithMockUser(roles = "TENANT_ADMIN")
    void scheduleNotPending_returns409() throws Exception {
        // Pre-collect schedule 1 so it's no longer PENDING.
        var schedules = scheduleRepo.findByLeaseId(lease.getId());
        PaymentSchedule first = schedules.get(0);
        first.setStatus(PaymentStatus.COLLECTED);
        scheduleRepo.save(first);

        BulkAttachChequesRequest req = new BulkAttachChequesRequest();
        req.setItems(List.of(buildItem(first.getId(), "C-X", LocalDate.now())));

        mvc.perform(post("/api/v1/leases/" + lease.getId() + "/cheques/bulk-attach")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isConflict());
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

    private BulkAttachChequeItem buildItem(UUID scheduleId, String num, LocalDate date) {
        BulkAttachChequeItem it = new BulkAttachChequeItem();
        it.setScheduleId(scheduleId);
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
