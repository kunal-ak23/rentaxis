package com.datagami.rentaxis.core.email.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Testcontainers
class EmailOutboxRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired EmailOutboxRepository repo;

    @Test
    void pickPendingReturnsOnlyDuePendingRows() {
        EmailOutbox future = newRow(EmailOutbox.Status.PENDING, Instant.now().plusSeconds(60));
        EmailOutbox dueNow = newRow(EmailOutbox.Status.PENDING, Instant.now().minusSeconds(1));
        EmailOutbox sent  = newRow(EmailOutbox.Status.SENT,    Instant.now().minusSeconds(1));
        repo.saveAll(List.of(future, dueNow, sent));

        List<EmailOutbox> picked = repo.pickPending(10);

        assertThat(picked).extracting(EmailOutbox::getId).containsExactly(dueNow.getId());
    }

    private EmailOutbox newRow(EmailOutbox.Status s, Instant scheduledAt) {
        EmailOutbox o = new EmailOutbox();
        o.setEventType("LEASE_SIGNED");
        o.setEventCategory("TRANSACTIONAL");
        o.setRecipientUserId(UUID.randomUUID());
        o.setRecipientEmail("a@b.test");
        o.setRecipientLocale("en");
        o.setSubject("s");
        o.setBodyHtml("<p>x</p>");
        o.setStatus(s);
        o.setScheduledAt(scheduledAt);
        return o;
    }
}
