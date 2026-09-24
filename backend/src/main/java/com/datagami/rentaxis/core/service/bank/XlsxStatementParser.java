package com.datagami.rentaxis.core.service.bank;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.WorkbookGuard;
import com.datagami.rentaxis.domain.entity.BankStatementProfile;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * XLSX through {@link WorkbookGuard} (zip-bomb ratios, entry and text caps,
 * macros and encryption refused). A date-formatted cell becomes a LocalDate
 * ({@code DateUtil.getLocalDateTimeCellValue}); any other number a BigDecimal.
 */
@Component
public class XlsxStatementParser implements StatementParser {

    @Override
    public BankStatementProfile.FileKind kind() {
        return BankStatementProfile.FileKind.XLSX;
    }

    @Override
    public StatementGrid read(byte[] bytes, String sheetName, String delimiter) {
        try (Workbook wb = WorkbookGuard.open(bytes)) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) names.add(wb.getSheetName(i));
            Sheet sheet = sheetName == null || sheetName.isBlank() ? wb.getSheetAt(0) : wb.getSheet(sheetName);
            if (sheet == null) {
                throw BankRecRefusal.refuse("noSuchSheet", "The workbook has no sheet named \"" + sheetName + "\"", "sheet", sheetName);
            }
            if (sheet.getLastRowNum() + 1 > MAX_GRID_ROWS) throw CsvStatementParser.tooMany();
            FormulaEvaluator eval = wb.getCreationHelper().createFormulaEvaluator();
            List<List<Object>> rows = new ArrayList<>();
            for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                List<Object> cells = new ArrayList<>();
                if (row != null) {
                    // Only the cells that exist, never the row's addressable span (P1-1).
                    for (Cell cell : row) {
                        int c = cell.getColumnIndex();
                        Object v = value(cell, eval);
                        if (c >= MAX_COLS) {
                            if (v == null) continue;
                            throw BankRecRefusal.refuse("tooManyColumnsAt", "Row " + (r + 1) + " has a value in column "
                                    + StatementMapper.letter(c) + "; a statement may use at most " + MAX_COLS + " columns", "row", r + 1, "column", StatementMapper.letter(c), "max", MAX_COLS);
                        }
                        while (cells.size() < c) cells.add(null);
                        cells.add(v);
                    }
                }
                rows.add(cells);
            }
            return new StatementGrid(rows, names, sheet.getSheetName());
        } catch (IOException e) {
            throw BankRecRefusal.refuse("workbookUnreadable", "The workbook could not be read");
        }
    }

    static Object value(Cell cell, FormulaEvaluator eval) {
        if (cell == null) return null;
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            try {
                type = cell.getCachedFormulaResultType();
            } catch (RuntimeException e) {
                return null;
            }
        }
        return switch (type) {
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().toLocalDate()
                    : new BigDecimal(Double.toString(cell.getNumericCellValue()));
            case STRING -> {
                String s = cell.getStringCellValue().trim();
                yield s.isEmpty() ? null : s;
            }
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            default -> null;
        };
    }
}
