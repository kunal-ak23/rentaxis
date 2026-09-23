package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.InterestDTO;
import com.datagami.rentaxis.api.dto.UnitListingCreateRequest;
import com.datagami.rentaxis.api.dto.UnitListingDTO;
import com.datagami.rentaxis.api.dto.UnitListingMediaDTO;
import com.datagami.rentaxis.api.dto.UnitListingMediaUploadResponse;
import com.datagami.rentaxis.api.dto.UnitListingSummaryDTO;
import com.datagami.rentaxis.api.dto.UnitListingUpdateRequest;
import com.datagami.rentaxis.api.exception.AccessDeniedException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.security.PropertyScope;
import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.core.service.UnitListingService;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.UnitListing;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.entity.enums.ListingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/listings")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
public class UnitListingController {

    private final UnitListingService service;
    private final TenantFeatureService tenantFeatureService;
    private final LandlordOrgRepository landlordOrgRepository;
    private final UnitRepository unitRepository;
    private final PropertyScope propertyScope;

    public UnitListingController(UnitListingService service, TenantFeatureService tenantFeatureService,
                                 LandlordOrgRepository landlordOrgRepository,
                                 UnitRepository unitRepository,
                                 PropertyScope propertyScope) {
        this.service = service;
        this.tenantFeatureService = tenantFeatureService;
        this.landlordOrgRepository = landlordOrgRepository;
        this.unitRepository = unitRepository;
        this.propertyScope = propertyScope;
    }

    @GetMapping
    public ResponseEntity<Page<UnitListingSummaryDTO>> list(
            @RequestParam(required = false) ListingStatus status,
            @RequestParam(required = false) UUID propertyId,
            @RequestParam(required = false) String q,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable) {
        checkEnabled();
        UUID tenantId = TenantContextHolder.getTenantId();
        // A property manager sees the listings of their own buildings only (audit P1-5).
        List<UUID> scope = propertyScope.scopedPropertyIds();
        Page<UnitListing> page = service.list(tenantId, status, propertyId, scope, q, pageable);
        var summaryData = service.getSummaryData(page.getContent());
        return ResponseEntity.ok(page.map(listing -> toSummary(
                listing,
                summaryData.getOrDefault(
                        listing.getId(),
                        new UnitListingService.ListingSummaryData(null, null, 0L)))));
    }

    @GetMapping("/{id}")
    public ResponseEntity<UnitListingDTO> get(@PathVariable UUID id) {
        checkEnabled();
        requireListingInScope(id);
        UUID tenantId = TenantContextHolder.getTenantId();
        return ResponseEntity.ok(toDetail(service.get(tenantId, id)));
    }

    @PostMapping
    public ResponseEntity<UnitListingDTO> create(@RequestBody UnitListingCreateRequest req) {
        checkEnabled();
        UUID tenantId = TenantContextHolder.getTenantId();
        requireUnitInScope(req.unitId());
        UnitListing created = service.create(tenantId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDetail(created));
    }

