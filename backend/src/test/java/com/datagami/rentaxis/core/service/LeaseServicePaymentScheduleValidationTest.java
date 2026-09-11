package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.UpdatePaymentScheduleDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import com.datagami.rentaxis.domain.repository.LeaseAttachmentRepository;
import com.datagami.rentaxis.domain.repository.LeaseChargeRepository;
import com.datagami.rentaxis.domain.repository.LeaseDocumentRepository;
import com.datagami.rentaxis.domain.repository.LeaseEventRepository;
import com.datagami.rentaxis.domain.repository.LeaseInteractionRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cheque details on a payment-schedule row are optional.
 *
 * <p>A payment plan is agreed when the lease is signed, but cheques arrive on
 * the renter's own schedule. Requiring chequeNumber, chequeDate and bankName on
 * every CHEQUE row meant the plan could not be saved until every cheque was in
 * hand — and because CHEQUE is the default method, the only workaround was to
 * relabel the outstanding rows as CASH, recording a payment method that was
 * untrue.</p>
 *
 * <p>These are plain Mockito tests: the validation under test is pure logic
 * over the DTO and the loaded rows, so they need neither Spring nor a
 * database.</p>
 */
class LeaseServicePaymentScheduleValidationTest {

    private LeaseRepository leaseRepository;
    private PaymentScheduleRepository paymentScheduleRepository;
    private LeaseService service;

    @BeforeEach
    void setUp() {
        leaseRepository = mock(LeaseRepository.class);
        paymentScheduleRepository = mock(PaymentScheduleRepository.class);

        // Pass-through by default: these tests are about lease behaviour, not
        // authorization. LeaseAccessPolicyTest covers the scoping itself.
        com.datagami.rentaxis.core.security.LeaseAccessPolicy leaseAccessPolicy =
                mock(com.datagami.rentaxis.core.security.LeaseAccessPolicy.class);
        when(leaseAccessPolicy.filterReadable(any())).thenAnswer(inv -> inv.getArgument(0));

        service = new LeaseService(
                leaseRepository,
                mock(UnitRepository.class),
                mock(RenterRepository.class),
                mock(LeaseEventRepository.class),
                mock(LeaseDocumentRepository.class),
                mock(LeaseAttachmentRepository.class),
                mock(PaymentScheduleService.class),
                paymentScheduleRepository,
                mock(LeaseChargeRepository.class),
                mock(LeaseInteractionRepository.class),
                mock(SettlementService.class),
                mock(UnitListingService.class),
                mock(ApplicationEventPublisher.class),
                leaseAccessPolicy);

        when(paymentScheduleRepository.saveAll(any()))
                .thenAnswer(inv -> new java.util.ArrayList<>((java.util.Collection<?>) inv.getArgument(0)));
    }

