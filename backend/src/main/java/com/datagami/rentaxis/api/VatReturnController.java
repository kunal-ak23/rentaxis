package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.vat.VatReturnDTO;
import com.datagami.rentaxis.core.service.vat.VatReturnExport;
import com.datagami.rentaxis.core.service.vat.VatReturnService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import java.util.List;
import java.util.UUID;

/**
 * #55: the quarterly VAT return. Tenant-wide, so finance roles only; marking a
 * period filed (and re-opening one) is for an organisation admin.
 */
@RestController
@RequestMapping("/api/v1/finance/vat-returns")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
public class VatReturnController {

    private static final MediaType CSV = MediaType.parseMediaType("text/csv; charset=UTF-8");

    private final VatReturnService service;

    public VatReturnController(VatReturnService service) {
        this.service = service;
    }

    public record FileRequest(LocalDate periodStart, String filingReference) { }

    public record ReopenRequest(String reason) { }

    @GetMapping
    public VatReturnDTO get(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart) {
        return service.get(periodStart);
    }

    @GetMapping("/filings")
    public List<VatReturnDTO.Filing> filings() {
        return service.filings();
    }

    @GetMapping("/documents")
    public List<VatReturnDTO.Document> documents(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam String box) {
        return service.documents(periodStart, box);
    }

    @PostMapping("/file")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public VatReturnDTO file(@RequestBody FileRequest r) {
        return service.file(r.periodStart(), r.filingReference());
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public VatReturnDTO reopen(@PathVariable UUID id, @RequestBody ReopenRequest r) {
        return service.reopen(id, r == null ? null : r.reason());
    }

    @GetMapping("/return.pdf")
    public ResponseEntity<byte[]> pdf(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
                                      @RequestParam(defaultValue = "en") String lang) {
        return file(VatReturnExport.pdf(service.get(periodStart), lang), MediaType.APPLICATION_PDF,
                "vat-return-" + periodStart + "-" + ("ar".equals(lang) ? "ar" : "en") + ".pdf");
    }

    @GetMapping("/return.csv")
    public ResponseEntity<byte[]> csv(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
                                      @RequestParam(defaultValue = "en") String lang) {
        return file(VatReturnExport.csv(service.get(periodStart), lang), CSV, "vat-return-" + periodStart + ".csv");
    }

    private static ResponseEntity<byte[]> file(byte[] body, MediaType type, String name) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(type).body(body);
    }
}
