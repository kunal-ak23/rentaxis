package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.AccessDeniedException;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.auth.AppleTokenRevocationService;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.DeviceTokenRepository;
import com.datagami.rentaxis.domain.repository.GuardPropertyAssignmentRepository;
import com.datagami.rentaxis.domain.repository.NotificationRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Self-service account deletion. The refusals and the detach-before-delete
 * ordering are the parts that matter: the wrong order silently cascades the
 * renter's tenancy record away with their login.
 */
class AccountDeletionServiceTest {

    private UserRepository userRepository;
    private RenterRepository renterRepository;
    private DeviceTokenRepository deviceTokenRepository;
    private NotificationRepository notificationRepository;
    private GuardPropertyAssignmentRepository guardPropertyAssignmentRepository;
    private UserService userService;
    private AppleTokenRevocationService appleTokenRevocation;
    private AccountDeletionService service;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        renterRepository = mock(RenterRepository.class);
        deviceTokenRepository = mock(DeviceTokenRepository.class);
        notificationRepository = mock(NotificationRepository.class);
        guardPropertyAssignmentRepository = mock(GuardPropertyAssignmentRepository.class);
        userService = mock(UserService.class);
        appleTokenRevocation = mock(AppleTokenRevocationService.class);
        service = new AccountDeletionService(userRepository, renterRepository, deviceTokenRepository,
                notificationRepository, guardPropertyAssignmentRepository, userService, appleTokenRevocation);
        when(renterRepository.findByUserId(any())).thenReturn(Optional.empty());
    }

    private User user(UserRole role, UserStatus status) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail(u.getId() + "@test");
        u.setName("u");
        u.setRole(role);
        u.setStatus(status);
        u.setTenantId(role == UserRole.SUPER_ADMIN ? null : tenantId);
        when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    void unknownUserIsNotFound() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteOwnAccount(id)).isInstanceOf(NotFoundException.class);
        verify(userService, never()).deleteUser(any());
    }

    @Test
    void superAdminIsRefusedAndNothingIsTouched() {
        User admin = user(UserRole.SUPER_ADMIN, UserStatus.ACTIVE);

        assertThatThrownBy(() -> service.deleteOwnAccount(admin.getId()))
                .isInstanceOf(AccessDeniedException.class);

        verify(userService, never()).deleteUser(any());
        verify(renterRepository, never()).save(any());
        verify(deviceTokenRepository, never()).deleteByUserId(any());
    }

    @Test
    void lastActiveTenantAdminIsRefused() {
        User admin = user(UserRole.TENANT_ADMIN, UserStatus.ACTIVE);
        User inactivePeer = user(UserRole.TENANT_ADMIN, UserStatus.INACTIVE);
        when(userRepository.findByTenantIdAndRole(tenantId, UserRole.TENANT_ADMIN))
                .thenReturn(List.of(admin, inactivePeer));

        assertThatThrownBy(() -> service.deleteOwnAccount(admin.getId()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("only administrator");
        verify(userService, never()).deleteUser(any());
    }

    @Test
    void tenantAdminWithAnotherActiveAdminIsDeleted() {
        User admin = user(UserRole.TENANT_ADMIN, UserStatus.ACTIVE);
        User peer = user(UserRole.TENANT_ADMIN, UserStatus.ACTIVE);
        when(userRepository.findByTenantIdAndRole(tenantId, UserRole.TENANT_ADMIN))
                .thenReturn(List.of(admin, peer));

        service.deleteOwnAccount(admin.getId());

        verify(userService).deleteUser(admin.getId());
    }

    @Test
    void renterLoginIsDetachedFromTheTenancyRecordBeforeTheLoginRowIsDeleted() {
        User renterUser = user(UserRole.RENTER, UserStatus.ACTIVE);
        Renter renter = new Renter();
        renter.setId(UUID.randomUUID());
        renter.setUserId(renterUser.getId());
        when(renterRepository.findByUserId(renterUser.getId())).thenReturn(Optional.of(renter));

        service.deleteOwnAccount(renterUser.getId());

        ArgumentCaptor<Renter> saved = ArgumentCaptor.forClass(Renter.class);
        verify(renterRepository).save(saved.capture());
        // The renter row survives (it is the party on every lease); only the
        // pointer to the login is cleared.
        assertThat(saved.getValue()).isSameAs(renter);
        assertThat(saved.getValue().getUserId()).isNull();

        // Ordering is the whole point: renters.user_id is ON DELETE CASCADE.
        InOrder order = inOrder(renterRepository, userService);
        order.verify(renterRepository).save(any(Renter.class));
        order.verify(userService).deleteUser(renterUser.getId());
    }

    @Test
    void guardPostingsPushTokensAndNotificationsAreCleared() {
        User guard = user(UserRole.SECURITY_GUARD, UserStatus.ACTIVE);

        service.deleteOwnAccount(guard.getId());

        verify(guardPropertyAssignmentRepository).deleteByUserId(guard.getId());
        verify(deviceTokenRepository).deleteByUserId(guard.getId());
        verify(notificationRepository).deleteByUserIdUnfiltered(guard.getId());
        verify(userService).deleteUser(guard.getId());
    }

    @Test
    void storedAppleTokenIsRevokedWithTheLinkedClientId() {
        User u = user(UserRole.RENTER, UserStatus.ACTIVE);
        u.setAppleClientId("com.rentaxis.renter");
        u.setAppleRefreshToken("rt-123");
        when(appleTokenRevocation.revoke("com.rentaxis.renter", "rt-123")).thenReturn(true);

        service.deleteOwnAccount(u.getId());

        verify(appleTokenRevocation).revoke("com.rentaxis.renter", "rt-123");
        verify(userService).deleteUser(u.getId());
    }

    @Test
    void failedAppleRevocationDoesNotBlockDeletion() {
        User u = user(UserRole.RENTER, UserStatus.ACTIVE);
        u.setAppleClientId("com.rentaxis.renter");
        u.setAppleRefreshToken("rt-123");
        when(appleTokenRevocation.revoke(anyString(), anyString())).thenReturn(false);

        service.deleteOwnAccount(u.getId());

        verify(userService).deleteUser(u.getId());
    }

    @Test
    void noAppleTokenMeansNoRevocationCall() {
        User u = user(UserRole.PROPERTY_MANAGER, UserStatus.ACTIVE);

        service.deleteOwnAccount(u.getId());

        verify(appleTokenRevocation, never()).revoke(any(), any());
        verify(userService).deleteUser(u.getId());
    }
}
