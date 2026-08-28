package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.FinancialTransactionService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinancialTransactionControllerPortfolioTest {

    @Mock FinancialTransactionService service;
    @Mock UserPropertyAssignmentRepository assignmentRepository;

    @InjectMocks FinancialTransactionController controller;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void propertyManagerPortfolioUsesOnlyDistinctAssignedPropertyIds() {
        UUID alpha = UUID.randomUUID();
        UUID beta = UUID.randomUUID();
        authenticate("ROLE_PROPERTY_MANAGER");
        TenantContextHolder.setTenantId(tenantId);
        when(assignmentRepository.findByUserId(userId)).thenReturn(List.of(
                assignment(alpha), assignment(beta), assignment(alpha)));

        controller.getPortfolioProfitLoss(null, null);

        verify(service).getPortfolioProfitLoss(tenantId, List.of(alpha, beta), null, null);
    }

    @Test
    void tenantAdminPortfolioDelegatesWithWholeTenantScope() {
        authenticate("ROLE_TENANT_ADMIN");
        TenantContextHolder.setTenantId(tenantId);

        controller.getPortfolioProfitLoss(null, null);

        verify(service).getPortfolioProfitLoss(tenantId, null, null, null);
        verify(assignmentRepository, never()).findByUserId(userId);
    }

    @Test
    void propertyManagerCannotReadAnUnassignedPropertyReport() {
        UUID propertyId = UUID.randomUUID();
        authenticate("ROLE_PROPERTY_MANAGER");
        when(assignmentRepository.existsByUserIdAndPropertyId(userId, propertyId)).thenReturn(false);

        assertThatThrownBy(() -> controller.getPropertyReport(propertyId, null, null))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(service, never()).getPropertyReport(propertyId, null, null);
    }

    private void authenticate(String authority) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null,
                        List.of(new SimpleGrantedAuthority(authority))));
    }

    private UserPropertyAssignment assignment(UUID propertyId) {
        UserPropertyAssignment assignment = new UserPropertyAssignment();
        assignment.setUserId(userId);
        assignment.setPropertyId(propertyId);
        return assignment;
    }
}
