package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The renter-portal lease and payment routes take the renter from the verified
 * principal (PR #342). They read X-User-Id through {@code request.getHeader}, so
 * on the bearer path a renter could list another renter's leases and cheques, or
 * accept a contract on their behalf, by naming them.
 */
class RenterPortalCallerIdentityIT extends AbstractCallerIdentityIT {

    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LeaseService leaseService;
    @Autowired ChequeGenerationService generation;
    @Autowired LeasePostingService posting;

    private static final LocalDate START = LocalDate.now().minusMonths(2);
    private static final LocalDate END = START.plusYears(1).minusDays(1);

    private User renterA;
    private User victim;
    private UUID victimsPendingLease;

    @BeforeEach
    void setUp() {
        LeaseTestFixtures fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, generation, posting);

        Renter victimRenter = fixtures.renter();
        Renter aRenter = fixtures.createRenter("Renter A");
        victim = userRepo.findById(victimRenter.getUserId()).orElseThrow();
        renterA = userRepo.findById(aRenter.getUserId()).orElseThrow();

        // A posted lease (cheques on the register) and a contract awaiting signature.
        fixtures.postedLease(START.minusDays(5), START, END, List.of(line("RENT", "48000")), 4, "700010");
        victimsPendingLease = fixtures.draftLease(fixtures.createUnit(fixtures.property(), "102"), victimRenter,
                START.minusDays(5), START, END, List.of(line("RENT", "36000")));
        jdbc.update("UPDATE leases SET status = 'PENDING_SIGNATURE' WHERE id = ?", victimsPendingLease);

        LeaseTestFixtures.clearAuth();
        TenantContextHolder.clear();
    }

    @Test
    void theVictimSeesTheirOwnLeasesAndCheques() {
        // Control: the empty lists below are about who is asking, not missing rows.
        assertThat(asSelf(HttpMethod.GET, "/api/v1/leases/my-leases", victim).retrieve().body(List.class))
                .hasSize(2);
        assertThat(asSelf(HttpMethod.GET, "/api/v1/online-payments/my-payments", victim).retrieve().body(List.class))
                .isNotEmpty();
    }

    @Test
    void myLeasesWithAForgedUserIdAreTheCallers() {
        assertThat(forged(HttpMethod.GET, "/api/v1/leases/my-leases", renterA, victim).retrieve().body(List.class))
                .isEmpty();
    }

    @Test
    void myPaymentsWithAForgedUserIdAreTheCallers() {
        assertThat(forged(HttpMethod.GET, "/api/v1/online-payments/my-payments", renterA, victim)
                .retrieve().body(List.class)).isEmpty();
    }

    @Test
    void aForgedUserIdCannotAcceptAnotherRentersContract() {
        assertThat(status(forged(HttpMethod.PUT, "/api/v1/leases/" + victimsPendingLease + "/accept", renterA, victim)))
                .isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT renter_accepted_at IS NULL FROM leases WHERE id = ?",
                Boolean.class, victimsPendingLease)).isTrue();
    }
}
