package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.*;
import com.datagami.rentaxis.api.dto.BulkAttachChequesRequest;
import com.datagami.rentaxis.api.dto.BulkAttachChequesResponse;
import com.datagami.rentaxis.api.dto.SaveSettlementDTO;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.lease.AddChargeRequest;
import com.datagami.rentaxis.api.dto.lease.AddendumResponse;
import com.datagami.rentaxis.api.dto.lease.AmendLeaseLinesRequest;
import com.datagami.rentaxis.api.dto.lease.ExtendLeaseRequest;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.lease.GenerateChequeNumbersRequest;
import com.datagami.rentaxis.api.dto.lease.GiveNoticeRequest;
import com.datagami.rentaxis.api.dto.lease.GenerateChequesRequest;
import com.datagami.rentaxis.api.dto.lease.LeaseAddendumDTO;
import com.datagami.rentaxis.api.dto.lease.LeaseLineDTO;
import com.datagami.rentaxis.api.dto.lease.PostLeaseResponse;
import com.datagami.rentaxis.api.dto.lease.RecordEjariRequest;
import com.datagami.rentaxis.api.dto.lease.RenewLeaseRequest;
import com.datagami.rentaxis.api.dto.lease.TerminateLeaseRequest;
import com.datagami.rentaxis.api.dto.lease.TerminationPreviewDTO;
import com.datagami.rentaxis.api.dto.settlement.FinalizeSettlementRequest;
import com.datagami.rentaxis.api.dto.settlement.SettlementStatementDTO;
import com.datagami.rentaxis.core.service.ContractGenerationService;
import com.datagami.rentaxis.core.service.cheque.ChequeDetailsService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.lease.LeaseRenewalService;
import com.datagami.rentaxis.core.service.lease.LeaseTerminationService;
import com.datagami.rentaxis.core.service.lease.LeaseVariationService;
import com.datagami.rentaxis.core.service.LeaseInteractionService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.SettlementService;
import com.datagami.rentaxis.core.service.renewal.RenewalOpportunityService;
import com.datagami.rentaxis.domain.entity.enums.InteractionDirection;
import com.datagami.rentaxis.domain.entity.enums.InteractionType;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/leases")
@RequiredArgsConstructor
public class LeaseController {

    private final LeaseService leaseService;
    private final ContractGenerationService contractGenerationService;
    private final SettlementService settlementService;
    private final RenewalOpportunityService renewalOpportunityService;
    private final LeaseInteractionService leaseInteractionService;
    private final ChequeGenerationService chequeGenerationService;
    private final ChequeDetailsService chequeDetailsService;
    private final LeasePostingService leasePostingService;
    private final LeaseRenewalService leaseRenewalService;
    private final LeaseTerminationService leaseTerminationService;
    private final LeaseVariationService leaseVariationService;

    /**
     * ACCOUNTANT on every read below.
     *
     * <p>The role could already <em>post</em> a lease and could read its cheques
     * and its journals, but not load the lease itself — so the one person whose job
     * is to put a contract on the books could not open the contract. The write
     * endpoints are untouched: drafting, amending and terminating stay with the
     * admins and the manager. {@code LeaseAccessPolicy} already treats an accountant
     * as tenant-wide, so these are role gates catching up with the policy, not a new
     * scope.</p>
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER')")
    public ResponseEntity<List<LeaseDTO>> getAllLeases() {
        return ResponseEntity.ok(leaseService.getAllLeases());
    }

    /**
     * {@code status} and {@code propertyId} are filters, not hints: they narrow the
     * query, so the page and its total describe the same set of contracts. The list
     * screen used to filter status over the page it had been given, which made the
     * paginator announce a total it was not showing.
     */
    @GetMapping("/paged")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER')")
    public ResponseEntity<Page<LeaseDTO>> getLeasesPaged(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) LeaseStatus status,
            @RequestParam(required = false) UUID propertyId,
            @PageableDefault(size = 25, sort = "startDate", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(leaseService.getAllLeasesPaged(search, status, propertyId, pageable));
    }

    @GetMapping("/property/{propertyId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER')")
    public ResponseEntity<List<LeaseDTO>> getLeasesByPropertyId(@PathVariable UUID propertyId) {
        return ResponseEntity.ok(leaseService.getLeasesByPropertyId(propertyId));
    }

