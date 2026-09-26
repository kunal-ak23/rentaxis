package com.datagami.rentaxis.core.service.bank;

import com.datagami.rentaxis.api.dto.bank.BankRecDTOs;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.ledger.BankLockService;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.payables.IssuedChequeService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Matching statement lines to ledger movements (finance-ops spec §3 "Book side"
 * and "Matching").
 *
 * <ul>
 *   <li>Book items: journal lines on any leaf of the bank account's set, except
 *       OB. Journal lines are never touched: "matched" is the live join.</li>
 *   <li>Auto-match proposes by rule (cheque number, reference, deposit group,
 *       amount and date, contra pairs). <b>Every proposal is SUGGESTED</b>; only a
 *       confirmation makes it count.</li>
 *   <li>Every match keeps Σ statement = Σ book (debit − credit); a contra match
 *       has one side, summing to zero.</li>
 * </ul>
 *
 * <p>Native SQL with an explicit {@code tenant_id} on every statement: statement
 * line and journal line ids arrive in request bodies (spec §3 services).</p>
 */
@Service
public class BankMatchService {

    public enum Method { AUTO_CHEQUE, AUTO_REFERENCE, AUTO_AMOUNT_DATE, AUTO_GROUP, MANUAL, CREATED, CONTRA }

    private final BankAccountLedgerService ledgers;
    private final NamedParameterJdbcTemplate jdbc;
    private final PostingService posting;
    private final IssuedChequeService issuedCheques;
    private final Clock clock;
    private final com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService fiscal;
    private final com.datagami.rentaxis.core.security.PropertyScope propertyScope;
    private final BankLockService bankLock;

