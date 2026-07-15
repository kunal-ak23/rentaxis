package com.datagami.rentaxis.core.service.otp;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.domain.entity.LoginOtp;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LoginOtpRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Phone-OTP login for {@link UserRole#SECURITY_GUARD} users, backing the
 * standalone guard app. Guards have no password — they authenticate with a
 * 6-digit code delivered to their registered phone by an {@link OtpSender}.
 *
 * <p>This runs pre-authentication under {@code /api/auth/**} (permitAll), so
 * no tenant filter is enabled and the user lookups here are deliberately
 * cross-tenant: the phone number itself resolves the guard's tenant.
 *
 * <p>Security properties worth preserving when editing:
 * <ul>
 *   <li><b>Anti-enumeration</b> — {@link #requestOtp} returns silently for an
 *       unknown/inactive/non-guard phone. Never surface "no such guard".</li>
 *   <li><b>Throttle before lookup</b> — the rate check runs ahead of the user
 *       query so a throttled known phone behaves like a throttled unknown one.</li>
 *   <li><b>No oracle on verify</b> — every failure mode (missing, expired,
 *       spent, wrong code, deactivated guard) throws the identical message.</li>
 *   <li><b>Codes are bcrypt-hashed at rest</b>, never stored in plaintext.</li>
 * </ul>
 *
 * <p>Known platform caveat, out of scope here: the backend trusts client
 * {@code X-User-*} headers, so OTP verification is real but downstream session
 * integrity inherits that pre-existing issue.
 */
@Service
@Slf4j
public class OtpLoginService {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** E.164: a leading '+' then 8-15 digits. Applied after stripping spaces/hyphens. */
    private static final Pattern E164 = Pattern.compile("\\+\\d{8,15}");

    private static final int MAX_REQUESTS_PER_WINDOW = 3;
    private static final int THROTTLE_WINDOW_MINUTES = 15;
    private static final int CODE_TTL_MINUTES = 5;
    private static final int MAX_VERIFY_ATTEMPTS = 5;

    /**
     * Single message for every verification failure. Callers must not vary it —
     * distinguishing "expired" from "wrong code" hands an attacker a free oracle.
     */
    private static final String INVALID = "invalid or expired code";

    private final LoginOtpRepository otpRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final OtpSender sender;

    public OtpLoginService(LoginOtpRepository otpRepository,
                           UserRepository userRepository,
                           PasswordEncoder passwordEncoder,
                           OtpSender sender) {
        this.otpRepository = otpRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.sender = sender;
    }

    /**
     * Issues an OTP to {@code phone} if it belongs to an active security guard.
     *
     * <p>Returns normally whether or not a code was actually sent — an unknown
     * phone is indistinguishable from a known one by response or behaviour.
     *
     * @throws BusinessRuleViolationException if the phone is not E.164 (400)
     * @throws ResponseStatusException        429 once the per-phone rate limit is hit
     */
    @Transactional
    public void requestOtp(String phone) {
        String normalized = normalize(phone);

        // Throttle FIRST: this must not depend on whether the guard exists, or
        // the differing work would leak existence via timing/behaviour.
        Instant windowStart = Instant.now().minus(THROTTLE_WINDOW_MINUTES, ChronoUnit.MINUTES);
        if (otpRepository.countByPhoneNumberAndCreatedAtAfter(normalized, windowStart) >= MAX_REQUESTS_PER_WINDOW) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many code requests. Please try again later.");
        }

        Optional<User> guard = resolveActiveGuard(normalized);
        if (guard.isEmpty()) {
            // Anti-enumeration: no send, no save, no error.
            log.debug("OTP requested for a phone with no active security guard");
            return;
        }

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));

        LoginOtp otp = new LoginOtp();
        otp.setPhoneNumber(normalized);
        otp.setCodeHash(passwordEncoder.encode(code));
        otp.setExpiresAt(Instant.now().plus(CODE_TTL_MINUTES, ChronoUnit.MINUTES));
        otpRepository.save(otp);

        sender.send(normalized, code);
    }

    /**
     * Verifies {@code code} against the newest unconsumed OTP for {@code phone}
     * and returns the authenticated guard.
     *
     * <p><b>{@code noRollbackFor} is load-bearing — do not remove it.</b> This
     * method's two security writes (the failed-attempt increment, and marking a
     * correct code consumed) are both followed by a thrown
     * BadCredentialsException. Under Spring's default rollback-on-RuntimeException
     * they would be silently reverted, which would (a) reset the attempt counter
     * on every failure, giving an attacker unlimited guesses against the 5-try
     * cap, and (b) un-consume a correct code used by a deactivated guard, making
     * it replayable. Mocked-repository unit tests cannot catch either regression.
     *
     * @throws BusinessRuleViolationException if the phone is not E.164 (400)
     * @throws BadCredentialsException        on any verification failure (401)
     */
    @Transactional(noRollbackFor = BadCredentialsException.class)
    public User verifyOtp(String phone, String code) {
        String normalized = normalize(phone);

        LoginOtp otp = otpRepository
                .findTopByPhoneNumberAndConsumedAtIsNullOrderByCreatedAtDesc(normalized)
                .orElseThrow(() -> new BadCredentialsException(INVALID));

        // Expiry and the attempt cap are checked before the bcrypt compare, so a
        // spent budget rejects even a correct code and burns no further CPU.
        if (otp.getExpiresAt().isBefore(Instant.now()) || otp.getAttemptCount() >= MAX_VERIFY_ATTEMPTS) {
            throw new BadCredentialsException(INVALID);
        }

        if (code == null || !passwordEncoder.matches(code, otp.getCodeHash())) {
            otp.setAttemptCount(otp.getAttemptCount() + 1);
            otpRepository.save(otp);
            throw new BadCredentialsException(INVALID);
        }

        // Consume before resolving the user: a correct code is single-use even if
        // the guard turns out to be deactivated, so it cannot be replayed.
        otp.setConsumedAt(Instant.now());
        otpRepository.save(otp);

        return resolveActiveGuard(normalized)
                .orElseThrow(() -> new BadCredentialsException(INVALID));
    }

    /**
     * Resolves the single active security guard owning this phone.
     *
     * <p>Phone numbers carry no uniqueness constraint, so the query returns a
     * list. An ambiguous phone (two active guards, e.g. a shared gatehouse
     * handset across tenants) is rejected rather than guessed — picking one
     * would silently log the caller into an arbitrary tenant.
     */
    private Optional<User> resolveActiveGuard(String normalizedPhone) {
        List<User> active = userRepository.findByPhoneNumberAndRole(normalizedPhone, UserRole.SECURITY_GUARD)
                .stream()
                .filter(u -> u.getStatus() == UserStatus.ACTIVE)
                .toList();

        if (active.size() > 1) {
            log.warn("Phone number maps to {} active security guards — refusing to guess an identity",
                    active.size());
            return Optional.empty();
        }
        return active.stream().findFirst();
    }

    private String normalize(String phone) {
        if (phone == null || phone.isBlank()) {
            throw new BusinessRuleViolationException("Phone number is required");
        }
        String normalized = phone.replaceAll("[\\s-]", "");
        if (!E164.matcher(normalized).matches()) {
            throw new BusinessRuleViolationException(
                    "Phone number must be in international format, e.g. +971501234567");
        }
        return normalized;
    }
}
