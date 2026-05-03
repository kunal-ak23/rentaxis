# Cheque-Failure Penalties — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Reinforce cheque-first rent collection by adding configurable fixed-fee penalties on cheque failures (bounce / signature mismatch / account closed), per-day accrual on the unpaid fine itself, admin-recorded clearance via offline payment methods, and renter-side visibility + notifications.

**Architecture:** Three failure reasons drive different fine amounts (org-level config, optional per-property override). On the existing `BOUNCED` transition we now require a reason and create a `CHEQUE_FAILURE` `PaymentPenalty`. The unpaid penalty accrues per-day after a configured grace period. A new `PenaltyPayment` journal entity records admin-side receipts (bank transfer / cheque / cash); each receipt posts a `FinancialTransaction` of new nature `PENALTY_INCOME`. Renter portals are read-only.

**Tech Stack:** Java 21 + Spring Boot 4 + Spring Data JPA + Liquibase + PostgreSQL 16 (existing). Apache POI 5.3.0, Razorpay (untouched). Web: Next.js 16 + TypeScript 5 + Tailwind 4 + next-intl. Mobile: Flutter (Manager + Renter apps with shared `rentaxis_core`). Tests: JUnit 5 + Mockito + Testcontainers (already added in the bulk-import branch).

**Source design:** `docs/plans/2026-05-03-cheque-failure-penalties-design.md`

**Reference branch:** `feat/cheque-failure-penalties` (this branch). Builds atop `feat/bulk-import-payment-schedule` after that one merges. If the bulk-import branch is still open, target main and rebase later.

---

## Milestone 0 — Setup

### Task 0.1: Read the design + map the existing penalty code

**Files (read-only):**
- `docs/plans/2026-05-03-cheque-failure-penalties-design.md`
- `backend/src/main/java/com/datagami/rentaxis/domain/entity/PaymentPenalty.java`
- `backend/src/main/java/com/datagami/rentaxis/domain/entity/PaymentSchedule.java`
- `backend/src/main/java/com/datagami/rentaxis/domain/entity/RentCollectionSettings.java`
- `backend/src/main/java/com/datagami/rentaxis/domain/entity/OrgSettings.java`
- `backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/PaymentStatus.java`
- `backend/src/main/java/com/datagami/rentaxis/core/service/PenaltyService.java`
- `backend/src/main/java/com/datagami/rentaxis/core/service/PenaltyCalculationService.java`
- `backend/src/main/java/com/datagami/rentaxis/core/service/PaymentScheduleService.java` (focus on existing markBounced + replaced-by flow around line 397)
- `backend/src/main/java/com/datagami/rentaxis/core/service/NotificationService.java` (focus on PAYMENT_BOUNCED)
- `backend/src/main/java/com/datagami/rentaxis/core/service/AccountMappingService.java` (focus on `CHEQUE_BOUNCED` mapping)

**Step 1:** Confirm understanding of:
- `PaymentStatus.BOUNCED` already exists; `replacedBy` already supports replacement-cheque flow.
- `PaymentPenalty.penalty_type` is currently a `String`. We will keep it as String for now, just add a new value "CHEQUE_FAILURE".
- `RentCollectionSettings` is per-property; no org-level penalty config exists yet.
- `PenaltyService.processLeaseOverduePayments` is the existing daily cron path; we will add a sibling `processChequeFailureAccruals` invoked from the same daily entry point.

**Step 2:** Run existing tests to baseline:
```bash
cd backend && ./gradlew test --tests "*Penalty*" --tests "*PaymentSchedule*"
```
Expected: BUILD SUCCESSFUL.

**No commit at this task.**

---

## Milestone 1 — Database migrations

### Task 1.1: Liquibase changeset 47 — schema additions

**Files:**
- Create: `backend/src/main/resources/db/changelog/changesets/47-cheque-failure-penalties.yaml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml` (append the new include)

**Step 1: Write the changeset**

```yaml
databaseChangeLog:
  - changeSet:
      id: 47-cheque-failure-penalties
      author: rentaxis-system
      changes:
        # 1. Add cheque_failure_reason to payment_schedules
        - addColumn:
            tableName: payment_schedules
            columns:
              - column:
                  name: cheque_failure_reason
                  type: varchar(30)
                  constraints:
                    nullable: true

        # 2. Add 5 nullable per-property override fields to rent_collection_settings
        - addColumn:
            tableName: rent_collection_settings
            columns:
              - column: { name: fine_bounce_amount,             type: numeric(12,2), constraints: { nullable: true } }
              - column: { name: fine_signature_mismatch_amount, type: numeric(12,2), constraints: { nullable: true } }
              - column: { name: fine_account_closed_amount,     type: numeric(12,2), constraints: { nullable: true } }
              - column: { name: fine_grace_days,                type: int,           constraints: { nullable: true } }
              - column: { name: fine_per_day_rate,              type: numeric(12,2), constraints: { nullable: true } }

        # 3. Add fineGraceDays / finePerDayRate snapshot + cleared_at to payment_penalties
        - addColumn:
            tableName: payment_penalties
            columns:
              - column: { name: fine_grace_days,    type: int,           constraints: { nullable: true } }
              - column: { name: fine_per_day_rate,  type: numeric(12,2), constraints: { nullable: true } }
              - column: { name: cleared_at,         type: timestamp,     constraints: { nullable: true } }

        # 4. Create landlord_org_fine_settings
        - createTable:
            tableName: landlord_org_fine_settings
            columns:
              - column: { name: id,              type: uuid, constraints: { primaryKey: true,  nullable: false } }
              - column: { name: tenant_id,       type: uuid, constraints: { nullable: false } }
              - column: { name: landlord_org_id, type: uuid, constraints: { nullable: false, unique: true, foreignKeyName: fk_lofs_landlord_org, references: "landlord_org(id)" } }
              - column: { name: fine_bounce_amount,             type: numeric(12,2), constraints: { nullable: false }, defaultValueNumeric: 500 }
              - column: { name: fine_signature_mismatch_amount, type: numeric(12,2), constraints: { nullable: false }, defaultValueNumeric: 500 }
              - column: { name: fine_account_closed_amount,     type: numeric(12,2), constraints: { nullable: false }, defaultValueNumeric: 1000 }
              - column: { name: fine_grace_days,                type: int,           constraints: { nullable: false }, defaultValueNumeric: 7 }
              - column: { name: fine_per_day_rate,              type: numeric(12,2), constraints: { nullable: false }, defaultValueNumeric: 25 }
              - column: { name: created_at, type: timestamp, defaultValueComputed: CURRENT_TIMESTAMP }
              - column: { name: updated_at, type: timestamp, defaultValueComputed: CURRENT_TIMESTAMP }
        - createIndex:
            tableName: landlord_org_fine_settings
            indexName: idx_lofs_tenant_id
            columns:
              - column: { name: tenant_id }

        # 5. Create penalty_payments
        - createTable:
            tableName: penalty_payments
            columns:
              - column: { name: id,                       type: uuid, constraints: { primaryKey: true,  nullable: false } }
              - column: { name: tenant_id,                type: uuid, constraints: { nullable: false } }
              - column: { name: payment_penalty_id,       type: uuid, constraints: { nullable: false, foreignKeyName: fk_pp_payment_penalty, references: "payment_penalties(id)", deleteCascade: true } }
              - column: { name: amount,                   type: numeric(12,2), constraints: { nullable: false } }
              - column: { name: payment_method,           type: varchar(20),   constraints: { nullable: false } }
              - column: { name: payment_reference,        type: varchar(255),  constraints: { nullable: true  } }
              - column: { name: received_at,              type: date,          constraints: { nullable: false } }
              - column: { name: received_by,              type: uuid,          constraints: { nullable: false } }
              - column: { name: notes,                    type: text,          constraints: { nullable: true  } }
              - column: { name: financial_transaction_id, type: uuid,          constraints: { nullable: true,  foreignKeyName: fk_pp_financial_tx, references: "financial_transactions(id)" } }
              - column: { name: created_at,               type: timestamp, defaultValueComputed: CURRENT_TIMESTAMP }
        - createIndex:
            tableName: penalty_payments
            indexName: idx_pp_penalty_received
            columns:
              - column: { name: payment_penalty_id }
              - column: { name: received_at }
        - createIndex:
            tableName: penalty_payments
            indexName: idx_pp_tenant_id
            columns:
              - column: { name: tenant_id }
        - sql:
            sql: ALTER TABLE penalty_payments ADD CONSTRAINT chk_pp_amount_positive CHECK (amount > 0)
```

