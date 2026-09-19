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

    public LeaseRenewalService(LeaseRepository leaseRepository,
                               LeaseLineRepository leaseLineRepository,
                               ChequeRepository chequeRepository,
                               LeaseService leaseService,
                               LeasePostingService postingService,
                               ChequeGenerationService chequeGeneration,
                               LeaseChequeRegistrar chequeRegistrar,
                               LeaseAccessPolicy leaseAccessPolicy,
                               ApplicationEventPublisher events) {
        this.leaseRepository = leaseRepository;
        this.leaseLineRepository = leaseLineRepository;
        this.chequeRepository = chequeRepository;
        this.leaseService = leaseService;
        this.postingService = postingService;
        this.chequeGeneration = chequeGeneration;
        this.chequeRegistrar = chequeRegistrar;
        this.leaseAccessPolicy = leaseAccessPolicy;
        this.events = events;
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

        CreateLeaseDTO dto = successorHeader(predecessor, r);
        dto.setLines(r.lines() != null ? r.lines() : copiedLines(predecessor, r));
        return leaseService.createRenewalDraft(dto, predecessor, r.carryDepositForward());
    }

    /**
     * The successor's header: the request's dates, everything else inherited.
     *
     * <p>Payment terms, instalment distribution, payment methods, the grace period
     * and the VAT flag carry over because they describe <em>how this landlord
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
        dto.setGracePeriodDays(predecessor.getGracePeriodDays());
        dto.setRentVatApplicable(predecessor.isRentVatApplicable());
        return dto;
    }

    /**
     * Last year's charges, ready to be edited.
     *
     * <p>What carries over is what the charge <em>is</em>: the type, the account it
     * credits, the amounts, the narration and the VAT flag. What does not is the
     * period, because a period is about a term and this is a different term — RENT
     * lines are re-dated to the new one, and a fee's old window would be a date
     * range from a contract that has ended.</p>
     *
     * <p>When the deposit is being carried forward, no DEPOSIT line is copied. It
     * would otherwise charge the renter a second deposit and collect it on the
     * grid, while the {@code JV} moved the first one across — the renter would have
     * paid twice for one deposit, and the liability on the books would be double
     * what the landlord holds.</p>
     */
    private List<LeaseLineInput> copiedLines(Lease predecessor, RenewLeaseRequest r) {
        List<LeaseLine> source = leaseLineRepository.findByLease_IdOrderBySeqNoAsc(predecessor.getId());
        List<LeaseLineInput> copied = new ArrayList<>(source.size());
        for (LeaseLine line : source) {
            ChargeType type = line.getChargeType();
            ChargeBehaviour behaviour = type != null ? type.getBehaviour() : null;
            if (r.carryDepositForward() && behaviour == ChargeBehaviour.DEPOSIT) {
                continue;
            }
            boolean rent = behaviour == ChargeBehaviour.RENT;
            copied.add(new LeaseLineInput(
                    type != null ? type.getId() : null,
                    null,
                    line.getGrossAmount(),
                    line.getDiscountAmount(),
                    line.getNarration(),
                    line.isVatApplicable(),
                    line.getCreditAccount() != null ? line.getCreditAccount().getId() : null,
                    rent ? r.startDate() : null,
                    rent ? r.endDate() : null));
        }
        if (copied.isEmpty()) {
            throw new BusinessRuleViolationException(
                    "There is nothing to copy from the lease being renewed; send the renewal's lines explicitly.");
        }
        return copied;
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
        LocalDate entryDate = r.contractDate() != null ? r.contractDate() : LocalDate.now();
        LocalDate windowStart = previousEnd.plusDays(1);

        // ---- stage 1: the request, with nothing written -------------------
        List<LeaseLineInput> inputs = datedLines(r.lines(), windowStart, r.newEndDate());
        BigDecimal charged = extensionValue(inputs);

        List<ChequeRowInput> rows = r.cheques() == null ? List.of() : r.cheques();
        BigDecimal collected = BigDecimal.ZERO;
        for (ChequeRowInput row : rows) {
            if (row != null && row.amount() != null) collected = collected.add(row.amount());
        }
        if (collected.compareTo(charged) != 0) {
            throw new BusinessRuleViolationException("Cheque rows total " + LeasePostingService.money(collected)
                    + " but the extension charges " + LeasePostingService.money(charged) + ".");
        }

        List<String> lockErrors = postingService.periodLockErrors(entryDate, List.of());
        if (!lockErrors.isEmpty()) {
            throw new BusinessRuleViolationException(String.join(" ", lockErrors));
        }

        // ---- stage 2: rows exist, journals do not --------------------------
        List<LeaseLine> newLines = leaseService.appendLines(lease, inputs);
        List<Cheque> newRows = chequeGeneration.appendRows(lease, rows, entryDate);

        LeasePostingService.LinePlan plan = postingService.planLines(lease, newLines);
        Set<AccountRole> missing = postingService.unmappedRoles(lease, newLines, newRows);
        List<String> problems = new ArrayList<>(plan.errors());
        // The rows' own posting dates may fall outside the extension's: a row dated
        // in a closed month would be refused by PostingService halfway through
        // registering the set, after the TCO had gone in.
        problems.addAll(postingService.periodLockErrors(entryDate, newRows));
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

    /**
     * The extension's lines with their window pinned on.
     *
     * <p>A RENT line covers the extension window and nothing else. Leaving it to
     * {@code LeaseService}'s defaults would give it the lease's <em>whole</em>
     * term, which per-day recognition would then charge from the original start
     * date — every month of the original term recognised a second time. A caller
     * may name a narrower window inside the extension (two rent lines at different
     * rates, say), but never one that reaches outside it.</p>
     *
     * <p>A DEPOSIT line is refused outright. The deposit is held for the tenancy
     * and the tenancy has not changed; charging another one because the term got
     * longer would be collecting a second deposit for one set of keys. A renter who
     * genuinely owes more deposit is charged it as a separate act, not smuggled
     * into a date change.</p>
     */
    private List<LeaseLineInput> datedLines(List<LeaseLineInput> inputs, LocalDate windowStart, LocalDate windowEnd) {
        if (inputs == null || inputs.isEmpty()) {
            throw new BusinessRuleViolationException("At least one line is required");
        }
        List<LeaseLineInput> out = new ArrayList<>(inputs.size());
        int seqNo = 0;
        for (LeaseLineInput in : inputs) {
            seqNo++;
            if (in == null) {
                throw new BusinessRuleViolationException("Line " + seqNo + " is empty");
            }
            ChargeType type = leaseService.chargeTypeOf(in, seqNo);
            String where = "Line " + seqNo + " (" + type.getCode() + ")";
            if (type.getBehaviour() == ChargeBehaviour.DEPOSIT) {
                throw new BusinessRuleViolationException(where
                        + ": an extension cannot charge a deposit — the tenancy's deposit is already held.");
            }
            if (type.getBehaviour() != ChargeBehaviour.RENT) {
                // A fee carries no period; it is charged for the act of extending.
                out.add(in);
                continue;
            }
            LocalDate from = in.periodStart() != null ? in.periodStart() : windowStart;
            LocalDate to = in.periodEnd() != null ? in.periodEnd() : windowEnd;
            if (from.isBefore(windowStart) || to.isAfter(windowEnd) || to.isBefore(from)) {
                throw new BusinessRuleViolationException(where + ": a rent line must cover part of the extension ("
                        + windowStart + " to " + windowEnd + "), not " + from + " to " + to + ".");
            }
            out.add(new LeaseLineInput(in.chargeTypeId(), in.chargeTypeCode(), in.grossAmount(),
                    in.discountAmount(), in.narration(), in.vatApplicable(), in.creditAccountId(), from, to));
        }
        return out;
    }

    /**
     * What the extension charges, VAT included — the figure Σ cheques must equal.
     *
     * <p>Computed through {@link LeaseVat} on transient {@code LeaseLine} objects
     * rather than by multiplying by 5% here. It is the same object the post's own
     * guard measures, so the two arithmetics cannot round differently, and the
     * deposit rule and the per-line rounding come along with it for free.</p>
     */
    private BigDecimal extensionValue(List<LeaseLineInput> dated) {
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < dated.size(); i++) {
            LeaseLineInput in = dated.get(i);
            ChargeType type = leaseService.chargeTypeOf(in, i + 1);
            BigDecimal gross = in.grossAmount() == null ? BigDecimal.ZERO : in.grossAmount();
            BigDecimal discount = in.discountAmount() == null ? BigDecimal.ZERO : in.discountAmount();

            LeaseLine probe = new LeaseLine();
            probe.setChargeType(type);
            probe.setNetAmount(gross.subtract(discount));
            probe.setVatApplicable(in.vatApplicable() != null ? in.vatApplicable() : type.isVatApplicableDefault());
            total = total.add(LeaseVat.grossOf(probe));
        }
        return total;
    }
}
