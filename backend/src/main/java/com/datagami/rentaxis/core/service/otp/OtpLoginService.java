package com.datagami.rentaxis.core.service.otp;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.util.PhoneNumbers;
import com.datagami.rentaxis.domain.entity.LoginOtp;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LoginOtpRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Phone-OTP login for {@link UserRole#SECURITY_GUARD} users, backing the
 * standalone guard app. Guards have no password — they authenticate with a
 * 6-digit code delivered to their registered phone by an {@link OtpSender}.
 *
 * <p>This runs pre-authentication under {@code /api/auth/**} (permitAll), so
 * no tenant filter is enabled and the user lookups here are deliberately
 * cross-tenant: the phone number itself resolves the guard's tenant.
 *
 * <h2>Guarantees</h2>
 * <ul>
 *   <li><b>Anti-enumeration, per call, until a throttle engages</b> —
 *       {@link #requestOtp} returns 200 for an unknown, inactive, or non-guard
 *       phone exactly as for a real one, and {@link #verifyOtp} throws the single
 *       constant message {@value #INVALID} for every failure mode, so no
 *       individual 401 tells the caller anything. That is the whole of the claim:
 *       it does <em>not</em> extend to the throttled responses. Once either cap
 *       trips, both the status and the body identify a registered phone — see
 *       "429 oracle" under Known gaps.</li>
 *   <li><b>Throttle before lookup</b> — the rate check precedes the user query,
 *       so a throttled known phone behaves like a throttled unknown one.</li>
 *   <li><b>Codes are bcrypt-hashed at rest</b> and single-use. The plaintext
 *       exists only in memory, in the request and in {@link OtpRequestedEvent}
 *       until delivery — it is never written to a table, which is why delivery
 *       is an after-commit event and not an outbox row (see
 *       {@link OtpDeliveryListener}).</li>
 *   <li><b>Guess budget is enforced atomically</b> — see
 *       {@link LoginOtpRepository#claimAttempt}.</li>
 *   <li><b>The issuance throttle cannot be disabled by a failing sender</b> —
 *       delivery happens after commit, so the row that the throttle counts is
 *       durable before any network call is attempted. See
 *       {@link OtpDeliveryListener} for why this ordering is not optional.</li>
 * </ul>
 *
 * <h2>Known gaps — do not read the above as more than it says</h2>
 * <ul>
 *   <li><b>429 oracle — on status <em>and</em> body, from both throttles.</b>
 *       Both caps count rows that only a registered phone can ever have, so both
 *       leak the same way:
 *       <ul>
 *         <li>{@link #verifyOtp}'s per-phone hourly cap counts attempt rows. An
 *             unregistered phone never has any, can never reach
 *             {@value #MAX_ATTEMPTS_PER_HOUR}, and so always answers 401. A
 *             registered one reaches 429 in about eleven calls (request a code,
 *             then guess).</li>
 *         <li>{@link #requestOtp}'s issuance throttle counts {@code login_otps}
 *             rows, which are only written for a registered phone — so a fourth
 *             request inside {@value #THROTTLE_WINDOW_MINUTES} minutes answers
 *             429 for a real guard and 200 forever for an unknown number. This
 *             one is cheaper to exploit than the verify cap: three calls, no
 *             guessing.</li>
 *       </ul>
 *       The body leaks too, not just the status: these throw
 *       {@link ResponseStatusException}, and {@code GlobalExceptionHandler}
 *       copies {@code getReason()} verbatim into the JSON {@code message}. So
 *       "Too many code requests. Please try again later." and "Too many
 *       attempts. Please request a new code later." are both strings an
 *       unregistered phone can never elicit. Do not read the constant-401
 *       guarantee above as covering these.
 *       <p>Accepted as the cost of the caps themselves, which address the larger
 *       risk. Closing it means either faking 429s for unknown phones — which
 *       needs per-phone state for numbers with no rows, i.e. a store an attacker
 *       can fill — or dropping the caps. Per-IP limiting in
 *       {@code PublicRateLimitFilter} is what bounds mass enumeration through
 *       this oracle.</li>
 *   <li><b>Timing oracle on request.</b> Narrowed, not closed. Delivery no longer
 *       contributes: it runs after commit on {@code otpExecutor}, so no network
 *       call is on the response path regardless of channel. What remains is that
 *       an unknown phone returns early while a known one does a bcrypt hash
 *       (~50-100ms) plus an insert. That difference is still measurable — it is
 *       just now bounded by local CPU rather than by ACS's latency, and it no
 *       longer grows when the WhatsApp sender is switched on. Closing it properly
 *       means doing equivalent work on the unknown-phone path (a dummy hash).</li>
 *   <li><b>Guard lockout by a third party.</b> The hourly cap is per phone, not
 *       per caller, so anyone who knows a guard's number can request two codes
 *       and spend the {@value #MAX_ATTEMPTS_PER_HOUR} guesses on them — after
 *       which the real guard gets 429 on every verify for up to an hour.
 *       Requesting a fresh code does not help: a new row starts at
 *       {@code attemptCount = 0} but the cap sums attempts across rows in the
 *       window, and the spent ones stay in it. This is a knowing trade, not an
 *       oversight: the alternative (per-caller counting) is keyed on
 *       attacker-controlled input and bounds nothing. The DoS is bounded to one
 *       hour, self-healing, costs the attacker a WARN line per lockout
 *       ({@code "OTP verification locked for ..."}), and requires knowing the
 *       target's number — whereas an uncapped guess budget is a standing
 *       credential risk. Availability loses to integrity here on purpose. If
 *       guards report lockouts in practice, the fix is alerting on that WARN,
 *       not raising the cap.</li>
 *   <li><b>Residual brute-force exposure.</b> A 6-digit code is 10^6 wide. The
 *       caps here bound a sustained attack on a known phone to
 *       {@value #MAX_ATTEMPTS_PER_HOUR} guesses/hour (~240/day). The 5-minute TTL
 *       is what makes that acceptable: a guess only counts against the code that
 *       is live when it lands, so practical per-code exposure stays at
 *       {@value #MAX_VERIFY_ATTEMPTS} in 10^6. Raising the TTL or the caps
 *       degrades this quickly. Per-IP limiting is enforced separately, in
 *       {@code PublicRateLimitFilter}.</li>
 *   <li><b>Session integrity.</b> The backend trusts client {@code X-User-*}
 *       headers, so verification here is real but what happens to the identity
 *       afterwards inherits that pre-existing platform issue.</li>
 * </ul>
 */
@Service
@Slf4j
public class OtpLoginService {

    private static final int MAX_REQUESTS_PER_WINDOW = 3;
    private static final int THROTTLE_WINDOW_MINUTES = 15;
    private static final int CODE_TTL_MINUTES = 5;

    /** Per-code guess budget. Spent atomically, including by successful verifies. */
    private static final int MAX_VERIFY_ATTEMPTS = 5;

    /**
     * Cross-code guess budget per phone per rolling hour. Bounds the attack the
     * per-code cap cannot: requesting a fresh code resets the per-row budget, so
     * without this the real bound would be 3 codes x 5 attempts per 15 minutes =
     * 1,440 guesses/day, well beyond what NIST SP 800-63B allows for a 6-digit
     * secret.
     *
     * <p>A successful verify also spends one attempt (the claim precedes the
     * compare, by design). Issuance is capped at {@value #MAX_REQUESTS_PER_WINDOW}
     * per {@value #THROTTLE_WINDOW_MINUTES} minutes, so a guard cannot plausibly
     * reach this through legitimate logins.
     */
    private static final int MAX_ATTEMPTS_PER_HOUR = 10;

    /**
     * Single message for every verification failure. Callers must not vary it —
     * distinguishing "expired" from "wrong code" hands an attacker a free oracle.
     */
    private static final String INVALID = "invalid or expired code";

    private final LoginOtpRepository otpRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final OtpCodeGenerator codeGenerator;

    public OtpLoginService(LoginOtpRepository otpRepository,
                           UserRepository userRepository,
                           PasswordEncoder passwordEncoder,
                           ApplicationEventPublisher events,
                           OtpCodeGenerator codeGenerator) {
        this.otpRepository = otpRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
        this.codeGenerator = codeGenerator;
    }

    /**
     * Issues an OTP to {@code phone} if it belongs to an active security guard.
     *
     * <p>Returns normally whether or not a code was actually sent — see the
     * anti-enumeration notes on the class.
     *
     * @throws BusinessRuleViolationException if the phone is not E.164 (400)
     * @throws ResponseStatusException        429 once the per-phone rate limit is hit
     */
    @Transactional
    public void requestOtp(String phone) {
        String normalized = normalize(phone);

        // Throttle FIRST: this must not depend on whether the guard exists, or the
        // differing work would leak existence.
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

        // Injected rather than generated inline so a dev build can pin the code
        // (FixedOtpCodeGenerator) without touching this class. In prod the only
        // implementation that can ever be injected is SecureRandomOtpCodeGenerator —
        // see FixedOtpCodeGenerator for why that is a profile guard and not a
        // startup failure.
        String code = codeGenerator.generate();

        // Retire any still-live codes: several can be outstanding at once, and an
        // older one becomes "top" again — and would still verify — once the newest
        // is consumed.
        otpRepository.invalidateOutstanding(normalized, Instant.now());

        LoginOtp otp = new LoginOtp();
        otp.setPhoneNumber(normalized);
        otp.setCodeHash(passwordEncoder.encode(code));
        otp.setExpiresAt(Instant.now().plus(CODE_TTL_MINUTES, ChronoUnit.MINUTES));
        otpRepository.save(otp);

        // Delivery is deliberately NOT called here. Publishing defers the send to
        // after this transaction commits (OtpDeliveryListener): a sender that
        // throws must not roll this row back, because the issuance throttle above
        // counts rows and would switch itself off. Read OtpDeliveryListener before
        // changing this line back to a direct call.
        events.publishEvent(new OtpRequestedEvent(normalized, code));
    }

    /**
     * Verifies {@code code} against the newest unconsumed OTP for {@code phone}
     * and returns the authenticated guard.
     *
     * <p><b>{@code noRollbackFor} is load-bearing — do not remove it.</b> Every
     * failure path here throws, and under Spring's default
     * rollback-on-RuntimeException the attempt claim would be reverted along with
     * it, resetting the counter on each failure and handing an attacker unlimited
     * guesses. Mocked-repository unit tests cannot catch that regression;
     * {@code OtpLoginRollbackIT} can.
     *
     * <p>This method deliberately never mutates the loaded {@link LoginOtp}: all
     * writes go through bulk updates, because a dirty managed entity would be
     * flushed at commit carrying the pre-claim {@code attemptCount} and would
     * silently undo the atomic increment.
     *
     * @throws BusinessRuleViolationException if the phone is not E.164 (400)
     * @throws ResponseStatusException        429 once the per-phone hourly cap is hit
     * @throws BadCredentialsException        on any verification failure (401)
     */
    @Transactional(noRollbackFor = BadCredentialsException.class)
    public User verifyOtp(String phone, String code) {
        String normalized = normalize(phone);

        // Cross-code cap first — cheapest check, and it must apply regardless of
        // whether an OTP or a guard exists for this phone.
        Instant hourAgo = Instant.now().minus(1, ChronoUnit.HOURS);
        if (otpRepository.countRecentAttempts(normalized, hourAgo) >= MAX_ATTEMPTS_PER_HOUR) {
            // Deliberately loud: sustained guessing against one phone is the
            // signature worth alerting on. Never log the code itself.
            log.warn("OTP verification locked for {}: {} or more attempts in the last hour",
                    normalized, MAX_ATTEMPTS_PER_HOUR);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many attempts. Please request a new code later.");
        }

        LoginOtp otp = otpRepository
                .findTopByPhoneNumberAndConsumedAtIsNullOrderByCreatedAtDesc(normalized)
                .orElseThrow(() -> new BadCredentialsException(INVALID));

        if (otp.getExpiresAt().isBefore(Instant.now())) {
            throw new BadCredentialsException(INVALID);
        }

        // Claim BEFORE the compare: the budget is spent even if this process dies
        // mid-verify, and 0 rows means the cap is already reached — which rejects
        // even a correct code.
        if (otpRepository.claimAttempt(otp.getId(), MAX_VERIFY_ATTEMPTS) == 0) {
            throw new BadCredentialsException(INVALID);
        }

        if (code == null || !passwordEncoder.matches(code, otp.getCodeHash())) {
            throw new BadCredentialsException(INVALID);
        }

        // Consume before resolving the user: a correct code is single-use even if
        // the guard turns out to be deactivated, so it cannot be replayed. A 0 here
        // means a concurrent verify already consumed it.
        if (otpRepository.consume(otp.getId(), Instant.now()) == 0) {
            throw new BadCredentialsException(INVALID);
        }

        return resolveActiveGuard(normalized)
                .orElseThrow(() -> new BadCredentialsException(INVALID));
    }

    /**
     * Resolves the single active security guard owning this phone.
     *
     * <p>The repository returns a list, but changeset 65 creates
     * {@code uq_users_guard_phone ON users(phone_number) WHERE role='SECURITY_GUARD'}
     * and this query filters on exactly that role — so at most one row can come
     * back and the multi-match branch below is unreachable. It stays as
     * defense-in-depth: were that partial index dropped or its predicate widened,
     * refusing to guess is the only safe behaviour, since picking a row
     * arbitrarily would log the caller into an arbitrary tenant.
     */
    private Optional<User> resolveActiveGuard(String normalizedPhone) {
        List<User> active = userRepository.findByPhoneNumberAndRole(normalizedPhone, UserRole.SECURITY_GUARD)
                .stream()
                .filter(u -> u.getStatus() == UserStatus.ACTIVE)
                .toList();

        if (active.size() > 1) {
            log.error("Phone number maps to {} active security guards — uq_users_guard_phone should "
                    + "make this impossible; refusing to guess an identity", active.size());
            return Optional.empty();
        }
        return active.stream().findFirst();
    }

    /**
     * Delegates to {@link PhoneNumbers#toE164} — which is where this logic now
     * lives, and why.
     *
     * <p>In short: normalizing the login input here while
     * {@code UserService.createUser} stored the phone verbatim meant the two sides
     * of {@code findByPhoneNumberAndRole} were comparing different formats, and a
     * guard saved as "+971 50 123 4567" could never be found. Keeping this private
     * was the bug. Do not re-inline it.
     */
    private String normalize(String phone) {
        return PhoneNumbers.toE164(phone);
    }
}
