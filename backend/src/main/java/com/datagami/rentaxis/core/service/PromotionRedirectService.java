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
 * <p>Addendum C: the ad's Android and iPhone links ({@code redirectUrlAndroid},
 * {@code redirectUrlIos}) are resolved the same way, with the same parameters, each
 * on its own; the app picks the one for its platform and falls back to the main link.
 *
 * <p>{@code sharedFields} names the renter-data sources that actually made it into
 * the links (any of them: they share their parameters), so the app can ask before
 * handing them to the business; COUPON_CODE and STATIC are not renter data.
 *
 * <p>Each link is re-checked against the business's allowlist here, at read time,
 * not only when the ad was written: the renter's own data is about to be sent to
 * that host, so an allowlist narrowed since the ad was saved drops that link
 * rather than keeps sending to a domain the business no longer claims. An ad left
 * with no link has no redirect at all.
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

    /**
     * A card's redirect: the resolved main, Android and iPhone links (each null = none)
     * and the renter data in them.
     */
    public record Resolved(String url, String urlAndroid, String urlIos, List<String> sharedFields) {
        public static final Resolved NONE = new Resolved(null, null, null, List.of());
    }

    /** A coupon ad's links that are on its business's allowlist now; null = unset or dropped. */
    private record Links(String url, String android, String ios) {
        boolean none() {
            return url == null && android == null && ios == null;
        }
    }

    private record Candidate(PromoAd ad, Links links) {
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
        List<Candidate> withRedirect = new ArrayList<>();
        for (PromoAd a : ads) {
            PromoBusiness b = businesses.get(a.getBusinessId());
            if (a.getCtaType() != PromoCtaType.COUPON || b == null) {
                continue;
            }
            Links links = new Links(allowedNow(a.getRedirectUrl(), b),
                    allowedNow(a.getRedirectUrlAndroid(), b),
                    allowedNow(a.getRedirectUrlIos(), b));
            if (!links.none()) {
                withRedirect.add(new Candidate(a, links));
            }
        }
        if (withRedirect.isEmpty()) {
            return Map.of();
        }
        RenterData renter = new RenterData(tenantId, renterUserId);
        Map<UUID, Set<UUID>> targeting = adPropertyRepository
                .findByTenantIdAndAdIdIn(tenantId, withRedirect.stream().map(c -> c.ad().getId()).toList()).stream()
                .collect(Collectors.groupingBy(PromoAdProperty::getAdId,
                        Collectors.mapping(PromoAdProperty::getPropertyId, Collectors.toSet())));
        Map<UUID, Set<UUID>> promoterAllowed = new HashMap<>();

        Map<UUID, Resolved> out = new HashMap<>();
        for (Candidate c : withRedirect) {
            PromoAd ad = c.ad();
            // null = the ad targets every property (an admin's untargeted ad only: a
            // promoter's ad never runs untargeted, see PromoAdRepository.findEligible).
            Set<UUID> where = targeting.get(ad.getId());
            if (ad.getPromoterUserId() != null) {
                where = where == null ? Set.of() : where;
                Set<UUID> allowed = promoterAllowed.computeIfAbsent(ad.getPromoterUserId(),
                        uid -> new HashSet<>(promoterPropertyRepository.findPropertyIds(tenantId, uid)));
                where = where.stream().filter(allowed::contains).collect(Collectors.toSet());
            }
            out.put(ad.getId(), resolveOne(ad, c.links(), renter, where));
        }
        return out;
    }

    /** {@code link} if it is on the business's allowlist now, else null. */
    private String allowedNow(String link, PromoBusiness business) {
        return link != null && urlValidator.isAllowed(link, business.getAllowedDomains()) ? link : null;
    }

    /** The parameters, filled for this renter once, appended to each of the ad's links. */
    private Resolved resolveOne(PromoAd ad, Links links, RenterData renter, Set<UUID> targetedProperties) {
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
        return new Resolved(withQueryOrNull(links.url(), pairs), withQueryOrNull(links.android(), pairs),
                withQueryOrNull(links.ios(), pairs), shared.stream().map(Enum::name).toList());
    }

    private static String withQueryOrNull(String url, List<String> pairs) {
        return url == null ? null : withQuery(url, pairs);
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
