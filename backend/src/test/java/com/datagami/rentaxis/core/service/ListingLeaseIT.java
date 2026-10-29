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
    @Autowired MarketplaceService marketplace;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
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
        return interest(name, "+971501234567", "enquirer-" + UUID.randomUUID() + "@it.test");
    }

    private UUID interest(String name, String accountPhone, String email) {
        User u = new User();
        u.setRole(UserRole.RENTER);
        u.setEmail(email);
        u.setPhoneNumber(accountPhone);
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
        jdbc.update("update leases set status = 'TERMINATED' where unit_id = ? and tenant_id = ?",
                fixtures.unit().getId(), fixtures.tenantId());
        tx.executeWithoutResult(s -> listings.syncAvailableFrom(fixtures.unit().getId(), null));
        assertThat(listingRepo.findById(listingId).orElseThrow().getStatus()).isEqualTo(ListingStatus.PUBLISHED);
    }

    /**
     * #101: the lease that ended was not the unit's last — the next renter's lease is
     * already posted on it. The unit is not vacant: an opted-in listing stays unlisted
     * and is available from the day after that lease, not "now".
     */
    @Test
    void aListingIsNotRepublishedWhileAnotherPostedLeaseHoldsTheUnit() {
        tx.executeWithoutResult(s -> listings.setRepublishWhenVacant(fixtures.tenantId(), listingId, true));
        UUID first = fixtures.postedLease(LocalDate.of(2026, 4, 20), LocalDate.of(2026, 5, 1), LocalDate.of(2027, 4, 30),
                java.util.List.of(LeaseTestFixtures.line("RENT", "60000")), 4, null).lease().getId();
        fixtures.postedLease(fixtures.unit(), fixtures.createRenter("Next Renter"), LocalDate.of(2026, 9, 1),
                LocalDate.of(2027, 5, 1), LocalDate.of(2028, 4, 30),
                java.util.List.of(LeaseTestFixtures.line("RENT", "62000")), 4, null);
        jdbc.update("update leases set status = 'TERMINATED' where id = ?", first);
        tx.executeWithoutResult(s -> listings.syncAvailableFrom(fixtures.unit().getId(), null));
        UnitListing after = listingRepo.findById(listingId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ListingStatus.UNLISTED);
        assertThat(after.getAvailableFrom()).isEqualTo(LocalDate.of(2028, 5, 1));
    }

    /** Break-it R3 ops3 F10: a unit with a posted lease cannot be (re)published as available, nor shown so publicly. */
    @Test
    void aLetUnitCannotBePublishedAsAvailableAndTheMarketplaceHidesIt() {
        fixtures.postedLease(LocalDate.of(2026, 4, 20), LocalDate.of(2026, 5, 1), LocalDate.of(2027, 4, 30),
                java.util.List.of(LeaseTestFixtures.line("RENT", "60000")), 4, null);
        // Posting unlisted it and moved availability past the lease end.
        UnitListing l = listingRepo.findById(listingId).orElseThrow();
        assertThat(l.getStatus()).isEqualTo(ListingStatus.UNLISTED);

        // Clear the date: "available now" while let → refused.
        l.setAvailableFrom(null);
        listingRepo.save(l);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tx.executeWithoutResult(s -> listings.publish(fixtures.tenantId(), listingId)))
                .satisfies(e -> assertThat(((com.datagami.rentaxis.api.exception.BusinessRuleViolationException) e).getCode())
                        .isEqualTo("listing.unitLet"));
        assertThat(listingRepo.findById(listingId).orElseThrow().getStatus()).isEqualTo(ListingStatus.UNLISTED);

        // A listing published before the guard existed never shows on the marketplace.
        UnitListing legacy = listingRepo.findById(listingId).orElseThrow();
        legacy.setStatus(ListingStatus.PUBLISHED);
        listingRepo.save(legacy);
        var page = tx.execute(s -> marketplace.search(fixtures.tenantId(), null, org.springframework.data.domain.PageRequest.of(0, 50)));
        assertThat(page.getContent()).extracting(UnitListing::getId).doesNotContain(listingId);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tx.execute(s -> marketplace.getBySlug(fixtures.tenantId(), legacy.getSlug())))
                .isInstanceOf(com.datagami.rentaxis.api.exception.NotFoundException.class);

        // Pre-marketing: available from the day after the lease ends → allowed and shown.
        legacy.setStatus(ListingStatus.UNLISTED);
        legacy.setAvailableFrom(LocalDate.of(2027, 5, 1));
        listingRepo.save(legacy);
        tx.executeWithoutResult(s -> listings.publish(fixtures.tenantId(), listingId));
        assertThat(listingRepo.findById(listingId).orElseThrow().getStatus()).isEqualTo(ListingStatus.PUBLISHED);
        var after = tx.execute(s -> marketplace.search(fixtures.tenantId(), null, org.springframework.data.domain.PageRequest.of(0, 50)));
        assertThat(after.getContent()).extracting(UnitListing::getId).contains(listingId);
    }

    /** Review r3C I2: after notice with a move-out date, pre-marketing from the day after move-out is allowed and shown. */
    @Test
    void underNoticeTheListingMayBePreMarketedFromTheDayAfterMoveOut() {
        fixtures.postedLease(LocalDate.of(2026, 4, 20), LocalDate.of(2026, 5, 1), LocalDate.of(2027, 4, 30),
                java.util.List.of(LeaseTestFixtures.line("RENT", "60000")), 4, null);
        int noticed = jdbc.update("update leases set status = 'NOTICE_GIVEN', intended_move_out_date = ? where tenant_id = ?",
                LocalDate.of(2026, 11, 30), fixtures.tenantId());
        assertThat(noticed).isOne();

        UnitListing l = listingRepo.findById(listingId).orElseThrow();
        l.setAvailableFrom(LocalDate.of(2026, 11, 30));   // move-out day itself: still let
        listingRepo.save(l);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tx.executeWithoutResult(s -> listings.publish(fixtures.tenantId(), listingId)))
                .satisfies(e -> assertThat(((com.datagami.rentaxis.api.exception.BusinessRuleViolationException) e).getArgs())
                        .containsEntry("next", "01/12/2026"));

        l = listingRepo.findById(listingId).orElseThrow();
        l.setAvailableFrom(LocalDate.of(2026, 12, 1));
        listingRepo.save(l);
        tx.executeWithoutResult(s -> listings.publish(fixtures.tenantId(), listingId));
        var page = tx.execute(s -> marketplace.search(fixtures.tenantId(), null, org.springframework.data.domain.PageRequest.of(0, 50)));
        assertThat(page.getContent()).extracting(UnitListing::getId).contains(listingId);
        UnitListing shown = tx.execute(s -> marketplace.getBySlug(fixtures.tenantId(),
                listingRepo.findById(listingId).orElseThrow().getSlug()));
        assertThat(shown.getId()).isEqualTo(listingId);
    }

    // ------------------------------------------------------------------
    // Every tenant has a mobile (client request 2026-10-29; ruling: the
    // convert dialog asks for it when the enquirer's account has none).
    // ------------------------------------------------------------------

    private com.datagami.rentaxis.api.exception.BusinessRuleViolationException refusal(UUID interestId, String phone) {
        Throwable t = org.assertj.core.api.Assertions.catchThrowable(() ->
                tx.execute(s -> service.createLease(fixtures.tenantId(), listingId, interestId, phone)));
        assertThat(t).isInstanceOf(com.datagami.rentaxis.api.exception.BusinessRuleViolationException.class);
        return (com.datagami.rentaxis.api.exception.BusinessRuleViolationException) t;
    }

    @Test
    void anEnquirerWithNoMobileIsNotMadeATenantUntilOneIsGiven() {
        UUID interestId = interest("Noor Haddad", null, "noor-" + UUID.randomUUID() + "@it.test");
        long renters = renterRepo.count();

        assertThat(refusal(interestId, null).getCode()).isEqualTo("listing.tenantMobileRequired");
        assertThat(refusal(interestId, "\u00A0").getCode()).isEqualTo("listing.tenantMobileRequired");
        assertThat(refusal(interestId, "N/A").getCode()).isEqualTo("listing.tenantMobileInvalid");
        assertThat(renterRepo.count()).as("nothing created").isEqualTo(renters);
        assertThat(interestRepo.findById(interestId).orElseThrow().getStatus()).isEqualTo(InterestStatus.ACTIVE);

        LeaseDTO draft = tx.execute(s -> service.createLease(fixtures.tenantId(), listingId, interestId, "٠٥٠ ١٢٣ ٤٥٦٧"));
        assertThat(renterRepo.findById(draft.getRenterId()).orElseThrow().getPhone()).isEqualTo("050 123 4567");
    }

    @Test
    void theEnquirersOwnMobileIsUsedWhenTheirAccountHasOne() {
        UUID interestId = interest("Omar Saeed", "+971 55 765 4321", "omar-" + UUID.randomUUID() + "@it.test");
        LeaseDTO draft = tx.execute(s -> service.createLease(fixtures.tenantId(), listingId, interestId, null));
        assertThat(renterRepo.findById(draft.getRenterId()).orElseThrow().getPhone()).isEqualTo("+971 55 765 4321");
    }

    @Test
    void anExistingTenantWithNoMobileTakesTheOneGivenAndOneOnRecordIsKept() {
        String email = "layla-" + UUID.randomUUID() + "@it.test";
        com.datagami.rentaxis.domain.entity.Renter existing = new com.datagami.rentaxis.domain.entity.Renter();
        existing.setNameEn("Layla Haddad");
        existing.setEmail(email);
        UUID existingId = tx.execute(s -> renterRepo.save(existing).getId());

        UUID first = interest("Layla Haddad", null, email);
        assertThat(refusal(first, null).getCode()).isEqualTo("listing.tenantMobileRequired");
        LeaseDTO draft = tx.execute(s -> service.createLease(fixtures.tenantId(), listingId, first, "050 222 3333"));
        assertThat(draft.getRenterId()).isEqualTo(existingId);
        assertThat(renterRepo.findById(existingId).orElseThrow().getPhone()).isEqualTo("050 222 3333");
    }
}
