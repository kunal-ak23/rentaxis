package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.PromoAdDtos;
import com.datagami.rentaxis.api.dto.PromoAdDTO;
import com.datagami.rentaxis.api.dto.PromoAdRequest;
import com.datagami.rentaxis.api.dto.PromoAdStatsDTO;
import com.datagami.rentaxis.api.dto.PromoBusinessDTO;
import com.datagami.rentaxis.api.dto.PromoBusinessRequest;
import com.datagami.rentaxis.api.exception.AccessDeniedException;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.event.PromoAdSubmittedEvent;
import com.datagami.rentaxis.domain.entity.PromoAd;
import com.datagami.rentaxis.domain.entity.PromoAdProperty;
import com.datagami.rentaxis.domain.entity.PromoBusiness;
import com.datagami.rentaxis.domain.entity.PromoterAccess;
import com.datagami.rentaxis.domain.entity.PromoterBusiness;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.PromoApprovalStatus;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.PromoAdPropertyRepository;
import com.datagami.rentaxis.domain.repository.PromoAdRepository;
import com.datagami.rentaxis.domain.repository.PromoBusinessRepository;
import com.datagami.rentaxis.domain.repository.PromoterAccessRepository;
import com.datagami.rentaxis.domain.repository.PromoterBusinessRepository;
import com.datagami.rentaxis.domain.repository.PromoterPropertyRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What a PROMOTER may do (promoter plan, "Promoter API"): manage the businesses and
 * ads of the businesses an admin granted it, target only the properties an admin
 * gave it, and never put anything in front of a renter — every write of an ad, or of
 * a business with live ads, sends those ads back to PENDING for an admin.
 *
 * <p>Every lookup is scoped to the caller's home organisation AND its access rows,
 * and a business or ad outside them is a 404 (never a 403: ids cannot be probed).
 * The one 403 is a capability the caller visibly lacks — creating or editing a
 * business without the "can create businesses" switch.
 *
 * <p>Every method is transactional (read-only for reads): the Hibernate tenant
 * filter applies only inside a transaction.
 */
@Service
@Transactional
public class PromoterService {

    /** Who is asking: the promoter's user id and the organisation of the request. */
    public record Caller(UUID userId, UUID tenantId) {
    }

    public record Me(boolean canCreateBusinesses, String organisationName) {
    }

    public record PropertyRef(UUID id, String nameEn, String nameAr) {
    }

    private final UserRepository userRepository;
    private final LandlordOrgRepository orgRepository;
    private final PromoterAccessRepository accessRepository;
    private final PromoterBusinessRepository grantRepository;
    private final PromoterPropertyRepository promoterPropertyRepository;
    private final PropertyRepository propertyRepository;
    private final PromoBusinessRepository businessRepository;
    private final PromoAdRepository adRepository;
    private final PromoAdPropertyRepository adPropertyRepository;
    private final PromotionService promotionService;
    private final PromotionStatsService statsService;
    private final PromotionImageService images;
    private final ApplicationEventPublisher events;

    public PromoterService(UserRepository userRepository,
                           LandlordOrgRepository orgRepository,
                           PromoterAccessRepository accessRepository,
                           PromoterBusinessRepository grantRepository,
                           PromoterPropertyRepository promoterPropertyRepository,
                           PropertyRepository propertyRepository,
                           PromoBusinessRepository businessRepository,
                           PromoAdRepository adRepository,
                           PromoAdPropertyRepository adPropertyRepository,
                           PromotionService promotionService,
                           PromotionStatsService statsService,
                           PromotionImageService images,
                           ApplicationEventPublisher events) {
        this.userRepository = userRepository;
        this.orgRepository = orgRepository;
        this.accessRepository = accessRepository;
        this.grantRepository = grantRepository;
        this.promoterPropertyRepository = promoterPropertyRepository;
        this.propertyRepository = propertyRepository;
        this.businessRepository = businessRepository;
        this.adRepository = adRepository;
        this.adPropertyRepository = adPropertyRepository;
        this.promotionService = promotionService;
        this.statsService = statsService;
        this.images = images;
        this.events = events;
    }

    // ------------------------------------------------------------------ me

    @Transactional(readOnly = true)
    public Me me(Caller caller) {
        boolean canCreate = access(caller).canCreateBusinesses();
        String org = orgRepository.findById(caller.tenantId()).map(o -> o.getName()).orElse(null);
        return new Me(canCreate, org);
    }

