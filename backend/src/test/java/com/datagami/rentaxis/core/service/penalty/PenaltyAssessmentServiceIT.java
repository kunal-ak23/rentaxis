package com.datagami.rentaxis.core.service.penalty;

import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.lease.PostLeaseResponse;
import com.datagami.rentaxis.api.dto.penalty.PenaltyAssessmentDTO;
import com.datagami.rentaxis.api.dto.penalty.ProposePenaltyRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService.LedgerFilter;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.JournalLine;
import com.datagami.rentaxis.domain.entity.LandlordOrgFineSettings;
import com.datagami.rentaxis.domain.entity.PenaltyAssessment;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.RentCollectionSettings;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.JournalStatus;
import com.datagami.rentaxis.domain.entity.enums.PenaltyAssessmentStatus;
import com.datagami.rentaxis.domain.entity.enums.PenaltyReason;
import com.datagami.rentaxis.domain.entity.enums.PenaltyType;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgFineSettingsRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.PenaltyAssessmentRepository;
import com.datagami.rentaxis.domain.repository.RentCollectionSettingsRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Approval-gated penalties against a real database (spec §7.3).
 *
 * <p>The assertions that matter are about the ledger afterwards, and about
 * all-or-nothing behaviour only a real transaction exhibits — which is why none
 * of this is mocked. The figures are the register's own Galah 2 shape: 51,000 of
 * rent over four cheques of 12,750 plus a 2,000 admin fee, numbered 100040 up.</p>
 *
 * <p><b>Transactions.</b> {@code TenantAspect} only enables the Hibernate tenant
 * filter inside one, so every read-back goes through {@link #tx}.</p>
 */
@SpringBootTest
@Testcontainers
class PenaltyAssessmentServiceIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired PenaltyAssessmentService service;
    @Autowired ChequeService chequeService;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService generation;
    @Autowired LeaseService leaseService;
    @Autowired LedgerQueryService ledger;
    @Autowired AccountResolver resolver;
    @Autowired AccountService accountService;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired PenaltyAssessmentRepository assessments;
    @Autowired ChequeRepository chequeRepo;
    @Autowired JournalEntryRepository entries;
    @Autowired JournalLineRepository lines;
    @Autowired LandlordOrgFineSettingsRepository fineSettingsRepo;
    @Autowired RentCollectionSettingsRepository rentCollectionSettingsRepo;
    @Autowired UserPropertyAssignmentRepository assignmentRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private LeaseTestFixtures fixtures;

    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 9, 16);
    private static final LocalDate START = LocalDate.of(2026, 10, 2);
    private static final LocalDate END = LocalDate.of(2027, 10, 1);
    private static final LocalDate DEPOSIT_DATE = LocalDate.of(2026, 10, 5);
    private static final LocalDate BOUNCE_DATE = LocalDate.of(2026, 10, 12);
    private static final LocalDate APPROVE_DATE = LocalDate.of(2026, 10, 20);
    private static final LocalDate REVERSE_DATE = LocalDate.of(2026, 10, 25);

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, generation, posting);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /** 53,000 of contract over five numbered instruments, on the books. */
    private PostLeaseResponse posted() {
        return fixtures.postedLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000"), line("ADMIN_FEE", "2000")), 4, "100040");
    }

    private Account leaf(AccountRole role) {
        return tx.execute(s -> resolver.resolve(role, fixtures.property().getId()));
    }

    private JournalEntry entry(UUID entryId) {
        return tx.execute(s -> entries.findById(entryId).orElseThrow());
    }

    private List<JournalLine> linesOf(UUID entryId) {
        return tx.execute(s -> lines.findByEntry_IdOrderByLineNoAsc(entryId));
    }

    private Cheque rereadCheque(UUID chequeId) {
        return tx.execute(s -> chequeRepo.findById(chequeId).orElseThrow());
    }

    private PenaltyAssessment reread(UUID id) {
        return tx.execute(s -> assessments.findById(id).orElseThrow());
    }

    /** The lease's own slice of an account's ledger: debit minus credit. */
    private BigDecimal balance(Account account, UUID leaseId) {
        return tx.execute(s -> ledger.accountLedger(account.getId(),
                new LedgerFilter(null, null, null, null, leaseId, null)).closingBalance());
    }

    private long entryCount(JournalDocType docType, UUID sourceId) {
        return jdbc.queryForObject(
                "select count(*) from journal_entries where tenant_id = ? and doc_type = ? and source_id = ?",
                Long.class, fixtures.tenantId(), docType.name(), sourceId);
    }

    private long assessmentRows() {
        return jdbc.queryForObject("select count(*) from penalty_assessments where tenant_id = ?",
                Long.class, fixtures.tenantId());
    }

    private long journalEntryRows() {
        return jdbc.queryForObject("select count(*) from journal_entries where tenant_id = ?",
                Long.class, fixtures.tenantId());
    }

    /** The organisation's fine settings, created by the resolver's first read. */
    private LandlordOrgFineSettings fineSettings() {
        return tx.execute(s -> fineSettingsRepo.findByLandlordOrgId(fixtures.tenantId())
                .orElseGet(() -> {
                    LandlordOrgFineSettings o = new LandlordOrgFineSettings();
                    o.setLandlordOrgId(fixtures.tenantId());
                    o.setTenantId(fixtures.tenantId());
                    o.setFineBounceAmount(new BigDecimal("500"));
                    o.setFineSignatureMismatchAmount(new BigDecimal("500"));
                    o.setFineAccountClosedAmount(new BigDecimal("1000"));
                    o.setFineGraceDays(7);
                    o.setFinePerDayRate(new BigDecimal("25"));
                    o.setBouncesBeforePenalty(2);
                    o.setAutoProposeChequeReturn(true);
                    o.setAutoProposeLatePayment(false);
                    return fineSettingsRepo.save(o);
                }));
    }

    private void fineSettings(int threshold, boolean chequeReturn, boolean latePayment) {
        tx.executeWithoutResult(s -> {
            LandlordOrgFineSettings o = fineSettings();
            o.setBouncesBeforePenalty(threshold);
            o.setAutoProposeChequeReturn(chequeReturn);
            o.setAutoProposeLatePayment(latePayment);
            fineSettingsRepo.save(o);
        });
    }

    /** Late-payment rules for the fixture's property. */
    private void latePenalty(PenaltyType type, String amount, int graceDays) {
        tx.executeWithoutResult(s -> {
            RentCollectionSettings rcs = rentCollectionSettingsRepo
                    .findByPropertyId(fixtures.property().getId())
                    .orElseGet(() -> {
                        RentCollectionSettings n = new RentCollectionSettings();
                        n.setTenantId(fixtures.tenantId());
                        n.setProperty(fixtures.property());
                        return n;
                    });
            rcs.setPenaltyType(type);
            rcs.setPenaltyAmount(new BigDecimal(amount));
            rcs.setGracePeriodDays(graceDays);
            rentCollectionSettingsRepo.save(rcs);
        });
    }

    /** One proposal raised by hand, so a test about approval need not bounce anything. */
    private PenaltyAssessmentDTO proposal(UUID leaseId, UUID chequeId, PenaltyReason reason, String amount) {
        return service.propose(new ProposePenaltyRequest(leaseId, chequeId, reason,
                new BigDecimal(amount), "Raised by the test"), null);
    }

    /** The first cheque of a posted lease, bounced. */
    private ChequeDTO bounceFirst(PostLeaseResponse r) {
        UUID chequeId = r.cheques().get(0).id();
        chequeService.deposit(chequeId, ChequeActionRequest.on(DEPOSIT_DATE));
        return chequeService.bounce(chequeId,
                new ChequeActionRequest(BOUNCE_DATE, null, ChequeFailureReason.BOUNCE, null));
    }

    /** How many rows the lease's register holds — a count taken inside a transaction. */
    private long registerSize(UUID leaseId) {
        Integer rows = tx.execute(s -> chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId).size());
        return rows == null ? 0L : rows.longValue();
    }

    private List<PenaltyAssessment> assessmentsOf(UUID leaseId) {
        return tx.execute(s -> assessments.search(leaseId, null, null, true, List.of(),
                PageRequest.of(0, 50)).getContent());
    }

    private User user(UserRole role) {
        User u = new User();
        u.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@t.io");
        u.setName(role.name());
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(fixtures.tenantId());
        return userRepo.save(u);
    }

    private static void authenticateAs(UUID userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    // ------------------------------------------------------------------
    // PROPOSED -> APPROVED
    // ------------------------------------------------------------------

    /**
     * The whole point of the module, asserted end to end.
     *
     * <p>The {@code PEN} raises the fine against the renter and the collection
     * row's {@code PDR} takes it straight back off the receivable, so the renter's
     * receivable is exactly where it was and the money is now an instrument the
     * ordinary collection screens can bank. Penalty income is up by the fine.</p>
     */
    @Test
    void approvePostsPenAndTheCollectionRowNetsReceivableToZero() {
        PostLeaseResponse r = posted();
        UUID leaseId = r.lease().getId();
        UUID chequeId = r.cheques().get(0).id();
        Account receivable = leaf(AccountRole.RENT_RECEIVABLE);
        Account pdc = leaf(AccountRole.PDC_RECEIVABLE);
        Account income = leaf(AccountRole.CHEQUE_RETURN_PENALTY);

        BigDecimal receivableBefore = balance(receivable, leaseId);
        BigDecimal pdcBefore = balance(pdc, leaseId);
        BigDecimal incomeBefore = balance(income, leaseId);

        PenaltyAssessmentDTO proposed = proposal(leaseId, chequeId, PenaltyReason.CHEQUE_RETURN, "500");
        assertThat(proposed.status()).isEqualTo(PenaltyAssessmentStatus.PROPOSED);
        assertThat(proposed.journalId()).isNull();
        assertThat(proposed.collectionChequeId()).isNull();

        PenaltyAssessmentDTO approved = service.approve(proposed.id(), APPROVE_DATE);

        assertThat(approved.status()).isEqualTo(PenaltyAssessmentStatus.APPROVED);
        assertThat(approved.approvedAt()).isNotNull();
        assertThat(approved.journalId()).isNotNull();
        assertThat(approved.collectionChequeId()).isNotNull();
        assertThat(approved.collectionStatus()).isEqualTo(ChequeStatus.REGISTERED);

        // The PEN, whole: the document, the day, both accounts, each one's contra,
        // the money, the source it can be traced back to, and every dimension.
        JournalEntry pen = entry(approved.journalId());
        assertThat(pen.getDocType()).isEqualTo(JournalDocType.PEN);
        assertThat(pen.getEntryDate()).isEqualTo(APPROVE_DATE);
        assertThat(pen.getSourceType()).isEqualTo(JournalSourceType.PENALTY);
        assertThat(pen.getSourceId()).isEqualTo(approved.id());
        assertThat(pen.getNarration()).isEqualTo("Penalty - Cheque return - cheque 100040");

        List<JournalLine> penLines = linesOf(pen.getId());
        assertThat(penLines).hasSize(2);
        assertThat(penLines.get(0).getAccountId()).isEqualTo(receivable.getId());
        assertThat(penLines.get(0).getDebit()).isEqualByComparingTo("500");
        assertThat(penLines.get(0).getContraAccountId()).isEqualTo(income.getId());
        assertThat(penLines.get(1).getAccountId()).isEqualTo(income.getId());
        assertThat(penLines.get(1).getCredit()).isEqualByComparingTo("500");
        assertThat(penLines.get(1).getContraAccountId()).isEqualTo(receivable.getId());
        assertThat(penLines).allSatisfy(l -> {
            assertThat(l.getLeaseId()).isEqualTo(leaseId);
            assertThat(l.getChequeId()).isEqualTo(chequeId);
            assertThat(l.getPropertyId()).isEqualTo(fixtures.property().getId());
            assertThat(l.getUnitId()).isEqualTo(fixtures.unit().getId());
            assertThat(l.getRenterId()).isEqualTo(fixtures.renter().getId());
        });

        // The collection row: a CASH receipt on the register, linked both ways.
        Cheque collection = rereadCheque(approved.collectionChequeId());
        assertThat(collection.getMode()).isEqualTo(ChequeMode.CASH);
        assertThat(collection.getStatus()).isEqualTo(ChequeStatus.REGISTERED);
        assertThat(collection.getChequeDate()).isEqualTo(APPROVE_DATE);
        assertThat(collection.getAmount()).isEqualByComparingTo("500");
        assertThat(collection.getNarration()).isEqualTo("Penalty - Cheque return");
        assertThat(collection.getPenaltyAssessmentId()).isEqualTo(approved.id());
        assertThat(collection.getPdrJournalId()).isNotNull();
        assertThat(entry(collection.getPdrJournalId()).getEntryDate()).isEqualTo(APPROVE_DATE);

        // Net effect: the receivable is untouched, the fine is an instrument in
        // hand, and the landlord has 500 of penalty income.
        assertThat(balance(receivable, leaseId)).isEqualByComparingTo(receivableBefore);
        assertThat(balance(pdc, leaseId)).isEqualByComparingTo(pdcBefore.add(new BigDecimal("500")));
        assertThat(balance(income, leaseId)).isEqualByComparingTo(incomeBefore.subtract(new BigDecimal("500")));
    }

    /** A lease that names its own receivable keeps the fine on that same account. */
    @Test
    void thePenaltyDebitsTheLeasesOwnReceivableWhenItHasOne() {
        Account custom = tx.execute(s -> accountService.createLeaf(
                "Rent Receivable - VIP", accountService.getAccountByCode("A-02-01"),
                fixtures.property().getId()));
        PostLeaseResponse r = posted();
        UUID leaseId = r.lease().getId();
        tx.executeWithoutResult(s -> jdbc.update(
                "update leases set receivable_account_id = ? where id = ?", custom.getId(), leaseId));

        PenaltyAssessmentDTO approved = service.approve(
                proposal(leaseId, null, PenaltyReason.OTHER, "250").id(), APPROVE_DATE);

        List<JournalLine> penLines = linesOf(approved.journalId());
        assertThat(penLines.get(0).getAccountId()).isEqualTo(custom.getId());
        assertThat(penLines.get(1).getAccountId()).isEqualTo(leaf(AccountRole.OTHER_INCOME).getId());
        // And the collection row's PDR credits the same one, or the two halves
        // would sit in different ledgers and neither would net to zero.
        Cheque collection = rereadCheque(approved.collectionChequeId());
        assertThat(linesOf(collection.getPdrJournalId()).get(1).getAccountId()).isEqualTo(custom.getId());
    }

    /** A late-payment penalty credits rent penalty income, not the cheque-return account. */
    @Test
    void theReasonChoosesTheIncomeAccount() {
        PostLeaseResponse r = posted();
        UUID leaseId = r.lease().getId();

        PenaltyAssessmentDTO approved = service.approve(
                proposal(leaseId, null, PenaltyReason.LATE_PAYMENT, "300").id(), APPROVE_DATE);

        assertThat(linesOf(approved.journalId()).get(1).getAccountId())
                .isEqualTo(leaf(AccountRole.RENT_PENALTY).getId());
    }

    @Test
    void approvingTwiceIsRefusedAndPostsNothingASecondTime() {
        PostLeaseResponse r = posted();
        PenaltyAssessmentDTO proposed = proposal(r.lease().getId(), null, PenaltyReason.OTHER, "400");
        service.approve(proposed.id(), APPROVE_DATE);
        long entriesAfterFirst = journalEntryRows();

        assertThatThrownBy(() -> service.approve(proposed.id(), APPROVE_DATE))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Can only approve penalties in PROPOSED");

        assertThat(journalEntryRows()).isEqualTo(entriesAfterFirst);
        assertThat(entryCount(JournalDocType.PEN, proposed.id())).isEqualTo(1L);
    }

    /**
     * Approving into a closed period changes nothing at all.
     *
     * <p>The PEN is refused by the period lock, and because the collection row is
     * created in the same transaction the refusal has to take that with it — an
     * assessment left APPROVED with a register row and no journal would be a fine
     * the renter can be chased for that the ledger has never heard of.</p>
     */
    @Test
    void approveIntoALockedPeriodChangesNothing() {
        PostLeaseResponse r = posted();
        UUID leaseId = r.lease().getId();
        PenaltyAssessmentDTO proposed = proposal(leaseId, null, PenaltyReason.OTHER, "400");
        long chequesBefore = registerSize(leaseId);
        long entriesBefore = journalEntryRows();
        fiscal.lockThrough(LocalDate.of(2026, 10, 31));

        assertThatThrownBy(() -> service.approve(proposed.id(), APPROVE_DATE))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("books are locked through 2026-10-31");

        PenaltyAssessment unchanged = reread(proposed.id());
        assertThat(unchanged.getStatus()).isEqualTo(PenaltyAssessmentStatus.PROPOSED);
        assertThat(unchanged.getJournalId()).isNull();
        assertThat(unchanged.getCollectionCheque()).isNull();
        assertThat(journalEntryRows()).isEqualTo(entriesBefore);
        assertThat(registerSize(leaseId)).isEqualTo(chequesBefore);
    }

    /**
     * Two accountants on the same proposal. Without the row lock both read
     * PROPOSED, both pass the guard, and the renter is charged twice with two
     * collection rows to bank.
     */
    @Test
    void concurrentApprovalsPostExactlyOnePen() throws Exception {
        PostLeaseResponse r = posted();
        PenaltyAssessmentDTO proposed = proposal(r.lease().getId(), null, PenaltyReason.OTHER, "400");
        UUID tenant = fixtures.tenantId();

        CyclicBarrier bothReady = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Callable<Object>> racers = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            racers.add(() -> {
                TenantContextHolder.setTenantId(tenant);
                LeaseTestFixtures.authenticateAsTenantAdmin();
                try {
                    bothReady.await(10, TimeUnit.SECONDS);
                    return service.approve(proposed.id(), APPROVE_DATE);
                } catch (RuntimeException ex) {
                    return ex;
                } finally {
                    TenantContextHolder.clear();
                    LeaseTestFixtures.clearAuth();
                }
            });
        }
        List<Future<Object>> results;
        try {
            results = pool.invokeAll(racers, 60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        List<Object> outcomes = new ArrayList<>();
        for (Future<Object> f : results) outcomes.add(f.get());

        assertThat(outcomes).filteredOn(PenaltyAssessmentDTO.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !(o instanceof PenaltyAssessmentDTO))
                .hasSize(1)
                .allMatch(BusinessRuleViolationException.class::isInstance);
        assertThat(entryCount(JournalDocType.PEN, proposed.id())).isEqualTo(1L);
        assertThat(reread(proposed.id()).getStatus()).isEqualTo(PenaltyAssessmentStatus.APPROVED);
    }

    // ------------------------------------------------------------------
    // PROPOSED -> WAIVED
    // ------------------------------------------------------------------

    @Test
    void waivePostsNothingAndKeepsTheReason() {
        PostLeaseResponse r = posted();
        PenaltyAssessmentDTO proposed = proposal(r.lease().getId(), null, PenaltyReason.OTHER, "400");
        long entriesBefore = journalEntryRows();

        PenaltyAssessmentDTO waived = service.waive(proposed.id(), "Renter's bank confirmed their error");

        assertThat(waived.status()).isEqualTo(PenaltyAssessmentStatus.WAIVED);
        assertThat(waived.resolutionNote()).isEqualTo("Renter's bank confirmed their error");
        assertThat(waived.journalId()).isNull();
        assertThat(waived.collectionChequeId()).isNull();
        assertThat(journalEntryRows()).isEqualTo(entriesBefore);
    }

    @Test
    void waiveWithoutAReasonIsRefused() {
        PostLeaseResponse r = posted();
        PenaltyAssessmentDTO proposed = proposal(r.lease().getId(), null, PenaltyReason.OTHER, "400");

        assertThatThrownBy(() -> service.waive(proposed.id(), "   "))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("A waiver needs a reason");

        assertThat(reread(proposed.id()).getStatus()).isEqualTo(PenaltyAssessmentStatus.PROPOSED);
    }

    @Test
    void anApprovedPenaltyCannotBeWaived() {
        PostLeaseResponse r = posted();
        PenaltyAssessmentDTO proposed = proposal(r.lease().getId(), null, PenaltyReason.OTHER, "400");
        service.approve(proposed.id(), APPROVE_DATE);

        assertThatThrownBy(() -> service.waive(proposed.id(), "Changed our mind"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Can only waive penalties in PROPOSED");
    }

    // ------------------------------------------------------------------
    // APPROVED -> REVERSED
    // ------------------------------------------------------------------

    @Test
    void reversePostsTheMirrorAndCancelsTheCollectionRow() {
        PostLeaseResponse r = posted();
        UUID leaseId = r.lease().getId();
        Account receivable = leaf(AccountRole.RENT_RECEIVABLE);
        Account pdc = leaf(AccountRole.PDC_RECEIVABLE);
        Account income = leaf(AccountRole.OTHER_INCOME);
        BigDecimal receivableBefore = balance(receivable, leaseId);
        BigDecimal pdcBefore = balance(pdc, leaseId);
        BigDecimal incomeBefore = balance(income, leaseId);

        PenaltyAssessmentDTO approved = service.approve(
                proposal(leaseId, null, PenaltyReason.OTHER, "400").id(), APPROVE_DATE);

        PenaltyAssessmentDTO reversed = service.reverse(approved.id(), REVERSE_DATE, "Approved in error");

        assertThat(reversed.status()).isEqualTo(PenaltyAssessmentStatus.REVERSED);
        assertThat(reversed.resolutionNote()).isEqualTo("Approved in error");
        // The original is marked, not deleted; the mirror stands beside it.
        assertThat(entry(approved.journalId()).getStatus()).isEqualTo(JournalStatus.REVERSED);

        Cheque collection = rereadCheque(approved.collectionChequeId());
        assertThat(collection.getStatus()).isEqualTo(ChequeStatus.CANCELLED);
        assertThat(entry(collection.getPdrJournalId()).getStatus()).isEqualTo(JournalStatus.REVERSED);

        // Everything is back where it started, on all three accounts.
        assertThat(balance(receivable, leaseId)).isEqualByComparingTo(receivableBefore);
        assertThat(balance(pdc, leaseId)).isEqualByComparingTo(pdcBefore);
        assertThat(balance(income, leaseId)).isEqualByComparingTo(incomeBefore);
    }

    /**
     * The renter paid the fine. Unwinding the charge while the receipt stands
     * would leave cash in the bank against no receivable.
     */
    @Test
    void reverseAfterCollectionIsRefused() {
        PostLeaseResponse r = posted();
        PenaltyAssessmentDTO approved = service.approve(
                proposal(r.lease().getId(), null, PenaltyReason.OTHER, "400").id(), APPROVE_DATE);
        chequeService.receive(approved.collectionChequeId(), ChequeActionRequest.on(APPROVE_DATE));
        long entriesBefore = journalEntryRows();

        assertThatThrownBy(() -> service.reverse(approved.id(), REVERSE_DATE, "Approved in error"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Penalty was already collected; issue a refund/credit instead");

        assertThat(reread(approved.id()).getStatus()).isEqualTo(PenaltyAssessmentStatus.APPROVED);
        assertThat(entry(approved.journalId()).getStatus()).isEqualTo(JournalStatus.POSTED);
        assertThat(journalEntryRows()).isEqualTo(entriesBefore);
    }

    @Test
    void aProposalCannotBeReversed() {
        PostLeaseResponse r = posted();
        PenaltyAssessmentDTO proposed = proposal(r.lease().getId(), null, PenaltyReason.OTHER, "400");

        assertThatThrownBy(() -> service.reverse(proposed.id(), REVERSE_DATE, "No"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Can only reverse penalties in APPROVED");
    }

    // ------------------------------------------------------------------
    // the rules, driven through the register
    // ------------------------------------------------------------------

    /**
     * The threshold, exercised the way it actually fires: through
     * {@code ChequeService.bounce}, not by calling the engine.
     *
     * <p>A test that called {@code onBounce} directly would pass with the hook
     * unwired, which is the failure mode that matters — the rule is only worth
     * anything if the register calls it.</p>
     */
    @Test
    void bounceBelowThresholdProposesNothingThenThresholdProposesOnce() {
        fineSettings(2, true, false);
        PostLeaseResponse r = posted();
        UUID leaseId = r.lease().getId();

        // First bounce: one on this lease, threshold is two.
        bounceFirst(r);
        assertThat(assessmentRows()).isZero();

        // Second bounce on a different instrument: the threshold is crossed.
        UUID second = r.cheques().get(1).id();
        chequeService.deposit(second, ChequeActionRequest.on(DEPOSIT_DATE));
        chequeService.bounce(second,
                new ChequeActionRequest(BOUNCE_DATE, null, ChequeFailureReason.SIGNATURE_MISMATCH, null));

        List<PenaltyAssessment> raised = assessmentsOf(leaseId);
        assertThat(raised).hasSize(1);
        PenaltyAssessment a = raised.get(0);
        assertThat(a.getStatus()).isEqualTo(PenaltyAssessmentStatus.PROPOSED);
        assertThat(a.getReason()).isEqualTo(PenaltyReason.CHEQUE_RETURN);
        assertThat(a.getAmount()).isEqualByComparingTo("500");
        // SYSTEM: nobody decided this, a rule fired.
        assertThat(a.getProposedBy()).isNull();
        assertThat(a.getJournalId()).isNull();
        UUID linkedCheque = tx.execute(s -> assessments.findById(a.getId()).orElseThrow().getCheque().getId());
        assertThat(linkedCheque).isEqualTo(second);
    }

    @Test
    void autoProposalTurnedOffLeavesTheWorklistEmptyHoweverManyBounce() {
        fineSettings(1, false, false);
        PostLeaseResponse r = posted();
        bounceFirst(r);

        assertThat(assessmentRows()).isZero();
    }

    /**
     * The late-payment rule is gated twice: the landlord's switch and the
     * property's own penalty type. Both have to say yes.
     */
    @Test
    void lateClearProposalRespectsTheFlagAndGrace() {
        latePenalty(PenaltyType.FIXED_PER_DAY, "50", 5);
        PostLeaseResponse r = posted();
        UUID leaseId = r.lease().getId();
        List<ChequeDTO> cheques = r.cheques();

        // Flag off: late, and nothing is proposed.
        fineSettings(2, true, false);
        UUID first = cheques.get(0).id();
        chequeService.deposit(first, ChequeActionRequest.on(DEPOSIT_DATE));
        chequeService.clear(first, ChequeActionRequest.on(cheques.get(0).chequeDate().plusDays(30)));
        assertThat(assessmentRows()).isZero();

        // Flag on, but cleared inside the grace period: still nothing.
        fineSettings(2, true, true);
        UUID second = cheques.get(1).id();
        chequeService.deposit(second, ChequeActionRequest.on(DEPOSIT_DATE));
        chequeService.clear(second, ChequeActionRequest.on(cheques.get(1).chequeDate().plusDays(5)));
        assertThat(assessmentRows()).isZero();

        // Flag on and ten days past the grace period: one proposal, ten days at 50.
        UUID third = cheques.get(2).id();
        chequeService.deposit(third, ChequeActionRequest.on(DEPOSIT_DATE));
        chequeService.clear(third, ChequeActionRequest.on(cheques.get(2).chequeDate().plusDays(15)));

        List<PenaltyAssessment> raised = assessmentsOf(leaseId);
        assertThat(raised).hasSize(1);
        assertThat(raised.get(0).getReason()).isEqualTo(PenaltyReason.LATE_PAYMENT);
        assertThat(raised.get(0).getAmount()).isEqualByComparingTo("500");
        assertThat(raised.get(0).getProposedBy()).isNull();
    }

    // ------------------------------------------------------------------
    // who sees what
    // ------------------------------------------------------------------

    /**
     * A property manager assigned to one building must not read another building's
     * penalties — including through the total on a page they can see.
     */
    @Test
    void pmCannotSeeAnotherPropertysAssessments() {
        PostLeaseResponse mine = posted();
        PenaltyAssessmentDTO onMyProperty = proposal(mine.lease().getId(), null, PenaltyReason.OTHER, "400");

        // A second property with its own unit, renter, posted lease and proposal.
        Property other = fixtures.createProperty("OTH");
        Unit otherUnit = fixtures.createUnit(other, "202");
        Renter otherRenter = fixtures.createRenter("Other Renter");
        UUID otherLeaseId = leaseService.createDraftLease(fixtures.draftDto(
                otherUnit, otherRenter, START, END, List.of(line("RENT", "24000")))).getId();
        generation.generate(otherLeaseId, new com.datagami.rentaxis.api.dto.lease.GenerateChequesRequest(
                2, START, null, "Emirates NBD", null, false, null));
        posting.post(otherLeaseId);
        PenaltyAssessmentDTO onOtherProperty = proposal(otherLeaseId, null, PenaltyReason.OTHER, "700");

        User pm = user(UserRole.PROPERTY_MANAGER);
        UserPropertyAssignment assignment = new UserPropertyAssignment();
        assignment.setUserId(pm.getId());
        assignment.setPropertyId(fixtures.property().getId());
        assignmentRepo.save(assignment);

        // An admin sees both.
        assertThat(service.list(null, null, null, PageRequest.of(0, 50)).getTotalElements()).isEqualTo(2);

        authenticateAs(pm.getId(), "PROPERTY_MANAGER");
        Page<PenaltyAssessmentDTO> seen = service.list(null, null, null, PageRequest.of(0, 50));

        assertThat(seen.getTotalElements()).isEqualTo(1);
        assertThat(seen.getContent()).extracting(PenaltyAssessmentDTO::id).containsExactly(onMyProperty.id());

        // And cannot act on the one they cannot see.
        assertThatThrownBy(() -> service.approve(onOtherProperty.id(), APPROVE_DATE))
                .isInstanceOf(NotFoundException.class);
    }

    /** A manager assigned to nothing sees nothing, rather than everything. */
    @Test
    void aManagerWithNoAssignmentsSeesNothing() {
        PostLeaseResponse r = posted();
        proposal(r.lease().getId(), null, PenaltyReason.OTHER, "400");

        authenticateAs(user(UserRole.PROPERTY_MANAGER).getId(), "PROPERTY_MANAGER");

        assertThat(service.list(null, null, null, PageRequest.of(0, 50)).getTotalElements()).isZero();
    }

    /**
     * A proposal is finance deliberating about whether to charge the renter.
     * Showing it would turn "we are thinking about it" into "you owe this" — for
     * the ones that end up waived too.
     */
    @Test
    void renterSeesOnlyOwnApproved() {
        PostLeaseResponse r = posted();
        UUID leaseId = r.lease().getId();
        proposal(leaseId, null, PenaltyReason.OTHER, "111");
        PenaltyAssessmentDTO approved = service.approve(
                proposal(leaseId, null, PenaltyReason.OTHER, "222").id(), APPROVE_DATE);
        PenaltyAssessmentDTO waived = proposal(leaseId, null, PenaltyReason.OTHER, "333");
        service.waive(waived.id(), "Goodwill");

        // Another renter's penalty, on the same tenant.
        Unit otherUnit = fixtures.createUnit(fixtures.property(), "303");
        Renter otherRenter = fixtures.createRenter("Somebody Else");
        UUID otherLeaseId = leaseService.createDraftLease(fixtures.draftDto(
                otherUnit, otherRenter, START, END, List.of(line("RENT", "24000")))).getId();
        generation.generate(otherLeaseId, new com.datagami.rentaxis.api.dto.lease.GenerateChequesRequest(
                2, START, null, "Emirates NBD", null, false, null));
        posting.post(otherLeaseId);
        service.approve(proposal(otherLeaseId, null, PenaltyReason.OTHER, "999").id(), APPROVE_DATE);

        List<PenaltyAssessmentDTO> theirs = service.forRenter(fixtures.renter().getId());

        assertThat(theirs).extracting(PenaltyAssessmentDTO::id).containsExactly(approved.id());
        assertThat(theirs).allMatch(p -> p.status() == PenaltyAssessmentStatus.APPROVED);
    }

    // ------------------------------------------------------------------
    // proposing
    // ------------------------------------------------------------------

    @Test
    void proposingPostsNothing() {
        PostLeaseResponse r = posted();
        long entriesBefore = journalEntryRows();

        PenaltyAssessmentDTO proposed = proposal(r.lease().getId(), null, PenaltyReason.OTHER, "400");

        assertThat(proposed.status()).isEqualTo(PenaltyAssessmentStatus.PROPOSED);
        assertThat(journalEntryRows()).isEqualTo(entriesBefore);
        assertThat(proposed.propertyId()).isEqualTo(fixtures.property().getId());
        assertThat(proposed.renterId()).isEqualTo(fixtures.renter().getId());
    }

    /** A penalty hung off another lease's instrument would file under two contracts. */
    @Test
    void aChequeFromAnotherLeaseIsRefused() {
        PostLeaseResponse mine = posted();
        Unit otherUnit = fixtures.createUnit(fixtures.property(), "404");
        Renter otherRenter = fixtures.createRenter("Third Party");
        UUID otherLeaseId = leaseService.createDraftLease(fixtures.draftDto(
                otherUnit, otherRenter, START, END, List.of(line("RENT", "24000")))).getId();
        generation.generate(otherLeaseId, new com.datagami.rentaxis.api.dto.lease.GenerateChequesRequest(
                2, START, null, "Emirates NBD", null, false, null));
        PostLeaseResponse other = posting.post(otherLeaseId);

        assertThatThrownBy(() -> service.propose(new ProposePenaltyRequest(
                mine.lease().getId(), other.cheques().get(0).id(), PenaltyReason.CHEQUE_RETURN,
                new BigDecimal("500"), null), null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("does not belong to this lease");
    }

    @Test
    void anotherTenantsPenaltyDoesNotExist() {
        PostLeaseResponse r = posted();
        PenaltyAssessmentDTO proposed = proposal(r.lease().getId(), null, PenaltyReason.OTHER, "400");

        fixtures.newTenant();

        assertThatThrownBy(() -> service.approve(proposed.id(), APPROVE_DATE))
                .isInstanceOf(NotFoundException.class);
    }
}
