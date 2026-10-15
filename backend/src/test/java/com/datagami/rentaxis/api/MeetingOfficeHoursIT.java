package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tutorial 23: office-visit slots ran to 9 PM whatever the office keeps. They now
 * follow the organisation's office hours (09:00–18:00 until a Company Admin sets
 * them); property visits keep 09:00–21:00.
 */
class MeetingOfficeHoursIT extends AbstractCallerIdentityIT {

    private static final ZoneId DUBAI = ZoneId.of("Asia/Dubai");
    private User admin;
    private User renter;
    private LocalDate day;

    @BeforeEach
    void setUp() {
        UUID tenantId = newTenant("MOH");
        TenantContextHolder.setTenantId(tenantId);
        admin = user(tenantId, UserRole.TENANT_ADMIN, "x");
        renter = user(tenantId, UserRole.RENTER, "x");
        TenantContextHolder.clear();
        day = LocalDate.now(DUBAI).plusDays(7);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<LocalTime> slotStarts(String type) {
        String uri = "/api/v1/meetings/slots?hostUserId=" + admin.getId() + "&date=" + day
                + (type == null ? "" : "&type=" + type);
        List<Map> slots = asSelf(HttpMethod.GET, uri, renter).retrieve().body(List.class);
        return slots.stream()
                .map(s -> java.time.Instant.parse((String) s.get("start")).atZone(DUBAI).toLocalTime())
                .toList();
    }

    private int book(String type, LocalTime at) {
        return status(asSelf(HttpMethod.POST, "/api/v1/meetings", renter)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("type", type, "purpose", "OTHER", "hostUserId", admin.getId().toString(),
                        "slotStart", day.atTime(at).atZone(DUBAI).toInstant().toString())));
    }

    @Test
    void officeVisitsDefaultToNineToSix() {
        List<LocalTime> starts = slotStarts("OFFICE_VISIT");
        assertThat(starts.getFirst()).isEqualTo(LocalTime.of(9, 0));
        assertThat(starts.getLast()).isEqualTo(LocalTime.of(17, 30));
        // No type given is the default meeting, an office visit.
        assertThat(slotStarts(null)).isEqualTo(starts);
        assertThat(book("OFFICE_VISIT", LocalTime.of(19, 0))).isEqualTo(400);
        assertThat(book("OFFICE_VISIT", LocalTime.of(17, 30))).isEqualTo(201);
    }

    @Test
    void propertyVisitsKeepNineToNine() {
        List<LocalTime> starts = slotStarts("PROPERTY_VISIT");
        assertThat(starts.getLast()).isEqualTo(LocalTime.of(20, 30));
        assertThat(book("PROPERTY_VISIT", LocalTime.of(19, 0))).isEqualTo(201);
        // Late evening is outside 09:00–21:00 — including 23:30, whose end wraps to 00:00.
        assertThat(book("PROPERTY_VISIT", LocalTime.of(21, 0))).isEqualTo(400);
        assertThat(book("PROPERTY_VISIT", LocalTime.of(23, 30))).isEqualTo(400);
    }

    @Test
    void aCompanyAdminSetsTheOfficeHours() {
        @SuppressWarnings("rawtypes")
        Map saved = asSelf(HttpMethod.PUT, "/api/v1/settings/org/office-hours", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("start", "08:00", "end", "16:30"))
                .retrieve().body(Map.class);
        assertThat((String) saved.get("start")).startsWith("08:00");

        List<LocalTime> starts = slotStarts("OFFICE_VISIT");
        assertThat(starts.getFirst()).isEqualTo(LocalTime.of(8, 0));
        assertThat(starts.getLast()).isEqualTo(LocalTime.of(16, 0));

        // Refused: closing before opening, off the half hour; and a renter may not set them.
        assertThat(status(asSelf(HttpMethod.PUT, "/api/v1/settings/org/office-hours", admin)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("start", "18:00", "end", "09:00"))))
                .isEqualTo(400);
        assertThat(status(asSelf(HttpMethod.PUT, "/api/v1/settings/org/office-hours", admin)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("start", "09:15", "end", "17:00"))))
                .isEqualTo(400);
        assertThat(status(asSelf(HttpMethod.PUT, "/api/v1/settings/org/office-hours", renter)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("start", "06:00", "end", "23:00"))))
                .isEqualTo(403);
    }
}
