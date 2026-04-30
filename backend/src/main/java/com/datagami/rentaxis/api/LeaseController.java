package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.*;
import com.datagami.rentaxis.api.dto.ExtendLeaseDTO;
import com.datagami.rentaxis.api.dto.SaveSettlementDTO;
import com.datagami.rentaxis.core.service.ContractGenerationService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.SettlementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/leases")
@RequiredArgsConstructor
public class LeaseController {

    private final LeaseService leaseService;
    private final ContractGenerationService contractGenerationService;
    private final SettlementService settlementService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<List<LeaseDTO>> getAllLeases() {
        return ResponseEntity.ok(leaseService.getAllLeases());
    }

    @GetMapping("/paged")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<Page<LeaseDTO>> getLeasesPaged(
            @RequestParam(required = false) String search,
            @PageableDefault(size = 25, sort = "startDate", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(leaseService.getAllLeasesPaged(search, pageable));
    }

    @GetMapping("/property/{propertyId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<List<LeaseDTO>> getLeasesByPropertyId(@PathVariable UUID propertyId) {
        return ResponseEntity.ok(leaseService.getLeasesByPropertyId(propertyId));
    }

    @GetMapping("/my-leases")
    @PreAuthorize("hasRole('RENTER')")
    public ResponseEntity<List<LeaseDTO>> getMyLeases(HttpServletRequest request) {
        String userIdStr = request.getHeader("X-User-Id");
        UUID userId = UUID.fromString(userIdStr);
        return ResponseEntity.ok(leaseService.getLeasesForRenterUser(userId));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<LeaseDTO> getLeaseById(@PathVariable UUID id) {
        return ResponseEntity.ok(leaseService.getLeaseById(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<LeaseDTO> createDraftLease(@Valid @RequestBody CreateLeaseDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(leaseService.createDraftLease(dto));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<LeaseDTO> updateDraftLease(@PathVariable UUID id, @Valid @RequestBody CreateLeaseDTO dto) {
        return ResponseEntity.ok(leaseService.updateDraftLease(id, dto));
    }

    @PutMapping("/{id}/activate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<LeaseDTO> activateLease(@PathVariable UUID id) {
        return ResponseEntity.ok(leaseService.activateLease(id));
    }

    @PostMapping("/{id}/terminate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<LeaseDTO> terminateLease(
            @PathVariable UUID id,
            @RequestBody(required = false) TerminateWithSettlementDTO dto,
            HttpServletRequest request) {
        String userIdStr = request.getHeader("X-User-Id");
        UUID settledBy = userIdStr != null ? UUID.fromString(userIdStr) : null;
        return ResponseEntity.ok(leaseService.terminateWithSettlement(id, dto, settledBy));
    }

    @GetMapping("/{id}/settlement/preview")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<SettlementPreviewDTO> getSettlementPreview(@PathVariable UUID id) {
        return ResponseEntity.ok(settlementService.getSettlementPreview(id));
    }

    @GetMapping("/{id}/settlement")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<SettlementResponseDTO> getSettlement(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(settlementService.buildSettlementResponse(id));
        } catch (com.datagami.rentaxis.api.exception.NotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/settlement/draft")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<SettlementResponseDTO> saveSettlementDraft(
            @PathVariable UUID id,
            @Valid @RequestBody SaveSettlementDTO dto,
            HttpServletRequest request) {
        String userIdStr = request.getHeader("X-User-Id");
        UUID userId = userIdStr != null ? UUID.fromString(userIdStr) : null;
        settlementService.saveDraft(id, dto, userId);
        return ResponseEntity.ok(settlementService.buildSettlementResponse(id));
    }

    @PostMapping("/{id}/settlement/finalize")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    @Transactional
    public ResponseEntity<LeaseDTO> finalizeSettlement(
            @PathVariable UUID id,
            HttpServletRequest request) {
        String userIdStr = request.getHeader("X-User-Id");
        UUID settledBy = userIdStr != null ? UUID.fromString(userIdStr) : null;
        settlementService.finalizeSettlement(id, settledBy);
        return ResponseEntity.ok(leaseService.terminateLease(id, null));
    }

    @PostMapping("/{id}/extend")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<LeaseDTO> extendLease(
            @PathVariable UUID id,
            @RequestBody ExtendLeaseDTO dto) {
        return ResponseEntity.ok(leaseService.extendLease(id, dto.getNewEndDate()));
    }

    @GetMapping("/{id}/events")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<List<LeaseEventDTO>> getLeaseEvents(@PathVariable UUID id) {
        return ResponseEntity.ok(leaseService.getLeaseEvents(id));
    }

    // --- Contract Generation Endpoints ---

    @PostMapping("/{id}/generate-contract")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<LeaseDocumentDTO> generateContract(@PathVariable UUID id) {
        return ResponseEntity.ok(contractGenerationService.generateContract(id));
    }

    @PostMapping("/{id}/generate-contract/preview")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<byte[]> previewContract(@PathVariable UUID id) {
        byte[] pdf = contractGenerationService.previewContract(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"lease-preview.pdf\"")
                .body(pdf);
    }

    @GetMapping("/{id}/documents")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'RENTER')")
    public ResponseEntity<List<LeaseDocumentDTO>> getDocuments(@PathVariable UUID id) {
        return ResponseEntity.ok(contractGenerationService.getDocuments(id));
    }

    @GetMapping("/documents/{docId}/download")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'RENTER')")
    public ResponseEntity<byte[]> downloadDocument(@PathVariable UUID docId) {
        byte[] content = contractGenerationService.getDocumentContent(docId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"contract-" + docId + ".pdf\"")
                .body(content);
    }

    // --- Renter Portal Endpoints ---

    @PutMapping("/{id}/accept")
    @PreAuthorize("hasRole('RENTER')")
    public ResponseEntity<LeaseDTO> acceptLease(@PathVariable UUID id, HttpServletRequest request) {
        String userIdStr = request.getHeader("X-User-Id");
        UUID userId = UUID.fromString(userIdStr);
        return ResponseEntity.ok(leaseService.acceptLease(id, userId));
    }

    @PutMapping("/{id}/reject")
    @PreAuthorize("hasRole('RENTER')")
    public ResponseEntity<LeaseDTO> rejectLease(@PathVariable UUID id, HttpServletRequest request) {
        String userIdStr = request.getHeader("X-User-Id");
        UUID userId = UUID.fromString(userIdStr);
        return ResponseEntity.ok(leaseService.rejectLease(id, userId));
    }
}
