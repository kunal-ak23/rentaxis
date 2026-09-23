package com.datagami.rentaxis.api;

import com.datagami.rentaxis.config.SecurityConfig;
import com.datagami.rentaxis.core.security.ApiSecurityFilter;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.security.PublicRateLimitFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PendingFollowUpsController.class)
@Import(SecurityConfig.class)
class PendingFollowUpsControllerSecurityTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean com.datagami.rentaxis.core.service.LeaseInteractionService interactionService;
    @MockitoBean ApiSecurityFilter apiSecurityFilter;
    @MockitoBean PublicRateLimitFilter publicRateLimitFilter;

    private UUID tenantId;

    @BeforeEach
    void setUp() throws Exception {
        tenantId = UUID.randomUUID();
        when(interactionService.pendingFollowUps(eq(tenantId), any(LocalDate.class)))
                .thenReturn(List.of());
        doAnswer(invocation -> {
            // Stands in for ApiSecurityFilter, so it does that filter's job of
            // setting the request's tenant. A tenant set on the test thread
            // before perform() no longer reaches the request:
            // TenantContextResetFilter clears it (security audit P1-1).
            TenantContextHolder.setTenantId(tenantId);
            jakarta.servlet.FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(apiSecurityFilter).doFilter(any(), any(), any());
        doAnswer(invocation -> {
            jakarta.servlet.FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(publicRateLimitFilter).doFilter(any(), any(), any());
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void superAdminAdministeringTenantCanLoadFollowUps() throws Exception {
        mockMvc.perform(get("/api/v1/renewals/follow-ups").with(withTenant()))
                .andExpect(status().isOk());

        verify(interactionService).pendingFollowUps(eq(tenantId), any(LocalDate.class));
    }

    @Test
    @WithMockUser(roles = "RENTER")
    void renterCannotLoadAdministrativeFollowUps() throws Exception {
        mockMvc.perform(get("/api/v1/renewals/follow-ups").with(withTenant()))
                .andExpect(status().isForbidden());
    }

    private RequestPostProcessor withTenant() {
        return request -> {
            TenantContextHolder.setTenantId(tenantId);
            return request;
        };
    }
}
