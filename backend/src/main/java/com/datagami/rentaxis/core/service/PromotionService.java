package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.PromoAdRequest;
import com.datagami.rentaxis.api.dto.PromoBusinessRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.PromoAd;
import com.datagami.rentaxis.domain.entity.PromoAdProperty;
import com.datagami.rentaxis.domain.entity.PromoBusiness;
import com.datagami.rentaxis.domain.entity.enums.PromoCategory;
import com.datagami.rentaxis.domain.entity.enums.PromoCtaType;
import com.datagami.rentaxis.domain.entity.enums.PromoPlacement;
import com.datagami.rentaxis.domain.repository.PromoAdEventRepository;
import com.datagami.rentaxis.domain.repository.PromoAdPropertyRepository;
import com.datagami.rentaxis.domain.repository.PromoAdRepository;
import com.datagami.rentaxis.domain.repository.PromoBusinessRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Business and ad CRUD. No role logic — RBAC and any property-manager checks
 * live in {@code com.datagami.rentaxis.api.PromotionAdminController}, the same
 * split as {@link FacilityService}. Cross-tenant lookups throw
 * {@link NotFoundException} (404, never 403) so ids cannot be probed.
 *
 * <p>Every cross-field rule that needs the owning business row is enforced here
 * rather than by bean validation, and in particular the click-through URL is
 * checked against the business's allowlist on write. That is what lets the
 * mobile app open a stored {@code ctaUrl} without re-deriving trust.
 */
@Service
@Transactional
public class PromotionService {

    private final PromoBusinessRepository businessRepository;
    private final PromoAdRepository adRepository;
    private final PromoAdPropertyRepository adPropertyRepository;
    private final PromoAdEventRepository eventRepository;
    private final PromotionUrlValidator urlValidator;

    public PromotionService(PromoBusinessRepository businessRepository,
                            PromoAdRepository adRepository,
                            PromoAdPropertyRepository adPropertyRepository,
                            PromoAdEventRepository eventRepository,
                            PromotionUrlValidator urlValidator) {
        this.businessRepository = businessRepository;
        this.adRepository = adRepository;
        this.adPropertyRepository = adPropertyRepository;
        this.eventRepository = eventRepository;
        this.urlValidator = urlValidator;
    }

    // ------------------------------------------------------------- businesses

    @Transactional(readOnly = true)
    public Page<PromoBusiness> listBusinesses(UUID tenantId, Pageable pageable) {
        return businessRepository.findByTenantId(tenantId, pageable);
    }

    @Transactional(readOnly = true)
    public PromoBusiness getBusiness(UUID tenantId, UUID id) {
        PromoBusiness b = businessRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Business not found"));
        if (!Objects.equals(b.getTenantId(), tenantId)) {
            throw new NotFoundException("Business not found");
        }
        return b;
    }

    public PromoBusiness createBusiness(UUID tenantId, PromoBusinessRequest req) {
        PromoBusiness b = new PromoBusiness();
        b.setTenantId(tenantId);
        applyBusiness(b, req);
        return businessRepository.save(b);
    }

    public PromoBusiness updateBusiness(UUID tenantId, UUID id, PromoBusinessRequest req) {
        PromoBusiness b = getBusiness(tenantId, id);
        applyBusiness(b, req);
        return businessRepository.save(b);
    }

    /**
     * Hard delete only when nothing references the business — ad history and
     * event counts must survive, so anything in use is deactivated instead.
     */
    public void deleteBusiness(UUID tenantId, UUID id) {
        PromoBusiness b = getBusiness(tenantId, id);
        if (adRepository.countByBusinessId(id) > 0) {
            throw new BusinessRuleViolationException(
                    "This business has ads — deactivate it instead of deleting it.");
        }
        businessRepository.delete(b);
    }

