package com.datagami.rentaxis.core.service.recognition;

import com.datagami.rentaxis.core.service.lease.LeaseAmendedEvent;
import com.datagami.rentaxis.core.service.lease.LeaseExtendedEvent;
import com.datagami.rentaxis.core.service.lease.LeasePostedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Keeps the recognition schedule in step with the contract behind it (spec §8.5).
 *
 * <p><b>Same transaction, deliberately.</b> All three events are published
 * inside the posting transaction and these listeners are plain
 * {@code @EventListener}s, so the schedule is built, rebuilt or appended in the
 * very transaction that wrote the journals. A lease cannot end up on the books
 * with no schedule behind it, or — worse — amended in the ledger while its old
 * schedule survives and keeps recognising the rent the amendment took away. A
 * {@code @TransactionalEventListener} would have bought decoupling at the price
 * of exactly that window.</p>
 *
 * <p>The cost is that a failure in here fails the post, which is the trade we
 * want: a contract whose income cannot be scheduled is a contract with an
 * unmapped account or an impossible term, and that is a refusal an accountant
 * should see at the moment they press Post.</p>
 */
@Component
public class RecognitionEventListener {

    private final RecognitionService recognition;

    public RecognitionEventListener(RecognitionService recognition) {
        this.recognition = recognition;
    }

    @EventListener
    public void onLeasePosted(LeasePostedEvent event) {
        recognition.buildForLease(event.leaseId());
    }

    /**
     * The reversal is dated today rather than the contract date: it corrects the
     * books as of the day the correction was made, which is the same date
     * {@code LeasePostingService} reverses the {@code TCO} on.
     */
    @EventListener
    public void onLeaseAmended(LeaseAmendedEvent event) {
        recognition.rebuildAfterAmend(event.leaseId(), LocalDate.now());
    }

    @EventListener
    public void onLeaseExtended(LeaseExtendedEvent event) {
        recognition.appendForExtension(event.leaseId(), event.lineIds());
    }
}
