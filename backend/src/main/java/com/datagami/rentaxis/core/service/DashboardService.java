package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.DashboardSummaryDTO;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DashboardService {

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
        BigDecimal overdueAmount = BigDecimal.ZERO;

        List<PaymentSchedule> recentPayments = new ArrayList<>();

        for (PaymentSchedule ps : allPayments) {
            switch (ps.getStatus()) {
                case CLEARED -> clearedAmount = clearedAmount.add(ps.getAmount());
                case PENDING -> pendingAmount = pendingAmount.add(ps.getAmount());
                default -> { }
            }

            // Overdue: PENDING or COLLECTED payments where dueDate < today
            if ((ps.getStatus() == PaymentStatus.PENDING || ps.getStatus() == PaymentStatus.COLLECTED)
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
        summary.setOverdueAmount(overdueAmount);

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
