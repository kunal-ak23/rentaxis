# Lease Renewal Retention Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the renter-retention loop — 90/60/30-day renewal reminders, renter intent capture, PM-side CRM interaction log, and a renter portal renewal dashboard — on top of the existing RentAxis modular monolith.

**Architecture:** Three new tenant-scoped tables (`renewal_opportunities`, `lease_reminders`, `lease_interactions`) form the spine. A new `LeaseRenewalScheduler` runs daily, opens opportunities for leases entering the 90-day window, fires reminders idempotently via a (opportunity, slot, channel) ledger, and closes stale opportunities. Renewal emails use the existing `EmailEvent` + outbox pipeline with a new `LEASE_RENEWAL_REMINDER` event type. Magic-link HMAC JWT tokens drive a public intent-capture endpoint. PM lease-detail page grows an interactions timeline + log-entry modal. Renter portal grows a bucketed renewals page + magic-link confirm landing.

**Tech Stack:** Java 21 / Spring Boot 4.0.3 / Spring Data JPA / Liquibase / PostgreSQL 16 (backend); Next.js 16 / TypeScript 5 / Tailwind 4 / next-intl (frontend); JJWT 0.12+ for HMAC JWT (new dependency).

**Spec:** `docs/superpowers/specs/2026-05-13-lease-renewal-retention-design.md`.

---

## File Structure

### Backend (new files)

| Path | Purpose |
|---|---|
| `src/main/resources/db/changelog/changesets/58-lease-renewal.yaml` | Migration: 3 tables, indexes, partial-unique constraint |
| `src/main/java/com/datagami/rentaxis/domain/entity/RenewalOpportunity.java` | Opportunity entity |
| `src/main/java/com/datagami/rentaxis/domain/entity/LeaseReminder.java` | Reminder ledger entity |
| `src/main/java/com/datagami/rentaxis/domain/entity/LeaseInteraction.java` | CRM interaction entity |
| `src/main/java/com/datagami/rentaxis/domain/entity/enums/RenewalStage.java` | OPEN / INTENT_CAPTURED / CLOSED_WON / CLOSED_LOST |
| `src/main/java/com/datagami/rentaxis/domain/entity/enums/RenewalIntent.java` | RENEW / MOVE_OUT / DISCUSS |
| `src/main/java/com/datagami/rentaxis/domain/entity/enums/RenewalOutcome.java` | RENEWED / MOVED_OUT / EXPIRED_NO_RESPONSE |
| `src/main/java/com/datagami/rentaxis/domain/entity/enums/ReminderStatus.java` | PENDING / SENT / FAILED / SKIPPED |
| `src/main/java/com/datagami/rentaxis/domain/entity/enums/ReminderChannel.java` | EMAIL / IN_APP |
| `src/main/java/com/datagami/rentaxis/domain/entity/enums/InteractionType.java` | CALL / SMS / EMAIL / WHATSAPP / MEETING / SYSTEM_INTENT / NOTE |
| `src/main/java/com/datagami/rentaxis/domain/entity/enums/InteractionDirection.java` | INBOUND / OUTBOUND / INTERNAL |
| `src/main/java/com/datagami/rentaxis/domain/entity/enums/InteractionOutcome.java` | POSITIVE / NEUTRAL / NEGATIVE / NO_RESPONSE |
| `src/main/java/com/datagami/rentaxis/domain/repository/RenewalOpportunityRepository.java` | + `findByIdAcrossTenants` |
| `src/main/java/com/datagami/rentaxis/domain/repository/LeaseReminderRepository.java` | |
| `src/main/java/com/datagami/rentaxis/domain/repository/LeaseInteractionRepository.java` | + follow-up query |
| `src/main/java/com/datagami/rentaxis/core/service/renewal/RenewalOpportunityService.java` | Open + close opportunities |
| `src/main/java/com/datagami/rentaxis/core/service/renewal/RenewalReminderService.java` | Phase-2 firing logic |
| `src/main/java/com/datagami/rentaxis/core/service/renewal/RenewalTokenService.java` | HMAC JWT sign / verify |
| `src/main/java/com/datagami/rentaxis/core/service/renewal/RenewalIntentService.java` | Capture intent + log SYSTEM_INTENT + publish event |
| `src/main/java/com/datagami/rentaxis/core/service/renewal/LeaseRenewalScheduler.java` | Cron entry point, tenant loop |
| `src/main/java/com/datagami/rentaxis/core/service/renewal/RenewalIntentCapturedListener.java` | AFTER_COMMIT listener → notify PM |
| `src/main/java/com/datagami/rentaxis/core/service/LeaseInteractionService.java` | CRUD for interactions |
| `src/main/java/com/datagami/rentaxis/core/email/event/payload/LeaseRenewalReminderPayload.java` | Email payload |
| `src/main/java/com/datagami/rentaxis/core/email/event/payload/RenewalIntentCapturedPayload.java` | PM-notification email payload |
| `src/main/java/com/datagami/rentaxis/core/email/event/RenewalIntentCapturedEvent.java` | Spring app event (not EmailEvent) |
| `src/main/resources/email/templates/lease-renewal-reminder.html` | EN template |
| `src/main/resources/email/templates/lease-renewal-reminder_ar.html` | AR template |
| `src/main/resources/email/templates/renewal-intent-captured.html` | PM-side EN template |
| `src/main/resources/email/templates/renewal-intent-captured_ar.html` | PM-side AR template |
| `src/main/java/com/datagami/rentaxis/api/PublicRenewalController.java` | `POST /api/v1/public/renewal-intent` |
| `src/main/java/com/datagami/rentaxis/api/LeaseInteractionController.java` | CRM CRUD endpoints |
| `src/main/java/com/datagami/rentaxis/api/RenterRenewalController.java` | `/api/v1/me/renewals` |
| `src/main/java/com/datagami/rentaxis/api/PendingFollowUpsController.java` | `/api/v1/renewals/follow-ups` |
| `src/main/java/com/datagami/rentaxis/api/dto/RenewalIntentRequest.java` | |
| `src/main/java/com/datagami/rentaxis/api/dto/RenewalIntentResponse.java` | |
| `src/main/java/com/datagami/rentaxis/api/dto/RenewalSummaryDTO.java` | renter's view |
| `src/main/java/com/datagami/rentaxis/api/dto/InteractionDTO.java` | |
| `src/main/java/com/datagami/rentaxis/api/dto/CreateInteractionRequest.java` | |
| `src/main/java/com/datagami/rentaxis/api/dto/UpdateInteractionRequest.java` | |
| `src/main/java/com/datagami/rentaxis/api/dto/MarkRenewedRequest.java` | |

### Backend (modify)

| Path | Change |
|---|---|
| `src/main/java/com/datagami/rentaxis/core/email/EmailEventType.java` | Add `LEASE_RENEWAL_REMINDER`, `RENEWAL_INTENT_CAPTURED` |
| `src/main/java/com/datagami/rentaxis/core/email/dispatch/RecipientResolver.java` | Add userIds resolution for new event types |
| `src/main/java/com/datagami/rentaxis/domain/repository/LeaseRepository.java` | Add `findActiveLeasesEnteringRenewalWindow(LocalDate cutoff)` |
| `src/main/java/com/datagami/rentaxis/api/LeaseController.java` | Add `POST /{id}/renewal/mark-renewed` |
| `src/main/resources/application.yml` | `app.renewal.scheduler.enabled`, `app.renewal.token-secret`, `app.renewal.portal-base-url` |
| `build.gradle` | Add `io.jsonwebtoken:jjwt-api:0.12.6` + jjwt-impl + jjwt-jackson |

### Backend (tests)

| Path | Purpose |
|---|---|
| `src/test/java/com/datagami/rentaxis/core/service/renewal/LeaseRenewalSchedulerIntegrationTest.java` | All 10 scheduler scenarios from spec §14.1 |
| `src/test/java/com/datagami/rentaxis/core/service/renewal/RenewalTokenServiceTest.java` | HMAC sign/verify, expiry, rotation |
| `src/test/java/com/datagami/rentaxis/core/service/renewal/RenewalIntentServiceTest.java` | Capture, replay, closed-opportunity rejection |
| `src/test/java/com/datagami/rentaxis/api/PublicRenewalControllerTest.java` | 200 / 400 / 410 / 409 / 404 |
| `src/test/java/com/datagami/rentaxis/api/LeaseInteractionControllerTest.java` | CRUD + system-entry read-only + cross-tenant |
| `src/test/java/com/datagami/rentaxis/api/PendingFollowUpsControllerTest.java` | filter rules |
| `src/test/java/com/datagami/rentaxis/api/RenterRenewalControllerTest.java` | renter sees only own |

### Frontend (new files)

| Path | Purpose |
|---|---|
| `web/src/app/[locale]/dashboard/renter-portal/renewals/page.tsx` | Bucketed renewals page |
| `web/src/app/[locale]/dashboard/renter-portal/renewal-intent/page.tsx` | Magic-link confirm page |
| `web/src/components/renewals/RenewalCard.tsx` | Per-lease card with intent buttons |
| `web/src/components/renewals/RenewalIntentConfirm.tsx` | Confirm card on magic-link land |
| `web/src/components/renewals/RenewalBanner.tsx` | Top banner on renter-portal home |
| `web/src/components/leases/LeaseInteractionsPanel.tsx` | PM-side timeline + header |
| `web/src/components/leases/LogInteractionDialog.tsx` | Modal form (a11y mirror of MarkChequeFailedDialog) |
| `web/src/components/dashboard/FollowUpsWidget.tsx` | One-card widget on PM home |

### Frontend (modify)

| Path | Change |
|---|---|
| `web/src/app/[locale]/dashboard/renter-portal/page.tsx` | Mount `<RenewalBanner />` |
| `web/src/app/[locale]/dashboard/leases/[id]/page.tsx` | Mount `<LeaseInteractionsPanel />` below payment-schedule section |
| `web/src/app/[locale]/dashboard/page.tsx` | Mount `<FollowUpsWidget />` |
| `web/messages/en.json` | Add `interactions`, `renewals`, `followUpsWidget` namespaces |
| `web/messages/ar.json` | Same, Arabic translations |

### Frontend (tests)

| Path | Purpose |
|---|---|
| `web/src/components/renewals/__tests__/RenewalCard.test.tsx` | renders, buttons disabled per state |
| `web/src/components/renewals/__tests__/RenewalIntentConfirm.test.tsx` | submit, expired-token error |
| `web/src/components/leases/__tests__/LeaseInteractionsPanel.test.tsx` | render entries, system-entry read-only |
| `web/src/components/leases/__tests__/LogInteractionDialog.test.tsx` | modal a11y, submit |
| `web/src/components/dashboard/__tests__/FollowUpsWidget.test.tsx` | empty + populated |

---

## Phase A — Data model + migration (Tasks 1–4)

### Task 1: Liquibase migration `58-lease-renewal.yaml`

**Files:**
- Create: `backend/src/main/resources/db/changelog/changesets/58-lease-renewal.yaml`

- [ ] **Step 1: Write the migration**

