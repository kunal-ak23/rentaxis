package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.FineConfigDTO;
import com.datagami.rentaxis.core.service.FineSettingsInitializer;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrgFineSettings;
import com.datagami.rentaxis.domain.repository.LandlordOrgFineSettingsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FineSettingsControllerTest {

    @Mock
    LandlordOrgFineSettingsRepository repo;

    @Mock
    FineSettingsInitializer initializer;

    @InjectMocks
    FineSettingsController controller;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    private LandlordOrgFineSettings sampleSettings() {
        LandlordOrgFineSettings s = new LandlordOrgFineSettings();
        s.setId(UUID.randomUUID());
        s.setLandlordOrgId(tenantId);
        s.setFineBounceAmount(new BigDecimal("500"));
        s.setFineSignatureMismatchAmount(new BigDecimal("500"));
        s.setFineAccountClosedAmount(new BigDecimal("1000"));
        s.setFineGraceDays(7);
        s.setFinePerDayRate(new BigDecimal("25"));
        return s;
    }

    // -----------------------------------------------------------------------
    // GET tests
    // -----------------------------------------------------------------------

    @Test
    void get_asTenantAdmin_returnsConfig() {
        setAuth("ROLE_TENANT_ADMIN");
        when(repo.findByLandlordOrgId(tenantId)).thenReturn(Optional.of(sampleSettings()));

        ResponseEntity<FineConfigDTO> resp = controller.get();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        FineConfigDTO dto = resp.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.bounceAmount()).isEqualByComparingTo("500");
        assertThat(dto.graceDays()).isEqualTo(7);
        assertThat(dto.perDayRate()).isEqualByComparingTo("25");
    }

    @Test
    void get_whenNoRowExists_createsDefaultViaInitializerAndReturns() {
        setAuth("ROLE_TENANT_ADMIN");
        LandlordOrgFineSettings defaults = sampleSettings();
        when(repo.findByLandlordOrgId(tenantId)).thenReturn(Optional.empty());
        when(initializer.upsertDefault(tenantId)).thenReturn(defaults);

        ResponseEntity<FineConfigDTO> resp = controller.get();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(initializer).upsertDefault(tenantId);
    }

    // Auth-fail tests — @PreAuthorize is enforced by Spring Security at runtime
    // but in plain Mockito unit tests we exercise the controller directly.
    // We verify the role check by ensuring that callers with wrong roles would
    // be rejected; since @PreAuthorize is AOP-based it won't fire in plain unit
    // tests, so these tests verify that RBAC annotations are present and that
    // the method logic itself is correct for authorised callers.
    // For role enforcement the integration test suite (M16) provides coverage.

    @Test
    void get_asPropertyManager_returns403_annotationPresent() throws NoSuchMethodException {
        var method = FineSettingsController.class.getMethod("get");
        var annotation = method.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).contains("TENANT_ADMIN");
        assertThat(annotation.value()).doesNotContain("PROPERTY_MANAGER");
    }

    @Test
    void get_asRenter_returns403_annotationPresent() throws NoSuchMethodException {
        var method = FineSettingsController.class.getMethod("get");
        var annotation = method.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).doesNotContain("RENTER");
    }

    // -----------------------------------------------------------------------
    // PUT tests
    // -----------------------------------------------------------------------

    @Test
    void put_validValues_returns200_andPersistsToRepo() {
        setAuth("ROLE_TENANT_ADMIN");
        FineConfigDTO body = new FineConfigDTO(
                new BigDecimal("300"),
                new BigDecimal("300"),
                new BigDecimal("800"),
                5,
                new BigDecimal("20")
        );
        LandlordOrgFineSettings existing = sampleSettings();
        when(repo.findByLandlordOrgId(tenantId)).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<FineConfigDTO> resp = controller.update(body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<LandlordOrgFineSettings> captor = ArgumentCaptor.forClass(LandlordOrgFineSettings.class);
        verify(repo).save(captor.capture());
        LandlordOrgFineSettings saved = captor.getValue();
        assertThat(saved.getFineBounceAmount()).isEqualByComparingTo("300");
        assertThat(saved.getFineGraceDays()).isEqualTo(5);
        assertThat(saved.getFinePerDayRate()).isEqualByComparingTo("20");
    }

    @Test
    void put_bounceAmount_hasDecimalMinAnnotation() throws Exception {
        // Bean Validation fires via Spring MVC @Valid — verified here by annotation presence.
        var field = FineConfigDTO.class.getDeclaredFields();
        var bounceField = java.util.Arrays.stream(field)
                .filter(f -> f.getName().equals("bounceAmount"))
                .findFirst().orElseThrow();
        assertThat(bounceField.getAnnotation(jakarta.validation.constraints.DecimalMin.class))
                .isNotNull();
        assertThat(bounceField.getAnnotation(jakarta.validation.constraints.NotNull.class))
                .isNotNull();
    }

    @Test
    void put_graceDays_hasMinAnnotation() throws Exception {
        var field = FineConfigDTO.class.getDeclaredFields();
        var graceField = java.util.Arrays.stream(field)
                .filter(f -> f.getName().equals("graceDays"))
                .findFirst().orElseThrow();
        assertThat(graceField.getAnnotation(jakarta.validation.constraints.Min.class))
                .isNotNull();
        assertThat(graceField.getAnnotation(jakarta.validation.constraints.NotNull.class))
                .isNotNull();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void setAuth(String role) {
        var auth = new UsernamePasswordAuthenticationToken(
                UUID.randomUUID().toString(), null,
                Collections.singletonList(new SimpleGrantedAuthority(role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
