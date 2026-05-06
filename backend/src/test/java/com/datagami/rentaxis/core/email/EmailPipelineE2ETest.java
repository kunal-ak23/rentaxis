package com.datagami.rentaxis.core.email;

import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.email.event.payload.UserInvitedPayload;
import com.datagami.rentaxis.core.email.outbox.EmailOutbox;
import com.datagami.rentaxis.core.email.outbox.EmailOutboxRepository;
import com.datagami.rentaxis.core.email.outbox.EmailOutboxWorker;
import com.datagami.rentaxis.core.email.send.EmailSender;
import com.datagami.rentaxis.core.email.send.SendResult;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration test for the full email pipeline:
 * publish {@link EmailEvent} -&gt; {@code @TransactionalEventListener(AFTER_COMMIT)}
 * -&gt; {@link com.datagami.rentaxis.core.email.dispatch.EmailDispatcher} resolves
 * recipients, renders Thymeleaf, enqueues into {@code email_outbox}
 * -&gt; {@link EmailOutboxWorker#tick()} polls PENDING rows, calls
 * {@link EmailSender#send(EmailOutbox)}, marks SENT.
 *
 * <p>Uses Testcontainers Postgres + a mocked {@link EmailSender} to avoid hitting
 * Azure ACS. The rest of the pipeline (dispatcher, renderer, repository, worker)
 * is exercised against the real Spring context and a real database.</p>
 */
@SpringBootTest
@Testcontainers
class EmailPipelineE2ETest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @MockitoBean EmailSender sender;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired UserRepository userRepo;
    @Autowired EmailOutboxRepository outboxRepo;
    @Autowired EmailOutboxWorker worker;
    @Autowired TransactionTemplate tx;

    @Test
    void publishEventEnqueuesAndWorkerSends() {
        User u = new User();
        u.setEmail("e2e@x.test");
        u.setName("E2E");
        u.setRole(UserRole.RENTER);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u = userRepo.save(u);
        UUID userId = u.getId();

        when(sender.send(any(EmailOutbox.class))).thenReturn(new SendResult("msg-e2e", "Queued"));

        tx.executeWithoutResult(s -> publisher.publishEvent(new EmailEvent(this,
                EmailEventType.USER_INVITED,
                UUID.randomUUID(),
                new UserInvitedPayload(userId, "E2E", "https://app.test/set?t=x"),
                "E2E:" + userId)));

        List<EmailOutbox> rows = outboxRepo.findAll().stream()
                .filter(r -> userId.equals(r.getRecipientUserId()))
                .toList();
        assertThat(rows).hasSize(1);
        EmailOutbox row = rows.get(0);
        assertThat(row.getStatus()).isEqualTo(EmailOutbox.Status.PENDING);

        // Drive the worker. We invoke processOne directly rather than tick()
        // because tick() carries @SchedulerLock, which silently no-ops when
        // called outside the @Scheduled trigger. processOne is the per-row
        // unit of work the scheduler invokes for each picked row, and it is
        // @Transactional, so this still exercises the full
        // outbox -> EmailSender -> persistence path end-to-end.
        worker.processOne(row);

        EmailOutbox after = outboxRepo.findById(row.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(EmailOutbox.Status.SENT);
        assertThat(after.getAzureMessageId()).isEqualTo("msg-e2e");
    }
}
