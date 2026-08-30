package com.datagami.rentaxis.core.service.auth;

import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppleAuthServiceTest {

    @Mock AppleIdTokenVerifier tokenVerifier;
    @Mock UserRepository userRepository;
    AppleAuthService service;

    @BeforeEach
    void setUp() {
        service = new AppleAuthService(tokenVerifier, userRepository);
    }

    @Test
    void firstResidentLoginLinksAUniqueVerifiedEmail() {
        User renter = user(UserRole.RENTER, "resident@example.com");
        identity("com.rentaxis.renter", "resident@example.com", true);
        when(userRepository.findByAppleClientIdAndAppleSubject(
                "com.rentaxis.renter", "apple-subject")).thenReturn(Optional.empty());
        when(userRepository.findAllByEmail("resident@example.com")).thenReturn(List.of(renter));
        when(userRepository.saveAndFlush(renter)).thenReturn(renter);

        assertThat(service.authenticate("token", "nonce", null)).isSameAs(renter);
        assertThat(renter.getAppleSubject()).isEqualTo("apple-subject");
        assertThat(renter.getAppleClientId()).isEqualTo("com.rentaxis.renter");
        verify(userRepository).saveAndFlush(renter);
    }

    @Test
    void linkedIdentityDoesNotDependOnAppleReturningEmailAgain() {
        User manager = user(UserRole.PROPERTY_MANAGER, "manager@example.com");
        identity("com.rentaxis.manager", null, false);
        when(userRepository.findByAppleClientIdAndAppleSubject(
                "com.rentaxis.manager", "apple-subject")).thenReturn(Optional.of(manager));

        assertThat(service.authenticate("token", "nonce", null)).isSameAs(manager);
    }

    @Test
    void residentTokenCannotAuthenticateAManagerAccount() {
        User manager = user(UserRole.PROPERTY_MANAGER, "manager@example.com");
        identity("com.rentaxis.renter", "manager@example.com", true);
        when(userRepository.findByAppleClientIdAndAppleSubject(
                "com.rentaxis.renter", "apple-subject")).thenReturn(Optional.empty());
        when(userRepository.findAllByEmail("manager@example.com")).thenReturn(List.of(manager));

        assertThatThrownBy(() -> service.authenticate("token", "nonce", null))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid credentials");
    }

    @Test
    void duplicateEmailRequiresTenantSelectionBeforeLinking() {
        User a = user(UserRole.RENTER, "shared@example.com");
        User b = user(UserRole.RENTER, "shared@example.com");
        identity("com.rentaxis.renter", "shared@example.com", true);
        when(userRepository.findByAppleClientIdAndAppleSubject(
                "com.rentaxis.renter", "apple-subject")).thenReturn(Optional.empty());
        when(userRepository.findAllByEmail("shared@example.com")).thenReturn(List.of(a, b));

        assertThatThrownBy(() -> service.authenticate("token", "nonce", null))
                .isInstanceOf(AppleAuthService.AmbiguousAppleIdentityException.class);
    }

    private void identity(String clientId, String email, boolean verified) {
        when(tokenVerifier.verify("token", "nonce"))
                .thenReturn(new AppleIdTokenVerifier.VerifiedAppleIdentity(
                        "apple-subject", clientId, email, verified));
    }

    private static User user(UserRole role, String email) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setTenantId(UUID.randomUUID());
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setEmail(email);
        return user;
    }
}
