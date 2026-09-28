package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.CreateLeaseDTO;
import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.api.dto.lease.ExtendLeaseRequest;
import com.datagami.rentaxis.api.dto.lease.RenewLeaseRequest;
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

/**
 * Break round 1: the lease wizard accepted a contract running to 2999 (a ~973-year
 * term). Every door that sets a contract's term — draft create and update, renewal,
 * extension — refuses one longer than {@link LeaseService#MAX_TERM_YEARS} years with
 * a {@link BusinessRuleViolationException}, which {@code GlobalExceptionHandler}
 * answers as a 400 carrying the message. Exactly 50 years is still a valid term.
 */
@SpringBootTest
class LeaseTermLimitIT extends AbstractPostgresIT {

    @Autowired LeaseRenewalService renewal;
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
    @Autowired TransactionTemplate tx;

    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 5, 16);
    private static final LocalDate START = LocalDate.of(2026, 6, 1);
    private static final LocalDate END = LocalDate.of(2027, 5, 31);
    private static final LocalDate YEAR_2999 = LocalDate.of(2999, 6, 1);

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
    void aDraftRunningTo2999IsRefusedWithAClearMessage() {
        assertThatThrownBy(() -> leaseService.createDraftLease(dto(START, YEAR_2999)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("at most 50 years")
                .hasMessageContaining("01/06/2026")
                .hasMessageContaining("01/06/2999");
    }

    @Test
    void exactlyFiftyYearsIsAllowedAndOneDayMoreIsNot() {
        LocalDate fiftyYears = START.plusYears(50).minusDays(1); // 31/05/2076, end inclusive
        LeaseDTO ok = leaseService.createDraftLease(dto(START, fiftyYears));
        assertThat(ok.getEndDate()).isEqualTo(fiftyYears);

        CreateLeaseDTO oneDayMore = dto(START, START.plusYears(50));
        oneDayMore.setUnitId(fixtures.createUnit(fixtures.property(), "T2").getId());
        assertThatThrownBy(() -> leaseService.createDraftLease(oneDayMore))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("at most 50 years");
    }

    @Test
    void editingADraftTo2999IsRefusedAndTheDraftKeepsItsDates() {
        UUID id = leaseService.createDraftLease(dto(START, END)).getId();

        assertThatThrownBy(() -> leaseService.updateDraftLease(id, dto(START, YEAR_2999)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("at most 50 years");
        assertThat(reread(id).getEndDate()).isEqualTo(END);
    }

    @Test
    void aRenewalRunningTo2999IsRefused() {
        UUID firstId = fixtures.postedLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000")), 4, "300010").lease().getId();

        assertThatThrownBy(() -> renewal.renew(firstId,
                new RenewLeaseRequest(END, END.plusDays(1), YEAR_2999, null, false)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("at most 50 years");
    }

    @Test
    void anExtensionTo2999IsRefusedAndTheLeaseKeepsItsEnd() {
        UUID id = fixtures.postedLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000")), 4, "300110").lease().getId();

        assertThatThrownBy(() -> renewal.extend(id,
                new ExtendLeaseRequest(YEAR_2999, END, List.of(line("RENT", "1000")), List.of())))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("at most 50 years");
        assertThat(reread(id).getEndDate()).isEqualTo(END);
    }

    private CreateLeaseDTO dto(LocalDate start, LocalDate end) {
        CreateLeaseDTO dto = fixtures.draftDto(start, end, List.of(line("RENT", "51000")));
        dto.setContractDate(CONTRACT_DATE);
        return dto;
    }

    private Lease reread(UUID leaseId) {
        return tx.execute(s -> leaseRepo.findById(leaseId).orElseThrow());
    }
}
