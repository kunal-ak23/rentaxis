package com.datagami.rentaxis.core.service.bank;

import com.datagami.rentaxis.api.dto.bank.BankRecDTOs;
import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.cheque.ClearBatchRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.payables.IssuedChequeService;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.BankAccount;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;

/**
 * Create-from-line actions (finance-ops spec §3). Each runs in one transaction:
 * the existing service posts exactly as it does from its own screen (and refuses
 * exactly as it does), then a CONFIRMED {@code CREATED} match ties the statement
 * line(s) to the bank-side journal line(s) it posted. A posting that lands on a
 * leaf outside the bank account's set rolls the whole action back.
 */
@Service
public class BankLineActionService {

    private final BankAccountLedgerService ledgers;
    private final BankMatchService matches;
    private final BankStatementPostingService bnk;
    private final ChequeService cheques;
    private final IssuedChequeService issuedCheques;
    private final AccountResolver resolver;
    private final NamedParameterJdbcTemplate jdbc;
    private final EntityManager em;
    private final Clock clock;

    public BankLineActionService(BankAccountLedgerService ledgers, BankMatchService matches,
                                 BankStatementPostingService bnk, ChequeService cheques,
                                 IssuedChequeService issuedCheques, AccountResolver resolver,
                                 NamedParameterJdbcTemplate jdbc, EntityManager em, Clock clock) {
        this.ledgers = ledgers;
        this.matches = matches;
        this.bnk = bnk;
        this.cheques = cheques;
        this.issuedCheques = issuedCheques;
        this.resolver = resolver;
        this.jdbc = jdbc;
        this.em = em;
        this.clock = clock;
    }

    /** A statement line read (and locked) in the caller's tenant. */
    record L(UUID id, UUID bankAccountId, LocalDate txn, LocalDate value, String description, String reference,
             String chequeNo, BigDecimal amount) {
        LocalDate date() { return value != null ? value : txn; }
    }

    private List<L> lockLines(UUID t, List<UUID> ids, boolean requireUnmatched) {
        List<UUID> distinct = List.copyOf(new LinkedHashSet<>(ids == null ? List.of() : ids));
        if (distinct.isEmpty()) throw BankRecRefusal.refuse("selectLine", "Select a statement line");
        // The bank account first, then the lines: the order every match write takes (P2-5).
        List<UUID> owners = jdbc.queryForList("select distinct bank_account_id from bank_statement_lines where tenant_id = :t and id in (:ids)",
                new MapSqlParameterSource("t", t).addValue("ids", distinct), UUID.class);
        if (owners.isEmpty()) throw new NotFoundException("Statement line not found");
        if (owners.size() > 1) throw BankRecRefusal.refuse("linesDifferentAccounts", "Every statement line must belong to the same bank account");
        matches.lockAccount(t, owners.get(0));
        List<L> ls = jdbc.query("""
                select id, bank_account_id, txn_date, value_date, description, reference, cheque_no, amount
                from bank_statement_lines where tenant_id = :t and id in (:ids) order by txn_date, seq for update""",
                new MapSqlParameterSource("t", t).addValue("ids", distinct),
                (rs, i) -> new L(rs.getObject("id", UUID.class), rs.getObject("bank_account_id", UUID.class),
                        rs.getObject("txn_date", LocalDate.class), rs.getObject("value_date", LocalDate.class),
                        rs.getString("description"), rs.getString("reference"), rs.getString("cheque_no"),
                        rs.getBigDecimal("amount")));
        if (ls.size() != distinct.size()) throw new NotFoundException("Statement line not found");
        if (ls.stream().map(L::bankAccountId).distinct().count() > 1) {
            throw BankRecRefusal.refuse("linesDifferentAccounts", "Every statement line must belong to the same bank account");
        }
        if (requireUnmatched) {
            Integer live = jdbc.queryForObject("""
                    select count(*) from bank_match_statement_lines where tenant_id = :t and statement_line_id in (:ids) and not released""",
                    new MapSqlParameterSource("t", t).addValue("ids", distinct), Integer.class);
            if (live != null && live > 0) throw BankRecRefusal.refuse("lineAlreadyMatched", "The statement line is already matched");
        }
        return ls;
    }

