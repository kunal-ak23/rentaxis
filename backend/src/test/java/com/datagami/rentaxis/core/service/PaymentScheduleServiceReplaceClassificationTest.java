package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.UpdatePaymentStatusDTO;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.LeaseChargeRepository;
import com.datagami.rentaxis.domain.repository.LeaseEventRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PaymentPenaltyRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import com.datagami.rentaxis.domain.repository.RentCollectionSettingsRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Replacing a bounced cheque must carry the classification of the row it
 * replaces.
 *
 * <p>{@code replacePayment} copied only lease/unit/property/amount/installment
 * and the new cheque fields. Everything that classifies the money was dropped,
 * with two consequences: {@code vatAmount} reset to zero, so when the
 * replacement finally cleared, {@code clearPayment} took its no-VAT branch and
 * the installment was posted as VAT-free income on a commercial lease; and
 * {@code isSecurityDeposit}/{@code isCharge} reset to false, which breaks the
 * idempotency checks in {@code LeaseService.createOneTimeChargeAndDepositRows}
 * so a later lease edit creates a second deposit row and bills the renter again
 * for money already collected.
 */
class PaymentScheduleServiceReplaceClassificationTest {

    private PaymentScheduleRepository paymentScheduleRepository;
    private PaymentScheduleService service;

    @BeforeEach
    void setUp() {
        paymentScheduleRepository = mock(PaymentScheduleRepository.class);
        service = new PaymentScheduleService(
                paymentScheduleRepository,
                mock(LeaseChargeRepository.class),
                mock(LeaseRepository.class),
                mock(AccountRepository.class),
                mock(FinancialTransactionService.class),
                mock(AccountMappingService.class),
                mock(RentCollectionSettingsRepository.class),
                mock(NotificationService.class),
                mock(FineConfigResolver.class),
                mock(PaymentPenaltyRepository.class),
                mock(LeaseEventRepository.class),
                mock(ApplicationEventPublisher.class),
                new ObjectMapper());
        TenantContextHolder.setTenantId(UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private PaymentSchedule bounced(String amount, String vat, boolean deposit, boolean charge) {
        // mapToDTO dereferences unit, property and renter unconditionally, so
        // the graph has to be hydrated even though this test only asserts on
        // the persisted replacement row.
        Renter renter = new Renter();
        renter.setId(UUID.randomUUID());
        renter.setNameEn("Aisha Khan");

        Property property = new Property();
        property.setId(UUID.randomUUID());
        property.setNameEn("Marina Heights");

        Unit unit = new Unit();
        unit.setId(UUID.randomUUID());
        unit.setUnitNumber("1204");
        unit.setProperty(property);

        Lease lease = new Lease();
        lease.setId(UUID.randomUUID());
        lease.setStatus(LeaseStatus.ACTIVE);
        lease.setRenter(renter);
        lease.setUnit(unit);

        PaymentSchedule ps = new PaymentSchedule();
        ps.setId(UUID.randomUUID());
        ps.setLease(lease);
        ps.setUnit(unit);
        ps.setProperty(property);
        ps.setStatus(PaymentStatus.BOUNCED);
        ps.setAmount(new BigDecimal(amount));
        ps.setVatAmount(new BigDecimal(vat));
        ps.setInstallmentNumber(3);
        ps.setDueDate(LocalDate.now().minusDays(10));
        ps.setSecurityDeposit(deposit);
        ps.setCharge(charge);
        ps.setPurposeLabel(deposit ? "SECURITY DEPOSIT" : null);
        return ps;
    }

    /** Captures the replacement row handed to save(). */
    private PaymentSchedule replaceAndCaptureNewRow(PaymentSchedule old) {
        when(paymentScheduleRepository.findByIdForUpdate(old.getId())).thenReturn(Optional.of(old));
        when(paymentScheduleRepository.save(any(PaymentSchedule.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        UpdatePaymentStatusDTO dto = new UpdatePaymentStatusDTO();
        dto.setChequeNumber("998877");
        dto.setBankName("Emirates NBD");
        service.replacePayment(old.getId(), dto);

        ArgumentCaptor<PaymentSchedule> captor = ArgumentCaptor.forClass(PaymentSchedule.class);
        verify(paymentScheduleRepository, atLeastOnce()).save(captor.capture());
        List<PaymentSchedule> saved = captor.getAllValues();
        // The replacement is the row saved with PENDING status; the original is
        // saved separately as REPLACED.
        return saved.stream()
                .filter(p -> p.getStatus() == PaymentStatus.PENDING)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no replacement row was saved"));
    }

    @Test
    void replacementCarriesVatSoTheClearedInstallmentIsStillTaxed() {
        PaymentSchedule replacement = replaceAndCaptureNewRow(
                bounced("5250", "250", false, false));

        // Zero here is what made clearPayment post the row as VAT-free income.
        assertThat(replacement.getVatAmount()).isEqualByComparingTo("250");
        assertThat(replacement.getAmount()).isEqualByComparingTo("5250");
    }

    @Test
    void replacementKeepsSecurityDepositClassification() {
        PaymentSchedule replacement = replaceAndCaptureNewRow(
                bounced("10000", "0", true, false));

        // Losing this flag makes createOneTimeChargeAndDepositRows believe no
        // deposit row exists and bill the renter a second time.
        assertThat(replacement.isSecurityDeposit()).isTrue();
        assertThat(replacement.getPurposeLabel()).isEqualTo("SECURITY DEPOSIT");
    }

    @Test
    void replacementKeepsOneTimeChargeClassification() {
        PaymentSchedule replacement = replaceAndCaptureNewRow(
                bounced("1500", "0", false, true));

        assertThat(replacement.isCharge()).isTrue();
    }

    @Test
    void plainRentInstallmentStaysUnclassified() {
        PaymentSchedule replacement = replaceAndCaptureNewRow(
                bounced("5000", "0", false, false));

        // Guard against the fix over-reaching and tagging ordinary rent.
        assertThat(replacement.isSecurityDeposit()).isFalse();
        assertThat(replacement.isCharge()).isFalse();
        assertThat(replacement.isBookingDeposit()).isFalse();
        assertThat(replacement.getVatAmount()).isEqualByComparingTo("0");
    }

    @Test
    void originalRowIsMarkedReplaced() {
        PaymentSchedule old = bounced("5000", "0", false, false);
        replaceAndCaptureNewRow(old);

        assertThat(old.getStatus()).isEqualTo(PaymentStatus.REPLACED);
        assertThat(old.getReplacedBy()).isNotNull();
    }
}
