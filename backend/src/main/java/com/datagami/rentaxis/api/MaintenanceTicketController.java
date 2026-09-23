package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.*;
import com.datagami.rentaxis.core.service.MaintenanceTicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tickets")
@RequiredArgsConstructor
public class MaintenanceTicketController {

    private final MaintenanceTicketService ticketService;

    @PostMapping
    @PreAuthorize("hasAnyRole('RENTER', 'PROPERTY_MANAGER', 'TENANT_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<MaintenanceTicketDTO> createTicket(
            @RequestBody CreateTicketDTO dto,
            @RequestHeader("X-User-Id") UUID userId) {
        return ResponseEntity.ok(ticketService.createTicket(dto, userId));
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<MaintenanceTicketDTO>> listTickets(
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-User-Role") String role,
            @RequestParam(required = false) UUID unitId,
            @RequestParam(required = false) UUID renterId) {
        return ResponseEntity.ok(ticketService.getTickets(userId, role, unitId, renterId));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<MaintenanceTicketDTO> getTicket(
            @PathVariable UUID id,
            @RequestHeader(value = "X-User-Id", required = false) UUID userId) {
        return ResponseEntity.ok(ticketService.getTicket(id, userId));
    }

    @PutMapping("/{id}/assign")
    @PreAuthorize("hasAnyRole('PROPERTY_MANAGER', 'TENANT_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<MaintenanceTicketDTO> assignTicket(
            @PathVariable UUID id,
            @RequestBody Map<String, UUID> body,
            jakarta.servlet.http.HttpServletRequest request) {
        UUID performedBy = UUID.fromString(request.getHeader("X-User-Id"));
        return ResponseEntity.ok(ticketService.assignTicket(id, body.get("assignTo"), performedBy));
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('PROPERTY_MANAGER', 'TENANT_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<MaintenanceTicketDTO> updateStatus(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body,
            jakarta.servlet.http.HttpServletRequest request) {
        UUID performedBy = UUID.fromString(request.getHeader("X-User-Id"));
        return ResponseEntity.ok(ticketService.updateStatus(id, body.get("status"), performedBy));
    }

    @PutMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('PROPERTY_MANAGER', 'TENANT_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<MaintenanceTicketDTO> closeWithOtp(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body,
            jakarta.servlet.http.HttpServletRequest request) {
        UUID performedBy = UUID.fromString(request.getHeader("X-User-Id"));
        return ResponseEntity.ok(ticketService.closeWithOtp(id, body.get("otp"), performedBy));
    }

    /** A fresh closure OTP, sent to the renter who holds it (PR #342 review I2). */
    @PostMapping("/{id}/closure-otp")
    @PreAuthorize("hasAnyRole('PROPERTY_MANAGER', 'TENANT_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<MaintenanceTicketDTO> reissueClosureOtp(
            @PathVariable UUID id,
            @RequestHeader("X-User-Id") UUID userId) {
        return ResponseEntity.ok(ticketService.reissueClosureOtp(id, userId));
    }

    @PostMapping("/{id}/replies")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<TicketReplyDTO> addReply(
            @PathVariable UUID id,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(ticketService.addReply(
                id, userId, body.get("message")));
    }

    @GetMapping("/{id}/replies")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<TicketReplyDTO>> listReplies(@PathVariable UUID id) {
        return ResponseEntity.ok(ticketService.getReplies(id));
    }

    @GetMapping("/{id}/history")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<java.util.List<TicketHistoryDTO>> getHistory(@PathVariable UUID id) {
        return ResponseEntity.ok(ticketService.getHistory(id));
    }

    @GetMapping("/{id}/attachments")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<java.util.List<TicketAttachmentDTO>> getAttachments(@PathVariable UUID id) {
        return ResponseEntity.ok(ticketService.getAttachments(id));
    }

    @PostMapping("/{id}/attachments")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<TicketAttachmentDTO> uploadAttachment(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file) throws IOException {
        return ResponseEntity.ok(ticketService.uploadAttachment(id, file));
    }

    @DeleteMapping("/attachments/{attachmentId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> deleteAttachment(@PathVariable UUID attachmentId) {
        ticketService.deleteAttachment(attachmentId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/attachments/{attachmentId}/download")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> downloadAttachment(@PathVariable UUID attachmentId) {
        byte[] content = ticketService.downloadAttachment(attachmentId);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment")
                .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                .body(content);
    }

    @PutMapping("/{id}/rate")
    @PreAuthorize("hasRole('RENTER')")
    public ResponseEntity<MaintenanceTicketDTO> rateTicket(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body) {
        int rating = (int) body.get("rating");
        String comment = (String) body.get("comment");
        return ResponseEntity.ok(ticketService.rateTicket(id, rating, comment));
    }

    @GetMapping("/reports")
    @PreAuthorize("hasAnyRole('PROPERTY_MANAGER', 'TENANT_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<TicketReportDTO> getReport(
            @RequestParam(required = false) UUID propertyId,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate startDate,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate endDate) {
        return ResponseEntity.ok(ticketService.getReport(propertyId, startDate, endDate));
    }

    @PutMapping("/{id}/estimate")
    @PreAuthorize("hasAnyRole('PROPERTY_MANAGER', 'TENANT_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<MaintenanceTicketDTO> setEstimate(
            @PathVariable UUID id,
            @RequestBody Map<String, Integer> body) {
        return ResponseEntity.ok(ticketService.setEstimate(id, body.get("hours")));
    }
}
