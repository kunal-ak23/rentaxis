package com.datagami.rentaxis.core.service.bank;

import com.datagami.rentaxis.api.dto.bank.BankRecDTOs;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.WorkbookGuard;
import com.datagami.rentaxis.domain.entity.BankAccount;
import com.datagami.rentaxis.domain.entity.BankStatementImport;
import com.datagami.rentaxis.domain.entity.BankStatementProfile;
import com.datagami.rentaxis.domain.repository.BankStatementImportRepository;
import com.datagami.rentaxis.domain.repository.BankStatementProfileRepository;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Statement import (finance-ops spec §3 "Import"): refusals, the profile, parse,
 * order, running-balance continuity, de-duplication by line hash, storage.
 *
 * <p>A dry run does everything but write, so the wizard can show the parsed rows
 * before anything is committed. An import that fails any check writes nothing.</p>
 */
@Service
@Slf4j
public class BankStatementImportService {

    public static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    static final int PREVIEW_GRID_ROWS = 20;
    static final int PREVIEW_ROWS = 500;

    private final BankAccountLedgerService ledgers;
    private final BankStatementProfileRepository profiles;
    private final BankStatementImportRepository imports;
    private final CsvStatementParser csv;
    private final XlsxStatementParser xlsx;
    private final NamedParameterJdbcTemplate jdbc;
    private final java.time.Clock clock;
    private final com.datagami.rentaxis.core.service.ledger.BankLockService bankLock;

    @Value("${AZURE_STORAGE_CONNECTION_STRING:}")
    private String azureConnectionString;
    @Value("${AZURE_STORAGE_CONTAINER_PREFIX:tenant-}")
    private String containerPrefix;
    @Value("${rentaxis.assets.storage-path:./data/assets}")
    private String localStoragePath;

