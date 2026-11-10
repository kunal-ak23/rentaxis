package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.email.event.payload.RentReceiptPayload;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.BookingRequest;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.OnlinePayment;
import com.datagami.rentaxis.domain.entity.PenaltyAssessment;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.BookingResourceType;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.OnlinePaymentStatus;
import com.datagami.rentaxis.domain.repository.BookingRequestRepository;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.OnlinePaymentRepository;
import com.datagami.rentaxis.domain.repository.PenaltyAssessmentRepository;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
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
    private final PenaltyAssessmentRepository penaltyAssessments;
    private final BookingRequestRepository bookings;

    /** The most receipts one combined PDF may hold. */
    static final int MAX_COMBINED = 100;

    /**
     * The largest logo inlined into a receipt: anything bigger is left off rather
     * than base64-inflated into every PDF. The upload form's own 2 MB cap, so a logo
     * the form accepted is never silently missing from the receipt.
     */
    static final long MAX_LOGO_BYTES = OrgBrandImages.MAX_BYTES;

    /**
     * @param chequeId a CLEARED row on a lease the caller may read. A renter passes
     *        for their own tenancy and for nobody else's — {@code requireReadable}
     *        answers "not found" rather than "forbidden", so a receipt id cannot be
     *        used to enumerate a landlord's collections.
     */
    @Transactional(readOnly = true)
    public byte[] generateReceipt(UUID chequeId) {
        return receipt(chequeId).pdf();
    }

    /** F14-62: the PDF and the name to download it under. */
    public record Receipt(byte[] pdf, String fileName) {}

    /**
     * F14-62: {@code receipt-RR-26-7.pdf} for a receipt numbered from the RR series;
     * a row cleared before the series existed keeps the old {@code receipt-<id8>.pdf}.
     */
    static String receiptFileName(Cheque cheque) {
        return cheque.getReceiptNumber() != null
                ? "receipt-" + cheque.getReceiptNumber().replaceAll("[^A-Za-z0-9-]", "-") + ".pdf"
                : "receipt-" + cheque.getId().toString().substring(0, 8) + ".pdf";
    }

    /** The PDF of {@link #generateReceipt} and its download name. */
    @Transactional(readOnly = true)
    public Receipt receipt(UUID chequeId) {
        Cheque cheque = receiptable(chequeId);
        UUID tenantId = TenantContextHolder.getTenantId();
        LandlordOrg org = tenantId != null ? landlordOrgRepository.findById(tenantId).orElse(null) : null;

        byte[] pdfBytes = renderPdf(html(cheque, org, tenantId, template()));

        // NOTE: pdfBase64 can be large (typical receipt ~200–500 KB base64-encoded).
        // If body_html storage becomes a concern, replace pdfBase64 with a signed URL
        // and update RentReceiptPayload accordingly.
        String receiptFileName = receiptFileName(cheque);
        events.publishEvent(new EmailEvent(this,
                EmailEventType.RENT_RECEIPT_AVAILABLE,
                tenantId,
                RentReceiptPayload.ofCheque(cheque, money(cheque.getAmount()) + " AED",
                        Base64.getEncoder().encodeToString(pdfBytes), receiptFileName),
                "RENT_RECEIPT_AVAILABLE:" + chequeId));

        return new Receipt(pdfBytes, receiptFileName);
    }

    /**
     * Several receipts of one contract in one PDF, a page each, in the order the
     * money landed.
     *
     * <p>Every row passes the same checks as a single receipt. One contract only:
     * the PDF is handed to one renter, and the caller's right to read is decided per
     * lease. No receipt e-mail is sent — each receipt was mailed when it was first
     * downloaded, and this is a printout of them.</p>
     */
    @Transactional(readOnly = true)
    public Receipt combined(List<UUID> chequeIds) {
        List<UUID> ids = chequeIds == null ? List.of()
                : new ArrayList<>(new LinkedHashSet<>(chequeIds.stream().filter(java.util.Objects::nonNull).toList()));
        if (ids.isEmpty()) {
            throw new BusinessRuleViolationException("Choose at least one receipt");
        }
        if (ids.size() > MAX_COMBINED) {
            throw new BusinessRuleViolationException("A combined receipt holds at most " + MAX_COMBINED + " receipts");
        }
        List<Cheque> rows = new ArrayList<>();
        for (UUID id : ids) rows.add(receiptable(id));
        Lease lease = rows.get(0).getLease();
        if (rows.stream().anyMatch(c -> !c.getLease().getId().equals(lease.getId()))) {
            throw new BusinessRuleViolationException("A combined receipt holds the receipts of one contract");
        }
        rows.sort(Comparator.comparing(RentReceiptService::receivedOn)
                .thenComparingInt(Cheque::getSeqNo));

        UUID tenantId = TenantContextHolder.getTenantId();
        LandlordOrg org = tenantId != null ? landlordOrgRepository.findById(tenantId).orElse(null) : null;
        String template = template();
        List<byte[]> pages = new ArrayList<>();
        for (Cheque c : rows) pages.add(renderPdf(html(c, org, tenantId, template)));

        String ref = lease.getExternalContractRef() != null && !lease.getExternalContractRef().isBlank()
                ? lease.getExternalContractRef() : lease.getId().toString().substring(0, 8);
        return new Receipt(merge(pages), "receipts-" + ref.replaceAll("[^A-Za-z0-9-]", "-") + ".pdf");
    }

    /** A row the caller may have a receipt for: readable, ours, and CLEARED. */
    private Cheque receiptable(UUID chequeId) {
        Cheque cheque = chequeRepository.findById(chequeId)
                .orElseThrow(() -> new NotFoundException("Payment not found"));

        Lease lease = cheque.getLease();
        // Before the status check: "this cleared" and "this did not" are both facts
        // about someone else's tenancy when the caller is not entitled to the lease.
        leaseAccessPolicy.requireReadable(lease);
        // PR #359 R1: after an assignment the rows are split by renter; each sees their own.
        leaseAccessPolicy.requireRentersOwnCheque(cheque);
        // S16-14 (#376 P2-1): no receipt in our name for money the previous owner received.
        com.datagami.rentaxis.core.service.cheque.ChequeService.requireOurs(cheque, lease);

        if (cheque.getStatus() != ChequeStatus.CLEARED) {
            throw new BusinessRuleViolationException("Receipt can only be generated for cleared payments");
        }
        return cheque;
    }

    private static LocalDate receivedOn(Cheque cheque) {
        return cheque.getClearedAt() != null ? cheque.getClearedAt() : cheque.getChequeDate();
    }

    private static String template() {
        try {
            ClassPathResource resource = new ClassPathResource("templates/receipt-template.html");
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load receipt template", e);
        }
    }

    private static String money(java.math.BigDecimal amount) {
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.US);
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        return nf.format(amount);
    }

    /** One receipt's page. */
    private String html(Cheque cheque, LandlordOrg org, UUID tenantId, String template) {
        Unit unit = cheque.getUnit();
        Property property = cheque.getProperty();
        Renter renter = cheque.getRenter();

        // RR-YYYY-MM-SHORT_ID, dated by when the money landed rather than by when
        // the PDF was asked for: two downloads of one receipt are one receipt.
        LocalDate clearedAt = receivedOn(cheque);
        // F14-24: the sequence number given at clearing; rows cleared before it keep the old form.
        String receiptNumber = cheque.getReceiptNumber() != null ? cheque.getReceiptNumber()
                : "RR-" + String.format("%d-%02d", clearedAt.getYear(), clearedAt.getMonthValue())
                        + "-" + cheque.getId().toString().substring(0, 8).toUpperCase();

        DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("dd MMM yyyy");

        // A charge's row (a booking fee, a fine, a recharge) says what was charged,
        // not the "Penalty - ..." label its collection row carries; a booking also
        // says which booking.
        PenaltyAssessment charge = cheque.getPenaltyAssessmentId() == null ? null
                : penaltyAssessments.findById(cheque.getPenaltyAssessmentId()).orElse(null);
        BookingRequest booking = charge != null && BookingFeeService.SOURCE.equals(charge.getSourceType())
                && charge.getSourceId() != null ? bookings.findById(charge.getSourceId()).orElse(null) : null;
        String title = booking != null ? "BOOKING RECEIPT" : charge != null ? "RECEIPT" : "RENT RECEIPT";
        String particulars = charge != null && charge.getDescription() != null && !charge.getDescription().isBlank()
                ? charge.getDescription() : cheque.getNarration();

        return template
                .replace("{{ORG_LOGO}}", logoImg(org, tenantId))
                .replace("{{ORG_NAME}}", org != null ? safe(org.getName()) : "Property Management")
                .replace("{{ORG_ADDRESS}}", org != null && org.getAddress() != null ? safe(org.getAddress()) : "")
                .replace("{{ORG_TRN}}", org != null && org.getTrn() != null ? "TRN: " + safe(org.getTrn()) : "")
                .replace("{{RECEIPT_TITLE}}", title)
                .replace("{{RECEIPT_NUMBER}}", receiptNumber)
                .replace("{{RECEIPT_DATE}}", clearedAt.format(dateFmt))
                .replace("{{AMOUNT}}", money(cheque.getAmount()))
                .replace("{{PROPERTY_NAME}}", property != null ? safe(property.getNameEn()) : "")
                .replace("{{UNIT_NUMBER}}", unit != null ? safe(unit.getUnitNumber()) : "")
                .replace("{{PROPERTY_ADDRESS}}", propertyAddress(property))
                .replace("{{RENTER_NAME}}", renter != null ? safe(renter.getNameEn()) : "")
                .replace("{{RENTER_EMAIL}}", renter != null && renter.getEmail() != null ? safe(renter.getEmail()) : "N/A")
                .replace("{{RENTER_PHONE}}", renter != null && renter.getPhone() != null ? safe(renter.getPhone()) : "N/A")
                .replace("{{INSTALLMENT_NUMBER}}", String.valueOf(cheque.getSeqNo()))
                .replace("{{DUE_DATE}}", cheque.getChequeDate().format(dateFmt))
                .replace("{{PAYMENT_METHOD}}", cheque.getMode().name())
                .replace("{{PARTICULARS}}", particulars != null ? safe(particulars) : "N/A")
                .replace("{{BOOKING_ROWS}}", bookingRows(booking, dateFmt))
                .replace("{{CHEQUE_NUMBER}}", cheque.getChequeNumber() != null ? safe(cheque.getChequeNumber()) : "N/A")
                .replace("{{BANK_NAME}}", cheque.getPayeeBank() != null ? safe(cheque.getPayeeBank()) : "N/A")
                .replace("{{ONLINE_PAYMENT_ID}}", safe(gatewayReference(cheque.getId())))
                .replace("{{GENERATED_AT}}", java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm")));
    }

    /** "Booking: Parking spot, 05 Nov 2026 – 07 Nov 2026" and its times, or nothing. */
    private String bookingRows(BookingRequest b, DateTimeFormatter dateFmt) {
        if (b == null) return "";
        String kind = b.getResourceType() == BookingResourceType.PARKING_SPOT ? "Parking spot" : "Amenity";
        StringBuilder when = new StringBuilder();
        if (b.getPreferredDate() != null) {
            when.append(b.getPreferredDate().format(dateFmt));
            if (b.getPreferredEndDate() != null && !b.getPreferredEndDate().equals(b.getPreferredDate())) {
                when.append(" – ").append(b.getPreferredEndDate().format(dateFmt));
            }
        }
        if (b.getPreferredStartTime() != null) {
            when.append(when.isEmpty() ? "" : ", ").append(b.getPreferredStartTime());
            if (b.getPreferredEndTime() != null) when.append(" – ").append(b.getPreferredEndTime());
        }
        String row = "<tr><td class=\"label\">Booking</td><td class=\"value\">" + kind + "</td></tr>";
        return when.isEmpty() ? row
                : row + "<tr><td class=\"label\">Booked for</td><td class=\"value\">" + safe(when.toString()) + "</td></tr>";
    }

    /** One PDF out of several, in order. */
    private static byte[] merge(List<byte[]> pdfs) {
        if (pdfs.size() == 1) return pdfs.get(0);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDFMergerUtility merger = new PDFMergerUtility();
            for (byte[] pdf : pdfs) merger.addSource(new RandomAccessReadBuffer(pdf));
            merger.setDestinationStream(out);
            merger.mergeDocuments(IOUtils.createMemoryOnlyStreamCache());
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to combine receipts", e);
        }
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
        // Shared with the contract and the tax invoice (OrgBrandImages): our own
        // storage only, and the bytes, not the stored type, decide.
        String src = OrgBrandImages.dataUri(blobStorageService, tenantId, url).orElse(null);
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
            // Shared sanitiser: no bidi controls or glyph-less characters (R3 portal3 F11).
            builder.withHtmlContent(com.datagami.rentaxis.core.util.PdfText.clean(html), null);
            builder.toStream(baos);
            builder.run();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate receipt PDF", e);
        }
    }
}
