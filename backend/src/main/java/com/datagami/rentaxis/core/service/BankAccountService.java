package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.BankAccount;
import com.datagami.rentaxis.domain.repository.BankAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BankAccountService {

    private final BankAccountRepository repository;

    @Transactional(readOnly = true)
    public List<BankAccount> getAllBankAccounts() {
        return repository.findAllByOrderByBankNameAsc();
    }

    @Transactional(readOnly = true)
    public List<BankAccount> getByProperty(UUID propertyId) {
        return repository.findByPropertyId(propertyId);
    }

    @Transactional(readOnly = true)
    public BankAccount getBankAccountById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Bank account not found"));
    }

    @Transactional
    public BankAccount createBankAccount(BankAccount bankAccount) {
        return repository.save(bankAccount);
    }

    @Transactional
    public BankAccount updateBankAccount(UUID id, BankAccount updates) {
        BankAccount existing = getBankAccountById(id);
        existing.setBankName(updates.getBankName());
        existing.setAccountNumber(updates.getAccountNumber());
        existing.setIban(updates.getIban());
        existing.setBranchName(updates.getBranchName());
        existing.setCurrency(updates.getCurrency());
        existing.setProperty(updates.getProperty());
        existing.setCoaAccount(updates.getCoaAccount());
        existing.setDefault(updates.isDefault());
        existing.setActive(updates.isActive());
        return repository.save(existing);
    }

    @Transactional
    public void deleteBankAccount(UUID id) {
        repository.deleteById(id);
    }
}
