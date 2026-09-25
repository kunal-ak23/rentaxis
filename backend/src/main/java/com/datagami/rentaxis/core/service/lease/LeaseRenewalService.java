package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.CreateLeaseDTO;
import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.lease.ExtendLeaseRequest;
import com.datagami.rentaxis.api.dto.lease.LeaseLineInput;
import com.datagami.rentaxis.api.dto.lease.PostLeaseResponse;
import com.datagami.rentaxis.api.dto.lease.RenewLeaseRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.ledger.UnmappedAccountRoleException;
import com.datagami.rentaxis.domain.entity.ChargeType;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseLine;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChargeBehaviour;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LeaseLineRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The two ways a tenancy continues past the end of its contract (spec §6.6, §6.7).
 *
 * <p>They are genuinely different things and the temptation to unify them should
 * be resisted. A <b>renewal</b> is a <em>new contract</em>: new dates, possibly a
 * new rent, its own contract number, its own cheques, its own {@code TCO}. It
 * arrives as a DRAFT the accountant can still edit, and posting it retires the
 * lease it replaces. An <b>extension</b> is the <em>same contract, longer</em>:
 * nothing is reversed, {@code end_date} moves, new RENT lines cover the extra
 * window and a further {@code TCO} charges for them. The renter's receivable
 * simply carries more on it.</p>
 *
 * <p>The distinction matters in the ledger and not merely on screen. An extension
 * that had been modelled as a renewal would have split one tenancy across two
 * contracts, doubled the deposit handling and produced a second contract number
 * for a piece of paper nobody signed; a renewal modelled as an extension would
 * have re-dated a posted contract and left last year's rent recognised against
 * this year's term.</p>
 *
 * <p>Both methods are {@code @Transactional}, and not decoratively:
 * {@code TenantAspect} only enables the Hibernate tenant filter inside a
 * transaction, so a read taken outside one would cross tenants.</p>
 */
@Service
public class LeaseRenewalService {

    /**
     * What a lease must be to be renewed.
     *
     * <p>ACTIVE is the ordinary case. EXPIRED is the common late one — the renter
     * stayed on and the paperwork followed a month later — and refusing it would
     * mean the only way to regularise a holdover tenancy is a fresh lease with no
     * chain, which is exactly the history a renewal chain exists to keep.
     * NOTICE_GIVEN is a renter who said they were leaving and changed their mind.
     * A DRAFT has nothing to renew, and a TERMINATED or CLOSED lease ended rather
     * than continued.</p>
     */
    private static final Set<LeaseStatus> RENEWABLE =
            EnumSet.of(LeaseStatus.ACTIVE, LeaseStatus.EXPIRED, LeaseStatus.NOTICE_GIVEN);

    private final LeaseRepository leaseRepository;
    private final LeaseLineRepository leaseLineRepository;
    private final ChequeRepository chequeRepository;
    private final LeaseService leaseService;
    private final LeasePostingService postingService;
    private final ChequeGenerationService chequeGeneration;
    private final LeaseChequeRegistrar chequeRegistrar;
    private final LeaseAccessPolicy leaseAccessPolicy;
    private final ApplicationEventPublisher events;
    private final AdditionalCharges charges;

    public LeaseRenewalService(LeaseRepository leaseRepository,
                               LeaseLineRepository leaseLineRepository,
                               ChequeRepository chequeRepository,
                               LeaseService leaseService,
                               LeasePostingService postingService,
                               ChequeGenerationService chequeGeneration,
                               LeaseChequeRegistrar chequeRegistrar,
                               LeaseAccessPolicy leaseAccessPolicy,
                               ApplicationEventPublisher events,
                               AdditionalCharges charges) {
        this.leaseRepository = leaseRepository;
        this.leaseLineRepository = leaseLineRepository;
        this.chequeRepository = chequeRepository;
        this.leaseService = leaseService;
        this.postingService = postingService;
        this.chequeGeneration = chequeGeneration;
        this.chequeRegistrar = chequeRegistrar;
        this.leaseAccessPolicy = leaseAccessPolicy;
        this.events = events;
        this.charges = charges;
    }

    // ------------------------------------------------------------------
    // renew
    // ------------------------------------------------------------------

