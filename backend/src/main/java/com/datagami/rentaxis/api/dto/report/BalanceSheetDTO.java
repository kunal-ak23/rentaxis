package com.datagami.rentaxis.api.dto.report;

import com.datagami.rentaxis.api.dto.report.PropertyPnlDTO.Amount;
import com.datagami.rentaxis.api.dto.report.PropertyPnlDTO.Column;
import com.datagami.rentaxis.api.dto.report.PropertyPnlDTO.Group;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * F14-10: the balance sheet as at a date, per property and consolidated.
 *
 * <p>{@code sections} are ASSET, LIABILITY and EQUITY, each a list of level-two
 * groups whose rows read positive on their natural side (assets debit, the rest
 * credit). Equity adds two computed rows: {@code currentYearResult} (income less
 * expenses from the fiscal year's first day to {@code asAt}) and
 * {@code earlierYearsResult} (the result of earlier years not yet closed into
 * Retained Earnings). {@code check} is Assets − (Liabilities + Equity) per column;
 * {@code ok} is the Total column's (or, for a scoped caller, every column's).</p>
 */
public record BalanceSheetDTO(
        LocalDate asAt,
        LocalDate compareAt,
        LocalDate fiscalYearStart,
        boolean scoped,
        List<Column> columns,
        List<Section> sections,
        Map<String, Amount> currentYearResult,
        Map<String, Amount> earlierYearsResult,
        Map<String, Amount> liabilitiesAndEquity,
        Map<String, Amount> check,
        boolean ok,
        BigDecimal ledgerImbalance) {

    public record Section(String type, List<Group> groups, Map<String, Amount> total) { }
}
