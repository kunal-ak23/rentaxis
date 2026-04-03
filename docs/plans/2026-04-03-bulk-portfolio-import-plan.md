# Bulk Portfolio Import — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Enable onboarding a new landlord's entire portfolio via a single multi-sheet Excel workbook upload with async processing and auto-generated payment schedules.

**Architecture:** New `PortfolioImportService` parses a 4-sheet .xlsx (Properties, Units, Renters, Leases), validates all cross-references, then persists in order. Async job with status polling. Rich Excel template with dropdown validations via Apache POI (already in project).

**Tech Stack:** Java 21, Spring Boot 4.0.3, Apache POI 5.3.0, Liquibase, Next.js 16

**Design Doc:** `docs/plans/2026-04-03-bulk-portfolio-import-design.md`

---

## Task 1: Database Migration — `import_jobs` Table

**Files:**
- Create: `backend/src/main/resources/db/changelog/changesets/36-import-jobs.yaml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml` (add include at end)

**Step 1: Create the changeset file**

```yaml
databaseChangeLog:
  - changeSet:
      id: 36-import-jobs
      author: rentaxis
      changes:
        - createTable:
            tableName: import_jobs
            columns:
              - column:
                  name: id
                  type: uuid
                  constraints:
                    primaryKey: true
                    nullable: false
              - column:
                  name: tenant_id
                  type: uuid
                  constraints:
                    nullable: false
              - column:
                  name: status
                  type: varchar(30)
                  defaultValue: VALIDATING
                  constraints:
                    nullable: false
              - column:
                  name: file_name
                  type: varchar(255)
              - column:
                  name: properties_created
                  type: int
                  defaultValueNumeric: 0
              - column:
                  name: buildings_created
                  type: int
                  defaultValueNumeric: 0
              - column:
                  name: units_created
                  type: int
                  defaultValueNumeric: 0
              - column:
                  name: renters_created
                  type: int
                  defaultValueNumeric: 0
              - column:
                  name: leases_created
                  type: int
                  defaultValueNumeric: 0
              - column:
                  name: schedules_created
                  type: int
                  defaultValueNumeric: 0
              - column:
                  name: errors
                  type: jsonb
              - column:
                  name: created_by
                  type: uuid
              - column:
                  name: created_at
                  type: timestamp
                  defaultValueComputed: CURRENT_TIMESTAMP
              - column:
                  name: completed_at
                  type: timestamp
        - addForeignKeyConstraint:
            baseTableName: import_jobs
            baseColumnNames: tenant_id
            referencedTableName: tenants
            referencedColumnNames: id
            constraintName: fk_import_jobs_tenant
        - createIndex:
            tableName: import_jobs
            indexName: idx_import_jobs_tenant_id
            columns:
              - column:
                  name: tenant_id
```

**Step 2: Add include to master changelog**

Add at the end of `db.changelog-master.yaml`:
```yaml
  - include:
      file: db/changelog/changesets/36-import-jobs.yaml
```

**Step 3: Run backend to verify migration**

```bash
cd backend && ./gradlew bootRun
```
Expected: Application starts, `import_jobs` table created. Check logs for Liquibase success.

**Step 4: Commit**

```bash
git add backend/src/main/resources/db/changelog/
git commit -m "feat: add import_jobs table for async portfolio import tracking"
```

---

## Task 2: Entity, Repository, and DTOs

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/ImportJob.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/repository/ImportJobRepository.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/PortfolioImportResultDTO.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/ImportErrorDTO.java`

**Step 1: Create ImportJob entity**

```java
package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;
import java.util.UUID;

@Data
@Entity
@Table(name = "import_jobs")
public class ImportJob {
    @Id
    private UUID id;
    private UUID tenantId;
    private String status; // VALIDATING, VALIDATION_FAILED, PERSISTING, COMPLETED, FAILED
    private String fileName;
    private int propertiesCreated;
    private int buildingsCreated;
    private int unitsCreated;
    private int rentersCreated;
    private int leasesCreated;
    private int schedulesCreated;

    @Column(columnDefinition = "jsonb")
    private String errors; // JSON array of ImportErrorDTO

