package com.datagami.rentaxis.core.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The template is a customer-facing artifact: every organisation that downloads
 * it gets these sample rows, whoever they are.
 *
 * <p>That makes it the one place where a name copied out of the client's own PACT
 * export stops being a scruffy fixture and becomes one landlord's renter handed to
 * every other landlord. The first cut of this template shipped a real tenant's
 * name and email, taken from the General Ledger exports the design was written
 * against; this test is the thing that stops it coming back the next time someone
 * reaches for realistic-looking sample data.</p>
 *
 * <p>The denylist is deliberately short and literal — the real names that were in
 * the repository — rather than a clever heuristic. A heuristic that tried to guess
 * whether a name is real would either pass everything or fail on "Sample Tower".</p>
 */
class CutOverTemplatePrivacyTest {

    /**
     * Names, properties and institutions taken from the client's real exports.
     * Lower-cased; matched as substrings against every string in the workbook.
     */
    private static final List<String> DENIED = List.of(
            // real renters
            "mamanov", "ishtiaq", "prabhjot", "anum",
            // real properties / towers
            "tulip", "olivier", "boulevard", "freej", "warsan", "belle vue", "galah",
            "ocean residencia", "constance", "valencia", "grand residence", "l'horizon",
            // the real landlord and its bank
            "ashram", "emirates islamic", "enbd");

    @Test
    void theCutOverTemplateContainsNoRealClientData() throws Exception {
        assertThat(stringsIn(new PortfolioTemplateService().generateCutOverTemplate()))
                .describedAs("every string in the cut-over template workbook")
                .allSatisfy(s -> assertThat(DENIED)
                        .describedAs("denied name found in template cell '%s'", s)
                        .noneMatch(denied -> s.toLowerCase(Locale.ROOT).contains(denied)));
    }

    /** The ordinary portfolio template is held to the same bar. */
    @Test
    void theV1TemplateContainsNoRealClientDataEither() throws Exception {
        assertThat(stringsIn(new PortfolioTemplateService().generateTemplate()))
                .allSatisfy(s -> assertThat(DENIED)
                        .describedAs("denied name found in template cell '%s'", s)
                        .noneMatch(denied -> s.toLowerCase(Locale.ROOT).contains(denied)));
    }

    /** Proof the check can fail: the denylist really is matched against cell text. */
    @Test
    void theCheckWouldCatchARealNameIfOneCameBack() throws Exception {
        List<String> cells = stringsIn(new PortfolioTemplateService().generateCutOverTemplate());
        List<String> withAReintroducedName = new ArrayList<>(cells);
        withAReintroducedName.add("Islam Mamanov");

        assertThat(withAReintroducedName)
                .anySatisfy(s -> assertThat(DENIED)
                        .anyMatch(denied -> s.toLowerCase(Locale.ROOT).contains(denied)));
    }

    private static List<String> stringsIn(byte[] xlsx) throws Exception {
        List<String> out = new ArrayList<>();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sheet = wb.getSheetAt(s);
                out.add(sheet.getSheetName());
                for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        Cell cell = row.getCell(c);
                        if (cell == null) continue;
                        String v = SheetCells.getCellString(row, c);
                        if (!v.isEmpty()) out.add(v);
                    }
                }
            }
        }
        return out;
    }
}
