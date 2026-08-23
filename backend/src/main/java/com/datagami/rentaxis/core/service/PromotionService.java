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
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

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
 *
 * <p><b>What the allowlist is and is not.</b> It is a typo-and-mistake guard,
 * not a defence against a hostile tenant admin — an admin who wants an ad to
 * point at {@code evil.com} can add {@code evil.com} to the business first.
 * What it buys is that a careless ad edit cannot silently redirect renters to
 * a host the business never registered, and that a stored {@code ctaUrl} is
 * always https. Note also that the check runs at ad-write time only: narrowing
 * a business's allowlist later does not retroactively invalidate ads that
 * already link off it. Protecting renters from their own landlord's admin
 * would require SUPER_ADMIN-approved domains, which this deliberately is not.
 */
@Service
@Transactional
public class PromotionService {

    /** Migration 71's unique index name — kept in sync with the translation below. */
    static final String UQ_PROMO_BUSINESS_NAME = "uq_promo_business_name";

    /** Mirrors PromoAdRequest's @Pattern; the column is varchar(9). */
    private static final Pattern ACCENT_COLOR =
            Pattern.compile("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$");

    private final PromoBusinessRepository businessRepository;
    private final PromoAdRepository adRepository;
    private final PromoAdPropertyRepository adPropertyRepository;
    private final PromoAdEventRepository eventRepository;
    private final PropertyRepository propertyRepository;
    private final PromotionUrlValidator urlValidator;

    public PromotionService(PromoBusinessRepository businessRepository,
                            PromoAdRepository adRepository,
                            PromoAdPropertyRepository adPropertyRepository,
                            PromoAdEventRepository eventRepository,
                            PropertyRepository propertyRepository,
                            PromotionUrlValidator urlValidator) {
        this.businessRepository = businessRepository;
        this.adRepository = adRepository;
        this.adPropertyRepository = adPropertyRepository;
        this.eventRepository = eventRepository;
        this.propertyRepository = propertyRepository;
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
        return saveBusiness(b);
    }

    public PromoBusiness updateBusiness(UUID tenantId, UUID id, PromoBusinessRequest req) {
        PromoBusiness b = getBusiness(tenantId, id);
        applyBusiness(b, req);
        return saveBusiness(b);
    }

    /**
     * Translates a {@code uq_promo_business_name} collision into a 400 instead
     * of letting it surface as a 500. {@code saveAndFlush}, not {@code save} —
     * {@code GenerationType.UUID} assigns the id in memory, so Hibernate would
     * otherwise be free to defer the INSERT past this catch (same reasoning as
     * {@code FacilityService#saveSpot}).
     */
    private PromoBusiness saveBusiness(PromoBusiness b) {
        try {
            return businessRepository.saveAndFlush(b);
        } catch (DataIntegrityViolationException e) {
            Throwable cause = e.getMostSpecificCause();
            String message = cause != null ? cause.getMessage() : null;
            if (message != null && message.contains(UQ_PROMO_BUSINESS_NAME)) {
                throw new BusinessRuleViolationException(
                        "A business named \"" + b.getNameEn() + "\" already exists.");
            }
            throw e;
        }
    }

    /**
     * Hard delete only when nothing references the business — ad history and
     * event counts must survive, so anything in use is deactivated instead.
     */
    public void deleteBusiness(UUID tenantId, UUID id) {
        PromoBusiness b = getBusiness(tenantId, id);
        if (adRepository.countByBusinessId(id) > 0) {
            // Lowercase "deactivate" is asserted by the test; an em dash keeps it
            // mid-sentence rather than starting a new one.
            throw new BusinessRuleViolationException(
                    "This business has ads — deactivate it instead of deleting it.");
        }
        businessRepository.delete(b);
    }

    private void applyBusiness(PromoBusiness b, PromoBusinessRequest req) {
        String nameEn = trimToNull(req.nameEn());
        if (nameEn == null) {
            throw new BusinessRuleViolationException("A business needs a name");
        }
        String logoUrl = requireHttpsOrNull(trimToNull(req.logoUrl()), "logoUrl");
        // Normalise through the validator so the stored list and the check that
        // guards ad URLs can never disagree about what a domain looks like.
        // Entries the validator cannot turn into a hostname are reported rather
        // than dropped: silently discarding them would store an empty allowlist
        // and then refuse every one of this business's ad links, with nothing
        // anywhere explaining why.
        //
        // null means "leave unchanged" and an empty list means "clear", matching
        // `active` in this same method and AmenityUpdateRequest's buildingIds. A
        // partial update that simply omits the field must NOT silently wipe a
        // security-relevant allowlist.
        String allowedDomains = b.getAllowedDomains();
        if (req.allowedDomains() != null) {
            List<String> requested = req.allowedDomains().stream()
                    .filter(java.util.Objects::nonNull)
                    .map(String::trim)
                    .filter(d -> !d.isEmpty())
                    .toList();
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
            allowedDomains = domains.isEmpty() ? null : String.join(",", domains);
        }

        // Everything above can throw; nothing below can. Assign only once the
        // request is known good, so a rejected edit cannot leave a managed
        // entity half-updated in the persistence context.
        b.setNameEn(nameEn);
        b.setNameAr(trimToNull(req.nameAr()));
        b.setLogoUrl(logoUrl);
        b.setCategory(req.category() == null ? PromoCategory.OTHER : req.category());
        b.setPhoneE164(trimToNull(req.phoneE164()));
        b.setWhatsappE164(trimToNull(req.whatsappE164()));
        b.setAllowedDomains(allowedDomains);
        if (req.active() != null) {
            b.setActive(req.active());
        }
    }

