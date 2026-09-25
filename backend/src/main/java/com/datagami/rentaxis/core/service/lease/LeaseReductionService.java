package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.lease.AddendumResponse;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.lease.ReduceLeaseRequest;
import com.datagami.rentaxis.api.dto.lease.ReductionPreviewDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.ledger.EntryNumberService;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.recognition.ProrationEngine;
import com.datagami.rentaxis.core.service.recognition.RecognitionPoster;
import com.datagami.rentaxis.core.service.recognition.RecognitionService;
import com.datagami.rentaxis.core.service.vat.VatTaxPointService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseAddendum;
import com.datagami.rentaxis.domain.entity.LeaseAddendumCredit;
import com.datagami.rentaxis.domain.entity.LeaseLine;
import com.datagami.rentaxis.domain.entity.RentSegment;
import com.datagami.rentaxis.domain.entity.TenantFiscalSettings;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChargeBehaviour;
import com.datagami.rentaxis.domain.entity.enums.ChequeRowKind;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.JournalStatus;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LeaseAddendumCreditRepository;
import com.datagami.rentaxis.domain.repository.LeaseAddendumRepository;
import com.datagami.rentaxis.domain.repository.LeaseLineRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.TenantFiscalSettingsRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A mid-term reduction (F14-32): a numbered <em>credit</em> addendum that removes a
 * charge, or lowers its rate, for the rest of the term — even after cheques have
 * cleared, because nothing already on the books is rewritten.
 *
 * <p>Per line cut, day by day: the line's schedule has {@code R} left to earn from
 * the effective date {@code E}; at the new rate it would have {@code R'}
 * ({@code new amount × remaining days ÷ the line's window days}). The credit is
 * {@code R − R'}. One {@code TCC} journal posts, per line,
 * {@code Dr <the leaf the TCO deferred into> / Cr RENT_RECEIVABLE}, plus the VAT on
 * the credit: off the tax points still to be declared
 * ({@code Dr OUTPUT_VAT_DEFERRED}), and the rest, already declared, as a credit note
 * ({@code Dr OUTPUT_VAT}, a TCN). The recognition schedule keeps what was earned
 * through {@code E − 1} and earns {@code R'} from {@code E}.</p>
 *
 * <p>The receivable is now {@code credit} lower than the instruments that pay it.
 * That excess goes one of two ways: <b>CHEQUES</b> — uncleared instalments are
 * handed back (PDR reversed) and optionally replaced by smaller ones, and the two
 * must come to the credit exactly; or <b>CREDIT</b> — it stays on the renter's
 * receivable as a credit, which later charges net against and the settlement
 * refunds (through its Pay refund).</p>
 *
 * <p>Refused: a line with no schedule left to cut (a one-off fee, fees posted at
 * posting, a schedule that already ended), a new amount below zero (it would take
 * the rest of the term negative), or one that is not lower. Every method is
 * {@code @Transactional}: the tenant filter only applies inside one.</p>
 */
@Service
public class LeaseReductionService {

    private static final Set<LeaseStatus> REDUCIBLE = EnumSet.of(LeaseStatus.ACTIVE, LeaseStatus.NOTICE_GIVEN);

    private final LeaseRepository leaseRepository;
    private final LeaseLineRepository leaseLineRepository;
    private final ChequeRepository chequeRepository;
    private final LeaseAddendumRepository addendumRepository;
    private final LeaseAddendumCreditRepository creditRepository;
    private final TenantFiscalSettingsRepository fiscalSettings;
    private final LeaseService leaseService;
    private final LeasePostingService leasePostingService;
    private final LeaseVariationService variationService;
    private final ChequeGenerationService chequeGeneration;
    private final LeaseChequeRegistrar chequeRegistrar;
    private final ChequeService chequeService;
    private final RecognitionService recognition;
    private final RecognitionPoster recognitionPoster;
    private final VatTaxPointService vatTaxPoints;
    private final PostingService postingService;
    private final EntryNumberService entryNumbers;
    private final LeaseAccessPolicy leaseAccessPolicy;

    public LeaseReductionService(LeaseRepository leaseRepository, LeaseLineRepository leaseLineRepository,
                                 ChequeRepository chequeRepository, LeaseAddendumRepository addendumRepository,
                                 LeaseAddendumCreditRepository creditRepository,
                                 TenantFiscalSettingsRepository fiscalSettings, LeaseService leaseService,
                                 LeasePostingService leasePostingService, LeaseVariationService variationService,
                                 ChequeGenerationService chequeGeneration, LeaseChequeRegistrar chequeRegistrar,
                                 ChequeService chequeService, RecognitionService recognition,
                                 RecognitionPoster recognitionPoster, VatTaxPointService vatTaxPoints,
                                 PostingService postingService, EntryNumberService entryNumbers,
                                 LeaseAccessPolicy leaseAccessPolicy) {
        this.leaseRepository = leaseRepository;
        this.leaseLineRepository = leaseLineRepository;
        this.chequeRepository = chequeRepository;
        this.addendumRepository = addendumRepository;
        this.creditRepository = creditRepository;
        this.fiscalSettings = fiscalSettings;
        this.leaseService = leaseService;
        this.leasePostingService = leasePostingService;
        this.variationService = variationService;
        this.chequeGeneration = chequeGeneration;
        this.chequeRegistrar = chequeRegistrar;
        this.chequeService = chequeService;
        this.recognition = recognition;
        this.recognitionPoster = recognitionPoster;
        this.vatTaxPoints = vatTaxPoints;
        this.postingService = postingService;
        this.entryNumbers = entryNumbers;
        this.leaseAccessPolicy = leaseAccessPolicy;
    }

    /** One line's cut, computed. */
    private record LinePlan(LeaseLine line, List<RentSegment> segments, BigDecimal newLineAmount,
                            LocalDate from, LocalDate to, int remainingDays,
                            BigDecimal before, BigDecimal after, BigDecimal credit, BigDecimal vat) {
    }

    // ------------------------------------------------------------------
    // preview
    // ------------------------------------------------------------------

    /** What {@link #reduce} would do, with nothing written; problems are listed rather than thrown. */
    @Transactional(readOnly = true)
    public ReductionPreviewDTO preview(UUID leaseId, ReduceLeaseRequest r) {
        Lease lease = leaseRepository.findById(leaseId).orElseThrow(() -> new NotFoundException("Lease not found"));
        requireOwnTenant(lease);
        leaseAccessPolicy.requireReadable(lease);
        List<ReductionPreviewDTO.Problem> problems = new ArrayList<>();
        List<LinePlan> plans = List.of();
        try {
            requireReducible(lease, r);
            plans = plan(lease, r);
        } catch (BusinessRuleViolationException e) {
            problems.add(new ReductionPreviewDTO.Problem(e.getCode(), e.getMessage(), e.getArgs()));
        }
        BigDecimal net = sum(plans.stream().map(LinePlan::credit).toList());
        BigDecimal vat = sum(plans.stream().map(LinePlan::vat).toList());
        BigDecimal taxable = taxableOf(plans);
        VatTaxPointService.ReductionVat rv = vatTaxPoints.previewReduction(leaseId, vat, taxable);
        BigDecimal total = net.add(vat);

        List<Cheque> register = chequeRepository.findByLease_IdOrderBySeqNoAsc(leaseId);
        List<ReductionPreviewDTO.ReturnableCheque> returnable = register.stream().filter(this::returnable)
                .map(c -> new ReductionPreviewDTO.ReturnableCheque(c.getId(), c.getSeqNo(), c.getChequeNumber(),
                        c.getChequeDate(), c.getAmount(), c.getVatAmount()))
                .toList();
        Set<UUID> chosen = new HashSet<>(r == null || r.returnChequeIds() == null ? List.of() : r.returnChequeIds());
        BigDecimal returned = sum(register.stream().filter(c -> chosen.contains(c.getId())).map(Cheque::getAmount).toList());
        BigDecimal newRows = sum(rows(r).stream().map(ChequeRowInput::amount).toList());
        BigDecimal gap = ReduceLeaseRequest.EXCESS_CHEQUES.equals(r == null ? null : r.excess())
                ? total.subtract(returned.subtract(newRows)) : BigDecimal.ZERO;

        List<ReductionPreviewDTO.LineCredit> lines = plans.stream().map(p -> new ReductionPreviewDTO.LineCredit(
                p.line().getId(), p.line().getChargeType().getCode(), p.line().getChargeType().getNameEn(),
                p.line().getChargeType().getNameAr(), p.line().getNetAmount(), p.newLineAmount(), p.from(), p.to(),
                p.remainingDays(), p.before(), p.after(), p.credit(), p.vat())).toList();
        return new ReductionPreviewDTO(r == null ? null : r.effectiveFrom(), lines, s2(net), rv.fromDeferred(),
                rv.creditNote(), s2(total), returnable, s2(returned), s2(newRows), s2(gap), problems);
    }

    // ------------------------------------------------------------------
    // reduce
    // ------------------------------------------------------------------

    @Transactional
    public AddendumResponse reduce(UUID leaseId, ReduceLeaseRequest r) {
        Lease lease = leasePostingService.lockLease(leaseId);
        leaseAccessPolicy.requireManageable(lease);
        requireReducible(lease, r);
        LocalDate e = r.effectiveFrom();
        LocalDate entryDate = r.contractDate() != null ? r.contractDate() : LocalDate.now();
        if (entryDate.isBefore(lease.getContractDate() == null ? entryDate : lease.getContractDate())) {
            throw new BusinessRuleViolationException("The credit addendum cannot be dated before the contract ("
                    + lease.getContractDate() + ").");
        }

        // ---- stage 1: the arithmetic and every refusal, nothing written ----
        List<LinePlan> plans = plan(lease, r);
        BigDecimal net = sum(plans.stream().map(LinePlan::credit).toList());
        BigDecimal vat = sum(plans.stream().map(LinePlan::vat).toList());
        BigDecimal total = net.add(vat);

        String excess = r.excess() == null ? ReduceLeaseRequest.EXCESS_CREDIT : r.excess().trim().toUpperCase();
        if (!ReduceLeaseRequest.EXCESS_CHEQUES.equals(excess) && !ReduceLeaseRequest.EXCESS_CREDIT.equals(excess)) {
            throw new BusinessRuleViolationException("Say where the excess goes: CHEQUES or CREDIT.");
        }
        List<Cheque> register = chequeRepository.findByLease_IdOrderBySeqNoAsc(leaseId);
        List<Cheque> toReturn = new ArrayList<>();
        List<ChequeRowInput> newRowInputs = rows(r);
        if (ReduceLeaseRequest.EXCESS_CREDIT.equals(excess)) {
            if ((r.returnChequeIds() != null && !r.returnChequeIds().isEmpty()) || !newRowInputs.isEmpty()) {
                throw new BusinessRuleViolationException("A credit left on the renter's account hands back no"
                        + " instalments; choose CHEQUES to hand some back.");
            }
        } else {
            Set<UUID> ids = new HashSet<>(r.returnChequeIds() == null ? List.of() : r.returnChequeIds());
            for (UUID id : ids) {
                Cheque c = register.stream().filter(x -> x.getId().equals(id)).findFirst()
                        .orElseThrow(() -> new BusinessRuleViolationException("Instalment " + id + " is not on this lease."));
                if (!returnable(c)) {
                    throw new BusinessRuleViolationException("Instalment " + label(c) + " is " + c.getStatus()
                            + "; only an uncleared, undeposited rent or fee instalment can be handed back.",
                            "lease.reductionChequeNotReturnable", Map.of("row", label(c), "status", c.getStatus().name()));
                }
                toReturn.add(c);
            }
            BigDecimal returned = sum(toReturn.stream().map(Cheque::getAmount).toList());
            BigDecimal replaced = sum(newRowInputs.stream().map(ChequeRowInput::amount).toList());
            BigDecimal gap = total.subtract(returned.subtract(replaced));
            if (gap.signum() != 0) {
                throw new BusinessRuleViolationException("The instalments handed back (" + money(returned)
                        + ") less the replacements (" + money(replaced) + ") must come to the credit of "
                        + money(total) + "; they are " + money(gap.abs()) + (gap.signum() > 0 ? " short." : " over."),
                        "lease.reductionChequeGap", Map.of("returned", money(returned), "replaced", money(replaced),
                                "credit", money(total), "gap", money(gap.abs()),
                                "direction", gap.signum() > 0 ? "short" : "over"));
            }
        }

        List<String> problems = new ArrayList<>(leasePostingService.periodLockErrors(entryDate, List.of()));
        LocalDate locked = lockedThrough();
        if (locked != null && !e.minusDays(1).isAfter(locked)) {
            problems.add("The day before the effective date (" + e.minusDays(1) + ") is in the locked period: books"
                    + " are locked through " + locked + ", and the recognition before it cannot be re-cut.");
        }

        // ---- stage 2: the rows exist, journals do not ------------------------
        List<ChequeRowInput> stripped = newRowInputs.stream().map(LeaseReductionService::withoutVat).toList();
        List<Cheque> newRows = stripped.isEmpty() ? List.of()
                : chequeGeneration.appendRows(lease, stripped, entryDate, List.of());
        problems.addAll(leasePostingService.periodLockErrors(entryDate, newRows));
        problems.addAll(LeaseChequeRegistrar.missingNumbers(newRows));
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(String.join(" ", problems));
        }

        // ---- stage 3: the number and the journals -------------------------
        String number = entryNumbers.nextDocumentNumber("ADD", entryDate);
        String reason = blankToNull(r.reason());
        String narration = "Credit addendum " + number + (reason == null ? "" : ": " + reason);

        for (Cheque c : toReturn) {
            chequeService.returnToTenant(c.getId(), entryDate, "Handed back under credit addendum " + number);
        }
        VatTaxPointService.ReductionVat rv = vatTaxPoints.applyReduction(lease, vat, taxableOf(plans), toReturn, newRows);
        chequeRepository.saveAll(newRows);

        List<PostingRequest.Pair> pairs = new ArrayList<>();
        Map<PostingRequest.AccountRef, BigDecimal> byAccount = new LinkedHashMap<>();
        for (LinePlan p : plans) {
            byAccount.merge(recognitionPoster.deferralOf(p.segments().get(0), lease), p.credit(), BigDecimal::add);
        }
        byAccount.forEach((account, amount) -> pairs.add(PostingRequest.pair(
                new PostingRequest.Line(account, PostingRequest.Side.DR, amount, null, narration),
                LeaseChequeRegistrar.crReceivable(lease, amount).withNarration(narration))));
        if (rv.fromDeferred().signum() > 0) {
            String n = "VAT not yet declared on the reduced charges";
            pairs.add(PostingRequest.pair(
                    PostingRequest.dr(AccountRole.OUTPUT_VAT_DEFERRED, rv.fromDeferred()).withNarration(n),
                    LeaseChequeRegistrar.crReceivable(lease, rv.fromDeferred()).withNarration(n)));
        }
        if (rv.creditNote().signum() > 0) {
            String n = "VAT credited back on the reduced charges";
            pairs.add(PostingRequest.pair(
                    PostingRequest.dr(AccountRole.OUTPUT_VAT, rv.creditNote()).withNarration(n),
                    LeaseChequeRegistrar.crReceivable(lease, rv.creditNote()).withNarration(n)));
        }
        JournalEntry tcc = postingService.post(PostingRequest.ofPairs(JournalDocType.TCC, entryDate, narration,
                LeaseChequeRegistrar.dimensions(lease, null), JournalSourceType.LEASE, lease.getId(), null, pairs));

        for (LinePlan p : plans) {
            recognition.reduceFrom(lease, p.segments(), e, p.after(), entryDate, "Credit addendum " + number);
        }
        for (Cheque row : newRows) {
            chequeRegistrar.register(lease, row);
        }
        vatTaxPoints.buildForLease(lease.getId());
        vatTaxPoints.recordReductionCreditNote(lease, entryDate, rv, tcc.getId());

        LeaseAddendum addendum = new LeaseAddendum();
        addendum.setLease(lease);
        addendum.setKind(LeaseAddendum.KIND_CREDIT);
        addendum.setExcess(excess);
        addendum.setAddendumNumber(number);
        addendum.setEffectiveFrom(e);
        addendum.setContractDate(entryDate);
        addendum.setEjariNumber(blankToNull(r.ejariNumber()));
        addendum.setReason(reason);
        addendum.setValue(net.negate());
        addendum.setTcoJournalId(tcc.getId());
        addendum.setTcoEntryNumber(tcc.getEntryNumber());
        addendum.setCreatedBy(currentUserId());
        addendum = addendumRepository.save(addendum);
        for (LinePlan p : plans) {
            LeaseAddendumCredit c = new LeaseAddendumCredit();
            c.setAddendumId(addendum.getId());
            c.setLeaseLineId(p.line().getId());
            c.setNewLineAmount(p.newLineAmount());
            c.setRemainingBefore(p.before());
            c.setRemainingAfter(p.after());
            c.setCreditAmount(p.credit());
            c.setVatAmount(p.vat());
            creditRepository.save(c);
        }

        // PR #359 R1 P2-1: the header's current rent, the unit's rent and the dashboard.
        leaseService.syncDerivedTotals(lease);
        leaseRepository.save(lease);

        leaseService.recordLeaseEvent(lease, lease.getStatus(), lease.getStatus(),
                "Credit addendum " + number + " from " + e + ": " + money(total) + " off the renter's account ("
                        + plans.stream().map(p -> p.line().getChargeType().getCode()).distinct()
                                .reduce((a, b) -> a + ", " + b).orElse("") + "), posted as " + tcc.getEntryNumber()
                        + (ReduceLeaseRequest.EXCESS_CHEQUES.equals(excess)
                                ? "; " + toReturn.size() + " instalment(s) handed back, " + newRows.size() + " added"
                                : "; left as a credit on the renter's account"));

        return new AddendumResponse(variationService.toDto(addendum, JournalStatus.POSTED),
                leasePostingService.response(lease, tcc, chequeRepository.findByLease_IdOrderBySeqNoAsc(leaseId)));
    }

    // ------------------------------------------------------------------
    // the arithmetic
    // ------------------------------------------------------------------

    private void requireReducible(Lease lease, ReduceLeaseRequest r) {
        if (!REDUCIBLE.contains(lease.getStatus()) || lease.getPostingJournalId() == null) {
            throw new BusinessRuleViolationException("Only a posted ACTIVE or NOTICE_GIVEN lease can be reduced;"
                    + " this one is " + lease.getStatus() + ".");
        }
        if (r == null || r.effectiveFrom() == null) {
            throw new BusinessRuleViolationException("The credit addendum needs an effective date");
        }
        LocalDate e = r.effectiveFrom();
        LocalDate end = lease.getTerminatedOn() != null ? lease.getTerminatedOn() : lease.getEndDate();
        if (e.isBefore(lease.getStartDate()) || e.isAfter(end)) {
            throw new BusinessRuleViolationException("The reduction must take effect within the tenancy ("
                    + lease.getStartDate() + " to " + end + "), not " + e + ".");
        }
        if (r.lines() == null || r.lines().isEmpty()) {
            throw new BusinessRuleViolationException("Choose at least one charge to reduce or remove.");
        }
    }

    private List<LinePlan> plan(Lease lease, ReduceLeaseRequest r) {
        LocalDate e = r.effectiveFrom();
        Map<UUID, LeaseLine> lines = new LinkedHashMap<>();
        for (LeaseLine l : leaseLineRepository.findByLease_IdOrderBySeqNoAsc(lease.getId())) lines.put(l.getId(), l);
        Set<UUID> seen = new HashSet<>();
        List<LinePlan> out = new ArrayList<>();
        for (ReduceLeaseRequest.LineReduction lr : r.lines()) {
            LeaseLine line = lr == null || lr.lineId() == null ? null : lines.get(lr.lineId());
            if (line == null) throw new BusinessRuleViolationException("That charge is not a line of this lease.");
            if (!seen.add(line.getId())) {
                throw new BusinessRuleViolationException("Line " + line.getSeqNo() + " is listed twice.");
            }
            String code = line.getChargeType() == null ? "?" : line.getChargeType().getCode();
            BigDecimal newAmount = lr.newAmount() == null ? BigDecimal.ZERO : lr.newAmount().setScale(2, RoundingMode.HALF_UP);
            if (newAmount.signum() < 0) {
                throw new BusinessRuleViolationException("Line " + line.getSeqNo() + " (" + code + "): a new amount of "
                        + money(newAmount) + " would take the rest of the term below zero.",
                        "lease.reductionNegative", Map.of("line", line.getSeqNo(), "code", code));
            }
            boolean rent = line.getChargeType() != null && line.getChargeType().getBehaviour() == ChargeBehaviour.RENT;
            boolean scheduled = rent || LeasePostingService.earnedOverTerm(lease, line);
            List<RentSegment> segs = scheduled ? recognition.liveSegmentsFrom(lease.getId(), line.getId(), e) : List.of();
            if (segs.isEmpty()) {
                throw new BusinessRuleViolationException("Line " + line.getSeqNo() + " (" + code + ") has nothing left"
                        + " to earn from " + e + " — a one-off fee, a fee taken to income at posting, or a charge whose"
                        + " period has ended — so it cannot be reduced by a credit addendum.",
                        "lease.reductionNotScheduled", Map.of("line", line.getSeqNo(), "code", code));
            }
            LocalDate winFrom = line.getPeriodStart() != null ? line.getPeriodStart() : lease.getStartDate();
            LocalDate winTo = line.getPeriodEnd() != null ? line.getPeriodEnd() : lease.getEndDate();
            LocalDate from = segs.stream().map(RentSegment::getFromDate).min(LocalDate::compareTo).orElseThrow();
            if (from.isBefore(e)) from = e;
            LocalDate to = segs.stream().map(RentSegment::getToDate).max(LocalDate::compareTo).orElseThrow();
            int remainingDays = ProrationEngine.daysInclusive(from, to);
            BigDecimal before = RecognitionService.remainingFrom(segs, e);
            BigDecimal after = newAmount.multiply(BigDecimal.valueOf(remainingDays))
                    .divide(BigDecimal.valueOf(ProrationEngine.daysInclusive(winFrom, winTo)), 2, RoundingMode.HALF_UP);
            BigDecimal credit = before.subtract(after);
            if (credit.signum() <= 0) {
                throw new BusinessRuleViolationException("Line " + line.getSeqNo() + " (" + code + "): at "
                        + money(newAmount) + " the rest of the term is worth " + money(after) + ", not less than the "
                        + money(before) + " still to earn; a credit addendum only reduces.",
                        "lease.reductionNotLower", Map.of("line", line.getSeqNo(), "code", code));
            }
            BigDecimal vat = LeaseVat.vatOnPortion(line, credit);
            out.add(new LinePlan(line, segs, newAmount, from, to, remainingDays, before, after, credit,
                    vat == null ? BigDecimal.ZERO : vat));
        }
        return out;
    }

    private static BigDecimal taxableOf(List<LinePlan> plans) {
        return sum(plans.stream().filter(p -> p.vat().signum() > 0).map(LinePlan::credit).toList());
    }

    private boolean returnable(Cheque c) {
        return c.getStatus() == ChequeStatus.REGISTERED && c.getRowKind() != ChequeRowKind.DEPOSIT;
    }

    private static List<ChequeRowInput> rows(ReduceLeaseRequest r) {
        return r == null || r.cheques() == null ? List.of() : r.cheques();
    }

    private static ChequeRowInput withoutVat(ChequeRowInput in) {
        return new ChequeRowInput(null, null, in.postingDate(), in.chequeNumber(), in.chequeDate(), in.payeeBank(),
                in.payerName(), in.debitAccountId(), in.amount(), in.narration(), in.mode(), null, in.rowKind());
    }

    private LocalDate lockedThrough() {
        UUID tenantId = TenantContextHolder.getTenantId();
        return tenantId == null ? null : fiscalSettings.findById(tenantId)
                .map(TenantFiscalSettings::getBooksLockedThrough).orElse(null);
    }

    private static void requireOwnTenant(Lease lease) {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId != null && !tenantId.equals(lease.getTenantId())) throw new NotFoundException("Lease not found");
    }

    private static BigDecimal sum(List<BigDecimal> xs) {
        return xs.stream().filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal s2(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static String money(BigDecimal v) {
        return LeasePostingService.money(v);
    }

    private static String label(Cheque c) {
        return "#" + c.getSeqNo() + (c.getChequeNumber() == null ? "" : " (" + c.getChequeNumber() + ")");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof String s)) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
