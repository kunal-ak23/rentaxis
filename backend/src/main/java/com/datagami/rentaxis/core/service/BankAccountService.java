package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.UnmappedAccountRoleException;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.BankAccount;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.repository.BankAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BankAccountService {

    private final BankAccountRepository repository;
    private final AccountService accountService;
    private final AccountResolver resolver;

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
        if (bankAccount.getCoaAccount() == null) {
            try {
                if (bankAccount.getProperty() != null) {
                    try {
                        bankAccount.setCoaAccount(resolver.resolve(AccountRole.BANK, bankAccount.getProperty().getId()));
                    } catch (UnmappedAccountRoleException e) {
                        // fall through to own leaf
                    }
                }
                if (bankAccount.getCoaAccount() == null) {
                    String accountNumber = bankAccount.getAccountNumber();
                    String last4 = accountNumber.length() > 4
                            ? accountNumber.substring(accountNumber.length() - 4) : accountNumber;
                    Account bankGroup = accountService.getAccountByCode("A-02-02");
                    bankAccount.setCoaAccount(accountService.createLeaf(bankAccount.getBankName() + " - " + last4, bankGroup, null));
                }
            } catch (NotFoundException e) {
                log.warn("No Bank account group (A-02-02) for tenant; creating bank account without a ledger account");
            }
        }
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
