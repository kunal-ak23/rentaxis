# Email Notifications — Design Spec

**Date:** 2026-05-06
**Status:** Draft (awaiting user approval)
**Owner:** Backend / Notifications

## Goal

Replace the existing best-effort, inline email-send code in `NotificationService` with a durable, observable, multi-tenant, bilingual (EN/AR) email pipeline that covers every key user-facing event in RentAxis. Every contractually relevant moment in the platform — onboarding, lease lifecycle, cheque/payment status, penalties, tickets, meetings — must be communicated to the right people in the right language with the right reliability guarantees.

## Why now

Existing state (`backend/src/main/java/com/datagami/rentaxis/core/service/NotificationService.java`) has gaps that block a real email program:

- No user-onboarding emails (welcome, password setup).
- No lease creation / contract / signature emails.
- No `CHEQUE_DEPOSITED` event (only collected / cleared / bounced).
- Hardcoded `/en/dashboard/...` URLs — Arabic users get English deep links into an Arabic portal.
- No per-user opt-out (PDPL / UAE compliance gap for marketing).
- No retry, no delivery tracking, no audit. Send failures vanish into `log.error(...)`.
- No multi-tenant branding: every email looks like RentAxis, not the property-management company.
- Templating is hand-rolled string-replace inside Java — unmaintainable past ~15 events.

## Decisions (locked during brainstorm)

| Topic | Decision |
|---|---|
| Provider | **Azure Communication Services Email** — already integrated (`azure-communication-email:1.0.9`, `AZURE_COMMUNICATION_CONNECTION_STRING`, `AZURE_EMAIL_SENDER`). |
| Audience | **Per-event recipient mapping** — every event declares `recipients: [RENTER, PROPERTY_MANAGER, ...]`. All four roles supported. |
| Multi-language | **Honor `user.language`** — render EN or AR template + RTL layout, deep links route to the correct locale. |
| Preferences | **Two categories** (`TRANSACTIONAL` always sent, `MARKETING` opt-outable) backed by a `preferences_json` JSONB column ready for future per-event toggles. PDPL-compliant. |
| Reliability | **Persistent transactional outbox + retry worker** — every send writes a row, scheduled worker picks up and retries with backoff. |
| Templating | **Thymeleaf for HTML layout + Spring `MessageSource` (i18n properties) for copy.** Layout in `templates/email/`, copy in `messages/email_{en,ar}.properties`. |
| Event scope | **Phase 1: Auth/Onboarding + Lease + Cheque/Payment gaps + harden existing.** Phase 2: Digests, Marketplace, Login alerts. |
| Attachments | **Hybrid** — receipts attached as PDF; contracts via signed time-limited URL; everything else deep link only. |
| Branding | **Single RentAxis sender domain, tenant logo + company name injected into body.** Per-tenant verified sender domains deferred. |

## Architecture

```
Business Service (e.g. PaymentScheduleService.markFailed)
        │ emits
        ▼
EmailEvent (Spring ApplicationEvent, typed payload + tenantId)
        │ @TransactionalEventListener(AFTER_COMMIT)
        ▼
EmailDispatcher
  1. resolves recipients per role mapping
  2. resolves user.language + email preferences
  3. checks transactional vs marketing opt-out
  4. writes one row per (event, recipient) to email_outbox (status=PENDING)
        │
        ▼
email_outbox table   (PENDING → SENDING → SENT/FAILED, retry, azure_message_id)
        │ polled every 30s
        ▼
EmailOutboxWorker  (@Scheduled + ShedLock single-instance)
  1. pick N PENDING rows (FOR UPDATE SKIP LOCKED)
  2. render Thymeleaf + MessageSource(locale)
  3. send via Azure ACS EmailClient
  4. record azure_message_id, mark SENT
  5. retry FAILED with exponential backoff (max 5)
```

### Why event-driven + outbox

- Business services emit events without depending on email — clean dependency direction.
- `AFTER_COMMIT` ensures emails fire only when the business txn actually committed (no spurious "your cheque bounced" email for a rolled-back bounce).
- Outbox is durable — JVM crash mid-send doesn't lose anything.
- Worker is idempotent (uses `azure_message_id` to detect already-sent rows).

### Why one outbox row per recipient (not per event)

- Per-recipient locale + branding + opt-out resolution is independent.
- Failure of one recipient doesn't block others.
- Audit trail: "did manager X get the LEASE_SIGNED email?" is a direct query.

