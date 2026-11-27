package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.PromoDirectoryDtos.CategoryAdminDTO;
import com.datagami.rentaxis.api.dto.PromoDirectoryDtos.CategoryRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.PromoDirectoryService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Admin surface for the business directory's categories. Same roles as
 * {@link PromotionAdminController}; under {@code /api/v1/promotions}, so
 * {@code PromoterFenceFilter} keeps promoters out.
 */
@RestController
@RequestMapping("/api/v1/promotions/directory-categories")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
public class PromoDirectoryAdminController {

    private final PromoDirectoryService directory;

    public PromoDirectoryAdminController(PromoDirectoryService directory) {
        this.directory = directory;
    }

    /** A SUPER_ADMIN may call without a tenant header; refuse rather than write a null tenant. */
    private UUID requireTenant() {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            throw new BusinessRuleViolationException("X-Tenant-Id is required to manage promotions");
        }
        return tenantId;
    }

    /** Live categories, or with {@code ?archived=true} the archived ones; by sort order, then created. */
    @GetMapping
    public ResponseEntity<Page<CategoryAdminDTO>> list(@RequestParam(defaultValue = "false") boolean archived,
                                                       Pageable pageable) {
        return ResponseEntity.ok(directory.listCategories(requireTenant(), archived, pageable));
    }

    @PostMapping
    public ResponseEntity<CategoryAdminDTO> create(@Valid @RequestBody CategoryRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(directory.createCategory(requireTenant(), req));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CategoryAdminDTO> update(@PathVariable UUID id, @Valid @RequestBody CategoryRequest req) {
        return ResponseEntity.ok(directory.updateCategory(requireTenant(), id, req));
    }

    /** Archives: sets {@code archived_at} and {@code active=false}. */
    @DeleteMapping("/{id}")
    public ResponseEntity<CategoryAdminDTO> archive(@PathVariable UUID id) {
        return ResponseEntity.ok(directory.archiveCategory(requireTenant(), id));
    }

    /** An archived category back in the list, still inactive; 400 if it is not archived. */
    @PostMapping("/{id}/restore")
    public ResponseEntity<CategoryAdminDTO> restore(@PathVariable UUID id) {
        return ResponseEntity.ok(directory.restoreCategory(requireTenant(), id));
    }
}
