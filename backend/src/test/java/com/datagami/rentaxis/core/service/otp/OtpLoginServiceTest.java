package com.datagami.rentaxis.core.service.otp;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.domain.entity.LoginOtp;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LoginOtpRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OtpLoginService}: OTP issuance (throttling,
 * anti-enumeration, normalization, hashing) and verification (expiry,
 * attempt cap, replay, post-issue deactivation).
 *
 * <p>Uses a real {@link BCryptPasswordEncoder} rather than a mock so that
 * hashing/matching is genuinely exercised — a mocked encoder would happily
 * "verify" a plaintext-stored code.
 */
@ExtendWith(MockitoExtension.class)
class OtpLoginServiceTest {

    private static final String RAW_PHONE = "+971 50 123-4567";
    private static final String PHONE = "+971501234567";

    @Mock LoginOtpRepository otpRepository;
    @Mock UserRepository userRepository;
    @Mock OtpSender sender;

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    private OtpLoginService service;

    @BeforeEach
    void setUp() {
        service = new OtpLoginService(otpRepository, userRepository, encoder, sender);
    }

    private User guard(UserStatus status) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail("guard@example.com");
        u.setName("Gate Guard");
        u.setRole(UserRole.SECURITY_GUARD);
        u.setStatus(status);
        u.setPhoneNumber(PHONE);
        u.setTenantId(UUID.randomUUID());
        return u;
    }

    private LoginOtp otp(String codeHash, Instant expiresAt, int attemptCount) {
        LoginOtp o = new LoginOtp();
        o.setId(UUID.randomUUID());
        o.setPhoneNumber(PHONE);
        o.setCodeHash(codeHash);
        o.setExpiresAt(expiresAt);
        o.setAttemptCount(attemptCount);
        return o;
    }

    // --- requestOtp ---

    @Test
    void requestSendsCodeForActiveGuard() {
        when(userRepository.findByPhoneNumberAndRole(PHONE, UserRole.SECURITY_GUARD))
                .thenReturn(List.of(guard(UserStatus.ACTIVE)));

        service.requestOtp(PHONE);

        ArgumentCaptor<LoginOtp> saved = ArgumentCaptor.forClass(LoginOtp.class);
        verify(otpRepository).save(saved.capture());
        ArgumentCaptor<String> sentCode = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq(PHONE), sentCode.capture());

        assertThat(sentCode.getValue()).matches("\\d{6}");
        // The stored hash must not be the raw code, and must genuinely verify it.
        assertThat(saved.getValue().getCodeHash()).isNotEqualTo(sentCode.getValue());
        assertThat(saved.getValue().getCodeHash()).startsWith("$2");
        assertThat(encoder.matches(sentCode.getValue(), saved.getValue().getCodeHash())).isTrue();
        assertThat(saved.getValue().getPhoneNumber()).isEqualTo(PHONE);
        assertThat(saved.getValue().getExpiresAt()).isAfter(Instant.now());
        assertThat(saved.getValue().getExpiresAt()).isBefore(Instant.now().plus(6, ChronoUnit.MINUTES));
    }

    @Test
    void requestIsSilentForUnknownPhone() {
        when(userRepository.findByPhoneNumberAndRole(PHONE, UserRole.SECURITY_GUARD))
                .thenReturn(List.of());

        service.requestOtp(PHONE); // must not throw — anti-enumeration

        verify(otpRepository, never()).save(any());
        verifyNoInteractions(sender);
    }

    @Test
    void requestIsSilentForInactiveGuard() {
        when(userRepository.findByPhoneNumberAndRole(PHONE, UserRole.SECURITY_GUARD))
                .thenReturn(List.of(guard(UserStatus.INACTIVE)));

        service.requestOtp(PHONE);

        verify(otpRepository, never()).save(any());
        verifyNoInteractions(sender);
    }

    @Test
    void requestIgnoresNonGuardUsers() {
        // A RENTER owns this phone. The repository query is role-scoped, so it
        // returns nothing — assert we actually asked for SECURITY_GUARD.
        when(userRepository.findByPhoneNumberAndRole(PHONE, UserRole.SECURITY_GUARD))
                .thenReturn(List.of());

        service.requestOtp(PHONE);

        verify(userRepository).findByPhoneNumberAndRole(PHONE, UserRole.SECURITY_GUARD);
        verify(otpRepository, never()).save(any());
        verifyNoInteractions(sender);
    }

    @Test
    void requestThrottledAfterThreeInFifteenMinutes() {
        when(otpRepository.countByPhoneNumberAndCreatedAtAfter(eq(PHONE), any())).thenReturn(3L);

        assertThatThrownBy(() -> service.requestOtp(PHONE))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("429");

        verify(otpRepository, never()).save(any());
        verifyNoInteractions(sender);
        // Throttle must short-circuit BEFORE the user lookup, so a known and an
        // unknown phone are indistinguishable once throttled.
        verify(userRepository, never()).findByPhoneNumberAndRole(anyString(), any());
    }

    @Test
    void requestRejectsMalformedPhone() {
        assertThatThrownBy(() -> service.requestOtp("12345"))
                .isInstanceOf(BusinessRuleViolationException.class);

        verifyNoInteractions(otpRepository);
        verifyNoInteractions(userRepository);
        verifyNoInteractions(sender);
    }

    @Test
    void requestNormalizesPhoneBeforeUse() {
        when(userRepository.findByPhoneNumberAndRole(PHONE, UserRole.SECURITY_GUARD))
                .thenReturn(List.of(guard(UserStatus.ACTIVE)));

        service.requestOtp(RAW_PHONE);

        verify(otpRepository).countByPhoneNumberAndCreatedAtAfter(eq(PHONE), any());
        verify(userRepository).findByPhoneNumberAndRole(PHONE, UserRole.SECURITY_GUARD);
        verify(sender).send(eq(PHONE), anyString());
        ArgumentCaptor<LoginOtp> saved = ArgumentCaptor.forClass(LoginOtp.class);
        verify(otpRepository).save(saved.capture());
        assertThat(saved.getValue().getPhoneNumber()).isEqualTo(PHONE);
    }

    // --- verifyOtp ---

    @Test
    void verifyHappyPathConsumesOtpAndReturnsUser() {
        User g = guard(UserStatus.ACTIVE);
        LoginOtp stored = otp(encoder.encode("123456"), Instant.now().plus(5, ChronoUnit.MINUTES), 0);
        when(otpRepository.findTopByPhoneNumberAndConsumedAtIsNullOrderByCreatedAtDesc(PHONE))
                .thenReturn(Optional.of(stored));
        when(userRepository.findByPhoneNumberAndRole(PHONE, UserRole.SECURITY_GUARD))
                .thenReturn(List.of(g));

        User result = service.verifyOtp(PHONE, "123456");

        assertThat(result.getId()).isEqualTo(g.getId());
        ArgumentCaptor<LoginOtp> saved = ArgumentCaptor.forClass(LoginOtp.class);
        verify(otpRepository).save(saved.capture());
        assertThat(saved.getValue().getConsumedAt()).isNotNull();
    }

    @Test
    void verifyWrongCodeIncrementsAttempts() {
        LoginOtp stored = otp(encoder.encode("123456"), Instant.now().plus(5, ChronoUnit.MINUTES), 1);
        when(otpRepository.findTopByPhoneNumberAndConsumedAtIsNullOrderByCreatedAtDesc(PHONE))
                .thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.verifyOtp(PHONE, "999999"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("invalid or expired code");

        ArgumentCaptor<LoginOtp> saved = ArgumentCaptor.forClass(LoginOtp.class);
        verify(otpRepository).save(saved.capture());
        assertThat(saved.getValue().getAttemptCount()).isEqualTo(2);
        assertThat(saved.getValue().getConsumedAt()).isNull();
    }

    @Test
    void verifyFailsAfterFiveAttempts() {
        // Correct code, but the attempt budget is already spent.
        LoginOtp stored = otp(encoder.encode("123456"), Instant.now().plus(5, ChronoUnit.MINUTES), 5);
        when(otpRepository.findTopByPhoneNumberAndConsumedAtIsNullOrderByCreatedAtDesc(PHONE))
                .thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.verifyOtp(PHONE, "123456"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("invalid or expired code");

        verify(otpRepository, never()).save(any());
        verifyNoInteractions(userRepository);
    }

    @Test
    void verifyFailsWhenExpired() {
        LoginOtp stored = otp(encoder.encode("123456"), Instant.now().minus(1, ChronoUnit.MINUTES), 0);
        when(otpRepository.findTopByPhoneNumberAndConsumedAtIsNullOrderByCreatedAtDesc(PHONE))
                .thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.verifyOtp(PHONE, "123456"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("invalid or expired code");

        verify(otpRepository, never()).save(any());
        verifyNoInteractions(userRepository);
    }

    @Test
    void verifyFailsWhenAlreadyConsumed() {
        // The consumed-at-is-null query returns nothing once the code is spent.
        when(otpRepository.findTopByPhoneNumberAndConsumedAtIsNullOrderByCreatedAtDesc(PHONE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyOtp(PHONE, "123456"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("invalid or expired code");

        verify(otpRepository, never()).save(any());
        verifyNoInteractions(userRepository);
    }

    /**
     * Guards the {@code noRollbackFor} on verifyOtp. Everything this class
     * asserts about the attempt counter and consumption is written through a
     * mocked repository, so it stays green even if those writes are rolled
     * back for real — which is exactly what Spring's default
     * rollback-on-RuntimeException does, since every failure path here throws.
     * That regression defeats the brute-force cap silently and was only caught
     * by hitting a live Postgres. Hence this reflective check.
     */
    @Test
    void verifyOtpDoesNotRollBackSecurityWritesOnFailure() throws NoSuchMethodException {
        org.springframework.transaction.annotation.Transactional tx =
                OtpLoginService.class
                        .getMethod("verifyOtp", String.class, String.class)
                        .getAnnotation(org.springframework.transaction.annotation.Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.noRollbackFor())
                .as("attempt-count increment and code consumption must survive the thrown "
                        + "BadCredentialsException, or the 5-attempt cap is unenforceable")
                .contains(BadCredentialsException.class);
    }

    @Test
    void verifyRejectsWhenGuardDeactivatedAfterRequest() {
        LoginOtp stored = otp(encoder.encode("123456"), Instant.now().plus(5, ChronoUnit.MINUTES), 0);
        when(otpRepository.findTopByPhoneNumberAndConsumedAtIsNullOrderByCreatedAtDesc(PHONE))
                .thenReturn(Optional.of(stored));
        when(userRepository.findByPhoneNumberAndRole(PHONE, UserRole.SECURITY_GUARD))
                .thenReturn(List.of(guard(UserStatus.INACTIVE)));

        assertThatThrownBy(() -> service.verifyOtp(PHONE, "123456"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("invalid or expired code");
    }
}
