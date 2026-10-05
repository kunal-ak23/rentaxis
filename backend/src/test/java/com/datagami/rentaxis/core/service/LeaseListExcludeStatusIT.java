package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #396 review P3-2: {@code GET /leases/paged?excludeStatus=…} drops contracts in
 * those states <em>before</em> the page is cut, so a page of excluded contracts can
 * never hide the one the caller is looking for, and the total counts only what is left.
 */
@SpringBootTest
class LeaseListExcludeStatusIT extends AbstractPostgresIT {

    @Autowired LeaseService leaseService;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService generation;
    @Autowired AccountService accountService;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired com.datagami.rentaxis.domain.repository.LeaseRepository leaseRepo;

    private UUID active;

    @BeforeEach
    void setUp() {
        LeaseTestFixtures f = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap().withLeaseServices(leaseService, generation, posting);
        // Three drafts sorted ahead of the one active contract (later start dates).
        for (int i = 0; i < 3; i++) {
            Unit u = f.createUnit(f.property(), "XD" + i);
            Renter r = f.createRenter("Exclude Renter " + i);
            f.draftLease(u, r, LocalDate.of(2026, 1, 5), LocalDate.of(2027, 2, 1), LocalDate.of(2028, 1, 31),
                    List.of(line("RENT", "48000")));
        }
        Unit u = f.createUnit(f.property(), "XA");
        Renter r = f.createRenter("Exclude Renter A");
        active = f.postedLease(u, r, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 1), LocalDate.of(2027, 1, 31),
                List.of(line("RENT", "48000")), 2, LeaseTestFixtures.nextChequeNumber()).lease().getId();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    @Test
    void excludedStatusesAreDroppedBeforeThePageIsCut() {
        // Page size 2, newest start first: without the exclusion the page is two drafts.
        Page<LeaseDTO> plain = leaseService.getAllLeasesPaged("Exclude Renter", null, null, null, null, PageRequest.of(0, 2));
        assertThat(plain.getTotalElements()).isEqualTo(4);

        Page<LeaseDTO> live = leaseService.getAllLeasesPaged("Exclude Renter", null, null, null,
                List.of(LeaseStatus.DRAFT, LeaseStatus.CLOSED), PageRequest.of(0, 2));
        assertThat(live.getTotalElements()).isEqualTo(1);
        assertThat(live.getContent()).extracting(LeaseDTO::getId).containsExactly(active);
    }

    @Test
    void theExclusionAppliesWithoutASearchTermToo() {
        Page<LeaseDTO> live = leaseService.getAllLeasesPaged(null, null, null, null,
                List.of(LeaseStatus.DRAFT), PageRequest.of(0, 25));
        assertThat(live.getContent()).extracting(LeaseDTO::getStatus).doesNotContain(LeaseStatus.DRAFT);
        assertThat(live.getContent()).extracting(LeaseDTO::getId).contains(active);
    }

    @Test
    void theDatabasePagesAndCountsWhatIsLeftWhenNoTermIsGiven() {
        // An unrestricted caller with no text term: the exclusion is in the query, so the
        // database cuts the page and its count is the count of what is left.
        UUID tenantId = TenantContextHolder.getTenantId();
        Page<com.datagami.rentaxis.domain.entity.Lease> rows = leaseRepo.searchExcluding(tenantId, null, null, null,
                List.of(LeaseStatus.DRAFT, LeaseStatus.CLOSED), PageRequest.of(0, 1));
        assertThat(rows.getContent()).extracting(com.datagami.rentaxis.domain.entity.Lease::getStatus)
                .doesNotContain(LeaseStatus.DRAFT, LeaseStatus.CLOSED);
        long live = leaseRepo.searchList(tenantId, null, null, null).stream()
                .filter(l -> l.getStatus() != LeaseStatus.DRAFT && l.getStatus() != LeaseStatus.CLOSED).count();
        assertThat(rows.getTotalElements()).isEqualTo(live);

        Page<LeaseDTO> viaService = leaseService.getAllLeasesPaged(null, null, null, null,
                List.of(LeaseStatus.DRAFT, LeaseStatus.CLOSED), PageRequest.of(0, 1));
        assertThat(viaService.getTotalElements()).isEqualTo(live);
        assertThat(viaService.getContent()).hasSize(1);
    }
}
