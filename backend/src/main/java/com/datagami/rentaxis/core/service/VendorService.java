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
        existing.setNameEn(updates.getNameEn());
        existing.setNameAr(updates.getNameAr());
        existing.setTradeLicenseNumber(updates.getTradeLicenseNumber());
        existing.setTrn(normaliseTrn(updates.getTrn()));
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
            throw new BusinessRuleViolationException("Cannot delete vendor with existing transactions");
        }
        repository.deleteById(id);
    }
}
