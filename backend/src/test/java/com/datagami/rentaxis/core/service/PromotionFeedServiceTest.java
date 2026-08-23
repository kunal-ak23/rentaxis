package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.PromoAdCardDTO;
import com.datagami.rentaxis.api.dto.PromoEventBatchRequest;
import com.datagami.rentaxis.core.service.PromotionSlate.Candidate;
import com.datagami.rentaxis.domain.entity.PromoAd;
import com.datagami.rentaxis.domain.entity.PromoAdEvent;
import com.datagami.rentaxis.domain.entity.PromoBusiness;
import com.datagami.rentaxis.domain.entity.enums.PromoCategory;
import com.datagami.rentaxis.domain.entity.enums.PromoCtaType;
import com.datagami.rentaxis.domain.entity.enums.PromoEventType;
import com.datagami.rentaxis.domain.entity.enums.PromoPlacement;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PromoAdEventRepository;
import com.datagami.rentaxis.domain.repository.PromoAdRepository;
import com.datagami.rentaxis.domain.repository.PromoBusinessRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionFeedServiceTest {

    @Mock PromoAdRepository adRepository;
    @Mock PromoBusinessRepository businessRepository;
    @Mock PromoAdEventRepository eventRepository;
    @Mock LeaseRepository leaseRepository;
    @Mock EntityManager entityManager;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID renterId = UUID.randomUUID();
    private final UUID businessId = UUID.randomUUID();

    private PromotionFeedService service() {
        return new PromotionFeedService(adRepository, businessRepository,
                eventRepository, leaseRepository, entityManager);
    }

    /**
     * Stubs the click-slot reservations the service now defers to, in the order
     * they will be issued: 1 for a slot granted, 0 for one refused.
     *
     * <p>These mocked tests can only pin down that the service asks and honours
     * the answer. That the answer is right under concurrency is the database's
     * job and is proven in {@code PromotionClickCapConcurrencyIT} — this whole
     * file passed just as happily on the check-then-act version that multiplied
     * the cap with parallelism.
     */
    private Query stubReservations(Integer first, Integer... rest) {
        Query reservation = mock(Query.class, RETURNS_SELF);
        when(reservation.executeUpdate()).thenReturn(first, rest);
        when(entityManager.createNativeQuery(anyString())).thenReturn(reservation);
        return reservation;
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
        when(businessRepository.findByTenantIdAndIdIn(eq(tenantId), anyCollection()))
                .thenReturn(List.of(business()));

        assertThat(service().homeFeed(tenantId, renterId)).hasSize(6);
    }

    @Test
    void homeFeed_isStableAcrossCalls() {
        when(leaseRepository.findActivePropertyIdsForRenterUser(tenantId, renterId))
                .thenReturn(List.of());
        List<PromoAd> pool = ads(40);
        when(adRepository.findEligible(eq(tenantId), any(), anyList(), anyList())).thenReturn(pool);
        when(businessRepository.findByTenantIdAndIdIn(eq(tenantId), anyCollection()))
                .thenReturn(List.of(business()));

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
        when(businessRepository.findByTenantIdAndIdIn(eq(tenantId), anyCollection()))
                .thenReturn(List.of(business()));

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
        stubReservations(1);

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
        when(eventRepository.countTodaysEventsByAd(
                eq(tenantId), anyCollection(), eq(renterId), any()))
                .thenReturn(List.<Object[]>of(
                        new Object[]{a.getId(), PromoEventType.IMPRESSION, 1L}));
        stubReservations(1);

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
    void recordEvents_looksUpTodaysCountsInOneQuery() {
        List<PromoAd> pool = List.of(ad(1), ad(1), ad(1), ad(1), ad(1), ad(1));
        when(adRepository.findEligible(eq(tenantId), any(), anyList(), anyList()))
                .thenReturn(pool);
        when(leaseRepository.findActivePropertyIdsForRenterUser(tenantId, renterId))
                .thenReturn(List.of());
        when(eventRepository.countTodaysEventsByAd(
                eq(tenantId), anyCollection(), eq(renterId), any()))
                .thenReturn(List.of());

        service().recordEvents(tenantId, renterId, new PromoEventBatchRequest(
                pool.stream()
                        .map(a -> new PromoEventBatchRequest.Event(
                                a.getId(), PromoEventType.IMPRESSION))
                        .toList()));

        // Six impressions, one lookup — not one per ad. This is the renter
        // hot path; a per-ad check would run on every home-screen load.
        verify(eventRepository, times(1)).countTodaysEventsByAd(
                any(), anyCollection(), any(), any());
    }

    @Test
    void recordEvents_writesOnlyTheClicksTheDatabaseGrantsASlotFor() {
        // Clicks have no unique index by design — a second tap is a real second
        // tap — so the cap lives in a counter row the database owns. The service
        // must write exactly the clicks it was granted a slot for: without a
        // ceiling of some kind a renter could set the tap count the client is
        // shown to justify an ad slot.
        PromoAd a = ad(1);
        when(adRepository.findEligible(eq(tenantId), any(), anyList(), anyList()))
                .thenReturn(List.of(a));
        when(leaseRepository.findActivePropertyIdsForRenterUser(tenantId, renterId))
                .thenReturn(List.of());
        // One slot left, three clicks offered.
        stubReservations(1, 0);

        service().recordEvents(tenantId, renterId, new PromoEventBatchRequest(List.of(
                new PromoEventBatchRequest.Event(a.getId(), PromoEventType.CLICK),
                new PromoEventBatchRequest.Event(a.getId(), PromoEventType.CLICK),
                new PromoEventBatchRequest.Event(a.getId(), PromoEventType.CLICK))));

        ArgumentCaptor<List<PromoAdEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(eventRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        // Two statements, not three: the counter only climbs inside this
        // transaction, so the first refusal settles the rest of the batch. A
        // 50-event batch aimed at one ad must not become 50 round trips.
        verify(entityManager, times(2)).createNativeQuery(anyString());
    }

    @Test
    void recordEvents_reservesEachClickSlotAgainstTheCallersTenantAndTheCap() {
        // The reservation is native SQL, so it runs outside Hibernate's
        // tenantFilter — every part of the isolation has to be written out here
        // rather than inherited, and the cap has to reach the statement that
        // enforces it.
        PromoAd a = ad(1);
        when(adRepository.findEligible(eq(tenantId), any(), anyList(), anyList()))
                .thenReturn(List.of(a));
        when(leaseRepository.findActivePropertyIdsForRenterUser(tenantId, renterId))
                .thenReturn(List.of());
        Query reservation = stubReservations(1);

        service().recordEvents(tenantId, renterId, new PromoEventBatchRequest(List.of(
                new PromoEventBatchRequest.Event(a.getId(), PromoEventType.CLICK))));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sql.capture());
        // A plain INSERT here would pass every other assertion in this file and
        // put the cap back where it started.
        assertThat(sql.getValue()).contains("ON CONFLICT");
        assertThat(sql.getValue()).contains("clicks < :cap");
        assertThat(sql.getValue()).contains("promo_ad_click_budgets.tenant_id = :tenantId");
        verify(reservation).setParameter("tenantId", tenantId);
        verify(reservation).setParameter("renterUserId", renterId);
        verify(reservation).setParameter("adId", a.getId());
        verify(reservation).setParameter("cap", PromotionFeedService.MAX_CLICKS_PER_AD_PER_DAY);
        // The same Dubai day the event rows are stamped with — a batch
        // straddling midnight must not spend one day's budget and file under the next.
        verify(reservation).setParameter("day", LocalDate.now(ZoneId.of("Asia/Dubai")));
    }

    @Test
    void recordEvents_usesOneInstantForDayAndOccurredAt() {
        // ck_promo_ad_event_day asserts day = occurred_at in Asia/Dubai. Reading
        // the clock twice with a DB round trip between lets a batch straddling
        // midnight violate it and 500 the whole request.
        PromoAd a = ad(1);
        when(adRepository.findEligible(eq(tenantId), any(), anyList(), anyList()))
                .thenReturn(List.of(a));
        when(leaseRepository.findActivePropertyIdsForRenterUser(tenantId, renterId))
                .thenReturn(List.of());
        when(eventRepository.countTodaysEventsByAd(
                eq(tenantId), anyCollection(), eq(renterId), any()))
                .thenReturn(List.of());
        stubReservations(1);

        service().recordEvents(tenantId, renterId, new PromoEventBatchRequest(List.of(
                new PromoEventBatchRequest.Event(a.getId(), PromoEventType.IMPRESSION),
                new PromoEventBatchRequest.Event(a.getId(), PromoEventType.CLICK))));

        ArgumentCaptor<List<PromoAdEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(eventRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).allSatisfy(row ->
                assertThat(row.getDay())
                        .isEqualTo(LocalDate.ofInstant(row.getOccurredAt(),
                                ZoneId.of("Asia/Dubai"))));
        // And every row in one batch shares the instant.
        assertThat(captor.getValue()).extracting(PromoAdEvent::getOccurredAt)
                .containsOnly(captor.getValue().get(0).getOccurredAt());
    }

    @Test
    void recordEvents_ignoresAnEmptyOrNullBatch() {
        service().recordEvents(tenantId, renterId, null);
        service().recordEvents(tenantId, renterId, new PromoEventBatchRequest(List.of()));

        verifyNoInteractions(eventRepository);
        verifyNoInteractions(entityManager);
    }

    @Test
    void homeFeed_preservesTheSlateOrder() {
        // The client renders in the order received, so the order the slate chose
        // must survive the map round-trip. Comparing the service to itself, as
        // the stability test does, would pass even if order were dropped.
        when(leaseRepository.findActivePropertyIdsForRenterUser(tenantId, renterId))
                .thenReturn(List.of());
        List<PromoAd> pool = ads(20);
        when(adRepository.findEligible(eq(tenantId), any(), anyList(), anyList()))
                .thenReturn(pool);
        when(businessRepository.findByTenantIdAndIdIn(eq(tenantId), anyCollection()))
                .thenReturn(List.of(business()));

        List<UUID> expected = PromotionSlate.pick(
                pool.stream().map(a -> new Candidate(a.getId(), a.getPriority())).toList(),
                renterId, LocalDate.now(ZoneId.of("Asia/Dubai")), 6);

        assertThat(service().homeFeed(tenantId, renterId).stream().map(PromoAdCardDTO::id))
                .containsExactlyElementsOf(expected);
    }

    @Test
    void offers_filtersByCategory() {
        when(leaseRepository.findActivePropertyIdsForRenterUser(tenantId, renterId))
                .thenReturn(List.of());
        PromoAd dining = ad(1);
        when(adRepository.findEligible(eq(tenantId), any(), anyList(), anyList()))
                .thenReturn(List.of(dining));
        PromoBusiness b = business();
        b.setCategory(PromoCategory.DINING);
        when(businessRepository.findByTenantIdAndIdIn(eq(tenantId), anyCollection()))
                .thenReturn(List.of(b));

        assertThat(service().offers(tenantId, renterId, PromoCategory.DINING)).hasSize(1);
        assertThat(service().offers(tenantId, renterId, PromoCategory.FITNESS)).isEmpty();
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
        // And it must not spend a slot on the way out: re-deriving the ad ids
        // from eligible() is what stops a renter burning someone else's budget.
        verifyNoInteractions(entityManager);
    }
}
