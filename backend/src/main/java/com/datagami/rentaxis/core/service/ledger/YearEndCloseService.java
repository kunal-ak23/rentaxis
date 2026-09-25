package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.dto.ledger.FiscalYearDTO;
import com.datagami.rentaxis.api.dto.ledger.YearClosePreviewDTO;
import com.datagami.rentaxis.api.dto.ledger.YearClosePreviewDTO.Issue;
import com.datagami.rentaxis.api.dto.ledger.YearClosePreviewDTO.PnlLine;
import com.datagami.rentaxis.api.dto.ledger.YearClosePreviewDTO.RetainedLine;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.ledger.PostingRequest.ById;
import com.datagami.rentaxis.core.service.ledger.PostingRequest.ByRole;
import com.datagami.rentaxis.core.service.ledger.PostingRequest.Dimensions;
import com.datagami.rentaxis.core.service.ledger.PostingRequest.Line;
import com.datagami.rentaxis.core.service.ledger.PostingRequest.Side;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.FiscalYearClose;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.TenantFiscalSettings;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.FiscalYearCloseStatus;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.repository.FiscalYearCloseRepository;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Fiscal year-end close (spec 2026-09-24 §3, #53).
 *
 * <p><b>Close</b> posts one {@code YEC} per tenant and year, dated the year's last
 * day: every income and expense balance, by account and property, is moved to
 * Retained Earnings — one account, one line per property (product decision). The
 * balances are cumulative through year end, which equals the year's own activity
 * because earlier years are closed first (or, in the first year, includes the
 * opening balance's P&L — spec edge case). Then the period lock moves to year end.</p>
 *
 * <p><b>Re-open</b> reverses the YEC on the same date (the trial balance as of year
 * end is restored exactly) and moves the lock back to the day before the year —
 * the only backwards move the lock allows. Only the latest closed year re-opens.</p>
 *
 * <p>Every query binds {@code tenant_id} explicitly: native SQL bypasses the
 * Hibernate filter.</p>
 */
@Service
public class YearEndCloseService {

    private final FiscalYearCloseRepository closes;
    private final TenantFiscalSettingsService fiscal;
    private final PostingService posting;
    private final JournalEntryRepository journals;
    private final NamedParameterJdbcTemplate jdbc;

    public YearEndCloseService(FiscalYearCloseRepository closes, TenantFiscalSettingsService fiscal,
                               PostingService posting, JournalEntryRepository journals,
                               NamedParameterJdbcTemplate jdbc) {
        this.closes = closes;
        this.fiscal = fiscal;
        this.posting = posting;
        this.journals = journals;
        this.jdbc = jdbc;
    }

    /** A fiscal year's dates, from the tenant's start month; labelled by the year it starts in. */
    public record Period(int fiscalYear, LocalDate start, LocalDate end) {
    }

    // Not read-only: fiscal.get() creates the settings row on a tenant's first access.
    @Transactional
    public Period periodOf(int fiscalYear) {
        int startMonth = fiscal.get().getFiscalYearStartMonth();
        LocalDate start = LocalDate.of(fiscalYear, startMonth, 1);
        return new Period(fiscalYear, start, start.plusYears(1).minusDays(1));
    }

    // ------------------------------------------------------------------
    // list
    // ------------------------------------------------------------------

    /** Every year from the first with a journal to the current one, newest first. */
    @Transactional
    public List<FiscalYearDTO> list(LocalDate today) {
        UUID tenant = tenant();
        LocalDate first = jdbc.queryForObject(
                "select min(entry_date) from journal_entries where tenant_id = :t", params(tenant), LocalDate.class);
        int to = fiscal.fiscalYearOf(today);
        int from = first == null ? to : Math.min(fiscal.fiscalYearOf(first), to);
        Map<Integer, FiscalYearClose> latest = new HashMap<>();
        for (FiscalYearClose c : closes.findAllByOrderByFiscalYearDescClosedAtDesc()) {
            latest.putIfAbsent(c.getFiscalYear(), c);
        }
        List<FiscalYearDTO> out = new ArrayList<>();
        for (int fy = to; fy >= from; fy--) {
            Period p = periodOf(fy);
            FiscalYearClose c = latest.get(fy);
            List<PnlLine> pnl = pnlLines(tenant, p.start(), p.end());
            BigDecimal result = sum(pnl, "INCOME").subtract(sum(pnl, "EXPENSE"));
            String number = c == null || c.getJournalId() == null ? null
                    : journals.findById(c.getJournalId()).map(JournalEntry::getEntryNumber).orElse(null);
            out.add(new FiscalYearDTO(fy, p.start(), p.end(), c == null ? "OPEN" : c.getStatus().name(),
                    result, c == null ? null : c.getJournalId(), number,
                    c == null ? null : c.getClosedAt(), c == null ? null : c.getClosedBy(),
                    c == null ? null : c.getReopenedAt(), c == null ? null : c.getReopenedBy(),
                    c == null ? null : c.getReopenReason()));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // preview
    // ------------------------------------------------------------------

    @Transactional
    public YearClosePreviewDTO preview(int fiscalYear, LocalDate today) {
        return build(tenant(), periodOf(fiscalYear), today);
    }

    private YearClosePreviewDTO build(UUID tenant, Period p, LocalDate today) {
        List<Issue> blockers = new ArrayList<>();
        List<Issue> warnings = new ArrayList<>();
        TenantFiscalSettings settings = fiscal.get();
        String fy = String.valueOf(p.fiscalYear());
        String end = p.end().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));

        if (closes.findClosed(p.fiscalYear()).isPresent()) {
            blockers.add(issue("alreadyClosed", "Fiscal year " + fy + " is already closed.", Map.of("year", fy)));
        }
        if (!p.end().isBefore(today)) {
            blockers.add(issue("notEnded", "Fiscal year " + fy + " ends on " + end + "; it can be closed from the day after.",
                    Map.of("year", fy, "end", end)));
        }
        // Years close in order. A previous year with no income or expense on or
        // before its end has nothing to close (e.g. one holding only an opening
        // balance of balance-sheet accounts), so it does not have to be closed first.
        LocalDate previousEnd = p.start().minusDays(1);
        if (hasPnlThrough(tenant, previousEnd) && closes.findClosed(p.fiscalYear() - 1).isEmpty()) {
            blockers.add(issue("previousOpen", "Close fiscal year " + (p.fiscalYear() - 1) + " first; years close in order.",
                    Map.of("year", String.valueOf(p.fiscalYear() - 1))));
        }
        long planned = count("""
                select count(*) from recognition_entries
                where tenant_id = :t and status = 'PLANNED' and period_end <= :end""", tenant, p.end());
        if (planned > 0) {
            blockers.add(issue("recognitionPending", planned + " recognition period(s) ending on or before " + end
                    + " are not posted yet; run month-end recognition to year end first.",
                    Map.of("count", String.valueOf(planned), "end", end)));
        }
        long vtp = count("""
                select count(*) from vat_tax_points
                where tenant_id = :t and status = 'PLANNED' and tax_point_date <= :end""", tenant, p.end());
        if (vtp > 0) {
            blockers.add(issue("vatPending", vtp + " VAT tax point(s) dated on or before " + end
                    + " are not declared yet; post them first.", Map.of("count", String.valueOf(vtp), "end", end)));
        }
        long drafts = jdbc.queryForObject("""
                select count(*) from import_batches where tenant_id = :t and status = 'DRAFT'""", params(tenant), Long.class);
        if (drafts > 0) {
            blockers.add(issue("importDraft", "A cut-over import batch is still in draft; post or discard it first.",
                    Map.of("count", String.valueOf(drafts))));
        }
        long draftVouchers = jdbc.queryForObject("""
                select count(*) from vouchers where tenant_id = :t and status = 'DRAFT' and doc_date between :start and :end""",
                params(tenant).addValue("start", p.start()).addValue("end", p.end()), Long.class);
        if (draftVouchers > 0) {
            warnings.add(issue("draftVouchers", draftVouchers + " draft voucher(s) are dated inside the year; closing"
                    + " will lock them out of it.", Map.of("count", String.valueOf(draftVouchers))));
        }

        List<PnlLine> lines = pnlLines(tenant, p.start(), p.end());
        BigDecimal income = sum(lines, "INCOME");
        BigDecimal expense = sum(lines, "EXPENSE");
        // F15-01: the year's own result per property, and what earlier open years
        // bring forward, apart — never one cumulative figure beside the year's lines.
        List<RetainedLine> retained = retainedByProperty(closingGroups(tenant, p.end()),
                closingGroups(tenant, p.start().minusDays(1)));
        LocalDate lockBefore = settings.getBooksLockedThrough();
        LocalDate lockAfter = lockBefore != null && lockBefore.isAfter(p.end()) ? lockBefore : p.end();
        return new YearClosePreviewDTO(p.fiscalYear(), p.start(), p.end(), blockers, warnings, lines,
                income, expense, income.subtract(expense), retained, lockBefore, lockAfter);
    }

    // ------------------------------------------------------------------
    // close
    // ------------------------------------------------------------------

    /** Post the YEC (when there is P&L to close), lock the year, and record the close. */
    @Transactional
    public FiscalYearDTO close(int fiscalYear, boolean overrideWarnings, LocalDate today) {
        UUID tenant = tenant();
        fiscal.lockRow();
        Period p = periodOf(fiscalYear);
        YearClosePreviewDTO preview = build(tenant, p, today);
        if (!preview.blockers().isEmpty()) {
            throw new BusinessRuleViolationException(preview.blockers().get(0).message(),
                    "fiscalYear." + preview.blockers().get(0).code(), preview.blockers().get(0).args());
        }
        if (!preview.warnings().isEmpty() && !overrideWarnings) {
            throw new BusinessRuleViolationException(preview.warnings().get(0).message()
                    + " Confirm to close anyway.", "fiscalYear." + preview.warnings().get(0).code(),
                    preview.warnings().get(0).args());
        }

        FiscalYearClose row = new FiscalYearClose();
        row.setTenantId(tenant);
        row.setFiscalYear(fiscalYear);
        row.setPeriodStart(p.start());
        row.setPeriodEnd(p.end());
        row.setStatus(FiscalYearCloseStatus.CLOSED);
        row.setLockBefore(preview.lockBefore());
        row.setClosedBy(currentUserId());
        row.setClosedAt(Instant.now());
        row = closes.saveAndFlush(row);

        List<Line> lines = closingLines(closingGroups(tenant, p.end()));
        if (!lines.isEmpty()) {
            JournalEntry yec = posting.post(new PostingRequest(JournalDocType.YEC, p.end(),
                    "Year-end close FY " + fiscalYear, Dimensions.none(), JournalSourceType.YEAR_END, row.getId(),
                    null, lines));
            row.setJournalId(yec.getId());
        }
        if (preview.lockBefore() == null || preview.lockBefore().isBefore(p.end())) {
            fiscal.lockThrough(p.end());
        }
        closes.save(row);
        return list(today).stream().filter(y -> y.fiscalYear() == fiscalYear).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------
    // re-open
    // ------------------------------------------------------------------

    /**
     * Reverse the year's YEC on its own date and move the lock back to the day
     * before the year (never before the books start). Only the latest closed year.
     */
    @Transactional
    public FiscalYearDTO reopen(int fiscalYear, String reason, LocalDate today) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleViolationException("A reason is required to re-open a fiscal year.",
                    "fiscalYear.reasonRequired", Map.of());
        }
        fiscal.lockRow();
        FiscalYearClose row = closes.findClosed(fiscalYear).orElseThrow(() ->
                new NotFoundException("Fiscal year " + fiscalYear + " is not closed"));
        boolean laterClosed = closes.findAllByOrderByFiscalYearDescClosedAtDesc().stream()
                .anyMatch(c -> c.getStatus() == FiscalYearCloseStatus.CLOSED && c.getFiscalYear() > fiscalYear);
        if (laterClosed) {
            throw new BusinessRuleViolationException("Re-open the later closed year first; only the latest closed year"
                    + " can be re-opened.", "fiscalYear.laterClosed", Map.of("year", String.valueOf(fiscalYear)));
        }
        if (row.getJournalId() != null) {
            posting.reverse(row.getJournalId(), row.getPeriodEnd(), "Fiscal year " + fiscalYear + " re-opened: " + reason.trim());
        }
        LocalDate to = row.getPeriodStart().minusDays(1);
        LocalDate booksStart = fiscal.booksStartDate();
        if (booksStart != null && to.isBefore(booksStart.minusDays(1))) {
            // Never unlock the pre-cut-over period: it holds the opening balance and imports.
            to = booksStart.minusDays(1);
        }
        fiscal.reopenTo(to);
        row.setStatus(FiscalYearCloseStatus.REOPENED);
        row.setReopenedBy(currentUserId());
        row.setReopenedAt(Instant.now());
        row.setReopenReason(reason.trim().length() > 500 ? reason.trim().substring(0, 500) : reason.trim());
        closes.save(row);
        return list(today).stream().filter(y -> y.fiscalYear() == fiscalYear).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------
    // the arithmetic
    // ------------------------------------------------------------------

    /** Σ(debit − credit) of one income/expense account on one property through year end. */
    record Group(UUID accountId, UUID propertyId, BigDecimal net) {
    }

    /**
     * Cumulative income and expense balances through {@code end}, by account and
     * effective property (the line's, else the account's) — the P&L report's own
     * rule, so the property split of Retained Earnings matches the property P&L.
     */
    private List<Group> closingGroups(UUID tenant, LocalDate end) {
        return jdbc.query("""
                select l.account_id as account_id, coalesce(l.property_id, a.property_id) as property_id,
                       coalesce(sum(l.debit), 0) - coalesce(sum(l.credit), 0) as net
                from journal_lines l
                     join journal_entries e on e.id = l.journal_entry_id
                     join accounts a on a.id = l.account_id
                where l.tenant_id = :t and e.tenant_id = :t and e.entry_date <= :end
                  and a.account_type in ('INCOME', 'EXPENSE')
                group by l.account_id, coalesce(l.property_id, a.property_id)
                having coalesce(sum(l.debit), 0) - coalesce(sum(l.credit), 0) <> 0
                order by 1, 2""",
                params(tenant).addValue("end", end),
                (rs, i) -> new Group(rs.getObject("account_id", UUID.class), rs.getObject("property_id", UUID.class),
                        rs.getBigDecimal("net")));
    }

    /** The YEC's lines: each balance reversed on its own account and property, then Retained Earnings per property. */
    static List<Line> closingLines(List<Group> groups) {
        List<Line> lines = new ArrayList<>();
        Map<UUID, BigDecimal> profitByProperty = new LinkedHashMap<>();
        for (Group g : groups) {
            BigDecimal net = g.net().setScale(2, java.math.RoundingMode.HALF_UP);
            if (net.signum() == 0) continue;
            Side side = net.signum() > 0 ? Side.CR : Side.DR;
            lines.add(new Line(new ById(g.accountId()), side, net.abs(), Dimensions.ofProperty(g.propertyId()),
                    "Year-end close", PostingRequest.NO_PAIR, true));
            profitByProperty.merge(g.propertyId(), net.negate(), BigDecimal::add);
        }
        profitByProperty.forEach((property, profit) -> {
            if (profit.signum() == 0) return;
            lines.add(new Line(new ByRole(AccountRole.RETAINED_EARNINGS), profit.signum() > 0 ? Side.CR : Side.DR,
                    profit.abs(), Dimensions.ofProperty(property),
                    profit.signum() > 0 ? "Profit for the year" : "Loss for the year", PostingRequest.NO_PAIR, true));
        });
        return lines;
    }

    private List<RetainedLine> retainedByProperty(List<Group> throughEnd, List<Group> beforeStart) {
        Map<UUID, BigDecimal> cumulative = new LinkedHashMap<>();
        for (Group g : throughEnd) cumulative.merge(g.propertyId(), g.net().negate(), BigDecimal::add);
        Map<UUID, BigDecimal> earlier = new LinkedHashMap<>();
        for (Group g : beforeStart) earlier.merge(g.propertyId(), g.net().negate(), BigDecimal::add);
        java.util.Set<UUID> keys = new java.util.LinkedHashSet<>(cumulative.keySet());
        keys.addAll(earlier.keySet());
        Map<UUID, String> names = propertyNames(keys);
        return keys.stream()
                .map(k -> {
                    BigDecimal all = cumulative.getOrDefault(k, BigDecimal.ZERO);
                    BigDecimal bf = earlier.getOrDefault(k, BigDecimal.ZERO);
                    return new RetainedLine(k, k == null ? null : names.get(k),
                            all.subtract(bf).setScale(2, java.math.RoundingMode.HALF_UP),
                            bf.setScale(2, java.math.RoundingMode.HALF_UP));
                })
                .filter(r -> r.profit().signum() != 0 || r.broughtForward().signum() != 0)
                .sorted(Comparator.comparing(r -> r.propertyName() == null ? "" : r.propertyName()))
                .toList();
    }

    /** The year's own P&L by account and property, the closing entry excluded (a closed year's P&L is never zero). */
    private List<PnlLine> pnlLines(UUID tenant, LocalDate start, LocalDate end) {
        List<PnlLine> raw = jdbc.query("""
                select a.id as account_id, a.code, a.name, a.name_ar, a.account_type,
                       coalesce(l.property_id, a.property_id) as property_id,
                       coalesce(sum(l.debit), 0) - coalesce(sum(l.credit), 0) as net
                from journal_lines l
                     join journal_entries e on e.id = l.journal_entry_id
                     join accounts a on a.id = l.account_id
                where l.tenant_id = :t and e.tenant_id = :t and e.entry_date between :start and :end
                  and a.account_type in ('INCOME', 'EXPENSE') and e.doc_type <> 'YEC'
                group by a.id, a.code, a.name, a.name_ar, a.account_type, coalesce(l.property_id, a.property_id)
                having coalesce(sum(l.debit), 0) - coalesce(sum(l.credit), 0) <> 0
                order by a.code""",
                params(tenant).addValue("start", start).addValue("end", end),
                (rs, i) -> {
                    String type = rs.getString("account_type");
                    BigDecimal net = rs.getBigDecimal("net");
                    return new PnlLine(rs.getObject("account_id", UUID.class), rs.getString("code"), rs.getString("name"),
                            rs.getString("name_ar"), type, rs.getObject("property_id", UUID.class), null,
                            ("INCOME".equals(type) ? net.negate() : net).setScale(2, java.math.RoundingMode.HALF_UP));
                });
        Map<UUID, String> names = propertyNames(raw.stream().map(PnlLine::propertyId).filter(Objects::nonNull).toList());
        return raw.stream().map(l -> new PnlLine(l.accountId(), l.code(), l.name(), l.nameAr(), l.accountType(),
                l.propertyId(), l.propertyId() == null ? null : names.get(l.propertyId()), l.amount())).toList();
    }

    private Map<UUID, String> propertyNames(java.util.Collection<UUID> ids) {
        List<UUID> list = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (list.isEmpty()) return Map.of();
        Map<UUID, String> out = new HashMap<>();
        jdbc.query("select id, name_en from properties where tenant_id = :t and id in (:ids)",
                params(tenant()).addValue("ids", list),
                rs -> { out.put(rs.getObject("id", UUID.class), rs.getString("name_en")); });
        return out;
    }

    private boolean hasPnlThrough(UUID tenant, LocalDate end) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from journal_lines l
                               join journal_entries e on e.id = l.journal_entry_id
                               join accounts a on a.id = l.account_id
                               where l.tenant_id = :t and e.tenant_id = :t and e.entry_date <= :end
                                 and a.account_type in ('INCOME', 'EXPENSE'))""",
                params(tenant).addValue("end", end), Boolean.class));
    }

    private long count(String sql, UUID tenant, LocalDate end) {
        Long n = jdbc.queryForObject(sql, params(tenant).addValue("end", end), Long.class);
        return n == null ? 0 : n;
    }

    private static BigDecimal sum(List<PnlLine> lines, String type) {
        return lines.stream().filter(l -> type.equals(l.accountType())).map(PnlLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static Issue issue(String code, String message, Map<String, String> args) {
        return new Issue(code, message, args);
    }

    private static MapSqlParameterSource params(UUID tenant) {
        return new MapSqlParameterSource("t", tenant);
    }

    private static UUID tenant() {
        UUID t = TenantContextHolder.getTenantId();
        if (t == null) throw new IllegalStateException("No tenant in context");
        return t;
    }

    private static UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof String s)) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}
