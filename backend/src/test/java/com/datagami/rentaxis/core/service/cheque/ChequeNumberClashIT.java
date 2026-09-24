package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.lease.PostLeaseResponse;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.bank.BankAccountLedgerService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.BankAccount;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.BankAccountRepository;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F14-19: a cheque (drawer bank, number, renter) registered on one lease cannot be
 * registered again on another; the dry run reports it, the post refuses it. The
 * same number from another renter's account is a different cheque.
 */
@SpringBootTest
class ChequeNumberClashIT extends AbstractPostgresIT {

    @Autowired ChequeService cheques;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired LeasePostingService posting;
    @Autowired LeaseService leaseService;
    @Autowired AccountService accountService;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;

    private LeaseTestFixtures fixtures;

    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 9, 16);
    private static final LocalDate START = LocalDate.of(2026, 10, 2);
    private static final LocalDate END = LocalDate.of(2027, 10, 1);

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

    private UUID draftWithNumbers(com.datagami.rentaxis.domain.entity.Renter renter, String book) {
        var unit = fixtures.createUnit(fixtures.property(), "CN-" + UUID.randomUUID().toString().substring(0, 4));
        UUID lease = fixtures.draftLease(unit, renter, CONTRACT_DATE, START, END, List.of(line("RENT", "36000")));
        fixtures.unnumberedGrid(lease, 2, START);
        fixtures.numberGrid(lease, book);
        return lease;
    }

    @Test
    void theSameRentersChequeCannotBeRegisteredOnASecondLease() {
        String book = LeaseTestFixtures.nextChequeBook();
        posting.post(draftWithNumbers(fixtures.renter(), book));

        UUID second = draftWithNumbers(fixtures.renter(), book);
        var dry = posting.dryRun(second);
        assertThat(dry.ok()).isFalse();
        assertThat(dry.errors()).anyMatch(e -> e.contains("is already registered for this renter on another lease"));
        assertThatThrownBy(() -> posting.post(second))
                .isInstanceOf(com.datagami.rentaxis.api.exception.BusinessRuleViolationException.class)
                .hasMessageContaining("is already registered for this renter on another lease");
    }

    /** A row added to a posted lease registers paper too, through LeaseChequeRegistrar. */
    @Test
    void aRowAddedToAPostedLeaseCannotReuseAnotherLeasesCheque() {
        String book = LeaseTestFixtures.nextChequeBook();
        posting.post(draftWithNumbers(fixtures.renter(), book));
        UUID second = draftWithNumbers(fixtures.renter(), LeaseTestFixtures.nextChequeBook());
        posting.post(second);

        assertThatThrownBy(() -> cheques.addRowToPostedLease(second, new com.datagami.rentaxis.api.dto.lease.ChequeRowInput(
                null, null, START, book, START.plusMonths(3), "Emirates NBD", null, null,
                new java.math.BigDecimal("1000"), "Extra", com.datagami.rentaxis.domain.entity.enums.ChequeMode.PDC)))
                .isInstanceOf(com.datagami.rentaxis.api.exception.BusinessRuleViolationException.class)
                .hasMessageContaining("is already registered for this renter on another lease");
    }

    @Test
    void anotherRentersChequeWithTheSameNumberIsADifferentCheque() {
        String book = LeaseTestFixtures.nextChequeBook();
        posting.post(draftWithNumbers(fixtures.renter(), book));

        UUID other = draftWithNumbers(fixtures.createRenter("Other Drawer"), book);
        assertThat(posting.dryRun(other).errors()).isEmpty();
        posting.post(other);
    }
}
