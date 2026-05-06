# Email Notifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `NotificationService`'s inline Azure ACS email sender with a durable, multi-tenant, bilingual (EN/AR) outbox-based email pipeline covering Phase 1 of the event catalog from `docs/superpowers/specs/2026-05-06-email-notifications-design.md`.

**Architecture:** Business services publish typed `EmailEvent` ApplicationEvents → `EmailDispatcher` (`@TransactionalEventListener(AFTER_COMMIT)`) resolves recipients/locale/preferences and writes one row per recipient to `email_outbox` → `EmailOutboxWorker` (`@Scheduled` + ShedLock) picks up PENDING rows with `FOR UPDATE SKIP LOCKED`, renders via Thymeleaf + Spring `MessageSource`, sends via Azure ACS, retries with exponential backoff.

**Tech Stack:** Java 21, Spring Boot 4.0.3, Spring Data JPA, Liquibase, PostgreSQL 16, Azure Communication Services Email (`com.azure:azure-communication-email:1.0.9`, already on classpath), Thymeleaf (NEW dep), ShedLock (NEW dep).

---

## File Structure

**New packages:**
```
backend/src/main/java/com/datagami/rentaxis/core/email/
├── EmailEventType.java                  enum + per-value catalog
├── EmailCategory.java                   enum
├── RecipientRole.java                   enum
├── event/
│   ├── EmailEvent.java                  base ApplicationEvent
│   └── payload/                         one record per event
│       ├── UserInvitedPayload.java
│       ├── LeaseSignedPayload.java
│       ├── ChequeBouncedPayload.java
│       └── ...
├── dispatch/
│   ├── EmailDispatcher.java
│   ├── RecipientResolver.java
│   ├── EmailPreferenceService.java
│   └── TenantBrandingResolver.java
├── render/
│   ├── EmailRenderer.java
│   ├── EmailTemplateContext.java
│   ├── EmailRenderResult.java
│   └── AttachmentBuilder.java
├── outbox/
│   ├── EmailOutbox.java                 JPA entity
│   ├── EmailOutboxRepository.java
│   ├── EmailOutboxService.java
│   └── EmailOutboxWorker.java
├── send/
│   ├── EmailSender.java                 interface
│   ├── AzureAcsEmailSender.java
│   └── SendResult.java
├── prefs/
│   ├── EmailPreferences.java            JPA entity
│   └── EmailPreferencesRepository.java
└── api/
    ├── UnsubscribeController.java
    ├── EmailPreferencesController.java
    └── EmailOutboxAdminController.java
```

**New resources:**
```
backend/src/main/resources/
├── templates/email/
│   ├── layout/
│   │   ├── master.html                  LTR base
│   │   └── master-rtl.html              RTL mirror
│   └── events/
│       ├── user_invited.html
│       ├── user_welcomed.html
│       ├── password_reset_requested.html
│       ├── password_changed.html
│       ├── email_verified.html
│       ├── lease_created.html
│       ├── lease_contract_generated.html
│       ├── lease_signature_requested.html
│       ├── lease_signed.html
│       ├── lease_activated.html
│       ├── lease_expiring.html
│       ├── lease_renewed.html
│       ├── lease_terminated.html
│       ├── cheque_received.html
│       ├── cheque_deposited.html
│       ├── cheque_cleared.html
│       ├── cheque_bounced.html
│       ├── payment_due_reminder.html
│       ├── payment_overdue.html
│       ├── online_payment_received.html
│       ├── online_payment_failed.html
│       ├── rent_receipt_available.html
│       ├── penalty_incurred.html
│       ├── penalty_cleared.html
│       ├── penalty_waived.html
│       ├── ticket_assigned.html
│       ├── ticket_reply.html
│       ├── ticket_resolved.html
│       ├── ticket_created.html
│       ├── ticket_reopened.html
│       ├── meeting_requested.html
│       ├── meeting_approved.html
│       ├── meeting_cancelled.html
│       ├── meeting_completed.html
│       ├── meeting_no_show.html
│       ├── tenant_provisioned.html
│       ├── tenant_admin_added.html
│       └── staff_role_changed.html
└── messages/
    ├── email_en.properties
    └── email_ar.properties
```

**New Liquibase changesets:** `backend/src/main/resources/db/changelog/changesets/32-email-outbox.yaml`, `32-email-preferences.yaml`.

**Modified files:**
- `backend/build.gradle` — add Thymeleaf + ShedLock deps
- `backend/src/main/java/com/datagami/rentaxis/core/service/NotificationService.java` — strip inline send, publish events instead
- Various service classes — emit new events (`UserService`, `LandlordOrgService`, `MaintenanceTicketService`, `MeetingService`, `PenaltyService`, `PaymentScheduleService`, `OnlinePaymentService`, lease services, cheque services, auth services)
- `backend/src/main/resources/db/changelog/db.changelog-master.yaml` — register new changesets

---

## Task 1: Add Thymeleaf and ShedLock dependencies

**Files:**
- Modify: `backend/build.gradle`

- [x] **Step 1: Add dependencies**

Open `backend/build.gradle` and add to the `dependencies` block (alphabetical order around existing entries):

```gradle
dependencies {
    // ...existing deps...
    implementation 'org.springframework.boot:spring-boot-starter-thymeleaf'
    implementation 'net.javacrumbs.shedlock:shedlock-spring:5.16.0'
    implementation 'net.javacrumbs.shedlock:shedlock-provider-jdbc-template:5.16.0'
    // ...existing deps...
}
```

- [x] **Step 2: Verify build**

Run: `cd backend && ./gradlew build -x test`
Expected: `BUILD SUCCESSFUL`. New deps appear in `./gradlew dependencies` output.

- [x] **Step 3: Commit**

```bash
git add backend/build.gradle
git commit -m "chore(email): add Thymeleaf + ShedLock for email pipeline"
```

---

## Task 2: Liquibase 32 — email_outbox table

**Files:**
- Create: `backend/src/main/resources/db/changelog/changesets/32-email-outbox.yaml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml`

- [x] **Step 1: Write changeset**

Create `backend/src/main/resources/db/changelog/changesets/32-email-outbox.yaml`:

> Adapted for current append-only repo state: implemented as `53-email-outbox.yaml` because `32-fix-table-permissions.yaml` through `52-cheque-image-purge-partial-index.yaml` already exist.

```yaml
databaseChangeLog:
  - changeSet:
      id: 32-create-email-outbox-table
      author: rentaxis
      changes:
        - createTable:
            tableName: email_outbox
            columns:
              - column: { name: id, type: uuid, defaultValueComputed: gen_random_uuid(), constraints: { primaryKey: true, nullable: false } }
              - column: { name: tenant_id, type: uuid }
              - column: { name: event_type, type: varchar(60), constraints: { nullable: false } }
              - column: { name: event_category, type: varchar(20), constraints: { nullable: false } }
              - column: { name: recipient_user_id, type: uuid, constraints: { nullable: false } }
              - column: { name: recipient_email, type: varchar(320), constraints: { nullable: false } }
              - column: { name: recipient_locale, type: varchar(5), constraints: { nullable: false } }
              - column: { name: subject, type: varchar(500), constraints: { nullable: false } }
              - column: { name: body_html, type: text, constraints: { nullable: false } }
              - column: { name: body_text, type: text }
              - column: { name: attachments, type: jsonb }
              - column: { name: status, type: varchar(20), constraints: { nullable: false }, defaultValue: PENDING }
              - column: { name: scheduled_at, type: timestamp, defaultValueComputed: now(), constraints: { nullable: false } }
              - column: { name: attempts, type: int, defaultValueNumeric: 0, constraints: { nullable: false } }
              - column: { name: max_attempts, type: int, defaultValueNumeric: 5, constraints: { nullable: false } }
              - column: { name: last_attempt_at, type: timestamp }
              - column: { name: last_error, type: text }
              - column: { name: azure_message_id, type: varchar(100) }
              - column: { name: azure_delivery_status, type: varchar(30) }
              - column: { name: reference_type, type: varchar(30) }
              - column: { name: reference_id, type: uuid }
              - column: { name: dedup_key, type: varchar(200) }
              - column: { name: trace_id, type: varchar(50) }
              - column: { name: created_at, type: timestamp, defaultValueComputed: now(), constraints: { nullable: false } }
              - column: { name: updated_at, type: timestamp, defaultValueComputed: now(), constraints: { nullable: false } }
        - createIndex:
            indexName: idx_email_outbox_status_scheduled_at
            tableName: email_outbox
            columns: [{ column: { name: status } }, { column: { name: scheduled_at } }]
        - createIndex:
            indexName: idx_email_outbox_recipient_user_id
            tableName: email_outbox
            columns: [{ column: { name: recipient_user_id } }, { column: { name: created_at } }]
        - createIndex:
            indexName: idx_email_outbox_event_type
            tableName: email_outbox
            columns: [{ column: { name: event_type } }, { column: { name: created_at } }]
        - createIndex:
            indexName: idx_email_outbox_tenant_id
            tableName: email_outbox
            columns: [{ column: { name: tenant_id } }, { column: { name: created_at } }]
        - createIndex:
            indexName: idx_email_outbox_azure_message_id
            tableName: email_outbox
            columns: [{ column: { name: azure_message_id } }]
        - createIndex:
            indexName: idx_email_outbox_dedup_key
            tableName: email_outbox
            unique: true
            columns: [{ column: { name: dedup_key } }]

  - changeSet:
      id: 32-create-shedlock-table
      author: rentaxis
      changes:
        - createTable:
            tableName: shedlock
            columns:
              - column: { name: name, type: varchar(64), constraints: { primaryKey: true, nullable: false } }
              - column: { name: lock_until, type: timestamp, constraints: { nullable: false } }
              - column: { name: locked_at, type: timestamp, constraints: { nullable: false } }
              - column: { name: locked_by, type: varchar(255), constraints: { nullable: false } }
```

- [x] **Step 2: Register changeset in master**

Edit `backend/src/main/resources/db/changelog/db.changelog-master.yaml` and append (after the entry for `31-notifications-nullable-tenant.yaml`):

```yaml
  - include:
      file: db/changelog/changesets/32-email-outbox.yaml
```

- [x] **Step 3: Run migration locally**

Run: `cd backend && ./gradlew bootRun` (CTRL-C after Liquibase reports `Update successful`)
Expected: Liquibase logs show `32-create-email-outbox-table::rentaxis ran successfully` and `32-create-shedlock-table::rentaxis ran successfully`. No errors.

- [x] **Step 4: Verify schema**

Run: `docker compose exec db psql -U rentaxis -d rentaxis -c "\d email_outbox"` (or equivalent)
Expected: All columns and indexes from step 1 are present.

- [x] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/changelog/
git commit -m "feat(email): add email_outbox + shedlock tables (changeset 32)"
```

---

## Task 3: Liquibase 32 — email_preferences table + backfill

**Files:**
- Modify: `backend/src/main/resources/db/changelog/changesets/32-email-outbox.yaml` (append more changesets)

- [x] **Step 1: Append changesets**

Append to `32-email-outbox.yaml`:

> Adapted for current append-only repo state: appended to `53-email-outbox.yaml` with `53-*` changeset IDs.

```yaml
  - changeSet:
      id: 32-create-email-preferences-table
      author: rentaxis
      changes:
        - createTable:
            tableName: email_preferences
            columns:
              - column:
                  name: user_id
                  type: uuid
                  constraints:
                    primaryKey: true
                    nullable: false
                    foreignKeyName: fk_email_prefs_user
                    references: users(id)
              - column: { name: marketing_enabled, type: boolean, defaultValueBoolean: true, constraints: { nullable: false } }
              - column: { name: preferences_json, type: jsonb, defaultValue: '{}', constraints: { nullable: false } }
              - column: { name: unsubscribe_token, type: varchar(64), constraints: { nullable: false, unique: true } }
              - column: { name: updated_at, type: timestamp, defaultValueComputed: now(), constraints: { nullable: false } }

  - changeSet:
      id: 32-backfill-email-preferences
      author: rentaxis
      changes:
        - sql:
            sql: |
              INSERT INTO email_preferences (user_id, marketing_enabled, preferences_json, unsubscribe_token, updated_at)
              SELECT u.id, true, '{}'::jsonb,
                     replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', ''),
                     now()
              FROM users u
              WHERE NOT EXISTS (SELECT 1 FROM email_preferences ep WHERE ep.user_id = u.id);
```

- [x] **Step 2: Run migration**

Run: `cd backend && ./gradlew bootRun`
Expected: New changesets execute. Stop after success.

- [x] **Step 3: Verify backfill**

Run: `docker compose exec db psql -U rentaxis -d rentaxis -c "SELECT count(*) FROM email_preferences;"`
Expected: count equals `SELECT count(*) FROM users;`.

- [x] **Step 4: Commit**

```bash
git add backend/src/main/resources/db/changelog/changesets/32-email-outbox.yaml
git commit -m "feat(email): add email_preferences with backfill (changeset 32)"
```

---

## Task 4: Core enums (EmailCategory, RecipientRole, EmailEventType)

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/email/EmailCategory.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/core/email/RecipientRole.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/core/email/EmailEventType.java`
- Create: `backend/src/test/java/com/datagami/rentaxis/core/email/EmailEventTypeTest.java`

- [x] **Step 1: Write the failing test**

Create `backend/src/test/java/com/datagami/rentaxis/core/email/EmailEventTypeTest.java`:

```java
package com.datagami.rentaxis.core.email;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EmailEventTypeTest {

    @Test
    void chequeBouncedIsTransactional() {
        assertEquals(EmailCategory.TRANSACTIONAL, EmailEventType.CHEQUE_BOUNCED.category());
    }

    @Test
    void chequeBouncedRecipientsIncludeRenterAndManager() {
        assertTrue(EmailEventType.CHEQUE_BOUNCED.recipientRoles().contains(RecipientRole.RENTER));
        assertTrue(EmailEventType.CHEQUE_BOUNCED.recipientRoles().contains(RecipientRole.PROPERTY_MANAGER));
    }

    @Test
    void rentReceiptHasPdfAttachmentPolicy() {
        assertEquals(EmailEventType.AttachmentPolicy.PDF,
                EmailEventType.RENT_RECEIPT_AVAILABLE.attachmentPolicy());
    }

    @Test
    void leaseContractHasSignedUrlAttachmentPolicy() {
        assertEquals(EmailEventType.AttachmentPolicy.SIGNED_URL,
                EmailEventType.LEASE_CONTRACT_GENERATED.attachmentPolicy());
    }

    @Test
    void snakeReturnsLowercaseSnakeCase() {
        assertEquals("cheque_bounced", EmailEventType.CHEQUE_BOUNCED.snake());
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests EmailEventTypeTest`
Expected: FAIL with `cannot find symbol class EmailEventType`.

- [x] **Step 3: Implement EmailCategory**

Create `backend/src/main/java/com/datagami/rentaxis/core/email/EmailCategory.java`:

```java
package com.datagami.rentaxis.core.email;

public enum EmailCategory {
    TRANSACTIONAL,
    MARKETING
}
```

- [x] **Step 4: Implement RecipientRole**

Create `backend/src/main/java/com/datagami/rentaxis/core/email/RecipientRole.java`:

```java
package com.datagami.rentaxis.core.email;

public enum RecipientRole {
    USER,
    INVITEE,
    RENTER,
    PROPERTY_MANAGER,
    TENANT_ADMIN,
    SUPER_ADMIN,
    NEW_ADMIN,
    EXISTING_ADMINS
}
```

- [x] **Step 5: Implement EmailEventType**

Create `backend/src/main/java/com/datagami/rentaxis/core/email/EmailEventType.java`:

