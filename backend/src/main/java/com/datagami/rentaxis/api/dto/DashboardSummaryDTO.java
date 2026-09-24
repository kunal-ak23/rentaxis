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
    /** F14-01: units with a posted lease that starts after today and none covering today. */
    private int reservedUnits;
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

    // Collection tile (gap #59): one basis, the due date. receivedThisMonth is
    // exactly collectedAgainstDueThisMonth + collectedArrears + collectedAdvance.
    // The headline is collectedForThisMonth of dueThisMonth: it also counts
    // rows dated this month that cleared earlier (paid ahead).
    private BigDecimal dueThisMonth; // live rows with a cheque date in the month
    private BigDecimal collectedForThisMonth; // of those, CLEARED whenever it cleared (the headline)
    private BigDecimal collectedAgainstDueThisMonth; // of those, cleared this month
    private BigDecimal collectedArrears; // cleared this month, dated before the month
    private BigDecimal collectedAdvance; // cleared this month, dated after the month

    // Recent Activity
    private List<RecentActivityItem> recentActivity;

    @Data
    public static class RecentActivityItem {
        private String type; // "LEASE_ACTIVATED", "PAYMENT_CLEARED", "PAYMENT_COLLECTED", etc.
        /** English fallback sentence; clients that can should render from the fields below. */
        private String description;
        private String timestamp;
        // The facts the description is built from, so a client can render the
        // line in its own language and number format (gap #60).
        private String chequeStatus;
        private String chequeNumber;
        private Integer seqNo;
        private String unitNumber;
        private String propertyName;
        private BigDecimal amount;
    }
}
