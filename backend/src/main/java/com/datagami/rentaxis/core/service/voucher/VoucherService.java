package com.datagami.rentaxis.core.service.voucher;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.VoucherStatus;
import com.datagami.rentaxis.domain.entity.enums.VoucherType;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.VendorRepository;
import com.datagami.rentaxis.domain.repository.VoucherRepository;
import lombok.RequiredArgsConstructor;
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

    /** UAE standard rate is 5%; zero-rated and exempt supplies are 0. Nothing else is legal today. */
    private static final Set<BigDecimal> ALLOWED_VAT_RATES =
            Set.of(new BigDecimal("0.00"), new BigDecimal("5.00"));

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

    // ---- internals shared with post() in Tasks 3 and 4 ----

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
                throw new BusinessRuleViolationException(
                        "A payment voucher line cannot carry VAT — record the VAT on the purchase invoice");
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
