# Lease Renewal Retention — Design Spec

**Author:** Kunal Sharma · **Date:** 2026-05-13 · **Status:** Draft, brainstorming complete

## 1. Goal

Increase renter retention by (a) reminding renters at 90 / 60 / 30 days before lease end-date through email + in-app, (b) capturing the renter's renewal intent via structured response buttons in the email, (c) giving PMs a CRM-style interaction log on every lease so every call / meeting / message with the renter is recorded, and (d) ensuring every reminder we ever send is auditable through a ledger.

The success criterion is *renter retention*, not just notification delivery. The design therefore weights data capture (intent + interaction log) at least as heavily as the reminder pipeline.

## 2. What already exists (not net-new)

- `NotificationScheduler.checkExpiringLeases()` at `backend/src/main/java/com/datagami/rentaxis/core/service/NotificationScheduler.java:131` fires 90/60/30-day reminders today, daily at 08:00, via `NotificationService.notify("LEASE_EXPIRING", ...)`. It writes an in-app notification row and emits a `LEASE_EXPIRING` `EmailEvent` mapped via `mapLegacyType` (`NotificationService.java:115`).
- `EmailDispatcher` (`backend/.../core/email/dispatch/EmailDispatcher.java:46`) uses `@TransactionalEventListener(AFTER_COMMIT)` to drain `EmailEvent`s into the outbox.
- `LeaseEvent` (`backend/.../domain/entity/LeaseEvent.java`) records lease *state* transitions (PENDING_SIGNATURE → ACTIVE → EXPIRED etc.). It is narrowly typed for status changes — not a free-form CRM log.
- Renter portal lives at `web/src/app/[locale]/dashboard/renter-portal/` with payments + penalties sub-pages.

## 3. Gaps this spec fills

1. **No reminder ledger.** The existing scheduler relies on `endDate == today + N days` exact match. If the daily job misses a day (deploy, downtime) or a lease activates inside the window, the reminder is silently lost. There's no record of "did we send the 90-day reminder for lease X?"
2. **No CRM interaction log.** No place to record "called renter, will renew at 5% increase" or "renter says they're moving out".
3. **Existing scheduler is `findAll().stream().filter(...)`** — loads every lease in every tenant on every run.
4. **No renter-facing renewal dashboard or intent-capture surface.**
5. **No PM notification on renter response.**

## 4. Audience and scope

- **Renter** is the primary audience for the reminders. They receive emails and have a renewal-status dashboard in their portal.
- **PM / Property Manager** is the primary audience for the CRM log and follow-up reminders, and is notified when a renter responds.
- **Out of scope** for this spec:
  - SMS / WhatsApp channels (defer; existing infra is email + in-app)
  - Auto-detecting renewal via lease-to-lease linkage (PMs mark `CLOSED_WON` manually for MVP)
  - PM-facing portfolio-wide renewal funnel dashboard (only per-lease panel + a small "follow-ups due" widget)
  - Bulk reminder / bulk-followup workflows
  - Joint tenancy (single renter per lease only)
  - Auto-generated renewal contracts

## 5. Architecture overview

```
                ┌────────────────────────────────────────────────────────────┐
                │  LeaseRenewalScheduler  (cron: 0 0 8 * * *, per tenant)   │
                │                                                            │
                │  Phase 1: open renewal_opportunities for leases entering   │
                │           the 90-day window                                │
                │  Phase 2: fire reminders per (opportunity, slot, channel), │
                │           idempotent via UNIQUE(opp, slot, channel)        │
                │  Phase 3: close opportunities whose lease has resolved     │
                └────────────────────────────────────────────────────────────┘
                                       │
                                       │ publishes EmailEvent (LEASE_RENEWAL_REMINDER)
                                       ▼
                ┌────────────────────────────────────────────────────────────┐
                │  EmailDispatcher  (existing, AFTER_COMMIT)                  │
                │  → email_outbox → outbound mailer                           │
                └────────────────────────────────────────────────────────────┘
                                       │
                                       ▼
            ┌──────────────────────────────────────┐
            │  Renter receives email with 3 CTAs:   │
            │  RENEW · MOVE_OUT · DISCUSS           │
            └──────────────────────────────────────┘
                                       │  click → /renewal-intent?token=<jwt>
                                       ▼
            ┌──────────────────────────────────────┐
            │  Renter portal confirm page           │
            │  → POST /api/v1/public/renewal-intent │
            └──────────────────────────────────────┘
                                       │
                       ┌───────────────┼───────────────┐
                       ▼               ▼               ▼
              update opportunity   log              publish
              (stage, intent)      lease_           RenewalIntentCapturedEvent
                                   interactions     (AFTER_COMMIT → PM notify)
                                   (SYSTEM_INTENT)
```

