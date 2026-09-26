package com.datagami.rentaxis.core.scheduled;

import com.datagami.rentaxis.core.service.LeaseExpirationJob;
import com.datagami.rentaxis.core.service.NotificationScheduler;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.RentCollectionSettingsRepository;
import com.datagami.rentaxis.domain.repository.UnitListingRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * S16-12: the midnight lease-expiry sweep, the 08:00 notification run and the 02:00
 * listing pass each have a kill switch bound from the real property, like
 * {@code rentaxis.recognition.job.enabled}. With the switch off the scheduled entry
 * point reads nothing at all — asserted on the first repository each job touches,
 * so a switch spelled one way in {@code application.yml} and another in the
 * {@code @Value} fails here.
 */
@SpringBootTest(properties = {
        "rentaxis.lease-expiry.job.enabled=false",
        "rentaxis.notifications.daily-job.enabled=false",
        "rentaxis.listing.upcoming-job.enabled=false"})
class NightlyJobSwitchesIT extends AbstractPostgresIT {

    @Autowired LeaseExpirationJob expiry;
    @Autowired NotificationScheduler notifications;
    @Autowired ListingUpcomingJob listings;

    @MockitoSpyBean LandlordOrgRepository orgs;
    @MockitoSpyBean RentCollectionSettingsRepository rentSettings;
    @MockitoSpyBean UnitListingRepository listingRepo;
    @MockitoSpyBean LeaseRepository leaseRepo;

    @org.springframework.beans.factory.annotation.Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        clearInvocations(orgs, rentSettings, listingRepo, leaseRepo);
    }

    /**
     * The ShedLock aspect takes 'lease-expiration' (lockAtLeastFor one minute) before
     * the switch is read; left in the shared database it would make LeaseExpirationJobIT's
     * scheduled entry point skip its sweep.
     */
    @org.junit.jupiter.api.AfterEach
    void releaseTheLock() {
        jdbc.update("delete from shedlock where name = 'lease-expiration'");
    }

    @Test
    void leaseExpirySweepDoesNothingWhileSwitchedOff() {
        expiry.evaluateExpiredLeases();
        verifyNoInteractions(orgs, leaseRepo);
    }

    @Test
    void dailyNotificationsDoNothingWhileSwitchedOff() {
        notifications.sendDailyNotifications();
        verifyNoInteractions(rentSettings, orgs, leaseRepo);
    }

    @Test
    void listingUpcomingPassDoesNothingWhileSwitchedOff() {
        listings.run();
        verifyNoInteractions(listingRepo, leaseRepo);
    }
}