```yaml
databaseChangeLog:
  - changeSet:
      id: 58-lease-renewal
      author: rentaxis-system
      changes:
        # 1. renewal_opportunities
        - createTable:
            tableName: renewal_opportunities
            columns:
              - column: { name: id, type: uuid, constraints: { primaryKey: true, nullable: false } }
              - column: { name: tenant_id, type: uuid, constraints: { nullable: false } }
              - column: { name: lease_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_renewal_opp_lease, referencedTableName: leases, referencedColumnNames: id } }
              - column: { name: stage, type: varchar(30), constraints: { nullable: false } }
              - column: { name: intent, type: varchar(20) }
              - column: { name: intent_captured_at, type: timestamptz }
              - column: { name: opened_at, type: timestamptz, constraints: { nullable: false } }
              - column: { name: closed_at, type: timestamptz }
              - column: { name: outcome, type: varchar(30) }
              - column: { name: created_at, type: timestamptz, constraints: { nullable: false }, defaultValueComputed: now() }
              - column: { name: updated_at, type: timestamptz, constraints: { nullable: false }, defaultValueComputed: now() }
        - createIndex: { tableName: renewal_opportunities, indexName: idx_renewal_opp_tenant_stage, columns: [ { column: { name: tenant_id } }, { column: { name: stage } } ] }
        - createIndex: { tableName: renewal_opportunities, indexName: idx_renewal_opp_lease, columns: [ { column: { name: lease_id } } ] }
        - sql:
            sql: "CREATE UNIQUE INDEX uniq_open_opp_per_lease ON renewal_opportunities (lease_id) WHERE stage IN ('OPEN', 'INTENT_CAPTURED');"

        # 2. lease_reminders
        - createTable:
            tableName: lease_reminders
            columns:
              - column: { name: id, type: uuid, constraints: { primaryKey: true, nullable: false } }
              - column: { name: tenant_id, type: uuid, constraints: { nullable: false } }
              - column: { name: opportunity_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_reminder_opp, referencedTableName: renewal_opportunities, referencedColumnNames: id } }
              - column: { name: slot, type: smallint, constraints: { nullable: false } }
              - column: { name: channel, type: varchar(20), constraints: { nullable: false } }
              - column: { name: status, type: varchar(20), constraints: { nullable: false } }
              - column: { name: sent_at, type: timestamptz }
              - column: { name: attempt_count, type: int, constraints: { nullable: false }, defaultValueNumeric: 0 }
              - column: { name: last_error, type: text }
              - column: { name: created_at, type: timestamptz, constraints: { nullable: false }, defaultValueComputed: now() }
              - column: { name: updated_at, type: timestamptz, constraints: { nullable: false }, defaultValueComputed: now() }
        - addUniqueConstraint:
            tableName: lease_reminders
            columnNames: opportunity_id, slot, channel
            constraintName: uniq_reminder_opp_slot_channel

        # 3. lease_interactions
        - createTable:
            tableName: lease_interactions
            columns:
              - column: { name: id, type: uuid, constraints: { primaryKey: true, nullable: false } }
              - column: { name: tenant_id, type: uuid, constraints: { nullable: false } }
              - column: { name: opportunity_id, type: uuid, constraints: { nullable: true, foreignKeyName: fk_interaction_opp, referencedTableName: renewal_opportunities, referencedColumnNames: id } }
              - column: { name: lease_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_interaction_lease, referencedTableName: leases, referencedColumnNames: id } }
              - column: { name: type, type: varchar(20), constraints: { nullable: false } }
              - column: { name: direction, type: varchar(10), constraints: { nullable: false } }
              - column: { name: occurred_at, type: timestamptz, constraints: { nullable: false } }
              - column: { name: summary, type: text, constraints: { nullable: false } }
              - column: { name: outcome, type: varchar(20) }
              - column: { name: follow_up_date, type: date }
              - column: { name: created_by, type: uuid, constraints: { nullable: false, foreignKeyName: fk_interaction_user, referencedTableName: users, referencedColumnNames: id } }
              - column: { name: deleted_at, type: timestamptz }
              - column: { name: created_at, type: timestamptz, constraints: { nullable: false }, defaultValueComputed: now() }
        - createIndex: { tableName: lease_interactions, indexName: idx_interaction_lease_occurred, columns: [ { column: { name: lease_id } }, { column: { name: occurred_at } } ] }
        - createIndex: { tableName: lease_interactions, indexName: idx_interaction_tenant_followup, columns: [ { column: { name: tenant_id } }, { column: { name: follow_up_date } } ] }
        - createIndex: { tableName: lease_interactions, indexName: idx_interaction_opportunity, columns: [ { column: { name: opportunity_id } } ] }
```

- [ ] **Step 2: Verify migration applies on a Testcontainers Postgres**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.RentAxisApplicationTests'`
Expected: Either passes (if context loads) or fails on an unrelated pre-existing Liquibase ownership error (see PR #50 history). The migration itself must apply cleanly inside a Testcontainers run; verify by adding a temporary `@SpringBootTest @Testcontainers` smoke test, OR rely on Phase B's repository tests below (they exercise the migration).

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/db/changelog/changesets/58-lease-renewal.yaml
git commit -m "feat(db): add renewal_opportunities, lease_reminders, lease_interactions tables"
```

---

### Task 2: Java enums

**Files:**
- Create: 8 files under `backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/`

- [ ] **Step 1: Write all 8 enums**

`RenewalStage.java`:
```java
package com.datagami.rentaxis.domain.entity.enums;
public enum RenewalStage { OPEN, INTENT_CAPTURED, CLOSED_WON, CLOSED_LOST }
```

`RenewalIntent.java`:
```java
package com.datagami.rentaxis.domain.entity.enums;
public enum RenewalIntent { RENEW, MOVE_OUT, DISCUSS }
```

`RenewalOutcome.java`:
```java
package com.datagami.rentaxis.domain.entity.enums;
public enum RenewalOutcome { RENEWED, MOVED_OUT, EXPIRED_NO_RESPONSE }
```

`ReminderStatus.java`:
```java
package com.datagami.rentaxis.domain.entity.enums;
public enum ReminderStatus { PENDING, SENT, FAILED, SKIPPED }
```

`ReminderChannel.java`:
```java
package com.datagami.rentaxis.domain.entity.enums;
public enum ReminderChannel { EMAIL, IN_APP }
```

`InteractionType.java`:
```java
package com.datagami.rentaxis.domain.entity.enums;
public enum InteractionType { CALL, SMS, EMAIL, WHATSAPP, MEETING, SYSTEM_INTENT, NOTE }
```

`InteractionDirection.java`:
```java
package com.datagami.rentaxis.domain.entity.enums;
public enum InteractionDirection { INBOUND, OUTBOUND, INTERNAL }
```

`InteractionOutcome.java`:
```java
package com.datagami.rentaxis.domain.entity.enums;
public enum InteractionOutcome { POSITIVE, NEUTRAL, NEGATIVE, NO_RESPONSE }
```

- [ ] **Step 2: Compile**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/
git commit -m "feat(domain): renewal + interaction enums"
```

---

### Task 3: Entity classes

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/RenewalOpportunity.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/LeaseReminder.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/LeaseInteraction.java`

- [ ] **Step 1: Write `RenewalOpportunity`**

```java
package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.RenewalIntent;
import com.datagami.rentaxis.domain.entity.enums.RenewalOutcome;
import com.datagami.rentaxis.domain.entity.enums.RenewalStage;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "renewal_opportunities")
@Getter
@Setter
public class RenewalOpportunity extends BaseTenantEntity {

    @Id @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lease_id", nullable = false)
    private Lease lease;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RenewalStage stage = RenewalStage.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private RenewalIntent intent;

    @Column(name = "intent_captured_at")
    private Instant intentCapturedAt;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt = Instant.now();

    @Column(name = "closed_at")
    private Instant closedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private RenewalOutcome outcome;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void onUpdate() { this.updatedAt = Instant.now(); }
}
```

- [ ] **Step 2: Write `LeaseReminder`**

```java
package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.ReminderChannel;
import com.datagami.rentaxis.domain.entity.enums.ReminderStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "lease_reminders", uniqueConstraints = @UniqueConstraint(name = "uniq_reminder_opp_slot_channel", columnNames = { "opportunity_id", "slot", "channel" }))
@Getter
@Setter
public class LeaseReminder extends BaseTenantEntity {

    @Id @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "opportunity_id", nullable = false)
    private RenewalOpportunity opportunity;

    @Column(nullable = false)
    private short slot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReminderChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReminderStatus status = ReminderStatus.PENDING;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void onUpdate() { this.updatedAt = Instant.now(); }
}
```

- [ ] **Step 3: Write `LeaseInteraction`**

```java
package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.InteractionDirection;
import com.datagami.rentaxis.domain.entity.enums.InteractionOutcome;
import com.datagami.rentaxis.domain.entity.enums.InteractionType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "lease_interactions")
@Getter
@Setter
public class LeaseInteraction extends BaseTenantEntity {

    @Id @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "opportunity_id")
    private RenewalOpportunity opportunity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lease_id", nullable = false)
    private Lease lease;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InteractionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private InteractionDirection direction;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private InteractionOutcome outcome;

    @Column(name = "follow_up_date")
    private LocalDate followUpDate;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
```

- [ ] **Step 4: Compile**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/domain/entity/RenewalOpportunity.java backend/src/main/java/com/datagami/rentaxis/domain/entity/LeaseReminder.java backend/src/main/java/com/datagami/rentaxis/domain/entity/LeaseInteraction.java
git commit -m "feat(domain): renewal opportunity + reminder ledger + interaction entities"
```

---

### Task 4: Repositories + LeaseRepository method

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/repository/RenewalOpportunityRepository.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/repository/LeaseReminderRepository.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/repository/LeaseInteractionRepository.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/domain/repository/LeaseRepository.java`

- [ ] **Step 1: Write `RenewalOpportunityRepository`**

```java
package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.RenewalOpportunity;
import com.datagami.rentaxis.domain.entity.enums.RenewalStage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RenewalOpportunityRepository extends JpaRepository<RenewalOpportunity, UUID> {

    Optional<RenewalOpportunity> findByLeaseIdAndStageIn(UUID leaseId, List<RenewalStage> stages);

    @Query("SELECT o FROM RenewalOpportunity o WHERE o.tenantId = :tenantId AND o.stage IN :stages")
    List<RenewalOpportunity> findByTenantIdAndStageIn(@Param("tenantId") UUID tenantId, @Param("stages") List<RenewalStage> stages);

    /**
     * Cross-tenant lookup used ONLY by the public renewal-intent endpoint
     * (where the tenant context is established FROM the opportunity itself).
     * Bypasses the Hibernate tenant filter via a native query so it returns
     * regardless of TenantContextHolder.
     */
    @Query(value = "SELECT * FROM renewal_opportunities WHERE id = :id", nativeQuery = true)
    Optional<RenewalOpportunity> findByIdAcrossTenants(@Param("id") UUID id);
}
```

- [ ] **Step 2: Write `LeaseReminderRepository`**

```java
package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.LeaseReminder;
import com.datagami.rentaxis.domain.entity.enums.ReminderChannel;
import com.datagami.rentaxis.domain.entity.enums.ReminderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface LeaseReminderRepository extends JpaRepository<LeaseReminder, UUID> {

    List<LeaseReminder> findByOpportunityId(UUID opportunityId);

    @Modifying
    @Query("""
        UPDATE LeaseReminder r
        SET r.status = :status, r.lastError = :reason, r.updatedAt = CURRENT_TIMESTAMP
        WHERE r.opportunity.id = :opportunityId
          AND r.status = com.datagami.rentaxis.domain.entity.enums.ReminderStatus.PENDING
    """)
    int bulkSkipPendingForOpportunity(@Param("opportunityId") UUID opportunityId,
                                       @Param("status") ReminderStatus status,
                                       @Param("reason") String reason);

    boolean existsByOpportunityIdAndSlotAndChannel(UUID opportunityId, short slot, ReminderChannel channel);
}
```

- [ ] **Step 3: Write `LeaseInteractionRepository`**

```java
package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.LeaseInteraction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface LeaseInteractionRepository extends JpaRepository<LeaseInteraction, UUID> {

    @Query("""
        SELECT i FROM LeaseInteraction i
        WHERE i.lease.id = :leaseId AND i.deletedAt IS NULL
        ORDER BY i.occurredAt DESC
    """)
    Page<LeaseInteraction> findActiveByLeaseId(@Param("leaseId") UUID leaseId, Pageable pageable);

    @Query("""
        SELECT i FROM LeaseInteraction i
        WHERE i.tenantId = :tenantId
          AND i.followUpDate IS NOT NULL
          AND i.followUpDate <= :date
          AND i.deletedAt IS NULL
          AND (i.outcome IS NULL OR i.outcome <> com.datagami.rentaxis.domain.entity.enums.InteractionOutcome.POSITIVE)
        ORDER BY i.followUpDate ASC
    """)
    List<LeaseInteraction> findPendingFollowUps(@Param("tenantId") UUID tenantId, @Param("date") LocalDate date);
}
```

- [ ] **Step 4: Add lease query method to `LeaseRepository`**

```java
// Add to existing LeaseRepository interface:
@Query("""
    SELECT l FROM Lease l
    WHERE l.status = com.datagami.rentaxis.domain.entity.enums.LeaseStatus.ACTIVE
      AND l.endDate <= :cutoff
      AND NOT EXISTS (
        SELECT 1 FROM RenewalOpportunity o
        WHERE o.lease.id = l.id
          AND o.stage IN (com.datagami.rentaxis.domain.entity.enums.RenewalStage.OPEN,
                          com.datagami.rentaxis.domain.entity.enums.RenewalStage.INTENT_CAPTURED)
      )
""")
List<Lease> findActiveLeasesEnteringRenewalWindow(@Param("cutoff") LocalDate cutoff);
```

Adjust imports as needed.

- [ ] **Step 5: Compile**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/domain/repository/
git commit -m "feat(repo): renewal opportunity / reminder / interaction repositories"
```

---

## Phase B — Scheduler + reminder firing (Tasks 5–9)

### Task 5: Test fixture factory

**Files:**
- Create: `backend/src/test/java/com/datagami/rentaxis/testsupport/RenewalTestFixtures.java`

- [ ] **Step 1: Write reusable factory helpers**