    public BankStatementImportService(BankAccountLedgerService ledgers, BankStatementProfileRepository profiles,
                                      BankStatementImportRepository imports, CsvStatementParser csv,
                                      XlsxStatementParser xlsx, NamedParameterJdbcTemplate jdbc, java.time.Clock clock,
                                      com.datagami.rentaxis.core.service.ledger.BankLockService bankLock) {
        this.bankLock = bankLock;
        this.clock = clock;
        this.ledgers = ledgers;
        this.profiles = profiles;
        this.imports = imports;
        this.csv = csv;
        this.xlsx = xlsx;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ profile

    @Transactional(readOnly = true)
    public Optional<BankRecDTOs.Profile> profile(UUID bankAccountId) {
        ledgers.requireBankAccount(bankAccountId);
        return profiles.findByBankAccountId(bankAccountId).map(BankStatementImportService::dto);
    }

    @Transactional
    public BankRecDTOs.Profile saveProfile(UUID bankAccountId, BankRecDTOs.Profile in) {
        ledgers.requireBankAccount(bankAccountId);
        BankStatementProfile p = profiles.findByBankAccountId(bankAccountId).orElseGet(BankStatementProfile::new);
        p.setBankAccountId(bankAccountId);
        apply(p, in);
        p.setUpdatedBy(currentUserId());
        p.setUpdatedAt(Instant.now());
        return dto(profiles.save(p));
    }

    static BankStatementProfile apply(BankStatementProfile p, BankRecDTOs.Profile in) {
        if (in == null) throw new BusinessRuleViolationException("A column mapping is required");
        if (in.fileKind() != null) p.setFileKind(enumOf(BankStatementProfile.FileKind.class, in.fileKind(), "file kind"));
        p.setSheetName(in.sheetName() == null || in.sheetName().isBlank() ? null : in.sheetName().trim());
        if (in.headerRow() != null) p.setHeaderRow(in.headerRow());
        if (in.firstDataRow() != null) p.setFirstDataRow(in.firstDataRow());
        if (p.getHeaderRow() < 1 || p.getFirstDataRow() <= p.getHeaderRow()) {
            throw new BusinessRuleViolationException("The first data row must come after the header row");
        }
        if (in.csvDelimiter() != null && !in.csvDelimiter().isEmpty()) {
            if (in.csvDelimiter().length() > 2) throw new BusinessRuleViolationException("The delimiter is one character");
            p.setCsvDelimiter(in.csvDelimiter());
        }
        // PR #353 review: the date format is the accountant's statement, never a guess.
        if (in.dateFormats() == null || in.dateFormats().isEmpty() || in.dateFormats().stream().allMatch(f -> f == null || f.isBlank())) {
            throw new BusinessRuleViolationException("Choose the statement's date format");
        }
        if (in.decimalSeparator() != null && !in.decimalSeparator().isBlank()) {
            if (!in.decimalSeparator().equals(".") && !in.decimalSeparator().equals(",")) {
                throw new BusinessRuleViolationException("The decimal separator is . or ,");
            }
            p.setDecimalSeparator(in.decimalSeparator());
        }
        {
            for (String f : in.dateFormats()) {
                try {
                    StatementValues.formatter(f);
                } catch (IllegalArgumentException e) {
                    throw new BusinessRuleViolationException("\"" + f + "\" is not a date format");
                }
            }
            p.setDateFormats(in.dateFormats().toArray(String[]::new));
        }
        Map<String, String> cols = new LinkedHashMap<>();
        if (in.columns() != null) {
            in.columns().forEach((k, v) -> {
                if (!StatementMapper.FIELDS.contains(k)) throw new BusinessRuleViolationException("Unknown column field " + k);
                if (v != null && !v.isBlank()) cols.put(k, v.trim());
            });
        }
        p.setColumns(cols);
        if (in.amountMode() != null) p.setAmountMode(enumOf(BankStatementProfile.AmountMode.class, in.amountMode(), "amount mode"));
        if (in.chequeNoPattern() != null && !in.chequeNoPattern().isBlank()) {
            if (in.chequeNoPattern().length() > 100) throw new BusinessRuleViolationException("The cheque number pattern is at most 100 characters");
            try {
                Pattern.compile(in.chequeNoPattern());
            } catch (PatternSyntaxException e) {
                throw new BusinessRuleViolationException("The cheque number pattern is not a valid expression");
            }
            p.setChequeNoPattern(in.chequeNoPattern());
        }
        if (in.matchWindowDays() != null) {
            if (in.matchWindowDays() < 0 || in.matchWindowDays() > 31) {
                throw new BusinessRuleViolationException("The match window is 0 to 31 days");
            }
            p.setMatchWindowDays(in.matchWindowDays());
        }
        return p;
    }

    static BankRecDTOs.Profile dto(BankStatementProfile p) {
        return new BankRecDTOs.Profile(p.getFileKind().name(), p.getSheetName(), p.getHeaderRow(), p.getFirstDataRow(),
                p.getCsvDelimiter(), List.of(p.getDateFormats()), p.getColumns(), p.getAmountMode().name(),
                p.getChequeNoPattern(), p.getMatchWindowDays(), p.getDecimalSeparator());
    }

    // ------------------------------------------------------------------ import

    /**
     * {@code profileOverride}: the wizard's unsaved mapping, used instead of the
     * saved one (a dry run with it is the wizard's live preview). {@code dryRun}:
     * parse and check, write nothing.
     */
    @Transactional
    public BankRecDTOs.ImportResult importFile(UUID bankAccountId, String fileName, byte[] bytes,
                                               BankRecDTOs.Profile profileOverride, boolean dryRun) {
        UUID t = BankAccountLedgerService.requireTenant();
        BankAccount bank = ledgers.requireBankAccount(bankAccountId);
        if (bank.getCurrency() != null && !"AED".equalsIgnoreCase(bank.getCurrency())) {
            throw new BusinessRuleViolationException("Multi-currency statements are out of scope; this account is in "
                    + bank.getCurrency());
        }
        if (bytes == null || bytes.length == 0) throw new BusinessRuleViolationException("The file is empty");
        if (bytes.length > MAX_FILE_BYTES) throw new BusinessRuleViolationException("A statement file is at most 5 MB");
        String name = fileName == null || fileName.isBlank() ? "statement" : fileName.trim();
        if (name.toLowerCase(Locale.ROOT).endsWith(".xls")) {
            throw new BusinessRuleViolationException("Save the statement as .xlsx or .csv; the old .xls format is not read");
        }
        // P2-2: the very same file again is refused outright, whatever the mapping says now.
        String sha = StatementValues.sha256(bytes);
        List<Map<String, Object>> same = jdbc.queryForList("""
                select file_name, imported_at, lines_read from bank_statement_imports
                where tenant_id = :t and bank_account_id = :b and file_sha256 = :s order by imported_at limit 1""",
                new MapSqlParameterSource("t", t).addValue("b", bankAccountId).addValue("s", sha));
        if (!same.isEmpty()) {
            Map<String, Object> prior = same.get(0);
            String on = ((java.sql.Timestamp) prior.get("imported_at")).toInstant().atZone(java.time.ZoneId.of("Asia/Dubai"))
                    .toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
            return new BankRecDTOs.ImportResult("ALREADY_IMPORTED", "This file was already imported on " + on
                    + " (" + prior.get("file_name") + "); nothing was imported", List.of(), List.of(), null, null, List.of(),
                    List.of(), List.of(), 0, 0, ((Number) prior.get("lines_read")).intValue(), null, null, null, null,
                    null, List.of(), null);
        }
        BankStatementProfile.FileKind kind = WorkbookGuard.looksLikeXlsx(bytes)
                ? BankStatementProfile.FileKind.XLSX : BankStatementProfile.FileKind.CSV;
        if (kind == BankStatementProfile.FileKind.CSV && looksBinary(bytes)) {
            throw new BusinessRuleViolationException("This file is neither a CSV nor an .xlsx workbook");
        }
        BankStatementProfile saved = profiles.findByBankAccountId(bankAccountId).orElse(null);
        BankStatementProfile profile = profileOverride != null
                ? apply(copyOf(saved, kind), profileOverride) : saved;
        StatementParser parser = kind == BankStatementProfile.FileKind.XLSX ? xlsx : csv;

        if (profile == null) {
            StatementGrid g = parser.read(bytes, null, null);
            return profileRequired("No column mapping for this bank account yet", g, kind, List.of());
        }
        if (profile.getFileKind() != kind) {
            StatementGrid g = parser.read(bytes, null, null);
            return profileRequired("The saved mapping is for " + profile.getFileKind() + " files; this one is " + kind,
                    g, kind, List.of());
        }
        StatementGrid grid;
        try {
            grid = parser.read(bytes, profile.getSheetName(), kind == BankStatementProfile.FileKind.CSV
                    ? profile.getCsvDelimiter() : null);
        } catch (BusinessRuleViolationException e) {
            if (kind == BankStatementProfile.FileKind.XLSX && profile.getSheetName() != null) {
                return profileRequired(e.getMessage(), parser.read(bytes, null, null), kind, List.of());
            }
            throw e;
        }
        StatementMapper.Result r = StatementMapper.map(grid, profile);
        if (!r.missingColumns().isEmpty()) {
            return profileRequired("The file's header row no longer matches the saved mapping", grid, kind,
                    r.missingColumns());
        }
        if (r.rows().size() > StatementParser.MAX_ROWS) throw CsvStatementParser.tooMany();
        if (!r.errors().isEmpty()) {
            return result("INVALID", null, grid, kind, r, r.errors(), r.warnings(), 0, 0, List.of(), null);
        }
        if (r.rows().isEmpty()) {
            return result("INVALID", null, grid, kind, r, List.of("No transaction lines were found"), r.warnings(),
                    0, 0, List.of(), null);
        }

        List<String> period = periodErrors(r.rows());
        if (!period.isEmpty()) {
            return result("INVALID", null, grid, kind, r, period, r.warnings(), 0, 0, List.of(), null);
        }
        r = withKnownChequeNumbers(t, bankAccountId, r);

        if (!dryRun) {
            // One import per bank account at a time: the de-duplication below reads,
            // then writes.
            jdbc.queryForList("select id from bank_accounts where id = :b and tenant_id = :t for update",
                    new MapSqlParameterSource("t", t).addValue("b", bankAccountId), UUID.class);
        }
        List<String> hashes = hashes(bankAccountId, r.rows());
        Set<String> existing = new HashSet<>();
        for (int i = 0; i < hashes.size(); i += 1000) {
            existing.addAll(jdbc.queryForList(
                    "select line_hash from bank_statement_lines where bank_account_id = :b and tenant_id = :t and line_hash in (:h)",
                    new MapSqlParameterSource("t", t).addValue("b", bankAccountId)
                            .addValue("h", hashes.subList(i, Math.min(hashes.size(), i + 1000))), String.class));
        }
        List<String> warnings = new ArrayList<>(r.warnings());
        continuityWarning(t, bankAccountId, r).ifPresent(warnings::add);
        driftWarning(t, bankAccountId, r.rows(), hashes, existing).ifPresent(warnings::add);
        int dup = 0;
        List<BankRecDTOs.PreviewRow> preview = new ArrayList<>();
        for (int i = 0; i < r.rows().size(); i++) {
            StatementMapper.Row row = r.rows().get(i);
            boolean d = existing.contains(hashes.get(i));
            if (d) dup++;
            if (preview.size() < PREVIEW_ROWS) {
                preview.add(new BankRecDTOs.PreviewRow(row.fileRow(), row.txnDate(), row.valueDate(), row.description(),
                        row.reference(), row.chequeNo(), row.amount(), row.balance(), d));
            }
        }
        int fresh = r.rows().size() - dup;
        // Spec §4: a new line inside a finalized reconciliation would change it.
        LocalDate locked = bankLock.reconciledThrough(bankAccountId).orElse(null);
        if (locked != null) {
            List<String> inside = new ArrayList<>();
            for (int i = 0; i < r.rows().size(); i++) {
                StatementMapper.Row row = r.rows().get(i);
                if (!existing.contains(hashes.get(i)) && !row.txnDate().isAfter(locked)) {
                    inside.add("Row " + row.fileRow() + " (" + row.txnDate().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                            + ") is a new line inside the reconciliation finalized through "
                            + locked.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) + "; reopen it first");
                }
            }
            if (!inside.isEmpty()) {
                return result("INVALID", null, grid, kind, r, inside.size() > 20 ? inside.subList(0, 20) : inside, warnings,
                        0, 0, List.of(), null);
            }
        }
        if (dryRun) {
            return result("PREVIEW", null, grid, kind, r, List.of(), warnings, fresh, dup, preview, null);
        }

        BankStatementImport imp = new BankStatementImport();
        imp.setBankAccountId(bankAccountId);
        imp.setFileName(name.length() > 255 ? name.substring(name.length() - 255) : name);
        imp.setFileSha256(sha);
        imp.setLinesRead(r.rows().size());
        imp.setLinesNew(fresh);
        imp.setLinesDuplicate(dup);
        imp.setFirstDate(r.rows().stream().map(StatementMapper.Row::txnDate).min(Comparator.naturalOrder()).orElse(null));
        imp.setLastDate(r.rows().stream().map(StatementMapper.Row::txnDate).max(Comparator.naturalOrder()).orElse(null));
        imp.setOpeningBalance(r.openingBalance());
        imp.setClosingBalance(r.closingBalance());
        imp.setWarnings(warnings);
        imp.setImportedBy(currentUserId());
        imp = imports.saveAndFlush(imp);
        imp.setBlobPath(store(t, bankAccountId, imp.getId(), kind, bytes));

        Long maxSeq = jdbc.queryForObject("select coalesce(max(seq), 0) from bank_statement_lines where bank_account_id = :b and tenant_id = :t",
                new MapSqlParameterSource("t", t).addValue("b", bankAccountId), Long.class);
        long seq = maxSeq == null ? 0 : maxSeq;
        List<SqlParameterSource> batch = new ArrayList<>();
        for (int i = 0; i < r.rows().size(); i++) {
            if (existing.contains(hashes.get(i))) continue;
            StatementMapper.Row row = r.rows().get(i);
            batch.add(new MapSqlParameterSource("id", UUID.randomUUID()).addValue("t", t).addValue("b", bankAccountId)
                    .addValue("imp", imp.getId()).addValue("seq", ++seq).addValue("txn", row.txnDate())
                    .addValue("val", row.valueDate()).addValue("descr", row.description()).addValue("ref", row.reference())
                    .addValue("chq", row.chequeNo()).addValue("amt", row.amount()).addValue("bal", row.balance())
                    .addValue("hash", hashes.get(i)).addValue("row", row.fileRow()));
        }
        if (!batch.isEmpty()) {
            jdbc.batchUpdate("""
                    insert into bank_statement_lines (id, tenant_id, bank_account_id, import_id, seq, txn_date, value_date,
                        description, reference, cheque_no, amount, running_balance, line_hash, file_row)
                    values (:id, :t, :b, :imp, :seq, :txn, :val, :descr, :ref, :chq, :amt, :bal, :hash, :row)""",
                    batch.toArray(SqlParameterSource[]::new));
        }
        imports.save(imp);
        return result("IMPORTED", null, grid, kind, r, List.of(), warnings, fresh, dup, preview, imp.getId());
    }

