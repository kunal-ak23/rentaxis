package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.InteractionDTO;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.repository.LeaseInteractionRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/renewals/follow-ups")
@RequiredArgsConstructor
public class PendingFollowUpsController {

    private final LeaseInteractionRepository repo;
    private final UserRepository userRepo;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_TENANT_ADMIN','ROLE_PROPERTY_MANAGER')")
    public List<InteractionDTO> list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate cutoff = date != null ? date : LocalDate.now();
        return repo.findPendingFollowUps(TenantContextHolder.getTenantId(), cutoff)
                .stream().map(i -> new InteractionDTO(
                        i.getId(), i.getLease().getId(),
                        i.getOpportunity() != null ? i.getOpportunity().getId() : null,
                        i.getType(), i.getDirection(),
                        i.getOccurredAt(), i.getSummary(),
                        i.getOutcome(), i.getFollowUpDate(),
                        i.getCreatedBy(),
                        // findDisplayNameById, not the tenant-filtered findById: a
                        // SUPER_ADMIN acting inside a pivoted tenant has
                        // tenant_id = NULL, so the filtered lookup can't see them
                        // and the name comes back blank.
                        userRepo.findDisplayNameById(i.getCreatedBy()).orElse(null),
                        i.getCreatedAt()))
                .collect(Collectors.toList());
    }
}
