package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.CreateLeaseDTO;
import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.api.dto.lease.RenewLeaseRequest;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.RentCollectionSettings;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.RentCollectionSettingsRepository;
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

/**
 * A lease's grace comes from its property unless the lease names one (gap #65).
 *
 * <p>The backend already inherited on a null grace; the screens sent an explicit
 * 0, so a property's "5 days" never reached a lease drafted on them. With the
 * screens now sending null, the lease also has to remember <em>which</em> it was:
 * a renewal of a lease that took the building's policy takes the policy as it
 * stands at renewal, while one whose grace was agreed with the renter keeps it.
 * Either way the number is a snapshot — it never moves on a lease already drafted.</p>
 */
@SpringBootTest
class LeaseGraceInheritanceIT extends AbstractPostgresIT {

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
    @Autowired RentCollectionSettingsRepository rentCollectionSettingsRepo;
    @Autowired TransactionTemplate tx;

    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 9, 16);
    private static final LocalDate START = LocalDate.of(2026, 10, 2);
    private static final LocalDate END = LocalDate.of(2027, 10, 1);
    private static final LocalDate RENEWAL_CONTRACT_DATE = LocalDate.of(2027, 9, 16);
    private static final LocalDate RENEWAL_START = LocalDate.of(2027, 10, 2);
    private static final LocalDate RENEWAL_END = LocalDate.of(2028, 10, 1);

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
    void aDraftWithNoGraceInheritsThePropertysAndIsMarkedNotOverridden() {
        propertyGrace(5);

        LeaseDTO draft = leaseService.createDraftLease(dto(null));

        assertThat(draft.getGracePeriodDays()).isEqualTo(5);
        assertThat(draft.getGracePeriodOverridden()).isFalse();
        assertThat(reread(draft.getId()).isGracePeriodOverridden()).isFalse();
    }

    @Test
    void anExplicitZeroIsKeptAndMarkedOverridden() {
        propertyGrace(5);

        LeaseDTO draft = leaseService.createDraftLease(dto(0));

        assertThat(draft.getGracePeriodDays())
                .as("a renter who agreed to no grace is not given the building's five days")
                .isZero();
        assertThat(draft.getGracePeriodOverridden()).isTrue();
        assertThat(reread(draft.getId()).isGracePeriodOverridden()).isTrue();
    }

    /** Editing a draft back to "use the property default" re-inherits and clears the flag. */
    @Test
    void clearingAnOverrideOnADraftReInherits() {
        propertyGrace(5);
        LeaseDTO draft = leaseService.createDraftLease(dto(9));
        assertThat(draft.getGracePeriodOverridden()).isTrue();

        LeaseDTO edited = leaseService.updateDraftLease(draft.getId(), dto(null));

        assertThat(edited.getGracePeriodDays()).isEqualTo(5);
        assertThat(edited.getGracePeriodOverridden()).isFalse();
    }

    /**
     * The building's policy changed between the two terms. The successor of a
     * lease that inherited takes today's policy; the predecessor keeps the window
     * its renter agreed to.
     */
    @Test
    void aRenewalOfAnInheritingLeasePicksUpThePropertysCurrentValue() {
        propertyGrace(5);
        UUID firstId = fixtures.postedLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000")), 4, "100010").lease().getId();
        assertThat(reread(firstId).getGracePeriodDays()).isEqualTo(5);
        assertThat(reread(firstId).isGracePeriodOverridden()).isFalse();

        propertyGrace(8);
        LeaseDTO successor = renewal.renew(firstId, renewRequest());

        assertThat(successor.getGracePeriodDays()).isEqualTo(8);
        assertThat(successor.getGracePeriodOverridden()).isFalse();
        assertThat(reread(firstId).getGracePeriodDays())
                .as("a lease's grace never changes after it is drafted")
                .isEqualTo(5);
    }

    /** A grace agreed with the renter carries over, whatever the building now says. */
    @Test
    void aRenewalOfAnOverriddenLeaseCopiesTheOverride() {
        propertyGrace(5);
        UUID firstId = leaseService.createDraftLease(dto(7)).getId();
        fixtures.generateGrid(firstId, 4, START);
        fixtures.numberGrid(firstId, "100010");
        posting.post(firstId);

        propertyGrace(8);
        LeaseDTO successor = renewal.renew(firstId, renewRequest());

        assertThat(successor.getGracePeriodDays()).isEqualTo(7);
        assertThat(successor.getGracePeriodOverridden()).isTrue();
    }

    private CreateLeaseDTO dto(Integer grace) {
        CreateLeaseDTO dto = fixtures.draftDto(START, END, List.of(line("RENT", "51000")));
        dto.setContractDate(CONTRACT_DATE);
        dto.setGracePeriodDays(grace);
        return dto;
    }

    private RenewLeaseRequest renewRequest() {
        return new RenewLeaseRequest(RENEWAL_CONTRACT_DATE, RENEWAL_START, RENEWAL_END, null, false);
    }

    private Lease reread(UUID leaseId) {
        return tx.execute(s -> leaseRepo.findById(leaseId).orElseThrow());
    }

    /** The fixture property's rent-collection policy. */
    private void propertyGrace(Integer days) {
        tx.executeWithoutResult(s -> {
            RentCollectionSettings settings = rentCollectionSettingsRepo
                    .findByPropertyId(fixtures.property().getId())
                    .orElseGet(() -> {
                        RentCollectionSettings fresh = new RentCollectionSettings();
                        fresh.setTenantId(fixtures.tenantId());
                        fresh.setProperty(fixtures.property());
                        return fresh;
                    });
            settings.setGracePeriodDays(days);
            rentCollectionSettingsRepo.save(settings);
        });
    }
}
