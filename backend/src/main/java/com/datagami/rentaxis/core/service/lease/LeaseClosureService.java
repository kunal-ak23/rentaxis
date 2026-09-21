package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseEvent;
import com.datagami.rentaxis.domain.entity.LeaseSettlement;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * The one rule that says a contract is finished with: {@code CLOSED}.
 *
 * <p><b>It is a question about the ledger, not about the register.</b> A tenancy
 * is finished with when the books on it are flat: nothing owed on the rent
 * receivable, nothing still out on paper, and no deposit still being held. The
 * register's statuses are how that came to be true, not what makes it true — and
 * asking them instead was wrong in both directions (review C-1 / I-2):</p>
 *
 * <ul>
 *   <li>a <b>BOUNCED</b> row counted as outstanding for ever. When the settlement
 *       had already absorbed that debt — the {@code CBR} put it back on the rent
 *       receivable, the statement netted it against the deposit and raised a CASH
 *       row for the rest — the contract stayed TERMINATED with its only exit being
 *       {@code replace}, whose {@code PDR} credits the receivable a <em>second</em>
 *       time. The landlord ended up paid twice and the lease CLOSED owing the
 *       renter 12,750;</li>
 *   <li>a kept cheque <b>handed back or cancelled</b> after finalise emptied the
 *       register and closed the lease — while its reversed {@code PDR} had just
 *       re-debited the receivable with the very amount the refund was paid out
 *       over. CLOSED, with a debt, and every door that could collect it refused.</li>
 * </ul>
 *
 * <p>So the conditions are, all at once:</p>
 *
 * <ol>
 *   <li>the tenancy has <b>ended</b> — TERMINATED (spec §9.1), EXPIRED, or RENEWED
 *       (spec §6.6: the accountant may settle the old contract instead of carrying
 *       its deposit forward, and a predecessor settled that way is as finished as
 *       any other);</li>
 *   <li>its <b>settlement is FINALIZED</b> (spec §9.2) — the deposit has been
 *       accounted for, the deductions agreed and the {@code STL} posted;</li>
 *   <li>the lease-dimension balances of <b>rent receivable</b>, <b>PDC
 *       receivable</b> and the <b>deposit accounts</b> are each exactly zero;</li>
 *   <li>no row is <b>{@code ONLINE_PENDING}</b>.</li>
 * </ol>
 *
 * <p>(4) is the one condition the ledger cannot see and is deliberate
 * belt-and-braces: {@code registerOnlinePending} posts nothing, so today an
 * authorisation in flight always leaves its own {@code PDR} in PDC receivable and
 * (3) covers it — but the day some path moves that balance first, a lease would
 * close underneath a renter who is halfway through paying, and the capture would
 * then meet a CLOSED contract with the money already taken.</p>
 *
 * <p>Any of the conditions can be the last to arrive, which is why this is a
 * collaborator rather than a line in whichever method happens to notice. A
 * settlement finalised while §9.1's keep list still holds a cheque leaves a
 * contract that is settled on paper and unfinished in fact; the cheque clearing a
 * fortnight later is what finishes it. A settlement whose deductions exceed the
 * deposit raises a CASH row for the balance, and receiving that money is what
 * finishes it. {@code SettlementService.finalizeSettlement} and every clearing
 * path in {@code ChequeService} therefore ask this same question, and the answer
 * cannot drift between them.</p>
 *
 * <p><b>A residue after finalise stays visible rather than being closed over.</b>
 * If a balance is left on an ended, settled contract — a kept cheque that bounced
 * after the refund was paid — this refuses to close and the lease stays
 * TERMINATED, owing what it owes. What finance does about it (a second collection
 * row, a write-off journal that re-evaluates closure) is a product question on
 * #291; "visibly owing" is the honest state until it is answered.</p>
 *
 * <p><b>CLOSED is terminal</b> and this never un-closes anything: a lease that is
 * already CLOSED is left exactly as it is, so two callers arriving at the same
 * conclusion write one event between them.</p>
 *
 * <p><b>The caller holds the lease's row lock.</b> This reads the ledger and flips
 * a status; doing that on a row two transactions are clearing cheques against is
 * how a lease closes twice or not at all. Both production callers take
 * {@code findByIdForUpdate} — and re-read under it — before they get here.</p>
 */
@Service
public class LeaseClosureService {

    private static final Logger log = LoggerFactory.getLogger(LeaseClosureService.class);

    /** A tenancy that has ended and can therefore be finished with. */
    private static final Set<LeaseStatus> ENDED =
            EnumSet.of(LeaseStatus.TERMINATED, LeaseStatus.EXPIRED, LeaseStatus.RENEWED);

    /** Money a gateway is part-way through taking; see the class note's point (4). */
    static final Set<ChequeStatus> IN_FLIGHT = EnumSet.of(ChequeStatus.ONLINE_PENDING);

    private final LeaseRepository leases;
    private final ChequeRepository cheques;
    private final LeaseSettlementRepository settlements;
    private final LeaseEventRepository leaseEvents;
    private final LedgerQueryService ledgerQueryService;
    private final AccountResolver accountResolver;
    private final LeaseDepositLedger depositLedger;