**Step 2:** Append the include to `db.changelog-master.yaml`:

```yaml
  - include: { file: db/changelog/changesets/47-cheque-failure-penalties.yaml }
```

**Step 3: Run Liquibase update against a fresh DB**

```bash
cd backend && ./gradlew test --tests "RentAxisApplicationTests"  # Boots full context, runs Liquibase
```
Expected: BUILD SUCCESSFUL. The Testcontainers Postgres comes up and Liquibase applies all changesets including 47.

**Step 4: Commit**

```bash
git add backend/src/main/resources/db/changelog/changesets/47-cheque-failure-penalties.yaml \
        backend/src/main/resources/db/changelog/db.changelog-master.yaml
git commit -m "feat(db): add cheque-failure-penalty schema (changeset 47)"
```

### Task 1.2: Liquibase changeset 48 — backfill default org fine settings

**Files:**
- Create: `backend/src/main/resources/db/changelog/changesets/48-default-fine-settings.yaml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml`

**Step 1: Write the changeset (idempotent INSERT…SELECT)**

```yaml
databaseChangeLog:
  - changeSet:
      id: 48-default-fine-settings
      author: rentaxis-system
      changes:
        - sql:
            sql: |
              INSERT INTO landlord_org_fine_settings (
                id, tenant_id, landlord_org_id,
                fine_bounce_amount, fine_signature_mismatch_amount, fine_account_closed_amount,
                fine_grace_days, fine_per_day_rate
              )
              SELECT
                gen_random_uuid(), lo.id, lo.id, 500, 500, 1000, 7, 25
              FROM landlord_org lo
              WHERE NOT EXISTS (
                SELECT 1 FROM landlord_org_fine_settings lofs
                WHERE lofs.landlord_org_id = lo.id
              );
```

(Postgres-only; matches the project's existing PG-only Liquibase changesets. `gen_random_uuid()` is built into PG 16; no extension toggle needed.)

**Step 2:** Append the include.

**Step 3:** Re-run `RentAxisApplicationTests` to verify the changeset applies. Expected: BUILD SUCCESSFUL.

**Step 4: Commit**

```bash
git add backend/src/main/resources/db/changelog/changesets/48-default-fine-settings.yaml \
        backend/src/main/resources/db/changelog/db.changelog-master.yaml
git commit -m "feat(db): backfill default fine settings per landlord_org (changeset 48)"
```

---

## Milestone 2 — Enums + entity additions

### Task 2.1: Add `ChequeFailureReason` enum

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/ChequeFailureReason.java`

**Step 1:**

```java
package com.datagami.rentaxis.domain.entity.enums;

public enum ChequeFailureReason {
    BOUNCE,
    SIGNATURE_MISMATCH,
    ACCOUNT_CLOSED;
}
```

**Step 2:** Compile.
```bash
cd backend && ./gradlew compileJava -q
```
Expected: BUILD SUCCESSFUL.

**Step 3:** Commit.
```bash
git add backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/ChequeFailureReason.java
git commit -m "feat: add ChequeFailureReason enum (bounce/signature-mismatch/account-closed)"
```

### Task 2.2: Add `PENALTY_INCOME` to `TransactionNature`

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/TransactionNature.java`

**Step 1:** Add `PENALTY_INCOME` after the existing values.

**Step 2:** Compile + commit.
```bash
git add backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/TransactionNature.java
git commit -m "feat: add PENALTY_INCOME TransactionNature for cheque-failure fines"
```

### Task 2.3: Add `failureReason` to `PaymentSchedule`

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/domain/entity/PaymentSchedule.java`

**Step 1:** Add the field next to the cheque-related columns:

```java
@Enumerated(EnumType.STRING)
@Column(name = "cheque_failure_reason", length = 30)
private ChequeFailureReason failureReason;
```

Plus the import.

**Step 2:** Compile + commit.
```bash
git add backend/src/main/java/com/datagami/rentaxis/domain/entity/PaymentSchedule.java
git commit -m "feat: PaymentSchedule.failureReason column for cheque-failure tracking"
```

### Task 2.4: Add snapshot + cleared_at columns to `PaymentPenalty`

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/domain/entity/PaymentPenalty.java`

**Step 1:** Add three new fields:

```java
@Column(name = "fine_grace_days")
private Integer fineGraceDays;

@Column(name = "fine_per_day_rate")
private BigDecimal finePerDayRate;

@Column(name = "cleared_at")
private LocalDateTime clearedAt;
```

**Step 2:** Compile + commit.
```bash
git add backend/src/main/java/com/datagami/rentaxis/domain/entity/PaymentPenalty.java
git commit -m "feat: PaymentPenalty snapshots (fineGraceDays, finePerDayRate, clearedAt)"
```

### Task 2.5: Add 5 nullable override fields to `RentCollectionSettings`

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/domain/entity/RentCollectionSettings.java`

**Step 1:** Add fields:

```java
@Column(name = "fine_bounce_amount")
private BigDecimal fineBounceAmount;

@Column(name = "fine_signature_mismatch_amount")
private BigDecimal fineSignatureMismatchAmount;

@Column(name = "fine_account_closed_amount")
private BigDecimal fineAccountClosedAmount;

@Column(name = "fine_grace_days")
private Integer fineGraceDays;

@Column(name = "fine_per_day_rate")
private BigDecimal finePerDayRate;
```

**Step 2:** Compile + commit.
```bash
git add backend/src/main/java/com/datagami/rentaxis/domain/entity/RentCollectionSettings.java
git commit -m "feat: RentCollectionSettings per-property fine override fields (5 nullable)"
```

---

## Milestone 3 — New entities + repositories

### Task 3.1: Create `LandlordOrgFineSettings` entity + repository

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/LandlordOrgFineSettings.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/repository/LandlordOrgFineSettingsRepository.java`

**Step 1: Write the entity.**

```java
package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "landlord_org_fine_settings")
@Getter
@Setter
public class LandlordOrgFineSettings extends BaseTenantEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "landlord_org_id", nullable = false, unique = true)
    private UUID landlordOrgId;

    @Column(name = "fine_bounce_amount", nullable = false)
    private BigDecimal fineBounceAmount;

    @Column(name = "fine_signature_mismatch_amount", nullable = false)
    private BigDecimal fineSignatureMismatchAmount;

    @Column(name = "fine_account_closed_amount", nullable = false)
    private BigDecimal fineAccountClosedAmount;

    @Column(name = "fine_grace_days", nullable = false)
    private Integer fineGraceDays;

    @Column(name = "fine_per_day_rate", nullable = false)
    private BigDecimal finePerDayRate;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
}
```

**Step 2: Repository.**

```java
package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.LandlordOrgFineSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LandlordOrgFineSettingsRepository extends JpaRepository<LandlordOrgFineSettings, UUID> {
    Optional<LandlordOrgFineSettings> findByLandlordOrgId(UUID landlordOrgId);
}
```

**Step 3:** Compile + commit.
```bash
git add backend/src/main/java/com/datagami/rentaxis/domain/entity/LandlordOrgFineSettings.java \
        backend/src/main/java/com/datagami/rentaxis/domain/repository/LandlordOrgFineSettingsRepository.java
git commit -m "feat: LandlordOrgFineSettings entity + repository"
```

### Task 3.2: Create `PenaltyPayment` entity + repository

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/PenaltyPayment.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/repository/PenaltyPaymentRepository.java`

**Step 1: Entity.**

```java
package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "penalty_payments")
@Getter
@Setter
public class PenaltyPayment extends BaseTenantEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "payment_penalty_id", nullable = false)
    private UUID paymentPenaltyId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "payment_method", length = 20, nullable = false)
    private String paymentMethod;       // BANK_TRANSFER / CHEQUE / CASH

    @Column(name = "payment_reference", length = 255)
    private String paymentReference;

    @Column(name = "received_at", nullable = false)
    private LocalDate receivedAt;

    @Column(name = "received_by", nullable = false)
    private UUID receivedBy;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "financial_transaction_id")
    private UUID financialTransactionId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
