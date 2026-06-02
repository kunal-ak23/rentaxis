package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.CreateLeaseDTO;
import com.datagami.rentaxis.api.dto.LeaseChargeDTO;
import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.ChargeFrequency;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.PropertyType;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the full lease-charges generation flow.
 *
 * <p>Stands up a real PostgreSQL via Testcontainers, runs Liquibase migrations
 * (including changeset 61 that creates {@code lease_charges} and drops the
 * legacy fee columns), and drives {@link LeaseService#createDraftLease} which
 * persists charges and generates the schedule. Asserts the per-installment
 * charge is folded into each rent row, the one-time charge becomes its own
 * schedule row (additive VAT), and the security deposit becomes its own row
 * (no VAT).</p>
 *
 * <p>Requires Docker on the host.</p>
 */
@SpringBootTest
@Testcontainers
class LeaseChargesGenerationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired LeaseService leaseService;
    @Autowired PaymentScheduleRepository paymentScheduleRepository;
    @Autowired PropertyRepository propertyRepository;
    @Autowired UnitRepository unitRepository;
    @Autowired RenterRepository renterRepository;
    @Autowired LandlordOrgRepository landlordOrgRepository;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("IT-Tenant-" + UUID.randomUUID());
        org = landlordOrgRepository.save(org);
        this.tenantId = org.getId();
        TenantContextHolder.setTenantId(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void createDraftLease_foldsPerInstallmentCharge_andEmitsOneTimeChargeAndDepositRows() {
        Property property = new Property();
        property.setNameEn("Charges IT Property");
        property.setEmirate(Emirate.DUBAI);
        property.setType(PropertyType.RESIDENTIAL);
        property = propertyRepository.save(property);

        Unit unit = new Unit();
        unit.setProperty(property);
        unit.setUnitNumber("CIT-1");
        unit = unitRepository.save(unit);

        Renter renter = new Renter();
        renter.setNameEn("Charges IT Renter");
        renter.setEmail("charges-it@example.com");
        renter = renterRepository.save(renter);

        CreateLeaseDTO dto = new CreateLeaseDTO();
        dto.setUnitId(unit.getId());
        dto.setRenterId(renter.getId());
        dto.setStartDate(LocalDate.of(2026, 1, 1));
        dto.setEndDate(LocalDate.of(2026, 7, 1)); // 6 months
        dto.setRentAmount(new BigDecimal("30000")); // 5000/mo * 6
        dto.setMonthlyRent(new BigDecimal("5000"));
        dto.setDepositAmount(new BigDecimal("15000"));
        dto.setPaymentTerms(6);

        LeaseChargeDTO maintenance = new LeaseChargeDTO();
        maintenance.setName("Maintenance");
        maintenance.setAmount(new BigDecimal("200"));
        maintenance.setVatApplicable(true);
        maintenance.setFrequency(ChargeFrequency.PER_INSTALLMENT);

        LeaseChargeDTO adminFee = new LeaseChargeDTO();
        adminFee.setName("Admin Fee");
        adminFee.setAmount(new BigDecimal("1000"));
        adminFee.setVatApplicable(true);
        adminFee.setFrequency(ChargeFrequency.ONE_TIME);

        dto.setCharges(List.of(maintenance, adminFee));

        LeaseDTO created = leaseService.createDraftLease(dto);

        List<PaymentSchedule> rows = paymentScheduleRepository.findByLeaseId(created.getId());

        // 6 rent installments — each folds in Maintenance 200 * 1.05 = 210 and is
        // labelled with the recurring-charge suffix.
        List<PaymentSchedule> rentRows = rows.stream()
                .filter(r -> !r.isCharge() && !r.isSecurityDeposit() && !r.isBookingDeposit())
                .toList();
        assertThat(rentRows).hasSize(6);
        assertThat(rentRows).allSatisfy(r -> {
            assertThat(r.getPurposeLabel()).contains("(+ Maintenance)");
            // Each rent share (5000) + folded maintenance (210) = 5210.
            assertThat(r.getAmount()).isEqualByComparingTo("5210.00");
        });

        // One-time Admin Fee row → 1000 * 1.05 = 1050.00
        assertThat(rows.stream().filter(PaymentSchedule::isCharge))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.getPurposeLabel()).isEqualTo("Admin Fee");
                    assertThat(r.getAmount()).isEqualByComparingTo("1050.00");
                });

        // Security deposit row → 15000 (no VAT).
        assertThat(rows.stream().filter(PaymentSchedule::isSecurityDeposit))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.getPurposeLabel()).isEqualTo("SECURITY DEPOSIT");
                    assertThat(r.getAmount()).isEqualByComparingTo("15000");
                });
    }

    @Test
    void updateDraftLease_doesNotDuplicateCollectedSecurityDepositOrChargeRows() {
        Property property = new Property();
        property.setNameEn("Update IT Property");
        property.setEmirate(Emirate.DUBAI);
        property.setType(PropertyType.RESIDENTIAL);
        property = propertyRepository.save(property);

        Unit unit = new Unit();
        unit.setProperty(property);
        unit.setUnitNumber("UIT-1");
        unit = unitRepository.save(unit);

        Renter renter = new Renter();
        renter.setNameEn("Update IT Renter");
        renter.setEmail("update-it@example.com");
        renter = renterRepository.save(renter);

        CreateLeaseDTO dto = new CreateLeaseDTO();
        dto.setUnitId(unit.getId());
        dto.setRenterId(renter.getId());
        dto.setStartDate(LocalDate.of(2026, 1, 1));
        dto.setEndDate(LocalDate.of(2026, 7, 1)); // 6 months
        dto.setRentAmount(new BigDecimal("30000"));
        dto.setMonthlyRent(new BigDecimal("5000"));
        dto.setDepositAmount(new BigDecimal("15000"));
        dto.setPaymentTerms(6);

        LeaseChargeDTO adminFee = new LeaseChargeDTO();
        adminFee.setName("Admin Fee");
        adminFee.setAmount(new BigDecimal("1000"));
        adminFee.setVatApplicable(true);
        adminFee.setFrequency(ChargeFrequency.ONE_TIME);
        dto.setCharges(List.of(adminFee));

        LeaseDTO created = leaseService.createDraftLease(dto);

        // Simulate the SD row and the one-time charge row having been COLLECTED,
        // so the update path's "delete PENDING non-booking rows" step leaves them
        // in place. Without idempotency, the unconditional recreation would add a
        // duplicate PENDING SD / charge row.
        List<PaymentSchedule> beforeUpdate = paymentScheduleRepository.findByLeaseId(created.getId());
        beforeUpdate.stream().filter(PaymentSchedule::isSecurityDeposit).forEach(r -> {
            r.setStatus(com.datagami.rentaxis.domain.entity.enums.PaymentStatus.COLLECTED);
            paymentScheduleRepository.save(r);
        });
        beforeUpdate.stream().filter(PaymentSchedule::isCharge).forEach(r -> {
            r.setStatus(com.datagami.rentaxis.domain.entity.enums.PaymentStatus.COLLECTED);
            paymentScheduleRepository.save(r);
        });

        // Edit the lease (a lighter edit; charges left unchanged by sending the
        // same payload).
        dto.setRentAmount(new BigDecimal("36000"));
        dto.setMonthlyRent(new BigDecimal("6000"));
        leaseService.updateDraftLease(created.getId(), dto);

        List<PaymentSchedule> afterUpdate = paymentScheduleRepository.findByLeaseId(created.getId());

        // Exactly one SD row and one charge row remain — both the originally
        // collected ones, no PENDING duplicates.
        assertThat(afterUpdate.stream().filter(PaymentSchedule::isSecurityDeposit))
                .singleElement()
                .satisfies(r -> assertThat(r.getStatus())
                        .isEqualTo(com.datagami.rentaxis.domain.entity.enums.PaymentStatus.COLLECTED));
        assertThat(afterUpdate.stream().filter(PaymentSchedule::isCharge))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.getPurposeLabel()).isEqualTo("Admin Fee");
                    assertThat(r.getStatus())
                            .isEqualTo(com.datagami.rentaxis.domain.entity.enums.PaymentStatus.COLLECTED);
                });
    }
}
