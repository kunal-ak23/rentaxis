package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.PromoAd;
import com.datagami.rentaxis.domain.entity.PromoAdProperty;
import com.datagami.rentaxis.domain.entity.PromoBusiness;
import com.datagami.rentaxis.domain.entity.PromoRedirectParam;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.PromoCtaType;
import com.datagami.rentaxis.domain.entity.enums.PromoRedirectSource;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PromoAdPropertyRepository;
import com.datagami.rentaxis.domain.repository.PromoterPropertyRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Resolves a coupon ad's redirect link for one renter (promoter plan addendum A):
 * the stored {@code redirectUrl} with each parameter appended as
 * {@code name=value}, URL-encoded, the link's own query kept. A parameter whose
 * value this renter does not have (no email, no lease) is left out.
 *
 * <p>{@code sharedFields} names the renter-data sources that actually made it into
 * the link, so the app can ask before handing them to the business; COUPON_CODE and
 * STATIC are not renter data.
 *
 * <p>The link is re-checked against the business's allowlist here, at read time,
 * not only when the ad was written: the renter's own data is about to be sent to
 * that host, so an allowlist narrowed since the ad was saved drops the redirect
 * rather than keeps sending to a domain the business no longer claims.
 *
 * <p>PROPERTY_NAME and UNIT_NUMBER come from the renter's active lease at a
 * property the ad targets (for a promoter's ad, one also in that promoter's
 * allowed set — where the ad is actually shown); an ad that targets every property
 * uses the renter's earliest-starting active lease. English property name; the
 * unit number as the lease's unit has it.
 */
@Service
@Transactional(readOnly = true)
public class PromotionRedirectService {

    /** A card's redirect: the resolved link (null = none) and the renter data in it. */
    public record Resolved(String url, List<String> sharedFields) {
        public static final Resolved NONE = new Resolved(null, List.of());
    }

    private final UserRepository userRepository;
    private final RenterRepository renterRepository;
    private final LeaseRepository leaseRepository;
    private final PromoAdPropertyRepository adPropertyRepository;
    private final PromoterPropertyRepository promoterPropertyRepository;
    private final PromotionUrlValidator urlValidator;

    public PromotionRedirectService(UserRepository userRepository,
                                    RenterRepository renterRepository,
                                    LeaseRepository leaseRepository,
                                    PromoAdPropertyRepository adPropertyRepository,
                                    PromoterPropertyRepository promoterPropertyRepository,
                                    PromotionUrlValidator urlValidator) {
        this.userRepository = userRepository;
        this.renterRepository = renterRepository;
        this.leaseRepository = leaseRepository;
        this.adPropertyRepository = adPropertyRepository;
        this.promoterPropertyRepository = promoterPropertyRepository;
        this.urlValidator = urlValidator;
    }

    /** The redirect of each coupon ad in {@code ads} that has one, for this renter; absent = none. */
    public Map<UUID, Resolved> resolve(UUID tenantId, UUID renterUserId, List<PromoAd> ads,
                                       Map<UUID, PromoBusiness> businesses) {
        List<PromoAd> withRedirect = ads.stream()
                .filter(a -> a.getCtaType() == PromoCtaType.COUPON && a.getRedirectUrl() != null)
                .filter(a -> {
                    PromoBusiness b = businesses.get(a.getBusinessId());
                    return b != null && urlValidator.isAllowed(a.getRedirectUrl(), b.getAllowedDomains());
                })
                .toList();
        if (withRedirect.isEmpty()) {
            return Map.of();
        }
        RenterData renter = new RenterData(tenantId, renterUserId);
        Map<UUID, Set<UUID>> targeting = adPropertyRepository
                .findByTenantIdAndAdIdIn(tenantId, withRedirect.stream().map(PromoAd::getId).toList()).stream()
                .collect(Collectors.groupingBy(PromoAdProperty::getAdId,
                        Collectors.mapping(PromoAdProperty::getPropertyId, Collectors.toSet())));
        Map<UUID, Set<UUID>> promoterAllowed = new HashMap<>();

        Map<UUID, Resolved> out = new HashMap<>();
        for (PromoAd ad : withRedirect) {
            // null = the ad targets every property (an admin's untargeted ad only: a
            // promoter's ad never runs untargeted, see PromoAdRepository.findEligible).
            Set<UUID> where = targeting.get(ad.getId());
            if (ad.getPromoterUserId() != null) {
                where = where == null ? Set.of() : where;
                Set<UUID> allowed = promoterAllowed.computeIfAbsent(ad.getPromoterUserId(),
                        uid -> new HashSet<>(promoterPropertyRepository.findPropertyIds(tenantId, uid)));
                where = where.stream().filter(allowed::contains).collect(Collectors.toSet());
            }
            out.put(ad.getId(), resolveOne(ad, renter, where));
        }
        return out;
    }