    @PutMapping("/{id}")
    public ResponseEntity<UnitListingDTO> update(@PathVariable UUID id,
                                                 @RequestBody UnitListingUpdateRequest req) {
        checkEnabled();
        requireListingInScope(id);
        UUID tenantId = TenantContextHolder.getTenantId();
        return ResponseEntity.ok(toDetail(service.update(tenantId, id, req)));
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<Void> publish(@PathVariable UUID id) {
        checkEnabled();
        requireListingInScope(id);
        service.publish(TenantContextHolder.getTenantId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/unlist")
    public ResponseEntity<Void> unlist(@PathVariable UUID id) {
        checkEnabled();
        requireListingInScope(id);
        service.unlist(TenantContextHolder.getTenantId(), id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        checkEnabled();
        requireListingInScope(id);
        service.delete(TenantContextHolder.getTenantId(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/interests")
    public ResponseEntity<Page<InterestDTO>> interests(
            @PathVariable UUID id,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable) {
        checkEnabled();
        requireListingInScope(id);
        return ResponseEntity.ok(
                service.listInterests(TenantContextHolder.getTenantId(), id, pageable));
    }

    @PostMapping("/{id}/media")
    public ResponseEntity<UnitListingMediaUploadResponse> addMedia(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "caption", required = false) String caption,
            @RequestParam(value = "isCover", required = false) Boolean isCover) {
        checkEnabled();
        requireListingInScope(id);
        UnitListingMediaDTO dto = service.addMedia(
                TenantContextHolder.getTenantId(), id, file, caption, isCover);
        return ResponseEntity.ok(new UnitListingMediaUploadResponse(dto.id(), dto.url()));
    }

    @DeleteMapping("/{id}/media/{mediaId}")
    public ResponseEntity<Void> removeMedia(@PathVariable UUID id, @PathVariable UUID mediaId) {
        checkEnabled();
        requireListingInScope(id);
        service.removeMedia(TenantContextHolder.getTenantId(), id, mediaId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/media/reorder")
    public ResponseEntity<Void> reorderMedia(@PathVariable UUID id,
                                             @RequestBody ReorderRequest body) {
        checkEnabled();
        requireListingInScope(id);
        service.reorderMedia(TenantContextHolder.getTenantId(), id, body.mediaIds());
        return ResponseEntity.noContent().build();
    }

    public record ReorderRequest(List<UUID> mediaIds) {
    }

    private void checkEnabled() {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (!tenantFeatureService.isEnabled(tenantId, TenantFeature.LISTINGS)) {
            throw new NotFoundException("Listings feature is disabled");
        }
    }

    /**
     * A property manager may create a listing only for a unit in a building they
     * manage. Tenant-wide roles are unaffected.
     */
    private void requireUnitInScope(UUID unitId) {
        if (!propertyScope.isScoped()) return;
        if (unitId == null) {
            throw new AccessDeniedException("Unit must be specified");
        }
        Unit unit = unitRepository.findById(unitId)
                .orElseThrow(() -> new NotFoundException("Unit not found"));
        propertyScope.requireCanAccessUnit(unit, "Unit not found");
    }

    /**
     * Every {id} route: a property manager acts only on listings of units in the
     * buildings they manage, and anything else is simply not there (404). Before
     * round 5 only create checked this, so a manager could read the interested
     * renters' contact details, edit, unlist or delete any listing in the tenant.
     */
    private void requireListingInScope(UUID id) {
        if (!propertyScope.isScoped()) return;
        UnitListing listing = service.get(TenantContextHolder.getTenantId(), id);
        Unit unit = listing.getUnitId() == null ? null : unitRepository.findById(listing.getUnitId()).orElse(null);
        propertyScope.requireCanAccessUnit(unit, "Listing not found");
    }

    // ---- Mapping ----

    private UnitListingSummaryDTO toSummary(UnitListing l, UnitListingService.ListingSummaryData summaryData) {
        return new UnitListingSummaryDTO(
                l.getId(),
                l.getTitleEn(),
                summaryData.propertyName(),
                l.getBedrooms(),
                l.getBathrooms(),
                l.getAnnualRent(),
                l.getStatus(),
                summaryData.coverPhotoUrl(),
                summaryData.interestsCount(),
                l.getLat(),
                l.getLng(),
                l.getCreatedAt(),
                l.getUpdatedAt(),
                l.getSlug()
        );
    }

    private UnitListingDTO toDetail(UnitListing l) {
        String tenantSlug = landlordOrgRepository.findById(l.getTenantId())
                .map(LandlordOrg::getSlug)
                .orElse(null);

        List<UnitListingDTO.AmenityEntry> amenities = service.listAmenities(l.getId()).stream()
                .map(a -> new UnitListingDTO.AmenityEntry(a.getAmenity(), a.getCustomLabel()))
                .toList();

        List<UnitListingMediaDTO> media = service.listMedia(l.getId());

        return new UnitListingDTO(
                l.getId(), l.getUnitId(), l.getStatus(),
                l.getTitleEn(), l.getTitleAr(), l.getDescriptionEn(), l.getDescriptionAr(),
                l.getBedrooms(), l.getBathrooms(), l.getSizeSqft(), l.getFloor(), l.getParkingSpaces(),
                l.getFurnishing(), l.getViewType(),
                l.getAnnualRent(), l.getSecurityDeposit(), l.getMinLeaseMonths(),
                l.getChequesAccepted(), l.getDewaIncluded(), l.getChillerIncluded(),
                l.getUtilitiesEstimate(), l.getAvailableFrom(),
                tenantSlug, l.getSlug(), l.getSeoTitle(), l.getSeoDescription(), l.getSeoKeywords(), l.getOgImageUrl(),
                l.getLat(), l.getLng(),
                l.getPublishedAt(), l.getCreatedAt(), l.getUpdatedAt(),
                amenities, media
        );
    }
}
