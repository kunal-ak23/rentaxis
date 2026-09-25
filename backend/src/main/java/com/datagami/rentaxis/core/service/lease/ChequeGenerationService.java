package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.lease.GenerateChequesRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.api.exception.RowLockedException;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.core.service.ChequeRoundingCalculator;
import com.datagami.rentaxis.core.service.cheque.ChequeMapper;
import com.datagami.rentaxis.core.service.cheque.ChequeRowRules;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.core.util.DateMath;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.ChargeType;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseLine;
import com.datagami.rentaxis.domain.entity.LeaseRentFreePeriod;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChargeBehaviour;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.InstallmentDistribution;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.VatTiming;
import com.datagami.rentaxis.domain.entity.enums.ChequeRowKind;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LeaseLineRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.security.core.context.SecurityContextHolder;
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
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

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

    private final com.datagami.rentaxis.core.service.bank.OwnedBankLeaf ownedBankLeaf;

    public ChequeGenerationService(LeaseRepository leaseRepository,
                                   LeaseLineRepository leaseLineRepository,
                                   ChequeRepository chequeRepository,
                                   AccountRepository accountRepository,
                                   AccountResolver accountResolver,
                                   LeaseAccessPolicy leaseAccessPolicy,
                                   com.datagami.rentaxis.core.service.bank.OwnedBankLeaf ownedBankLeaf) {
        this.ownedBankLeaf = ownedBankLeaf;
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

    /**
     * A non-rent charge as the grid sees it: a label for the narration, an amount
     * (gross), and the VAT inside that amount with the net it is charged on (spec
     * 2026-09-24 §1). The two-argument form carries no VAT.
     */
    public record Extra(String label, BigDecimal amount, BigDecimal vat, BigDecimal taxable, ChequeRowKind kind) {
        public Extra(String label, BigDecimal amount, BigDecimal vat, BigDecimal taxable) {
            this(label, amount, vat, taxable, ChequeRowKind.FEE);
        }

        public Extra(String label, BigDecimal amount) {
            this(label, amount, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    /**
     * One proposed grid row before it is given a renter, a property and an id —
     * with the VAT it collects and the net that VAT is charged on. The five-argument
     * form carries no VAT.
     */
    public record Row(int seqNo, LocalDate postingDate, LocalDate chequeDate, BigDecimal amount, String narration,
                      BigDecimal vat, BigDecimal taxable, ChequeRowKind kind) {
        public Row(int seqNo, LocalDate postingDate, LocalDate chequeDate, BigDecimal amount, String narration,
                   BigDecimal vat, BigDecimal taxable) {
            this(seqNo, postingDate, chequeDate, amount, narration, vat, taxable, null);
        }

        public Row(int seqNo, LocalDate postingDate, LocalDate chequeDate, BigDecimal amount, String narration) {
            this(seqNo, postingDate, chequeDate, amount, narration, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

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
     * @param rent Σ of the RENT lines' gross (net + VAT); may be zero for a lease that charges none.
     * @param extras non-rent charges at their gross, in the order they should appear.
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
        return buildRows(rent, BigDecimal.ZERO, BigDecimal.ZERO, extras, n, postingDate, firstDueDate, leaseEnd,
                distribution, fold);
    }

    /**
     * {@link #buildRows(BigDecimal, List, int, LocalDate, LocalDate, LocalDate, InstallmentDistribution, boolean)}
     * with the rent's VAT spread across the rent rows (spec 2026-09-24 §1).
     *
     * <p>Each rent row carries the rent VAT in proportion to its amount, rounded to
     * the fils with the <b>first</b> row absorbing the remainder — the same row
     * {@code ChequeRoundingCalculator} gives the residual to — so Σ row VAT is the
     * rent VAT exactly. A folded extra adds its own VAT to row 1; an unfolded one
     * carries it on its own row. A deposit carries none ({@code LeaseVat}).</p>
     *
     * @param rentVat     Σ {@code LeaseVat.vatOf} over the RENT lines — already inside {@code rent}.
     * @param rentTaxable Σ of the VAT-bearing RENT lines' net.
     */
    public static List<Row> buildRows(BigDecimal rent,
                                      BigDecimal rentVat,
                                      BigDecimal rentTaxable,
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
            boolean rentTaxed = rentVat != null && rentVat.signum() > 0;
            List<BigDecimal> vats = rentTaxed ? LeaseVat.allocateFirstAbsorbs(rentVat, amounts) : null;
            List<BigDecimal> taxables = rentTaxed ? LeaseVat.allocateFirstAbsorbs(rentTaxable, amounts) : null;
            for (int i = 0; i < n; i++) {
                long monthOffset = (long) Math.floor((double) i * months / n);
                BigDecimal amount = amounts.get(i);
                BigDecimal vat = rentTaxed ? vats.get(i) : BigDecimal.ZERO;
                BigDecimal taxable = rentTaxed ? taxables.get(i) : BigDecimal.ZERO;
                String narration = "Rent - " + ordinal(i + 1) + " Installment";
                ChequeRowKind kind = i == 0 && fold && !nonRent.isEmpty() ? ChequeRowKind.MIXED : ChequeRowKind.RENT;
                if (i == 0 && fold) {
                    for (Extra e : nonRent) {
                        amount = amount.add(e.amount());
                        vat = vat.add(nz(e.vat()));
                        taxable = taxable.add(nz(e.taxable()));
                        narration = narration + " | " + e.label();
                    }
                }
                rows.add(new Row(seq++, postingDate, firstDueDate.plusMonths(monthOffset), amount, narration,
                        vat, taxable, kind));
            }
        }

        // Unfolded extras — and every extra when the lease charges no rent at all,
        // since there is no first instalment to fold them into.
        if (!fold || !hasRent) {
            for (Extra e : nonRent) {
                rows.add(new Row(seq++, postingDate, postingDate, e.amount(), e.label(), nz(e.vat()), nz(e.taxable()),
                        e.kind() == null ? ChequeRowKind.FEE : e.kind()));
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
    public static String ordinal(int i) {
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
        return generateForSystemImport(draftLease(leaseId), request);
    }

    /**
     * The same, for the portfolio import: a lease the caller has already created in
     * this transaction, passed as an entity rather than looked up by id.
     *
     * <p><b>Who may call this.</b> The bulk importer
     * ({@code PortfolioImportPersistService}) and {@link #generate} itself. It is
     * {@code public} only because the importer lives in another package; it is not
     * an entry point for a request handler, and a controller that reaches for it
     * instead of the id-taking form is a bug.</p>
     *
     * <p><b>Why it exists.</b> The import runs on a background thread with a tenant
     * context but no {@code Authentication} at all. {@code LeaseAccessPolicy} fails
     * closed, so the id-taking form answers "Lease not found" for a lease the import
     * created three lines earlier.</p>
     *
     * <p><b>What replaces the policy check.</b> The absence of a user is the licence,
     * so it is asserted rather than assumed: when an {@code Authentication} <em>is</em>
     * present it must be one {@code requireManageable} would accept, and the lease
     * must belong to the current tenant. A renter who reached this through some
     * future caller is refused exactly as they would be on the ordinary door.</p>
     */
    @Transactional
    public List<ChequeDTO> generateForSystemImport(Lease lease, GenerateChequesRequest request) {
        requireSystemOrManager(lease);
        // The same lease row lock the id-taking door takes, and for the same reason
        // — this form writes the identical rows. Re-locking a row this transaction
        // already holds (the {@link #generate} path) is a no-op.
        lease = requireDraft(lockLease(lease.getId()));
        UUID leaseId = lease.getId();
        GenerateChequesRequest r = request == null
                ? new GenerateChequesRequest(null, null, null, null, null, null, null)
                : request;

        ChequeMode mode = mode(r.mode());
        List<Row> rows = rowsFor(lease, r, true);

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
            c.setVatAmount(row.vat());
            c.setVatTaxableAmount(row.taxable());
            c.setRowKind(row.kind());
            c.setNarration(row.narration());
            c.setMode(mode);
            c.setPayeeBank(r.payeeBank());
            c.setDebitAccount(r.debitAccountId() != null ? debitAccount : defaultFor(mode, lease, debitAccount));
            saved.add(c);
        }
        return toDtos(chequeRepository.saveAll(saved), lease);
    }


    /**
     * The grid {@link #generateForSystemImport} would write, without writing it —
     * for the portfolio import, which has to put the sheet's own instruments, the
     * generated ones and a booking cheque together into <em>one</em> grid before it
     * saves anything (gap #83).
     *
     * <p>{@code includeRent = false} proposes only the non-rent lines, each as its own
     * row dated the contract date: what an import whose Cheques sheet states the rent
     * instalments still owes for the deposit and the fees.</p>
     *
     * <p><b>Never throws.</b> The importer runs every lease of a workbook in one
     * transaction, and a {@code BusinessRuleViolationException} leaving this
     * {@code @Transactional} proxy would mark that transaction rollback-only and
     * lose the whole workbook at commit. So the refusal comes back as a sentence.</p>
     */
    @Transactional(readOnly = true)
    public Proposal proposeForSystemImport(Lease lease, GenerateChequesRequest request, boolean includeRent) {
        GenerateChequesRequest r = request == null
                ? new GenerateChequesRequest(null, null, null, null, null, null, null)
                : request;
        try {
            return new Proposal(rowsFor(lease, r, includeRent), null);
        } catch (BusinessRuleViolationException e) {
            return new Proposal(List.of(), e.getMessage());
        }
    }

    /** What {@link #proposeForSystemImport} proposes: the rows, or why there are none. */
    public record Proposal(List<Row> rows, String problem) {}

    /**
     * The rows for a lease's lines under a generate request. With
     * {@code includeRent = false} the RENT lines are left out and every other line
     * becomes its own row; a lease with nothing but rent then proposes none.
     */
    private List<Row> rowsFor(Lease lease, GenerateChequesRequest r, boolean includeRent) {
        UUID leaseId = lease.getId();
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
        // Gross, not net: the renter writes cheques for what they owe, which
        // includes the VAT the contract charges. Per line and then summed, so the
        // grid's total is the contract's total to the fils — Task 6 posts TCO with
        // the same helper, which is what makes Σ cheques = contract value + VAT
        // true by construction rather than by a reconciliation.
        BigDecimal rent = BigDecimal.ZERO;
        BigDecimal rentVat = BigDecimal.ZERO;
        BigDecimal rentTaxable = BigDecimal.ZERO;
        List<Extra> extras = new ArrayList<>();
        for (LeaseLine line : lines) {
            ChargeType type = line.getChargeType();
            BigDecimal gross = LeaseVat.grossOf(line);
            if (type != null && type.getBehaviour() == ChargeBehaviour.RENT) {
                rent = rent.add(gross);
                rentVat = rentVat.add(LeaseVat.vatOf(line));
                rentTaxable = rentTaxable.add(LeaseVat.taxableOf(line));
            } else if (gross.signum() > 0) {
                extras.add(new Extra(foldLabel(type), gross, LeaseVat.vatOf(line), LeaseVat.taxableOf(line),
                        type != null && type.getBehaviour() == ChargeBehaviour.DEPOSIT
                                ? ChequeRowKind.DEPOSIT : ChequeRowKind.FEE));
            }
        }

        if (!includeRent) {
            if (extras.stream().noneMatch(e -> e.amount().signum() > 0)) return List.of();
            return buildRows(BigDecimal.ZERO, extras, 1, lease.getContractDate(), lease.getContractDate(),
                    lease.getEndDate(), distribution, false);
        }
        int n = installments(r, lease);
        LocalDate firstDueDate = firstNonNull(r.firstDueDate(), lease.getFirstDueDate(), lease.getStartDate());
        // Spec §4b: the renter pays nothing during a rent-free window: an instalment
        // that lands inside one (the first, on a free first month) moves to the day
        // after it.
        List<LeaseRentFreePeriod> free = rentFreePeriods == null ? List.of()
                : rentFreePeriods.findByLease_IdOrderByFromDateAsc(leaseId);
        if (free.isEmpty()) {
            return buildRows(rent, rentVat, rentTaxable, extras, n, lease.getContractDate(), firstDueDate,
                    lease.getEndDate(), distribution, fold);
        }
        // PR #358 R1: one instalment per charged month, never two on one date. The
        // month anchors are the usual ones with each free-window anchor moved to the
        // day after the window (duplicates dropped); the instalments spread over them
        // as they would over plain months. A monthly lease (no explicit count) gets
        // one instalment per charged month; an explicit count above it is refused.
        List<LocalDate> anchors = chargedAnchors(firstDueDate, lease.getEndDate(), free);
        int m = anchors.size();
        if (n > m) {
            if (r.installments() != null) {
                throw new BusinessRuleViolationException("The term has " + m + " charged month(s) after its rent-free"
                        + " period(s); use at most " + m + " instalment(s).");
            }
            n = m;
        }
        List<Row> rows = buildRows(rent, rentVat, rentTaxable, extras, n, lease.getContractDate(), firstDueDate,
                lease.getEndDate(), distribution, fold);
        List<Row> out = new ArrayList<>(rows.size());
        int rentIndex = 0;
        for (Row row : rows) {
            boolean rentRow = row.kind() == ChequeRowKind.RENT || row.kind() == ChequeRowKind.MIXED;
            LocalDate due = rentRow && rentIndex < n
                    ? anchors.get((int) Math.floor((double) (rentIndex++) * m / n))
                    : outOfFree(row.chequeDate(), free);
            out.add(new Row(row.seqNo(), row.postingDate(), due, row.amount(), row.narration(), row.vat(),
                    row.taxable(), row.kind()));
        }
        return out;
    }

    /** Each month's due date from {@code first}, moved out of any rent-free window, distinct and in order. */
    static List<LocalDate> chargedAnchors(LocalDate first, LocalDate end, List<LeaseRentFreePeriod> free) {
        long months = com.datagami.rentaxis.core.util.DateMath.monthsInclusive(first, end);
        List<LocalDate> anchors = new ArrayList<>();
        for (long k = 0; k < months; k++) {
            LocalDate a = outOfFree(first.plusMonths(k), free);
            if (a.isAfter(end)) continue;
            if (!anchors.isEmpty() && !a.isAfter(anchors.get(anchors.size() - 1))) continue;
            anchors.add(a);
        }
        if (anchors.isEmpty()) anchors.add(outOfFree(first, free));
        return anchors;
    }

    /** The day itself, or the day after the rent-free window it falls in (windows sorted by start). */
    static LocalDate outOfFree(LocalDate day, List<LeaseRentFreePeriod> free) {
        if (day == null) return null;
        LocalDate d = day;
        for (LeaseRentFreePeriod p : free) {
            if (!d.isBefore(p.getFromDate()) && !d.isAfter(p.getToDate())) d = p.getToDate().plusDays(1);
        }
        return d;
    }

    private com.datagami.rentaxis.domain.repository.LeaseRentFreePeriodRepository rentFreePeriods;

    @org.springframework.beans.factory.annotation.Autowired
    public void setRentFreePeriods(com.datagami.rentaxis.domain.repository.LeaseRentFreePeriodRepository repo) {
        this.rentFreePeriods = repo;
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
        return saveRowsForSystemImport(draftLease(leaseId), rows);
    }

    /**
     * {@link #saveRows} for the portfolio import — same door, same guards, and the
     * same restriction on who may knock. See
     * {@link #generateForSystemImport(Lease, GenerateChequesRequest)}.
     */
    @Transactional
    public List<ChequeDTO> saveRowsForSystemImport(Lease lease, List<ChequeRowInput> rows) {
        requireSystemOrManager(lease);
        // See generateForSystemImport: the lock is the rows' protection, not the
        // caller's, so it belongs on every door that rewrites them.
        lease = requireDraft(lockLease(lease.getId()));
        UUID leaseId = lease.getId();
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

        // The rules themselves live in ChequeRowRules: a replacement row typed on a
        // posted lease has to clear exactly the same bar as a row typed on the grid,
        // and two copies of "a PDC needs the date written on it" would eventually
        // disagree.
        ChequeRowRules.validateGrid(input, drafts, takenByOthers);

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
        Set<Cheque> fixedVat = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        int seq = 1;
        for (ChequeRowInput row : input) {
            Cheque c = row.id() != null ? drafts.get(row.id()) : blank(lease);
            c.setSeqNo(seq++);
            c.setPostingDate(row.postingDate() != null ? row.postingDate() : lease.getContractDate());
            c.setChequeDate(row.chequeDate());
            c.setAmount(row.amount());
            c.setNarration(row.narration());
            // A row that says what it collects keeps saying it; one that does not keeps its kind.
            if (row.rowKind() != null) c.setRowKind(row.rowKind());
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
                c.setDebitAccount(defaultFor(c.getMode(), lease, fallbackDebit));
            }
            if (row.vatAmount() != null) {
                if (row.vatAmount().signum() < 0 || (row.amount() != null && row.vatAmount().compareTo(row.amount()) > 0)) {
                    throw new BusinessRuleViolationException("Row " + c.getSeqNo() + ": VAT " + row.vatAmount()
                            + " must be between zero and the row amount.");
                }
                c.setVatAmount(row.vatAmount());
                fixedVat.add(c);
            }
            out.add(c);
        }
        // Rows that named their VAT keep it; the rest share what is left of the
        // contract's VAT pro rata (spec 2026-09-24 §1). Every row of a DRAFT grid is
        // in `out`, so the Σ this aims at is the whole contract's.
        List<LeaseLine> lines = leaseLineRepository.findByLease_IdOrderBySeqNoAsc(leaseId);
        InstalmentVat.fill(out, fixedVat, lines);
        chequeRepository.saveAll(out);
        chequeRepository.flush();
        return toDtos(chequeRepository.findByLease_IdOrderBySeqNoAsc(leaseId), lease);
    }

    /**
     * Rows added to a lease that is already on the books, numbered on from the
     * last position — the instruments that pay for an extension (spec §6.7).
     *
     * <p>The rows are written {@code DRAFT} and left that way: the caller registers
     * each one through {@code LeaseChequeRegistrar}, which is what turns it into an
     * instrument with a {@code PDR} behind it. Splitting it that way is deliberate —
     * every other row on a posted lease registers the moment it is created, and an
     * extension must not register a single one until its whole set of lines, rows
     * and accounts has been found acceptable.</p>
     *
     * <p>Not a public endpoint and not reachable from the grid: {@code generate},
     * {@code saveRows} and {@code generateNumbers} all refuse a posted lease, and
     * this takes the {@code Lease} rather than an id precisely so it cannot be
     * called without the caller having already locked and vetted it.
     * {@code ChequeRowRules} still applies, through the same door a replacement
     * row uses.</p>
     *
     * @param fallbackPostingDate the journal date for rows that name none — the
     *                            extension's contract date, not today.
     */
    List<Cheque> appendRows(Lease lease, List<ChequeRowInput> rows, LocalDate fallbackPostingDate) {
        return appendRows(lease, rows, fallbackPostingDate, List.of());
    }

    /**
     * The same, with the VAT of {@code newLines} spread over the new rows (spec
     * 2026-09-24 §1: "the new rows carry VAT allocated from the new lines only") —
     * for a lease on the INSTALMENT model. A legacy CONTRACT lease declared its VAT
     * at the TCO, so its rows carry none.
     */
    List<Cheque> appendRows(Lease lease, List<ChequeRowInput> rows, LocalDate fallbackPostingDate,
                            List<LeaseLine> newLines) {
        List<Cheque> register = chequeRepository.findByLease_IdOrderBySeqNoAsc(lease.getId());
        Set<String> taken = register.stream()
                .filter(c -> c.getMode() == ChequeMode.PDC && c.getChequeNumber() != null)
                .map(Cheque::getChequeNumber)
                .collect(Collectors.toCollection(HashSet::new));
        ChequeRowRules.validateNewRows(rows, taken, "cheque");

        int seq = register.stream().mapToInt(Cheque::getSeqNo).max().orElse(0);
        Account fallbackDebit = null;
        boolean fallbackResolved = false;
        List<Cheque> out = new ArrayList<>(rows.size());
        Set<Cheque> fixedVat = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (ChequeRowInput row : rows) {
            Cheque c = blank(lease);
            c.setSeqNo(++seq);
            c.setPostingDate(row.postingDate() != null ? row.postingDate() : fallbackPostingDate);
            c.setChequeDate(row.chequeDate());
            c.setAmount(row.amount());
            c.setNarration(row.narration());
            // Said by the caller, or read off the new lines when they are all one kind.
            c.setRowKind(row.rowKind() != null ? row.rowKind() : InstalmentVat.kindOf(newLines));
            c.setMode(row.mode() == null ? ChequeMode.PDC : row.mode());
            c.setChequeNumber(blankToNull(row.chequeNumber()));
            c.setPayeeBank(row.payeeBank());
            if (row.payerName() != null && !row.payerName().isBlank()) {
                c.setPayerName(row.payerName());
            }
            if (row.debitAccountId() != null) {
                c.setDebitAccount(account(row.debitAccountId()));
            } else {
                if (!fallbackResolved) {
                    fallbackDebit = debitAccount(null, lease);
                    fallbackResolved = true;
                }
                c.setDebitAccount(defaultFor(c.getMode(), lease, fallbackDebit));
            }
            if (row.vatAmount() != null && lease.getVatTiming() == VatTiming.INSTALMENT) {
                // The grid's own rule (saveRows), on this door too (review P3-2).
                if (row.vatAmount().signum() < 0 || (row.amount() != null && row.vatAmount().compareTo(row.amount()) > 0)) {
                    throw new BusinessRuleViolationException("Row " + c.getSeqNo() + ": VAT " + row.vatAmount()
                            + " must be between zero and the row amount.");
                }
                c.setVatAmount(row.vatAmount());
                fixedVat.add(c);
            }
            out.add(c);
        }
        if (lease.getVatTiming() == VatTiming.INSTALMENT && !fixedVat.isEmpty()) {
            // Rows that name their VAT may not claim more than the new lines charge:
            // the rest would get zero and the tax points would declare more than the
            // TCO deferred (review P3-2).
            BigDecimal claimed = InstalmentVat.rowVat(List.copyOf(fixedVat));
            BigDecimal charged = newLines == null ? BigDecimal.ZERO : InstalmentVat.contractVat(newLines);
            if (claimed.compareTo(charged) > 0) {
                throw new BusinessRuleViolationException("The new rows name VAT of " + claimed.setScale(2, java.math.RoundingMode.HALF_UP)
                        + " but the new lines charge " + charged.setScale(2, java.math.RoundingMode.HALF_UP)
                        + "; the rows' VAT must add up to the lines'.");
            }
        }
        if (lease.getVatTiming() == VatTiming.INSTALMENT && newLines != null && !newLines.isEmpty()) {
            InstalmentVat.fill(out, fixedVat, newLines);
        }
        chequeRepository.saveAll(out);
        chequeRepository.flush();
        return List.copyOf(out);
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
    /**
     * R1 P2-2: a CASH row nobody gave an account to is counted into the till — the
     * property's cash-in-hand leaf (or the tenant's) when the chart has one — not
     * into the bank the other rows default to.
     */
    private Account defaultFor(ChequeMode mode, Lease lease, Account bankDefault) {
        if (mode != ChequeMode.CASH) return bankDefault;
        Unit unit = lease.getUnit();
        UUID propertyId = unit != null && unit.getProperty() != null ? unit.getProperty().getId() : null;
        return ownedBankLeaf.cashInHand(propertyId).flatMap(accountRepository::findById).orElse(bankDefault);
    }

    private Account debitAccount(UUID requested, Lease lease) {
        if (requested != null) return account(requested);
        Unit unit = lease.getUnit();
        UUID propertyId = unit != null && unit.getProperty() != null ? unit.getProperty().getId() : null;
        // F14-16: a leaf some bank account owns, so the receipt can be reconciled;
        // the property's BANK mapping as before when the tenant has no bank account.
        Optional<UUID> owned = ownedBankLeaf.forProperty(propertyId);
        if (owned.isPresent()) {
            return accountRepository.findById(owned.get()).orElseGet(
                    () -> accountResolver.resolveOrNull(AccountRole.BANK, propertyId));
        }
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

    /**
     * The lease, locked, tenant-checked, readable-checked and <em>still</em> DRAFT.
     *
     * <p><b>The lock comes first, and the status is read after it.</b> These writers
     * rewrite whole cheque rows — {@code deleteAll} on the rows a payload omits,
     * full-column updates on the ones it keeps, a renumbered grid — and they used to
     * do it against a lease they had read without a lock. Under READ_COMMITTED that
     * is the race {@code LeasePostingService.post} was given the lease lock to
     * prevent, arrived at from the other side: the grid save reads DRAFT, Post locks
     * the lease and registers every row with its own PDR, and the save then flushes
     * status=DRAFT and {@code pdr_journal_id}=null over instruments the ledger now
     * points at. Nothing errors, and Σ cheques no longer holds.</p>
     *
     * <p>Reading the status before taking the lock would be the same bug wearing a
     * lock: Post commits in between and the check answers from a row that has since
     * moved. So {@link #lockLease} is the first thing that touches the lease, and
     * {@link #requireDraft} runs on what it returns.</p>
     *
     * <p>NOWAIT, so the loser is an immediate "try again" rather than a connection
     * parked behind an accountant's open tab — the same shape as
     * {@code ChequeService.lockLease} and {@code LeasePostingService.lockLease}.</p>
     */
    private Lease draftLease(UUID leaseId) {
        Lease lease = lockLease(leaseId);
        leaseAccessPolicy.requireReadable(lease);
        return requireDraft(lease);
    }

    /** The lease row, locked and tenant-checked, with the NOWAIT conflict translated. */
    private Lease lockLease(UUID leaseId) {
        Lease lease;
        try {
            lease = leaseRepository.findByIdForUpdate(leaseId)
                    .orElseThrow(() -> new NotFoundException("Lease not found"));
        } catch (PessimisticLockingFailureException e) {
            throw new RowLockedException(
                    "This lease is being posted by another request. Please try again.");
        }
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId != null && !tenantId.equals(lease.getTenantId())) {
            throw new NotFoundException("Lease not found");
        }
        return lease;
    }

    /**
     * The guard that stands in for {@code LeaseAccessPolicy} on the entity-taking
     * doors: a background caller with no user, or a user who could have managed the
     * lease anyway.
     *
     * <p>Both halves matter. Without the authentication check, "no policy applies"
     * would mean "anyone who can reach the method", so a renter-facing path added
     * later would edit somebody's grid. Without the tenant check, an entity handed
     * in from outside the tenant filter's reach would be written to under the wrong
     * organisation — the id-taking form gets that from {@code readableLease}, and
     * this form has no lookup to get it from.</p>
     */
    private void requireSystemOrManager(Lease lease) {
        if (lease == null) {
            throw new NotFoundException("Lease not found");
        }
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId != null && !tenantId.equals(lease.getTenantId())) {
            throw new NotFoundException("Lease not found");
        }
        if (SecurityContextHolder.getContext().getAuthentication() != null
                && !leaseAccessPolicy.canManage(lease)) {
            throw new NotFoundException("Lease not found");
        }
    }

    private static Lease requireDraft(Lease lease) {
        if (lease == null) {
            throw new NotFoundException("Lease not found");
        }
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

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String blankToNull(String s) {
        return ChequeRowRules.blankToNull(s);
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... candidates) {
        for (T c : candidates) {
            if (c != null) return c;
        }
        return null;
    }
}