```

**Step 2: Repository.**

```java
package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.PenaltyPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PenaltyPaymentRepository extends JpaRepository<PenaltyPayment, UUID> {
    List<PenaltyPayment> findByPaymentPenaltyIdOrderByReceivedAtAscCreatedAtAsc(UUID penaltyId);
}
```

**Step 3:** Compile + commit.
```bash
git add backend/src/main/java/com/datagami/rentaxis/domain/entity/PenaltyPayment.java \
        backend/src/main/java/com/datagami/rentaxis/domain/repository/PenaltyPaymentRepository.java
git commit -m "feat: PenaltyPayment entity + repository (payment journal)"
```

---

## Milestone 4 — `FineConfigResolver`

### Task 4.1: Failing test for org-only resolution

**Files:**
- Create: `backend/src/test/java/com/datagami/rentaxis/core/service/FineConfigResolverTest.java`

**Step 1:** Write tests:

```java
package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.LandlordOrgFineSettings;
import com.datagami.rentaxis.domain.entity.RentCollectionSettings;
import com.datagami.rentaxis.domain.repository.LandlordOrgFineSettingsRepository;
import com.datagami.rentaxis.domain.repository.RentCollectionSettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FineConfigResolverTest {

    @Mock RentCollectionSettingsRepository rcsRepo;
    @Mock LandlordOrgFineSettingsRepository orgRepo;
    @InjectMocks FineConfigResolver resolver;

    @Test
    void resolve_orgOnly_returnsOrgValuesAndSourceOrg() {
        UUID propertyId = UUID.randomUUID();
        UUID tenantId   = UUID.randomUUID();

        LandlordOrgFineSettings org = new LandlordOrgFineSettings();
        org.setFineBounceAmount(new BigDecimal("500"));
        org.setFineSignatureMismatchAmount(new BigDecimal("500"));
        org.setFineAccountClosedAmount(new BigDecimal("1000"));
        org.setFineGraceDays(7);
        org.setFinePerDayRate(new BigDecimal("25"));

        when(rcsRepo.findByPropertyId(propertyId)).thenReturn(Optional.empty());
        when(orgRepo.findByLandlordOrgId(tenantId)).thenReturn(Optional.of(org));

        FineConfig cfg = resolver.resolve(propertyId, tenantId);
        assertThat(cfg.bounceAmount()).isEqualByComparingTo("500");
        assertThat(cfg.accountClosedAmount()).isEqualByComparingTo("1000");
        assertThat(cfg.graceDays()).isEqualTo(7);
        assertThat(cfg.source()).isEqualTo(FineConfig.Source.ORG);
    }

    @Test
    void resolve_propertyOverridesField_returnsPropertyValueButOrgForOthers() {
        UUID propertyId = UUID.randomUUID();
        UUID tenantId   = UUID.randomUUID();

        RentCollectionSettings rcs = new RentCollectionSettings();
        rcs.setFineBounceAmount(new BigDecimal("750"));      // override only this one
        // others remain null

        LandlordOrgFineSettings org = orgWithDefaults();

        when(rcsRepo.findByPropertyId(propertyId)).thenReturn(Optional.of(rcs));
        when(orgRepo.findByLandlordOrgId(tenantId)).thenReturn(Optional.of(org));

        FineConfig cfg = resolver.resolve(propertyId, tenantId);
        assertThat(cfg.bounceAmount()).isEqualByComparingTo("750");          // overridden
        assertThat(cfg.signatureMismatchAmount()).isEqualByComparingTo("500"); // org default
        assertThat(cfg.source()).isEqualTo(FineConfig.Source.PROPERTY);      // any override → PROPERTY
    }

    @Test
    void resolve_orgMissing_createsDefaultsAndReturnsOrg() {
        UUID propertyId = UUID.randomUUID();
        UUID tenantId   = UUID.randomUUID();

        when(rcsRepo.findByPropertyId(propertyId)).thenReturn(Optional.empty());
        when(orgRepo.findByLandlordOrgId(tenantId)).thenReturn(Optional.empty());
        // resolver upserts defaults via orgRepo.save — verified in repo test, here just no-throw
        FineConfig cfg = resolver.resolve(propertyId, tenantId);
        assertThat(cfg.bounceAmount()).isEqualByComparingTo("500");
        assertThat(cfg.accountClosedAmount()).isEqualByComparingTo("1000");
    }

    private LandlordOrgFineSettings orgWithDefaults() {
        LandlordOrgFineSettings o = new LandlordOrgFineSettings();
        o.setFineBounceAmount(new BigDecimal("500"));
        o.setFineSignatureMismatchAmount(new BigDecimal("500"));
        o.setFineAccountClosedAmount(new BigDecimal("1000"));
        o.setFineGraceDays(7);
        o.setFinePerDayRate(new BigDecimal("25"));
        return o;
    }
}
```

**Step 2:** Run, verify failure (`FineConfigResolver` and `FineConfig` don't exist yet).

```bash
cd backend && ./gradlew test --tests "FineConfigResolverTest"
```
Expected: COMPILATION FAILED.

### Task 4.2: Implement `FineConfig` record + `FineConfigResolver`

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/FineConfig.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/FineConfigResolver.java`

