package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.ChequeExtractionResponseDTO;
import com.datagami.rentaxis.api.dto.ChequeImageMetaDTO;
import com.datagami.rentaxis.api.dto.ExtractedChequeDTO;
import com.datagami.rentaxis.config.SecurityConfig;
import com.datagami.rentaxis.core.security.ApiSecurityFilter;
import com.datagami.rentaxis.core.service.cheque.ChequeExtractionService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.security.PublicRateLimitFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChequeExtractionController.class)
@Import(SecurityConfig.class)
class ChequeExtractionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChequeExtractionService service;

    @MockitoBean
    private ApiSecurityFilter apiSecurityFilter;

    @MockitoBean
    private PublicRateLimitFilter publicRateLimitFilter;

    private UUID tenantId;

    @BeforeEach
    void setUp() throws Exception {
        tenantId = UUID.randomUUID();
        TenantContextHolder.setTenantId(tenantId);
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
    @WithMockUser(roles = "TENANT_ADMIN")
    void extract_happyPath_returns200WithBody() throws Exception {
        var resp = new ChequeExtractionResponseDTO(
                new ChequeImageMetaDTO("https://blob", "cheques/abc.jpg", OffsetDateTime.parse("2026-05-04T10:23:00Z")),
                new ExtractedChequeDTO("123", "ENBD", "Acme", LocalDate.of(2026, 6, 1), null, ExtractedChequeDTO.Confidence.HIGH),
                List.of()
        );
        when(service.extractAndStore(eq(tenantId), any())).thenReturn(resp);

        MockMultipartFile file = new MockMultipartFile("file", "cheque.jpg", MediaType.IMAGE_JPEG_VALUE, new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/v1/cheques/extract").file(file).with(withTenant()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.image.url").value("https://blob"))
                .andExpect(jsonPath("$.extracted.chequeNumber").value("123"));
    }

    @Test
    @WithMockUser(roles = "TENANT_ADMIN")
    void extract_serviceThrowsValidation_returns400() throws Exception {
        when(service.extractAndStore(eq(tenantId), any())).thenThrow(new IllegalArgumentException("bad file"));

        MockMultipartFile file = new MockMultipartFile("file", "cheque.jpg", MediaType.IMAGE_JPEG_VALUE, new byte[]{1});

        mockMvc.perform(multipart("/api/v1/cheques/extract").file(file).with(withTenant()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad file"));
    }

    @Test
    void extract_unauthenticated_returns401() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "cheque.jpg", MediaType.IMAGE_JPEG_VALUE, new byte[]{1});

        mockMvc.perform(multipart("/api/v1/cheques/extract").file(file).with(withTenant()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "PROPERTY_MANAGER")
    void extract_authorizedRole_propertyManager_returns200() throws Exception {
        when(service.extractAndStore(eq(tenantId), any())).thenReturn(
                new ChequeExtractionResponseDTO(new ChequeImageMetaDTO("https://blob", "cheques/abc.jpg", OffsetDateTime.now()), null, List.of("w"))
        );
        MockMultipartFile file = new MockMultipartFile("file", "cheque.jpg", MediaType.IMAGE_JPEG_VALUE, new byte[]{1});
        mockMvc.perform(multipart("/api/v1/cheques/extract").file(file).with(withTenant())).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "TENANT_USER")
    void extract_authorizedRole_tenantUser_returns200() throws Exception {
        when(service.extractAndStore(eq(tenantId), any())).thenReturn(
                new ChequeExtractionResponseDTO(new ChequeImageMetaDTO("https://blob", "cheques/abc.jpg", OffsetDateTime.now()), null, List.of("w"))
        );
        MockMultipartFile file = new MockMultipartFile("file", "cheque.jpg", MediaType.IMAGE_JPEG_VALUE, new byte[]{1});
        mockMvc.perform(multipart("/api/v1/cheques/extract").file(file).with(withTenant())).andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor withTenant() {
        return request -> {
            TenantContextHolder.setTenantId(tenantId);
            return request;
        };
    }
}
