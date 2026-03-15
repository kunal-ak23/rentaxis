package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.OnlinePaymentStatus;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.OnlinePaymentRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RentReceiptService {

    private final PaymentScheduleRepository paymentScheduleRepository;
    private final LandlordOrgRepository landlordOrgRepository;
    private final OnlinePaymentRepository onlinePaymentRepository;

    @Transactional(readOnly = true)
    public byte[] generateReceipt(UUID paymentScheduleId) {
        PaymentSchedule payment = paymentScheduleRepository.findById(paymentScheduleId)
                .orElseThrow(() -> new NotFoundException("Payment not found"));

        if (payment.getStatus() != PaymentStatus.CLEARED) {
            throw new BusinessRuleViolationException("Receipt can only be generated for cleared payments");
        }

        Lease lease = payment.getLease();
        Unit unit = lease.getUnit();
        Property property = unit.getProperty();
        Renter renter = lease.getRenter();

        // Get org info
        UUID tenantId = TenantContextHolder.getTenantId();
        LandlordOrg org = null;
        if (tenantId != null) {
            org = landlordOrgRepository.findById(tenantId).orElse(null);
        }

        // Load template
        String template;
        try {
            ClassPathResource resource = new ClassPathResource("templates/receipt-template.html");
            template = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load receipt template", e);
        }

        // Format amount
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.US);
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        String formattedAmount = nf.format(payment.getAmount());

        // Receipt number: RR-YYYY-MM-SHORT_ID (e.g. RR-2026-03-A1B2C3D4)
        String receiptNumber = "RR-" + String.format("%d-%02d", payment.getDueDate().getYear(), payment.getDueDate().getMonthValue())
                + "-" + payment.getId().toString().substring(0, 8).toUpperCase();

        DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("dd MMM yyyy");

        // Populate template
        String html = template
                .replace("{{ORG_LOGO}}", org != null && org.getLogoUrl() != null && !org.getLogoUrl().isEmpty()
                        ? "<img src=\"" + org.getLogoUrl() + "\" style=\"height: 40px; margin-bottom: 8px;\" />"
                        : "")
                .replace("{{ORG_NAME}}", org != null ? safe(org.getName()) : "Property Management")
                .replace("{{ORG_ADDRESS}}", org != null && org.getAddress() != null ? safe(org.getAddress()) : "")
                .replace("{{ORG_TRN}}", org != null && org.getTrn() != null ? "TRN: " + safe(org.getTrn()) : "")
                .replace("{{RECEIPT_NUMBER}}", receiptNumber)
                .replace("{{RECEIPT_DATE}}", LocalDate.now().format(dateFmt))
                .replace("{{AMOUNT}}", formattedAmount)
                .replace("{{PROPERTY_NAME}}", safe(property.getNameEn()))
                .replace("{{UNIT_NUMBER}}", safe(unit.getUnitNumber()))
                .replace("{{PROPERTY_ADDRESS}}", property.getAddress() != null ? safe(property.getAddress())
                        : (property.getEmirate() != null ? property.getEmirate().name().replace('_', ' ') : ""))
                .replace("{{RENTER_NAME}}", safe(renter.getNameEn()))
                .replace("{{RENTER_EMAIL}}", renter.getEmail() != null ? safe(renter.getEmail()) : "N/A")
                .replace("{{RENTER_PHONE}}", renter.getPhone() != null ? safe(renter.getPhone()) : "N/A")
                .replace("{{INSTALLMENT_NUMBER}}", String.valueOf(payment.getInstallmentNumber()))
                .replace("{{DUE_DATE}}", payment.getDueDate().format(dateFmt))
                .replace("{{PAYMENT_METHOD}}", payment.getPaymentMethod() != null ? safe(payment.getPaymentMethod()) : "N/A")
                .replace("{{CHEQUE_NUMBER}}", payment.getChequeNumber() != null ? safe(payment.getChequeNumber()) : "N/A")
                .replace("{{BANK_NAME}}", payment.getBankName() != null ? safe(payment.getBankName()) : "N/A")
                .replace("{{ONLINE_PAYMENT_ID}}", getOnlinePaymentId(paymentScheduleId))
                .replace("{{GENERATED_AT}}", java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm")));

        return renderPdf(html);
    }

    private String getOnlinePaymentId(UUID paymentScheduleId) {
        try {
            List<OnlinePayment> onlinePayments = onlinePaymentRepository.findByPaymentScheduleId(paymentScheduleId);
            return onlinePayments.stream()
                    .filter(op -> op.getStatus() == OnlinePaymentStatus.CAPTURED)
                    .findFirst()
                    .map(op -> op.getGatewayPaymentId() != null ? op.getGatewayPaymentId() : "N/A")
                    .orElse("N/A");
        } catch (Exception e) {
            return "N/A";
        }
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

            builder.withHtmlContent(html, null);
            builder.toStream(baos);
            builder.run();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate receipt PDF", e);
        }
    }
}
