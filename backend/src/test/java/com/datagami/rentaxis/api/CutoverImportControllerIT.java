package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.PortfolioTemplateService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.ImportJobRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cut-over import over HTTP: the template, the upload and the poll.
 *
 * <p>{@code @PreAuthorize} does nothing in a service-level test — the roles only
 * bite once a request has been through {@code ApiSecurityFilter} — so this is a
 * full-context HTTP test with the legacy {@code X-User-*} headers the Next.js proxy
 * sends, the same shape as {@code ImportBatchControllerIT}.</p>
 *
 * <p><b>The accountant is the point.</b> Every cut-over control admits SUPER_ADMIN,
 * TENANT_ADMIN and ACCOUNTANT, the template included: the person assembling a
 * cut-over workbook out of a PACT export is the accountant, and a template they
 * have to ask an admin to download for them is a template they will rebuild by
 * hand. The v1 portfolio import's own endpoints keep the roles they had.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CutoverImportControllerIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @LocalServerPort int port;

    @Autowired ObjectMapper json;
    @Autowired PortfolioTemplateService templates;
    @Autowired AccountService accounts;
    @Autowired PropertyAccountService propertyAccounts;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired ImportJobRepository importJobs;
    @Autowired LeaseRepository leaseRepo;
    @Autowired TransactionTemplate tx;

    static final String BASE = "/api/v1/import/portfolio/cutover";

    UUID tenantId;
    User accountant, tenantAdmin, superAdmin, propertyManager, renter;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("CutCtl-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
        accounts.seedDefaultAccounts();
        propertyAccounts.seedDefaultTemplateAndDefaults();
        accounts.createLeaf("Rental Income Tulip 7", accounts.getAccountByCode("C-01-01"), null);
        accounts.createLeaf("Rent Receivable - Tulip 7", accounts.getAccountByCode("A-02-01"), null);
        accounts.createLeaf("Advance Rent - Tulip 7", accounts.getAccountByCode("B-01-01"), null);
        accounts.createLeaf("Emirates Islamic - Tulip 7", accounts.getAccountByCode("A-02-02"), null);
        accounts.createLeaf("PDC Receivable Tulip 7", accounts.getAccountByCode("A-02-03"), null);
        accounts.createLeaf("Security Deposit Tulip 7", accounts.getAccountByCode("B-01-02"), null);

        accountant = user(UserRole.ACCOUNTANT);
        tenantAdmin = user(UserRole.TENANT_ADMIN);
        superAdmin = user(UserRole.SUPER_ADMIN);
        propertyManager = user(UserRole.PROPERTY_MANAGER);
        renter = user(UserRole.RENTER);
        TenantContextHolder.clear();
    }

    @AfterEach void clear() { TenantContextHolder.clear(); }

    // ------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------

    private User user(UserRole role) {
        User u = new User();
        u.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@t.io");
        u.setName(role.name());
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(tenantId);
        return userRepo.save(u);
    }

    private RestClient.RequestBodySpec request(HttpMethod method, String path, User caller, boolean withTenant) {
        RestClient.RequestBodySpec spec = RestClient.builder().build()
                .method(method)
                .uri(URI.create("http://localhost:" + port + path))
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name());
        if (withTenant) {
            spec = spec.header("X-Tenant-Id", tenantId.toString())
                    .header("X-User-Tenant-Id", tenantId.toString());
        }
        return spec;
    }

    private ResponseEntity<byte[]> get(String path, User caller) {
        return request(HttpMethod.GET, path, caller, true)
                .retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(byte[].class);
    }

    /** What the proxy sends for a SUPER_ADMIN who has not picked an organisation. */
    private ResponseEntity<String> getWithoutTenant(String path, User caller) {
        return request(HttpMethod.GET, path, caller, false)
                .retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(String.class);
    }

    private ResponseEntity<String> upload(User caller, byte[] bytes, String filename, boolean withTenant) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename; }
        });
        return request(HttpMethod.POST, BASE, caller, withTenant)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(String.class);
    }

    private JsonNode json(String body) {
        try {
            return json.readTree(body);
        } catch (Exception e) {
            throw new AssertionError("Response was not JSON: " + body, e);
        }
    }

    private JsonNode awaitTerminal(UUID jobId, User caller) {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<byte[]> res = get(BASE + "/" + jobId + "/status", caller);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode dto = json(new String(res.getBody(), StandardCharsets.UTF_8));
            String status = dto.get("status").asText();
            if ("COMPLETED".equals(status) || "VALIDATION_FAILED".equals(status) || "FAILED".equals(status)) {
                return dto;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("Job " + jobId + " never reached a terminal status");
    }

    // ------------------------------------------------------------------
    // the template
    // ------------------------------------------------------------------

    @Test
    void theCutOverTemplateIsAWorkbookWithTheContractsAndChequesSheets() throws Exception {
        ResponseEntity<byte[]> res = get(BASE + "/template", accountant);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getHeaders().getFirst("Content-Disposition")).contains("contract-import-template.xlsx");
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(res.getBody()))) {
            assertThat(wb.getSheet("Contracts")).isNotNull();
            assertThat(wb.getSheet("Cheques")).isNotNull();
            assertThat(wb.getSheet("Leases")).isNull();   // the v1 sheet is not in this one
        }
    }

    /** The ordinary portfolio template is untouched — still SUPER_ADMIN / TENANT_ADMIN. */
    @Test
    void theV1TemplateStillRefusesAnAccountant() {
        assertThat(get("/api/v1/import/portfolio/template", accountant).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/import/portfolio/template", tenantAdmin).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ------------------------------------------------------------------
    // the role gate
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"ACCOUNTANT", "TENANT_ADMIN", "SUPER_ADMIN"})
    void everyCutOverControlAdmitsTheThreeFinanceRoles(String role) {
        User caller = switch (role) {
            case "ACCOUNTANT" -> accountant;
            case "TENANT_ADMIN" -> tenantAdmin;
            default -> superAdmin;
        };
        assertThat(get(BASE + "/template", caller).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aPropertyManagerAndARenterAreRefusedEverywhere() throws Exception {
        byte[] bytes = templates.generateCutOverTemplate();
        for (User caller : new User[]{propertyManager, renter}) {
            assertThat(get(BASE + "/template", caller).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(upload(caller, bytes, "cutover.xlsx", true).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(get(BASE + "/" + UUID.randomUUID() + "/status", caller).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    /** A platform admin with no organisation picked gets one clean sentence, not a 500. */
    @Test
    void aTenantLessSuperAdminIsRefusedWithTheSameSentenceEverywhere() throws Exception {
        for (String path : new String[]{BASE + "/template", BASE + "/" + UUID.randomUUID() + "/status"}) {
            ResponseEntity<String> res = getWithoutTenant(path, superAdmin);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(res.getBody()).contains("Select an organisation first");
        }
        ResponseEntity<String> res = upload(superAdmin, templates.generateCutOverTemplate(), "c.xlsx", false);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("Select an organisation first");
    }

    // ------------------------------------------------------------------
    // the upload
    // ------------------------------------------------------------------

    @Test
    void anAccountantUploadsTheTemplateAndPollsItThroughToABatch() throws Exception {
        ResponseEntity<String> started = upload(accountant, templates.generateCutOverTemplate(),
                "al-ashram-cutover.xlsx", true);
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID jobId = UUID.fromString(json(started.getBody()).get("jobId").asText());

        JsonNode done = awaitTerminal(jobId, accountant);
        assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(done.get("contractsCreated").asInt()).isEqualTo(1);
        assertThat(done.get("chequesCreated").asInt()).isEqualTo(2);
        assertThat(done.get("mappingsCreated").asInt()).isEqualTo(6);
        assertThat(done.get("propertiesCreated").asInt()).isEqualTo(1);
        assertThat(done.get("unitsCreated").asInt()).isEqualTo(1);
        assertThat(done.get("rentersCreated").asInt()).isEqualTo(1);
        assertThat(done.get("errors")).isEmpty();
        assertThat(done.get("importBatchId").isNull()).isFalse();

        // The batch the job names is the one the batches screen already serves.
        UUID batchId = UUID.fromString(done.get("importBatchId").asText());
        ResponseEntity<byte[]> batch = get("/api/v1/finance/import-batches/" + batchId, accountant);
        assertThat(batch.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode dto = json(new String(batch.getBody(), StandardCharsets.UTF_8));
        assertThat(dto.get("status").asText()).isEqualTo("DRAFT");
        assertThat(dto.get("leasesImported").asInt()).isEqualTo(1);
        assertThat(dto.get("label").asText()).contains("al-ashram-cutover.xlsx");
    }

    @Test
    void aWorkbookWithErrorsComesBackAsValidationFailedWithEveryAddress() throws Exception {
        byte[] bytes;
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                new ByteArrayInputStream(templates.generateCutOverTemplate()))) {
            wb.getSheet("Properties").getRow(1).getCell(9).setCellValue("Bank Of Nowhere");
            var out = new java.io.ByteArrayOutputStream();
            wb.write(out);
            bytes = out.toByteArray();
        }
        UUID jobId = UUID.fromString(json(upload(accountant, bytes, "bad.xlsx", true).getBody())
                .get("jobId").asText());

        JsonNode done = awaitTerminal(jobId, accountant);
        assertThat(done.get("status").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(done.get("importBatchId").isNull()).isTrue();
        assertThat(done.get("errors")).isNotEmpty();
        done.get("errors").forEach(e -> {
            assertThat(e.get("sheet").asText()).isNotBlank();
            assertThat(e.get("row").asInt()).isGreaterThan(0);
            assertThat(e.get("field").asText()).isNotBlank();
        });
        // Scoped, and inside a transaction: TenantAspect only enables the Hibernate
        // tenant filter there, so an unscoped count would see every other test's rows.
        TenantContextHolder.setTenantId(tenantId);
        tx.executeWithoutResult(status -> assertThat(leaseRepo.findAll()).isEmpty());
        TenantContextHolder.clear();
    }

    /**
     * A file is an .xlsx because it starts like one, not because it is called one:
     * a renamed .exe with the right extension would otherwise reach the parser.
     */
    @Test
    void aFileThatIsNotReallyAWorkbookIsRefused() {
        ResponseEntity<String> res = upload(accountant,
                "MZ  this is not a workbook".getBytes(StandardCharsets.ISO_8859_1),
                "cutover.xlsx", true);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains(".xlsx");
    }

    @Test
    void anEmptyUploadIsRefused() {
        ResponseEntity<String> res = upload(accountant, new byte[0], "cutover.xlsx", true);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** A job of another organisation is a 404, whatever role is asking. */
    @Test
    void anotherOrganisationsJobIsNotFound() throws Exception {
        LandlordOrg other = new LandlordOrg();
        other.setName("Other-" + UUID.randomUUID());
        UUID otherTenant = orgRepo.save(other).getId();
        TenantContextHolder.setTenantId(otherTenant);
        var job = new com.datagami.rentaxis.domain.entity.ImportJob();
        job.setStatus("COMPLETED");
        job.setFileName("theirs.xlsx");
        UUID theirJobId = importJobs.save(job).getId();
        TenantContextHolder.clear();

        assertThat(get(BASE + "/" + theirJobId + "/status", accountant).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
