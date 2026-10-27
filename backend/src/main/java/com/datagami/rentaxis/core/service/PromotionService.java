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

import java.time.Instant;
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

    /** The code on the name-collision refusal, so the promoter path can word it without the name (review M2). */
    public static final String BUSINESS_NAME_TAKEN_CODE = "promo.businessNameTaken";

    /** Mirrors PromoAdRequest's @Pattern; the column is varchar(9). */
    private static final Pattern ACCENT_COLOR =
            Pattern.compile("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$");

    private final PromoBusinessRepository businessRepository;
    private final PromoAdRepository adRepository;
    private final PromoAdPropertyRepository adPropertyRepository;
    private final PromoAdEventRepository eventRepository;
    private final PropertyRepository propertyRepository;
    private final PromotionUrlValidator urlValidator;

    /** Uploaded-image routes (ux6); optional so unit tests constructing the service keep working. */
    private PromotionImageService images;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setImages(PromotionImageService images) {
        this.images = images;
    }

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

    /** Live (not archived) businesses. */
    @Transactional(readOnly = true)
    public Page<PromoBusiness> listBusinesses(UUID tenantId, Pageable pageable) {
        return listBusinesses(tenantId, false, pageable);
    }

    /** Addendum B: {@code archived} false = live businesses only, true = archived only. */
    @Transactional(readOnly = true)
    public Page<PromoBusiness> listBusinesses(UUID tenantId, boolean archived, Pageable pageable) {
        return archived
                ? businessRepository.findByTenantIdAndArchivedAtIsNotNull(tenantId, pageable)
                : businessRepository.findByTenantIdAndArchivedAtIsNull(tenantId, pageable);
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
        requireNotArchived(b);
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
                        "A business named \"" + b.getNameEn() + "\" already exists.",
                        BUSINESS_NAME_TAKEN_CODE, java.util.Map.of());
            }
            throw e;
        }
    }

    /** What a delete did (addendum B): removed outright, or archived because history exists. */
    public enum DeleteOutcome { DELETED, ARCHIVED }

    /**
     * Delete always succeeds (addendum B). A business no ad has ever referenced is
     * deleted outright. One with ads — live or archived; {@code fk_promo_ad_business}
     * RESTRICTs — is archived instead and deactivated, and every ad of it not yet
     * archived is archived and deactivated with it (none is hard-deleted here, event
     * history or not: archiving keeps the business's ads restorable together).
     * Deleting an archived business again changes nothing.
     */
    public DeleteOutcome deleteBusiness(UUID tenantId, UUID id) {
        PromoBusiness b = getBusiness(tenantId, id);
        if (adRepository.countByBusinessId(id) == 0) {
            businessRepository.delete(b);
            return DeleteOutcome.DELETED;
        }
        Instant now = Instant.now();
        if (b.getArchivedAt() == null) {
            b.setArchivedAt(now);
        }
        b.setActive(false);
        businessRepository.save(b);
        for (PromoAd ad : adRepository.findByTenantIdAndBusinessIdAndArchivedAtIsNull(tenantId, id)) {
            ad.setArchivedAt(now);
            ad.setActive(false);
            adRepository.save(ad);
        }
        return DeleteOutcome.ARCHIVED;
    }

    /**
     * Addendum B: an archived business back in the lists, still inactive (an admin
     * re-activates it by editing). Its ads stay archived until restored one by one.
     * Refused (400, the name-collision message) when a live business has taken its
     * name meanwhile.
     */
    public PromoBusiness restoreBusiness(UUID tenantId, UUID id) {
        PromoBusiness b = getBusiness(tenantId, id);
        if (b.getArchivedAt() == null) {
            throw new BusinessRuleViolationException("This business is not archived.");
        }
        b.setArchivedAt(null);
        b.setActive(false);
        return saveBusiness(b);
    }

    private static void requireNotArchived(PromoBusiness b) {
        if (b.getArchivedAt() != null) {
            throw new BusinessRuleViolationException("This business is archived — restore it first.");
        }
    }

    private void applyBusiness(PromoBusiness b, PromoBusinessRequest req) {
        String nameEn = trimToNull(req.nameEn());
        if (nameEn == null) {
            throw new BusinessRuleViolationException("A business needs a name");
        }
        String logoUrl = requireHttpsOrNull(b.getTenantId(), trimToNull(req.logoUrl()), "logoUrl");
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
    private String requireHttpsOrNull(UUID tenantId, String url, String field) {
        if (url == null) {
            return null;
        }
        // An image uploaded here is stored as its own (relative) serve route; the
        // DTOs carry it absolute, and an editor sends that back (R1 P2-1).
        if (images != null) {
            url = images.normaliseForStorage(url);
        }
        if (url.startsWith(PromotionImageService.ROUTE_PREFIX)) {
            if (PromotionImageService.fileOf(url).isEmpty()) {
                throw new BusinessRuleViolationException(field + " is not a valid uploaded image");
            }
            // Only this organisation's own upload (R1 P3-2).
            if (images != null && !images.ownedBy(tenantId, url)) {
                throw new BusinessRuleViolationException(field + " is not an image uploaded by this organisation");
            }
            return url;
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
        return listAds(tenantId, businessId, null, false, pageable);
    }

    /** {@code approvalStatus} null = every state (changeset 183). Live (not archived) ads. */
    @Transactional(readOnly = true)
    public Page<PromoAd> listAds(UUID tenantId, UUID businessId,
                                 com.datagami.rentaxis.domain.entity.enums.PromoApprovalStatus approvalStatus,
                                 Pageable pageable) {
        return listAds(tenantId, businessId, approvalStatus, false, pageable);
    }

    /**
     * The admin ad list: optionally one business, optionally one approval state, and
     * either the live ads ({@code archived} false, the default) or the archived ones
     * only (addendum B).
     */
    @Transactional(readOnly = true)
    public Page<PromoAd> listAds(UUID tenantId, UUID businessId,
                                 com.datagami.rentaxis.domain.entity.enums.PromoApprovalStatus approvalStatus,
                                 boolean archived, Pageable pageable) {
        if (businessId != null && approvalStatus != null) {
            return archived
                    ? adRepository.findByTenantIdAndBusinessIdAndApprovalStatusAndArchivedAtIsNotNull(
                            tenantId, businessId, approvalStatus, pageable)
                    : adRepository.findByTenantIdAndBusinessIdAndApprovalStatusAndArchivedAtIsNull(
                            tenantId, businessId, approvalStatus, pageable);
        }
        if (businessId != null) {
            return archived
                    ? adRepository.findByTenantIdAndBusinessIdAndArchivedAtIsNotNull(tenantId, businessId, pageable)
                    : adRepository.findByTenantIdAndBusinessIdAndArchivedAtIsNull(tenantId, businessId, pageable);
        }
        if (approvalStatus != null) {
            return archived
                    ? adRepository.findByTenantIdAndApprovalStatusAndArchivedAtIsNotNull(tenantId, approvalStatus, pageable)
                    : adRepository.findByTenantIdAndApprovalStatusAndArchivedAtIsNull(tenantId, approvalStatus, pageable);
        }
        return archived
                ? adRepository.findByTenantIdAndArchivedAtIsNotNull(tenantId, pageable)
                : adRepository.findByTenantIdAndArchivedAtIsNull(tenantId, pageable);
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
        requireNotArchived(a);
        // A promoter-written ad shows only where it is targeted AND the promoter is
        // allowed (PromoAdRepository.findEligible), so "every property" would quietly
        // show it nowhere. Said here rather than discovered on the renter's phone.
        if (a.getPromoterUserId() != null && (req.propertyIds() == null || req.propertyIds().isEmpty())) {
            throw new BusinessRuleViolationException(
                    "This ad was written by a promoter: choose at least one of the promoter's properties.");
        }
        // businessId is assigned inside applyAd, after validation — mutating a
        // managed entity before a check that can throw leaves it dirty in the
        // persistence context, which only rollback saves.
        applyAd(tenantId, a, req);
        PromoAd saved = adRepository.save(a);
        replaceTargeting(tenantId, id, req.propertyIds());
        return saved;
    }

    /**
     * Delete always succeeds (addendum B). An ad that never ran is deleted outright;
     * one with impression or click history ({@code fk_pae_ad} RESTRICTs, and the
     * history is the analytics) is archived and deactivated instead, its stats still
     * readable.
     */
    public DeleteOutcome deleteAd(UUID tenantId, UUID id) {
        PromoAd ad = getAd(tenantId, id);
        if (eventRepository.countByAdId(id) == 0) {
            adRepository.delete(ad);
            return DeleteOutcome.DELETED;
        }
        if (ad.getArchivedAt() == null) {
            ad.setArchivedAt(Instant.now());
        }
        ad.setActive(false);
        adRepository.save(ad);
        return DeleteOutcome.ARCHIVED;
    }

    /**
     * Addendum B: an archived ad back in the lists, still inactive (an admin
     * re-activates it by editing). Its business must not be archived.
     */
    public PromoAd restoreAd(UUID tenantId, UUID id) {
        PromoAd ad = getAd(tenantId, id);
        if (ad.getArchivedAt() == null) {
            throw new BusinessRuleViolationException("This ad is not archived.");
        }
        PromoBusiness business = getBusiness(tenantId, ad.getBusinessId());
        if (business.getArchivedAt() != null) {
            throw new BusinessRuleViolationException("Restore the business first.");
        }
        ad.setArchivedAt(null);
        ad.setActive(false);
        return adRepository.save(ad);
    }

    /** Addendum B: an archived ad is read-only until restored (edit, approve, reject). */
    public static void requireNotArchived(PromoAd ad) {
        if (ad.getArchivedAt() != null) {
            throw new BusinessRuleViolationException("This ad is archived — restore it first.");
        }
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
        requireNotArchived(business);
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
                requireHttpsOrNull(tenantId, trimToNull(req.backgroundImageUrl()), "backgroundImageUrl");
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
        String redirectUrl = null;
        List<com.datagami.rentaxis.domain.entity.PromoRedirectParam> redirectParams = null;
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
                redirectUrl = trimToNull(req.redirectUrl());
                if (redirectUrl != null) {
                    if (redirectUrl.length() > MAX_REDIRECT_URL) {
                        throw new BusinessRuleViolationException(
                                "redirectUrl must be at most " + MAX_REDIRECT_URL + " characters");
                    }
                    // The same rule as a WEBSITE ctaUrl: the renter app opens it, with
                    // the renter's own data appended, so https and allowlisted only.
                    if (!urlValidator.isAllowed(redirectUrl, business.getAllowedDomains())) {
                        throw new BusinessRuleViolationException(
                                "redirectUrl must be https and on one of this business's allowed domains");
                    }
                }
                redirectParams = redirectParams(req.redirectParams(), redirectUrl != null);
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
        // Addendum A: like the URL and code above, cleared for every other CTA type.
        a.setRedirectUrl(redirectUrl);
        a.setRedirectParams(redirectParams);
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

    static final int MAX_REDIRECT_URL = 2048;
    static final int MAX_REDIRECT_PARAMS = 10;
    static final int MAX_REDIRECT_STATIC_VALUE = 200;
    private static final Pattern REDIRECT_PARAM_NAME = Pattern.compile("^[A-Za-z0-9_.\\-]{1,40}$");

    /**
     * A coupon redirect's parameters (plan addendum A), checked and normalised:
     * at most ten; each name {@code [A-Za-z0-9_.-]{1,40}} and unique within the ad;
     * a value (trimmed, at most 200) only for STATIC and dropped for every other
     * source, whose value the feed fills per renter. Parameters with no redirect
     * link are refused rather than kept dormant. Null or empty means none (null).
     */
    private static List<com.datagami.rentaxis.domain.entity.PromoRedirectParam> redirectParams(
            List<PromoAdRequest.RedirectParam> requested, boolean hasUrl) {
        if (requested == null || requested.isEmpty()) {
            return null;
        }
        if (!hasUrl) {
            throw new BusinessRuleViolationException("Redirect parameters need a redirectUrl");
        }
        if (requested.size() > MAX_REDIRECT_PARAMS) {
            throw new BusinessRuleViolationException(
                    "A redirect link takes at most " + MAX_REDIRECT_PARAMS + " parameters");
        }
        List<com.datagami.rentaxis.domain.entity.PromoRedirectParam> out = new ArrayList<>();
        java.util.Set<String> names = new java.util.HashSet<>();
        for (PromoAdRequest.RedirectParam p : requested) {
            if (p == null) {
                throw new BusinessRuleViolationException("redirectParams must not contain nulls");
            }
            String name = p.name() == null ? null : p.name().trim();
            if (name == null || !REDIRECT_PARAM_NAME.matcher(name).matches()) {
                throw new BusinessRuleViolationException("Redirect parameter name \""
                        + (name == null ? "" : name)
                        + "\" is not valid: use 1-40 letters, digits, '_', '.' or '-'");
            }
            if (!names.add(name)) {
                throw new BusinessRuleViolationException("Redirect parameter \"" + name + "\" is listed twice");
            }
            if (p.source() == null) {
                throw new BusinessRuleViolationException("Redirect parameter \"" + name + "\" needs a source");
            }
            String value = null;
            if (p.source() == com.datagami.rentaxis.domain.entity.enums.PromoRedirectSource.STATIC) {
                value = trimToNull(p.value());
                if (value == null) {
                    throw new BusinessRuleViolationException(
                            "Redirect parameter \"" + name + "\" is a fixed value: enter it");
                }
                if (value.length() > MAX_REDIRECT_STATIC_VALUE) {
                    throw new BusinessRuleViolationException("Redirect parameter \"" + name
                            + "\" value must be at most " + MAX_REDIRECT_STATIC_VALUE + " characters");
                }
            }
            out.add(new com.datagami.rentaxis.domain.entity.PromoRedirectParam(name, p.source(), value));
        }
        return out;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
