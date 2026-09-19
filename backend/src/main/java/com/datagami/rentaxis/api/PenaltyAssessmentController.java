package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.penalty.PenaltyAssessmentDTO;
import com.datagami.rentaxis.api.dto.penalty.PenaltyDecisionRequest;
import com.datagami.rentaxis.api.dto.penalty.ProposePenaltyRequest;
import com.datagami.rentaxis.core.service.penalty.PenaltyAssessmentService;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.enums.PenaltyAssessmentStatus;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The penalty worklist and the three decisions finance can take on a proposal
 * (spec §7.3).
 *
 * <p><b>Why {@code /penalty-assessments} and not {@code /penalties}.</b> The v1
 * {@code PenaltyController} still owns {@code /api/v1/penalties} and is still what
 * the web app calls; two {@code @RequestMapping}s over the same GET and the same
 * {@code /{id}/waive} would refuse to start the context at all. The v1 controller
 * is deleted in task 12, and this moves onto the plain path then — one rename,
 * rather than a live endpoint broken for a sprint.</p>
 *
 * <p><b>Approving is not a manager's decision.</b> Listing and proposing are open
 * to a property manager, because spotting that a renter should be fined is part of
 * running a building. Turning that into a charge on the ledger is not: approve,
 * waive and reverse are the accountant's, and that split is the entire point of
 * the module.</p>
 *
 * <p>Object-level scoping is {@code LeaseAccessPolicy}'s, inside the service. The
 * role check here answers "may this kind of user call this", never "may this user
 * touch this penalty".</p>
 */
@RestController
@RequestMapping("/api/v1/penalty-assessments")
public class PenaltyAssessmentController {

    private static final int MAX_PAGE_SIZE = 200;

    private final PenaltyAssessmentService service;
    private final RenterRepository renterRepository;

    public PenaltyAssessmentController(PenaltyAssessmentService service, RenterRepository renterRepository) {
        this.service = service;
        this.renterRepository = renterRepository;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER')")
    public ResponseEntity<Page<PenaltyAssessmentDTO>> list(
            @RequestParam(required = false) UUID leaseId,
            @RequestParam(required = false) PenaltyAssessmentStatus status,
            @RequestParam(required = false) UUID propertyId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(service.list(leaseId, status, propertyId,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                        Sort.by(Sort.Direction.ASC, "proposedAt"))));
    }

    /**
     * The renter's own penalties — APPROVED only, and only ever their own.
     *
     * <p>A separate endpoint rather than a role branch inside {@link #list}: the
     * renter is not asking a filtered version of finance's question, they are
     * asking a different one, and the renter id comes from their session rather
     * than from a query parameter they could change.</p>
     */
    @GetMapping("/mine")
    @PreAuthorize("hasRole('RENTER')")
    public ResponseEntity<List<PenaltyAssessmentDTO>> mine() {
        UUID userId = currentUserId();
        UUID renterId = userId == null ? null
                : renterRepository.findByUserId(userId).map(Renter::getId).orElse(null);
        return ResponseEntity.ok(service.forRenter(renterId));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER')")
    public ResponseEntity<PenaltyAssessmentDTO> propose(@Valid @RequestBody ProposePenaltyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.propose(request, currentUserId()));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
    public ResponseEntity<PenaltyAssessmentDTO> approve(@PathVariable UUID id,
                                                        @RequestBody(required = false) PenaltyDecisionRequest request) {
        return ResponseEntity.ok(service.approve(id, body(request).date()));
    }

    @PostMapping("/{id}/waive")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
    public ResponseEntity<PenaltyAssessmentDTO> waive(@PathVariable UUID id,
                                                      @RequestBody(required = false) PenaltyDecisionRequest request) {
        return ResponseEntity.ok(service.waive(id, body(request).note()));
    }

    @PostMapping("/{id}/reverse")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
    public ResponseEntity<PenaltyAssessmentDTO> reverse(@PathVariable UUID id,
                                                        @RequestBody(required = false) PenaltyDecisionRequest request) {
        PenaltyDecisionRequest r = body(request);
        return ResponseEntity.ok(service.reverse(id, r.date(), r.note()));
    }

    private static PenaltyDecisionRequest body(PenaltyDecisionRequest request) {
        return request == null ? PenaltyDecisionRequest.empty() : request;
    }

    private static UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof String s)) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}