```java
package com.datagami.rentaxis.core.email;

import java.util.EnumSet;
import java.util.Set;

import static com.datagami.rentaxis.core.email.EmailCategory.TRANSACTIONAL;
import static com.datagami.rentaxis.core.email.EmailEventType.AttachmentPolicy.NONE;
import static com.datagami.rentaxis.core.email.EmailEventType.AttachmentPolicy.PDF;
import static com.datagami.rentaxis.core.email.EmailEventType.AttachmentPolicy.SIGNED_URL;
import static com.datagami.rentaxis.core.email.RecipientRole.*;

public enum EmailEventType {
    // Auth & Onboarding
    USER_INVITED            (TRANSACTIONAL, NONE,       EnumSet.of(INVITEE)),
    USER_WELCOMED           (TRANSACTIONAL, NONE,       EnumSet.of(USER)),
    PASSWORD_RESET_REQUESTED(TRANSACTIONAL, NONE,       EnumSet.of(USER)),
    PASSWORD_CHANGED        (TRANSACTIONAL, NONE,       EnumSet.of(USER)),
    EMAIL_VERIFIED          (TRANSACTIONAL, NONE,       EnumSet.of(USER)),

    // Tenant / Org
    TENANT_PROVISIONED      (TRANSACTIONAL, NONE,       EnumSet.of(TENANT_ADMIN, SUPER_ADMIN)),
    TENANT_ADMIN_ADDED      (TRANSACTIONAL, NONE,       EnumSet.of(NEW_ADMIN, EXISTING_ADMINS)),
    STAFF_ROLE_CHANGED      (TRANSACTIONAL, NONE,       EnumSet.of(USER, TENANT_ADMIN)),

    // Lease
    LEASE_CREATED           (TRANSACTIONAL, NONE,       EnumSet.of(RENTER, PROPERTY_MANAGER)),
    LEASE_CONTRACT_GENERATED(TRANSACTIONAL, SIGNED_URL, EnumSet.of(RENTER, PROPERTY_MANAGER)),
    LEASE_SIGNATURE_REQUESTED(TRANSACTIONAL, NONE,      EnumSet.of(RENTER)),
    LEASE_SIGNED            (TRANSACTIONAL, NONE,       EnumSet.of(RENTER, PROPERTY_MANAGER)),
    LEASE_ACTIVATED         (TRANSACTIONAL, NONE,       EnumSet.of(RENTER, PROPERTY_MANAGER)),
    LEASE_EXPIRING          (TRANSACTIONAL, NONE,       EnumSet.of(RENTER, PROPERTY_MANAGER)),
    LEASE_RENEWED           (TRANSACTIONAL, NONE,       EnumSet.of(RENTER, PROPERTY_MANAGER)),
    LEASE_TERMINATED        (TRANSACTIONAL, NONE,       EnumSet.of(RENTER, PROPERTY_MANAGER)),

    // Cheque & Payment
    CHEQUE_RECEIVED         (TRANSACTIONAL, NONE,       EnumSet.of(RENTER, PROPERTY_MANAGER)),
    CHEQUE_DEPOSITED        (TRANSACTIONAL, NONE,       EnumSet.of(RENTER, PROPERTY_MANAGER)),
    CHEQUE_CLEARED          (TRANSACTIONAL, NONE,       EnumSet.of(RENTER, PROPERTY_MANAGER)),
    CHEQUE_BOUNCED          (TRANSACTIONAL, NONE,       EnumSet.of(RENTER, PROPERTY_MANAGER)),
    PAYMENT_DUE_REMINDER    (TRANSACTIONAL, NONE,       EnumSet.of(RENTER)),
    PAYMENT_OVERDUE         (TRANSACTIONAL, NONE,       EnumSet.of(RENTER, PROPERTY_MANAGER)),
    ONLINE_PAYMENT_RECEIVED (TRANSACTIONAL, NONE,       EnumSet.of(RENTER, PROPERTY_MANAGER)),
    ONLINE_PAYMENT_FAILED   (TRANSACTIONAL, NONE,       EnumSet.of(RENTER)),
    RENT_RECEIPT_AVAILABLE  (TRANSACTIONAL, PDF,        EnumSet.of(RENTER)),

    // Penalties
    PENALTY_INCURRED        (TRANSACTIONAL, NONE,       EnumSet.of(RENTER, PROPERTY_MANAGER)),
    PENALTY_CLEARED         (TRANSACTIONAL, NONE,       EnumSet.of(RENTER, PROPERTY_MANAGER)),
    PENALTY_WAIVED          (TRANSACTIONAL, NONE,       EnumSet.of(RENTER, PROPERTY_MANAGER)),

    // Tickets
    TICKET_ASSIGNED         (TRANSACTIONAL, NONE,       EnumSet.of(USER)),
    TICKET_REPLY            (TRANSACTIONAL, NONE,       EnumSet.of(USER)),
    TICKET_RESOLVED         (TRANSACTIONAL, NONE,       EnumSet.of(USER)),
    TICKET_CREATED          (TRANSACTIONAL, NONE,       EnumSet.of(PROPERTY_MANAGER)),
    TICKET_REOPENED         (TRANSACTIONAL, NONE,       EnumSet.of(PROPERTY_MANAGER, RENTER)),

    // Meetings
    MEETING_REQUESTED       (TRANSACTIONAL, NONE,       EnumSet.of(USER)),
    MEETING_APPROVED        (TRANSACTIONAL, NONE,       EnumSet.of(USER)),
    MEETING_CANCELLED       (TRANSACTIONAL, NONE,       EnumSet.of(USER)),
    MEETING_COMPLETED       (TRANSACTIONAL, NONE,       EnumSet.of(USER)),
    MEETING_NO_SHOW         (TRANSACTIONAL, NONE,       EnumSet.of(USER));

    public enum AttachmentPolicy { NONE, PDF, SIGNED_URL }

    private final EmailCategory category;
    private final AttachmentPolicy attachmentPolicy;
    private final Set<RecipientRole> recipientRoles;

    EmailEventType(EmailCategory category, AttachmentPolicy attachmentPolicy, Set<RecipientRole> recipientRoles) {
        this.category = category;
        this.attachmentPolicy = attachmentPolicy;
        this.recipientRoles = recipientRoles;
    }

    public EmailCategory category() { return category; }
    public AttachmentPolicy attachmentPolicy() { return attachmentPolicy; }
    public Set<RecipientRole> recipientRoles() { return recipientRoles; }
    public String snake() { return name().toLowerCase(); }
}
```

- [x] **Step 6: Run tests**

Run: `cd backend && ./gradlew test --tests EmailEventTypeTest`
Expected: All 5 tests PASS.

- [x] **Step 7: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/email/ backend/src/test/java/com/datagami/rentaxis/core/email/
git commit -m "feat(email): catalog enums (EmailEventType, EmailCategory, RecipientRole)"
```

---

## Task 5: EmailOutbox JPA entity

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/email/outbox/EmailOutbox.java`

- [x] **Step 1: Implement entity**

Create `backend/src/main/java/com/datagami/rentaxis/core/email/outbox/EmailOutbox.java`:

```java
package com.datagami.rentaxis.core.email.outbox;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "email_outbox")
@Getter
@Setter
public class EmailOutbox {

    public enum Status { PENDING, SENDING, SENT, FAILED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(name = "event_category", nullable = false, length = 20)
    private String eventCategory;

    @Column(name = "recipient_user_id", nullable = false)
    private UUID recipientUserId;

    @Column(name = "recipient_email", nullable = false, length = 320)
    private String recipientEmail;

    @Column(name = "recipient_locale", nullable = false, length = 5)
    private String recipientLocale;

    @Column(nullable = false, length = 500)
    private String subject;

    @Column(name = "body_html", nullable = false, columnDefinition = "text")
    private String bodyHtml;

    @Column(name = "body_text", columnDefinition = "text")
    private String bodyText;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String attachments;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 5;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "azure_message_id", length = 100)
    private String azureMessageId;

    @Column(name = "azure_delivery_status", length = 30)
    private String azureDeliveryStatus;

    @Column(name = "reference_type", length = 30)
    private String referenceType;

    @Column(name = "reference_id")
    private UUID referenceId;

    @Column(name = "dedup_key", length = 200)
    private String dedupKey;

    @Column(name = "trace_id", length = 50)
    private String traceId;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (scheduledAt == null) scheduledAt = Instant.now();
    }
}
```

> **Note:** If `io.hypersistence.utils.hibernate.type.json.JsonType` isn't on the classpath, drop the import — `@JdbcTypeCode(SqlTypes.JSON)` alone (Hibernate 6+) is sufficient with a `String` field.

- [x] **Step 2: Verify compile**

Run: `cd backend && ./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [x] **Step 3: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/email/outbox/EmailOutbox.java
git commit -m "feat(email): EmailOutbox JPA entity"
```

---

## Task 6: EmailOutboxRepository with FOR UPDATE SKIP LOCKED pickup

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/email/outbox/EmailOutboxRepository.java`
- Create: `backend/src/test/java/com/datagami/rentaxis/core/email/outbox/EmailOutboxRepositoryTest.java`

- [x] **Step 1: Write failing repository integration test**

Create `backend/src/test/java/com/datagami/rentaxis/core/email/outbox/EmailOutboxRepositoryTest.java`:

```java
package com.datagami.rentaxis.core.email.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Testcontainers
class EmailOutboxRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired EmailOutboxRepository repo;

    @Test
    void pickPendingReturnsOnlyDuePendingRows() {
        EmailOutbox future = newRow(EmailOutbox.Status.PENDING, Instant.now().plusSeconds(60));
        EmailOutbox dueNow = newRow(EmailOutbox.Status.PENDING, Instant.now().minusSeconds(1));
        EmailOutbox sent  = newRow(EmailOutbox.Status.SENT,    Instant.now().minusSeconds(1));
        repo.saveAll(List.of(future, dueNow, sent));

        List<EmailOutbox> picked = repo.pickPending(10);

        assertThat(picked).extracting(EmailOutbox::getId).containsExactly(dueNow.getId());
    }

    private EmailOutbox newRow(EmailOutbox.Status s, Instant scheduledAt) {
        EmailOutbox o = new EmailOutbox();
        o.setEventType("LEASE_SIGNED");
        o.setEventCategory("TRANSACTIONAL");
        o.setRecipientUserId(UUID.randomUUID());
        o.setRecipientEmail("a@b.test");
        o.setRecipientLocale("en");
        o.setSubject("s");
        o.setBodyHtml("<p>x</p>");
        o.setStatus(s);
        o.setScheduledAt(scheduledAt);
        return o;
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests EmailOutboxRepositoryTest`
Expected: FAIL with `cannot find symbol EmailOutboxRepository`.

- [x] **Step 3: Implement repository**

Create `backend/src/main/java/com/datagami/rentaxis/core/email/outbox/EmailOutboxRepository.java`:

```java
package com.datagami.rentaxis.core.email.outbox;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface EmailOutboxRepository extends JpaRepository<EmailOutbox, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query(value = """
        SELECT * FROM email_outbox
        WHERE status = 'PENDING'
          AND scheduled_at <= now()
        ORDER BY scheduled_at ASC
        FOR UPDATE SKIP LOCKED
        LIMIT :limit
        """, nativeQuery = true)
    List<EmailOutbox> pickPending(@Param("limit") int limit);

    @Query("""
        SELECT o FROM EmailOutbox o
        WHERE o.status = com.datagami.rentaxis.core.email.outbox.EmailOutbox$Status.SENDING
          AND o.lastAttemptAt < :cutoff
        """)
    List<EmailOutbox> findStuckSending(@Param("cutoff") Instant cutoff);
}
```

- [x] **Step 4: Run test**

Run: `cd backend && ./gradlew test --tests EmailOutboxRepositoryTest`
Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/email/outbox/EmailOutboxRepository.java backend/src/test/java/com/datagami/rentaxis/core/email/outbox/EmailOutboxRepositoryTest.java
git commit -m "feat(email): EmailOutboxRepository with skip-locked pickup"
```

---

## Task 7: EmailPreferences entity + repository

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/email/prefs/EmailPreferences.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/core/email/prefs/EmailPreferencesRepository.java`

- [x] **Step 1: Implement entity**

Create `backend/src/main/java/com/datagami/rentaxis/core/email/prefs/EmailPreferences.java`:

```java
package com.datagami.rentaxis.core.email.prefs;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "email_preferences")
@Getter
@Setter
public class EmailPreferences {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "marketing_enabled", nullable = false)
    private boolean marketingEnabled = true;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "preferences_json", nullable = false, columnDefinition = "jsonb")
    private String preferencesJson = "{}";

    @Column(name = "unsubscribe_token", nullable = false, unique = true, length = 64)
    private String unsubscribeToken;

    @Column(name = "updated_at", nullable = false, insertable = false)
    private Instant updatedAt;
}
```

- [x] **Step 2: Implement repository**

Create `backend/src/main/java/com/datagami/rentaxis/core/email/prefs/EmailPreferencesRepository.java`:

```java
package com.datagami.rentaxis.core.email.prefs;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EmailPreferencesRepository extends JpaRepository<EmailPreferences, UUID> {
    Optional<EmailPreferences> findByUnsubscribeToken(String token);
}
```

- [x] **Step 3: Verify compile**

Run: `cd backend && ./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [x] **Step 4: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/email/prefs/
git commit -m "feat(email): EmailPreferences entity + repository"
```

---

## Task 8: EmailPreferenceService

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/email/dispatch/EmailPreferenceService.java`
- Create: `backend/src/test/java/com/datagami/rentaxis/core/email/dispatch/EmailPreferenceServiceTest.java`

- [x] **Step 1: Write failing test**

Create `backend/src/test/java/com/datagami/rentaxis/core/email/dispatch/EmailPreferenceServiceTest.java`:

```java
package com.datagami.rentaxis.core.email.dispatch;

import com.datagami.rentaxis.core.email.EmailCategory;
import com.datagami.rentaxis.core.email.prefs.EmailPreferences;
import com.datagami.rentaxis.core.email.prefs.EmailPreferencesRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailPreferenceServiceTest {

    @Mock EmailPreferencesRepository repo;
    @InjectMocks EmailPreferenceService service;

    @Test
    void transactionalAlwaysAllowedEvenIfMarketingDisabled() {
        UUID user = UUID.randomUUID();
        EmailPreferences prefs = new EmailPreferences();
        prefs.setUserId(user);
        prefs.setMarketingEnabled(false);
        when(repo.findById(user)).thenReturn(Optional.of(prefs));

        assertTrue(service.shouldSend(user, EmailCategory.TRANSACTIONAL));
    }

    @Test
    void marketingBlockedWhenDisabled() {
        UUID user = UUID.randomUUID();
        EmailPreferences prefs = new EmailPreferences();
        prefs.setUserId(user);
        prefs.setMarketingEnabled(false);
        when(repo.findById(user)).thenReturn(Optional.of(prefs));

        assertFalse(service.shouldSend(user, EmailCategory.MARKETING));
    }

    @Test
    void missingPrefsRowDefaultsToMarketingOn() {
        UUID user = UUID.randomUUID();
        when(repo.findById(user)).thenReturn(Optional.empty());

        assertTrue(service.shouldSend(user, EmailCategory.MARKETING));
        assertTrue(service.shouldSend(user, EmailCategory.TRANSACTIONAL));
    }
}
```

- [x] **Step 2: Run test to verify fail**

Run: `cd backend && ./gradlew test --tests EmailPreferenceServiceTest`
Expected: FAIL — `EmailPreferenceService` does not exist.

- [x] **Step 3: Implement service**

Create `backend/src/main/java/com/datagami/rentaxis/core/email/dispatch/EmailPreferenceService.java`:

```java
package com.datagami.rentaxis.core.email.dispatch;

import com.datagami.rentaxis.core.email.EmailCategory;
import com.datagami.rentaxis.core.email.prefs.EmailPreferences;
import com.datagami.rentaxis.core.email.prefs.EmailPreferencesRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EmailPreferenceService {

    private static final SecureRandom RNG = new SecureRandom();
    private final EmailPreferencesRepository repo;

    public boolean shouldSend(UUID userId, EmailCategory category) {
        if (category == EmailCategory.TRANSACTIONAL) return true;
        return repo.findById(userId).map(EmailPreferences::isMarketingEnabled).orElse(true);
    }

    @Transactional
    public EmailPreferences ensureRow(UUID userId) {
        return repo.findById(userId).orElseGet(() -> {
            EmailPreferences p = new EmailPreferences();
            p.setUserId(userId);
            p.setMarketingEnabled(true);
            p.setPreferencesJson("{}");
            p.setUnsubscribeToken(newToken());
            return repo.save(p);
        });
    }

    @Transactional
    public Optional<UUID> disableMarketingByToken(String token) {
        return repo.findByUnsubscribeToken(token).map(p -> {
            p.setMarketingEnabled(false);
            repo.save(p);
            return p.getUserId();
        });
    }

    @Transactional
    public void setMarketing(UUID userId, boolean enabled) {
        EmailPreferences p = ensureRow(userId);
        p.setMarketingEnabled(enabled);
        repo.save(p);
    }

    public String unsubscribeToken(UUID userId) {
        return ensureRow(userId).getUnsubscribeToken();
    }

    private static String newToken() {
        byte[] buf = new byte[32];
        RNG.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }
}
```

- [x] **Step 4: Run test**

