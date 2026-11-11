package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.rentalindex.RentalIndexImportResult;
import com.datagami.rentaxis.api.dto.rentalindex.RentalIndexRangeDTO;
import com.datagami.rentaxis.api.dto.rentalindex.RentalIndexRangeInput;
import com.datagami.rentaxis.core.service.lease.RentalIndexService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * The DLD rental index ranges on a property (the property page's "DLD rental index"
 * tab). Reads for everyone who reads the property; writes for the roles that manage
 * it. Nothing here talks to dubailand.gov.ae.
 */
@RestController
@RequestMapping("/api/v1/properties/{propertyId}/rental-index")
public class RentalIndexController {

    private final RentalIndexService service;

    public RentalIndexController(RentalIndexService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER', 'ACCOUNTANT')")
    public ResponseEntity<List<RentalIndexRangeDTO>> list(@PathVariable UUID propertyId) {
        return ResponseEntity.ok(service.list(propertyId));
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<RentalIndexRangeDTO> upsert(@PathVariable UUID propertyId,
                                                      @RequestBody RentalIndexRangeInput body) {
        return ResponseEntity.ok(service.upsert(propertyId, body, CallerIdentity.callerId()));
    }

    @PostMapping("/import")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<RentalIndexImportResult> importRows(@PathVariable UUID propertyId,
                                                              @RequestBody List<RentalIndexRangeInput> rows) {
        RentalIndexImportResult result = service.importRows(propertyId, rows, CallerIdentity.callerId());
        return result.errors().isEmpty() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<Void> delete(@PathVariable UUID propertyId, @PathVariable UUID id) {
        service.delete(propertyId, id);
        return ResponseEntity.noContent().build();
    }
}
