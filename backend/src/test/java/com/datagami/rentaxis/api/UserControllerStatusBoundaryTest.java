package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.exception.AccessDeniedException;
import com.datagami.rentaxis.core.service.UserReferenceReleaser;
import com.datagami.rentaxis.core.service.UserService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S17-01: the deactivate/reactivate endpoints apply the same target boundary as
 * edit and delete — a TENANT_ADMIN reaches only rows of their own organisation.
 *
 * <p>Over HTTP ({@code MembershipStatusIT}) a foreign row is also hidden by the
 * tenant filter inside the service, so that test alone cannot show this layer
 * holds; here the service is a mock and the controller's own check is all there
 * is. The service is never called for a foreign row.
 */
class UserControllerStatusBoundaryTest {

    private final UserService users = mock(UserService.class);
    private final UserController controller = new UserController(users, mock(UserReferenceReleaser.class));
    private final UUID orgA = UUID.randomUUID();
    private final UUID callerId = UUID.randomUUID();

    @BeforeEach
    void actAsOrgAAdmin() {
        TenantContextHolder.setTenantId(orgA);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                callerId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN"))));
    }

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    private User row(UUID tenantId, UserRole role) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setTenantId(tenantId);
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setEmail("x@test.invalid");
        u.setName("x");
        when(users.findById(u.getId())).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    void anotherOrganisationsRowIsRefusedBeforeTheServiceIsCalled() {
        User foreign = row(UUID.randomUUID(), UserRole.RENTER);

        assertThatThrownBy(() -> controller.deactivate(foreign.getId())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.reactivate(foreign.getId())).isInstanceOf(AccessDeniedException.class);
        verify(users, never()).deactivate(any(), any());
        verify(users, never()).reactivate(any(), any());
    }

    @Test
    void aPlatformAdminsRowIsRefusedToAnOrganisationAdmin() {
        User platform = row(null, UserRole.SUPER_ADMIN);

        assertThatThrownBy(() -> controller.deactivate(platform.getId())).isInstanceOf(AccessDeniedException.class);
        verify(users, never()).deactivate(any(), any());
    }

    @Test
    void anOwnOrganisationsRowReachesTheServiceWithTheCallersId() {
        User own = row(orgA, UserRole.RENTER);
        when(users.deactivate(own.getId(), callerId)).thenReturn(own);
        when(users.reactivate(own.getId(), callerId))
                .thenReturn(new UserService.Reactivated(own, UserService.LoginOnReactivation.INVITED));

        assertThat(controller.deactivate(own.getId()).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.reactivate(own.getId()).getBody().getReactivationLogin()).isEqualTo("INVITED");
        verify(users).deactivate(own.getId(), callerId);
    }
}
