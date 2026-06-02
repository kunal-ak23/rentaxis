# Lease Charges + Security Deposit Rework — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the fixed `admin_fee`/`parking_remote_fee` columns with a flexible `lease_charges` table (custom name, amount, VAT, one-time vs per-installment), make charges real collected money (additive 5% VAT), and turn the security deposit into its own schedule row.

**Architecture:** New `lease_charges` table + `LeaseCharge` entity. Schedule generation folds PER_INSTALLMENT charges into each rent installment and emits a separate row per ONE_TIME charge and one for the security deposit. Legacy fee columns are dropped (no backward compatibility). Web + backend only; mobile is a fast-follow.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, Liquibase, PostgreSQL; Next.js 16 + TypeScript; JUnit + Vitest.

**Spec:** `docs/superpowers/specs/2026-06-02-lease-charges-and-security-deposit-rework-design.md`

**Prerequisite:** Merge `fix/payment-schedule-editor-lease-endpoint` to `main` first (so the new SD/charge rows are visible in the editor). Work this plan on branch `feat/lease-charges-rework`.

---

## File Structure

**Backend (create):**
- `backend/src/main/resources/db/changelog/changesets/61-lease-charges.yaml` — migration
- `backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/ChargeFrequency.java` — enum
- `backend/src/main/java/com/datagami/rentaxis/domain/entity/LeaseCharge.java` — entity
- `backend/src/main/java/com/datagami/rentaxis/domain/repository/LeaseChargeRepository.java` — repo
- `backend/src/main/java/com/datagami/rentaxis/api/dto/LeaseChargeDTO.java` — DTO

**Backend (modify):**
- `Lease.java` — remove 5 dropped fields
- `PaymentSchedule.java` — add `isSecurityDeposit`, `isCharge`
- `CreateLeaseDTO.java`, `LeaseDTO.java`, `PaymentScheduleDTO.java`
- `PaymentScheduleService.java` — generation, mapToDTO, VAT helper
- `LeaseService.java` — persist charges, create SD + one-time rows
- `ContractGenerationService.java` — render charges from `lease_charges`
- `PortfolioImportPersistService.java` — map fee columns → charges
- `db.changelog-master.yaml` — include 61 (if not glob-included)

**Frontend (modify):**
- `web/src/app/[locale]/dashboard/leases/LeaseWizard.tsx` — charges repeater + state + submit
- `web/src/app/[locale]/dashboard/leases/LeaseMetadataEditor.tsx` — charges editor
- `web/src/app/[locale]/dashboard/leases/PaymentScheduleEditor.tsx` — row markers + sort
- `web/src/types/lease.ts` (or inline types) — `LeaseChargeDTO` shape

---

## Task 1: Liquibase migration

**Files:**
- Create: `backend/src/main/resources/db/changelog/changesets/61-lease-charges.yaml`
- Check: `backend/src/main/resources/db/changelog/db.changelog-master.yaml` (confirm changesets are glob-included; if listed individually, append the include)

- [ ] **Step 1: Write the migration**

```yaml
databaseChangeLog:
  - changeSet:
      id: 61-lease-charges
      author: rentaxis-system
      changes:
        - createTable:
            tableName: lease_charges
            columns:
              - column: { name: id, type: uuid, constraints: { primaryKey: true, nullable: false } }
              - column: { name: tenant_id, type: uuid, constraints: { nullable: false } }
              - column: { name: lease_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_lease_charge_lease, referencedTableName: leases, referencedColumnNames: id } }
              - column: { name: name, type: varchar(120), constraints: { nullable: false } }
              - column: { name: amount, type: "decimal(12,2)", constraints: { nullable: false }, defaultValueNumeric: 0 }
              - column: { name: vat_applicable, type: boolean, constraints: { nullable: false }, defaultValueBoolean: false }
              - column: { name: frequency, type: varchar(20), constraints: { nullable: false } }
              - column: { name: created_at, type: timestamptz, constraints: { nullable: false }, defaultValueComputed: now() }
        - createIndex: { tableName: lease_charges, indexName: idx_lease_charge_lease, columns: [ { column: { name: lease_id } } ] }
        - createIndex: { tableName: lease_charges, indexName: idx_lease_charge_tenant, columns: [ { column: { name: tenant_id } } ] }
        - addColumn:
            tableName: payment_schedules
            columns:
              - column: { name: is_security_deposit, type: boolean, constraints: { nullable: false }, defaultValueBoolean: false }
              - column: { name: is_charge, type: boolean, constraints: { nullable: false }, defaultValueBoolean: false }
        - dropColumn: { tableName: leases, columnName: admin_fee }
        - dropColumn: { tableName: leases, columnName: admin_fee_vat_applicable }
        - dropColumn: { tableName: leases, columnName: parking_remote_fee }
        - dropColumn: { tableName: leases, columnName: parking_remote_vat_applicable }
        - dropColumn: { tableName: leases, columnName: security_deposit_vat_applicable }
```

