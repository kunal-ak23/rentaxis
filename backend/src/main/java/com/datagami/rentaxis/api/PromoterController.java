package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.PromoAdDTO;
import com.datagami.rentaxis.api.dto.PromoAdRequest;
import com.datagami.rentaxis.api.dto.PromoAdStatsDTO;
import com.datagami.rentaxis.api.dto.PromoBusinessDTO;
import com.datagami.rentaxis.api.dto.PromoBusinessRequest;
import com.datagami.rentaxis.api.dto.PromoterApiKeyDtos.ApiKeyDTO;
import com.datagami.rentaxis.api.dto.PromoterApiKeyDtos.CreateKeyRequest;
import com.datagami.rentaxis.api.dto.PromoterApiKeyDtos.IssuedKeyDTO;
import com.datagami.rentaxis.api.dto.PromoterApiKeyDtos.RevokeKeyRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.BlobStorageService;
import com.datagami.rentaxis.core.service.PromoterApiKeyService;
import com.datagami.rentaxis.core.service.PromoterService;
import com.datagami.rentaxis.core.service.PromotionImageService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The promoter portal's API (promoter plan, "Promoter API"). PROMOTER only — and a
 * promoter reaches nothing else ({@code PromoterFenceFilter}). Every rule lives in
 * {@link PromoterService}; the caller is the verified principal and the
 * organisation of the request, never anything from the body.
 */
@RestController
@RequestMapping("/api/v1/promoter")
@PreAuthorize("hasRole('PROMOTER')")
public class PromoterController {

    private final PromoterService promoters;
    private final PromotionImageService images;
    private final BlobStorageService blobs;
    private final PromoterApiKeyService apiKeys;
    private final Clock clock;

    public PromoterController(PromoterService promoters, PromotionImageService images, BlobStorageService blobs,
                              PromoterApiKeyService apiKeys, Clock clock) {
        this.promoters = promoters;
        this.images = images;
        this.blobs = blobs;
        this.apiKeys = apiKeys;
        this.clock = clock;
    }

    @GetMapping("/me")
    public ResponseEntity<PromoterService.Me> me() {
        return ResponseEntity.ok(promoters.me(caller()));
    }

    @GetMapping("/properties")
    public ResponseEntity<List<PromoterService.PropertyRef>> properties() {
        return ResponseEntity.ok(promoters.properties(caller()));
    }

    // ------------------------------------------------------------- businesses

    @GetMapping("/businesses")
    public ResponseEntity<List<PromoBusinessDTO>> businesses() {
        return ResponseEntity.ok(promoters.businesses(caller()));
    }

    @PostMapping("/businesses")
    public ResponseEntity<PromoBusinessDTO> createBusiness(@Valid @RequestBody PromoBusinessRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(promoters.createBusiness(caller(), req));
    }

    @PutMapping("/businesses/{id}")
    public ResponseEntity<PromoBusinessDTO> updateBusiness(@PathVariable UUID id,
                                                           @Valid @RequestBody PromoBusinessRequest req) {
        return ResponseEntity.ok(promoters.updateBusiness(caller(), id, req));
    }

    /** Needs can-create and the grant (addendum B): {@code {"outcome": "DELETED" | "ARCHIVED"}}. */
    @DeleteMapping("/businesses/{id}")
    public ResponseEntity<Map<String, String>> deleteBusiness(@PathVariable UUID id) {
        return ResponseEntity.ok(Map.of("outcome", promoters.deleteBusiness(caller(), id).name()));
    }

    // -------------------------------------------------------------------- ads

    @GetMapping("/ads")
    public ResponseEntity<Page<PromoAdDTO>> ads(
            @RequestParam(required = false) UUID businessId,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(promoters.listAds(caller(), businessId, pageable));
    }

    @PostMapping("/ads")
    public ResponseEntity<PromoAdDTO> createAd(@Valid @RequestBody PromoAdRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(promoters.createAd(caller(), req));
    }

    @PutMapping("/ads/{id}")
    public ResponseEntity<PromoAdDTO> updateAd(@PathVariable UUID id, @Valid @RequestBody PromoAdRequest req) {
        return ResponseEntity.ok(promoters.updateAd(caller(), id, req));
    }

    /** Always succeeds (addendum B): {@code {"outcome": "DELETED" | "ARCHIVED"}}. */
    @DeleteMapping("/ads/{id}")
    public ResponseEntity<Map<String, String>> deleteAd(@PathVariable UUID id) {
        return ResponseEntity.ok(Map.of("outcome", promoters.deleteAd(caller(), id).name()));
    }

    @GetMapping("/ads/{id}/stats")
    public ResponseEntity<PromoAdStatsDTO> stats(@PathVariable UUID id) {
        return ResponseEntity.ok(promoters.stats(caller(), id));
    }

    // --------------------------------------------------------------- api keys

    @GetMapping("/api-keys")
    public ResponseEntity<List<ApiKeyDTO>> apiKeys() {
        return ResponseEntity.ok(apiKeys.listForPromoter(caller().userId()).stream()
                .map(k -> ApiKeyDTO.of(k, clock)).toList());
    }

    /** The secret is in this response only; never cached. */
    @PostMapping("/api-keys")
    public ResponseEntity<IssuedKeyDTO> createApiKey(@RequestBody CreateKeyRequest req) {
        return noStore(HttpStatus.CREATED, IssuedKeyDTO.of(apiKeys.create(caller().userId(), req.name()), clock));
    }

    @PostMapping("/api-keys/{id}/rotate")
    public ResponseEntity<IssuedKeyDTO> rotateApiKey(@PathVariable UUID id) {
        return noStore(HttpStatus.CREATED, IssuedKeyDTO.of(apiKeys.rotate(caller().userId(), id), clock));
    }

    @PostMapping("/api-keys/{id}/revoke")
    public ResponseEntity<ApiKeyDTO> revokeApiKey(@PathVariable UUID id,
                                                  @RequestBody(required = false) RevokeKeyRequest req) {
        return ResponseEntity.ok(ApiKeyDTO.of(
                apiKeys.revokeByPromoter(caller().userId(), id, req == null ? null : req.reason()), clock));
    }

    private static <T> ResponseEntity<T> noStore(HttpStatus status, T body) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    // ----------------------------------------------------------------- images

    /** Same upload as the admin's, into the organisation's private container. */
    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> uploadImage(@RequestParam("file") MultipartFile file) {
        promoters.me(caller()); // a promoter of this organisation, or 403
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("url", images.upload(tenant(), file)));
    }

    /** An uploaded promotion image of the organisation, live or not — for the editor's preview. */
    @GetMapping("/images/{file:.+}")
    public ResponseEntity<byte[]> image(@PathVariable String file,
                                        @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false)
                                        String ifNoneMatch) {
        if (!PromotionImageService.isFileName(file)) {
            throw new NotFoundException("Image not found");
        }
        promoters.me(caller());
        UUID tenantId = tenant();
        return ServedMedia.serve(blobs, tenantId, PromotionImageService.blobPath(file), false,
                ServedMedia.Caching.PRIVATE, ifNoneMatch, "promo-image|" + tenantId + "|" + file);
    }

    // ------------------------------------------------------------------------

    private PromoterService.Caller caller() {
        return new PromoterService.Caller(
                UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName()), tenant());
    }

    private static UUID tenant() {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            throw new BusinessRuleViolationException("X-Tenant-Id is required");
        }
        return tenantId;
    }
}