    private void applyBusiness(PromoBusiness b, PromoBusinessRequest req) {
        b.setNameEn(req.nameEn().trim());
        b.setNameAr(trimToNull(req.nameAr()));
        b.setLogoUrl(trimToNull(req.logoUrl()));
        b.setCategory(req.category() == null ? PromoCategory.OTHER : req.category());
        b.setPhoneE164(trimToNull(req.phoneE164()));
        b.setWhatsappE164(trimToNull(req.whatsappE164()));
        // Normalise through the validator so the stored list and the check that
        // guards ad URLs can never disagree about what a domain looks like.
        // Entries the validator cannot turn into a hostname are reported rather
        // than dropped: silently discarding them would store an empty allowlist
        // and then refuse every one of this business's ad links, with nothing
        // anywhere explaining why.
        List<String> requested = req.allowedDomains() == null
                ? List.of()
                : req.allowedDomains().stream().map(String::trim).filter(d -> !d.isEmpty()).toList();
        List<String> domains = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (String entry : requested) {
            List<String> parsed = urlValidator.parseDomains(entry);
            if (parsed.isEmpty()) {
                rejected.add(entry);
            } else {
                parsed.stream().filter(d -> !domains.contains(d)).forEach(domains::add);
            }
        }
        if (!rejected.isEmpty()) {
            throw new BusinessRuleViolationException(
                    "Not valid domains: " + String.join(", ", rejected)
                            + ". Enter a hostname like spice-bazaar.ae — a domain already "
                            + "covers its subdomains, so wildcards are not needed.");
        }
        b.setAllowedDomains(domains.isEmpty() ? null : String.join(",", domains));
        if (req.active() != null) {
            b.setActive(req.active());
        }
    }

    // -------------------------------------------------------------------- ads

    @Transactional(readOnly = true)
    public Page<PromoAd> listAds(UUID tenantId, UUID businessId, Pageable pageable) {
        if (businessId != null) {
            return adRepository.findByTenantIdAndBusinessId(tenantId, businessId, pageable);
        }
        return adRepository.findByTenantId(tenantId, pageable);
    }

    @Transactional(readOnly = true)
    public PromoAd getAd(UUID tenantId, UUID id) {
        PromoAd a = adRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Ad not found"));
        if (!Objects.equals(a.getTenantId(), tenantId)) {
            throw new NotFoundException("Ad not found");
        }
        return a;
    }

    public PromoAd createAd(UUID tenantId, PromoAdRequest req) {
        PromoAd a = new PromoAd();
        a.setTenantId(tenantId);
        a.setBusinessId(req.businessId());
        applyAd(tenantId, a, req);
        PromoAd saved = adRepository.save(a);
        replaceTargeting(tenantId, saved.getId(), req.propertyIds());
        return saved;
    }

    public PromoAd updateAd(UUID tenantId, UUID id, PromoAdRequest req) {
        PromoAd a = getAd(tenantId, id);
        a.setBusinessId(req.businessId());
        applyAd(tenantId, a, req);
        PromoAd saved = adRepository.save(a);
        replaceTargeting(tenantId, id, req.propertyIds());
        return saved;
    }

    /**
     * Hard delete only when the ad never ran. Impression and click history is
     * the entire point of the analytics, and fk_pae_ad RESTRICTs, so an ad
     * with events is deactivated instead — the same rule deleteBusiness
     * applies one level up.
     */
    public void deleteAd(UUID tenantId, UUID id) {
        PromoAd ad = getAd(tenantId, id);
        if (eventRepository.countByAdId(id) > 0) {
            throw new BusinessRuleViolationException(
                    "This ad has view history. Deactivate it instead of deleting it.");
        }
        adRepository.delete(ad);
    }

    @Transactional(readOnly = true)
    public List<UUID> targetedPropertyIds(UUID adId) {
        return adPropertyRepository.findByAdId(adId).stream()
                .map(PromoAdProperty::getPropertyId)
                .toList();
    }