**Step 1:** `FineConfig` record.

```java
package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;

import java.math.BigDecimal;

public record FineConfig(
        BigDecimal bounceAmount,
        BigDecimal signatureMismatchAmount,
        BigDecimal accountClosedAmount,
        Integer graceDays,
        BigDecimal perDayRate,
        Source source
) {
    public enum Source { ORG, PROPERTY }

    public BigDecimal amountFor(ChequeFailureReason reason) {
        return switch (reason) {
            case BOUNCE             -> bounceAmount;
            case SIGNATURE_MISMATCH -> signatureMismatchAmount;
            case ACCOUNT_CLOSED     -> accountClosedAmount;
        };
    }
}
```

**Step 2:** `FineConfigResolver` (commit and code together):

```java
package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.LandlordOrgFineSettings;
import com.datagami.rentaxis.domain.entity.RentCollectionSettings;
import com.datagami.rentaxis.domain.repository.LandlordOrgFineSettingsRepository;
import com.datagami.rentaxis.domain.repository.RentCollectionSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FineConfigResolver {

    private static final BigDecimal DEFAULT_BOUNCE = new BigDecimal("500");
    private static final BigDecimal DEFAULT_SIGN   = new BigDecimal("500");
    private static final BigDecimal DEFAULT_CLOSED = new BigDecimal("1000");
    private static final int        DEFAULT_GRACE = 7;
    private static final BigDecimal DEFAULT_RATE  = new BigDecimal("25");

    private final RentCollectionSettingsRepository rcsRepo;
    private final LandlordOrgFineSettingsRepository orgRepo;

    public FineConfig resolve(UUID propertyId, UUID tenantId) {
        LandlordOrgFineSettings org = orgRepo.findByLandlordOrgId(tenantId)
                .orElseGet(() -> upsertDefault(tenantId));

        RentCollectionSettings rcs = rcsRepo.findByPropertyId(propertyId).orElse(null);

        BigDecimal bounce  = coalesce(rcs == null ? null : rcs.getFineBounceAmount(),             org.getFineBounceAmount());
        BigDecimal sign    = coalesce(rcs == null ? null : rcs.getFineSignatureMismatchAmount(),  org.getFineSignatureMismatchAmount());
        BigDecimal closed  = coalesce(rcs == null ? null : rcs.getFineAccountClosedAmount(),      org.getFineAccountClosedAmount());
        Integer    grace   = coalesce(rcs == null ? null : rcs.getFineGraceDays(),                org.getFineGraceDays());
        BigDecimal rate    = coalesce(rcs == null ? null : rcs.getFinePerDayRate(),               org.getFinePerDayRate());

        boolean overridden = rcs != null && (
                rcs.getFineBounceAmount() != null
             || rcs.getFineSignatureMismatchAmount() != null
             || rcs.getFineAccountClosedAmount() != null
             || rcs.getFineGraceDays() != null
             || rcs.getFinePerDayRate() != null);

        return new FineConfig(bounce, sign, closed, grace, rate,
                overridden ? FineConfig.Source.PROPERTY : FineConfig.Source.ORG);
    }

    @Transactional
    LandlordOrgFineSettings upsertDefault(UUID tenantId) {
        LandlordOrgFineSettings o = new LandlordOrgFineSettings();
        o.setLandlordOrgId(tenantId);
        o.setFineBounceAmount(DEFAULT_BOUNCE);
        o.setFineSignatureMismatchAmount(DEFAULT_SIGN);
        o.setFineAccountClosedAmount(DEFAULT_CLOSED);
        o.setFineGraceDays(DEFAULT_GRACE);
        o.setFinePerDayRate(DEFAULT_RATE);
        return orgRepo.save(o);
    }

    private static <T> T coalesce(T a, T b) { return a != null ? a : b; }
}
```

**Step 3:** Run tests:
```bash
./gradlew test --tests "FineConfigResolverTest"
```
Expected: PASS.

**Step 4: Commit.**
```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/FineConfig.java \
        backend/src/main/java/com/datagami/rentaxis/core/service/FineConfigResolver.java \
        backend/src/test/java/com/datagami/rentaxis/core/service/FineConfigResolverTest.java
git commit -m "feat: FineConfigResolver — org default + per-property override precedence"
```

---

## Milestone 5 — Mark-failed flow

### Task 5.1: Failing tests for `markFailed`

