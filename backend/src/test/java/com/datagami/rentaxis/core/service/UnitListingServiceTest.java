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
        cover.setUrl("https://acct.blob.core.windows.net/tenant-id/listings%2Flisting-id%2Fcover.jpg");
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
        assertThat(result.get(listingId).coverPhotoUrl())
                .isEqualTo("https://acct.blob.core.windows.net/tenant-id/listings/listing-id/cover.jpg");
        assertThat(result.get(listingId).interestsCount()).isEqualTo(3L);
        verify(unitRepository).findByIdIn(List.of(unitId));
        verify(mediaRepository).findByListingIdInOrderByListingIdAscSortOrderAsc(List.of(listingId));
        verify(interestRepository).countByListingIdsAndStatus(List.of(listingId), InterestStatus.ACTIVE);
    }
}