## Data model

New Liquibase changesets (next available index: `32-*`).

### `32-email-outbox.yaml` — durable send queue

```
table: email_outbox
columns:
  id                     uuid PK            (gen_random_uuid())
  tenant_id              uuid NULL          (NULL for system emails like password reset)
  event_type             varchar(60) NN     ('LEASE_SIGNED', 'CHEQUE_BOUNCED', ...)
  event_category         varchar(20) NN     ('TRANSACTIONAL' | 'MARKETING')
  recipient_user_id      uuid NN
  recipient_email        varchar(320) NN    (snapshot at enqueue time)
  recipient_locale       varchar(5) NN      ('en' | 'ar')
  subject                varchar(500) NN    (rendered)
  body_html              text NN            (rendered)
  body_text              text               (rendered plain-text fallback)
  attachments            jsonb              ([{name, contentType, base64 | signedUrl}])
  status                 varchar(20) NN     ('PENDING' | 'SENDING' | 'SENT' | 'FAILED' | 'CANCELLED')
  scheduled_at           timestamp NN       (now() default; future-dated for digests)
  attempts               int NN default 0
  max_attempts           int NN default 5
  last_attempt_at        timestamp
  last_error             text
  azure_message_id       varchar(100)       (Azure ACS operation id for status polling)
  azure_delivery_status  varchar(30)        ('Queued' | 'OutForDelivery' | 'Delivered' | 'Failed' | ...)
  reference_type         varchar(30)        ('LEASE', 'PAYMENT', 'TICKET', ...)
  reference_id           uuid
  dedup_key              varchar(200) UNIQUE NULL   (e.g. 'LEASE_SIGNED:lease=<uuid>:user=<uuid>')
  trace_id               varchar(50)        (correlation id)
  created_at             timestamp NN
  updated_at             timestamp NN

indexes:
  (status, scheduled_at)             -- worker pickup
  (recipient_user_id, created_at)    -- per-user history
  (event_type, created_at)           -- per-event audit
  (tenant_id, created_at)            -- per-tenant reporting
  (azure_message_id)                 -- delivery status callbacks/polling
  (dedup_key) UNIQUE                 -- collision-safe enqueue
```

### `32-email-preferences.yaml` — per-user toggles

```
table: email_preferences
columns:
  user_id               uuid PK FK→users.id
  marketing_enabled     boolean NN default true
  preferences_json      jsonb NN default '{}'      (future per-event toggles)
  unsubscribe_token     varchar(64) NN UNIQUE      (random URL-safe; auth for unsubscribe link)
  updated_at            timestamp NN
```

- Auto-row-created on user provisioning.
- Missing row treated as default (marketing on).
- Unsubscribe: `GET /api/v1/email/unsubscribe?token={token}` → flips `marketing_enabled = false`. Token is the auth.

### Java enum — `EmailEventType` (Phase 1 catalog)

Each enum value declares `category` (TRANSACTIONAL/MARKETING), `recipientRoles`, and `attachmentPolicy` (NONE / PDF / SIGNED_URL).

**Auth & Onboarding (NEW)**
- `USER_INVITED` → `[INVITEE]` — set-password link
- `USER_WELCOMED` → `[USER]` — first successful login
- `PASSWORD_RESET_REQUESTED` → `[USER]`
- `PASSWORD_CHANGED` → `[USER]` — security confirmation
- `EMAIL_VERIFIED` → `[USER]`

**Tenant / Org**
- `TENANT_PROVISIONED` → `[TENANT_ADMIN, SUPER_ADMIN]` *(exists)*
- `TENANT_ADMIN_ADDED` → `[NEW_ADMIN, EXISTING_ADMINS]` (NEW)
- `STAFF_ROLE_CHANGED` → `[USER, TENANT_ADMIN]` (NEW)

**Lease (mostly NEW)**
- `LEASE_CREATED` → `[RENTER, PROPERTY_MANAGER]`
- `LEASE_CONTRACT_GENERATED` → `[RENTER, PROPERTY_MANAGER]` — `attachmentPolicy=SIGNED_URL`
- `LEASE_SIGNATURE_REQUESTED` → `[RENTER]`
- `LEASE_SIGNED` → `[RENTER, PROPERTY_MANAGER]`
- `LEASE_ACTIVATED` → `[RENTER, PROPERTY_MANAGER]`
- `LEASE_EXPIRING` → `[RENTER, PROPERTY_MANAGER]` *(exists)*
- `LEASE_RENEWED` → `[RENTER, PROPERTY_MANAGER]`
- `LEASE_TERMINATED` → `[RENTER, PROPERTY_MANAGER]`

