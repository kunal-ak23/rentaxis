package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.bank.BankRecDTOs;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.report.statement.ReportCsv;
import com.datagami.rentaxis.core.service.bank.*;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.BankAccount;
import com.datagami.rentaxis.domain.repository.BankAccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;

/**
 * Finance → Bank reconciliation (finance-ops spec §3): leaf sets, the column
 * mapping, statement imports, the matching workspace and the create-from-line
 * actions. SUPER_ADMIN (acting in an organisation), TENANT_ADMIN and
 * ACCOUNTANT; a property manager has none of it and keeps the cheque actions on
 * the cheque register.
 */
@RestController
@RequestMapping("/api/v1/finance/bank-reconciliation")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT')")
public class BankReconciliationController {

    private final BankAccountLedgerService ledgers;
    private final BankStatementImportService imports;
    private final BankMatchService matches;
    private final BankLineActionService actions;
    private final BankAccountRepository bankAccounts;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;
    private final com.datagami.rentaxis.core.service.bank.BankReconciliationService recs;
    private final com.datagami.rentaxis.core.service.bank.BankReconciliationPdfRenderer recPdf;

    public BankReconciliationController(BankAccountLedgerService ledgers, BankStatementImportService imports,
                                        BankMatchService matches, BankLineActionService actions,
                                        BankAccountRepository bankAccounts, NamedParameterJdbcTemplate jdbc,
                                        ObjectMapper json,
                                        com.datagami.rentaxis.core.service.bank.BankReconciliationService recs,
                                        com.datagami.rentaxis.core.service.bank.BankReconciliationPdfRenderer recPdf) {
        this.recs = recs;
        this.recPdf = recPdf;
        this.ledgers = ledgers;
        this.imports = imports;
        this.matches = matches;
        this.actions = actions;
        this.bankAccounts = bankAccounts;
        this.jdbc = jdbc;
        this.json = json;
    }