Run: `cd backend && ./gradlew test --tests EmailPreferenceServiceTest`
Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/email/dispatch/EmailPreferenceService.java backend/src/test/java/com/datagami/rentaxis/core/email/dispatch/EmailPreferenceServiceTest.java
git commit -m "feat(email): EmailPreferenceService for opt-out + unsubscribe tokens"
```

---

## Task 9: TenantBrandingResolver

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/email/dispatch/TenantBrandingResolver.java`
- Create: `backend/src/test/java/com/datagami/rentaxis/core/email/dispatch/TenantBrandingResolverTest.java`

- [x] **Step 1: Failing test**

Create `backend/src/test/java/com/datagami/rentaxis/core/email/dispatch/TenantBrandingResolverTest.java`:

```java
package com.datagami.rentaxis.core.email.dispatch;

import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantBrandingResolverTest {

    @Mock LandlordOrgRepository orgRepo;
    @InjectMocks TenantBrandingResolver resolver;

    @Test
    void returnsBrandingFromLandlordOrg() {
        UUID tenant = UUID.randomUUID();
        LandlordOrg org = new LandlordOrg();
        org.setId(tenant);
        org.setName("Acme PM");
        org.setLogoUrl("https://example/logo.png");
        when(orgRepo.findById(tenant)).thenReturn(Optional.of(org));

        TenantBranding b = resolver.resolve(tenant);

        assertEquals("Acme PM", b.companyName());
        assertEquals("https://example/logo.png", b.logoUrl());
    }

    @Test
    void returnsNullForUnknownTenant() {
        when(orgRepo.findById(any())).thenReturn(Optional.empty());
        assertNull(resolver.resolve(UUID.randomUUID()));
    }

    @Test
    void returnsNullForNullTenantId() {
        assertNull(resolver.resolve(null));
        verifyNoInteractions(orgRepo);
    }
}
```

- [x] **Step 2: Run test to verify fail**

Run: `cd backend && ./gradlew test --tests TenantBrandingResolverTest`
Expected: FAIL — `TenantBranding`/`TenantBrandingResolver` do not exist.

- [x] **Step 3: Implement TenantBranding record + resolver**

Create `backend/src/main/java/com/datagami/rentaxis/core/email/dispatch/TenantBrandingResolver.java`:

```java
package com.datagami.rentaxis.core.email.dispatch;

import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class TenantBrandingResolver {

    private final LandlordOrgRepository orgRepo;

    public TenantBranding resolve(UUID tenantId) {
        if (tenantId == null) return null;
        return orgRepo.findById(tenantId)
                .map(o -> new TenantBranding(o.getName(), o.getLogoUrl()))
                .orElse(null);
    }
}
```

In the same file (or separate file in the same package — pick same file for cohesion), append:

```java
record TenantBranding(String companyName, String logoUrl) {}
```

> If the existing codebase requires `record` types in their own files, split into `TenantBranding.java`. Keep package-private.

- [x] **Step 4: Run test**

Run: `cd backend && ./gradlew test --tests TenantBrandingResolverTest`
Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/email/dispatch/ backend/src/test/java/com/datagami/rentaxis/core/email/dispatch/TenantBrandingResolverTest.java
git commit -m "feat(email): TenantBrandingResolver from LandlordOrg"
```

---

## Task 10: EmailEvent base class + first payload (UserInvitedPayload)

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/email/event/EmailEvent.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/core/email/event/payload/UserInvitedPayload.java`

- [x] **Step 1: Implement base event**

Create `backend/src/main/java/com/datagami/rentaxis/core/email/event/EmailEvent.java`:

```java
package com.datagami.rentaxis.core.email.event;

import com.datagami.rentaxis.core.email.EmailEventType;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

public class EmailEvent extends ApplicationEvent {

    private final EmailEventType type;
    private final UUID tenantId;
    private final Object payload;
    private final String dedupKey;

    public EmailEvent(Object source, EmailEventType type, UUID tenantId, Object payload, String dedupKey) {
        super(source);
        this.type = type;
        this.tenantId = tenantId;
        this.payload = payload;
        this.dedupKey = dedupKey;
    }

    public EmailEventType getType() { return type; }
    public UUID getTenantId() { return tenantId; }
    public Object getPayload() { return payload; }
    public String getDedupKey() { return dedupKey; }
}
```

- [x] **Step 2: Implement first payload**

Create `backend/src/main/java/com/datagami/rentaxis/core/email/event/payload/UserInvitedPayload.java`:

```java
package com.datagami.rentaxis.core.email.event.payload;

import java.util.UUID;

public record UserInvitedPayload(
        UUID inviteeUserId,
        String inviteeName,
        String setPasswordUrl
) {}
```

- [x] **Step 3: Verify compile**

Run: `cd backend && ./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [x] **Step 4: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/email/event/
git commit -m "feat(email): EmailEvent base + UserInvitedPayload"
```

---

## Task 11: Remaining payload records (one per Phase 1 event)

**Files:** (all under `backend/src/main/java/com/datagami/rentaxis/core/email/event/payload/`)

- [x] **Step 1: Create all payloads**

Each payload is a `record`. Fields are the minimum needed for template rendering. Names match the templates' Thymeleaf variables. Pattern: include `referenceId` (the lease/payment/ticket UUID), human-readable labels, dates as ISO strings, monetary amounts as `String` formatted (e.g. "1,500 AED").

Create the following files (each shown verbatim — no shortcuts):

`UserWelcomedPayload.java`:
```java
package com.datagami.rentaxis.core.email.event.payload;
import java.util.UUID;
public record UserWelcomedPayload(UUID userId, String userName, String dashboardUrl) {}
```

`PasswordResetRequestedPayload.java`:
```java
package com.datagami.rentaxis.core.email.event.payload;
import java.util.UUID;
public record PasswordResetRequestedPayload(UUID userId, String userName, String resetUrl, String expiresAtIso) {}
```

`PasswordChangedPayload.java`:
```java
package com.datagami.rentaxis.core.email.event.payload;
import java.util.UUID;
public record PasswordChangedPayload(UUID userId, String userName, String changedAtIso, String ipAddress) {}
```

`EmailVerifiedPayload.java`:
```java
package com.datagami.rentaxis.core.email.event.payload;
import java.util.UUID;
public record EmailVerifiedPayload(UUID userId, String userName, String dashboardUrl) {}
```

`TenantProvisionedPayload.java`:
```java
package com.datagami.rentaxis.core.email.event.payload;
import java.util.UUID;
public record TenantProvisionedPayload(UUID tenantId, String tenantName, UUID adminUserId, String adminName) {}
```

`TenantAdminAddedPayload.java`:
```java
package com.datagami.rentaxis.core.email.event.payload;
import java.util.UUID;
public record TenantAdminAddedPayload(UUID tenantId, UUID newAdminUserId, String newAdminName, String addedByName) {}
```

`StaffRoleChangedPayload.java`:
```java
package com.datagami.rentaxis.core.email.event.payload;
import java.util.UUID;
public record StaffRoleChangedPayload(UUID tenantId, UUID userId, String userName, String oldRole, String newRole) {}
```

`LeasePayload.java` (used by all lease events; keeps payloads DRY since the template variables are the same):
```java
package com.datagami.rentaxis.core.email.event.payload;
import java.util.UUID;
public record LeasePayload(
        UUID leaseId,
        UUID renterUserId,
        UUID propertyManagerUserId,
        String unitLabel,
        String propertyName,
        String startDateIso,
        String endDateIso,
        String monthlyRentDisplay,
        String contractSignedUrl
) {}
```

`ChequePayload.java`:
```java
package com.datagami.rentaxis.core.email.event.payload;
import java.util.UUID;
public record ChequePayload(
        UUID paymentScheduleId,
        UUID leaseId,
        UUID renterUserId,
        UUID propertyManagerUserId,
        int installmentNumber,
        String chequeNumber,
        String bankName,
        String amountDisplay,
        String dueDateIso,
        String depositDateIso,
        String failureReason
) {}
```

`PaymentReminderPayload.java`:
```java
package com.datagami.rentaxis.core.email.event.payload;
import java.util.UUID;
public record PaymentReminderPayload(
        UUID paymentScheduleId,
        UUID leaseId,
        UUID renterUserId,
        int installmentNumber,
        String amountDisplay,
        String dueDateIso,
        int daysUntilDue
) {}
```

`OnlinePaymentPayload.java`:
```java
package com.datagami.rentaxis.core.email.event.payload;
import java.util.UUID;
public record OnlinePaymentPayload(
        UUID onlinePaymentId,
        UUID leaseId,
        UUID renterUserId,
        UUID propertyManagerUserId,
        String amountDisplay,
        String gatewayReference,
        String failureReason
) {}
```

`RentReceiptPayload.java`:
```java
package com.datagami.rentaxis.core.email.event.payload;
import java.util.UUID;
public record RentReceiptPayload(
        UUID receiptId,
        UUID leaseId,
        UUID renterUserId,
        String amountDisplay,
        String paidOnIso,
        String pdfBase64,
        String pdfFileName
) {}
```

`PenaltyPayload.java`:
```java
package com.datagami.rentaxis.core.email.event.payload;
import java.util.UUID;
public record PenaltyPayload(
        UUID penaltyId,
        UUID leaseId,
        UUID renterUserId,
        UUID propertyManagerUserId,
        String penaltyAmountDisplay,
        String reason,
        int installmentNumber
) {}
```

`TicketPayload.java`:
```java
package com.datagami.rentaxis.core.email.event.payload;
import java.util.UUID;
public record TicketPayload(
        UUID ticketId,
        UUID renterUserId,
        UUID propertyManagerUserId,
        String title,
        String category,
        String priority,
        String status,
        String latestReply
) {}
```

`MeetingPayload.java`:
```java
package com.datagami.rentaxis.core.email.event.payload;
import java.util.UUID;
public record MeetingPayload(
        UUID meetingId,
        UUID renterUserId,
        UUID propertyManagerUserId,
        String purpose,
        String meetingType,
        String scheduledAtIso,
        String location,
        String status
) {}
```

- [x] **Step 2: Verify compile**

Run: `cd backend && ./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [x] **Step 3: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/email/event/payload/
git commit -m "feat(email): payload records for Phase 1 events"
```

---

## Task 12: AzureAcsEmailSender (extracted from NotificationService)

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/email/send/EmailSender.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/core/email/send/SendResult.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/core/email/send/AzureAcsEmailSender.java`

- [x] **Step 1: Implement interface + result**

Create `backend/src/main/java/com/datagami/rentaxis/core/email/send/EmailSender.java`:

```java
package com.datagami.rentaxis.core.email.send;

import com.datagami.rentaxis.core.email.outbox.EmailOutbox;

public interface EmailSender {
    SendResult send(EmailOutbox row);
}
```

Create `backend/src/main/java/com/datagami/rentaxis/core/email/send/SendResult.java`:

```java
package com.datagami.rentaxis.core.email.send;

public record SendResult(String azureMessageId, String azureDeliveryStatus) {}
```

- [x] **Step 2: Implement Azure ACS sender**

Create `backend/src/main/java/com/datagami/rentaxis/core/email/send/AzureAcsEmailSender.java`:

```java
package com.datagami.rentaxis.core.email.send;

import com.azure.communication.email.EmailClient;
import com.azure.communication.email.EmailClientBuilder;
import com.azure.communication.email.models.EmailAttachment;
import com.azure.communication.email.models.EmailMessage;
import com.azure.communication.email.models.EmailSendResult;
import com.datagami.rentaxis.core.email.outbox.EmailOutbox;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class AzureAcsEmailSender implements EmailSender {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Value("${AZURE_COMMUNICATION_CONNECTION_STRING:}")
    private String connectionString;

    @Value("${AZURE_EMAIL_SENDER:}")
    private String sender;

    private EmailClient client;

    @PostConstruct
    void init() {
        if (connectionString == null || connectionString.isBlank()) {
            log.warn("AZURE_COMMUNICATION_CONNECTION_STRING not set — emails will fail at send time");
            return;
        }
        client = new EmailClientBuilder().connectionString(connectionString).buildClient();
    }

    @Override
    public SendResult send(EmailOutbox row) {
        if (client == null) throw new IllegalStateException("Azure ACS not configured");
        if (sender == null || sender.isBlank()) throw new IllegalStateException("AZURE_EMAIL_SENDER not set");

        EmailMessage message = new EmailMessage()
                .setSenderAddress(sender)
                .setToRecipients(row.getRecipientEmail())
                .setSubject(row.getSubject())
                .setBodyHtml(row.getBodyHtml())
                .setBodyPlainText(row.getBodyText() == null ? "" : row.getBodyText());

        List<EmailAttachment> attachments = parseAttachments(row.getAttachments());
        if (!attachments.isEmpty()) message.setAttachments(attachments);

        var poller = client.beginSend(message);
        EmailSendResult result = poller.waitForCompletion().getValue();
        return new SendResult(result.getId(), String.valueOf(result.getStatus()));
    }

    private List<EmailAttachment> parseAttachments(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Map<String, String>> parsed = JSON.readValue(json, new TypeReference<>() {});
            return parsed.stream()
                    .filter(a -> a.containsKey("base64"))
                    .map(a -> new EmailAttachment(
                            a.get("name"),
                            a.get("contentType"),
                            com.azure.core.util.BinaryData.fromBytes(Base64.getDecoder().decode(a.get("base64")))))
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to parse attachments JSON: {}", e.getMessage());
            return List.of();
        }
    }
}
```

- [x] **Step 3: Verify compile**

Run: `cd backend && ./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`. If `EmailAttachment` or its constructor signature differs in your azure-communication-email version, adjust the constructor call to match — same intent (name, contentType, BinaryData).

- [x] **Step 4: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/email/send/
git commit -m "feat(email): AzureAcsEmailSender + EmailSender interface"
```

---

## Task 13: EmailRenderer (Thymeleaf + MessageSource)

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/email/render/EmailRenderResult.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/core/email/render/EmailTemplateContext.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/core/email/render/EmailRenderer.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/core/email/config/EmailMessageSourceConfig.java`
- Create: `backend/src/test/java/com/datagami/rentaxis/core/email/render/EmailRendererTest.java`

- [x] **Step 1: Configure MessageSource for email**

Create `backend/src/main/java/com/datagami/rentaxis/core/email/config/EmailMessageSourceConfig.java`:

```java
package com.datagami.rentaxis.core.email.config;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

@Configuration
public class EmailMessageSourceConfig {

    @Bean(name = "emailMessageSource")
    public MessageSource emailMessageSource() {
        ReloadableResourceBundleMessageSource ms = new ReloadableResourceBundleMessageSource();
        ms.setBasename("classpath:messages/email");
        ms.setDefaultEncoding("UTF-8");
        ms.setUseCodeAsDefaultMessage(false);
        ms.setFallbackToSystemLocale(false);
        return ms;
    }
}
```

- [x] **Step 2: Implement render result + context records**

Create `backend/src/main/java/com/datagami/rentaxis/core/email/render/EmailRenderResult.java`:

```java
package com.datagami.rentaxis.core.email.render;

public record EmailRenderResult(String subject, String html, String text, String attachmentsJson) {}
```

Create `backend/src/main/java/com/datagami/rentaxis/core/email/render/EmailTemplateContext.java`:

```java
package com.datagami.rentaxis.core.email.render;

import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.dispatch.TenantBranding;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public record EmailTemplateContext(
        EmailEventType type,
        Locale locale,
        UUID recipientUserId,
        String recipientName,
        String recipientEmail,
        String portalBaseUrl,
        TenantBranding tenantBranding,
        String unsubscribeUrl,
        Map<String, Object> payloadVars
) {}
```

> Note: `TenantBranding` was created in Task 9 alongside `TenantBrandingResolver`. Make it a top-level public record `backend/src/main/java/com/datagami/rentaxis/core/email/dispatch/TenantBranding.java`:
> ```java
> package com.datagami.rentaxis.core.email.dispatch;
> public record TenantBranding(String companyName, String logoUrl) {}
> ```
> If Task 9 placed it in the resolver file, move it out now and update the import.

- [x] **Step 3: Implement renderer**

Create `backend/src/main/java/com/datagami/rentaxis/core/email/render/EmailRenderer.java`:

```java
package com.datagami.rentaxis.core.email.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Map;
import java.util.NoSuchElementException;

