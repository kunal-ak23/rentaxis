package com.datagami.rentaxis.core.service.voucher;

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
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.VoucherStatus;
import com.datagami.rentaxis.domain.entity.enums.VoucherType;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.VendorRepository;
import com.datagami.rentaxis.domain.repository.VoucherRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import lombok.RequiredArgsConstructor;
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

    public record VoucherInput(VoucherType docType, LocalDate docDate, UUID vendorId, String invoiceNumber,
                               String narration, UUID propertyId, UUID unitId, UUID paymentAccountId,
                               String chequeNumber, LocalDate chequeDate, List<VoucherLineInput> lines) {}

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
        validate(in);
        Voucher v = new Voucher();
        apply(v, in);
        v.setStatus(VoucherStatus.DRAFT);
        return vouchers.save(v);
    }

    @Transactional
    public Voucher updateDraft(UUID voucherId, VoucherInput in) {
        Voucher v = get(voucherId);
        requireDraft(v);
        validate(in);
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
        Voucher v = lockForWrite(voucherId);
        requireDraft(v);
        requirePostable(v);

        BigDecimal net = VoucherMath.netTotal(v.getLines());
        BigDecimal vat = VoucherMath.vatTotal(v.getLines());
        BigDecimal gross = net.add(vat);

        PostingRequest.Dimensions headerDims =
                new PostingRequest.Dimensions(v.getPropertyId(), v.getUnitId(), null, null, null);

        List<PostingRequest.Line> journalLines = new ArrayList<>();
        for (VoucherLine l : v.getLines()) {
            PostingRequest.Line jl = PostingRequest.dr(l.getAccount().getId(), l.getAmount())
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
            case BPV -> journalLines.add(PostingRequest.cr(v.getPaymentAccount().getId(), net)
                    .withDims(headerDims)
                    .withNarration(v.getChequeNumber() == null
                            ? v.getPaymentAccount().getName()
                            : "Cheque " + v.getChequeNumber()));
            // Unreachable: requirePostable refuses RCP above, and validate() refuses it
            // at draft time. The arm is here because the switch is exhaustive over
            // VoucherType, and an RCP that ever did arrive should say where it belongs.
            case RCP -> throw new BusinessRuleViolationException(
                    "Cash Receipt Vouchers are posted from the lease receipt screen");
        }

        JournalEntry entry = posting.post(new PostingRequest(
                v.getDocType().toDocType(), v.getDocDate(), v.getNarration(), headerDims,
                JournalSourceType.VOUCHER, v.getId(), null, journalLines));

        v.setStatus(VoucherStatus.POSTED);
        v.setJournalId(entry.getId());
        v.setVoucherNumber(entry.getEntryNumber());
        v.setPostedAt(entry.getPostedAt() == null ? Instant.now() : entry.getPostedAt());
        v.setPostedBy(entry.getPostedBy());
        v.setUpdatedAt(Instant.now());
        return vouchers.save(v);
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
        Voucher original = lockForWrite(voucherId);
        if (original.getStatus() != VoucherStatus.POSTED) {
            throw new BusinessRuleViolationException(
                    "Only a POSTED voucher can be amended; this one is " + original.getStatus());
        }
        if (reversalDate == null) throw new BusinessRuleViolationException("A reversal date is required");
        // Asked here as well as inside PostingService.reverse so that a reversal
        // dated into a closed period is refused before any of this is written.
        fiscal.assertOpen(reversalDate);

        posting.reverse(original.getJournalId(), reversalDate, reason);
        original.setStatus(VoucherStatus.REVERSED);
        original.setUpdatedAt(Instant.now());
        vouchers.save(original);

        Voucher fresh = createDraft(replacement);
        fresh.setAmendedFromId(original.getId());
        // Flushed before post(), which loads the row by key and refreshes it under a
        // lock: refresh overwrites the instance from the database, so an insert still
        // sitting in the action queue would either be missed or would lose
        // amendedFromId. (Same shape as the flush updateDraft needs, for the same
        // reason: this class hands rows to Hibernate and then re-reads them.)
        vouchers.saveAndFlush(fresh);
        return post(fresh.getId());
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
     * voucher row held by a sequence-waiter, and a blocking lock here cannot
     * deadlock. Anyone adding a "lock a voucher row after posting" path is the one
     * who would break that.</p>
     */
    private Voucher lockForWrite(UUID voucherId) {
        Voucher v = entityManager.find(Voucher.class, voucherId);
        if (v == null) throw new NotFoundException("Voucher not found");
        try {
            entityManager.refresh(v, LockModeType.PESSIMISTIC_WRITE);
        } catch (PessimisticLockingFailureException | PessimisticLockException | LockTimeoutException e) {
            // Three types for one event: an EntityManager call is not put through
            // Spring Data's exception translation, so JPA's own types get out.
            throw new RowLockedException("This voucher is being posted right now; try again");
        }
        UUID tenantId = TenantContextHolder.getTenantId();
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
            if (v.getDocType() == VoucherType.PISR
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
                requirePayableLinesMatchTheVendor(v.getVendor() == null ? null : v.getVendor().getId(),
                        v.getLines().stream().map(l -> l.getAccount().getId()).toList());
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

    private void validate(VoucherInput in) {
        if (in.docType() == null) throw new BusinessRuleViolationException("Document type is required");
        if (in.docType() == VoucherType.RCP) {
            throw new BusinessRuleViolationException(
                    "Cash Receipt Vouchers are created from the lease receipt screen, not here");
        }
        if (in.docDate() == null) throw new BusinessRuleViolationException("Document date is required");
        if (in.lines() == null || in.lines().isEmpty()) {
            throw new BusinessRuleViolationException("A voucher needs at least one line");
        }
        if (in.docType() == VoucherType.PISR) {
            if (in.vendorId() == null) throw new BusinessRuleViolationException("A purchase invoice needs a vendor");
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
            if (in.docType() == VoucherType.PISR && lineAccount.getAccountType() != AccountType.EXPENSE
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
    }
}
