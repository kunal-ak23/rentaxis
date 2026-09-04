package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.config.SecurityConfig;
import com.datagami.rentaxis.core.security.ApiSecurityFilter;
import com.datagami.rentaxis.core.service.AccountDeletionService;
import com.datagami.rentaxis.security.PublicRateLimitFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code DELETE /api/v1/account} must act on the authenticated principal and
 * nothing else — there is no id in the path or body to get wrong, and no
 * header to spoof.
 */
@WebMvcTest(SelfAccountController.class)
@Import(SecurityConfig.class)
class SelfAccountControllerTest {

    private static final String USER_ID = "5f0c2c9e-7d0b-4a9d-9a6b-6a1a3f8c2e11";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountDeletionService accountDeletionService;

    @MockitoBean
    private ApiSecurityFilter apiSecurityFilter;

    @MockitoBean
    private PublicRateLimitFilter publicRateLimitFilter;

    @BeforeEach
    void passThroughFilters() throws Exception {
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

    @Test
    @WithMockUser(username = USER_ID, roles = "RENTER")
    void deletesExactlyTheAuthenticatedPrincipal() throws Exception {
        mockMvc.perform(delete("/api/v1/account"))
                .andExpect(status().isNoContent());

        verify(accountDeletionService).deleteOwnAccount(UUID.fromString(USER_ID));
    }

    @Test
    void unauthenticatedRequestIsRejectedBeforeTheService() throws Exception {
        mockMvc.perform(delete("/api/v1/account"))
                .andExpect(status().is4xxClientError());

        verify(accountDeletionService, never()).deleteOwnAccount(any());
    }

    @Test
    @WithMockUser(username = "not-a-uuid", roles = "RENTER")
    void principalThatIsNotAUserIdIsForbidden() throws Exception {
        mockMvc.perform(delete("/api/v1/account"))
                .andExpect(status().isForbidden());

        verify(accountDeletionService, never()).deleteOwnAccount(any());
    }

    @Test
    @WithMockUser(username = USER_ID, roles = "TENANT_ADMIN")
    void refusalMessageReachesTheClient() throws Exception {
        doThrow(new BusinessRuleViolationException("You are the only administrator of this organisation."))
                .when(accountDeletionService).deleteOwnAccount(UUID.fromString(USER_ID));

        mockMvc.perform(delete("/api/v1/account"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You are the only administrator of this organisation."));
    }
}
