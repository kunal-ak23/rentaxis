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
