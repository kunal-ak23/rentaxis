package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.MonthlyCollectionDTO;
import com.datagami.rentaxis.config.SecurityConfig;
import com.datagami.rentaxis.core.security.ApiSecurityFilter;
import com.datagami.rentaxis.core.service.DashboardService;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Verifies GET /api/v1/dashboard/monthly-collections returns the series and is role-protected. */
@WebMvcTest(DashboardController.class)
@Import(SecurityConfig.class)
class DashboardControllerMonthlyTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DashboardService dashboardService;
    @MockitoBean
    private ApiSecurityFilter apiSecurityFilter;
    @MockitoBean
    private PublicRateLimitFilter publicRateLimitFilter;

    private UUID tenantId;

    @BeforeEach
    void setUp() throws Exception {
        tenantId = UUID.randomUUID();
        TenantContextHolder.setTenantId(tenantId);
        when(dashboardService.getMonthlyCollections()).thenReturn(List.of(
                new MonthlyCollectionDTO("Jun", "2026-06", new BigDecimal("3500"), new BigDecimal("2500"))
        ));
        doAnswer(inv -> {
            jakarta.servlet.FilterChain chain = inv.getArgument(2);
            chain.doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(apiSecurityFilter).doFilter(any(), any(), any());
        doAnswer(inv -> {
            jakarta.servlet.FilterChain chain = inv.getArgument(2);
            chain.doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(publicRateLimitFilter).doFilter(any(), any(), any());
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @WithMockUser(roles = "TENANT_ADMIN")
    void monthlyCollections_returns200WithSeries() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/monthly-collections").with(withTenant()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].month").value("Jun"))
                .andExpect(jsonPath("$[0].expected").value(3500))
                .andExpect(jsonPath("$[0].collected").value(2500));
        verify(dashboardService).getMonthlyCollections();
    }

    @Test
    void monthlyCollections_unauthenticated_isForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/monthly-collections").with(withTenant()))
                .andExpect(result -> {
                    int code = result.getResponse().getStatus();
                    if (code != 401 && code != 403) {
                        throw new AssertionError("expected 401/403 but got " + code);
                    }
                });
    }

    private RequestPostProcessor withTenant() {
        return request -> {
            TenantContextHolder.setTenantId(tenantId);
            return request;
        };
    }
}
