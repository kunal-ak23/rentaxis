package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.AgingReportDTO;
import com.datagami.rentaxis.api.dto.LeasePaymentStatsDTO;
import com.datagami.rentaxis.api.dto.PaymentSummaryDTO;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
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
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Arrears must stay visible after the nightly penalty job relabels them.
 *
 * <p>{@code PenaltyProcessingService.processLeaseOverduePayments} rewrites every
 * past-due PENDING row to {@link PaymentStatus#OVERDUE} at 02:00. The aging
 * report and the per-lease stats used to filter on PENDING only, so an arrear
 * disappeared from both roughly 24 hours after it arose — the aging totals
 * trended toward zero exactly as real arrears grew, while the summary cards on
 * the same screen still counted them.
 *
 * <p>Each test here seeds a row already in the post-job OVERDUE state, which is
 * what production looks like for anything more than a day old.
 */
class PaymentScheduleServiceArrearsVisibilityTest {

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

    /**
     * getAgingReport null-guards renter/unit/property when building each
     * AgingDetail, so a bare lease is enough to exercise the bucket maths.
     */
    private PaymentSchedule pastDue(PaymentStatus status, String amount, int daysAgo) {
        Lease lease = new Lease();
        lease.setStatus(LeaseStatus.ACTIVE);

        PaymentSchedule ps = new PaymentSchedule();
        ps.setLease(lease);
        ps.setStatus(status);
        ps.setAmount(new BigDecimal(amount));
        ps.setDueDate(LocalDate.now().minusDays(daysAgo));
        return ps;
    }

    @Test
    void agingReport_includesRowsTheNightlyJobFlippedToOverdue() {
        when(paymentScheduleRepository.findAll()).thenReturn(List.of(
                pastDue(PaymentStatus.OVERDUE, "5000", 45),
                pastDue(PaymentStatus.PENDING, "3000", 10)));

        AgingReportDTO report = service.getAgingReport(null);

        // Before the fix this was 3000 — the 45-day-old arrear had been
        // relabelled OVERDUE and was filtered out of every bucket.
        assertThat(report.getTotalOutstanding()).isEqualByComparingTo("8000");
        assertThat(report.getBuckets())
                .filteredOn(b -> b.getCount() > 0)
                .extracting(AgingReportDTO.AgingBucket::getLabel)
                .contains("31-60 Days", "1-30 Days");
    }

    @Test
    void agingReport_stillExcludesRowsThatAreNotOwed() {
        when(paymentScheduleRepository.findAll()).thenReturn(List.of(
                pastDue(PaymentStatus.CLEARED, "5000", 45),
                pastDue(PaymentStatus.BOUNCED, "1000", 20),
                pastDue(PaymentStatus.CANCELLED, "900", 20),
                pastDue(PaymentStatus.OVERDUE, "2000", 5)));

        AgingReportDTO report = service.getAgingReport(null);

        // Widening the predicate must not sweep in settled or void rows.
        assertThat(report.getTotalOutstanding()).isEqualByComparingTo("2000");
    }

    @Test
    void agingReport_excludesFutureDatedInstallments() {
        Lease lease = new Lease();
        lease.setStatus(LeaseStatus.ACTIVE);
        PaymentSchedule future = new PaymentSchedule();
        future.setLease(lease);
        future.setStatus(PaymentStatus.PENDING);
        future.setAmount(new BigDecimal("4000"));
        future.setDueDate(LocalDate.now().plusDays(15));

        when(paymentScheduleRepository.findAll()).thenReturn(List.of(future));

        assertThat(service.getAgingReport(null).getTotalOutstanding()).isEqualByComparingTo("0");
    }

    @Test
    void leaseStats_countOverdueRowsAsOverdueAndStillPending() {
        UUID leaseId = UUID.randomUUID();
        when(paymentScheduleRepository.findByLeaseId(leaseId)).thenReturn(List.of(
                pastDue(PaymentStatus.OVERDUE, "5000", 45),
                pastDue(PaymentStatus.PENDING, "3000", 10),
                pastDue(PaymentStatus.CLEARED, "2000", 60)));

        LeasePaymentStatsDTO stats = service.getPaymentStatsByLeaseIds(List.of(leaseId)).get(0);

        // Before the fix: overdue 1/3000, pending 1. The OVERDUE row was
        // invisible in both, so the lease list badge under-reported arrears.
        assertThat(stats.getOverduePayments()).isEqualTo(2);
        assertThat(stats.getOverdueAmount()).isEqualByComparingTo("8000");
        assertThat(stats.getPendingPayments()).isEqualTo(2);
        assertThat(stats.getClearedPayments()).isEqualTo(1);
    }

    @Test
    void summaryAndAgingReportAgreeOnTheSameData() {
        List<PaymentSchedule> rows = List.of(
                pastDue(PaymentStatus.OVERDUE, "5000", 45),
                pastDue(PaymentStatus.PENDING, "3000", 10),
                pastDue(PaymentStatus.COLLECTED, "1500", 2));
        when(paymentScheduleRepository.findAll()).thenReturn(rows);

        PaymentSummaryDTO summary = service.getSummary(null);
        AgingReportDTO report = service.getAgingReport(null);

        // The two views sit on the same finance screen; they disagreeing is the
        // symptom operators actually reported.
        assertThat(report.getTotalOutstanding()).isEqualByComparingTo(summary.getOverdueAmount());
        assertThat(summary.getOverdueCount()).isEqualTo(3);
    }

    private PaymentSchedule pastDueOnLease(LeaseStatus leaseStatus, String amount, int daysAgo) {
        Lease lease = new Lease();
        lease.setStatus(leaseStatus);

        PaymentSchedule ps = new PaymentSchedule();
        ps.setLease(lease);
        ps.setStatus(PaymentStatus.OVERDUE);
        ps.setAmount(new BigDecimal(amount));
        ps.setDueDate(LocalDate.now().minusDays(daysAgo));
        return ps;
    }

    @Test
    void agingReport_excludesUnsignedLeasesJustLikeTheSummaryDoes() {
        when(paymentScheduleRepository.findAll()).thenReturn(List.of(
                pastDueOnLease(LeaseStatus.ACTIVE, "5000", 40),
                pastDueOnLease(LeaseStatus.DRAFT, "9000", 40),
                pastDueOnLease(LeaseStatus.PENDING_SIGNATURE, "7000", 40)));

        AgingReportDTO report = service.getAgingReport(null);

        // An unsigned lease has a generated payment plan but no obligation, so
        // it must not appear as a debtor. Found on production, where summary
        // reported 67,250 outstanding and the aging report reported 111,000.
        assertThat(report.getTotalOutstanding()).isEqualByComparingTo("5000");
    }

    @Test
    void summaryAndAgingAgreeOnceUnsignedLeasesAreExcludedFromBoth() {
        when(paymentScheduleRepository.findAll()).thenReturn(List.of(
                pastDueOnLease(LeaseStatus.ACTIVE, "5000", 40),
                pastDueOnLease(LeaseStatus.DRAFT, "9000", 40),
                pastDueOnLease(LeaseStatus.ACTIVE, "1200", 3)));

        PaymentSummaryDTO summary = service.getSummary(null);
        AgingReportDTO report = service.getAgingReport(null);

        assertThat(report.getTotalOutstanding()).isEqualByComparingTo(summary.getOverdueAmount());
        assertThat(report.getTotalOutstanding()).isEqualByComparingTo("6200");
    }
}
