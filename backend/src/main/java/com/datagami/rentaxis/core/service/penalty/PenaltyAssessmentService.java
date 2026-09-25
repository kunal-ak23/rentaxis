package com.datagami.rentaxis.core.service.penalty;

import com.datagami.rentaxis.core.service.lease.LeaseVat;

import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.penalty.PenaltyAssessmentDTO;
import com.datagami.rentaxis.api.dto.penalty.ProposePenaltyRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.api.exception.RowLockedException;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.core.service.NotificationService;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.lease.LeaseChequeRegistrar;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.PenaltyAssessment;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.PenaltyAssessmentStatus;
import com.datagami.rentaxis.domain.entity.enums.PenaltyReason;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PenaltyAssessmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Penalties, and the approval that is the only thing which charges one
 * (spec §7.3).
 *
 * <p><b>Why this class exists at all.</b> The client's accountant was explicit
 * that a fine must not post itself: after two or three returned cheques finance
 * decides whether to charge the renter, and sometimes decides not to. The v1
 * behaviour — a nightly job that wrote penalties into the renter's balance —
 * produced charges nobody had agreed to and that nobody could take back without
 * editing history. So the system proposes and a human disposes: {@code PROPOSED}
 * is a row on a worklist with no journal behind it, and only {@link #approve}
 * writes to the ledger.</p>
 *
 * <p><b>Approval is one transaction with two halves</b> and they belong together.
 * The {@code PEN} entry says the renter now owes the fine (Dr the lease's
 * receivable / Cr the property's penalty income). The collection row says how it
 * will be taken, and carries its own {@code PDR} which credits that same
 * receivable straight back. Net on the receivable: zero. That is not an
 * accounting curiosity — it is what makes the fine follow the ordinary receipt
 * path (bank it, clear it, bounce it) instead of being a special balance the
 * collection screens have to know about. Half of this pair committing alone would
 * leave either an uncollectable charge or a collection row for a fine that was
 * never raised.</p>
 *
 * <p><b>Reversal refuses once the money is in.</b> An approved penalty whose
 * collection row has CLEARED has been paid; reversing the charge while keeping
 * the receipt would leave cash in the bank against nothing. Finance issues a
 * refund or a credit instead, and is told so.</p>
 *
 * <p><b>Every public method is {@code @Transactional}</b>: {@code TenantAspect}
 * only enables the Hibernate tenant filter inside a transaction, so a read
 * outside one would cross tenants.</p>
 */
@Service
public class PenaltyAssessmentService {

    private static final Logger log = LoggerFactory.getLogger(PenaltyAssessmentService.class);

    /** Statuses that mean "this proposal is still live", for the duplicate guard. */
    static final Set<PenaltyAssessmentStatus> OPEN =
            EnumSet.of(PenaltyAssessmentStatus.PROPOSED, PenaltyAssessmentStatus.APPROVED);

    /**
     * A lease a penalty may still be charged against — the same set the register
     * calls posted.
     *
     * <p>Off this set the contract is closed: a TERMINATED lease has had its
     * uncleared instruments handed back and its unearned rent reversed, and a
     * DRAFT one has no receivable to debit at all. Approving against either would
     * raise a charge the renter can never be sent an instrument for, and on a
     * terminated lease it would reopen a receivable the settlement just closed.
     * The money owed on a closed contract is settled, not invoiced.</p>
     */
    private static final Set<LeaseStatus> CHARGEABLE = EnumSet.of(
            LeaseStatus.ACTIVE, LeaseStatus.NOTICE_GIVEN, LeaseStatus.EXPIRED, LeaseStatus.RENEWED);

    private static final String BEING_UPDATED =
            "This penalty is being updated by another request. Please try again.";

    private final PenaltyAssessmentRepository repository;
    private final LeaseRepository leaseRepository;
    private final ChequeRepository chequeRepository;
    private final PostingService postingService;
    private final ChequeService chequeService;
    private final LeaseAccessPolicy leaseAccessPolicy;
    private final NotificationService notificationService;

    /** R2 N3: required, so the reversal-date rule can never be skipped. */
    private final com.datagami.rentaxis.domain.repository.JournalEntryRepository journalEntries;

    public PenaltyAssessmentService(PenaltyAssessmentRepository repository,
                                    LeaseRepository leaseRepository,
                                    ChequeRepository chequeRepository,
                                    PostingService postingService,
                                    ChequeService chequeService,
                                    LeaseAccessPolicy leaseAccessPolicy,
                                    NotificationService notificationService,
                                    com.datagami.rentaxis.domain.repository.JournalEntryRepository journalEntries) {
        this.journalEntries = journalEntries;
        this.repository = repository;
        this.leaseRepository = leaseRepository;
        this.chequeRepository = chequeRepository;
        this.postingService = postingService;
        this.chequeService = chequeService;
        this.leaseAccessPolicy = leaseAccessPolicy;
        this.notificationService = notificationService;
    }

    // ------------------------------------------------------------------
    // -> PROPOSED  (no journal)
    // ------------------------------------------------------------------

    /**
     * Finance raises a penalty by hand. Nothing posts — this is a request for a
     * decision, and the person making it may not be the person who takes it.
     *
     * @param byUser the proposer, or null for SYSTEM.
     */
    @Transactional
    public PenaltyAssessmentDTO propose(ProposePenaltyRequest r, UUID byUser) {
        if (r == null || r.leaseId() == null) throw new BusinessRuleViolationException("A penalty needs a lease");
        Lease lease = leaseRepository.findById(r.leaseId())
                .orElseThrow(() -> new NotFoundException("Lease not found"));
        leaseAccessPolicy.requireManageable(lease);
        requireChargeable(lease);

        Cheque cheque = null;
        if (r.chequeId() != null) {
            cheque = chequeRepository.findById(r.chequeId())
                    .orElseThrow(() -> new NotFoundException("Cheque not found"));
            if (cheque.getLease() == null || !lease.getId().equals(cheque.getLease().getId())) {
                // Otherwise a penalty could be hung off another lease's instrument
                // and would file under this lease's dimensions with that lease's
                // cheque id — two contracts pointing at one piece of paper.
                throw new BusinessRuleViolationException("That cheque does not belong to this lease");
            }
        }
        LocalDate incident = r.incidentDate() != null ? r.incidentDate() : LocalDate.now();
        if (incident.isAfter(LocalDate.now())) {
            throw new BusinessRuleViolationException("A penalty cannot be raised for a date in the future");
        }
        LocalDate floor = lease.earliestEventDate();
        if (floor != null && incident.isBefore(floor)) {
            throw new BusinessRuleViolationException(
                    "A penalty cannot be raised for a date before the contract (" + floor + ")");
        }
        PenaltyAssessment saved = save(lease, cheque, r.reason(), r.amount(), r.description(), incident, byUser);
        saved.setVatable(vatableFor(lease, r.reason(), r.vatable()));
        return dto(repository.save(saved));
    }

    /**
     * F14-49 / F14-50: a charge raised by another document (a maintenance ticket, an
     * amenity booking) — the same proposal, remembering its source. {@code system}
     * skips the manage-the-lease check: the caller already authorised the act that
     * raised it (a booking approval, possibly by the renter's manager).
     */
    @Transactional
    public PenaltyAssessmentDTO proposeFromSource(ProposePenaltyRequest r, UUID byUser, String sourceType, UUID sourceId,
                                                 boolean system) {
        PenaltyAssessment a;
        if (system) {
            Lease lease = leaseRepository.findById(r.leaseId()).orElseThrow(() -> new NotFoundException("Lease not found"));
            requireChargeable(lease);
            a = save(lease, null, r.reason(), r.amount(), r.description(),
                    r.incidentDate() != null ? r.incidentDate() : LocalDate.now(), byUser);
            a.setVatable(vatableFor(lease, r.reason(), r.vatable()));
        } else {
            a = repository.findById(propose(r, byUser).id()).orElseThrow();
        }
        a.setSourceType(sourceType);
        a.setSourceId(sourceId);
        return dto(repository.save(a));
    }

    /** F14-49 / F14-50: the charges a document raised. */
    @Transactional(readOnly = true)
    public List<PenaltyAssessmentDTO> forSource(String sourceType, UUID sourceId) {
        return repository.findBySourceTypeAndSourceIdOrderByProposedAtAsc(sourceType, sourceId).stream().map(this::dto).toList();
    }

    /**
     * The rule engine's door, and deliberately not {@link #propose}.
     *
     * <p>There is no user here to authorise anything: a rule fires inside a
     * transition somebody else already ran their guard on ({@code ChequeService}
     * checked {@code requireManageable} before it bounced the cheque), and the one
     * path where the authenticated caller is the <em>renter</em> — paying online,
     * clearing their own row late — must not fail closed and roll back the payment
     * because a renter may not manage the lease. The authorisation for the
     * proposal is the authorisation for the transition that caused it.</p>
     */
    @Transactional
    public PenaltyAssessment proposeBySystem(Lease lease, Cheque cheque, PenaltyReason reason,
                                             BigDecimal amount, String description) {
        return proposeBySystem(lease, cheque, reason, amount, description, LocalDate.now(), null, null);
    }

    /**
     * As above. F14-23: {@code incidentDate} is the day the thing happened (the
     * bounce, the late clearing), not the day the proposal was written. F14-31:
     * {@code descriptionCode}/{@code args} let the screen render the description
     * in the reader's language; {@code description} is the English fallback.
     */
    @Transactional
    public PenaltyAssessment proposeBySystem(Lease lease, Cheque cheque, PenaltyReason reason,
                                             BigDecimal amount, String description, LocalDate incidentDate,
                                             String descriptionCode, java.util.Map<String, String> args) {
        PenaltyAssessment a = save(lease, cheque, reason, amount, description,
                incidentDate != null ? incidentDate : LocalDate.now(), null);
        a.setVatable(vatableFor(lease, reason, null));
        if (descriptionCode != null) {
            a.setDescriptionCode(descriptionCode);
            a.setDescriptionArgs(args);
            a = repository.save(a);
        }
        return a;
    }

    private PenaltyAssessment save(Lease lease, Cheque cheque, PenaltyReason reason,
                                   BigDecimal amount, String description, LocalDate incidentDate,
                                   UUID byUser) {
        if (reason == null) throw new BusinessRuleViolationException("A penalty needs a reason");
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessRuleViolationException("A penalty amount must be greater than zero");
        }
        Unit unit = lease.getUnit();
        Property property = unit != null ? unit.getProperty() : null;
        Renter renter = lease.getRenter();
        if (property == null || renter == null) {
            throw new BusinessRuleViolationException(
                    "This lease has no property or renter; a penalty cannot be attributed");
        }

        PenaltyAssessment a = new PenaltyAssessment();
        a.setTenantId(lease.getTenantId());
        a.setLease(lease);
        a.setCheque(cheque);
        a.setRenter(renter);
        a.setProperty(property);
        a.setReason(reason);
        a.setAmount(amount);
        a.setDescription(description);
        a.setIncidentDate(incidentDate);
        a.setStatus(PenaltyAssessmentStatus.PROPOSED);
        a.setProposedBy(byUser);
        a.setProposedAt(Instant.now());
        return repository.save(a);
    }

    // ------------------------------------------------------------------
    // PROPOSED -> APPROVED  (PEN + a collection row)
    // ------------------------------------------------------------------

    /**
     * Finance charges the fine.
     *
     * <p>{@code PEN} Dr the lease's receivable / Cr the property's penalty income,
     * then a CASH row on the register for the same money. The debit side goes
     * through {@link LeaseChequeRegistrar#drReceivable} rather than naming
     * {@code RENT_RECEIVABLE} outright, so a lease with its own receivable account
     * (spec §6.3) keeps its whole renter ledger on one account instead of
     * scattering the fine onto the property's default.</p>
     *
     * @param date the entry date for both journals; defaults to today.
     */
    @Transactional
    public PenaltyAssessmentDTO approve(UUID id, LocalDate date) {
        PenaltyAssessment a = lock(id);
        Lease lease = a.getLease();
        leaseAccessPolicy.requireManageable(lease);
        requireStatus(a, "approve", PenaltyAssessmentStatus.PROPOSED);
        // Up front, before a single line is posted. A proposal can outlive the
        // contract it was raised on — a cheque bounces in March, the lease
        // terminates in April, finance gets to the worklist in May — and by then
        // the answer is "settle it", not "charge it".
        requireChargeable(lease);

        LocalDate on = date != null ? date : LocalDate.now();
        BigDecimal amount = a.getAmount();
        UUID chequeId = a.getCheque() != null ? a.getCheque().getId() : null;
        String narration = narrationFor(a);

        // F14-30: a charge that is consideration for a supply carries 5 % VAT on top,
        // declared on the PEN itself (tax point = the charge date) with its tax invoice.
        BigDecimal vat = a.isVatable() ? LeaseVat.vatOfNet(amount) : BigDecimal.ZERO;
        BigDecimal owed = amount.add(vat);
        List<PostingRequest.Pair> pairs = new java.util.ArrayList<>();
        pairs.add(PostingRequest.pair(
                LeaseChequeRegistrar.drReceivable(lease, amount).withNarration(narration),
                PostingRequest.cr(a.getReason().incomeRole(), amount).withNarration(narration)));
        if (vat.signum() > 0) {
            String vatNarration = "VAT on " + narration;
            pairs.add(PostingRequest.pair(
                    LeaseChequeRegistrar.drReceivable(lease, vat).withNarration(vatNarration),
                    PostingRequest.cr(com.datagami.rentaxis.domain.entity.enums.AccountRole.OUTPUT_VAT, vat)
                            .withNarration(vatNarration)));
        }
        JournalEntry pen = postingService.post(PostingRequest.ofPairs(
                JournalDocType.PEN,
                on,
                narration,
                LeaseChequeRegistrar.dimensions(lease, chequeId),
                JournalSourceType.PENALTY,
                a.getId(),
                null,
                pairs));
        a.setVatAmount(vat);
        if (vat.signum() > 0 && vatTaxPoints != null) {
            vatTaxPoints.recordChargeVat(lease, on, pen.getId(), amount, vat);
        }

        // Through ChequeService, not by hand: the row has to be validated, numbered
        // and registered by exactly the code every other row goes through, or the
        // register grows a row with no PDR behind it.
        //
        // The *internal* door, not the public grid one. CHARGEABLE admits EXPIRED —
        // a tenancy that simply ran out is still owed its fines — while shaping the
        // grid of an ended contract is a live lease's privilege (review I2). This is
        // the same door the settlement's balance-due row uses, and for the same
        // reason: the amount is not the caller's to choose, the debt it collects was
        // raised in the ledger a line above, and no user typed it.
        ChequeDTO row = chequeService.addCollectionRow(lease.getId(), new ChequeRowInput(
                null, null, on, null, on, null, null, null, owed,
                "Penalty - " + a.getReason().label(), ChequeMode.CASH));

        // Set on the entity rather than widened into ChequeRowInput: the link is a
        // fact about this approval, not something a caller of the grid may type.
        Cheque collection = chequeRepository.findById(row.id())
                .orElseThrow(() -> new IllegalStateException("Collection row vanished after it was created"));
        collection.setPenaltyAssessmentId(a.getId());
        chequeRepository.save(collection);

        a.setJournalId(pen.getId());
        a.setCollectionCheque(collection);
        a.setStatus(PenaltyAssessmentStatus.APPROVED);
        a.setApprovedBy(currentUserId());
        a.setApprovedAt(Instant.now());
        PenaltyAssessment approved = repository.save(a);

        // The renter hears about a fine here and only here. A PROPOSED assessment
        // is finance deciding whether to charge them — some are waived — and
        // telling them about one would turn a deliberation into a demand.
        //
        // Caught out here rather than inside the notification: it runs in its own
        // REQUIRES_NEW transaction, so a failed notification row rolls that one back
        // and leaves this one untouched. Catching it in there would mark the new
        // transaction rollback-only and surface as an UnexpectedRollbackException on
        // the approval — a fine nobody could charge because a notification failed.
        try {
            notificationService.sendPenaltyIncurred(approved);
        } catch (Exception e) {
            log.warn("Penalty {} was approved but the renter could not be notified: {}",
                    approved.getId(), e.getMessage());
        }
        return dto(approved);
    }

    // ------------------------------------------------------------------
    // PROPOSED -> WAIVED  (nothing posted)
    // ------------------------------------------------------------------

    /**
     * Finance declines the fine. Nothing is posted and nothing ever will be for
     * this proposal — a waiver is a decision, not a deferral.
     *
     * <p>The note is required. "We chose not to charge this renter" with no reason
     * recorded is the one thing an auditor asks about, and the person who decided
     * will not remember in six months.</p>
     */
    @Transactional
    public PenaltyAssessmentDTO waive(UUID id, String note) {
        PenaltyAssessment a = lock(id);
        leaseAccessPolicy.requireManageable(a.getLease());
        requireStatus(a, "waive", PenaltyAssessmentStatus.PROPOSED);
        if (note == null || note.isBlank()) {
            throw new BusinessRuleViolationException("A waiver needs a reason");
        }
        a.setStatus(PenaltyAssessmentStatus.WAIVED);
        a.setResolutionNote(note.trim());
        return dto(repository.save(a));
    }

    /**
     * F14-28: a partial waiver. The proposal stays PROPOSED at the lower amount,
     * the amount first proposed is kept, and the reason is recorded — one
     * decision on one assessment rather than a waiver plus an unlinked new
     * proposal.
     */
    @Transactional
    public PenaltyAssessmentDTO reduce(UUID id, BigDecimal newAmount, String note) {
        PenaltyAssessment a = lock(id);
        leaseAccessPolicy.requireManageable(a.getLease());
        requireStatus(a, "reduce", PenaltyAssessmentStatus.PROPOSED);
        if (note == null || note.isBlank()) {
            throw new BusinessRuleViolationException("A reduction needs a reason");
        }
        if (newAmount == null || newAmount.signum() <= 0 || newAmount.compareTo(a.getAmount()) >= 0) {
            throw new BusinessRuleViolationException("The reduced amount must be more than zero and less than "
                    + a.getAmount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
                    + "; to charge nothing, waive the penalty");
        }
        if (a.getProposedAmount() == null) a.setProposedAmount(a.getAmount());
        String line = "Reduced from " + a.getAmount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
                + " to " + newAmount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + ": " + note.trim();
        a.setAmount(newAmount.setScale(2, java.math.RoundingMode.HALF_UP));
        a.setResolutionNote(a.getResolutionNote() == null ? line : a.getResolutionNote() + "\n" + line);
        return dto(repository.save(a));
    }

    // ------------------------------------------------------------------
    // APPROVED -> REVERSED  (mirror journal, collection row cancelled)
    // ------------------------------------------------------------------

    /**
     * The approval was wrong. The {@code PEN} is reversed through
     * {@code PostingService} — never mirrored by hand, so the reversal carries the
     * original's dimensions and contra accounts and marks it REVERSED — and the
     * collection row is cancelled, which reverses its {@code PDR}.
     *
     * <p><b>Refused once the row has CLEARED.</b> The renter has paid; unwinding
     * the charge while the receipt stands would leave money in the bank against no
     * receivable and the renter's statement showing a credit they did not earn.
     * A refund or a credit note is a different document, and finance raises it.</p>
     */
    @Transactional
    public PenaltyAssessmentDTO reverse(UUID id, LocalDate date, String note) {
        PenaltyAssessment a = lock(id);
        leaseAccessPolicy.requireManageable(a.getLease());
        requireStatus(a, "reverse", PenaltyAssessmentStatus.APPROVED);
        if (a.getJournalId() == null) {
            throw new BusinessRuleViolationException("This penalty has no journal to reverse");
        }

        Cheque collection = a.getCollectionCheque();
        if (collection != null && collection.getStatus() == ChequeStatus.CLEARED) {
            throw new BusinessRuleViolationException(
                    "Penalty was already collected; issue a refund/credit instead");
        }

        // F14-28: every decision on a charged amount carries a reason, as a waiver does.
        if (note == null || note.isBlank()) {
            throw new BusinessRuleViolationException("A reversal needs a reason");
        }
        LocalDate on = date != null ? date : LocalDate.now();
        // R1 P2-3 (the F14-41 rule): not before the penalty was charged.
        com.datagami.rentaxis.domain.entity.JournalEntry pen = journalEntries.findById(a.getJournalId())
                .orElseThrow(() -> new IllegalStateException("Penalty journal " + a.getJournalId() + " not found"));
        if (pen.getEntryDate() != null && on.isBefore(pen.getEntryDate())) {
            java.time.format.DateTimeFormatter dmy = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");
            throw new BusinessRuleViolationException("A reversal cannot be dated before the penalty was charged ("
                    + pen.getEntryDate().format(dmy) + ")", "penalty.reverseBeforeCharge",
                    java.util.Map.of("charged", pen.getEntryDate().format(dmy), "date", on.format(dmy)));
        }
        String reason = note.trim();
        com.datagami.rentaxis.domain.entity.JournalEntry rev = postingService.reverse(a.getJournalId(), on, reason);
        // F14-30: the VAT a tax invoice declared goes back on a tax credit note.
        if (a.getVatAmount() != null && a.getVatAmount().signum() > 0 && vatTaxPoints != null) {
            vatTaxPoints.recordChargeVat(a.getLease(), on, rev.getId(), a.getAmount().negate(), a.getVatAmount().negate());
        }

        // Only from REGISTERED: a row that was cancelled or returned already had its
        // PDR reversed, and reversing it twice is refused by PostingService anyway.
        if (collection != null && collection.getStatus() == ChequeStatus.REGISTERED) {
            chequeService.cancel(collection.getId(), new ChequeActionRequest(on, reason, null, null));
        }

        a.setStatus(PenaltyAssessmentStatus.REVERSED);
        a.setResolutionNote(reason);
        return dto(repository.save(a));
    }

    // ------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------

    /**
     * The finance worklist.
     *
     * <p>A property manager is scoped inside the query rather than filtered after
     * it: filtering a page the database already counted would report totals that
     * include other buildings' penalties and hand back short pages. A manager
     * assigned to nothing is answered without a query at all — an empty {@code in}
     * list is not a question worth asking Postgres.</p>
     */
    @Transactional(readOnly = true)
    public Page<PenaltyAssessmentDTO> list(UUID leaseId, PenaltyAssessmentStatus status,
                                           UUID propertyId, Pageable pageable) {
        List<UUID> visible = leaseAccessPolicy.visiblePropertyIds();
        boolean unrestricted = visible == null;
        if (!unrestricted && visible.isEmpty()) {
            return Page.empty(pageable);
        }
        return repository.search(leaseId, status, propertyId, unrestricted,
                        unrestricted ? List.of() : visible, pageable)
                .map(this::dto);
    }

    /**
     * The renter portal's list: APPROVED only.
     *
     * <p>A renter must never see a proposal. It is an internal deliberation about
     * whether to charge them, and showing it would turn "finance is thinking about
     * it" into "you owe this" — including for the ones that end up waived.</p>
     */
    @Transactional(readOnly = true)
    public List<PenaltyAssessmentDTO> forRenter(UUID renterId) {
        if (renterId == null) return List.of();
        return repository.findByRenter_IdAndStatusOrderByProposedAtAsc(
                        renterId, PenaltyAssessmentStatus.APPROVED).stream()
                .map(this::dto)
                .toList();
    }

    /**
     * What this lease still owes in fines — Σ of the APPROVED assessments whose
     * collection row has not CLEARED.
     *
     * <p>The settlement's deduction, and deliberately not "every penalty ever
     * charged": an approved fine the renter has already paid is money the landlord
     * has, and deducting it again from the deposit would charge them twice. Nor is
     * it "every live penalty": a PROPOSED one is finance still deciding, and a
     * settlement is not the place that decision gets made by default.</p>
     *
     * <p>No access check here. The only caller is {@code SettlementService}, which
     * has already run {@code LeaseAccessPolicy} on the lease before it builds a
     * preview; adding a second gate would mean a manager could be refused a figure
     * on a screen they were just allowed to open.</p>
     */
    @Transactional(readOnly = true)
    public BigDecimal outstandingForLease(UUID leaseId) {
        if (leaseId == null) return BigDecimal.ZERO;
        BigDecimal total = repository.sumOutstandingForLease(leaseId);
        return total != null ? total : BigDecimal.ZERO;
    }

    // ------------------------------------------------------------------
    // guards and plumbing
    // ------------------------------------------------------------------

    private com.datagami.rentaxis.core.service.vat.VatTaxPointService vatTaxPoints;

    /** Setter-injected: hand-built instances in unit tests need no new argument. */
    @org.springframework.beans.factory.annotation.Autowired
    public void setVatTaxPoints(com.datagami.rentaxis.core.service.vat.VatTaxPointService vatTaxPoints) {
        this.vatTaxPoints = vatTaxPoints;
    }

    /**
     * F14-30: whether a new charge carries VAT. Only on a VAT-registered lease (its
     * rent carries VAT); there, the caller's choice, else the reason's default —
     * a penalty (bounce, late payment) is out of scope, a service / admin / damage
     * / booking charge is standard-rated.
     */
    boolean vatableFor(Lease lease, PenaltyReason reason, Boolean requested) {
        boolean vatLease = LeaseVat.isVatLease(lease, leaseLines);
        if (Boolean.TRUE.equals(requested) && !vatLease) {
            throw new BusinessRuleViolationException("This lease carries no VAT, so the charge cannot either.",
                    "penalty.vatOnNonVatLease", java.util.Map.of());
        }
        if (!vatLease) return false;
        return requested != null ? requested : reason.vatableByDefault();
    }

    private com.datagami.rentaxis.domain.repository.LeaseLineRepository leaseLines;

    @org.springframework.beans.factory.annotation.Autowired
    public void setLeaseLines(com.datagami.rentaxis.domain.repository.LeaseLineRepository leaseLines) {
        this.leaseLines = leaseLines;
    }

    /**
     * The row, locked, tenant-checked.
     *
     * <p>A NOWAIT conflict is a 400 that says "try again", not a 500: the other
     * caller is almost always the same accountant double-clicking Approve.</p>
     */
    private PenaltyAssessment lock(UUID id) {
        PenaltyAssessment a;
        try {
            a = repository.findByIdForUpdate(id)
                    .orElseThrow(() -> new NotFoundException("Penalty not found"));
        } catch (PessimisticLockingFailureException e) {
            throw new RowLockedException(BEING_UPDATED);
        }
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId != null && !tenantId.equals(a.getTenantId())) {
            throw new NotFoundException("Penalty not found");
        }
        if (a.getLease() == null) throw new NotFoundException("Lease not found");
        return a;
    }

    /**
     * The lease is still one a charge can be raised on.
     *
     * <p>Checked <em>before</em> anything posts, not left to
     * {@code ChequeService.addCollectionRow} half way through the approval: by the
     * time the register refuses the collection row the {@code PEN} has already been
     * written and numbered, and the rollback that follows burns an entry number for
     * a charge nobody made.</p>
     *
     * <p>This set is also the <em>narrower</em> of the two rules that gate the
     * collection row: the register's own door admits every status a settlement may
     * touch, and this is what keeps a penalty off a TERMINATED lease.</p>
     */
    private static void requireChargeable(Lease lease) {
        if (!CHARGEABLE.contains(lease.getStatus())) {
            throw new BusinessRuleViolationException(
                    "Lease is " + lease.getStatus() + "; charge this penalty through settlement");
        }
    }

    private static void requireStatus(PenaltyAssessment a, String verb, PenaltyAssessmentStatus allowed) {
        if (a.getStatus() != allowed) {
            throw new BusinessRuleViolationException(
                    "Can only " + verb + " penalties in " + allowed + " (current: " + a.getStatus() + ")");
        }
    }

    /** "Penalty - Cheque return - cheque 100041", or without the tail where there is no instrument. */
    private static String narrationFor(PenaltyAssessment a) {
        String head = "Penalty - " + a.getReason().label();
        Cheque c = a.getCheque();
        if (c == null) return head;
        String number = c.getChequeNumber() != null && !c.getChequeNumber().isBlank()
                ? c.getChequeNumber() : "row " + c.getSeqNo();
        return head + " - cheque " + number;
    }

    private static UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof String s)) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    private PenaltyAssessmentDTO dto(PenaltyAssessment a) {
        Cheque cheque = a.getCheque();
        Cheque collection = a.getCollectionCheque();
        // F14-31 leftover: a system proposal written before description codes existed
        // is read back into its code and arguments, so it renders in Arabic too.
        String code = a.getDescriptionCode();
        java.util.Map<String, String> args = a.getDescriptionArgs();
        if (code == null && a.getProposedBy() == null) {
            var legacy = LegacyPenaltyDescription.parse(a.getDescription()).orElse(null);
            if (legacy != null) {
                code = legacy.code();
                args = legacy.args();
            }
        }
        return new PenaltyAssessmentDTO(
                a.getId(),
                a.getLease() != null ? a.getLease().getId() : null,
                cheque != null ? cheque.getId() : null,
                cheque != null ? cheque.getChequeNumber() : null,
                a.getRenter() != null ? a.getRenter().getId() : null,
                a.getRenter() != null ? a.getRenter().getNameEn() : null,
                a.getProperty() != null ? a.getProperty().getId() : null,
                a.getProperty() != null ? a.getProperty().getNameEn() : null,
                a.getReason(),
                a.getAmount(),
                a.getDescription(),
                a.getIncidentDate(),
                a.getStatus(),
                a.getProposedBy(),
                a.getProposedAt(),
                a.getApprovedBy(),
                a.getApprovedAt(),
                a.getJournalId(),
                collection != null ? collection.getId() : null,
                collection != null ? collection.getStatus() : null,
                a.getResolutionNote(),
                code,
                args,
                a.getProposedAmount(),
                a.isVatable(), a.getVatAmount(), a.getSourceType(), a.getSourceId());
    }
}