    public LeaseClosureService(LeaseRepository leases,
                               ChequeRepository cheques,
                               LeaseSettlementRepository settlements,
                               LeaseEventRepository leaseEvents,
                               LedgerQueryService ledgerQueryService,
                               AccountResolver accountResolver,
                               LeaseDepositLedger depositLedger) {
        this.leases = leases;
        this.cheques = cheques;
        this.settlements = settlements;
        this.leaseEvents = leaseEvents;
        this.ledgerQueryService = ledgerQueryService;
        this.accountResolver = accountResolver;
        this.depositLedger = depositLedger;
    }

    /**
     * Close the lease if every condition above holds; otherwise leave it alone.
     *
     * <p>Joins the caller's transaction ({@code REQUIRED}) deliberately: the
     * clearance and the closing are one fact, and a close that committed on its own
     * would describe a collection the caller then rolled back. It is also what makes
     * the balances readable — the caller's own {@code CRT} or {@code STL} is still
     * uncommitted, and the ledger queries are native, so Hibernate flushes the
     * session before answering them.</p>
     *
     * @param lease a lease the caller has already locked FOR UPDATE and re-read.
     * @param reason what the lease's trail records — "the last instrument cleared",
     *               "the settlement was finalised".
     * @return whether this call closed it.
     */
    @Transactional
    public boolean closeIfFullyCollected(Lease lease, String reason) {
        if (lease == null || !ENDED.contains(lease.getStatus())) {
            return false;
        }
        if (!isSettlementFinalized(lease.getId())) {
            return false;
        }
        if (cheques.countByLease_IdAndStatusIn(lease.getId(), IN_FLIGHT) > 0) {
            return false;
        }
        BigDecimal receivable = receivableBalance(lease);
        if (receivable.signum() != 0) {
            log.debug("Lease {} stays {}: rent receivable is {}", lease.getId(), lease.getStatus(), receivable);
            return false;
        }
        BigDecimal instruments = instrumentBalance(lease);
        if (instruments.signum() != 0) {
            return false;
        }
        // A FINALIZED settlement releases every deposit account it found holding
        // anything, so this is normally zero by construction. Asserted rather than
        // assumed: a deposit still on the books is the one balance that would let a
        // contract close while the landlord is holding the renter's money.
        BigDecimal deposits = depositLedger.depositHeld(lease);
        if (deposits.signum() != 0) {
            log.debug("Lease {} stays {}: {} of deposit is still held", lease.getId(), lease.getStatus(), deposits);
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
        event.setNotes("Closed: the settlement is finalised and nothing is left on the lease's books"
                + (reason == null || reason.isBlank() ? "" : " (" + reason.trim() + ")"));
        event.setCreatedAt(Instant.now());
        leaseEvents.save(event);

        log.info("Lease {} closed: settlement finalised, receivable, instruments and deposits all flat",
                lease.getId());
        return true;
    }

    /**
     * Whether this lease's settlement has been finalised.
     *
     * <p>Public because the register asks it too: once the statement has been drawn
     * and the {@code STL} posted, {@code ChequeService} refuses the transitions that
     * would move money the settlement has already accounted for — see its
     * {@code replace} and {@code returnToTenant}. One definition of "this contract
     * has been settled", in the class that owns what settlement means for a
     * lease's life.</p>
     */
    @Transactional(readOnly = true)
    public boolean isSettlementFinalized(UUID leaseId) {
        return settlements.findByLeaseId(leaseId)
                .map(LeaseSettlement::getStatus)
                .filter(status -> status == SettlementStatus.FINALIZED)
                .isPresent();
    }

    /**
     * What the renter still owes on this contract: the rent receivable's balance on
     * the lease dimension, debit-positive.
     *
     * <p>Resolved the way {@code PostingService} resolves it — the lease's own
     * receivable leaf when it overrides the property's (spec §6.3) — so the balance
     * this reads is the one the lease's own journals moved.</p>
     */
    @Transactional(readOnly = true)
    public BigDecimal receivableBalance(Lease lease) {
        return balanceOn(receivableAccountOf(lease), lease.getId());
    }

    /** What the landlord is still holding on paper, on this lease's dimension. */
    private BigDecimal instrumentBalance(Lease lease) {
        return balanceOn(accountResolver.resolve(AccountRole.PDC_RECEIVABLE, propertyIdOf(lease)).getId(),
                lease.getId());
    }

    private BigDecimal balanceOn(UUID accountId, UUID leaseId) {
        BigDecimal balance = ledgerQueryService.accountLedger(accountId,
                new LedgerQueryService.LedgerFilter(null, null, null, null, leaseId, null)).closingBalance();
        return balance == null ? BigDecimal.ZERO : balance;
    }

    private UUID receivableAccountOf(Lease lease) {
        return lease.getReceivableAccountId() != null
                ? lease.getReceivableAccountId()
                : accountResolver.resolve(AccountRole.RENT_RECEIVABLE, propertyIdOf(lease)).getId();
    }

    private static UUID propertyIdOf(Lease lease) {
        return lease.getUnit() != null && lease.getUnit().getProperty() != null
                ? lease.getUnit().getProperty().getId() : null;
    }
}