    /** Only the properties an admin gave the promoter, by name, sorted by English name. */
    @Transactional(readOnly = true)
    public List<PropertyRef> properties(Caller caller) {
        Set<UUID> allowed = access(caller).propertyIds();
        if (allowed.isEmpty()) {
            return List.of();
        }
        return propertyRepository.findByTenantIdAndIdIn(caller.tenantId(), allowed).stream()
                .sorted(Comparator.comparing((Property p) -> p.getNameEn() == null ? "" : p.getNameEn().toLowerCase())
                        .thenComparing(Property::getId))
                .map(p -> new PropertyRef(p.getId(), p.getNameEn(), p.getNameAr()))
                .toList();
    }

    // ----------------------------------------------------------- businesses

    /** The businesses granted to the promoter, oldest first. */
    @Transactional(readOnly = true)
    public List<PromoBusinessDTO> businesses(Caller caller) {
        Set<UUID> granted = access(caller).businessIds();
        if (granted.isEmpty()) {
            return List.of();
        }
        List<PromoBusiness> rows = businessRepository.findByTenantIdAndIdIn(caller.tenantId(), granted).stream()
                .sorted(Comparator.comparing(PromoBusiness::getCreatedAt).thenComparing(PromoBusiness::getId))
                .toList();
        Map<UUID, Long> counts = adRepository.countByBusinessIdIn(caller.tenantId(),
                        rows.stream().map(PromoBusiness::getId).toList()).stream()
                .collect(Collectors.toMap(r -> (UUID) r[0], r -> (Long) r[1]));
        return rows.stream().map(b -> PromoAdDtos.business(b, counts.getOrDefault(b.getId(), 0L), images::forDto))
                .toList();
    }

    /** Needs "can create businesses"; the new business is granted to its creator. */
    public PromoBusinessDTO createBusiness(Caller caller, PromoBusinessRequest req) {
        Access access = access(caller);
        requireCanCreate(access);
        PromoBusiness b = promotionService.createBusiness(caller.tenantId(), req);
        grantRepository.save(PromoterBusiness.of(caller.tenantId(), caller.userId(), b.getId()));
        return PromoAdDtos.business(b, 0L, images::forDto);
    }

    /**
     * Needs the grant (else 404) and "can create businesses" (else 403). The business's
     * name, logo and numbers show on every one of its ads, so each APPROVED ad of it
     * goes back to PENDING, attributed to this promoter.
     */
    public PromoBusinessDTO updateBusiness(Caller caller, UUID businessId, PromoBusinessRequest req) {
        Access access = access(caller);
        requireGrantedBusiness(access, businessId, "Business not found");
        requireCanCreate(access);
        PromoBusiness b = promotionService.updateBusiness(caller.tenantId(), businessId, req);
        Instant now = Instant.now();
        for (PromoAd ad : adRepository.findForUpdateByBusinessAndStatus(
                caller.tenantId(), businessId, PromoApprovalStatus.APPROVED)) {
            markSubmitted(ad, caller, now);
        }
        return PromoAdDtos.business(b, adRepository.countByBusinessId(businessId), images::forDto);
    }

    /**
     * Needs the grant (else 404) and "can create businesses" (else 403). The admin rule
     * (addendum B): deleted if it never had an ad, otherwise archived with its ads.
     */
    public PromotionService.DeleteOutcome deleteBusiness(Caller caller, UUID businessId) {
        Access access = access(caller);
        requireGrantedBusiness(access, businessId, "Business not found");
        requireCanCreate(access);
        return promotionService.deleteBusiness(caller.tenantId(), businessId);
    }

    // ------------------------------------------------------------------ ads

    /** Ads of the granted businesses (one of them, with {@code businessId}), paged. */
    @Transactional(readOnly = true)
    public Page<PromoAdDTO> listAds(Caller caller, UUID businessId, Pageable pageable) {
        Access access = access(caller);
        Page<PromoAd> page;
        if (businessId != null) {
            requireGrantedBusiness(access, businessId, "Business not found");
            page = adRepository.findByTenantIdAndBusinessIdAndArchivedAtIsNull(caller.tenantId(), businessId, pageable);
        } else if (access.businessIds().isEmpty()) {
            return Page.empty(pageable);
        } else {
            page = adRepository.findByTenantIdAndBusinessIdInAndArchivedAtIsNull(
                    caller.tenantId(), access.businessIds(), pageable);
        }
        Map<UUID, PromoAdDTO> dtos = toDtos(caller, access, page.getContent());
        return page.map(a -> dtos.get(a.getId()));
    }

