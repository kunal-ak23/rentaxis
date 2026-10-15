package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.lease.ExtendLeaseRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Lease;
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
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;

/**
 * Break-it R4 money4 F5: an ended contract (01/01–30/06) extended to 31/12 on an
 * organisation locked through 31/08 was accepted without a word, and July and
 * August — inside the lock — stayed PLANNED for ever: the month-end close skips
 * locked rows. Transfer and termination into a locked period are refused; so is
 * an extension whose new months start inside the lock (re-open the period, or
 * extend on a later contract instead).
 */
@SpringBootTest
class ExtensionIntoLockedPeriodIT extends AbstractPostgresIT {

    // PR #400 review P3-1: the realistic companions lock the normal way (recognise, then lock).
    @org.springframework.beans.factory.annotation.Autowired
    com.datagami.rentaxis.core.service.recognition.RecognitionService recognitionForLock;
    @org.springframework.beans.factory.annotation.Autowired
    com.datagami.rentaxis.core.service.vat.VatTaxPointService vatForLock;
    @org.springframework.beans.factory.annotation.Autowired
    com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService fiscalForLock;

    @Autowired LeaseRenewalService renewal;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService cheques;
    @Autowired LeaseService leaseService;
    @Autowired AccountService accountService;
    @Autowired LeaseRepository leaseRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired TransactionTemplate tx;

    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 6, 30);
    private static final LocalDate NEW_END = LocalDate.of(2026, 12, 31);
    private static final LocalDate TODAY = LocalDate.now(java.time.ZoneId.of("Asia/Dubai"));

    private LeaseTestFixtures fixtures;

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

    @Test
    void anExtensionWhoseNewMonthsStartInsideTheLockIsRefusedAndNothingChanges() {
        UUID id = fixtures.postedLease(START, START, END, List.of(line("RENT", "36000")), 2, "500010")
                .lease().getId();
        tx.executeWithoutResult(s -> com.datagami.rentaxis.testsupport.LockedBooks.lockOverPlanned(jdbc, LocalDate.of(2026, 8, 31)));

        assertThatThrownBy(() -> renewal.extend(id,
                new ExtendLeaseRequest(NEW_END, TODAY, List.of(line("RENT", "36800")),
                        List.of(LeaseTestFixtures.chequeRow("36800", TODAY.plusDays(30))))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("books are locked through 31/08/2026")
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getCode())
                        .isEqualTo("lease.extendIntoLock"));
        assertThat(reread(id).getEndDate()).isEqualTo(END);
    }

    /** PR #400 review P3-1: the same, with the lock reached the normal way (recognise, then lock). */
    @Test
    void anExtensionWhoseNewMonthsStartInsideTheLockIsRefusedAndNothingChanges_afterRecognisingThenLocking() {
        UUID id = fixtures.postedLease(START, START, END, List.of(line("RENT", "36000")), 2, "500010")
                .lease().getId();
        tx.executeWithoutResult(s -> com.datagami.rentaxis.testsupport.LockedBooks.lockAfterRecognising(recognitionForLock, vatForLock, fiscalForLock, LocalDate.of(2026, 8, 31)));

        assertThatThrownBy(() -> renewal.extend(id,
                new ExtendLeaseRequest(NEW_END, TODAY, List.of(line("RENT", "36800")),
                        List.of(LeaseTestFixtures.chequeRow("36800", TODAY.plusDays(30))))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("books are locked through 31/08/2026")
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getCode())
                        .isEqualTo("lease.extendIntoLock"));
        assertThat(reread(id).getEndDate()).isEqualTo(END);
    }

    private Lease reread(UUID leaseId) {
        return tx.execute(s -> leaseRepo.findById(leaseId).orElseThrow());
    }
}
