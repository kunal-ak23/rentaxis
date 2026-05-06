package com.datagami.rentaxis.core.email.api;

import com.datagami.rentaxis.core.email.dispatch.EmailPreferenceService;
import com.datagami.rentaxis.core.email.prefs.EmailPreferences;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/email/preferences")
@RequiredArgsConstructor
public class EmailPreferencesController {

    private final EmailPreferenceService service;

    @GetMapping
    public ResponseEntity<Map<String, Object>> get() {
        UUID userId = currentUserId();
        EmailPreferences p = service.ensureRow(userId);
        return ResponseEntity.ok(Map.of(
                "marketingEnabled", p.isMarketingEnabled(),
                "preferences", p.getPreferencesJson()));
    }

    @PutMapping
    public ResponseEntity<Void> update(@RequestBody(required = false) EmailPreferencesUpdateRequest body) {
        UUID userId = currentUserId();
        if (body != null && body.marketingEnabled() != null) {
            service.setMarketing(userId, body.marketingEnabled());
        }
        return ResponseEntity.noContent().build();
    }

    private UUID currentUserId() {
        return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName());
    }
}
