package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.PromoterApiKeyDtos.RevokeKeyRequest;
import com.datagami.rentaxis.core.service.PromoterApiKeyService;
import com.datagami.rentaxis.core.service.PromoterApiKeyService.AdminKeyRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Super-admin view of every organisation's promoter API keys, and the kill switch.
 * The service does not check the role itself: this class's SUPER_ADMIN gate is it.
 * Logging of the revoke lives in the service.
 */
@RestController
@RequestMapping("/api/admin/promoter-api-keys")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PromoterApiKeyAdminController {

    private final PromoterApiKeyService keys;

    public PromoterApiKeyAdminController(PromoterApiKeyService keys) {
        this.keys = keys;
    }

    @GetMapping
    public ResponseEntity<Page<AdminKeyRow>> list(@RequestParam(required = false) String q,
                                                  @RequestParam(required = false) String status,
                                                  @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(keys.listAll(q, status, pageable));
    }

    @PostMapping("/{id}/revoke")
    public ResponseEntity<AdminKeyRow> revoke(@PathVariable UUID id,
                                              @RequestBody(required = false) RevokeKeyRequest req) {
        keys.revokeByAdmin(CallerIdentity.callerId(), id, req == null ? null : req.reason());
        return ResponseEntity.ok(keys.adminRow(id));
    }
}