@Component
@RequiredArgsConstructor
@Slf4j
public class EmailRenderer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TemplateEngine emailTemplateEngine;

    @Qualifier("emailMessageSource")
    private final MessageSource messageSource;

    public EmailRenderResult render(EmailTemplateContext ctx) {
        Context tlCtx = new Context(ctx.locale());
        tlCtx.setVariable("recipient", Map.of(
                "name", ctx.recipientName() == null ? "" : ctx.recipientName(),
                "email", ctx.recipientEmail() == null ? "" : ctx.recipientEmail()));
        tlCtx.setVariable("portalBaseUrl", ctx.portalBaseUrl());
        tlCtx.setVariable("tenantBranding", ctx.tenantBranding());
        tlCtx.setVariable("unsubscribeUrl", ctx.unsubscribeUrl());
        tlCtx.setVariable("layout",
                ctx.locale().getLanguage().equals("ar") ? "email/layout/master-rtl" : "email/layout/master");
        ctx.payloadVars().forEach(tlCtx::setVariable);

        String subjectKey = "email." + ctx.type().snake() + ".subject";
        Object[] subjectArgs = (Object[]) ctx.payloadVars().getOrDefault("__subjectArgs", new Object[0]);
        String subject;
        try {
            subject = messageSource.getMessage(subjectKey, subjectArgs, ctx.locale());
        } catch (NoSuchElementException | org.springframework.context.NoSuchMessageException e) {
            log.warn("Missing email i18n key {} for locale {} — falling back to event name",
                    subjectKey, ctx.locale());
            subject = ctx.type().name();
        }

        String html = emailTemplateEngine.process("email/events/" + ctx.type().snake(), tlCtx);
        String text = htmlToPlainText(html);

        return new EmailRenderResult(subject, html, text, null);
    }

    public String renderAttachmentsJson(Object attachmentsList) {
        if (attachmentsList == null) return null;
        try {
            return JSON.writeValueAsString(attachmentsList);
        } catch (Exception e) {
            log.warn("Failed to serialize attachments: {}", e.getMessage());
            return null;
        }
    }

    private String htmlToPlainText(String html) {
        return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }
}
```

- [x] **Step 4: Failing renderer test**

Create `backend/src/test/java/com/datagami/rentaxis/core/email/render/EmailRendererTest.java`:

```java
package com.datagami.rentaxis.core.email.render;

import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.dispatch.TenantBranding;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class EmailRendererTest {

    @Autowired EmailRenderer renderer;

    @Test
    void rendersUserInvitedInEnglishWithTenantBranding() {
        EmailTemplateContext ctx = new EmailTemplateContext(
                EmailEventType.USER_INVITED,
                Locale.ENGLISH,
                UUID.randomUUID(),
                "Sara",
                "sara@example.com",
                "https://app.test",
                new TenantBranding("Acme PM", "https://example/logo.png"),
                "https://app.test/api/v1/email/unsubscribe?token=abc",
                Map.of("setPasswordUrl", "https://app.test/set-password?token=xyz",
                       "__subjectArgs", new Object[]{"Acme PM"})
        );

        EmailRenderResult result = renderer.render(ctx);

        assertThat(result.subject()).isNotBlank();
        assertThat(result.html()).contains("Sara").contains("Acme PM");
    }

    @Test
    void rendersInArabicAndUsesRtlLayout() {
        EmailTemplateContext ctx = new EmailTemplateContext(
                EmailEventType.USER_INVITED,
                new Locale("ar"),
                UUID.randomUUID(),
                "سارة",
                "sara@example.com",
                "https://app.test",
                new TenantBranding("شركة الأمل", "https://example/logo.png"),
                "https://app.test/api/v1/email/unsubscribe?token=abc",
                Map.of("setPasswordUrl", "https://app.test/set-password?token=xyz",
                       "__subjectArgs", new Object[]{"شركة الأمل"})
        );

        EmailRenderResult result = renderer.render(ctx);

        assertThat(result.html()).contains("dir=\"rtl\"");
    }
}
```

- [x] **Step 5: Run test to verify fail**

Run: `cd backend && ./gradlew test --tests EmailRendererTest`
Expected: FAIL — templates and i18n keys not yet present (Tasks 14–16 fix this).

- [x] **Step 6: Commit (red-stays for now; will go green once templates exist)**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/email/render/ backend/src/main/java/com/datagami/rentaxis/core/email/config/ backend/src/test/java/com/datagami/rentaxis/core/email/render/EmailRendererTest.java
git commit -m "feat(email): EmailRenderer scaffolding (Thymeleaf + MessageSource)"
```

---

## Task 14: Master layout templates (LTR + RTL)

**Files:**
- Create: `backend/src/main/resources/templates/email/layout/master.html`
- Create: `backend/src/main/resources/templates/email/layout/master-rtl.html`

- [x] **Step 1: Create LTR layout**

Create `backend/src/main/resources/templates/email/layout/master.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>RentAxis</title>
</head>
<body style="margin:0;padding:0;background:#FAFAF8;font-family:'Segoe UI',Tahoma,Geneva,Verdana,sans-serif;">
<table width="100%" cellpadding="0" cellspacing="0" style="background:#FAFAF8;padding:32px 0;">
  <tr><td align="center">
    <table width="560" cellpadding="0" cellspacing="0" style="background:#fff;border-radius:12px;border:1px solid #E2E0DC;overflow:hidden;">
      <tr><td style="background:#0F1B2D;padding:24px 32px;">
        <table width="100%" cellpadding="0" cellspacing="0">
          <tr>
            <td style="color:#C8A951;font-size:20px;font-weight:700;letter-spacing:1px;">RentAxis</td>
            <td align="right" style="color:rgba(255,255,255,0.5);font-size:11px;">Property Management</td>
          </tr>
        </table>
      </td></tr>

      <tr><td style="padding:32px;">
        <th:block th:fragment="layout(content)">
          <th:block th:replace="${content}" />
        </th:block>

        <hr style="border:none;border-top:1px solid #E2E0DC;margin:24px 0;" />

        <table th:if="${tenantBranding != null}" width="100%" cellpadding="0" cellspacing="0" style="background:#F5F4F0;border-radius:8px;padding:16px;">
          <tr><td style="padding:12px 16px;">
            <img th:if="${tenantBranding.logoUrl} != null" th:src="${tenantBranding.logoUrl}" alt="" height="24" style="vertical-align:middle;margin-right:8px;" />
            <span th:text="${tenantBranding.companyName}" style="font-weight:600;color:#0F172A;font-size:13px;">Acme PM</span>
          </td></tr>
        </table>
      </td></tr>

      <tr><td style="padding:20px 32px;border-top:1px solid #E2E0DC;background:#F5F4F0;">
        <p style="margin:0;color:#94A3B8;font-size:11px;text-align:center;line-height:1.6;">
          This is an automated notification from RentAxis Property Management System. Please do not reply to this email.<br/>
          <a th:if="${unsubscribeUrl}" th:href="${unsubscribeUrl}" style="color:#94A3B8;">Unsubscribe from marketing emails</a>
        </p>
      </td></tr>
    </table>
  </td></tr>
</table>
</body>
</html>
```

- [x] **Step 2: Create RTL layout**

Create `backend/src/main/resources/templates/email/layout/master-rtl.html` — same as `master.html` but:
- Add `dir="rtl"` to `<html>`
- `text-align:right` on `<td style="padding:32px;">`
- Swap header table cells (logo on right, label on left)

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" dir="rtl">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>RentAxis</title>
</head>
<body style="margin:0;padding:0;background:#FAFAF8;font-family:'Segoe UI',Tahoma,Geneva,Verdana,Arial,sans-serif;">
<table width="100%" cellpadding="0" cellspacing="0" style="background:#FAFAF8;padding:32px 0;" dir="rtl">
  <tr><td align="center">
    <table width="560" cellpadding="0" cellspacing="0" style="background:#fff;border-radius:12px;border:1px solid #E2E0DC;overflow:hidden;">
      <tr><td style="background:#0F1B2D;padding:24px 32px;">
        <table width="100%" cellpadding="0" cellspacing="0" dir="rtl">
          <tr>
            <td style="color:rgba(255,255,255,0.5);font-size:11px;">إدارة العقارات</td>
            <td align="left" style="color:#C8A951;font-size:20px;font-weight:700;letter-spacing:1px;">RentAxis</td>
          </tr>
        </table>
      </td></tr>

      <tr><td style="padding:32px;text-align:right;">
        <th:block th:fragment="layout(content)">
          <th:block th:replace="${content}" />
        </th:block>

        <hr style="border:none;border-top:1px solid #E2E0DC;margin:24px 0;" />

        <table th:if="${tenantBranding != null}" width="100%" cellpadding="0" cellspacing="0" style="background:#F5F4F0;border-radius:8px;padding:16px;" dir="rtl">
          <tr><td style="padding:12px 16px;">
            <span th:text="${tenantBranding.companyName}" style="font-weight:600;color:#0F172A;font-size:13px;">Acme PM</span>
            <img th:if="${tenantBranding.logoUrl} != null" th:src="${tenantBranding.logoUrl}" alt="" height="24" style="vertical-align:middle;margin-right:8px;" />
          </td></tr>
        </table>
      </td></tr>

      <tr><td style="padding:20px 32px;border-top:1px solid #E2E0DC;background:#F5F4F0;">
        <p style="margin:0;color:#94A3B8;font-size:11px;text-align:center;line-height:1.6;">
          هذا إشعار تلقائي من نظام RentAxis لإدارة العقارات. الرجاء عدم الرد على هذا البريد الإلكتروني.<br/>
          <a th:if="${unsubscribeUrl}" th:href="${unsubscribeUrl}" style="color:#94A3B8;">إلغاء الاشتراك في رسائل التسويق</a>
        </p>
      </td></tr>
    </table>
  </td></tr>
</table>
</body>
</html>
```

- [x] **Step 3: Commit**

```bash
git add backend/src/main/resources/templates/email/layout/
git commit -m "feat(email): master layout templates (LTR + RTL)"
```

---

## Task 15: Per-event templates (Phase 1)

**Files:** All under `backend/src/main/resources/templates/email/events/` — one file per event listed in the file structure.

- [x] **Step 1: Create all event templates**

For each event, create a file `<event_snake>.html`. The skeleton is identical:

```html
<th:block xmlns:th="http://www.thymeleaf.org" th:replace="~{${layout} :: layout(~{::content})}">
<th:block th:fragment="content">
  <h2 th:text="#{email.<event_snake>.title}" style="margin:0 0 8px;color:#0F172A;font-size:18px;font-weight:600;"></h2>
  <p th:text="#{email.<event_snake>.greeting(${recipient.name})}" style="margin:0 0 16px;color:#475569;font-size:14px;line-height:1.6;"></p>
  <p th:utext="#{email.<event_snake>.body(...)}" style="margin:0 0 16px;color:#475569;font-size:14px;line-height:1.6;"></p>

  <table cellpadding="0" cellspacing="0" style="margin:16px 0;"><tr><td>
    <a th:href="${ctaUrl}" th:text="#{email.<event_snake>.cta}" style="display:inline-block;background:#0F766E;color:#fff;padding:12px 24px;border-radius:8px;text-decoration:none;font-size:13px;font-weight:600;"></a>
  </td></tr></table>
</th:block>
</th:block>
```

Replace `<event_snake>` with the event's snake-case name. Replace `(...)` in `email.<event_snake>.body(...)` with the message-format args needed for that body — e.g. for `lease_signed`, the body args are `(${unitLabel}, ${propertyName}, ${signedAtIso})`.

The full list of events to create:
- `user_invited` — args: `(${tenantBranding != null ? tenantBranding.companyName : 'RentAxis'})`; ctaUrl=`${setPasswordUrl}`
- `user_welcomed` — args: none; ctaUrl=`${dashboardUrl}`
- `password_reset_requested` — args: `(${expiresAtIso})`; ctaUrl=`${resetUrl}`
- `password_changed` — args: `(${changedAtIso}, ${ipAddress})`; no CTA (drop the CTA block)
- `email_verified` — args: none; ctaUrl=`${dashboardUrl}`
- `tenant_provisioned` — args: `(${tenantName}, ${adminName})`; ctaUrl=`${portalBaseUrl}`
- `tenant_admin_added` — args: `(${newAdminName}, ${addedByName})`; ctaUrl=`${portalBaseUrl}`
- `staff_role_changed` — args: `(${userName}, ${oldRole}, ${newRole})`; no CTA
- `lease_created` — args: `(${unitLabel}, ${propertyName}, ${startDateIso})`; ctaUrl=lease deep link (computed below)
- `lease_contract_generated` — args: `(${unitLabel}, ${propertyName})`; ctaUrl=`${contractSignedUrl}`
- `lease_signature_requested` — args: `(${unitLabel}, ${propertyName})`; ctaUrl=lease deep link
- `lease_signed` — args: `(${unitLabel}, ${propertyName}, ${startDateIso})`; ctaUrl=lease deep link
- `lease_activated` — args: `(${unitLabel}, ${propertyName}, ${startDateIso})`; ctaUrl=lease deep link
- `lease_expiring` — args: `(${unitLabel}, ${propertyName}, ${endDateIso})`; ctaUrl=lease deep link
- `lease_renewed` — args: `(${unitLabel}, ${propertyName}, ${endDateIso})`; ctaUrl=lease deep link
- `lease_terminated` — args: `(${unitLabel}, ${propertyName})`; ctaUrl=lease deep link
- `cheque_received` — args: `(${chequeNumber}, ${amountDisplay}, ${installmentNumber})`; ctaUrl=payments page
- `cheque_deposited` — args: `(${chequeNumber}, ${amountDisplay}, ${depositDateIso})`; ctaUrl=payments page
- `cheque_cleared` — args: `(${chequeNumber}, ${amountDisplay})`; ctaUrl=payments page
- `cheque_bounced` — args: `(${chequeNumber}, ${amountDisplay}, ${failureReason})`; ctaUrl=payments page
- `payment_due_reminder` — args: `(${amountDisplay}, ${dueDateIso}, ${daysUntilDue})`; ctaUrl=payments page
- `payment_overdue` — args: `(${amountDisplay}, ${dueDateIso})`; ctaUrl=payments page
- `online_payment_received` — args: `(${amountDisplay}, ${gatewayReference})`; ctaUrl=payments page
- `online_payment_failed` — args: `(${amountDisplay}, ${failureReason})`; ctaUrl=payments page
- `rent_receipt_available` — args: `(${amountDisplay}, ${paidOnIso})`; ctaUrl=lease deep link
- `penalty_incurred` — args: `(${penaltyAmountDisplay}, ${reason}, ${installmentNumber})`; ctaUrl=payments page
- `penalty_cleared` — args: `(${penaltyAmountDisplay})`; ctaUrl=payments page
- `penalty_waived` — args: `(${penaltyAmountDisplay}, ${reason})`; ctaUrl=payments page
- `ticket_assigned` — args: `(${title}, ${priority})`; ctaUrl=ticket deep link
- `ticket_reply` — args: `(${title}, ${latestReply})`; ctaUrl=ticket deep link
- `ticket_resolved` — args: `(${title})`; ctaUrl=ticket deep link
- `ticket_created` — args: `(${title}, ${category})`; ctaUrl=ticket deep link
- `ticket_reopened` — args: `(${title})`; ctaUrl=ticket deep link
- `meeting_requested` — args: `(${purpose}, ${scheduledAtIso}, ${location})`; ctaUrl=meeting deep link
- `meeting_approved` — args: `(${purpose}, ${scheduledAtIso}, ${location})`; ctaUrl=meeting deep link
- `meeting_cancelled` — args: `(${purpose}, ${scheduledAtIso})`; ctaUrl=meeting deep link
- `meeting_completed` — args: `(${purpose}, ${scheduledAtIso})`; ctaUrl=meeting deep link
- `meeting_no_show` — args: `(${purpose}, ${scheduledAtIso})`; ctaUrl=meeting deep link

The deep-link variables (`leaseDeepLink`, `paymentsPageUrl`, `ticketDeepLink`, `meetingDeepLink`) are injected by the dispatcher in Task 18 alongside the payload. They are constructed as:
- `lease`: `${portalBaseUrl}/<locale>/dashboard/leases/<leaseId>`
- `payments`: `${portalBaseUrl}/<locale>/dashboard/finance/payments`
- `ticket`: `${portalBaseUrl}/<locale>/dashboard/tickets/<ticketId>`
- `meeting`: `${portalBaseUrl}/<locale>/dashboard/meetings/<meetingId>`

To keep templates simple, the dispatcher computes the right `ctaUrl` and adds it as a variable. So **every template uses `${ctaUrl}`** — no per-template URL logic.

**Concrete example — `lease_signed.html`:**

```html
<th:block xmlns:th="http://www.thymeleaf.org" th:replace="~{${layout} :: layout(~{::content})}">
<th:block th:fragment="content">
  <h2 th:text="#{email.lease_signed.title}" style="margin:0 0 8px;color:#0F172A;font-size:18px;font-weight:600;"></h2>
  <p th:text="#{email.lease_signed.greeting(${recipient.name})}" style="margin:0 0 16px;color:#475569;font-size:14px;line-height:1.6;"></p>
  <p th:utext="#{email.lease_signed.body(${unitLabel}, ${propertyName}, ${startDateIso})}" style="margin:0 0 16px;color:#475569;font-size:14px;line-height:1.6;"></p>

  <table cellpadding="0" cellspacing="0" style="margin:16px 0;"><tr><td>
    <a th:href="${ctaUrl}" th:text="#{email.lease_signed.cta}" style="display:inline-block;background:#0F766E;color:#fff;padding:12px 24px;border-radius:8px;text-decoration:none;font-size:13px;font-weight:600;"></a>
  </td></tr></table>
