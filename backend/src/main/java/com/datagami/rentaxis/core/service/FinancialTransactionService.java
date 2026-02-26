package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.ReportDTO;
import com.datagami.rentaxis.domain.entity.FinancialTransaction;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.repository.FinancialTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class FinancialTransactionService {

    private final FinancialTransactionRepository repository;

    public FinancialTransactionService(FinancialTransactionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public FinancialTransaction createTransaction(FinancialTransaction txn) {
        // Auto-resolve property from unit if unit is set
        if (txn.getUnit() != null && txn.getProperty() == null) {
            txn.setProperty(txn.getUnit().getProperty());
        }
        // Denormalize account fields
        if (txn.getAccount() != null) {
            txn.setAccountCode(txn.getAccount().getCode());
            txn.setAccountType(txn.getAccount().getAccountType());
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
            return repository.findByPropertyIdAndDateBetween(propertyId, startDate, endDate);
        }
        if (unitId != null) {
            return repository.findByUnitId(unitId);
        }
        if (propertyId != null) {
            return repository.findByPropertyId(propertyId);
        }
        if (accountType != null) {
            return repository.findByAccountType(accountType);
        }
        if (startDate != null && endDate != null) {
            return repository.findByDateBetween(startDate, endDate);
        }
        return repository.findAll();
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
            transactions = repository.findByDateBetween(startDate, endDate);
        } else {
            transactions = repository.findAll();
        }
        ReportDTO report = buildReport(transactions);
        report.setReportType("ORGANISATION");
        report.setReportName("Organisation Consolidated Report");
        report.setDateRange(formatDateRange(startDate, endDate));
        return report;
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
