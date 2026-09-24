package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.payables.AdvanceDTO;
import com.datagami.rentaxis.api.dto.payables.OpenItemDTO;
import com.datagami.rentaxis.api.dto.voucher.*;
import com.datagami.rentaxis.core.service.payables.PayablesService;
import com.datagami.rentaxis.core.service.voucher.VoucherAllocationService;
import com.datagami.rentaxis.domain.entity.VoucherAllocation;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.voucher.VoucherAttachmentService;
import com.datagami.rentaxis.core.service.voucher.VoucherService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Voucher;
import com.datagami.rentaxis.domain.entity.enums.VoucherStatus;
import com.datagami.rentaxis.domain.entity.enums.VoucherType;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The voucher HTTP API for Purchase/Service Invoices (PISR) and Bank/Cash Payment
 * Vouchers (BPV) — spec §10.1, §11. Thin by design: every business rule lives in
 * {@link VoucherService} (draft validation, posting, amendment) or
 * {@link VoucherAttachmentService} (attachment storage); this class only maps a
 * DTO to the service's input record, calls it, and maps the result back.
 *
 * <p><b>Role gate.</b> One {@code @PreAuthorize} at the class level covers every
 * handler below, including the attachment sub-resources: SUPER_ADMIN, TENANT_ADMIN
 * and ACCOUNTANT may touch vouchers; PROPERTY_MANAGER, TENANT_USER and RENTER get
 * a 403 before any service is called.
 *
 * <p><b>The tenant guard.</b> {@code ApiSecurityFilter} authorises a SUPER_ADMIN
 * unconditionally but only populates {@code TenantContextHolder} when the caller
 * has selected an organisation, so a platform admin with none selected would
 * otherwise reach a service that assumes an ambient tenant and fail as an opaque
 * 500. {@link #requireTenantSelected()} — the same guard and wording as
 * {@code RecognitionController} — turns that into a clean 400 at the top of every
 * handler (issue #289 tracks extracting this as a shared filter/interceptor; not
 * done here, to keep this task's diff to the voucher feature).
 */
@RestController
@RequestMapping("/api/v1/finance/vouchers")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT')")
public class VoucherController {

    /** Same wording as {@code RecognitionController.NO_TENANT} — one sentence, said consistently. */
    static final String NO_TENANT = "Select an organisation first";

    /** Ceiling on the page size a caller can ask for, same cap as {@code JournalController}. */
    private static final int MAX_PAGE_SIZE = 200;

    private final VoucherService vouchers;
    private final VoucherAttachmentService attachments;
    private final VoucherAllocationService allocationService;
    private final PayablesService payables;

    @GetMapping
    public ResponseEntity<Page<VoucherDTO>> list(
            @RequestParam(required = false) VoucherType docType,
            @RequestParam(required = false) VoucherStatus status,
            @RequestParam(required = false) UUID vendorId,
            @RequestParam(required = false) UUID propertyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        requireTenantSelected();
        Page<Voucher> result = vouchers.list(docType, status, vendorId, propertyId, from, to,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE)));
        return ResponseEntity.ok(result.map(VoucherDTO::of));
    }

    @GetMapping("/{id}")
    public ResponseEntity<VoucherDetailDTO> get(@PathVariable UUID id) {
        requireTenantSelected();
        return ResponseEntity.ok(detail(vouchers.get(id)));
    }

    @PostMapping
    public ResponseEntity<VoucherDetailDTO> create(@Valid @RequestBody VoucherInputDTO body) {
        requireTenantSelected();
        return ResponseEntity.status(HttpStatus.CREATED).body(detail(vouchers.createDraft(toInput(body))));
    }

    @PutMapping("/{id}")
    public ResponseEntity<VoucherDetailDTO> update(@PathVariable UUID id, @Valid @RequestBody VoucherInputDTO body) {
        requireTenantSelected();
        return ResponseEntity.ok(detail(vouchers.updateDraft(id, toInput(body))));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        requireTenantSelected();
        vouchers.deleteDraft(id);
        return ResponseEntity.noContent().build();
    }

    /** Body optional: a BPV may name the invoices it settles (finance-ops spec §2). */
    @PostMapping("/{id}/post")
    public ResponseEntity<VoucherDetailDTO> post(@PathVariable UUID id,
                                                 @Valid @RequestBody(required = false) PostVoucherDTO body) {
        requireTenantSelected();
        return ResponseEntity.ok(detail(vouchers.post(id, allocations(body == null ? null : body.allocations()), null,
                body == null ? VoucherService.PostOptions.NONE
                        : VoucherService.PostOptions.of(body.notOnStatement(), body.allowNegativeCash()))));
    }

    @PostMapping("/{id}/amend")
    public ResponseEntity<VoucherDetailDTO> amend(@PathVariable UUID id, @Valid @RequestBody AmendVoucherDTO body) {
        requireTenantSelected();
        return ResponseEntity.ok(detail(vouchers.amend(id, body.reversalDate(), body.reason(),
                toInput(body.replacement()),
                // Absent: the replacement payment carries the original's allocations.
                body.allocations() == null ? null : allocations(body.allocations()),
                VoucherService.PostOptions.of(body.notOnStatement(), body.allowNegativeCash()))));
    }

    /** Spec §2: PISRs and opening items with what is still owed on them, now. */
    /** F14-42: reverse a posted voucher with a reason and mark it VOID. */
    @PostMapping("/{id}/void")
    public ResponseEntity<VoucherDetailDTO> voidVoucher(@PathVariable UUID id,
                                                        @Valid @RequestBody com.datagami.rentaxis.api.dto.voucher.VoidVoucherDTO body) {
        requireTenantSelected();
        return ResponseEntity.ok(detail(vouchers.voidVoucher(id, body.date(), body.reason())));
    }

        @GetMapping("/open-items")
    public ResponseEntity<List<OpenItemDTO>> openItems(
            @RequestParam(required = false) UUID vendorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueBefore,
            @RequestParam(required = false) UUID propertyId,
            @RequestParam(defaultValue = "true") boolean includePartPaid) {
        requireTenantSelected();
        return ResponseEntity.ok(payables.openItemsNow(vendorId, dueBefore, propertyId, includePartPaid));
    }

    /** Posted payments with an unallocated part (advances), now. */
    @GetMapping("/advances")
    public ResponseEntity<List<AdvanceDTO>> advances(@RequestParam(required = false) UUID vendorId) {
        requireTenantSelected();
        return ResponseEntity.ok(payables.advancesNow(vendorId));
    }

    /** The posted voucher an invoice number would duplicate for this vendor, if any (the form's inline check). */
    @GetMapping("/duplicate-check")
    public ResponseEntity<Map<String, String>> duplicateCheck(@RequestParam UUID vendorId,
                                                              @RequestParam String invoiceNumber,
                                                              @RequestParam(required = false) UUID excludeId) {
        requireTenantSelected();
        String existing = vouchers.postedDuplicateOf(vendorId, invoiceNumber, excludeId);
        Map<String, String> out = new java.util.HashMap<>();
        out.put("duplicateOf", existing);
        return ResponseEntity.ok(out);
    }

    /** Allocations on either side of this voucher, live and released. */
    @GetMapping("/{id}/allocations")
    public ResponseEntity<List<AllocationDTO>> allocationsOf(@PathVariable UUID id) {
        requireTenantSelected();
        vouchers.get(id);   // 404 for an id outside the tenant
        return ResponseEntity.ok(allocationDtos(allocationService.ofVoucher(id)));
    }

    @PostMapping(path = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<VoucherAttachmentDTO> upload(@PathVariable UUID id,
                                                       @RequestParam("name") String name,
                                                       @RequestParam("file") MultipartFile file) throws IOException {
        requireTenantSelected();
        return ResponseEntity.status(HttpStatus.CREATED).body(attachments.upload(id, name, file));
    }

    @GetMapping("/{id}/attachments")
    public ResponseEntity<List<VoucherAttachmentDTO>> listAttachments(@PathVariable UUID id) {
        requireTenantSelected();
        return ResponseEntity.ok(attachments.list(id));
    }

    @GetMapping("/attachments/{attachmentId}/download")
    public ResponseEntity<Resource> download(@PathVariable UUID attachmentId) throws IOException {
        requireTenantSelected();
        VoucherAttachmentDTO meta = attachments.get(attachmentId);
        String contentType = meta.fileType() == null ? "application/octet-stream" : meta.fileType();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(meta.name()))
                .contentType(MediaType.parseMediaType(contentType))
                .body(new InputStreamResource(attachments.download(attachmentId)));
    }

    /**
     * A safe {@code Content-Disposition} for an attachment's display name, which is
     * free text a clerk typed and never trusted verbatim: an unescaped {@code "} or
     * a raw CR/LF could inject extra header fields or corrupt the response line.
     * Two filename params, per RFC 6266/5987 — {@code filename} is an ASCII-safe
     * fallback (control characters and filesystem/header-special characters
     * stripped) for a client that ignores {@code filename*}; {@code filename*} is
     * percent-encoded UTF-8 of the real name, so a non-ASCII invoice name (Arabic,
     * accented Latin) round-trips correctly in a client that honours it.
     */
    private static String contentDisposition(String rawName) {
        String name = rawName == null ? "attachment" : rawName;
        // An HTTP header value has to be US-ASCII (RFC 7230); a raw non-ASCII
        // character surviving into this fallback param does not throw here, but
        // silently produces a header the server or client drops entirely — which
        // is how this shipped with a null Content-Disposition on any non-ASCII
        // name before this fix (Task 5 fix round 1). Everything outside printable
        // ASCII is replaced, on top of the filesystem/header-special characters.
        String asciiSafe = name.replaceAll("[\"\\r\\n\\\\/:*?<>|]", "_").replaceAll("[^\\x20-\\x7E]", "_");
        String encoded = java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        return "attachment; filename=\"" + asciiSafe + "\"; filename*=UTF-8''" + encoded;
    }

    @DeleteMapping("/attachments/{attachmentId}")
    public ResponseEntity<Void> deleteAttachment(@PathVariable UUID attachmentId) {
        requireTenantSelected();
        attachments.delete(attachmentId);
        return ResponseEntity.noContent().build();
    }

    private VoucherDetailDTO detail(Voucher v) {
        return VoucherDetailDTO.of(v, attachments.list(v.getId()), settlement(v));
    }

    /** Derived from live allocations; null on a draft. */
    private VoucherDetailDTO.Settlement settlement(Voucher v) {
        if (v.getStatus() == VoucherStatus.DRAFT) return null;
        return switch (v.getDocType()) {
            case PISR -> VoucherDetailDTO.Settlement.ofInvoice(
                    allocationService.grossOf(v.getId(), null), allocationService.liveOnInvoice(v.getId(), null));
            case BPV -> VoucherDetailDTO.Settlement.ofPayment(
                    allocationService.payableAmount(v.getId()), allocationService.liveOnPayment(v.getId()));
            default -> null;
        };
    }

    private List<AllocationDTO> allocationDtos(List<VoucherAllocation> rows) {
        java.util.Map<UUID, Voucher> cache = new java.util.HashMap<>();
        java.util.function.Function<UUID, Voucher> load = id -> id == null ? null
                : cache.computeIfAbsent(id, k -> vouchers.get(k));
        return rows.stream().map(a -> {
            Voucher pay = load.apply(a.getPaymentVoucherId());
            Voucher inv = load.apply(a.getInvoiceVoucherId());
            String invoiceNumber = inv != null ? inv.getInvoiceNumber() : payables.openingItemNumber(a.getOpeningItemId());
            return AllocationDTO.of(a, pay == null ? null : pay.getVoucherNumber(), invoiceNumber,
                    inv == null ? null : inv.getVoucherNumber());
        }).toList();
    }

    private static List<VoucherAllocationService.AllocationInput> allocations(List<AllocationInputDTO> in) {
        if (in == null) return List.of();
        return in.stream().map(a -> new VoucherAllocationService.AllocationInput(a.invoiceId(), a.openingItemId(),
                a.amount())).toList();
    }

    private VoucherService.VoucherInput toInput(VoucherInputDTO d) {
        return new VoucherService.VoucherInput(d.docType(), d.docDate(), d.vendorId(), d.invoiceNumber(),
                d.narration(), d.propertyId(), d.unitId(), d.paymentAccountId(), d.chequeNumber(), d.chequeDate(),
                d.lines().stream().map(l -> new VoucherService.VoucherLineInput(
                        l.accountId(), l.description(), l.amount(), l.vatRate(), l.propertyId(), l.unitId(),
                        Boolean.TRUE.equals(l.shared()))).toList(),
                d.supplierInvoiceDate(), d.dueDate(), d.paymentMethod(), d.paymentReference(), d.settlementId());
    }

    /** A voucher needs an organisation to belong to; see the class Javadoc. */
    private void requireTenantSelected() {
        if (TenantContextHolder.getTenantId() == null) {
            throw new BusinessRuleViolationException(NO_TENANT);
        }
    }
}
