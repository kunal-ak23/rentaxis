package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.report.ReceivableAccountDTO;
import com.datagami.rentaxis.api.dto.report.ReportGridDTO;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.CrossTenantHttp;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.datagami.rentaxis.domain.entity.enums.UserRole.ACCOUNTANT;
import static com.datagami.rentaxis.domain.entity.enums.UserRole.PROPERTY_MANAGER;
import static com.datagami.rentaxis.domain.entity.enums.UserRole.RENTER;
import static com.datagami.rentaxis.domain.entity.enums.UserRole.SECURITY_GUARD;
import static com.datagami.rentaxis.domain.entity.enums.UserRole.SUPER_ADMIN;
import static com.datagami.rentaxis.domain.entity.enums.UserRole.TENANT_ADMIN;
import static com.datagami.rentaxis.domain.entity.enums.UserRole.TENANT_USER;
import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.CONTENT_DISPOSITION;
import static org.springframework.http.HttpMethod.GET;

/**
 * The Tenant Ledger (R1) over HTTP (reports spec §1.4, §2): the finance roles reach every handler and nobody
 * else does, an organisation is required, the files carry their type and an RFC 5987 name, a PDF over its row
 * cap is a 422 with a translatable code, the screen and the files print the same REPORT TOTAL, the workbook is
 * in PACT's layout, an empty selection still exports, the account picker lists receivable leaves only, and
 * another organisation's property is a 404.
 *
 * <p>The PDF cap is lowered to 5 rows so the one contract here (2 TCO rows + 5 PDR rows on the receivable)
 * is over it, and a one-row party ledger is not. Synthetic data only.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "rentaxis.reports.max-rows.pdf=5")
class FinanceRegisterReportControllerIT extends AbstractPostgresIT {

    private static final String BASE = "/api/v1/finance/reports";
    private static final String Q = "from=2026-09-01&to=2027-09-30";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @LocalServerPort int port;

    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LeaseService leaseService;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired LeasePostingService leasePosting;
    @Autowired AccountResolver resolver;

    private CrossTenantHttp http;
    private LeaseTestFixtures fixtures;
    private UUID tenant;
    private User accountant;
    private Property property;
    private UUID emptyProperty;
    private Account rr;
    private Account emptyRr;
    private UUID adminFeeLeaf;
    private UUID otherOrgProperty;

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo, propertyService, accountService,
                propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, chequeGeneration, leasePosting);
        tenant = fixtures.tenantId();
        property = fixtures.property();
        fixtures.postedLease(LocalDate.of(2026, 9, 16), LocalDate.of(2026, 10, 2), LocalDate.of(2027, 10, 1),
                List.of(line("RENT", "51000"), line("ADMIN_FEE", "2000")), 4, null);
        emptyProperty = fixtures.createProperty("EMP").getId();
        rr = resolver.resolve(AccountRole.RENT_RECEIVABLE, property.getId());
        emptyRr = resolver.resolve(AccountRole.RENT_RECEIVABLE, emptyProperty);
        adminFeeLeaf = resolver.resolve(AccountRole.ADMIN_FEE, property.getId()).getId();

        http = new CrossTenantHttp(port, orgRepo, userRepo, accountService, propertyAccountService, propertyService);
        accountant = http.user(tenant, ACCOUNTANT);

        // A second organisation with a property of its own (this makes it the current tenant).
        http.tenant("TL-Other-");
        otherOrgProperty = http.property("Other Tower").getId();

        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    // ------------------------------------------------------------------

    @Test
    void financeRolesOnlyEveryHandler() {
        for (UserRole ok : List.of(SUPER_ADMIN, TENANT_ADMIN, ACCOUNTANT)) {
            User caller = http.user(tenant, ok);
            for (String p : List.of("/tenant-ledger?" + Q, "/tenant-ledger.xlsx?" + Q, "/tenant-ledger/accounts")) {
                assertThat(http.status(caller, GET, BASE + p)).as(ok + p).isEqualTo(200);
            }
        }
        for (UserRole no : List.of(PROPERTY_MANAGER, TENANT_USER, RENTER, SECURITY_GUARD)) {
            User caller = http.user(tenant, no);
            for (String p : List.of("/tenant-ledger?" + Q, "/tenant-ledger.csv?" + Q, "/tenant-ledger/accounts")) {
                assertThat(http.status(caller, GET, BASE + p)).as(no + p).isEqualTo(403);
            }
        }
    }

    @Test
    void aSuperAdminWithNoOrganisationIsToldToPickOne() {
        User superAdmin = http.user(null, SUPER_ADMIN);
        for (String p : List.of("/tenant-ledger?" + Q, "/tenant-ledger.xlsx?" + Q, "/tenant-ledger/accounts")) {
            ResponseEntity<String> res = RestClient.builder().baseUrl("http://localhost:" + port).build()
                    .get().uri(BASE + p)
                    .header("X-User-Id", superAdmin.getId().toString())
                    .header("X-User-Role", superAdmin.getRole().name())
                    .retrieve().onStatus(s -> true, (req, r) -> { }).toEntity(String.class);
            assertThat(res.getStatusCode().value()).as(p).isEqualTo(400);
            assertThat(res.getBody()).as(p).contains("Select an organisation first");
        }
    }

    @Test
    void filesCarryTheirTypeAndAnRfc5987Name() {
        ResponseEntity<byte[]> x = file("/tenant-ledger.xlsx?" + Q);
        assertThat(x.getStatusCode().value()).isEqualTo(200);
        assertThat(x.getHeaders().getContentType().toString()).isEqualTo(XLSX);
        assertThat(x.getHeaders().getFirst(CONTENT_DISPOSITION))
                .startsWith("attachment")
                .contains("filename*=UTF-8''tenant-ledger-2026-09-01-2027-09-30.xlsx");

        ResponseEntity<byte[]> csv = file("/tenant-ledger.csv?" + Q);
        assertThat(csv.getStatusCode().value()).isEqualTo(200);
        assertThat(csv.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(csv.getHeaders().getFirst(CONTENT_DISPOSITION)).contains("tenant-ledger-2026-09-01-2027-09-30.csv");

        // A one-row party ledger fits the lowered PDF cap.
        ResponseEntity<byte[]> pdf = file("/tenant-ledger.pdf?" + Q + "&accountIds=" + adminFeeLeaf + "&lang=ar");
        assertThat(pdf.getStatusCode().value()).isEqualTo(200);
        assertThat(pdf.getHeaders().getContentType().toString()).isEqualTo("application/pdf");
        assertThat(new String(pdf.getBody(), 0, 5, ISO_8859_1)).isEqualTo("%PDF-");
        assertThat(pdf.getHeaders().getFirst(CONTENT_DISPOSITION)).contains("tenant-ledger-2026-09-01-2027-09-30-ar.pdf");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void aPdfOverTheCapIsA422WithItsCode() {
        ResponseEntity<Map> r = http.call(accountant, GET, BASE + "/tenant-ledger.pdf?" + Q, null);
        assertThat(r.getStatusCode().value()).isEqualTo(422);
        assertThat(r.getBody()).containsEntry("code", "report.tooManyRowsPdf").containsEntry("status", 422);
        assertThat((Map<String, Object>) r.getBody().get("args")).containsEntry("rows", 7).containsEntry("cap", 5);
        assertThat((String) r.getBody().get("message")).contains("export Excel or narrow the filter");
    }

    @Test
    void jsonXlsxAndCsvShowTheSameReportTotal() {
        // The receivable view: the PDRs credit what the TCO rows debit, so its balance is zero.
        List<BigDecimal> receivable = sameReportTotalInEveryFormat(Q);
        assertThat(receivable.get(0)).isEqualByComparingTo("53000");

        // A party ledger whose balance is not zero (2,000 Cr), so a sign or abs() slip in a file shows.
        List<BigDecimal> adminFee = sameReportTotalInEveryFormat(Q + "&accountIds=" + adminFeeLeaf);
        assertThat(adminFee.get(2).signum()).isNotZero();
        assertThat(adminFee.get(2)).isEqualByComparingTo("-2000");
    }

    @Test
    void theWorkbookIsInPactLayout() {
        XSSFSheet s = sheet("/tenant-ledger.xlsx?" + Q);
        assertThat(headers(s)).containsExactly("Doc Date", "Particular", "Debit", "Credit", "Balance", "Chq No",
                "Payee Bank", "BDDays", "ConvertedDate", "Doc No");
        assertThat(s.getRow(1).getCell(0).getCellStyle().getFillForegroundXSSFColor().getARGBHex()).isEqualTo("FFFFFFFF");
        assertThat(firstDataRow(s).getCell(0).getCellStyle().getDataFormatString()).isEqualTo("dd-mm-yyyy");
        assertThat(firstPdrRow(s).getCell(8).getCellStyle().getDataFormatString()).isEqualTo("dd/mm/yyyy");
        assertThat(s.isRightToLeft()).isFalse();

        XSSFSheet ar = sheet("/tenant-ledger.xlsx?" + Q + "&lang=ar");
        assertThat(ar.isRightToLeft()).isTrue();
        assertThat(ar.getRow(1).getCell(0).getStringCellValue()).isEqualTo("تاريخ المستند");
    }

    @Test
    void anEmptySelectionExportsZeroTotals() {
        String q = Q + "&propertyId=" + emptyProperty;
        XSSFRow total = reportTotalRow(sheet("/tenant-ledger.xlsx?" + q));
        assertThat(xlsxTotals(total)).containsExactly(new BigDecimal("0.00"), new BigDecimal("0.00"),
                new BigDecimal("0.00"));
        List<ReportGridDTO.Item> items = json("/tenant-ledger?" + q).items();
        assertThat(items).extracting(ReportGridDTO.Item::type).containsExactly("total");
        for (String k : List.of("debit", "credit", "balance")) {
            assertThat(new BigDecimal(items.get(0).cells().get(k))).as(k).isZero();
        }

        assertThat(csvTotalFields("/tenant-ledger.csv?" + q)).startsWith("REPORT TOTAL", "", "0.00", "0.00", "0.00");
        ResponseEntity<byte[]> pdf = file("/tenant-ledger.pdf?" + q);
        assertThat(pdf.getStatusCode().value()).isEqualTo(200);
        assertThat(new String(pdf.getBody(), 0, 5, ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    void thePickerListsReceivableLeavesOnly() {
        List<ReceivableAccountDTO> all = accounts("/tenant-ledger/accounts");
        assertThat(all).extracting(ReceivableAccountDTO::id).containsExactlyInAnyOrder(rr.getId(), emptyRr.getId());
        assertThat(all).extracting(ReceivableAccountDTO::code).isSorted();
        assertThat(all).filteredOn(a -> a.id().equals(rr.getId())).singleElement().satisfies(a -> {
            assertThat(a.code()).isEqualTo(rr.getCode());
            assertThat(a.name()).isEqualTo(rr.getName());
            assertThat(a.propertyId()).isEqualTo(property.getId());
            assertThat(a.propertyName()).isEqualTo(property.getNameEn());
        });

        assertThat(accounts("/tenant-ledger/accounts?propertyId=" + emptyProperty))
                .extracting(ReceivableAccountDTO::id).containsExactly(emptyRr.getId());
    }

    @Test
    void anotherOrganisationsPropertyIs404() {
        assertThat(http.status(accountant, GET, BASE + "/tenant-ledger?" + Q + "&propertyId=" + otherOrgProperty))
                .isEqualTo(404);
        assertThat(http.status(accountant, GET, BASE + "/tenant-ledger.xlsx?" + Q + "&propertyId=" + otherOrgProperty))
                .isEqualTo(404);
        assertThat(http.status(accountant, GET, BASE + "/tenant-ledger/accounts?propertyId=" + otherOrgProperty))
                .isEqualTo(404);
        // Org A's own property still answers, so the 404 is the tenant binding and not a broken route.
        assertThat(http.status(accountant, GET, BASE + "/tenant-ledger?" + Q + "&propertyId=" + property.getId()))
                .isEqualTo(200);
    }

    // ------------------------------------------------------------------

    /**
     * The REPORT TOTAL debit, credit and balance of one selection, checked equal across the JSON page, the
     * workbook (numeric cells C to E) and the CSV.
     */
    private List<BigDecimal> sameReportTotalInEveryFormat(String query) {
        ReportGridDTO g = json("/tenant-ledger?" + query);
        ReportGridDTO.Item last = g.items().get(g.items().size() - 1);
        assertThat(last.type()).as(query).isEqualTo("total");
        List<BigDecimal> fromJson = List.of(new BigDecimal(last.cells().get("debit")),
                new BigDecimal(last.cells().get("credit")), new BigDecimal(last.cells().get("balance")));

        assertThat(xlsxTotals(reportTotalRow(sheet("/tenant-ledger.xlsx?" + query)))).as(query).isEqualTo(fromJson);
        assertThat(csvTotalFields("/tenant-ledger.csv?" + query)).as(query).startsWith("REPORT TOTAL", "",
                fromJson.get(0).toPlainString(), fromJson.get(1).toPlainString(), fromJson.get(2).toPlainString(),
                fromJson.get(2).signum() >= 0 ? "Dr" : "Cr");
        return fromJson;
    }

    /** Debit, credit and balance (columns C to E) of a workbook total row; each must be a real number cell. */
    private static List<BigDecimal> xlsxTotals(XSSFRow total) {
        return IntStream.of(2, 3, 4).mapToObj(i -> {
            Cell c = total.getCell(i);
            assertThat(c.getCellType()).as("column %d", i).isEqualTo(CellType.NUMERIC);
            return BigDecimal.valueOf(c.getNumericCellValue()).setScale(2, RoundingMode.HALF_UP);
        }).toList();
    }

    /** The fields of the CSV's REPORT TOTAL line. */
    private List<String> csvTotalFields(String path) {
        ResponseEntity<byte[]> r = file(path);
        assertThat(r.getStatusCode().value()).as(path).isEqualTo(200);
        String csv = new String(r.getBody(), StandardCharsets.UTF_8);
        return Arrays.stream(csv.split("\r\n"))
                .filter(l -> l.startsWith("REPORT TOTAL"))
                .findFirst().map(l -> Arrays.asList(l.split(",", -1))).orElseThrow();
    }

    private ResponseEntity<byte[]> file(String path) {
        return http.request(accountant, GET, BASE + path).retrieve().onStatus(s -> true, (req, res) -> { })
                .toEntity(byte[].class);
    }

    private ReportGridDTO json(String path) {
        ResponseEntity<ReportGridDTO> r = http.request(accountant, GET, BASE + path).retrieve()
                .toEntity(ReportGridDTO.class);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        return r.getBody();
    }

    private List<ReceivableAccountDTO> accounts(String path) {
        ResponseEntity<List<ReceivableAccountDTO>> r = http.request(accountant, GET, BASE + path).retrieve()
                .toEntity(new ParameterizedTypeReference<List<ReceivableAccountDTO>>() { });
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        return r.getBody();
    }

    private XSSFSheet sheet(String path) {
        ResponseEntity<byte[]> r = file(path);
        assertThat(r.getStatusCode().value()).as(path).isEqualTo(200);
        try {
            // Read fully into memory; left open so the sheet stays readable.
            return new XSSFWorkbook(new ByteArrayInputStream(r.getBody())).getSheetAt(0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> headers(XSSFSheet s) {
        XSSFRow h = s.getRow(1);
        return IntStream.range(0, h.getLastCellNum()).mapToObj(i -> h.getCell(i).getStringCellValue()).toList();
    }

    /** The first ledger row: a real date cell in column A. */
    private static XSSFRow firstDataRow(XSSFSheet s) {
        for (int i = 2; i <= s.getLastRowNum(); i++) {
            XSSFRow r = s.getRow(i);
            if (r != null && r.getCell(0) != null && r.getCell(0).getCellType() == CellType.NUMERIC) {
                return r;
            }
        }
        throw new AssertionError("no data row");
    }

    /** The first cheque registration row: Doc No (column J) is a PDR. */
    private static XSSFRow firstPdrRow(XSSFSheet s) {
        for (int i = 2; i <= s.getLastRowNum(); i++) {
            XSSFRow r = s.getRow(i);
            if (r != null && r.getCell(9) != null && r.getCell(9).getCellType() == CellType.STRING
                    && r.getCell(9).getStringCellValue().startsWith("PDR")) {
                return r;
            }
        }
        throw new AssertionError("no PDR row");
    }

    private static XSSFRow reportTotalRow(XSSFSheet s) {
        for (int i = 2; i <= s.getLastRowNum(); i++) {
            XSSFRow r = s.getRow(i);
            if (r != null && r.getCell(0) != null && r.getCell(0).getCellType() == CellType.STRING
                    && "REPORT TOTAL".equals(r.getCell(0).getStringCellValue())) {
                return r;
            }
        }
        throw new AssertionError("no REPORT TOTAL row");
    }
}
