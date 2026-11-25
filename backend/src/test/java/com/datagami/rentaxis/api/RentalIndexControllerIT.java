package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.BlobStorageService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.rentalindex.DldPdfFixtures;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.RentalIndexRate;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
import com.datagami.rentaxis.domain.entity.enums.RentalIndexSource;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import com.datagami.rentaxis.domain.entity.enums.UnitType;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.RentalIndexRateRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import com.datagami.rentaxis.testsupport.TestIdentities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Rental index range endpoints over HTTP: flag gate, roles, tenant and property scope, upload, manual, revoke. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RentalIndexControllerIT extends AbstractPostgresIT {

    private static final String BASE = "/api/v1/rental-index";

    @LocalServerPort int port;
    @MockitoBean BlobStorageService blob;

    @Autowired TenantFeatureService features;
    @Autowired RentalIndexRateRepository rates;
    @Autowired LeaseService leaseService;
    @Autowired ChequeGenerationService generation;
    @Autowired LeasePostingService posting;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired UserPropertyAssignmentRepository assignmentRepo;

    private LeaseTestFixtures a;
    private UUID tenantId;
    private UUID propertyId;
    private Unit unit;
    private UUID admin;
    private UUID manager;
    private UUID unassignedManager;
    private UUID accountant;
    private UUID otherTenantId;
    private UUID otherAdmin;

    @BeforeEach
    void setUp() {
        a = fixtures();
        tenantId = a.tenantId();
        propertyId = a.property().getId();
        unit = a.unit();
        unit.setType(UnitType.BHK2);
        unit.setDewaPremiseNumber("123456789");
        unit = unitRepo.save(unit);
        features.setEnabled(tenantId, TenantFeature.RENTAL_INDEX, true);

        LeaseTestFixtures b = fixtures();
        otherTenantId = b.tenantId();
        features.setEnabled(otherTenantId, TenantFeature.RENTAL_INDEX, true);
        otherAdmin = TestIdentities.user(userRepo, UserRole.TENANT_ADMIN, otherTenantId);

        admin = TestIdentities.user(userRepo, UserRole.TENANT_ADMIN, tenantId);
        manager = TestIdentities.user(userRepo, UserRole.PROPERTY_MANAGER, tenantId);
        unassignedManager = TestIdentities.user(userRepo, UserRole.PROPERTY_MANAGER, tenantId);
        accountant = TestIdentities.user(userRepo, UserRole.ACCOUNTANT, tenantId);
        UserPropertyAssignment assignment = new UserPropertyAssignment();
        assignment.setUserId(manager);
        assignment.setPropertyId(propertyId);
        assignmentRepo.save(assignment);

        when(blob.uploadRentalIndexDoc(eq(tenantId), any()))
                .thenAnswer(inv -> new BlobStorageService.UploadResult("https://blob.invalid/x",
                        "rental-index/" + UUID.randomUUID() + ".pdf"));
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    @Test
    void flagOffReturns404() {
        UUID rateId = manualRate(admin);
        features.setEnabled(tenantId, TenantFeature.RENTAL_INDEX, false);
        assertThat(raw(admin, UserRole.TENANT_ADMIN, HttpMethod.GET, BASE + "/rates?propertyId=" + propertyId, null)
                .getStatusCode().value()).isEqualTo(404);
        assertThat(multipart(admin, UserRole.TENANT_ADMIN, BASE + "/parse", pdfForm(DldPdfFixtures.Values.valid()))
                .getStatusCode().value()).isEqualTo(404);
        MultiValueMap<String, Object> form = pdfForm(DldPdfFixtures.Values.valid());
        form.add("propertyId", propertyId.toString());
        form.add("unitType", "BHK2");
        assertThat(multipart(admin, UserRole.TENANT_ADMIN, BASE + "/rates/from-pdf", form)
                .getStatusCode().value()).isEqualTo(404);
        assertThat(call(admin, UserRole.TENANT_ADMIN, HttpMethod.POST, BASE + "/rates/manual", manualBody(
                "138000", "162000")).getStatusCode().value()).isEqualTo(404);
        assertThat(call(admin, UserRole.TENANT_ADMIN, HttpMethod.POST, BASE + "/rates/" + rateId + "/revoke", null)
                .getStatusCode().value()).isEqualTo(404);
        assertThat(raw(admin, UserRole.TENANT_ADMIN, HttpMethod.GET, BASE + "/rates/" + rateId + "/document", null)
                .getStatusCode().value()).isEqualTo(404);
        assertThat(rates.findById(rateId).orElseThrow().getRevokedAt()).isNull();
    }

    @Test
    void renterForbidden() {
        UUID rateId = manualRate(admin);
        for (UserRole role : List.of(UserRole.RENTER, UserRole.TENANT_USER, UserRole.SECURITY_GUARD)) {
            UUID u = TestIdentities.user(userRepo, role, tenantId);
            assertThat(raw(u, role, HttpMethod.GET, BASE + "/rates?propertyId=" + propertyId, null)
                    .getStatusCode().value()).as(role.name()).isEqualTo(403);
            assertThat(call(u, role, HttpMethod.POST, BASE + "/rates/manual", manualBody("138000", "162000"))
                    .getStatusCode().value()).as(role.name()).isEqualTo(403);
            assertThat(raw(u, role, HttpMethod.GET, BASE + "/rates/" + rateId + "/document", null)
                    .getStatusCode().value()).as(role.name()).isEqualTo(403);
        }
        // An accountant reads but does not write.
        assertThat(raw(accountant, UserRole.ACCOUNTANT, HttpMethod.GET, BASE + "/rates?propertyId=" + propertyId, null)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(call(accountant, UserRole.ACCOUNTANT, HttpMethod.POST, BASE + "/rates/manual",
                manualBody("138000", "162000")).getStatusCode().value()).isEqualTo(403);
        assertThat(call(accountant, UserRole.ACCOUNTANT, HttpMethod.POST, BASE + "/rates/" + rateId + "/revoke", null)
                .getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void otherTenantCannotSeeRates() {
        UUID rateId = manualRate(admin);
        ResponseEntity<List> theirs = callFor(otherAdmin, UserRole.TENANT_ADMIN, otherTenantId, HttpMethod.GET,
                BASE + "/rates", null, List.class);
        assertThat(theirs.getStatusCode().value()).isEqualTo(200);
        assertThat(theirs.getBody()).isEmpty();
        assertThat(callFor(otherAdmin, UserRole.TENANT_ADMIN, otherTenantId, HttpMethod.GET,
                BASE + "/rates?propertyId=" + propertyId, null, Object.class).getStatusCode().value()).isIn(200, 404);
        assertThat(callFor(otherAdmin, UserRole.TENANT_ADMIN, otherTenantId, HttpMethod.POST,
                BASE + "/rates/" + rateId + "/revoke", null, Map.class).getStatusCode().value()).isEqualTo(404);
        assertThat(callFor(otherAdmin, UserRole.TENANT_ADMIN, otherTenantId, HttpMethod.GET,
                BASE + "/rates/" + rateId + "/document", null, byte[].class).getStatusCode().value()).isEqualTo(404);
        // Nor add a range on our property.
        Map<String, Object> body = new java.util.HashMap<>(manualBody("138000", "162000"));
        assertThat(callFor(otherAdmin, UserRole.TENANT_ADMIN, otherTenantId, HttpMethod.POST,
                BASE + "/rates/manual", body, Map.class).getStatusCode().value()).isEqualTo(404);
        assertThat(rates.findById(rateId).orElseThrow().getRevokedAt()).isNull();

        // A manager outside the property: 404 on it, nothing listed tenant-wide.
        assertThat(raw(unassignedManager, UserRole.PROPERTY_MANAGER, HttpMethod.GET,
                BASE + "/rates?propertyId=" + propertyId, null).getStatusCode().value()).isEqualTo(404);
        assertThat(callFor(unassignedManager, UserRole.PROPERTY_MANAGER, tenantId, HttpMethod.GET, BASE + "/rates",
                null, List.class).getBody()).isEmpty();
        assertThat(call(unassignedManager, UserRole.PROPERTY_MANAGER, HttpMethod.POST,
                BASE + "/rates/" + rateId + "/revoke", null).getStatusCode().value()).isEqualTo(404);
        assertThat(call(unassignedManager, UserRole.PROPERTY_MANAGER, HttpMethod.POST, BASE + "/rates/manual",
                manualBody("138000", "162000")).getStatusCode().value()).isEqualTo(404);
        // The assigned manager sees it.
        assertThat(callFor(manager, UserRole.PROPERTY_MANAGER, tenantId, HttpMethod.GET, BASE + "/rates", null,
                List.class).getBody()).hasSize(1);
    }

    @Test
    void uploadFromPdfStoresRateAndDocument() {
        byte[] pdf = DldPdfFixtures.layoutNovember(DldPdfFixtures.Values.valid());
        MultiValueMap<String, Object> form = form(pdf);
        form.add("propertyId", propertyId.toString());
        form.add("unitType", "BHK2");
        form.add("unitId", unit.getId().toString());
        ResponseEntity<Map> res = multipart(manager, UserRole.PROPERTY_MANAGER, BASE + "/rates/from-pdf", form);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        Map<?, ?> dto = res.getBody();
        assertThat(dto.get("source")).isEqualTo("DLD_PDF");
        assertThat(dto.get("unitType")).isEqualTo("BHK2");
        assertThat(dto.get("dldPropertyType")).isEqualTo("APARTMENT");
        assertThat(dto.get("category")).isEqualTo("2 bed rooms+hall");
        assertThat(new BigDecimal(dto.get("indexMin").toString())).isEqualByComparingTo("138000");
        assertThat(new BigDecimal(dto.get("indexMax").toString())).isEqualByComparingTo("162000");
        assertThat(new BigDecimal(dto.get("average").toString())).isEqualByComparingTo("150000");
        assertThat(dto.get("dldPercent")).isEqualTo(5);
        assertThat(dto.get("hasDocument")).isEqualTo(true);
        assertThat(dto.get("areaName")).isEqualTo("Test Area");
        assertThat(dto.toString()).doesNotContain("blob.invalid").doesNotContain("rental-index/");

        UUID id = UUID.fromString(dto.get("id").toString());
        RentalIndexRate row = rates.findById(id).orElseThrow();
        assertThat(row.getTenantId()).isEqualTo(tenantId);
        assertThat(row.getSourceUnitId()).isEqualTo(unit.getId());
        assertThat(row.getCreatedBy()).isEqualTo(manager);
        assertThat(row.getDocumentPath()).startsWith("rental-index/").endsWith(".pdf");
        // The November layout carries no issue time: checked today, expires a year on.
        assertThat(row.getCheckedOn()).isEqualTo(LocalDate.now());
        assertThat(row.getExpiresOn()).isEqualTo(row.getCheckedOn().plusMonths(12));
        verify(blob).uploadRentalIndexDoc(eq(tenantId), eq(pdf));

        when(blob.download(tenantId, row.getDocumentPath()))
                .thenReturn(new BlobStorageService.DownloadResult(pdf, "application/octet-stream"));
        ResponseEntity<byte[]> doc = callFor(manager, UserRole.PROPERTY_MANAGER, tenantId, HttpMethod.GET,
                BASE + "/rates/" + id + "/document", null, byte[].class);
        assertThat(doc.getStatusCode().value()).isEqualTo(200);
        assertThat(doc.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(doc.getBody()).isEqualTo(pdf);

        // The October layout carries the issue time: checked on that date.
        MultiValueMap<String, Object> oct = form(DldPdfFixtures.layoutOctober(DldPdfFixtures.Values.valid()));
        oct.add("propertyId", propertyId.toString());
        oct.add("unitType", "BHK2");
        Map<?, ?> octDto = multipart(admin, UserRole.TENANT_ADMIN, BASE + "/rates/from-pdf", oct).getBody();
        assertThat(octDto.get("checkedOn")).isEqualTo("2026-11-25");
        assertThat(octDto.get("expiresOn")).isEqualTo("2027-11-25");
        assertThat(octDto.get("sourceUnitId")).isNull();
    }

    @Test
    void premiseMismatchRefusedWhenUnitGiven() {
        DldPdfFixtures.Values v = DldPdfFixtures.Values.valid();
        DldPdfFixtures.Values other = new DldPdfFixtures.Values(v.pct(), v.increase(), v.after(), v.rent(), v.min(),
                v.max(), "987654321", v.building(), v.category(), v.endDate(), v.area(), v.issuedAt());
        MultiValueMap<String, Object> form = form(DldPdfFixtures.layoutNovember(other));
        form.add("propertyId", propertyId.toString());
        form.add("unitType", "BHK2");
        form.add("unitId", unit.getId().toString());
        ResponseEntity<Map> res = multipart(admin, UserRole.TENANT_ADMIN, BASE + "/rates/from-pdf", form);
        assertThat(res.getStatusCode().is4xxClientError()).isTrue();
        assertThat(res.getBody().get("code")).isEqualTo("rentalIndex.premiseMismatch");
        assertThat(rates.findAll().stream().filter(r -> tenantId.equals(r.getTenantId()))).isEmpty();
        verify(blob, never()).uploadRentalIndexDoc(any(), any());

        // Without a unit it is a building range: accepted.
        MultiValueMap<String, Object> noUnit = form(DldPdfFixtures.layoutNovember(other));
        noUnit.add("propertyId", propertyId.toString());
        noUnit.add("unitType", "BHK2");
        assertThat(multipart(admin, UserRole.TENANT_ADMIN, BASE + "/rates/from-pdf", noUnit)
                .getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void manualRangeInvalidRefused() {
        for (String[] bad : new String[][]{{"0", "100000"}, {"-5", "100000"}, {"150000", "149999"}}) {
            ResponseEntity<Map> res = call(admin, UserRole.TENANT_ADMIN, HttpMethod.POST, BASE + "/rates/manual",
                    manualBody(bad[0], bad[1]));
            assertThat(res.getStatusCode().is4xxClientError()).as(String.join("..", bad)).isTrue();
            assertThat(res.getBody().get("code")).isEqualTo("rentalIndex.rangeInvalid");
        }
        ResponseEntity<Map> ok = call(admin, UserRole.TENANT_ADMIN, HttpMethod.POST, BASE + "/rates/manual",
                manualBody("150000", "150000"));
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(ok.getBody().get("source")).isEqualTo("MANUAL");
        assertThat(ok.getBody().get("hasDocument")).isEqualTo(false);
        assertThat(ok.getBody().get("checkedOn")).isEqualTo("2026-11-01");
        assertThat(ok.getBody().get("expiresOn")).isEqualTo("2027-11-01");
    }

    @Test
    void revokeKeepsRowFlagged() {
        UUID rateId = manualRate(admin);
        ResponseEntity<Map> res = call(manager, UserRole.PROPERTY_MANAGER, HttpMethod.POST,
                BASE + "/rates/" + rateId + "/revoke", null);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("revoked")).isEqualTo(true);
        RentalIndexRate row = rates.findById(rateId).orElseThrow();
        assertThat(row.getRevokedAt()).isNotNull();
        assertThat(row.getRevokedBy()).isEqualTo(manager);
        assertThat(row.getSource()).isEqualTo(RentalIndexSource.MANUAL);

        List<?> listed = callFor(admin, UserRole.TENANT_ADMIN, tenantId, HttpMethod.GET,
                BASE + "/rates?propertyId=" + propertyId, null, List.class).getBody();
        assertThat(listed).hasSize(1);
        Map<?, ?> first = (Map<?, ?>) listed.get(0);
        assertThat(first.get("revoked")).isEqualTo(true);
        assertThat(first.get("revokedAt")).isNotNull();
    }

    @Test
    void parseReturnsChecks() {
        a.withLeaseServices(leaseService, generation, posting);
        TenantContextHolder.setTenantId(tenantId);
        LeaseTestFixtures.authenticateAsTenantAdmin();
        UUID leaseId = a.draftLease(unit, a.renter(), LocalDate.of(2026, 2, 20), LocalDate.of(2026, 3, 1),
                LocalDate.of(2027, 2, 28), List.of(line("RENT", "120000")));
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();

        MultiValueMap<String, Object> form = pdfForm(DldPdfFixtures.Values.valid());
        form.add("unitId", unit.getId().toString());
        form.add("leaseId", leaseId.toString());
        ResponseEntity<Map> res = multipart(manager, UserRole.PROPERTY_MANAGER, BASE + "/parse", form);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        Map<?, ?> body = res.getBody();
        assertThat(body.get("premiseNo")).isEqualTo("123456789");
        assertThat(body.get("dldPercent")).isEqualTo(5);
        assertThat(body.get("proposedUnitType")).isEqualTo("BHK2");
        assertThat(body.get("premiseMatches")).isEqualTo(true);
        assertThat(body.get("endMatches")).isEqualTo(true);
        assertThat(body.get("rentMatches")).isEqualTo(true);

        DldPdfFixtures.Values v = DldPdfFixtures.Values.valid();
        DldPdfFixtures.Values off = new DldPdfFixtures.Values(5, 5000, 105000, 100000, v.min(), v.max(),
                "987654321", v.building(), v.category(), "31/03/2027", v.area(), v.issuedAt());
        MultiValueMap<String, Object> offForm = pdfForm(off);
        offForm.add("leaseId", leaseId.toString());
        Map<?, ?> offBody = multipart(admin, UserRole.TENANT_ADMIN, BASE + "/parse", offForm).getBody();
        assertThat(offBody.get("premiseMatches")).isEqualTo(false); // from the lease's unit
        assertThat(offBody.get("endMatches")).isEqualTo(false);
        assertThat(offBody.get("rentMatches")).isEqualTo(false);

        Map<?, ?> bare = multipart(admin, UserRole.TENANT_ADMIN, BASE + "/parse", pdfForm(v)).getBody();
        assertThat(bare.get("premiseMatches")).isNull();
        assertThat(bare.get("endMatches")).isNull();
        assertThat(bare.get("rentMatches")).isNull();
        assertThat(rates.findAll().stream().filter(r -> tenantId.equals(r.getTenantId()))).isEmpty();
        verify(blob, never()).uploadRentalIndexDoc(any(), any());

        // Not a DLD result.
        ResponseEntity<Map> junk = multipart(admin, UserRole.TENANT_ADMIN, BASE + "/parse",
                form(DldPdfFixtures.pdf(List.of("Hello", "World"))));
        assertThat(junk.getBody().get("code")).isEqualTo("rentalIndex.notDldResult");

        // A manager outside the unit's property: 404.
        assertThat(multipart(unassignedManager, UserRole.PROPERTY_MANAGER, BASE + "/parse", form)
                .getStatusCode().value()).isEqualTo(404);
    }

    // --- helpers ---

    private LeaseTestFixtures fixtures() {
        return new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService).bootstrap();
    }

    private UUID manualRate(UUID by) {
        ResponseEntity<Map> res = call(by, UserRole.TENANT_ADMIN, HttpMethod.POST, BASE + "/rates/manual",
                manualBody("138000", "162000"));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        return UUID.fromString(res.getBody().get("id").toString());
    }

    private Map<String, Object> manualBody(String min, String max) {
        return Map.of("propertyId", propertyId.toString(), "unitType", "BHK2", "dldPropertyType", "APARTMENT",
                "category", "2 bed rooms+hall", "indexMin", min, "indexMax", max, "checkedOn", "2026-11-01");
    }

    private MultiValueMap<String, Object> pdfForm(DldPdfFixtures.Values v) {
        return form(DldPdfFixtures.layoutNovember(v));
    }

    private static MultiValueMap<String, Object> form(byte[] pdf) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(pdf) {
            @Override public String getFilename() { return "result.pdf"; }
        });
        return form;
    }

    private ResponseEntity<Object> raw(UUID user, UserRole role, HttpMethod method, String path, Object body) {
        return callFor(user, role, tenantId, method, path, body, Object.class);
    }

    private ResponseEntity<Map> call(UUID user, UserRole role, HttpMethod method, String path, Object body) {
        return callFor(user, role, tenantId, method, path, body, Map.class);
    }

    private <T> ResponseEntity<T> callFor(UUID user, UserRole role, UUID tenant, HttpMethod method, String path,
                                          Object body, Class<T> type) {
        RestClient.RequestBodySpec spec = RestClient.builder().baseUrl("http://localhost:" + port).build()
                .method(method).uri(path)
                .header("X-User-Id", user.toString())
                .header("X-User-Role", role.name())
                .header("X-Tenant-Id", tenant.toString())
                .header("X-User-Tenant-Id", tenant.toString());
        if (body != null) spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        return spec.retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(type);
    }

    private ResponseEntity<Map> multipart(UUID user, UserRole role, String path, MultiValueMap<String, Object> form) {
        return RestClient.builder().baseUrl("http://localhost:" + port).build()
                .post().uri(path)
                .header("X-User-Id", user.toString())
                .header("X-User-Role", role.name())
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Tenant-Id", tenantId.toString())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(Map.class);
    }
}