**Cheque & Payment (gaps + existing)**
- `CHEQUE_RECEIVED` → `[RENTER, PROPERTY_MANAGER]` (NEW)
- `CHEQUE_DEPOSITED` → `[RENTER, PROPERTY_MANAGER]` (NEW)
- `CHEQUE_CLEARED` → `[RENTER, PROPERTY_MANAGER]` *(was PAYMENT_CLEARED)*
- `CHEQUE_BOUNCED` → `[RENTER, PROPERTY_MANAGER]` *(was PAYMENT_BOUNCED)*
- `PAYMENT_DUE_REMINDER` → `[RENTER]` *(exists, 7/3/1 days)*
- `PAYMENT_OVERDUE` → `[RENTER, PROPERTY_MANAGER]` *(exists)*
- `ONLINE_PAYMENT_RECEIVED` → `[RENTER, PROPERTY_MANAGER]` (NEW — Razorpay success)
- `ONLINE_PAYMENT_FAILED` → `[RENTER]` *(was PAYMENT_FAILED)*
- `RENT_RECEIPT_AVAILABLE` → `[RENTER]` — `attachmentPolicy=PDF` (NEW)

**Penalties** *(all exist; migrate to outbox)*
- `PENALTY_INCURRED`, `PENALTY_CLEARED`, `PENALTY_WAIVED`

**Tickets** *(existing + new)*
- `TICKET_ASSIGNED`, `TICKET_REPLY`, `TICKET_RESOLVED` *(exist)*
- `TICKET_CREATED` → `[PROPERTY_MANAGER]` (NEW)
- `TICKET_REOPENED` → `[PROPERTY_MANAGER, RENTER]` (NEW)

**Meetings** *(all exist)*
- `MEETING_REQUESTED`, `MEETING_APPROVED`, `MEETING_CANCELLED`, `MEETING_COMPLETED`, `MEETING_NO_SHOW`

**Phase 2 (designed but not built v1)**
- `LISTING_INTEREST_RECEIVED`, `LISTING_PUBLISHED`, `LISTING_UNLISTED`
- `WEEKLY_DIGEST`, `MONTHLY_PORTFOLIO_REPORT`
- `LOGIN_FROM_NEW_DEVICE`

## Java components

```
com.datagami.rentaxis.core.email/
├── EmailEventType.java             enum (catalog, recipients, category, attachment policy)
├── EmailCategory.java              enum: TRANSACTIONAL, MARKETING
├── RecipientRole.java              enum: USER, INVITEE, RENTER, PROPERTY_MANAGER, TENANT_ADMIN, SUPER_ADMIN
│
├── event/
│   ├── EmailEvent.java             base ApplicationEvent (eventType, tenantId, payload)
│   ├── LeaseSignedEvent.java       one class per event (typed payload)
│   ├── ChequeBouncedEvent.java
│   └── ...
│
├── dispatch/
│   ├── EmailDispatcher.java        @TransactionalEventListener(AFTER_COMMIT)
│   ├── RecipientResolver.java      per-event role → user[] resolution
│   └── EmailPreferenceService.java marketing opt-out checks, unsubscribe token mgmt
│
├── render/
│   ├── EmailRenderer.java          Thymeleaf + MessageSource → (subject, htmlBody, textBody)
│   ├── EmailTemplateContext.java   payload + tenant branding + signed URLs + locale
│   └── AttachmentBuilder.java      PDF attach vs signed-URL vs deep-link by event policy
│
├── outbox/
│   ├── EmailOutbox.java            JPA entity
│   ├── EmailOutboxRepository.java  with @Query for FOR UPDATE SKIP LOCKED pickup
│   ├── EmailOutboxService.java     enqueue, mark sending/sent/failed
│   └── EmailOutboxWorker.java      @Scheduled(fixedDelay = 30s) tick
│
├── send/
│   ├── EmailSender.java            interface — single send(OutboxRow) method
│   └── AzureAcsEmailSender.java    Azure ACS impl (replaces inline code in NotificationService)
│
└── api/
    ├── UnsubscribeController.java       GET /api/v1/email/unsubscribe?token=...
    ├── EmailPreferencesController.java  GET/PUT /api/v1/email/preferences (authenticated)
    └── EmailHistoryController.java      admin-only: GET /api/v1/admin/email/outbox
```

