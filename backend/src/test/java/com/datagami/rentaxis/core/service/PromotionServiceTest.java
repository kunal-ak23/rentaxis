package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.PromoAdRequest;
import com.datagami.rentaxis.api.dto.PromoBusinessRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.PromoAd;
import com.datagami.rentaxis.domain.entity.PromoBusiness;
import com.datagami.rentaxis.domain.entity.enums.PromoCtaType;
import com.datagami.rentaxis.domain.repository.PromoAdEventRepository;
import com.datagami.rentaxis.domain.repository.PromoAdPropertyRepository;
import com.datagami.rentaxis.domain.repository.PromoAdRepository;
import com.datagami.rentaxis.domain.repository.PromoBusinessRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionServiceTest {

    @Mock PromoBusinessRepository businessRepository;
    @Mock PromoAdRepository adRepository;
    @Mock PromoAdPropertyRepository adPropertyRepository;
    @Mock PromoAdEventRepository eventRepository;

    PromotionService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID businessId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PromotionService(businessRepository, adRepository,
                adPropertyRepository, eventRepository, new PromotionUrlValidator());
    }

    private PromoBusiness business() {
        PromoBusiness b = new PromoBusiness();
        b.setId(businessId);
        b.setTenantId(tenantId);
        b.setNameEn("Spice Bazaar");
        b.setAllowedDomains("spice-bazaar.ae");
        b.setPhoneE164("+971501234567");
        b.setWhatsappE164("+971501234567");
        return b;
    }

    private PromoAdRequest adRequest(PromoCtaType type, String url, String coupon) {
        return new PromoAdRequest(businessId, "Friday brunch", null, null, null,
                null, null, type, null, null, url, coupon, null, null,
                null, null, 1, null, List.of(), null);
    }

    // ---------------------------------------------------------------- business

    @Test
    void createBusiness_normalisesAllowedDomains() {
        when(businessRepository.save(any(PromoBusiness.class))).thenAnswer(i -> i.getArgument(0));

        PromoBusiness saved = service.createBusiness(tenantId, new PromoBusinessRequest(
                "Spice Bazaar", null, null, null, null, null,
                List.of("HTTPS://Www.Spice-Bazaar.AE/menu", " gym.example.com "), null));

        assertThat(saved.getAllowedDomains()).isEqualTo("www.spice-bazaar.ae,gym.example.com");
    }

    @Test
    void createBusiness_reportsDomainEntriesItCannotUse() {
        // A wildcard is the most likely thing an admin types meaning "and
        // subdomains". Storing nothing and failing every ad link later is the
        // worst outcome; say so at the point of entry instead.
        assertThatThrownBy(() -> service.createBusiness(tenantId, new PromoBusinessRequest(
                "Spice Bazaar", null, null, null, null, null,
                List.of("*.spice-bazaar.ae"), null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("*.spice-bazaar.ae");
        verify(businessRepository, never()).save(any(PromoBusiness.class));
    }

    @Test
    void getBusiness_crossTenantThrowsNotFound() {
        PromoBusiness other = business();
        other.setTenantId(UUID.randomUUID());
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.getBusiness(tenantId, businessId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteBusiness_refusesWhenAdsExist() {
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(business()));
        when(adRepository.countByBusinessId(businessId)).thenReturn(3L);

        assertThatThrownBy(() -> service.deleteBusiness(tenantId, businessId))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("deactivate");
        verify(businessRepository, never()).delete(any());
    }

    // --------------------------------------------------------------------- ad

    @Test
    void createAd_acceptsUrlOnAnAllowedDomain() {
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(business()));
        when(adRepository.save(any(PromoAd.class))).thenAnswer(i -> i.getArgument(0));

        PromoAd saved = service.createAd(tenantId,
                adRequest(PromoCtaType.WEBSITE, "https://offers.spice-bazaar.ae/friday", null));

        assertThat(saved.getCtaUrl()).isEqualTo("https://offers.spice-bazaar.ae/friday");
    }

    @Test
    void createAd_rejectsUrlOffTheAllowlist() {
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(business()));

        assertThatThrownBy(() -> service.createAd(tenantId,
                adRequest(PromoCtaType.WEBSITE, "https://evil.com/x", null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("allowed domains");
        verify(adRepository, never()).save(any());
    }

    @Test
    void createAd_rejectsNonHttpsUrl() {
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(business()));

        assertThatThrownBy(() -> service.createAd(tenantId,
                adRequest(PromoCtaType.WEBSITE, "http://spice-bazaar.ae/x", null)))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void createAd_rejectsWebsiteWithoutUrl() {
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(business()));

        assertThatThrownBy(() -> service.createAd(tenantId,
                adRequest(PromoCtaType.WEBSITE, null, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("ctaUrl");
    }

    @Test
    void createAd_rejectsCouponWithoutCode() {
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(business()));

        assertThatThrownBy(() -> service.createAd(tenantId,
                adRequest(PromoCtaType.COUPON, null, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("couponCode");
    }

    @Test
    void createAd_rejectsCallWhenBusinessHasNoPhone() {
        PromoBusiness b = business();
        b.setPhoneE164(null);
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(b));

        assertThatThrownBy(() -> service.createAd(tenantId,
                adRequest(PromoCtaType.CALL, null, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("phone");
    }

    @Test
    void createAd_rejectsBlankTitleInBothLanguages() {
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(business()));

        PromoAdRequest req = new PromoAdRequest(businessId, "  ", "", null, null,
                null, null, PromoCtaType.NONE, null, null, null, null, null, null,
                null, null, 1, null, List.of(), null);

        assertThatThrownBy(() -> service.createAd(tenantId, req))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("title");
    }

    @Test
    void createAd_rejectsEndBeforeStart() {
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(business()));
        Instant start = Instant.now();

        PromoAdRequest req = new PromoAdRequest(businessId, "Brunch", null, null, null,
                null, null, PromoCtaType.NONE, null, null, null, null, null, null,
                start, start.minus(1, ChronoUnit.DAYS), 1, null, List.of(), null);

        assertThatThrownBy(() -> service.createAd(tenantId, req))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("endsAt");
    }

    @Test
    void createAd_rejectsBusinessFromAnotherTenant() {
        PromoBusiness other = business();
        other.setTenantId(UUID.randomUUID());
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.createAd(tenantId,
                adRequest(PromoCtaType.NONE, null, null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void createAd_clearsUnusedCtaFields() {
        // A COUPON ad must not carry a stale URL from an earlier edit.
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(business()));
        when(adRepository.save(any(PromoAd.class))).thenAnswer(i -> i.getArgument(0));

        PromoAd saved = service.createAd(tenantId,
                adRequest(PromoCtaType.COUPON, "https://spice-bazaar.ae/x", "MIFTAH25"));

        assertThat(saved.getCouponCode()).isEqualTo("MIFTAH25");
        assertThat(saved.getCtaUrl()).isNull();
    }

    @Test
    void deleteAd_refusesWhenTheAdHasViewHistory() {
        PromoAd existing = new PromoAd();
        existing.setId(UUID.randomUUID());
        existing.setTenantId(tenantId);
        when(adRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(eventRepository.countByAdId(existing.getId())).thenReturn(42L);

        assertThatThrownBy(() -> service.deleteAd(tenantId, existing.getId()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Deactivate");
        verify(adRepository, never()).delete(any());
    }

    @Test
    void deleteAd_allowsDeletingAnAdThatNeverRan() {
        PromoAd existing = new PromoAd();
        existing.setId(UUID.randomUUID());
        existing.setTenantId(tenantId);
        when(adRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(eventRepository.countByAdId(existing.getId())).thenReturn(0L);

        service.deleteAd(tenantId, existing.getId());

        verify(adRepository).delete(existing);
    }

    @Test
    void updateAd_replacesTargetingRows() {
        PromoAd existing = new PromoAd();
        existing.setId(UUID.randomUUID());
        existing.setTenantId(tenantId);
        existing.setBusinessId(businessId);
        UUID propertyId = UUID.randomUUID();

        when(adRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(business()));
        when(adRepository.save(any(PromoAd.class))).thenAnswer(i -> i.getArgument(0));

        PromoAdRequest req = new PromoAdRequest(businessId, "Brunch", null, null, null,
                null, null, PromoCtaType.NONE, null, null, null, null, null, null,
                null, null, 1, null, List.of(propertyId), null);

        service.updateAd(tenantId, existing.getId(), req);

        verify(adPropertyRepository).deleteByTenantIdAndAdId(tenantId, existing.getId());
        verify(adPropertyRepository).saveAll(any());
    }
}
