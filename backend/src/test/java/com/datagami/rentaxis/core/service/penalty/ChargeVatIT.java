package com.datagami.rentaxis.core.service.penalty;

import com.datagami.rentaxis.api.dto.penalty.PenaltyAssessmentDTO;
import com.datagami.rentaxis.api.dto.penalty.ProposePenaltyRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.enums.PenaltyReason;
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
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.vatLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F14-30: a charge that is consideration for a supply carries 5 % VAT and a tax
 * invoice on a VAT lease; a penalty (bounce, late payment) never does; nothing on
 * a lease without VAT; a reversal issues the credit note.
 */
@SpringBootTest
class ChargeVatIT extends AbstractPostgresIT {

    @Autowired PenaltyAssessmentService service;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired LeaseService leaseService;
    @Autowired AccountService accountService;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired JdbcTemplate jdbc;

    private LeaseTestFixtures fixtures;
    private static final LocalDate CONTRACT = LocalDate.of(2026, 4, 20);
    private static final LocalDate START = LocalDate.of(2026, 5, 1);
    private static final LocalDate END = LocalDate.of(2027, 4, 30);
    private static final LocalDate ON = LocalDate.of(2026, 6, 1);

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

    private UUID vatLease() {
        return fixtures.postedLease(CONTRACT, START, END, List.of(vatLine("RENT", "120000")), 4, null).lease().getId();
    }

    private PenaltyAssessmentDTO raise(UUID lease, PenaltyReason reason, String amount, Boolean vatable) {
        return service.propose(new ProposePenaltyRequest(lease, null, reason, new BigDecimal(amount), "x", ON, vatable), null);
    }

    private BigDecimal outputVatOn(UUID journalId) {
        return jdbc.queryForObject("""
                select coalesce(sum(l.credit - l.debit), 0) from journal_lines l
                join tenant_default_account_mappings m on m.account_id = l.account_id and m.role = 'OUTPUT_VAT'
                where l.journal_entry_id = ?""", BigDecimal.class, journalId);
    }

    @Test
    void aServiceRechargeOnAVatLeaseCarriesVatAndATaxInvoice() {
        UUID lease = vatLease();
        PenaltyAssessmentDTO raised = raise(lease, PenaltyReason.SERVICE_RECHARGE, "200.00", null);
        assertThat(raised.vatable()).isTrue();
        // F15-18: the approver sees the VAT before approving.
        assertThat(raised.vatAmount()).isZero();
        assertThat(raised.expectedVat()).isEqualByComparingTo("10.00");
        PenaltyAssessmentDTO a = service.approve(raised.id(), ON);
        assertThat(a.vatAmount()).isEqualByComparingTo("10.00");
        assertThat(a.expectedVat()).isEqualByComparingTo("10.00");
        assertThat(outputVatOn(a.journalId())).isEqualByComparingTo("10.00");
        assertThat(jdbc.queryForObject("select amount from cheques where id = ?", BigDecimal.class, a.collectionChequeId()))
                .as("the renter owes the charge with its VAT").isEqualByComparingTo("210.00");
        assertThat(jdbc.queryForObject("""
                select count(*) from tax_invoices where journal_id = ? and kind = 'TAX_INVOICE'
                  and taxable_amount = 200.00 and vat_amount = 10.00""", Integer.class, a.journalId())).isOne();
        assertThat(service.outstandingForLease(lease)).isEqualByComparingTo("210.00");

        // Reversed: the VAT goes back on a credit note.
        PenaltyAssessmentDTO r = service.reverse(a.id(), ON, "raised in error");
        assertThat(jdbc.queryForObject("select count(*) from tax_invoices where lease_id = ? and kind = 'CREDIT_NOTE'",
                Integer.class, lease)).isOne();
        assertThat(r.status().name()).isEqualTo("REVERSED");
    }

    @Test
    void penaltiesAreOutOfScopeAndOtherFollowsTheUsersChoice() {
        UUID lease = vatLease();
        PenaltyAssessmentDTO late = service.approve(raise(lease, PenaltyReason.LATE_PAYMENT, "300.00", null).id(), ON);
        assertThat(late.vatable()).isFalse();
        assertThat(late.expectedVat()).isEqualByComparingTo("0");
        assertThat(outputVatOn(late.journalId())).isZero();
        PenaltyAssessmentDTO bounce = raise(lease, PenaltyReason.CHEQUE_RETURN, "500.00", null);
        assertThat(bounce.vatable()).isFalse();
        assertThat(raise(lease, PenaltyReason.OTHER, "50.00", null).vatable()).isFalse();
        PenaltyAssessmentDTO keyCard = service.approve(raise(lease, PenaltyReason.OTHER, "200.00", true).id(), ON);
        assertThat(outputVatOn(keyCard.journalId())).isEqualByComparingTo("10.00");
        // The user may also switch VAT off a service charge.
        assertThat(raise(lease, PenaltyReason.ADMIN_FEE, "100.00", false).vatable()).isFalse();
    }

    @Test
    void aLeaseWithoutVatChargesNone() {
        UUID lease = fixtures.postedLease(CONTRACT, START, END, List.of(line("RENT", "60000")), 4, null).lease().getId();
        assertThat(raise(lease, PenaltyReason.SERVICE_RECHARGE, "200.00", null).vatable()).isFalse();
        assertThatThrownBy(() -> raise(lease, PenaltyReason.DAMAGE, "200.00", true))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getCode()).isEqualTo("penalty.vatOnNonVatLease"));
    }
}