    /**
     * Both image URLs end up in {@code CachedNetworkImageProvider} on a renter's
     * phone, so a plain-http or {@code javascript:} value would have the device
     * make an arbitrary outbound request to whatever host an admin typed. Not
     * allowlisted — businesses legitimately host artwork on CDNs — but https is
     * required so the request is at least encrypted and the host is a real host.
     */
    private String requireHttpsOrNull(String url, String field) {
        if (url == null) {
            return null;
        }
        java.net.URI uri;
        try {
            uri = new java.net.URI(url);
        } catch (java.net.URISyntaxException e) {
            throw new BusinessRuleViolationException(field + " is not a valid URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new BusinessRuleViolationException(field + " must be an https URL");
        }
        return url;
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
        applyAd(tenantId, a, req);
        PromoAd saved = adRepository.save(a);
        replaceTargeting(tenantId, saved.getId(), req.propertyIds());
        return saved;
    }

    public PromoAd updateAd(UUID tenantId, UUID id, PromoAdRequest req) {
        PromoAd a = getAd(tenantId, id);
        // businessId is assigned inside applyAd, after validation — mutating a
        // managed entity before a check that can throw leaves it dirty in the
        // persistence context, which only rollback saves.
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

    /**
     * Replaces an ad's targeting set.
     *
     * <p>Every id is checked to belong to this tenant BEFORE the delete, for two
     * reasons. The obvious one: {@code fk_pap_property} references
     * {@code properties(id)} with no tenant predicate, so Postgres would happily
     * accept another tenant's property id, and the differing responses for a
     * real-but-foreign id versus a fabricated one are an existence oracle across
     * the tenant boundary. The subtler one: {@code fk_pap_property} cascades, so
     * a foreign row would couple this ad's targeting to another tenant's
     * lifecycle — and because zero rows means "every property", losing the last
     * row silently BROADENS the ad rather than hiding it. Validating first also
     * means a bad id cannot blow away existing targeting on its way to failing.
     *
     * <p>This mirrors {@code FacilityService#requirePropertyInTenant}, which
     * guards the same class of write for amenities and parking spots.
     */
    private void replaceTargeting(UUID tenantId, UUID adId, List<UUID> propertyIds) {
        List<UUID> targets = propertyIds == null
                ? List.of()
                : propertyIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (propertyIds != null && propertyIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new BusinessRuleViolationException("propertyIds must not contain nulls");
        }
        for (UUID propertyId : targets) {
            if (!propertyRepository.existsByIdAndTenantId(propertyId, tenantId)) {
                throw new NotFoundException("Property not found");
            }
        }

        adPropertyRepository.deleteByTenantIdAndAdId(tenantId, adId);
        if (targets.isEmpty()) {
            return; // zero rows = every property
        }
        List<PromoAdProperty> rows = targets.stream().map(pid -> {
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
        // Re-checked here, not left to the DTO's @Min/@Max. ck_promo_ad_priority
        // would otherwise turn a bad value into a 500 at commit, and the service
        // should be correct standing alone rather than depending on the
        // controller remembering @Valid.
        int priority = req.priority() == null ? 1 : req.priority();
        if (priority < 1 || priority > 10) {
            throw new BusinessRuleViolationException("priority must be between 1 and 10");
        }
        String backgroundImageUrl =
                requireHttpsOrNull(trimToNull(req.backgroundImageUrl()), "backgroundImageUrl");
        // Re-checked here for the same reason as priority above: accent_color is
        // varchar(9), so an unvalidated value is a DataIntegrityViolationException
        // at commit — a 500 with a raw JDBC message rather than a field error.
        String accentColor = trimToNull(req.accentColor());
        if (accentColor != null && !ACCENT_COLOR.matcher(accentColor).matches()) {
            throw new BusinessRuleViolationException(
                    "accentColor must be #RRGGBB or #AARRGGBB");
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

        a.setBusinessId(business.getId());
        a.setTitleEn(titleEn);
        a.setTitleAr(titleAr);
        a.setSubtitleEn(trimToNull(req.subtitleEn()));
        a.setSubtitleAr(trimToNull(req.subtitleAr()));
        a.setBackgroundImageUrl(backgroundImageUrl);
        a.setAccentColor(accentColor);
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
        a.setPriority(priority);
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
