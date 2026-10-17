package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.ImportErrorDTO;
import com.datagami.rentaxis.api.dto.PortfolioImportJobDetailsDTO;
import com.datagami.rentaxis.core.service.cutover.CutoverFixture;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.BookingRequest;
import com.datagami.rentaxis.domain.entity.ImportJob;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.ParkingSpot;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.BookingRequestStatus;
import com.datagami.rentaxis.domain.entity.enums.BookingResourceType;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.BookingRequestRepository;
import com.datagami.rentaxis.domain.repository.ImportJobRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.ParkingSpotBuildingScopeRepository;
import com.datagami.rentaxis.domain.repository.ParkingSpotRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Attached parking (changeset 175): the portfolio workbook's optional Parking sheet,
 * through the real async orchestrator against Postgres — the happy path, the row
 * errors, an idempotent re-import that updates bays in place, an older workbook
 * without the sheet, and the cut-over dialect's template importing as it stands.
 */
@SpringBootTest
class ParkingSheetImportIT extends AbstractPostgresIT {

    @Autowired PortfolioImportService importService;
    @Autowired PortfolioTemplateService templates;
    @Autowired ImportJobRepository importJobRepository;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired ParkingSpotRepository spotRepo;
    @Autowired ParkingSpotBuildingScopeRepository scopeRepo;
    @Autowired BookingRequestRepository bookingRepo;
    @Autowired UserRepository userRepo;
    @Autowired TransactionTemplate tx;
    @Autowired AccountService accountService;
    @Autowired com.datagami.rentaxis.core.service.ledger.PropertyAccountService propertyAccountService;
    @Autowired com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService fiscal;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROPERTY = "Parking Plaza";

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("Parking-IT-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    // ------------------------------------------------------------------ workbooks

    private static Sheet sheet(Workbook wb, String name, String... headers) {
        Sheet s = wb.createSheet(name);
        Row r = s.createRow(0);
        for (int i = 0; i < headers.length; i++) r.createCell(i).setCellValue(headers[i]);
        return s;
    }

    private static void row(Sheet s, String... values) {
        Row r = s.createRow(s.getLastRowNum() + 1);
        for (int i = 0; i < values.length; i++) r.createCell(i).setCellValue(values[i]);
    }

    /**
     * A v1 workbook: optionally one new property with units 101 and 102 in Tower A and
     * 101 in Tower B, no tenants or contracts, and the given Parking rows (null = no sheet).
     */
    private static Workbook v1(boolean withProperty, List<String[]> parking) {
        Workbook wb = new XSSFWorkbook();
        Sheet props = sheet(wb, "Properties", "PropertyName", "PropertyNameAr", "Emirate", "Address", "Type", "MakaniNumber");
        Sheet units = sheet(wb, "Units", "PropertyName", "BuildingName", "UnitNumber", "UnitType", "SizeSqft", "ExpectedRent");
        if (withProperty) {
            row(props, PROPERTY, "", "DUBAI", "", "RESIDENTIAL", "");
            row(units, PROPERTY, "Tower A", "101", "BHK1", "", "");
            row(units, PROPERTY, "Tower A", "102", "BHK1", "", "");
            row(units, PROPERTY, "Tower B", "101", "BHK1", "", "");
        }
        sheet(wb, "Tenants", "Name", "NameAr", "Email", "Phone");
        sheet(wb, "Tenancy Contracts", "PropertyName", "BuildingName", "UnitNumber", "RenterEmail", "StartDate", "EndDate",
                "RentAmount", "DepositAmount", "PaymentTerms", "PaymentMethod", "EjariNumber");
        if (parking != null) {
            Sheet p = sheet(wb, "Parking", PortfolioSheets.PARKING_HEADERS.toArray(String[]::new));
            parking.forEach(values -> row(p, values));
        }
        return wb;
    }

    private static byte[] bytes(Workbook wb) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            return out.toByteArray();
        }
    }

    private ImportJob run(Workbook wb) throws Exception {
        ImportJob job = new ImportJob();
        job.setStatus("VALIDATING");
        job.setFileName("parking.xlsx");
        job.setCreatedBy(UUID.randomUUID());
        job = importJobRepository.save(job);
        importService.processImportAsync(bytes(wb), job, tenantId);
        ImportJob done = awaitTerminal(job.getId());
        TenantContextHolder.setTenantId(tenantId);
        return done;
    }

    private ImportJob awaitTerminal(UUID jobId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            ImportJob j = importJobRepository.findById(jobId).orElseThrow();
            if (List.of("COMPLETED", "VALIDATION_FAILED", "FAILED").contains(j.getStatus())) return j;
            Thread.sleep(200);
        }
        throw new AssertionError("Import " + jobId + " did not finish");
    }

    private static PortfolioImportJobDetailsDTO details(ImportJob job) throws Exception {
        assertThat(job.getErrors()).as("job details").startsWith("{");
        return JSON.readValue(job.getErrors(), PortfolioImportJobDetailsDTO.class);
    }

    private static List<ImportErrorDTO> errors(ImportJob job) throws Exception {
        return JSON.readValue(job.getErrors(), new TypeReference<List<ImportErrorDTO>>() {});
    }

    private Property property() {
        return propertyRepo.findByTenantIdAndNameEnIn(tenantId, List.of(PROPERTY)).getFirst();
    }

    private Map<String, ParkingSpot> bays() {
        return spotRepo.findAll().stream()
                .filter(s -> tenantId.equals(s.getTenantId()))
                .collect(Collectors.toMap(ParkingSpot::getSpotNumber, s -> s));
    }

    /** The unit's id by building name and number, inside a transaction (building is lazy). */
    private UUID unit(String building, String number) {
        return tx.execute(s -> unitRepo.findByPropertyIdUnordered(property().getId()).stream()
                .filter(u -> number.equals(u.getUnitNumber()))
                .filter(u -> u.getBuilding() != null && building.equals(u.getBuilding().getNameEn()))
                .findFirst().orElseThrow().getId());
    }

    private List<UUID> scope(ParkingSpot s) {
        return tx.execute(st -> scopeRepo.findByParkingSpotId(s.getId()).stream()
                .map(com.datagami.rentaxis.domain.entity.ParkingSpotBuildingScope::getBuildingId).toList());
    }

    // ------------------------------------------------------------------ tests

    @Test
    void aNewPropertysBaysImportAttachedAndBookable() throws Exception {
        ImportJob job = run(v1(true, List.of(
                new String[]{PROPERTY, "Tower A", "P-1", "B1", "TRUE", "101"},
                new String[]{PROPERTY, "", "P-2", "B2", "FALSE", ""})));

        assertThat(job.getStatus()).as(job.getErrors()).isEqualTo("COMPLETED");
        assertThat(details(job).getParkingBaysCreated()).isEqualTo(2);
        assertThat(details(job).getParkingBaysUpdated()).isZero();

        Map<String, ParkingSpot> bays = bays();
        assertThat(bays).containsOnlyKeys("P-1", "P-2");
        ParkingSpot p1 = bays.get("P-1");
        assertThat(p1.getAttachedUnitId()).isEqualTo(unit("Tower A", "101"));
        assertThat(p1.getLevel()).isEqualTo("B1");
        assertThat(p1.isCovered()).isTrue();
        assertThat(scope(p1)).hasSize(1);
        ParkingSpot p2 = bays.get("P-2");
        assertThat(p2.getAttachedUnitId()).isNull();
        assertThat(p2.isCovered()).isFalse();
        assertThat(scope(p2)).isEmpty();
    }

    @Test
    void unknownNamesAndDuplicatesAreRowErrorsAndNothingIsWritten() throws Exception {
        ImportJob job = run(v1(true, List.of(
                new String[]{"Nowhere Towers", "", "P-1", "", "", ""},
                new String[]{PROPERTY, "Tower Z", "P-2", "", "", ""},
                new String[]{PROPERTY, "Tower A", "P-3", "", "", "999"},
                new String[]{PROPERTY, "", "P-4", "", "", "101"},      // 101 is in Tower A and Tower B
                new String[]{PROPERTY, "", "P-5", "", "maybe", ""},
                new String[]{PROPERTY, "", "P-6", "", "", ""},
                new String[]{PROPERTY, "", "P-6", "", "", ""})));

        assertThat(job.getStatus()).isEqualTo("VALIDATION_FAILED");
        Map<Integer, String> byRow = errors(job).stream()
                .filter(e -> "Parking".equals(e.getSheet()))
                .collect(Collectors.toMap(ImportErrorDTO::getRow, ImportErrorDTO::getField, (a, b) -> a));
        assertThat(byRow).containsEntry(2, "PropertyName")
                .containsEntry(3, "BuildingName")
                .containsEntry(4, "AttachedUnitNumber")
                .containsEntry(5, "AttachedUnitNumber")
                .containsEntry(6, "Covered")
                .containsEntry(8, "BayNumber")
                .doesNotContainKey(7);
        assertThat(propertyRepo.findByTenantIdAndNameEnIn(tenantId, List.of(PROPERTY))).isEmpty();
        assertThat(bays()).isEmpty();
    }

    @Test
    void reImportingUpdatesExistingBaysInPlaceAndIsIdempotent() throws Exception {
        assertThat(run(v1(true, List.of(
                new String[]{PROPERTY, "Tower A", "P-1", "B1", "TRUE", "101"},
                new String[]{PROPERTY, "", "P-2", "B2", "TRUE", ""}))).getStatus()).isEqualTo("COMPLETED");
        UUID p1 = bays().get("P-1").getId();

        // A Parking-only workbook against the existing property: P-1 detached and moved
        // to level B3, uncovered; P-2 attached to Tower B's 101.
        Workbook again = v1(false, List.of(
                new String[]{PROPERTY, "", "P-1", "B3", "FALSE", ""},
                new String[]{PROPERTY, "Tower B", "P-2", "B2", "TRUE", "101"}));
        for (int round = 0; round < 2; round++) {
            ImportJob job = run(again);
            assertThat(job.getStatus()).as(job.getErrors()).isEqualTo("COMPLETED");
            assertThat(details(job).getParkingBaysCreated()).isZero();
            assertThat(details(job).getParkingBaysUpdated()).isEqualTo(2);

            Map<String, ParkingSpot> bays = bays();
            assertThat(bays).containsOnlyKeys("P-1", "P-2");
            assertThat(bays.get("P-1").getId()).isEqualTo(p1);
            assertThat(bays.get("P-1").getAttachedUnitId()).isNull();
            assertThat(bays.get("P-1").getLevel()).isEqualTo("B3");
            assertThat(bays.get("P-1").isCovered()).isFalse();
            assertThat(scope(bays.get("P-1"))).isEmpty();
            assertThat(bays.get("P-2").getAttachedUnitId()).isEqualTo(unit("Tower B", "101"));
        }
    }

    @Test
    void reImportCannotAttachABayABookingStillHolds() throws Exception {
        assertThat(run(v1(true, List.<String[]>of(
                new String[]{PROPERTY, "", "P-1", "", "", ""}))).getStatus()).isEqualTo("COMPLETED");
        ParkingSpot p1 = bays().get("P-1");
        UUID unitId = unit("Tower A", "102");
        User renter = new User();
        renter.setEmail("bay-renter-" + UUID.randomUUID() + "@t.io");
        renter.setName("Bay Renter");
        renter.setRole(UserRole.RENTER);
        renter.setStatus(UserStatus.ACTIVE);
        renter.setPasswordHash("x");
        renter.setTenantId(tenantId);
        renter = userRepo.save(renter);
        BookingRequest b = new BookingRequest();
        b.setTenantId(tenantId);
        b.setPropertyId(p1.getPropertyId());
        b.setResourceType(BookingResourceType.PARKING_SPOT);
        b.setParkingSpotId(p1.getId());
        b.setUnitId(unitId);
        b.setRenterUserId(renter.getId());
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Dubai"));
        b.setPreferredDate(today.minusDays(3));
        b.setPreferredEndDate(today.plusDays(3));
        b.setStatus(BookingRequestStatus.APPROVED);
        bookingRepo.saveAndFlush(b);

        ImportJob job = run(v1(false, List.<String[]>of(new String[]{PROPERTY, "Tower A", "P-1", "", "", "101"})));
        assertThat(job.getStatus()).isEqualTo("VALIDATION_FAILED");
        assertThat(errors(job)).singleElement().satisfies(e -> {
            assertThat(e.getSheet()).isEqualTo("Parking");
            assertThat(e.getRow()).isEqualTo(2);
            assertThat(e.getMessage()).contains("booking");
        });
        assertThat(bays().get("P-1").getAttachedUnitId()).isNull();
    }

    @Test
    void aParkingSheetMissingAColumnIsAFileError_neverReadAsBlanks() throws Exception {
        Workbook wb = v1(true, null);
        Sheet p = sheet(wb, "Parking", "PropertyName", "BuildingName", "BayNumber", "Covered");
        row(p, PROPERTY, "Tower A", "P-1", "TRUE");

        ImportJob job = run(wb);

        assertThat(job.getStatus()).isEqualTo("VALIDATION_FAILED");
        assertThat(errors(job).stream().filter(e -> "Parking".equals(e.getSheet())).map(ImportErrorDTO::getField))
                .containsExactlyInAnyOrder("Level", "AttachedUnitNumber");
        assertThat(bays()).isEmpty();
    }

    @Test
    void anOlderWorkbookWithoutTheSheetStillImports() throws Exception {
        ImportJob job = run(v1(true, null));
        assertThat(job.getStatus()).as(job.getErrors()).isEqualTo("COMPLETED");
        assertThat(job.getUnitsCreated()).isEqualTo(3);
        assertThat(job.getErrors() == null || !job.getErrors().contains("parkingBays")).isTrue();
        assertThat(bays()).isEmpty();
    }

    @Test
    void bothTemplatesCarryTheParkingSheetWithTwoSampleBays() throws Exception {
        for (byte[] template : List.of(templates.generateTemplate(), templates.generateCutOverTemplate())) {
            try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(template))) {
                Sheet p = wb.getSheet("Parking");
                assertThat(p).isNotNull();
                Row header = p.getRow(0);
                assertThat(List.of(0, 1, 2, 3, 4, 5).stream().map(i -> header.getCell(i).getStringCellValue()).toList())
                        .containsExactlyElementsOf(PortfolioSheets.PARKING_HEADERS);
                assertThat(p.getLastRowNum()).isEqualTo(2);
                assertThat(p.getRow(1).getCell(5).getStringCellValue()).isNotBlank();   // attached
                assertThat(p.getRow(2).getCell(5).getStringCellValue()).isBlank();      // bookable
                assertThat(p.getDataValidations()).isNotEmpty();                        // Covered dropdown
            }
        }
    }

    @Test
    void theCutOverTemplateImportsItsParkingSheetAsItStands() throws Exception {
        CutoverFixture fixture = new CutoverFixture(orgRepo, accountService, propertyAccountService, fiscal,
                templates, importJobRepository);
        tenantId = fixture.newCutOverTenant("PARK-CO");
        ImportJob job = fixture.newJob();
        importService.processCutoverImportAsync(templates.generateCutOverTemplate(), job, tenantId);
        ImportJob done = awaitTerminal(job.getId());
        TenantContextHolder.setTenantId(tenantId);

        assertThat(done.getStatus()).as(done.getErrors()).isEqualTo("COMPLETED");
        assertThat(details(done).getParkingBaysCreated()).isEqualTo(2);
        Map<String, ParkingSpot> bays = bays();
        assertThat(bays.get("P-001").getAttachedUnitId()).isNotNull();
        assertThat(bays.get("P-002").getAttachedUnitId()).isNull();
        Unit a101 = unitRepo.findById(bays.get("P-001").getAttachedUnitId()).orElseThrow();
        assertThat(a101.getUnitNumber()).isEqualTo("A-101");
    }
}
