package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.rating.AddAdjustmentRequest;
import com.datagami.rentaxis.api.dto.rating.RatingEventDTO;
import com.datagami.rentaxis.api.dto.rating.RenterRatingDetailDTO;
import com.datagami.rentaxis.api.dto.rating.RenterRatingRowDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.security.PropertyScope;
import com.datagami.rentaxis.core.service.RenterService;
import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.core.service.rating.RenterRatingJob;
import com.datagami.rentaxis.core.service.rating.RenterRatingService;
import com.datagami.rentaxis.core.service.report.statement.ReportCsv;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.enums.RatingGrade;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Premium renter rating (staff only). Every endpoint except the SUPER_ADMIN recompute starts with the
 * feature check for the caller's org (404 when off, as LISTINGS does). A property manager is limited to
 * renters visible to them ({@code RenterService.requireReadableRenter}) and to their properties in the list.
 */
@RestController
@RequestMapping("/api/v1")
public class RenterRatingController {

    static final int MAX_PAGE_SIZE = 200;
    static final int MAX_RENTER_IDS = 200;
    private static final int EXPORT_PAGE = 500;
    private static final String VIEW_ROLES = "hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','PROPERTY_MANAGER','ACCOUNTANT')";
    private static final String ADJUST_ROLES = "hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')";

    private final RenterRatingService service;
    private final RenterRatingJob job;
    private final TenantFeatureService tenantFeatureService;
    private final RenterService renterService;
    private final PropertyScope propertyScope;
    private final LandlordOrgRepository orgRepository;
    private final Executor executor;
    private final Clock clock;

    public RenterRatingController(RenterRatingService service, RenterRatingJob job,
                                  TenantFeatureService tenantFeatureService, RenterService renterService,
                                  PropertyScope propertyScope, LandlordOrgRepository orgRepository,
                                  @Qualifier("renterRatingExecutor") Executor executor, Clock clock) {
        this.service = service;
        this.job = job;
        this.tenantFeatureService = tenantFeatureService;
        this.renterService = renterService;
        this.propertyScope = propertyScope;
        this.orgRepository = orgRepository;
        this.executor = executor;
        this.clock = clock;
    }

    @GetMapping("/renters/{id}/rating")
    @PreAuthorize(VIEW_ROLES)
    public ResponseEntity<RenterRatingDetailDTO> rating(@PathVariable UUID id) {
        UUID tenantId = requireFeature();
        renterService.requireReadableRenter(id);
        return ResponseEntity.ok(service.live(tenantId, id, LocalDate.now(clock)));
    }

    @GetMapping("/renter-ratings")
    @PreAuthorize(VIEW_ROLES)
    public ResponseEntity<Page<RenterRatingRowDTO>> list(
            @RequestParam(required = false) RatingGrade grade,
            @RequestParam(required = false) UUID propertyId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String renterIds,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort) {
        UUID tenantId = requireFeature();
        List<UUID> ids = parseIds(renterIds);
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE), parseSort(sort));
        return ResponseEntity.ok(query(tenantId, grade, propertyId, q, ids, pageable));
    }

    @GetMapping("/renter-ratings/export")
    @PreAuthorize(VIEW_ROLES)
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) RatingGrade grade,
            @RequestParam(required = false) UUID propertyId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String renterIds,
            @RequestParam(required = false) String sort) {
        UUID tenantId = requireFeature();
        List<UUID> ids = parseIds(renterIds);
        Sort s = parseSort(sort);
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Renter", "Grade", "Score", "Deductions", "Unit", "Property", "Computed at"));
        for (int p = 0; ; p++) {
            Page<RenterRatingRowDTO> chunk = query(tenantId, grade, propertyId, q, ids, PageRequest.of(p, EXPORT_PAGE, s));
            for (RenterRatingRowDTO r : chunk.getContent()) {
                rows.add(List.of(nz(r.renterName()), r.grade().name(), String.valueOf(r.score()),
                        String.valueOf(r.deductionsCount()), nz(r.unitLabel()), nz(r.propertyName()),
                        r.computedAt() == null ? "" : r.computedAt().toString()));
            }
            if (!chunk.hasNext()) break;
        }
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("renter-ratings.csv").build().toString())
                .body(ReportCsv.encode(rows));
    }

    @PostMapping("/renters/{id}/rating/adjustments")
    @PreAuthorize(ADJUST_ROLES)
    public ResponseEntity<RatingEventDTO> addAdjustment(@PathVariable UUID id, @RequestBody AddAdjustmentRequest body) {
        UUID tenantId = requireFeature();
        return ResponseEntity.status(HttpStatus.CREATED).body(
                service.addAdjustment(tenantId, id, body.points(), body.reason(), currentUserId(), LocalDate.now(clock)));
    }

    @DeleteMapping("/renters/{id}/rating/adjustments/{adjustmentId}")
    @PreAuthorize(ADJUST_ROLES)
    public ResponseEntity<Void> removeAdjustment(@PathVariable UUID id, @PathVariable UUID adjustmentId) {
        UUID tenantId = requireFeature();
        service.removeAdjustment(tenantId, id, adjustmentId, currentUserId(), LocalDate.now(clock));
        return ResponseEntity.noContent().build();
    }

    /** Queues a recompute of one org on the rating executor and returns at once. */
    @PostMapping("/admin/renter-ratings/recompute")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> recompute(@RequestParam UUID tenantId) {
        if (!orgRepository.existsById(tenantId)) throw new NotFoundException("Organization not found");
        if (!tenantFeatureService.isEnabled(tenantId, TenantFeature.RENTER_RATING)) {
            throw new NotFoundException("Renter rating feature is disabled");
        }
        LocalDate today = LocalDate.now(clock);
        executor.execute(() -> job.runNowForTenant(tenantId, today));
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    private Page<RenterRatingRowDTO> query(UUID tenantId, RatingGrade grade, UUID propertyId, String q,
                                           List<UUID> renterIds, Pageable pageable) {
        List<UUID> scoped = null;
        if (propertyScope.isScoped()) {
            if (propertyId != null) propertyScope.requireCanAccessProperty(propertyId);
            scoped = propertyScope.scopedPropertyIds();
        }
        return service.list(tenantId, grade, propertyId, scoped, q, renterIds, pageable);
    }

    private UUID requireFeature() {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null || !tenantFeatureService.isEnabled(tenantId, TenantFeature.RENTER_RATING)) {
            throw new NotFoundException("Renter rating feature is disabled");
        }
        return tenantId;
    }

    private static List<UUID> parseIds(String csv) {
        if (csv == null || csv.isBlank()) return null;
        Set<UUID> out = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (t.isEmpty()) continue;
            try {
                out.add(UUID.fromString(t));
            } catch (IllegalArgumentException e) {
                throw new BusinessRuleViolationException("Invalid renter id: " + t);
            }
            if (out.size() > MAX_RENTER_IDS) {
                throw new BusinessRuleViolationException("At most " + MAX_RENTER_IDS + " renterIds");
            }
        }
        return out.isEmpty() ? null : List.copyOf(out);
    }

    /** "key" or "key,desc"; the service whitelists the key (score, name, computedAt). */
    private static Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) return Sort.by(Sort.Direction.ASC, "score");
        String[] p = sort.split(",", 2);
        Sort.Direction d = p.length > 1 && "desc".equalsIgnoreCase(p[1].trim()) ? Sort.Direction.DESC : Sort.Direction.ASC;
        return Sort.by(d, p[0].trim());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof String s)) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}
