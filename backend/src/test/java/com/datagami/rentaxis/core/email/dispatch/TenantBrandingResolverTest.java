package com.datagami.rentaxis.core.email.dispatch;

import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantBrandingResolverTest {

    @Mock LandlordOrgRepository orgRepo;
    @InjectMocks TenantBrandingResolver resolver;

    @Test
    void returnsBrandingFromLandlordOrg() {
        UUID tenant = UUID.randomUUID();
        LandlordOrg org = new LandlordOrg();
        org.setId(tenant);
        org.setName("Acme PM");
        org.setLogoUrl("https://example/logo.png");
        when(orgRepo.findById(tenant)).thenReturn(Optional.of(org));

        TenantBranding b = resolver.resolve(tenant);

        assertEquals("Acme PM", b.companyName());
        assertEquals("https://example/logo.png", b.logoUrl());
    }

    @Test
    void returnsNullForUnknownTenant() {
        when(orgRepo.findById(any())).thenReturn(Optional.empty());
        assertNull(resolver.resolve(UUID.randomUUID()));
    }

    @Test
    void returnsNullForNullTenantId() {
        assertNull(resolver.resolve(null));
        verifyNoInteractions(orgRepo);
    }
}
