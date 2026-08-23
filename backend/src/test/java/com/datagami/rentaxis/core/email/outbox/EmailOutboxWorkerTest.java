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
import java.util.Optional;
import java.util.UUID;

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
        SendResult result = new SendResult("msg-123", "Queued");
        when(service.claimForSending(row.getId())).thenReturn(Optional.of(row));
        when(sender.send(row)).thenReturn(result);
        when(service.markSent(row.getId(), result)).thenReturn(true);

        processor.processOne(row);

        verify(service).markSent(row.getId(), result);
    }

    @Test
    void failedSendRequeuesWithBackoff() {
        EmailOutbox row = newPendingRow();
        EmailOutbox failed = newPendingRow();
        failed.setId(row.getId());
        failed.setAttempts(1);
        failed.setStatus(EmailOutbox.Status.PENDING);
        failed.setScheduledAt(Instant.now().plusSeconds(60));
        when(service.claimForSending(row.getId())).thenReturn(Optional.of(row));
        when(sender.send(row)).thenThrow(new RuntimeException("ACS 503"));
        when(service.markFailedAttempt(row.getId(), "ACS 503")).thenReturn(Optional.of(failed));

        processor.processOne(row);

        verify(service).markFailedAttempt(row.getId(), "ACS 503");
    }

    @Test
    void unclaimedRowIsNotSent() {
        EmailOutbox row = newPendingRow();
        when(service.claimForSending(row.getId())).thenReturn(Optional.empty());

        processor.processOne(row);

        verifyNoInteractions(sender);
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
