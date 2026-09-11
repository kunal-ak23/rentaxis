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
    void vatExemptRent_withVatApplicablePerInstallmentCharge_recordsOnlyChargeVatOnRentRows() {
        Property property = new Property();
        property.setNameEn("VAT IT Property");
        property.setEmirate(Emirate.DUBAI);
        property.setType(PropertyType.RESIDENTIAL);
        property = propertyRepository.save(property);

        Unit unit = new Unit();
        unit.setProperty(property);
        unit.setUnitNumber("VIT-1");
        unit = unitRepository.save(unit);

        Renter renter = new Renter();
        renter.setNameEn("VAT IT Renter");
        renter.setEmail("vat-it@example.com");
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
        dto.setRentVatApplicable(false); // VAT-EXEMPT rent

        // PER_INSTALLMENT charge that IS VAT-applicable.
        LeaseChargeDTO maintenance = new LeaseChargeDTO();
        maintenance.setName("Maintenance");
        maintenance.setAmount(new BigDecimal("200"));
        maintenance.setVatApplicable(true);
        maintenance.setFrequency(ChargeFrequency.PER_INSTALLMENT);

        // ONE_TIME charge that IS VAT-applicable.
        LeaseChargeDTO adminFee = new LeaseChargeDTO();
        adminFee.setName("Admin Fee");
        adminFee.setAmount(new BigDecimal("1000"));
        adminFee.setVatApplicable(true);
        adminFee.setFrequency(ChargeFrequency.ONE_TIME);

        dto.setCharges(List.of(maintenance, adminFee));

        LeaseDTO created = leaseService.createDraftLease(dto);
        List<PaymentSchedule> rows = paymentScheduleRepository.findByLeaseId(created.getId());

        // Rent rows: rent is VAT-exempt, so the row's vatAmount must equal ONLY
        // the folded charge VAT (200 * 0.05 = 10.00), NOT gross*5/105 (which
        // would be 5210*5/105 = 248.10 — the bug B1 fixes).
        List<PaymentSchedule> rentRows = rows.stream()
                .filter(r -> !r.isCharge() && !r.isSecurityDeposit() && !r.isBookingDeposit())
                .toList();
        assertThat(rentRows).hasSize(6);
        assertThat(rentRows).allSatisfy(r -> {
            assertThat(r.getAmount()).isEqualByComparingTo("5210.00"); // 5000 + 200*1.05
            assertThat(r.getVatAmount()).isEqualByComparingTo("10.00"); // charge VAT only
        });

        // One-time Admin Fee row: vatAmount = 1000 * 0.05 = 50.00 (additive).
        assertThat(rows.stream().filter(PaymentSchedule::isCharge))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.getAmount()).isEqualByComparingTo("1050.00");
                    assertThat(r.getVatAmount()).isEqualByComparingTo("50.00");
                });

        // Security deposit row: never VAT.
        assertThat(rows.stream().filter(PaymentSchedule::isSecurityDeposit))
                .singleElement()
                .satisfies(r -> assertThat(r.getVatAmount()).isEqualByComparingTo("0"));
    }

    @Test
    void vatInclusiveRent_withVatExemptCharge_recordsOnlyRentVat() {
        Property property = new Property();
        property.setNameEn("VAT2 IT Property");
        property.setEmirate(Emirate.DUBAI);
        property.setType(PropertyType.COMMERCIAL);
        property = propertyRepository.save(property);

        Unit unit = new Unit();
        unit.setProperty(property);
        unit.setUnitNumber("V2IT-1");
        unit = unitRepository.save(unit);

        Renter renter = new Renter();
        renter.setNameEn("VAT2 IT Renter");
        renter.setEmail("vat2-it@example.com");
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
        dto.setRentVatApplicable(true); // VAT-INCLUSIVE rent

        // PER_INSTALLMENT charge that is VAT-EXEMPT.
        LeaseChargeDTO maintenance = new LeaseChargeDTO();
        maintenance.setName("Maintenance");
        maintenance.setAmount(new BigDecimal("200"));
        maintenance.setVatApplicable(false);
        maintenance.setFrequency(ChargeFrequency.PER_INSTALLMENT);
        dto.setCharges(List.of(maintenance));

        LeaseDTO created = leaseService.createDraftLease(dto);
        List<PaymentSchedule> rows = paymentScheduleRepository.findByLeaseId(created.getId());

        // Rent rows: rent VAT inclusive on the rent-only share (5000), charge VAT
        // zero. 5000*5/105 = 238.10 (HALF_UP). Amount = 5000 + 200 = 5200.00.
        List<PaymentSchedule> rentRows = rows.stream()
                .filter(r -> !r.isCharge() && !r.isSecurityDeposit() && !r.isBookingDeposit())
                .toList();
        assertThat(rentRows).hasSize(6);
        assertThat(rentRows).allSatisfy(r -> {
            assertThat(r.getAmount()).isEqualByComparingTo("5200.00");
            assertThat(r.getVatAmount()).isEqualByComparingTo("238.10");
        });
    }

    /**
     * A PER_INSTALLMENT charge is folded into every rent cheque after the rent
     * is split, so each row is (rent share + charge) and the rows still sum to
     * rent + total charge.
     *
     * <p>This pair of cases used to be about the deposit cap: the cap was
     * checked against the rent-only split, so folding the charge could push a
     * cheque past the deposit, and the caller shrank the cap by the charge to
     * compensate — with a second case for when that shrink went negative. The
     * cap is gone (see {@link ChequeRoundingCalculator}), so what is left worth
     * pinning is the folding arithmetic itself.</p>
     */
    @Test
    void perInstallmentChargeIsFoldedIntoEveryRentCheque() {
        Property property = new Property();
        property.setNameEn("Cap IT Property");
        property.setEmirate(Emirate.DUBAI);
        property.setType(PropertyType.RESIDENTIAL);
        property = propertyRepository.save(property);

        Unit unit = new Unit();
        unit.setProperty(property);
        unit.setUnitNumber("CAPIT-1");
        unit = unitRepository.save(unit);

        Renter renter = new Renter();
        renter.setNameEn("Cap IT Renter");
        renter.setEmail("cap-it@example.com");
        renter = renterRepository.save(renter);

        // Rent 11000 over 2 cheques: the 1000-step split is [5000, 6000].
        // Folding the 500 charge gives [5500, 6500]. The 6000 deposit no longer
        // has any bearing on that — under the old rule this lease was rewritten
        // to [5500, 5500] to keep the largest cheque inside the deposit.
        CreateLeaseDTO dto = new CreateLeaseDTO();
        dto.setUnitId(unit.getId());
        dto.setRenterId(renter.getId());
        dto.setStartDate(LocalDate.of(2026, 1, 1));
        dto.setEndDate(LocalDate.of(2026, 3, 1)); // 2 months
        dto.setRentAmount(new BigDecimal("11000"));
        dto.setMonthlyRent(new BigDecimal("5500"));
        dto.setDepositAmount(new BigDecimal("6000"));
        dto.setPaymentTerms(2);

        LeaseChargeDTO maintenance = new LeaseChargeDTO();
        maintenance.setName("Maintenance");
        maintenance.setAmount(new BigDecimal("500"));
        maintenance.setVatApplicable(false);
        maintenance.setFrequency(ChargeFrequency.PER_INSTALLMENT);
        dto.setCharges(List.of(maintenance));

        LeaseDTO created = leaseService.createDraftLease(dto);
        List<PaymentSchedule> rentRows = paymentScheduleRepository.findByLeaseId(created.getId()).stream()
                .filter(r -> !r.isCharge() && !r.isSecurityDeposit() && !r.isBookingDeposit())
                .toList();

        assertThat(rentRows).extracting(PaymentSchedule::getAmount)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("5500.00"), new BigDecimal("6500.00"));
        // Sum integrity: total rent + total folded charge preserved.
        BigDecimal total = rentRows.stream().map(PaymentSchedule::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isEqualByComparingTo("12000.00"); // 11000 rent + 2*500 charge
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
