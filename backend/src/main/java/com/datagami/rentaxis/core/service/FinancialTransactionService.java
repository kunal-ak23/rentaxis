package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.CreateSplitTransactionDTO;
import com.datagami.rentaxis.api.dto.ReportDTO;
import com.datagami.rentaxis.api.dto.TrialBalanceDTO;
import com.datagami.rentaxis.api.dto.VatReturnDTO;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.FinancialTransaction;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Staff;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.Vendor;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.FinancialTransactionRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.StaffRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.VendorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class FinancialTransactionService {

    private final FinancialTransactionRepository repository;
    private final AccountRepository accountRepository;
    private final UnitRepository unitRepository;
    private final PropertyRepository propertyRepository;
    private final VendorRepository vendorRepository;
    private final StaffRepository staffRepository;

    public FinancialTransactionService(FinancialTransactionRepository repository,
            AccountRepository accountRepository,
            UnitRepository unitRepository,
            PropertyRepository propertyRepository,
            VendorRepository vendorRepository,
            StaffRepository staffRepository) {
        this.repository = repository;
        this.accountRepository = accountRepository;
        this.unitRepository = unitRepository;
        this.propertyRepository = propertyRepository;
        this.vendorRepository = vendorRepository;
        this.staffRepository = staffRepository;
    }

    @Transactional
    public FinancialTransaction createTransaction(FinancialTransaction txn) {
        // Fetch full entities to avoid detached entity version errors
        if (txn.getUnit() != null && txn.getUnit().getId() != null) {
            Unit fullUnit = unitRepository.findById(txn.getUnit().getId())
                    .orElseThrow(() -> new RuntimeException("Unit not found: " + txn.getUnit().getId()));
            txn.setUnit(fullUnit);
            if (txn.getProperty() == null) {
                txn.setProperty(fullUnit.getProperty());
            }
        }
        if (txn.getProperty() != null && txn.getProperty().getId() != null && txn.getUnit() == null) {
            Property fullProperty = propertyRepository.findById(txn.getProperty().getId())
                    .orElseThrow(() -> new RuntimeException("Property not found: " + txn.getProperty().getId()));
            txn.setProperty(fullProperty);
        }
        if (txn.getAccount() != null && txn.getAccount().getId() != null) {
            Account fullAccount = accountRepository.findById(txn.getAccount().getId())
                    .orElseThrow(() -> new RuntimeException("Account not found: " + txn.getAccount().getId()));
            txn.setAccount(fullAccount);
            txn.setAccountCode(fullAccount.getCode());
            txn.setAccountType(fullAccount.getAccountType());
        }
        return repository.save(txn);
    }

    @Transactional(readOnly = true)
    public List<FinancialTransaction> getTransactions(UUID propertyId, UUID unitId,
            AccountType accountType,
            LocalDate startDate, LocalDate endDate) {
        // Apply filters in priority order
        if (unitId != null && startDate != null && endDate != null) {
            return repository.findByUnitIdAndDateBetween(unitId, startDate, endDate);
        }
        if (propertyId != null && startDate != null && endDate != null) {
            return repository.findByParentTransactionIsNullAndPropertyIdAndDateBetweenOrderByDateDesc(propertyId, startDate, endDate);
        }
        if (unitId != null) {
            return repository.findByUnitId(unitId);
        }
        if (propertyId != null) {
            return repository.findByParentTransactionIsNullAndPropertyIdOrderByDateDesc(propertyId);
        }
        if (accountType != null) {
            return repository.findByParentTransactionIsNullAndAccountTypeOrderByDateDesc(accountType);
        }
        if (startDate != null && endDate != null) {
            return repository.findByParentTransactionIsNullAndDateBetweenOrderByDateDesc(startDate, endDate);
        }
        return repository.findByParentTransactionIsNullOrderByDateDesc();
    }

    @Transactional(readOnly = true)
    public FinancialTransaction getTransactionById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));
    }

    /**
     * Property-level P&L report.
     * Includes all transactions tagged directly to this property + all child unit
     * transactions.
     */
    @Transactional(readOnly = true)
    public ReportDTO getPropertyReport(UUID propertyId, LocalDate startDate, LocalDate endDate) {
        List<FinancialTransaction> transactions;
        if (startDate != null && endDate != null) {
            transactions = repository.findByPropertyIdAndDateBetween(propertyId, startDate, endDate);
        } else {
            transactions = repository.findByPropertyId(propertyId);
        }
        ReportDTO report = buildReport(transactions);
        report.setReportType("PROPERTY");
        report.setDateRange(formatDateRange(startDate, endDate));
        return report;
    }

    /**
     * Unit-level ledger report.
     */
    @Transactional(readOnly = true)
    public ReportDTO getUnitReport(UUID unitId, LocalDate startDate, LocalDate endDate) {
        List<FinancialTransaction> transactions;
        if (startDate != null && endDate != null) {
            transactions = repository.findByUnitIdAndDateBetween(unitId, startDate, endDate);
        } else {
            transactions = repository.findByUnitId(unitId);
        }
        ReportDTO report = buildReport(transactions);
        report.setReportType("UNIT");
        report.setDateRange(formatDateRange(startDate, endDate));
        return report;
    }

    /**
     * Organisation-level consolidated P&L + balance sheet.
     */
    @Transactional(readOnly = true)
    public ReportDTO getOrganisationReport(LocalDate startDate, LocalDate endDate) {
        List<FinancialTransaction> transactions;
        if (startDate != null && endDate != null) {
            transactions = repository.findBySplitParentFalseAndDateBetweenOrderByDateDesc(startDate, endDate);
        } else {
            transactions = repository.findBySplitParentFalseOrderByDateDesc();
        }
        ReportDTO report = buildReport(transactions);
        report.setReportType("ORGANISATION");
        report.setReportName("Organisation Consolidated Report");
        report.setDateRange(formatDateRange(startDate, endDate));
        return report;
    }

    @Transactional(readOnly = true)
    public TrialBalanceDTO getTrialBalance(LocalDate startDate, LocalDate endDate) {
        List<FinancialTransaction> transactions;
        if (startDate != null && endDate != null) {
            transactions = repository.findBySplitParentFalseAndDateBetweenOrderByDateDesc(startDate, endDate);
        } else {
            transactions = repository.findBySplitParentFalseOrderByDateDesc();
        }

        Map<String, TrialBalanceDTO.TrialBalanceLine> lineMap = new LinkedHashMap<>();

        for (FinancialTransaction t : transactions) {
            String key = t.getAccountCode();
            TrialBalanceDTO.TrialBalanceLine line = lineMap.computeIfAbsent(key, k -> {
                TrialBalanceDTO.TrialBalanceLine l = new TrialBalanceDTO.TrialBalanceLine();
                l.setAccountCode(t.getAccountCode());
                l.setAccountName(t.getAccount() != null ? t.getAccount().getName() : "");
                l.setAccountType(t.getAccountType().name());
                return l;
            });
            line.setDebit(line.getDebit().add(t.getDebit()));
            line.setCredit(line.getCredit().add(t.getCredit()));
            line.setBalance(line.getDebit().subtract(line.getCredit()));
        }

        TrialBalanceDTO dto = new TrialBalanceDTO();
        dto.setLines(new ArrayList<>(lineMap.values()));
        dto.setTotalDebit(dto.getLines().stream()
                .map(TrialBalanceDTO.TrialBalanceLine::getDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        dto.setTotalCredit(dto.getLines().stream()
                .map(TrialBalanceDTO.TrialBalanceLine::getCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        dto.setDateRange(formatDateRange(startDate, endDate));
        return dto;
    }

    @Transactional(readOnly = true)
    public VatReturnDTO getVatReturn(LocalDate startDate, LocalDate endDate) {
        List<FinancialTransaction> transactions;
        if (startDate != null && endDate != null) {
            transactions = repository.findBySplitParentFalseAndDateBetweenOrderByDateDesc(startDate, endDate);
        } else {
            transactions = repository.findBySplitParentFalseOrderByDateDesc();
        }

        List<FinancialTransaction> vatTransactions = transactions.stream()
                .filter(FinancialTransaction::isVatApplicable)
                .toList();

        VatReturnDTO dto = new VatReturnDTO();
        dto.setPeriod(formatDateRange(startDate, endDate));

        List<VatReturnDTO.VatLine> salesLines = new ArrayList<>();
        List<VatReturnDTO.VatLine> purchaseLines = new ArrayList<>();

        for (FinancialTransaction t : vatTransactions) {
            VatReturnDTO.VatLine line = new VatReturnDTO.VatLine();
            line.setDescription(t.getDescription());
            line.setVatAmount(t.getVatAmount());

            if (t.getAccountType() == AccountType.INCOME) {
                line.setTaxableAmount(t.getCredit().subtract(t.getDebit()));
                salesLines.add(line);
                dto.setTotalOutputVat(dto.getTotalOutputVat().add(t.getVatAmount()));
                dto.setTotalTaxableSales(dto.getTotalTaxableSales().add(line.getTaxableAmount()));
            } else if (t.getAccountType() == AccountType.EXPENSE) {
                line.setTaxableAmount(t.getDebit().subtract(t.getCredit()));
                purchaseLines.add(line);
                dto.setTotalInputVat(dto.getTotalInputVat().add(t.getVatAmount()));
                dto.setTotalTaxablePurchases(dto.getTotalTaxablePurchases().add(line.getTaxableAmount()));
            }
        }

        dto.setSalesLines(salesLines);
        dto.setPurchaseLines(purchaseLines);
        dto.setNetVatPayable(dto.getTotalOutputVat().subtract(dto.getTotalInputVat()));
        return dto;
    }

    @Transactional
    public FinancialTransaction createSplitTransaction(CreateSplitTransactionDTO dto) {
        // Validate that exactly one of debit/credit is positive
        BigDecimal debit = dto.getDebit() != null ? dto.getDebit() : BigDecimal.ZERO;
        BigDecimal credit = dto.getCredit() != null ? dto.getCredit() : BigDecimal.ZERO;
        boolean hasDebit = debit.compareTo(BigDecimal.ZERO) > 0;
        boolean hasCredit = credit.compareTo(BigDecimal.ZERO) > 0;
        if (hasDebit == hasCredit) {
            throw new IllegalArgumentException("Exactly one of debit or credit must be positive for a split transaction");
        }
        BigDecimal parentAmount = hasDebit ? debit : credit;

        // Null guard and minimum split check
        if (dto.getSplits() == null || dto.getSplits().size() < 2) {
            throw new IllegalArgumentException("At least 2 split allocations are required");
        }

        // Validate splits sum
        BigDecimal splitTotal = dto.getSplits().stream()
                .map(CreateSplitTransactionDTO.SplitAllocation::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (splitTotal.compareTo(parentAmount) != 0) {
            throw new IllegalArgumentException("Split amounts (" + splitTotal + ") must equal transaction amount (" + parentAmount + ")");
        }

        for (CreateSplitTransactionDTO.SplitAllocation split : dto.getSplits()) {
            if (split.getPropertyId() == null && split.getUnitId() == null) {
                throw new IllegalArgumentException("Each split must specify a property or unit");
            }
        }

        // Resolve account
        Account account = accountRepository.findById(dto.getAccountId())
                .orElseThrow(() -> new RuntimeException("Account not found: " + dto.getAccountId()));

        // Compute parent VAT
        BigDecimal parentVatAmount = BigDecimal.ZERO;
        BigDecimal parentGrossAmount = BigDecimal.ZERO;
        BigDecimal parentNetAmount = parentAmount;
        if (dto.isVatApplicable() && dto.getVatRate() != null) {
            parentVatAmount = parentAmount.multiply(dto.getVatRate())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            parentGrossAmount = parentAmount.add(parentVatAmount);
        } else {
            parentGrossAmount = parentAmount;
        }

        // Create parent transaction (org-level, no property/unit)
        FinancialTransaction parent = new FinancialTransaction();
        parent.setDate(dto.getDate());
        parent.setDescription(dto.getDescription());
        parent.setAccount(account);
        parent.setAccountCode(account.getCode());
        parent.setAccountType(account.getAccountType());
        parent.setDebit(debit);
        parent.setCredit(credit);
        parent.setVatApplicable(dto.isVatApplicable());
        parent.setVatRate(dto.isVatApplicable() && dto.getVatRate() != null ? dto.getVatRate() : BigDecimal.ZERO);
        parent.setVatAmount(parentVatAmount);
        parent.setNetAmount(parentNetAmount);
        parent.setGrossAmount(parentGrossAmount);
        parent.setNotes(dto.getNotes());
        parent.setSplitParent(true);

        // Optional vendor/staff on parent
        if (dto.getVendorId() != null) {
            Vendor vendor = vendorRepository.findById(dto.getVendorId())
                    .orElseThrow(() -> new RuntimeException("Vendor not found: " + dto.getVendorId()));
            parent.setVendor(vendor);
        }
        if (dto.getStaffId() != null) {
            Staff staff = staffRepository.findById(dto.getStaffId())
                    .orElseThrow(() -> new RuntimeException("Staff not found: " + dto.getStaffId()));
            parent.setStaff(staff);
        }

        parent = repository.save(parent);

        // Create child transactions
        BigDecimal accumulatedChildVat = BigDecimal.ZERO;
        int splitIndex = 0;
        int lastIndex = dto.getSplits().size() - 1;

        for (CreateSplitTransactionDTO.SplitAllocation split : dto.getSplits()) {
            FinancialTransaction child = new FinancialTransaction();
            child.setParentTransaction(parent);
            child.setDate(dto.getDate());
            child.setDescription(dto.getDescription());
            child.setAccount(account);
            child.setAccountCode(account.getCode());
            child.setAccountType(account.getAccountType());

            if (hasDebit) {
                child.setDebit(split.getAmount());
                child.setCredit(BigDecimal.ZERO);
            } else {
                child.setCredit(split.getAmount());
                child.setDebit(BigDecimal.ZERO);
            }

            // Pro-rate VAT — last child absorbs rounding remainder
            if (dto.isVatApplicable() && parentAmount.compareTo(BigDecimal.ZERO) > 0 && parentVatAmount.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal childVat;
                if (splitIndex == lastIndex) {
                    // Last child absorbs rounding remainder
                    childVat = parentVatAmount.subtract(accumulatedChildVat);
                } else {
                    BigDecimal ratio = split.getAmount().divide(parentAmount, 10, RoundingMode.HALF_UP);
                    childVat = parentVatAmount.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
                    accumulatedChildVat = accumulatedChildVat.add(childVat);
                }
                child.setVatApplicable(true);
                child.setVatRate(dto.getVatRate());
                child.setVatAmount(childVat);
                child.setNetAmount(split.getAmount());
                child.setGrossAmount(split.getAmount().add(childVat));
            } else {
                child.setVatApplicable(false);
                child.setVatAmount(BigDecimal.ZERO);
                child.setNetAmount(split.getAmount());
                child.setGrossAmount(split.getAmount());
            }

            // Resolve property/unit — prefer unit (property auto-derives from it)
            if (split.getUnitId() != null) {
                Unit unit = unitRepository.findById(split.getUnitId())
                        .orElseThrow(() -> new RuntimeException("Unit not found: " + split.getUnitId()));
                child.setUnit(unit);
                child.setProperty(unit.getProperty());
            } else if (split.getPropertyId() != null) {
                Property property = propertyRepository.findById(split.getPropertyId())
                        .orElseThrow(() -> new RuntimeException("Property not found: " + split.getPropertyId()));
                child.setProperty(property);
            }

            child.setNotes(dto.getNotes());
            repository.save(child);
            splitIndex++;
        }

        return repository.findById(parent.getId()).orElse(parent);
    }

    @Transactional(readOnly = true)
    public FinancialTransaction getTransactionWithChildren(UUID id) {
        FinancialTransaction txn = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Transaction not found: " + id));
        if (txn.isSplitParent()) {
            txn.setSplitChildren(repository.findByParentTransaction_Id(id));
        }
        return txn;
    }

    @Transactional(readOnly = true)
    public List<FinancialTransaction> getVendorLedger(UUID vendorId, LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null) {
            return repository.findByVendorIdAndDateBetween(vendorId, startDate, endDate);
        }
        return repository.findByVendorId(vendorId);
    }

    private ReportDTO buildReport(List<FinancialTransaction> transactions) {
        ReportDTO report = new ReportDTO();

        // Group by account type
        Map<AccountType, List<FinancialTransaction>> byType = transactions.stream()
                .collect(Collectors.groupingBy(FinancialTransaction::getAccountType));

        // Income
        List<FinancialTransaction> incomeTxns = byType.getOrDefault(AccountType.INCOME, List.of());
        Map<String, BigDecimal> incomeBreakdown = new LinkedHashMap<>();
        BigDecimal rentalIncome = BigDecimal.ZERO;
        BigDecimal otherIncome = BigDecimal.ZERO;
        for (FinancialTransaction t : incomeTxns) {
            BigDecimal amount = t.getCredit().subtract(t.getDebit());
            incomeBreakdown.merge(t.getAccountCode() + " - " + (t.getAccount() != null ? t.getAccount().getName() : ""),
                    amount, BigDecimal::add);
            if (t.getAccountCode().startsWith("C-01-01")) {
                rentalIncome = rentalIncome.add(amount);
            } else {
                otherIncome = otherIncome.add(amount);
            }
        }
        report.setTotalRentalIncome(rentalIncome);
        report.setTotalOtherIncome(otherIncome);
        report.setTotalIncome(rentalIncome.add(otherIncome));
        report.setIncomeBreakdown(incomeBreakdown);

        // Expenses
        List<FinancialTransaction> expenseTxns = byType.getOrDefault(AccountType.EXPENSE, List.of());
        Map<String, BigDecimal> directExpBreak = new LinkedHashMap<>();
        Map<String, BigDecimal> indirectExpBreak = new LinkedHashMap<>();
        BigDecimal directExp = BigDecimal.ZERO;
        BigDecimal indirectExp = BigDecimal.ZERO;
        for (FinancialTransaction t : expenseTxns) {
            BigDecimal amount = t.getDebit().subtract(t.getCredit());
            String label = t.getAccountCode() + " - " + (t.getAccount() != null ? t.getAccount().getName() : "");
            if (t.getAccountCode().startsWith("D-01")) {
                directExpBreak.merge(label, amount, BigDecimal::add);
                directExp = directExp.add(amount);
            } else {
                indirectExpBreak.merge(label, amount, BigDecimal::add);
                indirectExp = indirectExp.add(amount);
            }
        }
        report.setTotalDirectExpenses(directExp);
        report.setTotalIndirectExpenses(indirectExp);
        report.setTotalExpenses(directExp.add(indirectExp));
        report.setDirectExpenseBreakdown(directExpBreak);
        report.setIndirectExpenseBreakdown(indirectExpBreak);

        // NOI and Net Profit
        report.setNetOperatingIncome(report.getTotalIncome().subtract(directExp));
        report.setNetProfit(report.getNetOperatingIncome().subtract(indirectExp));

        // Balance sheet items
        List<FinancialTransaction> assetTxns = byType.getOrDefault(AccountType.ASSET, List.of());
        BigDecimal totalAssets = assetTxns.stream()
                .map(t -> t.getDebit().subtract(t.getCredit()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        report.setTotalAssets(totalAssets);

        List<FinancialTransaction> liabilityTxns = byType.getOrDefault(AccountType.LIABILITY, List.of());
        BigDecimal totalLiabilities = liabilityTxns.stream()
                .map(t -> t.getCredit().subtract(t.getDebit()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        report.setTotalLiabilities(totalLiabilities);

        List<FinancialTransaction> equityTxns = byType.getOrDefault(AccountType.EQUITY, List.of());
        BigDecimal totalEquity = equityTxns.stream()
                .map(t -> t.getCredit().subtract(t.getDebit()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        report.setTotalEquity(totalEquity);

        // Specific line items for property reports
        report.setOutstandingRentReceivables(
                assetTxns.stream()
                        .filter(t -> t.getAccountCode().equals("A-02-01"))
                        .map(t -> t.getDebit().subtract(t.getCredit()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        report.setSecurityDepositsHeld(
                liabilityTxns.stream()
                        .filter(t -> t.getAccountCode().equals("B-01-02"))
                        .map(t -> t.getCredit().subtract(t.getDebit()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        report.setPdcReceivable(
                assetTxns.stream()
                        .filter(t -> t.getAccountCode().equals("A-02-03"))
                        .map(t -> t.getDebit().subtract(t.getCredit()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        report.setPdcPayable(
                liabilityTxns.stream()
                        .filter(t -> t.getAccountCode().equals("B-02"))
                        .map(t -> t.getCredit().subtract(t.getDebit()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        report.setAdvanceRentBalance(
                liabilityTxns.stream()
                        .filter(t -> t.getAccountCode().equals("B-01-01"))
                        .map(t -> t.getCredit().subtract(t.getDebit()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add));

        return report;
    }

    private String formatDateRange(LocalDate start, LocalDate end) {
        if (start != null && end != null) {
            return start.toString() + " to " + end.toString();
        }
        return "All time";
    }
}
