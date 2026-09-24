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

    public BankReconciliationController(BankAccountLedgerService ledgers, BankStatementImportService imports,
                                        BankMatchService matches, BankLineActionService actions,
                                        BankAccountRepository bankAccounts, NamedParameterJdbcTemplate jdbc,
                                        ObjectMapper json) {
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
            out.add(new BankRecDTOs.BankAccountRow(b.getId(), b.getBankName(), b.getAccountNumber(), b.getIban(),
                    b.getCurrency(), b.getBankTrn(), b.isActive(), leaves, leaves.isEmpty(),
                    l.get("imported_at") == null ? null : ((java.sql.Timestamp) l.get("imported_at")).toInstant(),
                    (String) l.get("file_name"),
                    l.get("last_line") == null ? null : ((java.sql.Date) l.get("last_line")).toLocalDate(),
                    matches.unmatchedCount(b.getId()), profile != null && profile > 0));
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
            throw new BusinessRuleViolationException("A statement file is at most 5 MB");
        }
        BankRecDTOs.Profile override = null;
        if (profile != null && !profile.isBlank()) {
            try {
                override = json.readValue(profile, BankRecDTOs.Profile.class);
            } catch (IOException e) {
                throw new BusinessRuleViolationException("The column mapping could not be read");
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

    private static UUID requireTenantSelected() {
        UUID t = TenantContextHolder.getTenantId();
        if (t == null) throw new BusinessRuleViolationException("Select an organisation first");
        return t;
    }
}
