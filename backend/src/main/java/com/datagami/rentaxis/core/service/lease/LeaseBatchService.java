package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.api.dto.lease.LeaseBatch;
import com.datagami.rentaxis.api.dto.lease.RenewLeaseRequest;
import com.datagami.rentaxis.api.dto.lease.TerminateLeaseRequest;
import com.datagami.rentaxis.api.dto.lease.TerminationPreviewDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * The renewal season's batch acts (scale spec #16 bulk renew, #17 bulk terminate).
 *
 * <p><b>No parallel path.</b> Every contract goes through the single-contract service the
 * detail page uses — {@link LeaseRenewalService#renew}, {@link LeaseTerminationService#preview}
 * and {@link LeaseTerminationService#terminate} — with the same arguments the single screen
 * would send, so a batch result is by construction the result of doing the contracts one by
 * one. Nothing here writes a journal; the termination's journals are written by the
 * termination service through {@code PostingService}, as they always are, and a renewal
 * writes none (it is a DRAFT; posting stays the separate, reviewable bulk post).</p>
 *
 * <p><b>One transaction per contract, deliberately not one per batch.</b> This class is not
 * {@code @Transactional}: each row runs in a {@code REQUIRES_NEW} transaction of its own, and
 * the single service joins it. A contract the service refuses rolls back alone — all or
 * nothing for that contract (a termination's cheque returns, recognition cut and TCR stand or
 * fall together) — and the batch carries on with the next, reporting the refusal on its row.
 * {@code TenantAspect} enables the tenant filter inside each row transaction, and every
 * lookup goes through {@code LeaseAccessPolicy}, so a manager's batch touches only their
 * buildings and another organisation's id is "Lease not found", exactly as on the single door.</p>
 */
@Service
public class LeaseBatchService {

    private static final Logger log = LoggerFactory.getLogger(LeaseBatchService.class);

    private final LeaseRepository leaseRepository;
    private final LeaseAccessPolicy leaseAccessPolicy;
    private final LeaseRenewalService renewalService;
    private final LeaseTerminationService terminationService;
    private final ChequeGenerationService chequeGeneration;
    private final com.datagami.rentaxis.domain.repository.ChequeRepository chequeRepository;
    private final TransactionTemplate rowTx;
    /** Review P3-1: previews and plans run read-only, as the single preview does — Hibernate never flushes. */
    private final TransactionTemplate readRowTx;

    public LeaseBatchService(LeaseRepository leaseRepository,
                             LeaseAccessPolicy leaseAccessPolicy,
                             LeaseRenewalService renewalService,
                             LeaseTerminationService terminationService,
                             ChequeGenerationService chequeGeneration,
                             com.datagami.rentaxis.domain.repository.ChequeRepository chequeRepository,
                             PlatformTransactionManager transactionManager) {
        this.leaseRepository = leaseRepository;
        this.leaseAccessPolicy = leaseAccessPolicy;
        this.renewalService = renewalService;
        this.terminationService = terminationService;
        this.chequeGeneration = chequeGeneration;
        this.chequeRepository = chequeRepository;
        this.rowTx = new TransactionTemplate(transactionManager);
        this.rowTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readRowTx = new TransactionTemplate(transactionManager);
        this.readRowTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readRowTx.setReadOnly(true);
    }

    // ------------------------------------------------------------------
    // bulk renew (#16)
    // ------------------------------------------------------------------

    /** What a row of a bulk renew asks the single renew for, worked out from the predecessor. */
    record RenewTerms(RenewLeaseRequest request, BigDecimal previousRent) {
    }

    public List<LeaseBatch.RenewRow> renew(LeaseBatch.RenewRequest r) {
        requireRule(r);
        List<LeaseBatch.RenewRow> rows = new ArrayList<>();
        for (UUID leaseId : distinct(r.leaseIds())) {
            rows.add(run(leaseId, id -> renewOne(id, r), (code, args, message) ->
                    new LeaseBatch.RenewRow(leaseId, false, null, null, null, null, null, null, 0, code, args, message)));
        }
        return rows;
    }

    private LeaseBatch.RenewRow renewOne(UUID leaseId, LeaseBatch.RenewRequest r) {
        RenewTerms terms = termsFor(leaseId, r);
        LeaseDTO draft = renewalService.renew(leaseId, terms.request());
        int rows = 0;
        if (r.generateGrid()) {
            rows = chequeGeneration.generate(draft.getId(), gridFor(leaseId, draft)).size();
        }
        return new LeaseBatch.RenewRow(leaseId, true, draft.getId(), draft.getStartDate(), draft.getEndDate(),
                draft.getRenewalPreviousRent() != null ? draft.getRenewalPreviousRent() : terms.previousRent(),
                draft.getRentAmount(), draft.getRenewalChangePercent(), rows, null, null, null);
    }

    /**
     * The grid the draft editor's "Generate cheques" would cut: the lease's own payment terms
     * (copied from the predecessor). A contract with none — an import that carried its own
     * register — takes as many instalments as the predecessor's rent cheques, the nearest
     * reading of "the same as now". Everything else is the generator's default.
     */
    private com.datagami.rentaxis.api.dto.lease.GenerateChequesRequest gridFor(UUID predecessorId, LeaseDTO draft) {
        if (draft.getPaymentTerms() != null && draft.getPaymentTerms() > 0) return null;
        long rent = chequeRepository.findByLease_IdOrderBySeqNoAsc(predecessorId).stream()
                .filter(c -> c.getRowKind() == null || c.getRowKind() == com.datagami.rentaxis.domain.entity.enums.ChequeRowKind.RENT
                        || c.getRowKind() == com.datagami.rentaxis.domain.entity.enums.ChequeRowKind.MIXED)
                .filter(c -> c.getStatus() != com.datagami.rentaxis.domain.entity.enums.ChequeStatus.REPLACED
                        && c.getStatus() != com.datagami.rentaxis.domain.entity.enums.ChequeStatus.CANCELLED)
                .count();
        return new com.datagami.rentaxis.api.dto.lease.GenerateChequesRequest((int) Math.max(1, rent), null, null, null,
                null, null, null);
    }

    /**
     * The single renew request this rule means for one contract: the day after its end, a term
     * as long as the current one (or {@code termMonths}), and the rent change. Package-private
     * so the parity tests can renew the same contract by hand with the very same request.
     */
    RenewTerms termsFor(UUID leaseId, LeaseBatch.RenewRequest r) {
        Lease lease = leaseRepository.findByIdScopedToTenant(leaseId)
                .orElseThrow(() -> new NotFoundException("Lease not found"));
        leaseAccessPolicy.requireManageable(lease);
        if (lease.getStartDate() == null || lease.getEndDate() == null) {
            throw new BusinessRuleViolationException("The contract has no term to renew from.");
        }
        LocalDate start = lease.getEndDate().plusDays(1);
        LocalDate end = r.termMonths() != null
                ? LeaseRenewalService.termEnd(start, r.termMonths())
                : sameTermEnd(lease.getStartDate(), lease.getEndDate(), start);
        List<com.datagami.rentaxis.api.dto.lease.LeaseLineInput> extra =
                r.additionalLines() == null || r.additionalLines().isEmpty() ? null : r.additionalLines();
        LeaseBatch.RentRule rule = r.rule();
        RenewLeaseRequest.RentChange change;
        BigDecimal previous = null;
        switch (rule.mode()) {
            case NONE -> change = null;
            case PERCENT -> change = new RenewLeaseRequest.RentChange(RenewLeaseRequest.RentChange.Mode.PERCENT,
                    rule.percent(), null);
            case ADD -> {
                // The single renew's own base, read by its own plan: what the renter pays now.
                RenewLeaseRequest probe = new RenewLeaseRequest(r.contractDate(), start, end, null,
                        r.carryDepositForward(), null, null, null);
                BigDecimal base = renewalService.planFor(leaseId, probe).baseRent();
                if (base == null) {
                    throw new BusinessRuleViolationException(
                            "The lease being renewed has no contract rent line to revise.");
                }
                previous = base;
                BigDecimal newRent = base.add(rule.amount()).setScale(0, RoundingMode.HALF_UP);
                if (newRent.signum() <= 0) {
                    throw new BusinessRuleViolationException("The rent change leaves no rent to charge.");
                }
                change = new RenewLeaseRequest.RentChange(RenewLeaseRequest.RentChange.Mode.AMOUNT, null,
                        newRent.setScale(2));
            }
            default -> throw new IllegalStateException();
        }
        return new RenewTerms(new RenewLeaseRequest(r.contractDate(), start, end, null, r.carryDepositForward(),
                change, null, extra), previous);
    }

    /** {@link #termsFor} in a read-only transaction of its own (review P3-1), for callers outside one. */
    public RenewLeaseRequest renewRequestFor(UUID leaseId, LeaseBatch.RenewRequest r) {
        return readRowTx.execute(s -> termsFor(leaseId, r)).request();
    }

    /**
     * The end of a term as long as {@code oldStart..oldEnd}, starting {@code newStart}: the
     * web's {@code leaseMath.sameTermEnd}, which the single renew dialog proposes. A whole-month
     * term renews as the same number of months; any other as its Period (months, then days).
     */
    static LocalDate sameTermEnd(LocalDate oldStart, LocalDate oldEnd, LocalDate newStart) {
        Integer months = LeaseRenewalService.wholeMonthTerm(oldStart, oldEnd);
        if (months != null) return LeaseRenewalService.termEnd(newStart, months);
        Period p = Period.between(oldStart, oldEnd.plusDays(1)).normalized();
        return newStart.plusMonths(p.toTotalMonths()).plusDays(p.getDays()).minusDays(1);
    }

    private static void requireRule(LeaseBatch.RenewRequest r) {
        LeaseBatch.RentRule rule = r.rule();
        if (rule == null || rule.mode() == null) {
            throw new BusinessRuleViolationException("Choose how the rent changes.");
        }
        if (rule.mode() == LeaseBatch.RentRule.Mode.PERCENT && rule.percent() == null) {
            throw new BusinessRuleViolationException("Enter the rent change in percent.");
        }
        if (rule.mode() == LeaseBatch.RentRule.Mode.ADD && (rule.amount() == null || rule.amount().signum() == 0)) {
            throw new BusinessRuleViolationException("Enter the amount the rent changes by.");
        }
        if (r.termMonths() != null && (r.termMonths() < 1 || r.termMonths() > 120)) {
            throw new BusinessRuleViolationException("The new term must be between 1 and 120 months.");
        }
    }

    // ------------------------------------------------------------------
    // bulk terminate (#17)
    // ------------------------------------------------------------------

    /** Each contract's termination preview, or why it cannot be terminated. Writes nothing. */
    public List<LeaseBatch.TerminatePreviewRow> previewTerminations(LeaseBatch.TerminateRequest r) {
        List<LeaseBatch.TerminatePreviewRow> rows = new ArrayList<>();
        for (UUID leaseId : distinct(r.leaseIds())) {
            rows.add(run(readRowTx, leaseId, id -> {
                LocalDate t = dateFor(id, r.terminationDate());
                TerminationPreviewDTO preview = terminationService.preview(id, t);
                return new LeaseBatch.TerminatePreviewRow(id, true, t, preview, null, null, null);
            }, (code, args, message) ->
                    new LeaseBatch.TerminatePreviewRow(leaseId, false, null, null, code, args, message)));
        }
        return rows;
    }

    /**
     * End each contract on its date with the preview's default cheque split — what the single
     * termination page sends when the operator accepts the preview (both lists empty). Each
     * contract is one transaction: it ends completely or not at all.
     */
    public List<LeaseBatch.TerminateRow> terminate(LeaseBatch.TerminateRequest r, UUID byUser) {
        List<LeaseBatch.TerminateRow> rows = new ArrayList<>();
        for (UUID leaseId : distinct(r.leaseIds())) {
            rows.add(run(leaseId, id -> {
                LocalDate t = dateFor(id, r.terminationDate());
                LeaseBatch.ChequeSplit split = r.splits() == null ? null : r.splits().get(id);
                LeaseDTO ended = terminationService.terminate(id, new TerminateLeaseRequest(t,
                        split == null ? null : split.returnChequeIds(), split == null ? null : split.keepChequeIds(),
                        r.notes() == null || r.notes().isBlank() ? null : r.notes().trim()), byUser);
                return new LeaseBatch.TerminateRow(id, true, t, ended.getStatus() == null ? null : ended.getStatus().name(),
                        ended.getTerminationJournalId(), null, null, null);
            }, (code, args, message) ->
                    new LeaseBatch.TerminateRow(leaseId, false, null, null, null, code, args, message)));
        }
        return rows;
    }

    private LocalDate dateFor(UUID leaseId, LocalDate requested) {
        if (requested != null) return requested;
        Lease lease = leaseRepository.findByIdScopedToTenant(leaseId)
                .orElseThrow(() -> new NotFoundException("Lease not found"));
        leaseAccessPolicy.requireReadable(lease);
        return lease.getEndDate();
    }

    // ------------------------------------------------------------------
    // rows
    // ------------------------------------------------------------------

    @FunctionalInterface
    interface Failure<R> {
        R of(String code, Map<String, Object> args, String message);
    }

    /**
     * One contract in a {@code REQUIRES_NEW} transaction; a refusal rolls back that contract
     * only and comes back as its row, in the single act's own words (and code, when it has one).
     */
    private <R> R run(UUID leaseId, Function<UUID, R> work, Failure<R> failure) {
        return run(rowTx, leaseId, work, failure);
    }

    private <R> R run(TransactionTemplate tx, UUID leaseId, Function<UUID, R> work, Failure<R> failure) {
        try {
            return tx.execute(s -> work.apply(leaseId));
        } catch (BusinessRuleViolationException e) {
            return failure.of(e.getCode(), e.getCode() == null ? null : e.getArgs(), e.getMessage());
        } catch (NotFoundException | org.springframework.security.access.AccessDeniedException e) {
            return failure.of("lease.notFound", null, "Lease not found");
        } catch (com.datagami.rentaxis.api.exception.ContractChangedException e) {
            return failure.of(com.datagami.rentaxis.api.exception.ContractChangedException.CODE, null, e.getMessage());
        } catch (org.springframework.dao.ConcurrencyFailureException e) {
            return failure.of("lease.batchBusy", null,
                    "This contract was changed by another request at the same time; try again.");
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.warn("Batch row {} refused by a constraint: {}", leaseId, e.getMostSpecificCause().getMessage());
            return failure.of("lease.batchConflict", null,
                    "This contract conflicts with another record (it may already have a successor); reload and check it.");
        } catch (RuntimeException e) {
            log.error("Batch row {} failed", leaseId, e);
            return failure.of("lease.batchFailed", null, "This contract could not be processed; open it and try on its own.");
        }
    }

    private static List<UUID> distinct(List<UUID> ids) {
        if (ids == null) return List.of();
        if (ids.size() > LeaseBatch.MAX_LEASES) {
            throw new BusinessRuleViolationException("At most " + LeaseBatch.MAX_LEASES + " contracts at a time.");
        }
        return List.copyOf(new LinkedHashSet<>(ids.stream().filter(java.util.Objects::nonNull).toList()));
    }
}
