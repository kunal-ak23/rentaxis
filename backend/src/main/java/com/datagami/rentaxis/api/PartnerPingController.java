package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.security.PartnerPrincipal;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;

/**
 * Promoter developer API: "check your key". {@code GET /api/v1/partner/promoter/ping}
 * with {@code X-Api-Key} answers who the key acts for — the promoter and organisation —
 * or the partner filter's 401 for a key that does not work. Touches no data.
 */
@RestController
@RequestMapping("/api/v1/partner/promoter")
@PreAuthorize("hasAuthority('PARTNER_API')")
public class PartnerPingController {

    @GetMapping("/ping")
    public Map<String, String> ping(@AuthenticationPrincipal PartnerPrincipal principal) {
        // The partner filter sets the key's organisation as the tenant context. Should
        // anything downstream ever clear or replace it, fail loudly here rather than let
        // partner endpoints run unscoped or in another organisation.
        if (principal == null || !Objects.equals(TenantContextHolder.getTenantId(), principal.tenantId())) {
            throw new IllegalStateException("Partner request without its key's tenant context");
        }
        return Map.of(
                "promoterUserId", principal.promoterUserId().toString(),
                "tenantId", principal.tenantId().toString());
    }
}
