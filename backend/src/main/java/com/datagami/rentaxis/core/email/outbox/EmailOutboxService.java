package com.datagami.rentaxis.core.email.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailOutboxService {

    private static final Duration STUCK_TIMEOUT = Duration.ofMinutes(5);

    private final EmailOutboxRepository repo;

    @Transactional
    public void enqueue(EmailOutbox row) {
        if (row.getScheduledAt() == null) row.setScheduledAt(Instant.now());
        try {
            repo.save(row);
        } catch (DataIntegrityViolationException e) {
            log.info("email.outbox.dedup_collision dedup_key={}", row.getDedupKey());
        }
    }

    @Transactional
    public void resetStuckSending() {
        List<EmailOutbox> stuck = repo.findStuckSending(Instant.now().minus(STUCK_TIMEOUT));
        for (EmailOutbox row : stuck) {
            log.warn("email.outbox.stuck_reset id={} attempts={}", row.getId(), row.getAttempts());
            row.setStatus(EmailOutbox.Status.PENDING);
            repo.save(row);
        }
    }

    public Duration backoff(int attempts) {
        return switch (attempts) {
            case 1 -> Duration.ofMinutes(1);
            case 2 -> Duration.ofMinutes(5);
            case 3 -> Duration.ofMinutes(15);
            case 4 -> Duration.ofHours(1);
            default -> Duration.ofHours(6);
        };
    }
}