    private static BigDecimal sum(List<L> ls) {
        return ls.stream().map(L::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static LocalDate dateOf(List<L> ls) {
        return ls.stream().map(L::date).max(Comparator.naturalOrder()).orElseThrow();
    }

    /** The bank-side line(s) of a journal on a leaf of the set, by side; none → the action is refused. */
    private List<UUID> bankLines(UUID t, UUID entryId, Set<UUID> leaves, boolean debit) {
        em.flush();
        List<UUID> ids = jdbc.queryForList("select id from journal_lines where tenant_id = :t and journal_entry_id = :e and account_id in (:l) and "
                        + (debit ? "debit > 0" : "credit > 0"),
                new MapSqlParameterSource("t", t).addValue("e", entryId).addValue("l", leaves), UUID.class);
        if (ids.isEmpty()) {
            throw BankRecRefusal.refuse("postedOutsideSet", "The entry was posted to a ledger account outside this bank account's set; "
                    + "nothing was booked");
        }
        return ids;
    }

    private BankRecDTOs.ActionResult done(UUID t, UUID matchId, Collection<UUID> entryIds) {
        List<UUID> ids = List.copyOf(new LinkedHashSet<>(entryIds));
        List<String> numbers = ids.isEmpty() ? List.of() : jdbc.queryForList(
                "select entry_number from journal_entries where tenant_id = :t and id in (:ids) order by entry_number",
                new MapSqlParameterSource("t", t).addValue("ids", ids), String.class);
        return new BankRecDTOs.ActionResult(matchId, ids, numbers);
    }

    private void requireNotFuture(LocalDate d) {
        if (d.isAfter(LocalDate.now(clock))) {
            throw BankRecRefusal.refuse("lineInFuture", "The line is dated in the future; nothing can be booked from it yet");
        }
    }

    // ------------------------------------------------------------------ clear

    /** Clear cheque(s): one credit (or several) ↔ one or more DEPOSITED cheques banked into the set, Σ equal. */
    @Transactional
    public BankRecDTOs.ActionResult clearCheques(BankRecDTOs.ClearChequesInput in) {
        UUID t = BankAccountLedgerService.requireTenant();
        List<L> ls = lockLines(t, in.statementLineIds(), true);
        UUID bankAccountId = ls.get(0).bankAccountId();
        Set<UUID> leaves = ledgers.requireLeafSet(bankAccountId);
        if (ls.stream().anyMatch(l -> l.amount().signum() <= 0)) {
            throw BankRecRefusal.refuse("clearFromCredit", "Cheques are cleared from credit lines");
        }
        List<UUID> ids = List.copyOf(new LinkedHashSet<>(in.chequeIds() == null ? List.of() : in.chequeIds()));
        if (ids.isEmpty()) throw BankRecRefusal.refuse("selectCheques", "Select the cheques the bank credited");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, cheque_number, status, amount, debit_account_id, property_id from cheques where tenant_id = :t and id in (:ids)""",
                new MapSqlParameterSource("t", t).addValue("ids", ids));
        if (rows.size() != ids.size()) throw new NotFoundException("Cheque not found");
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            String label = "Cheque " + Objects.toString(r.get("cheque_number"), "(no number)");
            if (!"DEPOSITED".equals(r.get("status"))) throw BankRecRefusal.refuse("chequeNotDeposited", label + " is " + r.get("status") + ", not DEPOSITED", "cheque", Objects.toString(r.get("cheque_number"), "—"), "status", r.get("status"));
            // No account named at deposit: the CRT will debit the property's BANK leaf.
            UUID banked = r.get("debit_account_id") != null ? (UUID) r.get("debit_account_id")
                    : propertyBank((UUID) r.get("property_id"));
            if (banked == null || !leaves.contains(banked)) {
                throw BankRecRefusal.refuse("chequeNotDepositedHere", label + " was not deposited into this bank account", "cheque", Objects.toString(r.get("cheque_number"), "—"));
            }
            total = total.add((BigDecimal) r.get("amount"));
        }
        if (total.compareTo(sum(ls)) != 0) {
            throw BankRecRefusal.refuse("chequesTotalMismatch", "The cheques total " + StatementValues.money(total)
                    + "; the statement shows " + StatementValues.money(sum(ls)), "total", StatementValues.money(total), "line", StatementValues.money(sum(ls)));
        }
        LocalDate date = dateOf(ls);
        requireNotFuture(date);
        // One cheque or several, the batch door (PR #353 review P2-1): it refuses a
        // clearing date in the future or before the deposit, which the single
        // clear() does not check.
        cheques.clearBatch(new ClearBatchRequest(ids, date, "Cleared per bank statement"));
        em.flush();
        List<UUID> entries = jdbc.queryForList("select crt_journal_id from cheques where tenant_id = :t and id in (:ids)",
                new MapSqlParameterSource("t", t).addValue("ids", ids), UUID.class);
        List<UUID> jls = new ArrayList<>();
        for (UUID e : entries) jls.addAll(bankLines(t, e, leaves, true));
        UUID m = matches.recordCreated(bankAccountId, ls.stream().map(L::id).toList(), jls);
        return done(t, m, entries);
    }

    // ------------------------------------------------------------------ receive

    /**
     * Receive: a credit ↔ a REGISTERED transfer or cash row of equal amount, into
     * the set's leaf for the row's property. {@code fromSuspense}: the line was
     * booked as an unidentified receipt; the row is received from the suspense
     * leaf instead (Dr BANK_SUSPENSE / Cr PDC_RECEIVABLE) and the line stays
     * matched to its BNK.
     */
    @Transactional
    public BankRecDTOs.ActionResult receive(BankRecDTOs.ReceiveInput in) {
        UUID t = BankAccountLedgerService.requireTenant();
        boolean fromSuspense = Boolean.TRUE.equals(in.fromSuspense());
        L line = lockLines(t, List.of(in.statementLineId()), !fromSuspense).get(0);
        UUID bankAccountId = line.bankAccountId();
        Set<UUID> leaves = ledgers.requireLeafSet(bankAccountId);
        if (line.amount().signum() <= 0) throw BankRecRefusal.refuse("receiveFromCredit", "A receipt is booked from a credit line");
        Map<String, Object> c = cheque(t, in.chequeId());
        if (!"REGISTERED".equals(c.get("status"))) {
            throw BankRecRefusal.refuse("rowNotRegistered", "The row is " + c.get("status") + "; only a REGISTERED row can be received", "status", c.get("status"));
        }
        BigDecimal amount = (BigDecimal) c.get("amount");
        if (fromSuspense) {
            Map<String, Object> suspense = suspenseOf(t, line.id());
            if (suspense == null) {
                throw BankRecRefusal.refuse("notSuspenseLine", "This line was not booked as an unidentified receipt");
            }
            UUID suspenseLeaf = (UUID) suspense.get("account_id");
            // PR #353 review P2-4: per line. The line row is locked above, so two draws
            // on one line are serialised; the leaf balance is the second fence.
            BigDecimal drawn = jdbc.queryForObject("""
                    select coalesce(sum(amount), 0) from bank_suspense_draws where tenant_id = :t and statement_line_id = :l""",
                    new MapSqlParameterSource("t", t).addValue("l", line.id()), BigDecimal.class);
            BigDecimal left = line.amount().subtract(drawn);
            BigDecimal balance = jdbc.queryForObject("""
                    select coalesce(sum(credit - debit), 0) from journal_lines where tenant_id = :t and account_id = :a""",
                    new MapSqlParameterSource("t", t).addValue("a", suspenseLeaf), BigDecimal.class);
            if (amount.compareTo(left) > 0 || amount.compareTo(balance) > 0) {
                throw BankRecRefusal.refuse("suspenseShort", "The row is " + StatementValues.money(amount)
                        + "; only " + StatementValues.money(left.min(balance)) + " of this receipt is still unidentified", "amount", StatementValues.money(amount), "left", StatementValues.money(left.min(balance)));
            }
            cheques.receive(in.chequeId(), new ChequeActionRequest(line.date(), "Received from unidentified receipts", null,
                    suspenseLeaf));
            em.flush();
            UUID crt = jdbc.queryForObject("select crt_journal_id from cheques where tenant_id = :t and id = :id",
                    new MapSqlParameterSource("t", t).addValue("id", in.chequeId()), UUID.class);
            jdbc.update("""
                    insert into bank_suspense_draws (id, tenant_id, statement_line_id, cheque_id, journal_entry_id, amount, created_by)
                    values (:id, :t, :l, :c, :e, :a, :u)""",
                    new MapSqlParameterSource("id", UUID.randomUUID()).addValue("t", t).addValue("l", line.id())
                            .addValue("c", in.chequeId()).addValue("e", crt).addValue("a", amount)
                            .addValue("u", BankStatementImportService.currentUserId()));
            return done(t, null, List.of(crt));
        }
        requireNotFuture(line.date());
        if (amount.compareTo(line.amount()) != 0) {
            throw BankRecRefusal.refuse("rowAmountMismatch", "The row is " + StatementValues.money(amount) + "; the line is "
                    + StatementValues.money(line.amount()), "amount", StatementValues.money(amount), "line", StatementValues.money(line.amount()));
        }
        UUID leaf = leafFor(leaves, (UUID) c.get("property_id"), in.bankLeafId());
        cheques.receive(in.chequeId(), new ChequeActionRequest(line.date(), "Received per bank statement", null, leaf));
        em.flush();
        UUID crt = jdbc.queryForObject("select crt_journal_id from cheques where tenant_id = :t and id = :id",
                new MapSqlParameterSource("t", t).addValue("id", in.chequeId()), UUID.class);
        UUID m = matches.recordCreated(bankAccountId, List.of(line.id()), bankLines(t, crt, leaves, true));
        return done(t, m, List.of(crt));
    }

    /** The suspense BNK a line is matched to (its credit line on BANK_SUSPENSE), or null. */
    private Map<String, Object> suspenseOf(UUID t, UUID lineId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select sj.account_id from bank_match_statement_lines ml
                join bank_matches m on m.id = ml.match_id and m.status = 'CONFIRMED'
                join bank_match_book_items i on i.match_id = m.id and not i.released
                join journal_lines bl on bl.id = i.journal_line_id
                join journal_lines sj on sj.journal_entry_id = bl.journal_entry_id and sj.credit > 0
                join journal_entries je on je.id = bl.journal_entry_id and je.doc_type = 'BNK' and je.reversed_by_id is null
                join tenant_default_account_mappings d on d.account_id = sj.account_id and d.role = 'BANK_SUSPENSE'
                  and d.tenant_id = ml.tenant_id
                where ml.tenant_id = :t and ml.statement_line_id = :l and not ml.released limit 1""",
                new MapSqlParameterSource("t", t).addValue("l", lineId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ------------------------------------------------------------------ bounce

    /** Bounce: a debit ↔ a CLEARED cheque banked into the set, equal amount. Posts the CBR. */
    @Transactional
    public BankRecDTOs.ActionResult bounce(BankRecDTOs.BounceInput in) {
        UUID t = BankAccountLedgerService.requireTenant();
        L line = lockLines(t, List.of(in.statementLineId()), true).get(0);
        Set<UUID> leaves = ledgers.requireLeafSet(line.bankAccountId());
        if (line.amount().signum() >= 0) throw BankRecRefusal.refuse("bounceFromDebit", "A returned cheque is booked from a debit line");
        requireNotFuture(line.date());
        Map<String, Object> c = cheque(t, in.chequeId());
        if (!"CLEARED".equals(c.get("status"))) {
            throw BankRecRefusal.refuse("chequeNotCleared", "The cheque is " + c.get("status") + "; only a CLEARED cheque bounces from a statement", "status", c.get("status"));
        }
        if (((BigDecimal) c.get("amount")).compareTo(line.amount().negate()) != 0) {
            throw BankRecRefusal.refuse("chequeAmountMismatch", "The cheque is " + StatementValues.money((BigDecimal) c.get("amount"))
                    + "; the line is " + StatementValues.money(line.amount().negate()), "amount", StatementValues.money((BigDecimal) c.get("amount")), "line", StatementValues.money(line.amount().negate()));
        }
        if (c.get("debit_account_id") == null || !leaves.contains((UUID) c.get("debit_account_id"))) {
            throw BankRecRefusal.refuse("chequeNotClearedHere", "The cheque was not cleared into this bank account");
        }
        // The register's bounce refuses a return without its reason (PR #353 review P2-6).
        if (in.reason() == null || in.reason().isBlank()) {
            throw BankRecRefusal.refuse("bounceReasonRequired", "Say why the bank returned the cheque (failureReason is required)");
        }
        ChequeFailureReason reason;
        try {
            reason = ChequeFailureReason.valueOf(in.reason().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw BankRecRefusal.refuse("unknownReturnReason", "Unknown return reason " + in.reason(), "reason", in.reason());
        }
        cheques.bounce(in.chequeId(), new ChequeActionRequest(line.date(), "Returned per bank statement", reason, null));
        em.flush();
        UUID cbr = jdbc.queryForObject("select cbr_journal_id from cheques where tenant_id = :t and id = :id",
                new MapSqlParameterSource("t", t).addValue("id", in.chequeId()), UUID.class);
        UUID m = matches.recordCreated(line.bankAccountId(), List.of(line.id()), bankLines(t, cbr, leaves, false));
        return done(t, m, List.of(cbr));
    }

    // ------------------------------------------------------------------ present

    /** Present supplier cheque: a debit ↔ an ISSUED cheque drawn on the set, equal amount and number. Posts the BPC. */
    @Transactional
    public BankRecDTOs.ActionResult present(BankRecDTOs.PresentInput in) {
        UUID t = BankAccountLedgerService.requireTenant();
        L line = lockLines(t, List.of(in.statementLineId()), true).get(0);
        Set<UUID> leaves = ledgers.requireLeafSet(line.bankAccountId());
        if (line.amount().signum() >= 0) throw BankRecRefusal.refuse("presentFromDebit", "A presented cheque is booked from a debit line");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, cheque_number, status, amount, bank_account_id from issued_cheques where tenant_id = :t and id = :id""",
                new MapSqlParameterSource("t", t).addValue("id", in.issuedChequeId()));
        if (rows.isEmpty()) throw new NotFoundException("Issued cheque not found");
        Map<String, Object> c = rows.get(0);
        if (!"ISSUED".equals(c.get("status"))) {
            throw BankRecRefusal.refuse("issuedChequeStatus", "Cheque " + c.get("cheque_number") + " is " + c.get("status"), "cheque", c.get("cheque_number"), "status", c.get("status"));
        }
        if (((BigDecimal) c.get("amount")).compareTo(line.amount().negate()) != 0) {
            throw BankRecRefusal.refuse("issuedChequeAmountMismatch", "Cheque " + c.get("cheque_number") + " is "
                    + StatementValues.money((BigDecimal) c.get("amount")) + "; the line is " + StatementValues.money(line.amount().negate()), "cheque", c.get("cheque_number"), "amount", StatementValues.money((BigDecimal) c.get("amount")), "line", StatementValues.money(line.amount().negate()));
        }
        if (!leaves.contains((UUID) c.get("bank_account_id"))) {
            throw BankRecRefusal.refuse("issuedChequeOtherBank", "Cheque " + c.get("cheque_number") + " is drawn on another bank account", "cheque", c.get("cheque_number"));
        }
        if (line.chequeNo() != null && !BankMatchService.sameChequeNo(line.chequeNo(), (String) c.get("cheque_number"))) {
            throw BankRecRefusal.refuse("lineNamesOtherCheque", "The line names cheque " + line.chequeNo() + ", not " + c.get("cheque_number"), "lineCheque", line.chequeNo(), "cheque", c.get("cheque_number"));
        }
        issuedCheques.present(in.issuedChequeId(), line.date());
        em.flush();
        UUID bpc = jdbc.queryForObject("select bpc_journal_id from issued_cheques where tenant_id = :t and id = :id",
                new MapSqlParameterSource("t", t).addValue("id", in.issuedChequeId()), UUID.class);
        UUID m = matches.recordCreated(line.bankAccountId(), List.of(line.id()), bankLines(t, bpc, leaves, false));
        return done(t, m, List.of(bpc));
    }

    // ------------------------------------------------------------------ BNK

    /** Bank charge, interest, unidentified receipt or other: a BNK through PostingService, and its match. */
    @Transactional
    public BankRecDTOs.ActionResult post(BankRecDTOs.PostLinesInput in) {
        UUID t = BankAccountLedgerService.requireTenant();
        BankStatementPostingService.Kind kind;
        try {
            kind = BankStatementPostingService.Kind.valueOf(in.kind().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw BankRecRefusal.refuse("unknownPostingKind", "Unknown posting kind " + in.kind(), "kind", in.kind());
        }
        List<L> ls = lockLines(t, in.statementLineIds(), true);
        UUID bankAccountId = ls.get(0).bankAccountId();
        BankAccount bank = ledgers.requireBankAccount(bankAccountId);
        Set<UUID> leaves = ledgers.requireLeafSet(bankAccountId);
        UUID leaf = pickLeaf(leaves, in.bankLeafId());
        LocalDate date = dateOf(ls);
        requireNotFuture(date);
        boolean shared = in.shared() != null ? in.shared() : leaves.size() > 1;
        UUID propertyId;
        if (kind == BankStatementPostingService.Kind.OTHER && in.propertyId() != null) {
            Integer n = jdbc.queryForObject("select count(*) from properties where id = :p and tenant_id = :t",
                    new MapSqlParameterSource("t", t).addValue("p", in.propertyId()), Integer.class);
            if (n == null || n == 0) throw new NotFoundException("Property not found");
            propertyId = in.propertyId();
        } else {
            propertyId = shared ? null : jdbc.queryForObject("select property_id from accounts where id = :a and tenant_id = :t",
                    new MapSqlParameterSource("t", t).addValue("a", leaf), UUID.class);
        }
        JournalEntry e = bnk.post(kind, ls.stream().map(l -> new BankStatementPostingService.Line(l.id(), l.amount(),
                        l.description(), l.reference())).toList(), date, leaf, propertyId,
                Boolean.TRUE.equals(in.vatIncluded()), bank.getBankTrn() != null && !bank.getBankTrn().isBlank(),
                in.accountId(), in.narration(), in.net(), in.vat(), leaves);
        BigDecimal total = sum(ls);
        UUID m = matches.recordCreated(bankAccountId, ls.stream().map(L::id).toList(),
                bankLines(t, e.getId(), Set.of(leaf), total.signum() > 0));
        return done(t, m, List.of(e.getId()));
    }

    // ------------------------------------------------------------------ candidates

    /** What each action would use for this line, for the dialogs (spec §3 web). */
    @Transactional(readOnly = true)
    public BankRecDTOs.LineCandidates candidates(UUID lineId) {
        UUID t = BankAccountLedgerService.requireTenant();
        List<L> found = jdbc.query("""
                select id, bank_account_id, txn_date, value_date, description, reference, cheque_no, amount
                from bank_statement_lines where tenant_id = :t and id = :id""",
                new MapSqlParameterSource("t", t).addValue("id", lineId),
                (rs, i) -> new L(rs.getObject("id", UUID.class), rs.getObject("bank_account_id", UUID.class),
                        rs.getObject("txn_date", LocalDate.class), rs.getObject("value_date", LocalDate.class),
                        rs.getString("description"), rs.getString("reference"), rs.getString("cheque_no"),
                        rs.getBigDecimal("amount")));
        if (found.isEmpty()) throw new NotFoundException("Statement line not found");
        L l = found.get(0);
        BankAccount bank = ledgers.requireBankAccount(l.bankAccountId());
        List<BankRecDTOs.Leaf> leafRows = ledgers.leaves(l.bankAccountId());
        Set<UUID> leaves = new HashSet<>();
        leafRows.forEach(x -> leaves.add(x.id()));
        leaves.add(new UUID(0, 0)); // keeps "in (:l)" valid when the set is empty
        MapSqlParameterSource p = new MapSqlParameterSource("t", t).addValue("l", leaves)
                .addValue("amt", l.amount().abs()).addValue("d", l.date());
        List<BankRecDTOs.Candidate> clear = List.of(), receive = List.of(), bounce = List.of(), present = List.of();
        List<BankRecDTOs.Refused> refused = new ArrayList<>();
        if (l.amount().signum() > 0) {
            clear = jdbc.query("""
                    select c.id, c.cheque_number, c.amount, c.deposited_at, c.property_id, c.status, r.name_en
                    from cheques c left join renters r on r.id = c.renter_id
                    where c.tenant_id = :t and c.status = 'DEPOSITED' and c.amount <= :amt
                      and (c.debit_account_id in (:l)
                           or (c.debit_account_id is null and coalesce(
                                 (select pm.account_id from property_account_mappings pm
                                   where pm.tenant_id = c.tenant_id and pm.property_id = c.property_id and pm.role = 'BANK'),
                                 (select d.account_id from tenant_default_account_mappings d
                                   where d.tenant_id = c.tenant_id and d.role = 'BANK')) in (:l)))
                    order by (c.amount = :amt) desc, c.deposited_at, c.cheque_number limit 100""", p,
                    (rs, i) -> cand(rs, "CHEQUE", rs.getObject("deposited_at", LocalDate.class), l));
            clear = preselectGroup(clear, l);
            receive = jdbc.query("""
                    select c.id, c.cheque_number, c.amount, c.cheque_date as d, c.property_id, c.status, c.mode,
                           coalesce(r.name_en, c.payer_name) as name_en
                    from cheques c left join renters r on r.id = c.renter_id
                    where c.tenant_id = :t and c.status = 'REGISTERED' and c.mode in ('TRANSFER', 'CASH') and c.amount = :amt
                      and coalesce((select je.entry_date from journal_entries je where je.id = c.pdr_journal_id), c.posting_date) <= :d
                    order by c.cheque_date limit 100""", p,
                    (rs, i) -> cand(rs, rs.getString("mode"), rs.getObject("d", LocalDate.class), l));
            // F14-02: a row put on the books after the line's date cannot be received on it.
            refused.addAll(jdbc.query("""
                    select c.id, c.cheque_number, c.amount, coalesce((select je.entry_date from journal_entries je where je.id = c.pdr_journal_id), c.posting_date) as booked,
                           coalesce(r.name_en, c.payer_name) as name_en
                    from cheques c left join renters r on r.id = c.renter_id
                    where c.tenant_id = :t and c.status = 'REGISTERED' and c.mode in ('TRANSFER', 'CASH') and c.amount = :amt
                      and coalesce((select je.entry_date from journal_entries je where je.id = c.pdr_journal_id), c.posting_date) > :d
                    order by c.cheque_date limit 20""", p,
                    (rs, i) -> refusedReceipt(rs, l)));
            String text = (l.description() + " " + Objects.toString(l.reference(), "")).toUpperCase(Locale.ROOT);
            receive = receive.stream().sorted(Comparator.comparing((BankRecDTOs.Candidate c) ->
                    c.label() != null && Arrays.stream(c.label().toUpperCase(Locale.ROOT).split("\\s+"))
                            .anyMatch(w -> w.length() > 2 && text.contains(w)) ? 0 : 1)).toList();
        } else {
            bounce = jdbc.query("""
                    select c.id, c.cheque_number, c.amount, c.cleared_at as d, c.property_id, c.status, r.name_en
                    from cheques c left join renters r on r.id = c.renter_id
                    where c.tenant_id = :t and c.status = 'CLEARED' and c.mode = 'PDC' and c.debit_account_id in (:l)
                      and c.amount = :amt order by c.cleared_at desc limit 100""", p,
                    (rs, i) -> cand(rs, "CHEQUE", rs.getObject("d", LocalDate.class), l));
            present = jdbc.query("""
                    select ic.id, ic.cheque_number, ic.amount, ic.cheque_date as d, null::uuid as property_id, ic.status,
                           v.name_en
                    from issued_cheques ic left join vendors v on v.id = ic.vendor_id
                    where ic.tenant_id = :t and ic.status = 'ISSUED' and ic.bank_account_id in (:l) and ic.amount = :amt
                      and ic.cheque_date <= :d order by ic.cheque_date limit 100""", p,
                    (rs, i) -> cand(rs, "ISSUED_CHEQUE", rs.getObject("d", LocalDate.class), l));
            // F14-06: the issued cheque that fits but is dated after the line; say so
            // instead of "nothing in the books fits".
            refused.addAll(jdbc.query("""
                    select ic.id, ic.cheque_number, ic.amount, ic.cheque_date as d, v.name_en
                    from issued_cheques ic left join vendors v on v.id = ic.vendor_id
                    where ic.tenant_id = :t and ic.status = 'ISSUED' and ic.bank_account_id in (:l) and ic.amount = :amt
                      and ic.cheque_date > :d order by ic.cheque_date limit 20""", p,
                    (rs, i) -> refusedPresent(rs, l)));
        }
        BigDecimal suspense = jdbc.queryForObject("""
                select coalesce(sum(jl.credit - jl.debit), 0) from journal_lines jl
                join tenant_default_account_mappings d on d.account_id = jl.account_id and d.tenant_id = jl.tenant_id
                where jl.tenant_id = :t and d.role = 'BANK_SUSPENSE'""", p, BigDecimal.class);
        return new BankRecDTOs.LineCandidates(l.id(), clear, receive, bounce, present, suspense,
                bank.getBankTrn() != null && !bank.getBankTrn().isBlank(), leafRows, refused);
    }

    private static final java.time.format.DateTimeFormatter DMY = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static BankRecDTOs.Refused refusedReceipt(java.sql.ResultSet rs, L l) throws java.sql.SQLException {
        String no = rs.getString("cheque_number");
        String name = rs.getString("name_en");
        String label = ((no == null ? "" : no + " ") + (name == null ? "" : name)).trim();
        LocalDate booked = rs.getObject("booked", LocalDate.class);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("row", label);
        args.put("booked", booked.format(DMY));
        args.put("date", l.date().format(DMY));
        return new BankRecDTOs.Refused(rs.getObject("id", UUID.class), "receive", label, rs.getBigDecimal("amount"),
                booked, "bankrec.receiveBeforeBooked", args,
                label + " was put on the books on " + booked.format(DMY) + "; it cannot be received on "
                        + l.date().format(DMY) + ", before that date.");
    }

    private static BankRecDTOs.Refused refusedPresent(java.sql.ResultSet rs, L l) throws java.sql.SQLException {
        String no = rs.getString("cheque_number");
        String name = rs.getString("name_en");
        String label = ((no == null ? "" : no + " ") + (name == null ? "" : name)).trim();
        LocalDate dated = rs.getObject("d", LocalDate.class);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("cheque", label);
        args.put("chequeDate", dated.format(DMY));
        args.put("date", l.date().format(DMY));
        return new BankRecDTOs.Refused(rs.getObject("id", UUID.class), "present", label, rs.getBigDecimal("amount"),
                dated, "bankrec.presentBeforeChequeDate", args,
                "Issued cheque " + label + " is dated " + dated.format(DMY) + "; a cheque cannot be presented before its date ("
                        + "this line is " + l.date().format(DMY) + ").");
    }

    private static BankRecDTOs.Candidate cand(java.sql.ResultSet rs, String kind, LocalDate date, L l) throws java.sql.SQLException {
        String no = rs.getString("cheque_number");
        BigDecimal amt = rs.getBigDecimal("amount");
        boolean pre = amt.compareTo(l.amount().abs()) == 0 && l.chequeNo() != null && BankMatchService.sameChequeNo(no, l.chequeNo());
        String name = rs.getString("name_en");
        return new BankRecDTOs.Candidate(rs.getObject("id", UUID.class), kind,
                (no == null ? "" : no + " ") + (name == null ? "" : name), amt, date, no,
                rs.getObject("property_id", UUID.class), rs.getString("status"), pre);
    }

    /** With no cheque-number hit, a single deposit day whose cheques add up to the line is pre-selected (rule 3). */
    private static List<BankRecDTOs.Candidate> preselectGroup(List<BankRecDTOs.Candidate> cs, L l) {
        if (cs.stream().anyMatch(BankRecDTOs.Candidate::preselected)) return cs;
        Map<LocalDate, BigDecimal> byDay = new HashMap<>();
        cs.forEach(c -> { if (c.date() != null) byDay.merge(c.date(), c.amount(), BigDecimal::add); });
        List<LocalDate> fit = byDay.entrySet().stream().filter(e -> e.getValue().compareTo(l.amount()) == 0)
                .map(Map.Entry::getKey).toList();
        if (fit.size() != 1) return cs;
        return cs.stream().map(c -> fit.get(0).equals(c.date()) ? new BankRecDTOs.Candidate(c.id(), c.kind(), c.label(),
                c.amount(), c.date(), c.chequeNo(), c.propertyId(), c.status(), true) : c).toList();
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> cheque(UUID t, UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, cheque_number, status, mode, amount, debit_account_id, property_id from cheques where tenant_id = :t and id = :id""",
                new MapSqlParameterSource("t", t).addValue("id", id));
        if (rows.isEmpty()) throw new NotFoundException("Cheque not found");
        return rows.get(0);
    }

    private UUID propertyBank(UUID propertyId) {
        if (propertyId == null) return null;
        Account a = resolver.resolveOrNull(AccountRole.BANK, propertyId);
        return a == null ? null : a.getId();
    }

    /** The leaf the user picked (must be in the set), else the set's only leaf. */
    private static UUID pickLeaf(Set<UUID> leaves, UUID picked) {
        if (picked != null) {
            if (!leaves.contains(picked)) throw BankRecRefusal.refuse("leafNotInSet", "That ledger account is not in this bank account's set");
            return picked;
        }
        if (leaves.size() == 1) return leaves.iterator().next();
        throw BankRecRefusal.refuse("chooseLeaf", "This bank account has several ledger accounts; choose the one to post to");
    }

    /** The property's BANK leaf when it is in the set; else the pick; else the only leaf. */
    private UUID leafFor(Set<UUID> leaves, UUID propertyId, UUID picked) {
        if (picked != null) return pickLeaf(leaves, picked);
        if (propertyId != null) {
            Account a = resolver.resolveOrNull(AccountRole.BANK, propertyId);
            if (a != null && leaves.contains(a.getId())) return a.getId();
        }
        return pickLeaf(leaves, null);
    }
}