### Key contracts

**Dispatcher (sketch):**
```java
@TransactionalEventListener(phase = AFTER_COMMIT)
public void onEmailEvent(EmailEvent event) {
    EmailEventType type = event.getType();
    List<User> recipients = recipientResolver.resolve(type, event.getPayload());
    for (User user : recipients) {
        if (type.category() == MARKETING && !preferences.marketingEnabled(user)) continue;
        EmailRenderResult rendered = renderer.render(type, user.getLocale(), event.getPayload());
        outboxService.enqueue(EmailOutboxRow.builder()
            .tenantId(event.getTenantId())
            .eventType(type)
            .recipientUserId(user.getId())
            .recipientEmail(user.getEmail())
            .recipientLocale(user.getLocale())
            .subject(rendered.subject())
            .bodyHtml(rendered.html())
            .bodyText(rendered.text())
            .attachments(rendered.attachments())
            .scheduledAt(now())
            .build());
    }
}
```

**Worker (sketch):**
```java
@Scheduled(fixedDelay = 30_000)
@SchedulerLock(name = "email-outbox-worker")
public void tick() {
    List<EmailOutbox> batch = repo.pickPending(BATCH_SIZE);  // FOR UPDATE SKIP LOCKED
    for (EmailOutbox row : batch) {
        try {
            row.setStatus(SENDING); row.setLastAttemptAt(now()); repo.saveAndFlush(row);
            SendResult r = sender.send(row);
            row.setAzureMessageId(r.messageId());
            row.setStatus(SENT);
        } catch (Exception e) {
            row.setAttempts(row.getAttempts() + 1);
            row.setLastError(truncate(e.getMessage()));
            row.setStatus(row.getAttempts() >= row.getMaxAttempts() ? FAILED : PENDING);
            row.setScheduledAt(now().plus(backoff(row.getAttempts())));
            // backoff: 1m, 5m, 15m, 1h, 6h
        }
        repo.save(row);
    }
}
```

### Migration of existing `NotificationService`

`NotificationService.notify(...)` keeps its current signature. Internally:
- Continues writing the in-app `Notification` row.
- Replaces inline `sendEmailAsync(...)` with `applicationEventPublisher.publishEvent(EmailEvent.from(...))`.

All 17 existing call sites (PenaltyService, MaintenanceTicketService, MeetingService, etc.) keep working unchanged — only the email-send code moves out.

## Templates & i18n

```
backend/src/main/resources/
├── templates/email/
│   ├── layout/
│   │   ├── master.html             Thymeleaf layout (LTR)
│   │   └── master-rtl.html         RTL mirror for AR
│   └── events/
│       ├── lease_signed.html
│       ├── cheque_bounced.html
│       └── ...                     one per EmailEventType
└── messages/
    ├── email_en.properties         EN copy keyed by event + slot
    └── email_ar.properties         AR copy, same keys
```

### Example fragment — `events/lease_signed.html`

```html
<div th:replace="email/layout/master :: layout(~{::content})">
  <th:block th:fragment="content">
    <h2 th:text="#{email.lease_signed.title}">Lease signed</h2>
    <p th:text="#{email.lease_signed.greeting(${recipient.name})}">Hi ...</p>
    <p th:utext="#{email.lease_signed.body(${unitLabel}, ${propertyName}, ${signedAt})}">...</p>
    <a th:href="${deepLink}" th:text="#{email.lease_signed.cta}" class="btn-primary">View lease</a>
    <table th:if="${tenantBranding != null}" class="footer-tenant">
      <tr><td>
        <img th:src="${tenantBranding.logoUrl}" th:alt="${tenantBranding.companyName}" />
        <span th:text="${tenantBranding.companyName}">Acme PM</span>
      </td></tr>
    </table>
  </th:block>
</div>
```

### Example copy — `email_en.properties`

```properties
email.lease_signed.subject={0} signed your lease for {1}
email.lease_signed.title=Lease signed
email.lease_signed.greeting=Hi {0},
email.lease_signed.body=Your lease for <b>{0}</b> at <b>{1}</b> has been signed on {2}. The signed contract is attached as a secure download link.
email.lease_signed.cta=View lease
```