```java
package com.datagami.rentaxis.testsupport;

import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public class RenewalTestFixtures {

    public static Lease createActiveLease(LandlordOrgRepository orgRepo, UserRepository userRepo,
                                          RenterRepository renterRepo, PropertyRepository propertyRepo,
                                          UnitRepository unitRepo, LeaseRepository leaseRepo,
                                          UUID tenantId, LocalDate startDate, LocalDate endDate) {
        User u = new User();
        u.setEmail("renter+" + UUID.randomUUID() + "@test");
        u.setName("Test Renter");
        u.setRole(UserRole.RENTER);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(tenantId);
        u = userRepo.save(u);

        Renter r = new Renter();
        r.setUserId(u.getId());
        r.setNameEn("Test Renter");
        r.setTenantId(tenantId);
        r = renterRepo.save(r);

        Property p = new Property();
        p.setNameEn("Test Property");
        p.setEmirate(Emirate.DUBAI);
        p.setTenantId(tenantId);
        p = propertyRepo.save(p);

        Unit unit = new Unit();
        unit.setProperty(p);
        unit.setUnitNumber("A1");
        unit.setTenantId(tenantId);
        unit = unitRepo.save(unit);

        Lease lease = new Lease();
        lease.setUnit(unit);
        lease.setRenter(r);
        lease.setTenantId(tenantId);
        lease.setStartDate(startDate);
        lease.setEndDate(endDate);
        lease.setMonthlyRent(BigDecimal.valueOf(5000));
        lease.setStatus(LeaseStatus.ACTIVE);
        return leaseRepo.save(lease);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/test/java/com/datagami/rentaxis/testsupport/RenewalTestFixtures.java
git commit -m "test(renewal): shared fixture factory for lease setup"
```

---

### Task 6: `RenewalOpportunityService` — open + close

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/renewal/RenewalOpportunityService.java`

- [ ] **Step 1: Write the service**

```java
package com.datagami.rentaxis.core.service.renewal;

import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.RenewalOpportunity;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.RenewalOutcome;
import com.datagami.rentaxis.domain.entity.enums.RenewalStage;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.RenewalOpportunityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class RenewalOpportunityService {

    public static final int WINDOW_DAYS = 90;
    private static final List<RenewalStage> OPEN_STAGES =
            List.of(RenewalStage.OPEN, RenewalStage.INTENT_CAPTURED);

    private final LeaseRepository leaseRepository;
    private final RenewalOpportunityRepository opportunityRepository;

    /** Phase 1: open opportunities for ACTIVE leases entering the 90-day window. */
    @Transactional
    public int openOpportunitiesForCurrentTenant(LocalDate today) {
        LocalDate cutoff = today.plusDays(WINDOW_DAYS);
        List<Lease> leases = leaseRepository.findActiveLeasesEnteringRenewalWindow(cutoff);
        int opened = 0;
        for (Lease lease : leases) {
            RenewalOpportunity o = new RenewalOpportunity();
            o.setLease(lease);
            o.setTenantId(lease.getTenantId());
            o.setStage(RenewalStage.OPEN);
            o.setOpenedAt(Instant.now());
            opportunityRepository.save(o);
            opened++;
        }
        if (opened > 0) log.info("Opened {} renewal opportunities", opened);
        return opened;
    }

    /** Phase 3: close opportunities whose lease has resolved. */
    @Transactional
    public int closeStaleOpportunitiesForCurrentTenant() {
        // Pull all open opportunities for this tenant context; close those
        // whose lease has transitioned to EXPIRED / TERMINATED / NOTICE_GIVEN.
        // Caller supplies tenant context via TenantContextHolder.
        List<RenewalOpportunity> open = opportunityRepository
                .findByTenantIdAndStageIn(
                        com.datagami.rentaxis.core.tenant.TenantContextHolder.getTenantId(),
                        OPEN_STAGES);
        int closed = 0;
        Instant now = Instant.now();
        for (RenewalOpportunity o : open) {
            LeaseStatus s = o.getLease().getStatus();
            if (s == LeaseStatus.ACTIVE) continue;

            RenewalOutcome outcome;
            if (s == LeaseStatus.EXPIRED && o.getIntent() == null) {
                outcome = RenewalOutcome.EXPIRED_NO_RESPONSE;
            } else if (s == LeaseStatus.EXPIRED && o.getIntent() == com.datagami.rentaxis.domain.entity.enums.RenewalIntent.MOVE_OUT) {
                outcome = RenewalOutcome.MOVED_OUT;
            } else {
                // TERMINATED, NOTICE_GIVEN, CLOSED → MOVED_OUT
                outcome = RenewalOutcome.MOVED_OUT;
            }
            o.setStage(RenewalStage.CLOSED_LOST);
            o.setOutcome(outcome);
            o.setClosedAt(now);
            opportunityRepository.save(o);
            closed++;
        }
        if (closed > 0) log.info("Closed {} stale renewal opportunities", closed);
        return closed;
    }

    /** Manual close from the PM via mark-renewed endpoint. */
    @Transactional
    public RenewalOpportunity markRenewed(UUID leaseId) {
        RenewalOpportunity o = opportunityRepository
                .findByLeaseIdAndStageIn(leaseId, OPEN_STAGES)
                .orElseThrow(() -> new com.datagami.rentaxis.api.exception.NotFoundException("No open renewal opportunity for lease " + leaseId));
        o.setStage(RenewalStage.CLOSED_WON);
        o.setOutcome(RenewalOutcome.RENEWED);
        o.setClosedAt(Instant.now());
        return opportunityRepository.save(o);
    }
}
```

(Add UUID import.)

- [ ] **Step 2: Write integration test**

Create `backend/src/test/java/com/datagami/rentaxis/core/service/renewal/RenewalOpportunityServiceTest.java`:

```java
package com.datagami.rentaxis.core.service.renewal;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.RenewalOpportunity;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.RenewalStage;
import com.datagami.rentaxis.domain.repository.*;
import com.datagami.rentaxis.testsupport.RenewalTestFixtures;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class RenewalOpportunityServiceTest {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired RenewalOpportunityService service;
    @Autowired LeaseRepository leaseRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired RenewalOpportunityRepository oppRepo;

    UUID tenantId;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("Org-" + UUID.randomUUID());
        org = orgRepo.save(org);
        tenantId = org.getId();
        TenantContextHolder.setTenantId(tenantId);
    }

    @AfterEach
    void tearDown() { TenantContextHolder.clear(); }

    @Test
    void opens_opportunity_for_lease_in_90_day_window() {
        LocalDate today = LocalDate.of(2026, 6, 1);
        Lease lease = RenewalTestFixtures.createActiveLease(orgRepo, userRepo, renterRepo, propertyRepo, unitRepo, leaseRepo, tenantId, today.minusYears(1).plusDays(15), today.plusDays(85));

        int opened = service.openOpportunitiesForCurrentTenant(today);

        assertThat(opened).isEqualTo(1);
        List<RenewalOpportunity> opps = oppRepo.findByTenantIdAndStageIn(tenantId, List.of(RenewalStage.OPEN));
        assertThat(opps).hasSize(1);
        assertThat(opps.get(0).getLease().getId()).isEqualTo(lease.getId());
    }

    @Test
    void does_not_open_for_lease_beyond_90_days() {
        LocalDate today = LocalDate.of(2026, 6, 1);
        RenewalTestFixtures.createActiveLease(orgRepo, userRepo, renterRepo, propertyRepo, unitRepo, leaseRepo, tenantId, today, today.plusDays(200));

        int opened = service.openOpportunitiesForCurrentTenant(today);
        assertThat(opened).isZero();
    }

    @Test
    void idempotent_does_not_open_second_opportunity() {
        LocalDate today = LocalDate.of(2026, 6, 1);
        RenewalTestFixtures.createActiveLease(orgRepo, userRepo, renterRepo, propertyRepo, unitRepo, leaseRepo, tenantId, today.minusYears(1), today.plusDays(85));

        service.openOpportunitiesForCurrentTenant(today);
        int second = service.openOpportunitiesForCurrentTenant(today);

        assertThat(second).isZero();
    }

    @Test
    void closes_opportunity_when_lease_terminated() {
        LocalDate today = LocalDate.of(2026, 6, 1);
        Lease lease = RenewalTestFixtures.createActiveLease(orgRepo, userRepo, renterRepo, propertyRepo, unitRepo, leaseRepo, tenantId, today.minusYears(1), today.plusDays(50));
        service.openOpportunitiesForCurrentTenant(today);

        lease.setStatus(LeaseStatus.TERMINATED);
        leaseRepo.save(lease);

        int closed = service.closeStaleOpportunitiesForCurrentTenant();
        assertThat(closed).isEqualTo(1);
        List<RenewalOpportunity> closedOpps = oppRepo.findByTenantIdAndStageIn(tenantId, List.of(RenewalStage.CLOSED_LOST));
        assertThat(closedOpps).hasSize(1);
    }
}
```

- [ ] **Step 3: Run tests**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.renewal.RenewalOpportunityServiceTest'`
Expected: All 4 pass.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/renewal/RenewalOpportunityService.java backend/src/test/java/com/datagami/rentaxis/core/service/renewal/RenewalOpportunityServiceTest.java
git commit -m "feat(renewal): RenewalOpportunityService — open + close + mark-renewed"
```

---

### Task 7: `RenewalReminderService` — Phase 2 firing

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/renewal/RenewalReminderService.java`

- [ ] **Step 1: Write the service**

```java
package com.datagami.rentaxis.core.service.renewal;

import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.email.event.payload.LeaseRenewalReminderPayload;
import com.datagami.rentaxis.core.service.NotificationService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LeaseReminder;
import com.datagami.rentaxis.domain.entity.RenewalOpportunity;
import com.datagami.rentaxis.domain.entity.enums.RenewalIntent;
import com.datagami.rentaxis.domain.entity.enums.RenewalStage;
import com.datagami.rentaxis.domain.entity.enums.ReminderChannel;
import com.datagami.rentaxis.domain.entity.enums.ReminderStatus;
import com.datagami.rentaxis.domain.repository.LeaseReminderRepository;
import com.datagami.rentaxis.domain.repository.RenewalOpportunityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RenewalReminderService {

    public static final List<Short> SLOTS = List.of((short) 90, (short) 60, (short) 30);
    public static final int MAX_ATTEMPTS = 3;

    private final RenewalOpportunityRepository opportunityRepository;
    private final LeaseReminderRepository reminderRepository;
    private final ApplicationEventPublisher events;
    private final NotificationService notificationService;
    private final RenewalTokenService tokenService;

    @Value("${app.renewal.portal-base-url:https://app.rentaxis.ae}")
    private String portalBaseUrl;

    /**
     * Fire one slot worth of reminders for one opportunity.
     * Runs in its own tx — caller iterates over opportunities.
     */
    @Transactional
    public void fireRemindersForOpportunity(UUID opportunityId, LocalDate today) {
        RenewalOpportunity o = opportunityRepository.findById(opportunityId).orElse(null);
        if (o == null) return;

        // Skip if intent is RENEW/MOVE_OUT — bulk-skip any pending slots.
        if (o.getIntent() == RenewalIntent.RENEW || o.getIntent() == RenewalIntent.MOVE_OUT) {
            reminderRepository.bulkSkipPendingForOpportunity(
                    o.getId(), ReminderStatus.SKIPPED, "intent captured: " + o.getIntent());
            return;
        }

        long daysRemaining = ChronoUnit.DAYS.between(today, o.getLease().getEndDate());

        for (short slot : SLOTS) {
            for (ReminderChannel ch : ReminderChannel.values()) {
                processSlot(o, slot, ch, daysRemaining);
            }
        }
    }

    private void processSlot(RenewalOpportunity o, short slot, ReminderChannel ch, long daysRemaining) {
        // Claim the row via INSERT-or-skip.
        LeaseReminder r;
        try {
            r = new LeaseReminder();
            r.setOpportunity(o);
            r.setTenantId(o.getTenantId());
            r.setSlot(slot);
            r.setChannel(ch);
            r.setStatus(ReminderStatus.PENDING);
            r = reminderRepository.save(r);
        } catch (DataIntegrityViolationException dup) {
            // Already claimed by a previous run; load existing.
            r = reminderRepository.findByOpportunityId(o.getId()).stream()
                    .filter(x -> x.getSlot() == slot && x.getChannel() == ch)
                    .findFirst().orElseThrow();
        }

        if (r.getStatus() != ReminderStatus.PENDING) return;

        // Slot windowing
        long lowerExclusive = slot == 30 ? 0 : (slot == 60 ? 30 : 60);
        long upperInclusive = slot;

        if (daysRemaining <= lowerExclusive) {
            // window already passed
            r.setStatus(ReminderStatus.SKIPPED);
            r.setLastError("window passed at open");
            reminderRepository.save(r);
            return;
        }
        if (daysRemaining > upperInclusive) {
            // not yet eligible; leave PENDING for future runs
            return;
        }

        // Within window — fire.
        try {
            if (ch == ReminderChannel.EMAIL) {
                String renew = tokenService.sign(o.getId(), RenewalIntent.RENEW, o.getLease().getEndDate());
                String moveOut = tokenService.sign(o.getId(), RenewalIntent.MOVE_OUT, o.getLease().getEndDate());
                String discuss = tokenService.sign(o.getId(), RenewalIntent.DISCUSS, o.getLease().getEndDate());

                events.publishEvent(new EmailEvent(this,
                        EmailEventType.LEASE_RENEWAL_REMINDER,
                        o.getTenantId(),
                        new LeaseRenewalReminderPayload(
                                o.getId(),
                                o.getLease().getId(),
                                o.getLease().getRenter().getUserId(),
                                o.getLease().getEndDate().toString(),
                                (int) slot,
                                renew, moveOut, discuss,
                                portalBaseUrl),
                        "LEASE_RENEWAL_REMINDER:" + o.getId() + ":" + slot));
            } else { // IN_APP
                UUID renterUserId = o.getLease().getRenter().getUserId();
                if (renterUserId != null) {
                    notificationService.notifyInAppInNewTx(
                            o.getTenantId(), renterUserId,
                            "LEASE_RENEWAL_REMINDER",
                            "Your lease ends in " + slot + " days",
                            "Lease ends " + o.getLease().getEndDate() + ". Tap to choose your renewal option.",
                            "LEASE", o.getLease().getId());
                }
            }
            r.setStatus(ReminderStatus.SENT);
            r.setSentAt(Instant.now());
            reminderRepository.save(r);
        } catch (Exception e) {
            r.setAttemptCount(r.getAttemptCount() + 1);
            r.setLastError(e.getMessage());
            if (r.getAttemptCount() >= MAX_ATTEMPTS) {
                r.setStatus(ReminderStatus.FAILED);
            }
            reminderRepository.save(r);
            log.warn("Reminder send failed for opp {} slot {} channel {}: {} (attempt {})", o.getId(), slot, ch, e.getMessage(), r.getAttemptCount());
        }
    }
}
```

