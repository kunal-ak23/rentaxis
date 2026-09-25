package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.lease.AssignLeaseRequest;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.lease.LeaseAssignmentDTO;
import com.datagami.rentaxis.api.dto.ledger.TrialBalanceRowDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.recognition.RecognitionService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.JournalLine;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F14-39: a lease assigned to another renter — same unit, number, deposit and
 * cheques; the outgoing renter's sub-ledger balances move in one journal.
 *
 * <p>Fixture: 51,000 of rent 24/09/2026 → 23/09/2027 with a 3,000 deposit, a deposit
 * cheque and four quarterly rent cheques of 12,750. The deposit and the first two
 * rent cheques have cleared; July and April are still in the drawer.</p>
 */
@SpringBootTest
class LeaseAssignmentIT extends AbstractPostgresIT {

    @Autowired LeaseAssignmentService assignments;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired ChequeService chequeService;
    @Autowired RecognitionService recognition;
    @Autowired LeaseService leaseService;
    @Autowired LedgerQueryService ledger;
    @Autowired AccountResolver resolver;
    @Autowired AccountService accountService;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LeaseRepository leaseRepo;
    @Autowired ChequeRepository chequeRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired JournalLineRepository journalLines;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private LeaseTestFixtures fixtures;

    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 9, 16);
    private static final LocalDate START = LocalDate.of(2026, 9, 24);
    private static final LocalDate END = LocalDate.of(2027, 9, 23);
    private static final LocalDate RENT_1 = LocalDate.of(2026, 10, 2);
    private static final LocalDate RENT_2 = LocalDate.of(2027, 1, 2);
    private static final LocalDate RENT_3 = LocalDate.of(2027, 4, 2);
    private static final LocalDate RENT_4 = LocalDate.of(2027, 7, 2);
    private static final LocalDate ON = LocalDate.of(2027, 2, 1);

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, chequeGeneration, posting);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    @Test
    void theOutgoingRentersBalancesMoveInOneJournalAndTheOpenChequesFollow() {
        UUID leaseId = lease(true);
        Renter a = fixtures.renter();
        Renter b = fixtures.createRenter("Estate of the late tenant");
        BigDecimal rrBefore = balanceOf(AccountRole.RENT_RECEIVABLE, leaseId, a.getId());

        LeaseAssignmentDTO draft = assignments.draft(leaseId, new AssignLeaseRequest(b.getId(), ON, "Death of the tenant", null));
        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(draft.overdue()).isEmpty();
        assertThat(draft.chequesMoving()).isEqualTo(2);
        assertThat(draft.balances()).extracting(LeaseAssignmentDTO.Balance::amount).contains(
                new BigDecimal("12750.00"), new BigDecimal("-3000.00"), new BigDecimal("-51000.00"));
        assertThat(draft.balances()).hasSize(4);   // two PDCs by cheque, the deposit, unearned rent — no bank
        assertThat(balanceOf(AccountRole.PDC_RECEIVABLE, leaseId, b.getId())).as("a draft moves nothing").isZero();

        LeaseAssignmentDTO posted = assignments.post(leaseId, draft.id(), null);
        assertThat(posted.status()).isEqualTo("POSTED");
        assertThat(posted.journalNumber()).startsWith("JV");
        List<JournalLine> jv = tx.execute(s -> journalLines.findByEntry_IdOrderByLineNoAsc(posted.journalId()));
        assertThat(jv).allSatisfy(l -> assertThat(l.getLeaseId()).isEqualTo(leaseId));
        assertThat(jv.stream().filter(l -> l.getDebit().signum() > 0).map(JournalLine::getRenterId).distinct())
                .containsExactlyInAnyOrder(a.getId(), b.getId());

        // A's sub-ledger on the lease is empty; B holds what A held.
        for (AccountRole role : List.of(AccountRole.RENT_RECEIVABLE, AccountRole.PDC_RECEIVABLE,
                AccountRole.SECURITY_DEPOSIT, AccountRole.ADVANCE_RENT)) {
            assertThat(balanceOf(role, leaseId, a.getId())).as(role + " for A").isZero();
        }
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, leaseId, b.getId())).isEqualByComparingTo(rrBefore);
        assertThat(balanceOf(AccountRole.PDC_RECEIVABLE, leaseId, b.getId())).isEqualByComparingTo("25500");
        assertThat(balanceOf(AccountRole.SECURITY_DEPOSIT, leaseId, b.getId())).isEqualByComparingTo("-3000");

        // Same lease, same number, same cheques — B's now, the drawer as written.
        var lease = tx.execute(s -> leaseRepo.findById(leaseId).orElseThrow());
        assertThat(renterOf(leaseId)).isEqualTo(b.getId());
        assertThat(lease.getRenterAcceptedAt()).isNull();
        Cheque april = chequeOn(leaseId, RENT_3);
        assertThat(april.getRenter().getId()).isEqualTo(b.getId());
        assertThat(april.getPayerName()).isEqualTo(a.getNameEn());
        assertThat(chequeOn(leaseId, RENT_1).getRenter().getId()).as("cleared history stays A's").isEqualTo(a.getId());
        assertThat(unitHolder())
                .isEqualTo(b.getNameEn());

        // B's cheque clears on B's sub-ledger, and the rest of the term is earned on B's.
        chequeService.deposit(april.getId(), ChequeActionRequest.on(RENT_3));
        chequeService.clear(april.getId(), ChequeActionRequest.on(RENT_3));
        recognition.runTo(END, false);
        assertThat(balanceOf(AccountRole.PDC_RECEIVABLE, leaseId, a.getId())).isZero();
        assertThat(balanceOf(AccountRole.PDC_RECEIVABLE, leaseId, b.getId())).isEqualByComparingTo("12750");
        // Per instrument too: each PDC moved on its own cheque's line, so the cleared
        // April instrument is nil for both renters and July's 12,750 is B's alone.
        UUID pdc = tx.execute(s -> resolver.resolve(AccountRole.PDC_RECEIVABLE, fixtures.property().getId())).getId();
        String byCheque = "select coalesce(sum(debit), 0) - coalesce(sum(credit), 0) from journal_lines"
                + " where lease_id = ? and account_id = ? and cheque_id = ? and renter_id = ?";
        assertThat(jdbc.queryForObject(byCheque, BigDecimal.class, leaseId, pdc, april.getId(), a.getId())).isZero();
        assertThat(jdbc.queryForObject(byCheque, BigDecimal.class, leaseId, pdc, april.getId(), b.getId())).isZero();
        assertThat(jdbc.queryForObject(byCheque, BigDecimal.class, leaseId, pdc, chequeOn(leaseId, RENT_4).getId(), b.getId()))
                .isEqualByComparingTo("12750");
        assertThat(balanceOf(AccountRole.ADVANCE_RENT, leaseId, b.getId())).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select count(*) from lease_events where lease_id = ? and notes like 'Assigned from %'",
                Long.class, leaseId)).isEqualTo(1);
        assertTrialBalanceBalances();
    }

    @Test
    void overdueItemsAreRefusedUnlessTheIncomingRenterTakesThemOn() {
        UUID leaseId = lease(false);   // the deposit is banked; October's cheque is still in the drawer on 01/12
        Renter b = fixtures.createRenter("Novated Co LLC");
        LocalDate december = LocalDate.of(2026, 12, 1);
        assertThatThrownBy(() -> assignments.draft(leaseId, new AssignLeaseRequest(b.getId(), december, "Novation", null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .extracting(e -> ((BusinessRuleViolationException) e).getCode()).isEqualTo("lease.assignmentOverdue");
        LeaseAssignmentDTO draft = assignments.draft(leaseId, new AssignLeaseRequest(b.getId(), december, "Novation", true));
        assertThat(draft.overdue()).extracting(LeaseAssignmentDTO.Overdue::chequeDate).containsExactly(RENT_1);
        assignments.post(leaseId, draft.id(), null);
        assertThat(chequeOn(leaseId, RENT_1).getRenter().getId()).isEqualTo(b.getId());
        assertTrialBalanceBalances();
    }

    @Test
    void refusals() {
        UUID leaseId = lease(true);
        Renter a = fixtures.renter();
        Renter b = fixtures.createRenter("Heir");
        assertThatThrownBy(() -> assignments.draft(leaseId, new AssignLeaseRequest(a.getId(), ON, "x", null)))
                .extracting(e -> ((BusinessRuleViolationException) e).getCode()).isEqualTo("lease.assignmentSameRenter");
        assertThatThrownBy(() -> assignments.draft(leaseId, new AssignLeaseRequest(b.getId(), ON, " ", null)))
                .extracting(e -> ((BusinessRuleViolationException) e).getCode()).isEqualTo("lease.assignmentReason");
        assertThatThrownBy(() -> assignments.draft(leaseId, new AssignLeaseRequest(b.getId(), END.plusDays(1), "x", null)))
                .hasMessageContaining("within the tenancy");
        LeaseAssignmentDTO draft = assignments.draft(leaseId, new AssignLeaseRequest(b.getId(), ON, "Heir", null));
        assertThatThrownBy(() -> assignments.draft(leaseId, new AssignLeaseRequest(b.getId(), ON, "Heir", null)))
                .extracting(e -> ((BusinessRuleViolationException) e).getCode()).isEqualTo("lease.assignmentDraftExists");
        assignments.cancel(leaseId, draft.id());
        assertThatThrownBy(() -> assignments.post(leaseId, draft.id(), null)).hasMessageContaining("CANCELLED");
        assertThat(renterOf(leaseId)).isEqualTo(a.getId());
    }

    @Test
    void anUnassignedPropertyManagerCannotAssign() {
        UUID leaseId = lease(true);
        Renter b = fixtures.createRenter("Heir");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(UUID.randomUUID().toString(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_PROPERTY_MANAGER"))));
        assertThatThrownBy(() -> assignments.draft(leaseId, new AssignLeaseRequest(b.getId(), ON, "Heir", null)))
                .isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------

    private UUID lease(boolean clearFirstTwo) {
        UUID leaseId = fixtures.draftLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000"), line("SECURITY_DEPOSIT", "3000")));
        chequeGeneration.saveRows(leaseId, List.of(
                row("500040", CONTRACT_DATE, CONTRACT_DATE, "3000"),
                row("500041", CONTRACT_DATE, RENT_1, "12750"),
                row("500042", CONTRACT_DATE, RENT_2, "12750"),
                row("500043", CONTRACT_DATE, RENT_3, "12750"),
                row("500044", CONTRACT_DATE, RENT_4, "12750")));
        posting.post(leaseId);
        for (LocalDate d : clearFirstTwo ? List.of(CONTRACT_DATE, RENT_1, RENT_2) : List.of(CONTRACT_DATE)) {
            {
                Cheque c = chequeOn(leaseId, d);
                chequeService.deposit(c.getId(), ChequeActionRequest.on(d));
                chequeService.clear(c.getId(), ChequeActionRequest.on(d));
            }
        }
        return leaseId;
    }

    private static ChequeRowInput row(String number, LocalDate postingDate, LocalDate chequeDate, String amount) {
        return new ChequeRowInput(null, null, postingDate, number, chequeDate, "Emirates NBD",
                null, null, new BigDecimal(amount), null, null);
    }

    private Cheque chequeOn(UUID leaseId, LocalDate chequeDate) {
        return tx.execute(s -> {
            Cheque c = chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId).stream()
                    .filter(x -> chequeDate.equals(x.getChequeDate())).findFirst().orElseThrow();
            c.getRenter().getNameEn();
            return c;
        });
    }

    private BigDecimal balanceOf(AccountRole role, UUID leaseId, UUID renterId) {
        UUID id = tx.execute(s -> resolver.resolve(role, fixtures.property().getId())).getId();
        return tx.execute(s -> ledger.accountLedger(id,
                new LedgerQueryService.LedgerFilter(null, null, null, null, leaseId, renterId)).closingBalance());
    }

    private UUID renterOf(UUID leaseId) {
        return tx.execute(s -> leaseRepo.findById(leaseId).orElseThrow().getRenter().getId());
    }

    private String unitHolder() {
        return tx.execute(s -> unitRepo.findById(fixtures.unit().getId()).orElseThrow().getCurrentTenantName());
    }

    private void assertTrialBalanceBalances() {
        List<TrialBalanceRowDTO> rows = tx.execute(s -> ledger.trialBalance(LocalDate.of(2030, 1, 1), null));
        BigDecimal debit = rows.stream().map(TrialBalanceRowDTO::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = rows.stream().map(TrialBalanceRowDTO::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debit).as("trial balance").isEqualByComparingTo(credit);
    }
}
