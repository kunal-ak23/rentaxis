package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.core.service.LeaseService;
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
    // bulk cheque numbering (scale PR C ruling 2)
    // ------------------------------------------------------------------

    /** Each draft's grid for the bulk numbering screen, read-only. A lease that is not a DRAFT is refused. */
    public List<LeaseBatch.DraftGrid> draftGrids(LeaseBatch.DraftsRequest r) {
        List<LeaseBatch.DraftGrid> rows = new ArrayList<>();
        for (UUID leaseId : distinct(r.leaseIds())) {
            rows.add(run(readRowTx, leaseId, id -> {
                Lease lease = leaseRepository.findByIdScopedToTenant(id)
                        .orElseThrow(() -> new NotFoundException("Lease not found"));
                leaseAccessPolicy.requireReadable(lease);
                requireDraft(lease);
                return new LeaseBatch.DraftGrid(id, true, lease.getVersion(),
                        LeaseService.displayContractNumber(lease.getUnit().getProperty().getCode(), lease.getContractNumber()),
                        lease.getUnit().getUnitNumber(), lease.getRenter().getNameEn(),
                        lease.getUnit().getProperty().getNameEn(), chequeGeneration.list(id), null, null, null);
            }, (code, args, message) -> new LeaseBatch.DraftGrid(leaseId, false, null, null, null, null, null, null,
                    code, args, message)));
        }
        return rows;
    }

    /**
     * Number the drafts' cheques: each draft's grid saved by {@link ChequeGenerationService#saveRows}
     * — the single grid's save — with exactly the rows the grid editor would send (every DRAFT row
     * as loaded, its number, bank and date replaced), against the version the screen loaded. So a
     * bulk save is a series of single saves. Before that, each row is checked: it belongs to the
     * draft, its amount is the one shown, a post-dated cheque has a number and a date, and no number
     * repeats within the draft; any of these refuses the draft with per-row errors and writes
     * nothing. One transaction per draft; no journal (the rows are DRAFT).
     */
    public List<LeaseBatch.DraftNumbersRow> saveChequeNumbers(LeaseBatch.ChequeNumbersRequest r) {
        if (r.drafts() == null || r.drafts().size() > LeaseBatch.MAX_LEASES) {
            throw new BusinessRuleViolationException("At most " + LeaseBatch.MAX_LEASES + " contracts at a time.");
        }
        List<LeaseBatch.DraftNumbersRow> out = new ArrayList<>();
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        for (LeaseBatch.DraftNumbers d : r.drafts()) {
            if (d == null || d.leaseId() == null || !seen.add(d.leaseId())) continue;
            out.add(run(d.leaseId(), id -> numberOne(d), (code, args, message) ->
                    new LeaseBatch.DraftNumbersRow(d.leaseId(), false, null, null, List.of(), code, args, message)));
        }
        return out;
    }

    private LeaseBatch.DraftNumbersRow numberOne(LeaseBatch.DraftNumbers d) {
        Lease lease = leaseRepository.findByIdScopedToTenant(d.leaseId())
                .orElseThrow(() -> new NotFoundException("Lease not found"));
        leaseAccessPolicy.requireManageable(lease);
        requireDraft(lease);
        List<com.datagami.rentaxis.domain.entity.Cheque> grid = chequeRepository.findByLease_IdOrderBySeqNoAsc(lease.getId())
                .stream().filter(c -> c.getStatus() == com.datagami.rentaxis.domain.entity.enums.ChequeStatus.DRAFT).toList();
        java.util.Map<UUID, com.datagami.rentaxis.domain.entity.Cheque> byId = new java.util.LinkedHashMap<>();
        grid.forEach(c -> byId.put(c.getId(), c));
        java.util.Map<UUID, LeaseBatch.ChequeNumberRow> edits = new java.util.LinkedHashMap<>();
        List<LeaseBatch.RowError> errors = new ArrayList<>();
        for (LeaseBatch.ChequeNumberRow row : d.rows() == null ? List.<LeaseBatch.ChequeNumberRow>of() : d.rows()) {
            if (row == null) continue;
            com.datagami.rentaxis.domain.entity.Cheque c = byId.get(row.chequeId());
            if (c == null) {
                errors.add(new LeaseBatch.RowError(row.chequeId(), null, "cheque.notInDraft",
                        "This row is not a draft cheque of this contract any more; reload the grid."));
                continue;
            }
            if (row.amount() != null && (c.getAmount() == null || row.amount().compareTo(c.getAmount()) != 0)) {
                errors.add(new LeaseBatch.RowError(row.chequeId(), "amount", "cheque.amountChanged",
                        "The amount is " + c.getAmount() + " now, not " + row.amount() + "; reload the grid."));
            }
            edits.put(row.chequeId(), row);
        }
        // Every PDC row of the draft, after the edits: a number and a date, and no number twice.
        java.util.Map<String, UUID> numbers = new java.util.HashMap<>();
        for (com.datagami.rentaxis.domain.entity.Cheque c : grid) {
            LeaseBatch.ChequeNumberRow e = edits.get(c.getId());
            String number = e != null ? chequeNumber(e.chequeNumber()) : c.getChequeNumber();
            LocalDate date = e != null && e.chequeDate() != null ? e.chequeDate() : c.getChequeDate();
            if (c.getMode() != com.datagami.rentaxis.domain.entity.enums.ChequeMode.PDC) continue;
            if (number == null) {
                errors.add(new LeaseBatch.RowError(c.getId(), "chequeNumber", "cheque.numberRequired", "Enter the cheque number."));
            } else if (numbers.putIfAbsent(number, c.getId()) != null) {
                errors.add(new LeaseBatch.RowError(c.getId(), "chequeNumber", "cheque.numberRepeated",
                        "Cheque number " + number + " is used twice in this contract."));
            }
            if (date == null) {
                errors.add(new LeaseBatch.RowError(c.getId(), "chequeDate", "cheque.dateRequired", "Enter the cheque date."));
            }
        }
        if (!errors.isEmpty()) {
            return new LeaseBatch.DraftNumbersRow(lease.getId(), false, lease.getVersion(), null, errors,
                    "cheque.rowsRefused", Map.of("count", errors.size()),
                    errors.size() + " row(s) need attention; nothing was saved for this contract.");
        }
        List<ChequeRowInput> input = new ArrayList<>(grid.size());
        for (com.datagami.rentaxis.domain.entity.Cheque c : grid) {
            input.add(gridRow(c, edits.get(c.getId())));
        }
        ChequeGenerationService.VersionedCheques saved = chequeGeneration.saveRows(lease.getId(), input, d.version());
        return new LeaseBatch.DraftNumbersRow(lease.getId(), true, saved.leaseVersion(), saved.cheques(), List.of(),
                null, null, null);
    }

    /**
     * The row the grid editor sends for a loaded cheque ({@code ChequeGrid.toChequeRows}), with the
     * edit's number, bank and date when there is one. Package-private for the parity test.
     */
    static ChequeRowInput gridRow(com.datagami.rentaxis.domain.entity.Cheque c, LeaseBatch.ChequeNumberRow edit) {
        String number = edit != null ? chequeNumber(edit.chequeNumber()) : c.getChequeNumber();
        String bank = edit != null ? blankToNull(edit.payeeBank()) : c.getPayeeBank();
        LocalDate date = edit != null && edit.chequeDate() != null ? edit.chequeDate() : c.getChequeDate();
        return new ChequeRowInput(c.getId(), c.getSeqNo(), c.getPostingDate(), number, date, bank,
                blankToNull(c.getPayerName()), c.getDebitAccount() == null ? null : c.getDebitAccount().getId(),
                c.getAmount(), blankToNull(c.getNarration()), c.getMode(), c.getVatAmount(), c.getRowKind());
    }

    /**
     * A typed cheque number as saved and compared (re-review P3-1): trimmed, with Arabic-Indic
     * (U+0660–0669) and Extended Arabic-Indic (U+06F0–06F9) digits as ASCII, so ٥٠٠٠٠١ and 500001
     * are one number. Null when blank.
     */
    static String chequeNumber(String s) {
        String t = blankToNull(s);
        if (t == null) return null;
        StringBuilder b = new StringBuilder(t.length());
        for (char ch : t.toCharArray()) {
            if (ch >= '\u0660' && ch <= '\u0669') b.append((char) ('0' + (ch - '\u0660')));
            else if (ch >= '\u06F0' && ch <= '\u06F9') b.append((char) ('0' + (ch - '\u06F0')));
            else b.append(ch);
        }
        return b.toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static void requireDraft(Lease lease) {
        if (lease.getStatus() != com.datagami.rentaxis.domain.entity.enums.LeaseStatus.DRAFT) {
            throw new BusinessRuleViolationException("Only a draft contract's cheques are numbered here; this one is "
                    + lease.getStatus() + ".", "lease.notDraft", Map.of("status", lease.getStatus().name()));
        }
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
