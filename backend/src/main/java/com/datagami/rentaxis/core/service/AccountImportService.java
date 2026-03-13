package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.entity.enums.AccountSubType;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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

    @Transactional
    public List<Account> importFromCsv(MultipartFile file) throws Exception {
        List<Account> accounts = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            String header = reader.readLine(); // skip header
            String line;
            while ((line = reader.readLine()) != null) {
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
                    throw new IllegalArgumentException("Invalid account type '" + cols[2].trim() + "' at line " + (accounts.size() + 2) + ". Valid types: ASSET, LIABILITY, INCOME, EXPENSE, EQUITY");
                }
                a.setParentCode(cols[3].trim().isEmpty() ? null : cols[3].trim());
                a.setDescription(cols.length > 5 ? cols[5].trim() : "");
                a.setGroup(cols.length > 6 && Boolean.parseBoolean(cols[6].trim()));
                a.setHierarchyLevel(a.getParentCode() == null ? 1 :
                    (int) a.getCode().chars().filter(c -> c == '-').count());
                accounts.add(a);
            }
        }
        return repository.saveAll(accounts);
    }

    @Transactional
    public List<Account> importFromExcel(MultipartFile file) throws Exception {
        List<Account> accounts = new ArrayList<>();
        try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
            Iterator<Row> rows = sheet.iterator();
            if (rows.hasNext()) rows.next(); // skip header
            while (rows.hasNext()) {
                Row row = rows.next();
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
                    throw new IllegalArgumentException("Invalid account type '" + typeStr + "' at row " + (accounts.size() + 2) + ". Valid types: ASSET, LIABILITY, INCOME, EXPENSE, EQUITY");
                }
                String parent = getCellString(row, 4);
                a.setParentCode(parent == null || parent.isEmpty() ? null : parent);
                a.setDescription(getCellString(row, 5));
                a.setGroup(row.getCell(6) != null &&
                    row.getCell(6).getCellType() == CellType.BOOLEAN &&
                    row.getCell(6).getBooleanCellValue());
                a.setHierarchyLevel(a.getParentCode() == null ? 1 :
                    (int) a.getCode().chars().filter(c -> c == '-').count());
                accounts.add(a);
            }
        }
        return repository.saveAll(accounts);
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
