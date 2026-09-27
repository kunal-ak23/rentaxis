package com.datagami.rentaxis.core.tenant;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The SUPER_ADMIN platform-total exception (batch 5 ruling) is GET-only and
 * SUPER_ADMIN-only; everything else with no organisation is refused.
 */
class OrganisationRequiredInterceptorTest {

    private final OrganisationRequiredInterceptor interceptor = new OrganisationRequiredInterceptor();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    private static void as(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                UUID.randomUUID().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private boolean pass(String method, String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        return interceptor.preHandle(req, new MockHttpServletResponse(), new Object());
    }

    @Test
    void superAdminReadsThePlatformTotalsWithNoOrganisation() {
        as("SUPER_ADMIN");
        for (String uri : List.of("/api/v1/dashboard/summary", "/api/v1/cheques/summary", "/api/v1/cheques/aging")) {
            assertThat(pass("GET", uri)).as(uri).isTrue();
        }
    }

    @Test
    void anyOtherRoleMethodOrPathWithNoOrganisationIsRefused() {
        as("TENANT_ADMIN");
        assertThatThrownBy(() -> pass("GET", "/api/v1/dashboard/summary"))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessage("Select an organisation first");
        as("SUPER_ADMIN");
        assertThatThrownBy(() -> pass("POST", "/api/v1/cheques/summary"))
                .isInstanceOf(BusinessRuleViolationException.class);
        for (String uri : List.of("/api/v1/finance/journals", "/api/v1/properties", "/api/v1/finance/fiscal-settings",
                "/api/v1/dashboard/summary/extra")) {
            assertThatThrownBy(() -> pass("GET", uri)).as(uri).isInstanceOf(BusinessRuleViolationException.class);
        }
    }

    @Test
    void anOrganisationInContextAlwaysPasses() {
        as("TENANT_ADMIN");
        TenantContextHolder.setTenantId(UUID.randomUUID());
        assertThat(pass("GET", "/api/v1/finance/journals")).isTrue();
    }

    /**
     * The no-organisation allow-list is a trust surface: pinned here so an addition
     * is a deliberate, reviewed change. The marketplace is RENTER-only and a renter
     * always has a tenant, so it is not on it (batch 5 review).
     */
    @Test
    void theCrossOrganisationAllowListIsExactlyThis() {
        assertThat(TenantSelectionWebConfig.CROSS_ORG_PATHS).containsExactly(
                "/api/auth/**", "/api/v1/auth/**", "/api/admin/**", "/api/v1/admin/**",
                "/api/v1/notifications/**", "/api/v1/email/**", "/api/v1/account/**",
                "/api/v1/tenant/features", "/api/v1/tenant/info", "/api/v1/assets/serve/**",
                "/api/v1/public/**", "/public/**", "/api/webhooks/**", "/error");
        assertThat(OrganisationRequiredInterceptor.SUPER_ADMIN_PLATFORM_READS).containsExactly(
                "/api/v1/dashboard/summary", "/api/v1/cheques/summary", "/api/v1/cheques/aging");
    }
}
