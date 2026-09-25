package com.datagami.rentaxis.core.service.voucher;

import com.datagami.rentaxis.core.service.ledger.BankLockService;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.api.exception.RowLockedException;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.entity.enums.AccountSubType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.VoucherPaymentMethod;
import com.datagami.rentaxis.domain.entity.enums.VoucherStatus;
import com.datagami.rentaxis.domain.entity.enums.VoucherType;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.IssuedChequeRepository;
import com.datagami.rentaxis.domain.repository.VendorRepository;
import com.datagami.rentaxis.domain.repository.VoucherRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Draft lifecycle for Purchase/Service Invoices (PISR) and Bank/Cash Payment
 * Vouchers (BPV). Posting (Tasks 3-4) adds {@code post()}/{@code amend()} on top
 * of this class; nothing here writes a journal.
 */
@Service
@RequiredArgsConstructor
public class VoucherService {

    private final VoucherRepository vouchers;
    private final AccountRepository accounts;
    private final VendorRepository vendors;
    private final PostingService posting;
    private final TenantFiscalSettingsService fiscal;
    private final EntityManager entityManager;
    private final VoucherAllocationService allocationService;
    private final IssuedChequeRepository issuedCheques;
    /** Finance-ops spec §4: the per-bank lock, checked before a post, an amend or a reversal does any work. */
    private final com.datagami.rentaxis.core.service.ledger.BankLockService bankLock;

    /** UAE standard rate is 5%; zero-rated and exempt supplies are 0. Nothing else is legal today. */
    private static final Set<BigDecimal> ALLOWED_VAT_RATES =
            Set.of(new BigDecimal("0.00"), new BigDecimal("5.00"));

    /**
     * Controller ruling (Plan 4): a BPV line carries no VAT. One constant, checked
     * both when the draft is written and again when it is posted — a draft edited
     * by SQL or by some future code path must not slip VAT past the second gate.
     */
    static final String BPV_VAT_REFUSAL =
            "A payment voucher line cannot carry VAT — record the VAT on the purchase invoice";

    /**
     * {@code shared}: the caller ticked "Shared / head office" for a line with no
     * property. Not persisted (a null property already means shared); it only lets
     * {@link #validate} tell a deliberate head-office cost from a forgotten property.
     */
    public record VoucherLineInput(UUID accountId, String description, BigDecimal amount,
                                   BigDecimal vatRate, UUID propertyId, UUID unitId, boolean shared) {
        public VoucherLineInput(UUID accountId, String description, BigDecimal amount,
                                BigDecimal vatRate, UUID propertyId, UUID unitId) {
            this(accountId, description, amount, vatRate, propertyId, unitId, false);
        }
    }

    /**
     * Finance-ops spec §1 (S12 / O8): an income or expense line with no property
     * drops out of every property report, so it must name one — on the line, on
     * the header, or through a property-bound account — or say it is shared.
     */
    static final String LINE_PROPERTY_REFUSAL =
            "needs a property, or tick Shared / head office for a cost that belongs to no single property";

    /**
     * Finance-ops spec §2 (S8): input VAT is recoverable only on a tax invoice, and
     * a tax invoice carries the supplier's TRN. Checked at draft save and at post.
     */
    static String trnRefusal(Vendor vendor) {
        return "Input VAT needs the supplier's TRN from their tax invoice. Add the TRN to "
                + vendor.getNameEn() + " or post the invoice without VAT.";
    }

    /**
     * {@code supplierInvoiceDate}/{@code dueDate}: PISR only; null takes the posting
     * date and the supplier date plus the vendor's terms. {@code paymentMethod} /
     * {@code paymentReference}: BPV only; a null method is inferred (a cheque
     * number means CHEQUE, a cash leaf CASH, otherwise TRANSFER).
     */
    public record VoucherInput(VoucherType docType, LocalDate docDate, UUID vendorId, String invoiceNumber,
                               String narration, UUID propertyId, UUID unitId, UUID paymentAccountId,
                               String chequeNumber, LocalDate chequeDate, List<VoucherLineInput> lines,
                               LocalDate supplierInvoiceDate, LocalDate dueDate,
                               VoucherPaymentMethod paymentMethod, String paymentReference,
                               UUID settlementId) {
        public VoucherInput(VoucherType docType, LocalDate docDate, UUID vendorId, String invoiceNumber,
                            String narration, UUID propertyId, UUID unitId, UUID paymentAccountId,
                            String chequeNumber, LocalDate chequeDate, List<VoucherLineInput> lines) {
            this(docType, docDate, vendorId, invoiceNumber, narration, propertyId, unitId, paymentAccountId,
                    chequeNumber, chequeDate, lines, null, null, null, null, null);
        }

        public VoucherInput(VoucherType docType, LocalDate docDate, UUID vendorId, String invoiceNumber,
                            String narration, UUID propertyId, UUID unitId, UUID paymentAccountId,
                            String chequeNumber, LocalDate chequeDate, List<VoucherLineInput> lines,
                            LocalDate supplierInvoiceDate, LocalDate dueDate,
                            VoucherPaymentMethod paymentMethod, String paymentReference) {
            this(docType, docDate, vendorId, invoiceNumber, narration, propertyId, unitId, paymentAccountId,
                    chequeNumber, chequeDate, lines, supplierInvoiceDate, dueDate, paymentMethod, paymentReference, null);
        }
    }

    /**
     * The duplicate-invoice key (spec §2): trimmed, upper-cased, inner whitespace
     * and hyphens removed — "inv-7781 " and "INV 7781" are the same invoice.
     * Changeset 110 backfills with the same rule in SQL. Null when nothing is left.
     */
    public static String normaliseInvoiceNumber(String invoiceNumber) {
        if (invoiceNumber == null) return null;
        // Character by character, not String.toUpperCase: that one expands "ß" to
        // "SS", which Postgres upper() does not, and the backfill must agree.
        StringBuilder b = new StringBuilder(invoiceNumber.length());
        invoiceNumber.trim().codePoints()
                .filter(c -> c != '-' && !Character.isWhitespace(c))
                .map(Character::toUpperCase)
                .forEach(b::appendCodePoint);
        return b.isEmpty() ? null : b.toString();
    }

    /**
     * PR #351 review P2-1: an invoice cannot be booked before it exists. Aging
     * counts an item from its supplier date, the ledger from the posting date, and
     * allocations from the posting date, so a later supplier date would leave the
     * credit on the ledger with no item to tie it to.
     */
    static final String SUPPLIER_DATE_AFTER_POSTING =
            "The supplier's invoice date cannot be after the posting date";

    /** A PISR's due date when none is given: the supplier's date plus the vendor's terms. */
    static LocalDate defaultDueDate(LocalDate supplierInvoiceDate, Vendor vendor) {
        if (supplierInvoiceDate == null) return null;
        int terms = vendor == null || vendor.getPaymentTermsDays() == null ? 30 : vendor.getPaymentTermsDays();
        return supplierInvoiceDate.plusDays(terms);
    }

    @Transactional(readOnly = true)
    public Voucher get(UUID voucherId) {
        Voucher v = vouchers.findById(voucherId).orElseThrow(() -> new NotFoundException("Voucher not found"));
        v.getLines().size();   // initialise before the session closes
        return v;
    }

    /** No document is dated before this, or after it — sentinels standing in for "no bound" (see VoucherRepository). */
    private static final LocalDate EARLIEST_POSSIBLE_DATE = LocalDate.of(1900, 1, 1);
    private static final LocalDate LATEST_POSSIBLE_DATE = LocalDate.of(9999, 12, 31);

    @Transactional(readOnly = true)
    public Page<Voucher> list(VoucherType docType, VoucherStatus status, UUID vendorId, UUID propertyId,
                              LocalDate from, LocalDate to, Pageable pageable) {
        Page<Voucher> page = vouchers.search(docType, status, vendorId, propertyId,
                from == null ? EARLIEST_POSSIBLE_DATE : from,
                to == null ? LATEST_POSSIBLE_DATE : to,
                pageable);
        // VoucherDTO.of sums the lines for netTotal/vatTotal/grossTotal, and
        // Voucher.lines is LAZY — initialise every row's collection before the
        // session closes so the controller (Task 5) can map outside the
        // transaction, same as get() does for a single voucher.
        page.getContent().forEach(v -> v.getLines().size());
        return page;
    }

    @Transactional
    public Voucher createDraft(VoucherInput in) {
        validate(in, null);
        Voucher v = new Voucher();
        apply(v, in);
        v.setStatus(VoucherStatus.DRAFT);
        return vouchers.save(v);
    }

    @Transactional
    public Voucher updateDraft(UUID voucherId, VoucherInput in) {
        Voucher v = get(voucherId);
        requireDraft(v);
        validate(in, v.getId());
        // Clear the old lines and flush the deletes BEFORE inserting the replacements.
        // Hibernate's action queue runs every INSERT in a flush before any orphan-removal
        // DELETE from the same flush, and the replacement lines reuse line_no starting at 1
        // — so without this intermediate flush, line 1's INSERT races line 1's own DELETE
        // and trips ux_voucher_lines_voucher_line_no (changeset 87) on the same voucher_id.
        v.getLines().clear();
        vouchers.saveAndFlush(v);
        apply(v, in);
        v.setUpdatedAt(Instant.now());
        return vouchers.save(v);
    }

    @Transactional
    public void deleteDraft(UUID voucherId) {
        Voucher v = get(voucherId);
        requireDraft(v);
        vouchers.delete(v);   // lines and attachments cascade (changeset 87)
    }

    /**
     * Freeze the draft and write its journal (spec §10.1 / §10.2).
     *
     * <p>PISR: Dr every line at its net amount, Dr INPUT_VAT once with the summed
     * per-line VAT, Cr the vendor's payable leaf with the gross. BPV: Dr every
     * line, Cr the payment account with the total — a payment carries no VAT, so
     * net <em>is</em> the total there.</p>
     *
     * <p>The balance check, the leaf-only check, the period lock and the entry
     * number all live in {@code PostingService} — this method's job is to turn a
     * voucher into a {@code PostingRequest} and to refuse the documents that would
     * make a balanced-but-wrong one.</p>
     */
    @Transactional
    public Voucher post(UUID voucherId) {
        return post(voucherId, List.of());
    }

