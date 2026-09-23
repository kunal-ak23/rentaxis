package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.lease.AddChargeRequest;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.lease.ExtendLeaseRequest;
import com.datagami.rentaxis.api.dto.lease.LeaseLineDTO;
import com.datagami.rentaxis.api.dto.lease.PostLeaseDryRunResponse;
import com.datagami.rentaxis.api.dto.lease.PostLeaseResponse;
import com.datagami.rentaxis.api.dto.lease.RenewLeaseRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService.LedgerFilter;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.JournalLine;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseEvent;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.RenewalOpportunity;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.JournalStatus;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.RenewalOutcome;
import com.datagami.rentaxis.domain.entity.enums.RenewalStage;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseEventRepository;
import com.datagami.rentaxis.domain.repository.LeaseLineRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.RenewalOpportunityRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.chequeRow;
import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.linePeriod;
import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.vatLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Renewing a lease and extending one, against a real database.
 *
 * <p>The two are tested together because their whole point is that they are
 * different: a renewal produces a second contract whose post retires the first,
 * an extension produces a second {@code TCO} on the same contract and reverses
 * nothing. Most of the assertions here are about what is in the ledger afterwards
 * and about all-or-nothing behaviour, neither of which a mock can exhibit.</p>
 *
 * <p><b>Transactions.</b> {@code TenantAspect} only enables the Hibernate tenant
 * filter inside one, so every read-back goes through {@link #tx}.</p>
 */
@SpringBootTest
class LeaseRenewalServiceIT extends AbstractPostgresIT {

    @Autowired LeaseRenewalService renewal;
    @Autowired LeasePostingService posting;
    @Autowired LeaseVariationService variations;
    @Autowired ChequeGenerationService cheques;
    @Autowired LeaseService leaseService;
    @Autowired LedgerQueryService ledger;
    @Autowired PostingService postingService;
    @Autowired AccountResolver resolver;
    @Autowired AccountService accountService;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired LeaseRepository leaseRepo;
    @Autowired LeaseLineRepository lineRepo;
    @Autowired ChequeRepository chequeRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired JournalEntryRepository entries;
    @Autowired JournalLineRepository journalLines;
    @Autowired LeaseEventRepository leaseEvents;
    @Autowired RenewalOpportunityRepository opportunities;
    @Autowired DepositCarryForward carryForward;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private LeaseTestFixtures fixtures;

    /** Year one: contract dated before the tenancy starts, as PACT's are. */
    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 9, 16);
    private static final LocalDate START = LocalDate.of(2026, 10, 2);
    private static final LocalDate END = LocalDate.of(2027, 10, 1);

    /** Year two. */
    private static final LocalDate RENEWAL_CONTRACT_DATE = LocalDate.of(2027, 9, 16);
    private static final LocalDate RENEWAL_START = LocalDate.of(2027, 10, 2);
    private static final LocalDate RENEWAL_END = LocalDate.of(2028, 10, 1);

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, cheques, posting);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /** Year one on the books: 51,000 of rent in four cheques plus a 3,000 deposit. */
    private UUID postedWithDeposit() {
        return fixtures.postedLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000"), line("SECURITY_DEPOSIT", "3000")), 4, null)
                .lease().getId();
    }

    /** Year one on the books: 51,000 of rent plus a 2,000 admin fee, no deposit. */
    private UUID postedWithFee() {
        return fixtures.postedLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000"), line("ADMIN_FEE", "2000")), 4, null)
                .lease().getId();
    }

    private RenewLeaseRequest renewRequest(boolean carryDeposit) {
        return new RenewLeaseRequest(RENEWAL_CONTRACT_DATE, RENEWAL_START, RENEWAL_END, null, carryDeposit);
    }

    private Account leaf(AccountRole role) {
        return tx.execute(s -> resolver.resolve(role, fixtures.property().getId()));
    }

    private Lease reread(UUID leaseId) {
        return tx.execute(s -> leaseRepo.findById(leaseId).orElseThrow());
    }

    private List<JournalLine> linesOf(UUID entryId) {
        return tx.execute(s -> journalLines.findByEntry_IdOrderByLineNoAsc(entryId));
    }

    private List<Cheque> registerOf(UUID leaseId) {
        return tx.execute(s -> chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId));
    }

    private List<LeaseLineDTO> leaseLines(UUID leaseId) {
        return tx.execute(s -> leaseService.getLines(leaseId));
    }

    private BigDecimal balanceOf(Account account, UUID leaseId) {
        return tx.execute(s -> ledger.accountLedger(account.getId(),
                new LedgerFilter(null, null, null, null, leaseId, null)).closingBalance());
    }

    private long journalEntryRows() {
        return jdbc.queryForObject("select count(*) from journal_entries where tenant_id = ?",
                Long.class, fixtures.tenantId());
    }

    /** Cut the grid for a draft successor and post it; returns its id. */
    private UUID postAndReturn(UUID draftId, LocalDate firstDueDate) {
        fixtures.generateGrid(draftId, 4, firstDueDate);
        posting.post(draftId);
        return draftId;
    }

    /** The carry-forward JVs written so far, oldest first. */
    private List<JournalEntry> carryForwardJournals() {
        return tx.execute(s -> entries.findAll().stream()
                .filter(e -> e.getDocType() == JournalDocType.JV
                        && e.getNarration() != null
                        && e.getNarration().startsWith("Security deposit carried forward"))
                .sorted(java.util.Comparator.comparing(JournalEntry::getEntryNumber))
                .toList());
    }

    private void setStatus(UUID leaseId, LeaseStatus status) {
        tx.executeWithoutResult(s -> {
            Lease lease = leaseRepo.findById(leaseId).orElseThrow();
            lease.setStatus(status);
            leaseRepo.save(lease);
        });
    }

    /** Frees the unit the way an expiry sweep would, so another lease can be drafted on it. */
    private void setUnitVacant() {
        tx.executeWithoutResult(s -> {
            Unit unit = unitRepo.findById(fixtures.unit().getId()).orElseThrow();
            unit.setStatus(UnitStatus.VACANT);
            unit.setCurrentTenantName(null);
            unitRepo.save(unit);
        });
    }

    private List<LeaseEvent> eventsOf(UUID leaseId) {
        return tx.execute(s -> leaseEvents.findByLeaseIdOrderByCreatedAtDesc(leaseId));
    }

    private UUID openOpportunityFor(UUID leaseId) {
        return tx.execute(s -> {
            RenewalOpportunity o = new RenewalOpportunity();
            o.setLease(leaseRepo.findById(leaseId).orElseThrow());
            o.setTenantId(fixtures.tenantId());
            o.setStage(RenewalStage.OPEN);
            o.setOpenedAt(java.time.Instant.now());
            return opportunities.save(o).getId();
        });
    }

    private RenewalOpportunity opportunity(UUID id) {
        return tx.execute(s -> opportunities.findById(id).orElseThrow());
    }

    /** Every TCO raised against this lease, oldest first. */
    private List<JournalEntry> tcosOf(UUID leaseId) {
        return tx.execute(s -> entries.findAll().stream()
                .filter(e -> e.getDocType() == JournalDocType.TCO
                        && e.getSourceType() == JournalSourceType.LEASE
                        && leaseId.equals(e.getSourceId()))
                .sorted(java.util.Comparator.comparing(JournalEntry::getEntryNumber))
                .toList());
    }

    // ------------------------------------------------------------------
    // renew
    // ------------------------------------------------------------------

    /**
     * The successor is a DRAFT carrying last year's charges and this year's dates,
     * joined to the chain and pointing back at the lease it replaces.
     */
    @Test
    void renewCopiesLinesIntoDraftWithChain() {
        UUID firstId = postedWithFee();
        Lease first = reread(firstId);

        LeaseDTO successor = renewal.renew(firstId, renewRequest(false));

        assertThat(successor.getStatus()).isEqualTo(LeaseStatus.DRAFT);
        assertThat(successor.getRenewedFromLeaseId()).isEqualTo(firstId);
        assertThat(successor.getChainId()).isEqualTo(first.getChainId());
        assertThat(successor.getChainId()).isEqualTo(firstId);
        assertThat(successor.getUnitId()).isEqualTo(fixtures.unit().getId());
        assertThat(successor.getRenterId()).isEqualTo(fixtures.renter().getId());
        assertThat(successor.getStartDate()).isEqualTo(RENEWAL_START);
        assertThat(successor.getEndDate()).isEqualTo(RENEWAL_END);
        assertThat(successor.getContractDate()).isEqualTo(RENEWAL_CONTRACT_DATE);

        // Both charges copied, at the same money, crediting the same leaves.
        List<LeaseLineDTO> copied = leaseLines(successor.getId());
        assertThat(copied).extracting(LeaseLineDTO::chargeTypeCode).containsExactly("RENT", "ADMIN_FEE");
        assertThat(copied.get(0).netAmount()).isEqualByComparingTo("51000");
        assertThat(copied.get(1).netAmount()).isEqualByComparingTo("2000");
        assertThat(copied.get(0).creditAccountId()).isEqualTo(leaf(AccountRole.ADVANCE_RENT).getId());
        assertThat(copied.get(1).creditAccountId()).isEqualTo(leaf(AccountRole.ADMIN_FEE).getId());

        // The rent line covers the NEW term. Carrying last year's period over is
        // how per-day recognition would have charged year one twice.
        assertThat(copied.get(0).periodStart()).isEqualTo(RENEWAL_START);
        assertThat(copied.get(0).periodEnd()).isEqualTo(RENEWAL_END);
        assertThat(copied.get(1).periodStart()).isNull();

        // Nothing has happened to the predecessor, and no journal was written.
        assertThat(reread(firstId).getStatus()).isEqualTo(LeaseStatus.ACTIVE);
        assertThat(tcosOf(successor.getId())).isEmpty();
    }

    /**
     * Posting the successor retires the predecessor. The unit is never vacated in
     * between: the renter has not moved out, and a moment of VACANT is a moment the
     * unit is lettable to somebody else (spec §6.6).
     */
    @Test
    void postingSuccessorMarksPredecessorRenewedAndKeepsUnitOccupied() {
        UUID firstId = postedWithFee();
        LeaseDTO successor = renewal.renew(firstId, renewRequest(false));
        fixtures.generateGrid(successor.getId(), 4, RENEWAL_START);

        PostLeaseResponse posted = posting.post(successor.getId());

        assertThat(reread(firstId).getStatus()).isEqualTo(LeaseStatus.RENEWED);
        assertThat(reread(successor.getId()).getStatus()).isEqualTo(LeaseStatus.ACTIVE);
        Unit unit = tx.execute(s -> unitRepo.findById(fixtures.unit().getId()).orElseThrow());
        assertThat(unit.getStatus()).isEqualTo(UnitStatus.OCCUPIED);
        assertThat(unit.getCurrentTenantName()).isEqualTo(fixtures.renter().getNameEn());

        // The successor has its own contract journal, and the predecessor's is
        // untouched — a renewal supersedes, it does not reverse.
        assertThat(posted.tcoJournalId()).isNotNull();
        assertThat(tx.execute(s -> entries.findById(reread(firstId).getPostingJournalId()).orElseThrow())
                .getStatus()).isEqualTo(JournalStatus.POSTED);
    }

    /** One renewal per lease: a second is refused before any draft exists. */
    @Test
    void renewTwiceIsRefused() {
        UUID firstId = postedWithFee();
        LeaseDTO successor = renewal.renew(firstId, renewRequest(false));

        assertThatThrownBy(() -> renewal.renew(firstId, renewRequest(false)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("has already been renewed by lease " + successor.getId());

        // And exactly one successor exists, not two competing for the unit.
        List<Lease> successors = tx.execute(s -> leaseRepo.findByRenewedFromLeaseId(firstId));
        assertThat(successors).hasSize(1);
    }

    /** A lease that never went on the books has nothing to renew. */
    @Test
    void aDraftLeaseCannotBeRenewed() {
        UUID draftId = fixtures.draftLease(CONTRACT_DATE, START, END, List.of(line("RENT", "51000")));

        assertThatThrownBy(() -> renewal.renew(draftId, renewRequest(false)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Only an ACTIVE, EXPIRED or NOTICE_GIVEN lease can be renewed; this one is DRAFT");
    }

    // ------------------------------------------------------------------
    // carrying the deposit forward
    // ------------------------------------------------------------------

    /**
     * The renter's deposit follows them into the new contract: no second deposit is
     * charged, and one {@code JV} moves the liability from the old lease's dimension
     * to the new one's.
     */
    @Test
    void carryDepositForwardPostsJvBetweenLeaseDims() {
        UUID firstId = postedWithDeposit();
        Account deposit = leaf(AccountRole.SECURITY_DEPOSIT);
        assertThat(balanceOf(deposit, firstId)).isEqualByComparingTo("-3000");

        LeaseDTO successor = renewal.renew(firstId, renewRequest(true));

        // No deposit line was copied: charging one and carrying the old one across
        // would collect the same deposit twice.
        assertThat(leaseLines(successor.getId())).extracting(LeaseLineDTO::chargeTypeCode)
                .containsExactly("RENT");
        assertThat(reread(successor.getId()).getDepositAmount()).isEqualByComparingTo("0");
        assertThat(reread(successor.getId()).isCarryDepositForward()).isTrue();

        // The grid collects rent only — 51,000, not 54,000.
        List<ChequeDTO> grid = fixtures.generateGrid(successor.getId(), 4, RENEWAL_START);
        assertThat(grid.stream().map(ChequeDTO::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("51000");

        // The review step says what is about to move, before it moves.
        PostLeaseDryRunResponse dry = posting.dryRun(successor.getId());
        assertThat(dry.ok()).isTrue();
        assertThat(dry.depositCarriedForward()).isEqualByComparingTo("3000");

        posting.post(successor.getId());

        JournalEntry jv = tx.execute(s -> entries.findAll().stream()
                .filter(e -> e.getDocType() == JournalDocType.JV)
                .findFirst().orElseThrow());
        assertThat(jv.getEntryDate()).isEqualTo(RENEWAL_CONTRACT_DATE);
        assertThat(jv.getNarration()).startsWith("Security deposit carried forward from ");

        List<JournalLine> jvLines = linesOf(jv.getId());
        assertThat(jvLines).hasSize(2);
        assertThat(jvLines.get(0).getAccountId()).isEqualTo(deposit.getId());
        assertThat(jvLines.get(0).getDebit()).isEqualByComparingTo("3000");
        assertThat(jvLines.get(0).getLeaseId()).isEqualTo(firstId);
        assertThat(jvLines.get(1).getAccountId()).isEqualTo(deposit.getId());
        assertThat(jvLines.get(1).getCredit()).isEqualByComparingTo("3000");
        assertThat(jvLines.get(1).getLeaseId()).isEqualTo(successor.getId());

        // The liability moved rather than doubling: the old lease holds nothing,
        // the new one holds the deposit, and the tenant-wide total is unchanged.
        assertThat(balanceOf(deposit, firstId)).isEqualByComparingTo("0");
        assertThat(balanceOf(deposit, successor.getId())).isEqualByComparingTo("-3000");
    }

    /**
     * A deposit partly refunded during the term carries forward at what is
     * <em>left</em>, not at the figure last year's contract charged.
     *
     * <p>This is the assertion that distinguishes "read the ledger" from "read the
     * line". Using the line's nominal 3,000 here would credit the new lease with
     * money the landlord is not holding and strand 1,000 on a retired contract
     * that nothing will ever clear.</p>
     */
    @Test
    void carryForwardUsesTheLedgerBalanceNotTheNominalDeposit() {
        UUID firstId = postedWithDeposit();
        Account deposit = leaf(AccountRole.SECURITY_DEPOSIT);
        Account bank = leaf(AccountRole.BANK);

        // 1,000 of the deposit refunded mid-term, against the old lease.
        tx.executeWithoutResult(s -> {
            Lease first = leaseRepo.findById(firstId).orElseThrow();
            PostingRequest.Dimensions dims = LeaseChequeRegistrar.dimensions(first, null);
            postingService.post(PostingRequest.ofPairs(
                    JournalDocType.JV, LocalDate.of(2027, 1, 15), "Partial deposit refund",
                    dims, JournalSourceType.LEASE, firstId, null,
                    List.of(PostingRequest.pair(
                            PostingRequest.dr(deposit.getId(), new BigDecimal("1000")).withDims(dims),
                            PostingRequest.cr(bank.getId(), new BigDecimal("1000")).withDims(dims)))));
        });
        assertThat(balanceOf(deposit, firstId)).isEqualByComparingTo("-2000");

        LeaseDTO successor = renewal.renew(firstId, renewRequest(true));
        fixtures.generateGrid(successor.getId(), 4, RENEWAL_START);
        assertThat(posting.dryRun(successor.getId()).depositCarriedForward()).isEqualByComparingTo("2000");

        posting.post(successor.getId());

        JournalEntry carry = tx.execute(s -> entries.findAll().stream()
                .filter(e -> e.getDocType() == JournalDocType.JV
                        && e.getNarration() != null && e.getNarration().startsWith("Security deposit carried"))
                .findFirst().orElseThrow());
        List<JournalLine> carryLines = linesOf(carry.getId());
        assertThat(carryLines.get(0).getDebit()).isEqualByComparingTo("2000");
        assertThat(carryLines.get(1).getCredit()).isEqualByComparingTo("2000");

        assertThat(balanceOf(deposit, firstId)).isEqualByComparingTo("0");
        assertThat(balanceOf(deposit, successor.getId())).isEqualByComparingTo("-2000");
    }

    /** A deposit already refunded in full has nothing to carry, and no JV is written. */
    @Test
    void aFullyRefundedDepositCarriesNothingAndWritesNoJv() {
        UUID firstId = postedWithDeposit();
        Account deposit = leaf(AccountRole.SECURITY_DEPOSIT);
        Account bank = leaf(AccountRole.BANK);

        tx.executeWithoutResult(s -> {
            Lease first = leaseRepo.findById(firstId).orElseThrow();
            PostingRequest.Dimensions dims = LeaseChequeRegistrar.dimensions(first, null);
            postingService.post(PostingRequest.ofPairs(
                    JournalDocType.JV, LocalDate.of(2027, 1, 15), "Deposit refunded in full",
                    dims, JournalSourceType.LEASE, firstId, null,
                    List.of(PostingRequest.pair(
                            PostingRequest.dr(deposit.getId(), new BigDecimal("3000")).withDims(dims),
                            PostingRequest.cr(bank.getId(), new BigDecimal("3000")).withDims(dims)))));
        });

        LeaseDTO successor = renewal.renew(firstId, renewRequest(true));
        fixtures.generateGrid(successor.getId(), 4, RENEWAL_START);
        assertThat(posting.dryRun(successor.getId()).depositCarriedForward()).isEqualByComparingTo("0");

        posting.post(successor.getId());

        Long carries = jdbc.queryForObject(
                "select count(*) from journal_entries where tenant_id = ? and doc_type = 'JV' and narration like 'Security deposit carried%'",
                Long.class, fixtures.tenantId());
        assertThat(carries).isZero();
        assertThat(balanceOf(deposit, successor.getId())).isEqualByComparingTo("0");
    }

    /** Without the flag, nothing moves and the successor charges its own deposit. */
    @Test
    void aRenewalWithoutTheFlagCopiesTheDepositLineAndMovesNothing() {
        UUID firstId = postedWithDeposit();

        LeaseDTO successor = renewal.renew(firstId, renewRequest(false));

        assertThat(leaseLines(successor.getId())).extracting(LeaseLineDTO::chargeTypeCode)
                .containsExactly("RENT", "SECURITY_DEPOSIT");
        assertThat(posting.dryRun(successor.getId()).depositCarriedForward()).isEqualByComparingTo("0");
    }

    /** Year three, for the chained-renewal tests. */
    private static final LocalDate THIRD_CONTRACT_DATE = LocalDate.of(2028, 9, 16);
    private static final LocalDate THIRD_START = LocalDate.of(2028, 10, 2);
    private static final LocalDate THIRD_END = LocalDate.of(2029, 10, 1);

    /**
     * A → B → C, carrying at every hop. The deposit lands on C and is left on
     * neither of the leases it passed through.
     *
     * <p>This is the case that reading only the predecessor's lines cannot serve.
     * B was created <em>with</em> the carry flag, so B has no DEPOSIT line at all —
     * asking B which accounts hold its deposit returns nothing, and the second hop
     * would quietly move zero, report {@code ok}, and strand the renter's 3,000 on
     * a lease that is about to be marked RENEWED. The accounts come from walking
     * the chain back to the contract that actually charged the deposit; the amount
     * comes from B, which is where the money currently sits.</p>
     */
    @Test
    void carryingForwardTwiceMovesTheDepositAlongTheWholeChain() {
        Account deposit = leaf(AccountRole.SECURITY_DEPOSIT);

        UUID a = postedWithDeposit();
        UUID b = postAndReturn(renewal.renew(a, renewRequest(true)).getId(), RENEWAL_START);
        assertThat(balanceOf(deposit, a)).isEqualByComparingTo("0");
        assertThat(balanceOf(deposit, b)).isEqualByComparingTo("-3000");

        // Hop two. B has no DEPOSIT line of its own — it was told not to charge one.
        assertThat(leaseLines(b)).extracting(LeaseLineDTO::chargeTypeCode).containsExactly("RENT");

        LeaseDTO cDraft = renewal.renew(b, new RenewLeaseRequest(
                THIRD_CONTRACT_DATE, THIRD_START, THIRD_END, null, true));
        fixtures.generateGrid(cDraft.getId(), 4, THIRD_START);
        assertThat(posting.dryRun(cDraft.getId()).depositCarriedForward()).isEqualByComparingTo("3000");

        posting.post(cDraft.getId());

        assertThat(balanceOf(deposit, a)).isEqualByComparingTo("0");
        assertThat(balanceOf(deposit, b)).isEqualByComparingTo("0");
        assertThat(balanceOf(deposit, cDraft.getId())).isEqualByComparingTo("-3000");

        // Two hops, two JVs — the second one moved the money off B, not off A.
        List<JournalEntry> carries = carryForwardJournals();
        assertThat(carries).hasSize(2);
        assertThat(linesOf(carries.get(1).getId()).get(0).getLeaseId()).isEqualTo(b);
        assertThat(linesOf(carries.get(1).getId()).get(1).getLeaseId()).isEqualTo(cDraft.getId());

        assertThat(reread(a).getStatus()).isEqualTo(LeaseStatus.RENEWED);
        assertThat(reread(b).getStatus()).isEqualTo(LeaseStatus.RENEWED);
        assertThat(reread(cDraft.getId()).getStatus()).isEqualTo(LeaseStatus.ACTIVE);
        assertThat(reread(cDraft.getId()).getChainId()).isEqualTo(a);
    }

    /**
     * The middle lease tops the deposit up by 500 while carrying the original 3,000
     * across. The next renewal carries the whole 3,500, because the amount is the
     * balance on the immediate predecessor and not a figure from any one contract.
     */
    @Test
    void aTopUpDepositOnTheMiddleLeaseIsCarriedForwardToo() {
        Account deposit = leaf(AccountRole.SECURITY_DEPOSIT);
        UUID a = postedWithDeposit();

        // B carries A's deposit forward AND charges 500 more of its own, so its
        // lines are given explicitly rather than copied.
        LeaseDTO bDraft = renewal.renew(a, new RenewLeaseRequest(
                RENEWAL_CONTRACT_DATE, RENEWAL_START, RENEWAL_END,
                List.of(linePeriod("RENT", "51000", RENEWAL_START, RENEWAL_END),
                        line("SECURITY_DEPOSIT", "500")),
                true));
        UUID b = postAndReturn(bDraft.getId(), RENEWAL_START);
        assertThat(balanceOf(deposit, b)).isEqualByComparingTo("-3500");

        LeaseDTO cDraft = renewal.renew(b, new RenewLeaseRequest(
                THIRD_CONTRACT_DATE, THIRD_START, THIRD_END, null, true));
        fixtures.generateGrid(cDraft.getId(), 4, THIRD_START);
        assertThat(posting.dryRun(cDraft.getId()).depositCarriedForward()).isEqualByComparingTo("3500");

        posting.post(cDraft.getId());

        assertThat(balanceOf(deposit, b)).isEqualByComparingTo("0");
        assertThat(balanceOf(deposit, cDraft.getId())).isEqualByComparingTo("-3500");
    }

    /**
     * Without a tenant in context the JPQL loads would cross tenants, and a
     * carry-forward that answered "nothing to move" for a lease holding a deposit
     * is the worst possible failure mode: it posts, it succeeds, and the money is
     * gone. Refused outright instead.
     */
    @Test
    void carryForwardWithoutATenantInContextIsRefusedRatherThanAnsweringZero() {
        UUID a = postedWithDeposit();
        LeaseDTO successor = renewal.renew(a, renewRequest(true));
        Lease detached = reread(successor.getId());

        TenantContextHolder.clear();

        assertThatThrownBy(() -> carryForward.plan(detached))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No tenant in context");
    }

    // ------------------------------------------------------------------
    // predecessors that are not ACTIVE
    // ------------------------------------------------------------------

    /**
     * The holdover case: the contract ran out, the renter stayed, the paperwork
     * followed. Posting the successor retires the EXPIRED predecessor exactly as it
     * retires an ACTIVE one — RENEWED means "handed its unit to the next contract",
     * which is what has happened.
     */
    @Test
    void postingASuccessorFromAnExpiredPredecessorRetiresIt() {
        assertPredecessorRetiredFrom(LeaseStatus.EXPIRED);
    }

    /** A renter who gave notice and changed their mind. */
    @Test
    void postingASuccessorFromANoticeGivenPredecessorRetiresIt() {
        assertPredecessorRetiredFrom(LeaseStatus.NOTICE_GIVEN);
    }

    private void assertPredecessorRetiredFrom(LeaseStatus predecessorStatus) {
        UUID firstId = postedWithFee();
        setStatus(firstId, predecessorStatus);

        LeaseDTO successor = renewal.renew(firstId, renewRequest(false));
        fixtures.generateGrid(successor.getId(), 4, RENEWAL_START);
        posting.post(successor.getId());

        assertThat(reread(firstId).getStatus()).isEqualTo(LeaseStatus.RENEWED);
        assertThat(reread(successor.getId()).getStatus()).isEqualTo(LeaseStatus.ACTIVE);

        // The trail records where it came FROM, not a guessed ACTIVE.
        LeaseEvent retirement = eventsOf(firstId).stream()
                .filter(e -> e.getNewState() == LeaseStatus.RENEWED)
                .findFirst().orElseThrow();
        assertThat(retirement.getPreviousState()).isEqualTo(predecessorStatus);
        assertThat(retirement.getNotes()).startsWith("Renewed by TCO-");

        // The renter never moved out, so the unit is never vacant in between.
        Unit unit = tx.execute(s -> unitRepo.findById(fixtures.unit().getId()).orElseThrow());
        assertThat(unit.getStatus()).isEqualTo(UnitStatus.OCCUPIED);
        assertThat(unit.getCurrentTenantName()).isEqualTo(fixtures.renter().getNameEn());
    }

    // ------------------------------------------------------------------
    // the vacancy exemption's edges
    // ------------------------------------------------------------------

    /**
     * The exemption is narrow: it waives the vacancy check for the predecessor's
     * own occupancy and for nothing else. An expired lease whose unit has since
     * been re-let to somebody else cannot be renewed on top of the new tenant.
     */
    @Test
    void aUnitHeldByAnUnrelatedActiveLeaseCannotBeRenewedOnto() {
        UUID expired = postedWithFee();
        setStatus(expired, LeaseStatus.EXPIRED);
        // The unit is freed the way an expiry sweep would free it, so the incoming
        // lease can be drafted at all.
        setUnitVacant();

        Renter incoming = fixtures.createRenter("Incoming Renter");
        UUID other = fixtures.postedLease(fixtures.unit(), incoming,
                LocalDate.of(2027, 9, 20), LocalDate.of(2027, 10, 2), LocalDate.of(2028, 10, 1),
                List.of(line("RENT", "51000")), 4, null).lease().getId();
        assertThat(reread(other).getStatus()).isEqualTo(LeaseStatus.ACTIVE);

        assertThatThrownBy(() -> renewal.renew(expired, renewRequest(false)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Cannot create lease. Unit is not vacant.");

        List<Lease> successors = tx.execute(s -> leaseRepo.findByRenewedFromLeaseId(expired));
        assertThat(successors).isEmpty();
    }

    /**
     * There is no way to ask for a successor on a different unit: {@code renew}
     * reads the unit and the renter off the predecessor and {@code RenewLeaseRequest}
     * carries neither. A move to another unit is a new lease, not a renewal — it
     * would have to vacate one unit and claim another, which is exactly what the
     * vacancy check exists to police.
     */
    @Test
    void theSuccessorIsAlwaysOnThePredecessorsUnitAndRenter() {
        UUID firstId = postedWithFee();
        Unit elsewhere = fixtures.createUnit(fixtures.property(), "909");

        LeaseDTO successor = renewal.renew(firstId, renewRequest(false));

        assertThat(successor.getUnitId()).isEqualTo(fixtures.unit().getId());
        assertThat(successor.getUnitId()).isNotEqualTo(elsewhere.getId());
        assertThat(successor.getRenterId()).isEqualTo(fixtures.renter().getId());
    }

    // ------------------------------------------------------------------
    // the renewal funnel
    // ------------------------------------------------------------------

    /**
     * The funnel closes when the successor goes on the books, not when somebody
     * remembers to tick it — and it closes once.
     */
    @Test
    void postingTheSuccessorClosesTheOpenRenewalOpportunityExactlyOnce() {
        UUID firstId = postedWithFee();
        UUID opportunityId = openOpportunityFor(firstId);

        LeaseDTO successor = renewal.renew(firstId, renewRequest(false));
        // Drafting is not winning: the opportunity stays open until the contract is
        // actually on the books.
        assertThat(opportunity(opportunityId).getStage()).isEqualTo(RenewalStage.OPEN);

        fixtures.generateGrid(successor.getId(), 4, RENEWAL_START);
        posting.post(successor.getId());

        RenewalOpportunity closed = opportunity(opportunityId);
        assertThat(closed.getStage()).isEqualTo(RenewalStage.CLOSED_WON);
        assertThat(closed.getOutcome()).isEqualTo(RenewalOutcome.RENEWED);
        assertThat(closed.getClosedAt()).isNotNull();

        // Exactly one, not one per deposit line or one per cheque.
        List<RenewalOpportunity> all = tx.execute(s -> opportunities.findAll().stream()
                .filter(o -> o.getLease().getId().equals(firstId)).toList());
        assertThat(all).hasSize(1);
    }

    /**
     * Most renewals are drafted before the scheduler ever opens an opportunity, or
     * after a PM has closed one by hand. The post must not care.
     *
     * <p>It is the {@code NotFoundException} from the throwing {@code markRenewed}
     * that this guards against: raised inside a {@code @Transactional} proxy it
     * would mark the whole posting rollback-only, and the post would fail at commit
     * with "Transaction silently rolled back" — a contract refused over a CRM row.
     */
    @Test
    void aSuccessorPostsCleanlyWhenTheresNoOpenOpportunity() {
        UUID firstId = postedWithFee();
        List<RenewalOpportunity> none = tx.execute(s -> opportunities.findAll());
        assertThat(none).isEmpty();

        LeaseDTO successor = renewal.renew(firstId, renewRequest(false));
        fixtures.generateGrid(successor.getId(), 4, RENEWAL_START);

        PostLeaseResponse posted = posting.post(successor.getId());

        assertThat(posted.tcoEntryNumber()).startsWith("TCO-27/");
        assertThat(reread(successor.getId()).getStatus()).isEqualTo(LeaseStatus.ACTIVE);
        assertThat(reread(firstId).getStatus()).isEqualTo(LeaseStatus.RENEWED);
    }

    // ------------------------------------------------------------------
    // extend
    // ------------------------------------------------------------------

    private static final LocalDate EXTENSION_DATE = LocalDate.of(2027, 9, 20);
    private static final LocalDate NEW_END = LocalDate.of(2027, 12, 31);

    private ExtendLeaseRequest extension(String lineAmount, String chequeAmount) {
        return new ExtendLeaseRequest(NEW_END, EXTENSION_DATE,
                List.of(line("RENT", lineAmount)),
                List.of(chequeRow(chequeAmount, LocalDate.of(2027, 10, 2))));
    }

    /**
     * The extension appends its lines and rows, posts a second {@code TCO} for the
     * new lines alone, and leaves the original one POSTED.
     */
    @Test
    void extendAppendsLinesChequesAndPostsAdditionalTco() {
        UUID leaseId = postedWithFee();
        UUID originalTcoId = reread(leaseId).getPostingJournalId();

        PostLeaseResponse r = renewal.extend(leaseId, extension("12000", "12000"));

        // ---- a second TCO, for the extension only ------------------------
        JournalEntry second = tx.execute(s -> entries.findById(r.tcoJournalId()).orElseThrow());
        assertThat(second.getId()).isNotEqualTo(originalTcoId);
        assertThat(second.getDocType()).isEqualTo(JournalDocType.TCO);
        assertThat(second.getEntryDate()).isEqualTo(EXTENSION_DATE);
        assertThat(second.getNarration()).isEqualTo("Extension to " + NEW_END);
        assertThat(second.getSourceType()).isEqualTo(JournalSourceType.LEASE);
        assertThat(second.getSourceId()).isEqualTo(leaseId);

        List<JournalLine> secondLines = linesOf(second.getId());
        assertThat(secondLines).hasSize(2);
        assertThat(secondLines.get(0).getAccountId()).isEqualTo(leaf(AccountRole.RENT_RECEIVABLE).getId());
        assertThat(secondLines.get(0).getDebit()).isEqualByComparingTo("12000");
        assertThat(secondLines.get(1).getAccountId()).isEqualTo(leaf(AccountRole.ADVANCE_RENT).getId());
        assertThat(secondLines.get(1).getCredit()).isEqualByComparingTo("12000");

        // ---- the original is untouched ------------------------------------
        JournalEntry original = tx.execute(s -> entries.findById(originalTcoId).orElseThrow());
        assertThat(original.getStatus()).isEqualTo(JournalStatus.POSTED);
        assertThat(original.getReversedById()).isNull();
        assertThat(linesOf(originalTcoId)).hasSize(4);
        // postingJournalId still names the FIRST TCO — the one an amendment reverses.
        assertThat(reread(leaseId).getPostingJournalId()).isEqualTo(originalTcoId);
        assertThat(tcosOf(leaseId)).extracting(JournalEntry::getId)
                .containsExactly(originalTcoId, second.getId());

        // ---- the lease grew --------------------------------------------
        Lease lease = reread(leaseId);
        assertThat(lease.getEndDate()).isEqualTo(NEW_END);
        assertThat(lease.getStatus()).isEqualTo(LeaseStatus.ACTIVE);
        assertThat(lease.getRentAmount()).isEqualByComparingTo("63000");
        assertThat(lease.getTotalDays()).isEqualTo(
                (int) java.time.temporal.ChronoUnit.DAYS.between(START, NEW_END) + 1);

        List<LeaseLineDTO> lines = leaseLines(leaseId);
        assertThat(lines).hasSize(3);
        assertThat(lines.get(2).seqNo()).isEqualTo(3);
        assertThat(lines.get(2).chargeTypeCode()).isEqualTo("RENT");
        // The rent line covers the extension window, not the whole term.
        assertThat(lines.get(2).periodStart()).isEqualTo(END.plusDays(1));
        assertThat(lines.get(2).periodEnd()).isEqualTo(NEW_END);
        // And the original lines kept their ids, so the first TCO still describes rows that exist.
        assertThat(lines.get(0).netAmount()).isEqualByComparingTo("51000");

        // ---- the new row is a registered instrument ----------------------
        assertThat(r.cheques()).hasSize(6);
        ChequeDTO added = r.cheques().get(5);
        assertThat(added.amount()).isEqualByComparingTo("12000");
        assertThat(added.status()).isEqualTo(ChequeStatus.REGISTERED);
        assertThat(added.postingDate()).isEqualTo(EXTENSION_DATE);
        JournalEntry pdr = tx.execute(s -> entries.findById(added.pdrJournalId()).orElseThrow());
        assertThat(pdr.getDocType()).isEqualTo(JournalDocType.PDR);
        assertThat(pdr.getEntryDate()).isEqualTo(EXTENSION_DATE);
    }

    /**
     * The whole extension nets out in the renter's receivable, exactly as the
     * original contract did: the second TCO debits it and the extension's PDR
     * credits it back.
     */
    @Test
    void extendKeepsTheOriginalTcoPostedAndTheRenterLedgerNetsToZero() {
        UUID leaseId = postedWithFee();
        UUID originalTcoId = reread(leaseId).getPostingJournalId();
        Account receivable = leaf(AccountRole.RENT_RECEIVABLE);
        assertThat(balanceOf(receivable, leaseId)).isEqualByComparingTo("0");

        renewal.extend(leaseId, extension("12000", "12000"));

        assertThat(tx.execute(s -> entries.findById(originalTcoId).orElseThrow()).getStatus())
                .isEqualTo(JournalStatus.POSTED);
        Long tcrs = jdbc.queryForObject(
                "select count(*) from journal_entries where tenant_id = ? and doc_type = 'TCR'",
                Long.class, fixtures.tenantId());
        assertThat(tcrs).isZero();

        assertThat(balanceOf(receivable, leaseId)).isEqualByComparingTo("0");
        assertThat(balanceOf(leaf(AccountRole.ADVANCE_RENT), leaseId)).isEqualByComparingTo("-63000");
        assertThat(balanceOf(leaf(AccountRole.PDC_RECEIVABLE), leaseId)).isEqualByComparingTo("65000");
    }

    /**
     * The cheques have to pay for what the extension charges. A mismatch is refused
     * before the first line is appended, so a rejected extension leaves the lease
     * exactly as it found it.
     */
    @Test
    void extendRejectsChequeMismatch() {
        UUID leaseId = postedWithFee();
        long journalsBefore = journalEntryRows();

        assertThatThrownBy(() -> renewal.extend(leaseId, extension("12000", "11000")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Cheque rows total 11,000.00 but the extension charges 12,000.00");

        Lease lease = reread(leaseId);
        assertThat(lease.getEndDate()).isEqualTo(END);
        assertThat(leaseLines(leaseId)).hasSize(2);
        assertThat(journalEntryRows()).isEqualTo(journalsBefore);
        assertThat(registerOf(leaseId)).hasSize(5);
    }

    /** VAT is part of what the cheques must cover, through the same helper the post uses. */
    @Test
    void extendRequiresTheChequesToCoverVatToo() {
        UUID leaseId = postedWithFee();
        ExtendLeaseRequest shortByVat = new ExtendLeaseRequest(NEW_END, EXTENSION_DATE,
                List.of(vatLine("ADMIN_FEE", "1000")),
                List.of(chequeRow("1000", LocalDate.of(2027, 10, 2))));

        assertThatThrownBy(() -> renewal.extend(leaseId, shortByVat))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Cheque rows total 1,000.00 but the extension charges 1,050.00");

        ExtendLeaseRequest withVat = new ExtendLeaseRequest(NEW_END, EXTENSION_DATE,
                List.of(vatLine("ADMIN_FEE", "1000")),
                List.of(chequeRow("1050", LocalDate.of(2027, 10, 2))));
        PostLeaseResponse r = renewal.extend(leaseId, withVat);
        // Net pair plus the VAT pair.
        assertThat(linesOf(r.tcoJournalId())).hasSize(4);
        assertThat(linesOf(r.tcoJournalId()).get(3).getAccountId()).isEqualTo(leaf(AccountRole.OUTPUT_VAT).getId());
    }

    /** A locked period refuses the extension whole — no lines, no rows, no journals. */
    @Test
    void extendIntoALockedPeriodChangesNothing() {
        UUID leaseId = postedWithFee();
        long journalsBefore = journalEntryRows();
        fiscal.lockThrough(LocalDate.of(2027, 9, 30));

        assertThatThrownBy(() -> renewal.extend(leaseId, extension("12000", "12000")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Cannot post on 2027-09-20: books are locked through 2027-09-30");

        Lease lease = reread(leaseId);
        assertThat(lease.getEndDate()).isEqualTo(END);
        assertThat(leaseLines(leaseId)).hasSize(2);
        assertThat(registerOf(leaseId)).hasSize(5);
        assertThat(journalEntryRows()).isEqualTo(journalsBefore);
    }

    /** An extension cannot charge a second deposit for the same set of keys. */
    @Test
    void extendRefusesADepositLine() {
        UUID leaseId = postedWithFee();

        ExtendLeaseRequest withDeposit = new ExtendLeaseRequest(NEW_END, EXTENSION_DATE,
                List.of(line("SECURITY_DEPOSIT", "1000")),
                List.of(chequeRow("1000", LocalDate.of(2027, 10, 2))));

        assertThatThrownBy(() -> renewal.extend(leaseId, withDeposit))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("an extension cannot charge a deposit");
        assertThat(leaseLines(leaseId)).hasSize(2);
    }

    /** The new end date has to be later than the current one. */
    @Test
    void extendRefusesAnEndDateThatIsNotLater() {
        UUID leaseId = postedWithFee();

        ExtendLeaseRequest backwards = new ExtendLeaseRequest(END.minusDays(1), EXTENSION_DATE,
                List.of(line("RENT", "1000")),
                List.of(chequeRow("1000", LocalDate.of(2027, 10, 2))));

        assertThatThrownBy(() -> renewal.extend(leaseId, backwards))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("The new end date must be after the current one (" + END + ")");
    }

    /** A rent line reaching outside the extension window would re-charge the original term. */
    @Test
    void extendRefusesARentLineOutsideTheExtensionWindow() {
        UUID leaseId = postedWithFee();

        ExtendLeaseRequest overlapping = new ExtendLeaseRequest(NEW_END, EXTENSION_DATE,
                List.of(linePeriod("RENT", "12000", START, NEW_END)),
                List.of(chequeRow("12000", LocalDate.of(2027, 10, 2))));

        assertThatThrownBy(() -> renewal.extend(leaseId, overlapping))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("a rent line must cover part of the extension");
    }

    /** Only a lease that is on the books can be extended; a draft is edited instead. */
    @Test
    void extendRefusesALeaseThatIsNotActive() {
        UUID draftId = fixtures.draftLease(CONTRACT_DATE, START, END, List.of(line("RENT", "51000")));

        assertThatThrownBy(() -> renewal.extend(draftId, extension("12000", "12000")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Only an ACTIVE lease can be extended; this one is DRAFT");
    }

    /** Extending twice simply appends again; both extensions' TCOs stand. */
    @Test
    void aLeaseCanBeExtendedTwice() {
        UUID leaseId = postedWithFee();
        renewal.extend(leaseId, extension("12000", "12000"));

        LocalDate furtherEnd = LocalDate.of(2028, 3, 31);
        PostLeaseResponse second = renewal.extend(leaseId, new ExtendLeaseRequest(
                furtherEnd, LocalDate.of(2027, 12, 20),
                List.of(line("RENT", "9000")),
                List.of(chequeRow("9000", LocalDate.of(2028, 1, 2)))));

        assertThat(reread(leaseId).getEndDate()).isEqualTo(furtherEnd);
        assertThat(leaseLines(leaseId)).hasSize(4);
        assertThat(leaseLines(leaseId).get(3).periodStart()).isEqualTo(NEW_END.plusDays(1));
        assertThat(tcosOf(leaseId)).hasSize(3);
        assertThat(second.cheques()).hasSize(7);
        assertThat(balanceOf(leaf(AccountRole.RENT_RECEIVABLE), leaseId)).isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------------
    // extend × amend — the cross-task seam
    // ------------------------------------------------------------------

    /**
     * Amending an extended lease reverses <em>both</em> TCOs and reposts one.
     *
     * <p>This is the seam neither task could see on its own. {@code extend} appends
     * lines and rows and posts a second TCO, leaving {@code postingJournalId} on the
     * first. {@code amendLines} replaces <b>all</b> the lease's lines and validates Σ
     * of <b>all</b> its cheques against the new total — so the only input that passes
     * is one that re-includes the extension's charges — and it used to reverse only
     * the journal {@code postingJournalId} named. The extension's own TCO stayed
     * POSTED beside a fresh TCO that charged the same money again: rent receivable
     * and income overstated by exactly the extension's value, with the Σ guard
     * reporting everything was fine because it was comparing the same total against
     * itself.</p>
     *
     * <p>The figures below are the whole assertion. After a clean amendment the
     * ledger has to be what a from-scratch posting of the amended lease would be:
     * receivable back to zero (every charge covered by a cheque), income at the
     * amended split and not the sum of two postings, and one live TCO.</p>
     */
    @Test
    void amendingAnExtendedLeaseReversesEveryTcoAndDoesNotDoubleBookTheExtension() {
        UUID leaseId = postedWithFee();                       // 51,000 rent + 2,000 fee
        UUID originalTcoId = reread(leaseId).getPostingJournalId();
        UUID extensionTcoId = renewal.extend(leaseId, extension("12000", "12000")).tcoJournalId();

        Account receivable = leaf(AccountRole.RENT_RECEIVABLE);
        Account rentIncome = leaf(AccountRole.ADVANCE_RENT);
        Account adminIncome = leaf(AccountRole.ADMIN_FEE);
        Account pdc = leaf(AccountRole.PDC_RECEIVABLE);
        assertThat(balanceOf(receivable, leaseId)).isEqualByComparingTo("0");

        // The same 65,000 of cheques, re-cut: 1,000 of rent moved to the admin fee,
        // and the extension's own rent line restated exactly as it stands.
        posting.amendLines(leaseId, List.of(
                line("RENT", "50000"),
                line("ADMIN_FEE", "3000"),
                linePeriod("RENT", "12000", END.plusDays(1), NEW_END)), "Fee split corrected");

        // ---- one live TCO, both old ones reversed -------------------------
        assertThat(tx.execute(s -> entries.findById(originalTcoId).orElseThrow()).getStatus())
                .isEqualTo(JournalStatus.REVERSED);
        assertThat(tx.execute(s -> entries.findById(extensionTcoId).orElseThrow()).getStatus())
                .as("the extension's TCO charges money the fresh TCO charges again")
                .isEqualTo(JournalStatus.REVERSED);

        List<JournalEntry> live = tcosOf(leaseId).stream()
                .filter(e -> e.getStatus() == JournalStatus.POSTED)
                .toList();
        assertThat(live).hasSize(1);
        UUID repostedId = live.get(0).getId();
        assertThat(reread(leaseId).getPostingJournalId()).isEqualTo(repostedId);
        // One reversal per journal reposted, and no more.
        Long tcrs = jdbc.queryForObject(
                "select count(*) from journal_entries where tenant_id = ? and doc_type = 'TCR'",
                Long.class, fixtures.tenantId());
        assertThat(tcrs).isEqualTo(2L);

        // ---- the trial balance, per account -------------------------------
        // Everything charged is covered by an instrument, as it was before the amendment.
        assertThat(balanceOf(receivable, leaseId)).isEqualByComparingTo("0");
        // 50,000 + the extension's 12,000, credited once. The bug left this at -74,000.
        assertThat(balanceOf(rentIncome, leaseId)).isEqualByComparingTo("-62000");
        assertThat(balanceOf(adminIncome, leaseId)).isEqualByComparingTo("-3000");
        // The register never moved: the cheques are the amendment's precondition.
        assertThat(balanceOf(pdc, leaseId)).isEqualByComparingTo("65000");
        assertThat(registerOf(leaseId)).hasSize(6);

        // The reposted TCO carries every line, including the extension's.
        assertThat(linesOf(repostedId)).hasSize(6);
        assertThat(leaseLines(leaseId)).hasSize(3);
    }

    /**
     * And the same holds a second time round: amending an already-amended extended
     * lease reverses the one live TCO and no more. A TCO an earlier amendment
     * already reversed is history — {@code PostingService.reverse} refuses to
     * reverse it twice, so a filter that forgot the POSTED test would turn the
     * second amendment into a 400.
     */
    @Test
    void amendingTwiceReversesOnlyTheLiveTco() {
        UUID leaseId = postedWithFee();
        renewal.extend(leaseId, extension("12000", "12000"));
        posting.amendLines(leaseId, List.of(
                line("RENT", "50000"),
                line("ADMIN_FEE", "3000"),
                linePeriod("RENT", "12000", END.plusDays(1), NEW_END)), "First correction");

        posting.amendLines(leaseId, List.of(
                line("RENT", "49000"),
                line("ADMIN_FEE", "4000"),
                linePeriod("RENT", "12000", END.plusDays(1), NEW_END)), "Second correction");

        assertThat(tcosOf(leaseId).stream().filter(e -> e.getStatus() == JournalStatus.POSTED).toList())
                .hasSize(1);
        assertThat(balanceOf(leaf(AccountRole.RENT_RECEIVABLE), leaseId)).isEqualByComparingTo("0");
        assertThat(balanceOf(leaf(AccountRole.ADVANCE_RENT), leaseId)).isEqualByComparingTo("-61000");
        assertThat(balanceOf(leaf(AccountRole.ADMIN_FEE), leaseId)).isEqualByComparingTo("-4000");
    }

    /** A cheque row this lease already uses the number of is refused, as anywhere else. */
    @Test
    void extendAppliesTheSameChequeRowRulesAsEverywhereElse() {
        UUID leaseId = fixtures.postedLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000"), line("ADMIN_FEE", "2000")), 4, "100040").lease().getId();

        ChequeRowInput duplicate = new ChequeRowInput(null, null, null, "100041",
                LocalDate.of(2027, 10, 2), "Emirates NBD", null, null, new BigDecimal("12000"), null, null);
        ExtendLeaseRequest clash = new ExtendLeaseRequest(NEW_END, EXTENSION_DATE,
                List.of(line("RENT", "12000")), List.of(duplicate));

        assertThatThrownBy(() -> renewal.extend(leaseId, clash))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("cheque number 100041 is already used on this lease");
        assertThat(leaseLines(leaseId)).hasSize(2);
    }

    // ------------------------------------------------------------------
    // renew: only the contract's own lines are copied
    // ------------------------------------------------------------------

    /**
     * A mid-term addendum's line is a charge for part of the old term, priced for
     * that part. Copied into a renewal it would be charged again over a whole year
     * at its fragment price; the renewal carries only the contract's own lines.
     */
    @Test
    void renewAfterAnAddendumCopiesOnlyTheOriginalLines() {
        UUID leaseId = postedWithFee();
        variations.addCharge(leaseId, new AddChargeRequest(LocalDate.of(2027, 2, 15), LocalDate.of(2027, 2, 10),
                null, "Storage room", List.of(line("RENT", "4000"), line("PARKING_FEE", "1500")),
                List.of(chequeRow("5500", LocalDate.of(2027, 3, 1)))));
        assertThat(leaseLines(leaseId)).hasSize(4);

        LeaseDTO successor = renewal.renew(leaseId, renewRequest(false));

        List<LeaseLineDTO> copied = leaseLines(successor.getId());
        assertThat(copied).extracting(LeaseLineDTO::chargeTypeCode).containsExactly("RENT", "ADMIN_FEE");
        assertThat(copied.get(0).netAmount()).isEqualByComparingTo("51000");
        assertThat(copied.get(0).periodStart()).isEqualTo(RENEWAL_START);
        assertThat(copied.get(0).periodEnd()).isEqualTo(RENEWAL_END);
        assertThat(copied.get(1).netAmount()).isEqualByComparingTo("2000");
    }

    /**
     * An extension's rent covers only the extension window. Re-dated to a whole
     * new term it would charge the extension's price a second time as if it were
     * a year's rent, next to the contract's own rent line.
     */
    @Test
    void renewAfterAnExtensionCopiesOnlyTheOriginalLines() {
        UUID leaseId = postedWithFee();
        renewal.extend(leaseId, extension("12000", "12000"));
        assertThat(leaseLines(leaseId)).hasSize(3);

        LocalDate start = NEW_END.plusDays(1);
        LocalDate end = start.plusYears(1).minusDays(1);
        LeaseDTO successor = renewal.renew(leaseId,
                new RenewLeaseRequest(LocalDate.of(2027, 12, 15), start, end, null, false));

        List<LeaseLineDTO> copied = leaseLines(successor.getId());
        assertThat(copied).extracting(LeaseLineDTO::chargeTypeCode).containsExactly("RENT", "ADMIN_FEE");
        assertThat(copied.get(0).netAmount()).isEqualByComparingTo("51000");
        assertThat(copied.get(0).periodStart()).isEqualTo(start);
        assertThat(copied.get(0).periodEnd()).isEqualTo(end);
    }
}
