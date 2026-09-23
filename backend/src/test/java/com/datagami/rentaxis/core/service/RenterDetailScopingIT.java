package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #8: the renter-detail page reads a renter and their contracts. Both reads are
 * primary-key or foreign-key lookups the tenant filter does not narrow, so a
 * renter id from another landlord must be a 404, not their profile or leases.
 */
@SpringBootTest
class RenterDetailScopingIT extends AbstractPostgresIT {

    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LeaseService leaseService;
    @Autowired ChequeGenerationService generation;
    @Autowired LeasePostingService posting;
    @Autowired RenterService renterService;

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private LeaseTestFixtures fixtures() {
        return new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo, propertyService,
                accountService, propertyAccountService, chargeTypeService)
                .withLeaseServices(leaseService, generation, posting);
    }

    @Test
    void aRentersOwnLeasesAreListedAndAForeignRenterIsNotFound() {
        LeaseTestFixtures victim = fixtures().bootstrap();
        LocalDate start = LocalDate.of(2026, 1, 1);
        UUID leaseId = victim.draftLease(start.minusDays(5), start, start.plusYears(1).minusDays(1),
                List.of(line("RENT", "60000")));
        Renter victimRenter = victim.renter();

        List<LeaseDTO> own = leaseService.getLeasesForRenter(victimRenter.getId());
        assertThat(own).extracting(LeaseDTO::getId).containsExactly(leaseId);
        assertThat(renterService.getRenterById(victimRenter.getId()).getNameEn()).isEqualTo("Test Renter");

        // Another landlord, authenticated as its own tenant admin.
        fixtures().bootstrap();

        assertThatThrownBy(() -> leaseService.getLeasesForRenter(victimRenter.getId()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> renterService.getRenterById(victimRenter.getId()))
                .isInstanceOf(NotFoundException.class);
    }
}
