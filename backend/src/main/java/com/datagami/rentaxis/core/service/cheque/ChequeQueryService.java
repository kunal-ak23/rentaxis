package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.api.dto.cheque.AgingReportDTO;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.cheque.ChequeSummaryDTO;
import com.datagami.rentaxis.api.dto.cheque.LeaseChequeStatsDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The cheque register's read side: the lists, the tiles and the aging report the
 * collection screens are made of (spec §7.4, §7.5).
 *
 * <p><b>Nothing here scans.</b> Every method goes through a bounded JPQL query or
 * an aggregate; the register is the largest table in the system for a landlord of
 * any size, and a {@code findAll()} behind a summary tile is a full scan on every
 * page load.</p>
 *
 * <p><b>"Due" and "overdue" are computed once.</b> {@code ChequeRepository.findDue}
 * is {@link ChequeDueRules#due} written in SQL, and every count in here is then
 * taken by asking the rule about the rows that query returned, with the grace
 * period from each row's own lease. Two implementations of "is this late" is how
 * a register screen ends up showing seven overdue cheques above a list of six.
 * Grace is per lease and Postgres cannot add a column of days to a date in
 * portable JPQL, so the rule runs in Java over a query-bounded set rather than
 * being half-expressed in SQL.</p>
 *
 * <p><b>A property manager is scoped inside the query</b>, never by filtering a
 * page the database already produced: that would report totals counting
 * buildings the caller may not see and hand back short pages. A manager assigned
 * to nothing is answered without a query at all, and an explicit
 * {@code propertyId} outside their set yields an empty result rather than data.</p>
 *
 * <p>Class-level {@code @Transactional(readOnly = true)}: {@code TenantAspect}
 * only enables the Hibernate tenant filter inside a transaction, so a read
 * outside one would cross tenants.</p>
 */
@Service
@Transactional(readOnly = true)
public class ChequeQueryService {

    /** What the register's lists are ordered by when the caller asks for nothing. */
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.asc("chequeDate"), Sort.Order.asc("seqNo"));

    private final ChequeRepository chequeRepository;
    private final LeaseAccessPolicy leaseAccessPolicy;
    private final BouncedDebt bouncedDebt;

    public ChequeQueryService(ChequeRepository chequeRepository, LeaseAccessPolicy leaseAccessPolicy,
                              BouncedDebt bouncedDebt) {
        this.chequeRepository = chequeRepository;
        this.leaseAccessPolicy = leaseAccessPolicy;
        this.bouncedDebt = bouncedDebt;
    }

    // ------------------------------------------------------------------
    // lists
    // ------------------------------------------------------------------

    /** The register table: every filter optional, paged, newest instalment last. */
    public Page<ChequeDTO> search(UUID propertyId, ChequeStatus status, ChequeMode mode,
                                  LocalDate from, LocalDate to, String search, Pageable pageable) {
        Scope scope = scope(propertyId);
        Pageable page = sorted(pageable);
        if (scope.blocked()) {
            return Page.empty(page);
        }
        return toPage(chequeRepository.search(propertyId, status, mode, from, to, term(search),
                scope.unrestricted(), scope.propertyIds(), page));
    }

    /** Matured and unpaid — the same predicate {@link ChequeDueRules#due} applies per row. */
    public Page<ChequeDTO> due(UUID propertyId, LocalDate today, Pageable pageable) {
        Scope scope = scope(propertyId);
        Pageable page = sorted(pageable);
        if (scope.blocked()) {
            return Page.empty(page);
        }
        return toPage(chequeRepository.findDue(propertyId, on(today),
                scope.unrestricted(), scope.propertyIds(), page));
    }

    /** The day's deposit run: matured, unbanked paper. */
    public Page<ChequeDTO> toDeposit(UUID propertyId, LocalDate today, Pageable pageable) {
        Scope scope = scope(propertyId);
        Pageable page = sorted(pageable);
        if (scope.blocked()) {
            return Page.empty(page);
        }
        return toPage(chequeRepository.findToDeposit(propertyId, on(today),
                scope.unrestricted(), scope.propertyIds(), page));
    }

    /** The post-dated book for one month, in maturity order. */
    public List<ChequeDTO> postDated(UUID propertyId, YearMonth month) {
        Scope scope = scope(propertyId);
        if (scope.blocked()) {
            return List.of();
        }
        YearMonth ym = month != null ? month : YearMonth.now();
        List<Cheque> rows = chequeRepository.findPostDated(propertyId, ym.atDay(1), ym.atEndOfMonth(),
                scope.unrestricted(), scope.propertyIds());
        return toDtos(rows);
    }

    /**
     * The post-dated book a page at a time (scale P1-3): {@code from}/{@code to} when
     * given, else the month (default the current one), in maturity order.
     */
    public Page<ChequeDTO> postDatedPaged(UUID propertyId, YearMonth month, LocalDate from, LocalDate to,
                                          int page, int size) {
        Scope scope = scope(propertyId);
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, MAX_PAGE_SIZE)), DEFAULT_SORT);
        if (scope.blocked()) {
            return Page.empty(pageable);
        }
        YearMonth ym = month != null ? month : YearMonth.now();
        LocalDate lo = from != null ? from : (to != null ? LocalDate.of(2000, 1, 1) : ym.atDay(1));
        LocalDate hi = to != null ? to : (from != null ? LocalDate.of(2099, 12, 31) : ym.atEndOfMonth());
        return toPage(chequeRepository.findPostDatedPaged(propertyId, lo, hi, scope.unrestricted(),
                scope.propertyIds(), pageable));
    }

    /** The largest page a paged register endpoint serves. */
    static final int MAX_PAGE_SIZE = 200;

    /**
     * One register row, if the caller is entitled to the lease behind it.
     *
     * <p>A {@code DRAFT} row answers "not found" here even to a caller who may see
     * the lease: it is a grid row, and the grid is read through
     * {@code GET /api/v1/leases/&#123;id&#125;/cheques}. Answering it from the
     * register would make a proposal look like an instrument someone is holding.</p>
     */
    public ChequeDTO get(UUID chequeId) {
        Cheque cheque = chequeRepository.findById(chequeId)
                .orElseThrow(() -> new NotFoundException("Cheque not found"));
        if (cheque.getStatus() == ChequeStatus.DRAFT) {
            throw new NotFoundException("Cheque not found");
        }
        Lease lease = cheque.getLease();
        // requireReadable, not requireManageable: looking at a receipt is not moving
        // its money, and it answers "not found" rather than "forbidden" so a caller
        // cannot enumerate the register through the error code.
        leaseAccessPolicy.requireReadable(lease);
        return ChequeMapper.toDto(cheque, LocalDate.now(), graceOf(lease),
                ledgerSettled(List.of(cheque)).contains(cheque.getId()));
    }

    // ------------------------------------------------------------------
    // tiles, aging, per-lease stats
    // ------------------------------------------------------------------

    /**
     * The summary tiles. Two queries: one aggregate grouped by status, and the due
     * rows the lateness tiles are counted over.
     */
    public ChequeSummaryDTO summary(UUID propertyId, LocalDate today) {
        Scope scope = scope(propertyId);
        if (scope.blocked()) {
            return new ChequeSummaryDTO(0, ZERO, 0, ZERO, ZERO, 0, ZERO, 0, ZERO, 0, ZERO);
        }
        LocalDate on = on(today);

        Map<ChequeStatus, Totals> byStatus = new EnumMap<>(ChequeStatus.class);
        for (Object[] row : chequeRepository.totalsByStatus(propertyId, scope.unrestricted(), scope.propertyIds())) {
            byStatus.put((ChequeStatus) row[0],
                    new Totals(((Number) row[1]).longValue(), amount(row[2])));
        }

        YearMonth month = YearMonth.from(on);
        BigDecimal clearedThisMonth = amount(chequeRepository.sumClearedBetween(
                month.atDay(1), month.plusMonths(1).atDay(1),
                propertyId, scope.unrestricted(), scope.propertyIds()));

        // F14-08 and the lateness rules, in one aggregate (scale P1-10): a bounced row
        // counts only for the debt the ledger still carries, and a row with nothing
        // open is not counted at all.
        ChequeRepository.DueTotals t = chequeRepository.dueTotals(tenantScope().tenantId(), tenantScope().allTenants(), on,
                propertyId, scope.unrestricted(), nonEmpty(scope.propertyIds()));
        long dueCount = t.getDueCount();
        BigDecimal dueAmount = amount(t.getDueAmount());
        long overdueCount = t.getOverdueCount();
        BigDecimal overdueAmount = amount(t.getOverdueAmount());

        Totals registered = byStatus.getOrDefault(ChequeStatus.REGISTERED, Totals.NONE);
        Totals deposited = byStatus.getOrDefault(ChequeStatus.DEPOSITED, Totals.NONE);
        Totals bounced = byStatus.getOrDefault(ChequeStatus.BOUNCED, Totals.NONE);
        return new ChequeSummaryDTO(
                registered.count(), registered.amount(),
                deposited.count(), deposited.amount(),
                clearedThisMonth,
                bounced.count(), bounced.amount(),
                dueCount, dueAmount,
                overdueCount, overdueAmount);
    }

    /**
     * The aging report: the due rows bucketed by how far past grace they are.
     *
     * <p>"Current" holds the due rows that are not late yet — money owed today on a
     * lease whose grace period has not run out. It is a bucket rather than an
     * omission because the report's total is "what is outstanding", and a renter
     * who is inside their grace still owes it.</p>
     */
    public AgingReportDTO aging(UUID propertyId, LocalDate today) {
        Scope scope = scope(propertyId);
        LocalDate on = on(today);
        List<BucketDef> defs = List.of(
                new BucketDef("Current", 0, 0),
                new BucketDef("1-30", 1, 30),
                new BucketDef("31-60", 31, 60),
                new BucketDef("61-90", 61, 90),
                new BucketDef("90+", 91, null));
        Map<String, List<AgingReportDTO.Row>> rowsByBucket = new LinkedHashMap<>();
        Map<String, BigDecimal> amountByBucket = new LinkedHashMap<>();
        for (BucketDef d : defs) {
            rowsByBucket.put(d.label(), new ArrayList<>());
            amountByBucket.put(d.label(), ZERO);
        }

        BigDecimal total = ZERO;
        long totalCount = 0;
        if (!scope.blocked()) {
            // One statement: the due rows with what of each is still open (F14-08),
            // their lateness and the names the report prints (scale P1-10).
            for (ChequeRepository.OpenDueRow r : chequeRepository.openDueRows(tenantScope().tenantId(), tenantScope().allTenants(), on,
                    propertyId, scope.unrestricted(), nonEmpty(scope.propertyIds()))) {
                BigDecimal amt = r.getOpenAmount();
                int days = r.getOverdue() ? r.getDaysOverdue() : 0;
                BucketDef bucket = defs.stream().filter(d -> d.holds(days)).findFirst().orElse(defs.getLast());
                rowsByBucket.get(bucket.label()).add(new AgingReportDTO.Row(r.getChequeId(), r.getLeaseId(),
                        r.getRenterName(), r.getPropertyName(), r.getUnitNumber(), r.getChequeNumber(),
                        r.getChequeDate(), amt, days));
                amountByBucket.merge(bucket.label(), amt, BigDecimal::add);
                total = total.add(amt);
                totalCount++;
            }
        }

        List<AgingReportDTO.Bucket> buckets = defs.stream()
                .map(d -> new AgingReportDTO.Bucket(d.label(), d.fromDays(), d.toDays(),
                        rowsByBucket.get(d.label()).size(),
                        amountByBucket.get(d.label()),
                        List.copyOf(rowsByBucket.get(d.label()))))
                .toList();
        return new AgingReportDTO(buckets, total, totalCount);
    }

    /**
     * Collection position for a batch of leases, in the order they were asked for.
     *
     * <p>A lease outside the caller's properties simply produces no row instead of
     * an error, so one id they are not entitled to does not blank the whole table.
     * The caller's scope is resolved once for the batch rather than asked per row —
     * a per-row {@code canRead} would re-read the manager's assignments for every
     * cheque on every lease.</p>
     */
    public List<LeaseChequeStatsDTO> statsByLeases(List<UUID> leaseIds, LocalDate today) {
        return statsByLeases(leaseIds, today, false);
    }

    /**
     * {@code includeDrafts}: draft and awaiting-signature contracts get a row too (their
     * DRAFT grid rows are still left out), so one call covers a renter's every contract.
     */
    public List<LeaseChequeStatsDTO> statsByLeases(List<UUID> leaseIds, LocalDate today, boolean includeDrafts) {
        if (leaseIds == null || leaseIds.isEmpty()) {
            return List.of();
        }
        if (leaseIds.size() > MAX_STATS_LEASES) {
            // An unbounded `in` list is a table's worth of rows behind one request.
            // The screen this serves renders a page of leases, so the cap is well
            // above anything it asks for and refusing is better than quietly
            // truncating a list the caller believes it got an answer for.
            throw new BusinessRuleViolationException(
                    "Ask for at most " + MAX_STATS_LEASES + " leases at a time; this request named "
                            + leaseIds.size() + ".");
        }
        Scope scope = scope(null);
        if (scope.blocked()) {
            return List.of();
        }
        LocalDate on = on(today);
        List<UUID> distinct = leaseIds.stream().distinct().toList();
        Map<UUID, Accumulator> byLease = new LinkedHashMap<>();
        for (Cheque c : includeDrafts ? chequeRepository.findRegisterRowsForLeasesIncludingDrafts(distinct)
                : chequeRepository.findRegisterRowsForLeases(distinct)) {
            Lease lease = c.getLease();
            if (lease == null || !scope.allows(c.getProperty())) {
                continue;
            }
            byLease.computeIfAbsent(lease.getId(), id -> new Accumulator()).add(c, graceOf(lease), on);
        }
        List<LeaseChequeStatsDTO> out = new ArrayList<>(distinct.size());
        for (UUID leaseId : distinct) {
            Accumulator a = byLease.get(leaseId);
            if (a != null) {
                out.add(a.toDto(leaseId));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    /** Leases one {@code stats-by-leases} call may name. */
    static final int MAX_STATS_LEASES = 200;

    /**
     * Who the caller is, resolved once.
     *
     * <p>{@code blocked} folds two cases the queries must never be asked about: a
     * caller scoped to nothing, and an explicit {@code propertyId} outside the set
     * they were assigned. Both answer empty. Letting the second through would hand
     * a manager another building's register the moment they typed its id.</p>
     */
    private Scope scope(UUID propertyId) {
        List<UUID> visible = leaseAccessPolicy.visiblePropertyIds();
        if (visible == null) {
            return new Scope(true, List.of(), false);
        }
        if (visible.isEmpty()) {
            return new Scope(false, List.of(), true);
        }
        return new Scope(false, visible, propertyId != null && !visible.contains(propertyId));
    }

    private record Scope(boolean unrestricted, List<UUID> propertyIds, boolean blocked) {

        /** For the one read that filters in Java because it is keyed by lease, not property. */
        boolean allows(Property property) {
            return unrestricted || (property != null && propertyIds.contains(property.getId()));
        }
    }

    private record Totals(long count, BigDecimal amount) {
        static final Totals NONE = new Totals(0, BigDecimal.ZERO);
    }

    private record BucketDef(String label, int fromDays, Integer toDays) {
        boolean holds(int days) {
            return days >= fromDays && (toDays == null || days <= toDays);
        }
    }

    /** One lease's running totals, folded row by row. */
    private static final class Accumulator {
        private long total;
        private long cleared;
        private long uncleared;
        private long bounced;
        private BigDecimal totalAmount = BigDecimal.ZERO;
        private BigDecimal clearedAmount = BigDecimal.ZERO;
        private BigDecimal dueAmount = BigDecimal.ZERO;
        private BigDecimal unclearedAmount = BigDecimal.ZERO;
        private long liveCount;
        private BigDecimal liveAmount = BigDecimal.ZERO;

        void add(Cheque c, int graceDays, LocalDate today) {
            BigDecimal amount = c.getAmount() == null ? BigDecimal.ZERO : c.getAmount();
            total++;
            totalAmount = totalAmount.add(amount);
            if (c.getStatus() == ChequeStatus.CLEARED) {
                cleared++;
                clearedAmount = clearedAmount.add(amount);
            }
            if (c.getStatus() != null && c.getStatus().isUncleared()) {
                uncleared++;
                unclearedAmount = unclearedAmount.add(amount);
            }
            // Scale #14: "Cheques total" is every live instrument — a replaced or cancelled
            // row was superseded, and counting it would count the same money twice.
            if (c.getStatus() != ChequeStatus.REPLACED && c.getStatus() != ChequeStatus.CANCELLED) {
                liveCount++;
                liveAmount = liveAmount.add(amount);
            }
            if (c.getBouncedAt() != null || c.getStatus() == ChequeStatus.BOUNCED) {
                bounced++;
            }
            if (ChequeDueRules.due(c, today)) {
                dueAmount = dueAmount.add(amount);
            }
        }

        LeaseChequeStatsDTO toDto(UUID leaseId) {
            return new LeaseChequeStatsDTO(leaseId, total, cleared, uncleared, bounced,
                    totalAmount, clearedAmount, dueAmount, unclearedAmount, liveCount, liveAmount);
        }
    }

    /**
     * The register's own order when the request carries none.
     *
     * <p>An unsorted page over a table this size is whatever order Postgres felt
     * like, which makes page 2 overlap page 1. Schedule order — maturity, then
     * position within the lease — is what the screen reads as a collection list.</p>
     */
    private static Pageable sorted(Pageable pageable) {
        if (pageable == null) {
            return PageRequest.of(0, 20, DEFAULT_SORT);
        }
        if (pageable.isUnpaged() || pageable.getSort().isSorted()) {
            return pageable;
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), DEFAULT_SORT);
    }

    /** {@code "%term%"}, lowercased — the shape {@code ChequeRepository.search} expects. */
    private static String term(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        return "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
    }

    private static LocalDate on(LocalDate today) {
        return today != null ? today : LocalDate.now();
    }

    private static BigDecimal amount(Object value) {
        return value instanceof BigDecimal b ? b : BigDecimal.ZERO;
    }

    private static BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * A native {@code in (:ids)} cannot take an empty list; an unrestricted caller's list is
     * empty and ignored by the query, so it gets a sentinel no property has.
     */
    public static Collection<UUID> nonEmpty(Collection<UUID> ids) {
        return ids == null || ids.isEmpty() ? List.of(new UUID(0L, 0L)) : ids;
    }

    /** Grace comes from the row's own lease; a detached row is treated as having none. */
    private static int graceOf(Lease lease) {
        return lease == null ? 0 : lease.getGracePeriodDays();
    }

    private Page<ChequeDTO> toPage(Page<Cheque> page) {
        LocalDate today = LocalDate.now();
        java.util.Set<UUID> settled = ledgerSettled(page.getContent());
        return new PageImpl<>(
                page.getContent().stream()
                        .map(c -> ChequeMapper.toDto(c, today, graceOf(c.getLease()), settled.contains(c.getId()))).toList(),
                page.getPageable(), page.getTotalElements());
    }

    private List<ChequeDTO> toDtos(Collection<Cheque> rows) {
        LocalDate today = LocalDate.now();
        java.util.Set<UUID> settled = ledgerSettled(rows);
        return rows.stream()
                .map(c -> ChequeMapper.toDto(c, today, graceOf(c.getLease()), settled.contains(c.getId()))).toList();
    }

    /**
     * F14-52: the BOUNCED rows among {@code rows} whose debt the ledger no longer
     * carries — the same derivation the summary, aging and dashboard use (F14-08):
     * every bounced row of the lease shares its receivable balance, newest first.
     */
    public java.util.Set<UUID> ledgerSettled(Collection<Cheque> rows) {
        java.util.Set<UUID> leaseIds = rows.stream()
                .filter(c -> c.getStatus() == ChequeStatus.BOUNCED && c.getLease() != null)
                .map(c -> c.getLease().getId()).collect(java.util.stream.Collectors.toSet());
        if (leaseIds.isEmpty()) return java.util.Set.of();
        List<Cheque> bounced = chequeRepository.findRegisterRowsForLeases(leaseIds).stream()
                .filter(c -> c.getStatus() == ChequeStatus.BOUNCED).toList();
        java.util.Set<UUID> out = new java.util.HashSet<>();
        bouncedDebt.openAmounts(bounced).forEach((id, open) -> { if (open.signum() <= 0) out.add(id); });
        return out;
    }

    /** Whose rows the register's SQL reads: the caller's organisation, or all of them for a SUPER_ADMIN with none selected. */
    private static com.datagami.rentaxis.core.util.Search.TenantScope tenantScope() {
        return com.datagami.rentaxis.core.util.Search.tenantOrSuperAdmin();
    }
}