    @Test
    void chequeRow_savesWithNoChequeDetails() {
        Lease lease = draftLeaseWithRows(3);
        List<PaymentSchedule> rows = paymentScheduleRepository.findByLeaseId(lease.getId());

        UpdatePaymentScheduleDTO dto = dtoFor(rows, "CHEQUE", false);

        assertThatCode(() -> service.updatePaymentSchedule(lease.getId(), dto))
                .doesNotThrowAnyException();

        // The method is still recorded as CHEQUE — the point of the change is
        // that the row does not have to be mislabelled CASH to be saved.
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.getPaymentMethod()).isEqualTo("CHEQUE");
            assertThat(r.getChequeNumber()).isNull();
            assertThat(r.getBankName()).isNull();
        });
    }

    @Test
    void chequeRows_mayBePartiallyDetailed() {
        Lease lease = draftLeaseWithRows(3);
        List<PaymentSchedule> rows = paymentScheduleRepository.findByLeaseId(lease.getId());

        // The real-world case: the first cheque has been handed over, the rest
        // have not. All three rows stay CHEQUE.
        UpdatePaymentScheduleDTO dto = new UpdatePaymentScheduleDTO();
        dto.setRows(List.of(
                row(rows.get(0), "CHEQUE", "100200", LocalDate.of(2026, 1, 5), "Emirates NBD"),
                row(rows.get(1), "CHEQUE", null, null, null),
                row(rows.get(2), "CHEQUE", null, null, null)));

        service.updatePaymentSchedule(lease.getId(), dto);

        assertThat(rows.get(0).getChequeNumber()).isEqualTo("100200");
        assertThat(rows.get(0).getBankName()).isEqualTo("Emirates NBD");
        assertThat(rows.get(1).getChequeNumber()).isNull();
        assertThat(rows.get(2).getChequeNumber()).isNull();
        assertThat(rows).allSatisfy(r -> assertThat(r.getPaymentMethod()).isEqualTo("CHEQUE"));
    }

    @Test
    void chequeDetails_canBeFilledInLater() {
        Lease lease = draftLeaseWithRows(2);
        List<PaymentSchedule> rows = paymentScheduleRepository.findByLeaseId(lease.getId());

        service.updatePaymentSchedule(lease.getId(), dtoFor(rows, "CHEQUE", false));
        assertThat(rows.get(1).getChequeNumber()).isNull();

        // The cheque arrives; the same row is updated in place.
        UpdatePaymentScheduleDTO later = new UpdatePaymentScheduleDTO();
        later.setRows(List.of(row(rows.get(1), "CHEQUE", "100301", LocalDate.of(2026, 2, 5), "ADCB")));
        service.updatePaymentSchedule(lease.getId(), later);

        assertThat(rows.get(1).getChequeNumber()).isEqualTo("100301");
        assertThat(rows.get(1).getBankName()).isEqualTo("ADCB");
        assertThat(rows.get(1).getChequeDate()).isEqualTo(LocalDate.of(2026, 2, 5));
    }

    /**
     * The relaxation is specific to CHEQUE. BANK_TRANSFER and ONLINE still
     * require a bank and a date, because those rows describe a transfer that
     * has a counterparty and a value date rather than an instrument that is
     * physically handed over later.
     */
    @Test
    void bankTransferRow_stillRequiresBankAndDate() {
        Lease lease = draftLeaseWithRows(1);
        List<PaymentSchedule> rows = paymentScheduleRepository.findByLeaseId(lease.getId());

        UpdatePaymentScheduleDTO dto = dtoFor(rows, "BANK_TRANSFER", false);

        assertThatThrownBy(() -> service.updatePaymentSchedule(lease.getId(), dto))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("bankName");
    }

    @Test
    void unsupportedMethod_isStillRejected() {
        Lease lease = draftLeaseWithRows(1);
        List<PaymentSchedule> rows = paymentScheduleRepository.findByLeaseId(lease.getId());

        UpdatePaymentScheduleDTO dto = dtoFor(rows, "CRYPTO", false);

        assertThatThrownBy(() -> service.updatePaymentSchedule(lease.getId(), dto))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Unsupported payment method");
    }

    /** Editing a collected row is still refused — leniency is about detail, not status. */
    @Test
    void alreadyCollectedRow_isStillRefused() {
        Lease lease = draftLeaseWithRows(1);
        List<PaymentSchedule> rows = paymentScheduleRepository.findByLeaseId(lease.getId());
        rows.get(0).setStatus(PaymentStatus.COLLECTED);

        UpdatePaymentScheduleDTO dto = dtoFor(rows, "CHEQUE", false);

        assertThatThrownBy(() -> service.updatePaymentSchedule(lease.getId(), dto))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("already in status");
    }

    // ---- fixtures ----------------------------------------------------------

    private UpdatePaymentScheduleDTO dtoFor(List<PaymentSchedule> rows, String method, boolean withDetails) {
        UpdatePaymentScheduleDTO dto = new UpdatePaymentScheduleDTO();
        dto.setRows(rows.stream()
                .map(r -> withDetails
                        ? row(r, method, "1002" + r.getInstallmentNumber(), LocalDate.of(2026, 1, 5), "Emirates NBD")
                        : row(r, method, null, null, null))
                .toList());
        return dto;
    }

    private UpdatePaymentScheduleDTO.Row row(PaymentSchedule ps, String method,
                                             String chequeNumber, LocalDate chequeDate, String bankName) {
        UpdatePaymentScheduleDTO.Row row = new UpdatePaymentScheduleDTO.Row();
        row.setScheduleId(ps.getId());
        row.setDueDate(ps.getDueDate());
        row.setAmount(ps.getAmount());
        row.setPaymentMethod(method);
        row.setChequeNumber(chequeNumber);
        row.setChequeDate(chequeDate);
        row.setBankName(bankName);
        return row;
    }

    private Lease draftLeaseWithRows(int count) {
        Property property = new Property();
        property.setId(UUID.randomUUID());
        property.setNameEn("Test Property");

        Unit unit = new Unit();
        unit.setId(UUID.randomUUID());
        unit.setUnitNumber("101");
        unit.setProperty(property);

        Renter renter = new Renter();
        renter.setId(UUID.randomUUID());
        renter.setNameEn("Test Renter");

        Lease lease = new Lease();
        lease.setId(UUID.randomUUID());
        lease.setTenantId(UUID.randomUUID());
        lease.setUnit(unit);
        lease.setRenter(renter);
        lease.setStatus(LeaseStatus.DRAFT);
        lease.setStartDate(LocalDate.of(2026, 1, 1));
        lease.setEndDate(LocalDate.of(2026, 12, 31));
        lease.setRentAmount(new BigDecimal("72000"));

        List<PaymentSchedule> rows = new java.util.ArrayList<>();
        for (int i = 1; i <= count; i++) {
            PaymentSchedule ps = new PaymentSchedule();
            ps.setId(UUID.randomUUID());
            ps.setLease(lease);
            ps.setTenantId(lease.getTenantId());
            ps.setInstallmentNumber(i);
            ps.setDueDate(LocalDate.of(2026, i, 1));
            ps.setAmount(new BigDecimal("6000"));
            ps.setStatus(PaymentStatus.PENDING);
            ps.setPaymentMethod("CHEQUE");
            rows.add(ps);
        }

        when(leaseRepository.findById(lease.getId())).thenReturn(Optional.of(lease));
        when(paymentScheduleRepository.findByLeaseId(lease.getId())).thenReturn(rows);
        return lease;
    }
}
