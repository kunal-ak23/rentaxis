package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.TenantFiscalSettings;
import com.datagami.rentaxis.domain.entity.enums.AccountSubType;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.TenantFiscalSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AccountService {

    private static final long FIRST_LEAF_CODE = 100001L;

    private final AccountRepository repository;
    private final TenantFiscalSettingsRepository fiscalRepo;

    public AccountService(AccountRepository repository, TenantFiscalSettingsRepository fiscalRepo) {
        this.repository = repository;
        this.fiscalRepo = fiscalRepo;
    }

    @Transactional(readOnly = true)
    public List<Account> getAllAccounts() {
        return repository.findAll();
    }

    /** Root accounts (no parent). Children are fetched per node by the web via parentId. */
    @Transactional(readOnly = true)
    public List<Account> getTree() {
        return repository.findByParentIsNullOrderByDisplayOrderAscCodeAsc();
    }

    @Transactional(readOnly = true)
    public List<Account> getChildren(UUID parentId) {
        return repository.findByParent_IdOrderByDisplayOrderAscCodeAsc(parentId);
    }

    @Transactional(readOnly = true)
    public List<Account> getAccountsByType(AccountType type) {
        return repository.findByAccountType(type);
    }

    @Transactional(readOnly = true)
    public Account getAccountByCode(String code) {
        return repository.findByCode(code)
                .orElseThrow(() -> new NotFoundException("Account not found with code: " + code));
    }

    @Transactional(readOnly = true)
    public Account getAccountById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Account not found"));
    }

    @Transactional
    public Account createAccount(Account account) {
        if (account.getParent() != null) {
            Account parent = getAccountById(account.getParent().getId());
            if (!parent.isGroup()) {
                throw new BusinessRuleViolationException("Parent account must be a group account");
            }
            account.setParent(parent);
            if (account.getAccountType() == null) {
                account.setAccountType(parent.getAccountType());
            }
        }
        if (account.getCode() == null || account.getCode().isBlank()) {
            account.setCode(nextLeafCode());
        }
        if (account.getName() == null && account.getNameEn() != null) {
            account.setName(account.getNameEn());
        }
        return repository.save(account);
    }

    /**
     * Creates a non-group, non-system leaf under {@code parent} with the next numeric
     * code. Used by the property template and vendor/bank silent accounts.
     */
    @Transactional
    public Account createLeaf(String name, Account parent, UUID propertyId) {
        Account a = new Account();
        a.setCode(nextLeafCode());
        a.setName(name);
        a.setNameEn(name);
        a.setAccountType(parent.getAccountType());
        a.setAccountSubType(parent.getAccountSubType());
        a.setParent(parent);
        a.setGroup(false);
        a.setSystem(false);
        if (propertyId != null) {
            Property p = new Property();
            p.setId(propertyId);
            a.setProperty(p);
        }
        return repository.save(a);
    }

    /**
     * Hands out the next numeric account code for the tenant. Locks the tenant's
     * fiscal-settings row so two concurrent property creations cannot collide.
     * Initialised from the highest numeric code already present (imported PACT
     * codes are 5-6 digits) or 100001 when there are none.
     */
    @Transactional
    public String nextLeafCode() {
        UUID tenantId = TenantContextHolder.getTenantId();
        TenantFiscalSettings s = fiscalRepo.findForUpdate(tenantId).orElseGet(() -> {
            TenantFiscalSettings n = new TenantFiscalSettings();
            n.setTenantId(tenantId);
            fiscalRepo.saveAndFlush(n);
            return fiscalRepo.findForUpdate(tenantId).orElseThrow();
        });
        Long max = repository.findMaxNumericCode(tenantId);
        if (s.getNextAccountCode() == null) {
            s.setNextAccountCode(max == null ? FIRST_LEAF_CODE : max + 1);
        } else if (max != null && max >= s.getNextAccountCode()) {
            // An import may have added higher numeric codes since we last handed one out.
            s.setNextAccountCode(max + 1);
        }
        long code = s.getNextAccountCode();
        s.setNextAccountCode(code + 1);
        fiscalRepo.save(s);
        return Long.toString(code);
    }

    @Transactional
    public Account updateAccount(UUID id, Account updates) {
        Account existing = getAccountById(id);
        if (existing.isSystem()) {
            throw new BusinessRuleViolationException("System accounts cannot be modified");
        }
        existing.setName(updates.getName());
        existing.setNameEn(updates.getNameEn());
        existing.setNameAr(updates.getNameAr());
        existing.setAlias(updates.getAlias());
        existing.setDescription(updates.getDescription());
        existing.setAccountSubType(updates.getAccountSubType());
        existing.setActive(updates.isActive());
        existing.setDisplayOrder(updates.getDisplayOrder());
        existing.setProperty(updates.getProperty());
        return repository.save(existing);
    }

    @Transactional
    public void deleteAccount(UUID id) {
        Account account = getAccountById(id);
        if (account.isSystem()) {
            throw new BusinessRuleViolationException("System accounts cannot be deleted");
        }
        if (repository.existsByParent_Id(account.getId())) {
            throw new BusinessRuleViolationException("Cannot delete account with child accounts");
        }
        repository.delete(account);
    }

    /**
     * Seeds the default Chart of Accounts for a new tenant (PACT-shaped groups).
     * Only seeds if no accounts exist for the current tenant. Idempotent.
     *
     * <p>The tree is built in memory in a LinkedHashMap so every parent is
     * persisted before its children and the parent_id foreign key holds on the
     * very first insert.
     */
    @Transactional
    public List<Account> seedDefaultAccounts() {
        List<Account> existing = repository.findAll();
        if (!existing.isEmpty()) {
            return existing;
        }
        Map<String, Account> byCode = new LinkedHashMap<>();
        // (code, nameEn, nameAr, type, subType, parentCode, description, isGroup)
        seed(byCode, "A", "Assets", "الأصول", AccountType.ASSET, AccountSubType.OTHER_ASSET, null, "All assets", true);
        seed(byCode, "A-01", "Fixed Assets", "الأصول الثابتة", AccountType.ASSET, AccountSubType.FIXED_ASSET, "A", "Office equipment, AC units, other capital items", true);
        seed(byCode, "A-02", "Current Assets", "الأصول المتداولة", AccountType.ASSET, AccountSubType.OTHER_ASSET, "A", "Short-term assets", true);
        seed(byCode, "A-02-01", "Rental Receivable A/c", "إيجارات مستحقة", AccountType.ASSET, AccountSubType.RECEIVABLE, "A-02", "Rent due from tenants, one leaf per property", true);
        seed(byCode, "A-02-02", "Bank", "الحسابات البنكية", AccountType.ASSET, AccountSubType.BANK, "A-02", "Bank accounts, one leaf per property", true);
        seed(byCode, "A-02-03", "PDCs", "شيكات مؤجلة مستحقة", AccountType.ASSET, AccountSubType.PDC_RECEIVABLE, "A-02", "Post-dated cheques held from tenants, one leaf per property", true);
        seed(byCode, "A-02-04", "Input VAT", "ضريبة المدخلات", AccountType.ASSET, AccountSubType.OTHER_ASSET, "A-02", "VAT paid on purchases", true);
        seed(byCode, "A-02-04-001", "Input VAT on Purchases", "ضريبة المدخلات على المشتريات", AccountType.ASSET, AccountSubType.OTHER_ASSET, "A-02-04", null, false);
        seed(byCode, "A-02-05", "Cash Group", "النقد", AccountType.ASSET, AccountSubType.CASH, "A-02", "Cash in hand and petty cash", true);
        seed(byCode, "A-02-05-001", "Cash Account", "حساب النقد", AccountType.ASSET, AccountSubType.CASH, "A-02-05", null, false);

        seed(byCode, "B", "Liability", "الالتزامات", AccountType.LIABILITY, AccountSubType.OTHER_LIABILITY, null, "All liabilities", true);
        seed(byCode, "B-01", "Current Liability", "الالتزامات المتداولة", AccountType.LIABILITY, AccountSubType.OTHER_LIABILITY, "B", null, true);
        seed(byCode, "B-01-01", "Advance Rent Group", "الإيجار المقدم", AccountType.LIABILITY, AccountSubType.ADVANCE, "B-01", "Unearned rent, one leaf per property", true);
        seed(byCode, "B-01-02", "Security Deposits", "التأمينات", AccountType.LIABILITY, AccountSubType.DEPOSIT_HELD, "B-01", "Deposits held, one leaf per property", true);
        seed(byCode, "B-01-03", "Output VAT", "ضريبة المخرجات", AccountType.LIABILITY, AccountSubType.OTHER_LIABILITY, "B-01", null, true);
        seed(byCode, "B-01-03-001", "Output VAT on Sales", "ضريبة المخرجات على المبيعات", AccountType.LIABILITY, AccountSubType.OTHER_LIABILITY, "B-01-03", null, false);
        seed(byCode, "B-01-04", "Vendors", "الموردون", AccountType.LIABILITY, AccountSubType.PAYABLE, "B-01", "One leaf per vendor, created with the vendor", true);
        seed(byCode, "B-02", "PDC Payables", "شيكات مؤجلة مستحقة الدفع", AccountType.LIABILITY, AccountSubType.OTHER_LIABILITY, "B", null, true);

        seed(byCode, "C", "Income", "الإيرادات", AccountType.INCOME, AccountSubType.OTHER_INCOME, null, "All income", true);
        seed(byCode, "C-01", "Direct Income", "الإيرادات المباشرة", AccountType.INCOME, AccountSubType.OTHER_INCOME, "C", null, true);
        seed(byCode, "C-01-01", "Rental Income Group", "إيرادات الإيجار", AccountType.INCOME, AccountSubType.RENTAL_INCOME, "C-01", "Rental income and admin fees, one leaf per property", true);
        seed(byCode, "C-01-02", "Other Income", "إيرادات أخرى", AccountType.INCOME, AccountSubType.OTHER_INCOME, "C-01", "Penalties, maintenance charges, forfeitures", true);
        seed(byCode, "C-01-02-001", "Amount Forfeited", "مبالغ مصادرة", AccountType.INCOME, AccountSubType.OTHER_INCOME, "C-01-02", null, false);
        seed(byCode, "C-02", "Indirect Income", "إيرادات غير مباشرة", AccountType.INCOME, AccountSubType.OTHER_INCOME, "C", null, true);

        seed(byCode, "D", "Expense", "المصروفات", AccountType.EXPENSE, AccountSubType.OTHER_EXPENSE, null, "All expenses", true);
        seed(byCode, "D-01", "Direct Expense", "المصروفات المباشرة", AccountType.EXPENSE, AccountSubType.OTHER_EXPENSE, "D", "Building running costs, one leaf per property per category", true);
        seed(byCode, "D-02", "Indirect Expense", "المصروفات غير المباشرة", AccountType.EXPENSE, AccountSubType.OTHER_EXPENSE, "D", null, true);
        seed(byCode, "D-02-001", "Rounding Off", "فروق التقريب", AccountType.EXPENSE, AccountSubType.OTHER_EXPENSE, "D-02", null, false);
        seed(byCode, "D-02-002", "Discount Allowed", "خصم مسموح", AccountType.EXPENSE, AccountSubType.OTHER_EXPENSE, "D-02", null, false);
        seed(byCode, "D-02-003", "Bank Charges", "رسوم بنكية", AccountType.EXPENSE, AccountSubType.OTHER_EXPENSE, "D-02", null, false);

        seed(byCode, "F", "Equity", "حقوق الملكية", AccountType.EQUITY, AccountSubType.CAPITAL, null, null, true);
        seed(byCode, "F-01", "Capital Account", "حساب رأس المال", AccountType.EQUITY, AccountSubType.CAPITAL, "F", "Owner's capital account", false);
        seed(byCode, "F-02", "Opening Balance Difference", "فرق الأرصدة الافتتاحية", AccountType.EQUITY, AccountSubType.CAPITAL, "F", "Suspense for an unbalanced opening-balance import; clear with a JV", false);

        return repository.saveAll(byCode.values());
    }

    private void seed(Map<String, Account> byCode, String code, String nameEn, String nameAr,
                      AccountType type, AccountSubType subType, String parentCode,
                      String description, boolean isGroup) {
        Account a = new Account();
        a.setCode(code);
        a.setName(nameEn);
        a.setNameEn(nameEn);
        a.setNameAr(nameAr);
        a.setAccountType(type);
        a.setAccountSubType(subType);
        a.setDescription(description);
        a.setSystem(true);
        a.setGroup(isGroup);
        if (parentCode != null) {
            Account parent = byCode.get(parentCode);
            if (parent == null) throw new IllegalStateException("Seed parent missing: " + parentCode);
            a.setParent(parent);
        }
        byCode.put(code, a);
    }
}
