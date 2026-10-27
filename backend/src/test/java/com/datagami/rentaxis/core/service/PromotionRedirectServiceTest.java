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

    // ------------------------------------------------- addendum C: per-platform links

    @Test
    void eachPlatformLinkGetsTheSameParametersEncodedTheSameWay() {
        PromoAd ad = ad("https://quickwash.example/r",
                new PromoRedirectParam("code", PromoRedirectSource.COUPON_CODE, null),
                new PromoRedirectParam("q", PromoRedirectSource.STATIC, "a&b c"));
        ad.setRedirectUrlAndroid("https://android.quickwash.example/open?src=miftah");
        ad.setRedirectUrlIos("https://ios.quickwash.example/open#top");

        PromotionRedirectService.Resolved res = resolve(ad);

        assertThat(res.url()).isEqualTo("https://quickwash.example/r?code=SAVE10&q=a%26b%20c");
        // Its own query kept; the fragment stays last.
        assertThat(res.urlAndroid())
                .isEqualTo("https://android.quickwash.example/open?src=miftah&code=SAVE10&q=a%26b%20c");
        assertThat(res.urlIos()).isEqualTo("https://ios.quickwash.example/open?code=SAVE10&q=a%26b%20c#top");
        assertThat(res.sharedFields()).isEmpty();
    }

    @Test
    void aPlatformLinkAloneResolvesAndNamesTheRenterDataInIt() {
        Renter r = new Renter();
        r.setTenantId(tenantId);
        r.setNameEn("Aisha");
        when(renters.findByUserId(renterUserId)).thenReturn(Optional.of(r));
        when(users.findById(renterUserId)).thenReturn(Optional.empty());
        PromoAd android = ad(null,
                new PromoRedirectParam("n", PromoRedirectSource.RENTER_NAME, null),
                new PromoRedirectParam("e", PromoRedirectSource.RENTER_EMAIL, null));
        android.setRedirectUrlAndroid("https://android.quickwash.example/open");
        PromoAd ios = ad(null, new PromoRedirectParam("n", PromoRedirectSource.RENTER_NAME, null));
        ios.setRedirectUrlIos("https://ios.quickwash.example/open");

        Map<UUID, PromotionRedirectService.Resolved> out = service.resolve(tenantId, renterUserId,
                List.of(android, ios), Map.of(business.getId(), business));

        PromotionRedirectService.Resolved a = out.get(android.getId());
        assertThat(a.url()).isNull();
        assertThat(a.urlAndroid()).isEqualTo("https://android.quickwash.example/open?n=Aisha");
        assertThat(a.urlIos()).isNull();
        assertThat(a.sharedFields()).containsExactly("RENTER_NAME");
        PromotionRedirectService.Resolved i = out.get(ios.getId());
        assertThat(i.url()).isNull();
        assertThat(i.urlAndroid()).isNull();
        assertThat(i.urlIos()).isEqualTo("https://ios.quickwash.example/open?n=Aisha");
        assertThat(i.sharedFields()).containsExactly("RENTER_NAME");
    }

    @Test
    void aPlatformLinkOffTheAllowlistNowIsDroppedAndTheOthersKept() {
        business.setAllowedDomains("quickwash.example");
        PromoAd ad = ad("https://quickwash.example/r", new PromoRedirectParam("c", PromoRedirectSource.COUPON_CODE, null));
        ad.setRedirectUrlAndroid("https://play.google.com/store/apps/details?id=com.quickwash");
        ad.setRedirectUrlIos("https://ios.quickwash.example/open");

        PromotionRedirectService.Resolved res = resolve(ad);

        assertThat(res.url()).isEqualTo("https://quickwash.example/r?c=SAVE10");
        assertThat(res.urlAndroid()).isNull();
        assertThat(res.urlIos()).isEqualTo("https://ios.quickwash.example/open?c=SAVE10");
    }

    @Test
    void anAdWhoseEveryLinkIsOffTheAllowlistHasNoRedirect() {
        PromoAd ad = ad(null, new PromoRedirectParam("c", PromoRedirectSource.COUPON_CODE, null));
        ad.setRedirectUrlAndroid("https://play.google.com/store/apps/details?id=com.quickwash");
        ad.setRedirectUrlIos("https://apps.apple.com/app/id1");

        assertThat(service.resolve(tenantId, renterUserId, List.of(ad), Map.of(business.getId(), business)))
                .isEmpty();
    }

    @Test
    void aNonCouponAdIgnoresItsPlatformLinks() {
        PromoAd website = ad(null, new PromoRedirectParam("q", PromoRedirectSource.STATIC, "x"));
        website.setCtaType(PromoCtaType.WEBSITE);
        website.setRedirectUrlAndroid("https://android.quickwash.example/open");
        website.setRedirectUrlIos("https://ios.quickwash.example/open");

        assertThat(service.resolve(tenantId, renterUserId, List.of(website), Map.of(business.getId(), business)))
                .isEmpty();
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
