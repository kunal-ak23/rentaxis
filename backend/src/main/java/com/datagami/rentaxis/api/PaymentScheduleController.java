package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.AgingReportDTO;
import com.datagami.rentaxis.api.dto.MarkFailedRequestDTO;
import com.datagami.rentaxis.api.dto.MarkFailedResponseDTO;
import com.datagami.rentaxis.api.dto.PaymentScheduleDTO;
import com.datagami.rentaxis.api.dto.LeasePaymentStatsDTO;
import com.datagami.rentaxis.api.dto.PaymentSummaryDTO;
import com.datagami.rentaxis.api.dto.UpdatePaymentStatusDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.MarkFailedResult;
import com.datagami.rentaxis.core.service.PaymentScheduleService;
import com.datagami.rentaxis.core.service.RentReceiptService;
import com.datagami.rentaxis.domain.entity.PaymentPenalty;
import com.datagami.rentaxis.domain.entity.enums.InstallmentDistribution;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentScheduleController {

    private final PaymentScheduleService paymentScheduleService;
    private final RentReceiptService rentReceiptService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<Page<PaymentScheduleDTO>> getPayments(
            @RequestParam(required = false) UUID propertyId,
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(required = false) String renterName,
            @RequestParam(required = false, defaultValue = "false") boolean overdue,
            // "id" is a tiebreaker, not a meaningful ordering — dueDate alone is not
            // unique (many installments share a due date), so without it, rows with
            // the same dueDate have no defined relative order and can visibly swap
            // position between requests (e.g. right after marking one paid, which
            // triggers a full refetch) even though nothing about their sort key changed.
            @PageableDefault(size = 25, sort = {"dueDate", "id"}, direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(paymentScheduleService.getPaymentsForProperty(propertyId, status, renterName, overdue, pageable));
    }

    /**
     * Free-text payment lookup for the global command palette. Distinct from the
     * {@code renterName} filter on {@link #getPayments}, which matches renter
     * names only: this matches every field the palette actually displays
     * (cheque number, renter, unit, property) and evaluates them across the
     * whole tenant rather than whatever happens to fall in the newest page.
     */
    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<Page<PaymentScheduleDTO>> searchPayments(
            @RequestParam String q,
            @PageableDefault(size = 6, sort = {"dueDate", "id"}, direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(paymentScheduleService.searchPayments(q, pageable));
    }

    @GetMapping("/lease/{leaseId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<List<PaymentScheduleDTO>> getPaymentsForLease(@PathVariable UUID leaseId) {
        return ResponseEntity.ok(paymentScheduleService.getPaymentsForLease(leaseId));
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<PaymentSummaryDTO> getSummary(
            @RequestParam(required = false) UUID propertyId) {
        return ResponseEntity.ok(paymentScheduleService.getSummary(propertyId));
    }

    /**
     * Cheques in hand due (or overdue) for bank deposit today: status COLLECTED
     * with a post-dated chequeDate on/before today. Powers the dashboard
     * "cheques to deposit" widget.
     */
    @GetMapping("/to-deposit")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<Page<PaymentScheduleDTO>> getChequesToDeposit(
            @RequestParam(required = false) UUID propertyId,
            // "id" is a tiebreaker, not a meaningful ordering — chequeDate is not
            // unique (many cheques can share a deposit date), so without it rows
            // with the same chequeDate have no defined relative order and can swap
            // position between requests (e.g. right after depositing one, which
            // refetches the list) even though nothing about their sort key changed.
            @PageableDefault(size = 25, sort = {"chequeDate", "id"}, direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(paymentScheduleService.getChequesToDeposit(propertyId, pageable));
    }

    @PutMapping("/{id}/collect")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<PaymentScheduleDTO> collectPayment(
            @PathVariable UUID id,
            @RequestBody UpdatePaymentStatusDTO dto) {
        return ResponseEntity.ok(paymentScheduleService.collectPayment(id, dto));
    }

    @PutMapping("/{id}/deposit")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<PaymentScheduleDTO> depositPayment(
            @PathVariable UUID id,
            @RequestBody UpdatePaymentStatusDTO dto) {
        return ResponseEntity.ok(paymentScheduleService.depositPayment(id, dto));
    }

    @PutMapping("/{id}/clear")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<PaymentScheduleDTO> clearPayment(
            @PathVariable UUID id,
            @RequestBody UpdatePaymentStatusDTO dto) {
        return ResponseEntity.ok(paymentScheduleService.clearPayment(id, dto));
    }

    @PutMapping("/{id}/bounce")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<PaymentScheduleDTO> bouncePayment(
            @PathVariable UUID id,
            @RequestBody UpdatePaymentStatusDTO dto) {
        return ResponseEntity.ok(paymentScheduleService.bouncePayment(id, dto));
    }

    /**
     * Reason-aware replacement for {@link #bouncePayment}: also returns the
     * freshly-created penalty so the UI can show the fine immediately. The
     * legacy {@code PUT /{id}/bounce} endpoint is kept for backward
     * compatibility with existing clients (it now delegates to the same
     * service path with reason=BOUNCE).
     */
    @PostMapping("/{id}/mark-failed")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<MarkFailedResponseDTO> markFailed(
            @PathVariable UUID id,
            @Valid @RequestBody MarkFailedRequestDTO body) {
        MarkFailedResult result = paymentScheduleService.markFailed(
                id, body.failureReason(), body.notes(), body.effectiveDate());
        PaymentPenalty p = result.penalty();
        var penaltyDto = new MarkFailedResponseDTO.PenaltySummaryDTO(
                p.getId(), p.getPenaltyType(), p.getPenaltyAmount(),
                p.getFineGraceDays(), p.getFinePerDayRate(), p.getCreatedAt());
        return ResponseEntity.ok(new MarkFailedResponseDTO(result.schedule(), penaltyDto));
    }

    @PostMapping("/{id}/replace")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<PaymentScheduleDTO> replacePayment(
            @PathVariable UUID id,
            @RequestBody UpdatePaymentStatusDTO dto) {
        return ResponseEntity.ok(paymentScheduleService.replacePayment(id, dto));
    }

    @GetMapping("/aging-report")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<AgingReportDTO> getAgingReport(
            @RequestParam(required = false) UUID propertyId) {
        return ResponseEntity.ok(paymentScheduleService.getAgingReport(propertyId));
    }

    @PostMapping("/stats-by-leases")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public List<LeasePaymentStatsDTO> getStatsByLeases(@RequestBody List<UUID> leaseIds) {
        return paymentScheduleService.getPaymentStatsByLeaseIds(leaseIds);
    }

    @GetMapping("/{id}/receipt")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER', 'RENTER')")
    public ResponseEntity<byte[]> downloadReceipt(@PathVariable UUID id) {
        byte[] pdf = rentReceiptService.generateReceipt(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=receipt-" + id.toString().substring(0, 8) + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    /**
     * Previews an installment schedule for a prospective lease. Distribution-cap
     * violations ({@link BusinessRuleViolationException}) are surfaced as HTTP 422
     * with a flat {@code {"error": msg}} body so the wizard can render the message
     * inline without treating it as a generic 400 error.
     *
     * <p>All other handler methods in this controller (collect, deposit, clear,
     * replace, receipt, …) do <em>not</em> catch {@code BusinessRuleViolationException}
     * here; they continue to fall through to {@link GlobalExceptionHandler}, which
     * returns 400 as expected.
     */
    @GetMapping("/preview")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<?> previewSchedule(
            @RequestParam UUID propertyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam BigDecimal monthlyRent,
            @RequestParam(required = false) Integer paymentTerms,
            @RequestParam(required = false) BigDecimal depositAmount,
            @RequestParam(required = false) InstallmentDistribution strategy) {
        try {
            return ResponseEntity.ok(paymentScheduleService.previewSchedule(
                    propertyId, startDate, endDate, monthlyRent, paymentTerms, depositAmount, strategy));
        } catch (BusinessRuleViolationException ex) {
            return ResponseEntity.status(HttpStatusCode.valueOf(422))
                    .body(Map.of("error", ex.getMessage()));
        }
    }

}
