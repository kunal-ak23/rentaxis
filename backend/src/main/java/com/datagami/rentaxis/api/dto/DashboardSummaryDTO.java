package com.datagami.rentaxis.api.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class DashboardSummaryDTO {
    // Portfolio
    private int totalProperties;
    private int totalUnits;
    private int occupiedUnits;
    private int vacantUnits;
    private double occupancyRate;

    // Leases
    private int activeLeases;
    private int draftLeases;
    private int expiringLeases; // within 30 days

    // Financial
    private BigDecimal totalRentRevenue; // sum of active lease rent amounts
    private BigDecimal collectedAmount; // CLEARED payments
    private BigDecimal pendingAmount; // PENDING payments (all time)
    private BigDecimal pendingThisMonthAmount; // PENDING payments due in the current month
    private BigDecimal overdueAmount; // overdue payments
    private BigDecimal receivedThisMonth; // cash received this month (collect/deposit/clear)
    private BigDecimal receivedLastMonth; // cash received the previous month

    // Recent Activity
    private List<RecentActivityItem> recentActivity;

    @Data
    public static class RecentActivityItem {
        private String type; // "LEASE_ACTIVATED", "PAYMENT_CLEARED", "PAYMENT_COLLECTED", etc.
        private String description;
        private String timestamp;
    }
}
