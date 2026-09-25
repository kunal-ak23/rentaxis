package com.datagami.rentaxis.core.service.baddebt;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.baddebt.BadDebtService.ProposeRequest;
import com.datagami.rentaxis.core.service.baddebt.BadDebtService.RecoveryRequest;
import com.datagami.rentaxis.core.service.baddebt.BadDebtService.WriteOffDTO;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.BadDebtWriteOff.Status;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
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
 * F14-38: write off the unpaid rows of a lease (Dr bad debts / Cr rent receivable,
 * items closed), recover part later (Dr bank / Cr bad debts recovered), reverse a
 * write-off (debt open again), and undeclared VAT refused.
 */
@SpringBootTest
class BadDebtIT extends AbstractPostgresIT {

    @Autowired BadDebtService service;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired LeaseService leaseService;
    @Autowired AccountService accountService;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired AccountResolver resolver;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired JdbcTemplate jdbc;

    private LeaseTestFixtures fixtures;
    private static final LocalDate CONTRACT = LocalDate.of(2026, 4, 20);
    private static final LocalDate START = LocalDate.of(2026, 5, 1);
    private static final LocalDate END = LocalDate.of(2027, 4, 30);
    private static final LocalDate ON = LocalDate.of(2026, 9, 1);

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

    private UUID lease() {
        return fixtures.postedLease(CONTRACT, START, END, List.of(line("RENT", "60000")), 4, null).lease().getId();
    }

    private BigDecimal balance(AccountRole role, UUID leaseId) {
        UUID account = resolver.resolve(role, fixtures.property().getId()).getId();
        return jdbc.queryForObject("select coalesce(sum(debit - credit), 0) from journal_lines where account_id = ? and lease_id = ?",
                BigDecimal.class, account, leaseId);
    }

    private BigDecimal onAccount(AccountRole role) {
        UUID account = resolver.resolve(role, fixtures.property().getId()).getId();
        return jdbc.queryForObject("select coalesce(sum(debit - credit), 0) from journal_lines where account_id = ?",
                BigDecimal.class, account);
    }

    @Test
    void theUnpaidRowsAreWrittenOffRecoveredInPartAndClosed() {
        UUID leaseId = lease();
        assertThat(service.candidates(leaseId, ON)).hasSize(2);
        WriteOffDTO proposed = service.propose(new ProposeRequest(leaseId, null, ON, "Renter absconded"));
        assertThat(proposed.status()).isEqualTo(Status.PROPOSED);
        assertThat(proposed.amount()).isEqualByComparingTo("30000.00");
        // Nothing posted yet; the items are no longer offered for another write-off.
        assertThat(onAccount(AccountRole.BAD_DEBT)).isZero();
        assertThat(service.candidates(leaseId, ON)).isEmpty();

        WriteOffDTO w = service.approve(proposed.id(), "agreed");
        assertThat(w.status()).isEqualTo(Status.WRITTEN_OFF);
        assertThat(onAccount(AccountRole.BAD_DEBT)).isEqualByComparingTo("30000.00");
        assertThat(balance(AccountRole.RENT_RECEIVABLE, leaseId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from cheques where lease_id = ? and status = 'CANCELLED'",
                Integer.class, leaseId)).isEqualTo(2);
        // The journal stays inside the lease's property.
        assertThat(jdbc.queryForObject("select count(distinct coalesce(property_id::text, '-')) from journal_lines where journal_entry_id = ?",
                Integer.class, w.journalId())).isOne();

        UUID bank = resolver.resolve(AccountRole.BANK, fixtures.property().getId()).getId();
        WriteOffDTO r = service.recover(w.id(), new RecoveryRequest(new BigDecimal("5000"), ON.plusDays(10), bank, "cheque"));
        assertThat(r.recovered()).isEqualByComparingTo("5000.00");
        assertThat(onAccount(AccountRole.BAD_DEBT_RECOVERED)).isEqualByComparingTo("-5000.00");
        assertThatThrownBy(() -> service.recover(w.id(), new RecoveryRequest(new BigDecimal("25000.01"), ON, bank, null)))
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getCode()).isEqualTo("badDebt.recoveryTooMuch"));
        assertThatThrownBy(() -> service.reverse(w.id(), ON.plusDays(20), "wrong"))
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getCode()).isEqualTo("badDebt.recoveredCannotReverse"));
    }

    @Test
    void aReversedWriteOffOpensTheDebtAgain() {
        UUID leaseId = lease();
        UUID first = service.candidates(leaseId, ON).getFirst().chequeId();
        WriteOffDTO w = service.approve(service.propose(new ProposeRequest(leaseId, List.of(first), ON, "gone")).id(), null);
        assertThat(w.amount()).isEqualByComparingTo("15000.00");
        assertThatThrownBy(() -> service.reverse(w.id(), ON, " "))
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getCode()).isEqualTo("badDebt.reasonRequired"));
        WriteOffDTO rev = service.reverse(w.id(), ON.plusDays(5), "renter came back");
        assertThat(rev.status()).isEqualTo(Status.REVERSED);
        assertThat(onAccount(AccountRole.BAD_DEBT)).isZero();
        // The debt is one open collection row again, and receivable holds it (via its PDR).
        assertThat(service.candidates(leaseId, ON.plusDays(5)))
                .anySatisfy(i -> assertThat(i.narration()).isEqualTo("Bad debt write-off reversed"));
        assertThat(service.candidates(leaseId, ON.plusDays(5))).hasSize(2);
    }

    @Test
    void aRejectionPostsNothingAndUndeclaredVatIsRefused() {
        UUID leaseId = lease();
        WriteOffDTO p = service.propose(new ProposeRequest(leaseId, null, ON, "maybe"));
        assertThat(service.reject(p.id(), "keep chasing").status()).isEqualTo(Status.REJECTED);
        assertThat(onAccount(AccountRole.BAD_DEBT)).isZero();
        assertThatThrownBy(() -> service.approve(p.id(), null))
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getCode()).isEqualTo("badDebt.wrongStatus"));

        UUID vatLease = fixtures.postedLease(fixtures.createUnit(fixtures.property(), "V-1"), fixtures.createRenter("V"),
                CONTRACT, START, END, List.of(vatLine("RENT", "60000")), 4, null).lease().getId();
        WriteOffDTO v = service.propose(new ProposeRequest(vatLease, null, ON, "gone"));
        assertThat(v.vatLease()).isTrue();
        assertThatThrownBy(() -> service.approve(v.id(), null))
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getCode()).isEqualTo("badDebt.vatNotDeclared"));
    }
}
