package com.datagami.rentaxis.core.service.rating;

import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RenterRatingJobTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);

    @Test
    void failingBatchDoesNotStopLaterBatches() {
        UUID tenantId = UUID.randomUUID();
        List<UUID> first = IntStream.range(0, RenterRatingJob.BATCH_SIZE).mapToObj(i -> UUID.randomUUID()).toList();
        List<UUID> second = List.of(UUID.randomUUID());
        RenterHistoryLoader loader = mock(RenterHistoryLoader.class);
        when(loader.ratedRenterIds(eq(tenantId), eq(null), eq(RenterRatingJob.BATCH_SIZE))).thenReturn(first);
        when(loader.ratedRenterIds(eq(tenantId), eq(first.get(first.size() - 1)), eq(RenterRatingJob.BATCH_SIZE)))
                .thenReturn(second);
        RenterRatingService service = mock(RenterRatingService.class);
        when(service.recomputeAndStore(eq(tenantId), eq(first), eq(TODAY))).thenThrow(new IllegalStateException("boom"));
        when(service.recomputeAndStore(eq(tenantId), eq(second), eq(TODAY))).thenReturn(1);
        RenterRatingJob job = new RenterRatingJob(mock(LandlordOrgRepository.class), mock(TenantFeatureService.class),
                loader, service, mock(PlatformTransactionManager.class), Runnable::run, Clock.systemUTC());

        assertThatCode(() -> job.runNowForTenant(tenantId, TODAY)).doesNotThrowAnyException();

        verify(service, times(2)).recomputeAndStore(eq(tenantId), anyCollection(), any(LocalDate.class));
        verify(service).recomputeAndStore(tenantId, second, TODAY);
    }
}