### Locale resolution

```java
Locale locale = Locale.forLanguageTag(user.getLanguage().getCode());  // 'en' or 'ar'
String layout = locale.getLanguage().equals("ar") ? "master-rtl" : "master";
String subject = messageSource.getMessage(
    "email." + eventType.snake() + ".subject", payloadArgs, locale);
String html = thymeleafEngine.process("email/events/" + eventType.snake(),
    new Context(locale, contextVars));
```

### Deep-link routing fix

Replace hardcoded `/en/dashboard/...`:
```java
String deepLink = portalBaseUrl + "/" + locale.getLanguage() + "/dashboard/leases/" + leaseId;
```

### Tenant branding

Every render context auto-includes `tenantBranding = { logoUrl, companyName }` resolved from `tenants` table by the dispatcher. Null for system emails (password reset, etc.) — `th:if` skips the block.

### Translation workflow

EN is canonical. New event = add EN keys + AR keys + Thymeleaf fragment. Missing AR key falls back to EN with a `WARN` log so the gap is visible.

## Error handling, idempotency, observability

### Failure modes

| Failure | Detection | Response |
|---|---|---|
| Azure ACS 4xx (bad address, blocked sender) | Sync from `EmailClient.beginSend()` | Mark `FAILED` immediately, no retry. Log + Slack alert. |
| Azure ACS 5xx / network timeout | Exception | Increment `attempts`, requeue with backoff (1m, 5m, 15m, 1h, 6h). After `max_attempts` → `FAILED`. |
| User has no email | Resolved at dispatch time | Skip recipient, log INFO, no outbox row. |
| Recipient role resolves to empty | Dispatch | Log WARN with event_type + payload, no row written. |
| Worker crashes mid-`SENDING` | Row stuck in SENDING > 5 min | Sweeper at start of each tick: `WHERE status='SENDING' AND last_attempt_at < now()-5m` → reset to PENDING. |
| Two replicas pick same row | Concurrent ticks | `FOR UPDATE SKIP LOCKED` + ShedLock on the scheduled method. |
| Render template missing | Thymeleaf throws | Mark `FAILED` with last_error, no retry. Slack alert. |
| i18n key missing | `MessageSource` returns key name | Log WARN, send anyway with placeholder text. |

### Idempotency

- Each business event publishes one `EmailEvent` per business txn (AFTER_COMMIT fires once).
- `EmailDispatcher` writes outbox rows in a single `@Transactional` block — partial failure means zero rows written.
- Worker uses `azure_message_id`: SENDING row with a message id → poll Azure for status, do not re-send.
- `dedup_key` UNIQUE column (e.g. `LEASE_SIGNED:lease=<uuid>:user=<uuid>`) guarantees zero duplicates if a service accidentally publishes twice.

### Observability

```
Logs (structured, with trace_id):
  email.dispatch.enqueued       event_type, recipient_count, tenant_id
  email.outbox.send.start       outbox_id, event_type, recipient_email
  email.outbox.send.success     outbox_id, azure_message_id, latency_ms
  email.outbox.send.fail        outbox_id, attempts, error_class, error_msg
  email.unsubscribe             user_id, source=link

Metrics (Micrometer, tag by event_type + status):
  email.outbox.queue_depth      gauge — count of PENDING rows
  email.outbox.oldest_pending   gauge — age of oldest PENDING row in seconds
  email.outbox.sent.total       counter
  email.outbox.failed.total     counter
  email.outbox.send.latency     timer

Alerts (Slack webhook, if configured):
  - queue_depth > 500
  - oldest_pending > 10 min
  - failed.total spike (>20/min)
  - Azure ACS connection error
```

### Admin endpoints (table v1, UI Phase 2)

- `GET /api/v1/admin/email/outbox` — list with filters (event_type, status, date, recipient)
- `GET /api/v1/admin/email/outbox/{id}` — detail with rendered HTML preview
- `POST /api/v1/admin/email/outbox/{id}/retry` — manual requeue of FAILED row

### Security

- `body_html`/`body_text` contain PII — admin endpoints restricted to `SUPER_ADMIN` or `TENANT_ADMIN` (own tenant only).
- Unsubscribe token is 64-char URL-safe random, rotated only on user request.
- Signed download URLs for contracts use existing storage signing layer (assumption to confirm in plan stage).
- Email content is HTML-escaped at the Thymeleaf layer; `th:utext` slots receive only server-controlled values.