    private UUID createdBy;
    private Instant createdAt;
    private Instant completedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
        if (tenantId == null) tenantId = TenantContextHolder.getTenantId();
    }
}
```

**Step 2: Create ImportJobRepository**

```java
package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.ImportJob;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface ImportJobRepository extends JpaRepository<ImportJob, UUID> {
}
```

**Step 3: Create ImportErrorDTO**

```java
package com.datagami.rentaxis.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ImportErrorDTO {
    private String sheet;
    private int row;
    private String field;
    private String message;
}
```

**Step 4: Create PortfolioImportResultDTO**

```java
package com.datagami.rentaxis.api.dto;

import lombok.Data;
import java.util.List;
import java.util.UUID;

@Data
public class PortfolioImportResultDTO {
    private UUID jobId;
    private String status;
    private int propertiesCreated;
    private int buildingsCreated;
    private int unitsCreated;
    private int rentersCreated;
    private int leasesCreated;
    private int paymentSchedulesCreated;
    private List<ImportErrorDTO> errors;
}
```

**Step 5: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/domain/entity/ImportJob.java \
       backend/src/main/java/com/datagami/rentaxis/domain/repository/ImportJobRepository.java \
       backend/src/main/java/com/datagami/rentaxis/api/dto/PortfolioImportResultDTO.java \
       backend/src/main/java/com/datagami/rentaxis/api/dto/ImportErrorDTO.java
git commit -m "feat: add ImportJob entity, repository, and DTOs for portfolio import"
```

---

## Task 3: Async Configuration

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/config/AsyncConfig.java`

**Step 1: Create AsyncConfig**

```java
package com.datagami.rentaxis.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "importExecutor")
    public Executor importExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("import-");
        executor.initialize();
        return executor;
    }
}
```

**Step 2: Verify backend starts with async enabled**

```bash
cd backend && ./gradlew bootRun
```

**Step 3: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/config/AsyncConfig.java
git commit -m "feat: add async config with import thread pool"
```

---

## Task 4: Excel Template Generator

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/PortfolioTemplateService.java`

**Step 1: Create the template service**

This service generates a `.xlsx` file with 4 sheets, headers, dropdown data validations, and example rows. Use Apache POI `XSSFWorkbook`.

Key implementation details:
- **Sheet 1 "Properties"**: Columns A-F (PropertyName, PropertyNameAr, Emirate, Address, Type, MakaniNumber). Dropdown on Emirate (C), Type (E).
- **Sheet 2 "Units"**: Columns A-F (PropertyName, BuildingName, UnitNumber, UnitType, SizeSqft, ExpectedRent). Dropdown on UnitType (D).
- **Sheet 3 "Renters"**: Columns A-D (Name, NameAr, Email, Phone).
- **Sheet 4 "Leases"**: Columns A-J (PropertyName, UnitNumber, RenterEmail, StartDate, EndDate, RentAmount, DepositAmount, PaymentTerms, PaymentMethod, EjariNumber). Dropdown on PaymentMethod (I).
- Add 2-3 example rows per sheet with realistic UAE data.
- Use `DataValidation` with explicit list constraints for enum columns.
- Style header row with bold + background color.

Reference `AccountImportService.java` for POI patterns. The method should return `byte[]` of the workbook.

**Step 2: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/PortfolioTemplateService.java
git commit -m "feat: add Excel template generator for portfolio import"
```

---

## Task 5: Portfolio Import Service — Validation Phase

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/PortfolioImportService.java`

**Step 1: Create the import service with validation logic**

This is the core service. Implement validation phase first:

```java
@Service
@RequiredArgsConstructor
public class PortfolioImportService {
    private final ImportJobRepository importJobRepository;
    private final PropertyRepository propertyRepository;
    private final RenterRepository renterRepository;
    // ... other repos

