package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.LeaseDocumentDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.email.event.payload.LeasePayload;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.core.util.AmountInWordsUtil;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.DocumentType;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseDocumentRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ContractGenerationService {

    private static final BigDecimal VAT_RATE = new BigDecimal("0.05");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private final LeaseRepository leaseRepository;
    private final LeaseDocumentRepository leaseDocumentRepository;
    private final LandlordOrgRepository landlordOrgRepository;
    private final PaymentScheduleRepository paymentScheduleRepository;
    private final PaymentScheduleService paymentScheduleService;
    private final ApplicationEventPublisher events;

    @Value("${rentaxis.contracts.storage-path:./data/contracts}")
    private String storagePath;

    @Value("${AZURE_STORAGE_CONNECTION_STRING:}")
    private String azureConnectionString;

    @Value("${AZURE_STORAGE_CONTAINER_PREFIX:tenant-}")
    private String containerPrefix;

    public ContractGenerationService(LeaseRepository leaseRepository,
                                     LeaseDocumentRepository leaseDocumentRepository,
                                     LandlordOrgRepository landlordOrgRepository,
                                     PaymentScheduleRepository paymentScheduleRepository,
                                     PaymentScheduleService paymentScheduleService,
                                     ApplicationEventPublisher events) {
        this.leaseRepository = leaseRepository;
        this.leaseDocumentRepository = leaseDocumentRepository;
        this.landlordOrgRepository = landlordOrgRepository;
        this.paymentScheduleRepository = paymentScheduleRepository;
        this.paymentScheduleService = paymentScheduleService;
        this.events = events;
    }

    /**
     * Make sure the lease has its rent installment schedule before the contract
     * is rendered. New drafts created via LeaseService.createDraftLease already
     * have a schedule, but pre-existing drafts (or drafts created before draft-time
     * generation was added) won't — fix that on the fly so Section 4 of the
     * contract is never empty.
     */
    private void ensureScheduleExists(Lease lease) {
        boolean hasInstallments = paymentScheduleRepository.findByLeaseId(lease.getId())
                .stream().anyMatch(p -> !p.isBookingDeposit());
        if (!hasInstallments) {
            paymentScheduleService.generateScheduleForLease(lease);
        }
    }

    private boolean useAzureStorage() {
        return azureConnectionString != null && !azureConnectionString.isBlank();
    }

    @Transactional
    public LeaseDocumentDTO generateContract(UUID leaseId) {
        Lease lease = leaseRepository.findById(leaseId)
                .orElseThrow(() -> new NotFoundException("Lease not found"));
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId != null && !tenantId.equals(lease.getTenantId())) {
            throw new NotFoundException("Lease not found");
        }

        if (lease.getStatus() != LeaseStatus.DRAFT && lease.getStatus() != LeaseStatus.PENDING_SIGNATURE) {
            throw new BusinessRuleViolationException("Contract can only be generated for DRAFT or PENDING_SIGNATURE leases");
        }

        // Backfill the installment schedule for legacy drafts that predate
        // draft-time generation, so Section 4 is never empty on the contract.
        ensureScheduleExists(lease);

        // Remove old documents if regenerating — delete both the DB rows and
        // the underlying blob/file so the storage backend doesn't accumulate
        // stale PDFs forever.
        if (lease.getStatus() == LeaseStatus.PENDING_SIGNATURE) {
            List<LeaseDocument> oldDocs = leaseDocumentRepository.findByLeaseId(leaseId);
            for (LeaseDocument oldDoc : oldDocs) {
                deleteStoredFile(oldDoc.getDocumentUrl());
            }
            leaseDocumentRepository.deleteAll(oldDocs);
            log.info("Removed {} old documents (DB + storage) for lease {} before regeneration",
                    oldDocs.size(), leaseId);
        }

        // Assign contract number + agreement date if not set. Flush eagerly so a
        // concurrent generation against the same tenant surfaces the unique-index
        // conflict here (where we can translate it to a friendly error) rather
        // than at transaction commit (after the PDF was already generated).
        assignContractNumberIfNull(lease);
        if (lease.getAgreementDate() == null) {
            lease.setAgreementDate(LocalDate.now());
        }
        try {
            leaseRepository.saveAndFlush(lease);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessRuleViolationException(
                    "Another contract was generated at the same moment. Please try again.");
        }

        String contractNumberDisplay = String.valueOf(lease.getContractNumber());
        String html = renderContractHtml(lease, contractNumberDisplay);

        // Generate PDF to byte array
        String fileNameStem = "RA-" + contractNumberDisplay;
        String fileName = fileNameStem + "-" + System.currentTimeMillis() + ".pdf";
        byte[] pdfBytes = renderPdf(html);

        // Store PDF
        String documentUrl;
        if (useAzureStorage()) {
            documentUrl = uploadToAzure(lease.getTenantId(), fileName, pdfBytes);
        } else {
            documentUrl = saveToLocalDisk(fileName, pdfBytes);
        }

        // Save document record
        LeaseDocument doc = new LeaseDocument();
        doc.setLease(lease);
        doc.setDocumentUrl(documentUrl);
        doc.setType(DocumentType.CONTRACT);
        LeaseDocument savedDoc = leaseDocumentRepository.save(doc);

        // Transition lease to PENDING_SIGNATURE
        if (lease.getStatus() != LeaseStatus.PENDING_SIGNATURE) {
            lease.setStatus(LeaseStatus.PENDING_SIGNATURE);
            leaseRepository.save(lease);
        }

        log.info("Contract generated for lease {} at {}", leaseId, documentUrl);

        LeasePayload leasePayload = buildLeasePayload(lease, documentUrl);
        events.publishEvent(new EmailEvent(this,
                EmailEventType.LEASE_CONTRACT_GENERATED,
                lease.getTenantId(),
                leasePayload,
                "LEASE_CONTRACT_GENERATED:" + leaseId + ":" + System.currentTimeMillis()));

        events.publishEvent(new EmailEvent(this,
                EmailEventType.LEASE_SIGNATURE_REQUESTED,
                lease.getTenantId(),
                leasePayload,
                "LEASE_SIGNATURE_REQUESTED:" + leaseId + ":" + System.currentTimeMillis()));

        return mapToDTO(savedDoc);
    }

    @Transactional
    public byte[] previewContract(UUID leaseId) {
        Lease lease = leaseRepository.findById(leaseId)
                .orElseThrow(() -> new NotFoundException("Lease not found"));
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId != null && !tenantId.equals(lease.getTenantId())) {
            throw new NotFoundException("Lease not found");
        }

        // Backfill the installment schedule on the fly for legacy drafts that
        // predate draft-time generation. Persisting these rows is intentional —
        // they're needed regardless of whether the user later confirms-and-saves
        // the contract.
        ensureScheduleExists(lease);

        // Use placeholder for contract number when none assigned yet; do not
        // assign / mutate the lease's contract number on the preview path.
        String contractNumberDisplay = lease.getContractNumber() != null
                ? String.valueOf(lease.getContractNumber())
                : "DRAFT";
        String html = renderContractHtml(lease, contractNumberDisplay);
        return renderPdf(html);
    }

    // Package-private for tests: lets us assert on the substituted HTML without rendering PDF.
    String renderContractHtml(Lease lease, String contractNumberDisplay) {
        // Load landlord org for this tenant
        LandlordOrg org = landlordOrgRepository.findById(lease.getTenantId())
                .orElseThrow(() -> new NotFoundException("Landlord organization not found for tenant"));

        // Load payment schedules
        List<PaymentSchedule> schedules = paymentScheduleRepository.findByLeaseId(lease.getId());

        // Load template + terms partials
        String template = loadResource("templates/contract-template.html");
        String termsEn = loadResource("templates/contract-terms-en.html");
        String termsAr = loadResource("templates/contract-terms-ar.html");

        Unit unit = lease.getUnit();
        Property property = unit.getProperty();
        Renter renter = lease.getRenter();

        // Build dynamic sections
        String section3Rows = buildSection3Rows(lease);
        String section3Total = buildSection3Total(lease);
        String section4Rows = buildSection4Rows(schedules);

        // Grand total = rent + admin + deposit + parking
        BigDecimal grandTotal = nz(lease.getRentAmount())
                .add(nz(lease.getAdminFee()))
                .add(nz(lease.getDepositAmount()))
                .add(nz(lease.getParkingRemoteFee()));

        String amountInWords = AmountInWordsUtil.toEnglishWords(grandTotal, "AED");

        // Agreement date display (defaults to today only at generation time; preview uses what is stored)
        LocalDate agreementDate = lease.getAgreementDate() != null ? lease.getAgreementDate() : LocalDate.now();

        // Build the row-aligned terms table from the two partials.
        String termsTable = buildTermsTable(termsEn, termsAr);

        // Substitute placeholders in a single pass so user-provided values can't
        // accidentally introduce new {{...}} tokens that the next replace picks up.
        // User-controlled text fields are HTML-escaped (escapeUserText); pre-built
        // HTML fragments (sections, terms table) are passed through raw.
        Map<String, String> values = new HashMap<>();
        values.put("LANDLORD_NAME", escapeUserText(org.getName()));
        values.put("LANDLORD_ADDRESS", escapeUserText(org.getAddress()));
        values.put("LANDLORD_PHONE", escapeUserText(org.getPhone()));
        values.put("CONTRACT_NUMBER", escapeUserText(contractNumberDisplay));
        values.put("AGREEMENT_DATE", formatDate(agreementDate));
        values.put("BUILDING_NAME", escapeUserText(property.getNameEn()));
        values.put("TENANT_NAME", escapeUserText(renter.getNameEn()));
        values.put("TENANT_EMAIL", escapeUserText(renter.getEmail()));
        values.put("TENANT_PHONE", escapeUserText(renter.getPhone()));
        values.put("LEASE_START_DATE", formatDate(lease.getStartDate()));
        values.put("LEASE_END_DATE", formatDate(lease.getEndDate()));
        values.put("FLAT_NUMBER", escapeUserText(unit.getUnitNumber()));
        values.put("SECTION_3_ROWS", section3Rows);
        values.put("SECTION_3_TOTAL", section3Total);
        values.put("SECTION_4_ROWS", section4Rows);
        values.put("AMOUNT_IN_WORDS", escapeUserText(amountInWords));
        values.put("GRAND_TOTAL", formatAmount(grandTotal));
        values.put("PRINT_DATETIME", formatPrintDateTime(java.time.LocalDateTime.now()));
        values.put("TERMS_TABLE", termsTable);

        return substituteAll(template, values);
    }

    private static final Pattern TERM_LI = Pattern.compile(
            "<li[^>]*\\bvalue\\s*=\\s*\"(\\d+)\"[^>]*>(.*?)</li>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /**
     * Build a row-aligned terms table from the two language partials. Each
     * <li value="N">CONTENT</li> pair is rendered as a single <tr> with the
     * English cell on the left and the Arabic cell on the right, so clause N
     * stays vertically aligned across both languages even when one language's
     * text is much longer than the other.
     *
     * Falls back to a single row containing each partial as raw HTML if the
     * &lt;li value="..."&gt; markers can't be parsed (no regression vs. the
     * earlier two-column layout).
     */
    static String buildTermsTable(String termsEnHtml, String termsArHtml) {
        java.util.LinkedHashMap<Integer, String> en = extractTerms(termsEnHtml);
        java.util.LinkedHashMap<Integer, String> ar = extractTerms(termsArHtml);
        if (en.isEmpty() && ar.isEmpty()) {
            return "<table class=\"terms-rows\"><tr><td class=\"en\">" + termsEnHtml
                    + "</td><td class=\"ar\">" + termsArHtml + "</td></tr></table>";
        }
        java.util.TreeSet<Integer> keys = new java.util.TreeSet<>();
        keys.addAll(en.keySet());
        keys.addAll(ar.keySet());
        StringBuilder sb = new StringBuilder("<table class=\"terms-rows\">");
        for (Integer n : keys) {
            String enContent = en.getOrDefault(n, "");
            String arContent = ar.getOrDefault(n, "");
            // EN cell: "N." (digit then period). AR cell: ".N" (period then
            // digit) — proper RTL Arabic-style numbering, with the marker
            // sitting at the logical start of the line, which renders on the
            // visual right inside an RTL cell.
            sb.append("<tr>")
                    .append("<td class=\"en\"><span class=\"num\">").append(n).append(".</span> ")
                    .append(enContent).append("</td>")
                    .append("<td class=\"ar\"><span class=\"num\">.").append(n).append("</span> ")
                    .append(arContent).append("</td>")
                    .append("</tr>");
        }
        sb.append("</table>");
        return sb.toString();
    }

    private static java.util.LinkedHashMap<Integer, String> extractTerms(String html) {
        java.util.LinkedHashMap<Integer, String> out = new java.util.LinkedHashMap<>();
        if (html == null) return out;
        Matcher m = TERM_LI.matcher(html);
        while (m.find()) {
            try {
                out.put(Integer.parseInt(m.group(1)), m.group(2).trim());
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        return out;
    }

    private static final DateTimeFormatter PRINT_DATETIME_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM yyyy   hh:mm a", Locale.ENGLISH);

    private static String formatPrintDateTime(java.time.LocalDateTime ldt) {
        return PRINT_DATETIME_FORMAT.format(ldt);
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Z0-9_]+)}}");

    /** Single-pass replacement of every {{KEY}} occurrence; missing keys are left as-is. */
    static String substituteAll(String template, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length());
        while (m.find()) {
            String key = m.group(1);
            String replacement = values.get(key);
            if (replacement == null) {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group(0)));
            } else {
                m.appendReplacement(out, Matcher.quoteReplacement(replacement));
            }
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Build the totals row for Section 3. Sums non-zero rows of amount, VAT amount,
     * and amount-with-VAT. Renders blank VAT % cell.
     */
    private String buildSection3Total(Lease lease) {
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal totalVat = BigDecimal.ZERO;
        BigDecimal totalWithVat = BigDecimal.ZERO;

        Object[][] rows = new Object[][]{
                {nz(lease.getRentAmount()), lease.isRentVatApplicable()},
                {nz(lease.getAdminFee()), lease.isAdminFeeVatApplicable()},
                {nz(lease.getDepositAmount()), lease.isSecurityDepositVatApplicable()},
                {nz(lease.getParkingRemoteFee()), lease.isParkingRemoteVatApplicable()}
        };
        for (Object[] r : rows) {
            BigDecimal amt = (BigDecimal) r[0];
            boolean vat = (Boolean) r[1];
            if (amt.compareTo(BigDecimal.ZERO) == 0) continue;
            BigDecimal vatAmount = vat ? amt.multiply(VAT_RATE).setScale(2, RoundingMode.HALF_UP)
                                       : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            totalAmount = totalAmount.add(amt);
            totalVat = totalVat.add(vatAmount);
            totalWithVat = totalWithVat.add(amt.add(vatAmount));
        }

        return "<tr>"
                + "<td class=\"center\"></td>"
                + "<td style=\"font-weight:bold;\">TOTAL</td>"
                + "<td class=\"num\" style=\"font-weight:bold;\">" + formatAmount(totalAmount) + "</td>"
                + "<td class=\"center\"></td>"
                + "<td class=\"num\" style=\"font-weight:bold;\">" + formatAmount(totalVat) + "</td>"
                + "<td class=\"num\" style=\"font-weight:bold;\">" + formatAmount(totalWithVat) + "</td>"
                + "</tr>";
    }

    private String loadResource(String classpathPath) {
        try {
            return new String(new ClassPathResource(classpathPath).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load " + classpathPath, e);
        }
    }

    /**
     * Build the rows for Section 3 (Property Information). Hides rows where the
     * amount is null or zero. Each row renders S No, Particulars, Amount,
     * VAT %, VAT Amount, Amount With VAT.
     */
    public String buildSection3Rows(Lease lease) {
        StringBuilder sb = new StringBuilder();
        int sNo = 1;

        sNo = appendSection3Row(sb, sNo, "Rent", lease.getRentAmount(), lease.isRentVatApplicable());
        sNo = appendSection3Row(sb, sNo, "Admin Fee", lease.getAdminFee(), lease.isAdminFeeVatApplicable());
        sNo = appendSection3Row(sb, sNo, "Security Deposit", lease.getDepositAmount(), lease.isSecurityDepositVatApplicable());
        sNo = appendSection3Row(sb, sNo, "Parking Remote", lease.getParkingRemoteFee(), lease.isParkingRemoteVatApplicable());

        return sb.toString();
    }

    private int appendSection3Row(StringBuilder sb, int sNo, String label, BigDecimal amount, boolean vatApplicable) {
        BigDecimal amt = nz(amount);
        if (amt.compareTo(BigDecimal.ZERO) == 0) {
            return sNo;
        }
        BigDecimal vatAmount;
        String vatPctDisplay;
        if (vatApplicable) {
            vatAmount = amt.multiply(VAT_RATE).setScale(2, RoundingMode.HALF_UP);
            vatPctDisplay = "5%";
        } else {
            vatAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            vatPctDisplay = "Exempt";
        }
        BigDecimal amtWithVat = amt.add(vatAmount);

        sb.append("<tr>")
                .append("<td class=\"center\">").append(sNo).append("</td>")
                .append("<td>").append(safe(label)).append("</td>")
                .append("<td class=\"num\">").append(formatAmount(amt)).append("</td>")
                .append("<td class=\"center\">").append(vatPctDisplay).append("</td>")
                .append("<td class=\"num\">").append(formatAmount(vatAmount)).append("</td>")
                .append("<td class=\"num\">").append(formatAmount(amtWithVat)).append("</td>")
                .append("</tr>");
        return sNo + 1;
    }

    /**
     * Assigns the next sequential per-tenant contract number to the lease if its
     * current contract number is null. Idempotent for already-assigned leases.
     */
    public void assignContractNumberIfNull(Lease lease) {
        if (lease.getContractNumber() != null) return;
        Long max = leaseRepository.findMaxContractNumberForTenant(lease.getTenantId());
        long base = max == null ? 0L : max;
        lease.setContractNumber(base + 1L);
    }

    String formatAmount(BigDecimal amount) {
        BigDecimal v = nz(amount).setScale(2, RoundingMode.HALF_UP);
        DecimalFormat df = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
        return df.format(v);
    }

    String formatDate(LocalDate date) {
        return date != null ? DATE_FORMAT.format(date) : "";
    }

    /**
     * Build the rows for Section 4 (Payment Details). Sorts non-booking
     * installments by chequeDate ASC and appends booking-deposit rows last.
     */
    public String buildSection4Rows(List<PaymentSchedule> schedules) {
        if (schedules == null || schedules.isEmpty()) return "";

        List<PaymentSchedule> regular = new ArrayList<>();
        List<PaymentSchedule> booking = new ArrayList<>();
        for (PaymentSchedule p : schedules) {
            if (p.isBookingDeposit()) {
                booking.add(p);
            } else {
                regular.add(p);
            }
        }
        Comparator<PaymentSchedule> byChequeDate = Comparator.comparing(
                PaymentSchedule::getChequeDate,
                Comparator.nullsLast(Comparator.naturalOrder()));
        regular.sort(byChequeDate);
        booking.sort(byChequeDate);

        StringBuilder sb = new StringBuilder();
        int sNo = 1;
        for (PaymentSchedule p : regular) {
            appendSection4Row(sb, sNo++, p);
        }
        for (PaymentSchedule p : booking) {
            appendSection4Row(sb, sNo++, p);
        }
        return sb.toString();
    }

    private void appendSection4Row(StringBuilder sb, int sNo, PaymentSchedule p) {
        sb.append("<tr>")
                .append("<td class=\"center\">").append(sNo).append("</td>")
                .append("<td>").append(escapeUserText(p.getChequeNumber())).append("</td>")
                .append("<td>").append(p.getChequeDate() != null ? formatDate(p.getChequeDate()) : "").append("</td>")
                .append("<td>").append(escapeUserText(p.getPurposeLabel())).append("</td>")
                .append("<td>").append(escapeUserText(p.getBankName())).append("</td>")
                .append("<td class=\"num\">").append(formatAmount(nz(p.getAmount()))).append("</td>")
                .append("</tr>");
    }

    /**
     * Returns the empty string for null, otherwise the value as-is.
     * NOT for HTML — use {@link #escapeUserText(String)} for any value
     * coming from a user-controlled field.
     */
    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /**
     * HTML-escape a user-provided string before it is embedded in the contract
     * HTML template. Returns "" for null. Apply to every value that originated
     * from user input (names, addresses, phone numbers, cheque numbers, etc.).
     */
    private static String escapeUserText(String s) {
        return s == null ? "" : HtmlUtils.htmlEscape(s);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    // Package-private for tests: lets us stub PDF rendering via Mockito spy.
    byte[] renderPdf(String html) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();

            // Register fonts for Arabic support
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
                log.warn("Could not load custom fonts, Arabic text may not render: {}", e.getMessage());
            }

            builder.withHtmlContent(html, null);
            builder.toStream(baos);
            builder.run();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate PDF contract", e);
        }
    }

    private String uploadToAzure(UUID tenantId, String fileName, byte[] pdfBytes) {
        String containerName = containerPrefix + tenantId.toString();
        BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                .connectionString(azureConnectionString)
                .buildClient();

        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(containerName);
        if (!containerClient.exists()) {
            containerClient.create();
        }

        String blobPath = "contracts/" + fileName;
        BlobClient blobClient = containerClient.getBlobClient(blobPath);
        blobClient.upload(new ByteArrayInputStream(pdfBytes), pdfBytes.length, true);

        // Generate a 30-day SAS token so the URL in emails is directly downloadable
        // without requiring the recipient to be logged into the app.
        // The raw blob URL (without SAS) is still usable for backend-authenticated reads.
        try {
            com.azure.storage.blob.sas.BlobSasPermission permission =
                    new com.azure.storage.blob.sas.BlobSasPermission().setReadPermission(true);
            com.azure.storage.blob.sas.BlobServiceSasSignatureValues values =
                    new com.azure.storage.blob.sas.BlobServiceSasSignatureValues(
                            java.time.OffsetDateTime.now().plusDays(30), permission);
            String sasToken = blobClient.generateSas(values);
            String sasUrl = blobClient.getBlobUrl() + "?" + sasToken;
            log.info("Uploaded contract to Azure Blob (SAS URL generated): {}", blobClient.getBlobUrl());
            return sasUrl;
        } catch (Exception e) {
            // Fallback: return the raw URL if SAS generation fails (e.g. local dev with fake creds).
            log.warn("SAS token generation failed, falling back to raw blob URL: {}", e.getMessage());
            String accountUrl = blobServiceClient.getAccountUrl();
            return accountUrl + "/" + containerName + "/" + blobPath;
        }
    }

    private String saveToLocalDisk(String fileName, byte[] pdfBytes) {
        Path dirPath = Path.of(storagePath);
        try {
            Files.createDirectories(dirPath);
            Path filePath = dirPath.resolve(fileName);
            Files.write(filePath, pdfBytes);
            return filePath.toString();
        } catch (IOException e) {
            throw new RuntimeException("Failed to save contract to disk", e);
        }
    }

    /**
     * Best-effort delete of a stored contract PDF. Used during regeneration
     * to avoid accumulating stale blobs/files.
     * <p>
     * Errors are logged and swallowed: if the underlying file is already
     * missing or unreachable, the regeneration should still proceed.
     */
    private void deleteStoredFile(String documentUrl) {
        if (documentUrl == null || documentUrl.isBlank()) return;
        try {
            if (documentUrl.startsWith("https://") && documentUrl.contains(".blob.core.windows.net")) {
                if (!useAzureStorage()) {
                    log.warn("Cannot delete Azure blob {} — Azure storage not configured", documentUrl);
                    return;
                }
                java.net.URI uri = java.net.URI.create(documentUrl);
                String accountUrl = uri.getScheme() + "://" + uri.getHost();
                String[] segments = uri.getPath().substring(1).split("/", 2);
                if (segments.length < 2) {
                    log.warn("Unparseable Azure blob URL, skipping delete: {}", documentUrl);
                    return;
                }
                String containerName = segments[0];
                String blobPath = java.net.URLDecoder.decode(segments[1], StandardCharsets.UTF_8);
                BlobServiceClient client = new BlobServiceClientBuilder()
                        .endpoint(accountUrl)
                        .connectionString(azureConnectionString)
                        .buildClient();
                BlobContainerClient container = client.getBlobContainerClient(containerName);
                BlobClient blob = container.getBlobClient(blobPath);
                blob.deleteIfExists();
                log.info("Deleted Azure blob {}/{}", containerName, blobPath);
            } else {
                Path p = Path.of(documentUrl);
                boolean removed = Files.deleteIfExists(p);
                if (removed) {
                    log.info("Deleted local contract file {}", p);
                } else {
                    log.warn("Local contract file did not exist (already removed?): {}", p);
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to delete stored contract file {} — continuing regeneration: {}",
                    documentUrl, ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<LeaseDocumentDTO> getDocuments(UUID leaseId) {
        return leaseDocumentRepository.findByLeaseId(leaseId).stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public byte[] getDocumentContent(UUID docId) {
        LeaseDocument doc = leaseDocumentRepository.findById(docId)
                .orElseThrow(() -> new NotFoundException("Document not found"));

        String url = doc.getDocumentUrl();
        log.info("Downloading document {} with URL: {}", docId, url);

        // Azure Blob URL
        if (url.startsWith("https://") && url.contains(".blob.core.windows.net")) {
            try {
                return downloadFromAzure(url);
            } catch (Exception e) {
                log.error("Azure download failed for document {} (URL: {}): {}", docId, url, e.getMessage(), e);
                throw new RuntimeException("Failed to download document from storage: " + e.getMessage(), e);
            }
        }

        // Local file
        File file = new File(url);
        if (!file.exists()) {
            log.error("Local document file not found: {}", url);
            throw new NotFoundException("Document file not found on disk: " + url);
        }
        try {
            return Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to read document file", e);
        }
    }

    /**
     * @deprecated Use getDocumentContent() instead. Kept for backward compatibility.
     */
    @Transactional(readOnly = true)
    public File getDocumentFile(UUID docId) {
        LeaseDocument doc = leaseDocumentRepository.findById(docId)
                .orElseThrow(() -> new NotFoundException("Document not found"));

        String url = doc.getDocumentUrl();

        // For Azure URLs, download to temp file
        if (url.startsWith("https://")) {
            byte[] content = downloadFromAzure(url);
            try {
                Path tempFile = Files.createTempFile("contract-", ".pdf");
                Files.write(tempFile, content);
                return tempFile.toFile();
            } catch (IOException e) {
                throw new RuntimeException("Failed to create temp file for document", e);
            }
        }

        File file = new File(url);
        if (!file.exists()) {
            throw new RuntimeException("Document file not found on disk");
        }
        return file;
    }

    private byte[] downloadFromAzure(String blobUrl) {
        String container = extractContainerName(blobUrl);
        String blobPath = extractBlobPath(blobUrl);
        log.info("Azure download - container: '{}', blobPath: '{}'", container, blobPath);

        BlobClient blobClient = new BlobServiceClientBuilder()
                .connectionString(azureConnectionString)
                .buildClient()
                .getBlobContainerClient(container)
                .getBlobClient(blobPath);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        blobClient.downloadStream(baos);
        return baos.toByteArray();
    }

    private String extractContainerName(String blobUrl) {
        // URL format: https://<account>.blob.core.windows.net/<container>/<path>
        String marker = ".blob.core.windows.net/";
        int idx = blobUrl.indexOf(marker);
        if (idx < 0) {
            throw new RuntimeException("Invalid Azure Blob URL (missing host): " + blobUrl);
        }
        String path = blobUrl.substring(idx + marker.length());
        int slash = path.indexOf('/');
        return URLDecoder.decode(slash > 0 ? path.substring(0, slash) : path, StandardCharsets.UTF_8);
    }

    private String extractBlobPath(String blobUrl) {
        String marker = ".blob.core.windows.net/";
        int idx = blobUrl.indexOf(marker);
        if (idx < 0) {
            throw new RuntimeException("Invalid Azure Blob URL (missing host): " + blobUrl);
        }
        String path = blobUrl.substring(idx + marker.length());
        int firstSlash = path.indexOf('/');
        if (firstSlash < 0) {
            throw new RuntimeException("Invalid Azure Blob URL (missing blob path): " + blobUrl);
        }
        // Decode URL-encoded path (getBlobUrl() may return %2F for slashes)
        return URLDecoder.decode(path.substring(firstSlash + 1), StandardCharsets.UTF_8);
    }

    private LeasePayload buildLeasePayload(Lease lease, String contractUrl) {
        BigDecimal monthly = lease.getMonthlyRent() != null ? lease.getMonthlyRent() : lease.getRentAmount();
        return new LeasePayload(
                lease.getId(),
                lease.getRenter().getUserId(),
                null,  // propertyManagerUserId — not stored on Lease; RecipientResolver falls back to tenant admins
                lease.getUnit().getUnitNumber(),
                lease.getUnit().getProperty().getNameEn(),
                lease.getStartDate() != null ? lease.getStartDate().toString() : null,
                lease.getEndDate() != null ? lease.getEndDate().toString() : null,
                monthly != null ? monthly.toPlainString() : null,
                contractUrl
        );
    }

    private LeaseDocumentDTO mapToDTO(LeaseDocument doc) {
        LeaseDocumentDTO dto = new LeaseDocumentDTO();
        dto.setId(doc.getId());
        dto.setLeaseId(doc.getLease().getId());
        dto.setDocumentUrl(doc.getDocumentUrl());
        dto.setType(doc.getType());
        return dto;
    }
}
