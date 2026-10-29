package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.PartnerUnitDtos.UnitCount;
import com.datagami.rentaxis.api.dto.PartnerUnitDtos.UnitPage;
import com.datagami.rentaxis.core.security.PartnerPrincipal;
import com.datagami.rentaxis.core.service.PartnerUnitFeedService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Promoter developer API, authenticated by {@code X-Api-Key} alone: who the key
 * acts for ({@code /ping}) and the units of the promoter's granted properties.
 */
@RestController
@RequestMapping("/api/v1/partner/promoter")
@PreAuthorize("hasAuthority('PARTNER_API')")
@RequiredArgsConstructor
public class PartnerPromoterController {

    private final PartnerUnitFeedService feed;

    @GetMapping("/ping")
    public Map<String, String> ping(@AuthenticationPrincipal PartnerPrincipal principal) {
        requireTenantContext(principal);
        return Map.of(
                "promoterUserId", principal.promoterUserId().toString(),
                "tenantId", principal.tenantId().toString());
    }

    @GetMapping("/units/count")
    public UnitCount count(@AuthenticationPrincipal PartnerPrincipal principal) {
        requireTenantContext(principal);
        return feed.count(principal);
    }

    @GetMapping("/units")
    public UnitPage units(@AuthenticationPrincipal PartnerPrincipal principal,
                          @RequestParam(required = false) Integer page,
                          @RequestParam(required = false) Integer size,
                          @RequestParam(required = false) UUID propertyId) {
        requireTenantContext(principal);
        return feed.page(principal, page, size, propertyId);
    }

    /**
     * The partner filter sets the key's organisation as the tenant context. Should
     * anything downstream ever clear or replace it, fail loudly rather than let
     * partner endpoints run unscoped or in another organisation.
     */
    private static void requireTenantContext(PartnerPrincipal principal) {
        if (principal == null || !Objects.equals(TenantContextHolder.getTenantId(), principal.tenantId())) {
            throw new IllegalStateException("Partner request without its key's tenant context");
        }
    }
}
