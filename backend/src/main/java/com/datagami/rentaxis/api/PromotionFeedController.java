package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.PromoAdCardDTO;
import com.datagami.rentaxis.api.dto.PromoDirectoryDtos.DirectoryBusinessDTO;
import com.datagami.rentaxis.api.dto.PromoDirectoryDtos.DirectoryCategoryDTO;
import com.datagami.rentaxis.api.dto.PromoDirectoryDtos.DirectoryCategoryDetailDTO;
import com.datagami.rentaxis.api.dto.PromoEventBatchRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.PromoDirectoryService;
import com.datagami.rentaxis.core.service.PromotionFeedService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.enums.PromoCategory;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * What the renter mobile app calls.
 *
 * <p>The caller's identity is read from the SecurityContext rather than from a
 * request header directly. To be precise about what that buys: {@code
 * ApiSecurityFilter} populates the principal from the {@code X-User-Id} header,
 * so the SecurityContext is the seam identity flows through, not proof it was
 * established — the gateway stripping that header is what makes it trustworthy.
 * Reading it here is still the right call, because it is the one place that
 * becomes correct for free once that is closed, whereas a controller reading the
 * header itself would have to be hunted down and changed.
 * {@code LeaseController.getMyLeases} is an example of the latter — do not copy it.
 */
@RestController
@RequestMapping("/api/v1/promotions")
@PreAuthorize("hasRole('RENTER')")
public class PromotionFeedController {

    private final PromotionFeedService feedService;
    private final PromoDirectoryService directoryService;

    public PromotionFeedController(PromotionFeedService feedService, PromoDirectoryService directoryService) {
        this.feedService = feedService;
        this.directoryService = directoryService;
    }

    /** The home carousel slate — at most six cards, stable for the day. */
    @GetMapping("/feed")
    public ResponseEntity<List<PromoAdCardDTO>> feed() {
        return ResponseEntity.ok(
                feedService.homeFeed(requireTenant(), currentRenterId()));
    }

    @GetMapping("/offers")
    public ResponseEntity<List<PromoAdCardDTO>> offers(
            @RequestParam(required = false) PromoCategory category,
            @RequestParam(required = false) UUID businessId) {
        return ResponseEntity.ok(
                feedService.offers(requireTenant(), currentRenterId(), category, businessId));
    }

    /** The business directory's tiles: live categories with at least one live business. */
    @GetMapping("/directory")
    public ResponseEntity<List<DirectoryCategoryDTO>> directory() {
        return ResponseEntity.ok(directoryService.directory(requireTenant()));
    }

    /**
     * One category and its businesses; 404 for another org's, an archived or an
     * inactive one. Kept under {@code /directory} (ruling R2), off the admin
     * {@code /promotions/businesses/**} routes; {@code /directory/businesses/{id}}
     * has one more segment, so the two never compete for a path.
     */
    @GetMapping("/directory/{categoryId}")
    public ResponseEntity<DirectoryCategoryDetailDTO> directoryCategory(@PathVariable UUID categoryId) {
        return ResponseEntity.ok(directoryService.directoryCategory(requireTenant(), categoryId));
    }

    /** A business page; 404 unless it is live and in at least one live category. Its offers: {@code /offers?businessId=}. */
    @GetMapping("/directory/businesses/{id}")
    public ResponseEntity<DirectoryBusinessDTO> directoryBusiness(@PathVariable UUID id) {
        return ResponseEntity.ok(directoryService.directoryBusiness(requireTenant(), id));
    }

    /**
     * 202, not 200 — the app fires this on dispose and on app background and
     * does not wait on the result. Unknown or ineligible ad ids are dropped by
     * the service rather than failing the batch.
     */
    @PostMapping("/events")
    public ResponseEntity<Void> events(@Valid @RequestBody PromoEventBatchRequest batch) {
        feedService.recordEvents(requireTenant(), currentRenterId(), batch);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    /**
     * {@code ApiSecurityFilter} refuses a non-SUPER_ADMIN with no organisation,
     * and {@code OrganisationRequiredInterceptor} refuses a SUPER_ADMIN without
     * one, so a null tenant should not reach this controller. Every query here
     * carries an explicit tenantId predicate, where a null would just match
     * nothing and show an empty carousel with no clue why; this stays as the
     * loud belt-and-braces, matching {@code PromotionAdminController.requireTenant()}.
     */
    private UUID requireTenant() {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            throw new BusinessRuleViolationException("X-Tenant-Id is required");
        }
        return tenantId;
    }

    private UUID currentRenterId() {
        return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName());
    }
}
