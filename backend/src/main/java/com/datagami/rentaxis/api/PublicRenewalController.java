package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.RenewalIntentRequest;
import com.datagami.rentaxis.api.dto.RenewalIntentResponse;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.renewal.RenewalIntentService;
import com.datagami.rentaxis.core.service.renewal.RenewalTokenService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.RenewalOpportunity;
import com.datagami.rentaxis.domain.repository.RenewalOpportunityRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/public/renewal-intent")
@RequiredArgsConstructor
public class PublicRenewalController {

    private final RenewalTokenService tokenService;
    private final RenewalOpportunityRepository opportunityRepository;
    private final RenewalIntentService intentService;

    @PostMapping
    public ResponseEntity<?> captureIntent(@Valid @RequestBody RenewalIntentRequest req) {
        RenewalTokenService.VerifiedToken v;
        try {
            v = tokenService.verify(req.token());
        } catch (RenewalTokenService.TokenExpiredException e) {
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", "TOKEN_EXPIRED"));
        } catch (RenewalTokenService.TokenInvalidException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "TOKEN_INVALID"));
        }

        RenewalOpportunity o = opportunityRepository.findByIdAcrossTenants(v.opportunityId()).orElse(null);
        if (o == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "NOT_FOUND"));
        }

        TenantContextHolder.setTenantId(o.getTenantId());
        try {
            RenewalOpportunity updated = intentService.captureIntentFromToken(o.getId(), v.intent());
            return ResponseEntity.ok(new RenewalIntentResponse(
                    updated.getIntent().name(),
                    updated.getLease().getId(),
                    "/dashboard/renter-portal/renewals"));
        } catch (BusinessRuleViolationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "ALREADY_RESOLVED"));
        } finally {
            TenantContextHolder.clear();
        }
    }
}