    public BankMatchService(BankAccountLedgerService ledgers, NamedParameterJdbcTemplate jdbc, PostingService posting,
                            IssuedChequeService issuedCheques, Clock clock,
                            com.datagami.rentaxis.core.security.PropertyScope propertyScope,
                            com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService fiscal,
                            BankLockService bankLock) {
        this.bankLock = bankLock;
        this.fiscal = fiscal;
        this.propertyScope = propertyScope;
        this.ledgers = ledgers;
        this.jdbc = jdbc;
        this.posting = posting;
        this.issuedCheques = issuedCheques;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ rows

    record SLine(UUID id, long seq, LocalDate txn, LocalDate value, String description, String reference,
                 String chequeNo, BigDecimal amount, BigDecimal balance, UUID matchId, String matchStatus) {
        LocalDate date() { return value != null ? value : txn; }
    }

    record BItem(UUID jlId, UUID entryId, String entryNumber, String docType, String sourceType, UUID sourceId,
                 LocalDate date, String narration, UUID accountId, String accountName, String counter,
                 BigDecimal amount, String chequeNo, LocalDate depositedAt, String paymentRef, String runNo,
                 UUID reversalOfId, UUID reversedById, UUID matchId, String matchStatus) { }

    private static final String LINE_SQL = """
            select l.id, l.seq, l.txn_date, l.value_date, l.description, l.reference, l.cheque_no, l.amount,
                   l.running_balance, live.match_id, live.status
            from bank_statement_lines l
            left join lateral (select ml.match_id, m.status from bank_match_statement_lines ml
                               join bank_matches m on m.id = ml.match_id
                               where ml.statement_line_id = l.id and not ml.released limit 1) live on true
            where l.tenant_id = :t and l.bank_account_id = :b
            """;

    private static final String ITEM_SQL = """
            select jl.id as jl_id, je.id as entry_id, je.entry_number, je.doc_type, je.source_type, je.source_id,
                   je.entry_date, coalesce(jl.narration, je.narration) as narration, jl.account_id, a.name as account_name,
                   (select a2.name from journal_lines o join accounts a2 on a2.id = o.account_id
                     where o.journal_entry_id = je.id and o.id <> jl.id order by o.line_no limit 1) as counter,
                   (jl.debit - jl.credit) as amount,
                   case je.source_type
                     when 'CHEQUE' then (select c.cheque_number from cheques c where c.id = je.source_id and c.tenant_id = je.tenant_id)
                     when 'ISSUED_CHEQUE' then (select ic.cheque_number from issued_cheques ic where ic.id = je.source_id and ic.tenant_id = je.tenant_id)
                     when 'VOUCHER' then (select v.cheque_number from vouchers v where v.id = je.source_id and v.tenant_id = je.tenant_id)
                   end as cheque_no,
                   case when je.source_type = 'CHEQUE'
                     then (select c.deposited_at from cheques c where c.id = je.source_id and c.tenant_id = je.tenant_id) end as deposited_at,
                   case when je.source_type = 'VOUCHER'
                     then (select v.payment_reference from vouchers v where v.id = je.source_id and v.tenant_id = je.tenant_id) end as payment_ref,
                   case when je.source_type = 'VOUCHER'
                     then (select r.run_number from vouchers v join payment_runs r on r.id = v.payment_run_id
                            where v.id = je.source_id and v.tenant_id = je.tenant_id) end as run_no,
                   je.reversal_of_id, je.reversed_by_id, live.match_id, live.status
            from journal_lines jl
            join journal_entries je on je.id = jl.journal_entry_id and je.tenant_id = jl.tenant_id
            join accounts a on a.id = jl.account_id
            left join lateral (select i.match_id, m.status from bank_match_book_items i
                               join bank_matches m on m.id = i.match_id
                               where i.journal_line_id = jl.id and not i.released limit 1) live on true
            where jl.tenant_id = :t and jl.account_id in (:leaves) and je.doc_type <> 'OB'
            """;

    private static SLine sline(ResultSet rs) throws SQLException {
        return new SLine(rs.getObject("id", UUID.class), rs.getLong("seq"), rs.getObject("txn_date", LocalDate.class),
                rs.getObject("value_date", LocalDate.class), rs.getString("description"), rs.getString("reference"),
                rs.getString("cheque_no"), rs.getBigDecimal("amount"), rs.getBigDecimal("running_balance"),
                rs.getObject("match_id", UUID.class), rs.getString("status"));
    }

    private static BItem bitem(ResultSet rs) throws SQLException {
        return new BItem(rs.getObject("jl_id", UUID.class), rs.getObject("entry_id", UUID.class),
                rs.getString("entry_number"), rs.getString("doc_type"), rs.getString("source_type"),
                rs.getObject("source_id", UUID.class), rs.getObject("entry_date", LocalDate.class),
                rs.getString("narration"), rs.getObject("account_id", UUID.class), rs.getString("account_name"),
                rs.getString("counter"), rs.getBigDecimal("amount"), rs.getString("cheque_no"),
                rs.getObject("deposited_at", LocalDate.class), rs.getString("payment_ref"), rs.getString("run_no"),
                rs.getObject("reversal_of_id", UUID.class), rs.getObject("reversed_by_id", UUID.class),
                rs.getObject("match_id", UUID.class), rs.getString("status"));
    }

    private List<SLine> lines(UUID t, UUID bankAccountId, LocalDate from, LocalDate to) {
        return jdbc.query(LINE_SQL + " and (cast(:from as date) is null or l.txn_date >= :from)"
                        + " and (cast(:to as date) is null or l.txn_date <= :to) order by l.txn_date, l.seq",
                new MapSqlParameterSource("t", t).addValue("b", bankAccountId).addValue("from", from).addValue("to", to),
                (rs, i) -> sline(rs));
    }

    /** The day reconciliation starts for the account: the first finalized one's start, else the earliest draft's. */
    private LocalDate recStart(UUID t, UUID bankAccountId) {
        return jdbc.queryForList("""
                select coalesce(b.rec_start_date, (select min(r.period_from) from bank_reconciliations r
                                                   where r.tenant_id = b.tenant_id and r.bank_account_id = b.id))
                from bank_accounts b where b.tenant_id = :t and b.id = :b""",
                new MapSqlParameterSource("t", t).addValue("b", bankAccountId), LocalDate.class)
                .stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    private List<BItem> items(UUID t, Set<UUID> leaves, LocalDate from, LocalDate to) {
        if (leaves.isEmpty()) return List.of();
        return jdbc.query(ITEM_SQL + " and (cast(:from as date) is null or je.entry_date >= :from)"
                        + " and (cast(:to as date) is null or je.entry_date <= :to) order by je.entry_date, je.entry_number, jl.line_no",
                new MapSqlParameterSource("t", t).addValue("leaves", leaves).addValue("from", from).addValue("to", to),
                (rs, i) -> bitem(rs));
    }

    // ------------------------------------------------------------------ workspace

    @Transactional(readOnly = true)
    public BankRecDTOs.Workspace workspace(UUID bankAccountId, LocalDate from, LocalDate to, String state) {
        UUID t = BankAccountLedgerService.requireTenant();
        ledgers.requireBankAccount(bankAccountId);
        List<BankRecDTOs.Leaf> leaves = ledgers.leaves(bankAccountId);
        Set<UUID> leafIds = leaves.stream().map(BankRecDTOs.Leaf::id).collect(Collectors.toCollection(LinkedHashSet::new));
        String s = state == null ? "ALL" : state.toUpperCase(Locale.ROOT);
        Predicate<String> keep = switch (s) {
            case "UNMATCHED" -> st -> st == null;
            case "SUGGESTED" -> "SUGGESTED"::equals;
            default -> st -> true;
        };
        List<SLine> ls = lines(t, bankAccountId, from, to).stream().filter(l -> keep.test(l.matchStatus())).toList();
        List<BItem> is = items(t, leafIds, from, to).stream().filter(i -> keep.test(i.matchStatus())).toList();
        Set<UUID> matchIds = new LinkedHashSet<>();
        ls.forEach(l -> { if (l.matchId() != null) matchIds.add(l.matchId()); });
        is.forEach(i -> { if (i.matchId() != null) matchIds.add(i.matchId()); });
        return new BankRecDTOs.Workspace(bankAccountId, leaves, leaves.isEmpty(),
                ls.stream().map(l -> new BankRecDTOs.StatementLine(l.id(), l.seq(), l.txn(), l.value(), l.description(),
                        l.reference(), l.chequeNo(), l.amount(), l.balance(), l.matchId(), l.matchStatus())).toList(),
                is.stream().map(i -> new BankRecDTOs.BookItem(i.jlId(), i.entryId(), i.entryNumber(), i.docType(),
                        i.date(), i.narration(), i.accountId(), i.accountName(), i.counter(), i.amount(), i.chequeNo(),
                        i.matchId(), i.matchStatus(), i.reversalOfId(), i.reversedById())).toList(),
                matchesWithOpening(t, matchIds, openingItems(t, bankAccountId), keep),
                openingItems(t, bankAccountId).stream().filter(o -> keep.test(o.matchStatus())).toList(),
                bankLock.reconciledThrough(bankAccountId).orElse(null));
    }

    /** The workspace's matches, plus those that hold only opening items (spec §4). */
    private List<BankRecDTOs.Match> matchesWithOpening(UUID t, Set<UUID> matchIds, List<BankRecDTOs.OpeningItem> opening,
                                                       Predicate<String> keep) {
        Set<UUID> ids = new LinkedHashSet<>(matchIds);
        opening.stream().filter(o -> keep.test(o.matchStatus()) && o.matchId() != null).forEach(o -> ids.add(o.matchId()));
        return matches(t, ids);
    }

    /** The bank account's opening items with their live match (spec §4). */
    public List<BankRecDTOs.OpeningItem> openingItems(UUID t, UUID bankAccountId) {
        return jdbc.query("""
                select o.id, o.bank_account_id, o.item_date, o.description, o.reference, o.cheque_no, o.amount,
                       live.match_id, live.status
                from bank_rec_opening_items o
                left join lateral (select i.match_id, m.status from bank_match_book_items i
                                   join bank_matches m on m.id = i.match_id
                                   where i.opening_item_id = o.id and not i.released limit 1) live on true
                where o.tenant_id = :t and o.bank_account_id = :b
                order by o.item_date, o.created_at, o.id""",
                new MapSqlParameterSource("t", t).addValue("b", bankAccountId),
                (rs, i) -> new BankRecDTOs.OpeningItem(rs.getObject("id", UUID.class),
                        rs.getObject("bank_account_id", UUID.class), rs.getObject("item_date", LocalDate.class),
                        rs.getString("description"), rs.getString("reference"), rs.getString("cheque_no"),
                        rs.getBigDecimal("amount"), rs.getObject("match_id", UUID.class), rs.getString("status")));
    }

    private List<BankRecDTOs.Match> matches(UUID t, Collection<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        MapSqlParameterSource p = new MapSqlParameterSource("t", t).addValue("m", ids);
        Map<UUID, List<UUID>> sl = new HashMap<>();
        Map<UUID, BigDecimal> sTotal = new HashMap<>();
        jdbc.query("""
                select ml.match_id, ml.statement_line_id, l.amount from bank_match_statement_lines ml
                join bank_statement_lines l on l.id = ml.statement_line_id
                where ml.tenant_id = :t and ml.match_id in (:m)""", p, rs -> {
            UUID m = rs.getObject("match_id", UUID.class);
            sl.computeIfAbsent(m, k -> new ArrayList<>()).add(rs.getObject("statement_line_id", UUID.class));
            sTotal.merge(m, rs.getBigDecimal("amount"), BigDecimal::add);
        });
        Map<UUID, List<UUID>> bl = new HashMap<>();
        Map<UUID, BigDecimal> bTotal = new HashMap<>();
        Map<UUID, List<UUID>> ol = new HashMap<>();
        jdbc.query("""
                select i.match_id, i.journal_line_id, i.opening_item_id,
                       coalesce(jl.debit - jl.credit, o.amount) as amount from bank_match_book_items i
                left join journal_lines jl on jl.id = i.journal_line_id
                left join bank_rec_opening_items o on o.id = i.opening_item_id
                where i.tenant_id = :t and i.match_id in (:m)""", p, rs -> {
            UUID m = rs.getObject("match_id", UUID.class);
            UUID j = rs.getObject("journal_line_id", UUID.class);
            if (j != null) bl.computeIfAbsent(m, k -> new ArrayList<>()).add(j);
            else ol.computeIfAbsent(m, k -> new ArrayList<>()).add(rs.getObject("opening_item_id", UUID.class));
            bTotal.merge(m, rs.getBigDecimal("amount"), BigDecimal::add);
        });
        Map<UUID, List<String>> docs = new HashMap<>();
        Map<UUID, LocalDate> entryDate = new HashMap<>();
        jdbc.query("""
                select i.match_id, je.doc_type, min(je.entry_date) as d from bank_match_book_items i
                join bank_matches m on m.id = i.match_id and m.method = 'CREATED'
                join journal_lines jl on jl.id = i.journal_line_id
                join journal_entries je on je.id = jl.journal_entry_id
                where i.tenant_id = :t and i.match_id in (:m) group by i.match_id, je.doc_type""", p, rs -> {
            UUID m = rs.getObject("match_id", UUID.class);
            docs.computeIfAbsent(m, k -> new ArrayList<>()).add(rs.getString("doc_type"));
            LocalDate d = rs.getObject("d", LocalDate.class);
            entryDate.merge(m, d, (a, b) -> a.isBefore(b) ? a : b);
        });
        return jdbc.query("select * from bank_matches where tenant_id = :t and id in (:m) order by created_at", p,
                (rs, i) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    List<String> created = docs.getOrDefault(id, List.of());
                    boolean reversible = !created.isEmpty() && REVERSIBLE.containsAll(created)
                            && !"UNDONE".equals(rs.getString("status"));
                    return new BankRecDTOs.Match(id, rs.getString("method"), rs.getString("status"),
                            rs.getString("confidence"), sl.getOrDefault(id, List.of()), bl.getOrDefault(id, List.of()),
                            sTotal.getOrDefault(id, BigDecimal.ZERO), bTotal.getOrDefault(id, BigDecimal.ZERO),
                            instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("confirmed_at")), created,
                            reversible ? reverseDate(entryDate.get(id)) : null, ol.getOrDefault(id, List.of()));
                });
    }

