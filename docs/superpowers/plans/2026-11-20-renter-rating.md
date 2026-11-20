# Renter Rating Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A premium, staff-only renter rating: a 0–100 score and A/B/C/D/NEW grade per renter, explained by dated events. It is shown on the renewals queue, lease page and renter profile, and has a portfolio list page.

**Architecture:** A pure `RenterRatingEngine` scores one `RenterHistory`. `RenterHistoryLoader` bulk-loads histories for up to 500 renters in about 7 queries. The details endpoint scores live. A nightly `RenterRatingJob` stores results (written only when the fingerprint changes) plus monthly snapshots, for the list page. Everything is gated by `TenantFeature.RENTER_RATING`.

**Tech Stack:** Java 21, Spring Boot 4, JPA, Liquibase, PostgreSQL 16, Testcontainers. Next.js 16, TypeScript, next-intl, vitest.

**Spec:** `docs/superpowers/specs/2026-11-20-renter-rating-design.md`

## Global Constraints

- Feature flag: `TenantFeature.RENTER_RATING(false)`, label "Renter Rating (premium)".
- Viewing roles: `SUPER_ADMIN`, `TENANT_ADMIN`, `PROPERTY_MANAGER`, `ACCOUNTANT`. Adjustments: `TENANT_ADMIN` only (plus `SUPER_ADMIN` acting in an org, as elsewhere). All other roles are denied. No renter-facing endpoint, page or mobile screen.
- Feature off for the caller's org: every rating endpoint throws `NotFoundException("Renter rating feature is disabled")`. This is the `LISTINGS` precedent (404); the spec's "403" is satisfied in spirit (hidden), and this is noted as an executor-visible call.
- Every query scopes by `tenant_id`. A cross-org leak is P0.
- Liquibase changeset `190-renter-rating.yaml`, append-only.
- Policy numbers (all in `RenterRatingPolicy`):
  - base 70; on-time grace 7 days;
  - credits: on-time cheque +1 (cap 20), renewal +5 (cap 10), early intent +3 (cap 6, at least 30 days before `endDate`);
  - deductions: bounce −15, late clear −3, `LATE_PAYMENT` −8, other misconduct −10, write-off −30, long hold (more than 14 days) −10, `EXPIRED_NO_RESPONSE` −5, lease-linked `NO_SHOW` −5;
  - weights: 1.0 up to 12 months, 0.5 up to 24 months, excluded beyond;
  - grades: A at 85+, B at 70–84, C at 50–69, D at 0–49;
  - NEW = (earliest started lease under 6 months ago OR fewer than 3 cheques dated in the window) AND no deductions AND no adjustments;
  - adjustment range −30..+30, reason 1–500 characters.
- Started lease = status not in `DRAFT`, `PENDING_SIGNATURE`.
- API base `/api/v1`. Conventional commits, each ending `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Gradle runs only in WSL: `wsl -d Ubuntu-24.04 -- bash -lc 'cd /mnt/c/rentaxis-wt/tenant-rating/backend && JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true ./gradlew test --tests "<pattern>"'`. Web: `cd web && npx vitest run <path>`.
- English and Arabic strings for every UI string, RTL-safe (use logical `ms-`/`me-` classes).

## Review Focus

1. A renter with leases in two orgs: each org's rating sees only its own rows (Task 3 test `loaderNeverReturnsAnotherOrgsRows`).
2. An event dated exactly 365 or 730 days ago: weight 1.0 at ≤365 days, 0.5 at ≤730, excluded at 731 (Task 2 tests `weightBoundaryAt12Months`, `weightBoundaryAt24Months`).
3. A bounced cheque later replaced (status `REPLACED`, `bouncedAt` set) still counts. A `RETURNED` cheque with no bounce date does not (Task 2 `replacedBounceStillCounts`, `returnedChequeIsNotABounce`).
4. A renter with no started leases (only a DRAFT) is not rated and does not appear in the list (Task 3 `draftOnlyRenterNotPaged`).
5. A renter on the list page whose rating has not been computed yet: the badge still works live; the list shows them only after the job runs. The run-on-enable fills the list (Task 4 `enablingFeatureComputesOrg`).

---

### Task 1: Feature flag, schema, entities

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/TenantFeature.java` (add `RENTER_RATING(false)` with comment `// premium — off by default`)
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/TenantFeatureService.java` (label in `toLabel`; publish `TenantFeatureToggledEvent(tenantId, feature, enabled)` via `ApplicationEventPublisher` after `cache.invalidate`)
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/TenantFeatureToggledEvent.java` (record)
- Create: `backend/src/main/resources/db/changelog/changesets/190-renter-rating.yaml`; register in `db.changelog-master.yaml`
- Create: entities `RenterRating`, `RenterRatingSnapshot`, `RenterRatingAdjustment` in `domain/entity`, all extending `BaseTenantEntity`; enum `RatingGrade { A, B, C, D, NEW }` in `domain/entity/enums`
- Create: repositories `RenterRatingRepository`, `RenterRatingSnapshotRepository`, `RenterRatingAdjustmentRepository` in `domain/repository`
- Test: `backend/src/test/java/com/datagami/rentaxis/domain/RenterRatingSchemaIT.java`

