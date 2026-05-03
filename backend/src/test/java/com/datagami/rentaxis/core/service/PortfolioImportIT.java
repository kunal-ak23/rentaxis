package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.PortfolioImportJobDetailsDTO;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.ImportJob;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.repository.ImportJobRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Workbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for bulk-portfolio import.
 *
 * <p>Stands up a real PostgreSQL via Testcontainers, runs Liquibase migrations,
 * and drives the async {@link PortfolioImportService#processImportAsync} through
 * to a {@code COMPLETED} job. Asserts on persisted entities (read back via the
 * tenant-filtered repositories) and on the JSONB {@code import_jobs.errors}
 * roundtrip — the four wiring layers the existing mock-based end-to-end test
 * cannot exercise: orchestrator, {@code @Transactional} boundary, JSONB
 * serialization, and tenant filter activation under the {@code TenantAspect}.</p>
 *
 * <p>Requires Docker on the host. Skipping it cleanly without Docker would
 * require a {@code @DisabledIf} probe; for now we let it fail loudly in that
 * case so missing infra is obvious.</p>
 */
@SpringBootTest
@Testcontainers
class PortfolioImportIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired PortfolioImportService importService;
    @Autowired ImportJobRepository importJobRepository;
    @Autowired LeaseRepository leaseRepository;
    @Autowired PaymentScheduleRepository paymentScheduleRepository;
    @Autowired LandlordOrgRepository landlordOrgRepository;
    @Autowired UnitRepository unitRepository;
    @Autowired RenterRepository renterRepository;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        // Each test gets its own tenant. Multi-tenant isolation in the schema
        // means we don't need DB cleanup between tests — repository reads are
        // already scoped by TenantContextHolder.
        LandlordOrg org = new LandlordOrg();
        org.setName("IT-Tenant-" + UUID.randomUUID());
        org = landlordOrgRepository.save(org);
        this.tenantId = org.getId();
        TenantContextHolder.setTenantId(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void uploadWorkbookWithAllNewFields_persistsCorrectly() throws Exception {
        Workbook wb = PortfolioImportPersistEndToEndTest.buildFiveScenarioWorkbook();
        byte[] fileBytes;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            wb.write(baos);
            fileBytes = baos.toByteArray();
        }
        wb.close();

        ImportJob job = new ImportJob();
        job.setStatus("VALIDATING");
        job.setFileName("five-scenarios.xlsx");
        job.setCreatedBy(UUID.randomUUID());
        ImportJob saved = importJobRepository.save(job);

        importService.processImportAsync(fileBytes, saved, tenantId);

        ImportJob completed = pollUntilCompleted(saved.getId());

        // Job summary counters
        assertThat(completed.getStatus()).isEqualTo("COMPLETED");
        assertThat(completed.getLeasesCreated()).isEqualTo(5);
        assertThat(completed.getRentersCreated()).isEqualTo(5);
        assertThat(completed.getPropertiesCreated()).isEqualTo(1);

        // JSONB roundtrip — counters wrapped in PortfolioImportJobDetailsDTO,
        // discriminator is the leading '{' character.
        assertThat(completed.getErrors()).startsWith("{");
        PortfolioImportJobDetailsDTO details = new ObjectMapper()
                .readValue(completed.getErrors(), PortfolioImportJobDetailsDTO.class);
        assertThat(details.getChequesFromSheet()).isEqualTo(4);
        assertThat(details.getBookingDepositsCreated()).isEqualTo(1);

        // Persisted leases — read back via tenant-filtered repository.
        // Lease.unit and Lease.renter are LAZY @ManyToOne; the persistence context
        // closed when the async @Transactional ended, so we resolve associations
        // explicitly via repos instead of traversing proxies.
        List<Lease> leases = leaseRepository.findAll();
        assertThat(leases).hasSize(5);

        UUID tenant5RenterId = renterRepository.findByEmailIn(List.of("tenant5@email.com"))
                .stream().findFirst().orElseThrow().getId();
        UUID tenant2RenterId = renterRepository.findByEmailIn(List.of("tenant2@email.com"))
                .stream().findFirst().orElseThrow().getId();

        // Scenario 3: Status=DRAFT must leave its unit VACANT.
        Lease draft = leases.stream()
                .filter(l -> l.getStatus() == LeaseStatus.DRAFT)
                .findFirst().orElseThrow();
        UnitStatus draftUnitStatus = unitRepository.findById(draft.getUnit().getId())
                .orElseThrow().getStatus();
        assertThat(draftUnitStatus).isEqualTo(UnitStatus.VACANT);

        // Scenario 5: MonthlyRent=5000 over a 1-year (Jan 1 → Dec 31) lease →
        // totalRent = 60,000 under the end-date-inclusive convention.
        Lease scenario5 = leases.stream()
                .filter(l -> tenant5RenterId.equals(l.getRenter().getId()))
                .findFirst().orElseThrow();
        assertThat(scenario5.getRentAmount()).isEqualByComparingTo("60000");
        assertThat(scenario5.getMonthlyRent()).isEqualByComparingTo("5000");

        // Scenario 4: booking deposit row persisted with isBookingDeposit=true.
        List<PaymentSchedule> bookings = paymentScheduleRepository.findAll().stream()
                .filter(PaymentSchedule::isBookingDeposit)
                .toList();
        assertThat(bookings).hasSize(1);
        assertThat(bookings.get(0).getAmount()).isEqualByComparingTo("10000");

        // Scenario 2: 4 cheque rows persisted from the Cheques sheet (not auto-distributed).
        UUID tenant2LeaseId = leases.stream()
                .filter(l -> tenant2RenterId.equals(l.getRenter().getId()))
                .findFirst().orElseThrow().getId();
        List<PaymentSchedule> chequeRows = paymentScheduleRepository.findAll().stream()
                .filter(p -> !p.isBookingDeposit())
                .filter(p -> tenant2LeaseId.equals(p.getLease().getId()))
                .toList();
        assertThat(chequeRows).hasSize(4);
        assertThat(chequeRows).extracting(PaymentSchedule::getAmount)
                .allMatch(a -> a.compareTo(new java.math.BigDecimal("15000")) == 0);
    }

    @Test
    void invalidWorkbook_marksJobValidationFailed_andPersistsNoEntities() throws Exception {
        // Workbook references a renter email that does NOT exist in the Renters sheet —
        // Phase 1 validation should reject before any DB writes occur.
        Workbook wb = PortfolioImportPersistEndToEndTest.buildFiveScenarioWorkbook();
        // Corrupt one Leases row's RenterEmail to break cross-sheet validation.
        wb.getSheet("Leases").getRow(1).getCell(3).setCellValue("nonexistent@example.com");

        byte[] fileBytes;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            wb.write(baos);
            fileBytes = baos.toByteArray();
        }
        wb.close();

        ImportJob job = new ImportJob();
        job.setStatus("VALIDATING");
        job.setFileName("invalid.xlsx");
        job.setCreatedBy(UUID.randomUUID());
        ImportJob saved = importJobRepository.save(job);

        importService.processImportAsync(fileBytes, saved, tenantId);

        ImportJob terminal = pollUntilTerminal(saved.getId());
        assertThat(terminal.getStatus()).isEqualTo("VALIDATION_FAILED");
        // Phase 2 never ran → no persisted leases for this tenant.
        assertThat(leaseRepository.findAll()).isEmpty();
        // Errors persisted in legacy array form (leading '[').
        assertThat(terminal.getErrors()).startsWith("[");
    }

    /** Polls the job up to 30s, asserting it lands in COMPLETED. */
    private ImportJob pollUntilCompleted(UUID jobId) throws InterruptedException {
        return pollUntil(jobId, "COMPLETED");
    }

    /** Polls the job up to 30s, returning whichever terminal status it reaches. */
    private ImportJob pollUntilTerminal(UUID jobId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            ImportJob current = importJobRepository.findById(jobId).orElseThrow();
            String s = current.getStatus();
            if ("COMPLETED".equals(s) || "VALIDATION_FAILED".equals(s) || "FAILED".equals(s)) {
                return current;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Job " + jobId + " did not reach a terminal status within 30s");
    }

    private ImportJob pollUntil(UUID jobId, String expected) throws InterruptedException {
        ImportJob current = pollUntilTerminal(jobId);
        if (!expected.equals(current.getStatus())) {
            throw new AssertionError("Expected status " + expected + " but got " + current.getStatus()
                    + " (errors=" + current.getErrors() + ")");
        }
        return current;
    }
}
