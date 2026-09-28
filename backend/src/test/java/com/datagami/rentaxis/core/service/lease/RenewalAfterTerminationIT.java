package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.api.dto.lease.PostLeaseDryRunResponse;
import com.datagami.rentaxis.api.dto.lease.RenewLeaseRequest;
import com.datagami.rentaxis.api.dto.lease.TerminateLeaseRequest;
import com.datagami.rentaxis.api.dto.lease.TerminationPreviewDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.SettlementService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Break-it round 2 (contracts2) F1: a renewal drafted before its predecessor was
 * terminated must not post afterwards — posting it carried the leaving renter's
 * deposit onto a contract for a tenancy that had ended, and the terminated lease's
 * settlement could no longer refund it. Terminating a lease discards its unposted
 * renewal draft, and the termination preview says so first.
 */
@SpringBootTest
class RenewalAfterTerminationIT extends AbstractPostgresIT {

    @Autowired LeaseRenewalService renewal;
    @Autowired LeasePostingService posting;
    @Autowired LeaseTerminationService termination;
    @Autowired SettlementService settlements;
    @Autowired ChequeGenerationService cheques;
    @Autowired LeaseService leaseService;
    @Autowired LeaseRepository leaseRepo;
    @Autowired AccountService accountService;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired TransactionTemplate tx;

    private LeaseTestFixtures fixtures;

    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 9, 16);
    private static final LocalDate START = LocalDate.of(2026, 10, 2);
    private static final LocalDate END = LocalDate.of(2027, 10, 1);
    private static final LocalDate T = LocalDate.of(2027, 2, 15);

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

    private UUID postedWithDeposit() {
        return fixtures.postedLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "60000"), line("SECURITY_DEPOSIT", "5000")), 4, null)
                .lease().getId();
    }

    private UUID renewalDraft(UUID predecessorId) {
        LeaseDTO s = renewal.renew(predecessorId,
                new RenewLeaseRequest(RENEWAL_CONTRACT_DATE, RENEWAL_START, RENEWAL_END, null, true));
        return s.getId();
    }

    private Lease reread(UUID id) {
        return tx.execute(s -> leaseRepo.findById(id).orElse(null));
    }

    private void setStatus(UUID leaseId, LeaseStatus status) {
        tx.executeWithoutResult(s -> {
            Lease lease = leaseRepo.findById(leaseId).orElseThrow();
            lease.setStatus(status);
            leaseRepo.save(lease);
        });
    }

    /**
     * The post guard on its own: a renewal whose predecessor has stopped being
     * renewable (here TERMINATED behind the draft's back) is refused by the post
     * and reported by the dry run with the same words, and nothing moves.
     */
    @Test
    void aRenewalWhosePredecessorWasTerminatedIsRefusedAtPostAndInTheDryRun() {
        UUID first = postedWithDeposit();
        UUID successor = renewalDraft(first);
        fixtures.generateGrid(successor, 4, RENEWAL_START);
        setStatus(first, LeaseStatus.TERMINATED);

        PostLeaseDryRunResponse dry = posting.dryRun(successor);
        assertThat(dry.ok()).isFalse();
        assertThat(dry.errors()).anySatisfy(e -> assertThat(e)
                .contains("TERMINATED").contains("can no longer be posted"));

        assertThatThrownBy(() -> posting.post(successor))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("TERMINATED");

        assertThat(reread(successor).getStatus()).isEqualTo(LeaseStatus.DRAFT);
        assertThat(reread(first).getStatus()).isEqualTo(LeaseStatus.TERMINATED);
    }

    /** RENEWED (a sibling renewal already posted) is not renewable either. */
    @Test
    void aSecondRenewalOfAnAlreadyRenewedLeaseIsRefused() {
        UUID first = postedWithDeposit();
        UUID successor = renewalDraft(first);
        fixtures.generateGrid(successor, 4, RENEWAL_START);
        setStatus(first, LeaseStatus.RENEWED);

        assertThatThrownBy(() -> posting.post(successor))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("RENEWED");
    }

    /** The happy path is untouched: an ACTIVE predecessor still renews and retires. */
    @Test
    void aRenewalOfAnActiveLeaseStillPosts() {
        UUID first = postedWithDeposit();
        UUID successor = renewalDraft(first);
        fixtures.generateGrid(successor, 4, RENEWAL_START);

        assertThat(posting.dryRun(successor).ok()).isTrue();
        posting.post(successor);

        assertThat(reread(successor).getStatus()).isEqualTo(LeaseStatus.ACTIVE);
        assertThat(reread(first).getStatus()).isEqualTo(LeaseStatus.RENEWED);
    }

    /**
     * C09 end to end: renew, then terminate — the preview names the renewal draft
     * as discarded, terminate discards it, and the deposit stays on the terminated
     * lease for its settlement to refund.
     */
    @Test
    void terminatingDiscardsThePendingRenewalDraftAndKeepsTheDeposit() {
        UUID first = postedWithDeposit();
        UUID successor = renewalDraft(first);
        fixtures.generateGrid(successor, 4, RENEWAL_START);

        TerminationPreviewDTO preview = termination.preview(first, T);
        assertThat(preview.problems()).isEmpty();
        assertThat(preview.notices()).singleElement().satisfies(n -> assertThat(n)
                .contains("renewal draft").contains("02/10/2027").contains("will be discarded"));

        termination.terminate(first, new TerminateLeaseRequest(T, null, null, "Leaving early"), null);

        assertThat(reread(first).getStatus()).isEqualTo(LeaseStatus.TERMINATED);
        assertThat(reread(successor)).isNull();
        assertThatThrownBy(() -> posting.post(successor)).isInstanceOf(NotFoundException.class);
        assertThat(tx.execute(s -> settlements.statement(first)).depositsHeld()).isEqualByComparingTo("5000");
    }

    /** A lease with no renewal draft previews no notice. */
    @Test
    void noRenewalDraftNoNotice() {
        UUID first = postedWithDeposit();
        assertThat(termination.preview(first, T).notices()).isEmpty();
    }

    /** Once the renewal has posted the predecessor is RENEWED, and terminate refuses it as before (C12). */
    @Test
    void aPostedRenewalStillBlocksTermination() {
        UUID first = postedWithDeposit();
        UUID successor = renewalDraft(first);
        fixtures.generateGrid(successor, 4, RENEWAL_START);
        posting.post(successor);

        assertThatThrownBy(() -> termination.terminate(first, new TerminateLeaseRequest(T, null, null, null), null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("RENEWED");
        assertThat(reread(successor).getStatus()).isEqualTo(LeaseStatus.ACTIVE);
    }
}