**Interfaces — Produces:**
- Tables (all with `id uuid pk`, `tenant_id uuid not null`, an index on `tenant_id`):
  - `renter_ratings(renter_id uuid not null, score int not null, grade varchar(8) not null, deductions_count int not null, events_json text not null, fingerprint varchar(64) not null, computed_at timestamptz not null)`, unique `(tenant_id, renter_id)`.
  - `renter_rating_snapshots(renter_id, month date, score int, grade varchar(8))`, unique `(tenant_id, renter_id, month)`.
  - `renter_rating_adjustments(renter_id, points int, reason varchar(500), created_by uuid, created_at timestamptz, removed_by uuid null, removed_at timestamptz null)`.
  - FKs: `renter_id → renters(id)`.
- `RenterRatingRepository.findByTenantIdAndRenterId(UUID, UUID): Optional<RenterRating>`; `findByTenantIdAndRenterIdIn(UUID, Collection<UUID>): List<RenterRating>`.
- `RenterRatingSnapshotRepository.findTop12ByTenantIdAndRenterIdOrderByMonthDesc(UUID, UUID)`; `findByTenantIdAndRenterIdAndMonth(...)`.
- `RenterRatingAdjustmentRepository.findByTenantIdAndRenterIdInAndRemovedAtIsNull(UUID, Collection<UUID>)`; `findByIdAndTenantIdAndRenterId(...)`.

- [ ] **Step 1: Write failing test** `RenterRatingSchemaIT` (extends `AbstractPostgresIT`, `@SpringBootTest`):
  - `savesAndReadsRating`: save a `RenterRating` for a fixture renter, then read it back by `(tenantId, renterId)`.
  - `uniquePerRenter`: a second row for the same pair throws `DataIntegrityViolationException`.
  - `featureDefaultsOff`: `assertThat(TenantFeature.RENTER_RATING.isDefaultEnabled()).isFalse()`, and `tenantFeatureService.isEnabled(org, RENTER_RATING)` is false.
- [ ] **Step 2: Run, expect a compile failure.**
- [ ] **Step 3: Implement the enum value, label, event, changeset, entities and repositories.**
- [ ] **Step 4: Run** `--tests "*RenterRatingSchemaIT"` → PASS. Also run `--tests "*TenantFeature*"` → PASS. Superadmin and web tests listing features may enumerate values; fix any snapshot.
- [ ] **Step 5: Commit** `feat(rating): RENTER_RATING flag and rating tables`

### Task 2: Scoring engine (pure)

