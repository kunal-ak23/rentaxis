package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.Vendor;
import com.datagami.rentaxis.domain.repository.FinancialTransactionRepository;
import com.datagami.rentaxis.domain.repository.VendorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VendorService {

    private final VendorRepository repository;
    private final FinancialTransactionRepository transactionRepository;

    @Transactional(readOnly = true)
    public List<Vendor> getAllVendors() {
        return repository.findAllByOrderByNameEnAsc();
    }

    @Transactional(readOnly = true)
    public Vendor getVendorById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Vendor not found"));
    }

    @Transactional
    public Vendor createVendor(Vendor vendor) {
        return repository.save(vendor);
    }

    @Transactional
    public Vendor updateVendor(UUID id, Vendor updates) {
        Vendor existing = getVendorById(id);
        existing.setNameEn(updates.getNameEn());
        existing.setNameAr(updates.getNameAr());
        existing.setTradeLicenseNumber(updates.getTradeLicenseNumber());
        existing.setTrn(updates.getTrn());
        existing.setEmail(updates.getEmail());
        existing.setPhone(updates.getPhone());
        existing.setContactPerson(updates.getContactPerson());
        existing.setAddress(updates.getAddress());
        existing.setBankName(updates.getBankName());
        existing.setBankAccountNumber(updates.getBankAccountNumber());
        existing.setIban(updates.getIban());
        existing.setActive(updates.isActive());
        existing.setNotes(updates.getNotes());
        existing.setPayableAccount(updates.getPayableAccount());
        return repository.save(existing);
    }

    @Transactional
    public void deleteVendor(UUID id) {
        if (!transactionRepository.findByVendorId(id).isEmpty()) {
            throw new IllegalStateException("Cannot delete vendor with existing transactions");
        }
        repository.deleteById(id);
    }
}
