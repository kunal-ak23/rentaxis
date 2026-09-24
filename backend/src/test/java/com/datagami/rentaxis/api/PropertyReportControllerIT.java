package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingRequest.Dimensions;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.CrossTenantHttp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.datagami.rentaxis.core.service.ledger.PostingRequest.cr;
import static com.datagami.rentaxis.core.service.ledger.PostingRequest.dr;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finance-ops spec §1, over HTTP: who may read the property P&L and statement,
 * and what a property manager is narrowed to. A manager sees only assigned
 * properties — never Unassigned, Total, the allocation or the check row, which
 * are tenant-wide figures — and a named foreign property is a 404.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PropertyReportControllerIT extends AbstractPostgresIT {

    private static final String RANGE = "from=2026-09-01&to=2026-09-30";

    @LocalServerPort int port;

    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired PropertyService propertyService;
    @Autowired PostingService posting;
    @Autowired UserPropertyAssignmentRepository assignments;

    private CrossTenantHttp http;
    private UUID tenant;
    private Property marina, palm;
    private User pm;
    private UUID bankCharges;

    @BeforeEach
    void setUp() {
        http = new CrossTenantHttp(port, orgRepo, userRepo, accountService, propertyAccountService, propertyService);
        tenant = http.tenant("PnlHttp-");
        marina = http.property("Marina");
        palm = http.property("Palm");
        rent(marina, "1000.00");
        rent(palm, "700.00");
        bankCharges = accountService.getAccountByCode("D-02-003").getId();
        posting.post(new PostingRequest(JournalDocType.JV, LocalDate.of(2026, 9, 30), "Bank charges", Dimensions.none(),
                JournalSourceType.MANUAL, null, null, List.of(
                dr(bankCharges, new BigDecimal("50.00")),
                cr(AccountRole.CASH, new BigDecimal("50.00")))));
        pm = http.user(tenant, UserRole.PROPERTY_MANAGER);
        UserPropertyAssignment a = new UserPropertyAssignment();
        a.setUserId(pm.getId());
        a.setPropertyId(marina.getId());
        assignments.save(a);
        TenantContextHolder.clear();
    }

    @AfterEach
    void tearDown() { TenantContextHolder.clear(); }

    private void rent(Property p, String amount) {
        BigDecimal a = new BigDecimal(amount);
        posting.post(new PostingRequest(JournalDocType.CIL, LocalDate.of(2026, 9, 30), "rent", Dimensions.ofProperty(p.getId()),
                JournalSourceType.MANUAL, null, null, List.of(dr(AccountRole.ADVANCE_RENT, a), cr(AccountRole.RENTAL_INCOME, a))));
    }

    /** POST /property-pl/lines for September, one column, a row key or (null) NOI. */
    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> lines(User caller, String column, String rowKey) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("from", "2026-09-01");
        body.put("to", "2026-09-30");
        body.put("column", column);
        if (rowKey != null) body.put("rowKey", rowKey);
        return http.call(caller, HttpMethod.POST, "/api/v1/finance/reports/property-pl/lines", body);
    }

    @SuppressWarnings("unchecked")
    private static List<String> columnKeys(ResponseEntity<Map> res) {
        return ((List<Map<String, Object>>) res.getBody().get("columns")).stream().map(c -> (String) c.get("key")).toList();
    }

    @Test
    void tenantWideRolesIncludingASuperAdminActingInTheOrgReadEverything() {
        for (UserRole role : List.of(UserRole.SUPER_ADMIN, UserRole.TENANT_ADMIN, UserRole.ACCOUNTANT)) {
            ResponseEntity<Map> res = http.call(http.user(tenant, role), HttpMethod.GET,
                    "/api/v1/finance/reports/property-pl?" + RANGE + "&allocate=EQUAL", null);
            assertThat(res.getStatusCode().value()).as(role.name()).isEqualTo(200);
            assertThat(columnKeys(res)).as(role.name())
                    .containsExactlyInAnyOrder(marina.getId().toString(), palm.getId().toString(), "UNASSIGNED", "TOTAL");
            assertThat(res.getBody().get("check")).isNotNull();
            assertThat(res.getBody().get("allocation")).isNotNull();
        }
    }

    @Test
    void aManagerSeesOnlyAssignedPropertiesAndNoTenantWideFigures() {
        ResponseEntity<Map> res = http.call(pm, HttpMethod.GET,
                "/api/v1/finance/reports/property-pl?" + RANGE + "&allocate=EQUAL&compare=PREVIOUS", null);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(columnKeys(res)).containsExactly(marina.getId().toString());
        assertThat(res.getBody().get("scoped")).isEqualTo(true);
        assertThat(res.getBody().get("check")).isNull();
        assertThat(res.getBody().get("allocation")).isNull();
        // Palm's rent and the shared bank charge appear nowhere in the body.
        String body = res.getBody().toString();
        assertThat(body).doesNotContain(palm.getId().toString()).doesNotContain("UNASSIGNED").doesNotContain("TOTAL")
                .doesNotContain("700.0").doesNotContain("50.0");
    }

    @Test
    void aManagerNamingAnUnassignedPropertyGets404Everywhere() {
        String palmId = palm.getId().toString();
        assertThat(http.status(pm, HttpMethod.GET, "/api/v1/finance/reports/property-pl?" + RANGE + "&propertyId=" + palmId)).isEqualTo(404);
        assertThat(http.status(pm, HttpMethod.GET, "/api/v1/finance/reports/property-pl?" + RANGE
                + "&propertyId=" + marina.getId() + "&propertyId=" + palmId)).isEqualTo(404);
        assertThat(http.status(pm, HttpMethod.GET, "/api/v1/finance/reports/property-pl.csv?" + RANGE + "&propertyId=" + palmId)).isEqualTo(404);
        assertThat(http.status(pm, HttpMethod.GET, "/api/v1/finance/reports/property-statement?" + RANGE + "&propertyId=" + palmId)).isEqualTo(404);
        assertThat(http.status(pm, HttpMethod.GET, "/api/v1/finance/reports/property-statement.pdf?" + RANGE + "&propertyId=" + palmId)).isEqualTo(404);
        assertThat(lines(pm, palmId, null).getStatusCode().value()).isEqualTo(404);
        assertThat(lines(pm, "UNASSIGNED", null).getStatusCode().value()).isEqualTo(404);
        assertThat(lines(pm, "TOTAL", null).getStatusCode().value()).isEqualTo(404);
        // Its own property still works, including the drill-down and the statement.
        assertThat(lines(pm, marina.getId().toString(), "RENTAL_INCOME").getStatusCode().value()).isEqualTo(200);
        assertThat(http.status(pm, HttpMethod.GET, "/api/v1/finance/reports/property-statement?" + RANGE + "&propertyId=" + marina.getId())).isEqualTo(200);
        // The CoA picker's list is not a manager's.
        assertThat(http.status(pm, HttpMethod.GET, "/api/v1/finance/reports/report-lines")).isEqualTo(403);
    }

    @Test
    void anotherTenantsPropertyIs404ForAnAdmin() {
        UUID other = http.tenant("PnlHttpOther-");
        Property foreign = http.property("Foreign");
        TenantContextHolder.clear();
        User admin = http.user(tenant, UserRole.TENANT_ADMIN);
        assertThat(http.status(admin, HttpMethod.GET, "/api/v1/finance/reports/property-pl?" + RANGE + "&propertyId=" + foreign.getId())).isEqualTo(404);
        assertThat(http.status(admin, HttpMethod.GET, "/api/v1/finance/reports/property-statement?" + RANGE + "&propertyId=" + foreign.getId())).isEqualTo(404);
        assertThat(other).isNotEqualTo(tenant);
    }

    @Test
    void otherRolesAreRefused() {
        for (UserRole role : List.of(UserRole.RENTER, UserRole.TENANT_USER, UserRole.SECURITY_GUARD)) {
            assertThat(http.status(http.user(tenant, role), HttpMethod.GET, "/api/v1/finance/reports/property-pl?" + RANGE))
                    .as(role.name()).isEqualTo(403);
        }
    }

    @Test
    void statementDownloadsAsPdfAndCsvInBothLanguages() {
        User accountant = http.user(tenant, UserRole.ACCOUNTANT);
        String q = "?" + RANGE + "&propertyId=" + marina.getId();
        for (String lang : List.of("en", "ar")) {
            ResponseEntity<byte[]> pdf = http.request(accountant, HttpMethod.GET,
                    "/api/v1/finance/reports/property-statement.pdf" + q + "&lang=" + lang).retrieve().toEntity(byte[].class);
            assertThat(pdf.getStatusCode().value()).isEqualTo(200);
            assertThat(pdf.getHeaders().getContentType().toString()).isEqualTo("application/pdf");
            assertThat(new String(pdf.getBody(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        }
        ResponseEntity<byte[]> csv = http.request(accountant, HttpMethod.GET,
                "/api/v1/finance/reports/property-statement.csv" + q).retrieve().toEntity(byte[].class);
        assertThat(csv.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(new String(csv.getBody(), StandardCharsets.UTF_8)).contains("Collected").contains("Deposits held");

        ResponseEntity<byte[]> plCsv = http.request(accountant, HttpMethod.GET,
                "/api/v1/finance/reports/property-pl.csv?" + RANGE + "&compare=PREVIOUS").retrieve().toEntity(byte[].class);
        String text = new String(plCsv.getBody(), StandardCharsets.UTF_8);
        assertThat(text).contains("Rental income").contains("Unassigned").contains("1650.00");
    }

    /**
     * A NOI drill over a chart with hundreds of P&L leaves: the figure is named by
     * key in a POST body, so the request stays small whatever the chart holds.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aNoiDrillOverHundredsOfLeavesIsOneSmallRequest() {
        TenantContextHolder.setTenantId(tenant);
        com.datagami.rentaxis.domain.entity.Account group = accountService.getAccountByCode("D-02");
        java.util.List<UUID> leaves = new java.util.ArrayList<>();
        for (int i = 0; i < 210; i++) leaves.add(accountService.createLeaf("Overhead " + i, group, null).getId());
        posting.post(new PostingRequest(JournalDocType.JV, LocalDate.of(2026, 9, 30), "overheads", Dimensions.none(),
                JournalSourceType.MANUAL, null, null, List.of(
                dr(leaves.get(0), new BigDecimal("10.00")), dr(leaves.get(209), new BigDecimal("15.00")),
                cr(AccountRole.CASH, new BigDecimal("25.00")))));
        TenantContextHolder.clear();

        User admin = http.user(tenant, UserRole.TENANT_ADMIN);
        ResponseEntity<Map> res = lines(admin, "TOTAL", null);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        // Marina's and Palm's rent, the 50.00 bank charge, and the two overhead lines.
        List<Map<String, Object>> rows = (List<Map<String, Object>>) res.getBody().get("lines");
        assertThat(rows).hasSize(5);
        ResponseEntity<Map> unassigned = lines(admin, "UNASSIGNED", null);
        assertThat((List<Object>) unassigned.getBody().get("lines")).hasSize(3);
    }
}