    // Parse and validate all 4 sheets. Returns list of errors. No DB writes.
    public List<ImportErrorDTO> validateWorkbook(Workbook workbook) { ... }
}
```

Validation logic per sheet:
- **Properties**: nameEn required, emirate must be valid enum, type must be valid enum, no duplicate nameEn within file
- **Units**: propertyName required + must match Properties sheet, unitNumber required, unitType valid if provided, sizeSqft/expectedRent numeric if provided, no duplicate unitNumber within same property
- **Renters**: name required, email required + valid format, no duplicate emails within file
- **Leases**: propertyName+unitNumber must match Units sheet, renterEmail must match Renters sheet, startDate/endDate required + valid dates + endDate > startDate, rentAmount required + numeric

Also check for conflicts with existing DB data (property names already in tenant, renter emails already exist).

Use `getCellString()` helper pattern from `AccountImportService` for safe cell reading.

**Step 2: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/PortfolioImportService.java
git commit -m "feat: add portfolio import validation logic"
```

---

## Task 6: Portfolio Import Service — Persist Phase

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/PortfolioImportService.java`

**Step 1: Add the persist method**

```java
@Transactional
public ImportJob persistWorkbook(Workbook workbook, ImportJob job) {
    // 1. Create Properties (collect nameEn → Property map)
    // 2. Create Buildings (deduplicate by propertyId + buildingName)
    // 3. Create Units (link to property + building, set status VACANT by default)
    // 4. Create Renters
    // 5. Create Leases (link unit + renter, set status ACTIVE)
    //    - Update unit status to OCCUPIED
    //    - Auto-generate payment schedules via PaymentScheduleService.generateScheduleForLease()
    // 6. Update job with counts and COMPLETED status
}
```

Insert order matters for FK constraints. Use maps to track created entities by reference key (propertyName → Property, email → Renter, propertyName+unitNumber → Unit).

**Step 2: Add the async orchestrator method**

```java
@Async("importExecutor")
public void processImportAsync(byte[] fileBytes, ImportJob job) {
    try {
        Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(fileBytes));
        // Phase 1: Validate
        job.setStatus("VALIDATING");
        importJobRepository.save(job);
        List<ImportErrorDTO> errors = validateWorkbook(workbook);
        if (!errors.isEmpty()) {
            job.setStatus("VALIDATION_FAILED");
            job.setErrors(objectMapper.writeValueAsString(errors));
            job.setCompletedAt(Instant.now());
            importJobRepository.save(job);
            return;
        }
        // Phase 2: Persist
        job.setStatus("PERSISTING");
        importJobRepository.save(job);
        persistWorkbook(workbook, job);
        job.setStatus("COMPLETED");
        job.setCompletedAt(Instant.now());
        importJobRepository.save(job);
    } catch (Exception e) {
        job.setStatus("FAILED");
        job.setErrors("[{\"sheet\":\"General\",\"row\":0,\"field\":\"\",\"message\":\"" + e.getMessage() + "\"}]");
        job.setCompletedAt(Instant.now());
        importJobRepository.save(job);
    }
}
```

**Step 3: Commit**

```bash
git commit -am "feat: add portfolio import persist and async orchestration"
```

---

## Task 7: Import Controller Endpoints

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/api/PortfolioImportController.java`

**Step 1: Create the controller**

New controller (not modifying existing PropertyController) to keep concerns separate:

```java
@RestController
@RequestMapping("/api/v1/import/portfolio")
@RequiredArgsConstructor
public class PortfolioImportController {

    private final PortfolioImportService importService;
    private final PortfolioTemplateService templateService;
    private final ImportJobRepository importJobRepository;

    // POST / — upload .xlsx, start async job, return jobId
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, UUID>> importPortfolio(
            @RequestParam("file") MultipartFile file,
            @RequestHeader("X-User-Id") UUID userId) { ... }

    // GET /{jobId}/status — poll job status
    @GetMapping("/{jobId}/status")
    public ResponseEntity<PortfolioImportResultDTO> getStatus(
            @PathVariable UUID jobId) { ... }

    // GET /template — download .xlsx template
    @GetMapping("/template")
    public ResponseEntity<byte[]> downloadTemplate() { ... }
}
```

The upload endpoint validates file extension (.xlsx), creates ImportJob, calls `processImportAsync()`, returns `{ "jobId": "uuid" }`.

The status endpoint reads ImportJob from DB, maps to PortfolioImportResultDTO, parses errors JSON.

**Step 2: Verify all endpoints work**

