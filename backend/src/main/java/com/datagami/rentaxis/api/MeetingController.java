package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.*;
import com.datagami.rentaxis.core.service.MeetingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.api.CallerIdentity.callerId;
import static com.datagami.rentaxis.api.CallerIdentity.callerRole;

@RestController
@RequestMapping("/api/v1/meetings")
@RequiredArgsConstructor
public class MeetingController {

    private final MeetingService meetingService;

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<MeetingDTO> createMeeting(@Valid @RequestBody CreateMeetingDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(meetingService.createMeeting(dto, callerId()));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('PROPERTY_MANAGER', 'TENANT_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<Page<MeetingDTO>> listMeetings(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(meetingService.listMeetings(
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "slotStart"))));
    }

    @GetMapping("/my")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<MeetingDTO>> listMyMeetings(
            @RequestParam(defaultValue = "requester") String perspective,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(meetingService.listMyMeetings(callerId(), perspective,
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "slotStart"))));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<MeetingDTO> getMeeting(@PathVariable UUID id) {
        return ResponseEntity.ok(meetingService.getMeeting(id, callerId(), callerRole()));
    }

    @PutMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('PROPERTY_MANAGER', 'TENANT_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<MeetingDTO> approveMeeting(@PathVariable UUID id) {
        return ResponseEntity.ok(meetingService.approveMeeting(id, callerId()));
    }

    @PutMapping("/{id}/cancel")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<MeetingDTO> cancelMeeting(@PathVariable UUID id) {
        return ResponseEntity.ok(meetingService.cancelMeeting(id, callerId(), callerRole()));
    }

    @PutMapping("/{id}/complete")
    @PreAuthorize("hasAnyRole('PROPERTY_MANAGER', 'TENANT_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<MeetingDTO> completeMeeting(@PathVariable UUID id) {
        return ResponseEntity.ok(meetingService.completeMeeting(id, callerId()));
    }

    @PutMapping("/{id}/no-show")
    @PreAuthorize("hasAnyRole('PROPERTY_MANAGER', 'TENANT_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<MeetingDTO> noShowMeeting(@PathVariable UUID id) {
        return ResponseEntity.ok(meetingService.noShowMeeting(id, callerId()));
    }

    @GetMapping("/default-host")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<java.util.Map<String, String>> getDefaultHost() {
        java.util.UUID hostId = meetingService.getDefaultHostId();
        return ResponseEntity.ok(java.util.Map.of("userId", hostId.toString()));
    }

    @GetMapping("/slots")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<SlotDTO>> getAvailableSlots(
            @RequestParam UUID hostUserId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(meetingService.getAvailableSlots(hostUserId, date));
    }

    @GetMapping("/calendar")
    @PreAuthorize("hasAnyRole('PROPERTY_MANAGER', 'TENANT_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<Page<MeetingDTO>> getCalendarMeetings(
            @RequestParam Instant start,
            @RequestParam Instant end,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        return ResponseEntity.ok(meetingService.getCalendarMeetings(start, end,
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "slotStart"))));
    }
}