    /**
     * As {@link #post(UUID)}, and for a BPV the invoices it settles
     * ({@code allocations}), written in the same transaction by
     * {@link VoucherAllocationService}. What is left unallocated is an advance.
     */
    @Transactional
    public Voucher post(UUID voucherId, List<VoucherAllocationService.AllocationInput> allocations) {
        return post(voucherId, allocations, null);
    }

    /**
     * Finance-ops spec §2: a payment by cheque dated after the voucher is a
     * post-dated cheque. It credits {@code PDC_PAYABLE}, not the bank, and is
     * registered in {@code issued_cheques}; the bank moves when it is presented.
     */
    public static boolean isPostDatedCheque(Voucher v) {
        return v.getDocType() == VoucherType.BPV && v.getPaymentMethod() == VoucherPaymentMethod.CHEQUE
                && v.getChequeDate() != null && v.getDocDate() != null && v.getChequeDate().isAfter(v.getDocDate());
    }

    /**
     * What the user confirmed when posting (F14-20, F14-42).
     *
     * @param notOnStatement the bank payment is dated inside an imported statement's
     *        range and the user confirmed it is not on that statement.
     * @param allowNegativeCash the user saw the warning that the payment takes the
     *        cash leaf below zero and posted anyway.
     */
    public record PostOptions(boolean notOnStatement, boolean allowNegativeCash, boolean statementExempt) {
        public static final PostOptions NONE = new PostOptions(false, false, false);

        public static PostOptions of(Boolean notOnStatement, Boolean allowNegativeCash) {
            return new PostOptions(Boolean.TRUE.equals(notOnStatement), Boolean.TRUE.equals(allowNegativeCash), false);
        }

        /** An amend's replacement of a bank payment on the same leaf and date: the movement already existed. */
        PostOptions exemptFromStatement() {
            return new PostOptions(notOnStatement, allowNegativeCash, true);
        }
    }

    /** As above; {@code runId}: the payment run posting it, which its allocations are tagged with. */
    @Transactional
    public Voucher post(UUID voucherId, List<VoucherAllocationService.AllocationInput> allocations, UUID runId) {
        return post(voucherId, allocations, runId, PostOptions.NONE);
    }