## 6. Data model

Three new tables, all extending `BaseTenantEntity` (tenant-scoped via `tenant_id`, filter applied to load-by-key via PR #50 fix).

### 6.1 `renewal_opportunities`

```
id              UUID PK
tenant_id       UUID NOT NULL
lease_id        UUID FK → leases NOT NULL
stage           VARCHAR(30) NOT NULL  -- OPEN | INTENT_CAPTURED | CLOSED_WON | CLOSED_LOST
intent          VARCHAR(20) NULL      -- RENEW | MOVE_OUT | DISCUSS
intent_captured_at TIMESTAMPTZ NULL
opened_at       TIMESTAMPTZ NOT NULL
closed_at       TIMESTAMPTZ NULL
outcome         VARCHAR(30) NULL      -- RENEWED | MOVED_OUT | EXPIRED_NO_RESPONSE
created_at      TIMESTAMPTZ NOT NULL
updated_at      TIMESTAMPTZ NOT NULL
```

Partial unique index: at most ONE non-closed opportunity per lease:
```
CREATE UNIQUE INDEX uniq_open_opp_per_lease
  ON renewal_opportunities (lease_id)
  WHERE stage IN ('OPEN', 'INTENT_CAPTURED');
```

Other indexes:
- `(tenant_id, stage)` — Phase-2 sweep
- `(lease_id)` — joins

### 6.2 `lease_reminders` (the ledger)

```
id              UUID PK
tenant_id       UUID NOT NULL
opportunity_id  UUID FK → renewal_opportunities NOT NULL
slot            SMALLINT NOT NULL     -- 90 | 60 | 30
channel         VARCHAR(20) NOT NULL  -- EMAIL | IN_APP
status          VARCHAR(20) NOT NULL  -- PENDING | SENT | FAILED | SKIPPED
sent_at         TIMESTAMPTZ NULL
attempt_count   INT NOT NULL DEFAULT 0
last_error      TEXT NULL
created_at      TIMESTAMPTZ NOT NULL
updated_at      TIMESTAMPTZ NOT NULL

UNIQUE (opportunity_id, slot, channel)
```

The UNIQUE is the idempotency anchor. Scheduler uses `INSERT ... ON CONFLICT DO NOTHING` to claim, then UPDATEs `status` after the send attempt.

### 6.3 `lease_interactions` (CRM log)

```
id              UUID PK
tenant_id       UUID NOT NULL
opportunity_id  UUID FK → renewal_opportunities NULL   -- nullable: out-of-cycle entries attach to lease only
lease_id        UUID FK → leases NOT NULL              -- denormalized for fast lease-detail queries
type            VARCHAR(20) NOT NULL  -- CALL | SMS | EMAIL | WHATSAPP | MEETING | SYSTEM_INTENT | NOTE
direction       VARCHAR(10) NOT NULL  -- INBOUND | OUTBOUND | INTERNAL
occurred_at     TIMESTAMPTZ NOT NULL
summary         TEXT NOT NULL
outcome         VARCHAR(20) NULL      -- POSITIVE | NEUTRAL | NEGATIVE | NO_RESPONSE
follow_up_date  DATE NULL
created_by      UUID FK → users NOT NULL
deleted_at      TIMESTAMPTZ NULL
created_at      TIMESTAMPTZ NOT NULL
```

Indexes:
- `(lease_id, occurred_at DESC)` — lease-detail timeline
- `(tenant_id, follow_up_date)` — follow-up widget
- `(opportunity_id)` — opportunity rollups

Liquibase changeset: `58-lease-renewal.yaml`.

## 7. Scheduler design

**New service**: `LeaseRenewalScheduler` (separate from `NotificationScheduler` for single responsibility). Schedule: `@Scheduled(cron = "0 0 8 * * *")`. Loops over tenants like `PenaltyService` does (sets `TenantContextHolder` per iteration, processes that tenant's leases, clears).

### 7.1 Phase 1 — Open opportunities

JPQL on `LeaseRepository`:
```
@Query("""
    SELECT l FROM Lease l
    WHERE l.status = 'ACTIVE'
      AND l.endDate <= :cutoff
      AND NOT EXISTS (
        SELECT 1 FROM RenewalOpportunity o
        WHERE o.lease.id = l.id AND o.stage IN ('OPEN', 'INTENT_CAPTURED')
      )
""")
List<Lease> findActiveLeasesEnteringRenewalWindow(@Param("cutoff") LocalDate cutoff);
```

Called with `cutoff = today + 90`. For each result, insert `renewal_opportunities` row with `stage=OPEN, opened_at=now()`.

`NOTICE_GIVEN` leases are excluded — that status already signals a non-renewal decision.

### 7.2 Phase 2 — Fire reminders

For each opportunity in `(OPEN, INTENT_CAPTURED)` for this tenant:

1. Compute `days_remaining = lease.endDate − today`.
2. If `opportunity.intent ∈ {RENEW, MOVE_OUT}`: bulk-`SKIPPED` all not-yet-claimed slots with `last_error='intent captured: <intent>'`. Skip rest of this opportunity's processing.
3. For each `(slot ∈ {90, 60, 30}, channel ∈ {EMAIL, IN_APP})`:
   - `INSERT INTO lease_reminders (opportunity_id, slot, channel, status, attempt_count) VALUES (..., 'PENDING', 0) ON CONFLICT DO NOTHING`.
   - Reload. If `status != PENDING` (already claimed): continue.
   - Determine slot window:
     - slot 90 → fires when `60 < days_remaining ≤ 90`
     - slot 60 → fires when `30 < days_remaining ≤ 60`
     - slot 30 → fires when `0 < days_remaining ≤ 30`
   - If `days_remaining` is below the slot's window (e.g., slot=90, days_remaining=55): mark `status=SKIPPED, last_error='window passed at open'`. Log a `lease_interactions` row of type `NOTE`, direction `INTERNAL`: "90-day reminder window missed at opportunity open".
   - Else if `days_remaining` is in window: publish `EmailEvent(LEASE_RENEWAL_REMINDER, ...)` for EMAIL, or write notification row for IN_APP. Mark `status=SENT, sent_at=now()`.
   - Else (`days_remaining` above slot's window): leave as `PENDING`; future runs will fire when the window arrives.
   - On exception: bump `attempt_count`; if `< 3`, leave PENDING for retry; if `= 3`, mark `FAILED` with `last_error=<message>`.

### 7.3 Phase 3 — Close stale opportunities

Sweep:
```sql
SELECT o FROM RenewalOpportunity o
WHERE o.stage IN ('OPEN', 'INTENT_CAPTURED')
  AND o.lease.status IN ('EXPIRED', 'TERMINATED', 'CLOSED', 'NOTICE_GIVEN')
```

Outcome mapping:
- `EXPIRED` + `intent IS NULL` → `outcome=EXPIRED_NO_RESPONSE, stage=CLOSED_LOST`
- `EXPIRED` + `intent=MOVE_OUT` → `outcome=MOVED_OUT, stage=CLOSED_LOST`
- `TERMINATED` or `NOTICE_GIVEN` → `outcome=MOVED_OUT, stage=CLOSED_LOST`
- A separate manual `POST /api/v1/leases/{id}/renewal/mark-renewed` endpoint (PM action) sets `outcome=RENEWED, stage=CLOSED_WON`. See §9.1.

`RENEWED` is always set manually by the PM in MVP — auto-detection from lease-to-lease linkage is a post-MVP follow-up (§17). If the PM extends `lease.endDate` past the 90-day window without explicitly marking renewed, Phase 2 stops firing reminders (`last_error='endDate extended'`) but the opportunity stays OPEN until the PM closes it.

### 7.4 Per-tenant + per-lease transactional isolation

- Phase 1: one tx per tenant.
- Phase 2: one tx per opportunity (so a single lease's send failure doesn't abort the rest of the tenant's run).
- Phase 3: one tx per opportunity.

`TenantContextHolder.setTenantId()` at the start of each tenant batch, `clear()` in `finally`. Mirrors `PenaltyService` and is now belt-and-suspendered by the `AsyncConfig` `TaskDecorator` (PR #50, commit `ada9ddf`).

## 8. Email + magic-link + intent capture

### 8.1 New `EmailEventType`: `LEASE_RENEWAL_REMINDER`

Payload (new record `LeaseRenewalReminderPayload` under `core.email.event.payload`):
```java
record LeaseRenewalReminderPayload(
    UUID opportunityId,
    UUID leaseId,
    UUID renterUserId,
    String leaseEndDateIso,
    int slot,
    String renewToken,
    String moveOutToken,
    String discussToken,
    String portalBaseUrl
) implements EmailPayload {}
```

Template: `lease-renewal-reminder.html` (EN + AR variants). Subject: `Your lease ends in {slot} days`.

### 8.2 Token format

HMAC-signed JWT (HS256):
```
{ "oid": "<opportunityId>", "int": "RENEW|MOVE_OUT|DISCUSS", "exp": "<lease.endDate + 7 days, epoch>" }
```

- Server secret: env var `app.renewal.token-secret` (distinct from JWT auth secret).
- Rotation: support current + previous keys, verify against both, sign with current.
- Replayable within TTL — renter can change their mind.

### 8.3 Public endpoint

`POST /api/v1/public/renewal-intent`, controller `PublicRenewalController`. Skipped by `ApiSecurityFilter` via `/public/**` path exclusion (added to filter's skip list).

Request: `{ "token": "<jwt>" }`. Flow:
1. HMAC verify + expiry check. Invalid → 400; expired → 410.
2. Decode token. Load opportunity by id via `RenewalOpportunityRepository.findByIdAcrossTenants` (a non-filtered JPQL lookup — the only place we deliberately bypass tenant scope, since we don't yet have one).
3. If opportunity not found → 404 (covers cross-tenant forgery and bogus ids).
4. If `stage NOT IN (OPEN, INTENT_CAPTURED)` → 409.
5. `TenantContextHolder.setTenantId(opportunity.tenantId)` for the rest of the request.
6. Single `@Transactional`:
   - UPDATE opportunity: `stage=INTENT_CAPTURED, intent=<intent>, intent_captured_at=now()`.
   - INSERT `lease_interactions` row: `type=SYSTEM_INTENT, direction=INBOUND, summary='Renter selected: <intent>', occurred_at=now(), created_by=opportunity.lease.renter.userId`.
   - If `intent ∈ {RENEW, MOVE_OUT}`: bulk-`SKIPPED` not-yet-claimed reminder slots.
7. Publish `RenewalIntentCapturedEvent` (AFTER_COMMIT listener notifies the PM via in-app + email).
8. Return `{ intent, leaseId, redirectTo: "/dashboard/renter-portal/renewals" }`.

### 8.4 Two-click flow (prefetch safety)

Email CTA is a GET link to `/dashboard/renter-portal/renewal-intent?token=...`. That page renders a small confirm card ("Confirm: I want to renew") and the user clicks Confirm → POST to the public endpoint. This avoids mail-client link-prefetch accidentally firing intents.

For unauthenticated renters, the confirm card renders without portal chrome; on submit, the response includes a `redirectTo` URL the page navigates to (renter then logs in if needed; their portal banner shows the captured intent).

### 8.5 PM notification on intent capture

`RenewalIntentCapturedEvent` listener fires:
- In-app: `notificationService.notify(tenantId, recipientUserId, "RENEWAL_INTENT", "Renter responded to renewal reminder", "<renter name> selected <intent> for lease <unit number>", "LEASE", leaseId)`.
- Recipient: tenant admins (resolved via `RecipientResolver`'s existing fallback path). Future: assigned PM on lease — a follow-up.
- Email side handled by the legacy `mapLegacyType("RENEWAL_INTENT")` mapping; we add a `RENEWAL_INTENT_CAPTURED` `EmailEventType` + template + mapping entry.

## 9. CRM interaction log (PM side)

### 9.1 Backend endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/leases/{leaseId}/interactions` | TENANT_ADMIN, PROPERTY_MANAGER | Paginated list (default 20), sorted `occurred_at DESC`. Filters: `type`, `outcome`, `followUpBefore`. |
| `POST` | `/api/v1/leases/{leaseId}/interactions` | TENANT_ADMIN, PROPERTY_MANAGER | Create. Validates `type`, `direction`, non-empty `summary`, `occurred_at ≤ now`, `follow_up_date ≥ today`. Auto-attaches `opportunity_id` to current open opportunity if one exists. |
| `PATCH` | `/api/v1/leases/{leaseId}/interactions/{id}` | TENANT_ADMIN, PROPERTY_MANAGER | Edit `summary`, `outcome`, `follow_up_date`. SYSTEM_INTENT rows return 422. |
| `DELETE` | `/api/v1/leases/{leaseId}/interactions/{id}` | TENANT_ADMIN only | Soft-delete (`deleted_at = now()`). SYSTEM_INTENT rows return 422. |
| `GET` | `/api/v1/renewals/follow-ups?date=<date>` | TENANT_ADMIN, PROPERTY_MANAGER | All interactions with `follow_up_date <= date AND deleted_at IS NULL AND outcome NOT IN ('POSITIVE')`, scoped to caller's tenant. |
| `POST` | `/api/v1/leases/{leaseId}/renewal/mark-renewed` | TENANT_ADMIN, PROPERTY_MANAGER | Closes the lease's current open opportunity: `stage=CLOSED_WON, outcome=RENEWED, closed_at=now()`. Optional body `{ "note": "..." }` writes a `NOTE`-type interaction. 404 if no open opportunity. |

### 9.2 PM lease-detail UI

A new "Interactions" panel on `web/src/app/[locale]/dashboard/leases/[id]/page.tsx`, below the Payment Schedule Timeline. Hidden when `lease.status === "DRAFT"`.

Header shows the current open opportunity summary: `stage`, `intent`, days remaining. Body is a timeline of interactions with type/direction/author/summary/outcome/follow-up. SYSTEM_INTENT rows render as read-only and visually muted. Each editable row has a pencil + soft-delete icon.

"Log interaction" button opens a modal (mirroring `MarkChequeFailedDialog`'s a11y pattern: `role="dialog"`, `aria-modal`, Escape, focus trap). Fields: type, direction, occurred_at (defaults to now), summary (required), outcome, follow_up_date.

Lease-detail header gets a badge `⏰ {N} follow-ups due` when any interaction has `follow_up_date ≤ today AND outcome != POSITIVE`. Clicking scrolls to the panel.

### 9.3 Dashboard follow-ups widget

PM home page `web/src/app/[locale]/dashboard/page.tsx` gets a one-card widget: "Follow-ups due today: {N}" + first 5 entries with lease address + summary. Each row links to the lease detail Interactions panel.

## 10. Renter dashboard

### 10.1 Renter portal banner

Top of `/dashboard/renter-portal/page.tsx`: if the renter has ≥1 lease with an open/intent-captured opportunity, show a banner:

> ⏰ One of your leases is up for renewal in {N} days. We've sent reminders. Let us know your plan.
> [ Tell us your plan → ]  (CTA links to `/dashboard/renter-portal/renewals`)

### 10.2 New renewals page

`web/src/app/[locale]/dashboard/renter-portal/renewals/page.tsx`.

Single scrollable view, bucketed:
- "Within 30 days" — `daysRemaining ≤ 30`
- "Within 60 days" — `30 < daysRemaining ≤ 60`
- "Within 90 days" — `60 < daysRemaining ≤ 90`
- "Beyond 90 days" — `daysRemaining > 90`

Each lease card shows:
- Address, end date, days remaining
- Current status:
  - No intent captured → 3 buttons: `I want to renew`, `I plan to move out`, `Discuss`
  - Intent captured → confirmation banner + "Change my mind:" sub-buttons for the other two intents
- Reminder ledger summary: "Reminders sent: 90-day (Mar 14), 60-day (Apr 13)"

Empty bucket sections collapse to "(no leases in this window)". Zero-leases renter sees an empty-state page.

### 10.3 Renter-scoped endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/me/renewals` | RENTER | Caller's active leases + current opportunity (if any) + reminder ledger. |
| `POST` | `/api/v1/me/renewals/{opportunityId}/intent` | RENTER | Set/change intent from the portal. Body: `{ intent }`. Authorization: `opportunity.lease.renter.userId == currentUserId`. |

### 10.4 Magic-link landing

Email CTA URL: `/dashboard/renter-portal/renewal-intent?token=<jwt>`. Confirm card renders without portal chrome for unauthenticated users; submits to the public endpoint; on success, redirects to `/dashboard/renter-portal/renewals` (after login if not already authenticated).

## 11. i18n

New namespaces in `web/messages/en.json` + `web/messages/ar.json`:

- `interactions`: `panelTitle`, `logInteraction`, `noInteractions`, `type.CALL`/`SMS`/`EMAIL`/`WHATSAPP`/`MEETING`/`SYSTEM_INTENT`/`NOTE`, `direction.INBOUND`/`OUTBOUND`/`INTERNAL`, `outcome.POSITIVE`/`NEUTRAL`/`NEGATIVE`/`NO_RESPONSE`, `followUpDueBadge`, `cannotEditSystemEntry`, `confirmDelete`.
- `renewals`: bucket headers (`within30`, `within60`, `within90`, `beyond90`), intent button labels, `statusNoResponseYet`, `statusYouSelected`, `changeYourMind`, `remindersSentLabel`, `beyond90Hint`, `confirmIntent`.
- `followUpsWidget`: title, empty state, link label.

## 12. Error handling and edge cases

| Case | Behavior |
|---|---|
| Lease `endDate` extended past the 90-day window (any amount) | Phase 2 bulk-`SKIPS` remaining slots with `last_error='endDate extended'`. Opportunity stays OPEN until PM manually marks it renewed (or the lease status transitions). |
| Lease → `TERMINATED` mid-cycle | Phase 3 closes as `MOVED_OUT, CLOSED_LOST`. Remaining slots `SKIPPED`. |
| Lease → `NOTICE_GIVEN` mid-cycle | Same as TERMINATED. |
| Renter user deleted mid-cycle | Email send fails. Reminder marked `FAILED` after 3 retries. Opportunity stays open. PM sees failure in ledger. |
| Expired magic-link token | 410 Gone, body `{ "error": "TOKEN_EXPIRED" }`. Portal page surfaces message + login CTA. |
| Token for closed opportunity | 409 Conflict. Portal shows "This renewal has already been resolved." |
| Renter changes intent (RENEW → DISCUSS) | New `SYSTEM_INTENT` interaction logged; previously SKIPPED slots stay SKIPPED (we don't unskip). |
| Concurrent scheduler runs (rolling deploy) | UNIQUE `(opportunity_id, slot, channel)` serializes. ON CONFLICT DO NOTHING. |
| Slow Postgres → individual lease times out | Per-iteration tx; one slow lease doesn't block the run. |
| Tampered token / cross-tenant forgery | HMAC fails → 400. Even on signature match with forged ids, tenant filter prevents data access → 404. |
| Email outbox publish throws | Per-iteration tx rolls back; reminder stays PENDING; next run retries. |

## 13. Multi-tenancy

- All new entities extend `BaseTenantEntity` (PR #50's `applyToLoadByKey=true` on `@FilterDef` means `findById` respects the tenant filter).
- Public renewal endpoint is the only path that establishes tenant context *from data* (`opportunity.tenantId`). The only repo method that bypasses the filter is `RenewalOpportunityRepository.findByIdAcrossTenants` — a dedicated, narrowly named, JPQL-with-no-filter method. After that one lookup, tenant context is set and all subsequent queries are filtered.

## 14. Testing strategy

### 14.1 Backend integration tests

`LeaseRenewalSchedulerIntegrationTest`:
1. Lease at 91 days, no opportunity (not eligible).
2. Lease at 90 days, opportunity opens, 90-day reminder fires.
3. Lease activated at 80 days remaining → opportunity opens, 90-day `SKIPPED`, 60-day fires when window hits.
4. Idempotency: run scheduler twice on same day → second run no-op.
5. Intent=RENEW captured at day 70 → 60 + 30 slots end up `SKIPPED` with `last_error='intent captured: RENEW'`.
6. Intent=DISCUSS captured at day 70 → 60 + 30 slots still `SENT` in their windows.
7. Lease → TERMINATED at day 50 → opportunity `CLOSED_LOST, outcome=MOVED_OUT`; remaining slots `SKIPPED`.
8. Cross-tenant isolation: opportunity in tenant A invisible to tenant B's `TENANT_ADMIN`.
9. Failed send retry: mock outbox throw on attempt 1, retry succeeds on attempt 2, `attempt_count=2, status=SENT`.
10. Permanent failure: 3 failed attempts → `status=FAILED`, no further retries.

`PublicRenewalControllerTest`:
1. Valid token → intent recorded, interaction logged, 200.
2. Expired token → 410.
3. Tampered signature → 400.
4. Token for closed opportunity → 409.
5. Replay with different intent → intent updated + second interaction row.

`LeaseInteractionControllerTest`:
1. PM creates entry → 201.
2. RENTER role → 403 on create.
3. SYSTEM_INTENT edit → 422.
4. PATCH across tenants → 404 (filter).

`PendingFollowUpsControllerTest`:
1. Returns interactions with `follow_up_date <= today` and `outcome != POSITIVE`.
2. Excludes soft-deleted.
3. Tenant-scoped.

### 14.2 Frontend tests

- `RenewalsPage.test.tsx`: renders buckets, intent buttons for open opportunities, status banner for captured intents.
- `RenewalConfirmPage.test.tsx`: successful commit, expired-token error path.
- `LeaseInteractionsPanel.test.tsx`: log entry modal opens, submit creates entry, refresh on success, SYSTEM_INTENT rows read-only.
- `FollowUpsWidget.test.tsx`: empty state, 5-row preview, link to lease.

## 15. Observability

- Micrometer counters:
  - `lease_renewal_reminders_sent_total{slot, channel, status}`
  - `lease_renewal_intent_captured_total{intent}`
  - `lease_renewal_opportunities_opened_total`
  - `lease_renewal_opportunities_closed_total{outcome}`
- Logging: scheduler INFO per phase summary; WARN on send failure with `attempt_count`; ERROR on unhandled exceptions.

## 16. Rollout plan

1. Migration `58-lease-renewal.yaml`: 3 tables + indexes.
2. Code deploys with scheduler **disabled by default** via feature flag `app.renewal.scheduler.enabled` (default `false`). Read at startup.
3. Email template ships dormant — no renewal emails until the flag flips.
4. Staging smoke: synthetic ACTIVE lease at `endDate=today+89`. Verify end-to-end including public endpoint and renter portal.
5. Flip flag in prod. Watch metrics 48h.
6. Optional ops endpoint `POST /api/v1/admin/renewals/run-now` (SUPER_ADMIN) for manual trigger during troubleshooting.

## 17. Open follow-ups (post-MVP)

- SMS / WhatsApp channels.
- Auto-detect renewal via lease-to-lease linkage (add `previous_lease_id` to `Lease`).
- PM portfolio renewal funnel dashboard with stage breakdowns + cohort retention.
- Bulk follow-up workflows.
- Joint tenancy (multiple renters per lease).
- Assigned-PM-per-lease for sharper PM notification routing.
