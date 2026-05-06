package com.datagami.rentaxis.core.email.outbox;

import com.datagami.rentaxis.core.email.send.EmailSender;
import com.datagami.rentaxis.core.email.send.SendResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailOutboxWorkerTest {

    @Mock EmailOutboxRepository repo;
    @Mock EmailSender sender;
    @Mock EmailOutboxService service;
    @InjectMocks EmailOutboxRowProcessor processor;

    @Test
    void successfulSendMarksRowSent() {
        EmailOutbox row = newPendingRow();
        when(sender.send(row)).thenReturn(new SendResult("msg-123", "Queued"));

        processor.processOne(row);

        assertThat(row.getStatus()).isEqualTo(EmailOutbox.Status.SENT);
        assertThat(row.getAzureMessageId()).isEqualTo("msg-123");
        verify(repo, atLeastOnce()).save(row);
    }

    @Test
    void failedSendRequeuesWithBackoff() {
        EmailOutbox row = newPendingRow();
        when(sender.send(row)).thenThrow(new RuntimeException("ACS 503"));
        when(service.backoff(1)).thenReturn(java.time.Duration.ofMinutes(1));

        processor.processOne(row);

        assertThat(row.getStatus()).isEqualTo(EmailOutbox.Status.PENDING);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getLastError()).contains("ACS 503");
    }

    @Test
    void exhaustingMaxAttemptsMarksFailed() {
        EmailOutbox row = newPendingRow();
        row.setAttempts(4);
        row.setMaxAttempts(5);
        when(sender.send(row)).thenThrow(new RuntimeException("permanent"));
        lenient().when(service.backoff(anyInt())).thenReturn(java.time.Duration.ofHours(1));

        processor.processOne(row);

        assertThat(row.getStatus()).isEqualTo(EmailOutbox.Status.FAILED);
        assertThat(row.getAttempts()).isEqualTo(5);
    }

    @Test
    void tickDelegatesProcessingToProcessor() {
        EmailOutboxWorker worker = new EmailOutboxWorker(repo, service, processor);
        when(repo.pickPending(anyInt())).thenReturn(List.of());

        worker.tick();

        verify(service).resetStuckSending();
        verify(repo).pickPending(anyInt());
    }

    private EmailOutbox newPendingRow() {
        EmailOutbox r = new EmailOutbox();
        r.setId(UUID.randomUUID());
        r.setStatus(EmailOutbox.Status.PENDING);
        r.setEventType("LEASE_SIGNED");
        r.setEventCategory("TRANSACTIONAL");
        r.setRecipientUserId(UUID.randomUUID());
        r.setRecipientEmail("a@b");
        r.setRecipientLocale("en");
        r.setSubject("s");
        r.setBodyHtml("<p>x</p>");
        r.setScheduledAt(Instant.now().minusSeconds(1));
        r.setMaxAttempts(5);
        return r;
    }
}
