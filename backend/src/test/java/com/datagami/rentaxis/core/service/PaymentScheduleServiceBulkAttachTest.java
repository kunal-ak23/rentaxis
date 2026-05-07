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
