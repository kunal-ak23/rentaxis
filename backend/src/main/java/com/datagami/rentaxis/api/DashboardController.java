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

    static final String HOME_ROLES =
            "hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER')";

    private final DashboardService dashboardService;

    /**
     * Home's tiles. ACCOUNTANT is admitted (sandbox role test M4): the role already
     * reads every lease and the whole cheque register tenant-wide
     * ({@code LeaseAccessPolicy} treats it as tenant-wide), so these totals are the
     * same figures in one place, and the Hibernate tenant filter still bounds them
     * to the caller's organisation. TENANT_USER, RENTER and SECURITY_GUARD stay
     * refused. Mirrored on the web by {@code canViewDashboardSummary}.
     */
    @GetMapping("/summary")
    @PreAuthorize(HOME_ROLES)
    public DashboardSummaryDTO getSummary() {
        return dashboardService.getSummary();
    }

    /** Last 12 months of expected vs collected for the dashboard chart. */
    @GetMapping("/monthly-collections")
    @PreAuthorize(HOME_ROLES)
    public List<MonthlyCollectionDTO> getMonthlyCollections() {
        return dashboardService.getMonthlyCollections();
    }
}
