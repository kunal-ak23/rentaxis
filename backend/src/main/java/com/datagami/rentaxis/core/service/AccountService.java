package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.enums.AccountSubType;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository repository;
    private final AccountMappingService accountMappingService;

    public AccountService(AccountRepository repository, AccountMappingService accountMappingService) {
        this.repository = repository;
        this.accountMappingService = accountMappingService;
    }

    @Transactional(readOnly = true)
    public List<Account> getAllAccounts() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Account> getAccountsByType(AccountType type) {
        return repository.findByAccountType(type);
    }

    @Transactional(readOnly = true)
    public Account getAccountByCode(String code) {
        return repository.findByCode(code)
                .orElseThrow(() -> new RuntimeException("Account not found with code: " + code));
    }

    @Transactional(readOnly = true)
    public Account getAccountById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Account not found"));
    }

    @Transactional
    public Account createAccount(Account account) {
        return repository.save(account);
    }

    /**
     * Seeds the default Chart of Accounts per the finance spec.
     * Only seeds if no accounts exist for the current tenant.
     */
    @Transactional
    public List<Account> seedDefaultAccounts() {
        List<Account> existing = repository.findAll();
        if (!existing.isEmpty()) {
            return existing;
        }

        List<Account> defaults = List.of(
                // ═══ ASSETS ═══
                makeAccount("A-01", "Fixed Assets", "الأصول الثابتة",
                        AccountType.ASSET, AccountSubType.FIXED_ASSET,
                        null, "Office equipment, AC units, other capital items", true, 1),
                makeAccount("A-02", "Current Assets", "الأصول المتداولة",
                        AccountType.ASSET, AccountSubType.OTHER_ASSET,
                        null, "Short-term assets", true, 1),
                makeAccount("A-02-01", "Rental Receivable", "إيجارات مستحقة",
                        AccountType.ASSET, AccountSubType.RECEIVABLE,
                        "A-02", "Rent due from tenants, tracked per property", false, 2),
                makeAccount("A-02-02", "Bank Accounts", "الحسابات البنكية",
                        AccountType.ASSET, AccountSubType.BANK,
                        "A-02", "Emirates Islamic Bank accounts per property", true, 2),
                makeAccount("A-02-03", "PDCs Receivable", "شيكات مؤجلة مستحقة",
                        AccountType.ASSET, AccountSubType.PDC_RECEIVABLE,
                        "A-02", "Post-Dated Cheques held from tenants", false, 2),
                makeAccount("A-02-04", "Input VAT", "ضريبة القيمة المضافة المدخلة",
                        AccountType.ASSET, AccountSubType.OTHER_ASSET,
                        "A-02", "VAT on residential and commercial purchases", false, 2),
                makeAccount("A-02-05", "Cash Group", "النقد",
                        AccountType.ASSET, AccountSubType.CASH,
                        "A-02", "Petty cash, cash in hand, security deposits", true, 2),

                // ═══ LIABILITIES ═══
                makeAccount("B-01", "Current Liabilities", "الالتزامات المتداولة",
                        AccountType.LIABILITY, AccountSubType.OTHER_LIABILITY,
                        null, "Short-term liabilities", true, 1),
                makeAccount("B-01-01", "Advance Rent", "إيجار مقدم",
                        AccountType.LIABILITY, AccountSubType.ADVANCE,
                        "B-01", "Tenant advance rent balances", false, 2),
                makeAccount("B-01-02", "Security Deposits", "مبالغ التأمين",
                        AccountType.LIABILITY, AccountSubType.DEPOSIT_HELD,
                        "B-01", "Refundable security deposits held from tenants", false, 2),
                makeAccount("B-01-03", "Output VAT", "ضريبة القيمة المضافة المخرجة",
                        AccountType.LIABILITY, AccountSubType.OTHER_LIABILITY,
                        "B-01", "VAT collected on sales/rentals", false, 2),
                makeAccount("B-01-04", "Vendors / Creditors", "الموردون / الدائنون",
                        AccountType.LIABILITY, AccountSubType.PAYABLE,
                        "B-01", "Supplier and contractor payables", true, 2),
                makeAccount("B-02", "PDC Payables", "شيكات مؤجلة مستحقة الدفع",
                        AccountType.LIABILITY, AccountSubType.PDC_PAYABLE,
                        null, "Post-dated cheques issued to suppliers/owners", false, 1),

                // ═══ INCOME ═══
                makeAccount("C-01", "Direct Income", "الدخل المباشر",
                        AccountType.INCOME, AccountSubType.RENTAL_INCOME,
                        null, "Primary income sources", true, 1),
                makeAccount("C-01-01", "Rental Income", "دخل الإيجار",
                        AccountType.INCOME, AccountSubType.RENTAL_INCOME,
                        "C-01", "Monthly rental income + admin charges per property", false, 2),
                makeAccount("C-01-02", "Other Income", "دخل آخر",
                        AccountType.INCOME, AccountSubType.OTHER_INCOME,
                        "C-01", "Cooling charges, telecom tower rent, car washing, maintenance charge income", false, 2),
                makeAccount("C-02", "Indirect Income", "الدخل غير المباشر",
                        AccountType.INCOME, AccountSubType.OTHER_INCOME,
                        null, "Non-refundable bookings, store/washing income", true, 1),

                // ═══ EXPENSES ═══
                makeAccount("D-01", "Direct Expense", "المصاريف المباشرة",
                        AccountType.EXPENSE, AccountSubType.DIRECT_EXPENSE,
                        null, "Water & electricity, property-level operating costs", true, 1),
                makeAccount("D-01-01", "Security Charges", "رسوم الأمن",
                        AccountType.EXPENSE, AccountSubType.DIRECT_EXPENSE,
                        "D-01", "Property security charges", false, 2),
                makeAccount("D-01-02", "Waste Collection", "جمع النفايات",
                        AccountType.EXPENSE, AccountSubType.DIRECT_EXPENSE,
                        "D-01", "Waste collection per property", false, 2),
                makeAccount("D-01-03", "Repair & Maintenance", "الصيانة والإصلاح",
                        AccountType.EXPENSE, AccountSubType.DIRECT_EXPENSE,
                        "D-01", "Building repair and maintenance", false, 2),
                makeAccount("D-01-04", "Building Cleaning", "تنظيف المبنى",
                        AccountType.EXPENSE, AccountSubType.DIRECT_EXPENSE,
                        "D-01", "Building cleaning services", false, 2),
                makeAccount("D-01-05", "Pest Control AMC", "مكافحة الحشرات",
                        AccountType.EXPENSE, AccountSubType.DIRECT_EXPENSE,
                        "D-01", "Pest control annual maintenance contract", false, 2),
                makeAccount("D-01-06", "Pool Maintenance", "صيانة المسبح",
                        AccountType.EXPENSE, AccountSubType.DIRECT_EXPENSE,
                        "D-01", "Swimming pool maintenance", false, 2),
                makeAccount("D-01-07", "Lift/Elevator AMC", "صيانة المصاعد",
                        AccountType.EXPENSE, AccountSubType.DIRECT_EXPENSE,
                        "D-01", "Lift and elevator AMC", false, 2),
                makeAccount("D-01-08", "Fire Safety AMC", "السلامة من الحريق",
                        AccountType.EXPENSE, AccountSubType.DIRECT_EXPENSE,
                        "D-01", "Fire alarm & fire fighting AMC", false, 2),
                makeAccount("D-01-09", "Water Tank Cleaning", "تنظيف خزانات المياه",
                        AccountType.EXPENSE, AccountSubType.DIRECT_EXPENSE,
                        "D-01", "Water tank cleaning AMC", false, 2),
                makeAccount("D-01-10", "DEWA", "ديوا",
                        AccountType.EXPENSE, AccountSubType.DIRECT_EXPENSE,
                        "D-01", "Water & electricity (DEWA)", false, 2),
                makeAccount("D-01-11", "Insurance", "التأمين",
                        AccountType.EXPENSE, AccountSubType.DIRECT_EXPENSE,
                        "D-01", "Building insurance", false, 2),
                makeAccount("D-01-12", "Community Fees", "رسوم المجتمع",
                        AccountType.EXPENSE, AccountSubType.DIRECT_EXPENSE,
                        "D-01", "Community fees", false, 2),
                makeAccount("D-01-13", "Building Valuation", "تقييم المبنى",
                        AccountType.EXPENSE, AccountSubType.DIRECT_EXPENSE,
                        "D-01", "Building valuation", false, 2),
                makeAccount("D-01-14", "Property Staff Salaries", "رواتب موظفي العقار",
                        AccountType.EXPENSE, AccountSubType.SALARY_EXPENSE,
                        "D-01", "Staff salaries per property", false, 2),
                makeAccount("D-01-15", "Interest on Loans", "فوائد القروض",
                        AccountType.EXPENSE, AccountSubType.DIRECT_EXPENSE,
                        "D-01", "Interest on property loans", false, 2),
                makeAccount("D-02", "Indirect Expense", "المصاريف غير المباشرة",
                        AccountType.EXPENSE, AccountSubType.INDIRECT_EXPENSE,
                        null, "Staff salaries, bank charges, general office expenses", true, 1),

                // ═══ EQUITY ═══
                makeAccount("F-01", "Capital Account", "حساب رأس المال",
                        AccountType.EQUITY, AccountSubType.CAPITAL,
                        null, "Owner's capital account", false, 1));

        List<Account> saved = repository.saveAll(defaults);

        // Seed default account mappings now that accounts exist
        accountMappingService.seedDefaults();

        return saved;
    }

    @Transactional
    public Account updateAccount(UUID id, Account updates) {
        Account existing = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Account not found"));
        if (existing.isSystem()) {
            throw new RuntimeException("System accounts cannot be modified");
        }
        existing.setName(updates.getName());
        existing.setNameEn(updates.getNameEn());
        existing.setNameAr(updates.getNameAr());
        existing.setDescription(updates.getDescription());
        existing.setAccountSubType(updates.getAccountSubType());
        existing.setActive(updates.isActive());
        existing.setDisplayOrder(updates.getDisplayOrder());
        return repository.save(existing);
    }

    @Transactional
    public void deleteAccount(UUID id) {
        Account account = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Account not found"));
        if (account.isSystem()) {
            throw new RuntimeException("System accounts cannot be deleted");
        }
        if (repository.existsByParentCode(account.getCode())) {
            throw new RuntimeException("Cannot delete account with child accounts");
        }
        repository.delete(account);
    }

    private Account makeAccount(String code, String nameEn, String nameAr,
            AccountType type, AccountSubType subType,
            String parentCode, String description,
            boolean isGroup, int hierarchyLevel) {
        Account a = new Account();
        a.setCode(code);
        a.setName(nameEn);
        a.setNameEn(nameEn);
        a.setNameAr(nameAr);
        a.setAccountType(type);
        a.setAccountSubType(subType);
        a.setParentCode(parentCode);
        a.setDescription(description);
        a.setSystem(true);
        a.setGroup(isGroup);
        a.setHierarchyLevel(hierarchyLevel);
        return a;
    }
}