**Files:**
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/PaymentScheduleServiceMarkFailedTest.java` (new file). The existing `PaymentScheduleService` mark-bounced behavior is tested elsewhere; here we add tests for the new reason-aware behavior.

**Step 1: Write tests** covering:
- `markFailed_bounce_createsPenaltyWith500` — calls `paymentScheduleService.markFailed(scheduleId, ChequeFailureReason.BOUNCE, "notes")`. Assert: schedule.status=BOUNCED, schedule.failureReason=BOUNCE, one PaymentPenalty saved with penaltyType="CHEQUE_FAILURE" and penaltyAmount=500, snapshots set, `lastCalculatedAt=now`. Notification "PAYMENT_BOUNCED" sent. `LeaseEvent` "PAYMENT_FAILED_BOUNCE" written.
- `markFailed_signatureMismatch_createsPenaltyWith500_andUsesPropertyOverrideWhenSet` — mock `FineConfigResolver` to return PROPERTY-source 750; assert penaltyAmount=750.
- `markFailed_accountClosed_createsPenaltyWith1000`.
- `markFailed_alreadyBounced_throws` — reject double-marking.
- `markFailed_clearedSchedule_throws`.
- `markFailed_invalidStatusTransition_isRejected` — start from CANCELLED → throw.
- `markFailed_postsCheueBouncedFinancialTransaction` — verify FinancialTransactionService called with TransactionNature.CHEQUE_BOUNCED.
- `markFailed_firesPenaltyIncurredNotification`.

Use Mockito to stub:
- `FineConfigResolver` returning a fixed config
- `PaymentScheduleRepository`, `PaymentPenaltyRepository`
- `NotificationService`
- `FinancialTransactionService`
- `LeaseEventRepository`

**Step 2:** Run, expect FAIL (method `markFailed` does not exist — current method is `markBounced`).

### Task 5.2: Implement `markFailed`

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/PaymentScheduleService.java`

**Step 1:** Replace the existing `markBounced(UUID id)` with `markFailed(UUID id, ChequeFailureReason reason, String notes)`. Internal logic:

```java
@Transactional
public PaymentSchedule markFailed(UUID scheduleId, ChequeFailureReason reason, String notes) {
    PaymentSchedule s = paymentScheduleRepository.findById(scheduleId)
            .orElseThrow(() -> new NotFoundException("Payment schedule " + scheduleId + " not found"));

    Set<PaymentStatus> markable = EnumSet.of(
            PaymentStatus.PENDING, PaymentStatus.OVERDUE,
            PaymentStatus.COLLECTED, PaymentStatus.DEPOSITED);
    if (!markable.contains(s.getStatus())) {
        throw new BusinessRuleViolationException(
                "Cannot mark failed: schedule is in status " + s.getStatus());
    }

    s.setStatus(PaymentStatus.BOUNCED);
    s.setFailureReason(reason);
    s.setStatusChangedAt(Instant.now());
    if (notes != null && !notes.isBlank()) {
        s.setNotes(notes);
    }
    paymentScheduleRepository.save(s);

    UUID tenantId = TenantContextHolder.getTenantId();
    FineConfig cfg = fineConfigResolver.resolve(s.getProperty().getId(), tenantId);

    PaymentPenalty p = new PaymentPenalty();
    p.setPaymentScheduleId(s.getId());
    p.setLeaseId(s.getLease().getId());
    p.setPenaltyType("CHEQUE_FAILURE");
    p.setPenaltyAmount(cfg.amountFor(reason));
    p.setDaysOverdue(0);
    p.setFineGraceDays(cfg.graceDays());
    p.setFinePerDayRate(cfg.perDayRate());
    p.setLastCalculatedAt(LocalDateTime.now());
    paymentPenaltyRepository.save(p);

    financialTransactionService.recordChequeBounce(s);  // existing helper
    notificationService.sendChequeBounced(s, reason, p.getPenaltyAmount());
    notificationService.sendPenaltyIncurred(s, p);
    leaseEventRepository.save(LeaseEvent.failed(s, reason, currentUserId()));

    return s;
}
```

(Adapt to existing helper signatures. Add a back-compat `markBounced` that calls `markFailed(id, ChequeFailureReason.BOUNCE, null)` and is annotated `@Deprecated` for any remaining internal call sites — delete after the controller is migrated in 5.3.)

**Step 2:** Inject `FineConfigResolver` via the constructor.

**Step 3:** Run tests:
```bash
./gradlew test --tests "PaymentScheduleServiceMarkFailedTest"
```
Expected: PASS.

**Step 4: Commit.**
```bash
git commit -m "feat: PaymentScheduleService.markFailed(reason, notes) — fires fine + notifications"
```

### Task 5.3: Controller endpoint for `mark-failed`

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/PaymentScheduleController.java` (or wherever the existing `mark-bounced` endpoint lives — grep for it)

**Step 1:** Failing controller test (with MockMvc) for `POST /api/v1/payments/{id}/mark-failed`:
- 200 with `failureReason=BOUNCE` body returns updated schedule + new penalty.
- 400 if `failureReason` is missing or invalid.
- 403 for RENTER role.

**Step 2:** Implement endpoint:

```java
@PostMapping("/{id}/mark-failed")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
public ResponseEntity<MarkFailedResponseDTO> markFailed(
        @PathVariable UUID id,
        @Valid @RequestBody MarkFailedRequestDTO body) {
    PaymentSchedule s = paymentScheduleService.markFailed(id, body.failureReason(), body.notes());
    PaymentPenalty p = paymentPenaltyRepository
            .findFirstByPaymentScheduleIdOrderByCreatedAtDesc(s.getId())
            .orElseThrow();
    return ResponseEntity.ok(new MarkFailedResponseDTO(toDto(s), toDto(p)));
}
```

DTOs:
- `MarkFailedRequestDTO(@NotNull ChequeFailureReason failureReason, String notes)`
- `MarkFailedResponseDTO(PaymentScheduleDTO schedule, PenaltyDTO penalty)`

**Step 3:** Keep the legacy `mark-bounced` endpoint for one release; have it call `markFailed(id, ChequeFailureReason.BOUNCE, null)` and emit a deprecation header. Delete in a later release once the web/mobile clients are on the new endpoint.

**Step 4:** Run + commit.
```bash
git commit -m "feat(api): POST /api/v1/payments/{id}/mark-failed (reason-aware)"
```

---

## Milestone 6 — Daily accrual job

### Task 6.1: Failing tests for `processChequeFailureAccruals`

**Files:**
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/PenaltyServiceChequeFailureAccrualTest.java`

