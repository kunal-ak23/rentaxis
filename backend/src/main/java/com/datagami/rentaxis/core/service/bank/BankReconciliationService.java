package com.datagami.rentaxis.core.service.bank;

import com.datagami.rentaxis.api.dto.bank.BankRecDTOs;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.ledger.BankLockService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The reconciliation statement, finalize and reopen (finance-ops spec §4).
 *
 * <pre>
 * Balance per bank statement at <to>
 * + deposits in transit          (book debits not on a statement line dated ≤ to)
 * − unpresented payments         (book credits not on a statement line dated ≤ to)
 * − booked after the period      (statement lines ≤ to matched to book items dated > to)
 * = adjusted bank balance
 * Balance per books at <to>      (Σ over the leaf set, all entries ≤ to, OB included)
 * ± unrecorded statement items   (lines in the period in no confirmed match)
 * = adjusted book balance
 * Difference = adjusted bank − adjusted book
 * </pre>
 *
 * <p>Book items are the journal lines on the leaf set dated from the
 * reconciliation start (the first reconciliation's {@code period_from}) through
 * {@code to}, except OB, plus the opening items. A book item matched (CONFIRMED)
 * with a statement line dated ≤ {@code to} is not outstanding; nor is one in a
 * confirmed contra match whose items all fall on or before {@code to}.</p>
 *
 * <p>Periods chain: a reconciliation starts the day after the latest finalized
 * one ended, and its statement opening is that one's closing. Finalize locks the
 * bank account through {@code period_to} ({@link BankLockService}).</p>
 *
 * <p>Native SQL with an explicit {@code tenant_id} throughout, inside a
 * transaction, as the rest of the bank package.</p>
 */
@Service
public class BankReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(BankReconciliationService.class);
    static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final BankAccountLedgerService ledgers;
    private final BankMatchService matches;
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantFiscalSettingsService fiscal;
    private final Clock clock;
    private final ObjectMapper json;

    public BankReconciliationService(BankAccountLedgerService ledgers, BankMatchService matches,
                                     NamedParameterJdbcTemplate jdbc, TenantFiscalSettingsService fiscal, Clock clock,
                                     ObjectMapper json) {
        this.ledgers = ledgers;
        this.matches = matches;
        this.jdbc = jdbc;
        this.fiscal = fiscal;
        this.clock = clock;
        this.json = json;
    }

    // ------------------------------------------------------------------ rows

    /** A bank_reconciliations row. While DRAFT, {@code opening}/{@code closing} are the typed balances (null = derived). */
    record Rec(UUID id, UUID bankAccountId, LocalDate from, LocalDate to, String status, BigDecimal opening,
               BigDecimal closing, BigDecimal bookBalance, BigDecimal difference, String snapshot, UUID createdBy,
               Instant createdAt, UUID finalizedBy, Instant finalizedAt, UUID reopenedBy, Instant reopenedAt,
               String reopenReason) { }

    private static Rec rec(ResultSet rs) throws SQLException {
        return new Rec(rs.getObject("id", UUID.class), rs.getObject("bank_account_id", UUID.class),
                rs.getObject("period_from", LocalDate.class), rs.getObject("period_to", LocalDate.class),
                rs.getString("status"), rs.getBigDecimal("statement_opening"), rs.getBigDecimal("statement_closing"),
                rs.getBigDecimal("book_balance"), rs.getBigDecimal("difference"), rs.getString("snapshot"),
                rs.getObject("created_by", UUID.class), instant(rs.getTimestamp("created_at")),
                rs.getObject("finalized_by", UUID.class), instant(rs.getTimestamp("finalized_at")),
                rs.getObject("reopened_by", UUID.class), instant(rs.getTimestamp("reopened_at")),
                rs.getString("reopen_reason"));
    }

    private static final String REC_COLUMNS = """
            id, bank_account_id, period_from, period_to, status, statement_opening, statement_closing, book_balance,
            difference, snapshot::text as snapshot, created_by, created_at, finalized_by, finalized_at, reopened_by,
            reopened_at, reopen_reason""" + " ";

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    /** The bank account's own row, as the statement's header needs it. */
    record Bank(UUID id, String bankName, String accountNumber, String iban, LocalDate recStartDate,
                LocalDate reconciledThrough) {
        String label() {
            return BankLockService.label(bankName, accountNumber);
        }
    }

    private Bank bank(UUID t, UUID bankAccountId) {
        List<Bank> b = jdbc.query("""
                select id, bank_name, account_number, iban, rec_start_date, reconciled_through
                from bank_accounts where id = :b and tenant_id = :t""",
                new MapSqlParameterSource("t", t).addValue("b", bankAccountId),
                (rs, i) -> new Bank(rs.getObject("id", UUID.class), rs.getString("bank_name"),
                        rs.getString("account_number"), rs.getString("iban"),
                        rs.getObject("rec_start_date", LocalDate.class), rs.getObject("reconciled_through", LocalDate.class)));
        if (b.isEmpty()) throw new NotFoundException("Bank account not found");
        return b.get(0);
    }

    private Rec load(UUID t, UUID recId, boolean forUpdate) {
        List<Rec> r = jdbc.query("select " + REC_COLUMNS + " from bank_reconciliations where id = :id and tenant_id = :t"
                        + (forUpdate ? " for update" : ""),
                new MapSqlParameterSource("t", t).addValue("id", recId), (rs, i) -> rec(rs));
        if (r.isEmpty()) throw new NotFoundException("Reconciliation not found");
        return r.get(0);
    }

    /** The latest FINALIZED reconciliation of the bank account, other than {@code except}. */
    private Optional<Rec> latestFinalized(UUID t, UUID bankAccountId, UUID except) {
        return jdbc.query("select " + REC_COLUMNS + """
                         from bank_reconciliations where tenant_id = :t and bank_account_id = :b and status = 'FINALIZED'
                          and (cast(:x as uuid) is null or id <> :x)
                        order by period_to desc limit 1""",
                new MapSqlParameterSource("t", t).addValue("b", bankAccountId).addValue("x", except),
                (rs, i) -> rec(rs)).stream().findFirst();
    }

    private Optional<Rec> draftOf(UUID t, UUID bankAccountId) {
        return jdbc.query("select " + REC_COLUMNS + " from bank_reconciliations where tenant_id = :t and bank_account_id = :b and status = 'DRAFT'",
                new MapSqlParameterSource("t", t).addValue("b", bankAccountId), (rs, i) -> rec(rs)).stream().findFirst();
    }

    // ------------------------------------------------------------------ list, create, edit

    @Transactional(readOnly = true)
    public List<BankRecDTOs.ReconciliationRow> list(UUID bankAccountId) {
        UUID t = BankAccountLedgerService.requireTenant();
        ledgers.requireBankAccount(bankAccountId);
        return jdbc.query("select " + REC_COLUMNS + """
                         from bank_reconciliations where tenant_id = :t and bank_account_id = :b
                        order by period_to desc, created_at desc""",
                new MapSqlParameterSource("t", t).addValue("b", bankAccountId), (rs, i) -> rec(rs)).stream()
                .map(r -> new BankRecDTOs.ReconciliationRow(r.id(), r.from(), r.to(), r.status(),
                        "DRAFT".equals(r.status()) ? null : r.closing(), r.bookBalance(), r.difference(), r.createdAt(),
                        r.finalizedAt(), userName(r.finalizedBy()), r.reopenedAt(), userName(r.reopenedBy()),
                        r.reopenReason()))
                .toList();
    }

    /**
     * A DRAFT for the next period. The first one of a bank account starts on any
     * day on or after the books start date; every later one starts the day after
     * the latest finalized one ended, with that one's closing as its opening.
     */
    @Transactional
    public BankRecDTOs.Reconciliation create(UUID bankAccountId, BankRecDTOs.ReconciliationInput in) {
        UUID t = BankAccountLedgerService.requireTenant();
        ledgers.requireBankAccount(bankAccountId);
        matches.lockAccount(t, bankAccountId);
        ledgers.requireLeafSet(bankAccountId);
        if (in == null || in.periodTo() == null) throw new BusinessRuleViolationException("Give the period's last day");
        draftOf(t, bankAccountId).ifPresent(d -> {
            throw new BusinessRuleViolationException("A draft reconciliation for " + d.from().format(DMY) + " – "
                    + d.to().format(DMY) + " is open; finalize or discard it first");
        });
        Optional<Rec> prev = latestFinalized(t, bankAccountId, null);
        LocalDate from = startOf(prev, in);
        validatePeriod(from, in.periodTo());
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into bank_reconciliations (id, tenant_id, bank_account_id, period_from, period_to, statement_opening,
                                                  statement_closing, status, created_by, created_at)
                values (:id, :t, :b, :from, :to, :opening, :closing, 'DRAFT', :u, now())""",
                new MapSqlParameterSource("id", id).addValue("t", t).addValue("b", bankAccountId).addValue("from", from)
                        .addValue("to", in.periodTo())
                        .addValue("opening", prev.isEmpty() ? money(in.statementOpening()) : null)
                        .addValue("closing", money(in.statementClosing()))
                        .addValue("u", BankStatementImportService.currentUserId()));
        // The fast-path flag (BankLockService): from now on every posting of this
        // tenant checks the bank lock. Set a request before any finalize can run.
        fiscal.get();
        jdbc.update("update tenant_fiscal_settings set bank_rec_started = true where tenant_id = :t and not bank_rec_started",
                new MapSqlParameterSource("t", t));
        return view(t, load(t, id, false));
    }

    /** The draft's period end and typed balances. The first reconciliation may also move its start. */
    @Transactional
    public BankRecDTOs.Reconciliation update(UUID recId, BankRecDTOs.ReconciliationInput in) {
        UUID t = BankAccountLedgerService.requireTenant();
        Rec r0 = load(t, recId, false);
        matches.lockAccount(t, r0.bankAccountId());
        Rec r = load(t, recId, true);
        requireDraft(r);
        if (in == null || in.periodTo() == null) throw new BusinessRuleViolationException("Give the period's last day");
        Optional<Rec> prev = latestFinalized(t, r.bankAccountId(), r.id());
        LocalDate from = prev.isPresent() ? r.from() : (in.periodFrom() == null ? r.from() : in.periodFrom());
        if (prev.isPresent()) startOf(prev, in);
        validatePeriod(from, in.periodTo());
        jdbc.update("""
                update bank_reconciliations set period_from = :from, period_to = :to, statement_opening = :opening,
                       statement_closing = :closing where id = :id and tenant_id = :t""",
                new MapSqlParameterSource("id", recId).addValue("t", t).addValue("from", from).addValue("to", in.periodTo())
                        .addValue("opening", prev.isEmpty() ? money(in.statementOpening()) : null)
                        .addValue("closing", money(in.statementClosing())));
        return view(t, load(t, recId, false));
    }

    /** Discard a draft. Nothing else refers to it. */
    @Transactional
    public void discard(UUID recId) {
        UUID t = BankAccountLedgerService.requireTenant();
        Rec r0 = load(t, recId, false);
        matches.lockAccount(t, r0.bankAccountId());
        requireDraft(load(t, recId, true));
        jdbc.update("delete from bank_rec_day_totals where reconciliation_id = :id and tenant_id = :t",
                new MapSqlParameterSource("id", recId).addValue("t", t));
        jdbc.update("delete from bank_reconciliations where id = :id and tenant_id = :t and status = 'DRAFT'",
                new MapSqlParameterSource("id", recId).addValue("t", t));
    }

    /** Periods are contiguous (spec §4): the start is the day after the latest finalized period, or the typed one for the first. */
    private LocalDate startOf(Optional<Rec> prev, BankRecDTOs.ReconciliationInput in) {
        if (prev.isPresent()) {
            LocalDate next = prev.get().to().plusDays(1);
            if (in.periodFrom() != null && !in.periodFrom().equals(next)) {
                throw new BusinessRuleViolationException("Reconciliations are contiguous: the next one starts on "
                        + next.format(DMY) + ", the day after the last finalized one ended");
            }
            if (in.statementOpening() != null && money(in.statementOpening()).compareTo(prev.get().closing()) != 0) {
                throw new BusinessRuleViolationException("The statement opening balance is the previous reconciliation's closing balance ("
                        + StatementValues.money(prev.get().closing()) + ")");
            }
            return next;
        }
        if (in.periodFrom() == null) throw new BusinessRuleViolationException("Give the first reconciliation's start date");
        LocalDate booksStart = fiscal.booksStartDate();
        if (booksStart != null && in.periodFrom().isBefore(booksStart)) {
            throw new BusinessRuleViolationException("A reconciliation cannot start before the books do ("
                    + booksStart.format(DMY) + ")");
        }
        return in.periodFrom();
    }

    private static void validatePeriod(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new BusinessRuleViolationException("The period ends (" + to.format(DMY) + ") before it starts ("
                    + from.format(DMY) + ")");
        }
    }

    private static void requireDraft(Rec r) {
        if (!"DRAFT".equals(r.status())) {
            throw new BusinessRuleViolationException("This reconciliation is " + r.status() + "; only a draft can be changed");
        }
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ read

    /** Live figures while DRAFT; the snapshot taken at finalize afterwards. */
    @Transactional(readOnly = true)
    public BankRecDTOs.Reconciliation get(UUID recId) {
        UUID t = BankAccountLedgerService.requireTenant();
        return view(t, load(t, recId, false));
    }

    private BankRecDTOs.Reconciliation view(UUID t, Rec r) {
        if (!"DRAFT".equals(r.status()) && r.snapshot() != null) {
            try {
                BankRecDTOs.Reconciliation s = json.readValue(r.snapshot(), BankRecDTOs.Reconciliation.class);
                return decorate(s, r);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Reconciliation snapshot " + r.id() + " cannot be read", e);
            }
        }
        return compute(t, r);
    }

    /** The snapshot, with the row's current status and reopen facts (a REOPENED row keeps its finalize snapshot). */
    private BankRecDTOs.Reconciliation decorate(BankRecDTOs.Reconciliation s, Rec r) {
        return new BankRecDTOs.Reconciliation(s.id(), s.bankAccountId(), s.bankLabel(), s.bankName(), s.ibanMasked(),
                s.leaves(), s.periodFrom(), s.periodTo(), r.status(), s.first(), s.statementOpening(),
                s.statementClosing(), s.closingTyped(), s.statementMovement(), s.bookBalance(), s.bookBalanceAtStart(),
                s.openingItemsTotal(), s.depositsInTransit(), s.unpresentedPayments(), s.bookedAfterPeriod(),
                s.unrecordedCredits(), s.unrecordedDebits(), s.adjustedBank(), s.adjustedBook(), s.difference(),
                s.depositsInTransitItems(), s.unpresentedItems(), s.bookedAfterItems(), s.unrecordedItems(),
                s.withoutEvidenceCount(), s.matchedByMethod(), s.checks(), false, s.preparedAt(), s.preparedByName(),
                r.finalizedAt(), userName(r.finalizedBy()), r.reopenedAt(), userName(r.reopenedBy()), r.reopenReason(),
                s.unprovenDays() == null ? List.of() : s.unprovenDays(), s.dayTotalsVerdict(),
                "DRAFT".equals(r.status()) ? s.remedyReopenThrough() : null);
    }

    // ------------------------------------------------------------------ figures

    record SL(UUID id, LocalDate txn, long seq, String description, String reference, String chequeNo,
              BigDecimal amount, BigDecimal balance) { }

    record JI(UUID id, LocalDate date, String entryNumber, String docType, String sourceType, String narration,
              String chequeNo, BigDecimal amount, boolean offStatement) { }

    /** A confirmed live match: its statement lines, journal items and opening items. */
    static final class M {
        final String method;
        final List<SL> lines = new ArrayList<>();
        final List<JI> journal = new ArrayList<>();
        final List<UUID> opening = new ArrayList<>();
        final Map<UUID, LocalDate> journalDates = new HashMap<>();
        final Map<UUID, BigDecimal> journalAmounts = new HashMap<>();

        M(String method) {
            this.method = method;
        }
    }

    private static final String LINE_COLUMNS = "l.id, l.txn_date, l.seq, l.description, l.reference, l.cheque_no, l.amount, l.running_balance ";

    private static SL sl(ResultSet rs) throws SQLException {
        return new SL(rs.getObject("id", UUID.class), rs.getObject("txn_date", LocalDate.class), rs.getLong("seq"),
                rs.getString("description"), rs.getString("reference"), rs.getString("cheque_no"),
                rs.getBigDecimal("amount"), rs.getBigDecimal("running_balance"));
    }

    /** Every figure and item list of the statement, and the finalize checks, as of now. */
    BankRecDTOs.Reconciliation compute(UUID t, Rec r) {
        UUID b = r.bankAccountId();
        Bank bank = bank(t, b);
        List<BankRecDTOs.Leaf> leaves = ledgers.leaves(b);
        Set<UUID> leafIds = leaves.stream().map(BankRecDTOs.Leaf::id).collect(Collectors.toCollection(LinkedHashSet::new));
        Optional<Rec> prev = latestFinalized(t, b, r.id());
        boolean first = prev.isEmpty();
        LocalDate from = r.from(), to = r.to();
        LocalDate recStart = first || bank.recStartDate() == null ? from : bank.recStartDate();
        MapSqlParameterSource p = new MapSqlParameterSource("t", t).addValue("b", b).addValue("from", from)
                .addValue("to", to).addValue("start", recStart).addValue("leaves", leafIds.isEmpty() ? Set.of(UUID.randomUUID()) : leafIds);
        List<BankRecDTOs.Check> checks = new ArrayList<>();

        // ---- statement side
        List<SL> period = jdbc.query("select " + LINE_COLUMNS + """
                 from bank_statement_lines l where l.tenant_id = :t and l.bank_account_id = :b
                  and l.txn_date between :from and :to order by l.txn_date, l.seq""", p, (rs, i) -> sl(rs));
        BigDecimal opening;
        if (!first) {
            opening = prev.get().closing();
        } else if (r.opening() != null) {
            opening = r.opening();
        } else {
            opening = jdbc.query("select " + LINE_COLUMNS + """
                     from bank_statement_lines l where l.tenant_id = :t and l.bank_account_id = :b and l.txn_date >= :from
                    order by l.txn_date, l.seq limit 1""", p, (rs, i) -> sl(rs)).stream().findFirst()
                    .filter(l -> l.balance() != null).map(l -> l.balance().subtract(l.amount())).orElse(null);
        }
        boolean closingTyped = r.closing() != null;
        BigDecimal closing = closingTyped ? r.closing() : jdbc.query("select " + LINE_COLUMNS + """
                 from bank_statement_lines l where l.tenant_id = :t and l.bank_account_id = :b and l.txn_date <= :to
                order by l.txn_date desc, l.seq desc limit 1""", p, (rs, i) -> sl(rs)).stream().findFirst()
                .map(SL::balance).orElse(null);
        BigDecimal movement = period.stream().map(SL::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

        // Chain: the start and the opening balance follow the latest finalized reconciliation.
        if (!first) {
            LocalDate next = prev.get().to().plusDays(1);
            boolean chained = next.equals(from);
            checks.add(new BankRecDTOs.Check("CHAIN", chained, chained
                    ? "Follows the reconciliation finalized through " + prev.get().to().format(DMY)
                    : "This reconciliation starts on " + from.format(DMY) + "; the previous one ended on "
                            + prev.get().to().format(DMY) + ", so it must start on " + next.format(DMY)));
        }
        // #97: days whose line order no single file fixed, and of those the ones where a
        // file's line was taken for an identical stored one (bank_statement_days).
        Map<LocalDate, Boolean> unconfirmed = unconfirmedDays(t, b, from, to);
        // Review P2-3: an unproven day is proven by the day's total debits and total
        // credits typed from the paper statement, when they agree with its stored lines.
        Map<LocalDate, KeptCopies> copies = keptLookalikes(t, b, unconfirmed);
        // Sim r26 S26-01: a day holding only unmarked (pre-#441) twins keeps its totals verdict; the check's
        // text names the copy steps for it.
        List<BankRecDTOs.UnprovenDay> dayVerdicts = dayProof(t, r.id(), unconfirmed, period, copies.entrySet().stream()
                .filter(e -> !e.getValue().legacy()).map(Map.Entry::getKey).collect(Collectors.toSet()));
        // P3-20: the page sees only whether a day's totals were entered, not the verdict.
        List<BankRecDTOs.UnprovenDay> unprovenDays = dayVerdicts.stream().map(d -> "NEEDED".equals(d.status()) ? d
                : d.typedDebits() == null ? new BankRecDTOs.UnprovenDay(d.date(), null, null, "NEEDED")
                : new BankRecDTOs.UnprovenDay(d.date(), d.typedDebits(), d.typedCredits(), "ENTERED")).toList();
        boolean openingKnown = !first || r.opening() != null;
        // S20-02: with the paper's balances typed, the order of an unconfirmed day's
        // lines is not evidence of anything: the total decides there, not the chain.
        Set<LocalDate> chainSkipped = closingTyped && openingKnown ? unconfirmed.keySet() : Set.of();
        Remedy remedy = remedy(t, b, from, to);
        checks.add(continuity(opening, closing, movement, period, from, chainSkipped, unconfirmed.keySet(), remedy));
        lineOrder(unconfirmed, dayVerdicts, closingTyped, openingKnown, first && r.opening() != null, remedy, copies)
                .ifPresent(checks::add);
        // Sim r22 S22-03: the derived closing is the last stored line's balance. It is the
        // bank's closing only if nothing was posted after that line up to the period end;
        // a file that stopped mid-way through its last date cannot say so.
        if (!closingTyped && closing != null) closingProof(t, b, to).ifPresent(checks::add);

        // ---- book side
        BigDecimal book = sum(jdbc.queryForObject("""
                select coalesce(sum(jl.debit - jl.credit), 0) from journal_lines jl
                join journal_entries je on je.id = jl.journal_entry_id
                where jl.tenant_id = :t and jl.account_id in (:leaves) and je.entry_date <= :to""", p, BigDecimal.class));
        List<JI> bookItems = jdbc.query("""
                select jl.id, je.entry_date, je.entry_number, je.doc_type, je.source_type,
                       coalesce(jl.narration, je.narration) as narration, (jl.debit - jl.credit) as amount,
                       case je.source_type
                         when 'CHEQUE' then (select c.cheque_number from cheques c where c.id = je.source_id and c.tenant_id = je.tenant_id)
                         when 'ISSUED_CHEQUE' then (select ic.cheque_number from issued_cheques ic where ic.id = je.source_id and ic.tenant_id = je.tenant_id)
                         when 'VOUCHER' then (select v.cheque_number from vouchers v where v.id = je.source_id and v.tenant_id = je.tenant_id)
                       end as cheque_no,
                       exists (select 1 from bank_off_statement_items o
                               where o.tenant_id = je.tenant_id and o.bank_account_id = :b
                                 and o.journal_entry_id = je.id) as off_statement
                from journal_lines jl
                join journal_entries je on je.id = jl.journal_entry_id and je.tenant_id = jl.tenant_id
                where jl.tenant_id = :t and jl.account_id in (:leaves) and je.doc_type <> 'OB'
                  and (je.entry_date between :start and :to
                       -- F14-20: confirmed "not on the statement" and dated before the
                       -- reconciliation starts: outstanding until a statement line shows it.
                       or (je.entry_date < :start and exists (select 1 from bank_off_statement_items o
                               where o.tenant_id = je.tenant_id and o.bank_account_id = :b
                                 and o.journal_entry_id = je.id)))
                order by je.entry_date, je.entry_number, jl.line_no""", p,
                (rs, i) -> new JI(rs.getObject("id", UUID.class), rs.getObject("entry_date", LocalDate.class),
                        rs.getString("entry_number"), rs.getString("doc_type"), rs.getString("source_type"),
                        rs.getString("narration"), rs.getString("cheque_no"), rs.getBigDecimal("amount"),
                        rs.getBoolean("off_statement")));
        List<BankRecDTOs.OpeningItem> openingItems = matches.openingItems(t, b);
        BigDecimal openingTotal = openingItems.stream().map(BankRecDTOs.OpeningItem::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

        // ---- confirmed matches
        Map<UUID, M> ms = new LinkedHashMap<>();
        jdbc.query("select m.id as match_id, m.method, " + LINE_COLUMNS + """
                 from bank_matches m
                join bank_match_statement_lines ml on ml.match_id = m.id and not ml.released
                join bank_statement_lines l on l.id = ml.statement_line_id
                where m.tenant_id = :t and m.bank_account_id = :b and m.status = 'CONFIRMED'""", p, rs -> {
            ms.computeIfAbsent(rs.getObject("match_id", UUID.class), k -> new M(uncheckedString(rs, "method"))).lines.add(sl(rs));
        });
        jdbc.query("""
                select m.id as match_id, m.method, i.journal_line_id, i.opening_item_id, je.entry_date,
                       coalesce(jl.debit - jl.credit, o.amount) as amount
                from bank_matches m
                join bank_match_book_items i on i.match_id = m.id and not i.released
                left join journal_lines jl on jl.id = i.journal_line_id
                left join journal_entries je on je.id = jl.journal_entry_id
                left join bank_rec_opening_items o on o.id = i.opening_item_id
                where m.tenant_id = :t and m.bank_account_id = :b and m.status = 'CONFIRMED'""", p, rs -> {
            M m = ms.computeIfAbsent(rs.getObject("match_id", UUID.class), k -> new M(uncheckedString(rs, "method")));
            UUID j = rs.getObject("journal_line_id", UUID.class);
            if (j != null) {
                m.journalDates.put(j, rs.getObject("entry_date", LocalDate.class));
                m.journalAmounts.put(j, rs.getBigDecimal("amount"));
            } else {
                m.opening.add(rs.getObject("opening_item_id", UUID.class));
            }
        });
        Map<UUID, M> byJournal = new HashMap<>();
        Map<UUID, M> byOpening = new HashMap<>();
        Set<UUID> matchedLines = new HashSet<>();
        for (M m : ms.values()) {
            m.journalDates.keySet().forEach(j -> byJournal.put(j, m));
            m.opening.forEach(o -> byOpening.put(o, m));
            m.lines.forEach(l -> matchedLines.add(l.id()));
        }

        // ---- outstanding book items
        List<BankRecDTOs.RecItem> dit = new ArrayList<>(), unpresented = new ArrayList<>();
        int withoutEvidence = 0;
        for (JI j : bookItems) {
            if (!outstanding(byJournal.get(j.id()), recStart, to)) continue;
            boolean handCleared = j.offStatement()
                    || j.amount().signum() > 0 && "CRT".equals(j.docType()) && "CHEQUE".equals(j.sourceType());
            if (handCleared) withoutEvidence++;
            BankRecDTOs.RecItem item = new BankRecDTOs.RecItem("JOURNAL", j.id(), j.date(), j.entryNumber(), j.narration(),
                    j.chequeNo(), j.amount(), handCleared);
            (j.amount().signum() > 0 ? dit : unpresented).add(item);
        }
        for (BankRecDTOs.OpeningItem o : openingItems) {
            if (!outstanding(byOpening.get(o.id()), recStart, to)) continue;
            BankRecDTOs.RecItem item = new BankRecDTOs.RecItem("OPENING", o.id(), o.itemDate(),
                    o.reference() == null ? "" : o.reference(), o.description(), o.chequeNo(), o.amount(), false);
            (o.amount().signum() > 0 ? dit : unpresented).add(item);
        }
        BigDecimal ditTotal = total(dit);
        BigDecimal unpresentedTotal = total(unpresented).negate();

        // ---- booked after the period: lines ≤ to whose book side is dated after to (the mirror of a deposit in transit)
        List<BankRecDTOs.RecItem> bookedAfter = new ArrayList<>();
        Map<String, Integer> byMethod = new TreeMap<>();
        for (M m : ms.values()) {
            List<SL> le = m.lines.stream().filter(l -> !l.txn().isBefore(recStart) && !l.txn().isAfter(to)).toList();
            if (m.lines.stream().anyMatch(l -> !l.txn().isBefore(from) && !l.txn().isAfter(to))) {
                byMethod.merge(m.method, 1, Integer::sum);
            }
            if (le.isEmpty()) continue;
            BigDecimal later = m.journalDates.entrySet().stream().filter(e -> e.getValue().isAfter(to))
                    .map(e -> m.journalAmounts.get(e.getKey())).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal linesLater = m.lines.stream().filter(l -> l.txn().isAfter(to)).map(SL::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal ba = later.subtract(linesLater);
            if (ba.signum() != 0) {
                SL l = le.get(0);
                bookedAfter.add(new BankRecDTOs.RecItem("STATEMENT", l.id(), l.txn(), l.reference(), l.description(),
                        l.chequeNo(), ba, false));
            }
        }
        BigDecimal bookedAfterTotal = total(bookedAfter);

        // ---- unrecorded statement items
        List<BankRecDTOs.RecItem> unrecorded = period.stream().filter(l -> !matchedLines.contains(l.id()))
                .map(l -> new BankRecDTOs.RecItem("STATEMENT", l.id(), l.txn(), l.reference(), l.description(),
                        l.chequeNo(), l.amount(), false)).toList();
        BigDecimal unrecordedCredits = unrecorded.stream().map(BankRecDTOs.RecItem::amount).filter(a -> a.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal unrecordedDebits = unrecorded.stream().map(BankRecDTOs.RecItem::amount).filter(a -> a.signum() < 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add).negate();

        // ---- the formula
        BigDecimal adjustedBank = closing == null ? null
                : closing.add(ditTotal).subtract(unpresentedTotal).subtract(bookedAfterTotal);
        BigDecimal adjustedBook = book.add(unrecordedCredits).subtract(unrecordedDebits);
        BigDecimal difference = adjustedBank == null ? null : adjustedBank.subtract(adjustedBook);

        // ---- the remaining preconditions (spec §4 "Finalize preconditions")
        checks.add(new BankRecDTOs.Check("UNRECORDED", unrecorded.isEmpty(), unrecorded.isEmpty()
                ? "Every statement line in the period is matched"
                : unrecorded.size() + " statement line(s) in the period are not matched: book them from the line or match them"));
        boolean zero = difference != null && difference.signum() == 0;
        checks.add(new BankRecDTOs.Check("DIFFERENCE", zero, difference == null
                ? "The difference cannot be worked out without the statement balances"
                : zero ? "The difference is 0.00" : "The difference is " + StatementValues.money(difference) + "; it must be 0.00"));
        Integer suggested = jdbc.queryForObject("""
                select count(*) from bank_matches m where m.tenant_id = :t and m.bank_account_id = :b and m.status = 'SUGGESTED'
                  and (exists (select 1 from bank_match_statement_lines ml join bank_statement_lines l on l.id = ml.statement_line_id
                               where ml.match_id = m.id and not ml.released and l.txn_date between :from and :to)
                    or exists (select 1 from bank_match_book_items i join journal_lines jl on jl.id = i.journal_line_id
                               join journal_entries je on je.id = jl.journal_entry_id
                               where i.match_id = m.id and not i.released and je.entry_date between :from and :to))""", p, Integer.class);
        int nSuggested = suggested == null ? 0 : suggested;
        checks.add(new BankRecDTOs.Check("SUGGESTED", nSuggested == 0, nSuggested == 0
                ? "No suggested match is waiting"
                : nSuggested + " suggested match(es) in the period: confirm or reject each one"));
        LocalDate today = LocalDate.now(clock);
        checks.add(new BankRecDTOs.Check("NOT_FUTURE", !to.isAfter(today), !to.isAfter(today)
                ? "The period has ended"
                : "The period ends on " + to.format(DMY) + ", after today"));
        BigDecimal bookAtStart = null;
        if (first) {
            bookAtStart = sum(jdbc.queryForObject("""
                    select coalesce(sum(jl.debit - jl.credit), 0) from journal_lines jl
                    join journal_entries je on je.id = jl.journal_entry_id
                    where jl.tenant_id = :t and jl.account_id in (:leaves) and je.entry_date < :from""", p, BigDecimal.class));
            // F14-20: a confirmed off-statement entry dated before the start is in the
            // book at the start and listed above as outstanding, like an opening item.
            BigDecimal offBefore = bookItems.stream().filter(j -> j.offStatement() && j.date().isBefore(from))
                    .map(JI::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            checks.add(openingCheck(opening, openingTotal.add(offBefore), bookAtStart, openingItems, from));
        }
        boolean canFinalize = "DRAFT".equals(r.status()) && checks.stream().allMatch(BankRecDTOs.Check::ok);

        return new BankRecDTOs.Reconciliation(r.id(), b, bank.label(), bank.bankName(), maskIban(bank.iban(), bank.accountNumber()),
                leaves, from, to, r.status(), first, opening, closing, closingTyped, movement, book, bookAtStart,
                openingTotal, ditTotal, unpresentedTotal, bookedAfterTotal, unrecordedCredits, unrecordedDebits,
                adjustedBank, adjustedBook, difference, dit, unpresented, bookedAfter, unrecorded, withoutEvidence,
                byMethod, checks, canFinalize, Instant.now(clock), userName(BankStatementImportService.currentUserId()),
                r.finalizedAt(), userName(r.finalizedBy()), r.reopenedAt(), userName(r.reopenedBy()), r.reopenReason(),
                unprovenDays, dayTotalsVerdict(dayVerdicts), remedy.reopenThrough(), offersFor(t, copies));
    }

    /**
     * A book item is outstanding unless its confirmed match has a statement line
     * dated in [start, to], or it is a contra match (no lines) whose journal items
     * all fall on or before {@code to}.
     */
    private static boolean outstanding(M m, LocalDate start, LocalDate to) {
        if (m == null) return true;
        if (m.lines.stream().anyMatch(l -> !l.txn().isBefore(start) && !l.txn().isAfter(to))) return false;
        return !(m.lines.isEmpty() && m.journalDates.values().stream().noneMatch(d -> d.isAfter(to)));
    }

    /**
     * Spec §4 precondition 1, with the per-line balance chain the import only warned about.
     *
     * <p>Strict on purpose (#97 review P1-1): the lines are taken in stored
     * {@code (txn_date, seq)} order and never re-ordered here. Guessing an order
     * from the balances can chain the remaining lines round a missing one when a
     * day's balance passes through the same value twice. The order is settled at
     * import instead ({@link BankStatementImportService#dayOrder}).</p>
     */
    static BankRecDTOs.Check continuity(BigDecimal opening, BigDecimal closing, BigDecimal movement,
                                               List<SL> period, LocalDate from) {
        return continuity(opening, closing, movement, period, from, Set.of(), Set.of());
    }

    /**
     * As above; the per-line comparison is skipped on {@code chainSkipped} days (an
     * unconfirmed day order with the paper's balances typed, S20-02), and a break
     * on an {@code unconfirmed} day says how to settle it instead of "import the
     * missing lines".
     */
    static BankRecDTOs.Check continuity(BigDecimal opening, BigDecimal closing, BigDecimal movement,
                                        List<SL> period, LocalDate from, Set<LocalDate> chainSkipped,
                                        Set<LocalDate> unconfirmed) {
        return continuity(opening, closing, movement, period, from, chainSkipped, unconfirmed, Remedy.NONE);
    }

    /** As above; the messages name the statement to import ({@link Remedy}) where one would settle the gap. */
    static BankRecDTOs.Check continuity(BigDecimal opening, BigDecimal closing, BigDecimal movement,
                                        List<SL> period, LocalDate from, Set<LocalDate> chainSkipped,
                                        Set<LocalDate> unconfirmed, Remedy remedy) {
        if (opening == null) {
            return new BankRecDTOs.Check("CONTINUITY", false, "Type the statement balance at "
                    + from.minusDays(1).format(DMY) + ": no statement line gives it");
        }
        if (closing == null) {
            return new BankRecDTOs.Check("CONTINUITY", false,
                    "The statement lines carry no running balance: type the closing balance from the paper statement");
        }
        BigDecimal running = opening;
        LocalDate previous = null;
        for (SL l : period) {
            running = running.add(l.amount());
            if (chainSkipped.contains(l.txn())) {
                previous = l.txn();
                continue;
            }
            if (l.balance() != null && l.balance().compareTo(running) != 0 && unconfirmed.contains(l.txn())) {
                return new BankRecDTOs.Check("CONTINUITY", false, "The lines on " + l.txn().format(DMY)
                        + " came from more than one file and do not chain in the order they were imported: lines may be "
                        + "missing, or only their order is unknown. Type the closing balance from the paper statement "
                        + "(the total then decides)" + remedy.orImport(l.txn(), l.txn()) + ".");
            }
            if (l.balance() != null && l.balance().compareTo(running) != 0) {
                // S21-01: the gap lies between the line before (or the period's start) and this
                // one; a statement that holds both their days whole brings it without leaving a
                // day to settle.
                LocalDate gapFrom = previous == null ? l.txn() : previous;
                return new BankRecDTOs.Check("CONTINUITY", false, "Lines may be missing before " + l.txn().format(DMY)
                        + ": the statement balance there is " + StatementValues.money(l.balance()) + ", the lines add up to "
                        + StatementValues.money(running) + ". Import the missing lines" + remedy.importing(gapFrom, l.txn())
                        + ".");
            }
            previous = l.txn();
        }
        BigDecimal added = opening.add(movement);
        boolean ok = added.compareTo(closing) == 0;
        return new BankRecDTOs.Check("CONTINUITY", ok, ok
                ? StatementValues.money(opening) + " + " + StatementValues.money(movement) + " = " + StatementValues.money(closing)
                : "Statement lines add up to " + StatementValues.money(added) + ", the closing balance is "
                        + StatementValues.money(closing) + ": import the missing lines.");
    }

    /**
     * #97 review P2-1: a day of the period whose lines came from separate files
     * that do not fix their order ({@code bank_statement_days.order_confirmed} is
     * false). In the order they were stored, the lines of such a day may chain
     * round a missing line, so the balances the lines give are not evidence
     * enough: the closing balance (and, on a first reconciliation, the opening)
     * must be typed from the paper statement. Then the order-independent total
     * check {@code opening + movement = closing} decides. Absent when every day is
     * confirmed.
     */
    /** The period's unconfirmed days that still have lines, each with whether it is unproven. */
    private Map<LocalDate, Boolean> unconfirmedDays(UUID t, UUID b, LocalDate from, LocalDate to) {
        Map<LocalDate, Boolean> out = new TreeMap<>();
        jdbc.query("""
                select d.txn_date, d.merge_unproven from bank_statement_days d
                where d.tenant_id = :t and d.bank_account_id = :b and not d.order_confirmed
                  and d.txn_date between :from and :to
                  and exists (select 1 from bank_statement_lines l where l.tenant_id = d.tenant_id
                              and l.bank_account_id = d.bank_account_id and l.txn_date = d.txn_date)""",
                new MapSqlParameterSource("t", t).addValue("b", b).addValue("from", from).addValue("to", to),
                rs -> { out.put(rs.getObject("txn_date", LocalDate.class), rs.getBoolean("merge_unproven")); });
        return out;
    }

    /**
     * Sim r22 S22-03 (ruling): a reconciliation is finalized at a derived closing only when
     * nothing can follow the last stored line up to the period end: an import that brought
     * lines of the period's last line-day runs past that day, or carries a Closing row dated
     * after the period end. ({@code end_known} is not enough: a Closing row dated after the
     * day but before the period end proves the day's end, not the days after it.) Otherwise the closing must
     * be typed from the paper statement (one figure); CONTINUITY then checks it against
     * the lines. Absent when the closing is proven.
     */
    private Optional<BankRecDTOs.Check> closingProof(UUID t, UUID b, LocalDate to) {
        MapSqlParameterSource p = new MapSqlParameterSource("t", t).addValue("b", b).addValue("to", to);
        LocalDate last = jdbc.queryForObject("""
                select max(txn_date) from bank_statement_lines where tenant_id = :t and bank_account_id = :b
                and txn_date <= :to""", p, LocalDate.class);
        if (last == null) return Optional.empty();
        p.addValue("d", last);
        // Proof that nothing follows the last stored line up to the period end: an import that
        // brought lines of that day and either runs past it (a contiguous file: its lines of the
        // day are the day's last ones, and its next line lies beyond the period) or carries an
        // agreeing Closing row dated after the period end (the export reached past it). A
        // Closing row dated on or before the period end proves nothing about the end of that
        // day: an export taken during the period's last day prints one too.
        Boolean ended = jdbc.queryForObject("""
                select exists (select 1 from bank_statement_lines l
                                 join bank_statement_line_sources s on s.line_id = l.id and s.tenant_id = l.tenant_id
                                 join bank_statement_imports i on i.id = s.import_id and i.tenant_id = s.tenant_id
                                where l.tenant_id = :t and l.bank_account_id = :b and l.txn_date = :d
                                  and (i.last_date > :d or i.closing_row_date > :to))""", p, Boolean.class);
        if (Boolean.TRUE.equals(ended)) return Optional.empty();
        String d = last.format(DMY);
        return Optional.of(new BankRecDTOs.Check("CLOSING", false, "The imported lines end on " + d
                + ", and no imported file shows that day ended there: a line the bank posted later that day"
                + (last.isBefore(to) ? ", or after it up to " + to.format(DMY) + "," : "")
                + " would not show in any balance. Type the closing balance at " + to.format(DMY)
                + " from the paper statement, or import a statement that runs past " + d + "."));
    }

    /**
     * Sim r21 S21-02: how to keep overlapping exports from needing day totals. A day is
     * settled without typing only when one file holds it whole, and a file can show
     * that only for a day that is neither its first nor its last (or one its boundary
     * rows, dated outside its lines, prove): an export taken during the day, and the
     * next one starting on that day, leave the day held whole by neither.
     */
    static final String AVOID = " To avoid this with later statements, export whole days: end each export on a day "
            + "before the day you download it, and start the next export on the day after.";

    /**
     * Sim r21 S21-01: the statement to import to settle some days without leaving a new
     * day to settle. A file holds every day strictly between its first and last dates
     * whole, so those days settle (#97). Its first and last days are only partly held:
     * importing it leaves them as they were only when they are already complete (their
     * stored lines known to start and end the day, in the bank's order, nothing taken
     * for an identical line) or inside a finalized reconciliation; otherwise a line of
     * the file there may be taken for an identical stored one and the day becomes
     * unproven (S20-01) — which is what "the day before to the day after" did to its own
     * last day. So the edges are the nearest complete days before and after.
     */
    static final class Remedy {
        static final Remedy NONE = new Remedy(new TreeSet<>(), null);

        /**
         * An import's first and last statement dates, and whether it brought (owns) a line
         * dated inside the finalized period — such an import cannot be deleted.
         */
        record Span(LocalDate first, LocalDate last, boolean ownsLocked, String fileName) {
            Span(LocalDate first, LocalDate last) {
                this(first, last, false, null);
            }

            Span(LocalDate first, LocalDate last, boolean ownsLocked) {
                this(first, last, ownsLocked, null);
            }
        }

        /** The statement {@link #replacing} names, and the imports it replaces (each deletable). */
        record Replacement(LocalDate first, LocalDate last, List<Span> chained) { }

        private final TreeSet<LocalDate> complete;
        private final LocalDate locked;
        private final List<Span> imports;
        /** S25-01: the last line-day before the period (its opening day), or null. */
        private final LocalDate openingDay;
        /** Sim r22 S22-04: set when a named statement starts inside the finalized period. */
        private LocalDate reopenThrough;
        /** #441 ruling A: the account has shown a back-value ({@code bank_accounts.back_value_seen}). */
        private boolean backValues;

        Remedy backValues(boolean seen) {
            this.backValues = seen;
            return this;
        }

        boolean backValues() {
            return backValues;
        }

        /** #441 ruling A: what a whole-day statement no longer does alone on such an account. */
        static final String BACK_VALUES = "the bank has back-valued lines on this account (a running balance has moved), "
                + "so a statement holding a day whole no longer settles an unproven day by itself — an older download "
                + "would look the same";

        Remedy(TreeSet<LocalDate> complete, LocalDate locked) {
            this(complete, locked, List.of());
        }

        Remedy(TreeSet<LocalDate> complete, LocalDate locked, List<Span> imports) {
            this(complete, locked, imports, null);
        }

        Remedy(TreeSet<LocalDate> complete, LocalDate locked, List<Span> imports, LocalDate openingDay) {
            this.complete = complete;
            this.locked = locked;
            this.imports = imports;
            this.openingDay = openingDay;
        }

        /**
         * Sim r23 S23-05: the statement that can replace every import it overlaps. The
         * imports that brought {@code first}..{@code last}, and, again and again, every import
         * overlapping the span they reach: a statement over that whole span holds every line
         * of them, and after they are deleted no other import touches any of its days, so each
         * is that one statement's stretch (settled). Taking only the imports that brought the
         * days left an edge day shared with a kept import, which the import then unproved.
         *
         * <p>Sim r25 P3-6: days inside the finalized reconciliation are left as they are by any
         * import (it may bring no new line there), so an import whose lines all lie there joins
         * nothing and stays. One that runs from a finalized day into the open period joins the
         * chain and moves its start into the finalized period — on a bank whose exports repeat
         * the boundary day, each month's first import — provided it brought no line of the
         * finalized days (its lines there were already the earlier import's): then it can be
         * deleted. Empty when a chained import owns a finalized line (it cannot be deleted) or
         * none is known.</p>
         */
        Optional<LocalDate[]> replacing(LocalDate first, LocalDate last) {
            return replacement(first, last).map(w -> new LocalDate[]{w.first(), w.last()});
        }

        /** As {@link #replacing}, with the chained imports — the ones the user is to delete. */
        Optional<Replacement> replacement(LocalDate first, LocalDate last) {
            LocalDate a = first, z = last;
            boolean grew = true;
            // Identity: two imports may share a name and dates.
            Set<Span> chained = Collections.newSetFromMap(new IdentityHashMap<>());
            List<Span> order = new ArrayList<>();
            while (grew) {
                grew = false;
                for (Span s : imports) {
                    if (s.first() == null || s.last() == null || s.last().isBefore(a) || s.first().isAfter(z)) continue;
                    if (locked != null && !s.last().isAfter(locked)) continue;
                    if (locked != null && !s.first().isAfter(locked) && s.ownsLocked()) return Optional.empty();
                    if (chained.add(s)) order.add(s);
                    if (s.first().isBefore(a)) { a = s.first(); grew = true; }
                    if (s.last().isAfter(z)) { z = s.last(); grew = true; }
                }
            }
            if (chained.isEmpty()) return Optional.empty();
            return Optional.of(new Replacement(a, z, List.copyOf(order)));
        }

        /**
         * #438 review: imports dated inside {@code a}..{@code z} that are not chained — wholly inside
         * the finalized period — and own a finalized line, so {@code delete()} refuses them.
         */
        private boolean undeletableInside(LocalDate a, LocalDate z, List<Span> chained) {
            for (Span s : imports) {
                if (s.first() == null || s.last() == null || chained.stream().anyMatch(c -> c == s)) continue;
                if (!s.first().isBefore(a) && !s.last().isAfter(z) && s.ownsLocked()) return true;
            }
            return false;
        }

        /** The finalized period a named statement reaches into, or null (S22-04). */
        LocalDate reopenThrough() {
            return reopenThrough;
        }

        /**
         * S22-04: a statement whose first day is finalized imports only if it brings no new
         * line there. The nearest settled day before is the latest one, so when it is
         * finalized no open day could start the statement instead; say what to do if the
         * import is refused.
         */
        private String lockNote(LocalDate a) {
            if (locked == null || a.isAfter(locked)) return "";
            reopenThrough = locked;
            return " (it starts inside the reconciliation finalized through " + locked.format(DMY)
                    + ": if the import is refused because it brings a line there, reopen that reconciliation first)";
        }

        /**
         * Sim r26 S26-02: a step naming an import that brought a line of the finalized period: say to reopen
         * that reconciliation first (and offer the reopen on the page), so the deletes then go through.
         */
        String reopenFirst(LocalDate through) {
            if (this != NONE) reopenThrough = through;
            return "first reopen the reconciliation finalized through " + through.format(DMY) + " (an import named here "
                    + "brought a line inside it, so it cannot be deleted while that period is finalized); then ";
        }

        /** The first and last dates of a statement that holds {@code first}..{@code last} whole. */
        Optional<LocalDate[]> span(LocalDate first, LocalDate last) {
            LocalDate a = complete.lower(first), z = complete.higher(last);
            return a == null || z == null ? Optional.empty() : Optional.of(new LocalDate[]{a, z});
        }

        /** "; or import one statement that runs from … to …", or how to settle it when none can be named. */
        /**
         * #441 r4: for unproven days. A statement holding the day whole still brings a line missing
         * there, but no longer settles an unproven day (an older download looks the same as a
         * current one): the day's paper totals, which always settle it once its lines are stored,
         * are the way out, and the text says so.
         */
        String orImportUnproven(LocalDate first, LocalDate last) {
            if (this == NONE) return "";
            String imp = orImport(first, last);
            String why = "a statement holding a day whole no longer settles an unproven day by itself (an older download "
                    + "looks the same as a current one)";
            return imp.isEmpty() ? " (" + why + ")" : imp + " — it brings any line missing there, but " + why
                    + ": type the day's totals after it";
        }

        String orImport(LocalDate first, LocalDate last) {
            if (this == NONE) return "";
            Optional<LocalDate[]> s = span(first, last);
            if (s.isPresent()) {
                return "; or import one statement that runs from " + s.get()[0].format(DMY) + " to " + s.get()[1].format(DMY)
                        + ", which holds " + (first.equals(last) ? first.format(DMY) : "those days") + " whole"
                        + endDay(s.get()[1]) + lockNote(s.get()[0]);
            }
            Optional<Replacement> o = replacement(first, last);
            if (o.isEmpty()) return "";
            Replacement w = o.get();
            String head = "; or import one statement that runs from " + w.first().format(DMY) + " to " + w.last().format(DMY);
            String tail = " (the lines it also holds stay, with their matches): no statement can yet hold "
                    + (first.equals(last) ? "that day" : "them") + " between two settled days" + lockNote(w.first());
            if (undeletableInside(w.first(), w.last(), w.chained())) {
                // #438 review: an import dated inside the span cannot be deleted (it owns a finalized
                // line); name the ones to delete instead of "every import dated inside".
                if (w.chained().stream().anyMatch(sp -> sp.fileName() == null)) return "";
                return head + " and holds every line of these imports, then delete these imports: "
                        + w.chained().stream().map(sp -> sp.fileName() + " (" + sp.first().format(DMY) + "–"
                                + sp.last().format(DMY) + ")").collect(Collectors.joining(", ")) + tail;
            }
            return head + " and holds every line of the imports that brought "
                    + (first.equals(last) ? first.format(DMY) + "'s" : "those days'") + " lines and of every import"
                    + " dated inside those dates, then delete all of those imports" + tail;
        }

        /**
         * ": one statement that runs from … to … brings them …", or nothing when none can be named.
         *
         * <p>Sim r25 S25-01: a balance that breaks between two lines need not be a line missing
         * between them. When the bank back-values a line — adds it later, dated earlier — every
         * balance after it moves in its later exports, and the break shows where a stored line
         * from an earlier export meets one from a later export, however far the line itself
         * lies before. So the statement named holds the period's opening day (the last line-day
         * before it) whole too: it starts on the settled day before that, which brings a line
         * back-valued to the end of the previous period (the usual case) and anything between.
         * Its first day may be finalized; the lock note says to reopen if the import is refused.
         * Without a settled day before the opening day, the nearest settled days around the gap.</p>
         */
        String importing(LocalDate first, LocalDate last) {
            if (this == NONE) return "";
            LocalDate z = complete.higher(last);
            LocalDate a = openingDay == null || openingDay.isAfter(first) ? null : complete.lower(openingDay);
            if (a != null && z != null) {
                return ": one statement that runs from " + a.format(DMY) + " to " + z.format(DMY) + " brings them"
                        + " (it holds " + openingDay.format(DMY) + ", the day before the period,"
                        + " whole too, in case the bank has since added a line dated earlier)" + endDay(z) + lockNote(a);
            }
            return span(first, last).map(s -> ": one statement that runs from " + s[0].format(DMY) + " to "
                    + s[1].format(DMY) + " brings them" + endDay(s[1]) + lockNote(s[0])).orElse("");
        }

        /**
         * #441 review: what a named statement leaves. Every day strictly inside it is held whole,
         * and its first day, already complete, runs to its end in the file; but a file cannot
         * show that its last day ended where it stops (the bank may since have added a line in
         * front of an identical one there, S25-P1), so that day is left to settle by its paper
         * totals — the one day the statement leaves, said as it is.
         */
        private String endDay(LocalDate z) {
            return "; it leaves " + z.format(DMY) + ", the day it ends on, and any day it holds that is already unproven, "
                    + "to their paper totals: type each such day's total debits and total credits from the paper statement"
                    // Sim r26 S26-04: on such an account every day it holds whole that already holds lines stays
                    // unproven (#441 r4, BankStatementImportService#distrustWhole), not only those it moves.
                    + (backValues ? " (" + BACK_VALUES + ": every day it holds whole that already holds imported lines "
                            + "is then left to its totals too, and the check names each)" : "");
        }
    }

    /**
     * The period's neighbourhood, for {@link Remedy}: each line-day within 45 days of the
     * period that is complete, or inside the finalized reconciliation and not its first
     * reconciled line-day unless that day's start is known (review P3-2).
     */
    private Remedy remedy(UUID t, UUID b, LocalDate from, LocalDate to) {
        MapSqlParameterSource p = new MapSqlParameterSource("t", t).addValue("b", b)
                .addValue("lo", from.minusDays(45)).addValue("hi", to.plusDays(45));
        TreeSet<LocalDate> complete = new TreeSet<>();
        jdbc.query("""
                select x.txn_date from (select distinct txn_date from bank_statement_lines
                                         where tenant_id = :t and bank_account_id = :b
                                           and txn_date between :lo and :hi) x
                join bank_accounts ba on ba.id = :b and ba.tenant_id = :t
                left join bank_statement_days d on d.tenant_id = :t and d.bank_account_id = :b and d.txn_date = x.txn_date
                where (x.txn_date <= ba.reconciled_through
                       -- review P3-2: a finalized day is whole at its end (finalize needs the
                       -- paper closing) but the first reconciled line-day may have begun mid-day
                       -- with a typed opening; a statement starting there would bring earlier
                       -- lines into the finalized period and be refused.
                       and (coalesce(d.start_known, false)
                            or exists (select 1 from bank_statement_lines e
                                        where e.tenant_id = :t and e.bank_account_id = :b
                                          and e.txn_date < x.txn_date
                                          and e.txn_date >= coalesce(ba.rec_start_date, e.txn_date))))
                   or (d.order_confirmed and not d.merge_unproven and d.start_known and d.end_known)""", p,
                rs -> { complete.add(rs.getObject("txn_date", LocalDate.class)); });
        LocalDate locked = jdbc.queryForObject("select reconciled_through from bank_accounts where id = :b and tenant_id = :t",
                p, LocalDate.class);
        List<Remedy.Span> spans = jdbc.query("""
                select i.first_date, i.last_date, i.file_name,
                       exists (select 1 from bank_statement_lines l
                                join bank_accounts ba on ba.id = l.bank_account_id and ba.tenant_id = l.tenant_id
                               where l.tenant_id = :t and l.import_id = i.id
                                 and l.txn_date <= ba.reconciled_through) as owns_locked
                  from bank_statement_imports i
                 where i.tenant_id = :t and i.bank_account_id = :b and i.first_date is not null and i.last_date is not null
                 order by i.imported_at, i.id""", p,
                (rs, i) -> new Remedy.Span(rs.getObject("first_date", LocalDate.class), rs.getObject("last_date", LocalDate.class),
                        rs.getBoolean("owns_locked"), rs.getString("file_name")));
        LocalDate openingDay = jdbc.queryForObject("""
                select max(txn_date) from bank_statement_lines where tenant_id = :t and bank_account_id = :b
                and txn_date < :from""", p.addValue("from", from), LocalDate.class);
        Boolean seen = jdbc.queryForObject("select back_value_seen from bank_accounts where id = :b and tenant_id = :t", p,
                Boolean.class);
        return new Remedy(complete, locked, spans, openingDay).backValues(Boolean.TRUE.equals(seen));
    }

    private static String dayList(Collection<LocalDate> days) {
        return days.stream().limit(5).map(d -> d.format(DMY)).collect(Collectors.joining(", "))
                + (days.size() > 5 ? " and " + (days.size() - 5) + " more" : "");
    }

    /**
     * {@code LINE_ORDER}: absent when every day of the period is confirmed.
     *
     * <ul>
     *   <li>An <em>unproven</em> day (a file's line was taken for an identical
     *       stored one while the file did not hold the whole day; sim S20-01)
     *       fails it whatever is typed: the line taken may have been a second,
     *       separate one, and lines that net to nothing show in no balance. Only a
     *       file holding the whole day settles it.</li>
     *   <li>Otherwise an unconfirmed day (lines from separate files, review P2-1)
     *       needs the closing balance — and on a first reconciliation the opening —
     *       typed from the paper statement; the order-independent total check then
     *       decides.</li>
     * </ul>
     */
    private static Optional<BankRecDTOs.Check> lineOrder(Map<LocalDate, Boolean> unconfirmed,
                                                        List<BankRecDTOs.UnprovenDay> unprovenDays, boolean closingTyped,
                                                        boolean openingKnown, boolean typedOpening, Remedy remedy,
                                                        Map<LocalDate, KeptCopies> copies) {
        if (unconfirmed.isEmpty()) return Optional.empty();
        List<LocalDate> mismatch = unprovenDays.stream().filter(d -> "MISMATCH".equals(d.status()))
                .map(BankRecDTOs.UnprovenDay::date).toList();
        List<LocalDate> needed = unprovenDays.stream().filter(d -> "NEEDED".equals(d.status()))
                .map(BankRecDTOs.UnprovenDay::date).toList();
        if (!needed.isEmpty()) {
            return Optional.of(new BankRecDTOs.Check("LINE_ORDER", false, "On " + dayList(needed)
                    + " a line in one file was taken for an identical line already imported from another, but the file "
                    + "did not hold the whole day, so it may have been a second, separate line (a fee charged twice, say). "
                    + "A typed closing balance cannot settle this. Type " + (needed.size() == 1 ? "that day's" : "each day's")
                    + " total debits and total credits from the paper statement"
                    + remedy.orImportUnproven(needed.get(0), needed.get(needed.size() - 1)) + "." + AVOID));
        }
        // Review P3-20: only once every unproven day's totals are typed, and only the
        // overall result — no per-day verdict to probe the stored totals with.
        if (!mismatch.isEmpty()) {
            List<LocalDate> all = unprovenDays.stream().map(BankRecDTOs.UnprovenDay::date).toList();
            Optional<LocalDate> copied = mismatch.stream().filter(copies::containsKey).findFirst();
            return Optional.of(new BankRecDTOs.Check("LINE_ORDER", false, "The total debits and credits typed from the "
                    + "paper statement for " + dayList(all) + " do not all agree with the imported lines: a line is "
                    + "missing" + copied.map(d -> ", or on " + d.format(DMY) + " a line an import kept is a copy of one "
                            + "already imported at another balance").orElse("")
                    + ". Check the figures against the paper statement"
                    + remedy.orImportUnproven(all.get(0), all.get(all.size() - 1))
                    + "." + copied.map(d -> " " + copySteps(d, copies.get(d), remedy)).orElse("")));
        }
        // #441 review: totals that agree on a day holding a kept look-alike prove nothing.
        List<LocalDate> copy = unprovenDays.stream().filter(d -> "COPY".equals(d.status()))
                .map(BankRecDTOs.UnprovenDay::date).toList();
        if (!copy.isEmpty()) {
            LocalDate d = copy.get(0);
            return Optional.of(new BankRecDTOs.Check("LINE_ORDER", false, copySteps(d, copies.get(d), remedy)));
        }
        // Days proven by their typed totals have all their lines, but not a known order:
        // like any unconfirmed day they need the typed closing (an order that chains by
        // chance can hide a line missing elsewhere behind a wrong derived closing).
        TreeMap<LocalDate, Boolean> rest = new TreeMap<>(unconfirmed);
        String list = dayList(rest.keySet());
        boolean ok = closingTyped && openingKnown;
        // Review P3-11: the check knows only that a balance was typed, not that it
        // came from the paper statement; it says what it relies on.
        return Optional.of(new BankRecDTOs.Check("LINE_ORDER", ok, ok
                ? "Lines on " + list + " came from more than one file; the reconciliation relies on the "
                        + (openingKnown && closingTyped && !typedOpening ? "closing balance" : "statement balances")
                        + " typed from the paper statement"
                : "Lines on " + list + " came from more than one file, so their order and completeness are not "
                        + "confirmed and a missing line could go unnoticed. Type the "
                        + (openingKnown ? "closing balance" : "opening and closing balances")
                        + " from the paper statement (not the figure the imported lines give)"
                        + remedy.orImport(rest.firstKey(), rest.lastKey()) + "."));
    }

    /**
     * Review P2-3: each unproven day of the period with the totals typed for it on this
     * reconciliation, and whether they prove it.
     *
     * <p>Why debits and credits, not the closing: a missing fee and its missing
     * reversal (S20-01) leave the balance as it was, but they take one amount out of
     * the day's debits and another out of its credits. The stored lines are always
     * some of the bank's lines (a line is stored only from a file), so equal debit
     * and credit totals mean no line with an amount is missing.</p>
     */
    private List<BankRecDTOs.UnprovenDay> dayProof(UUID t, UUID recId, Map<LocalDate, Boolean> unconfirmed, List<SL> period,
                                                   Set<LocalDate> lookalike) {
        List<LocalDate> unproven = unconfirmed.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).toList();
        if (unproven.isEmpty()) return List.of();
        Map<LocalDate, BigDecimal[]> typed = new HashMap<>();
        jdbc.query("""
                select txn_date, debit_total, credit_total from bank_rec_day_totals
                where tenant_id = :t and reconciliation_id = :r""",
                new MapSqlParameterSource("t", t).addValue("r", recId),
                rs -> { typed.put(rs.getObject("txn_date", LocalDate.class),
                        new BigDecimal[]{rs.getBigDecimal("debit_total"), rs.getBigDecimal("credit_total")}); });
        // #441 review: on a day holding a line kept beside a look-alike at another balance (it may
        // be a copy of that line), agreeing totals prove nothing: a copy can stand in for a line
        // lost that day.
        List<BankRecDTOs.UnprovenDay> out = new ArrayList<>();
        for (LocalDate d : unproven) {
            BigDecimal[] tt = typed.get(d);
            if (tt == null) {
                // #441 review r2: on a day holding a kept look-alike, totals alone never settle it.
                out.add(new BankRecDTOs.UnprovenDay(d, null, null, lookalike.contains(d) ? "COPY" : "NEEDED"));
                continue;
            }
            boolean agree = totalsAgree(dayTotals(period, d), tt[0], tt[1]);
            out.add(new BankRecDTOs.UnprovenDay(d, tt[0], tt[1], !agree ? "MISMATCH" : lookalike.contains(d) ? "COPY" : "PROVEN"));
        }
        return out;
    }

    /**
     * #441 review (changeset 183): what an unproven day holding a line kept beside a stored
     * look-alike ({@code lookalike_kept}) needs. {@code holders}: the imports holding such a line
     * (deleting all of them removes it); {@code proof}: the import that held the day whole and
     * showed some of them copies ({@code copy_proven_by}), or null; {@code proven}: those copies;
     * {@code booked}: those of them in a confirmed match.
     */
    record KeptCopies(List<String> holders, String proof, List<String> proven, List<String> booked, String whole,
                      List<String> others, List<String> othersBooked, List<UUID> holderIds, UUID proofId, UUID wholeId,
                      List<UUID> otherIds, boolean legacy, LocalDate lockedThrough) {
        KeptCopies(List<String> holders, String proof, List<String> proven, List<String> booked) {
            this(holders, proof, proven, booked, null, List.of(), List.of(), new ArrayList<>(), null, null, List.of());
        }

        KeptCopies(List<String> holders, String proof, List<String> proven, List<String> booked, String whole,
                   List<String> others, List<String> othersBooked, List<UUID> holderIds, UUID proofId, UUID wholeId,
                   List<UUID> otherIds) {
            this(holders, proof, proven, booked, whole, others, othersBooked, holderIds, proofId, wholeId, otherIds, false, null);
        }

        /**
         * Sim r26 S26-01: a day holding twins kept by code from before #441 (no marker): no statement has
         * shown a copy there yet, so the check asks for one holding the day whole, exported now.
         */
        static KeptCopies legacyDay() {
            return new KeptCopies(List.of(), null, List.of(), List.of(), null, List.of(), List.of(), List.of(), null, null,
                    List.of(), true, null);
        }

        /** #441 r6: the statement the check names as proof for deleting imports, and the imports it names. */
        UUID offerProof() {
            return proof != null ? proofId : wholeId;
        }

        /**
         * Sim r26 S26-03: a copy step is one step — it removes the copies from every import holding them —
         * so it names one import (the earliest holder) for its button.
         */
        List<UUID> offerImports() {
            return proof != null ? (holderIds.isEmpty() ? List.of() : List.of(holderIds.get(0)))
                    : wholeId != null ? otherIds : List.of();
        }
    }

    private Map<LocalDate, KeptCopies> keptLookalikes(UUID t, UUID b, Map<LocalDate, Boolean> unconfirmed) {
        List<LocalDate> unproven = unconfirmed.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).toList();
        Map<LocalDate, KeptCopies> out = new TreeMap<>();
        if (unproven.isEmpty()) return out;
        Map<UUID, String> holderNames = new HashMap<>();
        jdbc.query("""
                select l.txn_date, l.description, l.amount, l.running_balance, i.id as import_id, i.file_name, i.first_date,
                       i.last_date, pi.file_name as proof_file, l.copy_proven_by,
                       exists (select 1 from bank_match_statement_lines ml
                                 join bank_matches m on m.id = ml.match_id and m.tenant_id = ml.tenant_id
                                where ml.statement_line_id = l.id and ml.tenant_id = l.tenant_id and not ml.released
                                  and m.status = 'CONFIRMED') as booked,
                       l.copy_proven_by is not null as proven
                from bank_statement_lines l
                join bank_statement_line_sources s on s.line_id = l.id and s.tenant_id = l.tenant_id
                join bank_statement_imports i on i.id = s.import_id and i.tenant_id = s.tenant_id
                left join bank_statement_imports pi on pi.id = l.copy_proven_by and pi.tenant_id = l.tenant_id
                where l.tenant_id = :t and l.bank_account_id = :b and l.lookalike_kept and l.txn_date in (:d)
                order by l.txn_date, l.seq, i.imported_at, i.id""",
                new MapSqlParameterSource("t", t).addValue("b", b).addValue("d", unproven),
                rs -> {
                    LocalDate d = rs.getObject("txn_date", LocalDate.class);
                    KeptCopies k = out.computeIfAbsent(d, x -> new KeptCopies(new ArrayList<>(), null, new ArrayList<>(),
                            new ArrayList<>()));
                    String name = rs.getString("file_name") + " (" + rs.getObject("first_date", LocalDate.class).format(DMY)
                            + "–" + rs.getObject("last_date", LocalDate.class).format(DMY) + ")";
                    if (!k.holders().contains(name)) k.holders().add(name);
                    UUID holder = rs.getObject("import_id", UUID.class);
                    if (!k.holderIds().contains(holder)) k.holderIds().add(holder);
                    holderNames.putIfAbsent(holder, name);
                    if (rs.getBoolean("proven")) {
                        String line = rs.getString("description") + " " + StatementValues.money(rs.getBigDecimal("amount").abs())
                                + " at " + StatementValues.money(rs.getBigDecimal("running_balance"));
                        if (!k.proven().contains(line)) k.proven().add(line);
                        if (rs.getBoolean("booked") && !k.booked().contains(line)) k.booked().add(line);
                        if (k.proof() == null) out.put(d, k = new KeptCopies(k.holders(), rs.getString("proof_file"), k.proven(),
                                k.booked(), null, List.of(), List.of(), k.holderIds(), rs.getObject("copy_proven_by", UUID.class), null,
                                List.of()));
                    }
                });
        // #441 r7: a delete on the word of the statement that marked copies names only imports made before
        // it — a later import may hold a line the bank added after that statement was exported.
        for (Map.Entry<LocalDate, KeptCopies> e : new ArrayList<>(out.entrySet())) {
            KeptCopies k = e.getValue();
            if (k.proofId() == null) continue;
            MapSqlParameterSource q = new MapSqlParameterSource("t", t).addValue("b", b).addValue("h", k.holderIds())
                    .addValue("w", k.proofId()).addValue("d", e.getKey());
            List<UUID> before = jdbc.queryForList("""
                    select i.id from bank_statement_imports i
                    where i.tenant_id = :t and i.id in (:h)
                      and i.imported_at < (select w.imported_at from bank_statement_imports w where w.id = :w and w.tenant_id = :t)
                    order by i.imported_at, i.id""", q, UUID.class);
            // Sim r26 S26-03: the step removes the copies every holder of which predates the statement; with
            // none such (a later import holds them too), it is no step: a newer whole-day export decides.
            Integer removable = jdbc.queryForObject("""
                    select count(*) from bank_statement_lines l
                    where l.tenant_id = :t and l.bank_account_id = :b and l.txn_date = :d and l.lookalike_kept and l.copy_proven_by = :w
                      and not exists (select 1 from bank_statement_line_sources s
                                        join bank_statement_imports x on x.id = s.import_id and x.tenant_id = s.tenant_id
                                       where s.line_id = l.id and s.tenant_id = l.tenant_id
                                         and x.imported_at >= (select w.imported_at from bank_statement_imports w
                                                                where w.id = :w and w.tenant_id = :t))""", q, Integer.class);
            if (removable == null || removable == 0 || before.isEmpty()) {
                out.put(e.getKey(), new KeptCopies(k.holders(), null, List.of(), List.of()));
                continue;
            }
            List<String> names = before.stream().map(holderNames::get).toList();
            out.put(e.getKey(), new KeptCopies(new ArrayList<>(names), k.proof(), k.proven(), k.booked(), null, List.of(), List.of(),
                    new ArrayList<>(before), k.proofId(), null, List.of()));
        }
        // Sim r26 S26-01: an unproven day holding twins code from before #441 kept with no marker (changeset
        // 183 set lookalike_kept = false on every existing row): two lines of the day with the same amount and
        // narration at different running balances, neither marked, that no one import holds both of.
        List<LocalDate> rest = unproven.stream().filter(d -> !out.containsKey(d)).toList();
        if (!rest.isEmpty()) {
            jdbc.queryForList("""
                    select distinct u.txn_date from bank_statement_lines u
                    join bank_statement_lines s on s.tenant_id = u.tenant_id and s.bank_account_id = u.bank_account_id
                         and s.txn_date = u.txn_date and s.id <> u.id and s.amount = u.amount
                         and s.running_balance <> u.running_balance and not s.lookalike_kept
                         and upper(regexp_replace(regexp_replace(s.description, '^\\s+|\\s+$', '', 'g'), '\\s+', ' ', 'g'))
                           = upper(regexp_replace(regexp_replace(u.description, '^\\s+|\\s+$', '', 'g'), '\\s+', ' ', 'g'))
                    where u.tenant_id = :t and u.bank_account_id = :b and u.txn_date in (:d) and not u.lookalike_kept
                      and not exists (select 1 from bank_statement_line_sources a
                                        join bank_statement_line_sources c on c.import_id = a.import_id and c.tenant_id = a.tenant_id
                                       where a.line_id = u.id and a.tenant_id = u.tenant_id and c.line_id = s.id)""",
                    new MapSqlParameterSource("t", t).addValue("b", b).addValue("d", rest), LocalDate.class)
                    .forEach(d -> out.put(d, KeptCopies.legacyDay()));
        }
        // #441 review r3: a statement holding the day whole imported after the kept lines, which
        // showed none of them as a copy (it holds them, or the day differs elsewhere): the day is
        // then settled by making it that statement's alone — deleting every other import holding
        // its lines (lines it holds stay, with their matches).
        for (Map.Entry<LocalDate, KeptCopies> e : new ArrayList<>(out.entrySet())) {
            if (e.getValue().proof() != null || e.getValue().legacy()) continue;
            MapSqlParameterSource q = new MapSqlParameterSource("t", t).addValue("b", b).addValue("d", e.getKey());
            List<Map<String, Object>> whole = jdbc.queryForList("""
                    select w.id, w.file_name, w.first_date, w.last_date from bank_statement_imports w
                    where w.tenant_id = :t and w.bank_account_id = :b and w.first_date < :d and w.last_date > :d
                      and w.imported_at > (select max(i.imported_at) from bank_statement_lines l
                                             join bank_statement_imports i on i.id = l.import_id and i.tenant_id = l.tenant_id
                                            where l.tenant_id = :t and l.bank_account_id = :b and l.txn_date = :d and l.lookalike_kept)
                    order by w.imported_at desc, w.id limit 1""", q);
            if (whole.isEmpty()) continue;
            UUID w = (UUID) whole.get(0).get("id");
            LocalDate wf = day(whole.get(0).get("first_date")), wl = day(whole.get(0).get("last_date"));
            q.addValue("w", w);
            List<UUID> otherIds = new ArrayList<>();
            List<String> others = new ArrayList<>();
            boolean inside = true;
            boolean later = false;
            for (Map<String, Object> o : jdbc.queryForList("""
                    select distinct i.id, i.file_name, i.first_date, i.last_date, i.imported_at,
                           i.imported_at > (select x.imported_at from bank_statement_imports x where x.id = :w and x.tenant_id = :t)
                               as after_w
                    from bank_statement_lines l
                    join bank_statement_line_sources s on s.line_id = l.id and s.tenant_id = l.tenant_id
                    join bank_statement_imports i on i.id = s.import_id and i.tenant_id = s.tenant_id
                    where l.tenant_id = :t and l.bank_account_id = :b and l.txn_date = :d and i.id <> :w
                    order by i.imported_at, i.id""", q)) {
                LocalDate f = day(o.get("first_date")), z = day(o.get("last_date"));

                // #441 r5: an import made after the statement cannot be overridden by it (delete would
                // refuse its lines' history): the check then asks for a newer whole-day export instead.
                if (Boolean.TRUE.equals(o.get("after_w"))) later = true;
                otherIds.add((UUID) o.get("id"));
                others.add(o.get("file_name") + " (" + f.format(DMY) + "–" + z.format(DMY) + ")");
            }
            if (later || otherIds.isEmpty()) continue;
            // Deleting them may only drop lines of days the whole statement holds whole: a line only
            // those imports hold, on any other day, would be lost with them.
            Integer outside = jdbc.queryForObject("""
                    select count(*) from bank_statement_lines l
                    where l.tenant_id = :t and l.bank_account_id = :b
                      and exists (select 1 from bank_statement_line_sources s where s.line_id = l.id and s.tenant_id = l.tenant_id
                                    and s.import_id in (:o))
                      and not exists (select 1 from bank_statement_line_sources s where s.line_id = l.id and s.tenant_id = l.tenant_id
                                        and s.import_id not in (:o))
                      and not (l.txn_date > :wf and l.txn_date < :wl)""",
                    q.addValue("o", otherIds).addValue("wf", wf).addValue("wl", wl), Integer.class);
            if (outside != null && outside > 0) inside = false;
            if (!inside) continue;
            // Booked lines only those imports hold: their matches are undone first.
            List<String> othersBooked = new ArrayList<>();
            jdbc.query("""
                    select l.description, l.amount, l.running_balance, l.txn_date from bank_statement_lines l
                    where l.tenant_id = :t and l.bank_account_id = :b
                      and exists (select 1 from bank_statement_line_sources s where s.line_id = l.id and s.tenant_id = l.tenant_id
                                    and s.import_id in (:o))
                      and not exists (select 1 from bank_statement_line_sources s where s.line_id = l.id and s.tenant_id = l.tenant_id
                                        and s.import_id not in (:o))
                      and exists (select 1 from bank_match_statement_lines ml
                                    join bank_matches m on m.id = ml.match_id and m.tenant_id = ml.tenant_id
                                   where ml.statement_line_id = l.id and ml.tenant_id = l.tenant_id and not ml.released
                                     and m.status = 'CONFIRMED')
                    order by l.txn_date, l.seq""", q,
                    rs -> { othersBooked.add(rs.getString("description") + " " + StatementValues.money(rs.getBigDecimal("amount").abs())
                            + " at " + StatementValues.money(rs.getBigDecimal("running_balance")) + " on "
                            + rs.getObject("txn_date", LocalDate.class).format(DMY)); });
            // Sim r26 S26-02: an import named that brought a line of the finalized period cannot be deleted
            // until that reconciliation is reopened; the step says so first.
            LocalDate lockedThrough = jdbc.queryForList("""
                    select ba.reconciled_through from bank_accounts ba
                    where ba.id = :b and ba.tenant_id = :t
                      and exists (select 1 from bank_statement_lines l where l.tenant_id = :t and l.bank_account_id = :b
                                    and l.import_id in (:o) and l.txn_date <= ba.reconciled_through)""", q, LocalDate.class)
                    .stream().findFirst().orElse(null);
            KeptCopies k = e.getValue();
            out.put(e.getKey(), new KeptCopies(k.holders(), null, k.proven(), k.booked(), (String) whole.get(0).get("file_name"),
                    others, othersBooked, k.holderIds(), null, w, otherIds, false, lockedThrough));
        }
        return out;
    }

    /** #441 r6: the page's view of the deletes the check offers this period (file names for the buttons). */
    private List<BankRecDTOs.DeleteOffer> offersFor(UUID t, Map<LocalDate, KeptCopies> copies) {
        List<BankRecDTOs.DeleteOffer> out = new ArrayList<>();
        copies.forEach((d, k) -> {
            if (k.offerProof() == null || k.offerImports().isEmpty()) return;
            Map<UUID, String> names = new HashMap<>();
            List<UUID> ids = new ArrayList<>(k.offerImports());
            ids.add(k.offerProof());
            jdbc.query("select id, file_name from bank_statement_imports where tenant_id = :t and id in (:i)",
                    new MapSqlParameterSource("t", t).addValue("i", ids),
                    rs -> { names.put(rs.getObject("id", UUID.class), rs.getString("file_name")); });
            out.add(new BankRecDTOs.DeleteOffer(d, k.offerProof(), names.get(k.offerProof()),
                    k.offerImports().stream().map(i -> new BankRecDTOs.ImportRef(i, names.get(i))).toList(),
                    k.proof() != null));
        });
        return out;
    }

    /** #441 r6: a delete the check offers: on {@code day}, deleting {@code imports} on the word of {@code proof}. */
    public record DeleteOffer(LocalDate day, UUID proof, List<UUID> imports, String text, boolean copies) { }

    /**
     * #441 r6: the deletes the check currently offers for the account's unproven days that hold a kept
     * look-alike, each with the statement it names as proof, the imports it names and its text.
     * Delete re-runs this on the server before removing a line with undone match history.
     */
    @Transactional(readOnly = true)
    public List<DeleteOffer> deleteOffers(UUID bankAccountId) {
        UUID t = BankAccountLedgerService.requireTenant();
        Map<LocalDate, Boolean> unproven = new TreeMap<>();
        jdbc.query("""
                select d.txn_date from bank_statement_days d
                where d.tenant_id = :t and d.bank_account_id = :b and not d.order_confirmed and d.merge_unproven""",
                new MapSqlParameterSource("t", t).addValue("b", bankAccountId),
                rs -> { unproven.put(rs.getObject("txn_date", LocalDate.class), true); });
        List<DeleteOffer> out = new ArrayList<>();
        keptLookalikes(t, bankAccountId, unproven).forEach((d, k) -> {
            if (k.offerProof() == null || k.offerImports().isEmpty()) return;
            out.add(new DeleteOffer(d, k.offerProof(), List.copyOf(k.offerImports()),
                    copySteps(d, k, remedy(t, bankAccountId, d, d)), k.proofId() != null));
        });
        return out;
    }

    private static LocalDate day(Object v) {
        return v instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) v;
    }

    /**
     * #441 review r2/r3: the steps that settle a day holding a kept look-alike, in order. Each step
     * leads to the next or settles the day; none dead-ends: a whole-day statement exported now
     * holds a separate kept line (and settles the day) or shows it as a copy; a booked copy's
     * match is undone; deleting the import holding it removes it (with an audit record, the
     * undone match kept); the day's paper totals then prove it. When the whole statement settles
     * nothing and shows no copy, the day is made its alone by deleting the other imports holding
     * its lines (recomputeDays then confirms it). Every step that removes a line says it relies
     * on the statement having been exported after the imports it overrides.
     */
    static String copySteps(LocalDate d, KeptCopies k, Remedy remedy) {
        String holders = String.join(", ", k.holders());
        String fresh = "exported now from the bank (an older download of that day shows it as it was before the line was "
                + "added and proves nothing)";
        if (k.proof() != null) {
            boolean one = k.proven().size() == 1;
            // Sim r26 S26-02/S26-03: the step removes the copies only — from every import holding them — so it
            // works whatever else those imports hold (a finalized line, a booked genuine line).
            return "On " + d.format(DMY) + " the statement " + k.proof() + " held the day whole and shows "
                    + String.join(", ", k.proven()) + (one ? " as a copy" : " as copies")
                    + " of a line already imported at another balance. If " + k.proof() + " was exported from the bank"
                    + " after the import that brought " + (one ? "it" : "them") + ": "
                    + (k.booked().isEmpty() ? "" : "undo the match" + (k.booked().size() == 1 ? "" : "es") + " of "
                            + String.join(", ", k.booked()) + " first, reversing the entr" + (k.booked().size() == 1 ? "y" : "ies")
                            + " the booking created (" + (k.booked().size() == 1 ? "it is" : "they are") + " booked); then ")
                    + "remove " + (one ? "it" : "them") + " with this step's button for " + k.holders().get(0)
                    + " (it removes " + (one ? "the copy" : "the copies") + " from every import holding "
                    + (one ? "it" : "them") + ", each with an audit record; " + holders + " and every other line "
                    + (k.holders().size() == 1 ? "it holds" : "they hold") + " stay), then type " + d.format(DMY)
                    + "'s total debits and total credits from the paper statement. If it is an older download, import a "
                    + "statement that holds " + d.format(DMY) + " whole, " + fresh + ", instead: it holds the kept line if "
                    + "that is a separate line, and the day's totals, typed, then settle it.";
        }
        if (k.whole() != null && !k.others().isEmpty()) {
            return "On " + d.format(DMY) + " an import kept a line beside one already imported with the same amount and "
                    + "narration at another running balance, and the statement " + k.whole() + ", which holds the day "
                    + "whole, does not settle it. If " + k.whole() + " was exported from the bank after those imports, "
                    + "make the day its alone: "
                    + (k.lockedThrough() == null ? "" : remedy.reopenFirst(k.lockedThrough()))
                    + (k.othersBooked().isEmpty() ? "" : "undo the match" + (k.othersBooked().size() == 1 ? "" : "es") + " of "
                            + String.join(", ", k.othersBooked()) + " first, reversing the entr"
                            + (k.othersBooked().size() == 1 ? "y" : "ies") + " the booking created (only those imports hold "
                            + (k.othersBooked().size() == 1 ? "it" : "them") + "); then ")
                    + "delete " + String.join(", ", k.others()) + " (" + k.whole() + " keeps every line it holds, with its "
                    + "matches; the lines it left out go, each with an audit record), then type " + d.format(DMY)
                    + "'s total debits and total credits from the paper statement. If it is an "
                    + "older download, import a statement that holds " + d.format(DMY) + " whole, " + fresh
                    + ", and do the same with that one.";
        }
        return "On " + d.format(DMY) + " an import kept a line beside one already imported with the same amount and "
                + "narration at another running balance: the bank may have moved balances since (a line added earlier), "
                + "so it may be a copy of that line or a separate one, and the paper's totals cannot tell (a copy can stand "
                + "in for a line missing that day). Import a statement that holds " + d.format(DMY) + " whole, " + fresh
                + remedy.orImport(d, d).replaceFirst("^; or import", "; for instance")
                + ". If the kept line is a separate line it holds it, and the day's totals, typed, then settle the day. "
                // Sim r26 S26-05: on such an account the statement's own first day may move too.
                + (remedy.backValues() ? "The bank has moved balances on this account, so that statement may itself keep "
                        + "look-alikes on its first day, whose balances have moved too; the check then names a statement "
                        + "starting earlier, and so on back to the day the bank added the line. To avoid that chain, start "
                        + "it on a day before the earliest line the paper statement shows the bank added later. " : "")
                + "If it shows a copy, this check then names the step that removes it on that statement's word (with a "
                + "button), then the day's totals; if the day still differs elsewhere, the imports to delete on its word "
                + "(with a button for each); if an import was made after it, the check asks for a newer whole-day export "
                + "instead.";
    }

    /**
     * Both totals must agree — not their difference: a missing fee and its missing
     * reversal leave the difference (and the balance) as it was.
     */
    static boolean totalsAgree(BigDecimal[] lines, BigDecimal typedDebits, BigDecimal typedCredits) {
        return lines[0].compareTo(typedDebits) == 0 && lines[1].compareTo(typedCredits) == 0;
    }

    /** P3-21: the overall verdict on the typed day totals, for the page to show without parsing a message. */
    static String dayTotalsVerdict(List<BankRecDTOs.UnprovenDay> verdicts) {
        if (verdicts.isEmpty()) return null;
        if (verdicts.stream().anyMatch(d -> "NEEDED".equals(d.status()) || "COPY".equals(d.status()))) return "PENDING";
        return verdicts.stream().anyMatch(d -> "MISMATCH".equals(d.status())) ? "DISAGREE" : "AGREE";
    }

    /** A day's stored lines: {total debits, total credits}, both positive. */
    static BigDecimal[] dayTotals(List<SL> lines, LocalDate day) {
        BigDecimal debits = BigDecimal.ZERO, credits = BigDecimal.ZERO;
        for (SL l : lines) {
            if (!l.txn().equals(day)) continue;
            if (l.amount().signum() < 0) debits = debits.add(l.amount().negate());
            else credits = credits.add(l.amount());
        }
        return new BigDecimal[]{debits.setScale(2, RoundingMode.HALF_UP), credits.setScale(2, RoundingMode.HALF_UP)};
    }

    /**
     * Review P2-3: the paper statement's total debits and credits for days of a draft,
     * kept with the reconciliation (audit). Replaces what was typed for those days;
     * both totals null removes a day.
     */
    @Transactional
    public BankRecDTOs.Reconciliation setDayTotals(UUID recId, BankRecDTOs.DayTotalsInput in) {
        UUID t = BankAccountLedgerService.requireTenant();
        Rec r0 = load(t, recId, false);
        matches.lockAccount(t, r0.bankAccountId());
        Rec r = load(t, recId, true);
        requireDraft(r);
        if (in == null || in.days() == null) throw new BusinessRuleViolationException("Give the days and their totals");
        UUID user = BankStatementImportService.currentUserId();
        for (BankRecDTOs.DayTotalInput d : in.days()) {
            if (d.date() == null || d.date().isBefore(r.from()) || d.date().isAfter(r.to())) {
                throw new BusinessRuleViolationException("A day's totals must be for a day of the period ("
                        + r.from().format(DMY) + " – " + r.to().format(DMY) + ")");
            }
            MapSqlParameterSource p = new MapSqlParameterSource("t", t).addValue("r", recId).addValue("d", d.date());
            jdbc.update("delete from bank_rec_day_totals where tenant_id = :t and reconciliation_id = :r and txn_date = :d", p);
            if (d.debits() == null && d.credits() == null) continue;
            if (d.debits() == null || d.credits() == null) {
                throw new BusinessRuleViolationException("Give both the total debits and the total credits for "
                        + d.date().format(DMY) + " (0.00 when there are none)");
            }
            if (d.debits().signum() < 0 || d.credits().signum() < 0) {
                throw new BusinessRuleViolationException("Day totals are amounts without a sign");
            }
            jdbc.update("""
                    insert into bank_rec_day_totals (id, tenant_id, reconciliation_id, txn_date, debit_total, credit_total,
                                                     entered_by, entered_at)
                    values (:id, :t, :r, :d, :dr, :cr, :u, now())""",
                    p.addValue("id", UUID.randomUUID()).addValue("dr", money(d.debits())).addValue("cr", money(d.credits()))
                            .addValue("u", user));
        }
        return view(t, load(t, recId, false));
    }

    /** Spec §4 precondition 6: the opening items explain exactly the gap between the statement and the books at the start. */
    private static BankRecDTOs.Check openingCheck(BigDecimal opening, BigDecimal openingTotal, BigDecimal bookAtStart,
                                                 List<BankRecDTOs.OpeningItem> items, LocalDate from) {
        String day = from.minusDays(1).format(DMY);
        Optional<BankRecDTOs.OpeningItem> late = items.stream().filter(o -> !o.itemDate().isBefore(from)).findFirst();
        if (late.isPresent()) {
            return new BankRecDTOs.Check("OPENING_ITEMS", false, "Opening item '" + late.get().description() + "' is dated "
                    + late.get().itemDate().format(DMY) + "; opening items are dated before the reconciliation starts ("
                    + from.format(DMY) + ")");
        }
        if (opening == null) {
            return new BankRecDTOs.Check("OPENING_ITEMS", false, "The statement balance at " + day + " is not known yet");
        }
        BigDecimal explained = opening.add(openingTotal);
        boolean ok = explained.compareTo(bookAtStart) == 0;
        return new BankRecDTOs.Check("OPENING_ITEMS", ok, "Statement balance at " + day + " " + StatementValues.money(opening)
                + " + outstanding items " + StatementValues.money(openingTotal) + " = " + StatementValues.money(explained)
                + (ok ? " = book balance" : "; the books show " + StatementValues.money(bookAtStart)
                        + ". The outstanding items must explain the difference of "
                        + StatementValues.money(bookAtStart.subtract(explained))));
    }

    private static BigDecimal sum(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal total(List<BankRecDTOs.RecItem> items) {
        return items.stream().map(BankRecDTOs.RecItem::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String uncheckedString(ResultSet rs, String col) {
        try {
            return rs.getString(col);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The IBAN (else the account number) with everything but the last four characters masked. */
    static String maskIban(String iban, String accountNumber) {
        boolean isIban = iban != null && !iban.isBlank();
        String v = isIban ? iban : accountNumber;
        if (v == null) return "";
        String s = v.replaceAll("\\s", "");
        if (s.length() <= 4) return s;
        String masked = "•".repeat(Math.min(s.length() - 4, 8)) + s.substring(s.length() - 4);
        // F14-48: an IBAN keeps its country code, which is what tells a reader (and the
        // PDF's label) that it is an IBAN rather than an account number.
        return isIban && s.length() > 6 && Character.isLetter(s.charAt(0)) ? s.substring(0, 2).toUpperCase() + masked : masked;
    }

    String userName(UUID userId) {
        if (userId == null) return null;
        return jdbc.queryForList("select name from users where id = :u", new MapSqlParameterSource("u", userId), String.class)
                .stream().findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ finalize and reopen

    /**
     * Spec §4 finalize, in one transaction: the bank account's row FOR UPDATE (so
     * a posting holding it FOR SHARE finishes first, and one arriving later is
     * refused by the lock), every figure recomputed, every precondition re-checked,
     * the figures and the snapshot stored, and the account locked through
     * {@code period_to}.
     */
    @Transactional
    public BankRecDTOs.Reconciliation finalizeRec(UUID recId) {
        UUID t = BankAccountLedgerService.requireTenant();
        Rec r0 = load(t, recId, false);
        matches.lockAccount(t, r0.bankAccountId());
        Rec r = load(t, recId, true);
        requireDraft(r);
        BankRecDTOs.Reconciliation v = compute(t, r);
        List<String> failing = v.checks().stream().filter(c -> !c.ok()).map(BankRecDTOs.Check::message).toList();
        if (!failing.isEmpty()) {
            throw new BusinessRuleViolationException("This reconciliation cannot be finalized: " + String.join("; ", failing));
        }
        UUID user = BankStatementImportService.currentUserId();
        Instant now = Instant.now(clock);
        BankRecDTOs.Reconciliation snap = new BankRecDTOs.Reconciliation(v.id(), v.bankAccountId(), v.bankLabel(),
                v.bankName(), v.ibanMasked(), v.leaves(), v.periodFrom(), v.periodTo(), "FINALIZED", v.first(),
                v.statementOpening(), v.statementClosing(), v.closingTyped(), v.statementMovement(), v.bookBalance(),
                v.bookBalanceAtStart(), v.openingItemsTotal(), v.depositsInTransit(), v.unpresentedPayments(),
                v.bookedAfterPeriod(), v.unrecordedCredits(), v.unrecordedDebits(), v.adjustedBank(), v.adjustedBook(),
                v.difference(), v.depositsInTransitItems(), v.unpresentedItems(), v.bookedAfterItems(),
                v.unrecordedItems(), v.withoutEvidenceCount(), v.matchedByMethod(), v.checks(), false, v.preparedAt(),
                v.preparedByName(), now, userName(user), null, null, null, v.unprovenDays(), v.dayTotalsVerdict(), null);
        String snapshot;
        try {
            snapshot = json.writeValueAsString(snap);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The reconciliation snapshot could not be written", e);
        }
        jdbc.update("""
                update bank_reconciliations set status = 'FINALIZED', statement_opening = :opening, statement_closing = :closing,
                       book_balance = :book, deposits_in_transit = :dit, unpresented_payments = :up, unrecorded_items = :u,
                       difference = :diff, finalized_by = :user, finalized_at = :now, snapshot = cast(:snap as jsonb)
                where id = :id and tenant_id = :t and status = 'DRAFT'""",
                new MapSqlParameterSource("id", recId).addValue("t", t).addValue("opening", v.statementOpening())
                        .addValue("closing", v.statementClosing()).addValue("book", v.bookBalance())
                        .addValue("dit", v.depositsInTransit()).addValue("up", v.unpresentedPayments())
                        .addValue("u", v.unrecordedCredits().subtract(v.unrecordedDebits()))
                        .addValue("diff", v.difference()).addValue("user", user).addValue("now", Timestamp.from(now))
                        .addValue("snap", snapshot));
        jdbc.update("""
                update bank_accounts set reconciled_through = :to, rec_start_date = case when :first then :from else rec_start_date end
                where id = :b and tenant_id = :t""",
                new MapSqlParameterSource("b", r.bankAccountId()).addValue("t", t).addValue("to", r.to())
                        .addValue("from", r.from()).addValue("first", v.first()));
        log.info("Bank reconciliation {} finalized: bank account {} reconciled through {}", recId, r.bankAccountId(), r.to());
        return view(t, load(t, recId, false));
    }

    /**
     * Spec §4 reopen: only the latest finalized reconciliation of a bank account,
     * with a reason. The row becomes REOPENED (it stays, with who, when and why);
     * the lock steps back to the previous finalized period's end, or goes. Matches
     * stay as they are.
     */
    @Transactional
    public BankRecDTOs.Reconciliation reopen(UUID recId, String reason) {
        UUID t = BankAccountLedgerService.requireTenant();
        if (reason == null || reason.isBlank()) throw new BusinessRuleViolationException("Say why the reconciliation is reopened");
        Rec r0 = load(t, recId, false);
        matches.lockAccount(t, r0.bankAccountId());
        Rec r = load(t, recId, true);
        if (!"FINALIZED".equals(r.status())) {
            throw new BusinessRuleViolationException("Only a finalized reconciliation can be reopened; this one is " + r.status());
        }
        Rec latest = latestFinalized(t, r.bankAccountId(), null).orElseThrow();
        if (!latest.id().equals(r.id())) {
            throw new BusinessRuleViolationException("Only the latest finalized reconciliation ("
                    + latest.from().format(DMY) + " – " + latest.to().format(DMY) + ") can be reopened");
        }
        draftOf(t, r.bankAccountId()).ifPresent(d -> {
            throw new BusinessRuleViolationException("Discard the draft reconciliation for " + d.from().format(DMY) + " – "
                    + d.to().format(DMY) + " first");
        });
        LocalDate back = latestFinalized(t, r.bankAccountId(), r.id()).map(Rec::to).orElse(null);
        UUID user = BankStatementImportService.currentUserId();
        jdbc.update("""
                update bank_reconciliations set status = 'REOPENED', reopened_by = :u, reopened_at = now(), reopen_reason = :why
                where id = :id and tenant_id = :t""",
                new MapSqlParameterSource("id", recId).addValue("t", t).addValue("u", user).addValue("why", reason.trim()));
        jdbc.update("update bank_accounts set reconciled_through = :r where id = :b and tenant_id = :t",
                new MapSqlParameterSource("b", r.bankAccountId()).addValue("t", t).addValue("r", back));
        // Review P3-10: changeset 170 left the days of finalized periods alone. Once
        // the period is open again, its days built from more than one import, with no
        // order recorded at import, are unconfirmed, as 170 marked open periods — and
        // unproven, as 171 marks them: the old de-duplication may have taken a
        // genuine line for an identical one.
        jdbc.update("""
                insert into bank_statement_days (tenant_id, bank_account_id, txn_date, order_confirmed, merge_unproven, updated_at)
                select l.tenant_id, l.bank_account_id, l.txn_date, false, true, now()
                  from (select distinct tenant_id, bank_account_id, txn_date from bank_statement_lines
                         where tenant_id = :t and bank_account_id = :b and txn_date between :from and :to) l
                 where (select count(*) from bank_statement_imports i
                         where i.tenant_id = l.tenant_id and i.bank_account_id = l.bank_account_id
                           and i.first_date is not null and i.last_date is not null
                           and l.txn_date between i.first_date and i.last_date) > 1
                on conflict (bank_account_id, txn_date) do nothing""",
                new MapSqlParameterSource("b", r.bankAccountId()).addValue("t", t).addValue("from", r.from())
                        .addValue("to", r.to()));
        log.warn("Bank reconciliation {} ({} – {}) reopened by {}: {}", recId, r.from(), r.to(), user, reason.trim());
        return view(t, load(t, recId, false));
    }

    // ------------------------------------------------------------------ opening items

    @Transactional(readOnly = true)
    public List<BankRecDTOs.OpeningItem> openingItems(UUID bankAccountId) {
        UUID t = BankAccountLedgerService.requireTenant();
        ledgers.requireBankAccount(bankAccountId);
        return matches.openingItems(t, bankAccountId);
    }

    @Transactional
    public BankRecDTOs.OpeningItem addOpeningItem(UUID bankAccountId, BankRecDTOs.OpeningItemInput in) {
        UUID t = BankAccountLedgerService.requireTenant();
        ledgers.requireBankAccount(bankAccountId);
        matches.lockAccount(t, bankAccountId);
        requireOpeningEditable(t, bankAccountId);
        validate(t, bankAccountId, in);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into bank_rec_opening_items (id, tenant_id, bank_account_id, item_date, description, reference, cheque_no,
                                                    amount, created_by, created_at)
                values (:id, :t, :b, :d, :desc, :ref, :chq, :amt, :u, now())""",
                openingParams(in).addValue("id", id).addValue("t", t).addValue("b", bankAccountId)
                        .addValue("u", BankStatementImportService.currentUserId()));
        return item(t, bankAccountId, id);
    }

    @Transactional
    public BankRecDTOs.OpeningItem updateOpeningItem(UUID bankAccountId, UUID itemId, BankRecDTOs.OpeningItemInput in) {
        UUID t = BankAccountLedgerService.requireTenant();
        ledgers.requireBankAccount(bankAccountId);
        matches.lockAccount(t, bankAccountId);
        requireOpeningEditable(t, bankAccountId);
        requireUnmatched(item(t, bankAccountId, itemId));
        validate(t, bankAccountId, in);
        jdbc.update("""
                update bank_rec_opening_items set item_date = :d, description = :desc, reference = :ref, cheque_no = :chq,
                       amount = :amt, updated_at = now() where id = :id and tenant_id = :t and bank_account_id = :b""",
                openingParams(in).addValue("id", itemId).addValue("t", t).addValue("b", bankAccountId));
        return item(t, bankAccountId, itemId);
    }

    @Transactional
    public void deleteOpeningItem(UUID bankAccountId, UUID itemId) {
        UUID t = BankAccountLedgerService.requireTenant();
        ledgers.requireBankAccount(bankAccountId);
        matches.lockAccount(t, bankAccountId);
        requireOpeningEditable(t, bankAccountId);
        requireUnmatched(item(t, bankAccountId, itemId));
        Integer history = jdbc.queryForObject("select count(*) from bank_match_book_items where tenant_id = :t and opening_item_id = :id",
                new MapSqlParameterSource("t", t).addValue("id", itemId), Integer.class);
        if (history != null && history > 0) {
            throw new BusinessRuleViolationException("This opening item has match history, which is kept as the audit trail");
        }
        jdbc.update("delete from bank_rec_opening_items where id = :id and tenant_id = :t and bank_account_id = :b",
                new MapSqlParameterSource("id", itemId).addValue("t", t).addValue("b", bankAccountId));
    }

    private BankRecDTOs.OpeningItem item(UUID t, UUID bankAccountId, UUID itemId) {
        return matches.openingItems(t, bankAccountId).stream().filter(o -> o.id().equals(itemId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Opening item not found"));
    }

    private static void requireUnmatched(BankRecDTOs.OpeningItem o) {
        if (o.matchId() != null) throw new BusinessRuleViolationException("This opening item is matched; undo the match first");
    }

    /** Editable until the bank account's first reconciliation is finalized (spec §4). */
    private void requireOpeningEditable(UUID t, UUID bankAccountId) {
        if (latestFinalized(t, bankAccountId, null).isPresent()) {
            throw new BusinessRuleViolationException("Opening items are fixed once the first reconciliation is finalized");
        }
    }

    private void validate(UUID t, UUID bankAccountId, BankRecDTOs.OpeningItemInput in) {
        if (in == null || in.itemDate() == null || in.amount() == null || in.description() == null || in.description().isBlank()) {
            throw new BusinessRuleViolationException("An opening item needs a date, a description and an amount");
        }
        if (in.amount().setScale(2, RoundingMode.HALF_UP).signum() == 0) {
            throw new BusinessRuleViolationException("An opening item moves money: its amount cannot be 0.00");
        }
        if (in.itemDate().isAfter(LocalDate.now(clock))) {
            throw new BusinessRuleViolationException("An opening item cannot be dated in the future");
        }
        draftOf(t, bankAccountId).ifPresent(d -> {
            if (!in.itemDate().isBefore(d.from())) {
                throw new BusinessRuleViolationException("An opening item is outstanding at the start: date it before "
                        + d.from().format(DMY));
            }
        });
    }

    private static MapSqlParameterSource openingParams(BankRecDTOs.OpeningItemInput in) {
        return new MapSqlParameterSource("d", in.itemDate()).addValue("desc", in.description().trim())
                .addValue("ref", blankToNull(in.reference())).addValue("chq", blankToNull(in.chequeNo()))
                .addValue("amt", in.amount().setScale(2, RoundingMode.HALF_UP));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
