package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.payables.IssuedChequeActionDTO;
import com.datagami.rentaxis.api.dto.payables.IssuedChequeDTO;
import com.datagami.rentaxis.api.dto.payables.IssuedChequeSummaryDTO;
import com.datagami.rentaxis.api.dto.payables.OpeningIssuedChequeInputDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.payables.IssuedChequeService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.IssuedCheque;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The issued-cheques register (finance-ops spec §2): post-dated supplier cheques,
 * present / cancel / unpresent, and cut-over cheques. Finance roles only.
 */
@RestController
@RequestMapping("/api/v1/finance/issued-cheques")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT')")
public class IssuedChequeController {

    private final IssuedChequeService cheques;

    public IssuedChequeController(IssuedChequeService cheques) {
        this.cheques = cheques;
    }

    @GetMapping
    public ResponseEntity<List<IssuedChequeDTO>> list(
            @RequestParam(required = false) IssuedCheque.Status status,
            @RequestParam(required = false) UUID bankAccountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "false") boolean duePresent) {
        requireTenantSelected();
        return ResponseEntity.ok(cheques.list(status, bankAccountId, from, to, duePresent));
    }

    @GetMapping("/summary")
    public ResponseEntity<IssuedChequeSummaryDTO> summary() {
        requireTenantSelected();
        return ResponseEntity.ok(cheques.summary());
    }

    @GetMapping("/{id}")
    public ResponseEntity<IssuedChequeDTO> get(@PathVariable UUID id) {
        requireTenantSelected();
        return ResponseEntity.ok(cheques.get(id));
    }

    /** Manual presentation: unreconciled until a statement line matches it. */
    @PostMapping("/{id}/present")
    public ResponseEntity<IssuedChequeDTO> present(@PathVariable UUID id, @Valid @RequestBody IssuedChequeActionDTO body) {
        requireTenantSelected();
        return ResponseEntity.ok(cheques.present(id, body.date(),
                com.datagami.rentaxis.core.service.ledger.BankLockService.StatementEvidence.of(body.notOnStatement())));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<IssuedChequeDTO> cancel(@PathVariable UUID id, @Valid @RequestBody IssuedChequeActionDTO body) {
        requireTenantSelected();
        return ResponseEntity.ok(cheques.cancel(id, body.date(), body.reason()));
    }

    @PostMapping("/{id}/unpresent")
    public ResponseEntity<IssuedChequeDTO> unpresent(@PathVariable UUID id, @Valid @RequestBody IssuedChequeActionDTO body) {
        requireTenantSelected();
        return ResponseEntity.ok(cheques.unpresent(id, body.date(), body.reason()));
    }

    @PostMapping("/opening")
    public ResponseEntity<IssuedChequeDTO> createOpening(@Valid @RequestBody OpeningIssuedChequeInputDTO body) {
        requireTenantSelected();
        return ResponseEntity.status(HttpStatus.CREATED).body(cheques.createOpening(body));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteOpening(@PathVariable UUID id) {
        requireTenantSelected();
        cheques.deleteOpening(id);
        return ResponseEntity.noContent().build();
    }

    private static void requireTenantSelected() {
        if (TenantContextHolder.getTenantId() == null) {
            throw new BusinessRuleViolationException("Select an organisation first");
        }
    }
}
