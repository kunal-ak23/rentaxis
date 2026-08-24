package com.datagami.rentaxis.core.email.outbox;

import com.datagami.rentaxis.core.email.send.SendResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    /**
     * Atomically claims a pending row in a short transaction. The committed
     * SENDING state prevents another worker from sending the same message, but
     * no database lock remains held while the external email provider is called.
     */
    @Transactional
    public Optional<EmailOutbox> claimForSending(UUID id) {
        if (repo.claimPending(id, Instant.now()) == 0) {
            return Optional.empty();
        }
        return repo.findById(id);
    }

    /** Finalize a successful provider call in its own short transaction. */
    @Transactional
    public boolean markSent(UUID id, SendResult result) {
        Optional<EmailOutbox> found = repo.findById(id);
        if (found.isEmpty()) {
            return false;
        }
        EmailOutbox row = found.get();
        row.setAzureMessageId(result.azureMessageId());
        row.setAzureDeliveryStatus(result.azureDeliveryStatus());
        row.setStatus(EmailOutbox.Status.SENT);
        repo.save(row);
        return true;
    }

    /** Record a failed provider call without retaining the claim transaction. */
    @Transactional
    public Optional<EmailOutbox> markFailedAttempt(UUID id, String error) {
        Optional<EmailOutbox> found = repo.findById(id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        EmailOutbox row = found.get();
        row.setAttempts(row.getAttempts() + 1);
        row.setLastError(truncate(error));
        if (row.getAttempts() >= row.getMaxAttempts()) {
            row.setStatus(EmailOutbox.Status.FAILED);
        } else {
            row.setStatus(EmailOutbox.Status.PENDING);
            row.setScheduledAt(Instant.now().plus(backoff(row.getAttempts())));
        }
        repo.save(row);
        return Optional.of(row);
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

    private String truncate(String value) {
        if (value == null) return null;
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }
}