</th:block>
</th:block>
```

For `password_changed` and `staff_role_changed` (no CTA), drop the `<table>...</table>` CTA block.

- [x] **Step 2: Verify templates parse**

Run: `cd backend && ./gradlew compileJava bootRun` (start the app and immediately stop). Expected: no Thymeleaf parse errors during initialization.

- [x] **Step 3: Commit**

```bash
git add backend/src/main/resources/templates/email/events/
git commit -m "feat(email): per-event Thymeleaf templates (Phase 1)"
```

---

## Task 16: i18n properties (EN + AR)

**Files:**
- Create: `backend/src/main/resources/messages/email_en.properties`
- Create: `backend/src/main/resources/messages/email_ar.properties`

- [x] **Step 1: Create English properties**

Create `backend/src/main/resources/messages/email_en.properties` with one block per event. Pattern per event:

```
email.<event_snake>.subject=...
email.<event_snake>.title=...
email.<event_snake>.greeting=Hi {0},
email.<event_snake>.body=...
email.<event_snake>.cta=...
```

Concrete content (minimum viable copy — refine with content team later):

```properties
# USER_INVITED
email.user_invited.subject=You're invited to {0} on RentAxis
email.user_invited.title=Welcome to RentAxis
email.user_invited.greeting=Hi {0},
email.user_invited.body=You've been invited to <b>{0}</b>. Click the button below to set your password and get started.
email.user_invited.cta=Set your password

# USER_WELCOMED
email.user_welcomed.subject=Welcome to RentAxis
email.user_welcomed.title=Welcome aboard
email.user_welcomed.greeting=Hi {0},
email.user_welcomed.body=Glad to have you here. Your account is ready — explore your dashboard to get started.
email.user_welcomed.cta=Open dashboard

# PASSWORD_RESET_REQUESTED
email.password_reset_requested.subject=Reset your RentAxis password
email.password_reset_requested.title=Password reset
email.password_reset_requested.greeting=Hi {0},
email.password_reset_requested.body=Click the button below to reset your password. This link expires at <b>{0}</b>.
email.password_reset_requested.cta=Reset password

# PASSWORD_CHANGED
email.password_changed.subject=Your RentAxis password was changed
email.password_changed.title=Password changed
email.password_changed.greeting=Hi {0},
email.password_changed.body=Your password was changed on <b>{0}</b> from IP <b>{1}</b>. If this wasn't you, contact support immediately.

# EMAIL_VERIFIED
email.email_verified.subject=Email verified
email.email_verified.title=Email verified
email.email_verified.greeting=Hi {0},
email.email_verified.body=Your email address has been verified. You can now access all features.
email.email_verified.cta=Open dashboard

# TENANT_PROVISIONED
email.tenant_provisioned.subject=Tenant {0} provisioned on RentAxis
email.tenant_provisioned.title=New organization provisioned
email.tenant_provisioned.greeting=Hi {0},
email.tenant_provisioned.body=The organization <b>{0}</b> has been created with admin <b>{1}</b>.
email.tenant_provisioned.cta=Open RentAxis

# TENANT_ADMIN_ADDED
email.tenant_admin_added.subject=New admin added: {0}
email.tenant_admin_added.title=New admin added
email.tenant_admin_added.greeting=Hi {0},
email.tenant_admin_added.body=<b>{0}</b> has been added as an admin by <b>{1}</b>.
email.tenant_admin_added.cta=Open RentAxis

# STAFF_ROLE_CHANGED
email.staff_role_changed.subject=Role updated for {0}
email.staff_role_changed.title=Role updated
email.staff_role_changed.greeting=Hi {0},
email.staff_role_changed.body=<b>{0}</b>'s role has been changed from <b>{1}</b> to <b>{2}</b>.

# LEASE_CREATED
email.lease_created.subject=Lease created for {0} at {1}
email.lease_created.title=Lease created
email.lease_created.greeting=Hi {0},
email.lease_created.body=A new lease for <b>{0}</b> at <b>{1}</b> starting <b>{2}</b> has been created.
email.lease_created.cta=View lease

# LEASE_CONTRACT_GENERATED
email.lease_contract_generated.subject=Your lease contract is ready
email.lease_contract_generated.title=Contract ready
email.lease_contract_generated.greeting=Hi {0},
email.lease_contract_generated.body=Your lease contract for <b>{0}</b> at <b>{1}</b> is ready. Click below to download.
email.lease_contract_generated.cta=Download contract

# LEASE_SIGNATURE_REQUESTED
email.lease_signature_requested.subject=Signature required for your lease
email.lease_signature_requested.title=Signature required
email.lease_signature_requested.greeting=Hi {0},
email.lease_signature_requested.body=Please review and sign your lease for <b>{0}</b> at <b>{1}</b>.
email.lease_signature_requested.cta=Review & sign

# LEASE_SIGNED
email.lease_signed.subject=Lease signed for {0}
email.lease_signed.title=Lease signed
email.lease_signed.greeting=Hi {0},
email.lease_signed.body=Your lease for <b>{0}</b> at <b>{1}</b> has been signed on <b>{2}</b>.
email.lease_signed.cta=View lease

# LEASE_ACTIVATED
email.lease_activated.subject=Lease activated for {0}
email.lease_activated.title=Lease activated
email.lease_activated.greeting=Hi {0},
email.lease_activated.body=Your lease for <b>{0}</b> at <b>{1}</b> is now active starting <b>{2}</b>.
email.lease_activated.cta=View lease

# LEASE_EXPIRING
email.lease_expiring.subject=Lease expiring soon: {0}
email.lease_expiring.title=Lease expiring soon
email.lease_expiring.greeting=Hi {0},
email.lease_expiring.body=Your lease for <b>{0}</b> at <b>{1}</b> expires on <b>{2}</b>. Contact your property manager to renew.
email.lease_expiring.cta=View lease

# LEASE_RENEWED
email.lease_renewed.subject=Lease renewed for {0}
email.lease_renewed.title=Lease renewed
email.lease_renewed.greeting=Hi {0},
email.lease_renewed.body=Your lease for <b>{0}</b> at <b>{1}</b> has been renewed through <b>{2}</b>.
email.lease_renewed.cta=View lease

# LEASE_TERMINATED
email.lease_terminated.subject=Lease terminated for {0}
email.lease_terminated.title=Lease terminated
email.lease_terminated.greeting=Hi {0},
email.lease_terminated.body=Your lease for <b>{0}</b> at <b>{1}</b> has been terminated.
email.lease_terminated.cta=View lease

# CHEQUE_RECEIVED
email.cheque_received.subject=Cheque received for installment {2}
email.cheque_received.title=Cheque received
email.cheque_received.greeting=Hi {0},
email.cheque_received.body=Cheque <b>{0}</b> for <b>{1}</b> (installment <b>{2}</b>) has been collected.
email.cheque_received.cta=View payments

# CHEQUE_DEPOSITED
email.cheque_deposited.subject=Cheque deposited
email.cheque_deposited.title=Cheque deposited
email.cheque_deposited.greeting=Hi {0},
email.cheque_deposited.body=Cheque <b>{0}</b> for <b>{1}</b> has been deposited on <b>{2}</b>. We'll notify you once it clears.
email.cheque_deposited.cta=View payments

# CHEQUE_CLEARED
email.cheque_cleared.subject=Cheque cleared
email.cheque_cleared.title=Cheque cleared
email.cheque_cleared.greeting=Hi {0},
email.cheque_cleared.body=Cheque <b>{0}</b> for <b>{1}</b> has cleared. Your receipt is now available.
email.cheque_cleared.cta=View receipt

# CHEQUE_BOUNCED
email.cheque_bounced.subject=Cheque bounced — action required
email.cheque_bounced.title=Cheque bounced
email.cheque_bounced.greeting=Hi {0},
email.cheque_bounced.body=Cheque <b>{0}</b> for <b>{1}</b> bounced (<b>{2}</b>). Please arrange a replacement to avoid penalties.
email.cheque_bounced.cta=Replace cheque

# PAYMENT_DUE_REMINDER
email.payment_due_reminder.subject=Rent due in {2} days
email.payment_due_reminder.title=Rent due soon
email.payment_due_reminder.greeting=Hi {0},
email.payment_due_reminder.body=Your rent of <b>{0}</b> is due on <b>{1}</b> ({2} days). Prepare your cheque or pay online.
email.payment_due_reminder.cta=Make payment

# PAYMENT_OVERDUE
email.payment_overdue.subject=Rent overdue — action required
email.payment_overdue.title=Rent overdue
email.payment_overdue.greeting=Hi {0},
email.payment_overdue.body=Your rent of <b>{0}</b> was due on <b>{1}</b>. Please settle immediately to avoid penalties.
email.payment_overdue.cta=Make payment

# ONLINE_PAYMENT_RECEIVED
email.online_payment_received.subject=Payment received
email.online_payment_received.title=Payment received
email.online_payment_received.greeting=Hi {0},
email.online_payment_received.body=We received your online payment of <b>{0}</b> (ref <b>{1}</b>). Thank you.
email.online_payment_received.cta=View payments

# ONLINE_PAYMENT_FAILED
email.online_payment_failed.subject=Online payment failed
email.online_payment_failed.title=Payment failed
email.online_payment_failed.greeting=Hi {0},
email.online_payment_failed.body=Your online payment of <b>{0}</b> failed: <b>{1}</b>. Please try again or use another method.
email.online_payment_failed.cta=Retry payment

# RENT_RECEIPT_AVAILABLE
email.rent_receipt_available.subject=Your rent receipt
email.rent_receipt_available.title=Receipt ready
email.rent_receipt_available.greeting=Hi {0},
email.rent_receipt_available.body=Receipt for <b>{0}</b> paid on <b>{1}</b> is attached.
email.rent_receipt_available.cta=View lease

# PENALTY_INCURRED
email.penalty_incurred.subject=Penalty incurred
email.penalty_incurred.title=Penalty incurred
email.penalty_incurred.greeting=Hi {0},
email.penalty_incurred.body=A penalty of <b>{0}</b> was added for installment <b>{2}</b> ({1}).
email.penalty_incurred.cta=View payments

# PENALTY_CLEARED
email.penalty_cleared.subject=Penalty cleared
email.penalty_cleared.title=Penalty cleared
email.penalty_cleared.greeting=Hi {0},
email.penalty_cleared.body=Your penalty of <b>{0}</b> has been cleared.
email.penalty_cleared.cta=View payments

# PENALTY_WAIVED
email.penalty_waived.subject=Penalty waived
email.penalty_waived.title=Penalty waived
email.penalty_waived.greeting=Hi {0},
email.penalty_waived.body=Your penalty of <b>{0}</b> has been waived ({1}).
email.penalty_waived.cta=View payments

# TICKET_ASSIGNED
email.ticket_assigned.subject=Ticket assigned: {0}
email.ticket_assigned.title=Ticket assigned
email.ticket_assigned.greeting=Hi {0},
email.ticket_assigned.body=Ticket <b>{0}</b> ({1} priority) has been assigned to you.
email.ticket_assigned.cta=View ticket

# TICKET_REPLY
email.ticket_reply.subject=New reply on ticket {0}
email.ticket_reply.title=New reply
email.ticket_reply.greeting=Hi {0},
email.ticket_reply.body=New reply on <b>{0}</b>: "{1}"
email.ticket_reply.cta=View ticket

# TICKET_RESOLVED
email.ticket_resolved.subject=Ticket resolved: {0}
email.ticket_resolved.title=Ticket resolved
email.ticket_resolved.greeting=Hi {0},
email.ticket_resolved.body=Your ticket <b>{0}</b> has been resolved. Share the OTP with your property manager to close it.
email.ticket_resolved.cta=View ticket

# TICKET_CREATED
email.ticket_created.subject=New ticket: {0}
email.ticket_created.title=New ticket
email.ticket_created.greeting=Hi {0},
email.ticket_created.body=A new <b>{1}</b> ticket has been raised: <b>{0}</b>.
email.ticket_created.cta=View ticket

# TICKET_REOPENED
email.ticket_reopened.subject=Ticket reopened: {0}
email.ticket_reopened.title=Ticket reopened
email.ticket_reopened.greeting=Hi {0},
email.ticket_reopened.body=Ticket <b>{0}</b> has been reopened.
email.ticket_reopened.cta=View ticket

# MEETING_REQUESTED
email.meeting_requested.subject=Meeting request: {0}
email.meeting_requested.title=Meeting requested
email.meeting_requested.greeting=Hi {0},
email.meeting_requested.body=Meeting requested for <b>{0}</b> at <b>{1}</b> ({2}).
email.meeting_requested.cta=View request

# MEETING_APPROVED
email.meeting_approved.subject=Meeting approved: {0}
email.meeting_approved.title=Meeting approved
email.meeting_approved.greeting=Hi {0},
email.meeting_approved.body=Your meeting for <b>{0}</b> at <b>{1}</b> ({2}) is approved.
email.meeting_approved.cta=View meeting

# MEETING_CANCELLED
email.meeting_cancelled.subject=Meeting cancelled: {0}
email.meeting_cancelled.title=Meeting cancelled
email.meeting_cancelled.greeting=Hi {0},
email.meeting_cancelled.body=The meeting for <b>{0}</b> on <b>{1}</b> has been cancelled.
email.meeting_cancelled.cta=View meeting

# MEETING_COMPLETED
email.meeting_completed.subject=Meeting completed
email.meeting_completed.title=Meeting completed
email.meeting_completed.greeting=Hi {0},
email.meeting_completed.body=The meeting for <b>{0}</b> on <b>{1}</b> has been marked complete.
email.meeting_completed.cta=View meeting

# MEETING_NO_SHOW
email.meeting_no_show.subject=Meeting marked as no-show
email.meeting_no_show.title=No-show
email.meeting_no_show.greeting=Hi {0},
email.meeting_no_show.body=You were marked as a no-show for <b>{0}</b> on <b>{1}</b>.
email.meeting_no_show.cta=View meeting
```

- [x] **Step 2: Create Arabic properties (parallel structure)**

Create `backend/src/main/resources/messages/email_ar.properties` mirroring every key from `email_en.properties`. Translation work — initial draft below; refine with translator. Every key must exist (missing keys log a WARN and fall back to EN).

```properties
# USER_INVITED
email.user_invited.subject=تمت دعوتك للانضمام إلى {0} على RentAxis
email.user_invited.title=مرحبا بك في RentAxis
email.user_invited.greeting=مرحبا {0}،
email.user_invited.body=تمت دعوتك للانضمام إلى <b>{0}</b>. اضغط على الزر أدناه لتعيين كلمة المرور والبدء.
email.user_invited.cta=تعيين كلمة المرور

# (... continue every key in email_en.properties with AR translation ...)
# For brevity in this plan, the AR file mirrors every EN key. Implementer:
# copy email_en.properties → email_ar.properties and translate each value.
# Keep MessageFormat placeholders ({0}, {1}, ...) untouched.
```

> **Implementer note:** This step is the bulk of the translation work. If translations aren't ready, fill with placeholder Arabic text matching the structure — the EN fallback at runtime will still work for missing keys, but every key SHOULD be present (missing keys log WARN and fall back to EN, which is correct but noisy). Final translation review can happen post-merge as a content task.

- [x] **Step 3: Verify renderer test now passes**

Run: `cd backend && ./gradlew test --tests EmailRendererTest`
Expected: PASS — both tests now succeed (EN + AR + RTL).

- [x] **Step 4: Commit**

```bash
git add backend/src/main/resources/messages/
git commit -m "feat(email): EN + AR i18n properties for Phase 1 events"
```

---

## Task 17: RecipientResolver

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/email/dispatch/RecipientResolver.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/core/email/dispatch/ResolvedRecipient.java`
- Create: `backend/src/test/java/com/datagami/rentaxis/core/email/dispatch/RecipientResolverTest.java`

- [x] **Step 1: Define ResolvedRecipient**

Create `backend/src/main/java/com/datagami/rentaxis/core/email/dispatch/ResolvedRecipient.java`:

