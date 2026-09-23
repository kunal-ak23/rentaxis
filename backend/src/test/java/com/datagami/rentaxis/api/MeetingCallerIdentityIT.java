package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Meeting;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.MeetingPurpose;
import com.datagami.rentaxis.domain.entity.enums.MeetingType;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.MeetingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The meeting API takes the caller from the verified principal (PR #342). It used
 * to read X-User-Id and X-User-Role, which on the bearer path are the caller's to
 * choose: a renter could read or cancel another renter's meeting by naming them,
 * or by claiming a staff role, and book meetings in someone else's name.
 */
class MeetingCallerIdentityIT extends AbstractCallerIdentityIT {

    @Autowired MeetingRepository meetingRepo;

    private User renterA;
    private User victim;
    private User host;
    private UUID victimsMeeting;

    @BeforeEach
    void setUp() {
        UUID tenantId = newTenant("MCI");
        TenantContextHolder.setTenantId(tenantId);
        renterA = user(tenantId, UserRole.RENTER, "x");
        victim = user(tenantId, UserRole.RENTER, "x");
        host = user(tenantId, UserRole.TENANT_ADMIN, "x");

        Meeting m = new Meeting();
        m.setType(MeetingType.OFFICE_VISIT);
        m.setPurpose(MeetingPurpose.OTHER);
        m.setTitle("Victim's meeting");
        Instant start = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        m.setSlotStart(start);
        m.setSlotEnd(start.plus(30, ChronoUnit.MINUTES));
        m.setHostUserId(host.getId());
        m.setRequesterUserId(victim.getId());
        victimsMeeting = meetingRepo.save(m).getId();
        TenantContextHolder.clear();
    }

    private String statusOf(UUID meetingId) {
        return jdbc.queryForObject("SELECT status FROM meetings WHERE id = ?", String.class, meetingId);
    }

    @Test
    void theVictimCanReadTheirOwnMeeting() {
        // Control: the 404s below are about who is asking, not a missing row.
        assertThat(status(asSelf(HttpMethod.GET, "/api/v1/meetings/" + victimsMeeting, victim))).isEqualTo(200);
    }

    @Test
    void aForgedIdentityCannotReadAnotherRentersMeeting() {
        assertThat(status(forged(HttpMethod.GET, "/api/v1/meetings/" + victimsMeeting, renterA, victim)))
                .isEqualTo(404);
    }

    @Test
    void aForgedIdentityCannotCancelAnotherRentersMeeting() {
        assertThat(status(forged(HttpMethod.PUT, "/api/v1/meetings/" + victimsMeeting + "/cancel", renterA, victim)))
                .isEqualTo(404);
        assertThat(statusOf(victimsMeeting)).isEqualTo("REQUESTED");
    }

    @Test
    void myMeetingsAreTheCallersNotTheHeadersUsers() {
        @SuppressWarnings("rawtypes")
        Map page = forged(HttpMethod.GET, "/api/v1/meetings/my", renterA, victim)
                .retrieve().body(Map.class);
        assertThat((java.util.List<?>) page.get("content")).isEmpty();
    }

    @Test
    void aMeetingBookedWithAForgedIdentityIsBookedAsTheCaller() {
        Instant slot = LocalDate.now(ZoneId.of("Asia/Dubai")).plusDays(5)
                .atTime(LocalTime.of(11, 0)).atZone(ZoneId.of("Asia/Dubai")).toInstant();
        @SuppressWarnings("rawtypes")
        Map created = forged(HttpMethod.POST, "/api/v1/meetings", renterA, victim)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("type", "OFFICE_VISIT", "purpose", "OTHER",
                        "slotStart", slot.toString(), "hostUserId", host.getId().toString()))
                .retrieve().body(Map.class);

        UUID id = UUID.fromString((String) created.get("id"));
        assertThat(jdbc.queryForObject("SELECT requester_user_id FROM meetings WHERE id = ?", UUID.class, id))
                .isEqualTo(renterA.getId());
    }
}
