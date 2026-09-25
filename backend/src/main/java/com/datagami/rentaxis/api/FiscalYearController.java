package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.ledger.FiscalYearDTO;
import com.datagami.rentaxis.api.dto.ledger.YearClosePreviewDTO;
import com.datagami.rentaxis.core.service.ledger.YearEndCloseService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Fiscal year-end close (spec 2026-09-24 §3): the years, a close preview, close
 * (TENANT_ADMIN, ACCOUNTANT) and re-open (TENANT_ADMIN only).
 */
@RestController
@RequestMapping("/api/v1/finance/fiscal-years")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
public class FiscalYearController {

    private final YearEndCloseService service;

    public FiscalYearController(YearEndCloseService service) {
        this.service = service;
    }

    public record CloseRequest(boolean overrideWarnings) {
    }

    public record ReopenRequest(String reason) {
    }

    @GetMapping
    public ResponseEntity<List<FiscalYearDTO>> list() {
        return ResponseEntity.ok(service.list(LocalDate.now()));
    }

    @GetMapping("/{fy}/close-preview")
    public ResponseEntity<YearClosePreviewDTO> preview(@PathVariable int fy) {
        return ResponseEntity.ok(service.preview(fy, LocalDate.now()));
    }

    @PostMapping("/{fy}/close")
    public ResponseEntity<FiscalYearDTO> close(@PathVariable int fy, @RequestBody(required = false) CloseRequest body) {
        return ResponseEntity.ok(service.close(fy, body != null && body.overrideWarnings(), LocalDate.now()));
    }

    @PostMapping("/{fy}/reopen")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<FiscalYearDTO> reopen(@PathVariable int fy, @RequestBody ReopenRequest body) {
        return ResponseEntity.ok(service.reopen(fy, body == null ? null : body.reason(), LocalDate.now()));
    }
}
