package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
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
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.JournalStatus;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseLineRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
@Testcontainers
class LeaseRenewalServiceIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired LeaseRenewalService renewal;
    @Autowired LeasePostingService posting;
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
}
