package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AccountImportService {

    private final AccountRepository repository;

    /**
     * One parsed row: the account itself plus the parent code the file named,
     * which cannot be resolved until every row has been read (a file is free to
     * list a child above its parent).
     */
    private record ParsedRow(Account account, String parentCode, int rowNumber) {}

    /**
     * What an import did. Break-it R3 data3 F7: the dialog reports these instead of
     * closing as if something happened when nothing did.
     *
     * @param created  accounts saved
     * @param skipped  blank lines passed over
     */
    public record Result(int created, int skipped, List<Account> accounts) {}

    private static final String VALID_TYPES = "ASSET, LIABILITY, INCOME, EXPENSE, EQUITY";

    /**
     * {@code code,name,type[,parentCode[,nameAr[,description[,isGroup]]]]}. Break-it R3
     * data3 F7: a row that stops after the type is an account with no parent (the
     * trailing columns are optional); a row that is malformed is a row error — it used
     * to be skipped silently, and a file of such rows "imported" nothing with a 200.
     * All rows are checked before anything is saved; any error saves nothing.
     */
    @Transactional
    public Result importFromCsv(MultipartFile file) throws Exception {
        List<ParsedRow> rows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int skipped = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            reader.readLine(); // skip header
            String line;
            int rowNumber = 1;
            while ((line = reader.readLine()) != null) {
                rowNumber++;
                List<String> cols = splitCsvLine(line);
                if (cols.stream().allMatch(String::isBlank)) {
                    skipped++;
                    continue;
                }
                if (cols.size() < 3) {
                    errors.add("Row " + rowNumber + ": needs at least code, name and type");
                    continue;
                }
                String code = cols.get(0).trim();
                String name = cols.get(1).trim();
                String type = cols.get(2).trim();
                Account a = parsedAccount(code, name, type, rowNumber, errors);
                if (a == null) continue;
                a.setNameAr(col(cols, 4));
                a.setDescription(col(cols, 5));
                a.setGroup(Boolean.parseBoolean(col(cols, 6)));
                String parentCode = col(cols, 3);
                rows.add(new ParsedRow(a, parentCode.isEmpty() ? null : parentCode, rowNumber));
            }
        }
        return finish(rows, errors, skipped);
    }

    /** The account a row describes, or null with the row's problems added to {@code errors}. */
    private static Account parsedAccount(String code, String name, String type, int rowNumber, List<String> errors) {
        int before = errors.size();
        if (code == null || code.isEmpty()) errors.add("Row " + rowNumber + ": code is required");
        if (name == null || name.isEmpty()) errors.add("Row " + rowNumber + ": name is required");
        AccountType accountType = null;
        if (type == null || type.isEmpty()) {
            errors.add("Row " + rowNumber + ": type is required (" + VALID_TYPES + ")");
        } else {
            try {
                accountType = AccountType.valueOf(type.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                errors.add("Row " + rowNumber + ": invalid account type '" + type + "'. Valid types: " + VALID_TYPES);
            }
        }
        if (errors.size() > before) return null;
        Account a = new Account();
        a.setCode(code);
        a.setName(name);
        a.setNameEn(name);
        a.setAccountType(accountType);
        a.setSystem(false);
        return a;
    }

    /**
     * Duplicate and existing codes become row errors (they used to surface as the
     * raw constraint name {@code uq_accounts_tenant_code}); then all or nothing.
     */
    private Result finish(List<ParsedRow> rows, List<String> errors, int skipped) {
        Map<String, Integer> firstRow = new HashMap<>();
        for (ParsedRow row : rows) {
            String code = row.account().getCode();
            Integer first = firstRow.putIfAbsent(code, row.rowNumber());
            if (first != null) {
                errors.add("Row " + row.rowNumber() + ": code " + code + " is already on row " + first);
            } else if (repository.findByCode(code).isPresent()) {
                errors.add("Row " + row.rowNumber() + ": account code " + code + " already exists");
            }
        }
        if (!errors.isEmpty()) {
            errors.sort(Comparator.comparingInt(AccountImportService::rowOf));
            throw new IllegalArgumentException(errors.size() + (errors.size() == 1 ? " row has" : " rows have")
                    + " problems; nothing was imported. " + String.join("; ", errors));
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("The file has no account rows; nothing was imported");
        }
        List<Account> saved = linkAndSave(rows);
        return new Result(saved.size(), skipped, saved);
    }

    private static int rowOf(String error) {
        try {
            return Integer.parseInt(error.substring(4, error.indexOf(':')));
        } catch (RuntimeException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static String col(List<String> cols, int i) {
        return cols.size() > i && cols.get(i) != null ? cols.get(i).trim() : "";
    }

    /** One RFC 4180 line: quoted cells may hold commas and doubled quotes. A leading BOM is dropped. */
    static List<String> splitCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        String s = line.startsWith("\uFEFF") ? line.substring(1) : line;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < s.length() && s.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else if (c == '"') quoted = false;
                else cell.append(c);
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        out.add(cell.toString());
        return out;
    }

    /**
     * A chart of accounts out of a spreadsheet.
     *
     * <p><b>Opened through {@link WorkbookGuard}</b>, like every other uploaded
     * workbook. This endpoint takes an untrusted file and hands it to a parser that
     * builds the whole thing in memory: an over-inflating archive here does not fail
     * the import, it exhausts the heap and takes the process — and everyone else's
     * requests — with it. The guard is the single door, so the chart import, the v1
     * portfolio import and the cut-over import are all judged by the same limits
     * rather than by whichever one was last reviewed. It also means a file that is
     * not really an .xlsx, a macro-enabled one, or a password-protected one is a 400
     * with one sentence instead of a stack trace out of POI.</p>
     *
     * <p>The client's own chart is 826 rows, so no cap needed adjusting.</p>
     */
    @Transactional
    public Result importFromExcel(MultipartFile file) throws Exception {
        List<ParsedRow> parsed = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int skipped = 0;
        try (Workbook wb = WorkbookGuard.open(file.getBytes())) {
            Sheet sheet = wb.getSheetAt(0);
            Iterator<Row> rows = sheet.iterator();
            if (rows.hasNext()) rows.next(); // skip header
            int rowNumber = 1;
            while (rows.hasNext()) {
                Row row = rows.next();
                rowNumber = row.getRowNum() + 1;
                String code = getCellString(row, 0);
                String name = getCellString(row, 1);
                String typeStr = getCellString(row, 3);
                if (code.isEmpty() && name.isEmpty() && typeStr.isEmpty()) {
                    skipped++;
                    continue;
                }
                // Break-it R3 data3 F7: a row with no type (or no code) is a row error, not skipped silently.
                Account a = parsedAccount(code, name, typeStr, rowNumber, errors);
                if (a == null) continue;
                a.setNameAr(getCellString(row, 2));
                a.setDescription(getCellString(row, 5));
                a.setGroup(row.getCell(6) != null &&
                    row.getCell(6).getCellType() == CellType.BOOLEAN &&
                    row.getCell(6).getBooleanCellValue());
                String parent = getCellString(row, 4);
                parsed.add(new ParsedRow(a, parent.isEmpty() ? null : parent, rowNumber));
            }
        }
        return finish(parsed, errors, skipped);
    }

    /**
     * Resolves each row's parent code — first against the rows in this file, then
     * against accounts the tenant already has — and saves parents before children
     * so the parent_id foreign key holds on insert.
     */
    private List<Account> linkAndSave(List<ParsedRow> rows) {
        Map<String, ParsedRow> byCode = new LinkedHashMap<>();
        for (ParsedRow row : rows) {
            byCode.put(row.account().getCode(), row);
        }

        for (ParsedRow row : rows) {
            String parentCode = row.parentCode();
            if (parentCode == null) continue;
            ParsedRow inFile = byCode.get(parentCode);
            if (inFile != null) {
                row.account().setParent(inFile.account());
                continue;
            }
            Account existing = repository.findByCode(parentCode)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown parent code: " + parentCode + " on row " + row.rowNumber()));
            row.account().setParent(existing);
        }

        List<Account> ordered = new ArrayList<>(rows.size());
        for (ParsedRow row : rows) {
            ordered.add(row.account());
        }
        ordered.sort(Comparator.comparingInt(a -> depthInFile(a, byCode)));
        return repository.saveAll(ordered);
    }

    /**
     * How many of this account's ancestors are also in this file. Rows whose
     * parent already exists in the database are depth 0 and go first; a row
     * whose parent is in the file sorts after it. The walk is bounded by the
     * number of rows so a cyclic file cannot hang the import.
     */
    private int depthInFile(Account account, Map<String, ParsedRow> byCode) {
        int depth = 0;
        Account cursor = account;
        while (depth <= byCode.size()) {
            Account parent = cursor.getParent();
            if (parent == null) return depth;
            ParsedRow inFile = byCode.get(parent.getCode());
            if (inFile == null || inFile.account() != parent) return depth;
            depth++;
            cursor = parent;
        }
        throw new IllegalArgumentException("Account parent codes form a cycle around: " + account.getCode());
    }

    private String getCellString(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            default -> "";
        };
    }
}
