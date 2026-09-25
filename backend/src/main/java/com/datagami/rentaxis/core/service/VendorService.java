package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Vendor;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository;
import com.datagami.rentaxis.domain.repository.VendorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class VendorService {

    private final VendorRepository repository;
    private final JournalLineRepository journalLineRepository;
    private final AccountService accountService;
    private final AccountRepository accountRepository;

    @Transactional(readOnly = true)
    public List<Vendor> getAllVendors() {
        return repository.findAllByOrderByNameEnAsc();
    }

    /** {@code GET /vendors/paged} (scale P1-3): searched (names, TRN, email, phone, contact) and paged, by name. */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<Vendor> searchPaged(String q, int page, int size) {
        return repository.searchPaged(com.datagami.rentaxis.core.util.Search.requireTenant(),
                com.datagami.rentaxis.core.util.Search.like(q),
                com.datagami.rentaxis.core.util.Search.page(page, size, org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Order.asc("nameEn"),
                        org.springframework.data.domain.Sort.Order.asc("id"))));
    }

    @Transactional(readOnly = true)
    public Vendor getVendorById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Vendor not found"));
    }

    /**
     * Finance-ops spec §2: a UAE TRN is 15 digits. Spaces a clerk typed between
     * the groups are dropped; anything else that is not exactly 15 digits is
     * refused. Blank means "no TRN" and is stored as null — the voucher's input-VAT
     * rule reads null and blank the same way, but one spelling is easier to query.
     */
    public static String normaliseTrn(String trn) {
        if (trn == null) return null;
        // Spaces and hyphens a clerk typed between the groups, and Arabic-Indic or
        // Persian digits, are normalised away before the 15-digit check.
        StringBuilder b = new StringBuilder();
        trn.codePoints().forEach(c -> {
            if (Character.isWhitespace(c) || c == '-' || c == '\u2010' || c == '\u2011' || c == '\u2013') return;
            if (c >= '\u0660' && c <= '\u0669') c = '0' + (c - '\u0660');
            else if (c >= '\u06F0' && c <= '\u06F9') c = '0' + (c - '\u06F0');
            b.appendCodePoint(c);
        });
        String digits = b.toString();
        if (digits.isEmpty()) return null;
        if (!digits.matches("\\d{15}")) {
            throw new BusinessRuleViolationException("TRN must be 15 digits (UAE format), got \"" + trn.trim() + "\"");
        }
        return digits;
    }

    private static int requireTerms(Integer days) {
        if (days == null) return 30;
        if (days < 0 || days > 365) {
            throw new BusinessRuleViolationException("Payment terms must be between 0 and 365 days");
        }
        return days;
    }

    @Transactional
    public Vendor createVendor(Vendor vendor) {
        vendor.setTrn(normaliseTrn(vendor.getTrn()));
        requireNoDuplicate(vendor, null);
        vendor.setPaymentTermsDays(requireTerms(vendor.getPaymentTermsDays()));
        if (vendor.getPayableAccount() == null) {
            try {
                Account vendorsGroup = accountService.getAccountByCode("B-01-04");
                vendor.setPayableAccount(accountService.createLeaf(vendor.getNameEn(), vendor.getNameAr(), vendorsGroup, null));
            } catch (NotFoundException e) {
                log.warn("No Vendors account group (B-01-04) for tenant; creating vendor without a ledger account");
            }
        }
        return repository.save(vendor);
    }

    @Transactional
    public Vendor updateVendor(UUID id, Vendor updates) {
        Vendor existing = getVendorById(id);
        String oldName = normaliseName(existing.getNameEn());
        String oldTrn = existing.getTrn();
        existing.setNameEn(updates.getNameEn());
        existing.setNameAr(updates.getNameAr());
        existing.setTradeLicenseNumber(updates.getTradeLicenseNumber());
        existing.setTrn(normaliseTrn(updates.getTrn()));
        // R1 P3-6: only when the name or TRN changes, so a legacy duplicate pair can
        // still be edited (a phone number) without being renamed first.
        if (!oldName.equals(normaliseName(updates.getNameEn())) || !java.util.Objects.equals(oldTrn, existing.getTrn())) {
            Vendor probe = new Vendor();
            probe.setNameEn(updates.getNameEn());
            probe.setTrn(existing.getTrn());
            requireNoDuplicate(probe, existing.getId());
        }
        // Left out of the body: keep the stored terms rather than resetting them.
        if (updates.getPaymentTermsDays() != null) {
            existing.setPaymentTermsDays(requireTerms(updates.getPaymentTermsDays()));
        }
        existing.setEmail(updates.getEmail());
        existing.setPhone(updates.getPhone());
        existing.setContactPerson(updates.getContactPerson());
        existing.setAddress(updates.getAddress());
        existing.setBankName(updates.getBankName());
        existing.setBankAccountNumber(updates.getBankAccountNumber());
        existing.setIban(updates.getIban());
        existing.setActive(updates.isActive());
        existing.setNotes(updates.getNotes());
        // The payable leaf is never taken from the update (PR #340 review I1): the
        // vendor keeps the one created with it, renamed/deactivated below. Taking
        // the client's object inserted it as a new, parentless account and moved
        // the vendor's ledger onto it.

        Account leaf = existing.getPayableAccount();
        if (leaf != null && !leaf.isSystem()) {
            leaf.setName(existing.getNameEn());
            leaf.setNameEn(existing.getNameEn());
            leaf.setNameAr(existing.getNameAr());
            leaf.setActive(existing.isActive());
            accountRepository.save(leaf);
        }
        return repository.save(existing);
    }

    @Transactional
    public void deleteVendor(UUID id) {
        if (!repository.existsById(id)) {
            throw new NotFoundException("Vendor not found");
        }
        // v1 asked financial_transactions "any row for this vendor?". The vendor
        // ledger is now journal_lines against the vendor's payable leaf
        // (LedgerQueryService), so that is what "has transactions" means.
        Vendor vendor = getVendorById(id);
        Account payable = vendor.getPayableAccount();
        if (payable != null && journalLineRepository.existsByAccount_Id(payable.getId())) {
            throw new BusinessRuleViolationException("Cannot delete vendor " + vendor.getNameEn()
                    + ": it has postings. Mark it inactive instead (archive).", "vendor.hasPostings",
                    java.util.Map.of("vendor", String.valueOf(vendor.getNameEn())));
        }
        repository.delete(vendor);
        // F14-43: its payable leaf leaves the chart with it. Deactivated rather than
        // deleted (R1 P3-5): a leaf a draft voucher still names would fail the delete
        // and poison the transaction; an inactive leaf is out of every picker.
        if (payable != null && !payable.isSystem()) {
            payable.setActive(false);
            accountRepository.save(payable);
        }
    }

    /**
     * F14-43: one vendor per TRN, and one per name. The name is compared
     * normalised (case, spacing and punctuation ignored), so "R14 Sparkle
     * Cleaning LLC" and "r14 sparkle cleaning, L.L.C." are the same vendor.
     */
    private void requireNoDuplicate(Vendor v, UUID self) {
        String name = normaliseName(v.getNameEn());
        for (Vendor other : repository.findAllByOrderByNameEnAsc()) {
            if (other.getId() != null && other.getId().equals(self)) continue;
            if (v.getTrn() != null && v.getTrn().equals(other.getTrn())) {
                throw new BusinessRuleViolationException("Vendor " + other.getNameEn() + " already has TRN " + v.getTrn(),
                        "vendor.duplicateTrn", java.util.Map.of("vendor", String.valueOf(other.getNameEn()), "trn", v.getTrn()));
            }
            if (!name.isEmpty() && name.equals(normaliseName(other.getNameEn()))) {
                throw new BusinessRuleViolationException("A vendor named " + other.getNameEn() + " already exists",
                        "vendor.duplicateName", java.util.Map.of("vendor", String.valueOf(other.getNameEn())));
            }
        }
    }

    static String normaliseName(String name) {
        if (name == null) return "";
        return name.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\p{Punct}]", "").replaceAll("\\s+", " ").trim();
    }
}
