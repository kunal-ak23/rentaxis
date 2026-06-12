package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.DashboardSummaryDTO;
import com.datagami.rentaxis.api.dto.MonthlyCollectionDTO;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DashboardService {

    /** Tenant-facing timezone — RentAxis serves UAE landlords. */
    private static final ZoneId UAE_ZONE = ZoneId.of("Asia/Dubai");

    private final PropertyRepository propertyRepository;
    private final UnitRepository unitRepository;
    private final LeaseRepository leaseRepository;
    private final PaymentScheduleRepository paymentScheduleRepository;

    @Transactional(readOnly = true)
    public DashboardSummaryDTO getSummary() {
        DashboardSummaryDTO summary = new DashboardSummaryDTO();

        // --- Portfolio ---
        List<Property> properties = propertyRepository.findAll();
        summary.setTotalProperties(properties.size());

        List<Unit> units = unitRepository.findAll();
        summary.setTotalUnits(units.size());

        int occupiedCount = 0;
        int vacantCount = 0;
        for (Unit unit : units) {
            if (unit.getStatus() == UnitStatus.OCCUPIED) {
                occupiedCount++;
            } else if (unit.getStatus() == UnitStatus.VACANT) {
                vacantCount++;
            }
        }
        summary.setOccupiedUnits(occupiedCount);
        summary.setVacantUnits(vacantCount);
        summary.setOccupancyRate(units.isEmpty() ? 0.0
                : (double) occupiedCount / units.size() * 100.0);

        // --- Leases ---
        List<Lease> allLeases = leaseRepository.findAll();

        int activeCount = 0;
        int draftCount = 0;
        int expiringCount = 0;
        BigDecimal totalRentRevenue = BigDecimal.ZERO;
        LocalDate today = LocalDate.now();
        LocalDate thirtyDaysFromNow = today.plusDays(30);

        for (Lease lease : allLeases) {
            if (lease.getStatus() == LeaseStatus.ACTIVE) {
                activeCount++;
                totalRentRevenue = totalRentRevenue.add(
                        lease.getRentAmount() != null ? lease.getRentAmount() : BigDecimal.ZERO);

                // Expiring = ACTIVE leases where endDate is within 30 days from today
                if (lease.getEndDate() != null
                        && !lease.getEndDate().isAfter(thirtyDaysFromNow)
                        && !lease.getEndDate().isBefore(today)) {
                    expiringCount++;
                }
            } else if (lease.getStatus() == LeaseStatus.DRAFT) {
                draftCount++;
            }
        }

        summary.setActiveLeases(activeCount);
        summary.setDraftLeases(draftCount);
        summary.setExpiringLeases(expiringCount);
        summary.setTotalRentRevenue(totalRentRevenue);

        // --- Financial (payments) ---
        List<PaymentSchedule> allPayments = paymentScheduleRepository.findAll();

        BigDecimal clearedAmount = BigDecimal.ZERO;
        BigDecimal pendingAmount = BigDecimal.ZERO;
        BigDecimal pendingThisMonthAmount = BigDecimal.ZERO;
        BigDecimal overdueAmount = BigDecimal.ZERO;

        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate nextMonthStart = monthStart.plusMonths(1);

        List<PaymentSchedule> recentPayments = new ArrayList<>();

        for (PaymentSchedule ps : allPayments) {
            // Unsigned leases (DRAFT / PENDING_SIGNATURE) have schedules too,
            // but no money is owed until the lease is signed — keep them out
            // of every financial aggregate.
            LeaseStatus leaseStatus = ps.getLease() != null ? ps.getLease().getStatus() : null;
            if (leaseStatus == LeaseStatus.DRAFT || leaseStatus == LeaseStatus.PENDING_SIGNATURE) {
                continue;
            }
            switch (ps.getStatus()) {
                case CLEARED -> clearedAmount = clearedAmount.add(ps.getAmount());
                case PENDING -> {
                    pendingAmount = pendingAmount.add(ps.getAmount());
                    // Pending due within the current calendar month (excludes prior
                    // unpaid months — those surface under "overdue").
                    LocalDate due = ps.getDueDate();
                    if (due != null && !due.isBefore(monthStart) && due.isBefore(nextMonthStart)) {
                        pendingThisMonthAmount = pendingThisMonthAmount.add(ps.getAmount());
                    }
                }
                default -> { }
            }

            // Overdue: PENDING/COLLECTED past due, or already flagged OVERDUE
            // by the penalty batch job.
            if ((ps.getStatus() == PaymentStatus.PENDING
                    || ps.getStatus() == PaymentStatus.COLLECTED
                    || ps.getStatus() == PaymentStatus.OVERDUE)
                    && ps.getDueDate() != null
                    && ps.getDueDate().isBefore(today)) {
                overdueAmount = overdueAmount.add(ps.getAmount());
            }

            // Collect payments that have statusChangedAt for recent activity
            if (ps.getStatusChangedAt() != null) {
                recentPayments.add(ps);
            }
        }

        summary.setCollectedAmount(clearedAmount);
        summary.setPendingAmount(pendingAmount);
        summary.setPendingThisMonthAmount(pendingThisMonthAmount);
        summary.setOverdueAmount(overdueAmount);

        // --- Cash received this month / last month (by status-change time) ---
        // Anchor month windows to UAE local time, not the JVM default (the
        // prod VM runs UTC; without this, receipts in the first 4h of a UAE
        // month would bucket into the previous month).
        ZoneId zone = UAE_ZONE;
        YearMonth currentMonth = YearMonth.from(today);
        Instant receiptsFrom = currentMonth.atDay(1).atStartOfDay(zone).toInstant();
        Instant receiptsTo = currentMonth.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        Instant prevReceiptsFrom = currentMonth.minusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        summary.setReceivedThisMonth(
                paymentScheduleRepository.sumReceivedBetween(receiptsFrom, receiptsTo));
        summary.setReceivedLastMonth(
                paymentScheduleRepository.sumReceivedBetween(prevReceiptsFrom, receiptsFrom));

        // --- Recent Activity ---
        recentPayments.sort(Comparator.comparing(PaymentSchedule::getStatusChangedAt).reversed());
        List<PaymentSchedule> topRecent = recentPayments.stream().limit(10).toList();

        List<DashboardSummaryDTO.RecentActivityItem> activityItems = new ArrayList<>();
        for (PaymentSchedule ps : topRecent) {
            DashboardSummaryDTO.RecentActivityItem item = new DashboardSummaryDTO.RecentActivityItem();
            item.setType("PAYMENT_" + ps.getStatus().name());
            item.setDescription(buildPaymentDescription(ps));
            item.setTimestamp(ps.getStatusChangedAt().toString());
            activityItems.add(item);
        }
        summary.setRecentActivity(activityItems);

        return summary;
    }

    /**
     * Last 12 calendar months (oldest → current) of expected vs collected,
     * zero-filling any months without schedules so the chart always has a
     * contiguous 12-point series.
     */
    @Transactional(readOnly = true)
    public List<MonthlyCollectionDTO> getMonthlyCollections() {
        YearMonth current = YearMonth.from(LocalDate.now());
        YearMonth start = current.minusMonths(11);
        LocalDate from = start.atDay(1);
        LocalDate toExclusive = current.plusMonths(1).atDay(1);

        Map<String, BigDecimal[]> byYm = new HashMap<>();
        for (Object[] row : paymentScheduleRepository.aggregateMonthlyCollection(from, toExclusive)) {
            String ym = (String) row[0];
            BigDecimal expected = row[1] != null ? (BigDecimal) row[1] : BigDecimal.ZERO;
            BigDecimal collected = row[2] != null ? (BigDecimal) row[2] : BigDecimal.ZERO;
            byYm.put(ym, new BigDecimal[]{expected, collected});
        }

        List<MonthlyCollectionDTO> series = new ArrayList<>(12);
        for (int i = 0; i < 12; i++) {
            YearMonth ym = start.plusMonths(i);
            String key = String.format("%04d-%02d", ym.getYear(), ym.getMonthValue());
            BigDecimal[] vals = byYm.getOrDefault(key, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            String label = ym.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
            series.add(new MonthlyCollectionDTO(label, key, vals[0], vals[1]));
        }
        return series;
    }

    private String buildPaymentDescription(PaymentSchedule ps) {
        String unitNumber = ps.getUnit() != null ? ps.getUnit().getUnitNumber() : "N/A";
        String propertyName = ps.getProperty() != null ? ps.getProperty().getNameEn() : "N/A";
        return String.format("Payment #%d %s - Unit %s, %s (AED %s)",
                ps.getInstallmentNumber(),
                ps.getStatus().name().toLowerCase(),
                unitNumber,
                propertyName,
                ps.getAmount().toPlainString());
    }
}