    /** The list page: every bank account with its leaves, last import and unmatched count. */
    @GetMapping("/bank-accounts")
    @Transactional(readOnly = true)
    public ResponseEntity<List<BankRecDTOs.BankAccountRow>> list() {
        UUID t = requireTenantSelected();
        List<BankRecDTOs.BankAccountRow> out = new ArrayList<>();
        for (BankAccount b : bankAccounts.findAllByOrderByBankNameAsc()) {
            if (!t.equals(b.getTenantId())) continue;
            List<BankRecDTOs.Leaf> leaves = ledgers.leaves(b.getId());
            List<Map<String, Object>> last = jdbc.queryForList("""
                    select i.imported_at, i.file_name,
                           (select max(l.txn_date) from bank_statement_lines l where l.tenant_id = :t and l.bank_account_id = :b) as last_line
                    from bank_statement_imports i where i.tenant_id = :t and i.bank_account_id = :b
                    order by i.imported_at desc limit 1""",
                    new MapSqlParameterSource("t", t).addValue("b", b.getId()));
            Integer profile = jdbc.queryForObject("select count(*) from bank_statement_profiles where tenant_id = :t and bank_account_id = :b",
                    new MapSqlParameterSource("t", t).addValue("b", b.getId()), Integer.class);
            Map<String, Object> l = last.isEmpty() ? Map.of() : last.get(0);
            Map<String, Object> lock = jdbc.queryForMap("""
                    select b.reconciled_through, b.rec_start_date,
                           (select r.id from bank_reconciliations r where r.tenant_id = :t and r.bank_account_id = b.id
                              and r.status = 'DRAFT') as draft_id,
                           (select r.id from bank_reconciliations r where r.tenant_id = :t and r.bank_account_id = b.id
                              and r.status = 'FINALIZED' order by r.period_to desc limit 1) as finalized_id
                    from bank_accounts b where b.id = :b and b.tenant_id = :t""",
                    new MapSqlParameterSource("t", t).addValue("b", b.getId()));
            out.add(new BankRecDTOs.BankAccountRow(b.getId(), b.getBankName(), b.getAccountNumber(), b.getIban(),
                    b.getCurrency(), b.getBankTrn(), b.isActive(), leaves, leaves.isEmpty(),
                    l.get("imported_at") == null ? null : ((java.sql.Timestamp) l.get("imported_at")).toInstant(),
                    (String) l.get("file_name"),
                    l.get("last_line") == null ? null : ((java.sql.Date) l.get("last_line")).toLocalDate(),
                    matches.unmatchedCount(b.getId()), profile != null && profile > 0,
                    lock.get("reconciled_through") == null ? null : ((java.sql.Date) lock.get("reconciled_through")).toLocalDate(),
                    lock.get("rec_start_date") == null ? null : ((java.sql.Date) lock.get("rec_start_date")).toLocalDate(),
                    (UUID) lock.get("draft_id"), (UUID) lock.get("finalized_id")));
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/bank-accounts/{id}/ledgers")
    public ResponseEntity<List<BankRecDTOs.Leaf>> ledgers(@PathVariable UUID id) {
        requireTenantSelected();
        return ResponseEntity.ok(ledgers.leaves(id));
    }

    @PutMapping("/bank-accounts/{id}/ledgers")
    public ResponseEntity<List<BankRecDTOs.Leaf>> setLedgers(@PathVariable UUID id, @Valid @RequestBody BankRecDTOs.LedgerSetInput in) {
        requireTenantSelected();
        return ResponseEntity.ok(ledgers.setLeaves(id, in.accountIds()));
    }

    @PutMapping("/bank-accounts/{id}/bank-trn")
    public ResponseEntity<Map<String, Object>> setBankTrn(@PathVariable UUID id, @Valid @RequestBody BankRecDTOs.BankTrnInput in) {
        requireTenantSelected();
        BankAccount b = ledgers.setBankTrn(id, in.bankTrn());
        Map<String, Object> body = new HashMap<>();
        body.put("id", b.getId());
        body.put("bankTrn", b.getBankTrn());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/bank-accounts/{id}/profile")
    public ResponseEntity<BankRecDTOs.Profile> profile(@PathVariable UUID id) {
        requireTenantSelected();
        return imports.profile(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PutMapping("/bank-accounts/{id}/profile")
    public ResponseEntity<BankRecDTOs.Profile> saveProfile(@PathVariable UUID id, @RequestBody BankRecDTOs.Profile in) {
        requireTenantSelected();
        return ResponseEntity.ok(imports.saveProfile(id, in));
    }

    /**
     * Multipart: {@code file}, optional {@code profile} (JSON, the wizard's unsaved
     * mapping) and {@code dryRun}. Returns PROFILE_REQUIRED, INVALID, PREVIEW or IMPORTED.
     */
    @PostMapping(value = "/bank-accounts/{id}/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BankRecDTOs.ImportResult> importFile(@PathVariable UUID id,
                                                               @RequestParam("file") MultipartFile file,
                                                               @RequestParam(value = "profile", required = false) String profile,
                                                               @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun)
            throws IOException {
        requireTenantSelected();
        if (file.getSize() > BankStatementImportService.MAX_FILE_BYTES) {
            throw BankRecRefusal.refuse("fileTooLarge", "A statement file is at most 5 MB");
        }
        BankRecDTOs.Profile override = null;
        if (profile != null && !profile.isBlank()) {
            try {
                override = json.readValue(profile, BankRecDTOs.Profile.class);
            } catch (IOException e) {
                throw BankRecRefusal.refuse("mappingUnreadable", "The column mapping could not be read");
            }
        }
        return ResponseEntity.ok(imports.importFile(id, file.getOriginalFilename(), file.getBytes(), override, dryRun));
    }

    @GetMapping("/bank-accounts/{id}/imports")
    public ResponseEntity<List<BankRecDTOs.ImportRow>> history(@PathVariable UUID id) {
        requireTenantSelected();
        return ResponseEntity.ok(imports.history(id));
    }

    @DeleteMapping("/imports/{id}")
    public ResponseEntity<Void> deleteImport(@PathVariable UUID id) {
        requireTenantSelected();
        imports.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/bank-accounts/{id}/workspace")
    public ResponseEntity<BankRecDTOs.Workspace> workspace(
            @PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "ALL") String state) {
        requireTenantSelected();
        return ResponseEntity.ok(matches.workspace(id, from, to, state));
    }

    /**
     * The statement lines as CSV, with their match state. Every cell goes through
     * {@link ReportCsv#encode}'s formula-injection escaping: descriptions and
     * references come from the bank's file.
     */
    @GetMapping(value = "/bank-accounts/{id}/lines.csv", produces = "text/csv")
    public ResponseEntity<byte[]> linesCsv(
            @PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        requireTenantSelected();
        BankRecDTOs.Workspace w = matches.workspace(id, from, to, "ALL");
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Date", "Value date", "Description", "Reference", "Cheque no", "Amount", "Balance", "Match"));
        for (BankRecDTOs.StatementLine l : w.statementLines()) {
            rows.add(List.of(dmy(l.txnDate()), dmy(l.valueDate()), l.description(), Objects.toString(l.reference(), ""),
                    Objects.toString(l.chequeNo(), ""), l.amount().toPlainString(),
                    l.runningBalance() == null ? "" : l.runningBalance().toPlainString(),
                    l.matchStatus() == null ? "UNMATCHED" : l.matchStatus()));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"statement-lines.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(ReportCsv.encode(rows, false));
    }

    private static String dmy(LocalDate d) {
        return d == null ? "" : d.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    @PostMapping("/bank-accounts/{id}/auto-match")
    public ResponseEntity<BankRecDTOs.AutoMatchResult> autoMatch(
            @PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        requireTenantSelected();
        return ResponseEntity.ok(matches.autoMatch(id, from, to));
    }

    @PostMapping("/matches")
    public ResponseEntity<BankRecDTOs.Match> manual(@RequestBody BankRecDTOs.ManualMatchInput in) {
        requireTenantSelected();
        return ResponseEntity.ok(matches.manual(in));
    }

    @PostMapping("/matches/{id}/confirm")
    public ResponseEntity<BankRecDTOs.Match> confirm(@PathVariable UUID id) {
        requireTenantSelected();
        return ResponseEntity.ok(matches.confirm(id));
    }

    @PostMapping("/matches/confirm")
    public ResponseEntity<Map<String, Integer>> confirmAll(
            @RequestParam UUID bankAccountId,
            @RequestParam(defaultValue = "HIGH") String confidence,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        requireTenantSelected();
        return ResponseEntity.ok(Map.of("confirmed", matches.confirmAll(bankAccountId, confidence, from, to)));
    }

    @DeleteMapping("/matches/{id}")
    public ResponseEntity<BankRecDTOs.Match> undo(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean reverseCreated,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reverseOn,
            @RequestParam(required = false) String reason) {
        requireTenantSelected();
        return ResponseEntity.ok(matches.undo(id, reverseCreated, reverseOn, reason));
    }

    @GetMapping("/lines/{id}/candidates")
    public ResponseEntity<BankRecDTOs.LineCandidates> candidates(@PathVariable UUID id) {
        requireTenantSelected();
        return ResponseEntity.ok(actions.candidates(id));
    }

    @PostMapping("/lines/actions/clear-cheques")
    public ResponseEntity<BankRecDTOs.ActionResult> clearCheques(@Valid @RequestBody BankRecDTOs.ClearChequesInput in) {
        requireTenantSelected();
        return ResponseEntity.ok(actions.clearCheques(in));
    }

    @PostMapping("/lines/actions/receive")
    public ResponseEntity<BankRecDTOs.ActionResult> receive(@Valid @RequestBody BankRecDTOs.ReceiveInput in) {
        requireTenantSelected();
        return ResponseEntity.ok(actions.receive(in));
    }

    @PostMapping("/lines/actions/bounce")
    public ResponseEntity<BankRecDTOs.ActionResult> bounce(@Valid @RequestBody BankRecDTOs.BounceInput in) {
        requireTenantSelected();
        return ResponseEntity.ok(actions.bounce(in));
    }

    @PostMapping("/lines/actions/present")
    public ResponseEntity<BankRecDTOs.ActionResult> present(@Valid @RequestBody BankRecDTOs.PresentInput in) {
        requireTenantSelected();
        return ResponseEntity.ok(actions.present(in));
    }

    @PostMapping("/lines/actions/post")
    public ResponseEntity<BankRecDTOs.ActionResult> post(@Valid @RequestBody BankRecDTOs.PostLinesInput in) {
        requireTenantSelected();
        return ResponseEntity.ok(actions.post(in));
    }

    // ------------------------------------------------------------------ §4 reconciliations

    @GetMapping("/bank-accounts/{id}/reconciliations")
    public ResponseEntity<List<BankRecDTOs.ReconciliationRow>> reconciliations(@PathVariable UUID id) {
        requireTenantSelected();
        return ResponseEntity.ok(recs.list(id));
    }

    @PostMapping("/bank-accounts/{id}/reconciliations")
    public ResponseEntity<BankRecDTOs.Reconciliation> createReconciliation(@PathVariable UUID id,
                                                                          @Valid @RequestBody BankRecDTOs.ReconciliationInput in) {
        requireTenantSelected();
        return ResponseEntity.ok(recs.create(id, in));
    }

    @GetMapping("/reconciliations/{id}")
    public ResponseEntity<BankRecDTOs.Reconciliation> reconciliation(@PathVariable UUID id) {
        requireTenantSelected();
        return ResponseEntity.ok(recs.get(id));
    }

    @PutMapping("/reconciliations/{id}")
    public ResponseEntity<BankRecDTOs.Reconciliation> updateReconciliation(@PathVariable UUID id,
                                                                          @Valid @RequestBody BankRecDTOs.ReconciliationInput in) {
        requireTenantSelected();
        return ResponseEntity.ok(recs.update(id, in));
    }

    @DeleteMapping("/reconciliations/{id}")
    public ResponseEntity<Void> discardReconciliation(@PathVariable UUID id) {
        requireTenantSelected();
        recs.discard(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reconciliations/{id}/finalize")
    public ResponseEntity<BankRecDTOs.Reconciliation> finalizeReconciliation(@PathVariable UUID id) {
        requireTenantSelected();
        return ResponseEntity.ok(recs.finalizeRec(id));
    }

    /** Spec §4: TENANT_ADMIN (and SUPER_ADMIN acting in the organisation) only, with a reason. */
    @PostMapping("/reconciliations/{id}/reopen")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<BankRecDTOs.Reconciliation> reopenReconciliation(@PathVariable UUID id,
                                                                          @Valid @RequestBody BankRecDTOs.ReopenInput in) {
        requireTenantSelected();
        return ResponseEntity.ok(recs.reopen(id, in.reason()));
    }

    @GetMapping(value = "/reconciliations/{id}.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> reconciliationPdf(@PathVariable UUID id, @RequestParam(defaultValue = "en") String lang) {
        requireTenantSelected();
        BankRecDTOs.Reconciliation r = recs.get(id);
        String l = "ar".equals(lang) ? "ar" : "en";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"bank-reconciliation-" + r.periodTo() + "-" + l + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(recPdf.render(r, l));
    }

    /** The statement as CSV. Every cell goes through {@link ReportCsv#encode}'s formula-injection escaping. */
    @GetMapping(value = "/reconciliations/{id}.csv", produces = "text/csv")
    public ResponseEntity<byte[]> reconciliationCsv(@PathVariable UUID id) {
        requireTenantSelected();
        BankRecDTOs.Reconciliation r = recs.get(id);
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Bank reconciliation statement", r.bankLabel(), dmy(r.periodFrom()) + " - " + dmy(r.periodTo()), r.status()));
        rows.add(List.of("Figure", "Amount"));
        rows.add(List.of("Balance per bank statement", plain(r.statementClosing())));
        rows.add(List.of("Deposits in transit", plain(r.depositsInTransit())));
        rows.add(List.of("Unpresented payments", plain(r.unpresentedPayments())));
        rows.add(List.of("Booked after the period", plain(r.bookedAfterPeriod())));
        rows.add(List.of("Adjusted bank balance", plain(r.adjustedBank())));
        rows.add(List.of("Balance per books", plain(r.bookBalance())));
        rows.add(List.of("Unrecorded statement items", plain(r.unrecordedCredits().subtract(r.unrecordedDebits()))));
        rows.add(List.of("Adjusted book balance", plain(r.adjustedBook())));
        rows.add(List.of("Difference", plain(r.difference())));
        rows.add(List.of());
        rows.add(List.of("Section", "Date", "Document", "Details", "Cheque no", "Amount", "Cleared without statement evidence"));
        csvItems(rows, "Deposit in transit", r.depositsInTransitItems());
        csvItems(rows, "Unpresented payment", r.unpresentedItems());
        csvItems(rows, "Booked after the period", r.bookedAfterItems());
        csvItems(rows, "Unrecorded statement item", r.unrecordedItems());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"bank-reconciliation-" + r.periodTo() + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(ReportCsv.encode(rows, false));
    }

    private static void csvItems(List<List<String>> rows, String section, List<BankRecDTOs.RecItem> items) {
        for (BankRecDTOs.RecItem i : items) {
            rows.add(List.of(section, dmy(i.date()), Objects.toString(i.document(), ""), Objects.toString(i.narration(), ""),
                    Objects.toString(i.chequeNo(), ""), plain(i.amount()), i.withoutEvidence() ? "yes" : ""));
        }
    }

    private static String plain(java.math.BigDecimal v) {
        return v == null ? "" : v.toPlainString();
    }

    @GetMapping("/bank-accounts/{id}/opening-items")
    public ResponseEntity<List<BankRecDTOs.OpeningItem>> openingItems(@PathVariable UUID id) {
        requireTenantSelected();
        return ResponseEntity.ok(recs.openingItems(id));
    }

    @PostMapping("/bank-accounts/{id}/opening-items")
    public ResponseEntity<BankRecDTOs.OpeningItem> addOpeningItem(@PathVariable UUID id,
                                                                 @Valid @RequestBody BankRecDTOs.OpeningItemInput in) {
        requireTenantSelected();
        return ResponseEntity.ok(recs.addOpeningItem(id, in));
    }

    @PutMapping("/bank-accounts/{id}/opening-items/{itemId}")
    public ResponseEntity<BankRecDTOs.OpeningItem> updateOpeningItem(@PathVariable UUID id, @PathVariable UUID itemId,
                                                                    @Valid @RequestBody BankRecDTOs.OpeningItemInput in) {
        requireTenantSelected();
        return ResponseEntity.ok(recs.updateOpeningItem(id, itemId, in));
    }

    @DeleteMapping("/bank-accounts/{id}/opening-items/{itemId}")
    public ResponseEntity<Void> deleteOpeningItem(@PathVariable UUID id, @PathVariable UUID itemId) {
        requireTenantSelected();
        recs.deleteOpeningItem(id, itemId);
        return ResponseEntity.noContent().build();
    }

    private static UUID requireTenantSelected() {
        UUID t = TenantContextHolder.getTenantId();
        if (t == null) throw BankRecRefusal.refuse("selectOrganisation", "Select an organisation first");
        return t;
    }
}
