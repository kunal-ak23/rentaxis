package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.PromoAdCardDTO;
import com.datagami.rentaxis.api.dto.PromoEventBatchRequest;
import com.datagami.rentaxis.core.service.PromotionFeedService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.enums.PromoCategory;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * What the renter mobile app calls. The caller's identity comes from the
 * SecurityContext, never from an X-User-Id request header — a client-supplied
 * header is not an identity, and this endpoint decides what a specific renter
 * is allowed to see.
 */
@RestController
@RequestMapping("/api/v1/promotions")
@PreAuthorize("hasRole('RENTER')")
public class PromotionFeedController {

    private final PromotionFeedService feedService;

    public PromotionFeedController(PromotionFeedService feedService) {
        this.feedService = feedService;
    }

    /** The home carousel slate — at most six cards, stable for the day. */
    @GetMapping("/feed")
    public ResponseEntity<List<PromoAdCardDTO>> feed() {
        return ResponseEntity.ok(
                feedService.homeFeed(TenantContextHolder.getTenantId(), currentRenterId()));
    }

    @GetMapping("/offers")
    public ResponseEntity<List<PromoAdCardDTO>> offers(
            @RequestParam(required = false) PromoCategory category) {
        return ResponseEntity.ok(
                feedService.offers(TenantContextHolder.getTenantId(), currentRenterId(), category));
    }

    /**
     * 202, not 200 — the app fires this on dispose and on app background and
     * does not wait on the result. Unknown or ineligible ad ids are dropped by
     * the service rather than failing the batch.
     */
    @PostMapping("/events")
    public ResponseEntity<Void> events(@Valid @RequestBody PromoEventBatchRequest batch) {
        feedService.recordEvents(TenantContextHolder.getTenantId(), currentRenterId(), batch);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    private UUID currentRenterId() {
        return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName());
    }
}
