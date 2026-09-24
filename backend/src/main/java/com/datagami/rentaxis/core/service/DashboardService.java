package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.DashboardSummaryDTO;
import com.datagami.rentaxis.api.dto.MonthlyCollectionDTO;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.core.service.cheque.ChequeDueRules;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.Lease;
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
    private final LeaseAccessPolicy leaseAccessPolicy;
    private final com.datagami.rentaxis.core.service.cheque.BouncedDebt bouncedDebt;

    /**
     * The whole dashboard, for whoever is asking.
     *
     * <p><b>One scope, every tile.</b> A property manager is assigned buildings, and
     * every number on this screen is about those buildings and no others — the
     * portfolio counts and the occupancy rate as much as the money. An unscoped
     * headline above a scoped list is two faults at once: the tile is somebody
     * else's estate, and the screen contradicts the pages it links to, which is how
     * a manager learns to trust neither.</p>
     *
     * <p><b>Counted in the database.</b> Each block is an aggregate over the
     * caller's properties rather than a {@code findAll()} the service then walks:
     * this is the first screen after login, and the tables behind it are the largest
     * the system has.</p>
     */
    @Transactional(readOnly = true)
    public DashboardSummaryDTO getSummary() {
        DashboardSummaryDTO summary = new DashboardSummaryDTO();
        Scope scope = scope();
        // The landlord's day: the JVM default zone is app.time-zone (Asia/Dubai,
        // see AppTimeZone), so "this month" starts at midnight Dubai time.
        LocalDate today = LocalDate.now();

        // --- Portfolio ---
        summary.setTotalProperties(scope.blocked() ? 0
                : (int) propertyRepository.countInScope(scope.unrestricted(), scope.propertyIds()));

        long totalUnits = 0;
        long maintenanceCount = 0;
        long occupiedCount = 0;
        long reservedCount = 0;
        if (!scope.blocked()) {
            for (Object[] row : unitRepository.countByStatusInScope(
                    scope.unrestricted(), scope.propertyIds())) {
                UnitStatus status = (UnitStatus) row[0];
                long count = ((Number) row[1]).longValue();
                totalUnits += count;
                if (status == UnitStatus.MAINTENANCE) {
                    maintenanceCount = count;
                }
            }
            // F14-01: occupied means a posted lease whose term covers today, not
            // unit.status — posting flips the unit to OCCUPIED the day the contract
            // is signed, which for a lease starting next month is a reservation.
            java.util.Set<java.util.UUID> occupied = new java.util.HashSet<>(leaseRepository.unitIdsOccupiedOnInScope(
                    today, scope.unrestricted(), scope.propertyIds()));
            occupiedCount = occupied.size();
            reservedCount = leaseRepository.unitIdsReservedAfterInScope(
                    today, scope.unrestricted(), scope.propertyIds()).stream()
                    .filter(id -> !occupied.contains(id)).count();
        }
        long vacantCount = Math.max(0, totalUnits - occupiedCount - reservedCount - maintenanceCount);
        summary.setTotalUnits((int) totalUnits);
        summary.setOccupiedUnits((int) occupiedCount);
        summary.setReservedUnits((int) reservedCount);
        summary.setVacantUnits((int) vacantCount);
        // Of the units the caller can see. A manager's occupancy is their own
        // buildings' occupancy; averaging in the rest of the estate would tell them
        // nothing about the one thing they are answerable for.
        summary.setOccupancyRate(totalUnits == 0 ? 0.0
                : (double) occupiedCount / totalUnits * 100.0);

        // --- Leases ---
        long activeCount = 0;
        long draftCount = 0;
        BigDecimal totalRentRevenue = BigDecimal.ZERO;
        if (!scope.blocked()) {
            for (Object[] row : leaseRepository.countAndRentByStatusInScope(
                    scope.unrestricted(), scope.propertyIds())) {
                LeaseStatus status = (LeaseStatus) row[0];
                if (status == LeaseStatus.ACTIVE) {
                    activeCount = ((Number) row[1]).longValue();
                    // Contracted rent on live tenancies only: a draft is a proposal
                    // and a terminated one is over.
                    totalRentRevenue = nz((BigDecimal) row[2]);
                } else if (status == LeaseStatus.DRAFT) {
                    draftCount = ((Number) row[1]).longValue();
                }
            }
        }
        summary.setActiveLeases((int) activeCount);
        summary.setDraftLeases((int) draftCount);
        // Expiring = ACTIVE leases ending within 30 days, today included.
        summary.setExpiringLeases(scope.blocked() ? 0
                : (int) leaseRepository.countExpiringInScope(today, today.plusDays(30),
                        scope.unrestricted(), scope.propertyIds()));
        summary.setTotalRentRevenue(totalRentRevenue);

        // --- Financial (the cheque register) ---
        //
        // Cheques on unsigned leases and DRAFT grid rows are proposals rather than
        // money owed, and every query here excludes them. A property manager's
        // totals have to equal what they see on /api/v1/cheques for the same
        // properties, which is why the scope resolved above is the one used here.
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate nextMonthStart = monthStart.plusMonths(1);

        Map<ChequeStatus, BigDecimal> byStatus = new EnumMap<>(ChequeStatus.class);
        if (!scope.blocked()) {
            for (Object[] row : chequeRepository.totalsByStatus(null, scope.unrestricted(), scope.propertyIds())) {
                byStatus.put((ChequeStatus) row[0], nz((BigDecimal) row[2]));
            }
        }
        BigDecimal outstanding = BigDecimal.ZERO;
        for (ChequeStatus s : OUTSTANDING) {
            outstanding = outstanding.add(byStatus.getOrDefault(s, BigDecimal.ZERO));
        }

        summary.setCollectedAmount(byStatus.getOrDefault(ChequeStatus.CLEARED, BigDecimal.ZERO));
        summary.setPendingAmount(outstanding);
        // Maturing inside the current calendar month. Earlier unpaid instalments
        // are not here — they surface under "overdue".
        summary.setPendingThisMonthAmount(scope.blocked() ? BigDecimal.ZERO
                : nz(chequeRepository.sumByStatusInAndChequeDateBetween(
                        OUTSTANDING, monthStart, nextMonthStart,
                        scope.unrestricted(), scope.propertyIds())));

        // Overdue is ChequeDueRules over the register's due rows, not "past its
        // date": grace is a per-lease number and a dashboard that ignored it would
        // show a renter as late days before their own contract says they are.
        BigDecimal overdueAmount = BigDecimal.ZERO;
        if (!scope.blocked()) {
            List<Cheque> due = chequeRepository.findDue(null, today, scope.unrestricted(), scope.propertyIds(),
                    org.springframework.data.domain.Pageable.unpaged()).getContent();
            // F14-08: a bounced row counts only for the debt the ledger still carries.
            Map<java.util.UUID, BigDecimal> open = bouncedDebt.openAmounts(due);
            for (Cheque c : due) {
                Lease lease = c.getLease();
                if (ChequeDueRules.overdue(c, lease == null ? 0 : lease.getGracePeriodDays(), today)) {
                    overdueAmount = overdueAmount.add(open.get(c.getId()));
                }
            }
        }
        summary.setOverdueAmount(overdueAmount);

        // --- Cash actually banked this month / last month ---
        // By clearedAt, which is a date rather than a timestamp, so the old
        // UAE-timezone correction around month boundaries no longer applies:
        // "the day the money landed" is already the landlord's local day.
        summary.setReceivedThisMonth(scope.blocked() ? BigDecimal.ZERO
                : nz(chequeRepository.sumClearedBetween(monthStart, nextMonthStart, null,
                        scope.unrestricted(), scope.propertyIds())));
        summary.setReceivedLastMonth(scope.blocked() ? BigDecimal.ZERO
                : nz(chequeRepository.sumClearedBetween(monthStart.minusMonths(1), monthStart, null,
                        scope.unrestricted(), scope.propertyIds())));

        // --- The collection tile, on one basis (gap #59) ---
        // The old tile divided money cleared this month by money dated this month,
        // and one catch-up banking run read "139,550 of 3,667". The headline is now
        // this month's dues against what of them has come in; money that came in
        // for other months is reported beside it as arrears or advance. The split
        // is one query with sumClearedBetween's where clause, so the three parts
        // add up to receivedThisMonth by construction.
        BigDecimal dueThisMonth = BigDecimal.ZERO;
        BigDecimal collectedForThisMonth = BigDecimal.ZERO;
        BigDecimal againstDue = BigDecimal.ZERO;
        BigDecimal arrears = BigDecimal.ZERO;
        BigDecimal advance = BigDecimal.ZERO;
        if (!scope.blocked()) {
            for (Object[] row : chequeRepository.aggregateMonthly(monthStart, nextMonthStart,
                    scope.unrestricted(), scope.propertyIds())) {
                dueThisMonth = dueThisMonth.add(nz((BigDecimal) row[1]));
                // The headline: every CLEARED row dated this month, whenever it
                // cleared. A September instalment paid on 28 August is
                // September's money in the bank; counting only rows cleared in
                // September would leave a fully paid month short of 100%.
                collectedForThisMonth = collectedForThisMonth.add(nz((BigDecimal) row[2]));
            }
            List<Object[]> split = chequeRepository.sumClearedBetweenByDueWindow(monthStart, nextMonthStart,
                    scope.unrestricted(), scope.propertyIds());
            if (split != null && !split.isEmpty()) {
                Object[] row = split.getFirst();
                arrears = nz((BigDecimal) row[0]);
                againstDue = nz((BigDecimal) row[1]);
                advance = nz((BigDecimal) row[2]);
            }
        }
        summary.setDueThisMonth(dueThisMonth);
        summary.setCollectedForThisMonth(collectedForThisMonth);
        summary.setCollectedAgainstDueThisMonth(againstDue);
        summary.setCollectedArrears(arrears);
        summary.setCollectedAdvance(advance);

        // --- Recent Activity ---
        List<DashboardSummaryDTO.RecentActivityItem> activityItems = new ArrayList<>();
        if (!scope.blocked()) {
            for (Cheque c : chequeRepository.findRecentlyChanged(scope.unrestricted(), scope.propertyIds(),
                    org.springframework.data.domain.PageRequest.of(0, RECENT_ACTIVITY_ROWS))) {
                DashboardSummaryDTO.RecentActivityItem item = new DashboardSummaryDTO.RecentActivityItem();
                item.setType("PAYMENT_" + c.getStatus().name());
                item.setDescription(buildChequeDescription(c));
                item.setTimestamp(c.getStatusChangedAt().toString());
                item.setChequeStatus(c.getStatus().name());
                item.setChequeNumber(blankToNull(c.getChequeNumber()));
                item.setSeqNo(c.getSeqNo());
                item.setUnitNumber(c.getUnit() != null ? c.getUnit().getUnitNumber() : null);
                item.setPropertyName(c.getProperty() != null ? c.getProperty().getNameEn() : null);
                item.setAmount(nz(c.getAmount()));
                activityItems.add(item);
            }
        }
        summary.setRecentActivity(activityItems);

        return summary;
    }

    /**
     * Who is looking at the dashboard, resolved once.
     *
     * <p>The same three answers {@code ChequeQueryService} works from: everything,
     * these properties, or nothing. {@code blocked} is the last of those — a renter,
     * a tenant user, or a manager assigned to no building — and it short-circuits
     * every query rather than asking Postgres to match an empty {@code in} list.</p>
     */
    private Scope scope() {
        List<java.util.UUID> visible = leaseAccessPolicy.visiblePropertyIds();
        if (visible == null) {
            return new Scope(true, List.of(), false);
        }
        if (visible.isEmpty()) {
            return new Scope(false, List.of(), true);
        }
        return new Scope(false, visible, false);
    }

    private record Scope(boolean unrestricted, List<java.util.UUID> propertyIds, boolean blocked) {
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

        Scope scope = scope();
        Map<String, BigDecimal[]> byYm = new HashMap<>();
        for (Object[] row : scope.blocked() ? List.<Object[]>of()
                : chequeRepository.aggregateMonthly(from, toExclusive,
                        scope.unrestricted(), scope.propertyIds())) {
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

    /**
     * The English fallback line. It names the cheque by the number an accountant
     * would search for, falling back to its position on the schedule only when
     * the paper has no number, and groups the amount like every other screen
     * ("31,500.00", not "31500.00").
     */
    static String buildChequeDescription(Cheque c) {
        String unitNumber = c.getUnit() != null ? c.getUnit().getUnitNumber() : "N/A";
        String propertyName = c.getProperty() != null ? c.getProperty().getNameEn() : "N/A";
        String number = blankToNull(c.getChequeNumber());
        String ref = number != null ? "Cheque " + number : "Payment #" + c.getSeqNo();
        return String.format(Locale.ENGLISH, "%s %s - Unit %s, %s (AED %,.2f)",
                ref,
                c.getStatus().name().toLowerCase(),
                unitNumber,
                propertyName,
                nz(c.getAmount()));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
