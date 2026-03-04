package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.RentCollectionSettingsDTO;
import com.datagami.rentaxis.core.service.RentCollectionSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/rent-settings")
@RequiredArgsConstructor
public class RentCollectionSettingsController {

    private final RentCollectionSettingsService rentCollectionSettingsService;

    @GetMapping("/{propertyId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<RentCollectionSettingsDTO> getSettings(@PathVariable UUID propertyId) {
        RentCollectionSettingsDTO settings = rentCollectionSettingsService.getSettings(propertyId);
        if (settings == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(settings);
    }

    @PostMapping("/{propertyId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<RentCollectionSettingsDTO> saveSettings(
            @PathVariable UUID propertyId,
            @RequestBody RentCollectionSettingsDTO dto) {
        return ResponseEntity.ok(rentCollectionSettingsService.saveSettings(propertyId, dto));
    }
}
