package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.FiguresChangedException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.event.PromoAdReviewedEvent;
import com.datagami.rentaxis.domain.entity.PromoAd;
import com.datagami.rentaxis.domain.entity.enums.PromoApprovalStatus;
import com.datagami.rentaxis.domain.repository.PromoAdPropertyRepository;
import com.datagami.rentaxis.domain.repository.PromoAdRepository;
import com.datagami.rentaxis.domain.repository.PromoBusinessRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

    /** Review round 1, I2: the decision was about a submission that has since been replaced. */
    public static final String CHANGED_CODE = "promo.changed";
    public static final String CHANGED_MESSAGE =
            "This ad was changed after you opened it. Reload to review the latest version.";

    private final PromoAdRepository adRepository;
    private final PromoAdPropertyRepository adPropertyRepository;
    private final PromoBusinessRepository businessRepository;
    private final ApplicationEventPublisher events;

    public PromoAdApprovalService(PromoAdRepository adRepository,
                                  PromoAdPropertyRepository adPropertyRepository,
                                  PromoBusinessRepository businessRepository,
                                  ApplicationEventPublisher events) {
        this.adRepository = adRepository;
        this.adPropertyRepository = adPropertyRepository;
        this.businessRepository = businessRepository;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public long pendingCount(UUID tenantId) {
        return adRepository.countByTenantIdAndApprovalStatusAndArchivedAtIsNull(tenantId, PromoApprovalStatus.PENDING);
    }

    /**
     * PENDING → APPROVED: live from its window. The rejection reason, if any, is cleared.
     * {@code expectedSubmittedAt} is the {@code submittedAt} the admin reviewed: a
     * submission since (an edit of the ad or of its business) is a 409 — an admin never
     * approves content they have not seen.
     */
    public PromoAd approve(UUID tenantId, UUID adId, UUID reviewerId, Instant expectedSubmittedAt) {
        requireExpected(expectedSubmittedAt);
        // Review round 1, I3: the business row first (FOR SHARE), then the ad (FOR
        // UPDATE) — the order a promoter's business edit takes too, so the two serialise
        // without deadlocking. The business id is read as a value, not the entity, so the
        // locked read of the ad below loads it fresh.
        UUID businessId = adRepository.findBusinessId(tenantId, adId)
                .orElseThrow(() -> new NotFoundException("Ad not found"));
        businessRepository.findForShare(tenantId, businessId);
        PromoAd ad = pending(tenantId, adId, "approved", expectedSubmittedAt);
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

    /** PENDING → REJECTED with a reason the promoter reads (required, trimmed, at most 500). Same 409 as approve. */
    public PromoAd reject(UUID tenantId, UUID adId, UUID reviewerId, String reason, Instant expectedSubmittedAt) {
        requireExpected(expectedSubmittedAt);
        String why = reason == null ? null : reason.trim();
        if (why == null || why.isEmpty()) {
            throw new BusinessRuleViolationException("A rejection needs a reason for the promoter.");
        }
        if (why.length() > MAX_REASON) {
            throw new BusinessRuleViolationException("The reason must be at most " + MAX_REASON + " characters.");
        }
        PromoAd ad = pending(tenantId, adId, "rejected", expectedSubmittedAt);
        review(ad, PromoApprovalStatus.REJECTED, reviewerId, why);
        return ad;
    }

    private static void requireExpected(Instant expectedSubmittedAt) {
        if (expectedSubmittedAt == null) {
            throw new BusinessRuleViolationException(
                    "expectedSubmittedAt is required: send the submittedAt of the version you reviewed.");
        }
    }

    private PromoAd pending(UUID tenantId, UUID adId, String verb, Instant expectedSubmittedAt) {
        PromoAd ad = adRepository.findForUpdate(tenantId, adId)
                .orElseThrow(() -> new NotFoundException("Ad not found"));
        PromotionService.requireNotArchived(ad);
        if (ad.getApprovalStatus() != PromoApprovalStatus.PENDING) {
            throw new BusinessRuleViolationException("Only an ad awaiting approval can be " + verb + ".");
        }
        if (!sameMicros(ad.getSubmittedAt(), expectedSubmittedAt)) {
            throw new FiguresChangedException(CHANGED_CODE, CHANGED_MESSAGE);
        }
        return ad;
    }

    /** Postgres keeps microseconds; compare at that precision whatever the client echoes back. */
    private static boolean sameMicros(Instant a, Instant b) {
        return a != null && b != null
                && a.truncatedTo(ChronoUnit.MICROS).equals(b.truncatedTo(ChronoUnit.MICROS));
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