    @GetMapping("/my-leases")
    @PreAuthorize("hasRole('RENTER')")
    public ResponseEntity<List<LeaseDTO>> getMyLeases(HttpServletRequest request) {
        String userIdStr = request.getHeader("X-User-Id");
        UUID userId = UUID.fromString(userIdStr);
        return ResponseEntity.ok(leaseService.getLeasesForRenterUser(userId));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER')")
    public ResponseEntity<LeaseDTO> getLeaseById(@PathVariable UUID id) {
        return ResponseEntity.ok(leaseService.getLeaseById(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<LeaseDTO> createDraftLease(@Valid @RequestBody CreateLeaseDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(leaseService.createDraftLease(dto));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<LeaseDTO> updateDraftLease(@PathVariable UUID id, @Valid @RequestBody CreateLeaseDTO dto) {
        return ResponseEntity.ok(leaseService.updateDraftLease(id, dto));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<Void> deleteDraftLease(@PathVariable UUID id) {
        leaseService.deleteDraftLease(id);
        return ResponseEntity.noContent().build();
    }

    // --- Posting -----------------------------------------------------------
    //
    // PUT /{id}/activate is gone. A lease used to become ACTIVE by a status change
    // with no journal behind it, so "active" and "on the books" were two separate
    // truths about the same contract. Post is the only path to ACTIVE now
    // (spec §6.3): it writes the TCO and the PDRs, claims the unit and flips the
    // status in one transaction.

    /**
     * Post the lease: one TCO dated the contract date, one PDR per cheque, unit
     * claimed, status ACTIVE.
     *
     * <p>{@code dryRun=true} runs every validation and returns 200 with the list of
     * problems, having written nothing — no journals, no status change, not even an
     * entry number. It is what the review step before the button calls, and it is
     * the same validation the real post runs, so the two cannot disagree.</p>
     */
    @PostMapping("/{id}/post")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
    public ResponseEntity<Object> postLease(@PathVariable UUID id,
                                            @RequestParam(name = "dryRun", defaultValue = "false") boolean dryRun) {
        return ResponseEntity.ok(dryRun
                ? leasePostingService.dryRun(id)
                : leasePostingService.post(id));
    }

    /**
     * Replace a posted lease's lines: the current TCO is reversed by a TCR and a
     * fresh TCO is posted in its place. Only while every cheque is still REGISTERED.
     */
    @PostMapping("/{id}/amend-lines")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
    public ResponseEntity<PostLeaseResponse> amendLeaseLines(@PathVariable UUID id,
                                                             @Valid @RequestBody AmendLeaseLinesRequest request) {
        return ResponseEntity.ok(leasePostingService.amendLines(id, request));
    }

    /**
     * The lease's charged particulars. Also carried inline on the lease itself;
     * this exists for the screens that render the lines on their own.
     */
    @GetMapping("/{id}/lines")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER')")
    public ResponseEntity<List<LeaseLineDTO>> getLeaseLines(@PathVariable UUID id) {
        return ResponseEntity.ok(leaseService.getLines(id));
    }

    // --- Cheque grid -------------------------------------------------------
    //
    // PUT /{id}/payment-schedule is gone. The payment plan is no longer a side
    // effect of the lease: cheques are generated explicitly against the lease's
    // lines and edited row by row here.
    //
    // These sit on the lease rather than on the cheque register because they are
    // operations on a draft contract, not on the register's worklist — the rows
    // they write are DRAFT and the register cannot see them at all.

    /** The lease's grid, every status, in schedule order. */
    @GetMapping("/{id}/cheques")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER')")
    public ResponseEntity<List<ChequeDTO>> getCheques(@PathVariable UUID id) {
        return ResponseEntity.ok(chequeGenerationService.list(id));
    }

    /** Cut the lease's lines into a fresh draft grid, replacing the previous draft rows. */
    @PostMapping("/{id}/cheques/generate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER')")
    public ResponseEntity<List<ChequeDTO>> generateCheques(
            @PathVariable UUID id,
            @RequestBody(required = false) GenerateChequesRequest request) {
        return ResponseEntity.ok(chequeGenerationService.generate(id, request));
    }

    /** Number the draft PDC rows sequentially from the renter's first cheque. */
    @PostMapping("/{id}/cheques/numbers")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER')")
    public ResponseEntity<List<ChequeDTO>> generateChequeNumbers(
            @PathVariable UUID id,
            @Valid @RequestBody GenerateChequeNumbersRequest request) {
        return ResponseEntity.ok(chequeGenerationService.generateNumbers(id, request.startingNumber()));
    }

    /** Replace the draft grid with the edited rows. */
    @PutMapping("/{id}/cheques")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER')")
    public ResponseEntity<List<ChequeDTO>> saveChequeRows(
            @PathVariable UUID id,
            @RequestBody List<ChequeRowInput> rows) {
        return ResponseEntity.ok(chequeGenerationService.saveRows(id, rows));
    }

    // --- Termination (spec §9.1) -------------------------------------------

    /**
     * The renter has said they are leaving: ACTIVE → NOTICE_GIVEN.
     *
     * <p>Open to PROPERTY_MANAGER as well as the finance roles, and scoped by
     * {@code LeaseAccessPolicy} to the buildings they are assigned (a lease
     * elsewhere is a 404, not a 403). Taking a renter's notice is the building
     * manager's job — it writes no journal, hands nothing back and leaves every
     * instrument on the register exactly where it was — which is why this is one
     * role wider than {@link #terminateLease}.</p>
     *
     * <p>The body is optional; anything in {@code notes} goes on the lease's event
     * trail. A lease that is not ACTIVE is refused with a 400.</p>
     */
    @PostMapping("/{id}/notice")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER')")
    public ResponseEntity<LeaseDTO> giveNotice(
            @PathVariable UUID id,
            @RequestBody(required = false) GiveNoticeRequest request,
            HttpServletRequest httpRequest) {
        String userIdStr = httpRequest.getHeader("X-User-Id");
        UUID byUser = userIdStr != null ? UUID.fromString(userIdStr) : null;
        return ResponseEntity.ok(leaseService.giveNotice(id, request == null ? null : request.notes(), byUser));
    }

    /**
     * What ending the contract on {@code date} would do, with nothing written: the
     * rent earned through that day, what has already been recognised, the advance
     * rent to be handed back, the default return/keep split of the uncleared
     * register rows, and the receivable the renter would be left with.
     *
     * <p>Open to PROPERTY_MANAGER as well as the finance roles, scoped by
     * {@code LeaseAccessPolicy} to the buildings they are assigned. Looking at the
     * consequences of a move-out is the building manager's job; posting the
     * journals that end a contract is the accountant's, which is why
     * {@link #terminateLease} is one role narrower.</p>
     */
    @GetMapping("/{id}/terminate/preview")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER')")
    public ResponseEntity<TerminationPreviewDTO> previewTermination(
            @PathVariable UUID id,
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(leaseTerminationService.preview(id, date));
    }

    /**
     * End the contract on {@code terminationDate}: the chosen uncleared cheques go
     * back with their {@code PDR}s reversed, recognition is truncated, the unearned
     * rent comes back as one {@code TCR}, and the lease goes TERMINATED. One
     * transaction — see {@code LeaseTerminationService}.
     */
    @PostMapping("/{id}/terminate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
    public ResponseEntity<LeaseDTO> terminateLease(
            @PathVariable UUID id,
            @Valid @RequestBody TerminateLeaseRequest request,
            HttpServletRequest httpRequest) {
        String userIdStr = httpRequest.getHeader("X-User-Id");
        UUID byUser = userIdStr != null ? UUID.fromString(userIdStr) : null;
        return ResponseEntity.ok(leaseTerminationService.terminate(id, request, byUser));
    }

    /**
     * The move-out statement, computed live from the ledger (spec §9.2).
     *
     * <p>The path is the one the screen has always called; what comes back is no
     * longer a deposit-minus-arrears guess but the statement itself — earned rent,
     * cleared receipts, the receivable's own balance, deposits held, outstanding
     * penalties, the draft's lines and the net refund. ACCOUNTANT is admitted
     * because this is a finance document; a PROPERTY_MANAGER is admitted for the
     * buildings they were assigned and scoped to them by {@code LeaseAccessPolicy}
     * (a lease elsewhere is a 404, not a 403).</p>
     */
    @GetMapping("/{id}/settlement/preview")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER')")
    public ResponseEntity<SettlementStatementDTO> getSettlementStatement(@PathVariable UUID id) {
        return ResponseEntity.ok(settlementService.statement(id));
    }

    @GetMapping("/{id}/settlement")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER')")
    public ResponseEntity<SettlementResponseDTO> getSettlement(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(settlementService.buildSettlementResponse(id));
        } catch (com.datagami.rentaxis.api.exception.NotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Save the settlement's lines.
     *
     * <p>PROPERTY_MANAGER is <em>not</em> here, and that asymmetry with the
     * statement above is the same one preview/terminate draws: deciding what comes
     * out of a renter's deposit is the accountant's call, looking at what a move-out
     * costs is the building manager's.</p>
     */
    @PostMapping("/{id}/settlement/draft")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
    public ResponseEntity<SettlementResponseDTO> saveSettlementDraft(
            @PathVariable UUID id,
            @Valid @RequestBody SaveSettlementDTO dto,
            HttpServletRequest request) {
        String userIdStr = request.getHeader("X-User-Id");
        UUID userId = userIdStr != null ? UUID.fromString(userIdStr) : null;
        settlementService.saveDraft(id, dto, userId);
        return ResponseEntity.ok(settlementService.buildSettlementResponse(id));
    }

    /**
     * Finalise the settlement: one {@code STL} on {@code settlementDate}, and the
     * lease CLOSED when nothing is left to collect (spec §9.2).
     *
     * <p><b>It does not terminate the lease.</b> Termination is its own act with
     * its own date, its own cheque decisions and its own journals
     * ({@code POST /{id}/terminate}), and it happens <em>first</em>: the statement
     * this finalises is drawn from the receivable that termination leaves behind.
     * A lease that is still running is refused.</p>
     *
     * <p>Returns the settlement, not the lease: the interesting result is the
     * journal number, the refund or balance due, and the collection row — all of
     * which are on the settlement. The lease's new status is one more
     * {@code GET /leases/{id}} away and the screen already has it.</p>
     */
    @PostMapping("/{id}/settlement/finalize")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
    public ResponseEntity<SettlementResponseDTO> finalizeSettlement(
            @PathVariable UUID id,
            @RequestBody(required = false) FinalizeSettlementRequest body,
            HttpServletRequest request) {
        String userIdStr = request.getHeader("X-User-Id");
        UUID settledBy = userIdStr != null ? UUID.fromString(userIdStr) : null;
        return ResponseEntity.ok(settlementService.finalizeSettlement(id, body, settledBy));
    }

    // --- Renewal chain and extension (spec §6.6, §6.7) ---------------------

    /**
     * Draft the successor to this lease: same unit, same renter, new term, and a
     * link back to the contract it replaces.
     *
     * <p>Open to PROPERTY_MANAGER as well as the finance roles, and that asymmetry
     * with <em>post</em> is deliberate: drafting next year's contract is the
     * building manager's job — it writes no journals and changes nothing about the
     * lease being renewed — while deciding when it goes on the books is the
     * accountant's.</p>
     */
    @PostMapping("/{id}/renew")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER')")
    public ResponseEntity<LeaseDTO> renewLease(@PathVariable UUID id,
                                               @Valid @RequestBody RenewLeaseRequest request) {
        return ResponseEntity.ok(leaseRenewalService.renew(id, request));
    }

    /**
     * Extend a posted lease: new lines for the extra window, a further TCO for
     * them, the cheques that pay for it registered on the spot.
     *
     * <p>Finance roles only. Unlike a renewal this posts immediately — there is no
     * draft to review, because the lease is already on the books and its grid
     * closed when it posted.</p>
     */
    @PostMapping("/{id}/extend")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
    public ResponseEntity<PostLeaseResponse> extendLease(@PathVariable UUID id,
                                                         @Valid @RequestBody ExtendLeaseRequest request) {
        return ResponseEntity.ok(leaseRenewalService.extend(id, request));
    }

    /**
     * Add a charge to a posted lease mid-term, as a numbered addendum: a further
     * TCO for the new lines, the cheques that pay for it registered on the spot.
     * Finance roles only, like /extend — it posts immediately.
     */
    @PostMapping("/{id}/addenda")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
    public ResponseEntity<AddendumResponse> addCharge(@PathVariable UUID id,
                                                      @Valid @RequestBody AddChargeRequest request) {
        return ResponseEntity.ok(leaseVariationService.addCharge(id, request));
    }

    @GetMapping("/{id}/addenda")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER')")
    public ResponseEntity<List<LeaseAddendumDTO>> listAddenda(@PathVariable UUID id) {
        return ResponseEntity.ok(leaseVariationService.list(id));
    }

    /** Fill in the Ejari a variation was re-registered under; blank until then ("Ejari pending"). */
    @PatchMapping("/{id}/addenda/{addendumId}/ejari")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
    public ResponseEntity<LeaseAddendumDTO> recordAddendumEjari(@PathVariable UUID id,
                                                               @PathVariable UUID addendumId,
                                                               @Valid @RequestBody RecordEjariRequest request) {
        return ResponseEntity.ok(leaseVariationService.recordEjari(id, addendumId, request.ejariNumber()));
    }

    @GetMapping("/{id}/events")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER')")
    public ResponseEntity<List<LeaseEventDTO>> getLeaseEvents(@PathVariable UUID id) {
        return ResponseEntity.ok(leaseService.getLeaseEvents(id));
    }

    // --- Contract Generation Endpoints ---

    @PostMapping("/{id}/generate-contract")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<LeaseDocumentDTO> generateContract(@PathVariable UUID id) {
        return ResponseEntity.ok(contractGenerationService.generateContract(id));
    }

    @PostMapping("/{id}/generate-contract/preview")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<byte[]> previewContract(@PathVariable UUID id) {
        byte[] pdf = contractGenerationService.previewContract(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"lease-preview.pdf\"")
                .body(pdf);
    }

    @GetMapping("/{id}/documents")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER', 'RENTER')")
    public ResponseEntity<List<LeaseDocumentDTO>> getDocuments(@PathVariable UUID id) {
        return ResponseEntity.ok(contractGenerationService.getDocuments(id));
    }

    @GetMapping("/documents/{docId}/download")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER', 'RENTER')")
    public ResponseEntity<byte[]> downloadDocument(@PathVariable UUID docId) {
        byte[] content = contractGenerationService.getDocumentContent(docId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"contract-" + docId + ".pdf\"")
                .body(content);
    }

    // --- Renter Portal Endpoints ---

    @PutMapping("/{id}/accept")
    @PreAuthorize("hasRole('RENTER')")
    public ResponseEntity<LeaseDTO> acceptLease(@PathVariable UUID id, HttpServletRequest request) {
        String userIdStr = request.getHeader("X-User-Id");
        UUID userId = UUID.fromString(userIdStr);
        return ResponseEntity.ok(leaseService.acceptLease(id, userId));
    }

    @PutMapping("/{id}/reject")
    @PreAuthorize("hasRole('RENTER')")
    public ResponseEntity<LeaseDTO> rejectLease(@PathVariable UUID id, HttpServletRequest request) {
        String userIdStr = request.getHeader("X-User-Id");
        UUID userId = UUID.fromString(userIdStr);
        return ResponseEntity.ok(leaseService.rejectLease(id, userId));
    }

    // --- Bulk Cheque Attach ---

    /**
     * A stack of scanned cheques assigned to the lease's register rows in one act.
     *
     * <p>Targets the rows themselves — a DRAFT row of the grid or a REGISTERED
     * instrument — and writes only number, bank, payer, date and image. Nothing
     * posts: the receivable was raised when the row registered and is exactly the
     * same size afterwards. A DEPOSITED or later row is refused with the same
     * error-row shape as any other bad item.</p>
     */
    @PostMapping("/{leaseId}/cheques/bulk-attach")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER')")
    public ResponseEntity<BulkAttachChequesResponse> bulkAttachCheques(
            @PathVariable UUID leaseId,
            @Valid @RequestBody BulkAttachChequesRequest request) {
        return ResponseEntity.ok(new BulkAttachChequesResponse(
                chequeDetailsService.bulkAttach(leaseId, request.getItems())));
    }

    // --- Renewal ---

    @PostMapping("/{id}/renewal/mark-renewed")
    @PreAuthorize("hasAnyAuthority('ROLE_TENANT_ADMIN','ROLE_PROPERTY_MANAGER')")
    public ResponseEntity<Map<String, Object>> markRenewed(
            @PathVariable UUID id,
            @RequestBody(required = false) MarkRenewedRequest req,
            @AuthenticationPrincipal String userIdStr) {
        var opp = renewalOpportunityService.markRenewed(id);
        if (req != null && req.note() != null && !req.note().isBlank()) {
            leaseInteractionService.create(id,
                    new CreateInteractionRequest(
                            InteractionType.NOTE,
                            InteractionDirection.INTERNAL,
                            java.time.Instant.now(),
                            req.note(),
                            null,
                            null),
                    UUID.fromString(userIdStr));
        }
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("id", opp.getId());
        body.put("stage", opp.getStage().name());
        body.put("outcome", opp.getOutcome() != null ? opp.getOutcome().name() : null);
        body.put("closedAt", opp.getClosedAt() != null ? opp.getClosedAt().toString() : null);
        return ResponseEntity.ok(body);
    }

}