    /**
     * PENDING, written by this promoter. Business granted (else 404); one or more
     * properties, all of them the promoter's (else 400). Priority and placement are
     * the admin's: a promoter's ad starts at 1 / HOME_AND_OFFERS whatever was sent.
     */
    public PromoAdDTO createAd(Caller caller, PromoAdRequest req) {
        Access access = access(caller);
        requireGrantedBusiness(access, req.businessId(), "Business not found");
        requireOwnProperties(access, req.propertyIds());
        PromoAd ad = promotionService.createAd(caller.tenantId(), withRanking(req, null, null));
        markSubmitted(ad, caller, Instant.now());
        return toDtos(caller, access, List.of(ad)).get(ad.getId());
    }

    /** Every edit but priority and placement (kept), and the ad goes back to PENDING. */
    public PromoAdDTO updateAd(Caller caller, UUID adId, PromoAdRequest req) {
        Access access = access(caller);
        PromoAd ad = adRepository.findForUpdate(caller.tenantId(), adId)
                .filter(a -> a.getArchivedAt() == null)
                .orElseThrow(() -> new NotFoundException("Ad not found"));
        requireGrantedBusiness(access, ad.getBusinessId(), "Ad not found");
        requireGrantedBusiness(access, req.businessId(), "Business not found");
        requireOwnProperties(access, req.propertyIds());
        PromoAd saved = promotionService.updateAd(caller.tenantId(), adId,
                withRanking(req, ad.getPriority(), ad.getPlacement()));
        markSubmitted(saved, caller, Instant.now());
        return toDtos(caller, access, List.of(saved)).get(saved.getId());
    }

    /** The admin rule (addendum B): deleted if it never ran, archived if it has view history. */
    public PromotionService.DeleteOutcome deleteAd(Caller caller, UUID adId) {
        requireAd(caller, access(caller), adId);
        return promotionService.deleteAd(caller.tenantId(), adId);
    }

    @Transactional(readOnly = true)
    public PromoAdStatsDTO stats(Caller caller, UUID adId) {
        requireAd(caller, access(caller), adId);
        return statsService.stats(caller.tenantId(), adId);
    }

    // ------------------------------------------------------------- helpers

    /** What the promoter was granted in the request's organisation. */
    private record Access(boolean canCreateBusinesses, Set<UUID> businessIds, Set<UUID> propertyIds) {
    }

    /**
     * The caller's grants, after checking it really is a promoter whose home is the
     * request's organisation. ApiSecurityFilter already allows no other organisation
     * (a promoter has no memberships elsewhere); this is the second line.
     */
    private Access access(Caller caller) {
        if (caller.tenantId() == null) {
            throw new BusinessRuleViolationException("X-Tenant-Id is required");
        }
        User user = userRepository.findById(caller.userId())
                .filter(u -> u.getRole() == UserRole.PROMOTER)
                .filter(u -> Objects.equals(u.getTenantId(), caller.tenantId()))
                .orElseThrow(() -> new AccessDeniedException("Not a promoter of this organisation."));
        boolean canCreate = accessRepository.findByTenantIdAndUserId(caller.tenantId(), user.getId())
                .map(PromoterAccess::isCanCreateBusinesses).orElse(false);
        // A granted business that is archived (addendum B) is out of reach like one never
        // granted: not listed, not written to, its ads 404.
        List<UUID> grants = grantRepository.findBusinessIds(caller.tenantId(), user.getId());
        Set<UUID> live = grants.isEmpty() ? Set.of()
                : businessRepository.findByTenantIdAndIdIn(caller.tenantId(), grants).stream()
                .filter(b -> b.getArchivedAt() == null)
                .map(PromoBusiness::getId)
                .collect(Collectors.toSet());
        return new Access(canCreate, new HashSet<>(live),
                new HashSet<>(promoterPropertyRepository.findPropertyIds(caller.tenantId(), user.getId())));
    }

    private static void requireCanCreate(Access access) {
        if (!access.canCreateBusinesses()) {
            throw new AccessDeniedException("You cannot create or edit businesses. Ask the organisation's admin.");
        }
    }

    private static void requireGrantedBusiness(Access access, UUID businessId, String notFound) {
        if (businessId == null || !access.businessIds().contains(businessId)) {
            throw new NotFoundException(notFound);
        }
    }

    private PromoAd requireAd(Caller caller, Access access, UUID adId) {
        PromoAd ad = adRepository.findById(adId)
                .filter(a -> Objects.equals(a.getTenantId(), caller.tenantId()))
                .filter(a -> a.getArchivedAt() == null)
                .orElseThrow(() -> new NotFoundException("Ad not found"));
        requireGrantedBusiness(access, ad.getBusinessId(), "Ad not found");
        return ad;
    }