**Files:**
- Create in `backend/src/main/java/com/datagami/rentaxis/core/service/rating/`:
  - `RenterRatingPolicy.java`: constants from Global Constraints, as `public static final`.
  - `RenterHistory.java`: record with nested row records (below).
  - `RatingEventType.java`
  - `RatingEvent.java`
  - `RenterRatingResult.java`
  - `RenterRatingEngine.java`
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/rating/RenterRatingEngineTest.java` (plain JUnit, no Spring)

**Interfaces — Produces:**
```java
record RenterHistory(UUID renterId,
    List<LeaseRow> leases, List<ChequeRow> cheques, List<PenaltyRow> penalties,
    List<WriteOffRow> writeOffs, List<LeaseEventRow> leaseEvents,
    List<OpportunityRow> opportunities, List<MeetingRow> meetings, List<AdjustmentRow> adjustments) {
  record LeaseRow(UUID id, LeaseStatus status, LocalDate startDate, LocalDate endDate, UUID renewedFromLeaseId) {}
  record ChequeRow(UUID id, UUID leaseId, ChequeStatus status, LocalDate chequeDate, LocalDate clearedAt,
                   LocalDate bouncedAt, LocalDate importedBouncedOn, ChequeFailureReason failureReason) {}
  record PenaltyRow(UUID id, UUID leaseId, PenaltyReason reason, PenaltyAssessmentStatus status,
                    LocalDate incidentDate, UUID sourceChequeId /* null unless sourceId is a cheque */) {}
  record WriteOffRow(UUID id, UUID leaseId, String status, LocalDate writeOffDate) {}
  record LeaseEventRow(UUID leaseId, String previousState, String newState, Instant createdAt) {}
  record OpportunityRow(UUID id, UUID leaseId, RenewalIntent intent, Instant intentCapturedAt,
                        RenewalOutcome outcome, Instant closedAt) {}
  record MeetingRow(UUID id, UUID leaseId, MeetingStatus status, Instant slotStart) {}
  record AdjustmentRow(UUID id, int points, String reason, UUID createdBy, Instant createdAt) {}
}
enum RatingEventType { ON_TIME_CHEQUE, RENEWAL, EARLY_INTENT, BOUNCE, LATE_CLEAR, LATE_PAYMENT_PENALTY,
  MISCONDUCT_PENALTY, WRITE_OFF, LONG_HOLD, NO_RESPONSE, MEETING_NO_SHOW, ADJUSTMENT }
record RatingEvent(RatingEventType type, LocalDate date, int basePoints, double weight, double appliedPoints,
                   String sourceType /* CHEQUE|PENALTY|WRITE_OFF|LEASE|OPPORTUNITY|MEETING|ADJUSTMENT */,
                   UUID sourceId, UUID leaseId, String note /* adjustment reason, else null */) {}
