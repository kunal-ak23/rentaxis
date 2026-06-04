package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.PaymentScheduleDTO;
import com.datagami.rentaxis.config.SecurityConfig;
import com.datagami.rentaxis.core.security.ApiSecurityFilter;
import com.datagami.rentaxis.core.service.PaymentScheduleService;
import com.datagami.rentaxis.core.service.RentReceiptService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import com.datagami.rentaxis.security.PublicRateLimitFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the {@code overdue} query parameter on {@code GET /api/v1/payments}
 * is plumbed through to {@link PaymentScheduleService#getPaymentsForProperty}.
 */
@WebMvcTest(PaymentScheduleController.class)
@Import(SecurityConfig.class)
class PaymentScheduleControllerOverdueTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentScheduleService paymentScheduleService;

    @MockitoBean
    private RentReceiptService rentReceiptService;

    @MockitoBean
    private ApiSecurityFilter apiSecurityFilter;

    @MockitoBean
    private PublicRateLimitFilter publicRateLimitFilter;

    private UUID tenantId;

    @BeforeEach
    void setUp() throws Exception {
        tenantId = UUID.randomUUID();
        TenantContextHolder.setTenantId(tenantId);
        Page<PaymentScheduleDTO> empty = new PageImpl<>(List.of());
        when(paymentScheduleService.getPaymentsForProperty(any(), any(), any(), anyBoolean(), any()))
                .thenReturn(empty);
        doAnswer(invocation -> {
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
    @WithMockUser(roles = "TENANT_ADMIN")
    void getPayments_overdueTrue_passesOverdueFlagToService() throws Exception {
        mockMvc.perform(get("/api/v1/payments").param("overdue", "true").with(withTenant()))
                .andExpect(status().isOk());

        verify(paymentScheduleService)
                .getPaymentsForProperty(isNull(), isNull(), isNull(), eq(true), any(Pageable.class));
    }

    @Test
    @WithMockUser(roles = "TENANT_ADMIN")
    void getPayments_overdueDefaultsToFalse_whenParamAbsent() throws Exception {
        mockMvc.perform(get("/api/v1/payments").with(withTenant()))
                .andExpect(status().isOk());

        verify(paymentScheduleService)
                .getPaymentsForProperty(isNull(), isNull(), isNull(), eq(false), any(Pageable.class));
    }

    @Test
    @WithMockUser(roles = "TENANT_ADMIN")
    void getPayments_statusFilter_keepsOverdueFalse() throws Exception {
        mockMvc.perform(get("/api/v1/payments").param("status", "PENDING").with(withTenant()))
                .andExpect(status().isOk());

        verify(paymentScheduleService)
                .getPaymentsForProperty(isNull(), eq(PaymentStatus.PENDING), isNull(), eq(false), any(Pageable.class));
    }

    @Test
    void getPayments_unauthenticated_returns401or403() throws Exception {
        mockMvc.perform(get("/api/v1/payments").param("overdue", "true").with(withTenant()))
                .andExpect(result -> {
                    int code = result.getResponse().getStatus();
                    if (code != 401 && code != 403) {
                        throw new AssertionError("expected 401 or 403 but got " + code);
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
