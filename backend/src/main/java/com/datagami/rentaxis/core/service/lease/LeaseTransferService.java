package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.CreateLeaseDTO;
import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.lease.LeaseLineInput;
import com.datagami.rentaxis.api.dto.lease.TransferLeaseRequest;
import com.datagami.rentaxis.api.dto.lease.TransferPreviewDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.recognition.ProrationEngine;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.ChargeType;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseLine;
import com.datagami.rentaxis.domain.entity.LeaseTransferCheque;
import com.datagami.rentaxis.domain.entity.TenantFiscalSettings;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.ChargeBehaviour;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LeaseLineRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.LeaseTransferChequeRepository;
import com.datagami.rentaxis.domain.repository.TenantFiscalSettingsRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Unit transfer (spec 2026-09-24 §2, #52): the renter moves from A's unit to another
 * mid-lease. A ends pro-rata on the move date T with no exit fee; B starts on T + 1;
 * the deposit, the balance (prepaid or owed) and the unused PDCs are carried; one
 * chain, one history.
 *
 * <p><b>Draft, then post, like a renewal.</b> {@link #draft} creates B as a DRAFT
 * with {@code transferred_from_lease_id = A}, the move date and the cheque plan (every
 * uncleared row of A in exactly one of CARRY | KEEP | RETURN); nothing is written to
 * the ledger, and the operator can edit B's lines and grid. Posting B
 * ({@code LeasePostingService.post}) calls {@link #completeForPosting} first, in the
 * same transaction:</p>
 * <ol>
 *   <li>A's CARRY rows: PDR reversed on T, row TRANSFERRED; RETURN rows handed back;</li>
 *   <li>A's recognition cut at T and the TCR for the unearned rent with its VAT
 *       pairs — the termination core, with no exit fee and no settlement deduction;</li>
 *   <li>C = A's receivable after that, bounced rows excluded, carried to B's
 *       receivable by a JV on T + 1 ("Balance carried to …");</li>
 *   <li>each CARRY row copied onto B (same number, date, bank, amount, image) with
 *       {@code transferred_from_id}, to register with B's grid;</li>
 *   <li>A → TERMINATED through T ("Transferred to …"), its unit released; and when
 *       nothing is left on it, CLOSED as settled by transfer.</li>
 * </ol>
 * <p>B's own post then checks Σ B rows = B's contract value incl. VAT + C, posts its
 * TCO and every row's PDR, and the deposit JV moves A's deposit to B's leaf (resolved
 * for B's property when it differs).</p>
 */
@Service
public class LeaseTransferService {

    private static final Set<LeaseStatus> TRANSFERABLE = EnumSet.of(LeaseStatus.ACTIVE, LeaseStatus.NOTICE_GIVEN);
    private static final Set<ChequeStatus> UNCLEARED = EnumSet.of(ChequeStatus.REGISTERED, ChequeStatus.DEPOSITED,
            ChequeStatus.ONLINE_PENDING);

    private final LeaseRepository leaseRepository;
    private final LeaseLineRepository leaseLineRepository;
    private final ChequeRepository chequeRepository;
    private final UnitRepository unitRepository;
    private final LeaseTransferChequeRepository plans;
    private final TenantFiscalSettingsRepository fiscalSettings;
    private final LeaseService leaseService;
    private final LeaseTerminationService termination;
    private final ChequeGenerationService chequeGeneration;
    private final DepositCarryForward depositCarryForward;
    private final LeaseDepositLedger depositLedger;
    private final LeaseClosureService closure;
    private final PostingService postingService;
    private final LeaseAccessPolicy leaseAccessPolicy;

    public LeaseTransferService(LeaseRepository leaseRepository, LeaseLineRepository leaseLineRepository,
                                ChequeRepository chequeRepository, UnitRepository unitRepository,
                                LeaseTransferChequeRepository plans, TenantFiscalSettingsRepository fiscalSettings,
                                LeaseService leaseService, LeaseTerminationService termination,
                                ChequeGenerationService chequeGeneration, DepositCarryForward depositCarryForward,
                                LeaseDepositLedger depositLedger, LeaseClosureService closure,
                                PostingService postingService, LeaseAccessPolicy leaseAccessPolicy) {
        this.leaseRepository = leaseRepository;
        this.leaseLineRepository = leaseLineRepository;
        this.chequeRepository = chequeRepository;
        this.unitRepository = unitRepository;
        this.plans = plans;
        this.fiscalSettings = fiscalSettings;
        this.leaseService = leaseService;
        this.termination = termination;
        this.chequeGeneration = chequeGeneration;
        this.depositCarryForward = depositCarryForward;
        this.depositLedger = depositLedger;
        this.closure = closure;
        this.postingService = postingService;
        this.leaseAccessPolicy = leaseAccessPolicy;
    }

    // ------------------------------------------------------------------
    // draft
    // ------------------------------------------------------------------

    @Transactional
    public LeaseDTO draft(UUID leaseId, TransferLeaseRequest r, LeasePostingService posting) {
        Lease a = posting.lockLease(leaseId);
        leaseAccessPolicy.requireManageable(a);
        requireTransferable(a, r == null ? null : r.moveDate());
        Unit target = targetUnit(a, r.targetUnitId());
        LocalDate t = r.moveDate();
        LocalDate start = t.plusDays(1);
        LocalDate end = r.endDate() != null ? r.endDate() : a.getEndDate();
        if (!end.isAfter(start)) {
            throw new BusinessRuleViolationException("The new term must end after it starts (" + start + ").");
        }

        CreateLeaseDTO dto = new CreateLeaseDTO();
        dto.setUnitId(target.getId());
        dto.setRenterId(a.getRenter().getId());
        dto.setStartDate(start);
        dto.setEndDate(end);
        dto.setContractDate(r.contractDate() != null ? r.contractDate() : start);
        dto.setFirstDueDate(start);
        dto.setPaymentTerms(a.getPaymentTerms());
        dto.setInstallmentDistribution(a.getInstallmentDistribution());
        dto.setPaymentMethod(a.getPaymentMethod() != null ? a.getPaymentMethod().name() : null);
        dto.setDepositPaymentMethod(a.getDepositPaymentMethod() != null ? a.getDepositPaymentMethod().name() : null);
        dto.setGracePeriodDays(a.isGracePeriodOverridden() ? a.getGracePeriodDays() : null);
        dto.setRentVatApplicable(a.isRentVatApplicable());
        List<LeaseLineInput> lines = r.lines() != null && !r.lines().isEmpty() ? r.lines() : defaultLines(a, start, end);
        if ((r.lines() == null || r.lines().isEmpty()) && r.rent() != null) {
            if (r.rent().signum() <= 0) throw new BusinessRuleViolationException("The new rent must be more than zero.");
            // The rent the operator typed in place of the suggestion; the fees stay pro rata.
            lines = lines.stream().map(l -> isRent(l.chargeTypeId())
                    ? new LeaseLineInput(l.chargeTypeId(), l.chargeTypeCode(), r.rent().setScale(2, RoundingMode.HALF_UP),
                            BigDecimal.ZERO, l.narration(), l.vatApplicable(), l.creditAccountId(), l.periodStart(), l.periodEnd())
                    : l).toList();
        }
        dto.setLines(lines);

        LeaseDTO b = leaseService.createTransferDraft(dto, a, t);
        Lease successor = leaseRepository.findById(b.getId()).orElseThrow();
        leaseAccessPolicy.requireManageable(successor);
        savePlan(a, successor, t, r.chequeDispositions());

        leaseService.recordLeaseEvent(a, a.getStatus(), a.getStatus(), "Transfer to "
                + target.getUnitNumber() + " drafted (move date " + t + "); post the new lease to complete it");
        return leaseService.getLeaseById(successor.getId());
    }

    /**
     * The new term's lines when none are sent: the recurring lines of A (rent and
     * fees earned over the term, not an addendum's or an extension's, not a one-off,
     * not the deposit — that is carried), each at A's day rate for the new term's
     * days (product decision 2026-09-24: a transfer keeps the old rent unless
     * edited). Accounts resolve for the new property.
     */
    List<LeaseLineInput> defaultLines(Lease a, LocalDate start, LocalDate end) {
        int newDays = ProrationEngine.daysInclusive(start, end);
        List<LeaseLineInput> out = new ArrayList<>();
        for (LeaseLine l : leaseLineRepository.findByLease_IdOrderBySeqNoAsc(a.getId())) {
            ChargeType type = l.getChargeType();
            if (type == null || l.getAddendumId() != null) continue;
            ChargeBehaviour b = type.getBehaviour();
            if (b == ChargeBehaviour.DEPOSIT) continue;
            if (b == ChargeBehaviour.FEE && (type.getRecognition() == null || !type.getRecognition().recurs())) continue;
            LocalDate from = l.getPeriodStart() != null ? l.getPeriodStart() : a.getStartDate();
            LocalDate to = l.getPeriodEnd() != null ? l.getPeriodEnd() : a.getEndDate();
            if (from.isAfter(a.getStartDate())) continue;   // an extension's window
            BigDecimal net = l.getGrossAmount().subtract(l.getDiscountAmount() == null ? BigDecimal.ZERO : l.getDiscountAmount());
            BigDecimal amount = net.multiply(BigDecimal.valueOf(newDays))
                    .divide(BigDecimal.valueOf(ProrationEngine.daysInclusive(from, to)), 2, RoundingMode.HALF_UP);
            out.add(new LeaseLineInput(type.getId(), null, amount, BigDecimal.ZERO,
                    b == ChargeBehaviour.RENT ? null : l.getNarration(), l.isVatApplicable(), null, null, null));
        }
        if (out.isEmpty()) {
            throw new BusinessRuleViolationException("Nothing recurring to carry into the new term; send its lines.");
        }
        return out;
    }

    private void savePlan(Lease a, Lease b, LocalDate t, List<TransferLeaseRequest.ChequeDisposition> asked) {
        Map<UUID, String> chosen = new HashMap<>();
        if (asked != null) {
            for (TransferLeaseRequest.ChequeDisposition d : asked) {
                if (d == null || d.chequeId() == null) continue;
                String disp = d.disposition() == null ? null : d.disposition().trim().toUpperCase();
                if (!LeaseTransferCheque.CARRY.equals(disp) && !LeaseTransferCheque.KEEP.equals(disp)
                        && !LeaseTransferCheque.RETURN.equals(disp)) {
                    throw new BusinessRuleViolationException("A cheque goes into CARRY, KEEP or RETURN; not " + d.disposition() + ".");
                }
                if (chosen.put(d.chequeId(), disp) != null) {
                    throw new BusinessRuleViolationException("A cheque is listed twice in the plan.");
                }
            }
        }
        List<Cheque> register = chequeRepository.findByLease_IdOrderBySeqNoAsc(a.getId());
        Set<UUID> uncleared = new java.util.HashSet<>();
        for (Cheque c : register) {
            if (!UNCLEARED.contains(c.getStatus())) continue;
            uncleared.add(c.getId());
            String disp = chosen.getOrDefault(c.getId(), defaultDisposition(c, t));
            if (LeaseTransferCheque.CARRY.equals(disp) && c.getStatus() != ChequeStatus.REGISTERED) {
                throw new BusinessRuleViolationException("Cheque " + label(c) + " is " + c.getStatus()
                        + "; only a cheque still in the drawer (REGISTERED) can be carried.",
                        "lease.transferCarryNotRegistered", Map.of("row", label(c), "status", c.getStatus().name()));
            }
            LeaseTransferCheque row = new LeaseTransferCheque();
            row.setSuccessorLeaseId(b.getId());
            row.setChequeId(c.getId());
            row.setDisposition(disp);
            plans.save(row);
        }
        for (UUID id : chosen.keySet()) {
            if (!uncleared.contains(id)) {
                throw new BusinessRuleViolationException("The plan names a cheque that is not an uncleared row of this lease.");
            }
        }
    }

    static String defaultDisposition(Cheque c, LocalDate t) {
        return c.getStatus() == ChequeStatus.REGISTERED && c.getChequeDate() != null && c.getChequeDate().isAfter(t)
                ? LeaseTransferCheque.CARRY : LeaseTransferCheque.KEEP;
    }

    // ------------------------------------------------------------------
    // preview
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public TransferPreviewDTO preview(UUID leaseId, LocalDate t, UUID targetUnitId, LocalDate endDate) {
        Lease a = leaseRepository.findByIdScopedToTenant(leaseId).orElseThrow(() -> new NotFoundException("Lease not found"));
        leaseAccessPolicy.requireReadable(a);
        List<String> problems = new ArrayList<>();
        try {
            requireTransferable(a, t);
            targetUnit(a, targetUnitId);
        } catch (BusinessRuleViolationException e) {
            problems.add(e.getMessage());
        }
        if (t == null) return new TransferPreviewDTO(null, targetUnitId, null, null, 0, null, null, null, null, null,
                null, List.of(), null, null, problems);
        LocalDate start = t.plusDays(1);
        LocalDate end = endDate != null ? endDate : a.getEndDate();
        int days = end.isAfter(start) ? ProrationEngine.daysInclusive(start, end) : 0;

        List<Cheque> register = chequeRepository.findByLease_IdOrderBySeqNoAsc(leaseId);
        List<TransferPreviewDTO.Row> rows = new ArrayList<>();
        List<Cheque> leaving = new ArrayList<>();
        BigDecimal carried = BigDecimal.ZERO;
        for (Cheque c : register) {
            if (!UNCLEARED.contains(c.getStatus())) continue;
            String d = defaultDisposition(c, t);
            if (!LeaseTransferCheque.KEEP.equals(d)) leaving.add(c);
            if (LeaseTransferCheque.CARRY.equals(d)) carried = carried.add(c.getAmount());
            rows.add(new TransferPreviewDTO.Row(c.getId(), c.getSeqNo(), c.getChequeNumber(), c.getChequeDate(),
                    c.getAmount(), c.getStatus().name(), d));
        }
        LeaseTerminationService.TransferEnd endA = null;
        BigDecimal suggested = null;
        BigDecimal c = null;
        BigDecimal gap = null;
        if (problems.isEmpty()) {
            endA = termination.previewForTransfer(a, t, leaving);
            c = endA.receivableAfter().subtract(bouncedTotal(register));
            List<LeaseLineInput> lines = days > 0 ? defaultLines(a, start, end) : List.of();
            suggested = lines.stream().filter(l -> isRent(l.chargeTypeId()))
                    .map(LeaseLineInput::grossAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal value = lines.stream().map(l -> l.grossAmount().add(Boolean.TRUE.equals(l.vatApplicable())
                            ? l.grossAmount().multiply(LeaseVat.RATE).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            gap = value.add(c).subtract(carried).setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal deposit = depositLedger.depositHeld(a);
        return new TransferPreviewDTO(t, targetUnitId, start, end, days,
                endA == null ? null : endA.earnedThrough(), endA == null ? null : endA.unearned(),
                endA == null ? null : endA.unearnedVat(), c, deposit, suggested, rows, carried, gap, problems);
    }

    private boolean isRent(UUID chargeTypeId) {
        return chargeTypeId != null && chargeTypeRent(chargeTypeId);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.datagami.rentaxis.domain.repository.ChargeTypeRepository chargeTypes;

    private boolean chargeTypeRent(UUID id) {
        return chargeTypes.findById(id).map(ct -> ct.getBehaviour() == ChargeBehaviour.RENT).orElse(false);
    }

    // ------------------------------------------------------------------
    // post
    // ------------------------------------------------------------------

    /** What completing A left for B's post to check: C, and the carried rows now on B. */
    record Completed(BigDecimal carriedBalance, List<Cheque> carriedRows) {
    }

    /**
     * Spec §2, steps 1–4 and A's status. Called by {@code LeasePostingService.post}
     * with B locked, before B's own validation, inside B's posting transaction:
     * anything refused here or later rolls all of it back.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Completed completeForPosting(Lease b, LeasePostingService posting) {
        Lease a = posting.lockLease(b.getTransferredFromLeaseId());
        leaseAccessPolicy.requireManageable(a);
        LocalDate t = b.getTransferMoveDate();
        requireTransferable(a, t, b.getId());
        if (!t.plusDays(1).equals(b.getStartDate())) {
            throw new BusinessRuleViolationException("The new lease must start the day after the move date ("
                    + t.plusDays(1) + "); it starts " + b.getStartDate() + ".");
        }
        if (b.getUnit().getId().equals(a.getUnit().getId())) {
            throw new BusinessRuleViolationException("The new lease is on the same unit; that is a renewal, not a transfer.");
        }
        List<String> locks = posting.periodLockErrors(t.plusDays(1), List.of());
        if (!locks.isEmpty()) throw new BusinessRuleViolationException(String.join(" ", locks));

        Map<UUID, String> plan = new LinkedHashMap<>();
        for (LeaseTransferCheque p : plans.findBySuccessorLeaseId(b.getId())) plan.put(p.getChequeId(), p.getDisposition());
        List<Cheque> register = chequeRepository.findByLease_IdOrderBySeqNoAsc(a.getId());
        List<Cheque> toCarry = new ArrayList<>();
        List<Cheque> toReturn = new ArrayList<>();
        for (Cheque c : register) {
            // Re-validated: a row that cleared since the draft drops out of CARRY and
            // its money is in C; a row added since stays on A (KEEP).
            if (!UNCLEARED.contains(c.getStatus())) continue;
            String d = plan.getOrDefault(c.getId(), LeaseTransferCheque.KEEP);
            if (LeaseTransferCheque.CARRY.equals(d) && c.getStatus() == ChequeStatus.REGISTERED) toCarry.add(c);
            else if (LeaseTransferCheque.RETURN.equals(d)) toReturn.add(c);
        }

        String note = "Transferred to " + unitNumber(b);
        UUID tcrId = termination.endForTransfer(a, t, toReturn, toCarry, note);

        // Step 4: what A's receivable holds now, bounced debt left behind on A.
        BigDecimal c = termination.receivableBalance(a).subtract(bouncedTotal(register)).setScale(2, RoundingMode.HALF_UP);
        if (c.signum() != 0) {
            String narration = "Balance carried to " + unitNumber(b) + " on transfer";
            PostingRequest.Dimensions dimsA = LeaseChequeRegistrar.dimensions(a, null);
            PostingRequest.Dimensions dimsB = LeaseChequeRegistrar.dimensions(b, null);
            BigDecimal amount = c.abs();
            PostingRequest.Pair pair = c.signum() < 0
                    ? PostingRequest.pair(LeaseChequeRegistrar.drReceivable(a, amount).withDims(dimsA).withNarration(narration),
                            LeaseChequeRegistrar.crReceivable(b, amount).withDims(dimsB).withNarration(narration))
                    : PostingRequest.pair(LeaseChequeRegistrar.drReceivable(b, amount).withDims(dimsB).withNarration(narration),
                            LeaseChequeRegistrar.crReceivable(a, amount).withDims(dimsA).withNarration(narration));
            postingService.post(PostingRequest.ofPairs(JournalDocType.JV, t.plusDays(1), narration, dimsB,
                    JournalSourceType.LEASE, b.getId(), null, List.of(pair)));
        }

        // The carried instruments, re-registered with B's grid when B posts.
        List<Cheque> copies = new ArrayList<>();
        if (!toCarry.isEmpty()) {
            boolean sameProperty = java.util.Objects.equals(LeasePostingService.propertyIdOf(a),
                    LeasePostingService.propertyIdOf(b));
            List<ChequeRowInput> rows = toCarry.stream().map(o -> new ChequeRowInput(null, null, b.getContractDate(),
                    o.getChequeNumber(), o.getChequeDate(), o.getPayeeBank(), o.getPayerName(),
                    sameProperty && o.getDebitAccount() != null ? o.getDebitAccount().getId() : null,
                    o.getAmount(), o.getNarration(), o.getMode(), null, o.getRowKind())).toList();
            copies = chequeGeneration.appendRows(b, rows, b.getContractDate());
            for (int i = 0; i < copies.size(); i++) {
                Cheque copy = copies.get(i);
                Cheque old = toCarry.get(i);
                copy.setTransferredFromId(old.getId());
                copy.setImageUrl(old.getImageUrl());
                copy.setImageBlobPath(old.getImageBlobPath());
                copy.setImageUploadedAt(old.getImageUploadedAt());
                chequeRepository.save(copy);
                Cheque fresh = chequeRepository.findById(old.getId()).orElseThrow();
                fresh.setTransferredToId(copy.getId());
                chequeRepository.save(fresh);
            }
        }

        leaseService.markTransferredOut(a, t, b, tcrId);
        return new Completed(c, copies);
    }

    /** Spec §2: what the dry run of B needs, estimated with nothing written. */
    public record Estimate(BigDecimal carriedBalance, BigDecimal carriedTotal, List<String> problems) {
    }

    @Transactional(readOnly = true)
    public Estimate estimateForDryRun(Lease b) {
        List<String> problems = new ArrayList<>();
        Lease a = leaseRepository.findByIdScopedToTenant(b.getTransferredFromLeaseId()).orElse(null);
        if (a == null) return new Estimate(BigDecimal.ZERO, BigDecimal.ZERO, List.of("The lease being left is gone."));
        LocalDate t = b.getTransferMoveDate();
        try {
            requireTransferable(a, t, b.getId());
            if (!t.plusDays(1).equals(b.getStartDate())) {
                problems.add("The new lease must start the day after the move date (" + t.plusDays(1) + ").");
            }
        } catch (BusinessRuleViolationException e) {
            return new Estimate(BigDecimal.ZERO, BigDecimal.ZERO, List.of(e.getMessage()));
        }
        Map<UUID, String> plan = new HashMap<>();
        for (LeaseTransferCheque p : plans.findBySuccessorLeaseId(b.getId())) plan.put(p.getChequeId(), p.getDisposition());
        List<Cheque> register = chequeRepository.findByLease_IdOrderBySeqNoAsc(a.getId());
        List<Cheque> leaving = new ArrayList<>();
        BigDecimal carried = BigDecimal.ZERO;
        for (Cheque c : register) {
            if (!UNCLEARED.contains(c.getStatus())) continue;
            String d = plan.getOrDefault(c.getId(), LeaseTransferCheque.KEEP);
            if (LeaseTransferCheque.CARRY.equals(d) && c.getStatus() == ChequeStatus.REGISTERED) {
                leaving.add(c);
                carried = carried.add(c.getAmount());
            } else if (LeaseTransferCheque.RETURN.equals(d)) {
                leaving.add(c);
            }
        }
        LeaseTerminationService.TransferEnd end = termination.previewForTransfer(a, t, leaving);
        BigDecimal c = end.receivableAfter().subtract(bouncedTotal(register)).setScale(2, RoundingMode.HALF_UP);
        return new Estimate(c, carried, problems);
    }

    /**
     * After B's post has carried the deposit: A closes as settled by transfer when
     * nothing is left on it (no bounced or kept instrument).
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void closeIfSettledByTransfer(Lease b) {
        Lease a = leaseRepository.findByIdScopedToTenant(b.getTransferredFromLeaseId()).orElse(null);
        if (a != null) closure.closeSettledByTransfer(a, unitNumber(b));
    }

    // ------------------------------------------------------------------
    // rules
    // ------------------------------------------------------------------

    private void requireTransferable(Lease a, LocalDate t) {
        requireTransferable(a, t, null);
    }

    /** @param successor B while it is being posted: not "another" transfer of A */
    private void requireTransferable(Lease a, LocalDate t, UUID successor) {
        if (!TRANSFERABLE.contains(a.getStatus()) || a.getPostingJournalId() == null) {
            throw new BusinessRuleViolationException("Only a posted ACTIVE or NOTICE_GIVEN lease can be transferred;"
                    + " this one is " + a.getStatus() + ".");
        }
        if (t == null) throw new BusinessRuleViolationException("A transfer needs the move date (the last night in the current unit).");
        if (t.isBefore(a.getStartDate()) || t.isAfter(a.getEndDate())) {
            throw new BusinessRuleViolationException("The move date " + t + " is outside the lease term ("
                    + a.getStartDate() + " – " + a.getEndDate() + ").");
        }
        if (t.equals(a.getEndDate())) {
            throw new BusinessRuleViolationException("The move date is the lease's last day: renew onto the new unit"
                    + " instead of transferring.", "lease.transferOnLastDay", Map.of());
        }
        LocalDate locked = fiscalSettings.findById(a.getTenantId()).map(TenantFiscalSettings::getBooksLockedThrough).orElse(null);
        if (locked != null && !t.isAfter(locked)) {
            throw new BusinessRuleViolationException("Cannot transfer on " + t + ": books are locked through " + locked + ".");
        }
        for (Lease s : leaseRepository.findByRenewedFromLeaseId(a.getId())) {
            throw new BusinessRuleViolationException("This lease has a renewal (" + s.getId() + ", " + s.getStatus()
                    + "); delete the draft renewal first.", "lease.transferHasRenewal", Map.of());
        }
        for (Lease s : leaseRepository.findByTransferredFromLeaseId(a.getId())) {
            if (s.getId().equals(successor)) continue;
            throw new BusinessRuleViolationException("This lease already has a transfer (" + s.getId() + ", "
                    + s.getStatus() + ").", "lease.transferExists", Map.of());
        }
    }

    private Unit targetUnit(Lease a, UUID targetUnitId) {
        if (targetUnitId == null) throw new BusinessRuleViolationException("Choose the unit the renter moves to.");
        Unit unit = unitRepository.findById(targetUnitId)
                .filter(u -> a.getTenantId().equals(u.getTenantId()))
                .orElseThrow(() -> new NotFoundException("Unit not found"));
        UUID tenant = TenantContextHolder.getTenantId();
        if (tenant != null && !tenant.equals(unit.getTenantId())) throw new NotFoundException("Unit not found");
        if (unit.getId().equals(a.getUnit().getId())) {
            throw new BusinessRuleViolationException("The renter is already in this unit; choose another.",
                    "lease.transferSameUnit", Map.of());
        }
        return unit;
    }

    private static BigDecimal bouncedTotal(List<Cheque> register) {
        return register.stream().filter(c -> c.getStatus() == ChequeStatus.BOUNCED)
                .map(Cheque::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String unitNumber(Lease l) {
        return l.getUnit() != null && l.getUnit().getUnitNumber() != null ? l.getUnit().getUnitNumber() : String.valueOf(l.getId());
    }

    private static String label(Cheque c) {
        return "#" + c.getSeqNo() + (c.getChequeNumber() == null ? "" : " (" + c.getChequeNumber() + ")");
    }
}