record RenterRatingResult(UUID renterId, int score, RatingGrade grade, int deductionsCount,
                          List<RatingEvent> events /* newest first */) {
  String fingerprint(); // SHA-256 hex of score|grade|each event's type,date,appliedPoints,sourceId
}
final class RenterRatingEngine { static RenterRatingResult score(RenterHistory h, LocalDate today); }
```
Use the existing enum types; check their exact names in `domain/entity/enums`. Instants convert to dates with `ZoneId.of("Asia/Dubai")`. Use the project's business zone constant if one exists.

Algorithm notes (not determined by the signature):
- Age in days = `today - date`. Weight 1.0 if ≤ 365, 0.5 if ≤ 730, otherwise drop the event.
- Caps apply per credit type on the weighted sum. When a cap trims a type, scale each event of that type proportionally, so the timeline still adds up to the score. Cap applies to positive credit types only.
- Score = `clamp(round(70 + Σ appliedPoints), 0, 100)`.
- Long hold: for each `LeaseEventRow` with `newState = "ON_HOLD"`, the end is the next event on the same lease with `previousState = "ON_HOLD"`, or today. If more than 14 days, add an event dated hold date + 15. At most one per lease.
- Only leases in started statuses count, and only cheques, penalties, opportunities and meetings whose `leaseId` belongs to a started lease (cheques with a null lease still count).

- [ ] **Step 1: Write the failing tests.** Use `TODAY = 2026-11-20` and a builder helper `history()`:
  - `emptyHistoryIsNewAt70`: score 70, grade NEW, no events.
  - `onTimeChequesCreditOneEach`: 12 cheques cleared on `chequeDate` (+0..+7 days), within the last 12 months, earliest lease 13 months ago → score 82, grade B, 12 `ON_TIME_CHEQUE` events.
  - `onTimeCreditCappedAt20`: 30 on-time cheques → score 90.
  - `workedExampleA`: 12 on-time cheques plus RENEW intent 40 days before `endDate` → 85, grade A.
  - `workedExampleBounce`: the same plus a bounce 8 months ago → 70, grade B. The BOUNCE event has `appliedPoints == -15`.
  - `replacedBounceStillCounts`: status `REPLACED`, `bouncedAt` set → BOUNCE −15.
  - `returnedChequeIsNotABounce`: status `RETURNED`, no bounce dates → no BOUNCE event.
  - `technicalReturnExcluded`: `bouncedAt` set, `failureReason = TECHNICAL_RETURN` → no BOUNCE.
  - `importedBounceCountsOnce`: both `bouncedAt` and `importedBouncedOn` set → exactly one BOUNCE, dated `bouncedAt`.
  - `lateClearMinus3`: cleared 8 days after `chequeDate` → LATE_CLEAR −3, no ON_TIME credit.
  - `weightBoundaryAt12Months`: bounce exactly 365 days ago → −15. At 366 days → −7.5.
  - `weightBoundaryAt24Months`: at 730 days → −7.5. At 731 days → no event.
  - `latePaymentApprovedAndWrittenOffCount`: APPROVED → −8. WRITTEN_OFF → −8. WAIVED, REVERSED, PROPOSED → none.
  - `damageCountsMisconduct`: DAMAGE APPROVED → MISCONDUCT_PENALTY −10.
  - `chargeReasonsIgnored`: each of SERVICE_RECHARGE, ADMIN_FEE, MAINTENANCE_RECHARGE, BOOKING_FEE, JOURNAL_VOUCHER, OTHER, USE_AND_OCCUPATION (APPROVED) → no event.
  - `chequeReturnPenaltyLinkedToChequeNotDoubleCounted`: CHEQUE_RETURN with `sourceChequeId` → none. Without it → −10.
  - `writeOffMinus30OnlyWhenWrittenOff`
  - `longHoldOver14Days`: ON_HOLD on day X, exit on X+20 → LONG_HOLD −10 dated X+15. Exit on X+14 → none. Still on hold, 30 days → −10.
  - `longHoldOncePerLease`: two holds on one lease → one event.
  - `noResponseMinus5`
  - `leaseLinkedNoShowMinus5AndUnlinkedIgnored`
  - `renewalCreditCappedAt10`: 3 renewal leases → +10.
  - `earlyIntentNeeds30Days`: captured 29 days before end → none. 30 days → +3.
  - `newRuleBroken`:
    - (a) earliest lease 5 months ago, 6 on-time cheques, no deductions → NEW;
    - (b) the same with one late clear → real grade;
    - (c) the same with a +5 adjustment → real grade;
    - (d) lease 13 months ago, 2 cheques → NEW.
  - `draftLeaseIgnored`: only a DRAFT lease with cheques → NEW, no events.
  - `adjustmentAppliesAndCountsAsDeductionWhenNegative`: −10 adjustment → score 60, `deductionsCount == 1`, event note equals the reason.
  - `clampAt0And100`
  - `fingerprintStableAndSensitive`: the same input gives the same fingerprint. A changed score gives a different one.
  - `eventsNewestFirst`
- [ ] **Step 2: Run** `--tests "*RenterRatingEngineTest"` → compile failure.
- [ ] **Step 3: Implement** the records, policy and engine.
- [ ] **Step 4: Run** → all PASS.
- [ ] **Step 5: Commit** `feat(rating): pure renter rating engine`

### Task 3: Bulk history loader

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/rating/RenterHistoryLoader.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/rating/RenterRatingQueries.java` (native or JPQL projections, one method per source)
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/rating/RenterHistoryLoaderIT.java`

**Interfaces:**
- Consumes: `RenterHistory` row records (Task 2).
- Produces:
  - `Map<UUID, RenterHistory> RenterHistoryLoader.load(UUID tenantId, Collection<UUID> renterIds, LocalDate since)`. `since = today - 730 days`. Every renter id gets an entry, empty if it has no rows. Leases load whole, not limited by date, so the NEW rule works.
  - `List<UUID> RenterHistoryLoader.ratedRenterIds(UUID tenantId, UUID afterId, int limit)`: renters with at least one started lease, ordered by id (keyset paging).
- Queries (each with `tenant_id = :tenantId`; each, except leases, also bounded by its event date ≥ `since`):
  - leases by `renter_id in`;
  - cheques by `renter_id in`;
  - penalties by `renter_id in` (`sourceChequeId` = `source_id` when a cheque with that id exists);
  - write-offs by `coalesce(w.renter_id, l.renter_id) in` via a join to the lease;
  - lease_events for the loaded lease ids;
  - renewal_opportunities for the lease ids;
  - meetings by `lease_id in` the lease ids;
  - active adjustments by `renter_id in`.
- Run with the tenant context set (`TenantContextHolder.setTenantId`). The explicit `tenant_id` predicate stays anyway.

- [ ] **Step 1: Failing tests** (`AbstractPostgresIT`, fixtures from `LeaseTestFixtures`/`TestIdentities`):
  - `loadsAllSourcesForRenter`: one renter, a posted lease, one bounced cheque, one approved LATE_PAYMENT penalty, one written-off write-off with null `renterId`, one ON_HOLD lease event, one no-show meeting. Every list is non-empty with the right ids.
  - `loaderNeverReturnsAnotherOrgsRows`: the same renter id queried under org B returns empty lists. A second org's rows for a different renter never appear.
  - `draftOnlyRenterNotPaged`: `ratedRenterIds` excludes a renter whose only lease is DRAFT.
  - `pagesByKeyset`: 3 rated renters, limit 2 → 2 ids, then 1 id.
  - `queryCountBounded`: enable Hibernate statistics or count via a `StatementInspector`; loading 50 renters runs ≤ 10 statements.
- [ ] **Step 2: Run** `--tests "*RenterHistoryLoaderIT"` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(rating): bulk renter history loader`

