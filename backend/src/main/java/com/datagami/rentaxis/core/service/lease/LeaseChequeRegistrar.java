package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingRequest.Dimensions;
import com.datagami.rentaxis.core.service.ledger.PostingRequest.Line;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Turning one cheque row into a registered instrument: the {@code PDR} that says
 * the landlord now holds paper against the renter's debt (spec §7.2, first row).
 *
 * <p>It is a collaborator rather than a private method because the grid is not
 * the only door. A lease posts its whole grid at once ({@code LeasePostingService});
 * a replacement for a bounced cheque, a penalty collection row and a late cash
 * receipt each arrive one at a time on a lease that is already on the books
 * ({@code ChequeService.addRowToPostedLease}). Both have to raise the identical
 * entry — same doc type, same pair, same dimensions, same date rule — or the
 * register and the ledger disagree about instruments that differ only in when
 * they were handed over.</p>
 *
 * <p><b>The credit side follows the lease, not the property.</b> A lease may name
 * its own receivable account ({@code lease.receivableAccountId}, spec §6.3); when
 * it does, the PDR has to credit the very account the {@code TCO} debited, or the
 * two halves of the same contract sit in different ledgers and neither nets to
 * zero.</p>
 *
 * <p><b>The date is the row's own posting date</b>, not today: a grid posted with
 * the contract files every PDR in the contract's period, and a row added later
 * files in the period it was actually taken.</p>
 */
@Component
public class LeaseChequeRegistrar {

    private final PostingService postingService;
    private final ChequeRepository chequeRepository;

    public LeaseChequeRegistrar(PostingService postingService, ChequeRepository chequeRepository) {
        this.postingService = postingService;
        this.chequeRepository = chequeRepository;
    }

    /**
     * Post the row's PDR and flip it to {@code REGISTERED}.
     *
     * <p>Runs in the caller's transaction on purpose: registering a replacement is
     * one half of "replace this bounced cheque", and a PDR that survived a rolled
     * back replacement would be a receivable against an instrument nobody holds.</p>
     *
     * @return the entry, already recorded on the cheque as {@code pdrJournalId}.
     */
    public JournalEntry register(Lease lease, Cheque cheque) {
        String narration = narrationOf(cheque);
        JournalEntry pdr = postingService.post(PostingRequest.ofPairs(
                JournalDocType.PDR,
                cheque.getPostingDate(),
                narration,
                dimensions(lease, cheque.getId()),
                JournalSourceType.CHEQUE,
                cheque.getId(),
                null,
                java.util.List.of(PostingRequest.pair(
                        PostingRequest.dr(AccountRole.PDC_RECEIVABLE, cheque.getAmount()).withNarration(narration),
                        crReceivable(lease, cheque.getAmount()).withNarration(narration)))));
        cheque.setPdrJournalId(pdr.getId());
        cheque.setStatus(ChequeStatus.REGISTERED);
        cheque.setStatusChangedAt(Instant.now());
        chequeRepository.save(cheque);
        return pdr;
    }

    /** What the ledger row is called: the row's own narration, else its position. */
    public static String narrationOf(Cheque cheque) {
        return cheque.getNarration() != null && !cheque.getNarration().isBlank()
                ? cheque.getNarration() : "Instalment " + cheque.getSeqNo();
    }

    /** The receivable the contract was raised against — the lease's override, else the property's. */
    public static Line crReceivable(Lease lease, BigDecimal amount) {
        return lease.getReceivableAccountId() != null
                ? PostingRequest.cr(lease.getReceivableAccountId(), amount)
                : PostingRequest.cr(AccountRole.RENT_RECEIVABLE, amount);
    }

    /** The same receivable on the debit side — what a bounce puts the debt back onto. */
    public static Line drReceivable(Lease lease, BigDecimal amount) {
        return lease.getReceivableAccountId() != null
                ? PostingRequest.dr(lease.getReceivableAccountId(), amount)
                : PostingRequest.dr(AccountRole.RENT_RECEIVABLE, amount);
    }

    /**
     * Every dimension a cheque journal carries. Read off the lease rather than the
     * cheque so a row created before its unit or renter was denormalised onto it
     * still files under the right ones.
     */
    public static Dimensions dimensions(Lease lease, UUID chequeId) {
        Unit unit = lease.getUnit();
        Property property = unit != null ? unit.getProperty() : null;
        return new Dimensions(
                property != null ? property.getId() : null,
                unit != null ? unit.getId() : null,
                lease.getId(),
                lease.getRenter() != null ? lease.getRenter().getId() : null,
                chequeId);
    }
}
