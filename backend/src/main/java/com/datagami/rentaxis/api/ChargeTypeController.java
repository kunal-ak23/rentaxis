package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.lease.ChargeTypeDTO;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The charge-type catalogue (spec §6.1).
 *
 * <p>A property manager builds leases out of these, so the class-level rule lets
 * them read the list; changing what a particular credits is an accounting decision
 * and the write methods narrow to SA/TA/ACCOUNTANT. Method-level
 * {@code @PreAuthorize} overrides the class-level one in Spring Security, so the
 * two annotations do not combine — the narrower rule is the whole rule on writes.</p>
 */
@RestController
@RequestMapping("/api/v1/finance/charge-types")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER')")
public class ChargeTypeController {

    private final ChargeTypeService service;

    public ChargeTypeController(ChargeTypeService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<ChargeTypeDTO>> list(
            @RequestParam(name = "activeOnly", defaultValue = "false") boolean activeOnly) {
        return ResponseEntity.ok(service.list(activeOnly));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
    public ResponseEntity<ChargeTypeDTO> create(@RequestBody ChargeTypeDTO body) {
        return ResponseEntity.ok(service.create(body));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
    public ResponseEntity<ChargeTypeDTO> update(@PathVariable UUID id, @RequestBody ChargeTypeDTO body) {
        return ResponseEntity.ok(service.update(id, body));
    }
}
