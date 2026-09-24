package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
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
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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
        return register(lease, cheque, null);
    }

    /**
     * The same registration, with the cut-over import batch this PDR belongs to
     * (accounting v2 plan 4, controller ruling R4).
     *
     * <p><b>This overload exists because the PDRs are written here, not by
     * {@code LeasePostingService}.</b> A batch's journals are found again by
     * {@code import_batch_id} and are exempt from the period lock because they
     * carry it ({@code PostingService} ~:54) — a cut-over is dated into months that
     * are closed by definition. A PDR written with a null id would therefore be
     * refused outright at post time, and, had it got through, would be invisible to
     * "Reverse batch" afterwards: the instrument would stay on the books with the
     * contract that raised it taken off.</p>
     *
     * <p>An explicit parameter and never a thread-local: the id has to travel with
     * the one posting it belongs to, and an ambient value would attach itself to
     * whatever else the same thread happened to post next.</p>
     *
     * @param importBatchId non-null only for a cut-over bulk post; every
     *                      user-facing path passes null.
     */
    public JournalEntry register(Lease lease, Cheque cheque, UUID importBatchId) {
        String missing = missingNumber(cheque);
        if (missing != null) {
            throw new BusinessRuleViolationException(missing);
        }
        return post(lease, cheque, importBatchId);
    }

    /**
     * The one exception to "a PDC is registered with its number" (#80): a row the
     * <b>portfolio import generated</b> — the deposit and fee rows, and every rent
     * row when the workbook had no Cheques sheet. No number exists for it to carry;
     * refusing would keep a running tenancy off the books, and the import result
     * tells the landlord how many to fill in (Cheque details, bulk attach).
     * {@code LeasePostingService.postForPortfolioImport} is the only caller, and
     * only for the rows the import itself named.
     */
    public JournalEntry registerGeneratedByImport(Lease lease, Cheque cheque) {
        return post(lease, cheque, null);
    }

    /**
     * #80, as a rule about the instrument rather than about the door it came
     * through: a post-dated cheque with no number cannot be matched at the bank,
     * bounced by number or found again. Every door that registers a row — first
     * post, addendum, extension, replacement, a row added to a posted lease —
     * reaches it through {@link #register}. Cash, transfer and online rows have no
     * cheque number by nature.
     *
     * @return the refusal, or null when the row may be registered.
     */
    public static String missingNumber(Cheque c) {
        if (c.getMode() != null && c.getMode() != ChequeMode.PDC) return null;
        if (c.getChequeNumber() != null && !c.getChequeNumber().isBlank()) return null;
        return "Cheque #" + c.getSeqNo() + " has no number; a post-dated cheque needs its number"
                + " before it is registered.";
    }

    /** {@link #missingNumber} over a set of rows, for a caller that reports every problem at once. */
    public static List<String> missingNumbers(Collection<Cheque> rows) {
        List<String> out = new ArrayList<>();
        for (Cheque c : rows) {
            String m = missingNumber(c);
            if (m != null) out.add(m);
        }
        return out;
    }

    private JournalEntry post(Lease lease, Cheque cheque, UUID importBatchId) {
        String narration = narrationOf(cheque);
        JournalEntry pdr = postingService.post(PostingRequest.ofPairs(
                JournalDocType.PDR,
                cheque.getPostingDate(),
                narration,
                dimensions(lease, cheque.getId()),
                JournalSourceType.CHEQUE,
                cheque.getId(),
                importBatchId,
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
