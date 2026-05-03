package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.PenaltyDTO;
import com.datagami.rentaxis.api.dto.PenaltyPaymentDTO;
import com.datagami.rentaxis.api.dto.RecordPenaltyPaymentRequestDTO;
import com.datagami.rentaxis.api.dto.WaivePenaltyRequestDTO;
import com.datagami.rentaxis.api.exception.AccessDeniedException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.PenaltyPaymentService;
import com.datagami.rentaxis.core.service.PenaltyService;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.PaymentPenalty;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.PenaltyPayment;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PaymentPenaltyRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import com.datagami.rentaxis.domain.repository.PenaltyPaymentRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/penalties")
@RequiredArgsConstructor
public class PenaltyController {

    private final PaymentPenaltyRepository penaltyRepo;
    private final PenaltyPaymentRepository paymentRepo;
    private final PaymentScheduleRepository scheduleRepo;
    private final PenaltyService penaltyService;
    private final PenaltyPaymentService penaltyPaymentService;
    private final RenterRepository renterRepository;
    private final LeaseRepository leaseRepository;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER', 'RENTER')")
    public ResponseEntity<Page<PenaltyDTO>> list(
            @RequestParam(required = false) UUID leaseId,
            @RequestParam(required = false, defaultValue = "all") String status,
            @RequestHeader("X-User-Id") UUID currentUser,
            Pageable pageable) {

        boolean openOnly = "open".equalsIgnoreCase(status);
        boolean clearedOnly = "cleared".equalsIgnoreCase(status);

        boolean isRenter = SecurityContextHolder.getContext().getAuthentication()
                .getAuthorities().stream()
                .anyMatch(a -> "ROLE_RENTER".equals(a.getAuthority()));

        Page<PaymentPenalty> page;
        if (isRenter) {
            Renter renter = renterRepository.findByUserId(currentUser)
                    .orElseThrow(() -> new NotFoundException("Renter not found for user " + currentUser));
            List<UUID> allowedLeaseIds = leaseRepository.findByRenterId(renter.getId())
                    .stream().map(Lease::getId).toList();
            if (leaseId != null && !allowedLeaseIds.contains(leaseId)) {
                throw new AccessDeniedException("Lease does not belong to this renter");
            }
            page = penaltyRepo.findFilteredForRenter(allowedLeaseIds, leaseId, openOnly, clearedOnly, pageable);
        } else {
            page = penaltyRepo.findFiltered(leaseId, openOnly, clearedOnly, pageable);
        }

        return ResponseEntity.ok(page.map(this::toDto));
    }

    @PostMapping("/{id}/payments")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<PenaltyPaymentDTO> recordPayment(
            @PathVariable UUID id,
            @Valid @RequestBody RecordPenaltyPaymentRequestDTO body,
            @RequestHeader("X-User-Id") UUID currentUser) {
        var input = new PenaltyPaymentService.RecordReceiptInput(
                body.amount(), body.paymentMethod(), body.paymentReference(), body.receivedAt(), body.notes());
        PenaltyPayment row = penaltyPaymentService.recordReceipt(id, input, currentUser);
        return ResponseEntity.ok(toDto(row));
    }

    @PostMapping("/{id}/waive")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<PenaltyDTO> waive(
            @PathVariable UUID id,
            @Valid @RequestBody WaivePenaltyRequestDTO body,
            @RequestHeader("X-User-Id") UUID currentUser) {
        PaymentPenalty waived = penaltyService.waivePenalty(id, body.reason(), currentUser);
        return ResponseEntity.ok(toDto(waived));
    }

    // -------------------------------------------------------------------------
    // Mappers
    // -------------------------------------------------------------------------

    private PenaltyDTO toDto(PaymentPenalty p) {
        ChequeFailureReason failureReason = null;
        if (p.getPaymentScheduleId() != null) {
            failureReason = scheduleRepo.findById(p.getPaymentScheduleId())
                    .map(PaymentSchedule::getFailureReason)
                    .orElse(null);
        }

        List<PenaltyPayment> receipts = paymentRepo
                .findByPaymentPenaltyIdOrderByReceivedAtAscCreatedAtAsc(p.getId());

        List<PenaltyPaymentDTO> paymentDtos = receipts.stream()
                .map(this::toDto)
                .toList();

        return new PenaltyDTO(
                p.getId(),
                p.getPaymentScheduleId(),
                p.getLeaseId(),
                p.getPenaltyType(),
                failureReason,
                p.getPenaltyAmount(),
                p.getDaysOverdue(),
                p.getFineGraceDays(),
                p.getFinePerDayRate(),
                penaltyPaymentService.currentTotal(p),
                penaltyPaymentService.outstanding(p),
                p.isWaived(),
                p.getWaivedReason(),
                p.getCreatedAt(),
                p.getClearedAt(),
                paymentDtos
        );
    }

    private PenaltyPaymentDTO toDto(PenaltyPayment pp) {
        return new PenaltyPaymentDTO(
                pp.getId(),
                pp.getAmount(),
                pp.getPaymentMethod(),
                pp.getPaymentReference(),
                pp.getReceivedAt(),
                pp.getReceivedBy(),
                pp.getNotes(),
                pp.getCreatedAt()
        );
    }
}
