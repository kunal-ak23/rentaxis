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
        if (in == null) throw BankRecRefusal.refuse("mappingRequired", "A column mapping is required");
        if (in.fileKind() != null) p.setFileKind(enumOf(BankStatementProfile.FileKind.class, in.fileKind(), "file kind"));
        p.setSheetName(in.sheetName() == null || in.sheetName().isBlank() ? null : in.sheetName().trim());
        if (in.headerRow() != null) p.setHeaderRow(in.headerRow());
        if (in.firstDataRow() != null) p.setFirstDataRow(in.firstDataRow());
        if (p.getHeaderRow() < 1 || p.getFirstDataRow() <= p.getHeaderRow()) {
            throw BankRecRefusal.refuse("firstDataRowAfterHeader", "The first data row must come after the header row");
        }
        if (in.csvDelimiter() != null && !in.csvDelimiter().isEmpty()) {
            if (in.csvDelimiter().length() > 2) throw BankRecRefusal.refuse("delimiterOneChar", "The delimiter is one character");
            p.setCsvDelimiter(in.csvDelimiter());
        }
        // PR #353 review: the date format is the accountant's statement, never a guess.
        if (in.dateFormats() == null || in.dateFormats().isEmpty() || in.dateFormats().stream().allMatch(f -> f == null || f.isBlank())) {
            throw BankRecRefusal.refuse("chooseDateFormat", "Choose the statement's date format");
        }
        if (in.decimalSeparator() != null && !in.decimalSeparator().isBlank()) {
            if (!in.decimalSeparator().equals(".") && !in.decimalSeparator().equals(",")) {
                throw BankRecRefusal.refuse("decimalSeparatorInvalid", "The decimal separator is . or ,");
            }
            p.setDecimalSeparator(in.decimalSeparator());
        }
        {
            for (String f : in.dateFormats()) {
                try {
                    StatementValues.formatter(f);
                } catch (IllegalArgumentException e) {
                    throw BankRecRefusal.refuse("notADateFormat", "\"" + f + "\" is not a date format", "format", f);
                }
            }
            p.setDateFormats(in.dateFormats().toArray(String[]::new));
        }
        Map<String, String> cols = new LinkedHashMap<>();
        if (in.columns() != null) {
            in.columns().forEach((k, v) -> {
                if (!StatementMapper.FIELDS.contains(k)) throw BankRecRefusal.refuse("unknownColumnField", "Unknown column field " + k, "field", k);
                if (v != null && !v.isBlank()) cols.put(k, v.trim());
            });
        }
        p.setColumns(cols);
        if (in.amountMode() != null) p.setAmountMode(enumOf(BankStatementProfile.AmountMode.class, in.amountMode(), "amount mode"));
        if (in.chequeNoPattern() != null && !in.chequeNoPattern().isBlank()) {
            if (in.chequeNoPattern().length() > 100) throw BankRecRefusal.refuse("chequePatternTooLong", "The cheque number pattern is at most 100 characters");
            try {
                Pattern.compile(in.chequeNoPattern());
            } catch (PatternSyntaxException e) {
                throw BankRecRefusal.refuse("chequePatternInvalid", "The cheque number pattern is not a valid expression");
            }
            p.setChequeNoPattern(in.chequeNoPattern());
        }
        if (in.matchWindowDays() != null) {
            if (in.matchWindowDays() < 0 || in.matchWindowDays() > 31) {
                throw BankRecRefusal.refuse("matchWindowRange", "The match window is 0 to 31 days");
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
            throw BankRecRefusal.refuse("multiCurrency", "Multi-currency statements are out of scope; this account is in "
                    + bank.getCurrency(), "currency", bank.getCurrency());
        }
        if (bytes == null || bytes.length == 0) throw BankRecRefusal.refuse("fileEmpty", "The file is empty");
        if (bytes.length > MAX_FILE_BYTES) throw BankRecRefusal.refuse("fileTooLarge", "A statement file is at most 5 MB");
        String name = fileName == null || fileName.isBlank() ? "statement" : fileName.trim();
        if (name.toLowerCase(Locale.ROOT).endsWith(".xls")) {
            throw BankRecRefusal.refuse("oldXls", "Save the statement as .xlsx or .csv; the old .xls format is not read");
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
                    null, List.of(), null, null, "bankrec.alreadyImported",
                    BankRecRefusal.args("on", on, "file", prior.get("file_name")));
        }
        BankStatementProfile.FileKind kind = WorkbookGuard.looksLikeXlsx(bytes)
                ? BankStatementProfile.FileKind.XLSX : BankStatementProfile.FileKind.CSV;
        if (kind == BankStatementProfile.FileKind.CSV && looksBinary(bytes)) {
            throw BankRecRefusal.refuse("notCsvOrXlsx", "This file is neither a CSV nor an .xlsx workbook");
        }
        BankStatementProfile saved = profiles.findByBankAccountId(bankAccountId).orElse(null);
        BankStatementProfile profile = profileOverride != null
                ? apply(copyOf(saved, kind), profileOverride) : saved;
        StatementParser parser = kind == BankStatementProfile.FileKind.XLSX ? xlsx : csv;

        // F14-04: the delimiter the file itself uses, so the wizard can default to it.
        String detected = kind == BankStatementProfile.FileKind.CSV ? CsvStatementParser.detectedDelimiter(bytes) : null;
        if (profile == null) {
            StatementGrid g = parser.read(bytes, null, null);
            return profileRequired("No column mapping for this bank account yet", "bankrec.noMapping", Map.of(), g, kind,
                    List.of(), detected);
        }
        if (profile.getFileKind() != kind) {
            StatementGrid g = parser.read(bytes, null, null);
            return profileRequired("The saved mapping is for " + profile.getFileKind() + " files; this one is " + kind,
                    "bankrec.mappingForOtherKind", BankRecRefusal.args("saved", profile.getFileKind(), "kind", kind),
                    g, kind, List.of(), detected);
        }
        StatementGrid grid;
        try {
            // An unsaved mapping that names no delimiter reads the file as detected (F14-04).
            boolean detect = profileOverride != null
                    && (profileOverride.csvDelimiter() == null || profileOverride.csvDelimiter().isEmpty());
            grid = parser.read(bytes, profile.getSheetName(), kind == BankStatementProfile.FileKind.CSV && !detect
                    ? profile.getCsvDelimiter() : null);
        } catch (BusinessRuleViolationException e) {
            if (kind == BankStatementProfile.FileKind.XLSX && profile.getSheetName() != null) {
                return profileRequired(e.getMessage(), e.getCode(), e.getArgs(), parser.read(bytes, null, null), kind,
                        List.of(), detected);
            }
            throw e;
        }
        StatementMapper.Result r = StatementMapper.map(grid, profile);
        if (!r.missingColumns().isEmpty()) {
            // F14-04: the saved mapping's delimiter may be what no longer fits (a ';' file
            // read with ','); the wizard is shown the file split the way it looks.
            StatementGrid shown = kind == BankStatementProfile.FileKind.CSV && profileOverride == null
                    ? parser.read(bytes, null, null) : grid;
            return profileRequired("The file's header row no longer matches the saved mapping", "bankrec.headerChanged",
                    Map.of(), shown, kind, r.missingColumns(), detected);
        }
        if (r.rows().size() > StatementParser.MAX_ROWS) throw CsvStatementParser.tooMany();
        if (!r.errors().isEmpty()) {
            return result("INVALID", null, grid, kind, r, r.errors(), r.warnings(), 0, 0, List.of(), null, detected);
        }
        if (r.rows().isEmpty()) {
            return result("INVALID", null, grid, kind, r, List.of("No transaction lines were found"), r.warnings(),
                    0, 0, List.of(), null, detected);
        }

        List<String> period = periodErrors(r.rows());
        if (!period.isEmpty()) {
            return result("INVALID", null, grid, kind, r, period, r.warnings(), 0, 0, List.of(), null, detected);
        }
        r = withKnownChequeNumbers(t, bankAccountId, r);

        if (!dryRun) {
            // One import per bank account at a time: the de-duplication below reads,
            // then writes.
            jdbc.queryForList("select id from bank_accounts where id = :b and tenant_id = :t for update",
                    new MapSqlParameterSource("t", t).addValue("b", bankAccountId), UUID.class);
        }
        // #97 review P2-2: the stored lines of every day in the file, and which of
        // them each file row is (a duplicate) or is not (a new line).
        Map<LocalDate, List<StoredLine>> stored = storedLines(t, bankAccountId, r.rows());
        Matched matched = match(bankAccountId, r.rows(), stored, r.openingRow(), r.closingRow());
        List<String> hashes = matched.hashes();
        Set<String> existing = matched.duplicates();
        List<String> warnings = new ArrayList<>(r.warnings());
        continuityWarning(t, bankAccountId, r).ifPresent(warnings::add);
        driftWarning(t, bankAccountId, r.rows(), hashes, existing).ifPresent(warnings::add);
        Map<String, BigDecimal> moved = matched.moved();
        Map<String, StoredLine> storedByHash = new HashMap<>();
        stored.values().forEach(ls -> ls.forEach(l -> storedByHash.put(l.hash(), l)));
        movedWarning(r.rows(), hashes, moved, storedByHash).ifPresent(warnings::add);
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
                } else if (moved.containsKey(hashes.get(i)) && !row.txnDate().isAfter(locked)) {
                    // S25-01: its balance would change inside the finalized period.
                    StoredLine was = storedByHash.get(hashes.get(i));
                    inside.add("Row " + row.fileRow() + " (" + dmy(row.txnDate()) + ", " + row.description()
                            + ") is a line already imported inside the reconciliation finalized through " + dmy(locked)
                            + " whose running balance the bank has since moved by "
                            + StatementValues.money(moved.get(hashes.get(i)).subtract(was.balance()))
                            + " (a line dated on or before it was added later); reopen it first");
                }
            }
            if (!inside.isEmpty()) {
                return result("INVALID", null, grid, kind, r, inside.size() > 20 ? inside.subList(0, 20) : inside, warnings,
                        0, 0, List.of(), null, detected);
            }
        }
        if (dryRun) {
            return result("PREVIEW", null, grid, kind, r, List.of(), warnings, fresh, dup, preview, null, detected);
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
        imp.setClosingRowDate(r.closingRowDate());
        imp.setWarnings(warnings);
        imp.setImportedBy(currentUserId());
        imp = imports.saveAndFlush(imp);
        imp.setBlobPath(store(t, bankAccountId, imp.getId(), kind, bytes));

        Long maxSeq = jdbc.queryForObject("select coalesce(max(seq), 0) from bank_statement_lines where bank_account_id = :b and tenant_id = :t",
                new MapSqlParameterSource("t", t).addValue("b", bankAccountId), Long.class);
        long seq = maxSeq == null ? 0 : maxSeq;
        // #97: the stored lines of each day in this file, before the insert.
        Map<LocalDate, List<String>> storedDays = new HashMap<>();
        stored.forEach((d, ls) -> storedDays.put(d, ls.stream().map(StoredLine::hash).toList()));
        List<SqlParameterSource> batch = new ArrayList<>();
        for (int i = 0; i < r.rows().size(); i++) {
            if (existing.contains(hashes.get(i))) continue;
            StatementMapper.Row row = r.rows().get(i);
            batch.add(new MapSqlParameterSource("id", UUID.randomUUID()).addValue("t", t).addValue("b", bankAccountId)
                    .addValue("imp", imp.getId()).addValue("seq", ++seq).addValue("txn", row.txnDate())
                    .addValue("val", row.valueDate()).addValue("descr", row.description()).addValue("ref", row.reference())
                    .addValue("chq", row.chequeNo()).addValue("amt", row.amount()).addValue("bal", row.balance())
                    .addValue("hash", hashes.get(i)).addValue("row", row.fileRow())
                    .addValue("lookalike", matched.twinned().contains(hashes.get(i))));
        }
        if (!batch.isEmpty()) {
            jdbc.batchUpdate("""
                    insert into bank_statement_lines (id, tenant_id, bank_account_id, import_id, seq, txn_date, value_date,
                        description, reference, cheque_no, amount, running_balance, line_hash, file_row, lookalike_kept)
                    values (:id, :t, :b, :imp, :seq, :txn, :val, :descr, :ref, :chq, :amt, :bal, :hash, :row, :lookalike)""",
                    batch.toArray(SqlParameterSource[]::new));
        }
        // S25-01: the lines taken although their balance moved carry the bank's balance now.
        if (!moved.isEmpty()) {
            List<SqlParameterSource> updates = new ArrayList<>();
            moved.forEach((h, bal) -> updates.add(new MapSqlParameterSource("t", t).addValue("b", bankAccountId)
                    .addValue("h", h).addValue("bal", bal)));
            jdbc.batchUpdate("""
                    update bank_statement_lines set running_balance = :bal
                    where tenant_id = :t and bank_account_id = :b and line_hash = :h""",
                    updates.toArray(SqlParameterSource[]::new));
        }
        // Review P2-3/P3-15: which imports brought each line — this one, for the lines it
        // stored and for the stored lines it matched.
        MapSqlParameterSource src = new MapSqlParameterSource("t", t).addValue("b", bankAccountId).addValue("imp", imp.getId());
        jdbc.update("""
                insert into bank_statement_line_sources (tenant_id, line_id, import_id)
                select tenant_id, id, import_id from bank_statement_lines where tenant_id = :t and import_id = :imp
                on conflict do nothing""", src);
        if (!existing.isEmpty()) {
            jdbc.update("""
                    insert into bank_statement_line_sources (tenant_id, line_id, import_id)
                    select tenant_id, id, :imp from bank_statement_lines
                    where tenant_id = :t and bank_account_id = :b and line_hash in (:h)
                    on conflict do nothing""", src.addValue("h", existing));
        }
        // Review P2-4: StatementMapper trusts boundary rows only when their own dates prove
        // the file's edges (an Opening row dated before the first line's day, a Closing row
        // dated after the last line's day).
        settleDays(t, bankAccountId, r.rows(), hashes, storedDays, locked, r.openingRow(), r.closingRow(),
                matched.twinned());
        imports.save(imp);
        return result("IMPORTED", null, grid, kind, r, List.of(), warnings, fresh, dup, preview, imp.getId(), detected);
    }

    /** A stored statement line, as de-duplication and day ordering need it. */
    record StoredLine(String hash, BigDecimal amount, String description, BigDecimal balance, LocalDate valueDate,
                      String reference, boolean lookalike) {
        StoredLine(String hash, BigDecimal amount, String description, BigDecimal balance) {
            this(hash, amount, description, balance, null, null, false);
        }

        StoredLine(String hash, BigDecimal amount, String description, BigDecimal balance, LocalDate valueDate,
                   String reference) {
            this(hash, amount, description, balance, valueDate, reference, false);
        }
    }

    /** #97: the lines already stored for each day of this file, in stored {@code seq} order. */
    private Map<LocalDate, List<StoredLine>> storedLines(UUID t, UUID bankAccountId, List<StatementMapper.Row> rows) {
        Set<LocalDate> days = new TreeSet<>();
        rows.forEach(row -> days.add(row.txnDate()));
        Map<LocalDate, List<StoredLine>> out = new HashMap<>();
        if (days.isEmpty()) return out;
        jdbc.query("""
                select txn_date, line_hash, amount, description, running_balance, value_date, reference, lookalike_kept
                from bank_statement_lines
                where tenant_id = :t and bank_account_id = :b and txn_date in (:d) order by txn_date, seq""",
                new MapSqlParameterSource("t", t).addValue("b", bankAccountId).addValue("d", days),
                rs -> { out.computeIfAbsent(rs.getObject("txn_date", LocalDate.class), k -> new ArrayList<>())
                        .add(new StoredLine(rs.getString("line_hash"), rs.getBigDecimal("amount"),
                                rs.getString("description"), rs.getBigDecimal("running_balance"),
                                rs.getObject("value_date", LocalDate.class), rs.getString("reference"),
                                rs.getBoolean("lookalike_kept"))); });
        return out;
    }

    /**
     * Each file row's line hash, in file order, and the hashes of the rows that are already
     * stored. {@code moved}: the stored lines taken although the bank's running balance on
     * them has moved since (sim r25 S25-01), each with the file's balance, in file order.
     */
    record Matched(List<String> hashes, Set<String> duplicates, Map<String, BigDecimal> moved, Set<String> twinned) {
        Matched(List<String> hashes, Set<String> duplicates) {
            this(hashes, duplicates, Map.of(), Set.of());
        }
    }

    static Matched match(UUID bankAccountId, List<StatementMapper.Row> rows, Map<LocalDate, List<StoredLine>> stored) {
        return match(bankAccountId, rows, stored, false, false);
    }

    /**
     * #97 review P2-2: which stored line, if any, each file row is.
     *
     * <p>A row is a stored line of its day with the same amount and description
     * (as the hash normalises them) and the same running balance. When either side
     * has no balance (a mapping without the balance column, PR #353 P2-2), amount
     * and description decide. Each stored line is taken by at most one row, in
     * file order against stored order. The old rule — "same date, amount,
     * description and n-th such line <em>within this file</em>" — took a partial
     * file's second fee for a fuller file's first one and silently dropped the
     * genuine first.</p>
     *
     * <p>Sim r25 S25-01: when the bank back-values a line (adds it with an earlier date),
     * every later running balance in its exports moves by that line's amount, so a
     * re-export's rows no longer carry the balances their stored lines were imported
     * with. Such a row is still the stored line — same date, value date, amount and sign,
     * description and reference — and is taken for it, never stored twice:</p>
     * <ul>
     *   <li>on a day the file holds whole (neither its first nor its last date, or one its
     *       boundary rows prove): the file has every line of the bank's day, so every
     *       stored line of the day is one of its rows. A row left over by the exact rule is
     *       taken for a left-over stored line like it, one for one in order: the count of
     *       identical lines stays the bank's (two identical charges stay two, a third in
     *       the file is new);</li>
     *   <li>never on the file's first or last date (part of a day). There a row matching a
     *       stored line at a moved balance may be a line the bank inserted in front of an
     *       identical one, with the export cut right after it; taking it lost that line in
     *       the property model, where storing it is never worse than before this change.
     *       Such a row is stored as new, and its day is unproven (below): the paper's day
     *       totals, or a statement holding the day whole — what the reconciliation names —
     *       settle it.</li>
     * </ul>
     * <p>A row whose balance did not move is matched exactly as before. A row stored as new
     * while an untaken stored line of its day has its amount and narration at another
     * balance is listed in {@code twinned}: moved or separate, the import cannot tell, so
     * the line is kept and its day unproven ({@link #settle}).</p>
     *
     * <p>A new row gets {@code lineHash(…, n)} with the lowest {@code n} no stored
     * or earlier new line of the day holds, so the hash stays unique per bank
     * account; for a day with nothing stored that is the old in-file occurrence,
     * so exact re-imports still match line for line.</p>
     */
    static Matched match(UUID bankAccountId, List<StatementMapper.Row> rows, Map<LocalDate, List<StoredLine>> stored,
                         boolean openingRow, boolean closingRow) {
        int n = rows.size();
        StoredLine[] twinOf = new StoredLine[n];
        // The balance shift of a row taken for a stored line, both with balances (zero: exact).
        BigDecimal[] shift = new BigDecimal[n];
        Set<StoredLine> claimed = Collections.newSetFromMap(new IdentityHashMap<>());
        Pos[] pos = new Pos[n];
        if (n > 0) {
            LocalDate first = rows.get(0).txnDate(), last = rows.get(0).txnDate();
            for (StatementMapper.Row row : rows) {
                if (row.txnDate().isBefore(first)) first = row.txnDate();
                if (row.txnDate().isAfter(last)) last = row.txnDate();
            }
            for (int i = 0; i < n; i++) pos[i] = Pos.of(rows.get(i).txnDate(), first, last, openingRow, closingRow);
        }
        for (int i = 0; i < n; i++) {
            StatementMapper.Row row = rows.get(i);
            List<StoredLine> day = stored.getOrDefault(row.txnDate(), List.of());
            BigDecimal amount = row.amount().setScale(2, java.math.RoundingMode.HALF_UP);
            String desc = StatementValues.normalise(row.description());
            StoredLine twin = null;
            StoredLine anyTwin = null;
            for (StoredLine s : day) {
                // #441 review: on a day the file holds whole, a line kept beside a look-alike
                // (it may be a copy) is taken last, after the lines it may be a copy of.
                if (s.lookalike() && pos[i] == Pos.INTERIOR) continue;
                if (claimed.contains(s) || s.amount().compareTo(amount) != 0
                        || !StatementValues.normalise(s.description()).equals(desc)) continue;
                if (row.balance() == null || s.balance() == null) {
                    if (anyTwin == null) anyTwin = s;
                } else if (s.balance().compareTo(row.balance()) == 0) {
                    twin = s;
                    break;
                }
            }
            if (twin == null) twin = anyTwin;
            if (twin != null) {
                claimed.add(twin);
                twinOf[i] = twin;
                if (row.balance() != null && twin.balance() != null) shift[i] = BigDecimal.ZERO;
            }
        }
        Map<String, BigDecimal> moved = new LinkedHashMap<>();
        if (n > 0) {
            // Days the file holds whole: a left-over row is a left-over stored line like it.
            for (int i = 0; i < n; i++) {
                if (twinOf[i] != null || pos[i] != Pos.INTERIOR || rows.get(i).balance() == null) continue;
                StoredLine s = movedTwin(rows.get(i), stored, claimed, null);
                if (s != null) take(i, s, rows, twinOf, shift, claimed);
            }
            // #441 review: then the kept look-alikes, at any balance. Those no row takes were
            // copies: the day stays unproven and the import that brought them can be deleted.
            for (int i = 0; i < n; i++) {
                if (twinOf[i] != null || pos[i] != Pos.INTERIOR || rows.get(i).balance() == null) continue;
                StoredLine s = keptLookalike(rows.get(i), stored, claimed);
                if (s != null) take(i, s, rows, twinOf, shift, claimed);
            }
            for (int i = 0; i < n; i++) {
                if (shift[i] != null && shift[i].signum() != 0) moved.put(twinOf[i].hash(), rows.get(i).balance());
            }
        }

        List<String> hashes = new ArrayList<>(n);
        Set<String> duplicates = new HashSet<>();
        Set<String> twinned = new HashSet<>();
        Map<LocalDate, Set<String>> occupied = new HashMap<>();
        for (int i = 0; i < n; i++) {
            StatementMapper.Row row = rows.get(i);
            if (twinOf[i] != null) {
                duplicates.add(twinOf[i].hash());
                hashes.add(twinOf[i].hash());
                continue;
            }
            boolean twin = row.balance() != null && hasLooseTwin(row, stored, claimed);
            List<StoredLine> day = stored.getOrDefault(row.txnDate(), List.of());
            Set<String> taken = occupied.computeIfAbsent(row.txnDate(),
                    d -> new HashSet<>(day.stream().map(StoredLine::hash).toList()));
            String h;
            int k = 0;
            do {
                h = StatementValues.lineHash(bankAccountId, row.txnDate(), row.amount(), row.description(), k++);
            } while (taken.contains(h));
            taken.add(h);
            hashes.add(h);
            if (twin) twinned.add(h);
        }
        return new Matched(hashes, duplicates, moved, twinned);
    }

    /**
     * Sim r25 review: whether a row stored as new has an untaken stored line of its day with
     * its amount and narration at another running balance. Once the bank can back-value, that
     * may be the same line at a moved balance (stored twice) or a separate one; the import
     * cannot tell, so it keeps the row and the day is unproven (the paper's day totals, or a
     * statement holding the day whole, decide). Unflagged, such a false duplicate balanced a
     * line lost elsewhere and let a typed closing finalize.
     */
    private static boolean hasLooseTwin(StatementMapper.Row row, Map<LocalDate, List<StoredLine>> stored,
                                        Set<StoredLine> claimed) {
        BigDecimal amount = row.amount().setScale(2, java.math.RoundingMode.HALF_UP);
        String desc = StatementValues.normalise(row.description());
        for (StoredLine s : stored.getOrDefault(row.txnDate(), List.of())) {
            if (!claimed.contains(s) && s.balance() != null && s.amount().compareTo(amount) == 0
                    && StatementValues.normalise(s.description()).equals(desc)
                    && s.balance().compareTo(row.balance()) != 0) return true;
        }
        return false;
    }

    /**
     * S25-01: the first unclaimed stored line of the row's day that is the same line but
     * for its running balance (both have one), moved by {@code shift} when given.
     */
    private static StoredLine movedTwin(StatementMapper.Row row, Map<LocalDate, List<StoredLine>> stored,
                                        Set<StoredLine> claimed, BigDecimal shift) {
        BigDecimal amount = row.amount().setScale(2, java.math.RoundingMode.HALF_UP);
        String desc = StatementValues.normalise(row.description());
        for (StoredLine s : stored.getOrDefault(row.txnDate(), List.of())) {
            if (s.lookalike() || claimed.contains(s) || s.balance() == null || s.amount().compareTo(amount) != 0
                    || !StatementValues.normalise(s.description()).equals(desc)) continue;
            if (row.valueDate() != null && s.valueDate() != null && !row.valueDate().equals(s.valueDate())) continue;
            if (!StatementValues.normalise(row.reference()).isEmpty() && !StatementValues.normalise(s.reference()).isEmpty()
                    && !StatementValues.normalise(row.reference()).equals(StatementValues.normalise(s.reference()))) continue;
            BigDecimal moved = row.balance().subtract(s.balance());
            if (moved.signum() == 0) continue;
            if (shift == null || moved.compareTo(shift) == 0) return s;
        }
        return null;
    }

    /** #441 review: the first unclaimed kept look-alike of the row's day with its amount and narration. */
    private static StoredLine keptLookalike(StatementMapper.Row row, Map<LocalDate, List<StoredLine>> stored,
                                            Set<StoredLine> claimed) {
        BigDecimal amount = row.amount().setScale(2, java.math.RoundingMode.HALF_UP);
        String desc = StatementValues.normalise(row.description());
        for (StoredLine s : stored.getOrDefault(row.txnDate(), List.of())) {
            if (s.lookalike() && !claimed.contains(s) && s.balance() != null && s.amount().compareTo(amount) == 0
                    && StatementValues.normalise(s.description()).equals(desc)) return s;
        }
        return null;
    }

    private static void take(int i, StoredLine s, List<StatementMapper.Row> rows, StoredLine[] twinOf, BigDecimal[] shift,
                             Set<StoredLine> claimed) {
        claimed.add(s);
        twinOf[i] = s;
        shift[i] = rows.get(i).balance().subtract(s.balance());
    }

    /**
     * #97: after the insert, settles each day of the file.
     *
     * <ul>
     *   <li>A day the file brings new lines to is put in the order {@link #dayOrder}
     *       gives, by handing that day's own seq values out again in that order
     *       (other days keep theirs). Its order is confirmed when nothing was
     *       stored for it, or when the file holds the whole day (it also has lines
     *       on the days before and after) and every line already stored; it is
     *       unconfirmed otherwise (review P2-1, P2-2; see {@link #settle}).</li>
     *   <li>A day the file brings nothing new to, but holds whole together with
     *       every stored line, takes the file's order and becomes confirmed:
     *       importing a statement that runs from the day before to the day after
     *       is how an unconfirmed day is confirmed. Days inside a finalized
     *       reconciliation are left alone.</li>
     * </ul>
     *
     * <p>The state is kept per bank account and day in {@code bank_statement_days}
     * (no row: confirmed). The reconciliation asks for a typed closing balance
     * while a day of its period is unconfirmed.</p>
     */
    private void settleDays(UUID t, UUID bankAccountId, List<StatementMapper.Row> rows, List<String> hashes,
                            Map<LocalDate, List<String>> storedDays, LocalDate locked, boolean openingRow,
                            boolean closingRow, Set<String> twinned) {
        TreeMap<LocalDate, List<String>> fileDays = new TreeMap<>();
        for (int i = 0; i < rows.size(); i++) {
            fileDays.computeIfAbsent(rows.get(i).txnDate(), k -> new ArrayList<>()).add(hashes.get(i));
        }
        if (fileDays.isEmpty()) return;
        LocalDate firstDay = fileDays.firstKey(), lastDay = fileDays.lastKey();
        Map<LocalDate, DayState> states = new HashMap<>();
        jdbc.query("""
                select txn_date, order_confirmed, merge_unproven, start_known, end_known from bank_statement_days
                where tenant_id = :t and bank_account_id = :b and txn_date in (:d)""",
                new MapSqlParameterSource("t", t).addValue("b", bankAccountId).addValue("d", fileDays.keySet()),
                rs -> { states.put(rs.getObject("txn_date", LocalDate.class), new DayState(rs.getBoolean("order_confirmed"),
                        rs.getBoolean("merge_unproven"), rs.getBoolean("start_known"), rs.getBoolean("end_known"))); });
        for (Map.Entry<LocalDate, List<String>> day : fileDays.entrySet()) {
            List<String> stored = storedDays.getOrDefault(day.getKey(), List.of());
            Pos pos = Pos.of(day.getKey(), firstDay, lastDay, openingRow, closingRow);
            // No row: a day imported before changeset 171 (or never): nothing is known
            // about where its lines sit in the day, and they were de-duplicated by the
            // old rule.
            DayState was = states.getOrDefault(day.getKey(), DayState.UNKNOWN);
            Settled s = settle(stored, day.getValue(), pos, was, day.getValue().stream().anyMatch(twinned::contains));
            if (s == null) continue;
            // A day inside a finalized reconciliation never takes new lines (refused
            // above); one with nothing new is left alone there too.
            if (!s.addsLines() && locked != null && !day.getKey().isAfter(locked)) continue;
            if (!s.order().equals(s.before())) resequence(t, bankAccountId, day.getKey(), s.order());
            DayState now = s.state();
            jdbc.update("""
                    insert into bank_statement_days (tenant_id, bank_account_id, txn_date, order_confirmed, merge_unproven,
                                                     start_known, end_known, updated_at)
                    values (:t, :b, :d, :ok, :unproven, :start, :end, now())
                    on conflict (bank_account_id, txn_date)
                    do update set order_confirmed = excluded.order_confirmed, merge_unproven = excluded.merge_unproven,
                                  start_known = excluded.start_known, end_known = excluded.end_known,
                                  updated_at = excluded.updated_at""",
                    new MapSqlParameterSource("t", t).addValue("b", bankAccountId).addValue("d", day.getKey())
                            .addValue("ok", now.confirmed()).addValue("unproven", now.unproven())
                            .addValue("start", now.startKnown()).addValue("end", now.endKnown()));
            // #441 review: a statement holding the day whole proved every kept look-alike a line of its own.
            if (now.confirmed()) {
                jdbc.update("""
                        update bank_statement_lines set lookalike_kept = false
                        where tenant_id = :t and bank_account_id = :b and txn_date = :d and lookalike_kept""",
                        new MapSqlParameterSource("t", t).addValue("b", bankAccountId).addValue("d", day.getKey()));
            }
        }
    }

    /** Hands the day's own seq values out again in {@code order} (line hashes). */
    private void resequence(UUID t, UUID bankAccountId, LocalDate day, List<String> order) {
        List<Long> pool = new ArrayList<>(jdbc.queryForList("""
                select seq from bank_statement_lines where tenant_id = :t and bank_account_id = :b and txn_date = :d
                order by seq""", new MapSqlParameterSource("t", t).addValue("b", bankAccountId).addValue("d", day),
                Long.class));
        List<SqlParameterSource> updates = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            updates.add(new MapSqlParameterSource("t", t).addValue("b", bankAccountId).addValue("h", order.get(i))
                    .addValue("s", pool.get(i)));
        }
        jdbc.batchUpdate("""
                update bank_statement_lines set seq = :s
                where tenant_id = :t and bank_account_id = :b and line_hash = :h""",
                updates.toArray(SqlParameterSource[]::new));
        log.info("Statement import for bank account {}: {} re-sequenced to the file's order", bankAccountId, day);
    }

    /**
     * Where a day sits in a file. A file is one contiguous stretch of the
     * statement, so on a day that is not its first date its lines start at the
     * day's first line, and on a day that is not its last date they run to the
     * day's last line.
     */
    enum Pos {
        /** The file's only date: its lines are some stretch of the day. */
        SINGLE,
        /** The first of several dates: its lines are the day's last lines. */
        FIRST,
        /** The last of several dates: its lines are the day's first lines. */
        LAST,
        /** Neither: the file holds the whole day. */
        INTERIOR;

        static Pos of(LocalDate day, LocalDate first, LocalDate last) {
            return of(day, first, last, false, false);
        }

        /**
         * With an explicit opening-balance row the export starts at the start of its
         * first date; with a closing-balance row it ends at the end of its last
         * (review P2-3, StatementMapper.Result#openingRow).
         */
        static Pos of(LocalDate day, LocalDate first, LocalDate last, boolean openingRow, boolean closingRow) {
            boolean starts = !day.equals(first) || openingRow;
            boolean ends = !day.equals(last) || closingRow;
            if (starts && ends) return INTERIOR;
            if (starts) return LAST;
            if (ends) return FIRST;
            return SINGLE;
        }

        boolean startsTheDay() {
            return this == LAST || this == INTERIOR;
        }

        boolean endsTheDay() {
            return this == FIRST || this == INTERIOR;
        }
    }

    /**
     * What is known about a stored day ({@code bank_statement_days}).
     *
     * @param confirmed  its lines are in the bank's order (no typed balance needed)
     * @param unproven   a file's line was taken for a stored identical line while
     *                   the file did not hold the whole day: it may have been a
     *                   second, separate line, and only a file holding the whole
     *                   day can settle that (S20-01)
     * @param startKnown the stored lines begin at the day's first line
     * @param endKnown   the stored lines run to the day's last line
     */
    record DayState(boolean confirmed, boolean unproven, boolean startKnown, boolean endKnown) {
        /** A day with no row: imported before changeset 171, or never. */
        static final DayState UNKNOWN = new DayState(true, false, false, false);
    }

    /**
     * One day after one import, from line hashes: {@code before} is the stored
     * order once the new lines are appended, {@code order} the day's order after
     * the import, {@code state} what is known about the day then.
     */
    record Settled(List<String> before, List<String> order, boolean addsLines, DayState state) {
        boolean confirmed() {
            return state.confirmed();
        }
    }

    /**
     * #97: what one import does to one day ({@code stored}: the day's stored
     * hashes in seq order; {@code file}: the file's lines of that day in file
     * order, each the hash of the stored line it was matched to or a new one;
     * {@code pos}: where the day sits in the file; {@code was}: what was known).
     *
     * <ul>
     *   <li>Nothing stored yet: the day is the file's lines, confirmed.</li>
     *   <li>The file holds the whole day and every stored line: the day becomes
     *       the file's lines in the file's order, confirmed and complete.</li>
     *   <li>The file adds nothing and its lines are exactly the stored lines at
     *       the place its position fixes — the day's last lines when the file and
     *       the stored lines both run to the day's end: the same lines again (a
     *       part re-import); null, the day is left as it is (S20-03, narrowed by
     *       S25-P1: {@link #sameLinesAgain}).</li>
     *   <li>Otherwise the day is not confirmed. If any of the file's lines was
     *       taken for a stored one, the day is also <em>unproven</em>.</li>
     * </ul>
     *
     * <p>Why (review P2-2, sim S20-01): two lines of a day can be identical —
     * same amount, description and running balance — at different places: a fee,
     * its reversal, the fee again. A file that ends at the first fee and a file
     * that starts at the second look exactly like two files overlapping by one
     * line. Taking the second fee for the first loses it — and the reversal
     * between them, if no file brought it, nets it to nothing, so no balance can
     * show it. Only a file that holds the whole day can say how many such lines
     * there are. Until one is imported, an unproven day cannot be finalized,
     * typed balances or not.</p>
     */
    static Settled settle(List<String> stored, List<String> file, Pos pos, DayState was) {
        return settle(stored, file, pos, was, false);
    }

    /**
     * As above; {@code twinned}: a new line of the file has an untaken stored look-alike at
     * another balance (sim r25 review) — on a day the file does not hold whole that unproves
     * the day like a line taken for an identical one.
     */
    static Settled settle(List<String> stored, List<String> file, Pos pos, DayState was, boolean twinned) {
        Set<String> inStored = new HashSet<>(stored);
        boolean addsLines = file.stream().anyMatch(h -> !inStored.contains(h));
        boolean matched = file.stream().anyMatch(inStored::contains) || twinned;
        if (stored.isEmpty()) {
            return new Settled(List.copyOf(file), List.copyOf(file), true,
                    new DayState(true, false, pos.startsTheDay(), pos.endsTheDay()));
        }
        boolean covers = new HashSet<>(file).containsAll(stored);
        List<String> before = addsLines ? appended(stored, file) : stored;
        if (covers && pos == Pos.INTERIOR) {
            return new Settled(before, addsLines ? dayOrder(stored, file) : List.copyOf(file), addsLines,
                    new DayState(true, false, true, true));
        }
        if (!addsLines && sameLinesAgain(stored, file, pos, was)) return null;
        return new Settled(before, addsLines ? dayOrder(stored, file) : stored, addsLines,
                new DayState(false, was.unproven() || matched, false, false));
    }

    /**
     * S20-03, narrowed by S25-P1 (#441 review): the file's lines are the stored ones its
     * position pins them to (see {@link #settle}).
     *
     * <p>Once the bank can back-value, a line may be added in front of an identical stored
     * one — anywhere in the day, its first line too. The added line carries exactly the
     * balance the stored one had (the stored one moves on), so an export cut right after it
     * matches the stored lines exactly and brings nothing new: taking it for the stored line
     * loses it. Only a file that runs to the end of the day ({@link Pos#endsTheDay}) over the
     * stored day's last lines, stored up to the day's end, rules that out for one added line:
     * a line added in front of one of them would be in the file too (a row more than stored),
     * and one added after them would carry another balance. Two added lines that net to
     * nothing can still reproduce those balances exactly; the file is then the same bytes as a
     * re-export, and that residue is kept (the S25-P1 report counts it).</p>
     *
     * <p>Anything else is unproven — a run from the day's start (even its first line alone, a
     * whole-day export ending on the day included) or any run inside a day stored whole: the
     * bytes are the same either way, so the line cannot be stored without storing every such
     * re-import twice. The day's paper totals, or a statement holding the day whole, decide.</p>
     */
    static boolean sameLinesAgain(List<String> stored, List<String> file, Pos pos, DayState was) {
        if (was.unproven() || file.isEmpty() || file.size() > stored.size()) return false;
        return was.endKnown() && pos.endsTheDay() && stored.subList(stored.size() - file.size(), stored.size()).equals(file);
    }

    /** Stored lines, then the file's new ones in file order: what an import did before #97. */
    static List<String> appended(List<String> stored, List<String> file) {
        Set<String> inStored = new HashSet<>(stored);
        List<String> out = new ArrayList<>(stored);
        file.stream().filter(h -> !inStored.contains(h)).forEach(out::add);
        return out;
    }

    /**
     * #97 P2-1: the order of one day's lines after an import, from line hashes.
     *
     * <p>A file is one contiguous stretch of the bank's statement, in the bank's
     * order (the import checks its running balance). When it contains stored lines
     * of the day, it is the authority for everything between the first and the
     * last of them: the day becomes the stored lines before that stretch, the
     * whole file, then the stored lines after it. That is how a fuller export
     * back-fills lines in the middle of a day.</p>
     *
     * <p>Otherwise the old order stands — stored lines, then the new ones — and
     * the reconciliation's strict per-line check decides:</p>
     * <ul>
     *   <li>the file shares no line with the day (nothing says where its lines
     *       belong, so they go after the ones imported earlier);</li>
     *   <li>a stored line inside the file's stretch is missing from the file (the
     *       file and the stored lines disagree).</li>
     * </ul>
     * <p>No order is ever guessed from balances: re-ordering by balance can chain
     * the remaining lines round a missing one (review P1-1).</p>
     */
    static List<String> dayOrder(List<String> stored, List<String> file) {
        Set<String> inFile = new HashSet<>(file);
        int first = -1, last = -1;
        for (int i = 0; i < stored.size(); i++) {
            if (!inFile.contains(stored.get(i))) continue;
            if (first < 0) first = i;
            last = i;
        }
        if (first < 0) return appended(stored, file);
        for (int i = first; i <= last; i++) {
            if (!inFile.contains(stored.get(i))) return appended(stored, file);
        }
        List<String> out = new ArrayList<>(stored.subList(0, first));
        out.addAll(file);
        out.addAll(stored.subList(last + 1, stored.size()));
        return out;
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
                r.closingBalance(), r.hasBalance(), r.order(), r.openingRow(), r.closingRow(), r.closingRowDate());
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

    /**
     * S25-01: the stored lines this file holds at a moved running balance — the bank added a
     * line dated before them after they were imported (a back-valued line).
     */
    static Optional<String> movedWarning(List<StatementMapper.Row> rows, List<String> hashes, Map<String, BigDecimal> moved,
                                         Map<String, StoredLine> storedByHash) {
        if (moved.isEmpty()) return Optional.empty();
        // #438 review: each line's own shift — two back-values move later lines by different amounts.
        List<String> each = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            if (!moved.containsKey(hashes.get(i))) continue;
            each.add(dmy(rows.get(i).txnDate()) + " " + rows.get(i).description() + " by "
                    + StatementValues.money(moved.get(hashes.get(i)).subtract(storedByHash.get(hashes.get(i)).balance())));
        }
        String list = each.size() > 20 ? String.join("; ", each.subList(0, 20)) + "; and " + (each.size() - 20) + " more"
                : String.join("; ", each);
        return Optional.of(moved.size() + " line(s) already imported carry a running balance the bank has since moved ("
                + list + "): the bank added a line dated before them after they were imported (a back-valued line). They "
                + "were taken as the same lines, not stored again, and now carry this statement's balances.");
    }

    private static String dmy(LocalDate d) {
        return d.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    private BankRecDTOs.ImportResult profileRequired(String reason, String reasonCode, Map<String, Object> reasonArgs,
                                                     StatementGrid g, BankStatementProfile.FileKind kind,
                                                     List<String> missing, String delimiter) {
        return new BankRecDTOs.ImportResult("PROFILE_REQUIRED", reason, gridPreview(g), g.sheetNames(), g.sheetName(),
                kind.name(), missing, List.of(), List.of(), 0, 0, 0, null, null, null, null, null, List.of(), null,
                delimiter, reasonCode, reasonCode == null ? null : reasonArgs);
    }

    private static BankRecDTOs.ImportResult result(String status, String reason, StatementGrid g,
                                                   BankStatementProfile.FileKind kind, StatementMapper.Result r,
                                                   List<String> errors, List<String> warnings, int fresh, int dup,
                                                   List<BankRecDTOs.PreviewRow> rows, UUID importId, String delimiter) {
        LocalDate first = r.rows().stream().map(StatementMapper.Row::txnDate).min(Comparator.naturalOrder()).orElse(null);
        LocalDate last = r.rows().stream().map(StatementMapper.Row::txnDate).max(Comparator.naturalOrder()).orElse(null);
        return new BankRecDTOs.ImportResult(status, reason, "IMPORTED".equals(status) ? List.of() : gridPreview(g),
                g.sheetNames(), g.sheetName(), kind.name(), List.of(), errors, warnings, r.rows().size(), fresh, dup,
                first, last, r.openingBalance(), r.closingBalance(), r.order().name(), rows, importId, delimiter, null, null);
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
                where l.tenant_id = :t and l.bank_account_id = :b
                  and """ + ONLY_THIS_IMPORT + """
                """,
                new MapSqlParameterSource("t", t).addValue("b", bankAccountId), UUID.class));
        return imports.findByBankAccountIdOrderByImportedAtDesc(bankAccountId).stream()
                .map(i -> new BankRecDTOs.ImportRow(i.getId(), i.getFileName(), i.getLinesRead(), i.getLinesNew(),
                        i.getLinesDuplicate(), i.getFirstDate(), i.getLastDate(), i.getOpeningBalance(),
                        i.getClosingBalance(), i.getWarnings() == null ? List.of() : i.getWarnings(), i.getImportedAt(),
                        !locked.contains(i.getId())))
                .toList();
    }

    /**
     * The stored line {@code l} would go with its import: no other import brought it
     * (review P3-15: a line another import also brought stays, re-attributed to it).
     */
    static final String ONLY_THIS_IMPORT = " not exists (select 1 from bank_statement_line_sources s"
            + " where s.line_id = l.id and s.tenant_id = l.tenant_id and s.import_id <> l.import_id) ";

    /**
     * Delete import (spec §3): its lines go, while none of them is in a CONFIRMED
     * match. An undone match is the audit trail the spec keeps (PR #353 review), so
     * a line with any match history also holds its import; only suggestions go.
     *
     * <p>Sim r21 S21-03: only the lines that actually go count. A line another import
     * also brought stays — the same row, re-attributed, with its matches and their
     * history untouched — so it holds nothing. That is what makes "import one
     * statement holding the day whole, then delete the partial imports" work after
     * the lines were matched and confirmed.</p>
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
                where l.tenant_id = :t and l.import_id = :i and """ + ONLY_THIS_IMPORT, p, Integer.class);
        if (confirmed != null && confirmed > 0) {
            throw BankRecRefusal.refuse("importLinesConfirmed", confirmed + " line(s) only this import brought are in "
                    + "confirmed matches; undo those matches first. Lines another import also holds are kept with their "
                    + "matches, so importing a statement that holds these lines too, before deleting, also works.",
                    "count", confirmed);
        }
        Integer history = jdbc.queryForObject("""
                select count(*) from bank_statement_lines l
                join bank_match_statement_lines ml on ml.statement_line_id = l.id
                join bank_matches m on m.id = ml.match_id and m.status = 'UNDONE'
                where l.tenant_id = :t and l.import_id = :i and """ + ONLY_THIS_IMPORT, p, Integer.class);
        if (history != null && history > 0) {
            throw BankRecRefusal.refuse("importHasHistory", "Lines of this import have match history (undone matches), "
                    + "which is kept as the audit trail; the import cannot be deleted");
        }
        List<UUID> matches = jdbc.queryForList("""
                select distinct ml.match_id from bank_match_statement_lines ml
                join bank_statement_lines l on l.id = ml.statement_line_id
                join bank_matches m on m.id = ml.match_id and m.status = 'SUGGESTED'
                where l.tenant_id = :t and l.import_id = :i and """ + ONLY_THIS_IMPORT, p, UUID.class);
        if (!matches.isEmpty()) {
            MapSqlParameterSource mp = new MapSqlParameterSource("t", t).addValue("m", matches);
            jdbc.update("delete from bank_match_statement_lines where tenant_id = :t and match_id in (:m)", mp);
            jdbc.update("delete from bank_match_book_items where tenant_id = :t and match_id in (:m)", mp);
            jdbc.update("delete from bank_matches where tenant_id = :t and id in (:m)", mp);
        }
        // The days this import brought lines to (stored or matched), before anything moves.
        List<LocalDate> days = jdbc.queryForList("""
                select distinct l.txn_date from bank_statement_lines l
                where l.tenant_id = :t and l.bank_account_id = :b
                  and (l.import_id = :i or exists (select 1 from bank_statement_line_sources s
                                                    where s.line_id = l.id and s.import_id = :i))""", p, LocalDate.class);
        // Review P3-15: a line this import stored that another import also brought stays,
        // now belonging to the earliest such import; only lines no other import brought go.
        jdbc.update("""
                update bank_statement_lines l
                   set import_id = (select s.import_id from bank_statement_line_sources s
                                      join bank_statement_imports i on i.id = s.import_id
                                     where s.line_id = l.id and s.import_id <> :i
                                     order by i.imported_at, i.id limit 1)
                 where l.tenant_id = :t and l.import_id = :i
                   and exists (select 1 from bank_statement_line_sources s where s.line_id = l.id and s.import_id <> :i)""", p);
        jdbc.update("delete from bank_statement_line_sources where tenant_id = :t and import_id = :i", p);
        jdbc.update("delete from bank_statement_lines where tenant_id = :t and import_id = :i", p);
        recomputeDays(t, imp.getBankAccountId(), importId, days);
        imports.delete(imp);
    }

    /**
     * Review P2-3 (3): after an import is deleted, a day whose remaining lines all came
     * from one import, which is also the only other import covering the day, is that
     * import's stretch again — in its order, nothing taken for anything — so its row goes
     * (confirmed). A day with no lines left loses its row. Any other day keeps its state:
     * deleting lines proves nothing about the ones left.
     */
    private void recomputeDays(UUID t, UUID bankAccountId, UUID deletedImport, List<LocalDate> days) {
        for (LocalDate d : days) {
            MapSqlParameterSource p = new MapSqlParameterSource("t", t).addValue("b", bankAccountId).addValue("d", d)
                    .addValue("x", deletedImport);
            List<UUID> sources = jdbc.queryForList("""
                    select distinct s.import_id from bank_statement_line_sources s
                    join bank_statement_lines l on l.id = s.line_id
                    where l.tenant_id = :t and l.bank_account_id = :b and l.txn_date = :d""", p, UUID.class);
            Integer lines = jdbc.queryForObject("""
                    select count(*) from bank_statement_lines where tenant_id = :t and bank_account_id = :b and txn_date = :d""",
                    p, Integer.class);
            List<UUID> covering = jdbc.queryForList("""
                    select id from bank_statement_imports where tenant_id = :t and bank_account_id = :b and id <> :x
                      and first_date is not null and last_date is not null and :d between first_date and last_date""",
                    p, UUID.class);
            boolean oneSource = lines != null && lines > 0 && sources.size() == 1 && covering.equals(sources);
            if ((lines != null && lines == 0) || oneSource) {
                jdbc.update("delete from bank_statement_days where tenant_id = :t and bank_account_id = :b and txn_date = :d", p);
            }
        }
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
            throw BankRecRefusal.refuse("unknownValue", "Unknown " + what + " " + v, "what", what, "value", v);
        }
    }

    static UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof String s)) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}