    /** What "undo and reverse" can reverse from here: a BNK (PostingService.reverse) and a BPC (unpresent). */
    static final Set<String> REVERSIBLE = Set.of("BNK", "BPC");

    /** The entry's own date while its period is open, else today. */
    LocalDate reverseDate(LocalDate entryDate) {
        LocalDate today = LocalDate.now(clock);
        if (entryDate == null || entryDate.isAfter(today)) return today;
        // Asked, not asserted: a refusal thrown out of the fiscal bean would mark this
        // (workspace's) transaction rollback-only even though it is caught (S16-01).
        return fiscal.isOpen(entryDate) ? entryDate : today;
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    // ------------------------------------------------------------------ auto-match

    /**
     * Spec §3 rules 1–5, in order; a line or item used by one rule is not offered
     * to a later one. The window's earlier suggestions are dropped first. A
     * reversed entry and its mirror are paired as a contra up front, so neither
     * is offered to rules 1–4 as a real movement.
     */
    @Transactional
    public BankRecDTOs.AutoMatchResult autoMatch(UUID bankAccountId, LocalDate from, LocalDate to) {
        UUID t = BankAccountLedgerService.requireTenant();
        ledgers.requireBankAccount(bankAccountId);
        Set<UUID> leaves = ledgers.requireLeafSet(bankAccountId);
        lockAccount(t, bankAccountId);
        int window = jdbc.queryForList("select match_window_days from bank_statement_profiles where tenant_id = :t and bank_account_id = :b",
                new MapSqlParameterSource("t", t).addValue("b", bankAccountId), Integer.class).stream().findFirst().orElse(3);
        dropSuggestions(t, bankAccountId, from, to);

        // Spec §4: nothing inside a finalized reconciliation is proposed.
        LocalDate locked = bankLock.reconciledThrough(bankAccountId).orElse(null);
        List<SLine> pool = new ArrayList<>(lines(t, bankAccountId, from, to).stream()
                .filter(l -> l.matchId() == null && (locked == null || l.txn().isAfter(locked))).toList());
        LocalDate lo = from == null ? null : from.minusDays(window);
        LocalDate hi = to == null ? null : to.plusDays(window);
        // F14-47: a book item dated before the reconciliation starts is carried by the
        // opening balance and its opening items; proposing it against a statement line
        // would count it twice. Only an entry confirmed "not on the statement" (F14-20)
        // is still outstanding from before the start and may be matched.
        LocalDate recStart = recStart(t, bankAccountId);
        Set<UUID> offStatement = recStart == null ? Set.of() : new HashSet<>(jdbc.queryForList(
                "select journal_entry_id from bank_off_statement_items where tenant_id = :t and bank_account_id = :b",
                new MapSqlParameterSource("t", t).addValue("b", bankAccountId), UUID.class));
        List<BItem> books = new ArrayList<>(items(t, leaves, lo, hi).stream().filter(i -> i.matchId() == null)
                .filter(i -> recStart == null || !i.date().isBefore(recStart) || offStatement.contains(i.entryId()))
                .toList());
        Map<String, Integer> count = new LinkedHashMap<>();

        // Rule 5 (book side), taken first: an original and its reversal mirror.
        List<List<BItem>> contraBooks = new ArrayList<>();
        Map<UUID, BItem> byEntryAndAccount = new HashMap<>();
        for (BItem i : books) byEntryAndAccount.put(key(i.entryId(), i.accountId()), i);
        Set<UUID> pairedBook = new HashSet<>();
        for (BItem rev : books) {
            if (rev.reversalOfId() == null) continue;
            BItem orig = byEntryAndAccount.get(key(rev.reversalOfId(), rev.accountId()));
            if (orig == null || pairedBook.contains(orig.jlId()) || pairedBook.contains(rev.jlId())) continue;
            if (orig.amount().add(rev.amount()).signum() != 0) continue;
            if (!inRange(orig.date(), from, to) || !inRange(rev.date(), from, to)) continue;
            if (locked != null && !orig.date().isAfter(locked) && !rev.date().isAfter(locked)) continue;
            contraBooks.add(List.of(orig, rev));
            pairedBook.add(orig.jlId());
            pairedBook.add(rev.jlId());
        }
        books.removeIf(i -> pairedBook.contains(i.jlId()));

        // Rule 1: cheque number.
        for (SLine l : List.copyOf(pool)) {
            if (l.chequeNo() == null) continue;
            List<BItem> c = books.stream().filter(i -> i.amount().compareTo(l.amount()) == 0
                    && sameChequeNo(i.chequeNo(), l.chequeNo())
                    && (l.amount().signum() > 0 ? "CRT".equals(i.docType()) && "CHEQUE".equals(i.sourceType())
                        : Set.of("BPC", "CBR", "BPV").contains(i.docType()))).toList();
            if (c.size() == 1) propose(t, bankAccountId, Method.AUTO_CHEQUE, "HIGH", List.of(l), c, pool, books, count);
        }
        // Rule 2: a payment reference or a payment run number in the line.
        for (SLine l : List.copyOf(pool)) {
            String text = ((l.reference() == null ? "" : l.reference()) + " " + l.description()).toUpperCase(Locale.ROOT);
            List<BItem> c = books.stream().filter(i -> "BPV".equals(i.docType()) && i.amount().compareTo(l.amount()) == 0
                    && (contains(text, i.paymentRef()) || contains(text, i.runNo()))).toList();
            if (c.size() == 1) propose(t, bankAccountId, Method.AUTO_REFERENCE, "HIGH", List.of(l), c, pool, books, count);
        }
        // Rule 3: one credit for a deposit of several cheques (same day, same leaf). Groups come from
        // the register, not a subset search. A line naming one cheque number is a single-cheque
        // credit, so it is offered a group only when that cheque is in it; and a group is proposed
        // only when exactly one line fits it and it is the only group fitting that line.
        Map<String, List<BItem>> groups = books.stream()
                .filter(i -> "CRT".equals(i.docType()) && "CHEQUE".equals(i.sourceType()) && i.depositedAt() != null)
                .collect(Collectors.groupingBy(i -> i.depositedAt() + "|" + i.accountId(), LinkedHashMap::new, Collectors.toList()));
        Map<SLine, List<List<BItem>>> groupFits = new LinkedHashMap<>();
        Map<List<BItem>, List<SLine>> lineFits = new HashMap<>();
        for (SLine l : pool) {
            if (l.amount().signum() <= 0) continue;
            for (List<BItem> g : groups.values()) {
                if (g.size() < 2
                        || g.stream().map(BItem::amount).reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(l.amount()) != 0
                        || !g.stream().allMatch(i -> within(i.date(), l.date(), window))
                        || (l.chequeNo() != null && g.stream().noneMatch(i -> sameChequeNo(i.chequeNo(), l.chequeNo())))) {
                    continue;
                }
                groupFits.computeIfAbsent(l, k -> new ArrayList<>()).add(g);
                lineFits.computeIfAbsent(g, k -> new ArrayList<>()).add(l);
            }
        }
        groupFits.forEach((l, gs) -> {
            if (gs.size() == 1 && lineFits.get(gs.get(0)).size() == 1) {
                propose(t, bankAccountId, Method.AUTO_GROUP, "MEDIUM", List.of(l), gs.get(0), pool, books, count);
            }
        });
        // Rule 4: exact amount inside the window, unique both ways.
        Map<UUID, List<BItem>> lineCands = new HashMap<>();
        Map<UUID, List<SLine>> itemCands = new HashMap<>();
        for (SLine l : pool) {
            for (BItem i : books) {
                if (i.amount().compareTo(l.amount()) == 0 && within(i.date(), l.date(), window)) {
                    lineCands.computeIfAbsent(l.id(), k -> new ArrayList<>()).add(i);
                    itemCands.computeIfAbsent(i.jlId(), k -> new ArrayList<>()).add(l);
                }
            }
        }
        for (SLine l : List.copyOf(pool)) {
            List<BItem> c = lineCands.getOrDefault(l.id(), List.of());
            if (c.size() == 1 && itemCands.getOrDefault(c.get(0).jlId(), List.of()).size() == 1) {
                propose(t, bankAccountId, Method.AUTO_AMOUNT_DATE, "MEDIUM", List.of(l), c, pool, books, count);
            }
        }
        // Rule 5: contra pairs — the book pairs found above, then a bank error and its correction.
        for (List<BItem> pair : contraBooks) {
            propose(t, bankAccountId, Method.CONTRA, "HIGH", List.of(), pair, pool, new ArrayList<>(pair), count);
        }
        for (SLine a : List.copyOf(pool)) {
            if (!pool.contains(a) || a.amount().signum() <= 0 || a.reference() == null) continue;
            Optional<SLine> b = pool.stream().filter(x -> x.amount().negate().compareTo(a.amount()) == 0
                    && a.reference().equalsIgnoreCase(x.reference())
                    && Math.abs(ChronoUnit.DAYS.between(a.txn(), x.txn())) <= 3).findFirst();
            b.ifPresent(x -> propose(t, bankAccountId, Method.CONTRA, "HIGH", List.of(a, x), List.of(), pool, books, count));
        }
        return new BankRecDTOs.AutoMatchResult(count.values().stream().mapToInt(Integer::intValue).sum(), count);
    }

    private void propose(UUID t, UUID bankAccountId, Method method, String confidence, List<SLine> ls, List<BItem> is,
                         List<SLine> pool, List<BItem> books, Map<String, Integer> count) {
        insertMatch(t, bankAccountId, method, "SUGGESTED", confidence,
                ls.stream().map(SLine::id).toList(), is.stream().map(BItem::jlId).toList());
        pool.removeAll(ls);
        books.removeAll(is);
        count.merge(method.name(), 1, Integer::sum);
    }

    private static UUID key(UUID entry, UUID account) {
        return UUID.nameUUIDFromBytes((entry + "|" + account).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static boolean sameChequeNo(String a, String b) {
        if (a == null || b == null) return false;
        String x = a.trim().replaceFirst("^0+(?=.)", "");
        String y = b.trim().replaceFirst("^0+(?=.)", "");
        return !x.isEmpty() && x.equalsIgnoreCase(y);
    }

    /** {@code token} in {@code text}, not as part of a longer number or word ("PR-26/4" is not in "PR-26/40"). */
    static boolean contains(String text, String token) {
        if (token == null || token.trim().length() < 3) return false;
        return Pattern.compile("(?<![A-Z0-9])" + Pattern.quote(token.trim().toUpperCase(Locale.ROOT)) + "(?![A-Z0-9])")
                .matcher(text).find();
    }

    private static boolean within(LocalDate a, LocalDate b, int days) {
        return Math.abs(ChronoUnit.DAYS.between(a, b)) <= days;
    }

    private static boolean inRange(LocalDate d, LocalDate from, LocalDate to) {
        return (from == null || !d.isBefore(from)) && (to == null || !d.isAfter(to));
    }

    /** Suggestions touching the window are dropped outright: they are proposals, not history. */
    private void dropSuggestions(UUID t, UUID bankAccountId, LocalDate from, LocalDate to) {
        MapSqlParameterSource p = new MapSqlParameterSource("t", t).addValue("b", bankAccountId)
                .addValue("from", from).addValue("to", to);
        List<UUID> ids = jdbc.queryForList("""
                select m.id from bank_matches m where m.tenant_id = :t and m.bank_account_id = :b and m.status = 'SUGGESTED'
                  and (exists (select 1 from bank_match_statement_lines ml join bank_statement_lines l on l.id = ml.statement_line_id
                               where ml.match_id = m.id and (cast(:from as date) is null or l.txn_date >= :from)
                                 and (cast(:to as date) is null or l.txn_date <= :to))
                    or exists (select 1 from bank_match_book_items i join journal_lines jl on jl.id = i.journal_line_id
                               join journal_entries je on je.id = jl.journal_entry_id
                               where i.match_id = m.id and (cast(:from as date) is null or je.entry_date >= :from)
                                 and (cast(:to as date) is null or je.entry_date <= :to)))""", p, UUID.class);
        deleteMatches(t, ids);
    }

    /** Suggestions only: re-read under the lock, so a match confirmed meanwhile is never deleted. */
    private void deleteMatches(UUID t, List<UUID> candidates) {
        if (candidates.isEmpty()) return;
        List<UUID> ids = jdbc.queryForList("select id from bank_matches where tenant_id = :t and id in (:m) and status = 'SUGGESTED' for update",
                new MapSqlParameterSource("t", t).addValue("m", candidates), UUID.class);
        if (ids.isEmpty()) return;
        MapSqlParameterSource mp = new MapSqlParameterSource("t", t).addValue("m", ids);
        jdbc.update("delete from bank_match_statement_lines where tenant_id = :t and match_id in (:m)", mp);
        jdbc.update("delete from bank_match_book_items where tenant_id = :t and match_id in (:m)", mp);
        jdbc.update("delete from bank_matches where tenant_id = :t and id in (:m)", mp);
    }

    // ------------------------------------------------------------------ manual, confirm, undo

    /** Spec §3 manual match: any statement lines and book items whose sums balance; CONFIRMED at once. */
    @Transactional
    public BankRecDTOs.Match manual(BankRecDTOs.ManualMatchInput in) {
        UUID t = BankAccountLedgerService.requireTenant();
        List<UUID> ls = in == null || in.statementLineIds() == null ? List.of() : List.copyOf(new LinkedHashSet<>(in.statementLineIds()));
        List<UUID> js = in == null || in.journalLineIds() == null ? List.of() : List.copyOf(new LinkedHashSet<>(in.journalLineIds()));
        List<UUID> os = in == null || in.openingItemIds() == null ? List.of() : List.copyOf(new LinkedHashSet<>(in.openingItemIds()));
        if (ls.isEmpty() && js.isEmpty() && os.isEmpty()) throw BankRecRefusal.refuse("selectToMatch", "Select statement lines and book items to match");
        UUID bankAccountId = bankAccountOf(t, ls, js, os);
        Method method = ls.isEmpty() || (js.isEmpty() && os.isEmpty()) ? Method.CONTRA : Method.MANUAL;
        UUID id = record(t, bankAccountId, method, "CONFIRMED", null, ls, js, os);
        return matches(t, List.of(id)).get(0);
    }

    /**
     * The CONFIRMED CREATED match a create-from-line action writes, in the
     * action's transaction, between the line(s) and the bank-side journal line(s)
     * its service just posted.
     */
    @Transactional
    public UUID recordCreated(UUID bankAccountId, List<UUID> statementLineIds, List<UUID> journalLineIds) {
        UUID t = BankAccountLedgerService.requireTenant();
        return record(t, bankAccountId, Method.CREATED, "CONFIRMED", null, statementLineIds, journalLineIds, List.of());
    }

    /** Validates tenant, bank account, leaf set, liveness and balance, then writes the match. */
    private UUID record(UUID t, UUID bankAccountId, Method method, String status, String confidence,
                        List<UUID> ls, List<UUID> js, List<UUID> os) {
        lockAccount(t, bankAccountId);
        Set<UUID> leaves = ledgers.requireLeafSet(bankAccountId);
        requireOutsideLock(t, bankAccountId, ls, js);
        BigDecimal sTotal = BigDecimal.ZERO;
        if (!ls.isEmpty()) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    select id, bank_account_id, amount from bank_statement_lines where tenant_id = :t and id in (:ids) for update""",
                    new MapSqlParameterSource("t", t).addValue("ids", ls));
            if (rows.size() != ls.size()) throw new NotFoundException("Statement line not found");
            for (Map<String, Object> r : rows) {
                if (!bankAccountId.equals(r.get("bank_account_id"))) {
                    throw BankRecRefusal.refuse("linesDifferentAccounts", "Every statement line must belong to the same bank account");
                }
                sTotal = sTotal.add((BigDecimal) r.get("amount"));
            }
            Integer live = jdbc.queryForObject("""
                    select count(*) from bank_match_statement_lines where tenant_id = :t and statement_line_id in (:ids) and not released""",
                    new MapSqlParameterSource("t", t).addValue("ids", ls), Integer.class);
            if (live != null && live > 0) throw BankRecRefusal.refuse("selectedLineMatched", "A selected statement line is already matched");
        }
        BigDecimal bTotal = BigDecimal.ZERO;
        if (!js.isEmpty()) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    select jl.id, jl.account_id, (jl.debit - jl.credit) as amount, je.doc_type from journal_lines jl
                    join journal_entries je on je.id = jl.journal_entry_id and je.tenant_id = jl.tenant_id
                    where jl.tenant_id = :t and jl.id in (:ids)""",
                    new MapSqlParameterSource("t", t).addValue("ids", js));
            if (rows.size() != js.size()) throw new NotFoundException("Journal line not found");
            for (Map<String, Object> r : rows) {
                if (!leaves.contains((UUID) r.get("account_id"))) {
                    throw BankRecRefusal.refuse("itemNotOnAccount", "A selected book item is not on this bank account's ledger accounts");
                }
                if ("OB".equals(r.get("doc_type"))) {
                    throw BankRecRefusal.refuse("openingNotMatched", "An opening balance is not matched; it is the reconciliation's starting point");
                }
                bTotal = bTotal.add((BigDecimal) r.get("amount"));
            }
            Integer live = jdbc.queryForObject("""
                    select count(*) from bank_match_book_items where tenant_id = :t and journal_line_id in (:ids) and not released""",
                    new MapSqlParameterSource("t", t).addValue("ids", js), Integer.class);
            if (live != null && live > 0) throw BankRecRefusal.refuse("selectedItemMatched", "A selected book item is already matched");
        }
        if (!os.isEmpty()) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    select id, bank_account_id, amount from bank_rec_opening_items where tenant_id = :t and id in (:ids)""",
                    new MapSqlParameterSource("t", t).addValue("ids", os));
            if (rows.size() != os.size()) throw new NotFoundException("Opening item not found");
            for (Map<String, Object> r : rows) {
                if (!bankAccountId.equals(r.get("bank_account_id"))) {
                    throw new BusinessRuleViolationException("A selected opening item belongs to another bank account");
                }
                bTotal = bTotal.add((BigDecimal) r.get("amount"));
            }
            Integer live = jdbc.queryForObject("""
                    select count(*) from bank_match_book_items where tenant_id = :t and opening_item_id in (:ids) and not released""",
                    new MapSqlParameterSource("t", t).addValue("ids", os), Integer.class);
            if (live != null && live > 0) throw new BusinessRuleViolationException("A selected opening item is already matched");
        }
        List<UUID> book = new ArrayList<>(js);
        book.addAll(os);
        requireBalanced(ls, book, sTotal, bTotal);
        return insertMatch(t, bankAccountId, method, status, confidence, ls, js, os);
    }

    /**
     * Matches inside a finalized reconciliation are frozen (spec §4): no new match
     * may take a statement line dated on or before the bank account's
     * reconciled-through date, and no one-sided (contra) match may pair book items
     * that all fall inside it; either would change a signed-off statement.
     */
    private void requireOutsideLock(UUID t, UUID bankAccountId, List<UUID> ls, List<UUID> js) {
        LocalDate locked = bankLock.reconciledThrough(bankAccountId).orElse(null);
        if (locked == null) return;
        MapSqlParameterSource p = new MapSqlParameterSource("t", t).addValue("r", locked);
        if (!ls.isEmpty()) {
            Integer inside = jdbc.queryForObject("select count(*) from bank_statement_lines where tenant_id = :t and id in (:ids) and txn_date <= :r",
                    p.addValue("ids", ls), Integer.class);
            if (inside != null && inside > 0) throw frozen(locked);
        } else if (!js.isEmpty()) {
            Integer outside = jdbc.queryForObject("""
                    select count(*) from journal_lines jl join journal_entries je on je.id = jl.journal_entry_id
                    where jl.tenant_id = :t and jl.id in (:ids) and je.entry_date > :r""", p.addValue("ids", js), Integer.class);
            if (outside == null || outside == 0) throw frozen(locked);
        }
    }

    private static BusinessRuleViolationException frozen(LocalDate locked) {
        return new BusinessRuleViolationException("This bank account is reconciled through "
                + locked.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                + "; matches inside a finalized reconciliation are frozen. Reopen that reconciliation first.");
    }

    /** The match balance invariant (spec §3): Σ statement = Σ book; a one-sided (contra) match sums to zero. */
    static void requireBalanced(List<UUID> ls, List<UUID> js, BigDecimal sTotal, BigDecimal bTotal) {
        if (ls.isEmpty() || js.isEmpty()) {
            List<UUID> side = ls.isEmpty() ? js : ls;
            BigDecimal total = ls.isEmpty() ? bTotal : sTotal;
            if (side.size() < 2 || total.signum() != 0) {
                throw BankRecRefusal.refuse("contraNotZero", "A one-sided (contra) match needs two or more items summing to 0.00; these sum to "
                        + StatementValues.money(total), "total", StatementValues.money(total));
            }
            return;
        }
        if (sTotal.compareTo(bTotal) != 0) {
            throw BankRecRefusal.refuse("notBalanced", "The selection does not balance: statement " + StatementValues.money(sTotal)
                    + ", book " + StatementValues.money(bTotal) + ", difference " + StatementValues.money(sTotal.subtract(bTotal)), "statement", StatementValues.money(sTotal), "book", StatementValues.money(bTotal), "difference", StatementValues.money(sTotal.subtract(bTotal)));
        }
    }

    private UUID insertMatch(UUID t, UUID bankAccountId, Method method, String status, String confidence,
                             List<UUID> ls, List<UUID> js) {
        return insertMatch(t, bankAccountId, method, status, confidence, ls, js, List.of());
    }

    private UUID insertMatch(UUID t, UUID bankAccountId, Method method, String status, String confidence,
                             List<UUID> ls, List<UUID> js, List<UUID> os) {
        UUID id = UUID.randomUUID();
        UUID user = BankStatementImportService.currentUserId();
        boolean confirmed = "CONFIRMED".equals(status);
        jdbc.update("""
                insert into bank_matches (id, tenant_id, bank_account_id, method, status, confidence, created_by, created_at,
                                          confirmed_by, confirmed_at)
                values (:id, :t, :b, :method, :status, :conf, :u, now(), :cu, :ca)""",
                new MapSqlParameterSource("id", id).addValue("t", t).addValue("b", bankAccountId)
                        .addValue("method", method.name()).addValue("status", status).addValue("conf", confidence)
                        .addValue("u", user).addValue("cu", confirmed ? user : null)
                        .addValue("ca", confirmed ? Timestamp.from(Instant.now()) : null));
        for (UUID l : ls) {
            jdbc.update("insert into bank_match_statement_lines (tenant_id, match_id, statement_line_id) values (:t, :m, :l)",
                    new MapSqlParameterSource("t", t).addValue("m", id).addValue("l", l));
        }
        for (UUID j : js) {
            jdbc.update("insert into bank_match_book_items (tenant_id, match_id, journal_line_id) values (:t, :m, :j)",
                    new MapSqlParameterSource("t", t).addValue("m", id).addValue("j", j));
        }
        for (UUID o : os) {
            jdbc.update("insert into bank_match_book_items (tenant_id, match_id, opening_item_id) values (:t, :m, :o)",
                    new MapSqlParameterSource("t", t).addValue("m", id).addValue("o", o));
        }
        return id;
    }

    /** The bank account the selection belongs to: the statement lines', else the owner of the book items' leaf. */
    private UUID bankAccountOf(UUID t, List<UUID> ls, List<UUID> js, List<UUID> os) {
        if (ls.isEmpty() && js.isEmpty()) {
            List<UUID> b = jdbc.queryForList("select distinct bank_account_id from bank_rec_opening_items where tenant_id = :t and id in (:ids)",
                    new MapSqlParameterSource("t", t).addValue("ids", os), UUID.class);
            if (b.isEmpty()) throw new NotFoundException("Opening item not found");
            if (b.size() > 1) throw new BusinessRuleViolationException("The opening items belong to different bank accounts");
            return b.get(0);
        }
        if (!ls.isEmpty()) {
            List<UUID> b = jdbc.queryForList("select distinct bank_account_id from bank_statement_lines where tenant_id = :t and id in (:ids)",
                    new MapSqlParameterSource("t", t).addValue("ids", ls), UUID.class);
            if (b.isEmpty()) throw new NotFoundException("Statement line not found");
            if (b.size() > 1) throw BankRecRefusal.refuse("linesDifferentAccounts", "Every statement line must belong to the same bank account");
            return b.get(0);
        }
        List<UUID> b = jdbc.queryForList("""
                select distinct bl.bank_account_id from journal_lines jl
                join bank_account_ledgers bl on bl.account_id = jl.account_id and bl.tenant_id = jl.tenant_id
                where jl.tenant_id = :t and jl.id in (:ids)""", new MapSqlParameterSource("t", t).addValue("ids", js), UUID.class);
        if (b.isEmpty()) throw new NotFoundException("Journal line not found");
        if (b.size() > 1) throw BankRecRefusal.refuse("itemsDifferentAccounts", "The book items belong to different bank accounts");
        return b.get(0);
    }

    private Map<String, Object> lockMatch(UUID t, UUID matchId) {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from bank_matches where id = :m and tenant_id = :t for update",
                new MapSqlParameterSource("t", t).addValue("m", matchId));
        if (rows.isEmpty()) throw new NotFoundException("Match not found");
        return rows.get(0);
    }

    /**
     * PR #353 review P2-5: every match write (auto-match, manual, created,
     * confirm, undo, delete import) takes the bank account's row first, so an
     * auto-match cannot drop a suggestion another user is confirming.
     */
    private void lockAccountOfMatch(UUID t, UUID matchId) {
        List<UUID> b = jdbc.queryForList("select bank_account_id from bank_matches where id = :m and tenant_id = :t",
                new MapSqlParameterSource("t", t).addValue("m", matchId), UUID.class);
        if (b.isEmpty()) throw new NotFoundException("Match not found");
        lockAccount(t, b.get(0));
    }

    /** The bank account's row FOR UPDATE: every match write, import and finalize takes it first. */
    public void lockAccount(UUID t, UUID bankAccountId) {
        jdbc.queryForList("select id from bank_accounts where id = :b and tenant_id = :t for update",
                new MapSqlParameterSource("t", t).addValue("b", bankAccountId), UUID.class);
    }

    @Transactional
    public BankRecDTOs.Match confirm(UUID matchId) {
        UUID t = BankAccountLedgerService.requireTenant();
        lockAccountOfMatch(t, matchId);
        Map<String, Object> m = lockMatch(t, matchId);
        if (!"SUGGESTED".equals(m.get("status"))) {
            throw BankRecRefusal.refuse("onlySuggestedConfirm", "Only a suggested match can be confirmed; this one is " + m.get("status"), "status", m.get("status"));
        }
        confirmLocked(t, matchId);
        return matches(t, List.of(matchId)).get(0);
    }

    /** Confirm every SUGGESTED match of this confidence in the window ("Confirm all HIGH"). */
    @Transactional
    public int confirmAll(UUID bankAccountId, String confidence, LocalDate from, LocalDate to) {
        UUID t = BankAccountLedgerService.requireTenant();
        ledgers.requireBankAccount(bankAccountId);
        lockAccount(t, bankAccountId);
        String conf = confidence == null ? "HIGH" : confidence.toUpperCase(Locale.ROOT);
        List<UUID> ids = jdbc.queryForList("""
                select m.id from bank_matches m where m.tenant_id = :t and m.bank_account_id = :b and m.status = 'SUGGESTED'
                  and m.confidence = :c
                  and (not exists (select 1 from bank_match_statement_lines ml where ml.match_id = m.id)
                       or exists (select 1 from bank_match_statement_lines ml join bank_statement_lines l on l.id = ml.statement_line_id
                                  where ml.match_id = m.id and (cast(:from as date) is null or l.txn_date >= :from)
                                    and (cast(:to as date) is null or l.txn_date <= :to)))
                order by m.created_at for update""",
                new MapSqlParameterSource("t", t).addValue("b", bankAccountId).addValue("c", conf)
                        .addValue("from", from).addValue("to", to), UUID.class);
        ids.forEach(id -> confirmLocked(t, id));
        return ids.size();
    }

    private void confirmLocked(UUID t, UUID matchId) {
        MapSqlParameterSource p = new MapSqlParameterSource("t", t).addValue("m", matchId);
        List<UUID> ls = jdbc.queryForList("select statement_line_id from bank_match_statement_lines where tenant_id = :t and match_id = :m and not released", p, UUID.class);
        List<UUID> js = jdbc.queryForList("select journal_line_id from bank_match_book_items where tenant_id = :t and match_id = :m and not released and journal_line_id is not null", p, UUID.class);
        List<UUID> os = jdbc.queryForList("select opening_item_id from bank_match_book_items where tenant_id = :t and match_id = :m and not released and opening_item_id is not null", p, UUID.class);
        UUID bankAccountId = jdbc.queryForObject("select bank_account_id from bank_matches where tenant_id = :t and id = :m", p, UUID.class);
        requireOutsideLock(t, bankAccountId, ls, js);
        BigDecimal s = ls.isEmpty() ? BigDecimal.ZERO : jdbc.queryForObject(
                "select coalesce(sum(amount), 0) from bank_statement_lines where tenant_id = :t and id in (:ids)",
                new MapSqlParameterSource("t", t).addValue("ids", ls), BigDecimal.class);
        BigDecimal b = js.isEmpty() ? BigDecimal.ZERO : jdbc.queryForObject(
                "select coalesce(sum(debit - credit), 0) from journal_lines where tenant_id = :t and id in (:ids)",
                new MapSqlParameterSource("t", t).addValue("ids", js), BigDecimal.class);
        if (!os.isEmpty()) {
            b = b.add(jdbc.queryForObject("select coalesce(sum(amount), 0) from bank_rec_opening_items where tenant_id = :t and id in (:ids)",
                    new MapSqlParameterSource("t", t).addValue("ids", os), BigDecimal.class));
        }
        List<UUID> book = new ArrayList<>(js);
        book.addAll(os);
        requireBalanced(ls, book, s, b);
        jdbc.update("update bank_matches set status = 'CONFIRMED', confirmed_by = :u, confirmed_at = now() where id = :m and tenant_id = :t",
                p.addValue("u", BankStatementImportService.currentUserId()));
    }

    /**
     * Undo (spec §3): the match becomes UNDONE and its children are released; the
     * row stays as the audit trail. {@code reverseCreated} on a CREATED match also
     * reverses what it created: a {@code BNK} through {@code PostingService.reverse},
     * a {@code BPC} through the issued-cheque service's unpresent. A receivable
     * cheque's {@code CRT}/{@code CBR} has no reversing service; it is refused.
     */
    @Transactional
    public BankRecDTOs.Match undo(UUID matchId, boolean reverseCreated, LocalDate reverseOn, String reason) {
        UUID t = BankAccountLedgerService.requireTenant();
        lockAccountOfMatch(t, matchId);
        Map<String, Object> m = lockMatch(t, matchId);
        if ("UNDONE".equals(m.get("status"))) throw BankRecRefusal.refuse("alreadyUndone", "This match was already undone");
        requireUndoOutsideLock(t, (UUID) m.get("bank_account_id"), matchId);
        String why = reason == null || reason.isBlank() ? "Match undone" : reason.trim();
        // PR #353 review P2-4: an unidentified receipt that has paid register rows
        // stays booked; undoing (or reversing) it would leave those receipts with
        // no money behind them.
        Integer draws = jdbc.queryForObject("""
                select count(*) from bank_suspense_draws d join bank_match_statement_lines ml
                  on ml.statement_line_id = d.statement_line_id and not ml.released
                where d.tenant_id = :t and ml.match_id = :m""",
                new MapSqlParameterSource("t", t).addValue("m", matchId), Integer.class);
        if (draws != null && draws > 0) {
            throw BankRecRefusal.refuse("suspenseDrawn", "Register rows have been received from this unidentified receipt; "
                    + "it can no longer be undone or reversed");
        }
        if (reverseCreated) {
            if (!"CREATED".equals(m.get("method"))) {
                throw BankRecRefusal.refuse("nothingToReverse", "Only a match created from a statement line has an entry to reverse");
            }
            LocalDate on = reverseOn;
            List<Map<String, Object>> entries = jdbc.queryForList("""
                    select distinct je.id, je.doc_type, je.source_type, je.source_id, je.entry_date from bank_match_book_items i
                    join journal_lines jl on jl.id = i.journal_line_id
                    join journal_entries je on je.id = jl.journal_entry_id
                    where i.tenant_id = :t and i.match_id = :m and not i.released""",
                    new MapSqlParameterSource("t", t).addValue("m", matchId));
            if (on == null) {
                on = reverseDate(entries.stream().map(e -> ((java.sql.Date) e.get("entry_date")).toLocalDate())
                        .min(Comparator.naturalOrder()).orElse(null));
            } else {
                // R1 P2-3 (the F14-41 rule): a reversal is not dated before the entry it reverses.
                LocalDate latest = entries.stream().map(e -> ((java.sql.Date) e.get("entry_date")).toLocalDate())
                        .max(Comparator.naturalOrder()).orElse(null);
                if (latest != null && on.isBefore(latest)) {
                    throw BankRecRefusal.refuse("reverseBeforeEntry", "The reversal cannot be dated before the entry it"
                            + " reverses (" + latest.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) + ")");
                }
            }
            for (Map<String, Object> e : entries) bankLock.assertOpenForEntry((UUID) e.get("id"), on);
            for (Map<String, Object> e : entries) {
                String doc = (String) e.get("doc_type");
                if ("BNK".equals(doc)) {
                    posting.reverse((UUID) e.get("id"), on, why);
                } else if ("BPC".equals(doc) && "ISSUED_CHEQUE".equals(e.get("source_type"))) {
                    issuedCheques.unpresent((UUID) e.get("source_id"), on, why);
                } else {
                    throw BankRecRefusal.refuse("reverseFromRegister", "A " + doc + " is corrected from the cheque register; undo the match "
                            + "without reversing, then correct the cheque there", "doc", doc);
                }
            }
        }
        MapSqlParameterSource p = new MapSqlParameterSource("t", t).addValue("m", matchId)
                .addValue("u", BankStatementImportService.currentUserId()).addValue("r", why);
        jdbc.update("update bank_match_statement_lines set released = true where tenant_id = :t and match_id = :m", p);
        jdbc.update("update bank_match_book_items set released = true where tenant_id = :t and match_id = :m", p);
        jdbc.update("""
                update bank_matches set status = 'UNDONE', undone_by = :u, undone_at = now(), undo_reason = :r
                where id = :m and tenant_id = :t""", p);
        return matches(t, List.of(matchId)).get(0);
    }

    /**
     * Spec §4: undoing a match any of whose statement lines is dated on or before
     * the reconciled-through date is refused; so is a contra match whose book
     * items all fall inside it.
     */
    private void requireUndoOutsideLock(UUID t, UUID bankAccountId, UUID matchId) {
        LocalDate locked = bankLock.reconciledThrough(bankAccountId).orElse(null);
        if (locked == null) return;
        MapSqlParameterSource p = new MapSqlParameterSource("t", t).addValue("m", matchId).addValue("r", locked);
        Integer lines = jdbc.queryForObject("select count(*) from bank_match_statement_lines where tenant_id = :t and match_id = :m and not released", p, Integer.class);
        if (lines != null && lines > 0) {
            Integer inside = jdbc.queryForObject("""
                    select count(*) from bank_match_statement_lines ml join bank_statement_lines l on l.id = ml.statement_line_id
                    where ml.tenant_id = :t and ml.match_id = :m and not ml.released and l.txn_date <= :r""", p, Integer.class);
            if (inside != null && inside > 0) throw frozen(locked);
            return;
        }
        Integer outside = jdbc.queryForObject("""
                select count(*) from bank_match_book_items i join journal_lines jl on jl.id = i.journal_line_id
                join journal_entries je on je.id = jl.journal_entry_id
                where i.tenant_id = :t and i.match_id = :m and not i.released and je.entry_date > :r""", p, Integer.class);
        if (outside == null || outside == 0) throw frozen(locked);
    }

    // ------------------------------------------------------------------ register evidence

    /**
     * The cheque register's Bank column (spec §3): CONFIRMED with the statement
     * date when the row's CRT bank line is in a confirmed match; CASH when it was
     * cleared to a cash leaf; NOT_ON_STATEMENT otherwise. Only CLEARED rows.
     */
    @Transactional(readOnly = true)
    public List<BankRecDTOs.ChequeEvidence> evidence(Collection<UUID> chequeIds) {
        UUID t = BankAccountLedgerService.requireTenant();
        if (chequeIds == null || chequeIds.isEmpty()) return List.of();
        record Row(BankRecDTOs.ChequeEvidence e, UUID propertyId) { }
        List<Row> rows = jdbc.query("""
                select c.id, c.property_id, a.account_sub_type,
                       (select min(l.txn_date) from bank_match_book_items i
                          join bank_matches m on m.id = i.match_id and m.status = 'CONFIRMED'
                          join bank_match_statement_lines ml on ml.match_id = m.id and not ml.released
                          join bank_statement_lines l on l.id = ml.statement_line_id
                         where i.journal_line_id = jl.id and not i.released) as confirmed_on
                from cheques c
                join journal_lines jl on jl.journal_entry_id = c.crt_journal_id and jl.debit > 0 and jl.tenant_id = c.tenant_id
                join accounts a on a.id = jl.account_id
                where c.tenant_id = :t and c.id in (:ids) and c.status = 'CLEARED'""",
                new MapSqlParameterSource("t", t).addValue("ids", chequeIds),
                (rs, i) -> {
                    LocalDate on = rs.getObject("confirmed_on", LocalDate.class);
                    String sub = rs.getString("account_sub_type");
                    String state = "CASH".equals(sub) ? "CASH" : !"BANK".equals(sub) ? "SUSPENSE"
                            : on != null ? "CONFIRMED" : "NOT_ON_STATEMENT";
                    return new Row(new BankRecDTOs.ChequeEvidence(rs.getObject("id", UUID.class), state, on),
                            rs.getObject("property_id", UUID.class));
                });
        // A property manager reaches the register too: only rows on the properties they are assigned.
        return propertyScope.filter(rows, Row::propertyId).stream().map(Row::e).toList();
    }

    /** Statement lines of a bank account with no live match (for the list page). */
    @Transactional(readOnly = true)
    public long unmatchedCount(UUID bankAccountId) {
        UUID t = BankAccountLedgerService.requireTenant();
        Long n = jdbc.queryForObject("""
                select count(*) from bank_statement_lines l where l.tenant_id = :t and l.bank_account_id = :b
                  and not exists (select 1 from bank_match_statement_lines ml join bank_matches m on m.id = ml.match_id
                                  where ml.statement_line_id = l.id and not ml.released and m.status = 'CONFIRMED')""",
                new MapSqlParameterSource("t", t).addValue("b", bankAccountId), Long.class);
        return n == null ? 0 : n;
    }

}
