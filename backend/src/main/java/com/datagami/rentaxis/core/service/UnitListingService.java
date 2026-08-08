package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.InterestDTO;
import com.datagami.rentaxis.api.dto.UnitListingCreateRequest;
import com.datagami.rentaxis.api.dto.UnitListingMediaDTO;
import com.datagami.rentaxis.api.dto.UnitListingUpdateRequest;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.event.ListingPublishedEvent;
import com.datagami.rentaxis.core.event.ListingUnlistedEvent;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.UnitListing;
import com.datagami.rentaxis.domain.entity.UnitListingAmenityEntry;
import com.datagami.rentaxis.domain.entity.UnitListingInterest;
import com.datagami.rentaxis.domain.entity.UnitListingMedia;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.InterestStatus;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.ListingMediaType;
import com.datagami.rentaxis.domain.entity.enums.ListingStatus;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.UnitListingAmenityRepository;
import com.datagami.rentaxis.domain.repository.UnitListingInterestRepository;
import com.datagami.rentaxis.domain.repository.UnitListingMediaRepository;
import com.datagami.rentaxis.domain.repository.UnitListingRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.Set;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional
@Slf4j
public class UnitListingService {

    private final UnitListingRepository listingRepository;
    private final UnitListingAmenityRepository amenityRepository;
    private final UnitListingMediaRepository mediaRepository;
    private final UnitListingInterestRepository interestRepository;
    private final UserRepository userRepository;
    private final LeaseRepository leaseRepository;
    private final UnitRepository unitRepository;
    private final NotificationService notificationService;
    private final SlugService slugService;
    private final ApplicationEventPublisher eventPublisher;
    private final BlobStorageService blobStorageService;

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "application/pdf");

    @Value("${rentaxis.listings.max-media-size-bytes:10485760}")
    private long maxMediaSizeBytes = 10_485_760L;

    public UnitListingService(
            UnitListingRepository listingRepository,
            UnitListingAmenityRepository amenityRepository,
            UnitListingMediaRepository mediaRepository,
            UnitListingInterestRepository interestRepository,
            UserRepository userRepository,
            LeaseRepository leaseRepository,
            UnitRepository unitRepository,
            NotificationService notificationService,
            SlugService slugService,
            ApplicationEventPublisher eventPublisher,
            BlobStorageService blobStorageService) {
        this.listingRepository = listingRepository;
        this.amenityRepository = amenityRepository;
        this.mediaRepository = mediaRepository;
        this.interestRepository = interestRepository;
        this.userRepository = userRepository;
        this.leaseRepository = leaseRepository;
        this.unitRepository = unitRepository;
        this.notificationService = notificationService;
        this.slugService = slugService;
        this.eventPublisher = eventPublisher;
        this.blobStorageService = blobStorageService;
    }

    @Transactional(readOnly = true)
    public Page<UnitListing> list(UUID tenantId, ListingStatus statusFilter, Pageable pageable) {
        if (statusFilter != null) {
            return listingRepository.findByTenantIdAndStatus(tenantId, statusFilter, pageable);
        }
        return listingRepository.findByTenantId(tenantId, pageable);
    }

    @Transactional(readOnly = true)
    public UnitListing get(UUID tenantId, UUID id) {
        UnitListing listing = listingRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Listing not found"));
        if (!Objects.equals(listing.getTenantId(), tenantId)) {
            throw new NotFoundException("Listing not found");
        }
        return listing;
    }

    public UnitListing create(UUID tenantId, UnitListingCreateRequest req) {
        UnitListing listing = new UnitListing();
        listing.setTenantId(tenantId);
        listing.setUnitId(req.unitId());
        listing.setStatus(ListingStatus.DRAFT);
        applyCreate(listing, req);

        // Auto-populate availableFrom from active lease end date if not provided
        if (listing.getAvailableFrom() == null && req.unitId() != null) {
            leaseRepository.findByUnitIdAndStatus(req.unitId(), LeaseStatus.ACTIVE).stream()
                    .findFirst()
                    .ifPresent(lease -> listing.setAvailableFrom(lease.getEndDate()));
        }

        String base = slugService.slugify(req.titleEn() == null ? "listing" : req.titleEn());
        if (base.isEmpty()) {
            base = "listing";
        }
        String slug = slugService.uniqueSlug(base,
                candidate -> listingRepository.existsBySlugAndTenantId(candidate, tenantId));
        listing.setSlug(slug);

        UnitListing saved = listingRepository.save(listing);
        replaceAmenities(saved.getId(), req.amenities());
        return saved;
    }

    /**
     * Syncs the availableFrom date on the listing for the given unit, then notifies
     * all renters who have shown active interest. Pass {@code null} to clear the date
     * (unit available immediately, e.g. after termination).
     */
    public void syncAvailableFrom(UUID unitId, LocalDate date) {
        listingRepository.findByUnitId(unitId).ifPresent(listing -> {
            listing.setAvailableFrom(date);
            listingRepository.save(listing);

            // Notify active interest holders
            List<UnitListingInterest> interests = interestRepository
                    .findByListingIdAndStatus(listing.getId(), InterestStatus.ACTIVE);
            for (UnitListingInterest interest : interests) {
                try {
                    String message = date == null
                            ? "The unit you expressed interest in is now available."
                            : "The lease on a unit you expressed interest in has been updated. It will be available from " + date + ".";
                    notificationService.notify(
                            listing.getTenantId(),
                            interest.getRenterUserId(),
                            date == null ? "LISTING_NOW_AVAILABLE" : "LISTING_AVAILABILITY_UPDATED",
                            "Listing Availability Update",
                            message,
                            "LISTING",
                            listing.getId());
                } catch (Exception e) {
                    log.warn("Failed to notify renter {} about listing availability change for listing {}",
                            interest.getRenterUserId(), listing.getId(), e);
                }
            }
        });
    }

    public UnitListing update(UUID tenantId, UUID id, UnitListingUpdateRequest req) {
        UnitListing listing = get(tenantId, id);
        applyUpdate(listing, req);
        UnitListing saved = listingRepository.save(listing);
        if (req.amenities() != null) {
            replaceAmenities(saved.getId(), req.amenities());
        }
        return saved;
    }

    public void publish(UUID tenantId, UUID id) {
        UnitListing listing = get(tenantId, id);
        listing.setStatus(ListingStatus.PUBLISHED);
        listing.setPublishedAt(LocalDateTime.now());
        listingRepository.save(listing);
        eventPublisher.publishEvent(new ListingPublishedEvent(listing.getId(), tenantId));
    }

    public void unlist(UUID tenantId, UUID id) {
        UnitListing listing = get(tenantId, id);
        listing.setStatus(ListingStatus.UNLISTED);
        listingRepository.save(listing);
        eventPublisher.publishEvent(new ListingUnlistedEvent(listing.getId(), tenantId));
    }

    public void delete(UUID tenantId, UUID id) {
        UnitListing listing = get(tenantId, id);
        listingRepository.delete(listing);
    }

    @Transactional(readOnly = true)
    public Page<InterestDTO> listInterests(UUID tenantId, UUID listingId, Pageable pageable) {
        get(tenantId, listingId);
        Page<UnitListingInterest> page = interestRepository.findByListingIdAndStatus(
                listingId, InterestStatus.ACTIVE, pageable);

        List<UUID> renterIds = page.getContent().stream()
                .map(UnitListingInterest::getRenterUserId)
                .toList();
        java.util.Map<UUID, User> usersById = new java.util.HashMap<>();
        if (!renterIds.isEmpty()) {
            for (User u : userRepository.findAllById(renterIds)) {
                usersById.put(u.getId(), u);
            }
        }

        return page.map(interest -> {
            User user = usersById.get(interest.getRenterUserId());
            return new InterestDTO(
                    interest.getId(),
                    interest.getListingId(),
                    interest.getRenterUserId(),
                    user == null ? null : user.getName(),
                    user == null ? null : user.getEmail(),
                    user == null ? null : user.getPhoneNumber(),
                    interest.getNote(),
                    interest.getStatus(),
                    interest.getCreatedAt()
            );
        });
    }

    @Transactional(readOnly = true)
    public List<UnitListingMediaDTO> listMedia(UUID listingId) {
        return mediaRepository.findByListingIdOrderBySortOrderAsc(listingId).stream()
                .map(this::toMediaDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public long countActiveInterests(UUID listingId) {
        return interestRepository.countByListingIdAndStatus(listingId, InterestStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public Map<UUID, ListingSummaryData> getSummaryData(List<UnitListing> listings) {
        if (listings.isEmpty()) {
            return Map.of();
        }

        List<UUID> listingIds = listings.stream().map(UnitListing::getId).toList();
        Map<UUID, String> propertyNamesByUnitId = new HashMap<>();
        unitRepository.findByIdIn(listings.stream().map(UnitListing::getUnitId).distinct().toList())
                .forEach(unit -> propertyNamesByUnitId.put(
                        unit.getId(),
                        unit.getProperty() == null ? null : unit.getProperty().getNameEn()));

        Map<UUID, String> coverUrlsByListingId = new HashMap<>();
        mediaRepository.findByListingIdInOrderByListingIdAscSortOrderAsc(listingIds)
                .forEach(media -> coverUrlsByListingId.merge(
                        media.getListingId(),
                        media.getUrl(),
                        (current, candidate) -> Boolean.TRUE.equals(media.getIsCover()) ? candidate : current));

        Map<UUID, Long> interestCountsByListingId = new HashMap<>();
        interestRepository.countByListingIdsAndStatus(listingIds, InterestStatus.ACTIVE)
                .forEach(count -> interestCountsByListingId.put(count.getListingId(), count.getInterestCount()));

        Map<UUID, ListingSummaryData> result = new HashMap<>();
        listings.forEach(listing -> result.put(
                listing.getId(),
                new ListingSummaryData(
                        propertyNamesByUnitId.get(listing.getUnitId()),
                        coverUrlsByListingId.get(listing.getId()),
                        interestCountsByListingId.getOrDefault(listing.getId(), 0L))));
        return result;
    }

    public record ListingSummaryData(String propertyName, String coverPhotoUrl, long interestsCount) {
    }

    @Transactional(readOnly = true)
    public List<UnitListingAmenityEntry> listAmenities(UUID listingId) {
        return amenityRepository.findByListingId(listingId);
    }

    // ---- Media ----

    public UnitListingMediaDTO addMedia(UUID tenantId, UUID listingId, MultipartFile file,
                                        String caption, Boolean isCover) {
        get(tenantId, listingId);
        validateMediaUpload(file);
        BlobStorageService.UploadResult uploaded = blobStorageService.upload(tenantId, listingId, file);
        String url = uploaded.url();
        String blobPath = uploaded.blobPath();

        ListingMediaType type = inferMediaType(file);
        List<UnitListingMedia> siblings = mediaRepository.findByListingIdOrderBySortOrderAsc(listingId);
        int nextSort = siblings.stream()
                .map(UnitListingMedia::getSortOrder)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(-1) + 1;

        if (Boolean.TRUE.equals(isCover)) {
            for (UnitListingMedia sibling : siblings) {
                if (Boolean.TRUE.equals(sibling.getIsCover())) {
                    sibling.setIsCover(false);
                    mediaRepository.save(sibling);
                }
            }
        }

        UnitListingMedia media = new UnitListingMedia();
        media.setListingId(listingId);
        media.setMediaType(type);
        media.setUrl(url);
        media.setCaption(caption);
        media.setSortOrder(nextSort);
        media.setIsCover(Boolean.TRUE.equals(isCover));
        media.setBlobPath(blobPath);
        UnitListingMedia saved = mediaRepository.save(media);
        return toMediaDto(saved);
    }

    public void removeMedia(UUID tenantId, UUID listingId, UUID mediaId) {
        get(tenantId, listingId);
        UnitListingMedia media = mediaRepository.findById(mediaId)
                .orElseThrow(() -> new NotFoundException("Media not found"));
        if (!Objects.equals(media.getListingId(), listingId)) {
            throw new NotFoundException("Media not found");
        }
        String blobPath = media.getBlobPath();
        if (blobPath == null) {
            blobPath = extractBlobPath(media.getUrl());
        }
        if (blobPath != null) {
            blobStorageService.delete(tenantId, blobPath);
        }
        mediaRepository.delete(media);
    }

    public void reorderMedia(UUID tenantId, UUID listingId, List<UUID> orderedMediaIds) {
        get(tenantId, listingId);
        List<UnitListingMedia> siblings = mediaRepository.findByListingIdOrderBySortOrderAsc(listingId);
        for (UUID id : orderedMediaIds) {
            if (siblings.stream().noneMatch(m -> Objects.equals(m.getId(), id))) {
                throw new NotFoundException("Media not found in listing: " + id);
            }
        }
        int order = 0;
        for (UUID id : orderedMediaIds) {
            for (UnitListingMedia m : siblings) {
                if (Objects.equals(m.getId(), id)) {
                    m.setSortOrder(order++);
                    mediaRepository.save(m);
                    break;
                }
            }
        }
    }

    // ---- Helpers ----

    private void applyCreate(UnitListing listing, UnitListingCreateRequest r) {
        listing.setTitleEn(r.titleEn());
        listing.setTitleAr(r.titleAr());
        listing.setDescriptionEn(r.descriptionEn());
        listing.setDescriptionAr(r.descriptionAr());
        listing.setBedrooms(r.bedrooms());
        listing.setBathrooms(r.bathrooms());
        listing.setSizeSqft(r.sizeSqft());
        listing.setFloor(r.floor());
        listing.setParkingSpaces(r.parkingSpaces());
        listing.setFurnishing(r.furnishing());
        listing.setViewType(r.viewType());
        listing.setAnnualRent(r.annualRent());
        listing.setSecurityDeposit(r.securityDeposit());
        listing.setMinLeaseMonths(r.minLeaseMonths());
        listing.setChequesAccepted(r.chequesAccepted());
        if (r.dewaIncluded() != null) listing.setDewaIncluded(r.dewaIncluded());
        if (r.chillerIncluded() != null) listing.setChillerIncluded(r.chillerIncluded());
        listing.setUtilitiesEstimate(r.utilitiesEstimate());
        listing.setAvailableFrom(r.availableFrom());
        listing.setSeoTitle(r.seoTitle());
        listing.setSeoDescription(r.seoDescription());
        listing.setSeoKeywords(r.seoKeywords());
        listing.setOgImageUrl(r.ogImageUrl());
        listing.setLat(r.lat());
        listing.setLng(r.lng());
    }

    private void applyUpdate(UnitListing listing, UnitListingUpdateRequest r) {
        if (r.titleEn() != null) listing.setTitleEn(r.titleEn());
        if (r.titleAr() != null) listing.setTitleAr(r.titleAr());
        if (r.descriptionEn() != null) listing.setDescriptionEn(r.descriptionEn());
        if (r.descriptionAr() != null) listing.setDescriptionAr(r.descriptionAr());
        if (r.bedrooms() != null) listing.setBedrooms(r.bedrooms());
        if (r.bathrooms() != null) listing.setBathrooms(r.bathrooms());
        if (r.sizeSqft() != null) listing.setSizeSqft(r.sizeSqft());
        if (r.floor() != null) listing.setFloor(r.floor());
        if (r.parkingSpaces() != null) listing.setParkingSpaces(r.parkingSpaces());
        if (r.furnishing() != null) listing.setFurnishing(r.furnishing());
        if (r.viewType() != null) listing.setViewType(r.viewType());
        if (r.annualRent() != null) listing.setAnnualRent(r.annualRent());
        if (r.securityDeposit() != null) listing.setSecurityDeposit(r.securityDeposit());
        if (r.minLeaseMonths() != null) listing.setMinLeaseMonths(r.minLeaseMonths());
        if (r.chequesAccepted() != null) listing.setChequesAccepted(r.chequesAccepted());
        if (r.dewaIncluded() != null) listing.setDewaIncluded(r.dewaIncluded());
        if (r.chillerIncluded() != null) listing.setChillerIncluded(r.chillerIncluded());
        if (r.utilitiesEstimate() != null) listing.setUtilitiesEstimate(r.utilitiesEstimate());
        if (r.availableFrom() != null) listing.setAvailableFrom(r.availableFrom());
        if (r.seoTitle() != null) listing.setSeoTitle(r.seoTitle());
        if (r.seoDescription() != null) listing.setSeoDescription(r.seoDescription());
        if (r.seoKeywords() != null) listing.setSeoKeywords(r.seoKeywords());
        if (r.ogImageUrl() != null) listing.setOgImageUrl(r.ogImageUrl());
        if (r.lat() != null) listing.setLat(r.lat());
        if (r.lng() != null) listing.setLng(r.lng());
    }

    private void replaceAmenities(UUID listingId, List<UnitListingCreateRequest.AmenityRequest> amenities) {
        amenityRepository.deleteByListingId(listingId);
        if (amenities == null) {
            return;
        }
        for (UnitListingCreateRequest.AmenityRequest a : amenities) {
            UnitListingAmenityEntry entry = new UnitListingAmenityEntry();
            entry.setListingId(listingId);
            entry.setAmenity(a.amenity());
            entry.setCustomLabel(a.customLabel());
            amenityRepository.save(entry);
        }
    }

    private void validateMediaUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Media file is required");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            throw new IllegalArgumentException(
                    "Unsupported media type: " + contentType
                            + ". Allowed: " + ALLOWED_CONTENT_TYPES);
        }
        if (file.getSize() > maxMediaSizeBytes) {
            throw new IllegalArgumentException(
                    "Media file exceeds maximum size of " + maxMediaSizeBytes + " bytes");
        }
    }

    private ListingMediaType inferMediaType(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name != null && name.toLowerCase().endsWith(".pdf")) {
            return ListingMediaType.FLOOR_PLAN;
        }
        return ListingMediaType.PHOTO;
    }

    /**
     * Extracts the container-relative blob path from the full URL.
     * E.g. https://acct.blob.core.windows.net/tenant-{id}/listings/{listingId}/file.jpg
     *   -> listings/{listingId}/file.jpg
     */
    static String extractBlobPath(String url) {
        if (url == null) return null;
        int idx = url.indexOf("/listings/");
        if (idx < 0) return null;
        return url.substring(idx + 1);
    }

    private UnitListingMediaDTO toMediaDto(UnitListingMedia m) {
        return new UnitListingMediaDTO(
                m.getId(), m.getMediaType(), m.getUrl(),
                m.getCaption(), m.getSortOrder(), m.getIsCover());
    }
}
