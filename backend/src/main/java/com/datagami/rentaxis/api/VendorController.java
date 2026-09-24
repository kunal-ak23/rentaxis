package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.VendorService;
import com.datagami.rentaxis.domain.entity.Vendor;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

/**
 * Finance-ops audit S1 (P0): an ACCOUNTANT could not load vendors — this
 * class-level check used to be SA/TA only, so the vendor dropdown on the
 * Purchase/Service Invoice and Payment Voucher forms 403'd and was swallowed
 * into an empty list. ACCOUNTANT is admitted here to match
 * {@code AccountController} (chart-of-accounts CRUD, SA/TA/ACCOUNTANT) and
 * {@code LedgerController#vendorLedger} (already SA/TA/ACCOUNTANT): the
 * accountant is the role that enters PISR/BPV vouchers against vendors
 * (VoucherController is SA/TA/ACCOUNTANT), so they need to read and maintain
 * the vendor master, including creating a new supplier on the fly while
 * booking an invoice. PROPERTY_MANAGER is deliberately still excluded — see
 * {@code web/src/lib/rbac.ts} canAccessFinanceOps.
 */
@RestController
@RequestMapping("/api/v1/vendors")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
public class VendorController {

    private final VendorService service;

    public VendorController(VendorService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<Vendor>> getAllVendors() {
        return ResponseEntity.ok(service.getAllVendors());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Vendor> getVendorById(@PathVariable UUID id) {
        return ResponseEntity.ok(service.getVendorById(id));
    }

    @PostMapping
    public ResponseEntity<Vendor> createVendor(@Valid @RequestBody Vendor vendor) {
        return ResponseEntity.ok(service.createVendor(vendor));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Vendor> updateVendor(@PathVariable UUID id, @Valid @RequestBody Vendor vendor) {
        return ResponseEntity.ok(service.updateVendor(id, vendor));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteVendor(@PathVariable UUID id) {
        service.deleteVendor(id);
        return ResponseEntity.noContent().build();
    }
}
