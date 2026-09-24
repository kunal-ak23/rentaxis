package com.datagami.rentaxis.core.service.penalty;

import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
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
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
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
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

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
class PenaltyAssessmentServiceIT extends AbstractPostgresIT {

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

    /**
     * The lease's own grace window — the field {@code ChequeMapper} hands
     * {@code ChequeDueRules}, and therefore the one the late-payment rule reads.
     * Set through SQL because the fixture builds its leases before a test knows
     * what it wants, and every service call reads the row fresh.
     */
    private void leaseGrace(UUID leaseId, int days) {
        jdbc.update("update leases set grace_period_days = ? where id = ?", days, leaseId);
    }

    /** Move a lease to a status the fixture cannot reach without running a termination. */
    private void leaseStatus(UUID leaseId, LeaseStatus status) {
        jdbc.update("update leases set status = ? where id = ?", status.name(), leaseId);
    }

    /**
     * The renter cannot be told, and the fine is charged anyway.
     *
     * <p>Approving posts a {@code PEN} and creates the collection row the fine is
     * paid through. Telling the renter is the last thing it does and the least
     * important: a notifications table that refuses the insert must not unwind a
     * charge finance has decided on.</p>
     *
     * <p>The trap this pins down is a specific one. The notification used to run in
     * the approval's own transaction, so a failed insert marked <em>that</em>
     * transaction rollback-only; the catch swallowed the exception and the approval
     * then died at commit with an {@code UnexpectedRollbackException} — a fine
     * nobody could charge because a notification failed. It runs in its own
     * {@code REQUIRES_NEW} transaction now, with the catch outside it.</p>
     */
    @Test
    void anApprovalSurvivesANotificationThatCannotBeWritten() {
        PostLeaseResponse r = posted();
        UUID leaseId = r.lease().getId();
        ChequeDTO bounced = bounceFirst(r);
        PenaltyAssessmentDTO proposed = proposal(leaseId, bounced.id(), PenaltyReason.CHEQUE_RETURN, "500");

        // Make the in-app row impossible to write, exactly as a bad migration or a
        // full disk would.
        // NOT VALID: other tests in this class share the container and may already
        // have written one of these rows. The constraint only has to stop the *next*
        // insert.
        jdbc.execute("alter table notifications add constraint no_penalty_incurred_it "
                + "check (type <> 'PENALTY_INCURRED') not valid");
        PenaltyAssessmentDTO approved;
        try {
            approved = service.approve(proposed.id(), APPROVE_DATE);
        } finally {
            jdbc.execute("alter table notifications drop constraint if exists no_penalty_incurred_it");
        }

        // The charge stands, whole: status, journal and the row it will be collected on.
        assertThat(approved.status()).isEqualTo(PenaltyAssessmentStatus.APPROVED);
        assertThat(approved.journalId()).isNotNull();
        assertThat(approved.collectionChequeId()).isNotNull();
        assertThat(entry(approved.journalId()).getDocType()).isEqualTo(JournalDocType.PEN);
        PenaltyAssessmentStatus persisted =
                tx.execute(s -> assessments.findById(proposed.id()).orElseThrow().getStatus());
        assertThat(persisted).isEqualTo(PenaltyAssessmentStatus.APPROVED);

        // And nothing was written for the renter, which is the whole point of the
        // failure being survivable rather than invisible.
        Long rows = jdbc.queryForObject(
                "select count(*) from notifications where tenant_id = ? and type = 'PENALTY_INCURRED'",
                Long.class, fixtures.tenantId());
        assertThat(rows).as("the notification really did fail to write").isZero();
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

    /** Σ of the lease's uncollected approved fines, read inside a transaction. */
    private BigDecimal outstanding(UUID leaseId) {
        return tx.execute(s -> service.outstandingForLease(leaseId));
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

    /** F14-28: a partial waiver reduces the proposal, keeps the first amount and the reason. */
    @Test
    void aProposalIsReducedWithAReasonAndKeepsItsFirstAmount() {
        PostLeaseResponse r = posted();
        PenaltyAssessmentDTO proposed = proposal(r.lease().getId(), null, PenaltyReason.OTHER, "650");
        assertThatThrownBy(() -> service.reduce(proposed.id(), new BigDecimal("300"), " "))
                .hasMessageContaining("A reduction needs a reason");
        assertThatThrownBy(() -> service.reduce(proposed.id(), new BigDecimal("650"), "goodwill"))
                .hasMessageContaining("less than 650.00");
        PenaltyAssessmentDTO reduced = service.reduce(proposed.id(), new BigDecimal("300"), "goodwill");
        assertThat(reduced.status()).isEqualTo(PenaltyAssessmentStatus.PROPOSED);
        assertThat(reduced.amount()).isEqualByComparingTo("300");
        assertThat(reduced.proposedAmount()).isEqualByComparingTo("650");
        assertThat(reduced.resolutionNote()).isEqualTo("Reduced from 650.00 to 300.00: goodwill");
    }

    /** R1 P2-3: a penalty reversal is not dated before the penalty was charged. */
    @Test
    void aReversalBeforeThePenaltyWasChargedIsRefused() {
        PostLeaseResponse r = posted();
        PenaltyAssessmentDTO approved = service.approve(
                proposal(r.lease().getId(), null, PenaltyReason.OTHER, "400").id(), APPROVE_DATE);
        assertThatThrownBy(() -> service.reverse(approved.id(), APPROVE_DATE.minusDays(1), "charged in error"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("cannot be dated before the penalty was charged");
        assertThat(reread(approved.id()).getStatus()).isEqualTo(PenaltyAssessmentStatus.APPROVED);
    }

    /** F14-28: reversing a charged penalty says why. */
    @Test
    void aReversalWithoutAReasonIsRefused() {
        PostLeaseResponse r = posted();
        PenaltyAssessmentDTO approved = service.approve(
                proposal(r.lease().getId(), null, PenaltyReason.OTHER, "400").id(), APPROVE_DATE);
        assertThatThrownBy(() -> service.reverse(approved.id(), REVERSE_DATE, ""))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("A reversal needs a reason");
        assertThat(reread(approved.id()).getStatus()).isEqualTo(PenaltyAssessmentStatus.APPROVED);
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
     * An APPROVED fine with no collection row of its own is still owed.
     *
     * <p>{@code sumOutstandingForLease}'s {@code p.collectionCheque is null} arm was
     * unreachable: dereferencing {@code p.collectionCheque.status} in the same
     * predicate makes Hibernate emit an <em>inner</em> join, which drops every row
     * the null test was written to catch. Harmless while every approval creates its
     * row in the same transaction — and exactly the shape a settlement must never
     * quietly drop, because a charge with no visible means of collection is the one
     * nobody will chase.</p>
     */
    @Test
    void anApprovedFineWithNoCollectionRowStillCountsAsOutstanding() {
        PostLeaseResponse r = posted();
        UUID leaseId = r.lease().getId();
        ChequeDTO bounced = bounceFirst(r);
        PenaltyAssessmentDTO proposed = proposal(leaseId, bounced.id(), PenaltyReason.CHEQUE_RETURN, "500");
        PenaltyAssessmentDTO approved = service.approve(proposed.id(), APPROVE_DATE);
        assertThat(outstanding(leaseId)).isEqualByComparingTo("500");

        // The link lost, the charge intact — a repaired row, a bad import, a future
        // flow that detaches the receipt.
        jdbc.update("update penalty_assessments set collection_cheque_id = null where id = ?", approved.id());

        assertThat(outstanding(leaseId))
                .as("a fine nobody can point a receipt at is still a fine the renter owes")
                .isEqualByComparingTo("500");
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
        ChequeDTO secondRow = r.cheques().get(1);
        UUID second = secondRow.id();
        // Banked on its own date: this row falls due a quarter after the first,
        // and a post-dated cheque may not be presented before its date.
        chequeService.deposit(second, ChequeActionRequest.on(secondRow.chequeDate()));
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

    /**
     * F14-22 ruling: a TECHNICAL_RETURN is the bank's error — no fee, and it does not
     * count toward the threshold. With a threshold of two, a technical return followed
     * by a stopped payment is still only one of the renter's bounces.
     */
    @Test
    void aTechnicalReturnIsNotFinedAndDoesNotCountTowardTheThreshold() {
        fineSettings(2, true, false);
        PostLeaseResponse r = posted();
        UUID leaseId = r.lease().getId();

        UUID first = r.cheques().get(0).id();
        chequeService.deposit(first, ChequeActionRequest.on(DEPOSIT_DATE));
        chequeService.bounce(first,
                new ChequeActionRequest(BOUNCE_DATE, null, ChequeFailureReason.TECHNICAL_RETURN, null));
        assertThat(assessmentRows()).isZero();

        ChequeDTO secondRow = r.cheques().get(1);
        chequeService.deposit(secondRow.id(), ChequeActionRequest.on(secondRow.chequeDate()));
        chequeService.bounce(secondRow.id(),
                new ChequeActionRequest(BOUNCE_DATE, null, ChequeFailureReason.STOPPED_PAYMENT, null));

        assertThat(assessmentRows()).isZero();
        assertThat((Long) tx.execute(s -> chequeRepo.countByLease_IdAndBouncedAtIsNotNull(leaseId))).isEqualTo(2L);
        assertThat((Long) tx.execute(s -> chequeRepo.countPenalisableBounces(leaseId))).isEqualTo(1L);
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
        // The property's own grace is 30 days and is deliberately never the answer:
        // it is a property-wide default, while the register flags this row overdue by
        // the lease's window. RentCollectionSettings still supplies the rate.
        latePenalty(PenaltyType.FIXED_PER_DAY, "50", 30);
        PostLeaseResponse r = posted();
        UUID leaseId = r.lease().getId();
        leaseGrace(leaseId, 5);
        List<ChequeDTO> cheques = r.cheques();

        // Flag off: six days late by the lease's grace, and nothing is proposed.
        fineSettings(2, true, false);
        UUID first = cheques.get(0).id();
        chequeService.deposit(first, ChequeActionRequest.on(cheques.get(0).chequeDate()));
        chequeService.clear(first, ChequeActionRequest.on(cheques.get(0).chequeDate().plusDays(6)));
        assertThat(assessmentRows()).isZero();

        // Flag on, cleared on the last acceptable day (chequeDate + 5): still nothing.
        fineSettings(2, true, true);
        UUID second = cheques.get(1).id();
        chequeService.deposit(second, ChequeActionRequest.on(cheques.get(1).chequeDate()));
        chequeService.clear(second, ChequeActionRequest.on(cheques.get(1).chequeDate().plusDays(5)));
        assertThat(assessmentRows()).isZero();

        // One day past it: one proposal, one day at 50.
        UUID third = cheques.get(2).id();
        chequeService.deposit(third, ChequeActionRequest.on(cheques.get(2).chequeDate()));
        chequeService.clear(third, ChequeActionRequest.on(cheques.get(2).chequeDate().plusDays(6)));

        List<PenaltyAssessment> raised = assessmentsOf(leaseId);
        assertThat(raised).hasSize(1);
        assertThat(raised.get(0).getReason()).isEqualTo(PenaltyReason.LATE_PAYMENT);
        assertThat(raised.get(0).getAmount()).isEqualByComparingTo("50");
        assertThat(raised.get(0).getProposedBy()).isNull();
    }

    /**
     * The property's {@code gracePeriodDays} is ignored outright.
     *
     * <p>Ten days after the cheque date is five days late by the lease's window and
     * not late at all by the property's thirty. Reading the property's column would
     * propose nothing here — and, on a property configured the other way round,
     * would fine a renter for days the register never called late.</p>
     */
    @Test
    void theLateFeeUsesTheLeasesGraceNotThePropertys() {
        latePenalty(PenaltyType.FIXED_PER_DAY, "50", 30);
        fineSettings(2, true, true);
        PostLeaseResponse r = posted();
        UUID leaseId = r.lease().getId();
        leaseGrace(leaseId, 5);
        ChequeDTO first = r.cheques().get(0);

        chequeService.deposit(first.id(), ChequeActionRequest.on(DEPOSIT_DATE));
        chequeService.clear(first.id(), ChequeActionRequest.on(first.chequeDate().plusDays(10)));

        List<PenaltyAssessment> raised = assessmentsOf(leaseId);
        assertThat(raised).hasSize(1);
        assertThat(raised.get(0).getAmount()).isEqualByComparingTo("250");
    }

    /**
     * Cash taken over the counter reaches CLEARED through {@code receive}, not
     * {@code clear}. The late fee must not depend on which door the renter paid
     * through.
     */
    @Test
    void aLateCashReceiptProposesTheSameLateFee() {
        latePenalty(PenaltyType.FIXED_PER_DAY, "50", 30);
        fineSettings(2, true, true);
        PostLeaseResponse r = posted();
        UUID leaseId = r.lease().getId();
        leaseGrace(leaseId, 5);

        LocalDate expected = LocalDate.of(2026, 11, 2);
        ChequeDTO cash = chequeService.addRowToPostedLease(leaseId, new ChequeRowInput(
                null, null, expected, null, expected, null, null, null,
                new BigDecimal("1500"), "Counter receipt", ChequeMode.CASH));

        chequeService.receive(cash.id(), ChequeActionRequest.on(expected.plusDays(8)));

        List<PenaltyAssessment> raised = assessmentsOf(leaseId);
        assertThat(raised).hasSize(1);
        assertThat(raised.get(0).getReason()).isEqualTo(PenaltyReason.LATE_PAYMENT);
        // 2 Nov + 5 days grace = 7 Nov; paid on the 10th is 3 days at 50.
        assertThat(raised.get(0).getAmount()).isEqualByComparingTo("150");
    }

    /**
     * A gateway retrying its webhook must not put a second fine on the worklist any
     * more than it may post a second CRT — the idempotent return sits above the
     * hook.
     */
    @Test
    void aLateOnlineCaptureProposesOnceEvenWhenDeliveredTwice() {
        latePenalty(PenaltyType.FIXED_PER_DAY, "50", 30);
        // Cheque-return proposals off, so the bounce that opens the gateway door
        // cannot contribute a second row and blur the count.
        fineSettings(2, false, true);
        PostLeaseResponse r = posted();
        UUID leaseId = r.lease().getId();
        leaseGrace(leaseId, 5);

        UUID bounced = r.cheques().get(0).id();
        chequeService.deposit(bounced, ChequeActionRequest.on(DEPOSIT_DATE));
        chequeService.bounce(bounced,
                new ChequeActionRequest(BOUNCE_DATE, null, ChequeFailureReason.BOUNCE, null));
        assertThat(assessmentRows()).isZero();

        LocalDate rowDate = LocalDate.of(2026, 11, 2);
        ChequeDTO online = chequeService.replaceForOnlinePayment(bounced, rowDate);
        chequeService.registerOnlinePending(online.id());

        LocalDate captured = rowDate.plusDays(9);
        chequeService.clearOnline(online.id(), captured, null);
        chequeService.clearOnline(online.id(), captured, null);

        List<PenaltyAssessment> raised = assessmentsOf(leaseId);
        assertThat(raised).hasSize(1);
        assertThat(raised.get(0).getReason()).isEqualTo(PenaltyReason.LATE_PAYMENT);
        // 2 Nov + 5 = 7 Nov, captured on the 11th: 4 days at 50.
        assertThat(raised.get(0).getAmount()).isEqualByComparingTo("200");
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
        generation.generateNumbers(otherLeaseId, LeaseTestFixtures.nextChequeBook());
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
        generation.generateNumbers(otherLeaseId, LeaseTestFixtures.nextChequeBook());
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

    /**
     * #12: a penalty a person raises from the lease page — a category, an amount,
     * the day it happened and a narration — is the same PROPOSED row the rule
     * engine writes, posts nothing, and becomes a charge only through the same
     * approval, whose PEN is written by PostingService.
     */
    @Test
    void aStaffRaisedPenaltyTakesTheSameProposalAndApprovalPath() {
        PostLeaseResponse r = posted();
        UUID leaseId = r.lease().getId();
        LocalDate incident = LocalDate.now().minusDays(3);
        long entriesBefore = journalEntryRows();

        PenaltyAssessmentDTO raised = service.propose(new ProposePenaltyRequest(
                leaseId, null, PenaltyReason.OTHER, new BigDecimal("750.00"), "Damaged lobby door", incident),
                fixtures.renter().getUserId());

        assertThat(raised.status()).isEqualTo(PenaltyAssessmentStatus.PROPOSED);
        assertThat(raised.incidentDate()).isEqualTo(incident);
        assertThat(raised.description()).isEqualTo("Damaged lobby door");
        assertThat(reread(raised.id()).getIncidentDate()).isEqualTo(incident);
        assertThat(journalEntryRows()).isEqualTo(entriesBefore);

        PenaltyAssessmentDTO approved = service.approve(raised.id(), APPROVE_DATE);

        assertThat(approved.status()).isEqualTo(PenaltyAssessmentStatus.APPROVED);
        assertThat(entryCount(JournalDocType.PEN, raised.id())).isEqualTo(1);
        List<JournalLine> pen = linesOf(approved.journalId());
        assertThat(pen.get(1).getAccountId()).isEqualTo(leaf(AccountRole.OTHER_INCOME).getId());
        assertThat(pen.get(1).getCredit()).isEqualByComparingTo("750.00");
    }

    @Test
    void aPenaltyCannotBeRaisedForAFutureDate() {
        PostLeaseResponse r = posted();
        long before = assessmentRows();

        assertThatThrownBy(() -> service.propose(new ProposePenaltyRequest(
                r.lease().getId(), null, PenaltyReason.OTHER, new BigDecimal("100"), "Noise",
                LocalDate.now().plusDays(1)), null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("future");
        assertThat(assessmentRows()).isEqualTo(before);
    }

    /** Web review M5: an incident cannot predate the contract (a 1990 typo). */
    @Test
    void aPenaltyCannotBeRaisedForADateBeforeTheContract() {
        PostLeaseResponse r = posted();
        long before = assessmentRows();

        assertThatThrownBy(() -> service.propose(new ProposePenaltyRequest(
                r.lease().getId(), null, PenaltyReason.OTHER, new BigDecimal("100"), "Noise",
                CONTRACT_DATE.minusDays(1)), null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("before the contract");
        assertThat(assessmentRows()).isEqualTo(before);

        // The contract date itself is fine: an advance cheque can bounce before move-in.
        assertThat(service.propose(new ProposePenaltyRequest(
                r.lease().getId(), null, PenaltyReason.OTHER, new BigDecimal("100"), "Noise",
                CONTRACT_DATE), null).incidentDate()).isEqualTo(CONTRACT_DATE);
    }

    /** Leaving the date out is today, not "unknown". */
    @Test
    void aRaisedPenaltyWithNoDateIsDatedToday() {
        PostLeaseResponse r = posted();

        PenaltyAssessmentDTO raised = service.propose(new ProposePenaltyRequest(
                r.lease().getId(), null, PenaltyReason.LATE_PAYMENT, new BigDecimal("100"), null), null);

        assertThat(raised.incidentDate()).isEqualTo(LocalDate.now());
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
        generation.generateNumbers(otherLeaseId, LeaseTestFixtures.nextChequeBook());
        PostLeaseResponse other = posting.post(otherLeaseId);

        assertThatThrownBy(() -> service.propose(new ProposePenaltyRequest(
                mine.lease().getId(), other.cheques().get(0).id(), PenaltyReason.CHEQUE_RETURN,
                new BigDecimal("500"), null), null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("does not belong to this lease");
    }

    /**
     * A proposal can outlive the contract it was raised on — a cheque bounces in
     * March, the lease terminates in April, finance reaches the worklist in May.
     * By then the answer is "settle it", not "charge it": the terminated lease has
     * had its uncleared instruments handed back and its unearned rent reversed, so
     * a PEN would reopen a receivable the settlement just closed and there is no
     * register to collect it through.
     *
     * <p>Refused <em>before</em> anything posts, not half way through when
     * {@code addRowToPostedLease} balks — by then the PEN has a number.</p>
     */
    @Test
    void approvingAgainstATerminatedLeaseIsRefusedAndPostsNothing() {
        PostLeaseResponse r = posted();
        UUID leaseId = r.lease().getId();
        PenaltyAssessmentDTO proposed = proposal(leaseId, null, PenaltyReason.CHEQUE_RETURN, "500");
        leaseStatus(leaseId, LeaseStatus.TERMINATED);
        long entriesBefore = journalEntryRows();
        long chequesBefore = registerSize(leaseId);

        assertThatThrownBy(() -> service.approve(proposed.id(), APPROVE_DATE))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Lease is TERMINATED; charge this penalty through settlement");

        PenaltyAssessment unchanged = reread(proposed.id());
        assertThat(unchanged.getStatus()).isEqualTo(PenaltyAssessmentStatus.PROPOSED);
        assertThat(unchanged.getJournalId()).isNull();
        assertThat(entryCount(JournalDocType.PEN, proposed.id())).isZero();
        assertThat(journalEntryRows()).isEqualTo(entriesBefore);
        assertThat(registerSize(leaseId)).isEqualTo(chequesBefore);
    }

    /**
     * A tenancy that simply ran out is still chargeable, and its collection row is
     * raised through the internal door rather than the public grid.
     *
     * <p>Review I2: shaping the grid of an ended contract is a live lease's
     * privilege, so {@code addRowToPostedLease} stopped admitting EXPIRED — and
     * approving a penalty on an expired lease must go on working, because
     * {@code CHARGEABLE} contains EXPIRED and always has. The same door Task 6
     * opened for the settlement's balance-due row serves this: a row raised by the
     * system against a contract that has ended, never one a user typed.</p>
     *
     * <p>The row is ordinary in every other way, which is the half that matters —
     * the renter can pay the fine.</p>
     */
    @Test
    void approvingAgainstAnExpiredLeaseRaisesACollectionRowThatCanBeCollected() {
        PostLeaseResponse r = posted();
        UUID leaseId = r.lease().getId();
        PenaltyAssessmentDTO proposed = proposal(leaseId, null, PenaltyReason.CHEQUE_RETURN, "500");
        leaseStatus(leaseId, LeaseStatus.EXPIRED);
        long chequesBefore = registerSize(leaseId);

        PenaltyAssessmentDTO approved = service.approve(proposed.id(), APPROVE_DATE);

        assertThat(approved.status()).isEqualTo(PenaltyAssessmentStatus.APPROVED);
        assertThat(registerSize(leaseId)).as("one collection row").isEqualTo(chequesBefore + 1);
        // Read inside a transaction: collectionCheque is a lazy association.
        Cheque collection = tx.execute(s -> {
            PenaltyAssessment saved = assessments.findById(proposed.id()).orElseThrow();
            assertThat(saved.getJournalId()).as("the PEN").isNotNull();
            Cheque row = saved.getCollectionCheque();
            assertThat(row).isNotNull();
            assertThat(row.getStatus()).isEqualTo(ChequeStatus.REGISTERED);
            assertThat(row.getMode()).isEqualTo(ChequeMode.CASH);
            assertThat(row.getPdrJournalId()).as("registered like any other row").isNotNull();
            return row;
        });

        // …and the fine is actually collectable on the expired lease.
        ChequeDTO received = chequeService.receive(collection.getId(),
                ChequeActionRequest.on(APPROVE_DATE));
        assertThat(received.status()).isEqualTo(ChequeStatus.CLEARED);
    }

    /**
     * The public grid door is shut on the same lease, which is the other half of
     * review I2: a finance user may not type a new instalment onto a contract whose
     * term has run out. Money owed on an expired tenancy is collected through its
     * settlement (spec §9.2), which has its own door.
     */
    @Test
    void anExpiredLeaseTakesNoTypedGridRow() {
        PostLeaseResponse r = posted();
        UUID leaseId = r.lease().getId();
        leaseStatus(leaseId, LeaseStatus.EXPIRED);
        long chequesBefore = registerSize(leaseId);

        assertThatThrownBy(() -> chequeService.addRowToPostedLease(leaseId, new ChequeRowInput(
                null, null, APPROVE_DATE, null, APPROVE_DATE, null, null, null,
                new BigDecimal("500"), "Typed in", ChequeMode.CASH)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("This lease is EXPIRED")
                .hasMessageContaining("cheque grid");

        assertThat(registerSize(leaseId)).isEqualTo(chequesBefore);
    }

    /** And there is no raising a fresh one against it either. */
    @Test
    void proposingAgainstATerminatedLeaseIsRefused() {
        PostLeaseResponse r = posted();
        UUID leaseId = r.lease().getId();
        leaseStatus(leaseId, LeaseStatus.TERMINATED);

        assertThatThrownBy(() -> proposal(leaseId, null, PenaltyReason.CHEQUE_RETURN, "500"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Lease is TERMINATED; charge this penalty through settlement");

        assertThat(assessmentRows()).isZero();
    }

    /**
     * The fine collected, through the ordinary receipt path.
     *
     * <p>This is what the collection row is for: the renter pays the penalty and it
     * clears exactly as an instalment does — {@code CRT} Dr cash / Cr PDC
     * receivable — with the income already recognised at approval and left alone.
     * If the fine needed its own collection treatment, the register would have to
     * know what a penalty is.</p>
     */
    @Test
    void collectingTheCollectionRowPostsAnOrdinaryCrt() {
        PostLeaseResponse r = posted();
        UUID leaseId = r.lease().getId();
        Account cash = leaf(AccountRole.CASH);
        Account pdc = leaf(AccountRole.PDC_RECEIVABLE);
        Account income = leaf(AccountRole.CHEQUE_RETURN_PENALTY);

        BigDecimal cashBefore = balance(cash, leaseId);
        BigDecimal pdcBefore = balance(pdc, leaseId);

        PenaltyAssessmentDTO approved = service.approve(
                proposal(leaseId, null, PenaltyReason.CHEQUE_RETURN, "500").id(), APPROVE_DATE);
        BigDecimal incomeAfterApproval = balance(income, leaseId);
        assertThat(balance(pdc, leaseId)).isEqualByComparingTo(pdcBefore.add(new BigDecimal("500")));

        ChequeDTO collected = chequeService.receive(approved.collectionChequeId(),
                ChequeActionRequest.on(APPROVE_DATE));

        assertThat(collected.status()).isEqualTo(ChequeStatus.CLEARED);
        JournalEntry crt = entry(collected.crtJournalId());
        assertThat(crt.getDocType()).isEqualTo(JournalDocType.CRT);
        List<JournalLine> crtLines = linesOf(crt.getId());
        assertThat(crtLines).hasSize(2);
        assertThat(crtLines.get(0).getAccountId()).isEqualTo(cash.getId());
        assertThat(crtLines.get(0).getDebit()).isEqualByComparingTo("500");
        assertThat(crtLines.get(1).getAccountId()).isEqualTo(pdc.getId());
        assertThat(crtLines.get(1).getCredit()).isEqualByComparingTo("500");

        assertThat(balance(cash, leaseId)).isEqualByComparingTo(cashBefore.add(new BigDecimal("500")));
        // The row's PDC is back to where it started; the income was recognised when
        // finance approved the fine and collecting it does not touch that.
        assertThat(balance(pdc, leaseId)).isEqualByComparingTo(pdcBefore);
        assertThat(balance(income, leaseId)).isEqualByComparingTo(incomeAfterApproval);
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
