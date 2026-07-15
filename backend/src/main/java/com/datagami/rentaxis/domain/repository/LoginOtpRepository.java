package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.LoginOtp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LoginOtpRepository extends JpaRepository<LoginOtp, UUID> {

    Optional<LoginOtp> findTopByPhoneNumberAndConsumedAtIsNullOrderByCreatedAtDesc(String phoneNumber);

    long countByPhoneNumberAndCreatedAtAfter(String phoneNumber, Instant after);

    /**
     * Atomically spends one attempt from this OTP's budget, returning 0 when the
     * budget is already exhausted.
     *
     * <p>This exists because {@code attemptCount = attemptCount + 1} in Java is an
     * unguarded read-modify-write: under READ_COMMITTED, N concurrent verifies all
     * read the same value and all write the same value+1, so the counter converges
     * to ~1 regardless of how many guesses land and the cap becomes decorative.
     * Doing the increment and the bound check in one UPDATE makes the database the
     * arbiter — same reasoning as the locking finders on {@code GatePassRepository}.
     *
     * <p>Callers must invoke this <em>before</em> comparing the code, so the attempt
     * is spent even if the process dies mid-verify. A pessimistic lock would also be
     * correct but would be held across the ~100ms bcrypt compare, serializing one
     * guard's logins into a self-inflicted DoS.
     *
     * @return 1 if an attempt was claimed, 0 if the budget is spent
     */
    @Modifying
    @Query("update LoginOtp o set o.attemptCount = o.attemptCount + 1 "
            + "where o.id = :id and o.attemptCount < :max")
    int claimAttempt(@Param("id") UUID id, @Param("max") int max);

    /**
     * Atomically marks a code consumed, returning 0 if it already was. Guarantees
     * single use even if two correct-code verifies race.
     *
     * @return 1 if this call consumed the code, 0 if it was already consumed
     */
    @Modifying
    @Query("update LoginOtp o set o.consumedAt = :now where o.id = :id and o.consumedAt is null")
    int consume(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Total attempts spent against a phone across all OTPs issued since
     * {@code since}. The per-row attempt cap is per-OTP, so without this a fresh
     * request would reset the guess budget and the real bound would be
     * (OTPs per window x attempts per OTP) — far above what NIST SP 800-63B allows
     * for a 6-digit secret.
     */
    @Query("select coalesce(sum(o.attemptCount), 0) from LoginOtp o "
            + "where o.phoneNumber = :phone and o.createdAt > :since")
    long countRecentAttempts(@Param("phone") String phone, @Param("since") Instant since);

    /**
     * Retires any still-live codes for a phone. Issuing a new code must invalidate
     * older ones: several can be outstanding at once, and once the newest is
     * consumed an older one becomes "top" again and would still verify.
     *
     * @return the number of codes retired
     */
    @Modifying
    @Query("update LoginOtp o set o.consumedAt = :now "
            + "where o.phoneNumber = :phone and o.consumedAt is null")
    int invalidateOutstanding(@Param("phone") String phone, @Param("now") Instant now);

    /**
     * Bulk-deletes spent/stale OTP rows. Driven by {@code LoginOtpPurgeScheduler}
     * to bound retention of the phone numbers stored here — callers need an
     * active transaction.
     *
     * @return the number of rows removed
     */
    @Modifying
    @Query("delete from LoginOtp o where o.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
