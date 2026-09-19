package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.cheque.AgingReportDTO;
import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.cheque.ChequeSummaryDTO;
import com.datagami.rentaxis.api.dto.cheque.DepositBatchRequest;
import com.datagami.rentaxis.api.dto.cheque.LeaseChequeStatsDTO;
import com.datagami.rentaxis.api.dto.cheque.ReplaceChequeRequest;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.RentReceiptService;
import com.datagami.rentaxis.core.service.cheque.ChequeDetailsService;
import com.datagami.rentaxis.core.service.cheque.ChequeQueryService;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * The cheque register (spec §7.4): the collection worklist and every way a row
 * on it moves.
 *
 * <p><b>Roles here answer "may this kind of user work the register at all".</b>
 * Whether this particular caller may touch <em>this</em> row is
 * {@code LeaseAccessPolicy}'s, applied inside the services: reads push the
 * caller's properties into the query rather than filtering a page afterwards, and
 * every mutation goes through {@code requireManageable} on the row's lease. A
 * property manager therefore sees and works their own buildings and gets an empty
 * page — not somebody else's data — when they name a property they were not
 * assigned.</p>
 *
 * <p><b>Cancelling is narrower than the rest.</b> It reverses the registering
 * journal, which is a finance correction rather than a collection step, so a
 * property manager may deposit, clear, bounce and replace but may not cancel.
 * {@code returnToTenant} is not exposed at all: handing the paper back belongs to
 * the termination flow, which owns deciding what to do with every uncleared
 * instrument at once.</p>
 *
 * <p>{@code GET /{id}/receipt} is the one endpoint a RENTER reaches, for their
 * own cleared rows; the policy inside {@code RentReceiptService} is what makes
 * "their own" true.</p>
 */
@RestController
@RequestMapping("/api/v1/cheques")
@RequiredArgsConstructor
public class ChequeController {

    private static final String STAFF =
            "hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER')";
    private static final String FINANCE =
            "hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')";

    private final ChequeQueryService queryService;
    private final ChequeService chequeService;
    private final ChequeDetailsService detailsService;
    private final RentReceiptService rentReceiptService;

    // ------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------

    @GetMapping
    @PreAuthorize(STAFF)
    public Page<ChequeDTO> search(@RequestParam(required = false) UUID propertyId,
                                  @RequestParam(required = false) ChequeStatus status,
                                  @RequestParam(required = false) ChequeMode mode,
                                  @RequestParam(required = false) LocalDate from,
                                  @RequestParam(required = false) LocalDate to,
                                  @RequestParam(required = false) String search,
                                  Pageable pageable) {
        return queryService.search(propertyId, status, mode, from, to, search, pageable);
    }

    @GetMapping("/due")
    @PreAuthorize(STAFF)
    public Page<ChequeDTO> due(@RequestParam(required = false) UUID propertyId,
                               @RequestParam(required = false) LocalDate asOf,
                               Pageable pageable) {
        return queryService.due(propertyId, asOf, pageable);
    }

    @GetMapping("/to-deposit")
    @PreAuthorize(STAFF)
    public Page<ChequeDTO> toDeposit(@RequestParam(required = false) UUID propertyId,
                                     @RequestParam(required = false) LocalDate asOf,
                                     Pageable pageable) {
        return queryService.toDeposit(propertyId, asOf, pageable);
    }

    @GetMapping("/post-dated")
    @PreAuthorize(STAFF)
    public List<ChequeDTO> postDated(@RequestParam(required = false) UUID propertyId,
                                     @RequestParam(required = false) String month) {
        return queryService.postDated(propertyId, parseMonth(month));
    }

    @GetMapping("/summary")
    @PreAuthorize(STAFF)
    public ChequeSummaryDTO summary(@RequestParam(required = false) UUID propertyId,
                                    @RequestParam(required = false) LocalDate asOf) {
        return queryService.summary(propertyId, asOf);
    }

    @GetMapping("/aging")
    @PreAuthorize(STAFF)
    public AgingReportDTO aging(@RequestParam(required = false) UUID propertyId,
                                @RequestParam(required = false) LocalDate asOf) {
        return queryService.aging(propertyId, asOf);
    }

