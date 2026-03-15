package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.LandlordOrgService;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/tenants")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class LandlordOrgController {

    private final LandlordOrgService service;

    public LandlordOrgController(LandlordOrgService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<LandlordOrg> createTenant(@RequestBody Map<String, String> payload) {
        String name = payload.get("name");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        LandlordOrg org = service.provisionTenant(name);
        return ResponseEntity.ok(org);
    }

    @GetMapping
    public ResponseEntity<List<LandlordOrg>> getTenants() {
        return ResponseEntity.ok(service.listAllTenants());
    }

    @PutMapping("/{id}")
    public ResponseEntity<LandlordOrg> updateTenant(
            @PathVariable java.util.UUID id,
            @RequestBody Map<String, String> payload) {
        java.util.Optional<LandlordOrg> orgOpt = service.findById(id);
        if (orgOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        LandlordOrg org = orgOpt.get();
        if (payload.containsKey("name") && !payload.get("name").isBlank()) {
            org.setName(payload.get("name"));
        }
        if (payload.containsKey("address")) {
            org.setAddress(payload.get("address"));
        }
        if (payload.containsKey("trn")) {
            org.setTrn(payload.get("trn"));
        }
        if (payload.containsKey("status")) {
            org.setStatus(payload.get("status"));
        }
        if (payload.containsKey("logoUrl")) {
            org.setLogoUrl(payload.get("logoUrl"));
        }
        if (payload.containsKey("ticketOtpRequired")) {
            org.setTicketOtpRequired(Boolean.parseBoolean(payload.get("ticketOtpRequired")));
        }
        return ResponseEntity.ok(service.save(org));
    }
}