- [ ] **Step 2: Confirm master changelog includes new changesets**

Run: `grep -n "includeAll\|61-lease-charges" backend/src/main/resources/db/changelog/db.changelog-master.yaml`
Expected: an `includeAll` of the `changesets/` directory (no edit needed). If instead each file is listed, add `- include: { file: db/changelog/changesets/61-lease-charges.yaml }` after the `60-*` line.

- [ ] **Step 3: Verify migration applies on a clean DB**

Run: `cd backend && ./gradlew bootRun` (against the dev DB) and watch logs for `61-lease-charges` applying with no error. (Local dev DB already migrated; if `dropColumn` fails because the schedule was generated under JPA `validate`, that's expected to surface only after the entity changes in later tasks — apply Task 1 + entity tasks together before running.)
Expected: Liquibase logs `ChangeSet ... 61-lease-charges ran successfully`.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/resources/db/changelog/changesets/61-lease-charges.yaml
git commit -m "feat(db): lease_charges table + payment_schedule deposit/charge flags; drop legacy fee columns"
```

---

## Task 2: ChargeFrequency enum + LeaseCharge entity + repository

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/ChargeFrequency.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/LeaseCharge.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/repository/LeaseChargeRepository.java`

- [ ] **Step 1: Create the enum**

```java
package com.datagami.rentaxis.domain.entity.enums;

public enum ChargeFrequency {
    ONE_TIME,
    PER_INSTALLMENT
}
```

- [ ] **Step 2: Create the entity** (extends `BaseTenantEntity` like `PaymentSchedule`, so `tenant_id` + tenant filtering are handled)

```java
package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.ChargeFrequency;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "lease_charges")
@Getter
@Setter
public class LeaseCharge extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lease_id", nullable = false)
    private Lease lease;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "vat_applicable", nullable = false)
    private boolean vatApplicable = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChargeFrequency frequency = ChargeFrequency.ONE_TIME;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
```

Note: confirm `BaseTenantEntity` maps `tenant_id`; if it does not auto-populate on save, set `tenantId` explicitly where `LeaseCharge` rows are created (Task 7).

- [ ] **Step 3: Create the repository**

```java
package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.LeaseCharge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LeaseChargeRepository extends JpaRepository<LeaseCharge, UUID> {
    List<LeaseCharge> findByLeaseId(UUID leaseId);
}
```

- [ ] **Step 4: Compile**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/ChargeFrequency.java backend/src/main/java/com/datagami/rentaxis/domain/entity/LeaseCharge.java backend/src/main/java/com/datagami/rentaxis/domain/repository/LeaseChargeRepository.java
git commit -m "feat(leases): LeaseCharge entity, ChargeFrequency enum, repository"
```

---

## Task 3: Entity field changes

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/domain/entity/Lease.java:73-89`
- Modify: `backend/src/main/java/com/datagami/rentaxis/domain/entity/PaymentSchedule.java:91-92`

- [ ] **Step 1: Remove dropped fields from `Lease.java`**

Delete these blocks (lines 73-89), keeping `rent_vat_applicable`:

```java
    @Column(name = "admin_fee", nullable = false)
    private BigDecimal adminFee = BigDecimal.ZERO;

    @Column(name = "parking_remote_fee", nullable = false)
    private BigDecimal parkingRemoteFee = BigDecimal.ZERO;
```
and
```java
    @Column(name = "admin_fee_vat_applicable", nullable = false)
    private boolean adminFeeVatApplicable = false;

    @Column(name = "security_deposit_vat_applicable", nullable = false)
    private boolean securityDepositVatApplicable = false;

    @Column(name = "parking_remote_vat_applicable", nullable = false)
    private boolean parkingRemoteVatApplicable = false;
```
Keep:
```java
    @Column(name = "rent_vat_applicable", nullable = false)
    private boolean rentVatApplicable = false;
```

- [ ] **Step 2: Add flags to `PaymentSchedule.java`** (after line 92, alongside `isBookingDeposit`)

```java
    @Column(name = "is_security_deposit", nullable = false)
    private boolean isSecurityDeposit = false;

    @Column(name = "is_charge", nullable = false)
    private boolean isCharge = false;
```

- [ ] **Step 3: Compile — expect errors at every legacy-field reference**

Run: `cd backend && ./gradlew compileJava 2>&1 | grep -E "error:|adminFee|parkingRemote|securityDepositVat" | head -40`
Expected: compile errors in `CreateLeaseDTO`, `LeaseDTO`, `LeaseService`, `ContractGenerationService`, `PortfolioImportPersistService`. These are fixed in Tasks 4–9. (Do not commit until the module compiles — commit at the end of Task 9.)

---

## Task 4: DTOs

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/LeaseChargeDTO.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/dto/CreateLeaseDTO.java:49-61`
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/dto/LeaseDTO.java` (the 4 fee/VAT fields, lines ~34-39)
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/dto/PaymentScheduleDTO.java:36`

- [ ] **Step 1: Create `LeaseChargeDTO`**

```java
package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.ChargeFrequency;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class LeaseChargeDTO {
    @NotBlank
    private String name;

    @NotNull
    @Min(0)
    private BigDecimal amount;

    private boolean vatApplicable;

    @NotNull
    private ChargeFrequency frequency;
}
```

- [ ] **Step 2: Update `CreateLeaseDTO`** — remove `adminFee`, `parkingRemoteFee`, `adminFeeVatApplicable`, `securityDepositVatApplicable`, `parkingRemoteVatApplicable` (lines 49-61), keep `rentVatApplicable`, and add:

```java
    private Boolean rentVatApplicable;

    private java.util.List<LeaseChargeDTO> charges;
```

- [ ] **Step 3: Update `LeaseDTO`** — remove the same 4 fee/VAT fields, keep `rentVatApplicable`, add `private List<LeaseChargeDTO> charges;`.

- [ ] **Step 4: Update `PaymentScheduleDTO`** — after `isBookingDeposit` (line 36) add:

```java
    private Boolean isSecurityDeposit;
    private Boolean isCharge;
```

- [ ] **Step 5: Compile** (still expect errors in services/contract — fixed next)

Run: `cd backend && ./gradlew compileJava 2>&1 | grep -E "error:" | grep -vE "LeaseService|ContractGenerationService|PortfolioImportPersistService|PaymentScheduleService" | head`
Expected: no DTO-level errors remain (only service-level ones).

---

## Task 5: VAT helper + mapToDTO

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/PaymentScheduleService.java`

- [ ] **Step 1: Add a VAT helper** (near `nz(...)` at line 183)

```java
    private static final BigDecimal VAT_MULTIPLIER = new BigDecimal("1.05");

    /** Additive 5% VAT: returns amount*1.05 (2dp) when vat applies, else amount. */
    static BigDecimal withVat(BigDecimal amount, boolean vat) {
        BigDecimal a = nz(amount);
        return vat ? a.multiply(VAT_MULTIPLIER).setScale(2, java.math.RoundingMode.HALF_UP) : a;
    }
```

- [ ] **Step 2: Map new flags in `mapToDTO`** — find the `mapToDTO` method (search `dto.setIsBookingDeposit`) and add next to it:

```java
        dto.setIsSecurityDeposit(ps.isSecurityDeposit());
        dto.setIsCharge(ps.isCharge());
```

- [ ] **Step 3: Compile**

Run: `cd backend && ./gradlew compileJava 2>&1 | grep "PaymentScheduleService" | head`
Expected: no new errors from this file (errors remain only in LeaseService/Contract/Import until Tasks 6–9).

---

## Task 6: Schedule generation — fold per-installment charges, fix preservation, relabel

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/PaymentScheduleService.java:60-181`
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/PaymentScheduleChargesTest.java` (create)

- [ ] **Step 1: Inject `LeaseChargeRepository`** — add to the constructor-injected final fields (line ~60):

```java
    private final LeaseChargeRepository leaseChargeRepository;
```
(If the class uses Lombok `@RequiredArgsConstructor`, just adding the field is enough; otherwise add it to the constructor.)

- [ ] **Step 2: Fix the preservation guard** (line 80) so SD and charge rows don't block rent generation:

Replace:
```java
        boolean hasInstallments = existing.stream().anyMatch(p -> !p.isBookingDeposit());
```
with:
```java
        boolean hasInstallments = existing.stream()
                .anyMatch(p -> !p.isBookingDeposit() && !p.isSecurityDeposit() && !p.isCharge());
```

- [ ] **Step 3: Compute per-installment charge total + label suffix** — inside `generateScheduleForLease`, after the `chequeAmounts` list is built (after line 127) and before the loop (line 146), add:

```java
        List<LeaseCharge> charges = leaseChargeRepository.findByLeaseId(lease.getId());
        BigDecimal perInstallmentCharge = charges.stream()
                .filter(c -> c.getFrequency() == ChargeFrequency.PER_INSTALLMENT)
                .map(c -> withVat(c.getAmount(), c.isVatApplicable()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String recurringLabel = charges.stream()
                .filter(c -> c.getFrequency() == ChargeFrequency.PER_INSTALLMENT)
                .map(LeaseCharge::getName)
                .collect(Collectors.joining(", "));
```
Add imports: `com.datagami.rentaxis.domain.entity.LeaseCharge`, `com.datagami.rentaxis.domain.entity.enums.ChargeFrequency` (Collectors already imported).

- [ ] **Step 4: Fold into each installment + drop the old bundle label** — replace the amount + label block (lines 155-175):

Replace:
```java
            BigDecimal amount = chequeAmounts.get(i);
```
with:
```java
            BigDecimal amount = chequeAmounts.get(i).add(perInstallmentCharge);
```
And replace the label block (lines 166-175):
```java
            String label = "RENT - " + ordinalOf(i + 1) + " INSTALLMENT";
            if (i == 0) {
                boolean hasBundledCharges = nz(lease.getAdminFee()).signum() > 0
                        || nz(lease.getDepositAmount()).signum() > 0
                        || nz(lease.getParkingRemoteFee()).signum() > 0;
                if (hasBundledCharges) {
                    label += "/ADMIN/SD/REMOTE";
                }
            }
            ps.setPurposeLabel(label);
```
with:
```java
            String label = "RENT - " + ordinalOf(i + 1) + " INSTALLMENT";
            if (!recurringLabel.isEmpty()) {
                label += " (+ " + recurringLabel + ")";
            }
            ps.setPurposeLabel(label);
```

- [ ] **Step 5: Write the generation test**

```java
package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.enums.ChargeFrequency;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class PaymentScheduleChargesTest {

    @Test
    void withVat_addsFivePercentWhenApplicable() {
        assertThat(PaymentScheduleService.withVat(new BigDecimal("100"), true))
                .isEqualByComparingTo("105.00");
    }

    @Test
    void withVat_returnsAmountWhenNotApplicable() {
        assertThat(PaymentScheduleService.withVat(new BigDecimal("100"), false))
                .isEqualByComparingTo("100");
    }
}
```

- [ ] **Step 6: Run the test**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.PaymentScheduleChargesTest'`
Expected: PASS (after Tasks 7–9 make the module compile; if the module doesn't yet compile, run after Task 9).

---

## Task 7: LeaseService — persist charges, create SD + one-time charge rows

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/LeaseService.java` (createLease, around 170-217; also `updateDraftLease` if it re-maps the same fields)

- [ ] **Step 1: Inject `LeaseChargeRepository`** into `LeaseService` (final field + constructor / `@RequiredArgsConstructor`).

- [ ] **Step 2: Persist charges from the DTO** — in `createLease`, after `savedLease` exists and before generating the schedule (before line 206), add:

```java
        if (dto.getCharges() != null) {
            for (LeaseChargeDTO c : dto.getCharges()) {
                LeaseCharge charge = new LeaseCharge();
                charge.setLease(savedLease);
                charge.setTenantId(savedLease.getTenantId()); // omit if BaseTenantEntity auto-sets
                charge.setName(c.getName());
                charge.setAmount(c.getAmount());
                charge.setVatApplicable(c.isVatApplicable());
                charge.setFrequency(c.getFrequency());
                leaseChargeRepository.save(charge);
            }
        }
```

- [ ] **Step 3: Create one-time charge rows + SD row** — immediately after Step 2 (still before `generateScheduleForLease`):

```java
        // One-time charges → their own schedule rows.
        if (dto.getCharges() != null) {
            for (LeaseChargeDTO c : dto.getCharges()) {
                if (c.getFrequency() != ChargeFrequency.ONE_TIME) continue;
                PaymentSchedule row = new PaymentSchedule();
                row.setLease(savedLease);
                row.setUnit(savedLease.getUnit());
                row.setProperty(savedLease.getUnit().getProperty());
                row.setInstallmentNumber(0);
                row.setDueDate(savedLease.getStartDate());
                row.setAmount(PaymentScheduleService.withVat(c.getAmount(), c.isVatApplicable()));
                row.setStatus(PaymentStatus.PENDING);
                row.setPaymentMethod(savedLease.getPaymentMethod() != null ? savedLease.getPaymentMethod().name() : "CHEQUE");
                row.setPurposeLabel(c.getName());
                row.setCharge(true);
                paymentScheduleRepository.save(row);
            }
        }

        // Security deposit → its own schedule row (refundable, never VAT).
        if (savedLease.getDepositAmount() != null && savedLease.getDepositAmount().signum() > 0) {
            PaymentSchedule sd = new PaymentSchedule();
            sd.setLease(savedLease);
            sd.setUnit(savedLease.getUnit());
            sd.setProperty(savedLease.getUnit().getProperty());
            sd.setInstallmentNumber(0);
            sd.setDueDate(savedLease.getStartDate());
            sd.setAmount(savedLease.getDepositAmount());
            sd.setStatus(PaymentStatus.PENDING);
            sd.setPaymentMethod(savedLease.getDepositPaymentMethod() != null ? savedLease.getDepositPaymentMethod().name() : "CHEQUE");
            sd.setPurposeLabel("SECURITY DEPOSIT");
            sd.setSecurityDeposit(true);
            paymentScheduleRepository.save(sd);
        }
```
Add imports: `com.datagami.rentaxis.domain.entity.LeaseCharge`, `com.datagami.rentaxis.api.dto.LeaseChargeDTO`, `com.datagami.rentaxis.domain.entity.enums.ChargeFrequency`, `com.datagami.rentaxis.core.service.PaymentScheduleService`.

(`row.setCharge(true)` / `sd.setSecurityDeposit(true)` are the Lombok setters for the boolean fields `isCharge`/`isSecurityDeposit`.)

- [ ] **Step 4: Remove legacy field mapping** — search `LeaseService.java` for `setAdminFee`, `setParkingRemoteFee`, `setAdminFeeVatApplicable`, `setSecurityDepositVatApplicable`, `setParkingRemoteVatApplicable` and delete those lines (in both create and update paths). In `mapToDTO`, replace the removed-field mapping with `dto.setCharges(...)` built from `leaseChargeRepository.findByLeaseId(lease.getId())` mapped to `LeaseChargeDTO`.

- [ ] **Step 5: If `updateDraftLease` regenerates the schedule** — ensure it also re-syncs `lease_charges` (delete existing for the lease, re-insert from DTO) and re-creates SD/one-time rows. If update does not touch charges in v1, note it and skip (DRAFT edits of charges then require recreate).

- [ ] **Step 6: Compile**

Run: `cd backend && ./gradlew compileJava 2>&1 | grep error: | head`
Expected: errors now only in `ContractGenerationService` / `PortfolioImportPersistService`.

---

## Task 8: Contract generation reads charges from `lease_charges`

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/ContractGenerationService.java:386-445`

- [ ] **Step 1: Inject `LeaseChargeRepository`** into `ContractGenerationService`.

- [ ] **Step 2: Replace the 4 hardcoded component rows** in Section 3 (lines ~386-432: rent, admin fee, security deposit, parking remote with per-component VAT) with: a Rent row (using `rentVatApplicable`), a Security Deposit row (no VAT), then iterate `leaseChargeRepository.findByLeaseId(lease.getId())` rendering one row per charge with `amount`, `vat_applicable ? amount*0.05 : 0`, total. Keep the existing `VAT_RATE = 0.05` and the table-row rendering helper; only the source of rows changes. Remove all references to `lease.getAdminFee()`, `lease.getParkingRemoteFee()`, `lease.isAdminFeeVatApplicable()`, `lease.isSecurityDepositVatApplicable()`, `lease.isParkingRemoteVatApplicable()`.

```java
// pseudocode shape for the rows list feeding the existing renderer:
List<ContractCharge> rows = new ArrayList<>();
rows.add(new ContractCharge("Annual Rent", totalRent, lease.isRentVatApplicable()));
if (lease.getDepositAmount() != null && lease.getDepositAmount().signum() > 0)
    rows.add(new ContractCharge("Security Deposit", lease.getDepositAmount(), false));
for (LeaseCharge c : leaseChargeRepository.findByLeaseId(lease.getId()))
    rows.add(new ContractCharge(c.getName(), c.getAmount(), c.isVatApplicable()));
// then render each row: amount, vat = applicable ? amount*VAT_RATE : 0, total = amount + vat
```
(Use the existing row-rendering code; `ContractCharge` can be a local record or just inline the three values.)

- [ ] **Step 3: Compile**

Run: `cd backend && ./gradlew compileJava 2>&1 | grep error: | head`
Expected: errors now only in `PortfolioImportPersistService`.

---

## Task 9: Portfolio import maps fee columns → charges

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/PortfolioImportPersistService.java:236-345`

- [ ] **Step 1:** Where the import currently parses `adminFee`/`parkingRemoteFee` (lines 236-239) and sets them on the lease, instead create `LeaseCharge` ONE_TIME rows (name `"Admin Fee"` / `"Parking / Remote"`, `vatApplicable` from the sheet's VAT intent if present else false) after the lease is saved. Remove the `lease.setAdminFee(...)`/`setParkingRemoteFee(...)` calls.

- [ ] **Step 2:** Remove the `/ADMIN/SD/REMOTE` label suffix logic (lines 341-345); imported installment labels become plain `RENT - Nth INSTALLMENT`. If the import should also emit an SD row, mirror Task 7 Step 3's SD block using the imported deposit amount.

- [ ] **Step 3: Compile the whole module**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run backend tests**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.*'`
Expected: PASS (including `PaymentScheduleChargesTest`). Fix any test that referenced the removed lease fields.

- [ ] **Step 5: Commit the backend slice**

```bash
git add backend/src
git commit -m "feat(leases): flexible lease charges + security-deposit schedule row (backend)"
```

---

## Task 10: Backend integration test — full generation

**Files:**
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/LeaseChargesGenerationIT.java` (create; use the project's existing integration-test base/`@SpringBootTest` pattern — copy the setup from an existing IT in that package)

- [ ] **Step 1: Write the test** — create a lease (rent 5000/mo × 6 months, deposit 15000) with charges: PER_INSTALLMENT "Maintenance" 200 vat=true, ONE_TIME "Admin Fee" 1000 vat=true. Assert:

```java
// after createLease(...) + generateScheduleForLease(...)
List<PaymentSchedule> rows = paymentScheduleRepository.findByLeaseId(leaseId);
// 6 rent installments, each = rentShare + 200*1.05 = rentShare + 210
assertThat(rows.stream().filter(r -> !r.isCharge() && !r.isSecurityDeposit() && !r.isBookingDeposit()))
    .allSatisfy(r -> assertThat(r.getPurposeLabel()).contains("(+ Maintenance)"));
// one-time admin fee row = 1000*1.05 = 1050.00
assertThat(rows.stream().filter(PaymentSchedule::isCharge))
    .singleElement()
    .satisfies(r -> { assertThat(r.getPurposeLabel()).isEqualTo("Admin Fee");
                      assertThat(r.getAmount()).isEqualByComparingTo("1050.00"); });
// security deposit row = 15000 (no VAT)
assertThat(rows.stream().filter(PaymentSchedule::isSecurityDeposit))
    .singleElement()
    .satisfies(r -> assertThat(r.getAmount()).isEqualByComparingTo("15000"));
```

- [ ] **Step 2: Run it**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.LeaseChargesGenerationIT'`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/test
git commit -m "test(leases): integration test for charges folding + SD/charge rows"
```

---

## Task 11: Frontend — wizard charges repeater

**Files:**
- Modify: `web/src/app/[locale]/dashboard/leases/LeaseWizard.tsx`

- [ ] **Step 1: Add a charge row type + update wizard state**

Near the top types, add:
```ts
type ChargeFrequency = "ONE_TIME" | "PER_INSTALLMENT";
type ChargeRow = { name: string; amount: number; vatApplicable: boolean; frequency: ChargeFrequency };
```
In the wizard `data` state (the object initialized around lines 71-85), remove `adminFee`, `parkingRemoteFee`, `adminFeeVatApplicable`, `securityDepositVatApplicable`, `parkingRemoteVatApplicable`; keep `rentVatApplicable`, `depositAmount`; add `charges: [] as ChargeRow[]`.

- [ ] **Step 2: Replace the "charges" step JSX** (lines 384-413) with the repeater:

```tsx
{currentStep.key === "charges" && (
    <div className="space-y-5">
        <div className="flex items-center justify-between">
            <h3 className="text-xs font-semibold">Other charges</h3>
            <button type="button"
                onClick={() => update({ charges: [...data.charges, { name: "", amount: 0, vatApplicable: isCommercial, frequency: "ONE_TIME" }] })}
                className="rounded border border-border px-2 py-1 text-xs">+ Add charge</button>
        </div>
        {data.charges.length === 0 && <p className="text-[11px] text-muted">No extra charges. Add admin fee, parking, maintenance, etc.</p>}
        {data.charges.map((c, i) => (
            <div key={i} className="grid grid-cols-1 md:grid-cols-[1fr_120px_120px_110px_32px] gap-2 items-end">
                <Field label="Name"><input type="text" value={c.name}
                    onChange={(e) => updateCharge(i, { name: e.target.value })}
                    className="w-full bg-input border border-border p-2 rounded-lg text-xs" /></Field>
                <Field label="Amount (AED)"><input type="number" min={0} step={0.01} value={c.amount}
                    onChange={(e) => updateCharge(i, { amount: Number(e.target.value) })}
                    className="w-full bg-input border border-border p-2 rounded-lg text-xs" /></Field>
                <Field label="Frequency">
                    <select value={c.frequency} onChange={(e) => updateCharge(i, { frequency: e.target.value as ChargeFrequency })}
                        className="w-full bg-input border border-border p-2 rounded-lg text-xs">
                        <option value="ONE_TIME">One-time</option>
                        <option value="PER_INSTALLMENT">Per installment</option>
                    </select>
                </Field>
                <VatToggle label="VAT" value={c.vatApplicable} onChange={(v) => updateCharge(i, { vatApplicable: v })} />
                <button type="button" onClick={() => update({ charges: data.charges.filter((_, j) => j !== i) })}
                    className="rounded border border-border p-2 text-xs">✕</button>
            </div>
        ))}
        <VatToggle label="Rent VAT" value={data.rentVatApplicable} onChange={(v) => update({ rentVatApplicable: v })} />
    </div>
)}
```
Add the helper near `update`:
```tsx
const updateCharge = (i: number, patch: Partial<ChargeRow>) =>
    update({ charges: data.charges.map((c, j) => (j === i ? { ...c, ...patch } : c)) });
```

- [ ] **Step 3: Update validation + submit body** — in the charges-step validation (lines ~163-174) replace the admin/parking `>= 0` checks with: each charge must have a non-blank `name` and `amount >= 0`. In the submit body (lines ~229-234 region), remove `adminFee`/`parkingRemoteFee`/the 3 dropped VAT flags and add `charges: data.charges`.

- [ ] **Step 4: Remove the commercial-VAT auto-set for dropped flags** (lines ~146-149) — keep only `rentVatApplicable`.

- [ ] **Step 5: Build**

Run: `cd web && npx tsc --noEmit 2>&1 | grep LeaseWizard | head`
Expected: no errors referencing LeaseWizard.

- [ ] **Step 6: Commit**

```bash
git add web/src/app/\[locale\]/dashboard/leases/LeaseWizard.tsx
git commit -m "feat(leases): wizard 'Other charges' repeater replaces fixed fee inputs"
```

---

## Task 12: Frontend — LeaseMetadataEditor charges + PaymentScheduleEditor markers

**Files:**
- Modify: `web/src/app/[locale]/dashboard/leases/LeaseMetadataEditor.tsx`
- Modify: `web/src/app/[locale]/dashboard/leases/PaymentScheduleEditor.tsx`

- [ ] **Step 1: LeaseMetadataEditor** — mirror Task 11: remove the 4 fee/VAT fields from `Lease` type, `FormShape`, `leaseToForm`, `onPickUnit`, the JSX inputs, and the submit body; add a `charges: ChargeRow[]` field seeded from `lease.charges ?? []` and the same repeater UI + `updateCharge` helper. Keep `rentVatApplicable` and `depositAmount`.

- [ ] **Step 2: PaymentScheduleEditor** — add to `ScheduleRow` (line 34):
```ts
    isSecurityDeposit?: boolean;
    isCharge?: boolean;
```
Update the sort (lines 98-102) so any non-rent row sorts last:
```ts
.sort((a, b) => {
    const aNon = !!a.isBookingDeposit || !!a.isSecurityDeposit || !!a.isCharge;
    const bNon = !!b.isBookingDeposit || !!b.isSecurityDeposit || !!b.isCharge;
    if (aNon !== bNon) return aNon ? 1 : -1;
    return (a.installmentNumber ?? 0) - (b.installmentNumber ?? 0);
});
```
Update the # cell (line ~271) marker:
```tsx
{r.isBookingDeposit ? "B" : r.isSecurityDeposit ? "S" : r.isCharge ? "C" : r.installmentNumber}
```
And the purpose fallback (line ~272) to show `r.purposeLabel || (r.isSecurityDeposit ? "Security Deposit" : r.isBookingDeposit ? "Booking Deposit" : "")`.

- [ ] **Step 3: Build**

Run: `cd web && npx tsc --noEmit 2>&1 | grep -E "LeaseMetadataEditor|PaymentScheduleEditor" | head`
Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git add web/src/app/\[locale\]/dashboard/leases/LeaseMetadataEditor.tsx web/src/app/\[locale\]/dashboard/leases/PaymentScheduleEditor.tsx
git commit -m "feat(leases): charges editor + S/C row markers in schedule editor"
```

---

## Task 13: Manual verification

- [ ] **Step 1:** Ensure `fix/payment-schedule-editor-lease-endpoint` is merged into this branch (or cherry-picked) so rows render.
- [ ] **Step 2:** Run backend (`./gradlew bootRun`) + web (`npm run dev`). Create a lease via the wizard: rent 5000/mo × 6 months, deposit 15000, charges = "Maintenance" 200 PER_INSTALLMENT VAT-on, "Admin Fee" 1000 ONE_TIME VAT-on.
- [ ] **Step 3:** On Schedule & finalize, confirm: 6 rent rows each `RENT - Nth INSTALLMENT (+ Maintenance)` with amount = rent share + 210; one `C` "Admin Fee" row = 1050.00; one `S` "Security Deposit" row = 15000; rows sorted with rent first, non-rent last.
- [ ] **Step 4:** Generate the contract PDF; confirm Section 3 lists Rent, Security Deposit (no VAT), Maintenance, Admin Fee with correct per-line VAT.

---

## Self-Review notes (author)
- Spec coverage: table + flags migration (T1), entity/enum/repo (T2-3), DTOs (T4), VAT+mapToDTO (T5), folding+relabel+preservation (T6), persistence+SD/one-time rows (T7), contract (T8), import (T9), tests (T10), web (T11-12), manual (T13). All spec sections covered.
- Asymmetry documented: rent VAT inclusive (unchanged), charges VAT additive (`withVat`).
- Risk: `updateDraftLease` charge re-sync (T7 Step 5) — confirm whether DRAFT edits must mutate charges; if yes, implement delete+reinsert.