    /**
     * Collection position for a batch of leases. A POST because the list of ids is
     * the request body — twenty UUIDs in a query string is a 900-character URL that
     * proxies truncate.
     */
    @PostMapping("/stats-by-leases")
    @PreAuthorize(STAFF)
    public List<LeaseChequeStatsDTO> statsByLeases(@RequestBody List<UUID> leaseIds) {
        return queryService.statsByLeases(leaseIds, null);
    }

    @GetMapping("/{id}")
    @PreAuthorize(STAFF)
    public ChequeDTO get(@PathVariable UUID id) {
        return queryService.get(id);
    }

    // ------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------

    @PutMapping("/{id}/deposit")
    @PreAuthorize(STAFF)
    public ChequeDTO deposit(@PathVariable UUID id,
                             @RequestBody(required = false) ChequeActionRequest request) {
        return chequeService.deposit(id, request);
    }

    @PostMapping("/deposit-batch")
    @PreAuthorize(STAFF)
    public List<ChequeDTO> depositBatch(@RequestBody DepositBatchRequest request) {
        return chequeService.depositBatch(request);
    }

    @PutMapping("/{id}/clear")
    @PreAuthorize(STAFF)
    public ChequeDTO clear(@PathVariable UUID id,
                           @RequestBody(required = false) ChequeActionRequest request) {
        return chequeService.clear(id, request);
    }

    @PutMapping("/{id}/receive")
    @PreAuthorize(STAFF)
    public ChequeDTO receive(@PathVariable UUID id,
                             @RequestBody(required = false) ChequeActionRequest request) {
        return chequeService.receive(id, request);
    }

    /**
     * The bank returned it. The reason is required here rather than defaulted:
     * "insufficient funds" and "signature mismatch" lead to different conversations
     * with the renter, and a blank one makes the bounce history useless.
     */
    @PutMapping("/{id}/bounce")
    @PreAuthorize(STAFF)
    public ChequeDTO bounce(@PathVariable UUID id, @RequestBody ChequeActionRequest request) {
        if (request == null || request.failureReason() == null) {
            throw new BusinessRuleViolationException("A bounce needs a failure reason");
        }
        return chequeService.bounce(id, request);
    }

    @PostMapping("/{id}/replace")
    @PreAuthorize(STAFF)
    public List<ChequeDTO> replace(@PathVariable UUID id, @RequestBody ReplaceChequeRequest request) {
        return chequeService.replace(id, request);
    }

    @PutMapping("/{id}/cancel")
    @PreAuthorize(FINANCE)
    public ChequeDTO cancel(@PathVariable UUID id,
                            @RequestBody(required = false) ChequeActionRequest request) {
        return chequeService.cancel(id, request);
    }

    /** Number, bank, payer and the date on the paper — REGISTERED rows only, no journal. */
    @PutMapping("/{id}/details")
    @PreAuthorize(STAFF)
    public ChequeDTO details(@PathVariable UUID id, @RequestBody ChequeRowInput input) {
        return detailsService.updateDetails(id, input);
    }

    /**
     * Cash or a transfer taken at the counter against a lease that is on the books:
     * the row is created and received in one call, so the register never holds a
     * receipt that exists but has not arrived.
     */
    @PostMapping("/lease/{leaseId}/cash-receipt")
    @PreAuthorize(STAFF)
    public ChequeDTO cashReceipt(@PathVariable UUID leaseId, @RequestBody ChequeRowInput row) {
        return chequeService.cashReceipt(leaseId, row);
    }

    // ------------------------------------------------------------------
    // receipt
    // ------------------------------------------------------------------

    /**
     * The receipt for a cleared row (spec §9.3). RENTER is included because it is
     * their receipt; {@code RentReceiptService} decides whether this row is theirs.
     */
    @GetMapping("/{id}/receipt")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER', 'RENTER')")
    public ResponseEntity<byte[]> downloadReceipt(@PathVariable UUID id) {
        byte[] pdf = rentReceiptService.generateReceipt(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=receipt-" + id.toString().substring(0, 8) + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    /** {@code YYYY-MM}, or the current month when the caller says nothing. */
    private static YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.now();
        }
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessRuleViolationException("month must be in YYYY-MM form, not '" + month + "'");
        }
    }
}