```java
package com.datagami.rentaxis.core.email.dispatch;

import com.datagami.rentaxis.core.email.RecipientRole;

import java.util.Locale;
import java.util.UUID;

public record ResolvedRecipient(
        UUID userId,
        String email,
        String name,
        Locale locale,
        RecipientRole role
) {}
```

- [x] **Step 2: Failing test**

Create `backend/src/test/java/com/datagami/rentaxis/core/email/dispatch/RecipientResolverTest.java`:

```java
package com.datagami.rentaxis.core.email.dispatch;

import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.event.payload.LeasePayload;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.Language;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecipientResolverTest {

    @Mock UserRepository userRepo;
    @Mock RenterRepository renterRepo;
    @InjectMocks RecipientResolver resolver;

    @Test
    void resolvesLeaseSignedToRenterAndPropertyManagerWithRenterArabicLocale() {
        UUID renterUserId = UUID.randomUUID();
        UUID managerUserId = UUID.randomUUID();
        UUID leaseId = UUID.randomUUID();

        User renterUser = user(renterUserId, "renter@x", "Sara");
        User managerUser = user(managerUserId, "mgr@x", "Maya");
        Renter renter = new Renter();
        renter.setUserId(renterUserId);
        renter.setPrimaryLanguage(Language.AR);

        when(userRepo.findById(renterUserId)).thenReturn(Optional.of(renterUser));
        when(userRepo.findById(managerUserId)).thenReturn(Optional.of(managerUser));
        when(renterRepo.findByUserId(renterUserId)).thenReturn(Optional.of(renter));

        LeasePayload payload = new LeasePayload(
                leaseId, renterUserId, managerUserId,
                "Unit 4B", "Pearl Tower", "2026-06-01", "2027-06-01",
                "5,000 AED", "https://signed/url");

        List<ResolvedRecipient> recipients = resolver.resolve(EmailEventType.LEASE_SIGNED, payload);

        assertThat(recipients).hasSize(2);
        ResolvedRecipient renterRecipient = recipients.stream()
                .filter(r -> r.userId().equals(renterUserId)).findFirst().orElseThrow();
        assertThat(renterRecipient.locale().getLanguage()).isEqualTo("ar");
        ResolvedRecipient managerRecipient = recipients.stream()
                .filter(r -> r.userId().equals(managerUserId)).findFirst().orElseThrow();
        assertThat(managerRecipient.locale().getLanguage()).isEqualTo("en");
    }

    private User user(UUID id, String email, String name) {
        User u = new User();
        u.setId(id); u.setEmail(email); u.setName(name);
        return u;
    }
}
```

- [x] **Step 3: Run test to verify fail**

Run: `cd backend && ./gradlew test --tests RecipientResolverTest`
Expected: FAIL — `RecipientResolver` not implemented.

- [x] **Step 4: Implement RecipientResolver**

Create `backend/src/main/java/com/datagami/rentaxis/core/email/dispatch/RecipientResolver.java`:

```java
package com.datagami.rentaxis.core.email.dispatch;

import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.RecipientRole;
import com.datagami.rentaxis.core.email.event.payload.*;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.Language;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class RecipientResolver {

    private final UserRepository userRepo;
    private final RenterRepository renterRepo;

    public List<ResolvedRecipient> resolve(EmailEventType type, Object payload) {
        List<ResolvedRecipient> result = new ArrayList<>();
        for (RecipientRole role : type.recipientRoles()) {
            for (UUID userId : userIdsFor(role, payload)) {
                userRepo.findById(userId).ifPresent(u -> {
                    if (u.getEmail() == null || u.getEmail().isBlank()) return;
                    result.add(new ResolvedRecipient(
                            u.getId(),
                            u.getEmail(),
                            u.getName(),
                            resolveLocale(u),
                            role));
                });
            }
        }
        if (result.isEmpty()) {
            log.warn("No email recipients resolved for event {} payload={}", type, payload);
        }
        return dedupByUserId(result);
    }

    private Locale resolveLocale(User u) {
        return renterRepo.findByUserId(u.getId())
                .map(Renter::getPrimaryLanguage)
                .map(this::toLocale)
                .orElse(Locale.ENGLISH);
    }

    private Locale toLocale(Language lang) {
        return lang == Language.AR ? new Locale("ar") : Locale.ENGLISH;
    }

    private List<UUID> userIdsFor(RecipientRole role, Object payload) {
        return switch (role) {
            case USER, INVITEE, NEW_ADMIN -> uuidField(payload, "userId", "inviteeUserId", "newAdminUserId");
            case RENTER -> uuidField(payload, "renterUserId");
            case PROPERTY_MANAGER -> uuidField(payload, "propertyManagerUserId");
            case TENANT_ADMIN, EXISTING_ADMINS -> tenantAdmins(payload);
            case SUPER_ADMIN -> superAdmins();
        };
    }

    private List<UUID> uuidField(Object payload, String... candidates) {
        for (String f : candidates) {
            UUID id = readUuid(payload, f);
            if (id != null) return List.of(id);
        }
        return List.of();
    }

    private UUID readUuid(Object payload, String fieldName) {
        try {
            var method = payload.getClass().getMethod(fieldName);
            return (UUID) method.invoke(payload);
        } catch (NoSuchMethodException e) { return null; }
        catch (Exception e) { log.warn("Failed to read {} on {}: {}", fieldName, payload, e.getMessage()); return null; }
    }

    private List<UUID> tenantAdmins(Object payload) {
        UUID tenantId = readUuid(payload, "tenantId");
        if (tenantId == null) return List.of();
        return userRepo.findAllByTenantIdAndRole(tenantId, UserRole.TENANT_ADMIN).stream()
                .map(User::getId).toList();
    }

    private List<UUID> superAdmins() {
        return userRepo.findAllByRole(UserRole.SUPER_ADMIN).stream().map(User::getId).toList();
    }

    private List<ResolvedRecipient> dedupByUserId(List<ResolvedRecipient> in) {
        Map<UUID, ResolvedRecipient> byId = new LinkedHashMap<>();
        for (ResolvedRecipient r : in) byId.putIfAbsent(r.userId(), r);
        return new ArrayList<>(byId.values());
    }
}
```

> **Implementer note on `findAllByTenantIdAndRole` / `findAllByRole`:** Add these methods to `UserRepository` if they don't already exist:
> ```java
> List<User> findAllByTenantIdAndRole(UUID tenantId, UserRole role);
> List<User> findAllByRole(UserRole role);
> ```
> Add `findByUserId(UUID userId)` to `RenterRepository` if missing.

- [x] **Step 5: Run test**

Run: `cd backend && ./gradlew test --tests RecipientResolverTest`
Expected: PASS.

- [x] **Step 6: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/email/dispatch/RecipientResolver.java backend/src/main/java/com/datagami/rentaxis/core/email/dispatch/ResolvedRecipient.java backend/src/main/java/com/datagami/rentaxis/domain/repository/ backend/src/test/java/com/datagami/rentaxis/core/email/dispatch/RecipientResolverTest.java
git commit -m "feat(email): RecipientResolver maps event roles to users + locales"
```

---

## Task 18: EmailDispatcher (transactional event listener)

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/email/dispatch/EmailDispatcher.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/core/email/dispatch/PayloadVarsExtractor.java`
- Create: `backend/src/test/java/com/datagami/rentaxis/core/email/dispatch/EmailDispatcherIntegrationTest.java`

- [x] **Step 1: Implement payload-to-vars extractor**

Create `backend/src/main/java/com/datagami/rentaxis/core/email/dispatch/PayloadVarsExtractor.java`:

```java
package com.datagami.rentaxis.core.email.dispatch;

import com.datagami.rentaxis.core.email.EmailEventType;

import java.lang.reflect.RecordComponent;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PayloadVarsExtractor {

    public static Map<String, Object> extract(EmailEventType type, Object payload, String portalBaseUrl, String localeLang) {
        Map<String, Object> vars = new HashMap<>();
        if (payload != null && payload.getClass().isRecord()) {
            for (RecordComponent c : payload.getClass().getRecordComponents()) {
                try { vars.put(c.getName(), c.getAccessor().invoke(payload)); }
                catch (Exception ignored) {}
            }
        }
        vars.put("ctaUrl", computeCtaUrl(type, vars, portalBaseUrl, localeLang));
        Object[] subjectArgs = subjectArgsFor(type, vars);
        vars.put("__subjectArgs", subjectArgs);
        return vars;
    }

    private static String computeCtaUrl(EmailEventType type, Map<String, Object> vars, String base, String lang) {
        String prefix = base + "/" + lang + "/dashboard";
        return switch (type) {
            case USER_INVITED -> str(vars, "setPasswordUrl");
            case USER_WELCOMED, EMAIL_VERIFIED -> str(vars, "dashboardUrl");
            case PASSWORD_RESET_REQUESTED -> str(vars, "resetUrl");
            case LEASE_CONTRACT_GENERATED -> str(vars, "contractSignedUrl");
            case LEASE_CREATED, LEASE_SIGNATURE_REQUESTED, LEASE_SIGNED, LEASE_ACTIVATED,
                 LEASE_EXPIRING, LEASE_RENEWED, LEASE_TERMINATED, RENT_RECEIPT_AVAILABLE
                    -> prefix + "/leases/" + str(vars, "leaseId");
            case CHEQUE_RECEIVED, CHEQUE_DEPOSITED, CHEQUE_CLEARED, CHEQUE_BOUNCED,
                 PAYMENT_DUE_REMINDER, PAYMENT_OVERDUE, ONLINE_PAYMENT_RECEIVED, ONLINE_PAYMENT_FAILED,
                 PENALTY_INCURRED, PENALTY_CLEARED, PENALTY_WAIVED
                    -> prefix + "/finance/payments";
            case TICKET_ASSIGNED, TICKET_REPLY, TICKET_RESOLVED, TICKET_CREATED, TICKET_REOPENED
                    -> prefix + "/tickets/" + str(vars, "ticketId");
            case MEETING_REQUESTED, MEETING_APPROVED, MEETING_CANCELLED,
                 MEETING_COMPLETED, MEETING_NO_SHOW
                    -> prefix + "/meetings/" + str(vars, "meetingId");
            default -> base;
        };
    }

    private static Object[] subjectArgsFor(EmailEventType type, Map<String, Object> vars) {
        return switch (type) {
            case USER_INVITED -> new Object[]{ vars.getOrDefault("companyName", "RentAxis") };
            case TENANT_PROVISIONED -> new Object[]{ vars.get("tenantName") };
            case STAFF_ROLE_CHANGED -> new Object[]{ vars.get("userName") };
            case LEASE_CREATED, LEASE_SIGNED, LEASE_ACTIVATED, LEASE_EXPIRING,
                 LEASE_RENEWED, LEASE_TERMINATED, LEASE_SIGNATURE_REQUESTED
                    -> new Object[]{ vars.get("unitLabel"), vars.get("propertyName") };
            case CHEQUE_RECEIVED, CHEQUE_DEPOSITED, CHEQUE_CLEARED, CHEQUE_BOUNCED
                    -> new Object[]{ vars.get("chequeNumber"), vars.get("amountDisplay"), vars.get("installmentNumber") };
            case PAYMENT_DUE_REMINDER -> new Object[]{ vars.get("amountDisplay"), vars.get("dueDateIso"), vars.get("daysUntilDue") };
            case ONLINE_PAYMENT_RECEIVED, ONLINE_PAYMENT_FAILED -> new Object[]{ vars.get("amountDisplay") };
            case TICKET_ASSIGNED, TICKET_REPLY, TICKET_RESOLVED, TICKET_CREATED, TICKET_REOPENED
                    -> new Object[]{ vars.get("title") };
            case MEETING_REQUESTED, MEETING_APPROVED, MEETING_CANCELLED,
                 MEETING_COMPLETED, MEETING_NO_SHOW -> new Object[]{ vars.get("purpose") };
            case TENANT_ADMIN_ADDED -> new Object[]{ vars.get("newAdminName") };
            default -> new Object[0];
        };
    }

    private static String str(Map<String, Object> vars, String key) {
        Object v = vars.get(key);
        return v == null ? "" : v.toString();
    }
}
```

- [x] **Step 2: Implement dispatcher**

Create `backend/src/main/java/com/datagami/rentaxis/core/email/dispatch/EmailDispatcher.java`:

```java
package com.datagami.rentaxis.core.email.dispatch;

import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.email.outbox.EmailOutbox;
import com.datagami.rentaxis.core.email.outbox.EmailOutboxService;
import com.datagami.rentaxis.core.email.render.EmailRenderResult;
import com.datagami.rentaxis.core.email.render.EmailRenderer;
import com.datagami.rentaxis.core.email.render.EmailTemplateContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class EmailDispatcher {

    private final RecipientResolver recipientResolver;
    private final EmailPreferenceService preferenceService;
    private final TenantBrandingResolver brandingResolver;
    private final EmailRenderer renderer;
    private final EmailOutboxService outboxService;

    @Value("${NEXT_PUBLIC_API_URL:https://rentaxis.uaenorth.cloudapp.azure.com}")
    private String portalBaseUrl;

    @Value("${rentaxis.email.outbox.enabled:true}")
    private boolean enabled;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onEmailEvent(EmailEvent event) {
        if (!enabled) return;

        var recipients = recipientResolver.resolve(event.getType(), event.getPayload());
        TenantBranding branding = brandingResolver.resolve(event.getTenantId());

        for (ResolvedRecipient recipient : recipients) {
            if (!preferenceService.shouldSend(recipient.userId(), event.getType().category())) {
                log.info("email.dispatch.skipped reason=opt-out user_id={} event_type={}", recipient.userId(), event.getType());
                continue;
            }

            String unsubscribeToken = preferenceService.unsubscribeToken(recipient.userId());
            String unsubscribeUrl = portalBaseUrl + "/api/v1/email/unsubscribe?token=" + unsubscribeToken;

            String localeLang = recipient.locale().getLanguage();
            Map<String, Object> payloadVars = PayloadVarsExtractor.extract(
                    event.getType(), event.getPayload(), portalBaseUrl, localeLang);

            EmailTemplateContext ctx = new EmailTemplateContext(
                    event.getType(),
                    recipient.locale(),
                    recipient.userId(),
                    recipient.name(),
                    recipient.email(),
                    portalBaseUrl,
                    branding,
                    unsubscribeUrl,
                    payloadVars);

            EmailRenderResult rendered = renderer.render(ctx);

            EmailOutbox row = new EmailOutbox();
            row.setTenantId(event.getTenantId());
            row.setEventType(event.getType().name());
            row.setEventCategory(event.getType().category().name());
            row.setRecipientUserId(recipient.userId());
            row.setRecipientEmail(recipient.email());
            row.setRecipientLocale(localeLang);
            row.setSubject(rendered.subject());
            row.setBodyHtml(rendered.html());
            row.setBodyText(rendered.text());
            row.setDedupKey(event.getDedupKey());

            outboxService.enqueue(row);
        }

        log.info("email.dispatch.enqueued event_type={} recipients={} tenant_id={}",
                event.getType(), recipients.size(), event.getTenantId());
    }
}
```

- [x] **Step 3: Failing integration test**

Create `backend/src/test/java/com/datagami/rentaxis/core/email/dispatch/EmailDispatcherIntegrationTest.java`:

```java
package com.datagami.rentaxis.core.email.dispatch;

import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.email.event.payload.UserInvitedPayload;
import com.datagami.rentaxis.core.email.outbox.EmailOutbox;
import com.datagami.rentaxis.core.email.outbox.EmailOutboxRepository;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class EmailDispatcherIntegrationTest {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired ApplicationEventPublisher publisher;
    @Autowired EmailOutboxRepository outboxRepo;
    @Autowired UserRepository userRepo;
    @Autowired TransactionTemplate tx;

    @Test
    void publishingUserInvitedEnqueuesOutboxRowOnCommit() {
        User u = new User();
        u.setEmail("invitee@x.test");
        u.setName("Invitee");
        u.setRole(UserRole.RENTER);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u = userRepo.save(u);

        UUID userId = u.getId();
        UUID tenantId = UUID.randomUUID();

        tx.executeWithoutResult(status -> {
            publisher.publishEvent(new EmailEvent(this,
                    EmailEventType.USER_INVITED,
                    tenantId,
                    new UserInvitedPayload(userId, "Invitee", "https://app.test/set?t=x"),
                    "USER_INVITED:user=" + userId));
        });

        List<EmailOutbox> rows = outboxRepo.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getRecipientUserId()).isEqualTo(userId);
        assertThat(rows.get(0).getEventType()).isEqualTo("USER_INVITED");
        assertThat(rows.get(0).getStatus()).isEqualTo(EmailOutbox.Status.PENDING);
    }

    @Test
    void rolledBackTransactionDoesNotEnqueue() {
        User u = new User();
        u.setEmail("rb@x.test");
        u.setName("Rb");
        u.setRole(UserRole.RENTER);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u = userRepo.save(u);

        UUID userId = u.getId();
        UUID tenantId = UUID.randomUUID();

        try {
            tx.executeWithoutResult(status -> {
                publisher.publishEvent(new EmailEvent(this,
                        EmailEventType.USER_INVITED,
                        tenantId,
                        new UserInvitedPayload(userId, "Rb", "https://app.test/set?t=x"),
                        "USER_INVITED:user=" + userId + ":rb"));
                throw new RuntimeException("force rollback");
            });
        } catch (RuntimeException ignored) {}

        long count = outboxRepo.findAll().stream()
                .filter(r -> r.getDedupKey() != null && r.getDedupKey().endsWith(":rb"))
                .count();
        assertThat(count).isZero();
    }
}
```

