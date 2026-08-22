package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.PromoAdDTO;
import com.datagami.rentaxis.api.dto.PromoAdRequest;
import com.datagami.rentaxis.api.dto.PromoBusinessDTO;
import com.datagami.rentaxis.api.dto.PromoBusinessRequest;
import com.datagami.rentaxis.core.service.PromotionService;
import com.datagami.rentaxis.core.service.PromotionStatsService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.PromoAd;
import com.datagami.rentaxis.domain.entity.PromoBusiness;
import com.datagami.rentaxis.domain.entity.enums.PromoCategory;
import com.datagami.rentaxis.domain.entity.enums.PromoCtaType;
import com.datagami.rentaxis.domain.repository.PromoAdPropertyRepository;
import com.datagami.rentaxis.domain.repository.PromoAdRepository;
import com.datagami.rentaxis.domain.repository.PromoBusinessRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionAdminControllerTest {

    @Mock PromotionService promotionService;
    @Mock PromotionStatsService statsService;
    @Mock PromoAdRepository adRepository;
    @Mock PromoAdPropertyRepository adPropertyRepository;
    @Mock PromoBusinessRepository businessRepository;

    @InjectMocks PromotionAdminController controller;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID businessId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private PromoBusiness business() {
        PromoBusiness b = new PromoBusiness();
        b.setId(businessId);
        b.setTenantId(tenantId);
        b.setNameEn("Spice Bazaar");
        b.setCategory(PromoCategory.DINING);
        b.setAllowedDomains("spice-bazaar.ae,gym.example.com");
        return b;
    }

    private PromoAd ad() {
        PromoAd a = new PromoAd();
        a.setId(UUID.randomUUID());
        a.setTenantId(tenantId);
        a.setBusinessId(businessId);
        a.setTitleEn("Friday brunch");
        a.setCtaType(PromoCtaType.COUPON);
        return a;
    }

    @Test
    void listBusinesses_splitsAllowedDomainsIntoAList() {
        Page<PromoBusiness> page = new PageImpl<>(List.of(business()));
        when(promotionService.listBusinesses(eq(tenantId), any(Pageable.class))).thenReturn(page);
        when(adRepository.countByBusinessId(businessId)).thenReturn(2L);

        ResponseEntity<Page<PromoBusinessDTO>> res =
                controller.listBusinesses(PageRequest.of(0, 10));

        PromoBusinessDTO dto = res.getBody().getContent().get(0);
        assertThat(dto.allowedDomains()).containsExactly("spice-bazaar.ae", "gym.example.com");
        assertThat(dto.adCount()).isEqualTo(2L);
    }

    @Test
    void createBusiness_returns201() {
        when(promotionService.createBusiness(eq(tenantId), any(PromoBusinessRequest.class)))
                .thenReturn(business());

        ResponseEntity<PromoBusinessDTO> res = controller.createBusiness(new PromoBusinessRequest(
                "Spice Bazaar", null, null, PromoCategory.DINING, null, null, List.of(), null));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void listAds_batchesTargetingAndStatsRatherThanQueryingPerRow() {
        PromoAd a1 = ad();
        PromoAd a2 = ad();
        Page<PromoAd> page = new PageImpl<>(List.of(a1, a2));
        when(promotionService.listAds(eq(tenantId), eq(null), any(Pageable.class))).thenReturn(page);
        when(adPropertyRepository.findByAdIdIn(anyList())).thenReturn(List.of());
        when(statsService.totals(eq(tenantId), anyList())).thenReturn(Map.of(
                a1.getId(), new PromotionStatsService.Totals(100, 10, 80, 8)));
        when(businessRepository.findByTenantIdAndIdIn(eq(tenantId), anyCollection()))
                .thenReturn(List.of(business()));

        ResponseEntity<Page<PromoAdDTO>> res = controller.listAds(null, PageRequest.of(0, 10));

        List<PromoAdDTO> rows = res.getBody().getContent();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).impressions()).isEqualTo(100);
        assertThat(rows.get(1).impressions()).isZero();
        assertThat(rows.get(0).businessNameEn()).isEqualTo("Spice Bazaar");
        // Three batched calls for the whole page, never one per row.
        verify(adPropertyRepository).findByAdIdIn(anyList());
        verify(statsService).totals(eq(tenantId), anyList());
        verify(businessRepository).findByTenantIdAndIdIn(eq(tenantId), anyCollection());
        verify(promotionService, never()).targetedPropertyIds(any());
        verify(promotionService, never()).getBusiness(any(), any());
    }

    @Test
    void listAds_skipsBatchQueriesForAnEmptyPage() {
        when(promotionService.listAds(eq(tenantId), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        controller.listAds(null, PageRequest.of(0, 10));

        verify(adPropertyRepository, never()).findByAdIdIn(anyList());
        verify(statsService, never()).totals(any(), anyList());
        verify(businessRepository, never()).findByTenantIdAndIdIn(any(), anyCollection());
    }

    @Test
    void listAds_mapsEverySwapProneFieldToTheRightSlot() {
        // PromoAdDTO is built positionally with 26 arguments, and seven pairs of
        // adjacent components share a type — titleEn/titleAr, the subtitles, the
        // cta labels, the coupon terms, startsAt/endsAt, impressions/clicks,
        // createdAt/updatedAt. Transposing any pair compiles cleanly and ships
        // wrong data, so give each a distinct value and check where it lands.
        PromoAd a = ad();
        a.setTitleEn("EN title");
        a.setTitleAr("AR title");
        a.setSubtitleEn("EN subtitle");
        a.setSubtitleAr("AR subtitle");
        a.setCtaLabelEn("EN label");
        a.setCtaLabelAr("AR label");
        a.setCouponTermsEn("EN terms");
        a.setCouponTermsAr("AR terms");
        a.setStartsAt(Instant.parse("2026-01-01T00:00:00Z"));
        a.setEndsAt(Instant.parse("2026-12-31T00:00:00Z"));
        a.setCreatedAt(Instant.parse("2025-01-01T00:00:00Z"));
        a.setUpdatedAt(Instant.parse("2025-06-01T00:00:00Z"));

        when(promotionService.listAds(eq(tenantId), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(a)));
        when(adPropertyRepository.findByAdIdIn(anyList())).thenReturn(List.of());
        when(statsService.totals(eq(tenantId), anyList())).thenReturn(Map.of(
                a.getId(), new PromotionStatsService.Totals(1240, 87, 900, 60)));
        when(businessRepository.findByTenantIdAndIdIn(eq(tenantId), anyCollection()))
                .thenReturn(List.of(business()));

        PromoAdDTO dto = controller.listAds(null, PageRequest.of(0, 10))
                .getBody().getContent().get(0);

        assertThat(dto.titleEn()).isEqualTo("EN title");
        assertThat(dto.titleAr()).isEqualTo("AR title");
        assertThat(dto.subtitleEn()).isEqualTo("EN subtitle");
        assertThat(dto.subtitleAr()).isEqualTo("AR subtitle");
        assertThat(dto.ctaLabelEn()).isEqualTo("EN label");
        assertThat(dto.ctaLabelAr()).isEqualTo("AR label");
        assertThat(dto.couponTermsEn()).isEqualTo("EN terms");
        assertThat(dto.couponTermsAr()).isEqualTo("AR terms");
        assertThat(dto.startsAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(dto.endsAt()).isEqualTo(Instant.parse("2026-12-31T00:00:00Z"));
        assertThat(dto.createdAt()).isEqualTo(Instant.parse("2025-01-01T00:00:00Z"));
        assertThat(dto.updatedAt()).isEqualTo(Instant.parse("2025-06-01T00:00:00Z"));
        assertThat(dto.impressions()).isEqualTo(1240);
        assertThat(dto.clicks()).isEqualTo(87);
    }

    @Test
    void deleteAd_returns204() {
        assertThat(controller.deleteAd(UUID.randomUUID()).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        verify(promotionService).deleteAd(eq(tenantId), any(UUID.class));
    }

    @Test
    void stats_delegatesToTheStatsService() {
        UUID adId = UUID.randomUUID();
        controller.stats(adId);
        verify(statsService).stats(tenantId, adId);
    }
}
