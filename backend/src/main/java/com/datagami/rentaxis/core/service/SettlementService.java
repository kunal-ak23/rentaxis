package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.SaveSettlementDTO;
import com.datagami.rentaxis.api.dto.SettlementResponseDTO;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.recognition.RecognitionEntryDTO;
import com.datagami.rentaxis.api.dto.settlement.AdditionLineDTO;
import com.datagami.rentaxis.api.dto.settlement.DeductionLineDTO;
import com.datagami.rentaxis.api.dto.settlement.FinalizeSettlementRequest;
import com.datagami.rentaxis.api.dto.settlement.OutstandingInstrumentDTO;
import com.datagami.rentaxis.api.dto.settlement.SettlementStatementDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.lease.LeaseChequeRegistrar;
import com.datagami.rentaxis.core.service.lease.LeaseClosureService;
import com.datagami.rentaxis.core.service.lease.LeaseDepositLedger;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.penalty.PenaltyAssessmentService;
import com.datagami.rentaxis.core.service.recognition.RecognitionService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseEvent;
import com.datagami.rentaxis.domain.entity.LeaseSettlement;
import com.datagami.rentaxis.domain.entity.LeaseSettlementDeduction;
import com.datagami.rentaxis.domain.entity.TenantFiscalSettings;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.entity.enums.AdditionCategory;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.DeductionCategory;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.LineItemType;
import com.datagami.rentaxis.domain.entity.enums.RecognitionStatus;
import com.datagami.rentaxis.domain.entity.enums.SettlementStatus;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.LeaseEventRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.LeaseSettlementDeductionRepository;
import com.datagami.rentaxis.domain.repository.LeaseSettlementRepository;
import com.datagami.rentaxis.domain.repository.TenantFiscalSettingsRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The move-out statement and the one journal that closes it (spec §9.2).
 *
 * <p><b>The statement is drawn, not stored.</b> Every figure comes from the ledger
 * and the register on each read: what the tenancy earned is Σ its POSTED
 * recognition rows, what the renter owes is the rent receivable's own balance on
 * this lease, what the landlord is holding is the credit balance of the deposit
 * accounts. None of it is re-derived from the contract columns — a lease that was
 * terminated mid-term has a truncated segment whose {@code amount} is now the
 * earned figure, a deposit may have been partly refunded or carried into a
 * renewal, and arrears computed from the register's dates cannot see an approved
 * penalty or the unearned rent a termination handed back.</p>
 *
 * <p><b>Finalise posts exactly one {@code STL}, with no pairs.</b> A settlement is
 * genuinely n-to-n — several deposit accounts released against several deduction
 * accounts, a receivable and a bank — and there is no clearing account to hang
 * pairs off. The lines are:</p>
 * <ul>
 *   <li>{@code Dr} each deposit account for the balance it holds on this lease;</li>
 *   <li>{@code Dr} each addition's account (the landlord giving income back);</li>
 *   <li>{@code Cr} each deduction's account;</li>
 *   <li>the rent receivable, moved to where the settlement leaves it — flat when
 *       the landlord pays out, at {@code −netRefund} when the renter still owes;</li>
 *   <li>{@code Cr} the chosen bank for the refund, when there is one.</li>
 * </ul>
 *
 * <p>It balances by construction: the receivable movement is
 * {@code −receivableBalance − min(netRefund, 0)} and the bank line is
 * {@code max(netRefund, 0)}, so debits less credits collapse to
 * {@code netRefund − netRefund}. {@code PostingService} enforces it anyway, and
 * {@code SettlementServiceIT} asserts every line by account.</p>
 *
 * <p><b>Order and atomicity.</b> Finalise locks the lease row first, then
 * validates, then posts, then writes the settlement and the lease status — one
 * transaction, so a refused {@code STL} cannot leave a FINALIZED row behind, and
 * two clerks pressing the button queue rather than both reading a DRAFT.</p>
 */
@Service
public class SettlementService {

    /**
     * A contract whose settlement may be closed: one that has ended.
     *
     * <p>EXPIRED is here as well as TERMINATED because a tenancy that simply ran
     * its course is settled by the same statement without §9.1's steps 1–2
     * (spec §9.2, last sentence) — there is no unearned rent to reverse and no
     * paper to hand back, but the deposit still has to come off the books.</p>
     *
     * <p><b>RENEWED is here too</b> (review I-1). Spec §6.6 makes the deposit the
     * accountant's choice: carry it forward "or a settlement on the old lease".
     * With {@code carryDepositForward=false} the successor charges its own deposit
     * line and the predecessor's stays on the predecessor's dimension — and that
     * lease can never be terminated (it was not cut short, and
     * {@code LeaseTerminationService} rightly refuses it), so without this the money
     * was stranded: finalise answered "terminate it first" and terminate answered
     * "settle it instead". The statement is the ordinary one; there is simply no
     * unearned rent and no paper to hand back, exactly as for an expiry.</p>
     *
     * <p><b>CLOSED is not here</b> (review M-3). Closure already requires a
     * FINALIZED settlement, so CLOSED-and-still-settleable is unreachable outside
     * legacy data; if it were reached with a balance due it would raise a
     * collection row on a contract whose register refuses every transition — money
     * nobody could ever take. The already-FINALIZED check below is what refuses a
     * second settlement, and it is unaffected.</p>
     */
    private static final Set<LeaseStatus> SETTLEABLE =
            EnumSet.of(LeaseStatus.TERMINATED, LeaseStatus.EXPIRED, LeaseStatus.RENEWED);

    /**
     * Where a deduction's money goes when the line does not say (spec §9.2).
     *
     * <p>{@code UNPAID_RENT} and {@code PENALTIES} are deliberately absent and are
     * refused rather than defaulted, because both are <em>already charged</em> —
     * though not, as an earlier version of this note claimed, both in
     * {@code receivableBalance}:</p>
     * <ul>
     *   <li>an approved <b>penalty</b> posts {@code Dr RENT_RECEIVABLE / Cr penalty
     *       income} and immediately raises a CASH collection row whose {@code PDR}
     *       credits the receivable straight back. Its net movement there is nil and
     *       the money is in {@code PDC_RECEIVABLE} — it is on the register, which is
     *       where it gets collected, and {@code instrumentsOutstanding} reports it;</li>
     *   <li><b>unpaid rent</b> splits: what a termination handed back is on the
     *       receivable (the reversed {@code PDR} re-debited it) and is netted by
     *       {@code receivableBalance}; what §9.1's keep list left for collection
     *       kept its {@code PDR} and is on the register with the penalties.</li>
     * </ul>
     *
     * <p>Either way a deduction line for one of them charges it a second time. The
     * refusals say which of the two homes the money is actually in.</p>
     */
    /**
     * F14-37: recharges that are supplies of the landlord's — repairing damage,
     * cleaning, replacing keys. On a lease that charges VAT they carry 5 % output
     * VAT and a tax invoice. Utility arrears are not among them: recovered at cost
     * they pass through to the utility account, and the early-termination fee and
     * "other" keep their own treatment.
     */
    static final java.util.Set<DeductionCategory> VATABLE_RECHARGES = java.util.EnumSet.of(
            DeductionCategory.PROPERTY_DAMAGE, DeductionCategory.CLEANING, DeductionCategory.KEY_REPLACEMENT);

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.datagami.rentaxis.domain.repository.LeaseLineRepository leaseLines;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.datagami.rentaxis.core.service.vat.VatTaxPointService vatTaxPoints;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;