### Task 4: Rating service, nightly job, adjustments

**Files:**
- Create in `core/service/rating/`: `RenterRatingService.java`, `RenterRatingJob.java`
- Create DTOs in `backend/src/main/java/com/datagami/rentaxis/api/dto/rating/`: `RenterRatingDetailDTO`, `RatingEventDTO`, `RatingTrendPointDTO`, `RenterRatingRowDTO`, `AddAdjustmentRequest`
- Modify: `backend/src/main/resources/application.yml` (`app.renter-rating.job.enabled: true`)
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/rating/RenterRatingServiceIT.java`

**Interfaces:**
- Consumes: `RenterHistoryLoader` (Task 3), `RenterRatingEngine` (Task 2), repositories (Task 1), `TenantFeatureToggledEvent` (Task 1).
- Produces:
  - `RenterRatingDetailDTO RenterRatingService.live(UUID tenantId, UUID renterId, LocalDate today)`. Throws `NotFoundException` if the renter is not in the org. The DTO has `renterId, score, grade, deductionsCount, base (=70), events: List<RatingEventDTO>, trend: List<RatingTrendPointDTO>` (the last 12 snapshots ascending, plus today). Each event has an `adjustmentId` when `type == ADJUSTMENT`.
  - `int RenterRatingService.recomputeAndStore(UUID tenantId, Collection<UUID> renterIds, LocalDate today)`. Returns the number of rows written. It skips rows whose fingerprint is unchanged and deletes stored rows for ids that are no longer rated.
  - `Page<RenterRatingRowDTO> RenterRatingService.list(UUID tenantId, RatingGrade grade, UUID propertyId, String q, Pageable p)`. Row: `renterId, renterName, renterNameAr, score, grade, deductionsCount, currentLeaseId, unitLabel, propertyName, computedAt`. Sort keys `score`, `name`, `computedAt`. "Current lease" is the latest started lease.
  - `RatingEventDTO RenterRatingService.addAdjustment(UUID tenantId, UUID renterId, int points, String reason, UUID userId, LocalDate today)`. Validates −30..30, non-zero, reason trimmed 1–500, else `BadRequestException`. Recomputes and stores that renter.
  - `void RenterRatingService.removeAdjustment(UUID tenantId, UUID renterId, UUID adjustmentId, UUID userId, LocalDate today)`. Sets `removedAt`/`removedBy` and recomputes.
  - `RenterRatingJob.runDaily()`: `@Scheduled(cron = "0 0 2 * * *")`, gated by the flag. `runNow(LocalDate)` loops orgs where `RENTER_RATING` is on. `runNowForTenant(UUID, LocalDate)` pages 500 ids, with one `TransactionTemplate` per batch. On `today.getDayOfMonth() == 1` it upserts a snapshot for every rated renter, month = `today.withDayOfMonth(1)`.
  - Listener: `@EventListener` on `TenantFeatureToggledEvent` where `feature == RENTER_RATING && enabled` submits `runNowForTenant` to an async executor. Use the existing `@EnableAsync` and executor if present; otherwise a `ThreadPoolTaskExecutor` bean `renterRatingExecutor` (1 thread).
  - Pattern: copy `LeaseRenewalScheduler` (`TenantContextHolder` set/clear per org).

- [ ] **Step 1: Failing tests:**
  - `liveMatchesEngine`: a fixture renter with one bounce → `live().score == 55`, and the events include BOUNCE with `sourceId` = the cheque id.
  - `storeWritesOnceThenSkipsUnchanged`: the first `recomputeAndStore` returns 1, a second call with the same day returns 0.
  - `storeRewritesWhenAgeWeightChanges`: a bounce 365 days before `today`. The next day's run returns 1, and the score changes from 55 to 63 (70 − 7.5 = 62.5, rounded half-up).
  - `snapshotOnFirstOfMonth`: `runNowForTenant(org, 2026-12-01)` creates a snapshot row. Running again does not duplicate it.
  - `jobSkipsOrgsWithFeatureOff`
  - `enablingFeatureComputesOrg`: `tenantFeatureService.setEnabled(org, RENTER_RATING, true)`, then await (Awaitility, if on the classpath, or a poll loop ≤ 10 s) until `renter_ratings` has a row for the renter.
  - `adjustmentValidation`: points 0, 31 and −31, and a blank or 501-character reason → `BadRequestException`.
  - `adjustmentAddAndRemove`: +10 → score 80. Remove → score 70, and the row still exists with `removedAt` set.
  - `listFiltersByGradeAndSearch`: two renters graded A and D. `grade=D` returns one row, and `q` matches by name.
- [ ] **Step 2: Run** `--tests "*RenterRatingServiceIT"` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(rating): rating service, nightly job and adjustments`

