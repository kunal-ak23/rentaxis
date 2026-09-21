package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseEvent;
import com.datagami.rentaxis.domain.entity.LeaseSettlement;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.SettlementStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LeaseEventRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.LeaseSettlementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * The one rule that says a contract is finished with: {@code CLOSED}.
 *
 * <p>Three things have to be true at once, and they become true at different
 * moments, which is why this is a collaborator rather than a line in whichever
 * method happens to notice:</p>
 *
 * <ol>
 *   <li>the tenancy has <b>ended</b> — TERMINATED (spec §9.1) or EXPIRED;</li>
 *   <li>its <b>settlement is FINALIZED</b> (spec §9.2) — the deposit has been
 *       accounted for, the deductions agreed and the {@code STL} posted;</li>
 *   <li><b>nothing is outstanding on the register</b>: no instrument the landlord
 *       is still waiting on and no cheque that bounced and was never replaced.</li>
 * </ol>
 *
 * <p>Either of the last two can be the last one to arrive. A settlement finalised
 * while §9.1's keep list still holds a cheque for collection leaves a contract
 * that is settled on paper and unfinished in fact; the cheque clearing a fortnight
 * later is what finishes it. Equally, a settlement whose deductions exceed the
 * deposit raises a CASH row for the balance, and that row is outstanding the moment
 * it is created — so finalising cannot close, and receiving the money does.
 * {@code SettlementService.finalizeSettlement} and every clearing path in
 * {@code ChequeService} therefore ask this same question, and the answer cannot
 * drift between them.</p>
 *
 * <p><b>A BOUNCED row counts as outstanding</b> even though
 * {@link ChequeStatus#isUncleared()} says it is not. That predicate is about the
 * <em>PDC receivable</em> — a bounce has already reversed it — but the debt did
 * not go away, it moved back onto the rent receivable. Closing a contract over a
 * returned cheque nobody has replaced would retire a lease the landlord is still
 * chasing.</p>
 *
 * <p><b>CLOSED is terminal</b> and this never un-closes anything: a lease that is
 * already CLOSED is left exactly as it is, so two callers arriving at the same
 * conclusion write one event between them.</p>
 *
 * <p><b>The caller holds the lease's row lock.</b> This reads the register and
 * flips a status; doing that on a row two transactions are clearing rows against
 * is how a lease closes twice or not at all. Both production callers take
 * {@code findByIdForUpdate} on the lease before they get here.</p>
 */
@Service
public class LeaseClosureService {

    private static final Logger log = LoggerFactory.getLogger(LeaseClosureService.class);

    /** A tenancy that has ended and can therefore be finished with. */
    private static final Set<LeaseStatus> ENDED =
            EnumSet.of(LeaseStatus.TERMINATED, LeaseStatus.EXPIRED);

    /**
     * Rows that mean the landlord is still owed money: the three uncleared states
     * plus BOUNCED — see the class note.
     */
    static final Set<ChequeStatus> OUTSTANDING = EnumSet.of(
            ChequeStatus.REGISTERED, ChequeStatus.DEPOSITED,
            ChequeStatus.ONLINE_PENDING, ChequeStatus.BOUNCED);

    private final LeaseRepository leases;
    private final ChequeRepository cheques;
    private final LeaseSettlementRepository settlements;
    private final LeaseEventRepository leaseEvents;

    public LeaseClosureService(LeaseRepository leases,
                               ChequeRepository cheques,
                               LeaseSettlementRepository settlements,
                               LeaseEventRepository leaseEvents) {
        this.leases = leases;
        this.cheques = cheques;
        this.settlements = settlements;
        this.leaseEvents = leaseEvents;
    }

    /**
     * Close the lease if the three conditions above hold; otherwise leave it alone.
     *
     * <p>Joins the caller's transaction ({@code REQUIRED}) deliberately: the
     * clearance and the closing are one fact, and a close that committed on its own
     * would describe a collection the caller then rolled back.</p>
     *
     * @param lease a lease the caller has already locked FOR UPDATE.
     * @param reason what the lease's trail records — "the last instrument cleared",
     *               "the settlement was finalised".
     * @return whether this call closed it.
     */
    @Transactional
    public boolean closeIfFullyCollected(Lease lease, String reason) {
        if (lease == null || !ENDED.contains(lease.getStatus())) {
            return false;
        }
        LeaseSettlement settlement = settlements.findByLeaseId(lease.getId()).orElse(null);
        if (settlement == null || settlement.getStatus() != SettlementStatus.FINALIZED) {
            return false;
        }
        // The count runs after the caller's own change to the row it just moved:
        // this is a JPQL query over the same entity, so Hibernate flushes the
        // pending CLEARED before it answers.
        long outstanding = cheques.countByLease_IdAndStatusIn(lease.getId(), OUTSTANDING);
        if (outstanding > 0) {
            return false;
        }

        LeaseStatus previous = lease.getStatus();
        lease.setStatus(LeaseStatus.CLOSED);
        leases.save(lease);

        LeaseEvent event = new LeaseEvent();
        event.setLease(lease);
        event.setTenantId(lease.getTenantId());
        event.setPreviousState(previous);
        event.setNewState(LeaseStatus.CLOSED);
        event.setNotes("Closed: the settlement is finalised and nothing is outstanding on the register"
                + (reason == null || reason.isBlank() ? "" : " (" + reason.trim() + ")"));
        event.setCreatedAt(Instant.now());
        leaseEvents.save(event);

        log.info("Lease {} closed: settlement finalised and no outstanding register rows", lease.getId());
        return true;
    }
}
