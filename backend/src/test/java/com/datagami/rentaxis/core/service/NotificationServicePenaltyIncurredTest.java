package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.Notification;
import com.datagami.rentaxis.domain.entity.PenaltyAssessment;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.enums.PenaltyReason;
import com.datagami.rentaxis.domain.repository.DeviceTokenRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.NotificationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * What the renter is actually told when a fine is charged.
 *
 * <p>The surviving third of {@code NotificationServicePenaltyHelpersTest}, which
 * went with the v1 penalty tables (changeset 84). {@code sendPenaltyCleared} and
 * {@code sendPenaltyWaived} took v1 entities and have no v2 counterpart — a
 * waiver is an internal decision the renter was never told about, and a
 * collected fine is the receipt for its register row. {@code sendPenaltyIncurred}
 * took a {@code PenaltyAssessment} all along and is still live, so its two cases
 * are kept here rather than dropped with the file.</p>
 *
 * <p>{@code PenaltyAssessmentServiceIT} covers the <em>approval</em> surviving a
 * notification that cannot be written; this covers what gets written when it can.
 * Drives the real service with stubbed repositories — no Spring, no database.</p>
 */
@ExtendWith(MockitoExtension.class)
class NotificationServicePenaltyIncurredTest {

    @Mock NotificationRepository notificationRepository;
    @Mock DeviceTokenRepository deviceTokenRepository;
    @Mock LeaseRepository leaseRepository;
    @Mock org.springframework.context.ApplicationEventPublisher events;

    private NotificationService service;
    private UUID tenantId;
    private UUID renterUserId;

    @BeforeEach
    void setUp() {
        service = new NotificationService(
                notificationRepository, deviceTokenRepository, leaseRepository, events);
        tenantId = UUID.randomUUID();
        renterUserId = UUID.randomUUID();
        TenantContextHolder.setTenantId(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void anApprovedAssessmentTellsTheRenterTheAmountTheReasonAndTheInstalment() {
        PenaltyAssessment assessment = assessmentFor(renterUserId);

        service.sendPenaltyIncurred(assessment);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(1)).save(captor.capture());

        Notification saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo("PENALTY_INCURRED");
        assertThat(saved.getTitle()).isEqualTo("Penalty Incurred");
        assertThat(saved.getMessage()).contains("500");
        assertThat(saved.getMessage()).contains(PenaltyReason.CHEQUE_RETURN.label());
        // The instalment the fine is about, off the cheque that failed.
        assertThat(saved.getMessage()).contains("#3");
        assertThat(saved.getReferenceType()).isEqualTo("PENALTY");
        assertThat(saved.getReferenceId()).isEqualTo(assessment.getId());
        assertThat(saved.getUserId()).isEqualTo(renterUserId);
    }

    /**
     * A renter with no portal account cannot be notified. Skipped quietly rather
     * than thrown: the approval that called this has already posted its journal,
     * and failing here would unwind a charge over an undeliverable message.
     */
    @Test
    void aRenterWithNoUserAccountIsSkippedRatherThanFailing() {
        service.sendPenaltyIncurred(assessmentFor(null));

        verify(notificationRepository, never()).save(any());
    }

    /** An approved assessment against a returned cheque — the shape approve() hands over. */
    private PenaltyAssessment assessmentFor(UUID renterUserId) {
        Renter renter = new Renter();
        renter.setId(UUID.randomUUID());
        renter.setUserId(renterUserId);
        renter.setNameEn("Test Renter");

        Cheque cheque = new Cheque();
        cheque.setId(UUID.randomUUID());
        cheque.setSeqNo(3);

        PenaltyAssessment a = new PenaltyAssessment();
        a.setId(UUID.randomUUID());
        a.setTenantId(tenantId);
        a.setRenter(renter);
        a.setCheque(cheque);
        a.setReason(PenaltyReason.CHEQUE_RETURN);
        a.setAmount(new BigDecimal("500"));
        return a;
    }
}
