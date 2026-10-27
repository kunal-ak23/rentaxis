package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.event.PromoAdReviewedEvent;
import com.datagami.rentaxis.domain.entity.PromoAd;
import com.datagami.rentaxis.domain.entity.enums.PromoApprovalStatus;
import com.datagami.rentaxis.domain.repository.PromoAdPropertyRepository;
import com.datagami.rentaxis.domain.repository.PromoAdRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An admin's decision on an ad awaiting approval (promoter plan, "Admin API"). Only
 * a PENDING ad can be approved or rejected (anything else is 400), the row is
 * locked so a promoter's concurrent edit and the decision serialize, and another
 * organisation's ad is a 404.
 */
@Service
@Transactional
public class PromoAdApprovalService {

    static final int MAX_REASON = 500;

    private final PromoAdRepository adRepository;
    private final PromoAdPropertyRepository adPropertyRepository;
    private final ApplicationEventPublisher events;

    public PromoAdApprovalService(PromoAdRepository adRepository,
                                  PromoAdPropertyRepository adPropertyRepository,
                                  ApplicationEventPublisher events) {
        this.adRepository = adRepository;
        this.adPropertyRepository = adPropertyRepository;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public long pendingCount(UUID tenantId) {
        return adRepository.countByTenantIdAndApprovalStatus(tenantId, PromoApprovalStatus.PENDING);
    }

    /** PENDING → APPROVED: live from its window. The rejection reason, if any, is cleared. */
    public PromoAd approve(UUID tenantId, UUID adId, UUID reviewerId) {
        PromoAd ad = pending(tenantId, adId, "approved");
        // A promoter's ad with no targeting would show nowhere once live (it never
        // means "every property" for a promoter): refuse rather than approve a dud.
        if (ad.getPromoterUserId() != null
                && adPropertyRepository.findByTenantIdAndAdIdIn(tenantId, List.of(adId)).isEmpty()) {
            throw new BusinessRuleViolationException(
                    "Choose at least one of the promoter's properties for this ad before approving it.");
        }
        review(ad, PromoApprovalStatus.APPROVED, reviewerId, null);
        return ad;
    }

    /** PENDING → REJECTED with a reason the promoter reads (required, trimmed, at most 500). */
    public PromoAd reject(UUID tenantId, UUID adId, UUID reviewerId, String reason) {
        String why = reason == null ? null : reason.trim();
        if (why == null || why.isEmpty()) {
            throw new BusinessRuleViolationException("A rejection needs a reason for the promoter.");
        }
        if (why.length() > MAX_REASON) {
            throw new BusinessRuleViolationException("The reason must be at most " + MAX_REASON + " characters.");
        }
        PromoAd ad = pending(tenantId, adId, "rejected");
        review(ad, PromoApprovalStatus.REJECTED, reviewerId, why);
        return ad;
    }

    private PromoAd pending(UUID tenantId, UUID adId, String verb) {
        PromoAd ad = adRepository.findForUpdate(tenantId, adId)
                .orElseThrow(() -> new NotFoundException("Ad not found"));
        if (ad.getApprovalStatus() != PromoApprovalStatus.PENDING) {
            throw new BusinessRuleViolationException("Only an ad awaiting approval can be " + verb + ".");
        }
        return ad;
    }

    private void review(PromoAd ad, PromoApprovalStatus status, UUID reviewerId, String reason) {
        ad.setApprovalStatus(status);
        ad.setReviewedBy(reviewerId);
        ad.setReviewedAt(Instant.now());
        ad.setRejectionReason(reason);
        adRepository.save(ad);
        if (ad.getPromoterUserId() != null) {
            events.publishEvent(new PromoAdReviewedEvent(ad.getTenantId(), ad.getId(), ad.getPromoterUserId(),
                    status, reason));
        }
    }
}
