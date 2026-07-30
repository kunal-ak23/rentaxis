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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FirebaseGuardAuthServiceTest {

    @Mock FirebaseIdTokenVerifier tokenVerifier;
    @Mock UserRepository userRepository;

    FirebaseGuardAuthService service;

    @BeforeEach
    void setUp() {
        service = new FirebaseGuardAuthService(tokenVerifier, userRepository);
    }

    @Test
    void verifiedPhoneAuthenticatesItsOnlyActiveGuard() {
        User guard = guard(UserStatus.ACTIVE);
        when(tokenVerifier.verify("firebase-token"))
                .thenReturn(new FirebaseIdTokenVerifier.VerifiedPhoneIdentity(
                        "firebase-uid", "+971501234567"));
        when(userRepository.findByPhoneNumberAndRole(
                "+971501234567", UserRole.SECURITY_GUARD))
                .thenReturn(List.of(guard));

        assertThat(service.authenticate("firebase-token")).isSameAs(guard);
        verify(userRepository).findByPhoneNumberAndRole(
                "+971501234567", UserRole.SECURITY_GUARD);
    }

    @Test
    void formattedFirebaseClaimIsNormalizedBeforeLookup() {
        User guard = guard(UserStatus.ACTIVE);
        when(tokenVerifier.verify("firebase-token"))
                .thenReturn(new FirebaseIdTokenVerifier.VerifiedPhoneIdentity(
                        "firebase-uid", "+971 50-123 4567"));
        when(userRepository.findByPhoneNumberAndRole(
                "+971501234567", UserRole.SECURITY_GUARD))
                .thenReturn(List.of(guard));

        assertThat(service.authenticate("firebase-token")).isSameAs(guard);
    }

    @Test
    void inactiveGuardIsRejectedWithGenericCredentialsFailure() {
        when(tokenVerifier.verify("firebase-token"))
                .thenReturn(new FirebaseIdTokenVerifier.VerifiedPhoneIdentity(
                        "firebase-uid", "+971501234567"));
        when(userRepository.findByPhoneNumberAndRole(
                "+971501234567", UserRole.SECURITY_GUARD))
                .thenReturn(List.of(guard(UserStatus.INACTIVE)));

        assertThatThrownBy(() -> service.authenticate("firebase-token"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid credentials");
    }

    @Test
    void unknownPhoneIsRejectedWithTheSameGenericFailure() {
        when(tokenVerifier.verify("firebase-token"))
                .thenReturn(new FirebaseIdTokenVerifier.VerifiedPhoneIdentity(
                        "firebase-uid", "+971501234567"));
        when(userRepository.findByPhoneNumberAndRole(
                "+971501234567", UserRole.SECURITY_GUARD))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.authenticate("firebase-token"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid credentials");
    }

    @Test
    void malformedSignedPhoneClaimIsRejected() {
        when(tokenVerifier.verify("firebase-token"))
                .thenReturn(new FirebaseIdTokenVerifier.VerifiedPhoneIdentity(
                        "firebase-uid", "0501234567"));

        assertThatThrownBy(() -> service.authenticate("firebase-token"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid credentials");
    }

    private static User guard(UserStatus status) {
        User user = new User();
        user.setRole(UserRole.SECURITY_GUARD);
        user.setStatus(status);
        user.setPhoneNumber("+971501234567");
        return user;
    }
}
