package com.datagami.rentaxis.api.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Map;

@Getter
@Setter
public class ReportDTO {

    private String reportType; // ORGANISATION or PROPERTY or UNIT
    private String reportName;
    private String dateRange;

    // Income section
    private BigDecimal totalRentalIncome = BigDecimal.ZERO;
    private BigDecimal totalOtherIncome = BigDecimal.ZERO;
    private BigDecimal totalIncome = BigDecimal.ZERO;

    // Expense section
    private BigDecimal totalDirectExpenses = BigDecimal.ZERO;
    private BigDecimal totalIndirectExpenses = BigDecimal.ZERO;
    private BigDecimal totalExpenses = BigDecimal.ZERO;

    // Detailed breakdowns
    private Map<String, BigDecimal> incomeBreakdown;
    private Map<String, BigDecimal> directExpenseBreakdown;
    private Map<String, BigDecimal> indirectExpenseBreakdown;

    // Calculated
    private BigDecimal netOperatingIncome = BigDecimal.ZERO; // Total Income - Direct Expenses
    private BigDecimal netProfit = BigDecimal.ZERO; // NOI - Indirect Expenses

    // Balance sheet (organisation report only)
    private BigDecimal totalAssets = BigDecimal.ZERO;
    private BigDecimal totalLiabilities = BigDecimal.ZERO;
    private BigDecimal totalEquity = BigDecimal.ZERO;

    // Property-specific
    private BigDecimal outstandingRentReceivables = BigDecimal.ZERO;
    private BigDecimal securityDepositsHeld = BigDecimal.ZERO;
    private BigDecimal pdcReceivable = BigDecimal.ZERO;
    private BigDecimal pdcPayable = BigDecimal.ZERO;
    private BigDecimal advanceRentBalance = BigDecimal.ZERO;
}