    private void replaceTargeting(UUID tenantId, UUID adId, List<UUID> propertyIds) {
        adPropertyRepository.deleteByTenantIdAndAdId(tenantId, adId);
        if (propertyIds == null || propertyIds.isEmpty()) {
            return; // zero rows = every property
        }
        List<PromoAdProperty> rows = propertyIds.stream().distinct().map(pid -> {
            PromoAdProperty row = new PromoAdProperty();
            row.setTenantId(tenantId);
            row.setAdId(adId);
            row.setPropertyId(pid);
            return row;
        }).toList();
        adPropertyRepository.saveAll(rows);
    }

    private void applyAd(UUID tenantId, PromoAd a, PromoAdRequest req) {
        PromoBusiness business = getBusiness(tenantId, req.businessId());
        PromoCtaType ctaType = req.ctaType() == null ? PromoCtaType.NONE : req.ctaType();

        String titleEn = trimToNull(req.titleEn());
        String titleAr = trimToNull(req.titleAr());
        if (titleEn == null && titleAr == null) {
            throw new BusinessRuleViolationException("An ad needs a title in at least one language");
        }
        if (req.startsAt() != null && req.endsAt() != null && !req.endsAt().isAfter(req.startsAt())) {
            throw new BusinessRuleViolationException("endsAt must be after startsAt");
        }

        String ctaUrl = null;
        String couponCode = null;
        switch (ctaType) {
            case WEBSITE -> {
                String url = trimToNull(req.ctaUrl());
                if (url == null) {
                    throw new BusinessRuleViolationException("ctaUrl is required for a website ad");
                }
                if (!urlValidator.isAllowed(url, business.getAllowedDomains())) {
                    throw new BusinessRuleViolationException(
                            "ctaUrl must be https and on one of this business's allowed domains");
                }
                ctaUrl = url;
            }
            case COUPON -> {
                couponCode = trimToNull(req.couponCode());
                if (couponCode == null) {
                    throw new BusinessRuleViolationException("couponCode is required for a coupon ad");
                }
            }
            case CALL -> {
                if (trimToNull(business.getPhoneE164()) == null) {
                    throw new BusinessRuleViolationException(
                            "This business has no phone number — add one before using a call ad");
                }
            }
            case WHATSAPP -> {
                if (trimToNull(business.getWhatsappE164()) == null) {
                    throw new BusinessRuleViolationException(
                            "This business has no WhatsApp number — add one before using a WhatsApp ad");
                }
            }
            case NONE -> {
                // nothing to validate
            }
        }

        a.setTitleEn(titleEn);
        a.setTitleAr(titleAr);
        a.setSubtitleEn(trimToNull(req.subtitleEn()));
        a.setSubtitleAr(trimToNull(req.subtitleAr()));
        a.setBackgroundImageUrl(trimToNull(req.backgroundImageUrl()));
        a.setAccentColor(trimToNull(req.accentColor()));
        a.setCtaType(ctaType);
        a.setCtaLabelEn(trimToNull(req.ctaLabelEn()));
        a.setCtaLabelAr(trimToNull(req.ctaLabelAr()));
        // Fields belonging to other CTA types are cleared, never carried over
        // from a previous edit — a coupon ad must not keep a stale URL.
        a.setCtaUrl(ctaUrl);
        a.setCouponCode(couponCode);
        a.setCouponTermsEn(ctaType == PromoCtaType.COUPON ? trimToNull(req.couponTermsEn()) : null);
        a.setCouponTermsAr(ctaType == PromoCtaType.COUPON ? trimToNull(req.couponTermsAr()) : null);
        a.setStartsAt(req.startsAt());
        a.setEndsAt(req.endsAt());
        a.setPriority(req.priority() == null ? 1 : req.priority());
        a.setPlacement(req.placement() == null ? PromoPlacement.HOME_AND_OFFERS : req.placement());
        if (req.active() != null) {
            a.setActive(req.active());
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