    /**
     * A promoter's ad targets one or more properties, all within its allowed set:
     * "no targeting = every property" is an admin's tool only. The same 400 for a
     * property of this organisation the promoter lacks and for any other id, so the
     * answer says nothing about ids outside its set.
     */
    private static void requireOwnProperties(Access access, List<UUID> propertyIds) {
        if (propertyIds == null || propertyIds.isEmpty()) {
            throw new BusinessRuleViolationException("Choose at least one of your properties for this ad.");
        }
        for (UUID id : propertyIds) {
            if (id == null || !access.propertyIds().contains(id)) {
                throw new BusinessRuleViolationException("An ad can target only the properties you were given.");
            }
        }
    }

    /** Back in front of an admin: PENDING, written by this promoter now, the last review cleared. */
    private void markSubmitted(PromoAd ad, Caller caller, Instant now) {
        ad.setApprovalStatus(PromoApprovalStatus.PENDING);
        ad.setPromoterUserId(caller.userId());
        ad.setSubmittedAt(now);
        ad.setReviewedBy(null);
        ad.setReviewedAt(null);
        ad.setRejectionReason(null);
        adRepository.save(ad);
        events.publishEvent(new PromoAdSubmittedEvent(caller.tenantId(), ad.getId(), caller.userId()));
    }

    /** {@code req} with the admin's ranking: null/null means the defaults (1, HOME_AND_OFFERS). */
    private static PromoAdRequest withRanking(PromoAdRequest req, Integer priority,
                                              com.datagami.rentaxis.domain.entity.enums.PromoPlacement placement) {
        return new PromoAdRequest(req.businessId(), req.titleEn(), req.titleAr(), req.subtitleEn(),
                req.subtitleAr(), req.backgroundImageUrl(), req.accentColor(), req.ctaType(),
                req.ctaLabelEn(), req.ctaLabelAr(), req.ctaUrl(), req.couponCode(),
                req.couponTermsEn(), req.couponTermsAr(), req.startsAt(), req.endsAt(),
                priority, placement, req.propertyIds(), req.active(),
                req.redirectUrl(), req.redirectParams());
    }

    /**
     * DTOs for a page of ads, in a handful of queries. Targeting is shown only within
     * the promoter's own properties — it learns nothing about the others.
     */
    private Map<UUID, PromoAdDTO> toDtos(Caller caller, Access access, List<PromoAd> ads) {
        if (ads.isEmpty()) {
            return Map.of();
        }
        UUID tenantId = caller.tenantId();
        List<UUID> adIds = ads.stream().map(PromoAd::getId).toList();
        Map<UUID, List<UUID>> targeting = adPropertyRepository.findByTenantIdAndAdIdIn(tenantId, adIds).stream()
                .filter(p -> access.propertyIds().contains(p.getPropertyId()))
                .collect(Collectors.groupingBy(PromoAdProperty::getAdId,
                        Collectors.mapping(PromoAdProperty::getPropertyId, Collectors.toList())));
        Map<UUID, PromotionStatsService.Totals> totals = statsService.totals(tenantId, adIds);
        Map<UUID, String> businessNames = businessRepository.findByTenantIdAndIdIn(tenantId,
                        ads.stream().map(PromoAd::getBusinessId).distinct().toList()).stream()
                .collect(Collectors.toMap(PromoBusiness::getId, PromoBusiness::getNameEn));
        List<UUID> writers = ads.stream().map(PromoAd::getPromoterUserId).filter(Objects::nonNull).distinct().toList();
        Map<UUID, String> writerNames = writers.isEmpty() ? Map.of()
                : userRepository.findByTenantIdAndIdIn(tenantId, writers).stream()
                .collect(Collectors.toMap(User::getId, User::getName));
        Map<UUID, PromoAdDTO> out = new java.util.HashMap<>();
        for (PromoAd a : ads) {
            out.put(a.getId(), PromoAdDtos.ad(a, businessNames.getOrDefault(a.getBusinessId(), ""),
                    targeting.getOrDefault(a.getId(), List.of()),
                    totals.getOrDefault(a.getId(), PromotionStatsService.Totals.EMPTY),
                    a.getPromoterUserId() == null ? null : writerNames.get(a.getPromoterUserId()),
                    images::forDto));
        }
        return out;
    }
}