    /** As above, with what the user confirmed. */
    @Transactional
    public Voucher post(UUID voucherId, List<VoucherAllocationService.AllocationInput> allocations, UUID runId,
                        PostOptions options) {
        PostOptions opts = options == null ? PostOptions.NONE : options;
        Voucher v = lockForWrite(voucherId);
        requireDraft(v);
        requirePostable(v);
        boolean allocating = allocations != null && !allocations.isEmpty();
        if (allocating && !isVendorDebit(v.getDocType())) {
            throw new BusinessRuleViolationException("Only a payment voucher or a supplier credit note settles invoices");
        }
        // Before the journal: this row, then the invoices, then the sequence (lockForWrite).
        if (allocating) allocationService.lockTargets(allocations);
        boolean postDated = isPostDatedCheque(v);
        // Finance-ops spec §4: refused early when it touches a reconciled bank leaf
        // inside the reconciled period. A post-dated cheque credits PDC_PAYABLE, not the bank.
        List<UUID> touched = new ArrayList<>();
        for (VoucherLine l : v.getLines()) touched.add(l.getAccount().getId());
        if (v.getDocType() == VoucherType.BPV && !postDated && v.getPaymentAccount() != null) touched.add(v.getPaymentAccount().getId());
        bankLock.assertOpen(touched, v.getDocDate());
        // F14-20: a bank payment dated inside an imported statement's range only
        // with the user's word that it is not on the statement.
        Optional<BankLockService.StatementCover> offStatement =
                v.getDocType() == VoucherType.BPV && !postDated && v.getPaymentAccount() != null
                        ? bankLock.requireOffStatement(List.of(v.getPaymentAccount().getId()), v.getDocDate(),
                                opts.statementExempt() ? BankLockService.StatementEvidence.EXEMPT
                                        : opts.notOnStatement() ? BankLockService.StatementEvidence.CONFIRMED_NOT_ON_STATEMENT
                                        : BankLockService.StatementEvidence.CHECK)
                        : Optional.empty();
        // F14-42: a cash payment may not take the till below zero unless the user
        // saw the warning and confirmed it.
        if (v.getDocType() == VoucherType.BPV && !postDated && !opts.allowNegativeCash()) {
            requireCashCovers(v);
        }
        // PR #352 review P3-5: every cheque — post-dated or not — is one number on
        // its bank leaf. The per-leaf advisory lock serialises two posts (or two
        // runs) reaching for the same numbers, so the check below cannot be raced.
        if (v.getDocType() == VoucherType.BPV && v.getPaymentMethod() == VoucherPaymentMethod.CHEQUE) {
            lockChequeNumbers(v.getPaymentAccount().getId());
            requireChequeNumberFree(v.getPaymentAccount(), v.getChequeNumber(), v.getId());
        }

        BigDecimal net = VoucherMath.netTotal(v.getLines());
        BigDecimal vat = VoucherMath.vatTotal(v.getLines());
        BigDecimal gross = net.add(vat);

        PostingRequest.Dimensions headerDims =
                new PostingRequest.Dimensions(v.getPropertyId(), v.getUnitId(), null, null, null);
        if (v.getSettlementId() != null) {
            // F14-36: under the settlement's row lock, so two payments cannot both
            // take the last of the refund; dimensioned by the lease and renter the
            // STL credited, so the renter-refund payable nets to nil per lease.
            com.datagami.rentaxis.domain.entity.LeaseSettlement s = entityManager.find(
                    com.datagami.rentaxis.domain.entity.LeaseSettlement.class, v.getSettlementId(),
                    LockModeType.PESSIMISTIC_WRITE);
            BigDecimal outstanding = refundOutstanding(v.getSettlementId(), v.getId());
            BigDecimal paying = VoucherMath.netTotal(v.getLines());
            if (paying.compareTo(outstanding) > 0) {
                throw new BusinessRuleViolationException("The renter is owed "
                        + outstanding.setScale(2, RoundingMode.HALF_UP).toPlainString() + " on this settlement; "
                        + paying.setScale(2, RoundingMode.HALF_UP).toPlainString() + " is more than that",
                        "voucher.refundExceedsOwed", Map.of("owed", outstanding.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                                "amount", paying.setScale(2, RoundingMode.HALF_UP).toPlainString()));
            }
            com.datagami.rentaxis.domain.entity.Lease lease =
                    entityManager.find(com.datagami.rentaxis.domain.entity.Lease.class, s.getLeaseId());
            headerDims = new PostingRequest.Dimensions(v.getPropertyId(), v.getUnitId(), s.getLeaseId(),
                    lease == null || lease.getRenter() == null ? null : lease.getRenter().getId(), null);
        }

        List<PostingRequest.Line> journalLines = new ArrayList<>();
        // F14-40: a supplier credit note takes back what an invoice charged, so its
        // lines are credits (the expense and, below, the input VAT) and the vendor is debited.
        boolean creditNote = v.getDocType() == VoucherType.PCN;
        for (VoucherLine l : v.getLines()) {
            PostingRequest.Line jl = (creditNote ? PostingRequest.cr(l.getAccount().getId(), l.getAmount())
                    : PostingRequest.dr(l.getAccount().getId(), l.getAmount()))
                    .withDims(new PostingRequest.Dimensions(l.getPropertyId(), l.getUnitId(), null, null, null))
                    .withNarration(l.getDescription());
            // apply() already copied the header's property onto every line that did
            // not name its own, so a line still without one was marked Shared / head
            // office: it must not pick the header's property up again at posting.
            journalLines.add(l.getPropertyId() == null ? jl.withOwnProperty() : jl);
        }

        switch (v.getDocType()) {
            case PISR -> {
                if (vat.signum() > 0) {
                    // One line for the whole invoice: the FTA return is filed per period,
                    // not per expense account.
                    //
                    // Resolved against the header's property, like every other line here.
                    // AccountRole.INPUT_VAT reports isPropertyScoped() == false, but that
                    // flag is advisory — it steers the mapping UI, and AccountResolver
                    // consults a property mapping for every role before the tenant default
                    // (review M-3). So a tenant that has deliberately mapped INPUT_VAT on a
                    // property gets that leaf, and everyone else gets the tenant default.
                    journalLines.add(PostingRequest.dr(AccountRole.INPUT_VAT, vat)
                            .withDims(headerDims)
                            .withNarration("Input VAT"));
                }
                journalLines.add(PostingRequest.cr(v.getVendor().getPayableAccount().getId(), gross)
                        .withDims(headerDims)
                        .withNarration(v.getInvoiceNumber() == null
                                ? v.getVendor().getNameEn()
                                : v.getVendor().getNameEn() + " — " + v.getInvoiceNumber()));
            }
            // A post-dated cheque is held in PDC_PAYABLE until it is presented
            // (IssuedChequeService posts the BPC then); every other payment
            // credits the payment account on the voucher's date, as before.
            case BPV -> journalLines.add(postDated
                    ? PostingRequest.cr(AccountRole.PDC_PAYABLE, net)
                        .withDims(headerDims)
                        .withNarration("PDC " + v.getChequeNumber() + " dated "
                                + v.getChequeDate().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                                + " on " + v.getPaymentAccount().getName())
                    : PostingRequest.cr(v.getPaymentAccount().getId(), net)
                        .withDims(headerDims)
                        .withNarration(v.getChequeNumber() == null
                                ? v.getPaymentAccount().getName()
                                : "Cheque " + v.getChequeNumber()));
            // Unreachable: requirePostable refuses RCP above, and validate() refuses it
            // at draft time. The arm is here because the switch is exhaustive over
            // VoucherType, and an RCP that ever did arrive should say where it belongs.
            case RCP -> throw new BusinessRuleViolationException(
                    "Cash Receipt Vouchers are posted from the lease receipt screen");
            case PCN -> {
                if (vat.signum() > 0) {
                    journalLines.add(PostingRequest.cr(AccountRole.INPUT_VAT, vat)
                            .withDims(headerDims)
                            .withNarration("Input VAT reversed"));
                }
                journalLines.add(PostingRequest.dr(v.getVendor().getPayableAccount().getId(), gross)
                        .withDims(headerDims)
                        .withNarration(v.getInvoiceNumber() == null
                                ? v.getVendor().getNameEn() + " — credit note"
                                : v.getVendor().getNameEn() + " — credit note " + v.getInvoiceNumber()));
            }
        }

        String baseNarration = v.getNarration();
        LocalDate docDate = v.getDocDate();
        String entryNarration = offStatement
                .map(c -> (baseNarration == null ? "" : baseNarration) + BankLockService.offStatementNote(c))
                .orElse(baseNarration);
        JournalEntry entry = posting.post(new PostingRequest(
                v.getDocType().toDocType(), v.getDocDate(), entryNarration, headerDims,
                JournalSourceType.VOUCHER, v.getId(), null, journalLines).withInterPropertyClearing());   // F15-11
        offStatement.ifPresent(c -> bankLock.recordOffStatement(c, entry.getId(), docDate));

        v.setStatus(VoucherStatus.POSTED);
        v.setJournalId(entry.getId());
        v.setVoucherNumber(entry.getEntryNumber());
        v.setPostedAt(entry.getPostedAt() == null ? Instant.now() : entry.getPostedAt());
        v.setPostedBy(entry.getPostedBy());
        v.setUpdatedAt(Instant.now());
        try {
            // Flushed here so the duplicate-invoice index answers inside this call
            // (a race past requireNoPostedDuplicate), not at commit as a bare 500.
            v = vouchers.saveAndFlush(v);
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("ux_vouchers_pisr_invoice")) {
                throw new BusinessRuleViolationException("Invoice " + v.getInvoiceNumber() + " from "
                        + v.getVendor().getNameEn() + " is already posted");
            }
            throw e;
        }
        if (postDated) registerIssuedCheque(v, net);
        if (allocating) allocationService.allocateOnPost(v.getId(), allocations, null, runId);
        return v;
    }

    /** The {@code issued_cheques} row of a post-dated BPV, ISSUED. The partial unique index is the backstop. */
    private void registerIssuedCheque(Voucher v, BigDecimal amount) {
        IssuedCheque c = new IssuedCheque();
        c.setVoucherId(v.getId());
        c.setVendorId(v.getVendor() == null ? null : v.getVendor().getId());
        // F14-36: a post-dated refund cheque is written to the renter, not a vendor.
        if (c.getVendorId() == null && v.getSettlementId() == null) throw new BusinessRuleViolationException(PDC_NEEDS_VENDOR);
        c.setBankAccountId(v.getPaymentAccount().getId());
        c.setChequeNumber(v.getChequeNumber().trim());
        c.setChequeDate(v.getChequeDate());
        c.setAmount(amount);
        c.setCreatedBy(v.getPostedBy());
        try {
            issuedCheques.saveAndFlush(c);
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("ux_issued_cheques_number")) {
                throw new BusinessRuleViolationException(chequeTaken(v.getPaymentAccount(), c.getChequeNumber()));
            }
            throw e;
        }
    }

    static final String PDC_NEEDS_VENDOR = "A cheque dated after the voucher is a post-dated cheque, held in PDC payable"
            + " against the vendor it is written to. Name the vendor, or date the cheque on the voucher date.";

    static String chequeTaken(Account bank, String chequeNumber) {
        return "Cheque " + chequeNumber + " on " + bank.getName() + " is already issued";
    }

    /**
     * A cheque number is used once per bank leaf: among the issued cheques that
     * are not cancelled, and among the posted payments that name it.
     */
    private void requireChequeNumberFree(Account bank, String chequeNumber, UUID selfId) {
        if (chequeNumber == null || chequeNumber.isBlank()) return;
        if (chequeNumberTaken(bank.getId(), chequeNumber, selfId)) {
            throw new BusinessRuleViolationException(chequeTaken(bank, chequeNumber.trim()));
        }
    }

    /**
     * The cheque leaves an amend touches: the original's (when it was a cheque
     * payment) and the replacement's (when it is one), locked in UUID order.
     */
    private void lockChequeLeavesForAmend(Voucher original, VoucherInput replacement) {
        java.util.TreeSet<UUID> leaves = new java.util.TreeSet<>();
        if (original.getDocType() == VoucherType.BPV && original.getPaymentMethod() == VoucherPaymentMethod.CHEQUE
                && original.getPaymentAccount() != null) {
            leaves.add(original.getPaymentAccount().getId());
        }
        if (replacement != null && replacement.docType() == VoucherType.BPV && replacement.paymentAccountId() != null) {
            VoucherPaymentMethod m = replacement.paymentMethod() != null ? replacement.paymentMethod()
                    : (isBlank(replacement.chequeNumber()) ? null : VoucherPaymentMethod.CHEQUE);
            if (m == VoucherPaymentMethod.CHEQUE) leaves.add(replacement.paymentAccountId());
        }
        for (UUID leaf : leaves) lockChequeNumbers(leaf);
    }

    /**
     * Transaction-scoped advisory lock on a bank leaf's cheque numbers.
     *
     * <p><b>Order:</b> after the voucher and invoice rows (the order
     * {@link #lockForWrite} documents) and before the entry-number sequence
     * row. A run takes it inside each vendor's post, after it has locked every row
     * it touches. {@code amend} takes it for the original's and the replacement's
     * leaves right after the counterpart rows and before {@code posting.reverse},
     * which is what takes the sequence (PR #352 re-review R1); the later call
     * inside {@code post(fresh)} is re-entrant. The cut-over register takes it
     * before it inserts, and takes no row or sequence lock after it.</p>
     */
    public void lockChequeNumbers(UUID bankAccountId) {
        UUID t = TenantContextHolder.getTenantId();
        entityManager.createNativeQuery("select pg_advisory_xact_lock(hashtextextended(:k, 0))")
                .setParameter("k", "cheque-numbers:" + t + ":" + bankAccountId)
                .getSingleResult();
    }

    /** True when this cheque number is already out on this bank leaf (see {@link #requireChequeNumberFree}). */
    @Transactional(readOnly = true)
    public boolean chequeNumberTaken(UUID bankAccountId, String chequeNumber, UUID excludeVoucherId) {
        String no = chequeNumber.trim();
        if (issuedCheques.countOutstandingNumber(bankAccountId, no, excludeVoucherId) > 0) return true;
        Long n = (Long) entityManager.createQuery("""
                select count(v) from Voucher v where v.docType = :bpv and v.status = :posted
                  and v.paymentAccount.id = :bank and trim(v.chequeNumber) = :no
                  and (:self is null or v.id <> :self)""")
                .setParameter("bpv", VoucherType.BPV).setParameter("posted", VoucherStatus.POSTED)
                .setParameter("bank", bankAccountId).setParameter("no", no)
                .setParameter("self", excludeVoucherId).getSingleResult();
        return n > 0;
    }

    /**
     * A payment that is being reversed takes its post-dated cheque with it: an
     * ISSUED cheque is CANCELLED on the reversal date; a PRESENTED one refuses —
     * the bank has paid it, so the BPC has to be undone (unpresent) first.
     */
    private void cancelIssuedChequeOf(Voucher original, LocalDate on, String reason) {
        if (original.getDocType() != VoucherType.BPV) return;
        for (IssuedCheque c : issuedCheques.findByVoucherId(original.getId())) {
            // Locked and re-read: a racing Present must not be overwritten by a stale ISSUED.
            entityManager.refresh(c, LockModeType.PESSIMISTIC_WRITE);
            if (c.getStatus() == IssuedCheque.Status.PRESENTED) {
                throw new BusinessRuleViolationException("Cheque " + c.getChequeNumber() + " was presented on "
                        + c.getPresentedOn() + "; unpresent it before reversing " + original.getVoucherNumber());
            }
            if (c.getStatus() == IssuedCheque.Status.ISSUED) {
                c.setStatus(IssuedCheque.Status.CANCELLED);
                c.setCancelledOn(on);
                c.setCancelReason(reason == null || reason.isBlank() ? "Payment " + original.getVoucherNumber() + " reversed" : reason.trim());
                c.setUpdatedAt(Instant.now());
                issuedCheques.saveAndFlush(c);
            }
        }
    }

    /**
     * F14-41: a posted voucher is reversed (amend, void, payment reversal) on or
     * after its own date, and only with a reason. A reversal dated earlier would
     * cancel a document that did not exist yet, and could move its VAT into an
     * earlier return.
     */
    /** F14-40: documents that debit the vendor's payable and so settle its invoices: payments and credit notes. */
    static boolean isVendorDebit(VoucherType type) {
        return type == VoucherType.BPV || type == VoucherType.PCN;
    }

    static void requireReversible(Voucher original, LocalDate date, String reason) {
        if (date == null) throw new BusinessRuleViolationException("A reversal date is required");
        if (original.getDocDate() != null && date.isBefore(original.getDocDate())) {
            String doc = original.getVoucherNumber() == null ? "the voucher" : original.getVoucherNumber();
            throw new BusinessRuleViolationException("A reversal cannot be dated before " + doc + " ("
                    + original.getDocDate().format(DMY) + ")", "voucher.reverseBeforeDocument",
                    Map.of("voucher", doc, "docDate", original.getDocDate().format(DMY), "date", date.format(DMY)));
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleViolationException("Give the reason for the reversal", "voucher.reasonRequired", Map.of());
        }
    }

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * F14-42: a payment out of a cash leaf may not take it below zero on the
     * payment's date. The balance counts every entry dated on or before it.
     */
    private void requireCashCovers(Voucher v) {
        Account pay = v.getPaymentAccount();
        if (pay == null || pay.getAccountSubType() != AccountSubType.CASH) return;
        BigDecimal balance = (BigDecimal) entityManager.createQuery(
                "select coalesce(sum(l.debit - l.credit), 0) from JournalLine l"
                        + " where l.account.id = :a and l.entry.entryDate <= :d")
                .setParameter("a", pay.getId()).setParameter("d", v.getDocDate()).getSingleResult();
        BigDecimal net = VoucherMath.netTotal(v.getLines());
        BigDecimal after = balance.subtract(net);
        if (after.signum() < 0) {
            String bal = balance.setScale(2, RoundingMode.HALF_UP).toPlainString();
            String amt = net.setScale(2, RoundingMode.HALF_UP).toPlainString();
            String left = after.setScale(2, RoundingMode.HALF_UP).toPlainString();
            throw new BusinessRuleViolationException(pay.getName() + " holds " + bal + " on "
                    + v.getDocDate().format(DMY) + "; paying " + amt + " from it would leave it at " + left
                    + ". Confirm the payment to post it anyway.", "voucher.cashNegative",
                    Map.of("account", pay.getName(), "balance", bal, "amount", amt,
                            "date", v.getDocDate().format(DMY), "after", left));
        }
    }

    /**
     * PR #352 review P3-1: an allocation released from this payment on R keeps
     * counting until R. Reversing the payment before R would leave it settling an
     * invoice after the payment is gone, so the reversal is dated on or after the
     * latest release.
     */
    private void requireNotBeforeARelease(Voucher payment, LocalDate reversalDate) {
        LocalDate released = allocationService.latestReleaseOfPayment(payment.getId());
        if (released != null && reversalDate.isBefore(released)) {
            throw new BusinessRuleViolationException("An allocation of " + payment.getVoucherNumber() + " was released on "
                    + released + "; the payment cannot be reversed before that date");
        }
    }

    /**
     * Reverse a posted payment with no replacement — an issued cheque cancelled
     * or stopped (spec §2). Its live allocations are released on {@code date}
     * and its invoices re-open; an ISSUED post-dated cheque is cancelled.
     */
    @Transactional
    public Voucher reversePayment(UUID voucherId, LocalDate date, String reason) {
        Voucher original = lockForWrite(voucherId);
        if (original.getDocType() != VoucherType.BPV) {
            throw new BusinessRuleViolationException("Only a payment voucher is reversed this way");
        }
        if (original.getStatus() != VoucherStatus.POSTED) {
            throw new BusinessRuleViolationException(
                    "Only a POSTED voucher can be reversed; this one is " + original.getStatus());
        }
        requireReversible(original, date, reason);
        fiscal.assertOpen(date);
        bankLock.assertOpenForEntry(original.getJournalId(), date);
        allocationService.lockCounterparts(original.getId(), null);
        requireNotBeforeARelease(original, date);
        cancelIssuedChequeOf(original, date, reason.trim());
        posting.reverse(original.getJournalId(), date, reason.trim());
        original.setStatus(VoucherStatus.REVERSED);
        original.setUpdatedAt(Instant.now());
        vouchers.saveAndFlush(original);
        allocationService.releaseAllOfPayment(original.getId(), date, "Payment " + original.getVoucherNumber() + " reversed");
        return original;
    }

    /**
     * F14-42: void a posted voucher that should never have been posted — a
     * mistaken payment or invoice. Its journal is reversed on {@code date} (on or
     * after its own date, inside an open period and bank lock) with the reason, and
     * it is marked VOID. A payment's allocations are released and an ISSUED
     * post-dated cheque is cancelled, as for {@link #reversePayment}. An invoice a
     * payment still settles is refused: release or amend that payment first.
     */
    @Transactional
    public Voucher voidVoucher(UUID voucherId, LocalDate date, String reason) {
        Voucher original = lockForWrite(voucherId);
        if (original.getStatus() != VoucherStatus.POSTED) {
            throw new BusinessRuleViolationException(
                    "Only a POSTED voucher can be voided; this one is " + original.getStatus());
        }
        if (date == null) throw new BusinessRuleViolationException("A void date is required");
        requireReversible(original, date, reason);
        fiscal.assertOpen(date);
        bankLock.assertOpenForEntry(original.getJournalId(), date);
        allocationService.lockCounterparts(original.getId(), null);
        if (original.getDocType() == VoucherType.PISR
                && allocationService.liveOnInvoice(original.getId(), null).signum() > 0) {
            throw new BusinessRuleViolationException(original.getVoucherNumber()
                    + " is settled by a payment; release that allocation or amend the payment before voiding it",
                    "voucher.voidSettledInvoice", Map.of("voucher", String.valueOf(original.getVoucherNumber())));
        }
        if (isVendorDebit(original.getDocType())) {
            requireNotBeforeARelease(original, date);
            cancelIssuedChequeOf(original, date, reason.trim());
        }
        posting.reverse(original.getJournalId(), date, "Void: " + reason.trim());
        original.setStatus(VoucherStatus.VOID);
        original.setUpdatedAt(Instant.now());
        vouchers.saveAndFlush(original);
        if (isVendorDebit(original.getDocType())) {
            allocationService.releaseAllOfPayment(original.getId(), date, original.getVoucherNumber() + " voided");
        }
        return original;
    }

    /**
     * Correct a posted voucher: reverse its journal, mark it REVERSED, and post a
     * fresh voucher carrying the corrected figures with {@code amendedFromId} set
     * back to it (spec §10.1). Never edits the posted row — journal entries are
     * immutable, so an "edit" that left the original journal in place would put the
     * document and the ledger permanently out of step.
     *
     * <p>One transaction, deliberately: a reversal that survived a failed
     * replacement would cancel the invoice and raise nothing in its place, and the
     * vendor's balance would silently fall to zero.</p>
     *
     * @return the NEW voucher, already POSTED.
     */
    @Transactional
    public Voucher amend(UUID voucherId, LocalDate reversalDate, String reason, VoucherInput replacement) {
        return amend(voucherId, reversalDate, reason, replacement, null);
    }

    /**
     * As above; {@code allocations} are the invoices a replacement BPV settles.
     *
     * <p>Allocation hooks (spec §2): a PISR's live allocations carry to the
     * replacement, capped at its gross, the excess becoming an advance on the
     * payment. A BPV's are released on the reversal date; the replacement then
     * settles {@code allocations} when given (an empty list: nothing, an advance),
     * or — {@code null} — carries the original's, trimmed to what it now pays.</p>
     *
     * <p>Flush order: the original is REVERSED and flushed before the replacement
     * is inserted or posted, so {@code ux_vouchers_pisr_invoice} never sees two
     * POSTED rows for an invoice amended under its own number
     * ({@code VoucherAmendIT}).</p>
     */
    @Transactional
    public Voucher amend(UUID voucherId, LocalDate reversalDate, String reason, VoucherInput replacement,
                         List<VoucherAllocationService.AllocationInput> allocations) {
        return amend(voucherId, reversalDate, reason, replacement, allocations, PostOptions.NONE);
    }

    /** As above, with what the user confirmed for the replacement (F14-20, F14-42). */
    @Transactional
    public Voucher amend(UUID voucherId, LocalDate reversalDate, String reason, VoucherInput replacement,
                         List<VoucherAllocationService.AllocationInput> allocations, PostOptions options) {
        PostOptions opts = options == null ? PostOptions.NONE : options;
        Voucher original = lockForWrite(voucherId);
        if (original.getStatus() != VoucherStatus.POSTED) {
            throw new BusinessRuleViolationException(
                    "Only a POSTED voucher can be amended; this one is " + original.getStatus());
        }
        if (reversalDate == null) throw new BusinessRuleViolationException("A reversal date is required");
        // Asked here as well as inside PostingService.reverse so that a reversal
        // dated into a closed period is refused before any of this is written.
        fiscal.assertOpen(reversalDate);
        // F14-41: dated on or after the original, and a reason.
        requireReversible(original, reversalDate, reason);
        bankLock.assertOpenForEntry(original.getJournalId(), reversalDate);
        // A grandfathered duplicate (changeset 110) shares its number with a POSTED
        // invoice the guard protects, so it can only be corrected to a new number —
        // while that invoice still stands (PR #351 re-review N3). Once it has been
        // reversed or renumbered, nothing holds the number and the index allows it.
        if (original.getDocType() == VoucherType.PISR && original.isDuplicateGrandfathered()
                && original.getInvoiceNoNorm() != null
                && original.getInvoiceNoNorm().equals(normaliseInvoiceNumber(replacement.invoiceNumber()))) {
            vouchers.findPostedDuplicate(original.getVendor().getId(), original.getInvoiceNoNorm()).stream()
                    .filter(d -> !d.getId().equals(original.getId()))
                    .map(Voucher::getVoucherNumber).findFirst()
                    .ifPresent(first -> {
                        throw new BusinessRuleViolationException(original.getVoucherNumber()
                                + " is a grandfathered duplicate of " + first + " (both carry "
                                + original.getInvoiceNumber() + "); amend it to a corrected invoice number");
                    });
        }

        // Every row the allocation hooks will touch, locked before the journal is
        // (voucher rows, then opening items, then the sequence): the original's
        // counterparts and whatever the replacement will settle.
        allocationService.lockCounterparts(original.getId(), allocations);
        // PR #352 re-review R1: the cheque-number lock comes before the sequence.
        // posting.reverse takes the BPV sequence row, and post(fresh) would only
        // then take the advisory lock, the reverse of a run's order (rows, advisory,
        // sequence), so two accountants could deadlock. The leaves are locked here,
        // in a stable order; the call inside post(fresh) is then re-entrant.
        lockChequeLeavesForAmend(original, replacement);
        if (isVendorDebit(original.getDocType())) requireNotBeforeARelease(original, reversalDate);
        // Before the replacement is written, so it may re-use the cheque number.
        cancelIssuedChequeOf(original, reversalDate, reason.trim());

        posting.reverse(original.getJournalId(), reversalDate, reason.trim());
        original.setStatus(VoucherStatus.REVERSED);
        original.setUpdatedAt(Instant.now());
        vouchers.saveAndFlush(original);
        List<VoucherAllocation> released = isVendorDebit(original.getDocType())
                ? allocationService.releaseAllOfPayment(original.getId(), reversalDate,
                        "Payment " + original.getVoucherNumber() + " amended")
                : List.of();

        Voucher fresh = createDraft(replacement);
        fresh.setAmendedFromId(original.getId());
        // PR #352 review P3-11: an amended run payment stays in its run (and bank file).
        if (original.getDocType() == VoucherType.BPV && fresh.getDocType() == VoucherType.BPV) {
            fresh.setPaymentRunId(original.getPaymentRunId());
        }
        // Flushed before post(), which loads the row by key and refreshes it under a
        // lock: refresh overwrites the instance from the database, so an insert still
        // sitting in the action queue would either be missed or would lose
        // amendedFromId. (Same shape as the flush updateDraft needs, for the same
        // reason: this class hands rows to Hibernate and then re-reads them.)
        vouchers.saveAndFlush(fresh);
        // F14-20: the replacement of a bank payment on the same leaf and date is the
        // movement the statement already shows (or does not); it is not asked again.
        boolean sameMovement = original.getDocType() == VoucherType.BPV && fresh.getDocType() == VoucherType.BPV
                && original.getPaymentAccount() != null && fresh.getPaymentAccount() != null
                && original.getPaymentAccount().getId().equals(fresh.getPaymentAccount().getId())
                && java.util.Objects.equals(original.getDocDate(), fresh.getDocDate());
        Voucher posted = post(fresh.getId(), List.of(), null, sameMovement ? opts.exemptFromStatement() : opts);
        if (sameMovement) {
            // R1 P3-7: the replacement is the same bank movement, so a confirmed
            // "not on the statement" stays with it.
            entityManager.flush();
            entityManager.createNativeQuery("""
                    insert into bank_off_statement_items (id, tenant_id, bank_account_id, journal_entry_id, entry_date,
                                                          statement_from, statement_to, confirmed_by, confirmed_at)
                    select gen_random_uuid(), o.tenant_id, o.bank_account_id, :fresh, o.entry_date,
                           o.statement_from, o.statement_to, o.confirmed_by, o.confirmed_at
                    from bank_off_statement_items o where o.journal_entry_id = :orig
                    on conflict (bank_account_id, journal_entry_id) do nothing""")
                    .setParameter("fresh", posted.getJournalId()).setParameter("orig", original.getJournalId())
                    .executeUpdate();
        }
        if (original.getDocType() == VoucherType.PISR) {
            allocationService.carryToReplacement(original.getId(), posted.getId(), reversalDate);
        } else if (isVendorDebit(posted.getDocType())) {
            // A replacement payment settles what it is told to, or — told nothing —
            // what the original settled, trimmed to what it now pays (spec §2 hooks,
            // PR #351 review P3-3). Dated no earlier than the reversal, so the
            // original keeps settling those invoices until it is reversed (P3-2).
            if (allocations == null) {
                allocationService.carryPaymentToReplacement(released, posted.getId(), reversalDate);
            } else {
                allocationService.allocateOnPost(posted.getId(), allocations, reversalDate);
            }
        }
        return posted;
    }

    // ---- internals shared with post() in Tasks 3 and 4 ----

    /**
     * The voucher row, locked for the length of this transaction.
     *
     * <p>Two clerks posting the same draft — or one clerk double-clicking — must
     * leave one journal, not two: the status check is read-then-act, so it is only
     * a guard while the row it read cannot move underneath it.</p>
     *
     * <p>{@code find} then {@code refresh(…, PESSIMISTIC_WRITE)} rather than a
     * {@code @Lock} finder, for the reason documented at
     * {@code ChequeService#lockLease}: a locking JPQL query hands back the
     * first-level-cache instance with its <em>stale</em> state, so the loser of the
     * race would take the lock and then decide on the pre-lock status — exactly the
     * bug the lock exists to prevent. {@code refresh} is defined as "overwrite this
     * instance from the database", so it both takes the lock and re-reads.</p>
     *
     * <p><b>The explicit tenant comparison below is the only guard on this path —
     * do not delete it as redundant.</b> {@code BaseTenantEntity} does set
     * {@code applyToLoadByKey = true}, but that only matters once the filter is
     * <em>enabled</em>, and {@code TenantAspect} enables it {@code @Before}
     * execution of {@code domain.repository..*} — nothing else. {@code post} and
     * {@code amend} call this first, so no repository method has run in the
     * transaction yet and the filter is off for the {@code find} and the
     * {@code refresh} on the next two lines. {@code tenantBCannotPostTenantAsVoucher}
     * and {@code tenantBCannotAmendTenantAsVoucher} are what hold the line.</p>
     *
     * <p><b>Lock ordering.</b> Every ledger write path takes its locks in the order
     * <em>business row → journal entry row → sequence row</em>: {@code post} takes
     * this row and then, inside {@code PostingService.post}, the entry-number
     * sequence; {@code amend} takes this row, then the entry row
     * ({@code JournalEntryRepository.lockById}), then the sequence. The one
     * inversion is {@code amend}'s trailing {@code post(fresh)}, which takes a
     * voucher row while already holding the sequence — but {@code fresh} was
     * inserted by that same transaction, so no rival can hold it or block on it.
     * There is therefore no transaction holding the sequence and waiting on a
     * voucher row held by a sequence-waiter.</p>
     *
     * <p><b>It can still deadlock with an allocation.</b> Supplier AP (spec §2)
     * locks voucher rows in id order ({@code VoucherAllocationService}), while
     * {@code post} and {@code amend} take this row first and the invoices it
     * settles after it. An allocation whose invoice id sorts before this payment's
     * id, running against an amend of the same payment, waits in the opposite
     * order: Postgres breaks the cycle (40P01), one side rolls back, and
     * {@code GlobalExceptionHandler.handleConcurrency} answers it 409 "try again".
     * Nothing is half-written. {@code VoucherAllocationDeadlockIT} pins that.</p>
     */
    private Voucher lockForWrite(UUID voucherId) {
        // Scale P1-12: the row lock is a plain SELECT ... FOR UPDATE on the vouchers row,
        // taken before the entity is read. It used to be refresh(v, PESSIMISTIC_WRITE),
        // whose Hibernate follow-on locking (the voucher's lines are a joined
        // collection) threw an NPE in TableLock.applyLoadedState under concurrent posts:
        // 77 of 2,000 posts at 8 threads answered 500. The tenant is bound in the lock
        // query itself, so another organisation's id locks nothing and is "not found".
        UUID tenantId = TenantContextHolder.getTenantId();
        List<?> locked;
        try {
            jakarta.persistence.Query lock = entityManager.createNativeQuery(
                            "select id from vouchers where id = :id"
                                    + (tenantId != null ? " and tenant_id = :tenantId" : "")
                                    + " for update")
                    .setParameter("id", voucherId);
            if (tenantId != null) lock.setParameter("tenantId", tenantId);
            locked = lock.getResultList();
        } catch (PessimisticLockingFailureException | PessimisticLockException | LockTimeoutException e) {
            // Three types for one event: an EntityManager call is not put through
            // Spring Data's exception translation, so JPA's own types get out.
            throw new RowLockedException("This voucher is being posted right now; try again");
        }
        if (locked.isEmpty()) throw new NotFoundException("Voucher not found");
        Voucher v = entityManager.find(Voucher.class, voucherId);
        if (v == null) throw new NotFoundException("Voucher not found");
        // Already in this session from an earlier read: re-read it now that the row is
        // ours, so the checks below see what the last committed writer left.
        entityManager.refresh(v);
        if (tenantId != null && !tenantId.equals(v.getTenantId())) {
            throw new NotFoundException("Voucher not found");
        }
        v.getLines().size();   // initialise before the session closes
        return v;
    }

    /**
     * What has to be true of the stored document — as opposed to the input that
     * created it — before it may become a journal. {@code validate()} has already
     * seen this content once, at draft time; this pass is deliberately not a
     * shortcut past that, because a row can be edited by SQL, restored from a
     * backup, or written by a future code path that skips the draft service.
     */
    private void requirePostable(Voucher v) {
        if (v.getDocDate() == null) throw new BusinessRuleViolationException("Document date is required");
        if (v.getLines().isEmpty()) throw new BusinessRuleViolationException("A voucher needs at least one line");

        for (VoucherLine l : v.getLines()) {
            Account a = l.getAccount();
            requirePostableLeaf(a, "Line account");
            if (l.getAmount() == null || l.getAmount().signum() <= 0) {
                throw new BusinessRuleViolationException("Every line needs an amount greater than zero");
            }
            if ((v.getDocType() == VoucherType.PISR || v.getDocType() == VoucherType.PCN)
                    && a.getAccountType() != AccountType.EXPENSE && a.getAccountType() != AccountType.ASSET) {
                throw new BusinessRuleViolationException("Line account " + a.getCode() + " " + a.getName()
                        + " is " + a.getAccountType() + "; a purchase invoice line must be an expense or asset account");
            }
            if (v.getDocType() == VoucherType.BPV
                    && (signum(l.getVatAmount()) != 0 || signum(l.getVatRate()) != 0)) {
                // The rate as well as the amount (review M-2): a row carrying
                // vat_rate = 5 with vat_amount = 0 posts a journal with no VAT while
                // the document on screen says it charged some.
                throw new BusinessRuleViolationException(BPV_VAT_REFUSAL);
            }
            // The stored VAT is the figure that becomes the INPUT_VAT line and the
            // vendor's gross, so it is recomputed rather than trusted: this pass
            // exists for rows that reached the table some other way, and a row whose
            // vat_amount was edited would otherwise post a balanced-but-wrong entry
            // (review M-2). apply() rounds per line HALF_UP, so the comparison is exact.
            BigDecimal expectedVat = VoucherMath.vat(l.getAmount(), l.getVatRate());
            if (expectedVat.compareTo(l.getVatAmount() == null ? VoucherMath.ZERO : l.getVatAmount()) != 0) {
                throw new BusinessRuleViolationException("Line " + l.getLineNo() + " stores VAT "
                        + l.getVatAmount() + ", but " + l.getVatRate() + "% of " + l.getAmount()
                        + " is " + expectedVat + "; re-save the voucher");
            }
        }

        switch (v.getDocType()) {
            case PISR -> {
                Vendor vendor = v.getVendor();
                if (vendor == null) throw new BusinessRuleViolationException("A purchase invoice needs a vendor");
                if (!vendor.isActive()) {
                    throw new BusinessRuleViolationException("Vendor " + vendor.getNameEn() + " is inactive");
                }
                if (vendor.getPayableAccount() == null) {
                    throw new BusinessRuleViolationException("Vendor " + vendor.getNameEn()
                            + " has no payable account. Re-save the vendor to create one.");
                }
                requirePostableLeaf(vendor.getPayableAccount(), "Vendor payable account");
                if (v.getInvoiceNoNorm() == null) {
                    throw new BusinessRuleViolationException("A purchase invoice needs the supplier's invoice number");
                }
                if (VoucherMath.vatTotal(v.getLines()).signum() > 0 && isBlank(vendor.getTrn())) {
                    throw new BusinessRuleViolationException(trnRefusal(vendor));
                }
                if (v.getSupplierInvoiceDate() != null && v.getSupplierInvoiceDate().isAfter(v.getDocDate())) {
                    throw new BusinessRuleViolationException(SUPPLIER_DATE_AFTER_POSTING);
                }
                if (v.getDueDate() != null && v.getSupplierInvoiceDate() != null
                        && v.getDueDate().isBefore(v.getSupplierInvoiceDate())) {
                    throw new BusinessRuleViolationException("The due date cannot be before the supplier's invoice date");
                }
                requireNoPostedDuplicate(vendor, v.getInvoiceNumber(), v.getInvoiceNoNorm(), v.getId());
            }
            // F14-40: a supplier credit note — the vendor must be live and have its payable leaf.
            case PCN -> {
                Vendor vendor = v.getVendor();
                if (vendor == null) throw new BusinessRuleViolationException("A supplier credit note needs a vendor");
                if (vendor.getPayableAccount() == null) {
                    throw new BusinessRuleViolationException("Vendor " + vendor.getNameEn()
                            + " has no payable account. Re-save the vendor to create one.");
                }
                requirePostableLeaf(vendor.getPayableAccount(), "Vendor payable account");
                if (VoucherMath.vatTotal(v.getLines()).signum() > 0 && isBlank(vendor.getTrn())) {
                    throw new BusinessRuleViolationException(trnRefusal(vendor));
                }
            }
            // A BPV line may be any leaf — a vendor payable being settled, an expense
            // paid without an invoice, a salary — so only the payment account is narrowed.
            case BPV -> {
                Account pay = v.getPaymentAccount();
                if (pay == null) {
                    throw new BusinessRuleViolationException("A payment voucher needs a payment account (bank or cash)");
                }
                requirePostableLeaf(pay, "Payment account");
                if (!ChequeService.isSettlementAccount(pay)) {
                    throw new BusinessRuleViolationException("Payment account " + pay.getCode() + " " + pay.getName()
                            + " must be a bank or cash account");
                }
                // F14-42: a line paying the payment account itself moves nothing.
                if (v.getLines().stream().anyMatch(l -> l.getAccount().getId().equals(pay.getId()))) {
                    throw new BusinessRuleViolationException("A line cannot be paid to " + pay.getCode() + " "
                            + pay.getName() + ", the account the payment is made from");
                }
                requirePayableLinesMatchTheVendor(v.getVendor() == null ? null : v.getVendor().getId(),
                        v.getLines().stream().map(l -> l.getAccount().getId()).toList());
                requireMethodMatchesAccount(v.getPaymentMethod(), pay, v.getChequeNumber());
            }
            case RCP -> throw new BusinessRuleViolationException(
                    "Cash Receipt Vouchers are posted from the lease receipt screen");
        }
    }

    private static int signum(BigDecimal b) { return b == null ? 0 : b.signum(); }

    private static void requirePostableLeaf(Account a, String label) {
        if (a == null) throw new BusinessRuleViolationException(label + " is missing");
        if (a.isGroup()) {
            throw new BusinessRuleViolationException(
                    label + " " + a.getCode() + " " + a.getName() + " is a group; pick a leaf account");
        }
        if (!a.isActive()) {
            throw new BusinessRuleViolationException(label + " " + a.getCode() + " is inactive");
        }
    }

    void requireDraft(Voucher v) {
        if (v.getStatus() != VoucherStatus.DRAFT) {
            throw new BusinessRuleViolationException(
                    "Voucher " + (v.getVoucherNumber() == null ? v.getId() : v.getVoucherNumber())
                            + " is " + v.getStatus() + "; only a DRAFT can be edited. Amend it instead.");
        }
    }

    private void validate(VoucherInput in, UUID selfId) {
        if (in.docType() == null) throw new BusinessRuleViolationException("Document type is required");
        if (in.docType() == VoucherType.RCP) {
            throw new BusinessRuleViolationException(
                    "Cash Receipt Vouchers are created from the lease receipt screen, not here");
        }
        if (in.docDate() == null) throw new BusinessRuleViolationException("Document date is required");
        if (in.lines() == null || in.lines().isEmpty()) {
            throw new BusinessRuleViolationException("A voucher needs at least one line");
        }
        if (in.docType() == VoucherType.PISR || in.docType() == VoucherType.PCN) {
            if (in.vendorId() == null) throw new BusinessRuleViolationException(in.docType() == VoucherType.PCN
                    ? "A supplier credit note needs a vendor" : "A purchase invoice needs a vendor");
            Vendor vendor = vendors.findById(in.vendorId())
                    .orElseThrow(() -> new NotFoundException("Vendor not found"));
            if (vendor.getPayableAccount() == null) {
                throw new BusinessRuleViolationException(
                        "Vendor " + vendor.getNameEn() + " has no payable account. Re-save the vendor to create one.");
            }
        }
        if (in.docType() == VoucherType.BPV) {
            if (in.paymentAccountId() == null) {
                throw new BusinessRuleViolationException("A payment voucher needs a payment account (bank or cash)");
            }
            // Controller ruling (Task 5): this used to be checked only at post() —
            // requirePostable, below — which let a clerk save a draft the server
            // would always refuse later. Same predicate as
            // ChequeService.isSettlementAccount, re-asserted at post for a row
            // reached some other way (SQL, a restored backup).
            Account pay = requireLeaf(in.paymentAccountId(), "Payment account");
            if (!ChequeService.isSettlementAccount(pay)) {
                throw new BusinessRuleViolationException("Payment account " + pay.getCode() + " " + pay.getName()
                        + " must be a bank or cash account");
            }
            VoucherPaymentMethod method = in.paymentMethod() == null ? inferMethod(in.chequeNumber(), pay) : in.paymentMethod();
            requireMethodMatchesAccount(method, pay, in.chequeNumber());
            // PR #352 review P3-6: a post-dated cheque is held in PDC payable against
            // the vendor it was written to (issued_cheques names one). Said at draft
            // save, not first at post.
            if (method == VoucherPaymentMethod.CHEQUE && in.chequeDate() != null && in.docDate() != null
                    && in.chequeDate().isAfter(in.docDate()) && in.vendorId() == null && in.settlementId() == null) {
                throw new BusinessRuleViolationException(PDC_NEEDS_VENDOR);
            }
            if (in.paymentReference() != null && in.paymentReference().trim().length() > 60) {
                throw new BusinessRuleViolationException("The payment reference is at most 60 characters");
            }
        }
        for (VoucherLineInput l : in.lines()) {
            if (l.accountId() == null) throw new BusinessRuleViolationException("Every line needs an account");
            Account lineAccount = requireLeaf(l.accountId(), "Line account");
            if (l.amount() == null || l.amount().signum() <= 0) {
                throw new BusinessRuleViolationException("Every line needs an amount greater than zero");
            }
            // Controller ruling (Task 5): moved from post()'s requirePostable — a
            // purchase invoice buys an expense or an asset, never income; re-checked
            // at post below for the same reason as the payment-account rule above.
            if ((in.docType() == VoucherType.PISR || in.docType() == VoucherType.PCN)
                    && lineAccount.getAccountType() != AccountType.EXPENSE
                    && lineAccount.getAccountType() != AccountType.ASSET) {
                throw new BusinessRuleViolationException("Line account " + lineAccount.getCode() + " "
                        + lineAccount.getName() + " is " + lineAccount.getAccountType()
                        + "; a purchase invoice line must be an expense or asset account");
            }
            if ((lineAccount.getAccountType() == AccountType.INCOME || lineAccount.getAccountType() == AccountType.EXPENSE)
                    && l.propertyId() == null && in.propertyId() == null && lineAccount.getPropertyId() == null
                    && !l.shared()) {
                throw new BusinessRuleViolationException("Line account " + lineAccount.getCode() + " "
                        + lineAccount.getName() + " " + LINE_PROPERTY_REFUSAL);
            }
            BigDecimal rate = l.vatRate() == null ? BigDecimal.ZERO : l.vatRate();
            // Controller ruling (Plan 4): a BPV line carries no VAT — VAT belongs to the
            // purchase invoice the payment settles, never to the payment itself.
            if (in.docType() == VoucherType.BPV && rate.signum() != 0) {
                throw new BusinessRuleViolationException(BPV_VAT_REFUSAL);
            }
            if (ALLOWED_VAT_RATES.stream().noneMatch(r -> r.compareTo(rate) == 0)) {
                throw new BusinessRuleViolationException("VAT rate must be 0 or 5, got " + rate);
            }
        }
        if (in.docType() == VoucherType.BPV) {
            requirePayableLinesMatchTheVendor(in.vendorId(),
                    in.lines().stream().map(VoucherLineInput::accountId).toList());
        }
        if (in.docType() == VoucherType.PISR) requireSupplierInvoiceRules(in, selfId);
        if (in.settlementId() != null) requireRefundPayment(in);
        requireRefundPayableNamesASettlement(in);
    }

    /**
     * F14-36: a payment of a settlement's deposit refund. A BPV with no vendor and
     * exactly one line, on the renter-refund payable; paid from a cash leaf or a bank
     * leaf some bank account owns; the settlement is this tenant's and FINALIZED
     * with a refund. The amount against what is still unpaid is checked at post.
     */
    private void requireRefundPayment(VoucherInput in) {
        if (in.docType() != VoucherType.BPV) {
            throw new BusinessRuleViolationException("Only a payment voucher pays a settlement refund");
        }
        if (in.vendorId() != null) {
            throw new BusinessRuleViolationException("A settlement refund is paid to the renter, not to a vendor");
        }
        com.datagami.rentaxis.domain.entity.LeaseSettlement s = refundSettlement(in.settlementId());
        UUID payable = refundPayableLeaf(s);
        if (in.lines().size() != 1 || !in.lines().get(0).accountId().equals(payable)) {
            throw new BusinessRuleViolationException("A settlement refund payment has one line, on the renters'"
                    + " refund payable account");
        }
        Account pay = accounts.findById(in.paymentAccountId()).orElse(null);
        if (pay != null && pay.getAccountSubType() == AccountSubType.BANK && ownedBankLeaf != null
                && ownedBankLeaf.anyOwned() && !ownedBankLeaf.isOwned(pay.getId())) {
            throw new BusinessRuleViolationException(pay.getCode() + " " + pay.getName()
                    + " is not the ledger account of any bank account; pay the refund from a bank account's"
                    + " account or from cash", "cheque.bankLeafNotOwned", Map.of("account", pay.getCode() + " " + pay.getName()));
        }
        // R1 P1: owed only by what the finalize booked to the payable. A settlement
        // finalized before refunds went through vouchers paid the bank directly and
        // owes nothing here.
        if (refundBooked(s).signum() <= 0) {
            throw new BusinessRuleViolationException("This settlement's refund was paid when it was finalized"
                    + " (before refunds were paid by voucher), so there is nothing left to pay",
                    "voucher.refundAlreadyPaid", Map.of());
        }
    }

    /**
     * R1 P2-4: the renters' refund payable is debited only by a payment that names
     * the settlement it pays; anything else would pay a refund the settlement's
     * tracking never sees.
     */
    private void requireRefundPayableNamesASettlement(VoucherInput in) {
        // R2 N4: debits only. A supplier credit note's lines are credits, so a
        // correcting credit to the payable is not blocked.
        if (in.settlementId() != null || accountResolver == null || in.docType() == VoucherType.PCN) return;
        java.util.Set<UUID> payables = new java.util.HashSet<>(entityManager.createQuery(
                "select m.account.id from TenantDefaultAccountMapping m where m.role = :r", UUID.class)
                .setParameter("r", com.datagami.rentaxis.domain.entity.enums.AccountRole.RENTER_REFUND_PAYABLE)
                .getResultList());
        payables.addAll(entityManager.createQuery(
                "select m.account.id from PropertyAccountMapping m where m.role = :r", UUID.class)
                .setParameter("r", com.datagami.rentaxis.domain.entity.enums.AccountRole.RENTER_REFUND_PAYABLE)
                .getResultList());
        if (in.lines().stream().anyMatch(l -> payables.contains(l.accountId()))) {
            throw new BusinessRuleViolationException("A deposit refund is paid from its settlement: open the lease's"
                    + " settlement and use Pay refund", "voucher.refundOutsideSettlement", Map.of());
        }
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.datagami.rentaxis.core.service.bank.OwnedBankLeaf ownedBankLeaf;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.datagami.rentaxis.core.service.ledger.AccountResolver accountResolver;

    private com.datagami.rentaxis.domain.entity.LeaseSettlement refundSettlement(UUID settlementId) {
        com.datagami.rentaxis.domain.entity.LeaseSettlement s =
                entityManager.find(com.datagami.rentaxis.domain.entity.LeaseSettlement.class, settlementId);
        UUID t = com.datagami.rentaxis.core.tenant.TenantContextHolder.getTenantId();
        if (s == null || (t != null && !t.equals(s.getTenantId()))) {
            throw new NotFoundException("Settlement not found");
        }
        if (s.getStatus() != com.datagami.rentaxis.domain.entity.enums.SettlementStatus.FINALIZED) {
            throw new BusinessRuleViolationException("Only a finalized settlement's refund can be paid");
        }
        return s;
    }

    /**
     * The payable leaf the settlement's STL credited (R2 N2: read off the journal's
     * refund line); for a journal without one, RENTER_REFUND_PAYABLE resolved for its
     * property (R1 P3-2).
     */
    private UUID refundPayableLeaf(com.datagami.rentaxis.domain.entity.LeaseSettlement s) {
        if (s.getJournalId() != null) {
            List<UUID> credited = entityManager.createQuery(
                    "select l.account.id from JournalLine l where l.entry.id = :e and l.narration = :n and l.credit > 0",
                    UUID.class).setParameter("e", s.getJournalId())
                    .setParameter("n", com.datagami.rentaxis.core.service.SettlementService.REFUND_PAYABLE_NARRATION)
                    .getResultList();
            if (!credited.isEmpty()) return credited.get(0);
        }
        if (accountResolver == null) throw new IllegalStateException("No account resolver");
        com.datagami.rentaxis.domain.entity.Lease lease =
                entityManager.find(com.datagami.rentaxis.domain.entity.Lease.class, s.getLeaseId());
        UUID property = lease == null || lease.getUnit() == null || lease.getUnit().getProperty() == null
                ? null : lease.getUnit().getProperty().getId();
        return accountResolver.resolve(com.datagami.rentaxis.domain.entity.enums.AccountRole.RENTER_REFUND_PAYABLE,
                property).getId();
    }

    /**
     * R1 P1: what the finalize booked to the renter: the STL's credit on the refund
     * payable leaf. Zero for a settlement finalized under the old flow, which paid
     * the refund straight from the bank.
     */
    public BigDecimal refundBooked(com.datagami.rentaxis.domain.entity.LeaseSettlement s) {
        if (s.getJournalId() == null) return BigDecimal.ZERO;
        return (BigDecimal) entityManager.createQuery(
                "select coalesce(sum(l.credit), 0) from JournalLine l where l.entry.id = :e and l.narration = :n")
                .setParameter("e", s.getJournalId())
                .setParameter("n", com.datagami.rentaxis.core.service.SettlementService.REFUND_PAYABLE_NARRATION)
                .getSingleResult();
    }

    /** F14-36: what a settlement's refund still owes: what finalize booked less every POSTED payment naming it. */
    public BigDecimal refundOutstanding(UUID settlementId, UUID excludeVoucherId) {
        com.datagami.rentaxis.domain.entity.LeaseSettlement s = refundSettlement(settlementId);
        BigDecimal paid = (BigDecimal) entityManager.createQuery(
                "select coalesce(sum(l.amount), 0) from VoucherLine l where l.voucher.settlementId = :s"
                        + " and l.voucher.status = :posted and l.voucher.id <> :self")
                .setParameter("s", settlementId).setParameter("posted", VoucherStatus.POSTED)
                .setParameter("self", excludeVoucherId == null ? UUID.randomUUID() : excludeVoucherId)
                .getSingleResult();
        return refundBooked(s).subtract(paid);
    }

    /**
     * Finance-ops spec §2, at draft save (and again, from the stored row, at post):
     * the supplier's invoice number is required and not already posted for this
     * vendor; input VAT needs the vendor's TRN; the due date is not before the
     * supplier's date. After the line checks, so a bad line is reported first.
     */
    private void requireSupplierInvoiceRules(VoucherInput in, UUID selfId) {
        Vendor vendor = vendors.findById(in.vendorId()).orElseThrow(() -> new NotFoundException("Vendor not found"));
        String norm = normaliseInvoiceNumber(in.invoiceNumber());
        if (norm == null) {
            throw new BusinessRuleViolationException("A purchase invoice needs the supplier's invoice number");
        }
        if (in.invoiceNumber().trim().length() > 60) {
            throw new BusinessRuleViolationException("The invoice number is at most 60 characters");
        }
        boolean anyVat = in.lines() != null && in.lines().stream()
                .anyMatch(l -> l.vatRate() != null && l.vatRate().signum() > 0);
        if (anyVat && isBlank(vendor.getTrn())) {
            throw new BusinessRuleViolationException(trnRefusal(vendor));
        }
        LocalDate supplierDate = in.supplierInvoiceDate() == null ? in.docDate() : in.supplierInvoiceDate();
        if (supplierDate != null && in.docDate() != null && supplierDate.isAfter(in.docDate())) {
            throw new BusinessRuleViolationException(SUPPLIER_DATE_AFTER_POSTING);
        }
        if (in.dueDate() != null && supplierDate != null && in.dueDate().isBefore(supplierDate)) {
            throw new BusinessRuleViolationException("The due date cannot be before the supplier's invoice date");
        }
        requireNoPostedDuplicate(vendor, in.invoiceNumber(), norm, selfId);
    }

    /**
     * A payment voucher settles the vendor it names.
     *
     * <p>The journal only ever touches the <em>line</em> accounts, and
     * {@code LedgerQueryService.vendorLedger} selects rows purely by the vendor's
     * payable leaf — so a voucher headed "paid to Emrill" whose line debits Al
     * Shirawi's payable moves Al Shirawi's balance and leaves Emrill's untouched.
     * The entry balances, the payments list says one thing and the vendor ledger
     * another, and nothing in the books afterwards says which is wrong. A payable
     * line with no vendor on the header is the same mistake in a different hat.</p>
     *
     * <p>Spec §10.2 keeps BPV lines open to any leaf — an expense paid without an
     * invoice, a salary — so it is only the payable ones that are tied down, and
     * only by the header the clerk already filled in.</p>
     */
    private void requirePayableLinesMatchTheVendor(UUID namedVendorId, List<UUID> lineAccountIds) {
        List<UUID> ids = lineAccountIds.stream().filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) return;
        // One query for the whole line set; empty for the ordinary voucher whose
        // lines are expenses, which is the common case.
        List<Vendor> owners = vendors.findByPayableAccount_IdIn(ids);
        if (owners.isEmpty()) return;

        Vendor named = namedVendorId == null ? null : vendors.findById(namedVendorId)
                .orElseThrow(() -> new NotFoundException("Vendor not found"));
        // The question is "is this line my vendor's payable account?", not "is my
        // vendor the only vendor who answers to it?". Nothing in the schema stops
        // two vendors sharing one leaf (vendors.payable_account_id has no unique
        // constraint, and updateVendor accepts a leaf already in use), and when they
        // do, a voucher naming either of them is settling exactly the account its
        // own vendor is settled through — so the comparison is on the account.
        UUID namedPayable = named == null || named.getPayableAccount() == null
                ? null : named.getPayableAccount().getId();
        for (Vendor owner : owners) {
            UUID ownerPayable = owner.getPayableAccount() == null ? null : owner.getPayableAccount().getId();
            if (ownerPayable != null && ownerPayable.equals(namedPayable)) continue;
            String account = owner.getPayableAccount() == null ? "" : owner.getPayableAccount().getCode() + " ";
            if (named == null) {
                throw new BusinessRuleViolationException("Line account " + account + "is "
                        + owner.getNameEn() + "'s payable account; name " + owner.getNameEn()
                        + " as this voucher's vendor, or change the line");
            }
            if (!owner.getId().equals(named.getId())) {
                throw new BusinessRuleViolationException("Line account " + account + "is "
                        + owner.getNameEn() + "'s payable account, but this voucher names " + named.getNameEn()
                        + "; pay " + owner.getNameEn() + " from their own voucher, or change the line");
            }
        }
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    /** A cheque number means a cheque, a cash leaf means cash, anything else a bank transfer. */
    static VoucherPaymentMethod inferMethod(String chequeNumber, Account pay) {
        if (!isBlank(chequeNumber)) return VoucherPaymentMethod.CHEQUE;
        if (pay != null && pay.getAccountSubType() == AccountSubType.CASH) return VoucherPaymentMethod.CASH;
        return VoucherPaymentMethod.TRANSFER;
    }

    /** Spec §2: CASH pays from a cash leaf; TRANSFER and CHEQUE from a bank leaf. A cheque has a number. */
    private static void requireMethodMatchesAccount(VoucherPaymentMethod method, Account pay, String chequeNumber) {
        if (method == null || pay == null) return;
        boolean cash = pay.getAccountSubType() == AccountSubType.CASH;
        if (method == VoucherPaymentMethod.CASH && !cash) {
            throw new BusinessRuleViolationException("A cash payment is paid from a cash account; "
                    + pay.getCode() + " " + pay.getName() + " is not one");
        }
        if (method != VoucherPaymentMethod.CASH && pay.getAccountSubType() != AccountSubType.BANK) {
            throw new BusinessRuleViolationException("A " + (method == VoucherPaymentMethod.CHEQUE ? "cheque" : "transfer")
                    + " is paid from a bank account; " + pay.getCode() + " " + pay.getName() + " is not one");
        }
        if (method == VoucherPaymentMethod.CHEQUE && isBlank(chequeNumber)) {
            throw new BusinessRuleViolationException("A cheque payment needs the cheque number");
        }
    }

    /**
     * Spec §2 duplicate guard: one POSTED PISR per vendor and normalised invoice
     * number ({@code ux_vouchers_pisr_invoice} enforces it; this names the
     * existing voucher). The same number from another vendor is a different invoice.
     */
    private void requireNoPostedDuplicate(Vendor vendor, String invoiceNumber, String norm, UUID selfId) {
        if (norm == null) return;
        vouchers.findPostedDuplicate(vendor.getId(), norm).stream()
                .filter(d -> selfId == null || !selfId.equals(d.getId()))
                .findFirst()
                .ifPresent(d -> {
                    throw new BusinessRuleViolationException(invoiceNumber.trim() + " from " + vendor.getNameEn()
                            + " is already posted as " + d.getVoucherNumber());
                });
    }

    /** Draft-time duplicate check for the form (spec §2 Web UI): the voucher number it would clash with, or null. */
    @Transactional(readOnly = true)
    public String postedDuplicateOf(UUID vendorId, String invoiceNumber, UUID excludeId) {
        String norm = normaliseInvoiceNumber(invoiceNumber);
        if (vendorId == null || norm == null) return null;
        return vouchers.findPostedDuplicate(vendorId, norm).stream()
                .filter(d -> excludeId == null || !excludeId.equals(d.getId()))
                .map(Voucher::getVoucherNumber).findFirst().orElse(null);
    }

    private Account requireLeaf(UUID accountId, String label) {
        Account a = accounts.findById(accountId)
                .orElseThrow(() -> new NotFoundException(label + " not found: " + accountId));
        if (a.isGroup()) {
            throw new BusinessRuleViolationException(
                    label + " " + a.getCode() + " " + a.getName() + " is a group; pick a leaf account");
        }
        if (!a.isActive()) {
            throw new BusinessRuleViolationException(label + " " + a.getCode() + " is inactive");
        }
        return a;
    }

    private void apply(Voucher v, VoucherInput in) {
        v.setDocType(in.docType());
        v.setDocDate(in.docDate());
        v.setVendor(in.vendorId() == null ? null : vendors.findById(in.vendorId()).orElseThrow(
                () -> new NotFoundException("Vendor not found")));
        v.setInvoiceNumber(in.invoiceNumber());
        v.setNarration(in.narration());
        v.setPropertyId(in.propertyId());
        v.setUnitId(in.unitId());
        v.setPaymentAccount(in.paymentAccountId() == null ? null : accounts.findById(in.paymentAccountId())
                .orElseThrow(() -> new NotFoundException("Payment account not found")));
        v.setChequeNumber(in.chequeNumber());
        v.setChequeDate(in.chequeDate());
        v.setSettlementId(in.settlementId());
        if (in.settlementId() != null) {
            // The refund's lease and unit, so the payment sits beside the STL that owed it.
            com.datagami.rentaxis.domain.entity.LeaseSettlement s = refundSettlement(in.settlementId());
            com.datagami.rentaxis.domain.entity.Lease lease =
                    entityManager.find(com.datagami.rentaxis.domain.entity.Lease.class, s.getLeaseId());
            if (lease != null && lease.getUnit() != null) {
                v.setUnitId(lease.getUnit().getId());
                if (lease.getUnit().getProperty() != null) v.setPropertyId(lease.getUnit().getProperty().getId());
            }
        }
        if (in.docType() == VoucherType.PISR) {
            v.setInvoiceNumber(in.invoiceNumber() == null ? null : in.invoiceNumber().trim());
            v.setInvoiceNoNorm(normaliseInvoiceNumber(in.invoiceNumber()));
            LocalDate supplierDate = in.supplierInvoiceDate() == null ? in.docDate() : in.supplierInvoiceDate();
            v.setSupplierInvoiceDate(supplierDate);
            v.setDueDate(in.dueDate() == null ? defaultDueDate(supplierDate, v.getVendor()) : in.dueDate());
            v.setPaymentMethod(null);
            v.setPaymentReference(null);
        } else if (in.docType() == VoucherType.PCN) {
            // F14-40: the supplier's credit note number is kept, but it is not an
            // invoice: no invoice key, no due date, no payment account or method.
            v.setInvoiceNumber(in.invoiceNumber() == null ? null : in.invoiceNumber().trim());
            v.setInvoiceNoNorm(null);
            v.setSupplierInvoiceDate(null);
            v.setDueDate(null);
            v.setPaymentAccount(null);
            v.setPaymentMethod(null);
            v.setPaymentReference(null);
        } else {
            v.setInvoiceNoNorm(null);
            v.setSupplierInvoiceDate(null);
            v.setDueDate(null);
            v.setPaymentMethod(in.paymentMethod() == null ? inferMethod(in.chequeNumber(), v.getPaymentAccount())
                    : in.paymentMethod());
            v.setPaymentReference(isBlank(in.paymentReference()) ? null : in.paymentReference().trim());
        }

        List<VoucherLine> newLines = new ArrayList<>();
        for (VoucherLineInput li : in.lines()) {
            VoucherLine l = new VoucherLine();
            l.setAccount(accounts.findById(li.accountId()).orElseThrow(
                    () -> new NotFoundException("Account not found: " + li.accountId())));
            l.setDescription(li.description());
            l.setAmount(li.amount().setScale(2, java.math.RoundingMode.HALF_UP));
            BigDecimal rate = li.vatRate() == null ? BigDecimal.ZERO : li.vatRate();
            l.setVatRate(rate.setScale(2, java.math.RoundingMode.HALF_UP));
            l.setVatAmount(VoucherMath.vat(l.getAmount(), rate));
            // "Shared / head office" keeps the line off every property, header included.
            l.setPropertyId(li.shared() && li.propertyId() == null ? null
                    : li.propertyId() == null ? in.propertyId() : li.propertyId());
            l.setUnitId(li.shared() && li.propertyId() == null ? null
                    : li.unitId() == null ? in.unitId() : li.unitId());
            newLines.add(l);
        }
        v.replaceLines(newLines);
        // Spec §2: the header property follows the lines. When every line of a
        // PISR names the same property and the header names none, the header takes
        // it, so the INPUT_VAT line and the vendor credit carry it too (§1 section 8).
        if (in.docType() == VoucherType.PISR && v.getPropertyId() == null && !newLines.isEmpty()) {
            UUID first = newLines.get(0).getPropertyId();
            if (first != null && newLines.stream().allMatch(l -> first.equals(l.getPropertyId()))) {
                v.setPropertyId(first);
            }
        }
    }
}