- [x] **Step 4: Run integration test**

Run: `cd backend && ./gradlew test --tests EmailDispatcherIntegrationTest`
Expected: PASS — both tests succeed.

- [x] **Step 5: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/email/dispatch/EmailDispatcher.java backend/src/main/java/com/datagami/rentaxis/core/email/dispatch/PayloadVarsExtractor.java backend/src/test/java/com/datagami/rentaxis/core/email/dispatch/EmailDispatcherIntegrationTest.java
git commit -m "feat(email): EmailDispatcher with transactional event listener"
```

---

## Task 19: EmailOutboxService

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/email/outbox/EmailOutboxService.java`

- [ ] **Step 1: Implement service**

Create `backend/src/main/java/com/datagami/rentaxis/core/email/outbox/EmailOutboxService.java`:

```java
package com.datagami.rentaxis.core.email.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailOutboxService {

    private static final Duration STUCK_TIMEOUT = Duration.ofMinutes(5);

    private final EmailOutboxRepository repo;

    @Transactional
    public void enqueue(EmailOutbox row) {
        if (row.getScheduledAt() == null) row.setScheduledAt(Instant.now());
        try {
            repo.save(row);
        } catch (DataIntegrityViolationException e) {
            log.info("email.outbox.dedup_collision dedup_key={}", row.getDedupKey());
        }
    }

    @Transactional
    public void resetStuckSending() {
        List<EmailOutbox> stuck = repo.findStuckSending(Instant.now().minus(STUCK_TIMEOUT));
        for (EmailOutbox row : stuck) {
            log.warn("email.outbox.stuck_reset id={} attempts={}", row.getId(), row.getAttempts());
            row.setStatus(EmailOutbox.Status.PENDING);
            repo.save(row);
        }
    }

    public Duration backoff(int attempts) {
        return switch (attempts) {
            case 1 -> Duration.ofMinutes(1);
            case 2 -> Duration.ofMinutes(5);
            case 3 -> Duration.ofMinutes(15);
            case 4 -> Duration.ofHours(1);
            default -> Duration.ofHours(6);
        };
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `cd backend && ./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/email/outbox/EmailOutboxService.java
git commit -m "feat(email): EmailOutboxService (enqueue, sweeper, backoff)"
```

---

## Task 20: ShedLock configuration

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/email/config/ShedLockConfig.java`

- [ ] **Step 1: Configure ShedLock**

Create `backend/src/main/java/com/datagami/rentaxis/core/email/config/ShedLockConfig.java`:

```java
package com.datagami.rentaxis.core.email.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT2M")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build());
    }
}
```

- [ ] **Step 2: Verify boot**

Run: `cd backend && ./gradlew bootRun` (CTRL-C after startup completes)
Expected: No errors. ShedLock initialized against the `shedlock` table.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/email/config/ShedLockConfig.java
git commit -m "chore(email): ShedLock JDBC config for distributed scheduling"
```

---

## Task 21: EmailOutboxWorker

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/email/outbox/EmailOutboxWorker.java`
- Create: `backend/src/test/java/com/datagami/rentaxis/core/email/outbox/EmailOutboxWorkerTest.java`

- [ ] **Step 1: Failing test**

Create `backend/src/test/java/com/datagami/rentaxis/core/email/outbox/EmailOutboxWorkerTest.java`:

```java
package com.datagami.rentaxis.core.email.outbox;

import com.datagami.rentaxis.core.email.send.EmailSender;
import com.datagami.rentaxis.core.email.send.SendResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailOutboxWorkerTest {

    @Mock EmailOutboxRepository repo;
    @Mock EmailSender sender;
    @Mock EmailOutboxService service;
    @InjectMocks EmailOutboxWorker worker;

    @Test
    void successfulSendMarksRowSent() {
        EmailOutbox row = newPendingRow();
        when(repo.pickPending(anyInt())).thenReturn(List.of(row));
        when(sender.send(row)).thenReturn(new SendResult("msg-123", "Queued"));

        worker.tick();

        assertThat(row.getStatus()).isEqualTo(EmailOutbox.Status.SENT);
        assertThat(row.getAzureMessageId()).isEqualTo("msg-123");
        verify(repo, atLeastOnce()).save(row);
    }

    @Test
    void failedSendRequeuesWithBackoff() {
        EmailOutbox row = newPendingRow();
        when(repo.pickPending(anyInt())).thenReturn(List.of(row));
        when(sender.send(row)).thenThrow(new RuntimeException("ACS 503"));
        when(service.backoff(1)).thenReturn(java.time.Duration.ofMinutes(1));

        worker.tick();

        assertThat(row.getStatus()).isEqualTo(EmailOutbox.Status.PENDING);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getLastError()).contains("ACS 503");
    }

    @Test
    void exhaustingMaxAttemptsMarksFailed() {
        EmailOutbox row = newPendingRow();
        row.setAttempts(4);
        row.setMaxAttempts(5);
        when(repo.pickPending(anyInt())).thenReturn(List.of(row));
        when(sender.send(row)).thenThrow(new RuntimeException("permanent"));
        when(service.backoff(anyInt())).thenReturn(java.time.Duration.ofHours(1));

        worker.tick();

        assertThat(row.getStatus()).isEqualTo(EmailOutbox.Status.FAILED);
        assertThat(row.getAttempts()).isEqualTo(5);
    }

    private EmailOutbox newPendingRow() {
        EmailOutbox r = new EmailOutbox();
        r.setId(UUID.randomUUID());
        r.setStatus(EmailOutbox.Status.PENDING);
        r.setEventType("LEASE_SIGNED");
        r.setEventCategory("TRANSACTIONAL");
        r.setRecipientUserId(UUID.randomUUID());
        r.setRecipientEmail("a@b");
        r.setRecipientLocale("en");
        r.setSubject("s");
        r.setBodyHtml("<p>x</p>");
        r.setScheduledAt(Instant.now().minusSeconds(1));
        r.setMaxAttempts(5);
        return r;
    }
}
```

- [ ] **Step 2: Run test to verify fail**

Run: `cd backend && ./gradlew test --tests EmailOutboxWorkerTest`
Expected: FAIL — `EmailOutboxWorker` not implemented.

- [ ] **Step 3: Implement worker**

Create `backend/src/main/java/com/datagami/rentaxis/core/email/outbox/EmailOutboxWorker.java`:

```java
package com.datagami.rentaxis.core.email.outbox;

import com.datagami.rentaxis.core.email.send.EmailSender;
import com.datagami.rentaxis.core.email.send.SendResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class EmailOutboxWorker {

    private final EmailOutboxRepository repo;
    private final EmailSender sender;
    private final EmailOutboxService service;

    @Value("${rentaxis.email.outbox.batch-size:50}")
    private int batchSize;

    @Value("${rentaxis.email.outbox.enabled:true}")
    private boolean enabled;

    @Scheduled(fixedDelayString = "${rentaxis.email.outbox.tick-ms:30000}")
    @SchedulerLock(name = "email-outbox-worker", lockAtMostFor = "PT2M", lockAtLeastFor = "PT5S")
    public void tick() {
        if (!enabled) return;
        try {
            service.resetStuckSending();
        } catch (Exception e) {
            log.warn("email.outbox.sweeper_failed: {}", e.getMessage());
        }
        List<EmailOutbox> batch = repo.pickPending(batchSize);
        for (EmailOutbox row : batch) {
            processOne(row);
        }
    }

    @Transactional
    public void processOne(EmailOutbox row) {
        row.setStatus(EmailOutbox.Status.SENDING);
        row.setLastAttemptAt(Instant.now());
        repo.saveAndFlush(row);
        try {
            SendResult r = sender.send(row);
            row.setAzureMessageId(r.azureMessageId());
            row.setAzureDeliveryStatus(r.azureDeliveryStatus());
            row.setStatus(EmailOutbox.Status.SENT);
            log.info("email.outbox.send.success id={} azure_id={} event={}",
                    row.getId(), r.azureMessageId(), row.getEventType());
        } catch (Exception e) {
            row.setAttempts(row.getAttempts() + 1);
            row.setLastError(truncate(e.getMessage()));
            if (row.getAttempts() >= row.getMaxAttempts()) {
                row.setStatus(EmailOutbox.Status.FAILED);
                log.error("email.outbox.send.fail_final id={} attempts={} error={}",
                        row.getId(), row.getAttempts(), e.getMessage());
            } else {
                row.setStatus(EmailOutbox.Status.PENDING);
                row.setScheduledAt(Instant.now().plus(service.backoff(row.getAttempts())));
                log.warn("email.outbox.send.fail_retry id={} attempts={} backoff_to={} error={}",
                        row.getId(), row.getAttempts(), row.getScheduledAt(), e.getMessage());
            }
        }
        repo.save(row);
    }

    private String truncate(String s) {
        if (s == null) return null;
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }
}
```

- [ ] **Step 4: Run test**

Run: `cd backend && ./gradlew test --tests EmailOutboxWorkerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/email/outbox/EmailOutboxWorker.java backend/src/test/java/com/datagami/rentaxis/core/email/outbox/EmailOutboxWorkerTest.java
git commit -m "feat(email): EmailOutboxWorker with retry + ShedLock"
```

---

## Task 22: Unsubscribe + preferences API

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/email/api/UnsubscribeController.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/core/email/api/EmailPreferencesController.java`

- [ ] **Step 1: Implement unsubscribe controller**

Create `backend/src/main/java/com/datagami/rentaxis/core/email/api/UnsubscribeController.java`:

```java
package com.datagami.rentaxis.core.email.api;

import com.datagami.rentaxis.core.email.dispatch.EmailPreferenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/email")
@RequiredArgsConstructor
@Slf4j
public class UnsubscribeController {

    private final EmailPreferenceService prefs;

    @GetMapping(value = "/unsubscribe", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> unsubscribe(@RequestParam("token") String token) {
        return prefs.disableMarketingByToken(token)
                .map(uid -> {
                    log.info("email.unsubscribe user_id={} source=link", uid);
                    return ResponseEntity.ok("<html><body><h2>You've been unsubscribed</h2>" +
                            "<p>You will no longer receive marketing emails from RentAxis. " +
                            "You will continue to receive transactional emails (lease, payment, etc).</p></body></html>");
                })
                .orElseGet(() -> ResponseEntity.status(404).body("<html><body>Unknown token</body></html>"));
    }
}
```

- [ ] **Step 2: Implement preferences controller**

Create `backend/src/main/java/com/datagami/rentaxis/core/email/api/EmailPreferencesController.java`:

```java
package com.datagami.rentaxis.core.email.api;

import com.datagami.rentaxis.core.email.dispatch.EmailPreferenceService;
import com.datagami.rentaxis.core.email.prefs.EmailPreferences;
import com.datagami.rentaxis.core.email.prefs.EmailPreferencesRepository;
import com.datagami.rentaxis.core.security.AuthenticatedUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/email/preferences")
@RequiredArgsConstructor
public class EmailPreferencesController {

    private final EmailPreferenceService service;
    private final EmailPreferencesRepository repo;
    private final AuthenticatedUserProvider auth;

    @GetMapping
    public ResponseEntity<Map<String, Object>> get() {
        UUID userId = auth.currentUserId();
        EmailPreferences p = service.ensureRow(userId);
        return ResponseEntity.ok(Map.of(
                "marketingEnabled", p.isMarketingEnabled(),
                "preferences", p.getPreferencesJson()));
    }

    @PutMapping
    public ResponseEntity<Void> update(@RequestBody Map<String, Object> body) {
        UUID userId = auth.currentUserId();
        if (body.get("marketingEnabled") instanceof Boolean b) service.setMarketing(userId, b);
        return ResponseEntity.noContent().build();
    }
}
```

> **Implementer note:** if `AuthenticatedUserProvider` doesn't exist, use whatever existing pattern the codebase uses to get the current user ID from `SecurityContextHolder` (e.g., look at `NotificationController` for the pattern).

- [ ] **Step 3: Verify compile**

Run: `cd backend && ./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`. If `AuthenticatedUserProvider` doesn't exist, replace it with the codebase's actual auth pattern before compiling.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/email/api/
git commit -m "feat(email): unsubscribe + preferences endpoints"
```

---

## Task 23: Migrate NotificationService to publish events

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/NotificationService.java`

- [ ] **Step 1: Refactor NotificationService**

Replace the body of `NotificationService.notify(...)` with: write the in-app `Notification` row (existing logic), then publish an `EmailEvent` with the matching `EmailEventType`. Delete `sendEmailAsync`, `buildEmailHtml`, `safe` and the Azure ACS imports.

```java
// (top imports — remove azure-communication imports, add)
import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.event.EmailEvent;
import org.springframework.context.ApplicationEventPublisher;

@Service
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final UserRepository userRepository;
    private final LeaseRepository leaseRepository;
    private final ApplicationEventPublisher events;

    public NotificationService(NotificationRepository notificationRepository,
                               DeviceTokenRepository deviceTokenRepository,
                               UserRepository userRepository,
                               LeaseRepository leaseRepository,
                               ApplicationEventPublisher events) {
        this.notificationRepository = notificationRepository;
        this.deviceTokenRepository = deviceTokenRepository;
        this.userRepository = userRepository;
        this.leaseRepository = leaseRepository;
        this.events = events;
    }

    @Transactional
    public void notify(UUID tenantId, UUID userId, String type, String title, String message,
                       String referenceType, UUID referenceId) {
        Notification n = new Notification();
        n.setTenantId(tenantId);
        n.setUserId(userId);
        n.setType(type);
        n.setTitle(title);
        n.setMessage(message);
        n.setReferenceType(referenceType);
        n.setReferenceId(referenceId);
        n.setChannel("IN_APP");
        n.setIsRead(false);
        notificationRepository.save(n);

        // Best-effort email path: only fire if `type` maps cleanly to an EmailEventType.
        // Callers that want richer structured payloads should publish EmailEvent directly
        // (Tasks 24+). This shim keeps the 17 legacy callers working unchanged.
        EmailEventType mapped = mapLegacyType(type);
        if (mapped != null) {
            String dedup = mapped.name() + ":legacy:" + (referenceId != null ? referenceId : userId);
            events.publishEvent(new EmailEvent(this, mapped, tenantId,
                    new com.datagami.rentaxis.core.email.event.payload.LegacyNotificationPayload(
                            userId, title, message, referenceType, referenceId),
                    dedup));
        }
    }

    private EmailEventType mapLegacyType(String legacy) {
        return switch (legacy) {
            case "PAYMENT_CLEARED" -> EmailEventType.CHEQUE_CLEARED;
            case "PAYMENT_BOUNCED" -> EmailEventType.CHEQUE_BOUNCED;
            case "PAYMENT_FAILED"  -> EmailEventType.ONLINE_PAYMENT_FAILED;
            case "PAYMENT_DUE"     -> EmailEventType.PAYMENT_DUE_REMINDER;
            case "PAYMENT_OVERDUE" -> EmailEventType.PAYMENT_OVERDUE;
            case "PAYMENT_COLLECTED"-> EmailEventType.CHEQUE_RECEIVED;
            case "TICKET_ASSIGNED" -> EmailEventType.TICKET_ASSIGNED;
            case "TICKET_REPLY"    -> EmailEventType.TICKET_REPLY;
            case "TICKET_RESOLVED" -> EmailEventType.TICKET_RESOLVED;
            case "LEASE_EXPIRING"  -> EmailEventType.LEASE_EXPIRING;
            case "PENALTY_INCURRED"-> EmailEventType.PENALTY_INCURRED;
            case "PENALTY_CLEARED" -> EmailEventType.PENALTY_CLEARED;
            case "PENALTY_WAIVED"  -> EmailEventType.PENALTY_WAIVED;
            case "MEETING_REQUESTED"-> EmailEventType.MEETING_REQUESTED;
            case "MEETING_APPROVED" -> EmailEventType.MEETING_APPROVED;
            case "MEETING_CANCELLED"-> EmailEventType.MEETING_CANCELLED;
            case "MEETING_COMPLETED"-> EmailEventType.MEETING_COMPLETED;
            case "MEETING_NO_SHOW"  -> EmailEventType.MEETING_NO_SHOW;
            case "TENANT_PROVISIONED"-> EmailEventType.TENANT_PROVISIONED;
            default -> null;
        };
    }