    private Resolved resolveOne(PromoAd ad, RenterData renter, Set<UUID> targetedProperties) {
        List<PromoRedirectParam> params = ad.getRedirectParams() == null ? List.of() : ad.getRedirectParams();
        List<String> pairs = new ArrayList<>();
        EnumSet<PromoRedirectSource> shared = EnumSet.noneOf(PromoRedirectSource.class);
        for (PromoRedirectParam p : params) {
            if (p == null || p.name() == null || p.source() == null) {
                continue;
            }
            String value = switch (p.source()) {
                case COUPON_CODE -> ad.getCouponCode();
                case STATIC -> p.value();
                case RENTER_NAME -> renter.name();
                case RENTER_PHONE -> renter.phone();
                case RENTER_EMAIL -> renter.email();
                case PROPERTY_NAME -> renter.place(targetedProperties).map(Place::propertyName).orElse(null);
                case UNIT_NUMBER -> renter.place(targetedProperties).map(Place::unitNumber).orElse(null);
            };
            if (value == null || value.isBlank()) {
                continue;
            }
            pairs.add(encode(p.name()) + "=" + encode(value.trim()));
            if (p.source().isRenterData()) {
                shared.add(p.source());
            }
        }
        return new Resolved(withQuery(ad.getRedirectUrl(), pairs), shared.stream().map(Enum::name).toList());
    }

    /** {@code url} with {@code pairs} appended to its query, before any fragment; its own query kept. */
    static String withQuery(String url, List<String> pairs) {
        if (pairs.isEmpty()) {
            return url;
        }
        String fragment = "";
        String base = url;
        int hash = url.indexOf('#');
        if (hash >= 0) {
            fragment = url.substring(hash);
            base = url.substring(0, hash);
        }
        StringBuilder sb = new StringBuilder(base);
        int q = base.indexOf('?');
        if (q < 0) {
            sb.append('?');
        } else if (q < base.length() - 1 && !base.endsWith("&")) {
            sb.append('&');
        }
        sb.append(String.join("&", pairs));
        return sb.append(fragment).toString();
    }

    /** Percent-encoding for a query component; a space is {@code %20}, not {@code +}. */
    static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private record Place(UUID propertyId, String propertyName, String unitNumber) {
    }

    /** The calling renter's own data, read once per feed and only if some ad asks for it. */
    private final class RenterData {
        private final UUID tenantId;
        private final UUID userId;
        private boolean loaded;
        private User user;
        private Renter renter;
        private List<Place> places;

        RenterData(UUID tenantId, UUID userId) {
            this.tenantId = tenantId;
            this.userId = userId;
        }

        private void load() {
            if (loaded) {
                return;
            }
            loaded = true;
            user = userRepository.findById(userId).orElse(null);
            renter = renterRepository.findByUserId(userId)
                    .filter(r -> Objects.equals(r.getTenantId(), tenantId))
                    .orElse(null);
            places = leaseRepository.findActiveLeasePlacesForRenterUser(tenantId, userId).stream()
                    .map(row -> new Place((UUID) row[0], (String) row[1], (String) row[2]))
                    .toList();
        }

        String name() {
            load();
            return firstNonBlank(renter == null ? null : renter.getNameEn(), user == null ? null : user.getName());
        }

        String phone() {
            load();
            return firstNonBlank(renter == null ? null : renter.getPhone(), user == null ? null : user.getPhoneNumber());
        }

        String email() {
            load();
            return firstNonBlank(renter == null ? null : renter.getEmail(), user == null ? null : user.getEmail());
        }

        /** The lease at a targeted property, earliest start first; any lease when {@code targeted} is null (all). */
        java.util.Optional<Place> place(Set<UUID> targeted) {
            load();
            return places.stream()
                    .filter(p -> targeted == null || targeted.contains(p.propertyId()))
                    .findFirst();
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b != null && !b.isBlank() ? b : null;
    }
}
