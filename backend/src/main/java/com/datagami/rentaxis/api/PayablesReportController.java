package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.payables.OpenItemDTO;
import com.datagami.rentaxis.api.dto.payables.PayablesAgingDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.security.PropertyScope;
import com.datagami.rentaxis.core.service.payables.PayablesService;
import com.datagami.rentaxis.core.service.report.statement.ReportCsv;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Finance → Payables → Aging (finance-ops spec §2 "Aging definition").
 *
 * <p>SUPER_ADMIN, TENANT_ADMIN and ACCOUNTANT see every vendor. A
 * PROPERTY_MANAGER gets the property-filtered view, read-only, for an assigned
 * property only: {@code propertyId} is required ({@code
 * requirePropertyNamedByManager}) and a property outside the assignment is a 404.
 * The property-filtered view carries no vendor-level figures.</p>
 */
@RestController
@RequestMapping("/api/v1/finance/reports")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT','PROPERTY_MANAGER')")
public class PayablesReportController {

    private static final MediaType CSV = MediaType.parseMediaType("text/csv; charset=UTF-8");

    private final PayablesService payables;
    private final PropertyScope propertyScope;

    public PayablesReportController(PayablesService payables, PropertyScope propertyScope) {
        this.payables = payables;
        this.propertyScope = propertyScope;
    }

    @GetMapping("/payables-aging")
    public PayablesAgingDTO aging(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) UUID propertyId,
            @RequestParam(required = false) UUID vendorId) {
        return scopedAging(asOf, propertyId, vendorId);
    }

    @GetMapping("/payables-aging.csv")
    public ResponseEntity<byte[]> agingCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) UUID propertyId,
            @RequestParam(required = false) UUID vendorId,
            @RequestParam(defaultValue = "en") String lang) {
        PayablesAgingDTO r = scopedAging(asOf, propertyId, vendorId);
        boolean ar = "ar".equals(lang);
        List<List<String>> out = new ArrayList<>();
        List<String> head = new ArrayList<>(List.of(ar ? "المورد" : "Vendor", ar ? "المستند" : "Doc",
                ar ? "الفاتورة" : "Invoice", ar ? "تاريخ الاستحقاق" : "Due date", ar ? "أيام التأخير" : "Days overdue",
                ar ? "جاري" : "Current", "1-30", "31-60", "61-90", "90+"));
        if (r.vendorLevel()) {
            head.addAll(List.of(ar ? "سلف غير مخصصة" : "Unallocated advances", ar ? "إجمالي المفتوح" : "Open total",
                    ar ? "رصيد الدفتر" : "Ledger balance", "Δ"));
        } else {
            head.add(ar ? "إجمالي المفتوح" : "Open total");
        }
        out.add(head);
        for (PayablesAgingDTO.VendorRow row : r.rows()) {
            String name = ar && row.vendorNameAr() != null && !row.vendorNameAr().isBlank() ? row.vendorNameAr() : row.vendorName();
            for (OpenItemDTO i : row.items()) {
                List<String> line = new ArrayList<>(List.of(nz(name), nz(i.docNumber()), nz(i.invoiceNumber()),
                        i.dueDate().toString(), String.valueOf(i.daysOverdue())));
                for (PayablesService.Bucket b : PayablesService.Bucket.values()) {
                    line.add(b.name().equals(i.bucket()) ? i.open().toPlainString() : "");
                }
                out.add(line);
            }
            out.add(figures(nz(name), row.figures(), r.vendorLevel()));
        }
        out.add(figures(ar ? "الإجمالي" : "Total", r.totals(), r.vendorLevel()));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"payables-aging-" + r.asOf() + ".csv\"")
                .contentType(CSV)
                .body(ReportCsv.encode(out));
    }

    private PayablesAgingDTO scopedAging(LocalDate asOf, UUID propertyId, UUID vendorId) {
        if (TenantContextHolder.getTenantId() == null) throw new BusinessRuleViolationException("Select an organisation first");
        propertyScope.requirePropertyNamedByManager(propertyId);
        if (propertyId != null) propertyScope.requireCanAccessProperty(propertyId);
        return payables.aging(asOf, vendorId, propertyId);
    }

    private static List<String> figures(String label, PayablesAgingDTO.Figures f, boolean vendorLevel) {
        List<String> line = new ArrayList<>(List.of(label, "", "", "", "",
                p(f.current()), p(f.d1to30()), p(f.d31to60()), p(f.d61to90()), p(f.d90plus())));
        if (vendorLevel) {
            line.addAll(List.of(p(f.advances()), p(f.openTotal()), p(f.ledgerBalance()), p(f.delta())));
        } else {
            line.add(p(f.openTotal()));
        }
        return line;
    }

    private static String p(BigDecimal v) { return v == null ? "" : v.toPlainString(); }

    private static String nz(String s) { return s == null ? "" : s; }
}
