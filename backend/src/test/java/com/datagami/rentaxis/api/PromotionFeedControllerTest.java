package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.PromoEventBatchRequest;
import com.datagami.rentaxis.core.service.PromotionFeedService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.enums.PromoCategory;
import com.datagami.rentaxis.domain.entity.enums.PromoEventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionFeedControllerTest {

    @Mock PromotionFeedService feedService;
    @InjectMocks PromotionFeedController controller;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID renterId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(tenantId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(renterId.toString(), null,
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_RENTER"))));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void feed_identifiesTheRenterFromTheSecurityContext() {
        when(feedService.homeFeed(tenantId, renterId)).thenReturn(List.of());

        controller.feed();

        // Never from an X-User-Id header — a client-supplied header is not an identity.
        verify(feedService).homeFeed(tenantId, renterId);
    }

    @Test
    void offers_passesTheCategoryFilterThrough() {
        when(feedService.offers(tenantId, renterId, PromoCategory.DINING)).thenReturn(List.of());

        controller.offers(PromoCategory.DINING);

        verify(feedService).offers(tenantId, renterId, PromoCategory.DINING);
    }

    @Test
    void events_returns202AndDelegates() {
        PromoEventBatchRequest batch = new PromoEventBatchRequest(List.of(
                new PromoEventBatchRequest.Event(UUID.randomUUID(), PromoEventType.IMPRESSION)));

        assertThat(controller.events(batch).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(feedService).recordEvents(tenantId, renterId, batch);
    }
}
