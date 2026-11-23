package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.ParkingSpotCreateRequest;
import com.datagami.rentaxis.api.dto.ParkingSpotUpdateRequest;
import com.datagami.rentaxis.api.dto.penalty.LeaseJournalVoucherRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.penalty.PenaltyAssessmentService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.ParkingSpot;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.repository.AccountRepository;
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

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.vatLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Task 2: a bay or amenity names the income leaf its booking fee credits, under one shared rule. */
@SpringBootTest
class FacilityIncomeAccountIT extends AbstractPostgresIT {

    @Autowired FacilityService facilities;
    @Autowired PenaltyAssessmentService penalties;
    @Autowired AccountResolver accountResolver;
    @Autowired AccountRepository accountRepo;
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
    @Autowired TransactionTemplate tx;

    private LeaseTestFixtures fixtures;

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

    private Account leaf(AccountRole role, UUID propertyId) {
        return tx.execute(s -> accountResolver.resolve(role, propertyId));
    }

    private ParkingSpot bay(String number, UUID incomeAccountId) {
        return facilities.createParkingSpot(fixtures.tenantId(), new ParkingSpotCreateRequest(
                fixtures.property().getId(), number, "B1", null, null, null, incomeAccountId));
    }

    private ParkingSpotUpdateRequest setIncome(UUID accountId) {
        return new ParkingSpotUpdateRequest(null, null, null, null, null, null, null, null, null, accountId, null);
    }

    @Test
    void aBayKeepsTheIncomeLeafItWasGiven() {
        UUID parking = leaf(AccountRole.PARKING_INCOME, fixtures.property().getId()).getId();
        ParkingSpot created = bay("B1-01", parking);
        ParkingSpot reloaded = facilities.getParkingSpot(fixtures.tenantId(), created.getId());
        assertThat(reloaded.getIncomeAccountId()).isEqualTo(parking);

        ParkingSpot cleared = facilities.updateParkingSpot(fixtures.tenantId(), created.getId(),
                new ParkingSpotUpdateRequest(null, null, null, null, null, null, null, null, null, null, true));
        assertThat(cleared.getIncomeAccountId()).isNull();
    }

    @Test
    void aGroupAnExpenseOrAnotherPropertysLeafIsRefused() {
        UUID propertyId = fixtures.property().getId();
        UUID parking = leaf(AccountRole.PARKING_INCOME, propertyId).getId();
        ParkingSpot spot = bay("B1-02", parking);

        UUID group = accountRepo.findByCodeAndTenantId("C-01-02", fixtures.tenantId()).orElseThrow().getId();
        UUID expense = leaf(AccountRole.DISCOUNT_ALLOWED, propertyId).getId();
        Property other = fixtures.createProperty("OTH");
        UUID otherLeaf = leaf(AccountRole.PARKING_INCOME, other.getId()).getId();

        for (UUID bad : List.of(group, expense, otherLeaf)) {
            assertThatThrownBy(() -> facilities.updateParkingSpot(fixtures.tenantId(), spot.getId(), setIncome(bad)))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .extracting(e -> ((BusinessRuleViolationException) e).getCode())
                    .isEqualTo("booking.incomeAccount");
        }
        assertThat(facilities.getParkingSpot(fixtures.tenantId(), spot.getId()).getIncomeAccountId()).isEqualTo(parking);
    }

    @Test
    void aNewPropertyGetsAnAmenityIncomeLeaf() {
        Property p2 = fixtures.createProperty("AM2");
        Account leaf = tx.execute(s -> {
            Account a = accountResolver.resolve(AccountRole.AMENITY_INCOME, p2.getId());
            a.getParent().getCode();
            return a;
        });
        assertThat(leaf.getName()).isEqualTo("Amenity Income - " + p2.getNameEn());
        String parentCode = tx.execute(s -> accountRepo.findById(leaf.getId()).orElseThrow().getParent().getCode());
        assertThat(parentCode).isEqualTo("C-01-02");
    }

    @Test
    void existingContractJournalVoucherStillRefusesAGroup() {
        UUID leaseId = fixtures.postedLease(LocalDate.of(2026, 4, 20), LocalDate.of(2026, 5, 1),
                LocalDate.of(2027, 4, 30), List.of(vatLine("RENT", "120000")), 4, null).lease().getId();
        Account group = accountRepo.findByCodeAndTenantId("C-01-02", fixtures.tenantId()).orElseThrow();
        assertThatThrownBy(() -> penalties.raiseJournalVoucher(leaseId, new LeaseJournalVoucherRequest(
                LocalDate.now(), group.getId(), new BigDecimal("100"), null, "Extra"), null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("is not an active ledger account");
    }
}
