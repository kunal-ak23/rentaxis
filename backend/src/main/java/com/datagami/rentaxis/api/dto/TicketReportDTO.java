package com.datagami.rentaxis.api.dto;

import lombok.Data;

import java.util.Map;

@Data
public class TicketReportDTO {
    private long totalTickets;
    private long openCount;
    private long resolvedCount;
    private long closedCount;
    private double avgResolutionHours;
    private double avgSatisfaction;
    private long overdueCount;
    private Map<String, Long> ticketsByCategory;
    private Map<String, Long> ticketsByPriority;
}
