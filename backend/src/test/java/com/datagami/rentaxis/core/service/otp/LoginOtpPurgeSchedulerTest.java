package com.datagami.rentaxis.core.service.otp;

import com.datagami.rentaxis.domain.repository.LoginOtpRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

/**
 * Unit test for {@link LoginOtpPurgeScheduler}. {@code login_otps} rows hold
 * phone numbers (PII) and are never deleted by the login flow itself, so the
 * purge job is the only thing bounding that retention.
 */
@ExtendWith(MockitoExtension.class)
class LoginOtpPurgeSchedulerTest {

    @Mock LoginOtpRepository loginOtpRepository;

    @Test
    void purgesRowsOlderThanTwentyFourHours() {
        when(loginOtpRepository.deleteByCreatedAtBefore(any())).thenReturn(7);
        LoginOtpPurgeScheduler scheduler = new LoginOtpPurgeScheduler(loginOtpRepository);
        Instant before = Instant.now();

        scheduler.purgeOldOtps();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(loginOtpRepository).deleteByCreatedAtBefore(cutoff.capture());

        // Cutoff should sit ~24h in the past — bracket it rather than pin an exact instant.
        assertThat(cutoff.getValue()).isBefore(before.minus(23, ChronoUnit.HOURS));
        assertThat(cutoff.getValue()).isAfter(before.minus(25, ChronoUnit.HOURS));
    }
}
