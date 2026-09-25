package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.UnitListing;
import com.datagami.rentaxis.domain.entity.UnitListingInterest;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.InterestStatus;
import com.datagami.rentaxis.domain.entity.enums.ListingStatus;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitListingInterestRepository;
import com.datagami.rentaxis.domain.repository.UnitListingRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F14-51: an enquiry becomes a draft lease (renter record, no login; the listing's
 * unit, rent, deposit and cheques); posting it unpublishes the listing; the unit
 * becoming vacant republishes it only when the user opted in.
 */
@SpringBootTest
class ListingLeaseIT extends AbstractPostgresIT {

    @Autowired ListingLeaseService service;
    @Autowired UnitListingService listings;
    @Autowired UnitListingRepository listingRepo;
    @Autowired UnitListingInterestRepository interestRepo;
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
    private UUID listingId;

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, chequeGeneration, posting);
        UnitListing l = new UnitListing();
        l.setTenantId(fixtures.tenantId());
        l.setUnitId(fixtures.unit().getId());
        l.setStatus(ListingStatus.PUBLISHED);
        l.setTitleEn("Bright 1BR");
        l.setSlug("bright-" + UUID.randomUUID());
        l.setAnnualRent(new BigDecimal("60000"));
        l.setSecurityDeposit(new BigDecimal("3000"));
        l.setChequesAccepted(4);
        listingId = listingRepo.save(l).getId();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private UUID interest(String name) {
        User u = new User();
        u.setRole(UserRole.RENTER);
        u.setEmail("enquirer-" + UUID.randomUUID() + "@it.test");
        u.setName(name);
        u.setPasswordHash("hash");
        u = userRepo.save(u);
        UnitListingInterest i = new UnitListingInterest();
        i.setListingId(listingId);
        i.setRenterUserId(u.getId());
        i.setStatus(InterestStatus.ACTIVE);
        return interestRepo.save(i).getId();
    }

    @Test
    void anEnquiryBecomesADraftLeaseAndPostingItUnpublishesTheListing() {
        UUID interestId = interest("Twin Tariq");
        long renters = renterRepo.count();
        LeaseDTO draft = tx.execute(s -> service.createLease(fixtures.tenantId(), listingId, interestId));
        assertThat(draft.getStatus().name()).isEqualTo("DRAFT");
        assertThat(renterRepo.count()).isEqualTo(renters + 1);
        var renter = renterRepo.findById(draft.getRenterId()).orElseThrow();
        assertThat(renter.getNameEn()).isEqualTo("Twin Tariq");
        assertThat(renter.getUserId()).as("renter record only, no login").isNull();
        assertThat(interestRepo.findById(interestId).orElseThrow().getStatus()).isEqualTo(InterestStatus.CONVERTED);
        // PR #361 R1: the drawer still lists the converted enquiry, with its lease.
        var listed = tx.execute(s -> listings.listInterests(fixtures.tenantId(), listingId,
                org.springframework.data.domain.PageRequest.of(0, 10))).getContent();
        assertThat(listed).singleElement().satisfies(i -> {
            assertThat(i.status()).isEqualTo(InterestStatus.CONVERTED);
            assertThat(i.leaseId()).isEqualTo(draft.getId());
        });
        java.util.List<com.datagami.rentaxis.api.dto.lease.LeaseLineDTO> lines = tx.execute(s -> leaseService.getLines(draft.getId()));
        assertThat(lines)
                .anySatisfy(l -> {
                    assertThat(l.chargeTypeCode()).isEqualTo("RENT");
                    assertThat(l.grossAmount()).isEqualByComparingTo("60000");
                })
                .anySatisfy(l -> assertThat(l.chargeTypeCode()).isEqualTo("SECURITY_DEPOSIT"));

        // Posting a lease on the listed unit takes the listing off the marketplace.
        fixtures.postedLease(LocalDate.of(2026, 4, 20), LocalDate.of(2026, 5, 1), LocalDate.of(2027, 4, 30),
                java.util.List.of(LeaseTestFixtures.line("RENT", "60000")), 4, null);
        UnitListing after = listingRepo.findById(listingId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ListingStatus.UNLISTED);
        assertThat(after.getAvailableFrom()).isEqualTo(LocalDate.of(2027, 5, 1));
        assertThat(after.getUnpublishedForLeaseId()).isNotNull();

        // Vacant again without opting in: stays unlisted.
        tx.executeWithoutResult(s -> listings.syncAvailableFrom(fixtures.unit().getId(), null));
        assertThat(listingRepo.findById(listingId).orElseThrow().getStatus()).isEqualTo(ListingStatus.UNLISTED);
    }

    @Test
    void optedInTheListingIsPublishedAgainWhenTheUnitIsVacant() {
        tx.executeWithoutResult(s -> listings.setRepublishWhenVacant(fixtures.tenantId(), listingId, true));
        fixtures.postedLease(LocalDate.of(2026, 4, 20), LocalDate.of(2026, 5, 1), LocalDate.of(2027, 4, 30),
                java.util.List.of(LeaseTestFixtures.line("RENT", "60000")), 4, null);
        assertThat(listingRepo.findById(listingId).orElseThrow().getStatus()).isEqualTo(ListingStatus.UNLISTED);
        tx.executeWithoutResult(s -> listings.syncAvailableFrom(fixtures.unit().getId(), null));
        assertThat(listingRepo.findById(listingId).orElseThrow().getStatus()).isEqualTo(ListingStatus.PUBLISHED);
    }
}
