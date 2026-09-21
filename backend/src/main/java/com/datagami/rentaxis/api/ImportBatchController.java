package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.cutover.ImportBatchDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.cutover.ImportBatchService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The cut-over batches screen (spec §10.3, §11): what has been imported, and the
 * one button that takes a whole run back off the books.
 *
 * <p><b>Role gate.</b> One class-level {@code @PreAuthorize}: SUPER_ADMIN,
 * TENANT_ADMIN and ACCOUNTANT. Reversing a cut-over unposts every contract it
 * created, so it sits with the rest of finance, not with property management.</p>
 *
 * <p><b>The tenant guard.</b> {@code ApiSecurityFilter} authorises a SUPER_ADMIN
 * unconditionally but only populates {@code TenantContextHolder} when the caller
 * has picked an organisation, so a platform admin with none selected would reach a
 * service that assumes an ambient tenant and fail as an opaque 500 — or, worse for
 * a list endpoint, read every organisation's batches, because the tenant filter is
 * left off when the context is empty. Same guard and same wording as
 * {@code RecognitionController} and {@code VoucherController}.</p>
 *
 * <p>Thin by design: every rule lives in {@link ImportBatchService}.</p>
 */
@RestController
@RequestMapping("/api/v1/finance/import-batches")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT')")
public class ImportBatchController {

    /** Same wording as {@code RecognitionController.NO_TENANT} — one sentence, said consistently. */
    static final String NO_TENANT = "Select an organisation first";

    private final ImportBatchService batches;

    public record ReverseBatchDTO(@NotNull LocalDate date, String reason) {}

    @GetMapping
    public ResponseEntity<List<ImportBatchDTO>> list() {
        requireTenantSelected();
        return ResponseEntity.ok(batches.list().stream().map(ImportBatchDTO::of).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ImportBatchDTO> get(@PathVariable UUID id) {
        requireTenantSelected();
        return ResponseEntity.ok(ImportBatchDTO.of(batches.get(id)));
    }

    @PostMapping("/{id}/reverse")
    public ResponseEntity<ImportBatchDTO> reverse(@PathVariable UUID id,
                                                  @Valid @RequestBody ReverseBatchDTO body) {
        requireTenantSelected();
        return ResponseEntity.ok(ImportBatchDTO.of(batches.reverse(id, body.date(), body.reason())));
    }

    /** A batch belongs to an organisation; see the class Javadoc. */
    private void requireTenantSelected() {
        if (TenantContextHolder.getTenantId() == null) {
            throw new BusinessRuleViolationException(NO_TENANT);
        }
    }
}