### Task 5: REST endpoints and security

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/api/RenterRatingController.java`
- Test: `backend/src/test/java/com/datagami/rentaxis/api/RenterRatingControllerIT.java` (HTTP, `X-User-*` headers, same shape as `PenaltyAssessmentControllerIT`)

**Interfaces:**
- Consumes: `RenterRatingService`, `RenterRatingJob` (Task 4).
- Produces, all under `/api/v1`, tenant from `TenantContextHolder`, first line `requireFeature(tenantId)`:
  - `GET /renters/{id}/rating` → `RenterRatingDetailDTO`. Roles: `hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','PROPERTY_MANAGER','ACCOUNTANT')`.
  - `GET /renter-ratings?grade&propertyId&q&page&size&sort` → `Page<RenterRatingRowDTO>`. Same roles.
  - `GET /renter-ratings/export` → `text/csv`, filename `renter-ratings.csv`, same filters, all pages. Columns: Renter, Grade, Score, Deductions, Unit, Property, Computed at.
  - `POST /renters/{id}/rating/adjustments` with body `AddAdjustmentRequest(int points, String reason)` → 201 `RatingEventDTO`. Roles: `hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')`.
  - `DELETE /renters/{id}/rating/adjustments/{adjustmentId}` → 204. Same roles as POST.
  - `POST /admin/renter-ratings/recompute?tenantId=` → 202. Role: `hasRole('SUPER_ADMIN')`. Does not require the feature for its own org; it requires the feature on for `tenantId`.
- If PROPERTY_MANAGER is property-scoped elsewhere for renters (check `RenterController`), apply the same scoping to `GET /renters/{id}/rating` and filter the list the same way.

