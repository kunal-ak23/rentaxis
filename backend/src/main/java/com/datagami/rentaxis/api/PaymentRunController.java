package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.payables.PaymentRunCandidatesDTO;
import com.datagami.rentaxis.api.dto.payables.PaymentRunDTO;
import com.datagami.rentaxis.api.dto.payables.PaymentRunInputDTO;
import com.datagami.rentaxis.api.dto.payables.PaymentRunPreviewDTO;
import com.datagami.rentaxis.api.dto.payables.PostRunRequestDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.payables.PaymentRunService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Supplier payment runs (finance-ops spec §2). Finance roles only: a property
 * manager has no run access, read or write.
 */
@RestController
@RequestMapping("/api/v1/finance/payment-runs")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT')")
public class PaymentRunController {

    private static final MediaType CSV = MediaType.parseMediaType("text/csv; charset=UTF-8");

    private final PaymentRunService runs;

    public PaymentRunController(PaymentRunService runs) {
        this.runs = runs;
    }

    @GetMapping
    public ResponseEntity<List<PaymentRunDTO>> list() {
        requireTenantSelected();
        return ResponseEntity.ok(runs.list());
    }

    @GetMapping("/candidates")
    public ResponseEntity<PaymentRunCandidatesDTO> candidates(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueBefore,
            @RequestParam(required = false) UUID vendorId,
            @RequestParam(required = false) UUID propertyId,
            @RequestParam(defaultValue = "true") boolean includePartPaid,
            @RequestParam(required = false) UUID excludeRunId) {
        requireTenantSelected();
        return ResponseEntity.ok(runs.candidates(dueBefore, vendorId, propertyId, includePartPaid, excludeRunId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PaymentRunDTO> get(@PathVariable UUID id) {
        requireTenantSelected();
        return ResponseEntity.ok(runs.get(id));
    }

    @PostMapping
    public ResponseEntity<PaymentRunDTO> create(@Valid @RequestBody PaymentRunInputDTO body) {
        requireTenantSelected();
        return ResponseEntity.status(HttpStatus.CREATED).body(runs.create(body));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PaymentRunDTO> update(@PathVariable UUID id, @Valid @RequestBody PaymentRunInputDTO body) {
        requireTenantSelected();
        return ResponseEntity.ok(runs.update(id, body));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        requireTenantSelected();
        runs.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<PaymentRunDTO> cancel(@PathVariable UUID id) {
        requireTenantSelected();
        return ResponseEntity.ok(runs.cancel(id));
    }

    @GetMapping("/{id}/preview")
    public ResponseEntity<PaymentRunPreviewDTO> preview(@PathVariable UUID id) {
        requireTenantSelected();
        return ResponseEntity.ok(runs.preview(id));
    }

    /**
     * All or nothing, and only what the preview showed: the body is the
     * preview's per-vendor figures; a run that would now post anything else is a
     * 409 naming each difference. A repeated submission of a run already posted
     * returns it unchanged.
     */
    @PostMapping("/{id}/post")
    public ResponseEntity<PaymentRunDTO> post(@PathVariable UUID id,
                                              @Valid @RequestBody(required = false) PostRunRequestDTO approved) {
        requireTenantSelected();
        return ResponseEntity.ok(runs.post(id, approved));
    }

    /**
     * The bank upload file. {@code bom=true} adds the UTF-8 byte-order mark (for
     * opening in Excel); the default leaves it out, as bank portals expect.
     * References longer than {@code referenceLimit} are cut, and the count is in
     * {@code X-Reference-Truncated} (the run page lists them).
     */
    @GetMapping("/{id}/bank-file.csv")
    public ResponseEntity<byte[]> bankFile(@PathVariable UUID id,
                                           @RequestParam(defaultValue = "false") boolean bom,
                                           @RequestParam(defaultValue = "35") int referenceLimit) {
        requireTenantSelected();
        PaymentRunDTO run = runs.get(id);
        PaymentRunService.BankFile file = runs.bankFile(id, bom, referenceLimit);
        return ResponseEntity.ok().contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"bank-file-"
                        + run.runNumber().replaceAll("[^A-Za-z0-9-]", "-") + ".csv\"")
                .header("X-Reference-Truncated", String.valueOf(file.warnings().size()))
                .body(file.body());
    }

    private static void requireTenantSelected() {
        if (TenantContextHolder.getTenantId() == null) {
            throw new BusinessRuleViolationException("Select an organisation first");
        }
    }
}
