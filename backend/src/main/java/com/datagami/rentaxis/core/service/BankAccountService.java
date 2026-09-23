package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.BankAccountRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.UnmappedAccountRoleException;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.BankAccount;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.AccountSubType;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.BankAccountRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
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
    private final AccountRepository accountRepository;
    private final PropertyRepository propertyRepository;

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
                .orElseThrow(() -> new NotFoundException("Bank account not found"));
    }

    /**
     * Creates a bank account from a request body. The property and ledger account
     * arrive as ids and are resolved here, inside the transaction and the caller's
     * tenant, before anything is saved (gap #67).
     */
    @Transactional
    public BankAccount createBankAccount(BankAccountRequest request) {
        BankAccount b = new BankAccount();
        apply(b, request);
        b.setDefault(Boolean.TRUE.equals(request.isDefault()));
        b.setActive(request.active() == null || request.active());
        return createBankAccount(b);
    }

    @Transactional
    public BankAccount updateBankAccount(UUID id, BankAccountRequest request) {
        BankAccount existing = getBankAccountById(id);
        apply(existing, request);
        if (request.isDefault() != null) existing.setDefault(request.isDefault());
        if (request.active() != null) existing.setActive(request.active());
        return repository.save(existing);
    }

    private void apply(BankAccount b, BankAccountRequest r) {
        if (r.bankName() == null || r.bankName().isBlank()) {
            throw new BusinessRuleViolationException("Bank name is required");
        }
        if (r.accountNumber() == null || r.accountNumber().isBlank()) {
            throw new BusinessRuleViolationException("Account number is required");
        }
        b.setBankName(r.bankName().trim());
        b.setAccountNumber(r.accountNumber().trim());
        b.setIban(r.iban());
        b.setBranchName(r.branchName());
        b.setCurrency(r.currency() == null || r.currency().isBlank() ? "AED" : r.currency());
        b.setProperty(resolveProperty(r.property()));
        b.setCoaAccount(resolveBankLeaf(r.coaAccount()));
    }

    private Property resolveProperty(BankAccountRequest.Ref ref) {
        if (ref == null) return null;
        if (ref.id() == null) throw new BusinessRuleViolationException("property.id is required");
        return propertyRepository.findById(ref.id())
                .filter(p -> inCurrentTenant(p.getTenantId()))
                .orElseThrow(() -> new NotFoundException("Property not found"));
    }

    /**
     * A bank account posts to its ledger account, so that account must be one of
     * this tenant's BANK-subtype leaves: not a group, not a receivable (gap #66).
     */
    private Account resolveBankLeaf(BankAccountRequest.Ref ref) {
        if (ref == null) return null;
        if (ref.id() == null) throw new BusinessRuleViolationException("coaAccount.id is required");
        Account a = accountRepository.findByIdScopedToTenant(ref.id())
                .filter(acc -> inCurrentTenant(acc.getTenantId()))
                .orElseThrow(() -> new NotFoundException("Ledger account not found"));
        if (a.isGroup() || a.getAccountSubType() != AccountSubType.BANK) {
            throw new BusinessRuleViolationException(
                    "A bank account must be linked to a Bank ledger account, not " + a.getCode() + " " + a.getName());
        }
        if (!a.isActive()) {
            throw new BusinessRuleViolationException("Ledger account " + a.getCode() + " is inactive");
        }
        return a;
    }

    /** Belt and braces over the Hibernate tenant filter: an id from a request body never crosses tenants. */
    private static boolean inCurrentTenant(UUID rowTenantId) {
        UUID current = TenantContextHolder.getTenantId();
        return current != null && current.equals(rowTenantId);
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
                    // accountNumber is optional on the entity, so this fallback has to
                    // tolerate a null one rather than NPE the whole create.
                    String accountNumber = bankAccount.getAccountNumber();
                    String last4 = accountNumber == null || accountNumber.isBlank() ? null
                            : accountNumber.length() > 4
                                    ? accountNumber.substring(accountNumber.length() - 4) : accountNumber;
                    Account bankGroup = accountService.getAccountByCode("A-02-02");
                    String leafName = last4 == null ? bankAccount.getBankName() : bankAccount.getBankName() + " - " + last4;
                    bankAccount.setCoaAccount(accountService.createLeaf(leafName, bankGroup, null));
                }
            } catch (NotFoundException e) {
                log.warn("No Bank account group (A-02-02) for tenant; creating bank account without a ledger account");
            }
        }
        return repository.save(bankAccount);
    }

    @Transactional
    public void deleteBankAccount(UUID id) {
        repository.deleteById(id);
    }
}
