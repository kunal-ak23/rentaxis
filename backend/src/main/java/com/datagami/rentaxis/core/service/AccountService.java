package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.Account;
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
                makeAccount("A-01", "Fixed Assets", AccountType.ASSET, null,
                        "Office equipment, AC units, other capital items"),
                makeAccount("A-02", "Current Assets", AccountType.ASSET, null, "Short-term assets"),
                makeAccount("A-02-01", "Rental Receivable", AccountType.ASSET, "A-02",
                        "Rent due from tenants, tracked per property"),
                makeAccount("A-02-02", "Bank Accounts", AccountType.ASSET, "A-02",
                        "Emirates Islamic Bank accounts per property"),
                makeAccount("A-02-03", "PDCs Receivable", AccountType.ASSET, "A-02",
                        "Post-Dated Cheques held from tenants"),
                makeAccount("A-02-04", "Input VAT", AccountType.ASSET, "A-02",
                        "VAT on residential and commercial purchases"),
                makeAccount("A-02-05", "Cash Group", AccountType.ASSET, "A-02",
                        "Petty cash, cash in hand, security deposits"),

                // ═══ LIABILITIES ═══
                makeAccount("B-01", "Current Liabilities", AccountType.LIABILITY, null, "Short-term liabilities"),
                makeAccount("B-01-01", "Advance Rent", AccountType.LIABILITY, "B-01", "Tenant advance rent balances"),
                makeAccount("B-01-02", "Security Deposits", AccountType.LIABILITY, "B-01",
                        "Refundable security deposits held from tenants"),
                makeAccount("B-01-03", "Output VAT", AccountType.LIABILITY, "B-01", "VAT collected on sales/rentals"),
                makeAccount("B-01-04", "Vendors / Creditors", AccountType.LIABILITY, "B-01",
                        "Supplier and contractor payables"),
                makeAccount("B-02", "PDC Payables", AccountType.LIABILITY, null,
                        "Post-dated cheques issued to suppliers/owners"),

                // ═══ INCOME ═══
                makeAccount("C-01", "Direct Income", AccountType.INCOME, null, "Primary income sources"),
                makeAccount("C-01-01", "Rental Income", AccountType.INCOME, "C-01",
                        "Monthly rental income + admin charges per property"),
                makeAccount("C-01-02", "Other Income", AccountType.INCOME, "C-01",
                        "Cooling charges, telecom tower rent, car washing, maintenance charge income"),
                makeAccount("C-02", "Indirect Income", AccountType.INCOME, null,
                        "Non-refundable bookings, store/washing income"),

                // ═══ EXPENSES ═══
                makeAccount("D-01", "Direct Expense", AccountType.EXPENSE, null,
                        "Water & electricity, property-level operating costs"),
                makeAccount("D-01-01", "Security Charges", AccountType.EXPENSE, "D-01", "Property security charges"),
                makeAccount("D-01-02", "Waste Collection", AccountType.EXPENSE, "D-01",
                        "Waste collection per property"),
                makeAccount("D-01-03", "Repair & Maintenance", AccountType.EXPENSE, "D-01",
                        "Building repair and maintenance"),
                makeAccount("D-01-04", "Building Cleaning", AccountType.EXPENSE, "D-01", "Building cleaning services"),
                makeAccount("D-01-05", "Pest Control AMC", AccountType.EXPENSE, "D-01",
                        "Pest control annual maintenance contract"),
                makeAccount("D-01-06", "Pool Maintenance", AccountType.EXPENSE, "D-01", "Swimming pool maintenance"),
                makeAccount("D-01-07", "Lift/Elevator AMC", AccountType.EXPENSE, "D-01", "Lift and elevator AMC"),
                makeAccount("D-01-08", "Fire Safety AMC", AccountType.EXPENSE, "D-01",
                        "Fire alarm & fire fighting AMC"),
                makeAccount("D-01-09", "Water Tank Cleaning", AccountType.EXPENSE, "D-01", "Water tank cleaning AMC"),
                makeAccount("D-01-10", "DEWA", AccountType.EXPENSE, "D-01", "Water & electricity (DEWA)"),
                makeAccount("D-01-11", "Insurance", AccountType.EXPENSE, "D-01", "Building insurance"),
                makeAccount("D-01-12", "Community Fees", AccountType.EXPENSE, "D-01", "Community fees"),
                makeAccount("D-01-13", "Building Valuation", AccountType.EXPENSE, "D-01", "Building valuation"),
                makeAccount("D-01-14", "Property Staff Salaries", AccountType.EXPENSE, "D-01",
                        "Staff salaries per property"),
                makeAccount("D-01-15", "Interest on Loans", AccountType.EXPENSE, "D-01", "Interest on property loans"),
                makeAccount("D-02", "Indirect Expense", AccountType.EXPENSE, null,
                        "Staff salaries, bank charges, general office expenses"),

                // ═══ EQUITY ═══
                makeAccount("F-01", "Capital Account", AccountType.EQUITY, null, "Owner's capital account"));

        List<Account> saved = repository.saveAll(defaults);

        // Seed default account mappings now that accounts exist
        accountMappingService.seedDefaults();

        return saved;
    }

    private Account makeAccount(String code, String name, AccountType type, String parentCode, String description) {
        Account a = new Account();
        a.setCode(code);
        a.setName(name);
        a.setAccountType(type);
        a.setParentCode(parentCode);
        a.setDescription(description);
        a.setSystem(true);
        return a;
    }
}
