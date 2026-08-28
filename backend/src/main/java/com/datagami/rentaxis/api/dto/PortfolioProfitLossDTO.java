package com.datagami.rentaxis.api.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * One portfolio-wide P&L followed by the same figures for every property in
 * scope. Property managers receive only assigned properties; tenant admins
 * receive the whole tenant portfolio.
 */
public record PortfolioProfitLossDTO(
        ReportDTO overall,
        List<PropertyProfitLossDTO> properties) {

    public record PropertyProfitLossDTO(
            UUID propertyId,
            String propertyNameEn,
            String propertyNameAr,
            BigDecimal totalIncome,
            BigDecimal totalExpenses,
            BigDecimal netOperatingIncome,
            BigDecimal netProfit) {
    }
}
