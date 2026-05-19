package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.RenewalSummaryDTO;
import com.datagami.rentaxis.api.dto.RenewalSummaryDTO.LeaseRenewalView;
import com.datagami.rentaxis.api.dto.RenewalSummaryDTO.ReminderEntry;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.renewal.RenewalIntentService;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseReminder;
import com.datagami.rentaxis.domain.entity.RenewalOpportunity;
import com.datagami.rentaxis.domain.entity.enums.RenewalIntent;
import com.datagami.rentaxis.domain.entity.enums.RenewalStage;
import com.datagami.rentaxis.domain.entity.enums.ReminderStatus;
import com.datagami.rentaxis.domain.repository.LeaseReminderRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.RenewalOpportunityRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/me/renewals")
@RequiredArgsConstructor
public class RenterRenewalController {

    private final RenterRepository renterRepo;
    private final LeaseRepository leaseRepo;
    private final RenewalOpportunityRepository oppRepo;
    private final LeaseReminderRepository reminderRepo;
    private final RenewalIntentService intentService;

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_RENTER')")
    @Transactional(readOnly = true)
    public RenewalSummaryDTO summary(@AuthenticationPrincipal String userIdStr) {
        UUID userId = UUID.fromString(userIdStr);
        var renter = renterRepo.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("Renter not found"));
        List<Lease> leases = leaseRepo.findByRenterId(renter.getId());
        List<LeaseRenewalView> views = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (Lease l : leases) {
            RenewalOpportunity o = oppRepo.findByLeaseIdAndStageIn(
                    l.getId(), List.of(RenewalStage.OPEN, RenewalStage.INTENT_CAPTURED)).orElse(null);
            List<ReminderEntry> rems = new ArrayList<>();
            if (o != null) {
                List<LeaseReminder> reminders = reminderRepo.findByOpportunityId(o.getId());
                // Group by slot — prefer SENT entries over PENDING/SKIPPED for display
                Map<Short, LeaseReminder> bySlot = new HashMap<>();
                for (LeaseReminder r : reminders) {
                    LeaseReminder existing = bySlot.get(r.getSlot());
                    if (existing == null || r.getStatus() == ReminderStatus.SENT) {
                        bySlot.put(r.getSlot(), r);
                    }
                }
                bySlot.values().stream()
                        .sorted(Comparator.comparingInt(r -> -r.getSlot()))
                        .forEach(r -> rems.add(new ReminderEntry(
                                r.getSlot(),
                                r.getStatus().name(),
                                r.getSentAt() != null
                                        ? r.getSentAt().atOffset(ZoneOffset.UTC).toLocalDate()
                                        : null)));
            }
            views.add(new LeaseRenewalView(
                    l.getId(),
                    l.getUnit() != null ? l.getUnit().getUnitNumber() : null,
                    (l.getUnit() != null && l.getUnit().getProperty() != null)
                            ? l.getUnit().getProperty().getNameEn() : null,
                    l.getEndDate(),
                    ChronoUnit.DAYS.between(today, l.getEndDate()),
                    o != null ? o.getId() : null,
                    o != null ? o.getStage().name() : null,
                    o != null && o.getIntent() != null ? o.getIntent().name() : null,
                    rems));
        }
        return new RenewalSummaryDTO(views);
    }

    @PostMapping("/{opportunityId}/intent")
    @PreAuthorize("hasAuthority('ROLE_RENTER')")
    public ResponseEntity<Map<String, String>> setIntent(@PathVariable UUID opportunityId,
                                                         @RequestBody Map<String, String> body,
                                                         @AuthenticationPrincipal String userIdStr) {
        RenewalIntent intent = RenewalIntent.valueOf(body.get("intent"));
        RenewalOpportunity updated = intentService.captureIntentFromRenter(
                opportunityId, intent, UUID.fromString(userIdStr));
        return ResponseEntity.ok(Map.of(
                "intent", updated.getIntent().name(),
                "stage", updated.getStage().name()));
    }
}
