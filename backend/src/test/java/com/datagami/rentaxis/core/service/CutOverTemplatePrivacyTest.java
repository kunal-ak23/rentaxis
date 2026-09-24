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
     * Names, properties, identifiers and institutions taken from the client's real
     * exports. Lower-cased; matched as substrings against every string in the
     * workbook.
     *
     * <p>{@link RepositoryPrivacyTest} matches {@link #NAMES} — this list without
     * the two banks — against the repository's own sources, so a name deleted here
     * stops being guarded in both places at once.</p>
     */
    static final List<String> NAMES = List.of(
            // real renters
            "mamanov", "ishtiaq", "prabhjot",
            // real properties / towers
            "tulip", "olivier", "boulevard", "freej", "warsan", "belle vue", "galah",
            "ocean residencia", "constance", "valencia", "grand residence", "l'horizon",
            // the rest of the same portfolio, distinctive enough to grep for
            // ("mir 1", "nas 1", "pine" and "victoria" are not, and are only scrubbed)
            "ost-10", "tara 2", "liwan", "rivington", "impz", "js towers", "jvc mir",
            // the real landlord — "ashram" also catches "alashram"
            "ashram", "tarek mohammed",
            // real PACT identifiers: contract references and a building code
            "tlp7/681", "tco-25/251", "tco-26/1629", "gla_b1");

    /**
     * What the template is held to: the real names, plus a bare first name and the
     * client's banks. Those three are too short or too common to grep the whole
     * repository for, and a bank is not private anyway — but a bank name in a
     * workbook every landlord downloads is still the client's banking
     * relationship, so the template names none. Ordinary fixtures may.
     */
    private static final List<String> DENIED =
            concat(NAMES, List.of("anum", "emirates islamic", "enbd"));

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return List.copyOf(out);
    }

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

    /**
     * Proof the check can fail: the denylist really is matched against cell text.
     *
     * <p>The reintroduced cell is built out of the denylist rather than typed, so
     * this file spells out no more of a real name than the list already has to.</p>
     */
    @Test
    void theCheckWouldCatchARealNameIfOneCameBack() throws Exception {
        List<String> cells = stringsIn(new PortfolioTemplateService().generateCutOverTemplate());
        List<String> withAReintroducedName = new ArrayList<>(cells);
        withAReintroducedName.add("Renter " + DENIED.get(0));

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
