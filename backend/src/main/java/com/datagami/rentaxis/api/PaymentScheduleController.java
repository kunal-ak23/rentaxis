package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.AgingReportDTO;
import com.datagami.rentaxis.api.dto.PaymentPreviewDTO;
import com.datagami.rentaxis.api.dto.PaymentScheduleDTO;
import com.datagami.rentaxis.api.dto.LeasePaymentStatsDTO;
import com.datagami.rentaxis.api.dto.PaymentSummaryDTO;
import com.datagami.rentaxis.api.dto.UpdatePaymentStatusDTO;
import com.datagami.rentaxis.core.service.PaymentScheduleService;
import com.datagami.rentaxis.core.service.RentReceiptService;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
            @PageableDefault(size = 25, sort = "dueDate", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(paymentScheduleService.getPaymentsForProperty(propertyId, status, renterName, pageable));
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

    @GetMapping("/preview")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<PaymentPreviewDTO> previewSchedule(
            @RequestParam UUID propertyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam BigDecimal monthlyRent) {
        return ResponseEntity.ok(paymentScheduleService.previewSchedule(propertyId, startDate, endDate, monthlyRent));
    }

}