```bash
cd backend && ./gradlew bootRun
# Test template download:
curl -o template.xlsx http://localhost:8080/api/v1/import/portfolio/template
# Test upload:
curl -X POST -F "file=@template.xlsx" -H "X-User-Id: bb000000-0000-4000-8000-000000000010" \
     -H "X-Tenant-Id: bb000000-0000-4000-8000-000000000001" \
     http://localhost:8080/api/v1/import/portfolio
```

**Step 3: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api/PortfolioImportController.java
git commit -m "feat: add portfolio import REST endpoints"
```

---

## Task 8: Web Frontend — Import Portfolio UI

**Files:**
- Modify: `web/src/app/[locale]/dashboard/properties/page.tsx`

**Step 1: Add "Import Portfolio" button**

Add alongside the existing "Import Property" button (around line 408). New button opens a separate full-screen modal.

**Step 2: Create the import portfolio modal**

4-step flow in the modal:

1. **Upload step**: Drag-drop `.xlsx` file area + "Download Template" button that calls `GET /api/proxy/v1/import/portfolio/template`. Submits file to `POST /api/proxy/v1/import/portfolio`.
2. **Validation step**: Shows spinner/progress while polling `GET /api/proxy/v1/import/portfolio/{jobId}/status` every 2 seconds. If `VALIDATION_FAILED`, show errors grouped by sheet tab.
3. **Confirm step**: When status is clean validation (no errors in PERSISTING state), show summary. Note: since async, validation clean → auto-proceeds to persist. If we want a confirmation gate, the backend can add a `VALIDATED` status that waits for `POST /confirm`.
4. **Result step**: When `COMPLETED`, show counts. When `FAILED`, show error. Close button.

**Simpler alternative (recommended for v1):** Skip explicit confirmation step. Backend auto-persists after validation passes. Frontend just polls and shows either errors or success.

**Step 3: Add Next.js proxy rewrite**

Check that `/api/proxy/v1/import/portfolio/**` is covered by the existing proxy rewrite in `next.config.ts`. The existing rewrite should handle `/api/proxy/v1/**` already.

**Step 4: Commit**

```bash
git add web/src/app/\\[locale\\]/dashboard/properties/page.tsx
git commit -m "feat: add portfolio import UI with upload, validation, and results"
```

---

## Task 9: End-to-End Testing

**Step 1: Create test Excel file**

Create a test `.xlsx` with:
- 2 properties (Marina Heights, Business Central)
- 4 units (2 per property, one with building name, one without)
- 2 renters
- 2 leases

**Step 2: Test happy path**

Upload via curl or web UI. Verify:
- ImportJob status progresses: VALIDATING → PERSISTING → COMPLETED
- Properties, buildings, units, renters, leases created in DB
- Payment schedules auto-generated for each lease
- Unit status set to OCCUPIED for leased units

**Step 3: Test validation errors**

Create a bad Excel file with:
- Missing required fields
- Invalid enum values
- Cross-sheet reference mismatches (lease references non-existent unit)
- Duplicate property names

Verify: Status = VALIDATION_FAILED, errors list is accurate, no DB writes occurred.

**Step 4: Commit any fixes**

```bash
git commit -am "fix: address issues found during e2e testing"
```

---

## Task 10: Cleanup Debug Statements

**Files:**
- Modify: `mobile/packages/rentaxis_core/lib/providers/auth_provider.dart` — remove `dev.log` debug statements
- Modify: `mobile/packages/rentaxis_core/lib/api/interceptors/auth_interceptor.dart` — remove debug statements
- Modify: `mobile/packages/rentaxis_core/lib/api/services/dashboard_service.dart` — remove debug statements
- Modify: `mobile/apps/manager/lib/router.dart` — remove debug import and log statements
- Modify: `mobile/apps/manager/lib/screens/dashboard_screen.dart` — remove debug Builder wrapper

**Step 1: Remove all `[*_DEBUG]` log statements added during this session**

**Step 2: Verify app still compiles**

```bash
cd mobile/apps/manager && flutter analyze lib/
```

**Step 3: Commit**

```bash
git commit -am "chore: remove debug statements from mobile app"
```