- [ ] **Step 1: Failing tests:**
  - `featureOffReturns404`: feature off, TENANT_ADMIN GET rating → 404.
  - `staffRolesCanRead`: TENANT_ADMIN, PROPERTY_MANAGER and ACCOUNTANT GET rating → 200, and the body has `grade`, `score` and `events`.
  - `nonStaffDenied`: RENTER, SECURITY_GUARD and TENANT_USER → 403.
  - `adjustAdminOnly`: PROPERTY_MANAGER POST adjustment → 403. TENANT_ADMIN → 201. DELETE → 204.
  - `crossOrgRenter404`: an org A admin requesting an org B renter's rating → 404.
  - `exportIsCsv`: Content-Type `text/csv` and a header row starting with `Renter,Grade,Score`.
- [ ] **Step 2: Run** `--tests "*RenterRatingControllerIT"` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS. Also run `--tests "*OsivOffRouteSweepIT" --tests "*RbacParity*"` (or any route sweep that enumerates controllers) → PASS, updating its expectations if it lists routes.
- [ ] **Step 5: Commit** `feat(rating): renter rating REST endpoints`

### Task 6: Web — API client, badge, details panel

**Files:**
- Create: `web/src/lib/api/renterRatings.ts`
- Create: `web/src/components/renters/rating/RatingBadge.tsx`, `RatingDetailsPanel.tsx`, `AdjustmentForm.tsx`, `useRenterRatingEnabled.ts`
- Modify: `web/src/lib/rbac.ts` (add `canViewRenterRatings: ['SUPER_ADMIN','TENANT_ADMIN','PROPERTY_MANAGER','ACCOUNTANT']`, `canAdjustRenterRatings: ['SUPER_ADMIN','TENANT_ADMIN']`)
- Modify: `web/messages/en.json`, `web/messages/ar.json` (new namespace `RenterRating`)
- Test: `web/src/components/renters/rating/__tests__/RatingBadge.test.tsx`, `RatingDetailsPanel.test.tsx`

**Interfaces:**
- Produces:
  - `renterRatingsApi.get(renterId): Promise<RenterRatingDetail>`
  - `renterRatingsApi.list(params: {grade?, propertyId?, q?, page?, size?, sort?})`
  - `renterRatingsApi.exportUrl(params): string`
  - `renterRatingsApi.addAdjustment(renterId, {points, reason})`
  - `renterRatingsApi.removeAdjustment(renterId, adjustmentId)`
  - These use `apiGet`/`apiSend`/`qs` from `@/lib/api/ledger`, like `badDebts.ts`. TS types mirror the Task 4 DTOs.
  - `useRenterRatingEnabled(): boolean`: true when `useTenantFeatures().features.RENTER_RATING` and `hasPermission(role, 'canViewRenterRatings')`.
  - `<RatingBadge renterId: string; compact?: boolean />`:
    - fetches the live rating;
    - renders the grade letter, score and label (`RenterRating.grade.A` = "Excellent", B "Good", C "Fair", D "Poor", NEW "New");
    - grade colours: A green, B teal, C amber, D red, NEW grey;
    - click opens `RatingDetailsPanel`;
    - renders `null` when `useRenterRatingEnabled()` is false (no fetch).
  - `<RatingDetailsPanel renterId open onClose />` (side sheet, using the existing dialog/sheet primitive in `components/ui`):
    - header: score, grade, and the line "Every renter starts at 70";
    - trend: a small inline SVG polyline of `trend`;
    - timeline rows: date, label (`RenterRating.event.<TYPE>`), points signed to one decimal place (e.g. "−7.5"), a "half weight" tag when `weight === 0.5`, the adjustment reason, and a link to the lease (`/dashboard/leases/{leaseId}`) when present;
    - `AdjustmentForm` (points number input −30..30, reason textarea, max 500) and per-adjustment "Remove", only when `canAdjustRenterRatings`;
    - refreshes after a save.

