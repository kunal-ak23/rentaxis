package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.BankAccountRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.bank.OwnedBankLeaf;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
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
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;
    private final OwnedBankLeaf ownedBankLeaf;

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
        UUID linkedBefore = existing.getCoaAccount() == null ? null : existing.getCoaAccount().getId();
        apply(existing, request);
        if (request.isDefault() != null) existing.setDefault(request.isDefault());
        if (request.active() != null) existing.setActive(request.active());
        BankAccount saved = repository.save(existing);
        if (saved.isDefault()) clearOtherDefaults(saved);
        // #98 M1: relinking to another leaf makes this account own it, on the same
        // terms as a new account (a building's unposted leaf moves to the building's
        // bank). Refused only where postings or a reconciliation would be affected:
        // the building's leaf has postings under another bank account, or the leaf
        // has postings inside this account's reconciled period. Keeping the current
        // link is not a new choice.
        Account linked = saved.getCoaAccount();
        if (linked != null && !linked.getId().equals(linkedBefore)) {
            repository.flush();
            OwnedBankLeaf.Claim c = ownedBankLeaf.claim(saved.getId(), linked.getId(), true);
            if (c == OwnedBankLeaf.Claim.OWNED_ELSEWHERE
                    && ownedBankLeaf.buildingLeaf(saved.getTenantId(), saved.getId(), linked.getId())) {
                throw new BusinessRuleViolationException(linked.getCode() + " " + linked.getName()
                        + " belongs to bank account " + ownerLabel(linked.getId())
                        + " and has postings; it cannot move to this bank account");
            }
            if (c == OwnedBankLeaf.Claim.LOCKED) {
                java.time.LocalDate through = jdbc.queryForObject(
                        "select reconciled_through from bank_accounts where id = :b and tenant_id = :t",
                        new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("b", saved.getId())
                                .addValue("t", saved.getTenantId()), java.time.LocalDate.class);
                throw new BusinessRuleViolationException("This bank account is reconciled through "
                        + (through == null ? "?" : through.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")))
                        + "; " + linked.getCode() + " " + linked.getName()
                        + " has postings on or before that date and cannot join it");
            }
        }
        return saved;
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
        b.setCoaAccount(resolveBankLeaf(r.coaAccount(), b.getCoaAccount()));
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
     *
     * <p>Only a change of link is validated. A row linked under the old ASSET-wide
     * picker (to a receivable or a group) re-sends that same id on every edit; if
     * it were re-validated here, fixing a typo in the IBAN would 400 until the
     * operator also re-linked it. Keeping the current link is not a new choice, so
     * {@code current} passes through unchanged (PR #340 review I-2).</p>
     */
    private Account resolveBankLeaf(BankAccountRequest.Ref ref, Account current) {
        if (ref == null) return null;
        if (ref.id() == null) throw new BusinessRuleViolationException("coaAccount.id is required");
        if (current != null && ref.id().equals(current.getId()) && inCurrentTenant(current.getTenantId())) {
            return current;
        }
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
        boolean fromProperty = false;
        if (bankAccount.getCoaAccount() == null) {
            // Both fallbacks are looked up, not caught: an exception out of the resolver
            // or AccountService marks this transaction rollback-only and the create then
            // fails at commit ("Transaction silently rolled back", sim4y S16-01's class).
            if (bankAccount.getProperty() != null) {
                bankAccount.setCoaAccount(resolver.resolveOrNull(AccountRole.BANK, bankAccount.getProperty().getId()));
                fromProperty = bankAccount.getCoaAccount() != null;
            }
            if (bankAccount.getCoaAccount() == null) {
                bankAccount.setCoaAccount(newOwnLeaf(bankAccount, false));
            }
        }
        // R2 ruling (b): the first bank account is the default one, and only one is.
        // #98 M4: counted in the database for this tenant, not by loading every row.
        UUID tenant = TenantContextHolder.getTenantId();
        Boolean anyOther = tenant == null ? Boolean.FALSE : jdbc.queryForObject(
                "select exists (select 1 from bank_accounts where tenant_id = :t and is_active)",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("t", tenant), Boolean.class);
        if (!Boolean.TRUE.equals(anyOther)) bankAccount.setDefault(true);
        BankAccount saved = repository.save(bankAccount);
        if (saved.isDefault()) clearOtherDefaults(saved);
        ownLeaf(saved, fromProperty);
        // …and it takes the properties' own bank leaves no bank account owns yet,
        // within its scope (#98 M2).
        ownedBankLeaf.attachUnownedPropertyLeaves(saved.getId());
        return saved;
    }

    /**
     * A new leaf of the bank account's own under A-02-02, named "<bank> - <last 4>",
     * scoped to its property when {@code scoped}. Null when the chart has no A-02-02.
     */
    private Account newOwnLeaf(BankAccount bankAccount, boolean scoped) {
        Account bankGroup = accountService.findAccountByCode("A-02-02").orElse(null);
        if (bankGroup == null) {
            log.warn("No Bank account group (A-02-02) for tenant; creating bank account without a ledger account");
            return null;
        }
        // accountNumber is optional on the entity, so this fallback has to
        // tolerate a null one rather than NPE the whole create.
        String accountNumber = bankAccount.getAccountNumber();
        String last4 = accountNumber == null || accountNumber.isBlank() ? null
                : accountNumber.length() > 4
                        ? accountNumber.substring(accountNumber.length() - 4) : accountNumber;
        String leafName = last4 == null ? bankAccount.getBankName() : bankAccount.getBankName() + " - " + last4;
        return accountService.createLeaf(leafName, bankGroup,
                scoped && bankAccount.getProperty() != null ? bankAccount.getProperty().getId() : null);
    }

    private String ownerLabel(UUID accountId) {
        return jdbc.queryForList("""
                select b.bank_name || ' ' || b.account_number from bank_account_ledgers l
                join bank_accounts b on b.id = l.bank_account_id and b.tenant_id = l.tenant_id
                where l.tenant_id = :t and l.account_id = :a""",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("t", TenantContextHolder.getTenantId())
                        .addValue("a", accountId), String.class).stream().findFirst().orElse("?");
    }

    /** R2 ruling (b): one default bank account per organisation. */
    private void clearOtherDefaults(BankAccount keep) {
        repository.flush();
        jdbc.update("update bank_accounts set is_default = false where tenant_id = :t and id <> :b and is_default",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("t", keep.getTenantId())
                        .addValue("b", keep.getId()));
    }

    /**
     * Finance-ops spec §3: a new bank account owns its ledger leaf (a leaf belongs
     * to at most one). #98 M1: when another bank account owns it — typically a
     * property's BANK leaf the operating bank took when the property was created —
     * it moves to this one if it has no postings yet (nothing reconciled or matched
     * can include it). A property's leaf that does have postings stays where it is;
     * a bank account defaulted from that property then gets a leaf of its own
     * instead of owning nothing ("one bank per building").
     */
    private void ownLeaf(BankAccount b, boolean fromProperty) {
        if (b.getCoaAccount() == null || b.getCoaAccount().getAccountSubType() != AccountSubType.BANK
                || b.getCoaAccount().isGroup()) return;
        repository.flush();
        OwnedBankLeaf.Claim c = ownedBankLeaf.claim(b.getId(), b.getCoaAccount().getId(), true);
        if (c == OwnedBankLeaf.Claim.OWNED_ELSEWHERE && fromProperty) {
            Account own = newOwnLeaf(b, true);
            if (own == null) return;
            b.setCoaAccount(own);
            repository.saveAndFlush(b);
            ownedBankLeaf.claim(b.getId(), own.getId(), false);
            log.info("Bank account {}: property {}'s bank leaf already has postings under another bank account; "
                    + "created its own leaf {}", b.getId(), b.getProperty().getId(), own.getCode());
        }
    }

    @Transactional
    public void deleteBankAccount(UUID id) {
        BankAccount b = getBankAccountById(id);
        var p = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("t", b.getTenantId()).addValue("b", id);
        Integer imports = jdbc.queryForObject(
                "select count(*) from bank_statement_imports where tenant_id = :t and bank_account_id = :b", p, Integer.class);
        if (imports != null && imports > 0) {
            throw new BusinessRuleViolationException("Statements have been imported into this bank account; deactivate it instead");
        }
        jdbc.update("delete from bank_statement_profiles where tenant_id = :t and bank_account_id = :b", p);
        jdbc.update("delete from bank_account_ledgers where tenant_id = :t and bank_account_id = :b", p);
        repository.deleteById(id);
    }
}