- [ ] **Step 2: Write integration test**

`backend/src/test/java/com/datagami/rentaxis/core/service/renewal/RenewalReminderServiceTest.java`:

```java
package com.datagami.rentaxis.core.service.renewal;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.*;
import com.datagami.rentaxis.testsupport.RenewalTestFixtures;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class RenewalReminderServiceTest {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired RenewalReminderService service;
    @Autowired RenewalOpportunityRepository oppRepo;
    @Autowired LeaseReminderRepository reminderRepo;
    @Autowired LeaseRepository leaseRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired UnitRepository unitRepo;

    UUID tenantId;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("Org-" + UUID.randomUUID());
        org = orgRepo.save(org);
        tenantId = org.getId();
        TenantContextHolder.setTenantId(tenantId);
    }

    @AfterEach
    void tearDown() { TenantContextHolder.clear(); }

    private RenewalOpportunity openOppForLeaseDaysOut(int daysOut) {
        LocalDate today = LocalDate.of(2026, 6, 1);
        Lease lease = RenewalTestFixtures.createActiveLease(orgRepo, userRepo, renterRepo, propertyRepo, unitRepo, leaseRepo, tenantId, today.minusYears(1), today.plusDays(daysOut));
        RenewalOpportunity o = new RenewalOpportunity();
        o.setLease(lease);
        o.setTenantId(tenantId);
        o.setStage(RenewalStage.OPEN);
        o.setOpenedAt(Instant.now());
        return oppRepo.save(o);
    }

    @Test
    void fires_90_day_reminder_when_in_window() {
        RenewalOpportunity o = openOppForLeaseDaysOut(85);
        service.fireRemindersForOpportunity(o.getId(), LocalDate.of(2026, 6, 1));

        List<LeaseReminder> reminders = reminderRepo.findByOpportunityId(o.getId());
        long sent90 = reminders.stream().filter(r -> r.getSlot() == 90 && r.getStatus() == ReminderStatus.SENT).count();
        assertThat(sent90).isEqualTo(2); // EMAIL + IN_APP
    }

    @Test
    void skips_passed_window_at_open() {
        RenewalOpportunity o = openOppForLeaseDaysOut(45); // 90-slot window is past
        service.fireRemindersForOpportunity(o.getId(), LocalDate.of(2026, 6, 1));

        List<LeaseReminder> reminders = reminderRepo.findByOpportunityId(o.getId());
        long skipped90 = reminders.stream().filter(r -> r.getSlot() == 90 && r.getStatus() == ReminderStatus.SKIPPED).count();
        assertThat(skipped90).isEqualTo(2);
        long sent60 = reminders.stream().filter(r -> r.getSlot() == 60 && r.getStatus() == ReminderStatus.SENT).count();
        assertThat(sent60).isEqualTo(2);
    }

    @Test
    void idempotent_second_call_does_not_duplicate() {
        RenewalOpportunity o = openOppForLeaseDaysOut(85);
        service.fireRemindersForOpportunity(o.getId(), LocalDate.of(2026, 6, 1));
        int firstCount = reminderRepo.findByOpportunityId(o.getId()).size();

        service.fireRemindersForOpportunity(o.getId(), LocalDate.of(2026, 6, 1));
        int secondCount = reminderRepo.findByOpportunityId(o.getId()).size();

        assertThat(secondCount).isEqualTo(firstCount);
    }

    @Test
    void skips_remaining_when_intent_is_RENEW() {
        RenewalOpportunity o = openOppForLeaseDaysOut(85);
        // First fire — 90-day reminders go out
        service.fireRemindersForOpportunity(o.getId(), LocalDate.of(2026, 6, 1));
        // Capture RENEW intent
        o.setIntent(RenewalIntent.RENEW);
        o.setStage(RenewalStage.INTENT_CAPTURED);
        oppRepo.save(o);
        // Second fire 30 days later — 60-day window is current but should be skipped
        service.fireRemindersForOpportunity(o.getId(), LocalDate.of(2026, 7, 1));

        List<LeaseReminder> reminders = reminderRepo.findByOpportunityId(o.getId());
        long skipped = reminders.stream().filter(r -> r.getStatus() == ReminderStatus.SKIPPED && "intent captured: RENEW".equals(r.getLastError())).count();
        assertThat(skipped).isGreaterThanOrEqualTo(2);
    }

    @Test
    void DISCUSS_intent_does_NOT_skip_remaining() {
        RenewalOpportunity o = openOppForLeaseDaysOut(85);
        service.fireRemindersForOpportunity(o.getId(), LocalDate.of(2026, 6, 1));
        o.setIntent(RenewalIntent.DISCUSS);
        o.setStage(RenewalStage.INTENT_CAPTURED);
        oppRepo.save(o);
        // 60-day window
        service.fireRemindersForOpportunity(o.getId(), LocalDate.of(2026, 7, 1));

        long sent60 = reminderRepo.findByOpportunityId(o.getId()).stream().filter(r -> r.getSlot() == 60 && r.getStatus() == ReminderStatus.SENT).count();
        assertThat(sent60).isEqualTo(2);
    }
}
```

Note: This test requires `LeaseRenewalReminderPayload` (Task 10) and `RenewalTokenService` (Task 13) to compile. Stub each minimally before this task or run this test only after Task 13 completes. Add a small `// stub` `RenewalTokenService` returning `"stub-token"` in Task 13 BEFORE writing the real one if needed for incremental compilation, OR re-order tasks so Tasks 10 + 13 land before this test.

**Decision:** the plan re-orders such that Task 10 (event type + payload) and Task 13 (token service stub) land before Task 7's test. Move tasks if a sub-skill is enforcing strict TDD ordering — otherwise commit Task 7's service with a TODO stub of the payload + token service that returns dummy values, then revisit when 10 + 13 complete. (Inline implementer note.)

- [ ] **Step 3: Compile & run**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.renewal.RenewalReminderServiceTest'`
Expected: 5/5 pass after Tasks 10 + 13 complete.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/renewal/RenewalReminderService.java backend/src/test/java/com/datagami/rentaxis/core/service/renewal/RenewalReminderServiceTest.java
git commit -m "feat(renewal): per-slot reminder firing with idempotent ledger"
```

---

### Task 8: `LeaseRenewalScheduler` cron entry point

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/renewal/LeaseRenewalScheduler.java`

- [ ] **Step 1: Write the scheduler**

```java
package com.datagami.rentaxis.core.service.renewal;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.RenewalOpportunity;
import com.datagami.rentaxis.domain.entity.enums.RenewalStage;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.RenewalOpportunityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class LeaseRenewalScheduler {

    private final LandlordOrgRepository orgRepository;
    private final RenewalOpportunityRepository opportunityRepository;
    private final RenewalOpportunityService opportunityService;
    private final RenewalReminderService reminderService;

    @Value("${app.renewal.scheduler.enabled:false}")
    private boolean enabled;

    @Scheduled(cron = "0 0 8 * * *")
    public void runDaily() {
        if (!enabled) {
            log.debug("Renewal scheduler disabled via flag");
            return;
        }
        runNow(LocalDate.now());
    }

    /** Public entry point usable from a SUPER_ADMIN ops endpoint. */
    public void runNow(LocalDate today) {
        log.info("Lease renewal scheduler starting for {}", today);
        TenantContextHolder.clear();
        try {
            List<LandlordOrg> tenants = orgRepository.findAll();
            for (LandlordOrg org : tenants) {
                processTenant(org.getId(), today);
            }
        } finally {
            TenantContextHolder.clear();
        }
        log.info("Lease renewal scheduler finished");
    }

    private void processTenant(java.util.UUID tenantId, LocalDate today) {
        TenantContextHolder.setTenantId(tenantId);
        try {
            int opened = opportunityService.openOpportunitiesForCurrentTenant(today);
            List<RenewalOpportunity> active = opportunityRepository
                    .findByTenantIdAndStageIn(tenantId, List.of(RenewalStage.OPEN, RenewalStage.INTENT_CAPTURED));
            int fired = 0;
            for (RenewalOpportunity o : active) {
                try {
                    reminderService.fireRemindersForOpportunity(o.getId(), today);
                    fired++;
                } catch (Exception e) {
                    log.error("Failed to process reminders for opportunity {}: {}", o.getId(), e.getMessage(), e);
                }
            }
            int closed = opportunityService.closeStaleOpportunitiesForCurrentTenant();
            log.info("Tenant {}: opened={}, fired={}, closed={}", tenantId, opened, fired, closed);
        } catch (Exception e) {
            log.error("Tenant {} processing failed: {}", tenantId, e.getMessage(), e);
        } finally {
            TenantContextHolder.clear();
        }
    }
}
```

- [ ] **Step 2: Add tenant-isolation test**

Create `backend/src/test/java/com/datagami/rentaxis/core/service/renewal/LeaseRenewalSchedulerIntegrationTest.java` with at minimum these tests:

```java
@Test
void cross_tenant_isolation_no_leak() {
    // Build two tenants, each with one ACTIVE lease at 85 days out.
    // Run scheduler. Verify each tenant has exactly 1 opportunity + 2 SENT reminders.
}

@Test
void terminated_lease_closes_with_MOVED_OUT() {
    // 85-day lease, open opp, fire 90-day. Terminate. Run scheduler.
    // Opp closes CLOSED_LOST + MOVED_OUT, remaining slots SKIPPED.
}
```

(Full body follows the pattern from Tasks 6 and 7. Use `RenewalTestFixtures` and run via `scheduler.runNow(today)` instead of waiting for the cron.)

