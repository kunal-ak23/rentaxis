package com.datagami.rentaxis.core.email.dispatch;

import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class TenantBrandingResolver {

    private final LandlordOrgRepository orgRepo;

    public TenantBranding resolve(UUID tenantId) {
        if (tenantId == null) return null;
        return orgRepo.findById(tenantId)
                .map(o -> new TenantBranding(o.getName(), o.getLogoUrl()))
                .orElse(null);
    }
}
