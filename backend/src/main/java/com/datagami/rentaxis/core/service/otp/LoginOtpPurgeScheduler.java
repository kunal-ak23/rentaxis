package com.datagami.rentaxis.core.service.otp;

import com.datagami.rentaxis.domain.repository.LoginOtpRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Bounds retention of {@code login_otps}.
 *
 * <p>Each row holds a phone number (PII) and nothing in the login flow ever
 * deletes it — consumed and expired codes both just sit there. Codes live 5
 * minutes and the throttle window is 15, so a 24h retention is far longer
 * than anything the flow needs while keeping a short forensic trail.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LoginOtpPurgeScheduler {

    private static final int RETENTION_HOURS = 24;

    private final LoginOtpRepository loginOtpRepository;

    @Scheduled(cron = "0 30 3 * * *") // 3:30 AM daily
    @Transactional
    public void purgeOldOtps() {
        Instant cutoff = Instant.now().minus(RETENTION_HOURS, ChronoUnit.HOURS);
        int deleted = loginOtpRepository.deleteByCreatedAtBefore(cutoff);
        log.info("Purged {} login OTP row(s) created before {}", deleted, cutoff);
    }
}
