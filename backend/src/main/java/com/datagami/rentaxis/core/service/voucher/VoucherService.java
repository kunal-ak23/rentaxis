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

    public record VoucherLineInput(UUID accountId, String description, BigDecimal amount,
                                   BigDecimal vatRate, UUID propertyId, UUID unitId) {}

    public record VoucherInput(VoucherType docType, LocalDate docDate, UUID vendorId, String invoiceNumber,
                               String narration, UUID propertyId, UUID unitId, UUID paymentAccountId,
                               String chequeNumber, LocalDate chequeDate, List<VoucherLineInput> lines) {}

    @Transactional(readOnly = true)
    public Voucher get(UUID voucherId) {
        Voucher v = vouchers.findById(voucherId).orElseThrow(() -> new NotFoundException("Voucher not found"));
        v.getLines().size();   // initialise before the session closes
        return v;
    }

    @Transactional(readOnly = true)
    public Page<Voucher> list(VoucherType docType, VoucherStatus status, UUID vendorId, UUID propertyId,
                              LocalDate from, LocalDate to, Pageable pageable) {
        return vouchers.search(docType, status, vendorId, propertyId, from, to, pageable);
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
            journalLines.add(PostingRequest.dr(l.getAccount().getId(), l.getAmount())
                    .withDims(new PostingRequest.Dimensions(l.getPropertyId(), l.getUnitId(), null, null, null))
                    .withNarration(l.getDescription()));
        }

        switch (v.getDocType()) {
            case PISR -> {
                if (vat.signum() > 0) {
                    // INPUT_VAT is not property-scoped (AccountRole#isPropertyScoped), so the
                    // resolver falls through to the tenant default. One line for the whole
                    // invoice: the FTA return is filed per period, not per expense account.
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
     * <p>The Hibernate tenant filter applies to {@code find} too
     * ({@code BaseTenantEntity}, {@code applyToLoadByKey = true}); the explicit
     * check repeats it rather than trusting one mechanism with a P0.</p>
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
            if (v.getDocType() == VoucherType.BPV && l.getVatAmount() != null && l.getVatAmount().signum() != 0) {
                throw new BusinessRuleViolationException(BPV_VAT_REFUSAL);
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
            }
            case RCP -> throw new BusinessRuleViolationException(
                    "Cash Receipt Vouchers are posted from the lease receipt screen");
        }
    }

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
            requireLeaf(in.paymentAccountId(), "Payment account");
        }
        for (VoucherLineInput l : in.lines()) {
            if (l.accountId() == null) throw new BusinessRuleViolationException("Every line needs an account");
            requireLeaf(l.accountId(), "Line account");
            if (l.amount() == null || l.amount().signum() <= 0) {
                throw new BusinessRuleViolationException("Every line needs an amount greater than zero");
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
    }

    private void requireLeaf(UUID accountId, String label) {
        Account a = accounts.findById(accountId)
                .orElseThrow(() -> new NotFoundException(label + " not found: " + accountId));
        if (a.isGroup()) {
            throw new BusinessRuleViolationException(
                    label + " " + a.getCode() + " " + a.getName() + " is a group; pick a leaf account");
        }
        if (!a.isActive()) {
            throw new BusinessRuleViolationException(label + " " + a.getCode() + " is inactive");
        }
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
            l.setPropertyId(li.propertyId() == null ? in.propertyId() : li.propertyId());
            l.setUnitId(li.unitId() == null ? in.unitId() : li.unitId());
            newLines.add(l);
        }
        v.replaceLines(newLines);
    }
}
