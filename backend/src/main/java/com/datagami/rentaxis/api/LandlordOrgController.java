package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.LandlordOrgService;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/tenants")
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
}
