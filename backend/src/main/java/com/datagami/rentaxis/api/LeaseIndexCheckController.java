package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.core.service.lease.LeaseRenewalService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The rental index checks stored on a lease (spec 2026-11-25 §9, §14.7; final review I2): what each renewal, and each
 * later edit of its successor draft, was decided against — figures, who, when and any override reason. Staff only;
 * renters never see it. 404 when RENTAL_INDEX is off, and for a lease the caller cannot read.
 */
@RestController
@RequestMapping("/api/v1/leases")
public class LeaseIndexCheckController {

    private final LeaseRenewalService renewalService;
    private final TenantFeatureService tenantFeatureService;

    public LeaseIndexCheckController(LeaseRenewalService renewalService, TenantFeatureService tenantFeatureService) {
        this.renewalService = renewalService;
        this.tenantFeatureService = tenantFeatureService;
    }

    @GetMapping("/{id}/index-checks")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','PROPERTY_MANAGER','ACCOUNTANT')")
    public List<LeaseRenewalService.IndexCheckView> indexChecks(@PathVariable UUID id) {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null || !tenantFeatureService.isEnabled(tenantId, TenantFeature.RENTAL_INDEX)) {
            throw new NotFoundException("Rental index feature is disabled");
        }
        return renewalService.indexChecksOf(id);
    }
}
