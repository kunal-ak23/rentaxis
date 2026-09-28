package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.UnitListingCreateRequest;
import com.datagami.rentaxis.api.dto.UnitListingMediaDTO;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.event.ListingPublishedEvent;
import com.datagami.rentaxis.core.event.ListingUnlistedEvent;
import com.datagami.rentaxis.domain.entity.UnitListing;
import com.datagami.rentaxis.domain.entity.UnitListingMedia;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.InterestStatus;
import com.datagami.rentaxis.domain.entity.enums.ListingStatus;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.UnitListingAmenityRepository;
import com.datagami.rentaxis.domain.repository.UnitListingInterestRepository;
import com.datagami.rentaxis.domain.repository.UnitListingMediaRepository;
import com.datagami.rentaxis.domain.repository.UnitListingRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UnitListingServiceTest {

    private UnitListingRepository listingRepository;
    private UnitListingAmenityRepository amenityRepository;
    private UnitListingMediaRepository mediaRepository;
    private UnitListingInterestRepository interestRepository;
    private UserRepository userRepository;
    private LeaseRepository leaseRepository;
    private UnitRepository unitRepository;
    private NotificationService notificationService;
    private SlugService slugService;
    private ApplicationEventPublisher eventPublisher;
    private BlobStorageService blobStorageService;
    private UnitListingService service;

    @BeforeEach
    void setUp() {
        listingRepository = mock(UnitListingRepository.class);
        amenityRepository = mock(UnitListingAmenityRepository.class);
        mediaRepository = mock(UnitListingMediaRepository.class);
        interestRepository = mock(UnitListingInterestRepository.class);
        userRepository = mock(UserRepository.class);
        leaseRepository = mock(LeaseRepository.class);
        unitRepository = mock(UnitRepository.class);
        notificationService = mock(NotificationService.class);
        slugService = new SlugService();
        eventPublisher = mock(ApplicationEventPublisher.class);
        blobStorageService = mock(BlobStorageService.class);
        service = new UnitListingService(
                listingRepository, amenityRepository, mediaRepository,
                interestRepository, userRepository, leaseRepository, unitRepository, notificationService,
                slugService, eventPublisher, blobStorageService);

        when(listingRepository.save(any(UnitListing.class))).thenAnswer(inv -> {
            UnitListing l = inv.getArgument(0);
            if (l.getId() == null) l.setId(UUID.randomUUID());
            return l;
        });
    }

    private UnitListingCreateRequest minimalCreate(String title) {
        return new UnitListingCreateRequest(
                UUID.randomUUID(), title, null, null, null, 2, 2,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void create_setsStatusDraft_andGeneratesUniqueSlug() {
        UUID tenantId = UUID.randomUUID();
        when(listingRepository.existsBySlugAndTenantId("nice-flat", tenantId)).thenReturn(false);

        UnitListing listing = service.create(tenantId, minimalCreate("Nice Flat"));

        assertThat(listing.getStatus()).isEqualTo(ListingStatus.DRAFT);
        assertThat(listing.getSlug()).isEqualTo("nice-flat");
        assertThat(listing.getTenantId()).isEqualTo(tenantId);
    }

    @Test
    void create_appendsCounterWhenSlugExists() {
        UUID tenantId = UUID.randomUUID();
        when(listingRepository.existsBySlugAndTenantId("nice-flat", tenantId)).thenReturn(true);
        when(listingRepository.existsBySlugAndTenantId("nice-flat-2", tenantId)).thenReturn(false);

        UnitListing listing = service.create(tenantId, minimalCreate("Nice Flat"));
        assertThat(listing.getSlug()).isEqualTo("nice-flat-2");
    }

    @Test
    void publish_setsStatusAndPublishesEvent() {
        UUID tenantId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        UnitListing existing = new UnitListing();
        existing.setId(id);
        existing.setTenantId(tenantId);
        when(listingRepository.findById(id)).thenReturn(Optional.of(existing));

        service.publish(tenantId, id);

        assertThat(existing.getStatus()).isEqualTo(ListingStatus.PUBLISHED);
        assertThat(existing.getPublishedAt()).isNotNull();
        verify(eventPublisher).publishEvent(any(ListingPublishedEvent.class));
    }

    @Test
    void unlist_setsStatusAndPublishesEvent() {
        UUID tenantId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        UnitListing existing = new UnitListing();
        existing.setId(id);
        existing.setTenantId(tenantId);
        existing.setStatus(ListingStatus.PUBLISHED);
        when(listingRepository.findById(id)).thenReturn(Optional.of(existing));

        service.unlist(tenantId, id);

        assertThat(existing.getStatus()).isEqualTo(ListingStatus.UNLISTED);
        verify(eventPublisher).publishEvent(any(ListingUnlistedEvent.class));
    }

    @Test
    void get_throwsWhenNotFound() {
        UUID tenantId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(listingRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(tenantId, id))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void get_throwsWhenWrongTenant() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        UnitListing existing = new UnitListing();
        existing.setId(id);
        existing.setTenantId(tenantA);
        when(listingRepository.findById(id)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.get(tenantB, id))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void addMedia_uploadsToBlob_andPersistsRow() {
        UUID tenantId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        UnitListing existing = new UnitListing();
        existing.setId(listingId);
        existing.setTenantId(tenantId);
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(existing));
        when(mediaRepository.findByListingIdOrderBySortOrderAsc(listingId)).thenReturn(List.of());
        String fakeUrl = "https://acct.blob.core.windows.net/tenant-" + tenantId + "/listings/" + listingId + "/abc.jpg";
        String fakePath = "listings/" + listingId + "/abc.jpg";
        when(blobStorageService.upload(any(), any(), any()))
                .thenReturn(new BlobStorageService.UploadResult(fakeUrl, fakePath));
        when(mediaRepository.save(any(UnitListingMedia.class))).thenAnswer(inv -> {
            UnitListingMedia m = inv.getArgument(0);
            if (m.getId() == null) m.setId(UUID.randomUUID());
            return m;
        });

        MockMultipartFile file = new MockMultipartFile("file", "hero.jpg", "image/jpeg", new byte[]{1});
        UnitListingMediaDTO dto = service.addMedia(tenantId, listingId, file, "Hero", true);

        assertThat(dto.url()).isEqualTo(fakeUrl);
        assertThat(dto.isCover()).isTrue();
        verify(blobStorageService).upload(tenantId, listingId, file);
        verify(mediaRepository).save(any(UnitListingMedia.class));
        verify(listingRepository, never()).delete(any(UnitListing.class));
    }

    @Test
    void countActiveInterests_countsOnlyActiveRows() {
        UUID listingId = UUID.randomUUID();
        when(interestRepository.countByListingIdAndStatus(listingId, InterestStatus.ACTIVE))
                .thenReturn(4L);

        assertThat(service.countActiveInterests(listingId)).isEqualTo(4L);
    }

    @Test
    void getSummaryData_batchesPropertyMediaAndInterestLookups() {
        UUID listingId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        UnitListing listing = new UnitListing();
        listing.setId(listingId);
        listing.setUnitId(unitId);

        Property property = new Property();
        property.setNameEn("Marina Heights");
        Unit unit = new Unit();
        unit.setId(unitId);
        unit.setProperty(property);

        UnitListingMedia first = new UnitListingMedia();
        first.setListingId(listingId);
        first.setUrl("https://cdn/first.jpg");
        first.setIsCover(false);
        UnitListingMedia cover = new UnitListingMedia();
        cover.setListingId(listingId);
        cover.setUrl("https://cdn/cover.jpg");
        cover.setIsCover(true);

        UnitListingInterestRepository.ListingInterestCount count =
                mock(UnitListingInterestRepository.ListingInterestCount.class);
        when(count.getListingId()).thenReturn(listingId);
        when(count.getInterestCount()).thenReturn(3L);
        when(unitRepository.findByIdIn(List.of(unitId))).thenReturn(List.of(unit));
        when(mediaRepository.findByListingIdInOrderByListingIdAscSortOrderAsc(List.of(listingId)))
                .thenReturn(List.of(first, cover));
        when(interestRepository.countByListingIdsAndStatus(List.of(listingId), InterestStatus.ACTIVE))
                .thenReturn(List.of(count));

        Map<UUID, UnitListingService.ListingSummaryData> result = service.getSummaryData(List.of(listing));

        assertThat(result.get(listingId).propertyName()).isEqualTo("Marina Heights");
        assertThat(result.get(listingId).coverPhotoUrl()).isEqualTo("https://cdn/cover.jpg");
        assertThat(result.get(listingId).interestsCount()).isEqualTo(3L);
        verify(unitRepository).findByIdIn(List.of(unitId));
        verify(mediaRepository).findByListingIdInOrderByListingIdAscSortOrderAsc(List.of(listingId));
        verify(interestRepository).countByListingIdsAndStatus(List.of(listingId), InterestStatus.ACTIVE);
    }

    // ---- break-it R3 ops3 F10: a let unit is not published as available ----

    private UnitListing draftOn(UUID tenantId, UUID unitId, java.time.LocalDate availableFrom) {
        UnitListing l = new UnitListing();
        l.setId(UUID.randomUUID());
        l.setTenantId(tenantId);
        l.setUnitId(unitId);
        l.setStatus(ListingStatus.DRAFT);
        l.setAvailableFrom(availableFrom);
        when(listingRepository.findById(l.getId())).thenReturn(Optional.of(l));
        return l;
    }

    private void currentLease(UUID tenantId, UUID unitId, java.time.LocalDate end) {
        com.datagami.rentaxis.domain.entity.Lease lease = new com.datagami.rentaxis.domain.entity.Lease();
        lease.setTenantId(tenantId);
        lease.setEndDate(end);
        lease.setStatus(com.datagami.rentaxis.domain.entity.enums.LeaseStatus.ACTIVE);
        when(leaseRepository.findByUnitIdAndStatusIn(org.mockito.ArgumentMatchers.eq(unitId), any()))
                .thenReturn(List.of(lease));
    }

    @Test
    void publish_unitLetPastAvailableFrom_isRefused() {
        UUID tenantId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        currentLease(tenantId, unitId, java.time.LocalDate.of(2027, 4, 30));

        UnitListing availableNow = draftOn(tenantId, unitId, null);
        assertThatThrownBy(() -> service.publish(tenantId, availableNow.getId()))
                .isInstanceOf(com.datagami.rentaxis.api.exception.BusinessRuleViolationException.class)
                .satisfies(e -> assertThat(((com.datagami.rentaxis.api.exception.BusinessRuleViolationException) e).getCode())
                        .isEqualTo("listing.unitLet"));
        UnitListing onTheLastDay = draftOn(tenantId, unitId, java.time.LocalDate.of(2027, 4, 30));
        assertThatThrownBy(() -> service.publish(tenantId, onTheLastDay.getId()))
                .isInstanceOf(com.datagami.rentaxis.api.exception.BusinessRuleViolationException.class);
        assertThat(availableNow.getStatus()).isEqualTo(ListingStatus.DRAFT);
        verify(eventPublisher, never()).publishEvent(any(ListingPublishedEvent.class));
    }

    @Test
    void publish_preMarketingAfterTheLeaseEnds_isAllowed() {
        UUID tenantId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        currentLease(tenantId, unitId, java.time.LocalDate.of(2027, 4, 30));
        UnitListing l = draftOn(tenantId, unitId, java.time.LocalDate.of(2027, 5, 1));

        service.publish(tenantId, l.getId());

        assertThat(l.getStatus()).isEqualTo(ListingStatus.PUBLISHED);
    }

    @Test
    void update_movingALiveListingIntoTheLease_isRefused() {
        UUID tenantId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        currentLease(tenantId, unitId, java.time.LocalDate.of(2027, 4, 30));
        UnitListing l = draftOn(tenantId, unitId, java.time.LocalDate.of(2027, 5, 1));
        l.setStatus(ListingStatus.PUBLISHED);
        java.time.LocalDate inside = java.time.LocalDate.of(2027, 1, 1);
        com.datagami.rentaxis.api.dto.UnitListingUpdateRequest req = new com.datagami.rentaxis.api.dto.UnitListingUpdateRequest(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, inside, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.update(tenantId, l.getId(), req))
                .isInstanceOf(com.datagami.rentaxis.api.exception.BusinessRuleViolationException.class);
        verify(listingRepository, never()).save(l);
    }

    // ---- review r3C I2: under notice, the unit is let until the move-out date ----

    @Test
    void publish_underNoticeWithAMoveOutDate_usesTheMoveOutDate() {
        UUID tenantId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        com.datagami.rentaxis.domain.entity.Lease lease = new com.datagami.rentaxis.domain.entity.Lease();
        lease.setTenantId(tenantId);
        lease.setEndDate(java.time.LocalDate.of(2027, 4, 30));
        lease.setIntendedMoveOutDate(java.time.LocalDate.of(2026, 11, 30));
        lease.setStatus(com.datagami.rentaxis.domain.entity.enums.LeaseStatus.NOTICE_GIVEN);
        when(leaseRepository.findByUnitIdAndStatusIn(org.mockito.ArgumentMatchers.eq(unitId), any()))
                .thenReturn(List.of(lease));

        UnitListing onMoveOut = draftOn(tenantId, unitId, java.time.LocalDate.of(2026, 11, 30));
        assertThatThrownBy(() -> service.publish(tenantId, onMoveOut.getId()))
                .isInstanceOf(com.datagami.rentaxis.api.exception.BusinessRuleViolationException.class)
                .satisfies(e -> assertThat(((com.datagami.rentaxis.api.exception.BusinessRuleViolationException) e).getArgs())
                        .containsEntry("end", "30/11/2026").containsEntry("next", "01/12/2026"));

        UnitListing dayAfter = draftOn(tenantId, unitId, java.time.LocalDate.of(2026, 12, 1));
        service.publish(tenantId, dayAfter.getId());
        assertThat(dayAfter.getStatus()).isEqualTo(ListingStatus.PUBLISHED);
    }

    @Test
    void publish_activeLeaseWithAStrayMoveOutDate_stillUsesTheContractEnd() {
        UUID tenantId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        com.datagami.rentaxis.domain.entity.Lease lease = new com.datagami.rentaxis.domain.entity.Lease();
        lease.setTenantId(tenantId);
        lease.setEndDate(java.time.LocalDate.of(2027, 4, 30));
        lease.setIntendedMoveOutDate(java.time.LocalDate.of(2026, 11, 30));
        lease.setStatus(com.datagami.rentaxis.domain.entity.enums.LeaseStatus.ACTIVE);
        when(leaseRepository.findByUnitIdAndStatusIn(org.mockito.ArgumentMatchers.eq(unitId), any()))
                .thenReturn(List.of(lease));
        UnitListing l = draftOn(tenantId, unitId, java.time.LocalDate.of(2026, 12, 1));
        assertThatThrownBy(() -> service.publish(tenantId, l.getId()))
                .isInstanceOf(com.datagami.rentaxis.api.exception.BusinessRuleViolationException.class);
    }
}
