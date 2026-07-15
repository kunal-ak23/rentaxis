package com.datagami.rentaxis.core.service.otp;

import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.LoginOtp;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LoginOtpRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link OtpLoginService} against a real Postgres — the
 * two properties the mocked unit tests structurally cannot see, because both
 * live in the database rather than in the service's control flow:
 *
 * <ol>
 *   <li>the failed-attempt claim actually <em>survives</em> the thrown
 *       BadCredentialsException (drop {@code noRollbackFor} and every mocked
 *       test still passes, while the counter silently resets on each failure);</li>
 *   <li>the claim is <em>atomic</em> — concurrent guesses cannot all read the
 *       same counter and collapse N increments into one. Replace
 *       {@code claimAttempt} with a read-modify-write and the mocked tests still
 *       pass, while the 5-attempt cap stops bounding anything.</li>
 * </ol>
 *
 * <p>Requires Docker on the host.
 */
@SpringBootTest
@Testcontainers
class OtpLoginRollbackIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired OtpLoginService otpLoginService;
    @Autowired LoginOtpRepository otpRepository;
    @Autowired UserRepository userRepository;
    @Autowired LandlordOrgRepository landlordOrgRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired TransactionTemplate transactionTemplate;

    private String phone;

    @BeforeEach
    void setUp() {
        // uq_users_guard_phone is a global partial unique index on guard phones,
        // so each test needs its own number.
        phone = "+9715" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode() % 100_000_000));

        LandlordOrg org = new LandlordOrg();
        org.setName("IT-Tenant-" + UUID.randomUUID());
        org = landlordOrgRepository.save(org);

        User guard = new User();
        guard.setTenantId(org.getId());
        guard.setEmail("guard-" + UUID.randomUUID() + "@example.com");
        guard.setName("IT Guard");
        guard.setRole(UserRole.SECURITY_GUARD);
        guard.setStatus(UserStatus.ACTIVE);
        guard.setPhoneNumber(phone);
        guard.setPasswordHash("unused");
        userRepository.save(guard);
    }

    /** Persists an OTP for {@link #phone} whose plaintext code is {@code code}. */
    private UUID givenOtp(String code) {
        return transactionTemplate.execute(status -> {
            LoginOtp otp = new LoginOtp();
            otp.setPhoneNumber(phone);
            otp.setCodeHash(passwordEncoder.encode(code));
            otp.setExpiresAt(Instant.now().plus(5, ChronoUnit.MINUTES));
            return otpRepository.save(otp).getId();
        });
    }

    /** Reads attemptCount back in a FRESH transaction — the committed value, not a cached one. */
    private int committedAttemptCount(UUID otpId) {
        return transactionTemplate.execute(status ->
                otpRepository.findById(otpId).orElseThrow().getAttemptCount());
    }

    @Test
    void failedAttemptSurvivesTheThrownException() {
        UUID otpId = givenOtp("123456");

        assertThatThrownBy(() -> otpLoginService.verifyOtp(phone, "999999"))
                .isInstanceOf(BadCredentialsException.class);

        // Without noRollbackFor this reads 0: the increment is rolled back with the
        // exception, and the attacker's budget silently resets on every guess.
        assertThat(committedAttemptCount(otpId))
                .as("the failed attempt must be committed, not rolled back with the exception")
                .isEqualTo(1);
    }

    @Test
    void concurrentWrongGuessesEachSpendExactlyOneAttempt() {
        UUID otpId = givenOtp("123456");

        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger rejected = new AtomicInteger();

        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        otpLoginService.verifyOtp(phone, "999999");
                    } catch (BadCredentialsException e) {
                        rejected.incrementAndGet();
                    } catch (Exception ignored) {
                        // Interrupted / infra failure — the assertions below still bound it.
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown(); // fire all guesses at once
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } finally {
            pool.shutdownNow();
        }

        assertThat(rejected.get()).isEqualTo(threads);

        // THE assertion. A read-modify-write lets all 5 threads read 0 and write 1,
        // so this reads ~1-3 and the cap never binds. The atomic claim makes each
        // guess cost exactly one.
        assertThat(committedAttemptCount(otpId))
                .as("%d concurrent guesses must each spend exactly one attempt", threads)
                .isEqualTo(threads);
    }

    @Test
    void correctCodeIsRejectedOnceConcurrentGuessesExhaustTheBudget() {
        UUID otpId = givenOtp("123456");

        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        otpLoginService.verifyOtp(phone, "999999");
                    } catch (Exception ignored) {
                        // expected
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } finally {
            pool.shutdownNow();
        }

        assertThat(committedAttemptCount(otpId)).isEqualTo(threads);

        // The payoff: the budget is genuinely spent, so the CORRECT code now loses.
        // Under a lost-update increment the counter would sit below the cap and this
        // login would succeed — which is exactly the attack.
        assertThatThrownBy(() -> otpLoginService.verifyOtp(phone, "123456"))
                .as("the correct code must lose once the guess budget is spent")
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void newRequestRetiresPriorOutstandingCode() {
        UUID firstId = givenOtp("111111");

        otpLoginService.requestOtp(phone);

        // The older code must be retired, or consuming the newest promotes it back
        // to "top" where it would still verify.
        Instant consumedAt = transactionTemplate.execute(status ->
                otpRepository.findById(firstId).orElseThrow().getConsumedAt());
        assertThat(consumedAt).as("issuing a new code must retire the previous one").isNotNull();

        assertThatThrownBy(() -> otpLoginService.verifyOtp(phone, "111111"))
                .isInstanceOf(BadCredentialsException.class);
    }
}