    private static final Map<DeductionCategory, AccountRole> DEDUCTION_ROLES =
            new EnumMap<>(Map.of(
                    DeductionCategory.PROPERTY_DAMAGE, AccountRole.MAINTENANCE_CHARGES,
                    DeductionCategory.CLEANING, AccountRole.MAINTENANCE_CHARGES,
                    DeductionCategory.KEY_REPLACEMENT, AccountRole.MAINTENANCE_CHARGES,
                    DeductionCategory.UTILITY_ARREARS, AccountRole.MAINTENANCE_CHARGES,
                    DeductionCategory.EARLY_TERMINATION_FEE, AccountRole.RENT_PENALTY,
                    DeductionCategory.OTHER, AccountRole.OTHER_INCOME));

    /**
     * …and for an addition, which the {@code STL} <em>debits</em>.
     *
     * <p>{@code PREPAID_RENT} and {@code UTILITY_OVERPAYMENT} are absent for the
     * mirror reason: rent paid in advance and a utility overpayment are credits on
     * the receivable already, and the statement subtracts the receivable.</p>
     */
    private static final Map<AdditionCategory, AccountRole> ADDITION_ROLES =
            new EnumMap<>(Map.of(
                    AdditionCategory.DEPOSIT_INTEREST, AccountRole.OTHER_INCOME,
                    AdditionCategory.LANDLORD_COMPENSATION, AccountRole.OTHER_INCOME,
                    AdditionCategory.OTHER, AccountRole.OTHER_INCOME));

    private final LeaseSettlementRepository leaseSettlementRepository;
    private final LeaseSettlementDeductionRepository leaseSettlementDeductionRepository;
    private final LeaseRepository leaseRepository;
    private final LeaseAccessPolicy leaseAccessPolicy;
    private final ChequeRepository chequeRepository;
    private final AccountRepository accountRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final LeaseEventRepository leaseEventRepository;
    private final UserRepository userRepository;
    private final TenantFiscalSettingsRepository fiscalSettings;
    private final LeaseDepositLedger depositLedger;
    private final PenaltyAssessmentService penaltyAssessmentService;
    private final DeductionAttachmentService deductionAttachmentService;
    private final RecognitionService recognitionService;
    private final LedgerQueryService ledgerQueryService;
    private final AccountResolver accountResolver;
    private final PostingService postingService;
    private final ChequeService chequeService;
    private final LeaseClosureService closure;
    private final Clock clock;

