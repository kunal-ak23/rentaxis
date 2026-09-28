package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The import writes the tree by parent_id now, and parent_id is a real foreign
 * key: a child row saved before its parent fails the insert outright. A chart
 * of accounts exported from anywhere is free to list a child above its parent,
 * so the import resolves codes in a second pass and saves by depth.
 */
class AccountImportServiceTest {

    private AccountRepository repository;
    private AccountImportService service;

    @BeforeEach
    void setUp() {
        repository = mock(AccountRepository.class);
        when(repository.findByCode(anyString())).thenReturn(Optional.empty());
        when(repository.saveAll(anyIterable()))
                .thenAnswer(inv -> new ArrayList<Account>(inv.getArgument(0)));
        service = new AccountImportService(repository);
    }

    /** code,name,type,parentCode,nameAr,description,isGroup */
    private MockMultipartFile csv(String... rows) {
        StringBuilder sb = new StringBuilder("code,name,type,parentCode,nameAr,description,isGroup\n");
        for (String row : rows) {
            sb.append(row).append('\n');
        }
        return new MockMultipartFile("file", "coa.csv", "text/csv", sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void childListedBeforeItsParentIsLinkedAndSavedAfterIt() throws Exception {
        List<Account> saved = service.importFromCsv(csv(
                "A-01-01,Rent Receivable,ASSET,A-01,,,false",
                "A-01,Current Assets,ASSET,A,,,true",
                "A,Assets,ASSET,,,,true")).accounts();

        assertThat(saved).extracting(Account::getCode)
                .containsExactly("A", "A-01", "A-01-01");
        Account leaf = saved.get(2);
        assertThat(leaf.getParent()).isSameAs(saved.get(1));
        assertThat(saved.get(1).getParent()).isSameAs(saved.get(0));
        assertThat(saved.get(0).getParent()).isNull();
    }

    @Test
    void parentAlreadyInTheTenantIsResolvedFromTheRepository() throws Exception {
        Account existing = new Account();
        existing.setId(UUID.randomUUID());
        existing.setCode("A-02");
        when(repository.findByCode("A-02")).thenReturn(Optional.of(existing));

        List<Account> saved = service.importFromCsv(csv("A-02-09,Rent Receivable,ASSET,A-02,,,false")).accounts();

        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getParent()).isSameAs(existing);
    }

    @Test
    void unknownParentCodeNamesTheOffendingRow() {
        assertThatThrownBy(() -> service.importFromCsv(csv(
                "A,Assets,ASSET,,,,true",
                "A-01-01,Rent Receivable,ASSET,NOPE,,,false")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown parent code: NOPE on row 3");
    }

    @Test
    void importedAccountsAreNeverSystemAccounts() throws Exception {
        List<Account> saved = service.importFromCsv(csv("A,Assets,ASSET,,,,true")).accounts();

        assertThat(saved.get(0).isSystem()).isFalse();
        assertThat(saved.get(0).isGroup()).isTrue();
    }

    // ------------------------------------------------------------------
    // break-it R3 data3 F7: short rows, malformed rows, counts
    // ------------------------------------------------------------------

    /** A hand-made CSV that stops after the type: the parent and the rest are optional. */
    @Test
    void aRowThatOmitsTheOptionalTrailingColumnsIsImported() throws Exception {
        AccountImportService.Result r = service.importFromCsv(csv("9B3D02,BRK3-DATA short,ASSET", "9B3D03,Short two,expense"));

        assertThat(r.created()).isEqualTo(2);
        assertThat(r.accounts()).extracting(Account::getCode).containsExactly("9B3D02", "9B3D03");
        assertThat(r.accounts().get(0).getParent()).isNull();
        assertThat(r.accounts().get(0).getNameAr()).isEmpty();
        assertThat(r.accounts().get(1).getAccountType().name()).isEqualTo("EXPENSE");
    }

    @Test
    void blankLinesAreCountedAsSkipped() throws Exception {
        AccountImportService.Result r = service.importFromCsv(csv("A,Assets,ASSET", "", " , , ", "B,Bank,ASSET,A"));

        assertThat(r.created()).isEqualTo(2);
        assertThat(r.skipped()).isEqualTo(2);
    }

    @Test
    void malformedRowsAreRowErrorsAndNothingIsSaved() {
        assertThatThrownBy(() -> service.importFromCsv(csv(
                "A,Assets,ASSET",
                "B,Only a name",
                ",No code,ASSET",
                "C,Bad type,ASSETS",
                "A,Duplicate of row 2,ASSET")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("4 rows have problems; nothing was imported")
                .hasMessageContaining("Row 3: needs at least code, name and type")
                .hasMessageContaining("Row 4: code is required")
                .hasMessageContaining("Row 5: invalid account type 'ASSETS'")
                .hasMessageContaining("Row 6: code A is already on row 2");
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).saveAll(anyIterable());
    }

    @Test
    void aCodeTheOrganisationAlreadyHasIsARowErrorNotAConstraintName() {
        Account existing = new Account();
        existing.setCode("A");
        when(repository.findByCode("A")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.importFromCsv(csv("A,Assets,ASSET")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Row 2: account code A already exists");
    }

    @Test
    void aFileWithNoAccountRowsIsRefusedRatherThanReportedAsDone() {
        assertThatThrownBy(() -> service.importFromCsv(csv("", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The file has no account rows; nothing was imported");
    }

    @Test
    void quotedCellsKeepTheirCommasAndArabicSurvives() throws Exception {
        AccountImportService.Result r = service.importFromCsv(csv(
                "\"A-1\",\"Rent, Dubai\",ASSET,,\"إيجار، دبي\""));

        assertThat(r.accounts().get(0).getName()).isEqualTo("Rent, Dubai");
        assertThat(r.accounts().get(0).getNameAr()).isEqualTo("إيجار، دبي");
    }

    @Test
    void anExcelRowWithNoTypeIsARowErrorNotSilentlyDropped() {
        assertThatThrownBy(() -> service.importFromExcel(xlsx(
                new String[]{"A", "Assets", "", "ASSET", "", ""},
                new String[]{"B", "No type", "", "", "", ""})))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Row 3: type is required");
    }

    // ------------------------------------------------------------------
    // the spreadsheet door
    // ------------------------------------------------------------------
    //
    // This endpoint parses an untrusted upload in-process with a library that
    // builds the whole workbook in memory. It went straight to `new XSSFWorkbook`
    // while the two portfolio importers went through WorkbookGuard, so the same
    // file was judged by two different sets of limits depending on which URL it
    // was posted to. These three pin that it now comes through the one door.

    @Test
    void anOrdinaryChartOfAccountsSpreadsheetStillImports() throws Exception {
        List<Account> saved = service.importFromExcel(xlsx(
                new String[]{"A", "Assets", "", "ASSET", "", ""},
                new String[]{"A-01", "Current Assets", "", "ASSET", "A", ""})).accounts();

        assertThat(saved).extracting(Account::getCode).containsExactly("A", "A-01");
        assertThat(saved.get(1).getParent()).isSameAs(saved.get(0));
    }

    @Test
    void aMacroEnabledChartIsRefusedWithASentenceRatherThanAStackTrace() {
        assertThatThrownBy(() -> service.importFromExcel(upload(zipOf("xl/vbaProject.bin", "\0\0macro"))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining(".xlsm");
    }

    @Test
    void aChartWithMoreRowsThanTheImportAcceptsIsRefused() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("Accounts");
            for (int r = 0; r <= WorkbookGuard.MAX_ROWS_PER_SHEET; r++) {
                sheet.createRow(r).createCell(0).setCellValue("A-" + r);
            }
            wb.write(out);
        }
        assertThatThrownBy(() -> service.importFromExcel(upload(out.toByteArray())))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("split it into smaller workbooks");
    }

    /** A file whose name says .xlsx and whose bytes say otherwise. */
    @Test
    void aFileThatIsNotReallyAWorkbookIsRefused() {
        assertThatThrownBy(() -> service.importFromExcel(
                upload("code,name\nA,Assets\n".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining(".xlsx");
    }

    /** code | name | nameAr | type | parentCode | description */
    private MockMultipartFile xlsx(String[]... rows) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("Accounts");
            var header = sheet.createRow(0);
            String[] headers = {"code", "name", "nameAr", "type", "parentCode", "description"};
            for (int c = 0; c < headers.length; c++) header.createCell(c).setCellValue(headers[c]);
            for (int r = 0; r < rows.length; r++) {
                var row = sheet.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) row.createCell(c).setCellValue(rows[r][c]);
            }
            wb.write(out);
        }
        return upload(out.toByteArray());
    }

    private MockMultipartFile upload(byte[] bytes) {
        return new MockMultipartFile("file", "coa.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
    }

    private static byte[] zipOf(String entryName, String content) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
