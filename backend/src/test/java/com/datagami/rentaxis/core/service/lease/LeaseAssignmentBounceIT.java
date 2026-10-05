package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.lease.AssignLeaseRequest;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.lease.LeaseAssignmentDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.core.service.ledger.PostingRequest.cr;
import static com.datagami.rentaxis.core.service.ledger.PostingRequest.dr;
import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR #399 R1 P3-1: a lease assignment's "overdue instalments" check applies the shared due
 * rule — a returned cheque counts only for what the lease's receivable still carries.
 *
 * <p>Not calendar-dependent: the effective date is fixed inside the term, and
 * {@link com.datagami.rentaxis.testsupport.LaterBusinessDayConfig} puts "today" years after it,
 * so the fixed timeline is always in the past.</p>
 */
@org.springframework.context.annotation.Import(com.datagami.rentaxis.testsupport.LaterBusinessDayConfig.class)
@SpringBootTest
class LeaseAssignmentBounceIT extends AbstractPostgresIT {

    @Autowired LeaseAssignmentService assignments;
    @Autowired LeasePostingService leasePosting;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired ChequeService chequeService;
    @Autowired LeaseService leaseService;
    @Autowired PostingService posting;
    @Autowired AccountService accountService;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired ChequeRepository chequeRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired TransactionTemplate tx;

    private LeaseTestFixtures fixtures;

    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 9, 16);
    private static final LocalDate START = LocalDate.of(2026, 9, 24);
    private static final LocalDate END = LocalDate.of(2027, 9, 23);
    private static final LocalDate RENT_1 = LocalDate.of(2026, 10, 2);
    /** Fixed, inside the term, after RENT_1 bounced and before RENT_2 is due. */
    private static final LocalDate ON = LocalDate.of(2026, 10, 20);

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, chequeGeneration, leasePosting);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    /** Deposit cheque cleared; RENT_1 (12,750) deposited and returned on 5 October. */
    private UUID leaseWithABounce() {
        UUID leaseId = fixtures.draftLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000"), line("SECURITY_DEPOSIT", "3000")));
        chequeGeneration.saveRows(leaseId, List.of(
                row("520040", CONTRACT_DATE, "3000"),
                row("520041", RENT_1, "12750"),
                row("520042", LocalDate.of(2027, 1, 2), "12750"),
                row("520043", LocalDate.of(2027, 4, 2), "12750"),
                row("520044", LocalDate.of(2027, 7, 2), "12750")));
        leasePosting.post(leaseId);
        Cheque deposit = chequeOn(leaseId, CONTRACT_DATE);
        chequeService.deposit(deposit.getId(), ChequeActionRequest.on(CONTRACT_DATE));
        chequeService.clear(deposit.getId(), ChequeActionRequest.on(CONTRACT_DATE));
        Cheque rent1 = chequeOn(leaseId, RENT_1);
        chequeService.deposit(rent1.getId(), ChequeActionRequest.on(RENT_1));
        chequeService.bounce(rent1.getId(), ChequeActionRequest.on(LocalDate.of(2026, 10, 5)));
        return leaseId;
    }

    /** Money received against the lease's receivable that is not a replacement (cash at the office). */
    private void receive(UUID leaseId, String amount) {
        UUID propertyId = fixtures.property().getId();
        BigDecimal a = new BigDecimal(amount);
        tx.executeWithoutResult(s -> posting.post(new PostingRequest(JournalDocType.JV, LocalDate.of(2026, 10, 10),
                "cash for the returned cheque", new PostingRequest.Dimensions(propertyId, null, leaseId, null, null),
                JournalSourceType.MANUAL, null, null,
                List.of(dr(AccountRole.BANK, a), cr(AccountRole.RENT_RECEIVABLE, a)))));
    }

    @Test
    void anOpenBounceIsOverdueAtItsFaceValue() {
        UUID leaseId = leaseWithABounce();
        Renter b = fixtures.createRenter("Novated Co LLC");
        assertThatThrownBy(() -> assignments.draft(leaseId, new AssignLeaseRequest(b.getId(), ON, "Novation", null)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("lease.assignmentOverdue");
                    assertThat(e.getArgs()).containsEntry("count", 1).containsEntry("amount", "12,750.00");
                });
    }

    @Test
    void aBounceTheLedgerHasClosedDoesNotBlockTheAssignment() {
        UUID leaseId = leaseWithABounce();
        receive(leaseId, "12750");
        Renter b = fixtures.createRenter("Novated Co LLC");
        LeaseAssignmentDTO draft = assignments.draft(leaseId, new AssignLeaseRequest(b.getId(), ON, "Novation", null));
        assertThat(draft.overdue()).isEmpty();
    }

    @Test
    void aPartPaidBounceIsOverdueForWhatIsStillOwed() {
        UUID leaseId = leaseWithABounce();
        receive(leaseId, "2750");
        Renter b = fixtures.createRenter("Novated Co LLC");
        assertThatThrownBy(() -> assignments.draft(leaseId, new AssignLeaseRequest(b.getId(), ON, "Novation", null)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getArgs()).containsEntry("amount", "10,000.00"));
        LeaseAssignmentDTO draft = assignments.draft(leaseId, new AssignLeaseRequest(b.getId(), ON, "Novation", true));
        assertThat(draft.overdue()).singleElement().satisfies(o -> {
            assertThat(o.chequeDate()).isEqualTo(RENT_1);
            assertThat(o.amount()).isEqualByComparingTo("10000.00");
        });
    }

    private static ChequeRowInput row(String number, LocalDate chequeDate, String amount) {
        return new ChequeRowInput(null, null, CONTRACT_DATE, number, chequeDate, "Emirates NBD",
                null, null, new BigDecimal(amount), null, null);
    }

    private Cheque chequeOn(UUID leaseId, LocalDate chequeDate) {
        return tx.execute(s -> chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId).stream()
                .filter(x -> chequeDate.equals(x.getChequeDate())).findFirst().orElseThrow());
    }
}