    public SettlementService(LeaseSettlementRepository leaseSettlementRepository,
                             LeaseSettlementDeductionRepository leaseSettlementDeductionRepository,
                             LeaseRepository leaseRepository,
                             LeaseAccessPolicy leaseAccessPolicy,
                             ChequeRepository chequeRepository,
                             AccountRepository accountRepository,
                             JournalEntryRepository journalEntryRepository,
                             LeaseEventRepository leaseEventRepository,
                             UserRepository userRepository,
                             TenantFiscalSettingsRepository fiscalSettings,
                             LeaseDepositLedger depositLedger,
                             PenaltyAssessmentService penaltyAssessmentService,
                             DeductionAttachmentService deductionAttachmentService,
                             RecognitionService recognitionService,
                             LedgerQueryService ledgerQueryService,
                             AccountResolver accountResolver,
                             PostingService postingService,
                             ChequeService chequeService,
                             LeaseClosureService closure,
                             Clock clock) {
        this.leaseSettlementRepository = leaseSettlementRepository;
        this.leaseSettlementDeductionRepository = leaseSettlementDeductionRepository;
        this.leaseRepository = leaseRepository;
        this.leaseAccessPolicy = leaseAccessPolicy;
        this.chequeRepository = chequeRepository;
        this.accountRepository = accountRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.leaseEventRepository = leaseEventRepository;
        this.userRepository = userRepository;
        this.fiscalSettings = fiscalSettings;
        this.depositLedger = depositLedger;
        this.penaltyAssessmentService = penaltyAssessmentService;
        this.deductionAttachmentService = deductionAttachmentService;
        this.recognitionService = recognitionService;
        this.ledgerQueryService = ledgerQueryService;
        this.accountResolver = accountResolver;
        this.postingService = postingService;
        this.chequeService = chequeService;
        this.closure = closure;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // the statement
    // ------------------------------------------------------------------

    /**
     * The statement as it stands right now: the ledger, plus whatever lines the
     * draft carries.
     *
     * <p>Readable rather than manageable — a property manager may see what ending
     * a tenancy in their building costs. Only the finance roles may save or
     * finalise (see the controller's role gate).</p>
     */
    @Transactional(readOnly = true)
    public SettlementStatementDTO statement(UUID leaseId) {
        // Object-level authorisation first, on the unscoped row: the role gate
        // admits PROPERTY_MANAGER, so without this a manager could read the
        // deposit and deduction detail of a building they were never assigned.
        leaseAccessPolicy.requireReadable(leaseRepository.findById(leaseId).orElse(null));
        Lease lease = findLeaseWithTenantCheck(leaseId);
        return buildStatement(lease, storedLines(leaseId));
    }

    /**
     * Every figure, from the ledger.
     *
     * <p>Lines are read from the settlement rather than passed in by the caller so
     * that the statement a screen shows and the statement finalise posts are
     * literally the same computation over the same rows.</p>
     */
    private SettlementStatementDTO buildStatement(Lease lease, List<LeaseSettlementDeduction> lines) {
        UUID leaseId = lease.getId();
        UUID propertyId = propertyIdOf(lease);

        List<RecognitionEntryDTO> schedule = recognitionService.scheduleFor(leaseId);
        BigDecimal earnedRent = schedule.stream()
                .filter(r -> r.status() == RecognitionStatus.POSTED)
                .map(RecognitionEntryDTO::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int unrecognised = (int) schedule.stream()
                .filter(r -> r.status() == RecognitionStatus.PLANNED).count();

        // The register once: what it has collected and what it is still holding are
        // two questions about the same rows, and a second query is a second chance
        // to disagree.
        List<Cheque> register = chequeRepository.findByLease_IdOrderBySeqNoAsc(leaseId);
        BigDecimal receivedTotal = register.stream()
                .filter(c -> c.getStatus() == ChequeStatus.CLEARED)
                .map(Cheque::getAmount)
                .filter(a -> a != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // What the landlord is still owed on paper. This is PDC receivable, NOT the
        // rent receivable below: a PDR moves an instrument's money off the
        // receivable the moment the row is registered, so §9.1's keep list and every
        // approved penalty's collection row are invisible to `receivableBalance`.
        // Reported, never netted — an uncleared instrument is collected through the
        // register, and silently deducting it from a deposit would settle a debt the
        // renter is still expected to pay.
        BigDecimal instrumentsOutstanding = ledgerQueryService.accountLedger(
                        accountResolver.resolve(AccountRole.PDC_RECEIVABLE, propertyId).getId(),
                        new LedgerQueryService.LedgerFilter(null, null, null, null, leaseId, null))
                .closingBalance();
        List<OutstandingInstrumentDTO> outstandingInstruments = register.stream()
                .filter(c -> c.getStatus().isUncleared())
                .map(SettlementService::outstanding)
                .sorted(OUTSTANDING_ORDER)
                .toList();

        BigDecimal receivableBalance = ledgerQueryService.accountLedger(receivableAccountOf(lease),
                        new LedgerQueryService.LedgerFilter(null, null, null, null, leaseId, null))
                .closingBalance();

        BigDecimal depositsHeld = depositLedger.heldByAccount(lease).values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal penalties = penaltyAssessmentService.outstandingForLease(leaseId);

        List<DeductionLineDTO> deductions = new ArrayList<>();
        List<AdditionLineDTO> additions = new ArrayList<>();
        BigDecimal totalDeductions = BigDecimal.ZERO;
        BigDecimal totalAdditions = BigDecimal.ZERO;
        BigDecimal totalDeductionVat = BigDecimal.ZERO;
        boolean vatLease = chargesVat(lease);
        for (LeaseSettlementDeduction line : lines) {
            BigDecimal amount = money(line.getAmount());
            Account account = accountOfLine(line, propertyId);
            if (line.getType() == LineItemType.ADDITION) {
                totalAdditions = totalAdditions.add(amount);
                additions.add(new AdditionLineDTO(line.getId(), line.getAdditionCategory(),
                        line.getDescription(), amount,
                        account == null ? null : account.getId(),
                        account == null ? null : account.getName()));
            } else {
                totalDeductions = totalDeductions.add(amount);
                BigDecimal vat = vatLease && VATABLE_RECHARGES.contains(line.getCategory())
                        ? amount.multiply(com.datagami.rentaxis.core.service.lease.LeaseVat.RATE)
                                .setScale(2, java.math.RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
                totalDeductionVat = totalDeductionVat.add(vat);
                deductions.add(new DeductionLineDTO(line.getId(), line.getCategory(),
                        line.getDescription(), amount,
                        account == null ? null : account.getId(),
                        account == null ? null : account.getName(),
                        line.isAutoCalculated(),
                        deductionAttachmentService.getAttachments(line.getId()),
                        money(vat)));
            }
        }

        BigDecimal netRefund = money(depositsHeld)
                .subtract(money(receivableBalance))
                .subtract(totalDeductions)
                .subtract(totalDeductionVat)
                .add(totalAdditions);

        return new SettlementStatementDTO(
                LocalDate.now(clock),
                money(earnedRent), money(receivedTotal), money(receivableBalance),
                money(depositsHeld), money(penalties),
                money(instrumentsOutstanding), outstandingInstruments,
                List.copyOf(deductions), List.copyOf(additions),
                money(totalDeductions), money(totalAdditions), money(netRefund),
                unrecognised, money(totalDeductionVat));
    }

    // ------------------------------------------------------------------
    // the draft
    // ------------------------------------------------------------------

    /**
     * Save the lines. The statement is recomputed from them, never sent in.
     *
     * <p>A draft stays editable until it is FINALIZED, and the snapshot columns are
     * refreshed on every save so {@code GET /settlement} and the live statement
     * cannot disagree the moment somebody presses Save.</p>
     *
     * <p><b>Under the lease's own row lock — the same one finalise takes</b>
     * (review I-4). Without it the two are not serialised at all: finalise claims
     * the lease row, save claimed nothing, and a save that had read a DRAFT before
     * finalise committed wrote the whole row back afterwards — {@code status=DRAFT},
     * {@code journal_id=NULL}, {@code settlement_date=NULL}, possibly different
     * lines — with the {@code STL} posted and the refund already paid out. The
     * settlement could then be finalised a second time, releasing the same deposit
     * and re-charging the same deductions. The lock is what makes the FINALIZED
     * check below reliable rather than advisory; {@code LeaseSettlement}'s
     * {@code @Version} is the second line of defence behind it.</p>
     */
    @Transactional
    public LeaseSettlement saveDraft(UUID leaseId, SaveSettlementDTO dto, UUID userId) {
        Lease lease = lockLease(leaseId);
        leaseAccessPolicy.requireManageable(lease);
        UUID propertyId = propertyIdOf(lease);

        Optional<LeaseSettlement> existingOpt = leaseSettlementRepository.findByLeaseId(leaseId);
        LeaseSettlement settlement;
        if (existingOpt.isPresent()) {
            settlement = existingOpt.get();
            // Read under the lease's row lock, so this is the settled fact and not a
            // value a concurrent finalise is about to overtake.
            if (settlement.getStatus() == SettlementStatus.FINALIZED) {
                throw new BusinessRuleViolationException("Settlement is already finalized");
            }
        } else {
            settlement = new LeaseSettlement();
            settlement.setLeaseId(leaseId);
            settlement.setStatus(SettlementStatus.DRAFT);
            // NOT NULL columns that the statement below overwrites; set so the
            // first insert is legal whatever the statement turns out to be.
            settlement.setDepositAmount(BigDecimal.ZERO);
            settlement.setTotalDeductions(BigDecimal.ZERO);
            settlement.setTotalAdditions(BigDecimal.ZERO);
            settlement.setRefundAmount(BigDecimal.ZERO);
        }
        // The header is saved before the lines are validated, so a refused payload
        // leaves nothing behind because this method is @Transactional and rolls
        // back — not because of the ordering. Validating first would not make the
        // write safe on its own; the transaction is what does.

        settlement.setNotes(dto.getNotes());
        LeaseSettlement saved = leaseSettlementRepository.save(settlement);

        // Every line is validated before any is written: a payload whose third row
        // names a retired account must not leave the first two saved.
        List<SaveSettlementDTO.DeductionItemDTO> items =
                dto.getDeductions() == null ? List.of() : dto.getDeductions();
        for (SaveSettlementDTO.DeductionItemDTO item : items) {
            validateLine(item, propertyId);
        }
        reconcileLines(saved.getId(), items);
        leaseSettlementDeductionRepository.flush();

        snapshot(saved, buildStatement(lease, storedLines(leaseId)));
        return leaseSettlementRepository.save(saved);
    }

    /**
     * The whole grid arrives on every save: a row with an id is updated in place
     * (which is what preserves its attachments), one without is inserted, and a row
     * the payload omits is deleted.
     */
    private void reconcileLines(UUID settlementId, List<SaveSettlementDTO.DeductionItemDTO> items) {
        List<LeaseSettlementDeduction> existing =
                leaseSettlementDeductionRepository.findBySettlementIdOrderByCreatedAtAsc(settlementId);
        Map<UUID, LeaseSettlementDeduction> byId = existing.stream()
                .collect(Collectors.toMap(LeaseSettlementDeduction::getId, d -> d));

        Set<UUID> kept = new HashSet<>();
        for (SaveSettlementDTO.DeductionItemDTO item : items) {
            LeaseSettlementDeduction row;
            if (item.getId() != null && byId.containsKey(item.getId())) {
                row = byId.get(item.getId());
                kept.add(item.getId());
            } else {
                row = new LeaseSettlementDeduction();
                row.setSettlementId(settlementId);
            }
            apply(row, item);
            leaseSettlementDeductionRepository.save(row);
        }
        for (LeaseSettlementDeduction old : existing) {
            if (!kept.contains(old.getId())) {
                deductionAttachmentService.deleteAllByDeductionId(old.getId());
                leaseSettlementDeductionRepository.delete(old);
            }
        }
    }

    private static void apply(LeaseSettlementDeduction row, SaveSettlementDTO.DeductionItemDTO item) {
        LineItemType type = item.getType() != null ? item.getType() : LineItemType.DEDUCTION;
        row.setType(type);
        row.setDescription(item.getDescription());
        row.setAmount(item.getAmount());
        row.setAutoCalculated(item.isAutoCalculated());
        row.setAccountId(item.getAccountId());
        if (type == LineItemType.ADDITION) {
            row.setAdditionCategory(additionCategoryOf(item));
            row.setCategory(null);
        } else {
            row.setCategory(item.getCategory());
            row.setAdditionCategory(null);
        }
    }

    // ------------------------------------------------------------------
    // finalise
    // ------------------------------------------------------------------

    /**
     * Post the {@code STL} and close the settlement (spec §9.2).
     *
     * <p>One transaction, under the lease's own row lock. The lease goes CLOSED
     * only if nothing is outstanding on its register once this has run — see
     * {@link LeaseClosureService}. When the renter still owes, the CASH row raised
     * for the balance is itself outstanding; when §9.1's keep list left an
     * instrument for collection, so is that; either way the contract closes later,
     * when the last of them clears.</p>
     */
    @Transactional
    public SettlementResponseDTO finalizeSettlement(UUID leaseId, FinalizeSettlementRequest request, UUID settledBy) {
        if (request == null || request.settlementDate() == null) {
            throw new BusinessRuleViolationException("A settlement needs a settlement date");
        }
        LocalDate settlementDate = request.settlementDate();

        // The lock first, for the reason posting and termination take it first: two
        // clerks pressing Finalise would otherwise both read a DRAFT and post two
        // STLs releasing the same deposit.
        Lease lease = lockLease(leaseId);
        leaseAccessPolicy.requireManageable(lease);

        // "Already finalised" is asked first, and of the row rather than the lease's
        // status, because the two guards answer different questions and the second
        // press of the button is the commonest way to meet either. Dropping CLOSED
        // from SETTLEABLE (review M-3) put the status guard in front of this one, so
        // a second finalise on a contract the first one closed started answering
        // "terminate the lease before settling it; this one is CLOSED" — true, and
        // the wrong sentence: nothing needs terminating, the settlement is done.
        LeaseSettlement existing = leaseSettlementRepository.findByLeaseId(leaseId).orElse(null);
        if (existing != null && existing.getStatus() == SettlementStatus.FINALIZED) {
            throw new BusinessRuleViolationException("Settlement is already finalized");
        }
        requireSettleable(lease);
        requireUsableDate(lease, settlementDate);
        if (existing == null) {
            throw new NotFoundException("No settlement found for this lease");
        }
        LeaseSettlement settlement = existing;

        UUID propertyId = propertyIdOf(lease);
        List<LeaseSettlementDeduction> lines = storedLines(leaseId);
        // Re-validated here and not only on save: a draft written before these
        // rules existed, or one whose account was retired since, must be refused
        // rather than posted to a leaf nobody can use.
        for (LeaseSettlementDeduction line : lines) {
            requirePostableCategory(line);
            requireUsableLineAccount(line, propertyId);
        }

        SettlementStatementDTO statement = buildStatement(lease, lines);
        BigDecimal netRefund = statement.netRefund();
        Account refundBank = netRefund.signum() > 0
                ? requireRefundBank(request.refundBankAccountId())
                : null;
        boolean acknowledged = requireOutstandingAcknowledged(statement, request);

        UUID journalId = postSettlement(lease, statement, settlementDate, refundBank);
        // F14-37: the recharges' VAT is a tax point of its own with a tax invoice (TI).
        if (journalId != null && vatTaxPoints != null && statement.totalDeductionVat().signum() > 0) {
            BigDecimal taxable = statement.deductions().stream().filter(d -> d.vatAmount().signum() > 0)
                    .map(DeductionLineDTO::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            vatTaxPoints.recordSettlementVat(lease, settlementDate, journalId, money(taxable),
                    statement.totalDeductionVat());
        }
        UUID collectionChequeId = netRefund.signum() < 0
                ? collectBalanceDue(lease, netRefund.negate(), settlementDate)
                : null;

        snapshot(settlement, statement);
        settlement.setStatus(SettlementStatus.FINALIZED);
        settlement.setSettlementDate(settlementDate);
        settlement.setRefundBankAccountId(refundBank == null ? null : refundBank.getId());
        settlement.setJournalId(journalId);
        settlement.setCollectionChequeId(collectionChequeId);
        settlement.setSettledBy(settledBy);
        settlement.setSettledAt(LocalDateTime.now(clock));
        leaseSettlementRepository.save(settlement);

        // CLOSED only when nothing is left to collect, which is the register's
        // question and not this method's: a balance due has just been raised as a
        // CASH row and is outstanding by definition, and §9.1's keep list may have
        // left an instrument on the register that nobody has banked yet. Closing on
        // "netRefund >= 0" alone retired contracts of the second kind — a finalised
        // settlement with a 12,750 cheque still in the drawer — which then could not
        // be banked at all, because a CLOSED lease refuses every transition.
        // LeaseClosureService owns the rule; ChequeService asks it again each time a
        // row clears.
        if (acknowledged) {
            recordAcknowledgement(lease, statement);
        }

        closure.closeIfFullyCollected(lease, "the settlement was finalised");

        return buildSettlementResponse(leaseId);
    }

    /**
     * The one {@code STL}, as an n-line entry without pairs.
     *
     * <p>Pairs name one counter-account per line (Addendum A) and a settlement has
     * no such correspondence: the deposit is not released "against" any one
     * deduction. So the lines are flat and the ledger falls back to listing the
     * entry's other accounts as each row's Particular, which is the honest answer
     * here.</p>
     *
     * @return the journal's id, or null when the statement had nothing to post —
     *         no deposit, no deductions, a flat receivable. An entry for nothing is
     *         not a document, and {@code PostingService} would refuse it anyway.
     */
    private UUID postSettlement(Lease lease, SettlementStatementDTO statement, LocalDate date,
                                Account refundBank) {
        List<PostingRequest.Line> lines = new ArrayList<>();
        PostingRequest.Dimensions dims = LeaseChequeRegistrar.dimensions(lease, null);

        depositLedger.heldByAccount(lease).forEach((accountId, held) -> {
            if (held.signum() > 0) {
                lines.add(new PostingRequest.Line(new PostingRequest.ById(accountId),
                        PostingRequest.Side.DR, held, null, "Deposit released on settlement"));
            }
        });
        for (AdditionLineDTO addition : statement.additions()) {
            if (addition.amount().signum() > 0) {
                lines.add(new PostingRequest.Line(new PostingRequest.ById(addition.accountId()),
                        PostingRequest.Side.DR, addition.amount(), null, narration(addition)));
            }
        }
        for (DeductionLineDTO deduction : statement.deductions()) {
            if (deduction.amount().signum() > 0) {
                lines.add(new PostingRequest.Line(new PostingRequest.ById(deduction.accountId()),
                        PostingRequest.Side.CR, deduction.amount(), null, narration(deduction)));
            }
        }
        // F14-37: the output VAT on the taxable recharges, declared by this STL.
        if (statement.totalDeductionVat() != null && statement.totalDeductionVat().signum() > 0) {
            lines.add(PostingRequest.cr(AccountRole.OUTPUT_VAT, statement.totalDeductionVat())
                    .withNarration("Output VAT on recharges deducted at settlement"));
        }

        // Where the settlement leaves the receivable, as a signed debit-positive
        // movement. It clears to zero when the landlord pays out; when the renter
        // still owes, the shortfall stays on the receivable and the collection row
        // is raised against it.
        BigDecimal netRefund = statement.netRefund();
        BigDecimal receivableMovement = statement.receivableBalance().negate()
                .subtract(netRefund.signum() < 0 ? netRefund : BigDecimal.ZERO);
        if (receivableMovement.signum() > 0) {
            lines.add(LeaseChequeRegistrar.drReceivable(lease, receivableMovement)
                    .withNarration("Balance settled against deposit"));
        } else if (receivableMovement.signum() < 0) {
            lines.add(LeaseChequeRegistrar.crReceivable(lease, receivableMovement.negate())
                    .withNarration("Balance settled against deposit"));
        }

        if (netRefund.signum() > 0) {
            lines.add(new PostingRequest.Line(new PostingRequest.ById(refundBank.getId()),
                    PostingRequest.Side.CR, netRefund, null, "Deposit refund"));
        }

        if (lines.isEmpty()) {
            return null;
        }
        JournalEntry stl = postingService.post(new PostingRequest(
                JournalDocType.STL,
                date,
                "Settlement of lease",
                dims,
                JournalSourceType.SETTLEMENT,
                lease.getId(),
                null,
                List.copyOf(lines)));
        return stl.getId();
    }

    /**
     * The renter owes money the deposit could not cover, so the register gets a
     * row for it.
     *
     * <p>Through the settlement-only door on {@code ChequeService}: the user-facing
     * {@code addRowToPostedLease} refuses a TERMINATED lease on purpose — nobody
     * should be able to add instalments to a contract that has ended — and this is
     * the one case where a row legitimately arrives afterwards. The row's own
     * {@code PDR} is the ordinary one, so the balance moves off the receivable and
     * onto PDC receivable exactly as any other instrument's would.</p>
     */
    private UUID collectBalanceDue(Lease lease, BigDecimal amount, LocalDate date) {
        return chequeService.addCollectionRow(lease.getId(), new ChequeRowInput(
                null, null, date, null, date, null, null, null,
                money(amount), "Settlement balance due", ChequeMode.CASH)).id();
    }

    // ------------------------------------------------------------------
    // reading the stored row
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Optional<LeaseSettlement> getSettlement(UUID leaseId) {
        return leaseSettlementRepository.findByLeaseId(leaseId);
    }

    @Transactional(readOnly = true)
    public List<LeaseSettlementDeduction> getSettlementDeductions(UUID settlementId) {
        return leaseSettlementDeductionRepository.findBySettlementIdOrderByCreatedAtAsc(settlementId);
    }

    @Transactional(readOnly = true)
    public SettlementResponseDTO buildSettlementResponse(UUID leaseId) {
        leaseAccessPolicy.requireReadable(leaseRepository.findById(leaseId).orElse(null));
        Lease lease = findLeaseWithTenantCheck(leaseId);
        LeaseSettlement settlement = leaseSettlementRepository.findByLeaseId(leaseId)
                .orElseThrow(() -> new NotFoundException("Settlement not found"));
        UUID propertyId = propertyIdOf(lease);

        SettlementResponseDTO response = new SettlementResponseDTO();
        response.setId(settlement.getId());
        response.setLeaseId(settlement.getLeaseId());
        response.setDepositAmount(settlement.getDepositAmount());
        response.setTotalDeductions(settlement.getTotalDeductions());
        response.setTotalAdditions(settlement.getTotalAdditions());
        response.setRefundAmount(settlement.getRefundAmount());
        response.setNotes(settlement.getNotes());
        response.setStatus(settlement.getStatus().name());
        response.setSettledBy(settlement.getSettledBy());
        // Filled rather than left blank: the field has existed since v1 and the
        // settlement screen renders it, so an id with no name beside it is a gap
        // the reader has to go and look up.
        //
        // findDisplayNameById, not the tenant-filtered findById: a SUPER_ADMIN
        // acting inside a pivoted tenant has tenant_id = NULL, so the filtered
        // lookup can't see them and the name degraded to the bare UUID on the
        // finalized statement — a legal move-out document that gets shown to a
        // departing tenant and may be produced in a rental dispute.
        response.setSettledByName(settlement.getSettledBy() == null ? null
                : userRepository.findDisplayNameById(settlement.getSettledBy()).orElse(null));
        response.setSettledAt(settlement.getSettledAt());
        response.setCreatedAt(settlement.getCreatedAt());

        response.setSettlementDate(settlement.getSettlementDate());
        response.setEarnedRent(settlement.getEarnedRent());
        response.setReceivedTotal(settlement.getReceivedTotal());
        response.setReceivableBalance(settlement.getReceivableBalance());
        response.setDepositsHeld(settlement.getDepositsHeld());
        response.setPenaltiesOutstanding(settlement.getPenaltiesOutstanding());
        response.setBalanceDue(settlement.getBalanceDue());
        response.setRefundBankAccountId(settlement.getRefundBankAccountId());
        response.setJournalId(settlement.getJournalId());
        response.setJournalNumber(settlement.getJournalId() == null ? null
                : journalEntryRepository.findById(settlement.getJournalId())
                        .map(JournalEntry::getEntryNumber).orElse(null));
        response.setCollectionChequeId(settlement.getCollectionChequeId());

        response.setDeductions(storedLines(leaseId).stream().map(d -> {
            SettlementResponseDTO.DeductionDTO dto = new SettlementResponseDTO.DeductionDTO();
            dto.setId(d.getId());
            dto.setCategory(d.getCategory() != null ? d.getCategory().name() : null);
            dto.setDescription(d.getDescription());
            dto.setAmount(d.getAmount());
            dto.setAutoCalculated(d.isAutoCalculated());
            dto.setType(d.getType().name());
            dto.setAdditionCategory(d.getAdditionCategory() != null ? d.getAdditionCategory().name() : null);
            Account account = accountOfLine(d, propertyId);
            dto.setAccountId(account == null ? null : account.getId());
            dto.setAccountName(account == null ? null : account.getName());
            dto.setAttachments(deductionAttachmentService.getAttachments(d.getId()));
            return dto;
        }).collect(Collectors.toList()));
        return response;
    }

    // ------------------------------------------------------------------
    // guards
    // ------------------------------------------------------------------

    private void requireSettleable(Lease lease) {
        // Finalising used to terminate the lease as a side effect. It no longer
        // does — termination is its own act, with its own effective date, its own
        // decision about every uncleared instrument and its own journals
        // (spec §9.1) — and the statement this finalises is drawn from the
        // receivable that termination leaves behind (§9.2). So the order is a
        // precondition, not a convention: without it the reachable state is a
        // FINALIZED settlement with the deposit deemed released, on a contract
        // that is still running, whose unit is still occupied, whose uncleared
        // cheques are still on the register and whose rent is still being
        // recognised every night.
        if (!SETTLEABLE.contains(lease.getStatus())) {
            throw new BusinessRuleViolationException(
                    "Terminate the lease before settling it; this one is " + lease.getStatus() + ".");
        }
    }

    /**
     * The {@code STL}'s date has to be a date the books will accept and a date the
     * tenancy had actually ended on.
     *
     * <p>The period lock is checked here rather than left to {@code PostingService}
     * so the refusal arrives before the collection row is raised and names the date
     * finance has to move.</p>
     */
    private void requireUsableDate(Lease lease, LocalDate settlementDate) {
        LocalDate terminatedOn = lease.getTerminatedOn();
        if (terminatedOn != null) {
            if (settlementDate.isBefore(terminatedOn)) {
                throw new BusinessRuleViolationException("The settlement date " + settlementDate
                        + " is before the lease was terminated (" + terminatedOn + ").");
            }
        } else if (lease.getEndDate() != null && settlementDate.isBefore(lease.getEndDate())) {
            // An EXPIRED lease has no terminatedOn — it was never cut short — so the
            // floor is the day its term actually ran out. Without this the only guard
            // on the date is the period lock, and a natural expiry could be settled
            // months before the tenant moved out.
            throw new BusinessRuleViolationException("The settlement date " + settlementDate
                    + " is before the lease ended (" + lease.getEndDate() + ").");
        }
        LocalDate locked = booksLockedThrough();
        if (locked != null && !settlementDate.isAfter(locked)) {
            throw new BusinessRuleViolationException("Cannot settle on " + settlementDate
                    + ": books are locked through " + locked + ".");
        }
    }

    /**
     * Paying a refund out while the register is still holding paper is a decision,
     * not a default.
     *
     * <p>The two figures are independent: {@code netRefund} is drawn from the
     * receivable and the deposit, and {@code instrumentsOutstanding} sits in PDC
     * receivable, which neither of them touches. So a statement can honestly offer
     * a healthy refund on a tenancy whose kept cheque or unpaid fine is still
     * outstanding — and handing the deposit back in that state is exactly what a
     * landlord would want to have chosen on purpose. The alternative, netting it
     * off silently, would settle a debt the renter is still expected to pay and
     * leave an instrument on the register with nothing behind it.</p>
     *
     * <p>Only a refund asks: a balance-due settlement is already collecting, and a
     * zero one hands over nothing.</p>
     *
     * @return whether an acknowledgement was actually needed and given — which is
     *         what gets written to the lease's trail.
     */
    private static boolean requireOutstandingAcknowledged(SettlementStatementDTO statement,
                                                          FinalizeSettlementRequest request) {
        if (statement.netRefund().signum() <= 0 || statement.instrumentsOutstanding().signum() <= 0) {
            return false;
        }
        if (!request.acknowledged()) {
            throw new BusinessRuleViolationException("AED " + formatMoney(statement.instrumentsOutstanding())
                    + " is still outstanding on the cheque register;"
                    + " acknowledge it to refund the deposit anyway");
        }
        return true;
    }

    /**
     * The acknowledged figure, on the lease's own trail.
     *
     * <p>Changeset 85's settlement columns are all spoken for and this task adds no
     * schema, so the record of "a refund was paid out over AED X of outstanding
     * paper, by this user, on this date" goes where the rest of a lease's history
     * already lives. {@code LeaseClosureService} writes its closure the same way.</p>
     */
    private void recordAcknowledgement(Lease lease, SettlementStatementDTO statement) {
        LeaseEvent event = new LeaseEvent();
        event.setLease(lease);
        event.setTenantId(lease.getTenantId());
        event.setPreviousState(lease.getStatus());
        event.setNewState(lease.getStatus());
        event.setNotes("Settlement finalised with AED " + formatMoney(statement.instrumentsOutstanding())
                + " still outstanding on the cheque register, acknowledged;"
                + " refund of AED " + formatMoney(statement.netRefund()) + " paid out");
        event.setCreatedAt(Instant.now());
        leaseEventRepository.save(event);
    }

    private LocalDate booksLockedThrough() {
        UUID tenantId = TenantContextHolder.getTenantId();
        return tenantId == null ? null : fiscalSettings.findById(tenantId)
                .map(TenantFiscalSettings::getBooksLockedThrough).orElse(null);
    }

    /** Category rules and the account override, for one line being saved. */
    private void validateLine(SaveSettlementDTO.DeductionItemDTO item, UUID propertyId) {
        LineItemType type = item.getType() != null ? item.getType() : LineItemType.DEDUCTION;
        if (type == LineItemType.ADDITION) {
            AdditionCategory category = additionCategoryOf(item);
            if (category == null) {
                throw new BusinessRuleViolationException("An addition needs an additionCategory");
            }
            requireAllowed(category);
            requireUsableAccount(item.getAccountId(), type);
            if (item.getAccountId() == null) {
                // Resolved now so a missing mapping is a refusal on Save, not a
                // surprise at Finalise when the accountant has already committed.
                accountResolver.resolve(ADDITION_ROLES.get(category), propertyId);
            }
            return;
        }
        if (item.getCategory() == null) {
            throw new BusinessRuleViolationException("A deduction needs a category");
        }
        requireAllowed(item.getCategory());
        requireUsableAccount(item.getAccountId(), type);
        if (item.getAccountId() == null && item.getCategory() == DeductionCategory.UTILITY_ARREARS) {
            if (utilitiesLeaf(propertyId) == null) {
                throw new BusinessRuleViolationException("Utility arrears are passed through to the property's"
                        + " Utilities expense account, and this property has none. Generate its missing accounts"
                        + " (Property → Ledger accounts) or choose the account on the line.");
            }
        } else if (item.getAccountId() == null) {
            accountResolver.resolve(DEDUCTION_ROLES.get(item.getCategory()), propertyId);
        }
    }

    /** The same two rules, asked of a row that is already stored. */
    private void requirePostableCategory(LeaseSettlementDeduction line) {
        if (line.getType() == LineItemType.ADDITION) {
            if (line.getAdditionCategory() == null) {
                throw new BusinessRuleViolationException("An addition needs an additionCategory");
            }
            requireAllowed(line.getAdditionCategory());
        } else {
            if (line.getCategory() == null) {
                throw new BusinessRuleViolationException("A deduction needs a category");
            }
            requireAllowed(line.getCategory());
        }
    }

    private void requireUsableLineAccount(LeaseSettlementDeduction line, UUID propertyId) {
        requireUsableAccount(line.getAccountId(), line.getType());
        if (line.getAccountId() == null && line.getType() != LineItemType.ADDITION
                && line.getCategory() == DeductionCategory.UTILITY_ARREARS) {
            if (utilitiesLeaf(propertyId) == null) {
                throw new BusinessRuleViolationException("Utility arrears are passed through to the property's"
                        + " Utilities expense account, and this property has none. Generate its missing accounts"
                        + " (Property → Ledger accounts) or choose the account on the line.");
            }
        } else if (line.getAccountId() == null) {
            accountResolver.resolve(roleOf(line), propertyId);
        }
    }

    private static void requireAllowed(DeductionCategory category) {
        if (category == DeductionCategory.PENALTIES) {
            throw new BusinessRuleViolationException(
                    "Approved penalties are collected through their own register row;"
                            + " add a post-termination charge as EARLY_TERMINATION_FEE or OTHER");
        }
        if (category == DeductionCategory.UNPAID_RENT) {
            throw new BusinessRuleViolationException(
                    "Unpaid rent is already accounted for: what the termination handed back is in the"
                            + " receivable balance, and what was kept for collection is on the cheque register."
                            + " It cannot also be a deduction.");
        }
        if (!DEDUCTION_ROLES.containsKey(category)) {
            throw new BusinessRuleViolationException(category + " is not a settlement deduction.");
        }
    }

    private static void requireAllowed(AdditionCategory category) {
        if (category == AdditionCategory.PREPAID_RENT || category == AdditionCategory.UTILITY_OVERPAYMENT) {
            // These two really are receivable credits — rent paid ahead and a
            // utility overpayment are money the renter has handed over against the
            // contract, and the statement already subtracts the receivable.
            throw new BusinessRuleViolationException(
                    category + " is already a credit on the receivable balance, so it cannot also be an addition.");
        }
        if (!ADDITION_ROLES.containsKey(category)) {
            throw new BusinessRuleViolationException(category + " is not a settlement addition.");
        }
    }

    /**
     * An account override has to be one this tenant may actually post to.
     *
     * <p>A deduction credits income; an addition debits it, and an expense leaf is
     * the other honest home for "the landlord paid the renter something", so both
     * are admitted there. Everything else — a bank, the receivable, a deposit — is
     * refused: those are the accounts the settlement's <em>own</em> lines move, and
     * a deduction pointed at one would net itself out silently.</p>
     */
    private void requireUsableAccount(UUID accountId, LineItemType type) {
        if (accountId == null) {
            return;
        }
        Account account = accountRepository.findById(accountId).orElse(null);
        UUID tenantId = TenantContextHolder.getTenantId();
        if (account == null || (tenantId != null && !tenantId.equals(account.getTenantId()))) {
            throw new NotFoundException("Account not found: " + accountId);
        }
        if (account.isGroup()) {
            throw new BusinessRuleViolationException(
                    "Account " + account.getCode() + " is a group account; name one of its leaves.");
        }
        if (!account.isActive()) {
            throw new BusinessRuleViolationException("Account " + account.getCode() + " is inactive.");
        }
        Set<AccountType> allowed = type == LineItemType.ADDITION
                ? EnumSet.of(AccountType.INCOME, AccountType.EXPENSE)
                : EnumSet.of(AccountType.INCOME);
        if (!allowed.contains(account.getAccountType())) {
            throw new BusinessRuleViolationException("Account " + account.getCode() + " is "
                    + account.getAccountType() + "; a settlement "
                    + (type == LineItemType.ADDITION ? "addition needs an INCOME or EXPENSE account."
                            : "deduction needs an INCOME account."));
        }
    }

    /** The bank the refund is paid from: an active asset leaf of this tenant. */
    private Account requireRefundBank(UUID accountId) {
        if (accountId == null) {
            throw new BusinessRuleViolationException(
                    "This settlement refunds the renter, so it needs a bank account to pay from.");
        }
        Account account = accountRepository.findById(accountId).orElse(null);
        UUID tenantId = TenantContextHolder.getTenantId();
        if (account == null || (tenantId != null && !tenantId.equals(account.getTenantId()))) {
            throw new NotFoundException("Account not found: " + accountId);
        }
        if (account.isGroup() || !account.isActive() || account.getAccountType() != AccountType.ASSET) {
            throw new BusinessRuleViolationException(
                    "Account " + account.getCode() + " cannot pay a refund; name an active asset leaf.");
        }
        return account;
    }

    // ------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------

    private List<LeaseSettlementDeduction> storedLines(UUID leaseId) {
        return leaseSettlementRepository.findByLeaseId(leaseId)
                .map(s -> leaseSettlementDeductionRepository.findBySettlementIdOrderByCreatedAtAsc(s.getId()))
                .orElseGet(List::of);
    }

    /**
     * Oldest instrument first. By the date on the paper, then by the row's position
     * on the register — two cheques written for the same day are still the third and
     * the fourth instalment, and the register's own numbering is what the clerk
     * reads them off.
     */
    private static final Comparator<OutstandingInstrumentDTO> OUTSTANDING_ORDER =
            Comparator.comparing(OutstandingInstrumentDTO::chequeDate,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparingInt(OutstandingInstrumentDTO::seqNo);

    private static OutstandingInstrumentDTO outstanding(Cheque cheque) {
        return new OutstandingInstrumentDTO(
                cheque.getId(), cheque.getSeqNo(), cheque.getMode(), cheque.getChequeNumber(),
                cheque.getChequeDate(), money(cheque.getAmount()), cheque.getStatus(),
                cheque.getPenaltyAssessmentId() != null);
    }

    /** F14-37: whether the lease charges VAT — any line with VAT on it. */
    private boolean chargesVat(Lease lease) {
        if (leaseLines == null || lease == null) return false;
        return leaseLines.findByLease_IdOrderBySeqNoAsc(lease.getId()).stream()
                .anyMatch(l -> com.datagami.rentaxis.core.service.lease.LeaseVat.vatOf(l).signum() > 0);
    }

    /**
     * F14-37: utility arrears recovered at cost are a pass-through, not income: they
     * credit the property's Utilities expense leaf (report line EXP_UTILITIES, which
     * "Generate missing accounts" creates), else a tenant-wide one.
     */
    private Account utilitiesLeaf(UUID propertyId) {
        if (jdbc == null) return null;
        List<UUID> ids = jdbc.queryForList("""
                select a.id from accounts a
                where a.tenant_id = :t and a.is_active and not a.is_group and a.report_line = 'EXP_UTILITIES'
                  and (a.property_id = :p or a.property_id is null)
                order by (a.property_id is null), a.code limit 1""",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("t",
                        com.datagami.rentaxis.core.tenant.TenantContextHolder.getTenantId()).addValue("p", propertyId),
                UUID.class);
        return ids.isEmpty() ? null : accountRepository.findById(ids.get(0)).orElse(null);
    }

    /** The leaf a line posts to: its own override, else the leaf its category resolves to. */
    private Account accountOfLine(LeaseSettlementDeduction line, UUID propertyId) {
        if (line.getAccountId() != null) {
            return accountRepository.findById(line.getAccountId()).orElse(null);
        }
        if (line.getType() != LineItemType.ADDITION && line.getCategory() == DeductionCategory.UTILITY_ARREARS) {
            return utilitiesLeaf(propertyId);
        }
        AccountRole role = roleOf(line);
        // resolveOrNull, never resolve-inside-a-try: an unmapped role thrown out of
        // the resolver marks this transaction rollback-only before any catch runs.
        // A statement is allowed to render a line whose account cannot be resolved;
        // finalise is what refuses it.
        return role == null ? null : accountResolver.resolveOrNull(role, propertyId);
    }

    private static AccountRole roleOf(LeaseSettlementDeduction line) {
        return line.getType() == LineItemType.ADDITION
                ? ADDITION_ROLES.get(line.getAdditionCategory())
                : DEDUCTION_ROLES.get(line.getCategory());
    }

    private static AdditionCategory additionCategoryOf(SaveSettlementDTO.DeductionItemDTO item) {
        if (item.getAdditionCategory() == null) {
            return null;
        }
        try {
            return AdditionCategory.valueOf(item.getAdditionCategory());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleViolationException("Invalid additionCategory: " + item.getAdditionCategory());
        }
    }

    /** Copy the computed statement onto the row's snapshot columns. */
    private static void snapshot(LeaseSettlement settlement, SettlementStatementDTO statement) {
        settlement.setEarnedRent(statement.earnedRent());
        settlement.setReceivedTotal(statement.receivedTotal());
        settlement.setReceivableBalance(statement.receivableBalance());
        settlement.setDepositsHeld(statement.depositsHeld());
        settlement.setDepositAmount(statement.depositsHeld());
        settlement.setPenaltiesOutstanding(statement.penaltiesOutstanding());
        settlement.setTotalDeductions(statement.totalDeductions());
        settlement.setTotalAdditions(statement.totalAdditions());
        BigDecimal net = statement.netRefund();
        settlement.setRefundAmount(net.signum() > 0 ? net : BigDecimal.ZERO);
        settlement.setBalanceDue(net.signum() < 0 ? net.negate() : BigDecimal.ZERO);
    }

    private static String narration(DeductionLineDTO line) {
        return line.description() != null && !line.description().isBlank()
                ? line.description() : String.valueOf(line.category());
    }

    private static String narration(AdditionLineDTO line) {
        return line.description() != null && !line.description().isBlank()
                ? line.description() : String.valueOf(line.category());
    }

    /** The lease's own receivable leaf when it overrides the property's (spec §6.3). */
    private UUID receivableAccountOf(Lease lease) {
        return lease.getReceivableAccountId() != null
                ? lease.getReceivableAccountId()
                : accountResolver.resolve(AccountRole.RENT_RECEIVABLE, propertyIdOf(lease)).getId();
    }

    private static UUID propertyIdOf(Lease lease) {
        return lease.getUnit() != null && lease.getUnit().getProperty() != null
                ? lease.getUnit().getProperty().getId() : null;
    }

    /**
     * The same figure as {@link #money}, grouped for a sentence a human reads —
     * "AED 13,250.00". The register's own refusals format money this way.
     */
    private static String formatMoney(BigDecimal value) {
        return String.format(Locale.ROOT, "%,.2f", money(value));
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    /** The lease row, claimed {@code FOR UPDATE} and tenant-checked. */
    private Lease lockLease(UUID leaseId) {
        Lease lease;
        try {
            lease = leaseRepository.findByIdForUpdate(leaseId)
                    .orElseThrow(() -> new NotFoundException("Lease not found"));
        } catch (PessimisticLockingFailureException e) {
            throw new BusinessRuleViolationException(
                    "This lease is being updated by another request. Please try again.");
        }
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "No tenant in context; a settlement cannot be read or written without one");
        }
        if (!tenantId.equals(lease.getTenantId())) {
            throw new NotFoundException("Lease not found");
        }
        return lease;
    }

    /**
     * The lease, scoped to the caller's tenant.
     *
     * <p><b>A missing tenant is refused, not tolerated.</b> Every figure the
     * statement is built from — the deposit, the receivable, the schedule — is
     * JPQL that relies on the Hibernate tenant filter, and {@code TenantAspect}
     * only enables that filter when a tenant is set. Without one, a statement would
     * either cross tenants or fail deep inside the arithmetic with an error about a
     * deposit balance, which tells the caller nothing about what is actually
     * wrong. Refused here, once, in the caller's own terms.</p>
     */
    private Lease findLeaseWithTenantCheck(UUID leaseId) {
        UUID currentTenantId = TenantContextHolder.getTenantId();
        if (currentTenantId == null) {
            throw new IllegalStateException(
                    "No tenant in context; a settlement cannot be read or written without one");
        }
        Lease lease = leaseRepository.findById(leaseId)
                .orElseThrow(() -> new NotFoundException("Lease not found"));
        if (!currentTenantId.equals(lease.getTenantId())) {
            throw new NotFoundException("Lease not found");
        }
        return lease;
    }
}
