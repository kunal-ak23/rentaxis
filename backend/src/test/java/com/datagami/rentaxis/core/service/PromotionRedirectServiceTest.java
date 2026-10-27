package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.PromoAd;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The coupon redirect's per-renter resolution (promoter plan addendum A), without a database. */
class PromotionRedirectServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID renterUserId = UUID.randomUUID();
    private final UserRepository users = mock(UserRepository.class);
    private final RenterRepository renters = mock(RenterRepository.class);
    private final LeaseRepository leases = mock(LeaseRepository.class);
    private final PromoAdPropertyRepository targeting = mock(PromoAdPropertyRepository.class);
    private final PromoterPropertyRepository promoterProperties = mock(PromoterPropertyRepository.class);
    private PromotionRedirectService service;
    private PromoBusiness business;

    @BeforeEach
    void setUp() {
        service = new PromotionRedirectService(users, renters, leases, targeting, promoterProperties,
                new PromotionUrlValidator());
        business = new PromoBusiness();
        business.setId(UUID.randomUUID());
        business.setAllowedDomains("quickwash.example");
        when(targeting.findByTenantIdAndAdIdIn(any(), anyCollection())).thenReturn(List.of());
        when(leases.findActiveLeasePlacesForRenterUser(tenantId, renterUserId)).thenReturn(List.of());
    }

    @Test
    void aRenterWithNoEmailAnywhereHasTheEmailParameterLeftOut() {
        User u = new User();
        u.setId(renterUserId);
        u.setName("Login Name");
        u.setEmail(null);
        when(users.findById(renterUserId)).thenReturn(Optional.of(u));
        Renter r = new Renter();
        r.setTenantId(tenantId);
        r.setNameEn("Aisha");
        when(renters.findByUserId(renterUserId)).thenReturn(Optional.of(r));

        PromotionRedirectService.Resolved res = resolve(ad("https://quickwash.example/r",
                new PromoRedirectParam("e", PromoRedirectSource.RENTER_EMAIL, null),
                new PromoRedirectParam("n", PromoRedirectSource.RENTER_NAME, null)));

        assertThat(res.url()).isEqualTo("https://quickwash.example/r?n=Aisha");
        assertThat(res.sharedFields()).containsExactly("RENTER_NAME");
    }

    @Test
    void valuesAreEncodedAsQueryComponents() {
        PromotionRedirectService.Resolved res = resolve(ad("https://quickwash.example/r?",
                new PromoRedirectParam("q", PromoRedirectSource.STATIC, "a&b=c d+é/مفتاح")));

        assertThat(res.url()).isEqualTo(
                "https://quickwash.example/r?q=a%26b%3Dc%20d%2B%C3%A9%2F%D9%85%D9%81%D8%AA%D8%A7%D8%AD");
        assertThat(res.sharedFields()).isEmpty();
    }

    @Test
    void anExistingQueryIsKeptWhateverItEndsWith() {
        assertThat(PromotionRedirectService.withQuery("https://h.example/r?a=1", List.of("b=2")))
                .isEqualTo("https://h.example/r?a=1&b=2");
        assertThat(PromotionRedirectService.withQuery("https://h.example/r?a=1&", List.of("b=2")))
                .isEqualTo("https://h.example/r?a=1&b=2");
        assertThat(PromotionRedirectService.withQuery("https://h.example/r?", List.of("b=2")))
                .isEqualTo("https://h.example/r?b=2");
        assertThat(PromotionRedirectService.withQuery("https://h.example/r#frag", List.of("b=2", "c=3")))
                .isEqualTo("https://h.example/r?b=2&c=3#frag");
        assertThat(PromotionRedirectService.withQuery("https://h.example/r?a=1", List.of()))
                .isEqualTo("https://h.example/r?a=1");
    }

    @Test
    void onlyCouponAdsWithARedirectResolve() {
        PromoAd website = ad("https://quickwash.example/r",
                new PromoRedirectParam("q", PromoRedirectSource.STATIC, "x"));
        website.setCtaType(PromoCtaType.WEBSITE);
        PromoAd noLink = ad(null);

        Map<UUID, PromotionRedirectService.Resolved> out = service.resolve(tenantId, renterUserId,
                List.of(website, noLink), Map.of(business.getId(), business));

        assertThat(out).isEmpty();
    }

    private PromotionRedirectService.Resolved resolve(PromoAd ad) {
        return service.resolve(tenantId, renterUserId, List.of(ad), Map.of(business.getId(), business))
                .get(ad.getId());
    }

    private PromoAd ad(String redirectUrl, PromoRedirectParam... params) {
        PromoAd a = new PromoAd();
        a.setId(UUID.randomUUID());
        a.setTenantId(tenantId);
        a.setBusinessId(business.getId());
        a.setCtaType(PromoCtaType.COUPON);
        a.setCouponCode("SAVE10");
        a.setRedirectUrl(redirectUrl);
        a.setRedirectParams(List.of(params));
        return a;
    }
}
