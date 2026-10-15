package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Meeting;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.MeetingPurpose;
import com.datagami.rentaxis.domain.entity.enums.MeetingStatus;
import com.datagami.rentaxis.domain.entity.enums.MeetingType;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.MeetingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tutorial 23: cancelling a meeting said nothing about who cancelled it or why. A
 * cancellation now keeps who, when and an optional reason, shows them on the
 * meeting, and puts the reason in both parties' notification.
 */
class MeetingCancellationIT extends AbstractCallerIdentityIT {

    @Autowired MeetingRepository meetingRepo;

    private User renter;
    private User host;
    private UUID meetingId;

    @BeforeEach
    void setUp() {
        UUID tenantId = newTenant("MCX");
        TenantContextHolder.setTenantId(tenantId);
        renter = user(tenantId, UserRole.RENTER, "x");
        host = user(tenantId, UserRole.TENANT_ADMIN, "x");
        Meeting m = new Meeting();
        m.setType(MeetingType.OFFICE_VISIT);
        m.setPurpose(MeetingPurpose.OTHER);
        m.setTitle("Key handover");
        m.setStatus(MeetingStatus.APPROVED);
        Instant start = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        m.setSlotStart(start);
        m.setSlotEnd(start.plus(30, ChronoUnit.MINUTES));
        m.setHostUserId(host.getId());
        m.setRequesterUserId(renter.getId());
        meetingId = meetingRepo.save(m).getId();
        TenantContextHolder.clear();
    }

    @SuppressWarnings("rawtypes")
    private Map cancel(User caller, Object body) {
        var spec = asSelf(HttpMethod.PUT, "/api/v1/meetings/" + meetingId + "/cancel", caller);
        if (body != null) spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        return spec.retrieve().body(Map.class);
    }

    @Test
    void theReasonIsKeptShownAndSentToBothParties() {
        @SuppressWarnings("rawtypes")
        Map dto = cancel(host, Map.of("reason", "  Office closed for Eid  "));

        assertThat(dto.get("status")).isEqualTo("CANCELLED");
        assertThat(dto.get("cancellationReason")).isEqualTo("Office closed for Eid");
        assertThat(dto.get("cancelledByUserId")).isEqualTo(host.getId().toString());
        assertThat(dto.get("cancelledByName")).isEqualTo(host.getName());
        assertThat(dto.get("cancelledAt")).isNotNull();

        // Read back, as the meeting page does.
        @SuppressWarnings("rawtypes")
        Map read = asSelf(HttpMethod.GET, "/api/v1/meetings/" + meetingId, renter).retrieve().body(Map.class);
        assertThat(read.get("cancellationReason")).isEqualTo("Office closed for Eid");

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT user_id, message, message_key, params->>'note' AS note FROM notifications "
                        + "WHERE reference_id = ? AND type = 'MEETING_CANCELLED'", meetingId);
        assertThat(rows).extracting(r -> r.get("user_id"))
                .containsExactlyInAnyOrder(host.getId(), renter.getId());
        assertThat(rows).allSatisfy(r -> {
            assertThat((String) r.get("message")).endsWith("Reason: Office closed for Eid");
            assertThat(r.get("message_key")).isEqualTo("MEETING_CANCELLED_REASON");
            assertThat(r.get("note")).isEqualTo("Office closed for Eid");
        });
    }

    @Test
    void aCancelWithNoBodyStillWorksForTheMobileApps() {
        @SuppressWarnings("rawtypes")
        Map dto = cancel(renter, null);
        assertThat(dto.get("status")).isEqualTo("CANCELLED");
        assertThat(dto.get("cancellationReason")).isNull();
        assertThat(dto.get("cancelledByUserId")).isEqualTo(renter.getId().toString());
        assertThat(jdbc.queryForList("SELECT message_key FROM notifications WHERE reference_id = ?",
                String.class, meetingId)).containsOnly("MEETING_CANCELLED");
    }

    @Test
    void anOverLongReasonIsRefusedAndNothingChanges() {
        int code = status(asSelf(HttpMethod.PUT, "/api/v1/meetings/" + meetingId + "/cancel", host)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", "x".repeat(501))));
        assertThat(code).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT status FROM meetings WHERE id = ?", String.class, meetingId))
                .isEqualTo("APPROVED");
    }

    /** Tutorial 23: the list filtered in the browser, so its total ignored the filter. */
    @Test
    @SuppressWarnings("rawtypes")
    void theListFiltersInTheQuerySoTheTotalMatches() {
        TenantContextHolder.setTenantId(host.getTenantId());
        Meeting other = new Meeting();
        other.setType(MeetingType.PROPERTY_VISIT);
        other.setPurpose(MeetingPurpose.OTHER);
        other.setStatus(MeetingStatus.REQUESTED);
        Instant start = Instant.now().plus(4, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        other.setSlotStart(start);
        other.setSlotEnd(start.plus(30, ChronoUnit.MINUTES));
        other.setHostUserId(host.getId());
        other.setRequesterUserId(renter.getId());
        meetingRepo.save(other);
        TenantContextHolder.clear();

        Map all = asSelf(HttpMethod.GET, "/api/v1/meetings", host).retrieve().body(Map.class);
        assertThat(((Number) all.get("totalElements")).intValue()).isEqualTo(2);
        Map approved = asSelf(HttpMethod.GET, "/api/v1/meetings?status=APPROVED", host).retrieve().body(Map.class);
        assertThat(((Number) approved.get("totalElements")).intValue()).isEqualTo(1);
        Map visits = asSelf(HttpMethod.GET, "/api/v1/meetings?type=PROPERTY_VISIT", host).retrieve().body(Map.class);
        assertThat(((Number) visits.get("totalElements")).intValue()).isEqualTo(1);
        Map mine = asSelf(HttpMethod.GET, "/api/v1/meetings/my?status=REQUESTED", renter).retrieve().body(Map.class);
        assertThat(((Number) mine.get("totalElements")).intValue()).isEqualTo(1);
    }
}
