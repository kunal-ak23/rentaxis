package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.PromoAdDTO;
import com.datagami.rentaxis.api.dto.PromoAdRequest;
import com.datagami.rentaxis.api.dto.PromoAdStatsDTO;
import com.datagami.rentaxis.api.dto.PromoBusinessDTO;
import com.datagami.rentaxis.api.dto.PromoBusinessRequest;
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

    /**
     * {@code PromotionStatsService.Totals.EMPTY} is package-private (visible only
     * within {@code core.service}), so it cannot be referenced from this package.
     * The record's canonical constructor is public, so we build our own empty
     * value with it instead.
     */
    private static final PromotionStatsService.Totals EMPTY_TOTALS =
            new PromotionStatsService.Totals(0, 0, 0, 0);

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

    // ------------------------------------------------------------- businesses

    @GetMapping("/businesses")
    public ResponseEntity<Page<PromoBusinessDTO>> listBusinesses(
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable) {
        UUID tenantId = TenantContextHolder.getTenantId();
        return ResponseEntity.ok(promotionService.listBusinesses(tenantId, pageable)
                .map(b -> toDTO(b, adRepository.countByBusinessId(b.getId()))));
    }

    @PostMapping("/businesses")
    public ResponseEntity<PromoBusinessDTO> createBusiness(@Valid @RequestBody PromoBusinessRequest req) {
        UUID tenantId = TenantContextHolder.getTenantId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(toDTO(promotionService.createBusiness(tenantId, req), 0L));
    }

    @PutMapping("/businesses/{id}")
    public ResponseEntity<PromoBusinessDTO> updateBusiness(@PathVariable UUID id,
                                                           @Valid @RequestBody PromoBusinessRequest req) {
        UUID tenantId = TenantContextHolder.getTenantId();
        PromoBusiness updated = promotionService.updateBusiness(tenantId, id, req);
        return ResponseEntity.ok(toDTO(updated, adRepository.countByBusinessId(id)));
    }

    @DeleteMapping("/businesses/{id}")
    public ResponseEntity<Void> deleteBusiness(@PathVariable UUID id) {
        promotionService.deleteBusiness(TenantContextHolder.getTenantId(), id);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------- ads

    @GetMapping("/ads")
    public ResponseEntity<Page<PromoAdDTO>> listAds(
            @RequestParam(required = false) UUID businessId,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable) {
        UUID tenantId = TenantContextHolder.getTenantId();
        Page<PromoAd> page = promotionService.listAds(tenantId, businessId, pageable);

        List<UUID> adIds = page.getContent().stream().map(PromoAd::getId).toList();
        // Three batched queries for the whole page, never one per row.
        Map<UUID, List<UUID>> targeting = batchTargeting(adIds);
        Map<UUID, PromotionStatsService.Totals> totals =
                adIds.isEmpty() ? Map.of() : statsService.totals(tenantId, adIds);
        Map<UUID, String> businessNames = batchBusinessNames(page.getContent());

        return ResponseEntity.ok(page.map(a -> toDTO(a,
                businessNames.getOrDefault(a.getBusinessId(), ""),
                targeting.getOrDefault(a.getId(), List.of()),
                totals.getOrDefault(a.getId(), EMPTY_TOTALS))));
    }

    @PostMapping("/ads")
    public ResponseEntity<PromoAdDTO> createAd(@Valid @RequestBody PromoAdRequest req) {
        UUID tenantId = TenantContextHolder.getTenantId();
        PromoAd created = promotionService.createAd(tenantId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDTO(created,
                promotionService.getBusiness(tenantId, created.getBusinessId()).getNameEn(),
                promotionService.targetedPropertyIds(created.getId()),
                EMPTY_TOTALS));
    }

    @PutMapping("/ads/{id}")
    public ResponseEntity<PromoAdDTO> updateAd(@PathVariable UUID id,
                                               @Valid @RequestBody PromoAdRequest req) {
        UUID tenantId = TenantContextHolder.getTenantId();
        PromoAd updated = promotionService.updateAd(tenantId, id, req);
        return ResponseEntity.ok(toDTO(updated,
                promotionService.getBusiness(tenantId, updated.getBusinessId()).getNameEn(),
                promotionService.targetedPropertyIds(id),
                statsService.totals(tenantId, List.of(id))
                        .getOrDefault(id, EMPTY_TOTALS)));
    }

    @DeleteMapping("/ads/{id}")
    public ResponseEntity<Void> deleteAd(@PathVariable UUID id) {
        promotionService.deleteAd(TenantContextHolder.getTenantId(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/ads/{id}/stats")
    public ResponseEntity<PromoAdStatsDTO> stats(@PathVariable UUID id) {
        return ResponseEntity.ok(statsService.stats(TenantContextHolder.getTenantId(), id));
    }

    // ----------------------------------------------------------------- mapping

    private Map<UUID, String> batchBusinessNames(List<PromoAd> ads) {
        if (ads.isEmpty()) {
            return Map.of();
        }
        UUID tenantId = TenantContextHolder.getTenantId();
        List<UUID> businessIds = ads.stream().map(PromoAd::getBusinessId).distinct().toList();
        return businessRepository.findByTenantIdAndIdIn(tenantId, businessIds).stream()
                .collect(Collectors.toMap(PromoBusiness::getId, PromoBusiness::getNameEn));
    }

    private Map<UUID, List<UUID>> batchTargeting(List<UUID> adIds) {
        if (adIds.isEmpty()) {
            return Map.of();
        }
        return adPropertyRepository.findByAdIdIn(adIds).stream()
                .collect(Collectors.groupingBy(PromoAdProperty::getAdId,
                        Collectors.mapping(PromoAdProperty::getPropertyId, Collectors.toList())));
    }

    private PromoBusinessDTO toDTO(PromoBusiness b, long adCount) {
        List<String> domains = b.getAllowedDomains() == null || b.getAllowedDomains().isBlank()
                ? List.of()
                : Arrays.stream(b.getAllowedDomains().split(",")).map(String::trim).toList();
        return new PromoBusinessDTO(b.getId(), b.getNameEn(), b.getNameAr(), b.getLogoUrl(),
                b.getCategory(), b.getPhoneE164(), b.getWhatsappE164(), domains,
                b.isActive(), adCount, b.getCreatedAt(), b.getUpdatedAt());
    }

    /**
     * 26 positional arguments, seven pairs of which are adjacent and share a
     * type. Keep the arguments one per line and in the record's declared order
     * so a transposition is visible in review — the compiler cannot catch one.
     * {@code listAds_mapsEverySwapProneFieldToTheRightSlot} is the backstop.
     */
    private PromoAdDTO toDTO(PromoAd a, String businessName, List<UUID> propertyIds,
                            PromotionStatsService.Totals totals) {
        return new PromoAdDTO(a.getId(), a.getBusinessId(), businessName,
                a.getTitleEn(), a.getTitleAr(), a.getSubtitleEn(), a.getSubtitleAr(),
                a.getBackgroundImageUrl(), a.getAccentColor(),
                a.getCtaType(), a.getCtaLabelEn(), a.getCtaLabelAr(), a.getCtaUrl(),
                a.getCouponCode(), a.getCouponTermsEn(), a.getCouponTermsAr(),
                a.getStartsAt(), a.getEndsAt(), a.getPriority(), a.getPlacement(), a.isActive(),
                propertyIds, totals.impressions(), totals.clicks(),
                a.getCreatedAt(), a.getUpdatedAt());
    }
}
