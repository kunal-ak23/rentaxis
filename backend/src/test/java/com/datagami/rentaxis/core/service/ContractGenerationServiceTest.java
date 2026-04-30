package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseDocumentRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
}