    /**
     * PR #353 review: a line dated in the future, or a file spread over more than a
     * statement year, is a misread date format (or the wrong file), not a statement.
     */
    private List<String> periodErrors(List<StatementMapper.Row> rows) {
        LocalDate today = LocalDate.now(clock);
        List<String> out = new ArrayList<>();
        rows.stream().filter(x -> x.txnDate().isAfter(today)).findFirst().ifPresent(x -> out.add("Row " + x.fileRow()
                + ": " + dmy(x.txnDate()) + " is in the future; check the date format"));
        LocalDate min = rows.stream().map(StatementMapper.Row::txnDate).min(Comparator.naturalOrder()).orElse(today);
        LocalDate max = rows.stream().map(StatementMapper.Row::txnDate).max(Comparator.naturalOrder()).orElse(today);
        if (java.time.temporal.ChronoUnit.DAYS.between(min, max) > 400) {
            out.add("The lines run from " + dmy(min) + " to " + dmy(max) + ", more than a year; check the date format "
                    + "or split the file");
        }
        return out;
    }

    /**
     * Without a mapped cheque-number column, a number in the text is a cheque number
     * only when it is a cheque the books know on this bank account's ledger
     * accounts (PR #353 review): a six-digit bank reference is not a cheque.
     */
    private StatementMapper.Result withKnownChequeNumbers(UUID t, UUID bankAccountId, StatementMapper.Result r) {
        if (r.rows().stream().allMatch(x -> x.chequeCandidates().isEmpty())) return r;
        Set<UUID> leaves = ledgers.leafSet(bankAccountId);
        if (leaves.isEmpty()) leaves = Set.of(new UUID(0, 0));
        Set<String> known = new HashSet<>();
        MapSqlParameterSource p = new MapSqlParameterSource("t", t).addValue("l", leaves);
        for (String sql : List.of(
                """
                select c.cheque_number from cheques c where c.tenant_id = :t and c.cheque_number is not null
                  and (c.debit_account_id in (:l) or (c.debit_account_id is null and coalesce(
                       (select pm.account_id from property_account_mappings pm
                         where pm.tenant_id = c.tenant_id and pm.property_id = c.property_id and pm.role = 'BANK'),
                       (select d.account_id from tenant_default_account_mappings d
                         where d.tenant_id = c.tenant_id and d.role = 'BANK')) in (:l)))""",
                "select cheque_number from issued_cheques where tenant_id = :t and bank_account_id in (:l)",
                """
                select cheque_number from vouchers where tenant_id = :t and cheque_number is not null
                  and payment_account_id in (:l)""")) {
            jdbc.queryForList(sql, p, String.class).forEach(n -> known.add(n.trim().replaceFirst("^0+(?=.)", "")));
        }
        List<StatementMapper.Row> rows = r.rows().stream().map(x -> x.chequeNo() != null ? x
                : x.withChequeNo(x.chequeCandidates().stream()
                        .filter(c -> known.contains(c.trim().replaceFirst("^0+(?=.)", ""))).findFirst().orElse(null)))
                .toList();
        return new StatementMapper.Result(rows, r.errors(), r.warnings(), r.missingColumns(), r.openingBalance(),
                r.closingBalance(), r.hasBalance(), r.order());
    }

