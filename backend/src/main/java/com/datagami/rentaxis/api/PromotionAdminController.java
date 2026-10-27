package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.PromoAdDTO;
import com.datagami.rentaxis.api.dto.PromoAdDtos;
import com.datagami.rentaxis.api.dto.PromoAdRequest;
import com.datagami.rentaxis.api.dto.PromoAdStatsDTO;
import com.datagami.rentaxis.api.dto.PromoBusinessDTO;
import com.datagami.rentaxis.api.dto.PromoBusinessRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.PromotionService;
import com.datagami.rentaxis.core.service.PromotionStatsService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.PromoAd;
import com.datagami.rentaxis.domain.entity.PromoAdProperty;
import com.datagami.rentaxis.domain.entity.PromoBusiness;
import com.datagami.rentaxis.domain.repository.PromoAdPropertyRepository;
import com.datagami.rentaxis.domain.repository.PromoAdRepository;
import com.datagami.rentaxis.domain.repository.PromoBusinessRepository;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Admin configuration surface for cross-promotion. RBAC lives here, not in
 * {@link PromotionService} — the same split as the amenities and gate-pass
 * modules.
 *
 * <p>Promotions are tenant-wide rather than per-property, so PROPERTY_MANAGER
 * is deliberately excluded: there is no property assignment that would scope a
 * manager's view of the ad catalogue meaningfully.
 */
@RestController
@RequestMapping("/api/v1/promotions")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
public class PromotionAdminController {

    private final PromotionService promotionService;
    private final PromotionStatsService statsService;
    private final PromoAdRepository adRepository;
    private final PromoAdPropertyRepository adPropertyRepository;
    private final PromoBusinessRepository businessRepository;

    public PromotionAdminController(PromotionService promotionService,
                                    PromotionStatsService statsService,
                                    PromoAdRepository adRepository,
                                    PromoAdPropertyRepository adPropertyRepository,
                                    PromoBusinessRepository businessRepository) {
        this.promotionService = promotionService;
        this.statsService = statsService;
        this.adRepository = adRepository;
        this.adPropertyRepository = adPropertyRepository;
        this.businessRepository = businessRepository;
    }

    // ---------------------------------------------------------------- images

    @org.springframework.beans.factory.annotation.Autowired
    private com.datagami.rentaxis.core.service.PromotionImageService images;

    @org.springframework.beans.factory.annotation.Autowired
    private com.datagami.rentaxis.core.service.BlobStorageService blobs;

    /** An uploaded image's route made absolute (R1 P2-1); null-safe for unit tests that build the controller. */
    private String imageForDto(String stored) {
        return images == null ? stored : images.forDto(stored);
    }

    /**
     * Uploads an ad's artwork or a business logo into the organisation's private
     * container; answers the route to save in {@code backgroundImageUrl} or
     * {@code logoUrl} (ux6 item 2 — these used to be bare URL boxes).
     */
    @PostMapping(value = "/images", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<java.util.Map<String, String>> uploadImage(
            @org.springframework.web.bind.annotation.RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        String url = images.upload(requireTenant(), file);
        return ResponseEntity.status(HttpStatus.CREATED).body(java.util.Map.of("url", url));
    }

    /** One of the organisation's uploaded promotion images, live or not — for the editor's preview. */
    @GetMapping("/images/{file:.+}")
    public ResponseEntity<byte[]> image(@PathVariable String file,
                                        @org.springframework.web.bind.annotation.RequestHeader(
                                                value = org.springframework.http.HttpHeaders.IF_NONE_MATCH,
                                                required = false) String ifNoneMatch) {
        if (!com.datagami.rentaxis.core.service.PromotionImageService.isFileName(file)) {
            throw new com.datagami.rentaxis.api.exception.NotFoundException("Image not found");
        }
        UUID tenantId = requireTenant();
        return ServedMedia.serve(blobs, tenantId,
                com.datagami.rentaxis.core.service.PromotionImageService.blobPath(file), false,
                ServedMedia.Caching.PRIVATE, ifNoneMatch, "promo-image|" + tenantId + "|" + file);
    }

    // ------------------------------------------------------------- businesses

    /**
     * ApiSecurityFilter authorises a SUPER_ADMIN without requiring a tenant
     * header, so the holder can legitimately be null here. Without this guard a
     * write becomes a NOT NULL violation surfaced as a 500 with a raw JDBC
     * message, and a read silently returns an empty page — telling a super-admin
     * the tenant has no businesses rather than that they forgot the header.
     */
    private UUID requireTenant() {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            throw new BusinessRuleViolationException(
                    "X-Tenant-Id is required to manage promotions");
        }
        return tenantId;
    }

    /** Live businesses; the shape callers used before addendum B. */
    public ResponseEntity<Page<PromoBusinessDTO>> listBusinesses(Pageable pageable) {
        return listBusinesses(false, pageable);
    }

