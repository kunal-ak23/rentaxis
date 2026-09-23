package com.datagami.rentaxis.core.email.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * {@code pickPending} against the shared test database, where other classes'
 * outbox rows are also present. The assertions are therefore about this test's
 * own three rows: the due one is picked, the future one and the SENT one are not.
 * The due row is dated far in the past so it sorts first under the query's
 * {@code ORDER BY scheduled_at} whatever else is waiting, and the limit cannot
 * crowd it out.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class EmailOutboxRepositoryTest extends AbstractPostgresIT {

    @Autowired EmailOutboxRepository repo;

    @Test
    void pickPendingReturnsOnlyDuePendingRows() {
        EmailOutbox future = newRow(EmailOutbox.Status.PENDING, Instant.now().plusSeconds(60));
        EmailOutbox dueNow = newRow(EmailOutbox.Status.PENDING, Instant.parse("2000-01-01T00:00:00Z"));
        EmailOutbox sent  = newRow(EmailOutbox.Status.SENT,    Instant.now().minusSeconds(1));
        repo.saveAll(List.of(future, dueNow, sent));

        List<EmailOutbox> picked = repo.pickPending(10);

        assertThat(picked).extracting(EmailOutbox::getId)
                .contains(dueNow.getId())
                .doesNotContain(future.getId(), sent.getId());
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