    /**
     * Draft the successor to this lease (spec §6.6).
     *
     * <p>It writes a DRAFT and nothing else — no journals, no status change on the
     * predecessor, no claim on the unit. All of that happens when the successor is
     * <em>posted</em>, and until then the renewal is a proposal the accountant can
     * edit, re-cut the grid for, or delete. That is the whole reason renewal is a
     * separate act from posting: the terms of next year's contract are usually
     * negotiated after the draft exists.</p>
     *
     * @return the DRAFT successor, exactly as {@code GET /leases/{id}} would return it.
     */
    @Transactional
    public LeaseDTO renew(UUID leaseId, RenewLeaseRequest r) {
        // Locked, not merely loaded. The "already renewed" check below reads a row
        // this call is about to create the competitor for: two accountants hitting
        // Renew at the same moment would both find no successor and both write one,
        // and nothing in the database forbids two drafts pointing at one
        // predecessor. The loser of the lock gets the 400 that names the other
        // draft, which is the answer they wanted.
        Lease predecessor = postingService.lockLease(leaseId);
        // Manageable, not merely readable: a renewal is a contract, and a renter
        // who may read their own lease may not write next year's.
        leaseAccessPolicy.requireManageable(predecessor);

        if (!RENEWABLE.contains(predecessor.getStatus())) {
            throw new BusinessRuleViolationException(
                    "Only an ACTIVE, EXPIRED or NOTICE_GIVEN lease can be renewed; this one is "
                            + predecessor.getStatus() + ".");
        }
        if (r == null || r.startDate() == null || r.endDate() == null) {
            throw new BusinessRuleViolationException("A renewal needs a start date and an end date");
        }
        if (!r.endDate().isAfter(r.startDate())) {
            throw new BusinessRuleViolationException("The renewal's end date must be after its start date");
        }

        // Renewing twice would put two successors on one unit, each expecting to
        // retire the same predecessor and claim the same unit. Caught here rather
        // than at posting time, where the loser would already have a cut grid and
        // collected cheques behind it.
        //
        // Any successor counts, whatever its status. A DRAFT one is somebody's work
        // in progress and is deleted if it was a mistake ({@code DELETE /leases/{id}}
        // removes the row outright, so it stops being a successor); a posted one is
        // the renewal, and the lease after it renews from that, not from here.
        Lease existing = leaseRepository.findByRenewedFromLeaseId(leaseId).stream()
                .findFirst().orElse(null);
        if (existing != null) {
            throw new BusinessRuleViolationException(
                    "This lease has already been renewed by lease " + existing.getId()
                            + " (" + existing.getStatus() + "); delete that draft or renew the successor instead.");
        }

        requireNoAssignmentPending(leaseId);

        // Spec §2: a lease with a transfer (drafted or posted) is not renewed; the
        // transfer's lease is, once it is on the books.
        Lease transfer = leaseRepository.findByTransferredFromLeaseId(leaseId).stream().findFirst().orElse(null);
        if (transfer != null) {
            throw new BusinessRuleViolationException("This lease is being transferred to lease " + transfer.getId()
                    + " (" + transfer.getStatus() + "); delete that draft, or renew the new lease instead.",
                    "lease.renewHasTransfer", java.util.Map.of());
        }

        RenewalPlan plan = plan(predecessor, r);
        CreateLeaseDTO dto = successorHeader(predecessor, r);
        dto.setLines(plan.lines());
        // Spec §4a: the new registration, when the operator already has it.
        String ejari = r.ejariNumber() == null || r.ejariNumber().isBlank() ? null : r.ejariNumber().trim();
        dto.setEjariNumber(ejari);
        LeaseDTO draft = leaseService.createRenewalDraft(dto, predecessor, r.carryDepositForward());

        Lease successor = leaseRepository.findById(draft.getId()).orElseThrow();
        if (plan.changed()) {
            // Audit (spec §4a): shown on the lease header and the contract.
            successor.setRenewalPreviousRent(plan.baseRent());
            successor.setRenewalChangePercent(plan.changePercent());
            leaseRepository.save(successor);
            draft.setRenewalPreviousRent(plan.baseRent());
            draft.setRenewalChangePercent(plan.changePercent());
        }
        if (ejari == null) {
            // The same follow-up the addendum flow leaves: the lease's history says so.
            leaseService.recordLeaseEvent(successor, LeaseStatus.DRAFT, LeaseStatus.DRAFT,
                    "Renewal drafted; Ejari registration pending");
        }
        // Spec §4c: shown to the operator — "Not copied: Admin Fee 1,500 (one-off)".
        draft.setSkippedOneOffLines(plan.skippedOneOff().stream().map(LeaseService::toLineDTO).toList());
        return draft;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.datagami.rentaxis.domain.repository.LeaseAssignmentRepository assignmentRepository;

    /** PR #359 R1 P2-3: a lease whose renter is about to change is not renewed or transferred first. */
    void requireNoAssignmentPending(UUID leaseId) {
        if (assignmentRepository != null && assignmentRepository.existsByLeaseIdAndStatus(leaseId,
                com.datagami.rentaxis.domain.entity.LeaseAssignment.DRAFT)) {
            throw new BusinessRuleViolationException("This lease has a draft assignment to another renter; post or"
                    + " delete it first.", "lease.assignmentPending", java.util.Map.of());
        }
    }

    /** The lines a renewal copies, the contract rent among them, and the one-off lines left behind (spec §4c). */
    record Copied(List<LeaseLineInput> lines, List<LeaseLine> sources, List<LeaseLine> skippedOneOff) {
    }

    /**
     * What a renewal will draft (spec §4a, §4c, §4d): the lines, the rent before and
     * after, the one-off lines not copied, and the property's notice threshold.
     *
     * @param baseRent      what the predecessor's contract RENT line charges: headline less
     *                      discount (before any rent-free concession); null when it has none
     * @param newRent       the successor's headline rent
     * @param changePercent (new − base) ÷ base × 100 at 3 dp; null when unchanged by request
     * @param warnPercent   the property's notice threshold, or null
     * @param exceedsWarn   the change is above it (informational only)
     */
    public record RenewalPlan(BigDecimal baseRent, BigDecimal newRent, BigDecimal changePercent, boolean changed,
                              List<LeaseLineInput> lines, List<LeaseLine> copiedSources,
                              List<LeaseLine> skippedOneOff, BigDecimal warnPercent, boolean exceedsWarn,
                              /* PR #358 R1 P2-3: the predecessor's rent discount, which does not renew. */
                              BigDecimal droppedDiscount) {
    }

    /** The renewal a request would draft, with nothing written (the preview endpoint). */
    @Transactional(readOnly = true)
    public com.datagami.rentaxis.api.dto.lease.RenewalPreviewDTO preview(UUID leaseId, RenewLeaseRequest r) {
        RenewalPlan plan = planFor(leaseId, r);
        List<com.datagami.rentaxis.api.dto.lease.RenewalPreviewDTO.Line> lines = new ArrayList<>();
        for (int i = 0; i < plan.lines().size(); i++) {
            LeaseLineInput in = plan.lines().get(i);
            LeaseLine src = i < plan.copiedSources().size() ? plan.copiedSources().get(i) : null;
            ChargeType type = src == null ? null : src.getChargeType();
            lines.add(new com.datagami.rentaxis.api.dto.lease.RenewalPreviewDTO.Line(in.chargeTypeId(),
                    type == null ? in.chargeTypeCode() : type.getCode(), type == null ? null : type.getNameEn(),
                    type == null ? null : type.getNameAr(),
                    type == null || type.getBehaviour() == null ? null : type.getBehaviour().name(),
                    in.grossAmount(), in.discountAmount(), Boolean.TRUE.equals(in.vatApplicable())));
        }
        return new com.datagami.rentaxis.api.dto.lease.RenewalPreviewDTO(plan.baseRent(), plan.newRent(),
                plan.changePercent(), lines, plan.skippedOneOff().stream().map(LeaseService::toLineDTO).toList(),
                plan.warnPercent(), plan.exceedsWarn(), plan.droppedDiscount());
    }

    /** The plan itself, for tests and the preview. */
    @Transactional(readOnly = true)
    public RenewalPlan planFor(UUID leaseId, RenewLeaseRequest r) {
        Lease predecessor = leaseRepository.findByIdScopedToTenant(leaseId)
                .orElseThrow(() -> new com.datagami.rentaxis.api.exception.NotFoundException("Lease not found"));
        leaseAccessPolicy.requireManageable(predecessor);
        if (r == null || r.startDate() == null || r.endDate() == null || !r.endDate().isAfter(r.startDate())) {
            throw new BusinessRuleViolationException("A renewal needs a start date and a later end date");
        }
        return plan(predecessor, r);
    }

    private RenewalPlan plan(Lease predecessor, RenewLeaseRequest r) {
        RenewLeaseRequest.RentChange change = r.rentChange();
        RenewLeaseRequest.RentChange.Mode mode = change == null || change.mode() == null
                ? RenewLeaseRequest.RentChange.Mode.NONE : change.mode();
        if (r.lines() != null && mode != RenewLeaseRequest.RentChange.Mode.NONE) {
            throw new BusinessRuleViolationException(
                    "Send either the renewal's lines or a rent change, not both.");
        }
        List<LeaseLineInput> lines;
        List<LeaseLine> sources;
        List<LeaseLine> skipped;
        BigDecimal base = null;
        BigDecimal dropped = BigDecimal.ZERO;
        BigDecimal newRent = null;
        BigDecimal percent = null;
        if (r.lines() != null) {
            lines = new ArrayList<>(r.lines());
            sources = List.of();
            skipped = List.of();
        } else {
            Copied copied = copiedLines(predecessor, r);
            lines = new ArrayList<>(copied.lines());
            sources = copied.sources();
            skipped = copied.skippedOneOff();
            LeaseLine contractRent = LeaseService.contractRentLine(predecessor,
                    leaseLineRepository.findByLease_IdOrderBySeqNoAsc(predecessor.getId()));
            int rentIndex = contractRent == null ? -1 : sources.indexOf(contractRent);
            if (rentIndex >= 0) {
                // PR #358 R1 P2-3: the base is what the renter pays — headline less the
                // discount — because the discount does not renew. A NONE renewal keeps
                // that rent; a percentage and the notice are measured net to net.
                dropped = contractRent.getDiscountAmount() == null ? BigDecimal.ZERO : contractRent.getDiscountAmount();
                base = contractRent.getGrossAmount().subtract(dropped);
                newRent = switch (mode) {
                    case NONE -> base;
                    case AMOUNT -> {
                        if (change.newRentAmount() == null || change.newRentAmount().signum() <= 0) {
                            throw new BusinessRuleViolationException("Enter the new rent amount.");
                        }
                        yield change.newRentAmount().setScale(2, java.math.RoundingMode.HALF_UP);
                    }
                    case PERCENT -> {
                        if (change.percent() == null) {
                            throw new BusinessRuleViolationException("Enter the rent change in percent.");
                        }
                        if (!sameTermLength(predecessor.getStartDate(), predecessor.getEndDate(), r.startDate(), r.endDate())) {
                            throw new BusinessRuleViolationException(
                                    "The term length changed; enter the new rent amount instead of a percentage.");
                        }
                        // Whole AED, half up (PACT contracts are whole-dirham; product decision).
                        BigDecimal factor = BigDecimal.ONE.add(change.percent().movePointLeft(2));
                        BigDecimal escalated = base.multiply(factor).setScale(0, java.math.RoundingMode.HALF_UP);
                        if (escalated.signum() <= 0) {
                            throw new BusinessRuleViolationException("The rent change leaves no rent to charge.");
                        }
                        yield escalated.setScale(2);
                    }
                };
                if (base.signum() > 0) {
                    percent = newRent.subtract(base).multiply(BigDecimal.valueOf(100))
                            .divide(base, 3, java.math.RoundingMode.HALF_UP);
                }
                // Concessions do not renew (spec §4a): the discount resets to 0, and the
                // successor starts with no rent-free period.
                LeaseLineInput in = lines.get(rentIndex);
                lines.set(rentIndex, new LeaseLineInput(in.chargeTypeId(), in.chargeTypeCode(), newRent,
                        BigDecimal.ZERO, in.narration(), in.vatApplicable(), in.creditAccountId(),
                        in.periodStart(), in.periodEnd()));
            } else if (mode != RenewLeaseRequest.RentChange.Mode.NONE) {
                throw new BusinessRuleViolationException("The lease being renewed has no contract rent line to revise.");
            }
        }
        // Spec §4d: charges added to this renewal — ordinary lines on the draft.
        if (r.additionalLines() != null) {
            for (LeaseLineInput extra : r.additionalLines()) {
                if (extra != null) lines.add(extra);
            }
        }
        BigDecimal warn = warnPercentFor(predecessor);
        boolean changed = mode != RenewLeaseRequest.RentChange.Mode.NONE;
        boolean exceeds = warn != null && percent != null && percent.compareTo(warn) > 0;
        return new RenewalPlan(base, newRent, changed ? percent : null, changed, List.copyOf(lines), sources,
                skipped, warn, exceeds, dropped);
    }

    /** Same length in whole calendar months and days: 24/09→23/09 and 01/10→30/09 are both 12 months. */
    static boolean sameTermLength(LocalDate oldStart, LocalDate oldEnd, LocalDate newStart, LocalDate newEnd) {
        java.time.Period a = java.time.Period.between(oldStart, oldEnd.plusDays(1)).normalized();
        java.time.Period b = java.time.Period.between(newStart, newEnd.plusDays(1)).normalized();
        return a.toTotalMonths() == b.toTotalMonths() && a.getDays() == b.getDays();
    }

    private com.datagami.rentaxis.domain.repository.RentCollectionSettingsRepository rentSettings;

    @org.springframework.beans.factory.annotation.Autowired
    public void setRentSettings(com.datagami.rentaxis.domain.repository.RentCollectionSettingsRepository repo) {
        this.rentSettings = repo;
    }

    private BigDecimal warnPercentFor(Lease lease) {
        if (rentSettings == null || lease.getUnit() == null || lease.getUnit().getProperty() == null) return null;
        return rentSettings.findByPropertyId(lease.getUnit().getProperty().getId())
                .map(com.datagami.rentaxis.domain.entity.RentCollectionSettings::getRenewalIncreaseWarnPercent)
                .orElse(null);
    }

    /**
     * The successor's header: the request's dates, everything else inherited.
     *
     * <p>Payment terms, instalment distribution, payment methods, a grace period
     * set on the lease (an inherited one is re-read from the property) and the VAT
     * flag carry over because they describe <em>how this landlord
     * bills this renter</em> and have not changed just because the year has. The
     * Ejari number deliberately does not: a renewal is registered afresh, and
     * copying last year's would put a stale registration on a live contract.</p>
     */
    private CreateLeaseDTO successorHeader(Lease predecessor, RenewLeaseRequest r) {
        CreateLeaseDTO dto = new CreateLeaseDTO();
        dto.setUnitId(predecessor.getUnit().getId());
        dto.setRenterId(predecessor.getRenter().getId());
        dto.setStartDate(r.startDate());
        dto.setEndDate(r.endDate());
        dto.setContractDate(r.contractDate());
        dto.setFirstDueDate(r.startDate());
        dto.setPaymentTerms(predecessor.getPaymentTerms());
        dto.setInstallmentDistribution(predecessor.getInstallmentDistribution());
        dto.setPaymentMethod(predecessor.getPaymentMethod() != null
                ? predecessor.getPaymentMethod().name() : null);
        dto.setDepositPaymentMethod(predecessor.getDepositPaymentMethod() != null
                ? predecessor.getDepositPaymentMethod().name() : null);
        // A grace the predecessor inherited is the building's policy, and the
        // successor takes the policy as it stands now; one set on the lease was
        // agreed with this renter and carries over (gap #65). Either way the new
        // lease snapshots its number when it is drafted.
        dto.setGracePeriodDays(predecessor.isGracePeriodOverridden()
                ? predecessor.getGracePeriodDays() : null);
        dto.setRentVatApplicable(predecessor.isRentVatApplicable());
        return dto;
    }

    /**
     * Last year's charges, ready to be edited.
     *
     * <p>What carries over is what the charge <em>is</em>: the type, the account it
     * credits, the amounts, a fee's narration and the VAT flag. What does not is the
     * period, because a period is about a term and this is a different term — RENT
     * lines are re-dated to the new one, and a fee's old window would be a date
     * range from a contract that has ended. A RENT line's narration goes too: it
     * names the old term ("Annual rent 01 Oct 2024 - 30 Sep 2025"), and the TCO,
     * the contract PDF and the renter's ledger would print it under the new dates.</p>
     *
     * <p>When the deposit is being carried forward, no DEPOSIT line is copied. It
     * would otherwise charge the renter a second deposit and collect it on the
     * grid, while the {@code JV} moved the first one across — the renter would have
     * paid twice for one deposit, and the liability on the books would be double
     * what the landlord holds.</p>
     *
     * <p><b>Only the contract's own lines are copied.</b> Two kinds of line on a
     * lease were not part of the contract as signed and are skipped:</p>
     * <ul>
     *   <li>an addendum's line ({@code addendumId} set) — a charge added mid-term
     *       and priced for the part of the term it covered, e.g. 4,000 of rent for
     *       February to September;</li>
     *   <li>an extension's rent line — a RENT line whose own period starts after
     *       the lease's start date. An extension always dates its rent from the day
     *       after the pre-extension end, while a contract's own rent line always
     *       starts on the lease's start date ({@code LeaseService} defaults it
     *       there, and no screen or import sets it anywhere else).</li>
     * </ul>
     * <p>Copied, either would be re-priced as a full year at its fragment amount
     * (a RENT line is re-dated to the whole new term) and charged next to the
     * contract's own rent. A charge the renter does carry on into the new term —
     * the parking bay they took by addendum — is added on the draft by the
     * operator, at the full-year price, which is a price only they know. If
     * nothing is left once these are skipped, the renewal is refused as having
     * nothing to copy.</p>
     */
    private Copied copiedLines(Lease predecessor, RenewLeaseRequest r) {
        List<LeaseLine> source = leaseLineRepository.findByLease_IdOrderBySeqNoAsc(predecessor.getId());
        List<LeaseLineInput> copied = new ArrayList<>(source.size());
        List<LeaseLine> copiedFrom = new ArrayList<>(source.size());
        List<LeaseLine> skipped = new ArrayList<>();
        for (LeaseLine line : source) {
            ChargeType type = line.getChargeType();
            ChargeBehaviour behaviour = type != null ? type.getBehaviour() : null;
            if (r.carryDepositForward() && behaviour == ChargeBehaviour.DEPOSIT) {
                continue;
            }
            boolean rent = behaviour == ChargeBehaviour.RENT;
            // F14-18: a periodic fee an extension charged is dated to its window,
            // exactly like the extension's rent, and is left behind the same way.
            boolean periodic = behaviour == ChargeBehaviour.FEE && type.getRecognition() != null
                    && type.getRecognition().recurs();
            if (line.getAddendumId() != null || ((rent || periodic) && isExtensionLine(line, predecessor))) {
                continue;
            }
            // Spec §4c: a one-off fee (admin fee, last year's renewal fee) was charged
            // once for that contract; the renewal reports it rather than re-charging it.
            if (behaviour == ChargeBehaviour.FEE && type.getRecognition() != null && !type.getRecognition().recurs()) {
                skipped.add(line);
                continue;
            }
            copiedFrom.add(line);
            copied.add(new LeaseLineInput(
                    type != null ? type.getId() : null,
                    null,
                    line.getGrossAmount(),
                    line.getDiscountAmount(),
                    rent ? null : line.getNarration(),
                    line.isVatApplicable(),
                    line.getCreditAccount() != null ? line.getCreditAccount().getId() : null,
                    rent ? r.startDate() : null,
                    rent ? r.endDate() : null));
        }
        if (copied.isEmpty()) {
            throw new BusinessRuleViolationException(
                    "There is nothing to copy from the lease being renewed; send the renewal's lines explicitly.");
        }
        return new Copied(copied, copiedFrom, skipped);
    }

    /** A RENT line dated from after the lease's start — only an extension writes one. */
    private static boolean isExtensionLine(LeaseLine line, Lease lease) {
        return line.getPeriodStart() != null && lease.getStartDate() != null
                && line.getPeriodStart().isAfter(lease.getStartDate());
    }

    // ------------------------------------------------------------------
    // extend
    // ------------------------------------------------------------------

    /**
     * Make a posted lease run longer, and charge for the extra time (spec §6.7).
     *
     * <p><b>Additive.</b> The original {@code TCO} is not reversed and
     * {@code lease.postingJournalId} is not repointed: it still names the entry
     * that raised the original term, which is the entry an amendment would reverse.
     * The extension's own {@code TCO} is found the way every other journal on a
     * lease is found — {@code sourceType LEASE}, {@code sourceId} the lease — so a
     * second column on the lease would have been a third place for the same fact,
     * and a lease extended twice has no column to put the third entry in anyway.</p>
     *
     * <p><b>Validated before anything is written, in two stages.</b> The shape of
     * the request — the date, the behaviours, the rows, Σ cheques against Σ lines
     * including VAT, and the period lock — is checked with nothing persisted at
     * all. The lines and rows are then written, and the accounts they resolve to
     * are checked before the first journal, because "which leaf does this line
     * credit" is a question only the persisted row can answer and
     * {@code LeaseService} is the only correct place to answer it. No entry number
     * is consumed until every check has passed, and the whole thing is one
     * transaction regardless.</p>
     */
    @Transactional
    public PostLeaseResponse extend(UUID leaseId, ExtendLeaseRequest r) {
        Lease lease = postingService.lockLease(leaseId);
        leaseAccessPolicy.requireManageable(lease);

        if (lease.getStatus() != LeaseStatus.ACTIVE) {
            throw new BusinessRuleViolationException(
                    "Only an ACTIVE lease can be extended; this one is " + lease.getStatus() + ".");
        }
        if (r == null || r.newEndDate() == null) {
            throw new BusinessRuleViolationException("The extension needs a new end date");
        }
        LocalDate previousEnd = lease.getEndDate();
        if (!r.newEndDate().isAfter(previousEnd)) {
            throw new BusinessRuleViolationException(
                    "The new end date must be after the current one (" + previousEnd + ")");
        }
        // P2-1: the longer term must not run into the next lease on the unit.
        leaseService.requireExtensionFree(lease, r.newEndDate());
        LocalDate entryDate = r.contractDate() != null ? r.contractDate() : LocalDate.now();
        LocalDate windowStart = previousEnd.plusDays(1);

        // ---- stage 1: the request, with nothing written -------------------
        List<LeaseLineInput> inputs = charges.dated(r.lines(), windowStart, r.newEndDate(),
                AdditionalCharges.Act.EXTENSION);
        BigDecimal charged = charges.valueOf(inputs, lease);

        List<ChequeRowInput> rows = r.cheques() == null ? List.of() : r.cheques();
        charges.requireCovered(rows, charged, AdditionalCharges.Act.EXTENSION);

        List<String> lockErrors = postingService.periodLockErrors(entryDate, List.of());
        if (!lockErrors.isEmpty()) {
            throw new BusinessRuleViolationException(String.join(" ", lockErrors));
        }

        // ---- stage 2: rows exist, journals do not --------------------------
        List<LeaseLine> newLines = leaseService.appendLines(lease, inputs);
        List<Cheque> newRows = chequeGeneration.appendRows(lease, rows, entryDate, newLines);

        LeasePostingService.LinePlan plan = postingService.planLines(lease, newLines);
        Set<AccountRole> missing = postingService.unmappedRoles(lease, newLines, newRows);
        List<String> problems = new ArrayList<>(plan.errors());
        // The rows' own posting dates may fall outside the extension's: a row dated
        // in a closed month would be refused by PostingService halfway through
        // registering the set, after the TCO had gone in.
        problems.addAll(postingService.periodLockErrors(entryDate, newRows));
        // VAT per instalment on the new rows (review P2-2): Σ, TRN, and no tax point
        // inside the locked period, where it would never post.
        problems.addAll(postingService.newRowsVatErrors(lease, newLines, newRows));
        // #80 on every door that registers paper, not only the first post: a
        // post-dated cheque needs its number. LeaseChequeRegistrar.register refuses
        // one anyway; listing it here puts it beside every other problem at once.
        problems.addAll(LeaseChequeRegistrar.missingNumbers(newRows));
        if (problems.isEmpty() && !missing.isEmpty()) {
            throw new UnmappedAccountRoleException(missing, LeasePostingService.propertyIdOf(lease));
        }
        if (!missing.isEmpty()) {
            problems.add(new UnmappedAccountRoleException(missing, LeasePostingService.propertyIdOf(lease)).getMessage());
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(String.join(" ", problems));
        }

        // ---- stage 3: the journals ----------------------------------------
        JournalEntry tco = postingService.postTco(lease, plan.pairs(), entryDate,
                "Extension to " + r.newEndDate());
        for (Cheque row : newRows) {
            chequeRegistrar.register(lease, row);
        }

        lease.setEndDate(r.newEndDate());
        // After the end date, so totalDays counts the term the lease now has.
        leaseService.syncDerivedTotals(lease);
        leaseRepository.save(lease);

        leaseService.recordLeaseEvent(lease, LeaseStatus.ACTIVE, LeaseStatus.ACTIVE,
                "Lease extended from " + previousEnd + " to " + r.newEndDate()
                        + ", posted as " + tco.getEntryNumber());
        events.publishEvent(new LeaseExtendedEvent(lease.getTenantId(), lease.getId(),
                previousEnd, r.newEndDate(), newLines.stream().map(LeaseLine::getId).toList()));

        return postingService.response(lease, tco,
                chequeRepository.findByLease_IdOrderBySeqNoAsc(lease.getId()));
    }
}
