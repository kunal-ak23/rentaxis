package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.lease.GenerateChequesRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.core.service.ChequeRoundingCalculator;
import com.datagami.rentaxis.core.service.cheque.ChequeMapper;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.core.util.DateMath;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.ChargeType;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseLine;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChargeBehaviour;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.InstallmentDistribution;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LeaseLineRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The cheque grid on a draft lease (spec §7.1) — how a contract's money is cut
 * into the instruments the renter actually hands over.
 *
 * <p>This is deliberately <em>not</em> the old payment schedule. A schedule was a
 * side effect of saving a lease and was regenerated behind the user's back; the
 * grid is an explicit act with a visible result the user then edits row by row,
 * because what the renter wrote on six pieces of paper is a fact about the world
 * and not something the software gets to derive twice.</p>
 *
 * <p>Nothing here posts. Rows are written {@code DRAFT}, and a DRAFT cheque is
 * invisible to the whole register ({@code ChequeRepository} filters on the
 * lease's status) precisely so a half-finished grid never counts as money owed.
 * Task 6 turns the grid into journals and is also where Σ cheques is required to
 * equal the contract value — checking it here would block the intermediate state
 * every edit passes through.</p>
 *
 * <p><b>DRAFT only, throughout.</b> {@code generate}, {@code saveRows} and
 * {@code generateNumbers} all refuse a lease that is not DRAFT and all skip any
 * cheque row that is not DRAFT. A registered cheque has a journal against it; an
 * "edit" of one is a replacement, which is Task 9's business, not a grid rewrite.</p>
 *
 * <p>Every public method is {@code @Transactional}: {@code TenantAspect} only
 * enables the Hibernate tenant filter inside a transaction, so a read outside one
 * would cross tenants.</p>
 */
@Service
public class ChequeGenerationService {

    /** PACT rounds cheque grids to tens; see {@code ChequeRoundingCalculator}. */
    static final BigDecimal TEN = new BigDecimal("10");

    /**
     * Short labels for the folded narration. PACT's grid reads
     * "Rent - 1st Installment | SD | Admin", not the catalogue's full names — the
     * line is 255 characters and a cheque with four extras folded in would spend
     * them all on "Security Deposit". A charge type outside this map falls back to
     * its own English name, which is what a tenant's own particular should show.
     */
    private static final Map<String, String> FOLD_LABELS = Map.of(
            "SECURITY_DEPOSIT", "SD",
            "ADMIN_FEE", "Admin",
            "PARKING_DEPOSIT", "Parking SD");

    private final LeaseRepository leaseRepository;
    private final LeaseLineRepository leaseLineRepository;
    private final ChequeRepository chequeRepository;
    private final AccountRepository accountRepository;
    private final AccountResolver accountResolver;
    private final LeaseAccessPolicy leaseAccessPolicy;

    public ChequeGenerationService(LeaseRepository leaseRepository,
                                   LeaseLineRepository leaseLineRepository,
                                   ChequeRepository chequeRepository,
                                   AccountRepository accountRepository,
                                   AccountResolver accountResolver,
                                   LeaseAccessPolicy leaseAccessPolicy) {
        this.leaseRepository = leaseRepository;
        this.leaseLineRepository = leaseLineRepository;
        this.chequeRepository = chequeRepository;
        this.accountRepository = accountRepository;
        this.accountResolver = accountResolver;
        this.leaseAccessPolicy = leaseAccessPolicy;
    }

    // ------------------------------------------------------------------
    // pure helpers — the arithmetic, testable without a database
    // ------------------------------------------------------------------

    /** A non-rent charge as the grid sees it: a label for the narration and an amount. */
    public record Extra(String label, BigDecimal amount) {}

    /** One proposed grid row before it is given a renter, a property and an id. */
    public record Row(int seqNo, LocalDate postingDate, LocalDate chequeDate, BigDecimal amount, String narration) {}

    /**
     * The grid itself: rent split into {@code n} instalments, with the deposits and
     * fees either folded into the first cheque or standing as their own rows.
     *
     * <p><b>Spacing.</b> Due date {@code i} is
     * {@code firstDueDate.plusMonths(floor(i × months / n))}, the rule v1's
     * schedule generator already used, with {@code months} the end-inclusive whole
     * month count of the span being scheduled. Twelve months over four cheques
     * gives offsets [0, 3, 6, 9]; thirteen over four gives the same offsets and a
     * last cheque covering four months. The span is measured from
     * {@code firstDueDate}, not from the tenancy start, because the anchor and the
     * count have to be the same date or the last cheque drifts past the end of the
     * term — and {@code firstDueDate} defaults to the tenancy start anyway.</p>
     *
     * <p><b>Folding.</b> PACT hands the deposit and the admin fee over with the
     * first cheque rather than as separate instruments, so folding adds them to row
     * 1's amount and appends their labels to its narration. Unfolded, each becomes
     * its own row dated {@code postingDate} — the contract date — because a deposit
     * is due when the contract is signed, not when the first rent instalment falls.</p>
     *
     * @param rent Σ net of the RENT lines; may be zero for a lease that charges none.
     * @param extras non-rent charges, in the order they should appear.
     * @param n number of rent instalments (>= 1).
     * @param postingDate the lease's contract date; every row posts on it.
     * @param firstDueDate date of the first instalment.
     * @param leaseEnd last day of the tenancy, inclusive.
     */
    public static List<Row> buildRows(BigDecimal rent,
                                      List<Extra> extras,
                                      int n,
                                      LocalDate postingDate,
                                      LocalDate firstDueDate,
                                      LocalDate leaseEnd,
                                      InstallmentDistribution distribution,
                                      boolean fold) {
        if (n < 1) throw new BusinessRuleViolationException("Number of instalments must be at least 1");
        if (firstDueDate == null) throw new BusinessRuleViolationException("First due date is required");
        if (leaseEnd == null) throw new BusinessRuleViolationException("Lease end date is required");
        List<Extra> nonRent = extras == null ? List.of() : extras.stream().filter(e -> e.amount() != null && e.amount().signum() > 0).toList();
        boolean hasRent = rent != null && rent.signum() > 0;
        if (!hasRent && nonRent.isEmpty()) {
            throw new BusinessRuleViolationException("The lease has nothing to collect: every line is zero");
        }

        List<Row> rows = new ArrayList<>();
        int seq = 1;

        if (hasRent) {
            List<BigDecimal> amounts = ChequeRoundingCalculator
                    .distribute(rent, n, distribution == null ? InstallmentDistribution.FIRST_LARGER : distribution, TEN)
                    .amounts();
            long months = DateMath.monthsInclusive(firstDueDate, leaseEnd);
            for (int i = 0; i < n; i++) {
                long monthOffset = (long) Math.floor((double) i * months / n);
                BigDecimal amount = amounts.get(i);
                String narration = "Rent - " + ordinal(i + 1) + " Installment";
                if (i == 0 && fold) {
                    for (Extra e : nonRent) {
                        amount = amount.add(e.amount());
                        narration = narration + " | " + e.label();
                    }
                }
                rows.add(new Row(seq++, postingDate, firstDueDate.plusMonths(monthOffset), amount, narration));
            }
        }

        // Unfolded extras — and every extra when the lease charges no rent at all,
        // since there is no first instalment to fold them into.
        if (!fold || !hasRent) {
            for (Extra e : nonRent) {
                rows.add(new Row(seq++, postingDate, postingDate, e.amount(), e.label()));
            }
        }
        return rows;
    }

    /**
     * The {@code offset}-th cheque number after {@code startingNumber}, keeping the
     * book's own formatting.
     *
     * <p>Cheque books are zero-padded to a fixed width and a leading zero is not
     * decoration: "000028" and "28" are different numbers to the bank. The numeric
     * tail is incremented and re-padded to the width it arrived with, and any
     * non-numeric prefix ("CHQ-") is carried through untouched. A number that
     * overflows its width simply gets wider — truncating it would produce a
     * duplicate.</p>
     */
    public static String nextNumber(String startingNumber, int offset) {
        if (startingNumber == null || startingNumber.isBlank()) {
            throw new BusinessRuleViolationException("A starting cheque number is required");
        }
        String s = startingNumber.trim();
        int firstDigit = s.length();
        for (int i = s.length() - 1; i >= 0; i--) {
            if (Character.isDigit(s.charAt(i))) firstDigit = i;
            else break;
        }
        if (firstDigit == s.length()) {
            throw new BusinessRuleViolationException(
                    "Cheque number '" + startingNumber + "' does not end in a number to count from");
        }
        String prefix = s.substring(0, firstDigit);
        String digits = s.substring(firstDigit);
        // BigInteger rather than long: a cheque number is a string of digits with
        // no defined width, and a 20-digit one would silently wrap a long.
        String next = new java.math.BigInteger(digits).add(java.math.BigInteger.valueOf(offset)).toString();
        if (next.length() < digits.length()) {
            next = "0".repeat(digits.length() - next.length()) + next;
        }
        return prefix + next;
    }

    /**
     * English ordinal for an instalment position.
     *
     * <p>The teens are the whole reason this is a method: 11, 12 and 13 take "th"
     * although they end in 1, 2 and 3, so the naive last-digit rule writes
     * "11st Installment" on the eleventh cheque of a twelve-cheque lease — the most
     * common lease there is.</p>
     */
    static String ordinal(int i) {
        int lastTwo = i % 100;
        if (lastTwo >= 11 && lastTwo <= 13) return i + "th";
        return switch (i % 10) {
            case 1 -> i + "st";
            case 2 -> i + "nd";
            case 3 -> i + "rd";
            default -> i + "th";
        };
    }

    /** The narration label a charge type contributes when it is folded into row 1. */
    static String foldLabel(ChargeType type) {
        if (type == null) return "Charge";
        String shortLabel = FOLD_LABELS.get(type.getCode());
        return shortLabel != null ? shortLabel : type.getNameEn();
    }

    // ------------------------------------------------------------------
    // the service
    // ------------------------------------------------------------------

    /** The lease's grid, every status, in schedule order. */
    @Transactional(readOnly = true)
    public List<ChequeDTO> list(UUID leaseId) {
        Lease lease = readableLease(leaseId);
        return toDtos(chequeRepository.findByLease_IdOrderBySeqNoAsc(leaseId), lease);
    }

    /**
     * Cut the lease's lines into a fresh grid, replacing whatever DRAFT rows were
     * there. Non-DRAFT rows are left alone, which on a DRAFT lease means there are
     * none to leave.
     */
    @Transactional
    public List<ChequeDTO> generate(UUID leaseId, GenerateChequesRequest request) {
        Lease lease = draftLease(leaseId);
        GenerateChequesRequest r = request == null
                ? new GenerateChequesRequest(null, null, null, null, null, null, null)
                : request;

        ChequeMode mode = mode(r.mode());
        int n = installments(r, lease);
        LocalDate firstDueDate = firstNonNull(r.firstDueDate(), lease.getFirstDueDate(), lease.getStartDate());
        // FIRST_LARGER rather than the lease's own installmentDistribution: the
        // grid's residual belongs on the cheque the landlord is most certain of,
        // which is the one handed over at signing. The lease-level field is the v1
        // schedule's preference and defaults to LAST_LARGER for rows that are
        // already written; a caller who wants it says so in the request.
        InstallmentDistribution distribution = r.distribution() != null
                ? r.distribution() : InstallmentDistribution.FIRST_LARGER;
        boolean fold = r.foldDepositsAndFeesIntoFirst() == null || r.foldDepositsAndFeesIntoFirst();

        List<LeaseLine> lines = leaseLineRepository.findByLease_IdOrderBySeqNoAsc(leaseId);
        if (lines.isEmpty()) {
            throw new BusinessRuleViolationException("The lease has no lines to generate cheques from");
        }
        BigDecimal rent = BigDecimal.ZERO;
        List<Extra> extras = new ArrayList<>();
        for (LeaseLine line : lines) {
            ChargeType type = line.getChargeType();
            BigDecimal net = line.getNetAmount() == null ? BigDecimal.ZERO : line.getNetAmount();
            if (type != null && type.getBehaviour() == ChargeBehaviour.RENT) {
                rent = rent.add(net);
            } else if (net.signum() > 0) {
                extras.add(new Extra(foldLabel(type), net));
            }
        }

        List<Row> rows = buildRows(rent, extras, n, lease.getContractDate(), firstDueDate,
                lease.getEndDate(), distribution, fold);

        // The old grid goes before the new one is written: a derived delete loads
        // the rows first, so the tenant filter applies to them as it would to a
        // read, and the flush makes the deletes hit the database ahead of the
        // inserts rather than after them.
        chequeRepository.deleteByLease_IdAndStatus(leaseId, ChequeStatus.DRAFT);
        chequeRepository.flush();

        Account debitAccount = debitAccount(r.debitAccountId(), lease);
        List<Cheque> saved = new ArrayList<>(rows.size());
        for (Row row : rows) {
            Cheque c = blank(lease);
            c.setSeqNo(row.seqNo());
            c.setPostingDate(row.postingDate());
            c.setChequeDate(row.chequeDate());
            c.setAmount(row.amount());
            c.setNarration(row.narration());
            c.setMode(mode);
            c.setPayeeBank(r.payeeBank());
            c.setDebitAccount(debitAccount);
            saved.add(c);
        }
        return toDtos(chequeRepository.saveAll(saved), lease);
    }

    /**
     * Number the grid: the starting number on the first DRAFT PDC row, the next on
     * the one after it, and so on in schedule order.
     *
     * <p>Only PDC rows are numbered — a cash receipt has no cheque number and the
     * partial unique index does not cover one — and numbering skips any gap the
     * cash rows leave rather than burning a number on them.</p>
     */
    @Transactional
    public List<ChequeDTO> generateNumbers(UUID leaseId, String startingNumber) {
        Lease lease = draftLease(leaseId);
        List<Cheque> all = chequeRepository.findByLease_IdOrderBySeqNoAsc(leaseId);
        List<Cheque> targets = all.stream()
                .filter(c -> c.getStatus() == ChequeStatus.DRAFT && c.getMode() == ChequeMode.PDC)
                .toList();
        if (targets.isEmpty()) {
            throw new BusinessRuleViolationException("The lease has no draft cheques to number");
        }

        // Numbers already spoken for by rows this call will not touch. The index
        // would refuse the clash anyway, as a 409 naming a constraint; saying it
        // here makes it a sentence about a cheque number.
        Set<String> taken = new HashSet<>();
        for (Cheque c : all) {
            if (c.getMode() == ChequeMode.PDC && c.getStatus() != ChequeStatus.DRAFT && c.getChequeNumber() != null) {
                taken.add(c.getChequeNumber());
            }
        }
        List<String> numbers = new ArrayList<>(targets.size());
        for (int i = 0; i < targets.size(); i++) {
            String number = nextNumber(startingNumber, i);
            if (taken.contains(number)) {
                throw new BusinessRuleViolationException(
                        "Cheque number " + number + " is already used on this lease");
            }
            numbers.add(number);
        }

        // Clear first, then assign. The unique index is checked per statement, so
        // renumbering a grid in place (100041 -> 100042 while another row still
        // holds 100042) would collide on a value that is about to be freed.
        targets.forEach(c -> c.setChequeNumber(null));
        chequeRepository.saveAll(targets);
        chequeRepository.flush();
        for (int i = 0; i < targets.size(); i++) {
            targets.get(i).setChequeNumber(numbers.get(i));
        }
        chequeRepository.saveAll(targets);
        chequeRepository.flush();
        return toDtos(chequeRepository.findByLease_IdOrderBySeqNoAsc(leaseId), lease);
    }

    /**
     * Replace the DRAFT grid with what the user edited: rows carrying an id are
     * updated, rows without one are inserted, and DRAFT rows the payload leaves out
     * are deleted.
     *
     * <p>Σ of the rows is <em>not</em> required to equal the contract value here.
     * Every edit that changes one row's amount passes through a state where it does
     * not, and refusing to save that state would make the grid uneditable. The
     * equality is a posting precondition (Task 6), where it can be reported once
     * against a finished grid.</p>
     */
    @Transactional
    public List<ChequeDTO> saveRows(UUID leaseId, List<ChequeRowInput> rows) {
        Lease lease = draftLease(leaseId);
        List<ChequeRowInput> input = rows == null ? List.of() : rows;

        List<Cheque> existing = chequeRepository.findByLease_IdOrderBySeqNoAsc(leaseId);
        Map<UUID, Cheque> drafts = new LinkedHashMap<>();
        Set<String> takenByOthers = new HashSet<>();
        for (Cheque c : existing) {
            if (c.getStatus() == ChequeStatus.DRAFT) {
                drafts.put(c.getId(), c);
            } else if (c.getMode() == ChequeMode.PDC && c.getChequeNumber() != null) {
                takenByOthers.add(c.getChequeNumber());
            }
        }

        validate(input, drafts, takenByOthers);

        // Drop the DRAFT rows the payload no longer mentions, and flush, so a row
        // that was deleted cannot hold a cheque number the payload reassigns.
        Set<UUID> kept = new HashSet<>();
        for (ChequeRowInput row : input) {
            if (row.id() != null) kept.add(row.id());
        }
        List<Cheque> removed = drafts.values().stream().filter(c -> !kept.contains(c.getId())).toList();
        if (!removed.isEmpty()) {
            chequeRepository.deleteAll(removed);
        }
        // Retained rows give up their numbers before anything is reassigned, for
        // the same per-statement reason as generateNumbers.
        List<Cheque> retained = drafts.values().stream().filter(c -> kept.contains(c.getId())).toList();
        retained.forEach(c -> c.setChequeNumber(null));
        chequeRepository.saveAll(retained);
        chequeRepository.flush();

        Account fallbackDebit = null;
        boolean fallbackResolved = false;
        List<Cheque> out = new ArrayList<>(input.size());
        int seq = 1;
        for (ChequeRowInput row : input) {
            Cheque c = row.id() != null ? drafts.get(row.id()) : blank(lease);
            c.setSeqNo(seq++);
            c.setPostingDate(row.postingDate() != null ? row.postingDate() : lease.getContractDate());
            c.setChequeDate(row.chequeDate());
            c.setAmount(row.amount());
            c.setNarration(row.narration());
            c.setMode(row.mode() == null ? ChequeMode.PDC : row.mode());
            c.setChequeNumber(blankToNull(row.chequeNumber()));
            c.setPayeeBank(row.payeeBank());
            if (row.payerName() != null && !row.payerName().isBlank()) {
                c.setPayerName(row.payerName());
            }
            if (row.debitAccountId() != null) {
                c.setDebitAccount(account(row.debitAccountId()));
            } else if (c.getDebitAccount() == null) {
                if (!fallbackResolved) {
                    fallbackDebit = debitAccount(null, lease);
                    fallbackResolved = true;
                }
                c.setDebitAccount(fallbackDebit);
            }
            out.add(c);
        }
        chequeRepository.saveAll(out);
        chequeRepository.flush();
        return toDtos(chequeRepository.findByLease_IdOrderBySeqNoAsc(leaseId), lease);
    }

    /**
     * Row-level rules. They replace the per-payment-method validation the v1
     * schedule carried, and they are all up-front: the unique cheque number in
     * particular is checked here rather than left to {@code ux_cheques_lease_number}
     * so the user gets "cheque number 100041 appears twice" and not a 409 quoting
     * an index name.
     */
    private static void validate(List<ChequeRowInput> input, Map<UUID, Cheque> drafts, Set<String> takenByOthers) {
        Set<String> seenNumbers = new HashSet<>();
        Set<UUID> seenIds = new HashSet<>();
        for (int i = 0; i < input.size(); i++) {
            ChequeRowInput row = input.get(i);
            String where = "Row " + (i + 1) + ": ";
            if (row == null) throw new BusinessRuleViolationException(where + "is empty");

            // The same row twice would resolve to one entity, and the second copy
            // would overwrite the first — two rows the user typed silently
            // becoming one, with the money of whichever came last.
            if (row.id() != null && !seenIds.add(row.id())) {
                throw new BusinessRuleViolationException(where + "cheque " + row.id() + " appears twice in the grid");
            }

            if (row.id() != null && !drafts.containsKey(row.id())) {
                // Either it belongs to another lease or it has been registered.
                // Both are "not a draft row of this lease", and neither is a 404:
                // the lease exists, the body is wrong about one of its rows.
                throw new BusinessRuleViolationException(
                        where + "cheque " + row.id() + " is not a draft row of this lease");
            }

            ChequeMode mode = row.mode() == null ? ChequeMode.PDC : row.mode();
            if (mode == ChequeMode.ONLINE) {
                // An online receipt is created by the payment gateway callback with
                // its own reference, never typed into the grid.
                throw new BusinessRuleViolationException(
                        where + "ONLINE receipts are recorded by the payment gateway, not entered on the grid");
            }

            if (row.amount() == null || row.amount().signum() <= 0) {
                throw new BusinessRuleViolationException(where + "amount must be greater than zero");
            }
            if (row.chequeDate() == null) {
                throw new BusinessRuleViolationException(mode == ChequeMode.PDC
                        ? where + "a post-dated cheque needs the date written on it"
                        : where + "a " + mode + " receipt needs the date it is expected on");
            }

            String number = blankToNull(row.chequeNumber());
            if (number != null) {
                if (mode != ChequeMode.PDC) {
                    throw new BusinessRuleViolationException(
                            where + "a " + mode + " receipt has no cheque number");
                }
                if (!seenNumbers.add(number) || takenByOthers.contains(number)) {
                    throw new BusinessRuleViolationException(
                            where + "cheque number " + number + " is already used on this lease");
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------

    private Cheque blank(Lease lease) {
        Unit unit = lease.getUnit();
        Property property = unit != null ? unit.getProperty() : null;
        if (property == null) {
            throw new BusinessRuleViolationException("The lease's unit has no property; cheques cannot be attributed");
        }
        Cheque c = new Cheque();
        c.setTenantId(lease.getTenantId());
        c.setLease(lease);
        c.setUnit(unit);
        c.setProperty(property);
        c.setRenter(lease.getRenter());
        c.setStatus(ChequeStatus.DRAFT);
        c.setPayerName(lease.getRenter() != null ? lease.getRenter().getNameEn() : null);
        return c;
    }

    /**
     * Where cleared funds will land. {@code resolveOrNull}, never {@code resolve}
     * in a try/catch: the resolver is a transactional proxy, so an exception out of
     * {@code resolve} marks this transaction rollback-only before any catch block
     * runs. An unmapped BANK role leaves the column null and the posting guard
     * names it.
     */
    private Account debitAccount(UUID requested, Lease lease) {
        if (requested != null) return account(requested);
        Unit unit = lease.getUnit();
        UUID propertyId = unit != null && unit.getProperty() != null ? unit.getProperty().getId() : null;
        return accountResolver.resolveOrNull(AccountRole.BANK, propertyId);
    }

    private Account account(UUID id) {
        Account a = accountRepository.findById(id)
                .orElseThrow(() -> new BusinessRuleViolationException("Account " + id + " does not exist"));
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId != null && !tenantId.equals(a.getTenantId())) {
            throw new BusinessRuleViolationException("Account " + id + " does not exist");
        }
        return a;
    }

    private int installments(GenerateChequesRequest r, Lease lease) {
        Integer n = r.installments() != null ? r.installments() : lease.getPaymentTerms();
        if (n == null) {
            throw new BusinessRuleViolationException(
                    "How many instalments? The lease has no payment terms, so the request must say.");
        }
        if (n < 1) throw new BusinessRuleViolationException("Number of instalments must be at least 1");
        return n;
    }

    private static ChequeMode mode(ChequeMode requested) {
        if (requested == ChequeMode.ONLINE) {
            throw new BusinessRuleViolationException(
                    "ONLINE receipts are recorded by the payment gateway, not generated on the grid");
        }
        return requested == null ? ChequeMode.PDC : requested;
    }

    private Lease draftLease(UUID leaseId) {
        Lease lease = readableLease(leaseId);
        if (lease.getStatus() != LeaseStatus.DRAFT) {
            throw new BusinessRuleViolationException(
                    "Only DRAFT leases can have their cheque grid changed; this lease is " + lease.getStatus());
        }
        return lease;
    }

    private Lease readableLease(UUID leaseId) {
        Lease lease = leaseRepository.findById(leaseId)
                .orElseThrow(() -> new NotFoundException("Lease not found"));
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId != null && !tenantId.equals(lease.getTenantId())) {
            throw new NotFoundException("Lease not found");
        }
        leaseAccessPolicy.requireReadable(lease);
        return lease;
    }

    private static List<ChequeDTO> toDtos(List<Cheque> cheques, Lease lease) {
        LocalDate today = LocalDate.now();
        int graceDays = lease.getGracePeriodDays();
        return cheques.stream().map(c -> ChequeMapper.toDto(c, today, graceDays)).toList();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... candidates) {
        for (T c : candidates) {
            if (c != null) return c;
        }
        return null;
    }
}
