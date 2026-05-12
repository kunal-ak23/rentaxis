package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.BulkAttachChequeItem;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class PaymentScheduleServiceBulkAttachTest {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired PaymentScheduleService service;
    @Autowired PaymentScheduleRepository scheduleRepo;
    @Autowired LeaseRepository leaseRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired EntityManager entityManager;

    private UUID tenantId;
    private Lease lease;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("BulkAttach-" + UUID.randomUUID());
        org = orgRepo.save(org);
        tenantId = org.getId();
        TenantContextHolder.setTenantId(tenantId);

        User renterUser = new User();
        renterUser.setEmail("renter+" + UUID.randomUUID() + "@test");
        renterUser.setName("Test Renter");
        renterUser.setRole(UserRole.RENTER);
        renterUser.setStatus(UserStatus.ACTIVE);
        renterUser.setPasswordHash("placeholder");
        renterUser.setTenantId(tenantId);
        renterUser = userRepo.save(renterUser);

        Renter renter = new Renter();
        renter.setUserId(renterUser.getId());
        renter.setNameEn("Test Renter");
        renter.setTenantId(tenantId);
        renter = renterRepo.save(renter);

        Property property = new Property();
        property.setNameEn("Test Property");
        property.setEmirate(Emirate.DUBAI);
        property.setTenantId(tenantId);
        property = propertyRepo.save(property);

        Unit unit = new Unit();
        unit.setProperty(property);
        unit.setUnitNumber("A1");
        unit.setTenantId(tenantId);
        unit = unitRepo.save(unit);

        lease = new Lease();
        lease.setUnit(unit);
        lease.setRenter(renter);
        lease.setTenantId(tenantId);
        lease.setStartDate(LocalDate.of(2026, 6, 1));
        lease.setEndDate(LocalDate.of(2027, 5, 31));
        lease.setMonthlyRent(BigDecimal.valueOf(5000));
        lease = leaseRepo.save(lease);

        for (int i = 1; i <= 3; i++) {
            PaymentSchedule ps = new PaymentSchedule();
            ps.setTenantId(tenantId);
            ps.setLease(lease);
            ps.setUnit(unit);
            ps.setProperty(property);
            ps.setInstallmentNumber(i);
            ps.setDueDate(LocalDate.of(2026, 5 + i, 5));
            ps.setAmount(BigDecimal.valueOf(5000));
            ps.setStatus(PaymentStatus.PENDING);
            scheduleRepo.save(ps);
        }
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void bulkAttach_happyPath_attachesAllAndFlipsStatus() {
        List<PaymentSchedule> pending = scheduleRepo.findByLeaseId(lease.getId());
        assertThat(pending).hasSize(3);

        List<BulkAttachChequeItem> items = pending.stream()
                .map(ps -> {
                    BulkAttachChequeItem it = new BulkAttachChequeItem();
                    it.setScheduleId(ps.getId());
                    it.setChequeNumber("CHQ-" + ps.getInstallmentNumber());
                    it.setChequeDate(ps.getDueDate().minusDays(2));
                    it.setBankName("Emirates NBD");
                    it.setPayerName("Test Renter");
                    it.setImageUrl("https://blob.test/a-" + ps.getInstallmentNumber() + ".jpg");
                    it.setImageBlobPath("tenant/" + tenantId + "/cheques/a-" + ps.getInstallmentNumber() + ".jpg");
                    it.setImageUploadedAt(OffsetDateTime.now());
                    return it;
                })
                .toList();

        var result = service.bulkAttachCheques(lease.getId(), items);

        assertThat(result).hasSize(3);
        for (var ps : scheduleRepo.findByLeaseId(lease.getId())) {
            assertThat(ps.getStatus()).isEqualTo(PaymentStatus.COLLECTED);
            assertThat(ps.getChequeNumber()).startsWith("CHQ-");
            assertThat(ps.getChequeImageBlobPath()).isNotNull();
        }
    }

    @Test
    void bulkAttach_scheduleNotPending_throwsAndDoesNotPartiallyApply() {
        List<PaymentSchedule> pending = scheduleRepo.findByLeaseId(lease.getId());
        // Pre-collect the first one so it's not PENDING anymore.
        PaymentSchedule first = pending.get(0);
        first.setStatus(PaymentStatus.COLLECTED);
        first.setChequeNumber("PRE-EXISTING");
        scheduleRepo.save(first);

        List<BulkAttachChequeItem> items = pending.stream()
                .map(ps -> {
                    BulkAttachChequeItem it = new BulkAttachChequeItem();
                    it.setScheduleId(ps.getId());
                    it.setChequeNumber("CHQ-" + ps.getInstallmentNumber());
                    it.setChequeDate(ps.getDueDate());
                    it.setBankName("Emirates NBD");
                    it.setPayerName("Renter");
                    it.setImageUrl("https://blob.test/x.jpg");
                    it.setImageBlobPath("tenant/x.jpg");
                    it.setImageUploadedAt(OffsetDateTime.now());
                    return it;
                })
                .toList();

        assertThatThrownBy(() -> service.bulkAttachCheques(lease.getId(), items))
                .isInstanceOf(BulkAttachValidationException.class);

        // Verify NO partial writes — the two originally-PENDING schedules stay PENDING.
        List<PaymentSchedule> after = scheduleRepo.findByLeaseId(lease.getId());
        long stillPending = after.stream().filter(p -> p.getStatus() == PaymentStatus.PENDING).count();
        assertThat(stillPending).isEqualTo(2);
    }

    @Test
    void bulkAttach_duplicateScheduleIdInRequest_throws() {
        List<PaymentSchedule> pending = scheduleRepo.findByLeaseId(lease.getId());
        UUID dup = pending.get(0).getId();

        List<BulkAttachChequeItem> items = List.of(
                buildItem(dup, "CHQ-A", LocalDate.now()),
                buildItem(dup, "CHQ-B", LocalDate.now())
        );

        assertThatThrownBy(() -> service.bulkAttachCheques(lease.getId(), items))
                .isInstanceOf(BulkAttachValidationException.class);
    }

    @Test
    void bulkAttach_scheduleFromOtherLease_throws() {
        // Build a second lease with one schedule.
        Lease other = new Lease();
        other.setUnit(lease.getUnit());
        other.setRenter(lease.getRenter());
        other.setTenantId(tenantId);
        other.setStartDate(LocalDate.now());
        other.setEndDate(LocalDate.now().plusYears(1));
        other.setMonthlyRent(BigDecimal.valueOf(1000));
        other = leaseRepo.save(other);

        PaymentSchedule otherPs = new PaymentSchedule();
        otherPs.setTenantId(tenantId);
        otherPs.setLease(other);
        otherPs.setUnit(lease.getUnit());
        otherPs.setProperty(lease.getUnit().getProperty());
        otherPs.setInstallmentNumber(1);
        otherPs.setDueDate(LocalDate.now().plusMonths(1));
        otherPs.setAmount(BigDecimal.valueOf(1000));
        otherPs.setStatus(PaymentStatus.PENDING);
        otherPs = scheduleRepo.save(otherPs);

        // Try to attach to our lease using the other lease's schedule id.
        List<BulkAttachChequeItem> items = List.of(buildItem(otherPs.getId(), "CHQ-X", LocalDate.now()));

        assertThatThrownBy(() -> service.bulkAttachCheques(lease.getId(), items))
                .isInstanceOf(BulkAttachValidationException.class);
    }

    @Test
    void bulkAttach_leaseNotFound_throwsNotFoundException() {
        UUID bogusLeaseId = UUID.randomUUID();
        var item = buildItem(UUID.randomUUID(), "CHQ-X", LocalDate.now());

        assertThatThrownBy(() -> service.bulkAttachCheques(bogusLeaseId, List.of(item)))
                .isInstanceOf(com.datagami.rentaxis.api.exception.NotFoundException.class);
    }

    @Test
    void bulkAttach_crossTenantLease_throwsNotFoundException() {
        // Build a fully independent lease under a SECOND tenant.
        UUID originalTenant = tenantId;

        LandlordOrg otherOrg = new LandlordOrg();
        otherOrg.setName("OtherTenant-" + UUID.randomUUID());
        otherOrg = orgRepo.save(otherOrg);
        UUID otherTenant = otherOrg.getId();
        TenantContextHolder.setTenantId(otherTenant);

        User otherRenterUser = new User();
        otherRenterUser.setEmail("other+" + UUID.randomUUID() + "@test");
        otherRenterUser.setName("Other Renter");
        otherRenterUser.setRole(UserRole.RENTER);
        otherRenterUser.setStatus(UserStatus.ACTIVE);
        otherRenterUser.setPasswordHash("placeholder");
        otherRenterUser.setTenantId(otherTenant);
        otherRenterUser = userRepo.save(otherRenterUser);

        Renter otherRenter = new Renter();
        otherRenter.setUserId(otherRenterUser.getId());
        otherRenter.setNameEn("Other Renter");
        otherRenter.setTenantId(otherTenant);
        otherRenter = renterRepo.save(otherRenter);

        Property otherProperty = new Property();
        otherProperty.setNameEn("Other Property");
        otherProperty.setEmirate(Emirate.DUBAI);
        otherProperty.setTenantId(otherTenant);
        otherProperty = propertyRepo.save(otherProperty);

        Unit otherUnit = new Unit();
        otherUnit.setProperty(otherProperty);
        otherUnit.setUnitNumber("Z9");
        otherUnit.setTenantId(otherTenant);
        otherUnit = unitRepo.save(otherUnit);

        Lease otherLease = new Lease();
        otherLease.setUnit(otherUnit);
        otherLease.setRenter(otherRenter);
        otherLease.setTenantId(otherTenant);
        otherLease.setStartDate(LocalDate.of(2026, 6, 1));
        otherLease.setEndDate(LocalDate.of(2027, 5, 31));
        otherLease.setMonthlyRent(BigDecimal.valueOf(5000));
        otherLease = leaseRepo.save(otherLease);

        // Simulate a fresh request: evict the just-persisted other-tenant
        // entities from the OSIV-shared L1 cache so the next findById
        // actually hits the DB (where the Hibernate tenant filter applies).
        // Saves above already committed via per-method transactions, so a
        // bare clear() is sufficient — flush() would require an active tx.
        entityManager.clear();

        // Switch back to the original tenant; otherLease.id MUST be invisible.
        TenantContextHolder.setTenantId(originalTenant);

        var item = buildItem(UUID.randomUUID(), "CHQ-X", LocalDate.now());
        UUID crossTenantLeaseId = otherLease.getId();
        assertThatThrownBy(() -> service.bulkAttachCheques(crossTenantLeaseId, List.of(item)))
                .isInstanceOf(com.datagami.rentaxis.api.exception.NotFoundException.class);
    }

    @Test
    void bulkAttach_duplicateChequeNumberInRequest_throws() {
        List<PaymentSchedule> pending = scheduleRepo.findByLeaseId(lease.getId());
        // Two distinct schedule IDs but the SAME chequeNumber.
        List<BulkAttachChequeItem> items = List.of(
                buildItem(pending.get(0).getId(), "DUP-CHQ", LocalDate.now()),
                buildItem(pending.get(1).getId(), "DUP-CHQ", LocalDate.now())
        );

        assertThatThrownBy(() -> service.bulkAttachCheques(lease.getId(), items))
                .isInstanceOf(BulkAttachValidationException.class);
    }

    @Test
    void bulkAttach_chequeNumberAlreadyUsedOnLease_throws() {
        List<PaymentSchedule> pending = scheduleRepo.findByLeaseId(lease.getId());
        // Pre-stamp installment #1 with a cheque number, leaving the other two PENDING.
        PaymentSchedule first = pending.get(0);
        first.setStatus(PaymentStatus.COLLECTED);
        first.setChequeNumber("CHQ-EXISTING");
        scheduleRepo.save(first);

        // Try to attach to installment #2 reusing the same cheque number.
        var item = buildItem(pending.get(1).getId(), "CHQ-EXISTING", LocalDate.now());

        assertThatThrownBy(() -> service.bulkAttachCheques(lease.getId(), List.of(item)))
                .isInstanceOf(BulkAttachValidationException.class);

        // Untouched schedule #2 stays PENDING.
        PaymentSchedule reloaded = scheduleRepo.findById(pending.get(1).getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    private BulkAttachChequeItem buildItem(UUID scheduleId, String num, LocalDate date) {
        BulkAttachChequeItem it = new BulkAttachChequeItem();
        it.setScheduleId(scheduleId);
        it.setChequeNumber(num);
        it.setChequeDate(date);
        it.setBankName("Bank");
        it.setPayerName("Payer");
        it.setImageUrl("https://blob.test/x.jpg");
        it.setImageBlobPath("t/x.jpg");
        it.setImageUploadedAt(OffsetDateTime.now());
        return it;
    }
}
