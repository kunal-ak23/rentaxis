package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.DashboardSummaryDTO;
import com.datagami.rentaxis.api.dto.MonthlyCollectionDTO;
import com.datagami.rentaxis.core.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public DashboardSummaryDTO getSummary() {
        return dashboardService.getSummary();
    }

    /** Last 12 months of expected vs collected for the dashboard chart. */
    @GetMapping("/monthly-collections")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public List<MonthlyCollectionDTO> getMonthlyCollections() {
        return dashboardService.getMonthlyCollections();
    }
}
