package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.SaveSettlementDTO;
import com.datagami.rentaxis.api.dto.SettlementResponseDTO;
import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.lease.TerminateLeaseRequest;
import com.datagami.rentaxis.api.dto.settlement.FinalizeSettlementRequest;
import com.datagami.rentaxis.core.service.baddebt.BadDebtService;
import com.datagami.rentaxis.core.service.baddebt.BadDebtService.ProposeRequest;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.lease.LeaseTerminationService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.recognition.RecognitionService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.DeductionCategory;
import com.datagami.rentaxis.domain.entity.enums.LineItemType;
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

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #102 / review P2-1: a finalized settlement's "still due" is the ledger, not
 * balance due minus every write-off. A row written off mid-tenancy was already out
 * of the receivable the balance due was taken from, and a receipt after the
 * move-out lowers what is owed; both must show in {@code stillDue} exactly once.
 */
@SpringBootTest
class SettlementStillDueIT extends AbstractPostgresIT {

    @Autowired SettlementService settlement;
    @Autowired LeaseTerminationService termination;
    @Autowired RecognitionService recognition;
    @Autowired BadDebtService badDebts;
    @Autowired ChequeService chequeService;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired LeasePostingService posting;
    @Autowired LeaseService leaseService;
    @Autowired LedgerQueryService ledger;
    @Autowired AccountResolver resolver;
    @Autowired AccountService accountService;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired TransactionTemplate tx;

    private LeaseTestFixtures fixtures;

    private static final LocalDate CONTRACT = LocalDate.of(2026, 4, 20);
    private static final LocalDate START = LocalDate.of(2026, 5, 1);
    private static final LocalDate END = LocalDate.of(2027, 4, 30);
    private static final LocalDate WRITE_OFF_ON = LocalDate.of(2026, 9, 1);
    private static final LocalDate T = LocalDate.of(2026, 9, 30);
    private static final LocalDate SETTLED_ON = LocalDate.of(2026, 10, 5);
    private static final LocalDate PAID_ON = LocalDate.of(2026, 10, 6);

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

    private BigDecimal ledgerOwed(UUID leaseId) {
        return tx.execute(s -> {
            UUID renter = fixtures.renter().getId();
            var f = new LedgerQueryService.LedgerFilter(null, null, null, null, leaseId, renter);
            BigDecimal receivable = ledger.accountLedger(
                    resolver.resolve(AccountRole.RENT_RECEIVABLE, fixtures.property().getId()).getId(), f).closingBalance();
            BigDecimal pdc = ledger.accountLedger(
                    resolver.resolve(AccountRole.PDC_RECEIVABLE, fixtures.property().getId()).getId(), f).closingBalance();
            return receivable.add(pdc).max(BigDecimal.ZERO);
        });
    }

    @Test
    void stillDueIsTheLedgerAfterAMidTenancyWriteOffAndAReceiptAfterTheMoveOut() {
        UUID leaseId = fixtures.postedLease(CONTRACT, START, END, List.of(line("RENT", "60000")), 4, null).lease().getId();

        // Mid-tenancy: the second unpaid row is paid on its date, the first (15,000) written off.
        var unpaid = badDebts.candidates(leaseId, WRITE_OFF_ON);
        assertThat(unpaid).hasSize(2);
        chequeService.deposit(unpaid.get(1).chequeId(), ChequeActionRequest.on(unpaid.get(1).date()));
        chequeService.clear(unpaid.get(1).chequeId(), ChequeActionRequest.on(unpaid.get(1).date()));
        UUID first = unpaid.get(0).chequeId();
        var w = badDebts.approve(badDebts.propose(new ProposeRequest(leaseId, List.of(first), WRITE_OFF_ON,
                "Bounced, renter unreachable")).id(), null);
        BigDecimal writtenOff = w.amount();
        assertThat(writtenOff).isPositive();

        recognition.runTo(T, false);
        termination.terminate(leaseId, new TerminateLeaseRequest(T, null, null, "Early exit"), null);
        SaveSettlementDTO draft = new SaveSettlementDTO();
        SaveSettlementDTO.DeductionItemDTO damage = new SaveSettlementDTO.DeductionItemDTO();
        damage.setType(LineItemType.DEDUCTION);
        damage.setCategory(DeductionCategory.PROPERTY_DAMAGE);
        damage.setDescription("damage");
        damage.setAmount(new BigDecimal("20000"));
        draft.setDeductions(List.of(damage));
        settlement.saveDraft(leaseId, draft, null);
        SettlementResponseDTO done = settlement.finalizeSettlement(leaseId,
                new FinalizeSettlementRequest(SETTLED_ON, null, false), null);
        assertThat(done.getBalanceDue()).isPositive();
        assertThat(done.getCollectionChequeId()).isNotNull();
        BigDecimal oldMath = done.getBalanceDue().subtract(writtenOff).max(BigDecimal.ZERO);
        assertThat(oldMath).as("the scenario keeps the old arithmetic above zero").isPositive();
        assertThat(tx.execute(s -> settlement.buildSettlementResponse(leaseId)).getStillDue())
                .as("just finalized: the ledger, the mid-tenancy write-off not taken off again")
                .isEqualByComparingTo(ledgerOwed(leaseId))
                .isEqualByComparingTo(done.getBalanceDue());

        // After the move-out the renter pays the collection row.
        chequeService.receive(done.getCollectionChequeId(), ChequeActionRequest.on(PAID_ON));

        SettlementResponseDTO after = tx.execute(s -> settlement.buildSettlementResponse(leaseId));
        BigDecimal owed = ledgerOwed(leaseId);
        assertThat(after.getStillDue()).as("still due is the ledger").isEqualByComparingTo(owed);
        assertThat(after.getBalanceDue()).as("the stored figure never moves").isEqualByComparingTo(done.getBalanceDue());
        assertThat(owed).as("paid in full after the move-out").isEqualByComparingTo("0");
        assertThat(owed).as("the old arithmetic said otherwise").isNotEqualByComparingTo(oldMath);
    }
}