    // sendPenaltyIncurred / sendPenaltyCleared / sendPenaltyWaived / getNotifications /
    // getUnreadCount / markAsRead / markAllAsRead / registerDevice / mapToDTO
    // STAY UNCHANGED — they all flow through `notify(...)` which now publishes events.
}
```

- [ ] **Step 2: Add LegacyNotificationPayload**

Create `backend/src/main/java/com/datagami/rentaxis/core/email/event/payload/LegacyNotificationPayload.java`:

```java
package com.datagami.rentaxis.core.email.event.payload;

import java.util.UUID;

public record LegacyNotificationPayload(
        UUID userId,
        String title,
        String body,
        String referenceType,
        UUID referenceId
) {}
```

> **Implementer note:** templates rendered for legacy events fall back to the generic message via the EN/AR properties. Since legacy callers pass only title+body strings, this is a transitional shim — Tasks 24+ replace the legacy path with structured payloads where the data is available.

- [ ] **Step 3: Add a generic fallback template**

Create `backend/src/main/resources/templates/email/events/legacy_notification.html`:

```html
<th:block xmlns:th="http://www.thymeleaf.org" th:replace="~{${layout} :: layout(~{::content})}">
<th:block th:fragment="content">
  <h2 th:text="${title}" style="margin:0 0 8px;color:#0F172A;font-size:18px;font-weight:600;"></h2>
  <p th:utext="${body}" style="margin:0 0 16px;color:#475569;font-size:14px;line-height:1.6;"></p>
</th:block>
</th:block>
```

> Note: this template name `legacy_notification` doesn't match any `EmailEventType.snake()`. To make the renderer use it for legacy payloads, special-case the `LegacyNotificationPayload` in `EmailRenderer.render(...)`: if `payload instanceof LegacyNotificationPayload`, override the template name to `email/events/legacy_notification` and the subject to `payload.title()`. Add this branch to `EmailRenderer` (one extra `if` at top of `render()`).

- [ ] **Step 4: Run NotificationService callers — full test suite**

Run: `cd backend && ./gradlew test`
Expected: All tests pass. The 17 existing callers of `NotificationService.notify` produce the same in-app notification but now route email through the outbox.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/NotificationService.java backend/src/main/java/com/datagami/rentaxis/core/email/event/payload/LegacyNotificationPayload.java backend/src/main/resources/templates/email/events/legacy_notification.html backend/src/main/java/com/datagami/rentaxis/core/email/render/EmailRenderer.java
git commit -m "refactor(email): NotificationService publishes EmailEvent instead of inline send"
```

---

## Task 24: Wire new lease events into lease services

**Files:**
- Modify: lease-related services (likely `LeaseService.java`, `ContractGenerationService.java` — investigate at task time)

- [ ] **Step 1: Locate lease lifecycle code**

Run: `grep -rln "class.*LeaseService\|ContractGeneration\|LeaseSignature\|leaseRepository.save" backend/src/main/java`
Expected: handful of services. Read each to find the commit points where `LEASE_CREATED`, `LEASE_CONTRACT_GENERATED`, `LEASE_SIGNATURE_REQUESTED`, `LEASE_SIGNED`, `LEASE_ACTIVATED`, `LEASE_RENEWED`, `LEASE_TERMINATED` should fire.

- [ ] **Step 2: Inject ApplicationEventPublisher and emit events**

Pattern (apply at each lifecycle commit point):

```java
@Autowired private ApplicationEventPublisher events;

// inside the @Transactional service method, after persisting the change:
events.publishEvent(new EmailEvent(this,
        EmailEventType.LEASE_SIGNED,
        lease.getTenantId(),
        new LeasePayload(
                lease.getId(),
                lease.getRenter().getUserId(),
                lease.getPropertyManagerUserId(),  // resolve from lease
                lease.getUnit().getLabel(),
                lease.getUnit().getProperty().getName(),
                lease.getStartDate().toString(),
                lease.getEndDate().toString(),
                formatAmount(lease.getMonthlyRent()),
                signedUrlForContract(lease)),
        "LEASE_SIGNED:" + lease.getId()));
```

For each event, the dedup key is `"<EVENT>:<leaseId>"`. For multi-fire events that can legitimately repeat (e.g., a lease re-signed after revisions), append a discriminator like `:" + Instant.now().toEpochMilli()`.

- [ ] **Step 3: Run targeted tests + smoke**

Run: `cd backend && ./gradlew test`
Expected: PASS.

Manual: run the app, sign a lease in the UI, verify `email_outbox` row appears and email is sent in dev (with `AZURE_COMMUNICATION_CONNECTION_STRING` set or stub mode).

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/...
git commit -m "feat(email): emit LEASE_* events from lease lifecycle"
```

---

## Task 25: Wire new cheque/payment events

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/PaymentScheduleService.java` and cheque-related services
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/OnlinePaymentService.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/RentReceiptService.java`

- [ ] **Step 1: Emit events at each lifecycle point**

Same pattern as Task 24. Events to add:
- `CHEQUE_RECEIVED` — when cheque is collected (existing legacy `PAYMENT_COLLECTED` path is now covered by Task 23 mapping; add structured `ChequePayload` here too).
- `CHEQUE_DEPOSITED` — when cheque sent to bank (NEW lifecycle hook — `PaymentScheduleService.markDeposited` if it exists, else add).
- `ONLINE_PAYMENT_RECEIVED` — `OnlinePaymentService` after Razorpay verify-success.
- `RENT_RECEIPT_AVAILABLE` — `RentReceiptService` after PDF generation; payload includes `pdfBase64` from the generated PDF.

- [ ] **Step 2: Run tests + smoke**

Run: `cd backend && ./gradlew test`
Expected: PASS. Manually trigger an online payment and a cheque deposit; verify outbox rows.

- [ ] **Step 3: Commit**

```bash
git commit -am "feat(email): emit CHEQUE_DEPOSITED, ONLINE_PAYMENT_RECEIVED, RENT_RECEIPT_AVAILABLE"
```

---

## Task 26: Wire onboarding/auth events

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/AuthController.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/UserService.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/LandlordOrgService.java`

- [ ] **Step 1: Emit events**

Add `ApplicationEventPublisher` to each service. Emit:
- `USER_INVITED` — `UserService` after creating a renter/staff user with a set-password token. Set `setPasswordUrl` to the existing onboarding link.
- `USER_WELCOMED` — `AuthController` on first successful login (track in `users.welcomed_at` column or via `last_login_at IS NULL` heuristic; if no such column exists, defer this event with a TODO and ship without).
- `PASSWORD_RESET_REQUESTED` — wherever the reset token is issued.
- `PASSWORD_CHANGED` — wherever password is actually changed.
- `EMAIL_VERIFIED` — wherever email verification flips.
- `TENANT_ADMIN_ADDED` — `LandlordOrgService` when an admin is added.
- `STAFF_ROLE_CHANGED` — `UserService` when role is updated.

If `welcomed_at` column doesn't exist, add it via a tiny changeset:

```yaml
# 32-add-users-welcomed-at.yaml
databaseChangeLog:
  - changeSet:
      id: 32-add-users-welcomed-at
      author: rentaxis
      changes:
        - addColumn:
            tableName: users
            columns:
              - column: { name: welcomed_at, type: timestamp }
```

- [ ] **Step 2: Run tests + smoke**

Run: `cd backend && ./gradlew test`
Expected: PASS. Manually invite a renter; verify outbox row + email arrives at the invited address.

- [ ] **Step 3: Commit**

```bash
git commit -am "feat(email): emit USER_INVITED, PASSWORD_*, EMAIL_VERIFIED, STAFF_ROLE_CHANGED"
```

---

## Task 27: Wire new ticket events

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/MaintenanceTicketService.java`

- [ ] **Step 1: Emit events**

Add:
- `TICKET_CREATED` — when a renter raises a ticket; recipient is property manager.
- `TICKET_REOPENED` — when a resolved ticket is reopened.

Existing `TICKET_ASSIGNED`, `TICKET_REPLY`, `TICKET_RESOLVED` continue flowing through `NotificationService.notify(...)` (already migrated in Task 23).

- [ ] **Step 2: Tests + commit**

```bash
cd backend && ./gradlew test
git commit -am "feat(email): emit TICKET_CREATED, TICKET_REOPENED"
```

---

## Task 28: Admin email outbox controller

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/email/api/EmailOutboxAdminController.java`

- [ ] **Step 1: Implement controller**

Create `backend/src/main/java/com/datagami/rentaxis/core/email/api/EmailOutboxAdminController.java`:

```java
package com.datagami.rentaxis.core.email.api;

import com.datagami.rentaxis.core.email.outbox.EmailOutbox;
import com.datagami.rentaxis.core.email.outbox.EmailOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/email/outbox")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
public class EmailOutboxAdminController {

    private final EmailOutboxRepository repo;

    @GetMapping
    public List<EmailOutbox> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String eventType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return repo.findAll(pageable).getContent();
    }

    @GetMapping("/{id}")
    public ResponseEntity<EmailOutbox> get(@PathVariable UUID id) {
        return repo.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<Void> retry(@PathVariable UUID id) {
        return repo.findById(id).map(row -> {
            row.setStatus(EmailOutbox.Status.PENDING);
            row.setScheduledAt(Instant.now());
            row.setLastError(null);
            repo.save(row);
            return ResponseEntity.noContent().<Void>build();
        }).orElse(ResponseEntity.notFound().build());
    }
}
```

- [ ] **Step 2: Smoke test**

Run: `cd backend && ./gradlew bootRun`. Hit `GET /api/v1/admin/email/outbox` as a SUPER_ADMIN. Verify 200 with rows.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/email/api/EmailOutboxAdminController.java
git commit -m "feat(email): admin endpoints for outbox list/detail/retry"
```

---

## Task 29: End-to-end integration test

**Files:**
- Create: `backend/src/test/java/com/datagami/rentaxis/core/email/EmailPipelineE2ETest.java`

- [ ] **Step 1: Write test**

Create `backend/src/test/java/com/datagami/rentaxis/core/email/EmailPipelineE2ETest.java`:

```java
package com.datagami.rentaxis.core.email;

import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.email.event.payload.UserInvitedPayload;
import com.datagami.rentaxis.core.email.outbox.EmailOutbox;
import com.datagami.rentaxis.core.email.outbox.EmailOutboxRepository;
import com.datagami.rentaxis.core.email.outbox.EmailOutboxWorker;
import com.datagami.rentaxis.core.email.send.EmailSender;
import com.datagami.rentaxis.core.email.send.SendResult;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
class EmailPipelineE2ETest {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @MockBean EmailSender sender;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired UserRepository userRepo;
    @Autowired EmailOutboxRepository outboxRepo;
    @Autowired EmailOutboxWorker worker;
    @Autowired TransactionTemplate tx;

    @Test
    void publishEventEnqueuesAndWorkerSends() {
        User u = new User();
        u.setEmail("e2e@x.test");
        u.setName("E2E");
        u.setRole(UserRole.RENTER);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u = userRepo.save(u);
        UUID userId = u.getId();

        when(sender.send(any(EmailOutbox.class))).thenReturn(new SendResult("msg-e2e", "Queued"));

        tx.executeWithoutResult(s -> publisher.publishEvent(new EmailEvent(this,
                EmailEventType.USER_INVITED,
                UUID.randomUUID(),
                new UserInvitedPayload(userId, "E2E", "https://app.test/set?t=x"),
                "E2E:" + userId)));

        List<EmailOutbox> rows = outboxRepo.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(EmailOutbox.Status.PENDING);

        worker.tick();

        EmailOutbox after = outboxRepo.findById(rows.get(0).getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(EmailOutbox.Status.SENT);
        assertThat(after.getAzureMessageId()).isEqualTo("msg-e2e");
    }
}
```

- [ ] **Step 2: Run E2E test**

Run: `cd backend && ./gradlew test --tests EmailPipelineE2ETest`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java/com/datagami/rentaxis/core/email/EmailPipelineE2ETest.java
git commit -m "test(email): end-to-end pipeline integration test"
```

---

## Task 30: Final smoke test on staging

- [ ] **Step 1: Deploy to staging**

Standard deployment via the project's CI/CD pipeline.

- [ ] **Step 2: Trigger one event of each Phase 1 category and verify**

For each event below, perform the action in the staging UI/API and confirm:
1. `email_outbox` row created within 5s of action (PENDING)
2. Worker picks up within 30s (SENDING → SENT)
3. Email arrives at the test inbox
4. EN test user gets EN; AR test user gets AR + RTL
5. Tenant logo + name visible in body
6. Deep link routes to the correct locale
7. Unsubscribe link works

Events to smoke:
- USER_INVITED (invite a renter)
- LEASE_SIGNED (sign a lease)
- CHEQUE_DEPOSITED (mark a cheque deposited)
- CHEQUE_BOUNCED (mark a cheque bounced)
- ONLINE_PAYMENT_RECEIVED (complete a Razorpay test payment)
- TICKET_CREATED (renter raises a ticket)
- MEETING_REQUESTED (renter requests a meeting)

- [ ] **Step 3: Verify metrics endpoint**

Run: `curl https://staging/actuator/metrics/email.outbox.sent.total`
Expected: counter > 0.

- [ ] **Step 4: Tag release**

```bash
git tag -a v-email-phase1 -m "Email Notifications Phase 1 — outbox pipeline + Phase 1 events"
git push origin v-email-phase1
```

---

## Self-Review

**Spec coverage:**
- Provider Azure ACS — Tasks 1, 12. ✓
- Per-event recipient mapping — Tasks 4, 17. ✓
- AR/EN with RTL + locale-aware deep links — Tasks 13, 14, 16, 18 (`PayloadVarsExtractor.computeCtaUrl`). ✓
- Marketing opt-out (PDPL) — Tasks 3, 7, 8, 22. ✓
- Transactional outbox + retry worker — Tasks 2, 5, 6, 19, 21. ✓
- Thymeleaf + i18n properties — Tasks 13, 14, 15, 16. ✓
- Phase 1 event catalog — Tasks 4, 11, 24, 25, 26, 27. ✓
- Hybrid attachments (PDF / signed URL / deep link) — Task 4 (`AttachmentPolicy` enum), Task 12 (`AzureAcsEmailSender.parseAttachments`), Task 25 (RENT_RECEIPT_AVAILABLE attaches PDF base64), Task 24 (LEASE_CONTRACT_GENERATED uses `contractSignedUrl`). ✓
- Single sender + tenant logo/name in body — Tasks 9, 13, 14. ✓
- 6-step rollout plan — covered across tasks; `email.outbox.enabled` feature flag in Tasks 18, 21. ✓
- AFTER_COMMIT semantics + idempotency via dedup_key — Tasks 2, 18, 19. ✓
- Stuck-row sweeper — Tasks 6, 19, 21. ✓
- Admin endpoints — Task 28. ✓
- Observability (logs, metrics, alerts) — log lines present throughout; Micrometer metrics surface via Actuator (already a dep). Slack alerts deferred to ops config — note in spec "if configured". Acceptable. ✓

**Placeholder scan:** No "TBD"/"TODO" outside of explicit "implementer note" callouts that direct the reader to a concrete decision. The largest "implementer note" is in Task 16 step 2 (AR translation work). That's a content task by design — copy is provided as a starting point and the file structure is enforced. Acceptable.

**Type consistency:** `EmailOutbox.Status` enum used consistently. `EmailEventType.snake()` used consistently. `TenantBranding` declared in Task 9 + Task 13 step 2 explicitly handles relocation if Task 9 placed it inline. `RecipientResolver.resolve()` returns `List<ResolvedRecipient>` matched by `EmailDispatcher`'s usage. `SendResult` shape matches both producer (`AzureAcsEmailSender`) and consumer (`EmailOutboxWorker`).

**Scope:** Phase 1 only — Phase 2 events (digests, marketplace, login alerts) explicitly out of scope per spec. Plan is sized to one execution cycle.

---

Plan complete and saved to `docs/superpowers/plans/2026-05-06-email-notifications.md`. Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
