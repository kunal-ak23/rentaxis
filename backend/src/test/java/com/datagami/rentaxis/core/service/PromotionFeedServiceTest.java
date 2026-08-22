package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.PromoAdCardDTO;
import com.datagami.rentaxis.api.dto.PromoEventBatchRequest;
import com.datagami.rentaxis.domain.entity.PromoAd;
import com.datagami.rentaxis.domain.entity.PromoAdEvent;
import com.datagami.rentaxis.domain.entity.PromoBusiness;
import com.datagami.rentaxis.domain.entity.enums.PromoCtaType;
import com.datagami.rentaxis.domain.entity.enums.PromoEventType;
import com.datagami.rentaxis.domain.entity.enums.PromoPlacement;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PromoAdEventRepository;
import com.datagami.rentaxis.domain.repository.PromoAdRepository;
import com.datagami.rentaxis.domain.repository.PromoBusinessRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionFeedServiceTest {

    @Mock PromoAdRepository adRepository;
    @Mock PromoBusinessRepository businessRepository;
    @Mock PromoAdEventRepository eventRepository;
    @Mock LeaseRepository leaseRepository;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID renterId = UUID.randomUUID();
    private final UUID businessId = UUID.randomUUID();

    private PromotionFeedService service() {
        return new PromotionFeedService(adRepository, businessRepository,
                eventRepository, leaseRepository);
    }

    private PromoBusiness business() {
        PromoBusiness b = new PromoBusiness();
        b.setId(businessId);
        b.setTenantId(tenantId);
        b.setNameEn("Spice Bazaar");
        b.setPhoneE164("+971501234567");
        b.setWhatsappE164("+971509999999");
        return b;
    }

    private PromoAd ad(int priority) {
        PromoAd a = new PromoAd();
        a.setId(UUID.randomUUID());
        a.setTenantId(tenantId);
        a.setBusinessId(businessId);
        a.setTitleEn("Brunch");
        a.setPriority(priority);
        a.setPlacement(PromoPlacement.HOME_AND_OFFERS);
        return a;
    }

    private List<PromoAd> ads(int count) {
        List<PromoAd> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(ad(1));
        }
        return out;
    }

    @Test
    void homeFeed_capsAtSixCards() {
        when(leaseRepository.findActivePropertyIdsForRenterUser(tenantId, renterId))
                .thenReturn(List.of(UUID.randomUUID()));
        when(adRepository.findEligible(eq(tenantId), any(), anyList(), anyList()))
                .thenReturn(ads(40));
        when(businessRepository.findByIdIn(anyList())).thenReturn(List.of(business()));

        assertThat(service().homeFeed(tenantId, renterId)).hasSize(6);
    }

    @Test
    void homeFeed_isStableAcrossCalls() {
        when(leaseRepository.findActivePropertyIdsForRenterUser(tenantId, renterId))
                .thenReturn(List.of());
        List<PromoAd> pool = ads(40);
        when(adRepository.findEligible(eq(tenantId), any(), anyList(), anyList())).thenReturn(pool);
        when(businessRepository.findByIdIn(anyList())).thenReturn(List.of(business()));

        PromotionFeedService s = service();
        assertThat(s.homeFeed(tenantId, renterId).stream().map(PromoAdCardDTO::id).toList())
                .isEqualTo(s.homeFeed(tenantId, renterId).stream().map(PromoAdCardDTO::id).toList());
    }

    @Test
    void homeFeed_passesSentinelPropertyIdWhenRenterHasNoActiveLease() {
        when(leaseRepository.findActivePropertyIdsForRenterUser(tenantId, renterId))
                .thenReturn(List.of());
        when(adRepository.findEligible(eq(tenantId), any(), anyList(), anyList()))
                .thenReturn(List.of());

        service().homeFeed(tenantId, renterId);

        ArgumentCaptor<List<UUID>> captor = ArgumentCaptor.forClass(List.class);
        verify(adRepository).findEligible(eq(tenantId), any(), anyList(), captor.capture());
        // Empty IN lists are invalid JPQL on Postgres — a sentinel keeps the
        // untargeted arm of the query working for a renter between leases.
        assertThat(captor.getValue()).containsExactly(new UUID(0L, 0L));
    }

    @Test
    void homeFeed_requestsOnlyHomePlacement() {
        when(leaseRepository.findActivePropertyIdsForRenterUser(tenantId, renterId))
                .thenReturn(List.of());
        when(adRepository.findEligible(eq(tenantId), any(), anyList(), anyList()))
                .thenReturn(List.of());

        service().homeFeed(tenantId, renterId);

        ArgumentCaptor<List<PromoPlacement>> captor = ArgumentCaptor.forClass(List.class);
        verify(adRepository).findEligible(eq(tenantId), any(), captor.capture(), anyList());
        assertThat(captor.getValue()).containsExactly(PromoPlacement.HOME_AND_OFFERS);
    }

    @Test
    void offers_requestsBothPlacements() {
        when(leaseRepository.findActivePropertyIdsForRenterUser(tenantId, renterId))
                .thenReturn(List.of());
        when(adRepository.findEligible(eq(tenantId), any(), anyList(), anyList()))
                .thenReturn(List.of());

        service().offers(tenantId, renterId, null);

        ArgumentCaptor<List<PromoPlacement>> captor = ArgumentCaptor.forClass(List.class);
        verify(adRepository).findEligible(eq(tenantId), any(), captor.capture(), anyList());
        assertThat(captor.getValue())
                .containsExactlyInAnyOrder(PromoPlacement.HOME_AND_OFFERS, PromoPlacement.OFFERS_ONLY);
    }

    @Test
    void toCard_omitsPriorityPlacementAndTargeting() {
        // Compile-time guarantee via the record's component list — assert the
        // renter DTO simply has no such accessors.
        assertThat(PromoAdCardDTO.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("priority", "placement", "propertyIds");
    }

    @Test
    void toCard_populatesPhoneForCallAdsOnly() {
        when(leaseRepository.findActivePropertyIdsForRenterUser(tenantId, renterId))
                .thenReturn(List.of());
        PromoAd call = ad(1);
        call.setCtaType(PromoCtaType.CALL);
        PromoAd plain = ad(1);
        plain.setCtaType(PromoCtaType.NONE);
        when(adRepository.findEligible(eq(tenantId), any(), anyList(), anyList()))
                .thenReturn(List.of(call, plain));
        when(businessRepository.findByIdIn(anyList())).thenReturn(List.of(business()));

        List<PromoAdCardDTO> cards = service().offers(tenantId, renterId, null);

        assertThat(cards).filteredOn(c -> c.ctaType() == PromoCtaType.CALL)
                .allMatch(c -> "+971501234567".equals(c.ctaPhone()));
        assertThat(cards).filteredOn(c -> c.ctaType() == PromoCtaType.NONE)
                .allMatch(c -> c.ctaPhone() == null);
    }

    @Test
    void recordEvents_writesOneRowPerEventWithDubaiDay() {
        PromoAd a = ad(1);
        when(adRepository.findEligible(eq(tenantId), any(), anyList(), anyList()))
                .thenReturn(List.of(a));
        when(leaseRepository.findActivePropertyIdsForRenterUser(tenantId, renterId))
                .thenReturn(List.of());

        service().recordEvents(tenantId, renterId, new PromoEventBatchRequest(List.of(
                new PromoEventBatchRequest.Event(a.getId(), PromoEventType.IMPRESSION),
                new PromoEventBatchRequest.Event(a.getId(), PromoEventType.CLICK))));

        ArgumentCaptor<List<PromoAdEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(eventRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue()).allMatch(e ->
                e.getDay().equals(java.time.LocalDate.ofInstant(
                        Instant.now(), java.time.ZoneId.of("Asia/Dubai"))));
    }

    @Test
    void recordEvents_skipsImpressionsAlreadyRecordedToday() {
        PromoAd a = ad(1);
        when(adRepository.findEligible(eq(tenantId), any(), anyList(), anyList()))
                .thenReturn(List.of(a));
        when(leaseRepository.findActivePropertyIdsForRenterUser(tenantId, renterId))
                .thenReturn(List.of());
        when(eventRepository.findAdIdsWithImpressionOn(
                eq(tenantId), anyCollection(), eq(renterId), any()))
                .thenReturn(List.of(a.getId()));

        service().recordEvents(tenantId, renterId, new PromoEventBatchRequest(List.of(
                new PromoEventBatchRequest.Event(a.getId(), PromoEventType.IMPRESSION),
                new PromoEventBatchRequest.Event(a.getId(), PromoEventType.CLICK))));

        ArgumentCaptor<List<PromoAdEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(eventRepository).saveAll(captor.capture());
        // The click still lands; the repeat impression is dropped before the
        // insert, because the partial unique index would only fail at commit.
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getEventType()).isEqualTo(PromoEventType.CLICK);
    }

    @Test
    void recordEvents_looksUpTodaysImpressionsInOneQuery() {
        List<PromoAd> pool = List.of(ad(1), ad(1), ad(1), ad(1), ad(1), ad(1));
        when(adRepository.findEligible(eq(tenantId), any(), anyList(), anyList()))
                .thenReturn(pool);
        when(leaseRepository.findActivePropertyIdsForRenterUser(tenantId, renterId))
                .thenReturn(List.of());
        when(eventRepository.findAdIdsWithImpressionOn(
                eq(tenantId), anyCollection(), eq(renterId), any()))
                .thenReturn(List.of());

        service().recordEvents(tenantId, renterId, new PromoEventBatchRequest(
                pool.stream()
                        .map(a -> new PromoEventBatchRequest.Event(
                                a.getId(), PromoEventType.IMPRESSION))
                        .toList()));

        // Six impressions, one lookup — not one per ad. This is the renter
        // hot path; a per-ad check would run on every home-screen load.
        verify(eventRepository, times(1)).findAdIdsWithImpressionOn(
                any(), anyCollection(), any(), any());
    }

    @Test
    void recordEvents_dropsAdsTheRenterIsNotEligibleFor() {
        when(adRepository.findEligible(eq(tenantId), any(), anyList(), anyList()))
                .thenReturn(List.of());
        when(leaseRepository.findActivePropertyIdsForRenterUser(tenantId, renterId))
                .thenReturn(List.of());

        service().recordEvents(tenantId, renterId, new PromoEventBatchRequest(List.of(
                new PromoEventBatchRequest.Event(UUID.randomUUID(), PromoEventType.CLICK))));

        // A stale batch from a backgrounded app must be ignored silently, not 400.
        verify(eventRepository, never()).saveAll(anyList());
    }
}