    /** One hash per row; {@code occurrence} counts identical lines (same day, amount, description) earlier in the file. */
    static List<String> hashes(UUID bankAccountId, List<StatementMapper.Row> rows) {
        Map<String, Integer> seen = new HashMap<>();
        List<String> out = new ArrayList<>(rows.size());
        for (StatementMapper.Row row : rows) {
            String key = row.txnDate() + "|" + row.amount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
                    + "|" + StatementValues.normalise(row.description());
            int occurrence = seen.merge(key, 1, Integer::sum) - 1;
            out.add(StatementValues.lineHash(bankAccountId, row.txnDate(), row.amount(), row.description(), occurrence));
        }
        return out;
    }

    /**
     * Spec §3 step 5: the file's first line against the latest earlier-dated line
     * already stored. A gap is a warning here (a blocker at finalize, §4).
     */
    private Optional<String> continuityWarning(UUID t, UUID bankAccountId, StatementMapper.Result r) {
        if (!r.hasBalance() || r.rows().isEmpty()) return Optional.empty();
        StatementMapper.Row first = r.rows().get(0);
        List<Map<String, Object>> prev = jdbc.queryForList("""
                select txn_date, running_balance from bank_statement_lines
                where tenant_id = :t and bank_account_id = :b and txn_date < :d and running_balance is not null
                order by txn_date desc, seq desc limit 1""",
                new MapSqlParameterSource("t", t).addValue("b", bankAccountId).addValue("d", first.txnDate()));
        if (prev.isEmpty()) return Optional.empty();
        BigDecimal before = (BigDecimal) prev.get(0).get("running_balance");
        BigDecimal expectedOpening = first.balance().subtract(first.amount());
        if (before.compareTo(expectedOpening) == 0) return Optional.empty();
        LocalDate prevDate = ((java.sql.Date) prev.get(0).get("txn_date")).toLocalDate();
        return Optional.of("Lines may be missing between " + dmy(prevDate) + " and " + dmy(first.txnDate())
                + ": the balance jumps by " + StatementValues.money(expectedOpening.subtract(before)));
    }

