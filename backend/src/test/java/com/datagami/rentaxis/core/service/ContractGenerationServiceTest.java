package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseDocumentRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContractGenerationServiceTest {

    private ContractGenerationService service;

    @BeforeEach
    void setUp() {
        service = new ContractGenerationService(
                mock(LeaseRepository.class),
                mock(LeaseDocumentRepository.class),
                mock(LandlordOrgRepository.class),
                mock(PaymentScheduleRepository.class));
    }

    private Lease leaseWith(BigDecimal rent, BigDecimal admin, BigDecimal deposit, BigDecimal parking,
                             boolean rentVat, boolean adminVat, boolean depositVat, boolean parkingVat) {
        Lease l = new Lease();
        l.setRentAmount(rent);
        l.setAdminFee(admin);
        l.setDepositAmount(deposit);
        l.setParkingRemoteFee(parking);
        l.setRentVatApplicable(rentVat);
        l.setAdminFeeVatApplicable(adminVat);
        l.setSecurityDepositVatApplicable(depositVat);
        l.setParkingRemoteVatApplicable(parkingVat);
        return l;
    }

    @Test
    void section3HidesZeroAmountRows() {
        Lease lease = leaseWith(new BigDecimal("55000"), new BigDecimal("2000"), new BigDecimal("3000"),
                BigDecimal.ZERO, false, false, false, false);
        String html = service.buildSection3Rows(lease);
        assertThat(html).contains("Rent").contains("55,000.00").contains("Exempt");
        assertThat(html).contains("Admin Fee").contains("2,000.00");
        assertThat(html).contains("Security Deposit").contains("3,000.00");
        assertThat(html).doesNotContain("Parking Remote");
    }

    @Test
    void section3RendersVatAt5PercentWhenApplicable() {
        Lease lease = leaseWith(new BigDecimal("55000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                true, false, false, false);
        String html = service.buildSection3Rows(lease);
        assertThat(html).contains("5%").contains("2,750.00").contains("57,750.00");
    }

    @Test
    void section3AllRowsExemptByDefault() {
        Lease lease = leaseWith(new BigDecimal("55000"), new BigDecimal("2000"), new BigDecimal("3000"),
                new BigDecimal("300"), false, false, false, false);
        String html = service.buildSection3Rows(lease);
        // "Exempt" should appear 4 times (one per row)
        int count = html.split("Exempt", -1).length - 1;
        assertThat(count).isEqualTo(4);
    }

    @Test
    void section3AllRowsCommercialVat() {
        Lease lease = leaseWith(new BigDecimal("55000"), new BigDecimal("2000"), new BigDecimal("3000"),
                new BigDecimal("300"), true, true, true, true);
        String html = service.buildSection3Rows(lease);
        int count = html.split("5%", -1).length - 1;
        assertThat(count).isEqualTo(4);
    }

    private PaymentSchedule ps(LocalDate chequeDate, String chqNo, String bank, BigDecimal amount,
                                String purpose, boolean booking) {
        PaymentSchedule p = new PaymentSchedule();
        p.setChequeDate(chequeDate);
        p.setChequeNumber(chqNo);
        p.setBankName(bank);
        p.setAmount(amount);
        p.setPurposeLabel(purpose);
        p.setBookingDeposit(booking); // Lombok strips "is" prefix on primitive boolean setters
        return p;
    }

    @Test
    void section4OrdersByChequeDateAscWithBookingLast() {
        PaymentSchedule p1 = ps(LocalDate.of(2026, 4, 18), "TT", "TRANSFER", new BigDecimal("18050"),
                "RENT - 1ST INSTALLMENT/ADMIN/SD/REMOTE", false);
        PaymentSchedule p2 = ps(LocalDate.of(2026, 7, 24), "000001", "ENBD", new BigDecimal("13750"),
                "RENT - 2ND INSTALLMENT", false);
        PaymentSchedule booking = ps(LocalDate.of(2026, 4, 14), "TT", "TRANSFER", new BigDecimal("1000"),
                "BOOKING RECEIVED", true);

        // Pass them in random order; the method should sort
        String html = service.buildSection4Rows(List.of(p2, booking, p1));

        int idxP1 = html.indexOf("18,050.00");
        int idxP2 = html.indexOf("13,750.00");
        int idxBooking = html.indexOf("BOOKING RECEIVED");
        assertThat(idxP1).isPositive();
        assertThat(idxP2).isPositive();
        assertThat(idxBooking).isPositive();
        assertThat(idxP1).isLessThan(idxP2);
        assertThat(idxBooking).isGreaterThan(idxP2); // booking last
    }

    @Test
    void section4FormatsDateAsDayMonthYear() {
        PaymentSchedule p = ps(LocalDate.of(2026, 4, 18), "TT", "TRANSFER", new BigDecimal("100"),
                "RENT - 1ST INSTALLMENT", false);
        String html = service.buildSection4Rows(List.of(p));
        assertThat(html).contains("18 Apr 2026");
    }

    @Test
    void assignsNextContractNumberWhenNull() {
        Lease lease = new Lease();
        lease.setTenantId(UUID.randomUUID());
        // Default new lease has contractNumber == null

        // Re-create service with controllable mock
        LeaseRepository leaseRepo = mock(LeaseRepository.class);
        when(leaseRepo.findMaxContractNumberForTenant(lease.getTenantId())).thenReturn(1750L);
        ContractGenerationService svc = new ContractGenerationService(
                leaseRepo, mock(LeaseDocumentRepository.class), mock(LandlordOrgRepository.class), mock(PaymentScheduleRepository.class));

        svc.assignContractNumberIfNull(lease);
        assertThat(lease.getContractNumber()).isEqualTo(1751L);
    }

    @Test
    void doesNotReassignExistingContractNumber() {
        Lease lease = new Lease();
        lease.setTenantId(UUID.randomUUID());
        lease.setContractNumber(42L);
        service.assignContractNumberIfNull(lease);
        assertThat(lease.getContractNumber()).isEqualTo(42L);
    }
}