**Step 1:** Use a fixed `Clock` (inject one if `PenaltyService` doesn't already accept one — small refactor first if needed). Tests:

- `accrual_dayZero_daysOverdueIsZero` — penalty createdAt today, graceDays=7, today=createdAt → daysOverdue=0.
- `accrual_dayOfGrace_stillZero` — today = createdAt + 7 → daysOverdue=0.
- `accrual_oneDayPastGrace_daysOverdueIsOne` — today = createdAt + 8 → 1.
- `accrual_thirtyDays_daysOverdueIsTwentyThree` — today = createdAt + 30 → 23.
- `accrual_clearedPenalty_isSkipped` — `clearedAt != null` → no update.
- `accrual_nonChequeFailureType_isSkipped` — `penaltyType="OVERDUE_FIXED_PER_DAY"` → no update.
- `accrual_idempotentSameDay` — running twice the same day yields identical state.

**Step 2:** Run, expect FAIL.

### Task 6.2: Implement `processChequeFailureAccruals`

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/PenaltyService.java`

**Step 1:** Add the method:

```java
@Transactional
public void processChequeFailureAccruals() {
    LocalDate today = LocalDate.now(clock);
    List<PaymentPenalty> open = paymentPenaltyRepository
            .findByPenaltyTypeAndClearedAtIsNull("CHEQUE_FAILURE");
    for (PaymentPenalty p : open) {
        LocalDate graceUntil = p.getCreatedAt().toLocalDate().plusDays(p.getFineGraceDays());
        long days = today.isAfter(graceUntil)
                ? ChronoUnit.DAYS.between(graceUntil, today) : 0;
        p.setDaysOverdue((int) days);
        p.setLastCalculatedAt(LocalDateTime.now(clock));
    }
    paymentPenaltyRepository.saveAll(open);
}
```

Add the new repo method:
```java
List<PaymentPenalty> findByPenaltyTypeAndClearedAtIsNull(String penaltyType);
```

Wire `processChequeFailureAccruals` into the existing daily entry-point method.

**Step 2:** Run tests; PASS.

**Step 3: Commit.**
```bash
git commit -m "feat: PenaltyService.processChequeFailureAccruals — daily per-day accrual"
```

---

## Milestone 7 — Penalty payment / clearance flow

### Task 7.1: Failing tests for `PenaltyPaymentService.recordReceipt`

**Files:**
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/PenaltyPaymentServiceTest.java`

**Step 1:** Tests:
- `recordReceipt_partialPay_keepsPenaltyOpen_outstandingDecreases`
- `recordReceipt_fullPay_setsClearedAt_firesPenaltyClearedNotification`
- `recordReceipt_amountExceedsOutstanding_throws`
- `recordReceipt_alreadyCleared_throws`
- `recordReceipt_postsFinancialTransactionWithPenaltyIncomeNature`
- `recordReceipt_writesLeaseEventPenaltyPaymentRecorded`

### Task 7.2: Implement service

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/PenaltyPaymentService.java`

**Step 1:** Service:

```java
@Service
@RequiredArgsConstructor
public class PenaltyPaymentService {

    private final PaymentPenaltyRepository paymentPenaltyRepository;
    private final PenaltyPaymentRepository penaltyPaymentRepository;
    private final FinancialTransactionService financialTransactionService;
    private final NotificationService notificationService;
    private final LeaseEventRepository leaseEventRepository;

    @Transactional
    public PenaltyPayment recordReceipt(UUID penaltyId, RecordReceiptInput input, UUID receivedBy) {
        PaymentPenalty p = paymentPenaltyRepository.findById(penaltyId)
                .orElseThrow(() -> new NotFoundException("Penalty " + penaltyId + " not found"));

        if (p.getClearedAt() != null) {
            throw new BusinessRuleViolationException("Penalty already cleared");
        }
        if (p.isWaived()) {
            throw new BusinessRuleViolationException("Penalty already waived");
        }

        BigDecimal currentTotal = currentTotal(p);
        BigDecimal alreadyPaid  = penaltyPaymentRepository
                .findByPaymentPenaltyIdOrderByReceivedAtAscCreatedAtAsc(penaltyId)
                .stream().map(PenaltyPayment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal outstanding = currentTotal.subtract(alreadyPaid);

        if (input.amount().compareTo(outstanding) > 0) {
            throw new BusinessRuleViolationException(
                    "Amount " + input.amount() + " exceeds outstanding " + outstanding);
        }

        PenaltyPayment row = new PenaltyPayment();
        row.setPaymentPenaltyId(penaltyId);
        row.setAmount(input.amount());
        row.setPaymentMethod(input.paymentMethod());
        row.setPaymentReference(input.paymentReference());
        row.setReceivedAt(input.receivedAt());
        row.setReceivedBy(receivedBy);
        row.setNotes(input.notes());

        UUID financialTxId = financialTransactionService.recordPenaltyIncome(p, row);
        row.setFinancialTransactionId(financialTxId);
        penaltyPaymentRepository.save(row);

        BigDecimal nowPaid = alreadyPaid.add(input.amount());
        if (nowPaid.compareTo(currentTotal) >= 0) {
            p.setClearedAt(LocalDateTime.now());
            paymentPenaltyRepository.save(p);
            notificationService.sendPenaltyCleared(p);
        }

        leaseEventRepository.save(LeaseEvent.penaltyPaymentRecorded(p, row));
        return row;
    }

    public BigDecimal currentTotal(PaymentPenalty p) {
        BigDecimal accrual = p.getFinePerDayRate() == null || p.getDaysOverdue() == null
                ? BigDecimal.ZERO
                : p.getFinePerDayRate().multiply(BigDecimal.valueOf(Math.max(0, p.getDaysOverdue())));
        return p.getPenaltyAmount().add(accrual);
    }

    public BigDecimal outstanding(PaymentPenalty p) {
        if (p.getClearedAt() != null || p.isWaived()) return BigDecimal.ZERO;
        BigDecimal paid = penaltyPaymentRepository
                .findByPaymentPenaltyIdOrderByReceivedAtAscCreatedAtAsc(p.getId())
                .stream().map(PenaltyPayment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return currentTotal(p).subtract(paid);
    }

    public record RecordReceiptInput(
            BigDecimal amount, String paymentMethod, String paymentReference,
            LocalDate receivedAt, String notes) {}
}
```

Add `FinancialTransactionService.recordPenaltyIncome(...)` posting nature `PENALTY_INCOME`.

**Step 2:** Run tests, PASS, commit.
```bash
git commit -m "feat: PenaltyPaymentService.recordReceipt — clearance journal + accounting"
```

### Task 7.3: Waive endpoint + notification

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/PenaltyService.java` (add `waive(penaltyId, waiver, reason)`)
- Modify: `NotificationService` to add `sendPenaltyWaived`

**Step 1:** Tests in `PenaltyPaymentServiceTest` (or split out): `waive_setsClearedAt_andFiresPenaltyWaivedNotification`. `waive_alreadyCleared_throws`.

**Step 2:** Implement, run tests, commit.
```bash
git commit -m "feat: PenaltyService.waive — admin waiver path with notification"
```

---

## Milestone 8 — Notifications

### Task 8.1: Add `PENALTY_INCURRED` / `PENALTY_CLEARED` / `PENALTY_WAIVED` types

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/NotificationService.java`

**Step 1:** Add three methods, copy templates matching the existing PAYMENT_BOUNCED style. Bodies include reason / amount / "how to pay" instructions copy (sourced from a new `OrgSettings.penalty_payment_instructions` field — added in 8.2).

**Step 2:** Tests in `NotificationServiceTest` for each new type: notification row created, deep-link target set, CTA label correct.

**Step 3:** Commit.
```bash
git commit -m "feat: notifications PENALTY_INCURRED / PENALTY_CLEARED / PENALTY_WAIVED"
```

### Task 8.2: Add `penalty_payment_instructions` to `OrgSettings`

**Files:**
- New changeset: `49-org-settings-penalty-instructions.yaml` (TEXT NULL column)
- Modify: `OrgSettings.java`

Commit:
```bash
git commit -m "feat(db): OrgSettings.penalty_payment_instructions for renter copy"
```

---

## Milestone 9 — API endpoints + DTOs

### Task 9.1: Fines settings controller + DTO

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/api/FineSettingsController.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/FineConfigDTO.java`

**Step 1:** Failing controller test for `GET /api/v1/settings/fines` and `PUT /api/v1/settings/fines`. Auth: 200 for SUPER_ADMIN/TENANT_ADMIN, 403 for everyone else. Validation: amounts ≥ 0 → 400 on negative.

**Step 2:** Implement.

**Step 3:** Run + commit.
```bash
git commit -m "feat(api): GET/PUT /api/v1/settings/fines — org-level config"
```

### Task 9.2: Per-property fine override fields in rent-settings

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/RentSettingsController.java` (or equivalent)
- Modify: corresponding DTO to accept the 5 nullable fields.

**Step 1:** Tests: nullable round-trip; setting all-null clears the override.

**Step 2:** Implement + commit.
```bash
git commit -m "feat(api): rent-settings PUT accepts nullable per-property fine overrides"
```

### Task 9.3: Penalty list + record-payment + waive endpoints

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/api/PenaltyController.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/PenaltyDTO.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/PenaltyPaymentDTO.java`

**Step 1:** Failing controller tests (one per endpoint, auth happy + sad path).

**Step 2:** Implement:
- `GET /api/v1/penalties?leaseId=&status=open|cleared|all` — paginated, embeds payments + outstanding/currentTotal.
- `POST /api/v1/penalties/{id}/payments` — body matches `RecordReceiptInput`.
- `POST /api/v1/penalties/{id}/waive` — body `{ reason: string }`.

**Step 3:** Renter-scoped read: when caller is RENTER, scope to their own leases (`leaseRepo.findByRenterUserId(currentUser)`).

**Step 4:** Commit.
```bash
git commit -m "feat(api): PenaltyController — list / record-payment / waive"
```

---

## Milestone 10 — Web: settings + per-property override

### Task 10.1: New page `dashboard/settings/fines/page.tsx`

**Files:**
- Create: `web/src/app/[locale]/dashboard/settings/fines/page.tsx`
- Modify: settings sidebar nav config

**Step 1:** Build a form with the 5 fields, GET on mount, PUT on save. RBAC gate: SUPER_ADMIN + TENANT_ADMIN only. i18n keys for labels.

**Step 2:** Manual smoke (browser): edit values, save, refresh, values persist.

**Step 3:** Commit.
```bash
git commit -m "feat(web): /dashboard/settings/fines — org-level fine config"
```

### Task 10.2: Per-property override section on `rent-settings` page

**Files:**
- Modify: `web/src/app/[locale]/dashboard/settings/rent-settings/page.tsx` (or per-property variant)

**Step 1:** Add a collapsible "Cheque-failure fine overrides" section. Each input shows the org-level value as placeholder; "Reset to org default" button clears the field to null.

**Step 2:** Manual smoke. Commit.
```bash
git commit -m "feat(web): per-property fine override section on rent-settings"
```

---

## Milestone 11 — Web: mark-failed dialog + lease/payments wiring

### Task 11.1: Build reusable `<MarkChequeFailedDialog/>`

**Files:**
- Create: `web/src/components/payments/MarkChequeFailedDialog.tsx`

**Step 1:** Component contract: props `{paymentScheduleId, isOpen, onClose, onSuccess(updatedSchedule, penalty)}`. Reason dropdown (3 options), notes textarea, confirm button → POST `/mark-failed`.

**Step 2:** Component test (Vitest/RTL) for happy + 4xx error paths.

**Step 3:** Commit.
```bash
git commit -m "feat(web): MarkChequeFailedDialog reusable component"
```

### Task 11.2: Wire dialog into the lease detail page

**Files:**
- Modify: `web/src/app/[locale]/dashboard/leases/[id]/page.tsx`

**Step 1:** Replace the existing "mark bounced" per-row action with the new dialog. Show a small penalty pill on rows that have an open penalty.

**Step 2:** Manual smoke. Commit.
```bash
git commit -m "feat(web): mark-failed dialog on lease detail page"
```

### Task 11.3: Wire dialog into the finance/payments page

**Files:**
- Modify: `web/src/app/[locale]/dashboard/finance/payments/page.tsx`

Same flow as 11.2. Commit.
```bash
git commit -m "feat(web): mark-failed dialog on finance/payments page"
```

### Task 11.4: Lease detail "Penalties" section

**Files:**
- Modify: `web/src/app/[locale]/dashboard/leases/[id]/page.tsx`

List the lease's penalties (open + cleared) with `outstanding`, `daysOverdue`, payment history. "Record payment" button opens a small payment-receipt dialog (amount, method, ref, date, notes).

Commit:
```bash
git commit -m "feat(web): lease detail — Penalties section + record-payment dialog"
```

---

## Milestone 12 — Renter portal

### Task 12.1: New page `/portal/penalties`

**Files:**
- Create: `web/src/app/[locale]/portal/penalties/page.tsx`
- Modify: portal nav config

Read-only list + "How to pay" panel (sourced from org `penalty_payment_instructions`). Cleared-penalties tab.

Commit:
```bash
git commit -m "feat(web): /portal/penalties — renter read-only penalty view"
```

---

## Milestone 13 — Mobile manager: mark-failed dialog

### Task 13.1: Flutter `MarkChequeFailedDialog`

**Files:**
- Create: `mobile/apps/manager/lib/widgets/mark_cheque_failed_dialog.dart`
- Modify: `mobile/apps/manager/lib/screens/lease_detail/...` to wire it into the per-row action menu.
- Add API service call to `mobile/packages/rentaxis_core/lib/services/payment_service.dart` (or equivalent).

Commit:
```bash
git commit -m "feat(mobile/manager): mark-cheque-failed dialog + lease wiring"
```

---

## Milestone 14 — Mobile renter: penalties

### Task 14.1: Penalty API client in `rentaxis_core`

**Files:**
- Modify: `mobile/packages/rentaxis_core/lib/services/penalty_service.dart` (NEW)

Methods: `listPenalties({leaseId, status})`, `getPenaltyPaymentInstructions()` (org settings).

### Task 14.2: `penalties_screen.dart`

**Files:**
- Create: `mobile/apps/renter/lib/screens/penalties_screen.dart`
- Modify: home screen to add a "Penalties" tile.
- Modify: `payments_screen.dart` to show an open-penalty badge.
- Modify: notification deep-link router to open penalty detail on `PENALTY_INCURRED` taps.

Commit:
```bash
git commit -m "feat(mobile/renter): penalties screen + home tile + payments badge"
```

---

## Milestone 15 — Cheque-first reinforcement (default flips + copy)

### Task 15.1: Lease-wizard payment-method default

**Files:**
- Modify: `web/src/app/[locale]/dashboard/leases/new/...` (or wherever the wizard's default-method derivation lives)
- Modify: `mobile/apps/manager/lib/screens/lease_create/...` (mirror change)

Change the default from "ONLINE if `RentCollectionSettings.onlinePaymentEnabled` else CHEQUE" to **always CHEQUE** unless the property explicitly opts into ONLINE.

Tests: existing wizard tests; assert default is now CHEQUE.

Commit:
```bash
git commit -m "feat: lease wizard defaults to CHEQUE method (cheque-first reinforcement)"
```

### Task 15.2: Renter copy update on payment-due notifications

**Files:**
- Modify: `NotificationService` PAYMENT_DUE / PAYMENT_OVERDUE templates

Reword to mention cheque collection / replacement first; online as secondary.

Commit:
```bash
git commit -m "content(notifications): cheque-first wording on payment-due copy"
```

---

## Milestone 16 — Integration tests

### Task 16.1: `ChequeFailurePenaltyIT`

**Files:**
- Create: `backend/src/test/java/com/datagami/rentaxis/core/service/ChequeFailurePenaltyIT.java`

**Scenario:**
1. Bulk-import a small workbook (one lease with 4 cheques) using the existing `PortfolioImportPersistEndToEndTest.buildFiveScenarioWorkbook` style.
2. Drive `processImportAsync` to COMPLETED (await pattern from `PortfolioImportIT`).
3. Call `paymentScheduleService.markFailed(chequeId, BOUNCE, "test")` for one of the cheques.
4. Assert: penalty created with amount=500, schedule.status=BOUNCED, schedule.failureReason=BOUNCE, notification row created with type=PENALTY_INCURRED, lease event written.
5. Issue a replacement cheque via the existing replacement flow; assert old cheque keeps replacedBy set, penalty unchanged.

Commit:
```bash
git commit -m "test: ChequeFailurePenaltyIT — full cheque-failure flow under @SpringBootTest"
```

### Task 16.2: `PenaltyClearanceIT`

**Files:**
- Create: `backend/src/test/java/com/datagami/rentaxis/core/service/PenaltyClearanceIT.java`

**Scenario:**
1. Create a lease + cheque + mark it BOUNCE (penalty=500).
2. Wait 8 days (`Clock` shifted; or `processChequeFailureAccruals` driven manually).
3. `currentTotal` now = 500 + 1*25 = 525.
4. Record a partial PenaltyPayment of 200 (BANK_TRANSFER, ref=UTR-1). Assert outstanding=325, penalty still open, FinancialTransaction posted.
5. Record final payment of 325. Assert clearedAt set, notification PENALTY_CLEARED fired, second FinancialTransaction posted.
6. Assert `Σ PenaltyPayment.amount = 525`.

Commit:
```bash
git commit -m "test: PenaltyClearanceIT — partial + final pay, accounting + notification"
```

---

## Milestone 17 — Final QA + PR

### Task 17.1: Full backend + frontend test pass

**Steps:**
1. `cd backend && ./gradlew clean test` — BUILD SUCCESSFUL.
2. `cd web && npm run test && npm run build` — BUILD SUCCESSFUL.
3. `cd mobile && melos run test` (or per-app `flutter test`) — pass.

**No commit at this task.**

### Task 17.2: Manual smoke (browser + mobile)

1. Configure org fines via `/dashboard/settings/fines` — values save.
2. Override a property's `bounce` amount via `/rent-settings` — placeholders render org default; saved override sticks.
3. Mark a cheque failed (bounce / signature mismatch / account closed) from lease detail → penalty appears; notification arrives in renter inbox.
4. Record a partial penalty payment → outstanding updates.
5. Record final payment → penalty clears; PENALTY_CLEARED notification fires.
6. Waive a penalty → cleared with `waived=true`; PENALTY_WAIVED fires.
7. Renter portal `/portal/penalties` shows open + cleared lists; "How to pay" panel renders the configured copy.
8. Renter mobile shows the same.
9. Bulk-import a portfolio with cheques → no penalties pre-created.

**No commit.**

### Task 17.3: Open PR

```bash
git push -u origin feat/cheque-failure-penalties
gh pr create --title "feat: cheque-failure penalties (fines + accrual + clearance)" \
  --body "$(cat <<'BODY'
## Summary
- Configurable fixed fines per cheque-failure type (bounce/signature mismatch/account closed) at landlord-org level with optional per-property override.
- Per-day accrual on the unpaid fine itself after a configurable grace period.
- New `PenaltyPayment` journal entity for admin-recorded clearance via bank transfer / cheque / cash. Each receipt posts a `FinancialTransaction` of new nature `PENALTY_INCOME`.
- Three new notifications: `PENALTY_INCURRED`, `PENALTY_CLEARED`, `PENALTY_WAIVED`.
- Renter portal + mobile show penalties read-only with "how to pay" instructions.
- Cheque-first reinforcement: lease wizard defaults to CHEQUE; renter notification copy emphasizes cheque collection.

Design: docs/plans/2026-05-03-cheque-failure-penalties-design.md
Plan: docs/plans/2026-05-03-cheque-failure-penalties-plan.md

## Test plan
- [x] Backend unit: `FineConfigResolverTest`, `PenaltyServiceChequeFailureAccrualTest`, `PenaltyPaymentServiceTest`, `PaymentScheduleServiceMarkFailedTest`.
- [x] Backend IT: `ChequeFailurePenaltyIT`, `PenaltyClearanceIT` (Testcontainers).
- [x] Web component: `MarkChequeFailedDialog` happy + 4xx paths.
- [x] Mobile widget: renter penalties screen.
- [x] Manual smoke per task 17.2.
BODY
)"
```

---

## Notes / conventions

- **Liquibase append-only** — never modify 47/48 once merged. Any follow-up takes a new changeset.
- **Tenant isolation** — every new repo uses the existing `TenantAspect` filter. The penalty list endpoint scopes by `TenantContextHolder` for admin and additionally filters by renter user-id for renter callers.
- **TDD throughout** — every behavior change has a failing test committed before the implementation.
- **Conventional commits** — `feat:`, `fix:`, `test:`, `docs:`, `content:`, scoped to the affected area where useful.
- **YAGNI** — no per-failure-type grace/rate, no online-fine-payment via Razorpay, no historical fine import. All deferred per the design doc's "Out of scope" section.
- **Frequent commits** — every task ends with a commit. Don't batch task changes.