- [ ] **Step 1: Failing tests** (mock `renterRatingsApi`, `useTenantFeatures`, `useSession`, following existing component tests in `components/renters/__tests__`):
  - `rendersGradeAndScore`: grade A, score 91 → the text "A", "91" and "Excellent".
  - `hiddenWhenFlagOff`: renders nothing, and `get` is not called.
  - `hiddenForRenterRole`
  - `newGradeGrey`: NEW has the grey class.
  - `panelListsEventsWithHalfWeightTag`
  - `adjustFormOnlyForAdmin`: PROPERTY_MANAGER sees no form. TENANT_ADMIN sees it, and submitting calls `addAdjustment` with `{points: 5, reason: "Bank error"}`.
  - `adjustFormRejectsOutOfRange`: 31 shows a validation message and no call.
- [ ] **Step 2: Run** `cd web && npx vitest run src/components/renters/rating` → FAIL.
- [ ] **Step 3: Implement** (with en and ar strings).
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(rating): web rating badge and details panel`

### Task 7: Web — placements and Renter ratings page

**Files:**
- Modify: `web/src/components/leases/renewals/RenewalQueue.tsx` (Rating column, `<RatingBadge compact renterId=…/>`, shown only when enabled)
- Modify: `web/src/app/[locale]/dashboard/leases/[id]/page.tsx` (badge next to the renter name)
- Modify: `web/src/app/[locale]/dashboard/renters/[id]/page.tsx` (badge in the header)
- Create: `web/src/app/[locale]/dashboard/renter-ratings/page.tsx`
- Modify: `web/src/lib/nav/navModel.ts` (entry under the leasing section after renters: `pi("renter-ratings", "/dashboard/renter-ratings", N("renterRatings"), "sidebar-renter-ratings")` when `ops && isEnabled("RENTER_RATING")` and the user has the role)
- Modify: `web/src/lib/nav/routeRegistry.ts` (`r(\`${D}/renter-ratings\`, "canViewRenterRatings", "RENTER_RATING")`; widen the `flag` union)
- Modify: `web/messages/en.json`, `ar.json` (nav label "Renter ratings" / "تقييم المستأجرين"; page strings)
- Test: `web/src/app/[locale]/dashboard/renter-ratings/__tests__/page.test.tsx`; update `web/src/lib/nav/__tests__/navModel.test.ts` and `rbacParity.test.ts` as their patterns require

**Interfaces:**
- Consumes: Task 6 components and API.
- Page: grade filter chips (All, A, B, C, D, NEW), a `TowerSelect` property filter, debounced search, a table (Renter link to `/dashboard/renters/{id}`, grade badge from row data without a live fetch, score, deductions, unit, property, computed at), a sort-by-score toggle, paging, an "Export CSV" button using `download.ts` with `exportUrl`, and an empty state "Ratings are being calculated" when the total is 0. RenewalQueue rows already carry the renter id; if not, use the lease's renter id from the queue DTO.

- [ ] **Step 1: Failing tests:**
  - `listsRowsAndFiltersByGrade`: clicking chip "D" calls `list` with `{grade: "D"}`.
  - `exportUsesFilters`
  - `emptyStateWhenNoRows`
  - nav: the `renter-ratings` item is present only when `RENTER_RATING` is enabled and the role is staff.
  - route registry: `/dashboard/renter-ratings` carries flag `RENTER_RATING`.
  - RenewalQueue: the existing test still passes with the flag off (no Rating column), and with the flag on the column header "Rating" renders.
- [ ] **Step 2: Run** the new and affected tests → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `cd web && npx vitest run` → all PASS. Then `npx tsc --noEmit` and `npm run lint` → clean.
- [ ] **Step 5: Commit** `feat(rating): rating placements and renter ratings page`

### Task 8: Full verification

- [ ] **Step 1:** Backend full suite in WSL (background; long): `./gradlew test` → BUILD SUCCESSFUL. Also run the golden tag (`-PincludeTags=golden`) to confirm accounting is untouched.
- [ ] **Step 2:** `cd web && npx vitest run && npx tsc --noEmit` → green.
- [ ] **Step 3:** Update the spec: change the §6.4 "403 when off" to the 404 actually used.
- [ ] **Step 4:** Commit any fixes. No push, and no PR unless the user asks.
