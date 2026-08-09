package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.LandlordOrgService;
import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LandlordOrgController} create/update payload handling:
 *
 *   - POST persists the optional fields the provisioning form collects
 *     (address, TRN, phone, logoUrl, ticketOtpRequired) instead of silently
 *     dropping everything but the name.
 *   - ticketOtpRequired is accepted as a real JSON boolean (the web client
 *     sends one) as well as the legacy "true"/"false" string.
 *   - PUT keeps partial-update semantics: absent keys leave fields untouched.
 */
@ExtendWith(MockitoExtension.class)
class LandlordOrgControllerTest {

    @Mock
    LandlordOrgService service;

    @Mock
    TenantFeatureService tenantFeatureService;

    LandlordOrgController controller;

    @BeforeEach
    void setUp() {
        controller = new LandlordOrgController(service, tenantFeatureService);
    }

    private LandlordOrg orgNamed(String name) {
        LandlordOrg org = new LandlordOrg();
        org.setId(UUID.randomUUID());
        org.setName(name);
        return org;
    }

    @Test
    void createTenant_persistsOptionalFields() {
        LandlordOrg provisioned = orgNamed("Acme");
        when(service.provisionTenant("Acme")).thenReturn(provisioned);
        when(service.save(any(LandlordOrg.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> payload = new HashMap<>();
        payload.put("name", "Acme");
        payload.put("address", "Office 1, Dubai");
        payload.put("trn", "100123456789");
        payload.put("phone", "+971 50 123 4567");
        payload.put("logoUrl", "https://cdn.example/logo.png");
        payload.put("status", "ACTIVE");
        payload.put("ticketOtpRequired", Boolean.FALSE);

        ResponseEntity<LandlordOrg> res = controller.createTenant(payload);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        LandlordOrg saved = res.getBody();
        assertThat(saved).isNotNull();
        assertThat(saved.getAddress()).isEqualTo("Office 1, Dubai");
        assertThat(saved.getTrn()).isEqualTo("100123456789");
        assertThat(saved.getPhone()).isEqualTo("+971 50 123 4567");
        assertThat(saved.getLogoUrl()).isEqualTo("https://cdn.example/logo.png");
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getTicketOtpRequired()).isFalse();
        verify(service).save(provisioned);
    }

    @Test
    void createTenant_nameOnly_skipsSecondSave() {
        LandlordOrg provisioned = orgNamed("Solo");
        when(service.provisionTenant("Solo")).thenReturn(provisioned);

        ResponseEntity<LandlordOrg> res = controller.createTenant(Map.of("name", "Solo"));

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).isSameAs(provisioned);
        verify(service, never()).save(any());
    }

    @Test
    void createTenant_blankName_isBadRequest() {
        assertThat(controller.createTenant(Map.of("name", "  ")).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.createTenant(Map.of("address", "x")).getStatusCode().value()).isEqualTo(400);
        verify(service, never()).provisionTenant(any());
    }

    @Test
    void updateTenant_acceptsJsonBooleanTicketOtp() {
        LandlordOrg org = orgNamed("Acme");
        org.setTicketOtpRequired(true);
        when(service.findById(org.getId())).thenReturn(Optional.of(org));
        when(service.save(any(LandlordOrg.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<LandlordOrg> res =
                controller.updateTenant(org.getId(), Map.of("ticketOtpRequired", Boolean.FALSE));

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(org.getTicketOtpRequired()).isFalse();
    }

    @Test
    void updateTenant_acceptsLegacyStringTicketOtp() {
        LandlordOrg org = orgNamed("Acme");
        org.setTicketOtpRequired(false);
        when(service.findById(org.getId())).thenReturn(Optional.of(org));
        when(service.save(any(LandlordOrg.class))).thenAnswer(inv -> inv.getArgument(0));

        controller.updateTenant(org.getId(), Map.of("ticketOtpRequired", "true"));

        assertThat(org.getTicketOtpRequired()).isTrue();
    }

    @Test
    void updateTenant_absentKeysLeaveFieldsUntouched() {
        LandlordOrg org = orgNamed("Acme");
        org.setAddress("Keep me");
        org.setTrn("100999999999");
        org.setStatus("ACTIVE");
        when(service.findById(org.getId())).thenReturn(Optional.of(org));
        when(service.save(any(LandlordOrg.class))).thenAnswer(inv -> inv.getArgument(0));

        controller.updateTenant(org.getId(), Map.of("name", "Renamed"));

        assertThat(org.getName()).isEqualTo("Renamed");
        assertThat(org.getAddress()).isEqualTo("Keep me");
        assertThat(org.getTrn()).isEqualTo("100999999999");
        assertThat(org.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void updateTenant_unknownId_isNotFound() {
        UUID id = UUID.randomUUID();
        when(service.findById(id)).thenReturn(Optional.empty());

        assertThat(controller.updateTenant(id, Map.of("name", "x")).getStatusCode().value()).isEqualTo(404);
        verify(service, never()).save(any());
    }
}