    /**
     * A new line with the date, amount and running balance of a stored line is
     * almost certainly that line read under a different mapping or wording
     * (PR #353 review P2-2): said, not silently imported.
     */
    private Optional<String> driftWarning(UUID t, UUID bankAccountId, List<StatementMapper.Row> rows, List<String> hashes,
                                          Set<String> existing) {
        int n = 0;
        for (int i = 0; i < rows.size(); i++) {
            StatementMapper.Row row = rows.get(i);
            if (existing.contains(hashes.get(i)) || row.balance() == null) continue;
            Integer hit = jdbc.queryForObject("""
                    select count(*) from bank_statement_lines where tenant_id = :t and bank_account_id = :b
                      and txn_date = :d and amount = :a and running_balance = :bal""",
                    new MapSqlParameterSource("t", t).addValue("b", bankAccountId).addValue("d", row.txnDate())
                            .addValue("a", row.amount()).addValue("bal", row.balance()), Integer.class);
            if (hit != null && hit > 0) n++;
        }
        return n == 0 ? Optional.empty() : Optional.of(n + " new line(s) have the date, amount and balance of a line "
                + "already imported, under a different description; check they are not the same lines");
    }

    private static String dmy(LocalDate d) {
        return d.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    private BankRecDTOs.ImportResult profileRequired(String reason, StatementGrid g, BankStatementProfile.FileKind kind,
                                                     List<String> missing) {
        return new BankRecDTOs.ImportResult("PROFILE_REQUIRED", reason, gridPreview(g), g.sheetNames(), g.sheetName(),
                kind.name(), missing, List.of(), List.of(), 0, 0, 0, null, null, null, null, null, List.of(), null);
    }

    private static BankRecDTOs.ImportResult result(String status, String reason, StatementGrid g,
                                                   BankStatementProfile.FileKind kind, StatementMapper.Result r,
                                                   List<String> errors, List<String> warnings, int fresh, int dup,
                                                   List<BankRecDTOs.PreviewRow> rows, UUID importId) {
        LocalDate first = r.rows().stream().map(StatementMapper.Row::txnDate).min(Comparator.naturalOrder()).orElse(null);
        LocalDate last = r.rows().stream().map(StatementMapper.Row::txnDate).max(Comparator.naturalOrder()).orElse(null);
        return new BankRecDTOs.ImportResult(status, reason, "IMPORTED".equals(status) ? List.of() : gridPreview(g),
                g.sheetNames(), g.sheetName(), kind.name(), List.of(), errors, warnings, r.rows().size(), fresh, dup,
                first, last, r.openingBalance(), r.closingBalance(), r.order().name(), rows, importId);
    }

    static List<List<String>> gridPreview(StatementGrid g) {
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < Math.min(PREVIEW_GRID_ROWS, g.rows().size()); i++) {
            out.add(g.rows().get(i).stream().map(StatementGrid::text).toList());
        }
        return out;
    }

