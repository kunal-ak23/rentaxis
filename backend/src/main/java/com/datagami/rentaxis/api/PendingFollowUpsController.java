package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.InteractionDTO;
import com.datagami.rentaxis.core.service.LeaseInteractionService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/renewals/follow-ups")
@RequiredArgsConstructor
public class PendingFollowUpsController {

    private final LeaseInteractionService service;

    /** Scoped to a property manager's buildings in the service (audit P1-3). */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_TENANT_ADMIN','ROLE_PROPERTY_MANAGER')")
    public List<InteractionDTO> list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate cutoff = date != null ? date : LocalDate.now();
        return service.pendingFollowUps(TenantContextHolder.getTenantId(), cutoff);
    }
}
