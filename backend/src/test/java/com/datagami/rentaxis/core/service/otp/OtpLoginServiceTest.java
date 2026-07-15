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
import org.springframework.context.ApplicationEventPublisher;
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
import static org.mockito.ArgumentMatchers.anyInt;
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
    @Mock ApplicationEventPublisher events;

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    private OtpLoginService service;

    @BeforeEach
    void setUp() {
        service = new OtpLoginService(otpRepository, userRepository, encoder, events);
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

        // Delivery is handed to OtpDeliveryListener via an event rather than called
        // inline — see that class for why the send must not sit inside this
        // transaction. The code still has to reach it intact.
        ArgumentCaptor<OtpRequestedEvent> published = ArgumentCaptor.forClass(OtpRequestedEvent.class);
        verify(events).publishEvent(published.capture());
        String sentCode = published.getValue().code();

        assertThat(published.getValue().phoneNumber()).isEqualTo(PHONE);
        assertThat(sentCode).matches("\\d{6}");
        // The stored hash must not be the raw code, and must genuinely verify it.
        assertThat(saved.getValue().getCodeHash()).isNotEqualTo(sentCode);
        assertThat(saved.getValue().getCodeHash()).startsWith("$2");
        assertThat(encoder.matches(sentCode, saved.getValue().getCodeHash())).isTrue();
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
        verifyNoInteractions(events);
    }

    @Test
    void requestIsSilentForInactiveGuard() {
        when(userRepository.findByPhoneNumberAndRole(PHONE, UserRole.SECURITY_GUARD))
                .thenReturn(List.of(guard(UserStatus.INACTIVE)));

        service.requestOtp(PHONE);

        verify(otpRepository, never()).save(any());
        verifyNoInteractions(events);
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
        verifyNoInteractions(events);
    }

    @Test
    void requestThrottledAfterThreeInFifteenMinutes() {
        when(otpRepository.countByPhoneNumberAndCreatedAtAfter(eq(PHONE), any())).thenReturn(3L);

        assertThatThrownBy(() -> service.requestOtp(PHONE))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("429");

        verify(otpRepository, never()).save(any());
        verifyNoInteractions(events);
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
        verifyNoInteractions(events);
    }

    @Test
    void requestNormalizesPhoneBeforeUse() {
        when(userRepository.findByPhoneNumberAndRole(PHONE, UserRole.SECURITY_GUARD))
                .thenReturn(List.of(guard(UserStatus.ACTIVE)));

        service.requestOtp(RAW_PHONE);

        verify(otpRepository).countByPhoneNumberAndCreatedAtAfter(eq(PHONE), any());
        verify(userRepository).findByPhoneNumberAndRole(PHONE, UserRole.SECURITY_GUARD);
        ArgumentCaptor<OtpRequestedEvent> published = ArgumentCaptor.forClass(OtpRequestedEvent.class);
        verify(events).publishEvent(published.capture());
        assertThat(published.getValue().phoneNumber()).isEqualTo(PHONE);
        ArgumentCaptor<LoginOtp> saved = ArgumentCaptor.forClass(LoginOtp.class);
        verify(otpRepository).save(saved.capture());
        assertThat(saved.getValue().getPhoneNumber()).isEqualTo(PHONE);
    }


    @Test
    void requestInvalidatesPriorOutstandingCodes() {
        // Up to three codes can be live at once. Without retiring the older ones,
        // consuming the newest promotes a previous code back to "top" — where it
        // would still verify.
        when(userRepository.findByPhoneNumberAndRole(PHONE, UserRole.SECURITY_GUARD))
                .thenReturn(List.of(guard(UserStatus.ACTIVE)));

        service.requestOtp(PHONE);

        verify(otpRepository).invalidateOutstanding(eq(PHONE), any());
    }

    // --- verifyOtp ---

    @Test
    void verifyHappyPathConsumesOtpAndReturnsUser() {
        User g = guard(UserStatus.ACTIVE);
        LoginOtp stored = otp(encoder.encode("123456"), Instant.now().plus(5, ChronoUnit.MINUTES), 0);
        when(otpRepository.findTopByPhoneNumberAndConsumedAtIsNullOrderByCreatedAtDesc(PHONE))
                .thenReturn(Optional.of(stored));
        when(otpRepository.claimAttempt(stored.getId(), 5)).thenReturn(1);
        when(otpRepository.consume(eq(stored.getId()), any())).thenReturn(1);
        when(userRepository.findByPhoneNumberAndRole(PHONE, UserRole.SECURITY_GUARD))
                .thenReturn(List.of(g));

        User result = service.verifyOtp(PHONE, "123456");

        assertThat(result.getId()).isEqualTo(g.getId());
        verify(otpRepository).consume(eq(stored.getId()), any());
    }

    @Test
    void verifyWrongCodeClaimsAnAttempt() {
        LoginOtp stored = otp(encoder.encode("123456"), Instant.now().plus(5, ChronoUnit.MINUTES), 1);
        when(otpRepository.findTopByPhoneNumberAndConsumedAtIsNullOrderByCreatedAtDesc(PHONE))
                .thenReturn(Optional.of(stored));
        when(otpRepository.claimAttempt(stored.getId(), 5)).thenReturn(1);

        assertThatThrownBy(() -> service.verifyOtp(PHONE, "999999"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("invalid or expired code");

        // The attempt must be spent atomically in the DB, not via a read-modify-write
        // on the entity — and the code must not be consumed.
        verify(otpRepository).claimAttempt(stored.getId(), 5);
        verify(otpRepository, never()).consume(any(), any());
        verify(otpRepository, never()).save(any());
    }

    @Test
    void verifyClaimsAttemptBeforeComparingCode() {
        // Ordering is the whole point: claim-then-compare spends the budget even if
        // the process dies mid-verify. Compare-then-claim would let an attacker
        // abort after the compare and guess for free.
        LoginOtp stored = otp(encoder.encode("123456"), Instant.now().plus(5, ChronoUnit.MINUTES), 0);
        when(otpRepository.findTopByPhoneNumberAndConsumedAtIsNullOrderByCreatedAtDesc(PHONE))
                .thenReturn(Optional.of(stored));
        when(otpRepository.claimAttempt(stored.getId(), 5)).thenReturn(0);

        assertThatThrownBy(() -> service.verifyOtp(PHONE, "123456"))
                .isInstanceOf(BadCredentialsException.class);

        verify(otpRepository).claimAttempt(stored.getId(), 5);
    }

    @Test
    void verifyFailsAfterFiveAttempts() {
        // Budget spent: claimAttempt updates 0 rows, so even the CORRECT code loses.
        LoginOtp stored = otp(encoder.encode("123456"), Instant.now().plus(5, ChronoUnit.MINUTES), 5);
        when(otpRepository.findTopByPhoneNumberAndConsumedAtIsNullOrderByCreatedAtDesc(PHONE))
                .thenReturn(Optional.of(stored));
        when(otpRepository.claimAttempt(stored.getId(), 5)).thenReturn(0);

        assertThatThrownBy(() -> service.verifyOtp(PHONE, "123456"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("invalid or expired code");

        verify(otpRepository, never()).consume(any(), any());
        verifyNoInteractions(userRepository);
    }

    @Test
    void verifyRejectedOnceHourlyCapReached() {
        // The per-code cap is per-row, so a fresh code resets it. This cross-code
        // cap is the only thing bounding a sustained attack.
        when(otpRepository.countRecentAttempts(eq(PHONE), any())).thenReturn(10L);

        assertThatThrownBy(() -> service.verifyOtp(PHONE, "123456"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("429");

        verify(otpRepository, never()).claimAttempt(any(), anyInt());
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

        verify(otpRepository, never()).claimAttempt(any(), anyInt());
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

        verify(otpRepository, never()).claimAttempt(any(), anyInt());
        verifyNoInteractions(userRepository);
    }

    @Test
    void verifyRejectsWhenConsumeRacesAnotherVerify() {
        // Two correct-code verifies race; only the one whose consume updates a row
        // may log in.
        LoginOtp stored = otp(encoder.encode("123456"), Instant.now().plus(5, ChronoUnit.MINUTES), 0);
        when(otpRepository.findTopByPhoneNumberAndConsumedAtIsNullOrderByCreatedAtDesc(PHONE))
                .thenReturn(Optional.of(stored));
        when(otpRepository.claimAttempt(stored.getId(), 5)).thenReturn(1);
        when(otpRepository.consume(eq(stored.getId()), any())).thenReturn(0);

        assertThatThrownBy(() -> service.verifyOtp(PHONE, "123456"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("invalid or expired code");

        verifyNoInteractions(userRepository);
    }

    @Test
    void verifyRejectsWhenGuardDeactivatedAfterRequest() {
        LoginOtp stored = otp(encoder.encode("123456"), Instant.now().plus(5, ChronoUnit.MINUTES), 0);
        when(otpRepository.findTopByPhoneNumberAndConsumedAtIsNullOrderByCreatedAtDesc(PHONE))
                .thenReturn(Optional.of(stored));
        when(otpRepository.claimAttempt(stored.getId(), 5)).thenReturn(1);
        when(otpRepository.consume(eq(stored.getId()), any())).thenReturn(1);
        when(userRepository.findByPhoneNumberAndRole(PHONE, UserRole.SECURITY_GUARD))
                .thenReturn(List.of(guard(UserStatus.INACTIVE)));

        assertThatThrownBy(() -> service.verifyOtp(PHONE, "123456"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("invalid or expired code");
    }
}
