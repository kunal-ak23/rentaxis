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

import static com.datagami.rentaxis.api.CallerIdentity.callerId;
import static com.datagami.rentaxis.api.CallerIdentity.callerRole;

@RestController
@RequestMapping("/api/v1/tickets")
@RequiredArgsConstructor
public class MaintenanceTicketController {

    private final MaintenanceTicketService ticketService;

    // Caller identity comes from the verified principal (CallerIdentity), never
    // from the X-User-* headers. Every route here requires an authenticated caller.

    @PostMapping
    @PreAuthorize("hasAnyRole('RENTER', 'PROPERTY_MANAGER', 'TENANT_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<MaintenanceTicketDTO> createTicket(
            @RequestBody CreateTicketDTO dto) {
        return ResponseEntity.ok(ticketService.createTicket(dto, callerId()));
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<MaintenanceTicketDTO>> listTickets(
            @RequestParam(required = false) UUID unitId,
            @RequestParam(required = false) UUID renterId) {
        return ResponseEntity.ok(ticketService.getTickets(callerId(), callerRole(), unitId, renterId));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<MaintenanceTicketDTO> getTicket(
            @PathVariable UUID id) {
        return ResponseEntity.ok(ticketService.getTicket(id, callerId()));
    }

    /** Who the ticket can be assigned to: admins, and managers of its building. */
    @GetMapping("/{id}/assignees")
    @PreAuthorize("hasAnyRole('PROPERTY_MANAGER', 'TENANT_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<java.util.List<com.datagami.rentaxis.core.service.MaintenanceTicketService.AssigneeOption>>
            getAssignees(@PathVariable UUID id) {
        return ResponseEntity.ok(ticketService.eligibleAssignees(id));
    }

    @PutMapping("/{id}/assign")
    @PreAuthorize("hasAnyRole('PROPERTY_MANAGER', 'TENANT_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<MaintenanceTicketDTO> assignTicket(
            @PathVariable UUID id,
            @RequestBody Map<String, UUID> body) {
        UUID performedBy = callerId();
        return ResponseEntity.ok(ticketService.assignTicket(id, body.get("assignTo"), performedBy));
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('PROPERTY_MANAGER', 'TENANT_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<MaintenanceTicketDTO> updateStatus(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        UUID performedBy = callerId();
        return ResponseEntity.ok(ticketService.updateStatus(id, body.get("status"), performedBy));
    }

    @PutMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('PROPERTY_MANAGER', 'TENANT_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<MaintenanceTicketDTO> closeWithOtp(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        UUID performedBy = callerId();
        return ResponseEntity.ok(ticketService.closeWithOtp(id, body.get("otp"), performedBy));
    }

    /** A fresh closure OTP, sent to the renter who holds it (PR #342 review I2). */
    @PostMapping("/{id}/closure-otp")
    @PreAuthorize("hasAnyRole('PROPERTY_MANAGER', 'TENANT_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<MaintenanceTicketDTO> reissueClosureOtp(
            @PathVariable UUID id) {
        return ResponseEntity.ok(ticketService.reissueClosureOtp(id, callerId()));
    }

    @PostMapping("/{id}/replies")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<TicketReplyDTO> addReply(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(ticketService.addReply(
                id, callerId(), body.get("message")));
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

    /**
     * A ticket changed by someone else between this request's read and its write
     * (MaintenanceTicket's {@code @Version}, PR #342 review r3 I2). The writers
     * lock the row first, so this is the backstop; the answer is a 409 to reload,
     * not a 500.
     */
    @ExceptionHandler({org.springframework.dao.OptimisticLockingFailureException.class,
            jakarta.persistence.OptimisticLockException.class})
    public ResponseEntity<Map<String, Object>> handleConcurrentChange(RuntimeException ex) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT).body(Map.of(
                "error", true,
                "message", "This ticket was changed by someone else at the same time. Reload it and try again.",
                "status", 409));
    }

    @PutMapping("/{id}/estimate")
    @PreAuthorize("hasAnyRole('PROPERTY_MANAGER', 'TENANT_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<MaintenanceTicketDTO> setEstimate(
            @PathVariable UUID id,
            @RequestBody Map<String, Integer> body) {
        return ResponseEntity.ok(ticketService.setEstimate(id, body.get("hours")));
    }

    // ------------------------------------------------------------------ F14-49: bill and recharge

    @org.springframework.beans.factory.annotation.Autowired
    private com.datagami.rentaxis.core.service.TicketChargesService ticketCharges;

    @GetMapping("/{id}/charges")
    @PreAuthorize("hasAnyRole('PROPERTY_MANAGER', 'TENANT_ADMIN', 'SUPER_ADMIN', 'ACCOUNTANT')")
    public ResponseEntity<com.datagami.rentaxis.core.service.TicketChargesService.TicketCharges> charges(@PathVariable UUID id) {
        return ResponseEntity.ok(ticketCharges.get(id));
    }

    @PostMapping("/{id}/bills/{voucherId}")
    @PreAuthorize("hasAnyRole('PROPERTY_MANAGER', 'TENANT_ADMIN', 'SUPER_ADMIN', 'ACCOUNTANT')")
    public ResponseEntity<com.datagami.rentaxis.core.service.TicketChargesService.TicketCharges> linkBill(
            @PathVariable UUID id, @PathVariable UUID voucherId) {
        return ResponseEntity.ok(ticketCharges.link(id, voucherId));
    }

    @DeleteMapping("/{id}/bills/{voucherId}")
    @PreAuthorize("hasAnyRole('PROPERTY_MANAGER', 'TENANT_ADMIN', 'SUPER_ADMIN', 'ACCOUNTANT')")
    public ResponseEntity<com.datagami.rentaxis.core.service.TicketChargesService.TicketCharges> unlinkBill(
            @PathVariable UUID id, @PathVariable UUID voucherId) {
        return ResponseEntity.ok(ticketCharges.unlink(id, voucherId));
    }

    @PostMapping("/{id}/recharge")
    @PreAuthorize("hasAnyRole('PROPERTY_MANAGER', 'TENANT_ADMIN', 'SUPER_ADMIN', 'ACCOUNTANT')")
    public ResponseEntity<com.datagami.rentaxis.core.service.TicketChargesService.TicketCharges> recharge(
            @PathVariable UUID id,
            @org.springframework.web.bind.annotation.RequestBody(required = false)
            com.datagami.rentaxis.core.service.TicketChargesService.RechargeRequest r) {
        return ResponseEntity.ok(ticketCharges.recharge(id, r));
    }
}