- [ ] **Step 3: Run tests**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.renewal.LeaseRenewalSchedulerIntegrationTest'`
Expected: tests pass.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/renewal/LeaseRenewalScheduler.java backend/src/test/java/com/datagami/rentaxis/core/service/renewal/LeaseRenewalSchedulerIntegrationTest.java
git commit -m "feat(renewal): LeaseRenewalScheduler — daily cron, per-tenant loop"
```

---

### Task 9: Wire feature flag + application.yml

**Files:**
- Modify: `backend/src/main/resources/application.yml`

- [ ] **Step 1: Add config keys**

Append under appropriate root:

```yaml
app:
  renewal:
    scheduler:
      enabled: ${APP_RENEWAL_SCHEDULER_ENABLED:false}
    token-secret: ${APP_RENEWAL_TOKEN_SECRET:dev-only-please-rotate-in-prod-min-32-bytes-please}
    portal-base-url: ${APP_RENEWAL_PORTAL_BASE_URL:http://localhost:3000}
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/resources/application.yml
git commit -m "chore(config): renewal scheduler feature flag + token secret"
```

---

## Phase C — Email template + EmailEventType (Tasks 10–12)

### Task 10: EmailEventType + payloads

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/email/EmailEventType.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/core/email/event/payload/LeaseRenewalReminderPayload.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/core/email/event/payload/RenewalIntentCapturedPayload.java`

- [ ] **Step 1: Add enum entries**

In `EmailEventType.java`, under the `// Lease` section add:
```java
LEASE_RENEWAL_REMINDER  (TRANSACTIONAL, NONE,       EnumSet.of(RENTER)),
RENEWAL_INTENT_CAPTURED (TRANSACTIONAL, NONE,       EnumSet.of(TENANT_ADMIN, PROPERTY_MANAGER)),
```

- [ ] **Step 2: Create reminder payload**

```java
package com.datagami.rentaxis.core.email.event.payload;

import java.util.UUID;

public record LeaseRenewalReminderPayload(
        UUID opportunityId,
        UUID leaseId,
        UUID renterUserId,
        String leaseEndDateIso,
        int slot,
        String renewToken,
        String moveOutToken,
        String discussToken,
        String portalBaseUrl
) {}
```

- [ ] **Step 3: Create intent-captured payload**

```java
package com.datagami.rentaxis.core.email.event.payload;

import java.util.UUID;

public record RenewalIntentCapturedPayload(
        UUID opportunityId,
        UUID leaseId,
        UUID renterUserId,
        String renterName,
        String unitNumber,
        String propertyNameEn,
        String intent
) {}
```

- [ ] **Step 4: Wire RecipientResolver**

In `RecipientResolver.userIdsFor(role, payload)` (existing switch), add cases for the new payloads to resolve recipient user ids per role. Pattern: pull `renterUserId` from `LeaseRenewalReminderPayload` for the RENTER role; for `RenewalIntentCapturedPayload`, fall back to tenant admins via the existing tenant-admin lookup path.

- [ ] **Step 5: Compile**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/email/
git commit -m "feat(email): LEASE_RENEWAL_REMINDER + RENEWAL_INTENT_CAPTURED event types"
```

---

### Task 11: Email templates (EN + AR)

**Files:**
- Create: `backend/src/main/resources/email/templates/lease-renewal-reminder.html`
- Create: `backend/src/main/resources/email/templates/lease-renewal-reminder_ar.html`
- Create: `backend/src/main/resources/email/templates/renewal-intent-captured.html`
- Create: `backend/src/main/resources/email/templates/renewal-intent-captured_ar.html`

- [ ] **Step 1: Write `lease-renewal-reminder.html`**

Mirror the layout of an existing template (`payment-due-reminder.html` is a good starting point — copy and adapt). The body should include:
- Subject (provided via the email pipeline as `Your lease ends in {slot} days`)
- Renter name + lease address
- 3 CTA buttons rendered as `<a href="{{portalBaseUrl}}/dashboard/renter-portal/renewal-intent?token={{renewToken}}">I want to renew</a>` and similar for move-out / discuss
- Footer pointing to the portal for any other lease management

Identical structure for `_ar.html` with translated copy and `dir="rtl"`.

- [ ] **Step 2: Write `renewal-intent-captured.html` (PM-facing)**

Single body block: "Renter {{renterName}} selected: **{{intent}}** for unit {{unitNumber}} at {{propertyNameEn}}. Open the lease to follow up."

Same for `_ar.html`.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/email/templates/lease-renewal-reminder.html backend/src/main/resources/email/templates/lease-renewal-reminder_ar.html backend/src/main/resources/email/templates/renewal-intent-captured.html backend/src/main/resources/email/templates/renewal-intent-captured_ar.html
git commit -m "feat(email): lease-renewal-reminder + intent-captured templates"
```

---

### Task 12: Wire payload variables in `PayloadVarsExtractor`

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/email/dispatch/PayloadVarsExtractor.java`

- [ ] **Step 1: Extend the extractor**

Add an instanceof branch for `LeaseRenewalReminderPayload` that emits a `Map<String, Object>` with keys: `slot`, `endDate`, `portalBaseUrl`, `renewToken`, `moveOutToken`, `discussToken` for template substitution. Same for `RenewalIntentCapturedPayload` emitting `renterName`, `intent`, `unitNumber`, `propertyNameEn`.

(Look at the existing `ChequePayload` branch as a model.)

- [ ] **Step 2: Compile + run any email-render tests**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/email/dispatch/PayloadVarsExtractor.java
git commit -m "feat(email): payload var extraction for renewal events"
```

---

## Phase D — Magic-link tokens + public intent endpoint (Tasks 13–16)

### Task 13: `RenewalTokenService` HMAC JWT

**Files:**
- Modify: `backend/build.gradle` — add `io.jsonwebtoken:jjwt-api:0.12.6`, `runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")`, `runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")`
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/renewal/RenewalTokenService.java`
- Create: `backend/src/test/java/com/datagami/rentaxis/core/service/renewal/RenewalTokenServiceTest.java`

- [ ] **Step 1: Add jjwt to build.gradle dependencies block**

```gradle
implementation 'io.jsonwebtoken:jjwt-api:0.12.6'
runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.12.6'
runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.12.6'
```

- [ ] **Step 2: Write the service**

```java
package com.datagami.rentaxis.core.service.renewal;

import com.datagami.rentaxis.domain.entity.enums.RenewalIntent;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;

@Service
@Slf4j
public class RenewalTokenService {

    private final SecretKey key;

    public RenewalTokenService(@Value("${app.renewal.token-secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) throw new IllegalStateException("app.renewal.token-secret must be ≥32 bytes");
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    public String sign(UUID opportunityId, RenewalIntent intent, LocalDate leaseEndDate) {
        Date exp = Date.from(leaseEndDate.plusDays(7).atStartOfDay(ZoneOffset.UTC).toInstant());
        return Jwts.builder()
                .claim("oid", opportunityId.toString())
                .claim("int", intent.name())
                .expiration(exp)
                .signWith(key)
                .compact();
    }

    public record VerifiedToken(UUID opportunityId, RenewalIntent intent) {}

    public VerifiedToken verify(String token) throws TokenInvalidException, TokenExpiredException {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            if (claims.getExpiration() != null && claims.getExpiration().toInstant().isBefore(Instant.now())) {
                throw new TokenExpiredException();
            }
            UUID oid = UUID.fromString(claims.get("oid", String.class));
            RenewalIntent intent = RenewalIntent.valueOf(claims.get("int", String.class));
            return new VerifiedToken(oid, intent);
        } catch (io.jsonwebtoken.ExpiredJwtException e) {
            throw new TokenExpiredException();
        } catch (JwtException | IllegalArgumentException e) {
            throw new TokenInvalidException(e.getMessage());
        }
    }

    public static class TokenInvalidException extends Exception {
        public TokenInvalidException(String m) { super(m); }
    }
    public static class TokenExpiredException extends Exception {}
}
```

- [ ] **Step 3: Write tests**

`RenewalTokenServiceTest.java`:

```java
package com.datagami.rentaxis.core.service.renewal;

import com.datagami.rentaxis.domain.entity.enums.RenewalIntent;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RenewalTokenServiceTest {

    private final RenewalTokenService service =
            new RenewalTokenService("test-secret-at-least-32-bytes-long-please-x");

    @Test
    void round_trip_signs_and_verifies() throws Exception {
        UUID opp = UUID.randomUUID();
        String t = service.sign(opp, RenewalIntent.RENEW, LocalDate.now().plusDays(30));

        var v = service.verify(t);
        assertThat(v.opportunityId()).isEqualTo(opp);
        assertThat(v.intent()).isEqualTo(RenewalIntent.RENEW);
    }

    @Test
    void expired_token_rejected() {
        String t = service.sign(UUID.randomUUID(), RenewalIntent.RENEW, LocalDate.now().minusDays(30));
        assertThatThrownBy(() -> service.verify(t))
                .isInstanceOf(RenewalTokenService.TokenExpiredException.class);
    }

    @Test
    void tampered_signature_rejected() {
        String t = service.sign(UUID.randomUUID(), RenewalIntent.RENEW, LocalDate.now().plusDays(30));
        String tampered = t.substring(0, t.length() - 4) + "abcd";
        assertThatThrownBy(() -> service.verify(tampered))
                .isInstanceOf(RenewalTokenService.TokenInvalidException.class);
    }
}
```

- [ ] **Step 4: Run tests**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.renewal.RenewalTokenServiceTest'`
Expected: 3/3 pass.

- [ ] **Step 5: Commit**

```bash
git add backend/build.gradle backend/src/main/java/com/datagami/rentaxis/core/service/renewal/RenewalTokenService.java backend/src/test/java/com/datagami/rentaxis/core/service/renewal/RenewalTokenServiceTest.java
git commit -m "feat(renewal): HMAC JWT token service for magic-link intent capture"
```

---

### Task 14: `RenewalIntentService` — capture flow

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/renewal/RenewalIntentService.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/core/email/event/RenewalIntentCapturedEvent.java`

- [ ] **Step 1: Spring app event class**

```java
package com.datagami.rentaxis.core.email.event;

import com.datagami.rentaxis.domain.entity.enums.RenewalIntent;
import java.util.UUID;

public record RenewalIntentCapturedEvent(
        UUID opportunityId,
        UUID leaseId,
        UUID tenantId,
        RenewalIntent intent
) {}
```

- [ ] **Step 2: Service**

```java
package com.datagami.rentaxis.core.service.renewal;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.email.event.RenewalIntentCapturedEvent;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LeaseInteraction;
import com.datagami.rentaxis.domain.entity.RenewalOpportunity;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.LeaseInteractionRepository;
import com.datagami.rentaxis.domain.repository.LeaseReminderRepository;
import com.datagami.rentaxis.domain.repository.RenewalOpportunityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RenewalIntentService {

    private final RenewalOpportunityRepository opportunityRepository;
    private final LeaseInteractionRepository interactionRepository;
    private final LeaseReminderRepository reminderRepository;
    private final ApplicationEventPublisher events;

    /**
     * Capture intent from the public token endpoint (called AFTER token verify
     * and tenant context establishment).
     */
    @Transactional
    public RenewalOpportunity captureIntentFromToken(UUID opportunityId, RenewalIntent intent) {
        RenewalOpportunity o = opportunityRepository.findById(opportunityId)
                .orElseThrow(() -> new NotFoundException("Opportunity not found"));
        if (o.getStage() == RenewalStage.CLOSED_WON || o.getStage() == RenewalStage.CLOSED_LOST) {
            throw new BusinessRuleViolationException("Renewal is already resolved");
        }
        return applyIntent(o, intent, o.getLease().getRenter().getUserId());
    }

    /** Called from the authenticated renter endpoint. */
    @Transactional
    public RenewalOpportunity captureIntentFromRenter(UUID opportunityId, RenewalIntent intent, UUID renterUserId) {
        RenewalOpportunity o = opportunityRepository.findById(opportunityId)
                .orElseThrow(() -> new NotFoundException("Opportunity not found"));
        if (!o.getLease().getRenter().getUserId().equals(renterUserId)) {
            throw new NotFoundException("Opportunity not found"); // hide existence
        }
        if (o.getStage() == RenewalStage.CLOSED_WON || o.getStage() == RenewalStage.CLOSED_LOST) {
            throw new BusinessRuleViolationException("Renewal is already resolved");
        }
        return applyIntent(o, intent, renterUserId);
    }

    private RenewalOpportunity applyIntent(RenewalOpportunity o, RenewalIntent intent, UUID createdBy) {
        RenewalIntent prev = o.getIntent();
        o.setIntent(intent);
        o.setStage(RenewalStage.INTENT_CAPTURED);
        o.setIntentCapturedAt(Instant.now());
        opportunityRepository.save(o);

        // SYSTEM_INTENT interaction
        LeaseInteraction i = new LeaseInteraction();
        i.setTenantId(o.getTenantId());
        i.setOpportunity(o);
        i.setLease(o.getLease());
        i.setType(InteractionType.SYSTEM_INTENT);
        i.setDirection(InteractionDirection.INBOUND);
        i.setOccurredAt(Instant.now());
        String summary = prev == null
                ? "Renter selected: " + intent
                : "Renter changed intent: " + prev + " → " + intent;
        i.setSummary(summary);
        i.setCreatedBy(createdBy);
        interactionRepository.save(i);

        // Bulk-skip pending slots if RENEW/MOVE_OUT
        if (intent == RenewalIntent.RENEW || intent == RenewalIntent.MOVE_OUT) {
            reminderRepository.bulkSkipPendingForOpportunity(
                    o.getId(), ReminderStatus.SKIPPED, "intent captured: " + intent);
        }

        events.publishEvent(new RenewalIntentCapturedEvent(
                o.getId(), o.getLease().getId(), o.getTenantId(), intent));
        return o;
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/renewal/RenewalIntentService.java backend/src/main/java/com/datagami/rentaxis/core/email/event/RenewalIntentCapturedEvent.java
git commit -m "feat(renewal): intent capture service + intent-captured app event"
```

---

### Task 15: `PublicRenewalController` + tests

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/api/PublicRenewalController.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/RenewalIntentRequest.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/RenewalIntentResponse.java`
- Create: `backend/src/test/java/com/datagami/rentaxis/api/PublicRenewalControllerTest.java`

- [ ] **Step 1: DTOs**

```java
package com.datagami.rentaxis.api.dto;
import jakarta.validation.constraints.NotBlank;
public record RenewalIntentRequest(@NotBlank String token) {}
```

```java
package com.datagami.rentaxis.api.dto;
import java.util.UUID;
public record RenewalIntentResponse(String intent, UUID leaseId, String redirectTo) {}
```

- [ ] **Step 2: Controller**

```java
package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.RenewalIntentRequest;
import com.datagami.rentaxis.api.dto.RenewalIntentResponse;
import com.datagami.rentaxis.core.service.renewal.RenewalIntentService;
import com.datagami.rentaxis.core.service.renewal.RenewalTokenService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.RenewalOpportunity;
import com.datagami.rentaxis.domain.repository.RenewalOpportunityRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/public/renewal-intent")
@RequiredArgsConstructor
public class PublicRenewalController {

    private final RenewalTokenService tokenService;
    private final RenewalOpportunityRepository opportunityRepository;
    private final RenewalIntentService intentService;

    @PostMapping
    public ResponseEntity<?> captureIntent(@Valid @RequestBody RenewalIntentRequest req) {
        RenewalTokenService.VerifiedToken v;
        try {
            v = tokenService.verify(req.token());
        } catch (RenewalTokenService.TokenExpiredException e) {
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", "TOKEN_EXPIRED"));
        } catch (RenewalTokenService.TokenInvalidException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "TOKEN_INVALID"));
        }

        RenewalOpportunity o = opportunityRepository.findByIdAcrossTenants(v.opportunityId()).orElse(null);
        if (o == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "NOT_FOUND"));
        }

        TenantContextHolder.setTenantId(o.getTenantId());
        try {
            RenewalOpportunity updated = intentService.captureIntentFromToken(o.getId(), v.intent());
            return ResponseEntity.ok(new RenewalIntentResponse(
                    updated.getIntent().name(),
                    updated.getLease().getId(),
                    "/dashboard/renter-portal/renewals"));
        } catch (com.datagami.rentaxis.api.exception.BusinessRuleViolationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "ALREADY_RESOLVED"));
        } finally {
            TenantContextHolder.clear();
        }
    }
}
```

- [ ] **Step 3: Tests**

Mirror the structure of `LeaseChequeBulkAttachControllerTest`. Cover the 5 cases listed in spec §14.1. Use `@SpringBootTest` + `@AutoConfigureMockMvc` + Testcontainers Postgres. Build a tenant + lease + opportunity via fixtures, sign a token via `RenewalTokenService`, POST it.

- [ ] **Step 4: Run tests**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.api.PublicRenewalControllerTest'`
Expected: 5/5 pass.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api/PublicRenewalController.java backend/src/main/java/com/datagami/rentaxis/api/dto/RenewalIntentRequest.java backend/src/main/java/com/datagami/rentaxis/api/dto/RenewalIntentResponse.java backend/src/test/java/com/datagami/rentaxis/api/PublicRenewalControllerTest.java
git commit -m "feat(api): POST /api/v1/public/renewal-intent for magic-link intent capture"
```

---

### Task 16: `RenewalIntentCapturedListener` — PM notification

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/renewal/RenewalIntentCapturedListener.java`

- [ ] **Step 1: AFTER_COMMIT listener**

```java
package com.datagami.rentaxis.core.service.renewal;

import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.email.event.RenewalIntentCapturedEvent;
import com.datagami.rentaxis.core.email.event.payload.RenewalIntentCapturedPayload;
import com.datagami.rentaxis.core.service.NotificationService;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RenewalIntentCapturedListener {

    private final LeaseRepository leaseRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final ApplicationEventPublisher events;

    @EventListener
    public void onIntentCaptured(RenewalIntentCapturedEvent ev) {
        var lease = leaseRepository.findById(ev.leaseId()).orElseThrow();
        String renterName = lease.getRenter().getNameEn();
        String unitNumber = lease.getUnit().getUnitNumber();
        String propertyName = lease.getUnit().getProperty().getNameEn();

        for (User admin : userRepository.findByTenantIdAndRole(ev.tenantId(), UserRole.TENANT_ADMIN)) {
            notificationService.notifyInAppInNewTx(
                    ev.tenantId(), admin.getId(),
                    "RENEWAL_INTENT",
                    "Renter responded to renewal reminder",
                    renterName + " selected " + ev.intent() + " for lease " + unitNumber,
                    "LEASE", ev.leaseId());
        }
        events.publishEvent(new EmailEvent(this,
                EmailEventType.RENEWAL_INTENT_CAPTURED,
                ev.tenantId(),
                new RenewalIntentCapturedPayload(
                        ev.opportunityId(), ev.leaseId(),
                        lease.getRenter().getUserId(),
                        renterName, unitNumber, propertyName,
                        ev.intent().name()),
                "RENEWAL_INTENT_CAPTURED:" + ev.opportunityId()));
    }
}
```

(Note: `UserRepository.findByTenantIdAndRole` may need to be added if absent. Verify before writing.)

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/renewal/RenewalIntentCapturedListener.java
git commit -m "feat(renewal): PM notification listener on intent capture"
```

---

## Phase E — CRM interactions backend (Tasks 17–20)

### Task 17: `LeaseInteractionService`

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/LeaseInteractionService.java`
- Create: relevant DTOs in `api/dto/`

- [ ] **Step 1: DTOs**

```java
// CreateInteractionRequest.java
package com.datagami.rentaxis.api.dto;
import com.datagami.rentaxis.domain.entity.enums.*;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
public record CreateInteractionRequest(
        @NotNull InteractionType type,
        @NotNull InteractionDirection direction,
        @NotNull @PastOrPresent Instant occurredAt,
        @NotBlank String summary,
        InteractionOutcome outcome,
        @FutureOrPresent LocalDate followUpDate
) {}
```

```java
// UpdateInteractionRequest.java
package com.datagami.rentaxis.api.dto;
import com.datagami.rentaxis.domain.entity.enums.InteractionOutcome;
import jakarta.validation.constraints.FutureOrPresent;
import java.time.LocalDate;
public record UpdateInteractionRequest(
        String summary,
        InteractionOutcome outcome,
        @FutureOrPresent LocalDate followUpDate
) {}
```

```java
// InteractionDTO.java
package com.datagami.rentaxis.api.dto;
import com.datagami.rentaxis.domain.entity.enums.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
public record InteractionDTO(
        UUID id, UUID leaseId, UUID opportunityId,
        InteractionType type, InteractionDirection direction,
        Instant occurredAt, String summary,
        InteractionOutcome outcome, LocalDate followUpDate,
        UUID createdBy, String createdByName, Instant createdAt
) {}
```

- [ ] **Step 2: Service**

```java
package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.*;
import com.datagami.rentaxis.api.exception.*;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LeaseInteraction;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LeaseInteractionService {

    private final LeaseInteractionRepository repo;
    private final LeaseRepository leaseRepo;
    private final RenewalOpportunityRepository oppRepo;
    private final UserRepository userRepo;

    @Transactional(readOnly = true)
    public Page<InteractionDTO> list(UUID leaseId, Pageable pageable) {
        return repo.findActiveByLeaseId(leaseId, pageable).map(this::toDTO);
    }

    @Transactional
    public InteractionDTO create(UUID leaseId, CreateInteractionRequest req, UUID userId) {
        var lease = leaseRepo.findById(leaseId).orElseThrow(() -> new NotFoundException("Lease not found"));
        LeaseInteraction i = new LeaseInteraction();
        i.setTenantId(lease.getTenantId());
        i.setLease(lease);
        i.setOpportunity(oppRepo.findByLeaseIdAndStageIn(leaseId, List.of(RenewalStage.OPEN, RenewalStage.INTENT_CAPTURED)).orElse(null));
        i.setType(req.type());
        i.setDirection(req.direction());
        i.setOccurredAt(req.occurredAt());
        i.setSummary(req.summary());
        i.setOutcome(req.outcome());
        i.setFollowUpDate(req.followUpDate());
        i.setCreatedBy(userId);
        return toDTO(repo.save(i));
    }

    @Transactional
    public InteractionDTO update(UUID interactionId, UpdateInteractionRequest req) {
        LeaseInteraction i = repo.findById(interactionId).orElseThrow(() -> new NotFoundException("Interaction not found"));
        if (i.getType() == InteractionType.SYSTEM_INTENT) {
            throw new BusinessRuleViolationException("SYSTEM_INTENT entries are read-only");
        }
        if (req.summary() != null) i.setSummary(req.summary());
        if (req.outcome() != null) i.setOutcome(req.outcome());
        if (req.followUpDate() != null) i.setFollowUpDate(req.followUpDate());
        return toDTO(repo.save(i));
    }

    @Transactional
    public void softDelete(UUID interactionId) {
        LeaseInteraction i = repo.findById(interactionId).orElseThrow(() -> new NotFoundException("Interaction not found"));
        if (i.getType() == InteractionType.SYSTEM_INTENT) {
            throw new BusinessRuleViolationException("SYSTEM_INTENT entries cannot be deleted");
        }
        i.setDeletedAt(Instant.now());
        repo.save(i);
    }

    private InteractionDTO toDTO(LeaseInteraction i) {
        String createdByName = userRepo.findById(i.getCreatedBy()).map(u -> u.getName()).orElse(null);
        return new InteractionDTO(
                i.getId(), i.getLease().getId(),
                i.getOpportunity() != null ? i.getOpportunity().getId() : null,
                i.getType(), i.getDirection(),
                i.getOccurredAt(), i.getSummary(),
                i.getOutcome(), i.getFollowUpDate(),
                i.getCreatedBy(), createdByName, i.getCreatedAt());
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api/dto/CreateInteractionRequest.java backend/src/main/java/com/datagami/rentaxis/api/dto/UpdateInteractionRequest.java backend/src/main/java/com/datagami/rentaxis/api/dto/InteractionDTO.java backend/src/main/java/com/datagami/rentaxis/core/service/LeaseInteractionService.java
git commit -m "feat(crm): LeaseInteractionService + DTOs"
```

---

### Task 18: `LeaseInteractionController` + tests

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/api/LeaseInteractionController.java`
- Create: `backend/src/test/java/com/datagami/rentaxis/api/LeaseInteractionControllerTest.java`

- [ ] **Step 1: Controller**

```java
package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.*;
import com.datagami.rentaxis.core.service.LeaseInteractionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/leases/{leaseId}/interactions")
@RequiredArgsConstructor
public class LeaseInteractionController {

    private final LeaseInteractionService service;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_TENANT_ADMIN','ROLE_PROPERTY_MANAGER')")
    public Page<InteractionDTO> list(@PathVariable UUID leaseId, Pageable pageable) {
        return service.list(leaseId, pageable);
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_TENANT_ADMIN','ROLE_PROPERTY_MANAGER')")
    public ResponseEntity<InteractionDTO> create(@PathVariable UUID leaseId,
                                                  @Valid @RequestBody CreateInteractionRequest req,
                                                  @AuthenticationPrincipal String userIdStr) {
        return ResponseEntity.status(201).body(service.create(leaseId, req, UUID.fromString(userIdStr)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_TENANT_ADMIN','ROLE_PROPERTY_MANAGER')")
    public InteractionDTO update(@PathVariable UUID id, @Valid @RequestBody UpdateInteractionRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_TENANT_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 2: Tests**

Mirror `LeaseChequeBulkAttachControllerTest`. Cover spec §14.1: PM 201, RENTER 403, SYSTEM_INTENT 422 on edit, cross-tenant 404.

- [ ] **Step 3: Run tests**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.api.LeaseInteractionControllerTest'`
Expected: pass.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api/LeaseInteractionController.java backend/src/test/java/com/datagami/rentaxis/api/LeaseInteractionControllerTest.java
git commit -m "feat(api): CRM interaction CRUD endpoints"
```

---

### Task 19: `PendingFollowUpsController` + test

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/api/PendingFollowUpsController.java`
- Create: `backend/src/test/java/com/datagami/rentaxis/api/PendingFollowUpsControllerTest.java`

- [ ] **Step 1: Controller**

```java
package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.InteractionDTO;
import com.datagami.rentaxis.core.service.LeaseInteractionService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.repository.LeaseInteractionRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/renewals/follow-ups")
@RequiredArgsConstructor
public class PendingFollowUpsController {

    private final LeaseInteractionRepository repo;
    private final UserRepository userRepo;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_TENANT_ADMIN','ROLE_PROPERTY_MANAGER')")
    public List<InteractionDTO> list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate cutoff = date != null ? date : LocalDate.now();
        return repo.findPendingFollowUps(TenantContextHolder.getTenantId(), cutoff)
                .stream().map(i -> new InteractionDTO(
                        i.getId(), i.getLease().getId(),
                        i.getOpportunity() != null ? i.getOpportunity().getId() : null,
                        i.getType(), i.getDirection(),
                        i.getOccurredAt(), i.getSummary(),
                        i.getOutcome(), i.getFollowUpDate(),
                        i.getCreatedBy(),
                        userRepo.findById(i.getCreatedBy()).map(u -> u.getName()).orElse(null),
                        i.getCreatedAt()))
                .collect(Collectors.toList());
    }
}
```

- [ ] **Step 2: Test**

Cover: returns only `follow_up_date <= today`, excludes soft-deleted, excludes `outcome=POSITIVE`, tenant-scoped.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api/PendingFollowUpsController.java backend/src/test/java/com/datagami/rentaxis/api/PendingFollowUpsControllerTest.java
git commit -m "feat(api): pending follow-ups query endpoint"
```

---

### Task 20: Mark-renewed endpoint

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/LeaseController.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/MarkRenewedRequest.java`

- [ ] **Step 1: DTO**

```java
package com.datagami.rentaxis.api.dto;
public record MarkRenewedRequest(String note) {}
```

- [ ] **Step 2: Endpoint on `LeaseController`**

```java
@PostMapping("/{id}/renewal/mark-renewed")
@PreAuthorize("hasAnyAuthority('ROLE_TENANT_ADMIN','ROLE_PROPERTY_MANAGER')")
public ResponseEntity<RenewalOpportunityDTO> markRenewed(
        @PathVariable UUID id,
        @RequestBody(required = false) MarkRenewedRequest req,
        @AuthenticationPrincipal String userIdStr) {
    var opp = renewalOpportunityService.markRenewed(id);
    if (req != null && req.note() != null && !req.note().isBlank()) {
        leaseInteractionService.create(id,
                new CreateInteractionRequest(InteractionType.NOTE, InteractionDirection.INTERNAL,
                        Instant.now(), req.note(), null, null),
                UUID.fromString(userIdStr));
    }
    return ResponseEntity.ok(toDTO(opp));
}
```

(Add `RenewalOpportunityDTO` if needed, or return inline `Map<String, Object>`.)

- [ ] **Step 3: Test**

In `LeaseControllerTest`: closes opportunity to CLOSED_WON, 404 when no open opp.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api/LeaseController.java backend/src/main/java/com/datagami/rentaxis/api/dto/MarkRenewedRequest.java
git commit -m "feat(api): POST /leases/{id}/renewal/mark-renewed"
```

---

## Phase F — CRM PM UI (Tasks 21–24)

### Task 21: `LeaseInteractionsPanel` component + i18n

**Files:**
- Create: `web/src/components/leases/LeaseInteractionsPanel.tsx`
- Create: `web/src/components/leases/__tests__/LeaseInteractionsPanel.test.tsx`
- Modify: `web/messages/en.json`, `web/messages/ar.json`

- [ ] **Step 1: Add i18n keys**

In both `en.json` and `ar.json`, add an `interactions` namespace block before the closing `}` for the relevant top-level group. Keys:
- `panelTitle`, `logInteraction`, `noInteractions`, `loading`
- `type.CALL`, `type.SMS`, `type.EMAIL`, `type.WHATSAPP`, `type.MEETING`, `type.SYSTEM_INTENT`, `type.NOTE`
- `direction.INBOUND`, `direction.OUTBOUND`, `direction.INTERNAL`
- `outcome.POSITIVE`, `outcome.NEUTRAL`, `outcome.NEGATIVE`, `outcome.NO_RESPONSE`
- `followUpDueBadge` with `{count}` placeholder
- `cannotEditSystemEntry`, `confirmDelete`

- [ ] **Step 2: Write the panel**

```tsx
"use client";
import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import LogInteractionDialog from "./LogInteractionDialog";

type Interaction = {
  id: string;
  leaseId: string;
  opportunityId: string | null;
  type: string;
  direction: string;
  occurredAt: string;
  summary: string;
  outcome: string | null;
  followUpDate: string | null;
  createdBy: string;
  createdByName: string | null;
  createdAt: string;
};

export default function LeaseInteractionsPanel({ leaseId }: { leaseId: string }) {
  const t = useTranslations("interactions");
  const [items, setItems] = useState<Interaction[]>([]);
  const [loading, setLoading] = useState(true);
  const [dialogOpen, setDialogOpen] = useState(false);

  const load = async () => {
    setLoading(true);
    const res = await fetch(`/api/proxy/v1/leases/${leaseId}/interactions?size=50&sort=occurredAt,desc`);
    const body = await res.json();
    setItems(body.content ?? []);
    setLoading(false);
  };

  useEffect(() => { void load(); }, [leaseId]);

  return (
    <section className="bg-surface rounded-[var(--radius-lg)] border border-border overflow-hidden">
      <div className="px-5 py-3.5 border-b border-border bg-[var(--sand-50)] flex items-center justify-between">
        <h2 className="text-xs font-semibold text-muted uppercase tracking-wider">{t("panelTitle")}</h2>
        <button type="button" onClick={() => setDialogOpen(true)} className="rounded border border-border px-2 py-1 text-xs hover:bg-input/40">
          + {t("logInteraction")}
        </button>
      </div>
      <div className="p-4">
        {loading ? <p className="text-xs text-muted">{t("loading")}</p>
         : items.length === 0 ? <p className="text-xs text-muted">{t("noInteractions")}</p>
         : (
          <ul className="space-y-3">
            {items.map(i => (
              <li key={i.id} className={"border-l-2 pl-3 " + (i.type === "SYSTEM_INTENT" ? "border-blue-400 opacity-80" : "border-primary")}>
                <p className="text-[11px] text-muted">
                  {new Date(i.occurredAt).toLocaleString()} · {t(`type.${i.type}` as any)} · {t(`direction.${i.direction}` as any)} · {i.createdByName ?? ""}
                </p>
                <p className="text-sm whitespace-pre-wrap">{i.summary}</p>
                {(i.outcome || i.followUpDate) && (
                  <p className="text-[11px] text-muted mt-1">
                    {i.outcome ? `${t(`outcome.${i.outcome}` as any)}` : ""}
                    {i.outcome && i.followUpDate ? " · " : ""}
                    {i.followUpDate ? `Follow-up: ${i.followUpDate}` : ""}
                  </p>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>
      {dialogOpen && (
        <LogInteractionDialog leaseId={leaseId}
                              onClose={() => setDialogOpen(false)}
                              onSuccess={() => { setDialogOpen(false); void load(); }} />
      )}
    </section>
  );
}
```

- [ ] **Step 3: Test**

Render the panel with a stub fetch returning 2 entries (1 SYSTEM_INTENT, 1 CALL). Assert: SYSTEM_INTENT row has `border-blue-400` class; the other doesn't; "Log interaction" button visible.

- [ ] **Step 4: Commit**

```bash
git add web/src/components/leases/LeaseInteractionsPanel.tsx web/src/components/leases/__tests__/LeaseInteractionsPanel.test.tsx web/messages/en.json web/messages/ar.json
git commit -m "feat(crm): LeaseInteractionsPanel + i18n keys"
```

---

### Task 22: `LogInteractionDialog` modal

**Files:**
- Create: `web/src/components/leases/LogInteractionDialog.tsx`
- Create: `web/src/components/leases/__tests__/LogInteractionDialog.test.tsx`

- [ ] **Step 1: Write the modal**

Mirror the a11y pattern from `BulkChequeUploadFlow.tsx` (PR #50): `role="dialog"`, `aria-modal`, `aria-labelledby`, Escape close, focus trap, focus restore via refs + mount-once effect. Form fields: type select, direction radio, occurred-at datetime (default now), summary textarea (required), outcome select, follow-up date picker.

Submit handler: POST `/api/proxy/v1/leases/{leaseId}/interactions`. On success call `onSuccess`.

- [ ] **Step 2: Test**

Modal opens, type is a required field, submit POSTs, calls onSuccess. Use vitest + testing-library + msw or stub `fetch`.

- [ ] **Step 3: Commit**

```bash
git add web/src/components/leases/LogInteractionDialog.tsx web/src/components/leases/__tests__/LogInteractionDialog.test.tsx
git commit -m "feat(crm): LogInteractionDialog modal with a11y"
```

---

### Task 23: Mount panel on lease detail page

**Files:**
- Modify: `web/src/app/[locale]/dashboard/leases/[id]/page.tsx`

- [ ] **Step 1: Add import + mount**

Import: `import LeaseInteractionsPanel from "@/components/leases/LeaseInteractionsPanel";`

Place `<LeaseInteractionsPanel leaseId={leaseId} />` after the Payment Schedule Timeline section (around line 845-859 as anchor), wrapped in the same `hidden when status === DRAFT` check.

- [ ] **Step 2: Tsc + manual smoke**

Run: `cd web && npx tsc --noEmit`
Expected: clean.

- [ ] **Step 3: Commit**

```bash
git add web/src/app/[locale]/dashboard/leases/[id]/page.tsx
git commit -m "feat(leases): mount interactions panel on lease detail"
```

---

### Task 24: `FollowUpsWidget` on PM dashboard

**Files:**
- Create: `web/src/components/dashboard/FollowUpsWidget.tsx`
- Create: `web/src/components/dashboard/__tests__/FollowUpsWidget.test.tsx`
- Modify: `web/src/app/[locale]/dashboard/page.tsx`
- Modify: i18n files: `followUpsWidget.title`, `followUpsWidget.empty`, `followUpsWidget.linkLabel`

- [ ] **Step 1: Widget**

```tsx
"use client";
import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { Link } from "@/i18n/routing";

export default function FollowUpsWidget() {
  const t = useTranslations("followUpsWidget");
  const [items, setItems] = useState<any[]>([]);
  const [count, setCount] = useState(0);

  useEffect(() => {
    (async () => {
      const res = await fetch("/api/proxy/v1/renewals/follow-ups");
      if (!res.ok) return;
      const body = await res.json();
      setItems(body.slice(0, 5));
      setCount(body.length);
    })();
  }, []);

  return (
    <div className="bg-surface rounded-[var(--radius-lg)] border border-border p-4">
      <h3 className="text-xs font-semibold text-muted uppercase tracking-wider mb-2">
        {t("title")} {count > 0 && `(${count})`}
      </h3>
      {items.length === 0 ? <p className="text-xs text-muted">{t("empty")}</p>
       : <ul className="space-y-2">
           {items.map(i => (
             <li key={i.id} className="text-xs">
               <Link href={`/dashboard/leases/${i.leaseId}`} className="hover:underline">
                 {i.followUpDate} · {i.summary.slice(0, 60)}
               </Link>
             </li>
           ))}
         </ul>}
    </div>
  );
}
```

- [ ] **Step 2: Mount on dashboard**

Add `<FollowUpsWidget />` to `dashboard/page.tsx` in the widget grid area (locate by reading existing structure).

- [ ] **Step 3: Test**

Empty state renders the empty string. Populated state renders 5 entries max.

- [ ] **Step 4: Commit**

```bash
git add web/src/components/dashboard/FollowUpsWidget.tsx web/src/components/dashboard/__tests__/FollowUpsWidget.test.tsx web/src/app/[locale]/dashboard/page.tsx web/messages/en.json web/messages/ar.json
git commit -m "feat(crm): FollowUpsWidget on PM dashboard"
```

---

## Phase G — Renter portal renewals (Tasks 25–28)

### Task 25: `RenterRenewalController` + tests

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/api/RenterRenewalController.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/RenewalSummaryDTO.java`
- Create: `backend/src/test/java/com/datagami/rentaxis/api/RenterRenewalControllerTest.java`

- [ ] **Step 1: DTO**

```java
package com.datagami.rentaxis.api.dto;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
public record RenewalSummaryDTO(
        List<LeaseRenewalView> leases
) {
    public record LeaseRenewalView(
            UUID leaseId, String unitNumber, String propertyNameEn,
            LocalDate endDate, long daysRemaining,
            UUID opportunityId, String stage, String intent,
            List<ReminderEntry> reminders
    ) {}
    public record ReminderEntry(int slot, String status, LocalDate sentAt) {}
}
```

- [ ] **Step 2: Controller**

```java
@RestController
@RequestMapping("/api/v1/me/renewals")
@RequiredArgsConstructor
public class RenterRenewalController {

    private final LeaseRepository leaseRepo;
    private final RenewalOpportunityRepository oppRepo;
    private final LeaseReminderRepository reminderRepo;
    private final RenewalIntentService intentService;

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_RENTER')")
    public RenewalSummaryDTO summary(@AuthenticationPrincipal String userIdStr) {
        UUID userId = UUID.fromString(userIdStr);
        // find renter's leases, build view
        // ...
    }

    @PostMapping("/{opportunityId}/intent")
    @PreAuthorize("hasAuthority('ROLE_RENTER')")
    public ResponseEntity<?> setIntent(@PathVariable UUID opportunityId,
                                        @RequestBody Map<String, String> body,
                                        @AuthenticationPrincipal String userIdStr) {
        var intent = RenewalIntent.valueOf(body.get("intent"));
        intentService.captureIntentFromRenter(opportunityId, intent, UUID.fromString(userIdStr));
        return ResponseEntity.ok().build();
    }
}
```

(Fill in `summary` body: list leases via `leaseRepo.findByRenterUserId(userId)` — may need to add this method; or join through `Renter` entity. Build `LeaseRenewalView` per lease.)

- [ ] **Step 3: Tests**

Renter sees only own leases; cross-renter access → 404; intent set persists; intent for non-existent opportunity → 404.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api/RenterRenewalController.java backend/src/main/java/com/datagami/rentaxis/api/dto/RenewalSummaryDTO.java backend/src/test/java/com/datagami/rentaxis/api/RenterRenewalControllerTest.java
git commit -m "feat(api): renter renewals summary + intent endpoints"
```

---

### Task 26: `RenewalCard` + `RenewalIntentConfirm` components

**Files:**
- Create: `web/src/components/renewals/RenewalCard.tsx`
- Create: `web/src/components/renewals/RenewalIntentConfirm.tsx`
- Create: `web/src/components/renewals/__tests__/RenewalCard.test.tsx`
- Create: `web/src/components/renewals/__tests__/RenewalIntentConfirm.test.tsx`
- Modify: i18n files with `renewals` namespace

- [ ] **Step 1: i18n keys**

Under `renewals` in en/ar JSON: bucket labels (`within30`, `within60`, `within90`, `beyond90`), button labels (`renew`, `moveOut`, `discuss`), `statusNoResponseYet`, `statusYouSelected`, `changeYourMind`, `remindersSentLabel`, `confirmIntent`, `intent.RENEW`, `intent.MOVE_OUT`, `intent.DISCUSS`.

- [ ] **Step 2: RenewalCard**

```tsx
"use client";
import { useTranslations } from "next-intl";

type Props = {
  leaseId: string;
  unitNumber: string;
  propertyNameEn: string;
  endDate: string;
  daysRemaining: number;
  opportunityId: string | null;
  stage: string | null;
  intent: string | null;
  reminders: { slot: number; status: string; sentAt: string | null }[];
  onSetIntent?: (intent: "RENEW" | "MOVE_OUT" | "DISCUSS") => void;
};

export default function RenewalCard(p: Props) {
  const t = useTranslations("renewals");
  if (!p.opportunityId) {
    return (
      <div className="rounded border border-border p-3 text-xs text-muted">
        {t("beyond90Hint")}
      </div>
    );
  }
  const noIntent = p.intent == null;
  return (
    <div className="rounded border border-border p-4">
      <p className="text-sm font-medium">{p.propertyNameEn} · {p.unitNumber}</p>
      <p className="text-xs text-muted">{t("endDateLine", { date: p.endDate, days: p.daysRemaining })}</p>
      {noIntent
        ? <div className="mt-3 flex gap-2">
            <button onClick={() => p.onSetIntent?.("RENEW")} className="rounded bg-primary text-primary-foreground px-3 py-1 text-xs">{t("renew")}</button>
            <button onClick={() => p.onSetIntent?.("MOVE_OUT")} className="rounded border border-border px-3 py-1 text-xs">{t("moveOut")}</button>
            <button onClick={() => p.onSetIntent?.("DISCUSS")} className="rounded border border-border px-3 py-1 text-xs">{t("discuss")}</button>
          </div>
        : <p className="mt-2 text-xs text-green-700">✅ {t("statusYouSelected", { intent: t(`intent.${p.intent}` as any) })}</p>}
      <p className="mt-3 text-[11px] text-muted">
        {t("remindersSentLabel")}: {p.reminders.filter(r => r.status === "SENT").map(r => `${r.slot}d (${r.sentAt})`).join(", ") || "—"}
      </p>
    </div>
  );
}
```

- [ ] **Step 3: RenewalIntentConfirm**

```tsx
"use client";
import { useState } from "react";
import { useTranslations } from "next-intl";

export default function RenewalIntentConfirm({ token, intent }: { token: string; intent: string }) {
  const t = useTranslations("renewals");
  const [status, setStatus] = useState<"idle"|"submitting"|"done"|"expired"|"error">("idle");

  const submit = async () => {
    setStatus("submitting");
    const res = await fetch("/api/proxy/v1/public/renewal-intent", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ token }),
    });
    if (res.status === 410) setStatus("expired");
    else if (!res.ok) setStatus("error");
    else {
      setStatus("done");
      const body = await res.json();
      setTimeout(() => { window.location.href = body.redirectTo; }, 1200);
    }
  };

  return (
    <div className="rounded border border-border p-6 max-w-md mx-auto mt-12">
      <h2 className="text-lg font-semibold">{t("confirmIntent")}: {t(`intent.${intent}` as any)}</h2>
      {status === "expired" && <p className="mt-2 text-sm text-red-700">{t("tokenExpired")}</p>}
      {status === "error" && <p className="mt-2 text-sm text-red-700">{t("genericError")}</p>}
      {status !== "done" && (
        <button onClick={submit} disabled={status === "submitting"}
                className="mt-4 rounded bg-primary text-primary-foreground px-4 py-2 text-sm disabled:opacity-50">
          {status === "submitting" ? "…" : t("confirm")}
        </button>
      )}
      {status === "done" && <p className="mt-3 text-sm text-green-700">{t("captured")}</p>}
    </div>
  );
}
```

- [ ] **Step 4: Tests**

`RenewalCard`: with `opportunityId=null` renders beyond-90 hint; with intent set renders confirmation; buttons fire callback.
`RenewalIntentConfirm`: 410 path renders expired message; success path navigates after 1200ms.

- [ ] **Step 5: Commit**

```bash
git add web/src/components/renewals/RenewalCard.tsx web/src/components/renewals/RenewalIntentConfirm.tsx web/src/components/renewals/__tests__/ web/messages/en.json web/messages/ar.json
git commit -m "feat(renewals): RenewalCard + RenewalIntentConfirm components"
```

---

### Task 27: Renter renewals page

**Files:**
- Create: `web/src/app/[locale]/dashboard/renter-portal/renewals/page.tsx`

- [ ] **Step 1: Page**

```tsx
"use client";
import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import RenewalCard from "@/components/renewals/RenewalCard";

export default function RenterRenewalsPage() {
  const t = useTranslations("renewals");
  const [data, setData] = useState<any>(null);

  const load = async () => {
    const res = await fetch("/api/proxy/v1/me/renewals");
    setData(await res.json());
  };
  useEffect(() => { void load(); }, []);

  const setIntent = async (opportunityId: string, intent: string) => {
    await fetch(`/api/proxy/v1/me/renewals/${opportunityId}/intent`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ intent }),
    });
    await load();
  };

  if (!data) return <p className="p-6 text-sm text-muted">{t("loading")}</p>;

  const buckets = [
    { key: "within30", filter: (d: number) => d <= 30 },
    { key: "within60", filter: (d: number) => d > 30 && d <= 60 },
    { key: "within90", filter: (d: number) => d > 60 && d <= 90 },
    { key: "beyond90", filter: (d: number) => d > 90 },
  ];

  return (
    <div className="p-6 space-y-6">
      <h1 className="text-lg font-semibold">{t("pageTitle")}</h1>
      {buckets.map(b => {
        const leases = (data.leases as any[]).filter(l => b.filter(l.daysRemaining));
        return (
          <section key={b.key}>
            <h2 className="text-sm font-medium mb-2">{t(b.key)}</h2>
            {leases.length === 0
              ? <p className="text-xs text-muted">{t("emptyBucket")}</p>
              : <div className="space-y-2">
                  {leases.map(l => <RenewalCard key={l.leaseId} {...l} onSetIntent={(i) => setIntent(l.opportunityId, i)} />)}
                </div>}
          </section>
        );
      })}
    </div>
  );
}
```

- [ ] **Step 2: Commit**

```bash
git add web/src/app/[locale]/dashboard/renter-portal/renewals/page.tsx
git commit -m "feat(renter): bucketed renewals dashboard page"
```

---

### Task 28: Magic-link confirm page

**Files:**
- Create: `web/src/app/[locale]/dashboard/renter-portal/renewal-intent/page.tsx`

- [ ] **Step 1: Page**

```tsx
"use client";
import { useSearchParams } from "next/navigation";
import RenewalIntentConfirm from "@/components/renewals/RenewalIntentConfirm";

export default function RenewalIntentPage() {
  const sp = useSearchParams();
  const token = sp.get("token") ?? "";
  const intent = sp.get("intent") ?? "";  // optional hint pulled from email; verified server-side
  if (!token) return <p className="p-6">Invalid link</p>;
  return <RenewalIntentConfirm token={token} intent={intent} />;
}
```

Note: the intent is *also* embedded inside the JWT, so the URL hint isn't load-bearing — it only affects what's displayed before the user clicks Confirm. The server validates from the JWT.

- [ ] **Step 2: Commit**

```bash
git add web/src/app/[locale]/dashboard/renter-portal/renewal-intent/page.tsx
git commit -m "feat(renter): magic-link confirm landing page"
```

---

## Phase H — Rollout (Tasks 29–30)

### Task 29: Ops endpoint + renewal banner

**Files:**
- Modify: existing admin/superadmin controller (look for an existing super-admin scoped controller; e.g., `SuperAdminController` if one exists)
- Create: `web/src/components/renewals/RenewalBanner.tsx`
- Modify: `web/src/app/[locale]/dashboard/renter-portal/page.tsx`

- [ ] **Step 1: Ops endpoint**

Add to whichever controller already serves SUPER_ADMIN actions:
```java
@PostMapping("/admin/renewals/run-now")
@PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
public ResponseEntity<Void> runRenewalsNow() {
    scheduler.runNow(LocalDate.now());
    return ResponseEntity.accepted().build();
}
```

If no suitable controller exists, create `OpsRenewalController` under `/api/v1/admin/`.

- [ ] **Step 2: RenewalBanner**

```tsx
"use client";
import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { Link } from "@/i18n/routing";

export default function RenewalBanner() {
  const t = useTranslations("renewals");
  const [active, setActive] = useState<{ days: number } | null>(null);

  useEffect(() => {
    (async () => {
      const res = await fetch("/api/proxy/v1/me/renewals");
      if (!res.ok) return;
      const body = await res.json();
      const inWindow = (body.leases ?? []).filter((l: any) => l.opportunityId).sort((a: any, b: any) => a.daysRemaining - b.daysRemaining)[0];
      if (inWindow) setActive({ days: inWindow.daysRemaining });
    })();
  }, []);

  if (!active) return null;
  return (
    <div className="rounded border border-border bg-[var(--sand-50)] p-3 mb-4 flex items-center justify-between">
      <p className="text-sm">⏰ {t("bannerMessage", { days: active.days })}</p>
      <Link href="/dashboard/renter-portal/renewals" className="text-xs underline">{t("bannerCta")}</Link>
    </div>
  );
}
```

Mount on `renter-portal/page.tsx` at the top.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api/ web/src/components/renewals/RenewalBanner.tsx web/src/app/[locale]/dashboard/renter-portal/page.tsx web/messages/en.json web/messages/ar.json
git commit -m "feat(renewal): ops trigger endpoint + renter portal renewal banner"
```

---

### Task 30: Final integration smoke + flag enablement README

**Files:**
- Modify: `README.md` or `backend/README.md` — add a "Renewal feature" subsection with env-var documentation

- [ ] **Step 1: README**

Document:
- `APP_RENEWAL_SCHEDULER_ENABLED` — toggle the daily cron (default false; flip true after staging smoke)
- `APP_RENEWAL_TOKEN_SECRET` — HMAC secret, ≥32 bytes
- `APP_RENEWAL_PORTAL_BASE_URL` — base URL embedded in renewal emails (no trailing slash)
- `POST /api/v1/admin/renewals/run-now` — SUPER_ADMIN ops trigger

Mention the manual close path (`POST /api/v1/leases/{id}/renewal/mark-renewed`).

- [ ] **Step 2: Run full backend test suite to confirm nothing regressed**

Run: `cd backend && ./gradlew test`
Expected: 299+/302 pass (3 pre-existing Liquibase ownership failures unrelated to this work — see PR #50 history).

- [ ] **Step 3: Run frontend tsc + tests**

Run: `cd web && npx tsc --noEmit && npm test -- --run`
Expected: clean + all green.

- [ ] **Step 4: Commit + push branch**

```bash
git add README.md
git commit -m "docs: renewal feature env vars + ops endpoint"
git push -u origin <feature-branch>
```

---

## Self-Review Checklist

This list is for the engineer / dispatcher to verify the plan is implementable. I (the planner) ran through it once before saving:

- ✅ Spec §6 (data model) → Tasks 1–3
- ✅ Spec §7 (scheduler) → Tasks 6–9
- ✅ Spec §8 (email + magic link + intent capture) → Tasks 10–16
- ✅ Spec §9 (CRM backend + UI) → Tasks 17–24
- ✅ Spec §10 (renter portal) → Tasks 25–28
- ✅ Spec §11 (i18n) → Tasks 21, 24, 26 (incremental)
- ✅ Spec §12 (edge cases) — addressed by service logic (see Tasks 6, 7, 14)
- ✅ Spec §13 (multi-tenancy) — addressed by `findByIdAcrossTenants` + tenant filter (Tasks 4, 15)
- ✅ Spec §14 (testing) → embedded in each backend service/controller task; frontend tasks 21, 22, 24, 26
- ✅ Spec §15 (observability) — left to follow-up unless explicitly required (consider adding Micrometer counters as a small Task 30b)
- ✅ Spec §16 (rollout) → Tasks 9, 29, 30
- ✅ Spec §17 (post-MVP) — not in plan, by design

**Gap:** Spec §15 Micrometer counters aren't tasked. Add a lightweight Task 30b if observability is a launch requirement; otherwise log-only is sufficient.
