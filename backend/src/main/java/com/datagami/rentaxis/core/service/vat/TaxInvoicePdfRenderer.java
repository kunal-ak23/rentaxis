package com.datagami.rentaxis.core.service.vat;

import com.datagami.rentaxis.core.service.RentReceiptService;
import com.datagami.rentaxis.core.util.PdfResourcePolicy;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.TaxInvoice;
import com.datagami.rentaxis.domain.entity.enums.TaxInvoiceKind;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * A tax invoice or credit note as a PDF, bilingual heading ("Tax Invoice / فاتورة
 * ضريبية").
 *
 * <p>The same stack and the same resource policy as the rent receipt and the
 * contract: openhtmltopdf with the Noto fonts handed over as classpath streams, and
 * {@link PdfResourcePolicy} so the renderer loads nothing but inline {@code data:}
 * URIs. Every value that came from a user (names, addresses, narrations) is escaped
 * before it reaches the template. The organisation's logo goes through the
 * receipt's own inliner, which only reads this tenant's uploads.</p>
 */
@Component
public class TaxInvoicePdfRenderer {

    private static final Logger log = LoggerFactory.getLogger(TaxInvoicePdfRenderer.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH);

    private final RentReceiptService receipts;

    public TaxInvoicePdfRenderer(RentReceiptService receipts) {
        this.receipts = receipts;
    }

    public byte[] render(TaxInvoice inv, LandlordOrg org, UUID tenantId) {
        return renderPdf(html(inv, org, tenantId));
    }

    /** The filled template — package-visible so a test can read what went into the PDF. */
    String html(TaxInvoice inv, LandlordOrg org, UUID tenantId) {
        String template;
        try {
            template = new String(new ClassPathResource("templates/tax-invoice-template.html")
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load the tax invoice template", e);
        }
        boolean credit = inv.getKind() == TaxInvoiceKind.CREDIT_NOTE;
        String logo = "";
        try {
            logo = receipts.logoImg(org, tenantId);
        } catch (RuntimeException e) {
            // A missing or unreadable logo is not a reason to refuse a tax invoice.
            log.warn("Tax invoice {}: logo left off ({})", inv.getInvoiceNumber(), e.getMessage());
        }
        String period = inv.getPeriodStart() == null || inv.getPeriodEnd() == null ? "—"
                : DAY.format(inv.getPeriodStart()) + " – " + DAY.format(inv.getPeriodEnd());
        String rate = inv.getVatRate().multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP) + "%";
        return template
                .replace("{{ORG_LOGO}}", logo)
                .replace("{{SUPPLIER_NAME}}", esc(inv.getSupplierName()))
                .replace("{{SUPPLIER_ADDRESS}}", esc(inv.getSupplierAddress()))
                .replace("{{SUPPLIER_TRN}}", esc(inv.getSupplierTrn()))
                .replace("{{TITLE_EN}}", credit ? "TAX CREDIT NOTE" : "TAX INVOICE")
                .replace("{{TITLE_AR}}", credit ? "إشعار دائن ضريبي" : "فاتورة ضريبية")
                .replace("{{INVOICE_NUMBER}}", esc(inv.getInvoiceNumber()))
                .replace("{{ISSUE_DATE}}", DAY.format(inv.getIssueDate()))
                .replace("{{REFERENCE_LINE}}", inv.getReferenceNote() == null || inv.getReferenceNote().isBlank() ? ""
                        : "<div class=\"meta\">Adjusts tax invoice(s) / يعدّل الفاتورة الضريبية: "
                                + esc(inv.getReferenceNote()) + "</div>")
                .replace("{{CUSTOMER_NAME}}", esc(inv.getCustomerName()))
                .replace("{{CUSTOMER_NAME_AR_ROW}}", inv.getCustomerNameAr() == null || inv.getCustomerNameAr().isBlank() ? ""
                        : "<tr><td class=\"label\">الاسم</td><td class=\"value ar\">" + esc(inv.getCustomerNameAr()) + "</td></tr>")
                .replace("{{CUSTOMER_TRN_ROW}}", inv.getCustomerTrn() == null || inv.getCustomerTrn().isBlank() ? ""
                        : "<tr><td class=\"label\">Customer TRN</td><td class=\"value\">" + esc(inv.getCustomerTrn()) + "</td></tr>")
                .replace("{{PROPERTY_NAME}}", esc(inv.getPropertyName()))
                .replace("{{UNIT_NUMBER}}", esc(inv.getUnitNumber()))
                .replace("{{PERIOD}}", period)
                .replace("{{DESCRIPTION}}", esc(inv.getDescription()))
                .replace("{{TAXABLE}}", money(inv.getTaxableAmount()))
                .replace("{{RATE}}", rate)
                .replace("{{VAT}}", money(inv.getVatAmount()))
                .replace("{{TOTAL}}", money(inv.getTotalAmount()))
                .replace("{{TOTAL_LABEL}}", credit ? "Total credited" : "Total amount due")
                .replace("{{FOOTER_NOTE}}", credit
                        ? "This credit note reduces the output VAT previously declared on the tenancy named above."
                        : "Tax invoice issued on the instalment's tax point under UAE VAT law.");
    }

    private static String money(BigDecimal v) {
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.US);
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        return nf.format(v == null ? BigDecimal.ZERO : v);
    }

    private static String esc(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static byte[] renderPdf(String html) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            ClassPathResource arabicFont = new ClassPathResource("fonts/NotoSansArabic.ttf");
            ClassPathResource latinFont = new ClassPathResource("fonts/NotoSans.ttf");
            builder.useFont(() -> {
                try { return arabicFont.getInputStream(); } catch (IOException ex) { throw new UncheckedIOException(ex); }
            }, "Noto Sans Arabic");
            builder.useFont(() -> {
                try { return latinFont.getInputStream(); } catch (IOException ex) { throw new UncheckedIOException(ex); }
            }, "Noto Sans");
            // Only inline data: URIs load; no http(s), no file:, no jar:.
            PdfResourcePolicy.apply(builder);
            builder.withHtmlContent(html, null);
            builder.toStream(baos);
            builder.run();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render the tax invoice PDF", e);
        }
    }
}
