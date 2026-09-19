package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.DashboardSummaryDTO;
import com.datagami.rentaxis.api.dto.MonthlyCollectionDTO;
import com.datagami.rentaxis.core.service.cheque.ChequeDueRules;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DashboardService {

    /**
     * Money the landlord is still waiting for: the instrument exists and the funds
     * have not arrived. A bounced cheque is not here — it has been superseded or is
     * about to be, and it is counted as overdue instead.
     */
    private static final Set<ChequeStatus> OUTSTANDING =
            EnumSet.of(ChequeStatus.REGISTERED, ChequeStatus.DEPOSITED, ChequeStatus.ONLINE_PENDING);

    /** How many rows the activity feed shows. */
    private static final int RECENT_ACTIVITY_ROWS = 10;

    private final PropertyRepository propertyRepository;
    private final UnitRepository unitRepository;
    private final LeaseRepository leaseRepository;
    private final ChequeRepository chequeRepository;

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

        // --- Financial (the cheque register) ---
        //
        // Aggregated in the database rather than by walking every row: the register
        // is the largest table a landlord of any size has, and the dashboard is the
        // first screen after login. Cheques on unsigned leases (DRAFT /
        // PENDING_SIGNATURE) are a proposal rather than money owed and every query
        // here excludes them.
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate nextMonthStart = monthStart.plusMonths(1);

        Map<ChequeStatus, BigDecimal> byStatus = new EnumMap<>(ChequeStatus.class);
        for (Object[] row : chequeRepository.totalsByStatus(null, true, List.of())) {
            byStatus.put((ChequeStatus) row[0], nz((BigDecimal) row[2]));
        }
        BigDecimal outstanding = BigDecimal.ZERO;
        for (ChequeStatus s : OUTSTANDING) {
            outstanding = outstanding.add(byStatus.getOrDefault(s, BigDecimal.ZERO));
        }

        summary.setCollectedAmount(byStatus.getOrDefault(ChequeStatus.CLEARED, BigDecimal.ZERO));
        summary.setPendingAmount(outstanding);
        // Maturing inside the current calendar month. Earlier unpaid instalments
        // are not here — they surface under "overdue".
        summary.setPendingThisMonthAmount(nz(chequeRepository.sumByStatusInAndChequeDateBetween(
                OUTSTANDING, monthStart, nextMonthStart)));

        // Overdue is ChequeDueRules over the register's due rows, not "past its
        // date": grace is a per-lease number and a dashboard that ignored it would
        // show a renter as late days before their own contract says they are.
        BigDecimal overdueAmount = BigDecimal.ZERO;
        for (Cheque c : chequeRepository.findDue(null, today, true, List.of(),
                org.springframework.data.domain.Pageable.unpaged()).getContent()) {
            Lease lease = c.getLease();
            if (ChequeDueRules.overdue(c, lease == null ? 0 : lease.getGracePeriodDays(), today)) {
                overdueAmount = overdueAmount.add(nz(c.getAmount()));
            }
        }
        summary.setOverdueAmount(overdueAmount);

        // --- Cash actually banked this month / last month ---
        // By clearedAt, which is a date rather than a timestamp, so the old
        // UAE-timezone correction around month boundaries no longer applies:
        // "the day the money landed" is already the landlord's local day.
        summary.setReceivedThisMonth(nz(chequeRepository.sumClearedBetween(
                monthStart, nextMonthStart, null, true, List.of())));
        summary.setReceivedLastMonth(nz(chequeRepository.sumClearedBetween(
                monthStart.minusMonths(1), monthStart, null, true, List.of())));

        // --- Recent Activity ---
        List<DashboardSummaryDTO.RecentActivityItem> activityItems = new ArrayList<>();
        for (Cheque c : chequeRepository.findRecentlyChanged(
                org.springframework.data.domain.PageRequest.of(0, RECENT_ACTIVITY_ROWS))) {
            DashboardSummaryDTO.RecentActivityItem item = new DashboardSummaryDTO.RecentActivityItem();
            item.setType("PAYMENT_" + c.getStatus().name());
            item.setDescription(buildChequeDescription(c));
            item.setTimestamp(c.getStatusChangedAt().toString());
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
        for (Object[] row : chequeRepository.aggregateMonthly(from, toExclusive)) {
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

    private String buildChequeDescription(Cheque c) {
        String unitNumber = c.getUnit() != null ? c.getUnit().getUnitNumber() : "N/A";
        String propertyName = c.getProperty() != null ? c.getProperty().getNameEn() : "N/A";
        return String.format("Payment #%d %s - Unit %s, %s (AED %s)",
                c.getSeqNo(),
                c.getStatus().name().toLowerCase(),
                unitNumber,
                propertyName,
                nz(c.getAmount()).toPlainString());
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
