package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.email.event.payload.RentReceiptPayload;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.core.util.ImageTypes;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.OnlinePayment;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.OnlinePaymentStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.OnlinePaymentRepository;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The receipt for a collected instalment (spec §9.3).
 *
 * <p><b>A receipt is a cleared register row rendered as a PDF.</b> There is no
 * receipt entity and no receipt table: the cheque's own id gives the receipt its
 * number, its {@code clearedAt} gives the number its period, and the {@code CRT}
 * behind it is the entry an accountant reconciles the paper against. Which is why
 * only a CLEARED row may be rendered — a receipt for money that has not landed is
 * the one document a landlord must not be able to hand out.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RentReceiptService {

    private final ChequeRepository chequeRepository;
    private final LandlordOrgRepository landlordOrgRepository;
    private final OnlinePaymentRepository onlinePaymentRepository;
    private final LeaseAccessPolicy leaseAccessPolicy;
    private final ApplicationEventPublisher events;
    private final BlobStorageService blobStorageService;

    /**
     * The largest logo inlined into a receipt: anything bigger is left off rather
     * than base64-inflated into every PDF. The upload form caps logos at 2 MB, but
     * a receipt logo is 40 px tall and 1 MB is already generous.
     */
    static final long MAX_LOGO_BYTES = 1024L * 1024;

    /**
     * @param chequeId a CLEARED row on a lease the caller may read. A renter passes
     *        for their own tenancy and for nobody else's — {@code requireReadable}
     *        answers "not found" rather than "forbidden", so a receipt id cannot be
     *        used to enumerate a landlord's collections.
     */
    @Transactional(readOnly = true)
    public byte[] generateReceipt(UUID chequeId) {
        Cheque cheque = chequeRepository.findById(chequeId)
                .orElseThrow(() -> new NotFoundException("Payment not found"));

        Lease lease = cheque.getLease();
        // Before the status check: "this cleared" and "this did not" are both facts
        // about someone else's tenancy when the caller is not entitled to the lease.
        leaseAccessPolicy.requireReadable(lease);

        if (cheque.getStatus() != ChequeStatus.CLEARED) {
            throw new BusinessRuleViolationException("Receipt can only be generated for cleared payments");
        }

        Unit unit = cheque.getUnit();
        Property property = cheque.getProperty();
        Renter renter = cheque.getRenter();

        UUID tenantId = TenantContextHolder.getTenantId();
        LandlordOrg org = tenantId != null ? landlordOrgRepository.findById(tenantId).orElse(null) : null;

        String template;
        try {
            ClassPathResource resource = new ClassPathResource("templates/receipt-template.html");
            template = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load receipt template", e);
        }

        NumberFormat nf = NumberFormat.getNumberInstance(Locale.US);
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        String formattedAmount = nf.format(cheque.getAmount());

        // RR-YYYY-MM-SHORT_ID, dated by when the money landed rather than by when
        // the PDF was asked for: two downloads of one receipt are one receipt.
        LocalDate clearedAt = cheque.getClearedAt() != null ? cheque.getClearedAt() : cheque.getChequeDate();
        String receiptNumber = "RR-" + String.format("%d-%02d", clearedAt.getYear(), clearedAt.getMonthValue())
                + "-" + cheque.getId().toString().substring(0, 8).toUpperCase();

        DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("dd MMM yyyy");

        String html = template
                .replace("{{ORG_LOGO}}", logoImg(org, tenantId))
                .replace("{{ORG_NAME}}", org != null ? safe(org.getName()) : "Property Management")
                .replace("{{ORG_ADDRESS}}", org != null && org.getAddress() != null ? safe(org.getAddress()) : "")
                .replace("{{ORG_TRN}}", org != null && org.getTrn() != null ? "TRN: " + safe(org.getTrn()) : "")
                .replace("{{RECEIPT_NUMBER}}", receiptNumber)
                .replace("{{RECEIPT_DATE}}", clearedAt.format(dateFmt))
                .replace("{{AMOUNT}}", formattedAmount)
                .replace("{{PROPERTY_NAME}}", property != null ? safe(property.getNameEn()) : "")
                .replace("{{UNIT_NUMBER}}", unit != null ? safe(unit.getUnitNumber()) : "")
                .replace("{{PROPERTY_ADDRESS}}", propertyAddress(property))
                .replace("{{RENTER_NAME}}", renter != null ? safe(renter.getNameEn()) : "")
                .replace("{{RENTER_EMAIL}}", renter != null && renter.getEmail() != null ? safe(renter.getEmail()) : "N/A")
                .replace("{{RENTER_PHONE}}", renter != null && renter.getPhone() != null ? safe(renter.getPhone()) : "N/A")
                .replace("{{INSTALLMENT_NUMBER}}", String.valueOf(cheque.getSeqNo()))
                .replace("{{DUE_DATE}}", cheque.getChequeDate().format(dateFmt))
                .replace("{{PAYMENT_METHOD}}", cheque.getMode().name())
                .replace("{{PARTICULARS}}", cheque.getNarration() != null ? safe(cheque.getNarration()) : "N/A")
                .replace("{{CHEQUE_NUMBER}}", cheque.getChequeNumber() != null ? safe(cheque.getChequeNumber()) : "N/A")
                .replace("{{BANK_NAME}}", cheque.getPayeeBank() != null ? safe(cheque.getPayeeBank()) : "N/A")
                .replace("{{ONLINE_PAYMENT_ID}}", safe(gatewayReference(chequeId)))
                .replace("{{GENERATED_AT}}", java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm")));

        byte[] pdfBytes = renderPdf(html);

        // NOTE: pdfBase64 can be large (typical receipt ~200–500 KB base64-encoded).
        // If body_html storage becomes a concern, replace pdfBase64 with a signed URL
        // and update RentReceiptPayload accordingly.
        String receiptFileName = "receipt-" + receiptNumber + ".pdf";
        events.publishEvent(new EmailEvent(this,
                EmailEventType.RENT_RECEIPT_AVAILABLE,
                tenantId,
                RentReceiptPayload.ofCheque(cheque, formattedAmount + " AED",
                        Base64.getEncoder().encodeToString(pdfBytes), receiptFileName),
                "RENT_RECEIPT_AVAILABLE:" + chequeId));

        return pdfBytes;
    }

    private static String propertyAddress(Property property) {
        if (property == null) {
            return "";
        }
        if (property.getAddress() != null) {
            return property.getAddress().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
        return property.getEmirate() != null ? property.getEmirate().name().replace('_', ' ') : "";
    }

    /** The gateway's own payment reference, when this row was collected online. */
    private String gatewayReference(UUID chequeId) {
        try {
            List<OnlinePayment> payments = onlinePaymentRepository.findByCheque_Id(chequeId);
            return payments.stream()
                    .filter(op -> op.getStatus() == OnlinePaymentStatus.CAPTURED)
                    .findFirst()
                    .map(op -> op.getGatewayPaymentId() != null ? op.getGatewayPaymentId() : "N/A")
                    .orElse("N/A");
        } catch (Exception e) {
            return "N/A";
        }
    }

    /**
     * The org logo as an inline {@code data:} image, or nothing.
     *
     * <p>The logo URL is free text on the org, and it used to be dropped raw into
     * {@code <img src="...">} for the renderer to fetch: a server-side request to
     * any host (cloud metadata included) or a {@code file:} read, and an attribute
     * break-out on a {@code "}. Now the renderer loads nothing but {@code data:}
     * URIs ({@link com.datagami.rentaxis.core.util.PdfResourcePolicy}); an uploaded
     * logo is read through the storage SDK — only from this account's
     * {@code shared} container or this tenant's own — and inlined.</p>
     */
    public String logoImg(LandlordOrg org, UUID tenantId) {
        String url = org != null ? org.getLogoUrl() : null;
        if (url == null || url.isBlank()) {
            return "";
        }
        String src;
        if (url.strip().regionMatches(true, 0, "data:image/", 0, 11)) {
            src = url.strip();
        } else {
            // The stored content type is ignored: uploads have been stored as
            // application/octet-stream, and it is the uploader's claim anyway. The
            // bytes decide, and the data: URI carries the type they prove.
            src = blobStorageService.downloadOwnedUrl(tenantId, url.strip(), MAX_LOGO_BYTES)
                    .filter(d -> d.bytes() != null && d.bytes().length <= MAX_LOGO_BYTES)
                    .flatMap(d -> ImageTypes.sniff(d.bytes())
                            .map(type -> "data:" + type + ";base64," + Base64.getEncoder().encodeToString(d.bytes())))
                    .orElse(null);
        }
        if (src == null) {
            return "";
        }
        return "<img src=\"" + HtmlUtils.htmlEscape(src, "UTF-8")
                + "\" style=\"height: 40px; margin-bottom: 8px;\" />";
    }

    private String safe(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private byte[] renderPdf(String html) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();

            try {
                ClassPathResource arabicFont = new ClassPathResource("fonts/NotoSansArabic.ttf");
                ClassPathResource latinFont = new ClassPathResource("fonts/NotoSans.ttf");
                builder.useFont(() -> {
                    try { return arabicFont.getInputStream(); } catch (IOException ex) { throw new UncheckedIOException(ex); }
                }, "Noto Sans Arabic");
                builder.useFont(() -> {
                    try { return latinFont.getInputStream(); } catch (IOException ex) { throw new UncheckedIOException(ex); }
                }, "Noto Sans");
            } catch (Exception e) {
                log.warn("Could not load custom fonts: {}", e.getMessage());
            }

            // Only inline data: URIs load; no http(s), no file:, no jar:.
            com.datagami.rentaxis.core.util.PdfResourcePolicy.apply(builder);
            builder.withHtmlContent(html, null);
            builder.toStream(baos);
            builder.run();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate receipt PDF", e);
        }
    }
}