## Testing

**Unit:**
- `EmailEventTypeTest` — recipients/category/attachment policy correctness
- `RecipientResolverTest` — per-event role → user resolution (mocked repos)
- `EmailRendererTest` — HTML output, AR fallback to EN, RTL layout swap, tenant branding, HTML escape
- `EmailPreferenceServiceTest` — marketing opt-out blocks MARKETING but never TRANSACTIONAL
- `EmailOutboxWorkerTest` — backoff, max_attempts, stuck-row sweeper

**Integration (Spring + Testcontainers PG):**
- Publish event → outbox rows materialized for each recipient
- AFTER_COMMIT semantics: rolled-back txn produces zero rows
- Duplicate event with same `dedup_key` → single row
- Worker pickup → send → SENT happy path
- Failure → retry → eventual FAILED
- Concurrent workers don't double-send
- Unsubscribe flow: link flips marketing flag → subsequent MARKETING blocked, TRANSACTIONAL still sent

**Manual smoke (staging):**
- Trigger each Phase 1 event for two test users (one EN, one AR)
- Verify: arrival, locale, tenant branding, deep link, unsubscribe link, attachments

## Rollout plan

1. **Migration (changeset 32)** — create `email_outbox` + `email_preferences`; backfill `email_preferences` row per existing user (default `marketing_enabled=true`).
2. **Ship infrastructure dark** — outbox + worker deployed, but `NotificationService.notify()` still inline-sends. Worker exists with no rows enqueued. Verify scheduler runs cleanly.
3. **Cutover existing 17 events** — flip `NotificationService.notify()` to publish `EmailEvent`. Behavior identical (same recipients, same content) — just delivered via outbox. Watch metrics for regression. Feature flag `email.outbox.enabled` for per-environment revert.
4. **Add new Phase 1 events** — wire LEASE_*, CHEQUE_RECEIVED/DEPOSITED, USER_INVITED/WELCOMED, PASSWORD_*, EMAIL_VERIFIED, ONLINE_PAYMENT_RECEIVED, RENT_RECEIPT_AVAILABLE, TENANT_ADMIN_ADDED, STAFF_ROLE_CHANGED, TICKET_CREATED/REOPENED into their owning services.
5. **Translations** — fill `email_ar.properties` for every Phase 1 key (parallelizable with step 4).
6. **Remove legacy code path** — delete inline `sendEmailAsync` from `NotificationService`; `notify()` becomes a thin in-app + event-publish wrapper.

## Out of scope (explicit)

- Email open/click tracking (Azure ACS doesn't natively support).
- Per-tenant verified sender domains (white-label DNS).
- Granular per-event preferences UI (table is ready, UI deferred).
- Phase 2 events: digests, marketplace, login alerts.
- SendGrid migration / multi-provider fallback (kept simple under Q1 = A).
- Webhook ingestion of Azure ACS delivery reports (we poll on-demand for v1).

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| AR translations missing at launch | AR keys must be filled before each event goes live; missing-key → WARN log + EN fallback. |
| Outbox backlog under load | Tunable `BATCH_SIZE`; alerting on queue_depth and oldest_pending; Phase 2 problem if it becomes one. |
| Azure ACS regional quota | Plan-stage action: confirm UAE North quotas; set sane rate-limit on worker. |
| Cutover regression (step 3) | Feature flag `email.outbox.enabled`, per-env. Roll back on any anomaly. |
| Signed-URL infra assumption | Plan-stage: confirm `data/contracts` storage layer supports signed URLs. If not, scope adds a small signing service. |

## Open questions for plan stage

- Where exactly is the storage signing layer? `backend/data/contracts` exists — what's the current download flow for contracts in the dashboard? Plan stage will inspect `ContractGenerationService` and the contracts download endpoint, then decide between (a) reusing existing signing or (b) adding a small signing helper.
- Confirm Azure ACS sender quota for UAE North and whether daily limits will affect the Phase 2 digest design.

## Decided defaults (no further input needed)

- **Outbox writes happen in the same `@Transactional` block as the dispatcher's listener** — atomic with preferences lookup; partial failure means zero rows enqueued; simpler than a secondary event hop.
- **Recipient email is snapshotted at enqueue time** (not re-resolved at send time) — honors the email at the time the business event fired; avoids "I changed my email yesterday but the bounce notice went to my old address an hour ago" being wrong.