    /** Live businesses, or with {@code ?archived=true} the archived ones only (addendum B). */
    @GetMapping("/businesses")
    public ResponseEntity<Page<PromoBusinessDTO>> listBusinesses(
            @RequestParam(defaultValue = "false") boolean archived,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable) {
        UUID tenantId = requireTenant();
        Page<PromoBusiness> page = archived
                ? promotionService.listBusinesses(tenantId, true, pageable)
                : promotionService.listBusinesses(tenantId, pageable);
        // One grouped query for the page, not a COUNT per row. Spring Data's
        // default max page size is 2000, so per-row counting would let a single
        // request fire 2001 queries.
        Map<UUID, Long> adCounts = batchAdCounts(tenantId, page.getContent());
        return ResponseEntity.ok(page.map(b -> toDTO(b, adCounts.getOrDefault(b.getId(), 0L))));
    }

    @PostMapping("/businesses")
    public ResponseEntity<PromoBusinessDTO> createBusiness(@Valid @RequestBody PromoBusinessRequest req) {
        UUID tenantId = requireTenant();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(toDTO(promotionService.createBusiness(tenantId, req), 0L));
    }

    @PutMapping("/businesses/{id}")
    public ResponseEntity<PromoBusinessDTO> updateBusiness(@PathVariable UUID id,
                                                           @Valid @RequestBody PromoBusinessRequest req) {
        UUID tenantId = requireTenant();
        PromoBusiness updated = promotionService.updateBusiness(tenantId, id, req);
        return ResponseEntity.ok(toDTO(updated, adRepository.countByBusinessIdAndArchivedAtIsNull(updated.getId())));
    }

    /** Always succeeds (addendum B): {@code {"outcome": "DELETED" | "ARCHIVED"}}. */
    @DeleteMapping("/businesses/{id}")
    public ResponseEntity<Map<String, String>> deleteBusiness(@PathVariable UUID id) {
        return ResponseEntity.ok(Map.of("outcome", promotionService.deleteBusiness(requireTenant(), id).name()));
    }

    /** An archived business back in the lists, still inactive; 400 if its name was taken meanwhile. */
    @PostMapping("/businesses/{id}/restore")
    public ResponseEntity<PromoBusinessDTO> restoreBusiness(@PathVariable UUID id) {
        UUID tenantId = requireTenant();
        PromoBusiness restored = promotionService.restoreBusiness(tenantId, id);
        return ResponseEntity.ok(toDTO(restored, adRepository.countByBusinessIdAndArchivedAtIsNull(restored.getId())));
    }

    // -------------------------------------------------------------------- ads

    /** Who last wrote each promoter ad (changeset 183); optional so unit tests building the controller keep working. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.datagami.rentaxis.domain.repository.UserRepository users;

    /** Every approval state; the shape callers used before changeset 183. */
    public ResponseEntity<Page<PromoAdDTO>> listAds(UUID businessId, Pageable pageable) {
        return listAds(businessId, null, false, pageable);
    }

    /**
     * {@code approvalStatus} (PENDING / APPROVED / REJECTED) narrows the list; omitted,
     * every state. Live ads, or with {@code ?archived=true} the archived ones only
     * (addendum B).
     */
    @GetMapping("/ads")
    public ResponseEntity<Page<PromoAdDTO>> listAds(
            @RequestParam(required = false) UUID businessId,
            @RequestParam(required = false) com.datagami.rentaxis.domain.entity.enums.PromoApprovalStatus approvalStatus,
            @RequestParam(defaultValue = "false") boolean archived,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable) {
        UUID tenantId = requireTenant();
        Page<PromoAd> page = approvalStatus == null && !archived
                ? promotionService.listAds(tenantId, businessId, pageable)
                : promotionService.listAds(tenantId, businessId, approvalStatus, archived, pageable);

        List<UUID> adIds = page.getContent().stream().map(PromoAd::getId).toList();
        // Three batched queries for the whole page, never one per row.
        Map<UUID, List<UUID>> targeting = batchTargeting(tenantId, adIds);
        Map<UUID, PromotionStatsService.Totals> totals =
                adIds.isEmpty() ? Map.of() : statsService.totals(tenantId, adIds);
        Map<UUID, String> businessNames = batchBusinessNames(tenantId, page.getContent());
        Map<UUID, String> writers = writerNames(tenantId, page.getContent());

        return ResponseEntity.ok(page.map(a -> toDTO(a,
                businessNames.getOrDefault(a.getBusinessId(), ""),
                targeting.getOrDefault(a.getId(), List.of()),
                totals.getOrDefault(a.getId(), PromotionStatsService.Totals.EMPTY),
                a.getPromoterUserId() == null ? null : writers.get(a.getPromoterUserId()))));
    }

    @PostMapping("/ads")
    public ResponseEntity<PromoAdDTO> createAd(@Valid @RequestBody PromoAdRequest req) {
        UUID tenantId = requireTenant();
        PromoAd created = promotionService.createAd(tenantId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDTO(created,
                promotionService.getBusiness(tenantId, created.getBusinessId()).getNameEn(),
                promotionService.targetedPropertyIds(created.getId()),
                PromotionStatsService.Totals.EMPTY));
    }

