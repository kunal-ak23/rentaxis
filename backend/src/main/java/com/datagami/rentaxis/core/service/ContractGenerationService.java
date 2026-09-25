package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.LeaseDocumentDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.email.event.payload.LeasePayload;
import com.datagami.rentaxis.core.service.lease.LeaseVat;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.core.util.AmountInWordsUtil;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.DocumentType;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseLineRepository;
import com.datagami.rentaxis.domain.repository.LeaseDocumentRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ContractGenerationService {

    /**
     * One rate, shared with the cheque grid and the posting journal. The literal
     * used to live here as well as in {@code LeaseVat}, and a contract printing a
     * different VAT figure from the cheques collecting it is the kind of
     * disagreement nobody notices until a renter adds the cheques up.
     */
    private static final BigDecimal VAT_RATE = LeaseVat.RATE;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private final LeaseRepository leaseRepository;
    private final com.datagami.rentaxis.core.security.LeaseAccessPolicy leaseAccessPolicy;
    private final LeaseDocumentRepository leaseDocumentRepository;
    private final LandlordOrgRepository landlordOrgRepository;
    private final ChequeRepository chequeRepository;
    private final LeaseLineRepository leaseLineRepository;
    private final ApplicationEventPublisher events;

    @Value("${rentaxis.contracts.storage-path:./data/contracts}")
    private String storagePath;

    @Value("${AZURE_STORAGE_CONNECTION_STRING:}")
    private String azureConnectionString;

    @Value("${AZURE_STORAGE_CONTAINER_PREFIX:tenant-}")
    private String containerPrefix;

    public ContractGenerationService(LeaseRepository leaseRepository,
            com.datagami.rentaxis.core.security.LeaseAccessPolicy leaseAccessPolicy,
                                     LeaseDocumentRepository leaseDocumentRepository,
                                     LandlordOrgRepository landlordOrgRepository,
                                     ChequeRepository chequeRepository,
                                     LeaseLineRepository leaseLineRepository,
                                     ApplicationEventPublisher events) {
        this.leaseRepository = leaseRepository;
        this.leaseAccessPolicy = leaseAccessPolicy;
        this.leaseDocumentRepository = leaseDocumentRepository;
        this.landlordOrgRepository = landlordOrgRepository;
        this.chequeRepository = chequeRepository;
        this.leaseLineRepository = leaseLineRepository;
        this.events = events;
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

        // Generating a contract does not create a payment plan. Section 4 renders
        // whatever schedule rows already exist — none, for a lease drafted under
        // the line model — until Task 11 points it at the cheque grid.

        // A rejected contract returns the lease to DRAFT but its document row
        // still exists. Replace documents based on what is actually persisted,
        // not on status, so reject -> regenerate cannot accumulate stale PDFs.
        List<LeaseDocument> oldDocs = leaseDocumentRepository.findByLeaseId(leaseId);
        List<String> oldDocumentUrls = oldDocs.stream()
                .map(LeaseDocument::getDocumentUrl)
                .toList();
        if (!oldDocs.isEmpty()) {
            leaseDocumentRepository.deleteAll(oldDocs);
            log.info("Removed {} old document rows for lease {} before regeneration",
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
        boolean replacementCleanupScheduled = scheduleReplacementCleanup(
                lease.getTenantId(), oldDocumentUrls, documentUrl);

        // Save document record
        LeaseDocument doc = new LeaseDocument();
        doc.setLease(lease);
        doc.setDocumentUrl(documentUrl);
        doc.setType(DocumentType.CONTRACT);
        LeaseDocument savedDoc = leaseDocumentRepository.save(doc);

        // Transition lease to PENDING_SIGNATURE. A new contract document is a new
        // thing to agree to, so any earlier acceptance was of a different document
        // and is cleared (#79) — otherwise the renter would be shown "accepted" for
        // a contract they have never seen, and could no longer reject it.
        if (lease.getStatus() != LeaseStatus.PENDING_SIGNATURE || lease.getRenterAcceptedAt() != null) {
            lease.setStatus(LeaseStatus.PENDING_SIGNATURE);
            lease.setRenterAcceptedAt(null);
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

        if (!replacementCleanupScheduled) {
            // Defensive fallback for a direct call outside Spring's
            // transactional proxy (principally narrow unit tests).
            cleanupTenantDocuments(lease.getTenantId(), oldDocumentUrls);
        }

        return mapToDTO(savedDoc);
    }

    private boolean scheduleReplacementCleanup(
            UUID tenantId,
            List<String> oldDocumentUrls,
            String newDocumentUrl) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return false;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cleanupTenantDocuments(tenantId, oldDocumentUrls);
            }

            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    deleteStoredFile(tenantId, newDocumentUrl);
                }
            }
        });
        return true;
    }

    @Transactional
    public byte[] previewContract(UUID leaseId) {
        Lease lease = leaseRepository.findById(leaseId)
                .orElseThrow(() -> new NotFoundException("Lease not found"));
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId != null && !tenantId.equals(lease.getTenantId())) {
            throw new NotFoundException("Lease not found");
        }

        // As in generateContract: previewing writes nothing.

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

        // The instruments the contract is collected through: the lease's own
        // cheque register, in schedule order.
        List<Cheque> cheques = chequeRepository.findByLease_IdOrderBySeqNoAsc(lease.getId());

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
        String section4Rows = buildSection4Rows(lease, cheques);

        // Grand total = every line's net, face amounts. Reading the lines rather
        // than rentAmount + depositAmount + lease_charges is not a refactor: fees
        // are lines now, nothing writes lease_charges any more, and the contract
        // was silently omitting every fee while its total came up short.
        BigDecimal grandTotal = BigDecimal.ZERO;
        for (LeaseLine l : chargeLines(lease)) {
            grandTotal = grandTotal.add(nz(l.getNetAmount()));
        }

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
    // Package-private so the rendering tests can assert on the total without
    // driving a full PDF generation.
    String buildSection3Total(Lease lease) {
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal totalVat = BigDecimal.ZERO;
        BigDecimal totalWithVat = BigDecimal.ZERO;

        List<Object[]> rows = new ArrayList<>();
        for (LeaseLine l : chargeLines(lease)) {
            rows.add(new Object[]{nz(l.getNetAmount()), vatOn(l)});
        }
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
        List<LeaseLine> lines = chargeLines(lease);
        for (LeaseLine l : lines) {
            sNo = appendSection3Row(sb, sNo, labelOf(l), l.getNetAmount(), vatOn(l));
        }
        appendRentFreeRows(sb, lease, lines);
        return sb.toString();
    }

    private com.datagami.rentaxis.domain.repository.LeaseRentFreePeriodRepository rentFreePeriods;

    @org.springframework.beans.factory.annotation.Autowired
    public void setRentFreePeriods(com.datagami.rentaxis.domain.repository.LeaseRentFreePeriodRepository repo) {
        this.rentFreePeriods = repo;
    }

    /**
     * Spec §4b: each rent-free window, its days and the concession, under the
     * charges — the rent row above already shows the payable rent. EN and AR.
     */
    private void appendRentFreeRows(StringBuilder sb, Lease lease, List<LeaseLine> lines) {
        if (rentFreePeriods == null) return;
        var periods = rentFreePeriods.findByLease_IdOrderByFromDateAsc(lease.getId());
        if (periods.isEmpty()) return;
        LeaseLine rent = com.datagami.rentaxis.core.service.LeaseService.contractRentLine(lease, lines);
        if (rent == null) return;
        long termDays = java.time.temporal.ChronoUnit.DAYS.between(lease.getStartDate(), lease.getEndDate()) + 1;
        // Numeric dates: the same text reads correctly in the Arabic line.
        java.time.format.DateTimeFormatter dmy = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");
        for (var p : periods) {
            long days = java.time.temporal.ChronoUnit.DAYS.between(p.getFromDate(), p.getToDate()) + 1;
            BigDecimal concession = com.datagami.rentaxis.core.service.LeaseService.concessionOf(p, rent.getGrossAmount(), termDays);
            String en = "Rent-free period " + p.getFromDate().format(dmy) + " – " + p.getToDate().format(dmy)
                    + " (" + days + " days): AED " + formatAmount(concession)
                    + " off the headline rent of AED " + formatAmount(rent.getGrossAmount());
            String ar = "فترة إعفاء من الإيجار " + p.getFromDate().format(dmy) + " – " + p.getToDate().format(dmy)
                    + " (" + days + " يوماً): خصم " + formatAmount(concession)
                    + " درهم من الإيجار الأساسي البالغ " + formatAmount(rent.getGrossAmount()) + " درهم";
            appendNoteRow(sb, en, ar);
        }
    }

    /** A full-width explanatory row in Section 3, English then Arabic. */
    private void appendNoteRow(StringBuilder sb, String en, String ar) {
        sb.append("<tr>")
                .append("<td class=\"center\"></td>")
                .append("<td colspan=\"5\" style=\"font-size:9px;\">").append(escapeUserText(en))
                .append("<br/><span dir=\"rtl\">").append(escapeUserText(ar)).append("</span></td>")
                .append("</tr>");
    }

    /**
     * The lease's charged particulars, in the order they were entered — which is
     * the order the contract lists them in. Rent first is a property of how the
     * lease was built, not something imposed here.
     */
    private List<LeaseLine> chargeLines(Lease lease) {
        return leaseLineRepository.findByLease_IdOrderBySeqNoAsc(lease.getId());
    }

    /** The particular's name as the catalogue spells it. */
    private static String labelOf(LeaseLine line) {
        return line.getChargeType() != null ? line.getChargeType().getNameEn() : null;
    }

    /**
     * A deposit is refundable money held, never a supply, so it never carries VAT
     * whatever the line says. Every other line is taken at its word — the line's
     * own flag, not the lease's {@code rentVatApplicable}, because the flag was
     * copied onto the line when it was created and may have been overridden since.
     *
     * <p>Delegated to {@link LeaseVat} rather than restated: the contract the
     * renter signs, the cheque grid that collects it and the posting journal that
     * books it have to agree, and they only do so if there is one definition of
     * which lines are taxable. Zero-amount rows are skipped by the callers, so
     * "no VAT because the net is zero" never reaches the page.</p>
     */
    private static boolean vatOn(LeaseLine line) {
        return LeaseVat.vatOf(line).signum() > 0;
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
                .append("<td>").append(escapeUserText(label)).append("</td>")
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
     * Section 4 (Payment Details): the instruments the renter actually hands over.
     *
     * <p>Rendered from the lease's cheque register rather than a schedule, because
     * the register <em>is</em> the list of instruments — the schedule was a derived
     * plan and a contract that printed one while the renter wrote cheques against
     * the other is exactly the disagreement this rewrite removes.</p>
     *
     * <p><b>Which rows.</b> A contract is generated for a DRAFT or
     * PENDING_SIGNATURE lease, where the grid rows are still {@code DRAFT}: those
     * are what the contract is proposing, so they print. Once a lease is on the
     * books the rows have registered and a DRAFT row would be a half-finished
     * edit, so every non-cancelled row prints instead. Cancelled, returned and
     * superseded instruments never print — the contract is a statement of what is
     * being collected, not a history of what failed.</p>
     *
     * <p>Ordered by the register's own position, not by the date on the paper.
     * That is the order the grid was typed in and the order Section 3 lists the
     * charges in, so the two tables read together: rent instalments first, then
     * the deposit and the booking cheque, which are dated at signing and would
     * otherwise jump to the top of a date-sorted list.</p>
     */
    public String buildSection4Rows(Lease lease, List<Cheque> cheques) {
        if (cheques == null || cheques.isEmpty()) return "";

        boolean draftContract = lease == null
                || lease.getStatus() == LeaseStatus.DRAFT
                || lease.getStatus() == LeaseStatus.PENDING_SIGNATURE;
        List<Cheque> printable = new ArrayList<>();
        for (Cheque c : cheques) {
            boolean include = draftContract
                    ? c.getStatus() == ChequeStatus.DRAFT
                    : c.getStatus() != ChequeStatus.CANCELLED
                        && c.getStatus() != ChequeStatus.RETURNED
                        && c.getStatus() != ChequeStatus.REPLACED;
            if (include) printable.add(c);
        }
        printable.sort(Comparator.comparingInt(Cheque::getSeqNo)
                .thenComparing(Cheque::getChequeDate, Comparator.nullsLast(Comparator.naturalOrder())));

        StringBuilder sb = new StringBuilder();
        int sNo = 1;
        for (Cheque c : printable) {
            appendSection4Row(sb, sNo++, c);
        }
        return sb.toString();
    }

    /**
     * One row. "In Favour Of" carries the instrument's narration — "Rent - 1st
     * Installment | SD" — which is what the grid folded the deposits and fees into
     * and therefore what the cheque is actually for.
     */
    private void appendSection4Row(StringBuilder sb, int sNo, Cheque c) {
        sb.append("<tr>")
                .append("<td class=\"center\">").append(sNo).append("</td>")
                .append("<td>").append(escapeUserText(c.getChequeNumber())).append("</td>")
                .append("<td>").append(c.getChequeDate() != null ? formatDate(c.getChequeDate()) : "").append("</td>")
                .append("<td>").append(escapeUserText(c.getNarration())).append("</td>")
                .append("<td>").append(escapeUserText(c.getPayeeBank())).append("</td>")
                .append("<td class=\"num\">").append(formatAmount(nz(c.getAmount()))).append("</td>")
                .append("</tr>");
    }

    /**
     * HTML-escape a user-provided string before it is embedded in the contract
     * HTML template. Returns "" for null. Apply to every value that originated
     * from user input (names, addresses, phone numbers, cheque numbers, etc.).
     */
    private static String escapeUserText(String s) {
        // UTF-8: escape only the markup characters (< > & " '). The default
        // (ISO-8859-1) turns an em dash, curly quotes or accented letters into
        // HTML-4 named entities (&mdash;, &rsquo;, &eacute;), which the renderer's
        // XML parser does not know, so the whole PDF failed with a 500. The "—"
        // shown for a renewal's missing contract number did exactly that (#75).
        return s == null ? "" : HtmlUtils.htmlEscape(s, "UTF-8");
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

            // Only inline data: URIs load; no http(s), no file:, no jar:.
            com.datagami.rentaxis.core.util.PdfResourcePolicy.apply(builder);
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
    private void deleteStoredFile(UUID tenantId, String documentUrl) {
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
                String expectedContainer = containerPrefix + tenantId;
                if (!expectedContainer.equals(containerName)) {
                    log.warn("Skipping contract blob outside tenant container {}: {}",
                            expectedContainer, documentUrl);
                    return;
                }
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
                Path root = Path.of(storagePath).toAbsolutePath().normalize();
                Path p = Path.of(documentUrl).toAbsolutePath().normalize();
                if (!p.startsWith(root)) {
                    log.warn("Skipping contract file outside configured storage path {}: {}", root, p);
                    return;
                }
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

    /**
     * Best-effort removal of the exact contract documents captured before a
     * tenant is deleted. Every path is tenant/container scoped by
     * {@link #deleteStoredFile(UUID, String)} before any external mutation.
     */
    public void cleanupTenantDocuments(UUID tenantId, List<String> documentUrls) {
        if (tenantId == null || documentUrls == null) return;
        documentUrls.forEach(url -> deleteStoredFile(tenantId, url));
    }

    /**
     * The contract for one lease, as a PDF (#38): the stored, generated contract
     * when there is one, otherwise the same template rendered on the fly from the
     * posted lease.
     *
     * <p>Why the fallback: a renewal is posted without a generated contract, so a
     * renter in their second year had nothing to download — the only stored
     * contract sat on the RENEWED predecessor. Rendering writes nothing (the same
     * path as {@link #previewContract}) and only for a <em>posted</em> contract;
     * a draft has not been issued to anybody.</p>
     *
     * <p>Access is {@link LeaseAccessPolicy#requireReadable}: a renter reaches only
     * a lease whose renter is them, a property manager only their buildings, and
     * everyone else gets the same 404 as for a lease that does not exist. The
     * tenant comparison is explicit as well, because a primary-key load is only
     * narrowed by the filter inside a transaction.</p>
     */
    @Transactional(readOnly = true)
    public byte[] currentContractPdf(UUID leaseId) {
        UUID tenantId = TenantContextHolder.getTenantId();
        Lease lease = leaseRepository.findById(leaseId)
                .filter(l -> tenantId == null || tenantId.equals(l.getTenantId()))
                .orElse(null);
        leaseAccessPolicy.requireReadable(lease);

        Optional<LeaseDocument> stored = leaseDocumentRepository.findByLeaseId(leaseId).stream()
                .filter(d -> d.getType() == DocumentType.CONTRACT)
                .findFirst();
        if (stored.isPresent()) {
            return getDocumentContent(stored.get().getId());
        }
        if (lease.getPostedAt() == null) {
            throw new NotFoundException("No contract has been issued for this lease yet");
        }
        String number = lease.getContractNumber() != null ? String.valueOf(lease.getContractNumber()) : "—";
        try {
            return renderPdf(renderContractHtml(lease, number));
        } catch (NotFoundException e) {
            throw e;
        } catch (RuntimeException e) {
            // A contract that cannot be produced is the caller's answer, not a
            // server fault page (#75). Logged with the lease so it can be fixed.
            log.error("Could not render the contract for lease {}", leaseId, e);
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "The contract for this lease could not be produced. Contact your landlord.");
        }
    }

    @Transactional(readOnly = true)
    public List<LeaseDocumentDTO> getDocuments(UUID leaseId) {
        // Granted to RENTER, and nothing below asked whose lease it is — the
        // same exposure as lease attachments, for generated contracts.
        leaseAccessPolicy.requireReadable(leaseRepository.findById(leaseId).orElse(null));

        return leaseDocumentRepository.findByLeaseId(leaseId).stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public byte[] getDocumentContent(UUID docId) {
        LeaseDocument doc = leaseDocumentRepository.findById(docId)
                .orElseThrow(() -> new NotFoundException("Document not found"));

        // The download takes a document id directly, so guarding the list alone
        // would leave it reachable.
        leaseAccessPolicy.requireReadable(doc.getLease());

        String url = doc.getDocumentUrl();
        // documentUrl can contain a bearer-style SAS signature. Never write it
        // to application logs; the document id is enough to correlate failures.
        log.info("Downloading document {}", docId);

        // Azure Blob URL
        if (url.startsWith("https://") && url.contains(".blob.core.windows.net")) {
            try {
                return downloadFromAzure(url);
            } catch (Exception e) {
                log.error("Azure download failed for document {}: {}", docId, e.getMessage(), e);
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

    String extractContainerName(String blobUrl) {
        String rawPath = azureBlobRawPath(blobUrl);
        int slash = rawPath.indexOf('/', 1);
        String container = slash > 1 ? rawPath.substring(1, slash) : rawPath.substring(1);
        if (container.isBlank()) {
            throw new RuntimeException("Invalid Azure Blob URL (missing container)");
        }
        return URLDecoder.decode(container, StandardCharsets.UTF_8);
    }

    String extractBlobPath(String blobUrl) {
        String rawPath = azureBlobRawPath(blobUrl);
        int firstSlash = rawPath.indexOf('/', 1);
        if (firstSlash < 0 || firstSlash == rawPath.length() - 1) {
            throw new RuntimeException("Invalid Azure Blob URL (missing blob path)");
        }
        // URI#getRawPath deliberately excludes the SAS query string. The old
        // substring parser included ?sv=...&sig=... in the blob name, which
        // made every backend-authenticated download return BlobNotFound.
        // Decode the encoded slash emitted by BlobClient#getBlobUrl().
        return URLDecoder.decode(rawPath.substring(firstSlash + 1), StandardCharsets.UTF_8);
    }

    private String azureBlobRawPath(String blobUrl) {
        java.net.URI uri;
        try {
            uri = java.net.URI.create(blobUrl);
        } catch (IllegalArgumentException ex) {
            throw new RuntimeException("Invalid Azure Blob URL", ex);
        }
        String host = uri.getHost();
        String rawPath = uri.getRawPath();
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || host == null
                || !host.toLowerCase(java.util.Locale.ROOT).endsWith(".blob.core.windows.net")
                || rawPath == null
                || !rawPath.startsWith("/")
                || rawPath.length() == 1) {
            throw new RuntimeException("Invalid Azure Blob URL");
        }
        return rawPath;
    }

    private LeasePayload buildLeasePayload(Lease lease, String contractUrl) {
        BigDecimal monthly = LeaseService.monthlyRentOf(lease);
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
