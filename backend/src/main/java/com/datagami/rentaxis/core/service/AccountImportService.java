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

    @Transactional
    public List<Account> importFromCsv(MultipartFile file) throws Exception {
        List<ParsedRow> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            reader.readLine(); // skip header
            String line;
            int rowNumber = 1;
            while ((line = reader.readLine()) != null) {
                rowNumber++;
                String[] cols = line.split(",", -1);
                if (cols.length < 4) continue;
                Account a = new Account();
                a.setCode(cols[0].trim());
                a.setName(cols[1].trim());
                a.setNameEn(cols[1].trim());
                a.setNameAr(cols.length > 4 ? cols[4].trim() : "");
                try {
                    a.setAccountType(AccountType.valueOf(cols[2].trim().toUpperCase()));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Invalid account type '" + cols[2].trim() + "' at line " + rowNumber + ". Valid types: ASSET, LIABILITY, INCOME, EXPENSE, EQUITY");
                }
                a.setDescription(cols.length > 5 ? cols[5].trim() : "");
                a.setGroup(cols.length > 6 && Boolean.parseBoolean(cols[6].trim()));
                a.setSystem(false);
                String parentCode = cols[3].trim().isEmpty() ? null : cols[3].trim();
                rows.add(new ParsedRow(a, parentCode, rowNumber));
            }
        }
        return linkAndSave(rows);
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
    public List<Account> importFromExcel(MultipartFile file) throws Exception {
        List<ParsedRow> parsed = new ArrayList<>();
        try (Workbook wb = WorkbookGuard.open(file.getBytes())) {
            Sheet sheet = wb.getSheetAt(0);
            Iterator<Row> rows = sheet.iterator();
            if (rows.hasNext()) rows.next(); // skip header
            int rowNumber = 1;
            while (rows.hasNext()) {
                Row row = rows.next();
                rowNumber++;
                String code = getCellString(row, 0);
                if (code == null || code.isEmpty()) continue;
                Account a = new Account();
                a.setCode(code);
                a.setName(getCellString(row, 1));
                a.setNameEn(getCellString(row, 1));
                a.setNameAr(getCellString(row, 2));
                String typeStr = getCellString(row, 3);
                if (typeStr == null || typeStr.isEmpty()) continue;
                try {
                    a.setAccountType(AccountType.valueOf(typeStr.toUpperCase()));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Invalid account type '" + typeStr + "' at row " + rowNumber + ". Valid types: ASSET, LIABILITY, INCOME, EXPENSE, EQUITY");
                }
                a.setDescription(getCellString(row, 5));
                a.setGroup(row.getCell(6) != null &&
                    row.getCell(6).getCellType() == CellType.BOOLEAN &&
                    row.getCell(6).getBooleanCellValue());
                a.setSystem(false);
                String parent = getCellString(row, 4);
                parsed.add(new ParsedRow(a, parent == null || parent.isEmpty() ? null : parent, rowNumber));
            }
        }
        return linkAndSave(parsed);
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
