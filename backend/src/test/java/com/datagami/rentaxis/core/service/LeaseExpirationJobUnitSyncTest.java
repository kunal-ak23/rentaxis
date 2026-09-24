package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** R1 P2-5: the daily lease job hands each unit to the lease covering the day. */
class LeaseExpirationJobUnitSyncTest {

    @Test
    void theSweepSyncsUnitHoldersForTheDay() {
        LeaseService leases = mock(LeaseService.class);
        LocalDate day = LocalDate.of(2027, 10, 2);
        when(leases.findLeasesToExpire(day)).thenReturn(List.of());
        LeaseExpirationJob job = new LeaseExpirationJob(mock(LandlordOrgRepository.class), leases, Clock.systemUTC());

        job.runTenant(UUID.randomUUID(), day);

        verify(leases).syncUnitHolders(day);
    }
}
