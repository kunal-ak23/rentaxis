package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.ledger.JournalEntryDTO;
import com.datagami.rentaxis.api.dto.ledger.ManualJournalRequest;
import com.datagami.rentaxis.api.dto.ledger.ReverseRequest;
import com.datagami.rentaxis.core.service.ledger.JournalService;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * The journal (spec §5). Entries are immutable once posted, so there is no PUT
 * and no DELETE here: a mistake is corrected by posting the reversal.
 */
@RestController
@RequestMapping("/api/v1/finance/journals")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
public class JournalController {

    /** Ceiling on the page size a caller can ask for; the journal is the biggest table in the ledger. */
    private static final int MAX_PAGE_SIZE = 200;

    private final JournalService service;

    public JournalController(JournalService service) { this.service = service; }

    @GetMapping
    public ResponseEntity<Page<JournalEntryDTO>> list(
            @RequestParam(required = false) JournalDocType docType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID propertyId,
            @RequestParam(required = false) UUID leaseId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(service.search(docType, from, to, propertyId, leaseId,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE))));
    }

    @GetMapping("/doc-types")
    public ResponseEntity<List<String>> docTypes() {
        return ResponseEntity.ok(Arrays.stream(JournalDocType.values()).map(Enum::name).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<JournalEntryDTO> get(@PathVariable UUID id) {
        return ResponseEntity.ok(service.get(id));
    }

    @PostMapping
    public ResponseEntity<JournalEntryDTO> postManual(@RequestBody ManualJournalRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.postManual(request));
    }

    @PostMapping("/{id}/reverse")
    public ResponseEntity<JournalEntryDTO> reverse(@PathVariable UUID id, @RequestBody(required = false) ReverseRequest request) {
        return ResponseEntity.ok(service.reverse(id, request));
    }
}
