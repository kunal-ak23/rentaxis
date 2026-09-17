package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
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
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Extending a lease must bill the months it adds.
 *
 * <p>{@code extendLease} moved the end date and nothing else, so every extension
 * silently lost the rent for the extra months: never invoiced, absent from the
 * aging report, and impossible to add afterwards — {@code updatePaymentSchedule}
 * is DRAFT-only and {@code generateScheduleForLease} short-circuits once any
 * installment exists. The operator saw a 200 and a "Lease extended" event.
 *
 * <p>The extension continues the pattern already being billed rather than
 * re-running generation for the new window, so the added rows cannot disagree
 * with what the renter has already been charged.
 */
class PaymentScheduleServiceExtensionTest {

    private PaymentScheduleRepository paymentScheduleRepository;
    private PaymentScheduleService service;
    private List<PaymentSchedule> saved;

    @BeforeEach
    void setUp() {
        paymentScheduleRepository = mock(PaymentScheduleRepository.class);
        saved = new ArrayList<>();
        service = new PaymentScheduleService(
                paymentScheduleRepository,
                mock(LeaseChargeRepository.class),
                mock(LeaseRepository.class),
                mock(RentCollectionSettingsRepository.class),
                mock(NotificationService.class),
                mock(FineConfigResolver.class),
                mock(PaymentPenaltyRepository.class),
                mock(LeaseEventRepository.class),
                mock(ApplicationEventPublisher.class),
                new ObjectMapper());
        when(paymentScheduleRepository.save(any(PaymentSchedule.class))).thenAnswer(inv -> {
            PaymentSchedule ps = inv.getArgument(0);
            saved.add(ps);
            return ps;
        });
        TenantContextHolder.setTenantId(UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private Lease lease(LocalDate start, LocalDate end) {
        Property property = new Property();
        Unit unit = new Unit();
        unit.setId(UUID.randomUUID());
        unit.setProperty(property);

        Lease l = new Lease();
        l.setId(UUID.randomUUID());
        l.setStatus(LeaseStatus.ACTIVE);
        l.setUnit(unit);
        l.setStartDate(start);
        l.setEndDate(end);
        return l;
    }

    /** Existing rent installments, monthly from {@code first}, {@code count} of them. */
    private void existingInstallments(Lease l, LocalDate first, int count, String amount, String vat, int stepMonths) {
        List<PaymentSchedule> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            PaymentSchedule ps = new PaymentSchedule();
            ps.setId(UUID.randomUUID());
            ps.setLease(l);
            ps.setUnit(l.getUnit());
            ps.setInstallmentNumber(i + 1);
            ps.setDueDate(first.plusMonths((long) i * stepMonths));
            ps.setAmount(new BigDecimal(amount));
            ps.setVatAmount(new BigDecimal(vat));
            ps.setStatus(PaymentStatus.PENDING);
            rows.add(ps);
        }
        when(paymentScheduleRepository.findByLeaseId(l.getId())).thenReturn(rows);
    }

    @Test
    void monthlyLeaseExtendedBySixMonthsGetsSixMoreInstallments() {
        Lease l = lease(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        existingInstallments(l, LocalDate.of(2026, 1, 1), 12, "5000", "0", 1);

        List<PaymentSchedule> created =
                service.extendScheduleForLease(l, LocalDate.of(2026, 12, 31), LocalDate.of(2027, 6, 30));

        // Before the fix this was zero and the six months were never billed.
        assertThat(created).hasSize(6);
        assertThat(created.get(0).getDueDate()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(created.get(5).getDueDate()).isEqualTo(LocalDate.of(2027, 6, 1));
    }

    @Test
    void extensionInstallmentsCarryTheAmountAlreadyBeingBilled() {
        Lease l = lease(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        existingInstallments(l, LocalDate.of(2026, 1, 1), 12, "5250", "250", 1);

        List<PaymentSchedule> created =
                service.extendScheduleForLease(l, LocalDate.of(2026, 12, 31), LocalDate.of(2027, 3, 31));

        // Continuing the observed rows is what keeps the extension consistent
        // with what the renter has already been charged, VAT included.
        assertThat(created).allSatisfy(ps -> {
            assertThat(ps.getAmount()).isEqualByComparingTo("5250");
            assertThat(ps.getVatAmount()).isEqualByComparingTo("250");
            assertThat(ps.getStatus()).isEqualTo(PaymentStatus.PENDING);
        });
    }

    @Test
    void quarterlyChequeCadenceIsPreserved() {
        // 4 cheques across 12 months.
        Lease l = lease(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        existingInstallments(l, LocalDate.of(2026, 1, 1), 4, "15000", "0", 3);

        List<PaymentSchedule> created =
                service.extendScheduleForLease(l, LocalDate.of(2026, 12, 31), LocalDate.of(2027, 6, 30));

        // Two more quarters, not six monthly rows.
        assertThat(created).hasSize(2);
        assertThat(created.get(0).getDueDate()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(created.get(1).getDueDate()).isEqualTo(LocalDate.of(2027, 4, 1));
    }

    @Test
    void installmentNumbersContinueFromTheExistingSchedule() {
        Lease l = lease(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        existingInstallments(l, LocalDate.of(2026, 1, 1), 12, "5000", "0", 1);

        List<PaymentSchedule> created =
                service.extendScheduleForLease(l, LocalDate.of(2026, 12, 31), LocalDate.of(2027, 2, 28));

        assertThat(created).extracting(PaymentSchedule::getInstallmentNumber)
                .containsExactly(13, 14);
        assertThat(created.get(0).getPurposeLabel()).contains("EXTENSION");
    }

    @Test
    void deposit_charge_and_bookingRowsAreNotMistakenForRent() {
        Lease l = lease(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        List<PaymentSchedule> rows = new ArrayList<>();

        PaymentSchedule deposit = new PaymentSchedule();
        deposit.setId(UUID.randomUUID());
        deposit.setLease(l);
        deposit.setSecurityDeposit(true);
        deposit.setDueDate(LocalDate.of(2026, 1, 1));
        deposit.setAmount(new BigDecimal("20000"));
        rows.add(deposit);

        PaymentSchedule rent = new PaymentSchedule();
        rent.setId(UUID.randomUUID());
        rent.setLease(l);
        rent.setUnit(l.getUnit());
        rent.setInstallmentNumber(1);
        rent.setDueDate(LocalDate.of(2026, 1, 1));
        rent.setAmount(new BigDecimal("5000"));
        rent.setVatAmount(BigDecimal.ZERO);
        rows.add(rent);

        when(paymentScheduleRepository.findByLeaseId(l.getId())).thenReturn(rows);

        List<PaymentSchedule> created =
                service.extendScheduleForLease(l, LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 31));

        // The deposit's 20000 must never become the extension's rent amount.
        assertThat(created).isNotEmpty();
        assertThat(created).allSatisfy(ps -> assertThat(ps.getAmount()).isEqualByComparingTo("5000"));
        assertThat(created).allSatisfy(ps -> assertThat(ps.isSecurityDeposit()).isFalse());
    }

    @Test
    void aLeaseWithNoRentInstallmentsGetsNothingAdded() {
        Lease l = lease(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        when(paymentScheduleRepository.findByLeaseId(l.getId())).thenReturn(List.of());

        assertThat(service.extendScheduleForLease(l, LocalDate.of(2026, 12, 31), LocalDate.of(2027, 6, 30)))
                .isEmpty();
    }
}