    @PutMapping("/ads/{id}")
    public ResponseEntity<PromoAdDTO> updateAd(@PathVariable UUID id,
                                               @Valid @RequestBody PromoAdRequest req) {
        UUID tenantId = requireTenant();
        PromoAd updated = promotionService.updateAd(tenantId, id, req);
        return ResponseEntity.ok(toDTO(updated,
                promotionService.getBusiness(tenantId, updated.getBusinessId()).getNameEn(),
                promotionService.targetedPropertyIds(id),
                statsService.totals(tenantId, List.of(id))
                        .getOrDefault(id, PromotionStatsService.Totals.EMPTY),
                updated.getPromoterUserId() == null ? null
                        : writerNames(tenantId, List.of(updated)).get(updated.getPromoterUserId())));
    }

    /** Always succeeds (addendum B): {@code {"outcome": "DELETED" | "ARCHIVED"}}. */
    @DeleteMapping("/ads/{id}")
    public ResponseEntity<Map<String, String>> deleteAd(@PathVariable UUID id) {
        return ResponseEntity.ok(Map.of("outcome", promotionService.deleteAd(requireTenant(), id).name()));
    }

    /** An archived ad back in the lists, still inactive; 400 while its business is archived. */
    @PostMapping("/ads/{id}/restore")
    public ResponseEntity<PromoAdDTO> restoreAd(@PathVariable UUID id) {
        UUID tenantId = requireTenant();
        PromoAd restored = promotionService.restoreAd(tenantId, id);
        return ResponseEntity.ok(toDTO(restored,
                promotionService.getBusiness(tenantId, restored.getBusinessId()).getNameEn(),
                promotionService.targetedPropertyIds(id),
                statsService.totals(tenantId, List.of(id)).getOrDefault(id, PromotionStatsService.Totals.EMPTY),
                restored.getPromoterUserId() == null ? null
                        : writerNames(tenantId, List.of(restored)).get(restored.getPromoterUserId())));
    }

    @GetMapping("/ads/{id}/stats")
    public ResponseEntity<PromoAdStatsDTO> stats(@PathVariable UUID id) {
        return ResponseEntity.ok(statsService.stats(requireTenant(), id));
    }

    // ----------------------------------------------------------------- mapping

    private Map<UUID, String> batchBusinessNames(UUID tenantId, List<PromoAd> ads) {
        if (ads.isEmpty()) {
            return Map.of();
        }
        List<UUID> businessIds = ads.stream().map(PromoAd::getBusinessId).distinct().toList();
        return businessRepository.findByTenantIdAndIdIn(tenantId, businessIds).stream()
                .collect(Collectors.toMap(PromoBusiness::getId, PromoBusiness::getNameEn));
    }

    /** Names of the promoters who last wrote these ads, in one query; empty when none did. */
    private Map<UUID, String> writerNames(UUID tenantId, List<PromoAd> ads) {
        List<UUID> ids = ads.stream().map(PromoAd::getPromoterUserId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty() || users == null) {
            return Map.of();
        }
        return users.findByTenantIdAndIdIn(tenantId, ids).stream()
                .collect(Collectors.toMap(com.datagami.rentaxis.domain.entity.User::getId,
                        com.datagami.rentaxis.domain.entity.User::getName));
    }

    private Map<UUID, Long> batchAdCounts(UUID tenantId, List<PromoBusiness> businesses) {
        if (businesses.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = businesses.stream().map(PromoBusiness::getId).toList();
        return adRepository.countByBusinessIdIn(tenantId, ids).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1]));
    }

    private Map<UUID, List<UUID>> batchTargeting(UUID tenantId, List<UUID> adIds) {
        if (adIds.isEmpty()) {
            return Map.of();
        }
        return adPropertyRepository.findByTenantIdAndAdIdIn(tenantId, adIds).stream()
                .collect(Collectors.groupingBy(PromoAdProperty::getAdId,
                        Collectors.mapping(PromoAdProperty::getPropertyId, Collectors.toList())));
    }

    private PromoBusinessDTO toDTO(PromoBusiness b, long adCount) {
        return PromoAdDtos.business(b, adCount, this::imageForDto);
    }

    /** See {@link PromoAdDtos#ad}; {@code submittedByName} is the promoter who last wrote the ad. */
    private PromoAdDTO toDTO(PromoAd a, String businessName, List<UUID> propertyIds,
                            PromotionStatsService.Totals totals) {
        return toDTO(a, businessName, propertyIds, totals, null);
    }

    private PromoAdDTO toDTO(PromoAd a, String businessName, List<UUID> propertyIds,
                            PromotionStatsService.Totals totals, String submittedByName) {
        return PromoAdDtos.ad(a, businessName, propertyIds, totals, submittedByName, this::imageForDto);
    }
}