    private static BankStatementProfile copyOf(BankStatementProfile saved, BankStatementProfile.FileKind kind) {
        BankStatementProfile p = new BankStatementProfile();
        p.setFileKind(kind);
        if (saved != null) {
            p.setDateFormats(saved.getDateFormats());
            p.setDecimalSeparator(saved.getDecimalSeparator());
            p.setChequeNoPattern(saved.getChequeNoPattern());
            p.setMatchWindowDays(saved.getMatchWindowDays());
        }
        return p;
    }

    private static boolean looksBinary(byte[] bytes) {
        int n = Math.min(bytes.length, 4096);
        for (int i = 0; i < n; i++) {
            if (bytes[i] == 0) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ history and delete

    @Transactional(readOnly = true)
    public List<BankRecDTOs.ImportRow> history(UUID bankAccountId) {
        UUID t = BankAccountLedgerService.requireTenant();
        ledgers.requireBankAccount(bankAccountId);
        Set<UUID> locked = new HashSet<>(jdbc.queryForList("""
                select distinct l.import_id from bank_statement_lines l
                join bank_match_statement_lines ml on ml.statement_line_id = l.id
                join bank_matches m on m.id = ml.match_id and m.status <> 'SUGGESTED'
                where l.tenant_id = :t and l.bank_account_id = :b""",
                new MapSqlParameterSource("t", t).addValue("b", bankAccountId), UUID.class));
        return imports.findByBankAccountIdOrderByImportedAtDesc(bankAccountId).stream()
                .map(i -> new BankRecDTOs.ImportRow(i.getId(), i.getFileName(), i.getLinesRead(), i.getLinesNew(),
                        i.getLinesDuplicate(), i.getFirstDate(), i.getLastDate(), i.getOpeningBalance(),
                        i.getClosingBalance(), i.getWarnings() == null ? List.of() : i.getWarnings(), i.getImportedAt(),
                        !locked.contains(i.getId())))
                .toList();
    }

    /**
     * Delete import (spec §3): its lines go, while none of them is in a CONFIRMED
     * match. An undone match is the audit trail the spec keeps (PR #353 review), so
     * a line with any match history also holds its import; only suggestions go.
     */
    @Transactional
    public void delete(UUID importId) {
        UUID t = BankAccountLedgerService.requireTenant();
        BankStatementImport imp = imports.findById(importId).orElseThrow(() -> new NotFoundException("Import not found"));
        if (!t.equals(imp.getTenantId())) throw new NotFoundException("Import not found");
        MapSqlParameterSource p = new MapSqlParameterSource("t", t).addValue("i", importId)
                .addValue("b", imp.getBankAccountId());
        jdbc.queryForList("select id from bank_accounts where id = :b and tenant_id = :t for update", p, UUID.class);
        LocalDate locked = bankLock.reconciledThrough(imp.getBankAccountId()).orElse(null);
        if (locked != null) {
            Integer inside = jdbc.queryForObject("select count(*) from bank_statement_lines where tenant_id = :t and import_id = :i and txn_date <= :r",
                    new MapSqlParameterSource(p.getValues()).addValue("r", locked), Integer.class);
            if (inside != null && inside > 0) {
                throw new BusinessRuleViolationException(inside + " line(s) of this import are inside the reconciliation finalized through "
                        + locked.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) + "; the import cannot be deleted");
            }
        }
        Integer confirmed = jdbc.queryForObject("""
                select count(*) from bank_statement_lines l
                join bank_match_statement_lines ml on ml.statement_line_id = l.id and not ml.released
                join bank_matches m on m.id = ml.match_id and m.status = 'CONFIRMED'
                where l.tenant_id = :t and l.import_id = :i""", p, Integer.class);
        if (confirmed != null && confirmed > 0) {
            throw new BusinessRuleViolationException(confirmed + " line(s) of this import are in confirmed matches; "
                    + "undo those matches first");
        }
        Integer history = jdbc.queryForObject("""
                select count(*) from bank_statement_lines l
                join bank_match_statement_lines ml on ml.statement_line_id = l.id
                join bank_matches m on m.id = ml.match_id and m.status = 'UNDONE'
                where l.tenant_id = :t and l.import_id = :i""", p, Integer.class);
        if (history != null && history > 0) {
            throw new BusinessRuleViolationException("Lines of this import have match history (undone matches), "
                    + "which is kept as the audit trail; the import cannot be deleted");
        }
        List<UUID> matches = jdbc.queryForList("""
                select distinct ml.match_id from bank_match_statement_lines ml
                join bank_statement_lines l on l.id = ml.statement_line_id
                join bank_matches m on m.id = ml.match_id and m.status = 'SUGGESTED'
                where l.tenant_id = :t and l.import_id = :i""", p, UUID.class);
        if (!matches.isEmpty()) {
            MapSqlParameterSource mp = new MapSqlParameterSource("t", t).addValue("m", matches);
            jdbc.update("delete from bank_match_statement_lines where tenant_id = :t and match_id in (:m)", mp);
            jdbc.update("delete from bank_match_book_items where tenant_id = :t and match_id in (:m)", mp);
            jdbc.update("delete from bank_matches where tenant_id = :t and id in (:m)", mp);
        }
        jdbc.update("delete from bank_statement_lines where tenant_id = :t and import_id = :i", p);
        imports.delete(imp);
    }

    // ------------------------------------------------------------------ storage

    /** The voucher-attachment arrangement: Azure blob when configured, local disk otherwise. Never public. */
    private String store(UUID tenantId, UUID bankAccountId, UUID importId, BankStatementProfile.FileKind kind, byte[] bytes) {
        String file = importId + (kind == BankStatementProfile.FileKind.XLSX ? ".xlsx" : ".csv");
        String path = "private/bank-statements/" + bankAccountId + "/" + file;
        try {
            if (azureConnectionString != null && !azureConnectionString.isBlank()) {
                var svc = new BlobServiceClientBuilder().connectionString(azureConnectionString).buildClient();
                BlobContainerClient container = svc.getBlobContainerClient(containerPrefix + tenantId);
                if (!container.exists()) container.create();
                container.getBlobClient(path).upload(new ByteArrayInputStream(bytes), bytes.length, true);
                return svc.getAccountUrl() + "/" + containerPrefix + tenantId + "/" + path;
            }
            Path dir = Path.of(localStoragePath, "private", "bank-statements", bankAccountId.toString());
            Files.createDirectories(dir);
            Files.write(dir.resolve(file), bytes);
            return path;
        } catch (Exception e) {
            // The lines are the record; the file is kept for audit. A storage outage
            // must not lose an import the accountant has checked.
            log.warn("Statement file for import {} was not stored: {}", importId, e.getMessage());
            return null;
        }
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String v, String what) {
        try {
            return Enum.valueOf(type, v.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleViolationException("Unknown " + what + " " + v);
        }
    }

    static UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof String s)) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}
