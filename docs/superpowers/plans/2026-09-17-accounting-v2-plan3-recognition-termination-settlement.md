# Accounting v2 — Plan 3: Per-day Recognition, Termination, Settlement — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Recognise rent income per day (day rate = rent ÷ actual term days; month = day rate × actual days; last period absorbs rounding) through precomputed `recognition_entries` posted as `CIL` journals by a nightly job and a manual month-end run; terminate a lease at a date by returning outstanding PDCs, truncating recognition and reversing unearned rent; rework settlement into a ledger-derived statement whose finalisation posts one `STL`.

**Architecture:** `ProrationEngine` is pure (no Spring) and is the single source of every day-rate number in the system. `RecognitionService` owns `rent_segments` + `recognition_entries` and reacts to Plan 2's `LeasePostedEvent` / `LeaseAmendedEvent` / `LeaseExtendedEvent`; `RevenueRecognitionJob` (ShedLock, per tenant) posts due entries. `LeaseTerminationService` orchestrates cheque returns (Plan 2 `ChequeService.returnToTenant`), recognition truncation and the `TCR` unearned-rent journal. `SettlementService` reads balances from `LedgerQueryService` and posts `STL` via `PostingService`.

**Tech Stack:** as Plans 1–2. ShedLock 5.16 (`@SchedulerLock`) already configured in `core/email/config/ShedLockConfig.java`. `java.time.Clock` bean from `config/ClockConfig.java` for all "today" reads.

**Spec:** `docs/superpowers/specs/2026-09-17-accounting-v2-design.md` §8 (recognition), §9.1 (termination), §9.2 (settlement), §11 (Recognition schedule tab, Run recognition, termination screen, settlement screen). **Depends on Plans 1 and 2 merged.** Consumes from Plan 2: `Lease` (lines, `contractDate`, `totalDays`), `LeaseLine` (`periodStart/End`, `chargeType.behaviour`), `Cheque` + `ChequeService.returnToTenant(chequeId, date, reason)` / `cancel`, `ChequeDueRules`, `LeasePostedEvent(tenantId, leaseId, contractDate)`, `LeaseAmendedEvent(tenantId, leaseId, reversedJournalId, newJournalId)`, `LeaseExtendedEvent(tenantId, leaseId, previousEndDate, newEndDate, lineIds)`, `PenaltyAssessmentService.outstandingForLease`, `LeaseTestFixtures`.

## Global Constraints

- Plans 1–2 Global Constraints apply. Liquibase numbers: **85** (segments, recognition, termination/settlement columns); **86** reserved (unused unless a fix-up is needed — say so in the changeset comment if used).
- Day rate stored at **6 dp** (`numeric(18,6)`), amounts at 2 dp HALF_UP; the final slice of a segment is `segment.amount − Σ previous slices` so Σ = amount exactly. Never compute a monthly figure any other way.
- `CIL` `entry_date = period_end` (spec D13). The nightly job only posts entries with `period_end ≤ today` (tenant's `Clock`) and `> books_locked_through`. The service-level `runTo(to)` does **not** clamp `to` (golden tests post future periods under a fixed `Clock`); the HTTP endpoint rejects `to > today`.
- Recognition/termination/settlement journals carry dims `(propertyId, unitId, leaseId, renterId, null)` and are posted as pairs (`PostingRequest.ofPairs`, Plan 1 Addendum A).
- Branch `feat/accounting-v2-recognition-settlement` from `main` after Plan 2 merges.

---

## File structure

**Backend — new**
- `domain/entity/enums/SegmentStatus.java`, `RecognitionStatus.java`
- `domain/entity/RentSegment.java`, `RecognitionEntry.java`
- `domain/repository/RentSegmentRepository.java`, `RecognitionEntryRepository.java`
- `core/service/recognition/ProrationEngine.java` (pure), `RecognitionService.java`, `RecognitionEventListener.java`, `RevenueRecognitionJob.java`
- `core/service/lease/LeaseTerminationService.java`, `api/dto/lease/TerminationPreviewDTO.java`, `TerminateLeaseRequest.java`
- `api/RecognitionController.java`, `api/dto/recognition/RecognitionEntryDTO.java`, `RecognitionRunResultDTO.java`
- `api/dto/settlement/SettlementStatementDTO.java`, `SettlementLineInput.java`, `FinalizeSettlementRequest.java`
- `db/changelog/changesets/85-recognition-termination-settlement.yaml`

**Backend — modified**
- `core/service/SettlementService.java` (statement from ledger, `STL` posting, no `postDepositReleaseEntries`/`createSettlement`), `domain/entity/LeaseSettlement.java` (+`earnedRent, receivedTotal, receivableBalance, depositsHeld, refundBankAccountId, journalId, balanceDue`), `LeaseSettlementDeduction.java` (+`accountId`), `api/dto/SaveSettlementDTO.java`/`SettlementResponseDTO.java`/`SettlementPreviewDTO.java` (replaced by the statement), `api/LeaseController.java` (`POST /{id}/terminate` body `TerminateLeaseRequest`, `GET /{id}/terminate/preview?date=`, settlement endpoints), `core/service/LeaseService.java` (`terminateLease`/`terminateWithSettlement` removed; expiry keeps cheques), `core/service/LeaseExpirationJob.java`, `core/service/lease/LeasePostingService.java` (publishes events — already), `api/dto/LeaseDTO.java` (+`terminatedOn`)
- Web: `leases/[id]/page.tsx` (Recognition schedule tab, Terminate action → termination page), `leases/[id]/settlement/page.tsx` (statement), new `leases/[id]/terminate/page.tsx`, new `finance/recognition/page.tsx` (month-end run), `MvpSidebar.tsx`, `lib/api/leasing.ts` (+`recognitionApi`, `terminationApi`, settlement types), messages.

---

### Task 1: Changeset 85 — segments, recognition entries, termination and settlement columns

**Files:** create `85-recognition-termination-settlement.yaml`; append include; test `domain/RecognitionSchemaIT.java`.

- [ ] **Step 1: Failing schema IT** — tables `rent_segments`, `recognition_entries` exist; `recognition_entries` has CHECK `ck_recognition_days_positive (days > 0)` and unique `(segment_id, period_start)`; `leases.terminated_on`, `lease_settlements.journal_id`, `lease_settlement_deductions.account_id` exist.

- [ ] **Step 2: Changeset**

```yaml
databaseChangeLog:
  # Accounting v2 plan 3 (spec §8-§9). Rent is recognised per day: one
  # rent_segment per RENT lease line (day rate = amount / actual days), sliced
  # into calendar-month recognition_entries that are posted as CIL journals
  # (Dr Advance Rent / Cr Rental Income) dated period_end. Termination
  # truncates segments and reverses unearned rent; settlement becomes a
  # ledger-derived statement whose finalisation posts one STL journal.
  - changeSet:
      id: 85-recognition-termination-settlement
      author: claude
      changes:
        - createTable:
            tableName: rent_segments
            columns:
              - column: { name: id, type: uuid, constraints: { primaryKey: true, nullable: false } }
              - column: { name: tenant_id, type: uuid, constraints: { nullable: false } }
              - column: { name: lease_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_rs_lease, referencedTableName: leases, referencedColumnNames: id } }
              - column: { name: lease_line_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_rs_line, referencedTableName: lease_lines, referencedColumnNames: id } }
              - column: { name: from_date, type: date, constraints: { nullable: false } }
              - column: { name: to_date, type: date, constraints: { nullable: false } }
              - column: { name: amount, type: "decimal(14,2)", constraints: { nullable: false } }
              - column: { name: days, type: int, constraints: { nullable: false } }
              - column: { name: day_rate, type: "decimal(18,6)", constraints: { nullable: false } }
              - column: { name: status, type: varchar(12), constraints: { nullable: false }, defaultValue: ACTIVE }
              - column: { name: created_at, type: timestamptz, constraints: { nullable: false }, defaultValueComputed: now() }
        - createIndex: { tableName: rent_segments, indexName: idx_rs_lease, columns: [ { column: { name: lease_id } } ] }
        - sql:
            sql: ALTER TABLE rent_segments ADD CONSTRAINT ck_rs_dates CHECK (to_date >= from_date AND days = (to_date - from_date) + 1)

        - createTable:
            tableName: recognition_entries
            columns:
              - column: { name: id, type: uuid, constraints: { primaryKey: true, nullable: false } }
              - column: { name: tenant_id, type: uuid, constraints: { nullable: false } }
              - column: { name: lease_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_re_lease, referencedTableName: leases, referencedColumnNames: id } }
              - column: { name: segment_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_re_segment, referencedTableName: rent_segments, referencedColumnNames: id } }
              - column: { name: period_start, type: date, constraints: { nullable: false } }
              - column: { name: period_end, type: date, constraints: { nullable: false } }
              - column: { name: days, type: int, constraints: { nullable: false } }
              - column: { name: amount, type: "decimal(14,2)", constraints: { nullable: false } }
              - column: { name: status, type: varchar(12), constraints: { nullable: false }, defaultValue: PLANNED }
              - column: { name: journal_id, type: uuid, constraints: { foreignKeyName: fk_re_journal, referencedTableName: journal_entries, referencedColumnNames: id } }
              - column: { name: posted_at, type: timestamptz }
        - addUniqueConstraint: { tableName: recognition_entries, columnNames: segment_id, period_start, constraintName: uq_re_segment_period }
        - createIndex: { tableName: recognition_entries, indexName: idx_re_tenant_status_end, columns: [ { column: { name: tenant_id } }, { column: { name: status } }, { column: { name: period_end } } ] }
        - createIndex: { tableName: recognition_entries, indexName: idx_re_lease, columns: [ { column: { name: lease_id } } ] }
        - sql:
            sql: ALTER TABLE recognition_entries ADD CONSTRAINT ck_recognition_days_positive CHECK (days > 0 AND period_end >= period_start)

        - addColumn:
            tableName: leases
            columns:
              - column: { name: terminated_on, type: date }
              - column: { name: termination_journal_id, type: uuid, constraints: { foreignKeyName: fk_leases_termination_journal, referencedTableName: journal_entries, referencedColumnNames: id } }
              - column: { name: termination_notes, type: text }

        - addColumn:
            tableName: lease_settlements
            columns:
              - column: { name: settlement_date, type: date }
              - column: { name: earned_rent, type: "decimal(14,2)", constraints: { nullable: false }, defaultValueNumeric: 0 }
              - column: { name: received_total, type: "decimal(14,2)", constraints: { nullable: false }, defaultValueNumeric: 0 }
              - column: { name: receivable_balance, type: "decimal(14,2)", constraints: { nullable: false }, defaultValueNumeric: 0 }
              - column: { name: deposits_held, type: "decimal(14,2)", constraints: { nullable: false }, defaultValueNumeric: 0 }
              - column: { name: penalties_outstanding, type: "decimal(14,2)", constraints: { nullable: false }, defaultValueNumeric: 0 }
              - column: { name: balance_due, type: "decimal(14,2)", constraints: { nullable: false }, defaultValueNumeric: 0 }
              - column: { name: refund_bank_account_id, type: uuid, constraints: { foreignKeyName: fk_ls_refund_bank, referencedTableName: accounts, referencedColumnNames: id } }
              - column: { name: journal_id, type: uuid, constraints: { foreignKeyName: fk_ls_journal, referencedTableName: journal_entries, referencedColumnNames: id } }
              - column: { name: collection_cheque_id, type: uuid, constraints: { foreignKeyName: fk_ls_collection_cheque, referencedTableName: cheques, referencedColumnNames: id } }
        - addColumn:
            tableName: lease_settlement_deductions
            columns:
              - column: { name: account_id, type: uuid, constraints: { foreignKeyName: fk_lsd_account, referencedTableName: accounts, referencedColumnNames: id } }
      rollback:
        - sql:
            sql: |
              ALTER TABLE lease_settlement_deductions DROP COLUMN IF EXISTS account_id;
              ALTER TABLE lease_settlements DROP COLUMN IF EXISTS settlement_date, DROP COLUMN IF EXISTS earned_rent, DROP COLUMN IF EXISTS received_total, DROP COLUMN IF EXISTS receivable_balance, DROP COLUMN IF EXISTS deposits_held, DROP COLUMN IF EXISTS penalties_outstanding, DROP COLUMN IF EXISTS balance_due, DROP COLUMN IF EXISTS refund_bank_account_id, DROP COLUMN IF EXISTS journal_id, DROP COLUMN IF EXISTS collection_cheque_id;
              ALTER TABLE leases DROP COLUMN IF EXISTS terminated_on, DROP COLUMN IF EXISTS termination_journal_id, DROP COLUMN IF EXISTS termination_notes;
              DROP TABLE IF EXISTS recognition_entries, rent_segments;
```

- [ ] **Step 3: Run, commit** `feat(recognition): changeset 85 — rent segments, recognition entries, termination/settlement columns`.

---

### Task 2: ProrationEngine (pure)

**Files:** create `core/service/recognition/ProrationEngine.java`; test `core/service/recognition/ProrationEngineTest.java`.

**Interfaces:**
```java
public final class ProrationEngine {
    public record Slice(LocalDate periodStart, LocalDate periodEnd, int days, BigDecimal amount) {}
    public static int daysInclusive(LocalDate from, LocalDate to)                                   // (to - from) + 1, throws if to < from
    public static BigDecimal dayRate(BigDecimal amount, LocalDate from, LocalDate to)               // amount / days, scale 6 HALF_UP
    public static List<Slice> slice(BigDecimal amount, LocalDate from, LocalDate to)                // calendar-month slices; last absorbs remainder; Σ == amount
    public static List<Slice> truncate(List<Slice> slices, LocalDate lastDay)                       // drops slices after lastDay; re-slices the one containing it; Σ == dayRate*daysThrough(lastDay) rounded, i.e. recomputed via slice(amountThrough, from, lastDay) where amountThrough = round(dayRate * daysInclusive(from,lastDay), 2)
    public static BigDecimal earnedThrough(BigDecimal amount, LocalDate from, LocalDate to, LocalDate asOf)  // 0 before from; amount at/after to; else round(dayRate * daysInclusive(from, asOf), 2)
}
```

- [ ] **Step 1: Failing unit test** — the spec §8.2 fixture verbatim:

```java
class ProrationEngineTest {
    static final LocalDate S = LocalDate.of(2026, 9, 24), E = LocalDate.of(2027, 9, 23);
    @Test void clientExampleFiftyOneThousand() {
        assertThat(ProrationEngine.daysInclusive(S, E)).isEqualTo(365);
        assertThat(ProrationEngine.dayRate(bd("51000"), S, E)).isEqualByComparingTo("139.726027");
        List<Slice> s = ProrationEngine.slice(bd("51000"), S, E);
        assertThat(s).hasSize(13);
        assertSlice(s.get(0), "2026-09-24", "2026-09-30", 7, "978.08");
        assertSlice(s.get(1), "2026-10-01", "2026-10-31", 31, "4331.51");
        assertSlice(s.get(2), "2026-11-01", "2026-11-30", 30, "4191.78");
        assertSlice(s.get(5), "2027-02-01", "2027-02-28", 28, "3912.33");
        assertSlice(s.get(12), "2027-09-01", "2027-09-23", 23, "3213.68");
        assertThat(s.stream().map(Slice::amount).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("51000.00");
    }
    @Test void leapYearTermHas366Days() {
        LocalDate from = LocalDate.of(2027, 3, 1), to = LocalDate.of(2028, 2, 29);
        assertThat(ProrationEngine.daysInclusive(from, to)).isEqualTo(366);
        assertThat(ProrationEngine.dayRate(bd("36600"), from, to)).isEqualByComparingTo("100.000000");
        assertThat(ProrationEngine.slice(bd("36600"), from, to).get(11).amount()).isEqualByComparingTo("2900.00"); // Feb 2028 = 29 days
    }
    @Test void singleDayAndSingleMonthTerms() {
        assertThat(ProrationEngine.slice(bd("100"), S, S)).singleElement().satisfies(x -> assertThat(x.amount()).isEqualByComparingTo("100.00"));
        assertThat(ProrationEngine.slice(bd("3000"), LocalDate.of(2026,10,1), LocalDate.of(2026,10,31))).singleElement().satisfies(x -> assertThat(x.days()).isEqualTo(31));
    }
    @Test void truncateReslicesTheMonthContainingTheCutoff() {
        List<Slice> s = ProrationEngine.slice(bd("51000"), S, E);
        List<Slice> t = ProrationEngine.truncate(s, LocalDate.of(2027, 1, 15));
        assertThat(t).hasSize(5);
        assertSlice(t.get(4), "2027-01-01", "2027-01-15", 15, "2095.89"); // 139.726027 * 15
        BigDecimal earned = t.stream().map(Slice::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(earned).isEqualByComparingTo(ProrationEngine.earnedThrough(bd("51000"), S, E, LocalDate.of(2027, 1, 15)));
        assertThat(earned).isEqualByComparingTo("15928.77"); // 114 days * 139.726027 = 15928.77
    }
    @Test void earnedThroughBoundaries() {
        assertThat(ProrationEngine.earnedThrough(bd("51000"), S, E, S.minusDays(1))).isEqualByComparingTo("0");
        assertThat(ProrationEngine.earnedThrough(bd("51000"), S, E, E)).isEqualByComparingTo("51000");
        assertThat(ProrationEngine.earnedThrough(bd("51000"), S, E, E.plusYears(1))).isEqualByComparingTo("51000");
    }
    @Test void rejectsNegativeAmountAndInvertedDates() { /* IllegalArgumentException for amount < 0 and to < from */ }
    private static void assertSlice(Slice s, String from, String to, int days, String amount) {
        assertThat(s.periodStart()).isEqualTo(LocalDate.parse(from)); assertThat(s.periodEnd()).isEqualTo(LocalDate.parse(to));
        assertThat(s.days()).isEqualTo(days); assertThat(s.amount()).isEqualByComparingTo(amount);
    }
}
```
Recompute the `truncate` expectations before trusting them: 139.726027 × 114 = 15,928.767… → 15,928.77; the sum of the first four full slices (978.08 + 4,331.51 + 4,191.78 + 4,331.51 = 13,832.88) plus the 15-day slice must equal that, so the 15-day slice is `15,928.77 − 13,832.88 = 2,095.89` — i.e. **truncate must compute the last slice as `earnedThrough − Σ previous`**, not as `round(dayRate × 15)` (which is 2,095.89 here by coincidence but not in general). Implement it that way.

- [ ] **Step 2: Implement** exactly to those rules (`slice`: iterate months; `amount_i = round(dayRate × days_i, 2)` for all but the last, last = `amount − Σ`; `truncate`: `amountThrough = earnedThrough(...)`, then `slice(amountThrough, from, lastDay)` — which by construction keeps the earlier slices identical because they are `round(dayRate × days)` with the same day rate — assert that in a test: `truncate(s, d).get(i) == s.get(i)` for all i before the cut month; `dayRate` for `truncate` must be the **original** segment rate, so pass it explicitly: `truncate(List<Slice>, BigDecimal dayRate, LocalDate lastDay)`; update the signature above accordingly).

- [ ] **Step 3: Run, commit** `feat(recognition): pure per-day proration engine with the client's fixture`.

---

### Task 3: Segments + recognition entries: build on post, rebuild on amend, append on extend

**Files:**
- Create: `domain/entity/enums/SegmentStatus.java` (`ACTIVE, TRUNCATED, CANCELLED`), `RecognitionStatus.java` (`PLANNED, POSTED, REVERSED, CANCELLED`), `RentSegment.java`, `RecognitionEntry.java`, repositories, `core/service/recognition/RecognitionService.java`, `RecognitionEventListener.java`, `api/dto/recognition/RecognitionEntryDTO.java`
- Test: `core/service/recognition/RecognitionServiceIT.java`

**Interfaces:**
```java
record RecognitionEntryDTO(UUID id, UUID leaseId, UUID segmentId, LocalDate periodStart, LocalDate periodEnd, int days, BigDecimal amount,
                           RecognitionStatus status, UUID journalId, String journalNumber, Instant postedAt)
List<RecognitionEntryDTO> RecognitionService.scheduleFor(UUID leaseId)
void RecognitionService.buildForLease(UUID leaseId)          // one ACTIVE segment per RENT line with net > 0 (from periodStart/periodEnd, default lease start/end); PLANNED entries from ProrationEngine.slice
void RecognitionService.rebuildAfterAmend(UUID leaseId, LocalDate reversalDate)   // reverse POSTED entries (PostingService.reverse -> status REVERSED), CANCEL PLANNED, mark segments CANCELLED, buildForLease
void RecognitionService.appendForExtension(UUID leaseId, List<UUID> lineIds)     // segments + entries for the new RENT lines only
RecognitionRunResult RecognitionService.runTo(LocalDate to, boolean preview)      // posts PLANNED entries with periodEnd <= to (and > booksLockedThrough); returns counts/amount; preview = no posting
List<RecognitionEntryDTO> RecognitionService.pending(LocalDate to)
record RecognitionRunResult(int posted, BigDecimal amount, List<RecognitionEntryDTO> entries, List<String> errors)
```
`RecognitionEventListener`: `@EventListener` (same transaction) on `LeasePostedEvent` → `buildForLease`; `LeaseAmendedEvent` → `rebuildAfterAmend(leaseId, today)`; `LeaseExtendedEvent` → `appendForExtension`.
`CIL` posting per entry: `PostingRequest.ofPairs(CIL, entry.periodEnd, "Advance rent adjustment – <MMM yyyy>", dims, RECOGNITION, entry.id, null, List.of(pair(dr(ADVANCE_RENT, amount), cr(incomeRef, amount))))` where `incomeRef = lease.incomeAccountId != null ? ById(...) : ByRole(RENTAL_INCOME)`; `ADVANCE_RENT` resolved for the property — but if the RENT line's `creditAccountId` differs from the property mapping (manual override), debit **that** account (`ById(line.creditAccountId)`) so the deferral and the release hit the same ledger. Entry → POSTED, `journalId`, `postedAt`. Each entry posts in its own `REQUIRES_NEW` transaction inside `runTo` so one failure (e.g. unmapped income account) is recorded in `errors` and does not roll back the rest.

- [ ] **Step 1: Failing IT** (Galah fixture: RENT 51,000 24-09-2026→23-09-2027 + ADMIN 2,000; post via `LeasePostingService`):
  - `postingBuildsOneSegmentAndThirteenPlannedEntries` — days 365, rate 139.726027, first entry 978.08 dated 2026-09-30, last 3,213.68 dated 2027-09-23; ADMIN line has no segment.
  - `runToPostsOnlyEntriesEndingOnOrBeforeTheDate` — `runTo(2026-11-30)` posts 3 (978.08 + 4,331.51 + 4,191.78), each `CIL` pair `Dr Advance Rent - <property> / Cr Rental Income <property>` dated `period_end`; running it again posts 0; `pending(2027-12-31)` lists 10.
  - `lockedPeriodEntriesAreSkippedNotFailed` — lock through 2026-10-31 → `runTo(2026-11-30)` posts only November; the two earlier stay PLANNED and appear in `errors` as "locked".
  - `amendRebuildsScheduleAndReversesPostedEntries` — after posting Sep+Oct, amend rent to 60,000 (cheques adjusted to match) → 2 reversal journals, all old entries REVERSED/CANCELLED, new schedule of 13 with first 1,150.68 (60,000/365×7) …
  - `extensionAppendsASecondSegment` — extend to 2027-12-31 with RENT 15,000 for 24-09→31-12-2027… wait the extension window is 2027-09-24→2027-12-31 (99 days): 3 new entries, existing untouched.
  - `incomeAccountOverrideIsHonoured` — set `lease.incomeAccountId` to a different leaf → `CIL` credits it.

- [ ] **Step 2: Implement** entities (BaseTenantEntity; `RentSegment.leaseLine` ManyToOne LAZY; `RecognitionEntry.segment` ManyToOne LAZY), repositories (`findByLease_IdOrderByPeriodStartAsc`, `findByTenantIdAndStatusAndPeriodEndLessThanEqualOrderByPeriodEndAsc(tenantId, PLANNED, to)` — a JPQL with explicit tenant param because the job runs per tenant with the filter on), service, listener, DTO.

- [ ] **Step 3: Run, commit** `feat(recognition): rent segments, planned entries, CIL posting, rebuild on amend/extend`.

---

### Task 4: Nightly job + API (manual run with preview, lease schedule)

**Files:** create `core/service/recognition/RevenueRecognitionJob.java`, `api/RecognitionController.java`, `api/dto/recognition/RecognitionRunResultDTO.java`; test `core/service/recognition/RevenueRecognitionJobIT.java`, `api/RecognitionControllerIT.java`.

**Interfaces:**
- `RevenueRecognitionJob.run()` — `@Scheduled(cron = "0 30 0 * * *") @SchedulerLock(name = "revenue-recognition", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")`; iterates `landlordOrgRepository.findAll()` ids, sets `TenantContextHolder`, calls `recognitionService.runTo(LocalDate.now(clock), false)`, logs per-tenant counts, clears context in `finally` (pattern: `PenaltyService.calculateDailyPenalties`).
- Endpoints (`/api/v1/finance/recognition`, SA/TA/ACCOUNTANT): `GET /pending?to=` → `List<RecognitionEntryDTO>`; `POST /run?to=&preview=true|false` → `RecognitionRunResultDTO(posted, amount, entries, errors)`; rejects `to > today` with 400 "Cannot recognise income for periods that have not ended". `GET /api/v1/leases/{id}/recognition` (SA/TA/ACCOUNTANT/PM) → schedule.

- [ ] **Step 1: Failing ITs** — job with a fixed `Clock` at 2026-12-01 posts Sep–Nov for two tenants independently (each tenant's numbering separate); controller: preview returns entries without posting; run posts; future `to` → 400; PM forbidden on run.
- [ ] **Step 2: Implement; override the `Clock` bean in tests with `@TestConfiguration` (`Clock.fixed(...)`).**
- [ ] **Step 3: Run, commit** `feat(recognition): nightly ShedLock job and month-end run API`.

---

### Task 5: Termination at a date — return PDCs, truncate recognition, reverse unearned rent

**Files:**
- Create: `core/service/lease/LeaseTerminationService.java`, `api/dto/lease/TerminationPreviewDTO.java`, `api/dto/lease/TerminateLeaseRequest.java`
- Modify: `core/service/recognition/RecognitionService.java` (+`truncateForTermination(leaseId, T)`), `api/LeaseController.java` (`GET /{id}/terminate/preview?date=`, `POST /{id}/terminate` body `TerminateLeaseRequest`; remove `terminateWithSettlement`), `core/service/LeaseService.java` (delete `terminateLease`/`terminateWithSettlement`; keep `releaseUnitIfNoOtherActiveLease` and make it package-visible), `api/dto/LeaseDTO.java` (+`terminatedOn`)
- Test: `core/service/lease/LeaseTerminationServiceIT.java`

**Interfaces:**
```java
record TerminationPreviewDTO(LocalDate terminationDate, BigDecimal earnedRentThroughDate, BigDecimal recognisedSoFar, BigDecimal unearnedRent,
        List<ChequeDTO> chequesToReturn, List<ChequeDTO> chequesToKeep, List<ChequeDTO> bouncedOutstanding, BigDecimal receivableAfter)
record TerminateLeaseRequest(LocalDate terminationDate, List<UUID> returnChequeIds, List<UUID> keepChequeIds, String notes)

TerminationPreviewDTO LeaseTerminationService.preview(UUID leaseId, LocalDate T)
LeaseDTO              LeaseTerminationService.terminate(UUID leaseId, TerminateLeaseRequest r, UUID byUser)
```
Rules (spec §9.1):
1. Lease must be ACTIVE or NOTICE_GIVEN; `T` within `[startDate, endDate]`; `T` not in a locked period.
2. Default split: uncleared rows (REGISTERED/DEPOSITED/ONLINE_PENDING→treated as REGISTERED after `revertOnlinePending`) with `chequeDate > T` → return; `≤ T` → keep. The request's explicit lists override; every uncleared row must be in exactly one list.
3. For each returned row: `chequeService.returnToTenant(id, T, "Contract terminated " + T)` (PDR reversal).
4. `recognitionService.truncateForTermination(leaseId, T)`: for each ACTIVE segment overlapping T: cancel PLANNED entries with `periodStart > T`; reverse POSTED entries with `periodStart > T`; the entry containing T: if POSTED → reverse it and post a replacement for `[periodStart, T]` with amount from `ProrationEngine.truncate`; if PLANNED → replace its amount/period_end; segment → `TRUNCATED` with `to_date = T`. Segments entirely after T → CANCELLED with all entries cancelled/reversed.
5. Unearned = Σ over RENT segments `(segment.amount − earnedThrough(T))`. Post `TCR pair(dr(ById(line.creditAccountId) /* Advance Rent */, unearned), cr(RENT_RECEIVABLE, unearned))` dated T, narration "Unearned rent reversed on termination"; `lease.terminationJournalId`.
6. `lease.status = TERMINATED`, `terminatedOn = T`, `terminationNotes`; `releaseUnitIfNoOtherActiveLease`; `LeaseEvent`; `LEASE_TERMINATED` email event; `unitListingService.syncAvailableFrom(unitId, null)` in a try/catch (as today).
7. Preview computes all of the above without writing, plus `receivableAfter` = current lease receivable balance (`LedgerQueryService.accountLedger(RR, filter leaseId).closingBalance()`) + Σ returned amounts − unearned.

- [ ] **Step 1: Failing IT** (Anil's scenario, Galah fixture posted 16-09-2026, cheques 2,000 admin 11-09 + 4 × 12,750 (02-10-2026, 02-01-2027, 02-04-2027, 02-07-2027); clear admin + first two rent cheques; recognise through 2027-01-31):
  - `previewDefaultsReturnChequesDatedAfterT` — T = 2027-02-15 → return: 02-04-2027 and 02-07-2027; keep: none uncleared before T; unearned = 51,000 − earnedThrough(2027-02-15) = 51,000 − round(139.726027 × 145, 2) = 51,000 − 20,260.27 = 30,739.73; recognisedSoFar = 18,164.39 (Sep–Jan).
  - `terminatePostsReturnReversalsTruncatesRecognitionAndReversesUnearned` — after terminate: two cheques RETURNED with PDR reversal journals; Feb entry replaced by a 1–15 Feb entry of 2,095.89 (20,260.27 − 18,164.39) PLANNED; Mar–Sep entries CANCELLED; `TCR` `Dr Advance Rent 30,739.73 / Cr Rent Receivable 30,739.73`; RR balance for the lease = earned − received = 20,260.27 − 25,500 = **−5,239.73** (we owe the tenant; rent receivable shows a credit); lease TERMINATED, unit VACANT.
  - `terminateWithPostedEntryAfterT` — recognise through 2027-03-31 then terminate at 2027-02-15 → March entry REVERSED, Feb replaced by reverse+repost.
  - `keepListOverridesDefault` — keep the 02-04-2027 cheque explicitly → only one returned; receivable reflects it.
  - `rejectsDateOutsideTermOrLocked`.

- [ ] **Step 2: Implement.** `LeaseController`: `GET /{id}/terminate/preview?date=` and `POST /{id}/terminate` (SA/TA/ACCOUNTANT; PM may preview). Delete `TerminateWithSettlementDTO`.
- [ ] **Step 3: Run, commit** `feat(leasing): termination returns PDCs, truncates recognition, reverses unearned rent`.

---

### Task 6: Settlement statement from the ledger; finalize posts STL

**Files:**
- Modify: `core/service/SettlementService.java` (rewrite), `domain/entity/LeaseSettlement.java`, `LeaseSettlementDeduction.java` (+`account`), `api/dto/SaveSettlementDTO.java` (line gets `accountId`), `api/dto/settlement/SettlementStatementDTO.java` (new, replaces `SettlementPreviewDTO`), `FinalizeSettlementRequest.java` (`settlementDate, refundBankAccountId`), `api/LeaseController.java` (settlement endpoints keep paths; finalize takes the body; `POST /{id}/settlement/finalize` no longer terminates — termination happens first), `api/dto/SettlementResponseDTO.java` (+statement fields, `journalNumber`, `collectionChequeId`)
- Test: `core/service/SettlementServiceIT.java` (replaces `SettlementDepositLedgerTest`)

**Interfaces:**
```java
record SettlementStatementDTO(LocalDate asOf, BigDecimal earnedRent, BigDecimal receivedTotal, BigDecimal receivableBalance /* +ve owed by tenant, -ve owed to tenant */,
        BigDecimal depositsHeld, BigDecimal penaltiesOutstanding, List<DeductionLineDTO> deductions, List<AdditionLineDTO> additions,
        BigDecimal totalDeductions, BigDecimal totalAdditions, BigDecimal netRefund /* >0 refund, <0 due from tenant */)
record DeductionLineDTO(UUID id, DeductionCategory category, String description, BigDecimal amount, UUID accountId, String accountName, boolean autoCalculated, List<DeductionAttachmentDTO> attachments)
record AdditionLineDTO(UUID id, AdditionCategory category, String description, BigDecimal amount, UUID accountId, String accountName)

SettlementStatementDTO SettlementService.statement(UUID leaseId)                       // computed live from ledger + draft lines
LeaseSettlement       SettlementService.saveDraft(UUID leaseId, SaveSettlementDTO dto, UUID userId)   // lines only; statement recomputed on read
SettlementResponseDTO SettlementService.finalize(UUID leaseId, FinalizeSettlementRequest r, UUID userId)
```
Statement sources: `earnedRent` = Σ POSTED recognition entries for the lease (+ for a still-ACTIVE lease being settled at expiry, `earnedThrough(today)` of any unposted tail is *not* included — run recognition first; the UI says so when `pending(today)` for the lease is non-empty); `receivedTotal` = Σ CLEARED cheques; `receivableBalance` = `LedgerQueryService.accountLedger(RENT_RECEIVABLE for property, filter leaseId).closingBalance()` (this already includes unearned reversal, PDC returns, approved penalties); `depositsHeld` = −(closing balance of every DEPOSIT-behaviour line's `creditAccountId` filtered by leaseId) (credit balance → positive number); `penaltiesOutstanding` = `PenaltyAssessmentService.outstandingForLease`.
Deduction default accounts by category (resolver, property): `PROPERTY_DAMAGE, CLEANING, KEY_REPLACEMENT, UTILITY_ARREARS → MAINTENANCE_CHARGES`; `EARLY_TERMINATION_FEE → RENT_PENALTY`; `PENALTIES → (not a line; already in receivable — reject the category with 400 "penalties are already in the receivable balance")`; `UNPAID_RENT → same rejection`; `OTHER → OTHER_INCOME`. Additions: `DEPOSIT_INTEREST, LANDLORD_COMPENSATION, OTHER → OTHER_INCOME` (debit); `PREPAID_RENT, UTILITY_OVERPAYMENT → rejected` (they are receivable credits already).
`netRefund = depositsHeld − receivableBalance − totalDeductions + totalAdditions`.
**Finalize** posts one `STL` dated `settlementDate` with pairs:
- `pair(dr(ById(depositAccount), depositsHeld), cr(<settlement clearing>))` — to keep pairs meaningful without a clearing account, post the STL as an **n-line entry without pairs** (lines: `Dr` each deposit account for its held balance; `Dr` each addition account; `Cr` each deduction account; `Cr RENT_RECEIVABLE receivableBalance` when > 0 (or `Dr` when < 0); `Cr ById(refundBankAccountId) netRefund` when > 0). When `netRefund < 0`: no bank line; the shortfall stays in RENT_RECEIVABLE and `chequeService.addRowToPostedLease(leaseId, CASH row amount = −netRefund, chequeDate = settlementDate, narration "Settlement balance due")` → `collectionChequeId`. Balance check is PostingService's job; assert in the test that the entry balances by construction.
- Settlement → FINALIZED, `journalId`, statement snapshot columns filled; lease → `CLOSED` when `netRefund ≥ 0` (or when the collection row later clears — `ChequeService.clear` on a row whose lease is TERMINATED with a FINALIZED settlement and no other uncleared rows sets CLOSED).

- [ ] **Step 1: Failing IT** — continuing Task 5's terminated Galah lease (receivable −5,239.73, SD 3,000 held via a cleared deposit cheque — add a SECURITY_DEPOSIT line + cheque to the fixture): `statementShowsCreditOwedToTenant` (earned 20,260.27, received 28,500, receivable −5,239.73, depositsHeld 3,000, netRefund 8,239.73); `finalizeRefundPostsStlAndClosesLease` — STL lines: Dr Security Deposit 3,000; Dr Rent Receivable 5,239.73; Cr Bank 8,239.73; lease CLOSED; `finalizeWithDeductionsExceedingDepositLeavesBalanceDue` (damage 4,000 → netRefund −? compute: 3,000 − (−5,239.73) − 4,000 = 4,239.73 refund still; make damage 10,000 → −1,760.27 due → CASH row created, lease stays TERMINATED); `rejectsUnpaidRentAndPenaltiesCategories`; `draftCanBeEditedUntilFinalized`.
- [ ] **Step 2: Implement; delete `SettlementPreviewDTO`, `createSettlement`, `postDepositReleaseEntries`.**
- [ ] **Step 3: Run, commit** `feat(settlement): ledger-derived statement, STL posting, balance-due collection row`.

---

### Task 7: Expiry job, notice-given, and lease status CLOSED rules

**Files:** modify `core/service/LeaseExpirationJob.java`, `core/service/cheque/ChequeService.java` (close-on-last-clear hook), `LeaseController` (`POST /{id}/notice` sets NOTICE_GIVEN — exists? if not, add: SA/TA/ACCOUNTANT/PM); test `core/service/LeaseExpirationJobIT.java`.

- Expiry (`ACTIVE|NOTICE_GIVEN` with `endDate < today` and `terminatedOn == null`) → `EXPIRED`; **no cheque changes** (uncleared rows stay collectable; recognition completes on its own); unit released as today; `LeaseEvent`.
- `ChequeService.clear/receive`: after clearing, if lease status ∈ {TERMINATED, EXPIRED}, settlement FINALIZED, and `countByLease_IdAndStatusIn(uncleared)` == 0 → lease `CLOSED`.
- `@SchedulerLock(name = "lease-expiration")` added to the job.

- [ ] Steps: failing IT (expired lease keeps its REGISTERED cheque; clearing the last row after a finalized settlement closes the lease), implement, commit `feat(leasing): expiry keeps cheques collectable; CLOSED on last clearance`.

---

### Task 8: Web — Recognition schedule tab, month-end run page, termination page, settlement statement

**Files:**
- Create: `web/src/app/[locale]/dashboard/finance/recognition/page.tsx`, `web/src/app/[locale]/dashboard/leases/[id]/terminate/page.tsx`, `web/src/components/leases/RecognitionScheduleTab.tsx`, `web/src/components/leases/ChequeReturnTable.tsx`
- Modify: `web/src/lib/api/leasing.ts` (+`recognitionApi { pending(to), run(to, preview), leaseSchedule(id) }`, `terminationApi { preview(id, date), terminate(id, body) }`, settlement types/calls `settlementApi { statement(id), saveDraft(id, body), finalize(id, body), get(id) }`), `leases/[id]/page.tsx` (Recognition tab real; Terminate → `/terminate`), `leases/[id]/settlement/page.tsx` (rewrite), `MvpSidebar.tsx` (Finance → Month-end recognition), `messages/*.json` (`Recognition`, `Termination`, `Settlement` namespaces), `web/src/lib/rbac.ts` (`canRunRecognition: SA/TA/ACCOUNTANT`, `canTerminateLeases: SA/TA/ACCOUNTANT`)
- Test: `components/leases/__tests__/RecognitionScheduleTab.test.tsx`, `leases/[id]/terminate/__tests__/preview.test.tsx`, `leases/[id]/settlement/__tests__/statement.test.tsx`

**Interfaces:**
- `RecognitionScheduleTab({ leaseId })` — table Period | Days | Amount | Status | Journal (link) with totals; badge colours PLANNED grey / POSTED green / REVERSED muted / CANCELLED strikethrough; footer "Σ = contract rent" check.
- Month-end page: "To date" picker (default last day of previous month, max today), **Preview** lists pending entries grouped by property with totals; **Run** with `ConfirmDialog` → result card (posted count, amount, errors list); locked-through date shown from `ledgerApi.fiscal.get()`.
- Termination page: date picker → `terminationApi.preview` → cards (earned through date, recognised so far, unearned to reverse, receivable after) + `ChequeReturnTable` (uncleared rows with a Return / Keep toggle per row, defaults from preview) + notes → **Terminate** with `ConfirmDialog` → navigate to settlement page.
- Settlement page: statement header (earned, received, receivable, deposits held, penalties outstanding) → deduction/addition lines grid with category, description, amount, **account** (`AccountPicker` filtered to INCOME, pre-filled by category), attachments (existing) → net refund / balance due banner → refund bank `AccountPicker` (ASSET) + settlement date → **Finalize** (`canPostJournals`) → shows STL number; read-only after FINALIZED.

- [ ] Steps: failing tests (schedule tab renders 13 rows and totals; termination page toggles a row from return to keep and sends the ids; settlement page disables Finalize until a refund bank is chosen when netRefund > 0), implement, vitest/tsc/lint, commit `feat(web): recognition schedule, month-end run, termination and settlement statement pages`.

---

### Task 9: Full verification and PR

- [ ] `cd backend && ./gradlew test`; `cd web && npx tsc --noEmit && npm run lint && npx vitest run`; Playwright: extend `e2e/finance/cheques.spec.ts` (Plan 2) with "run month-end → tenant ledger shows CIL rows → terminate → settlement finalize → trial balance balances".
- [ ] Update `scripts/seed_demo_tenant.py` to run recognition to the end of last month after seeding (Plan 5 rewrites the script fully).
- [ ] Commit, push `feat/accounting-v2-recognition-settlement`, open PR "feat(recognition): accounting v2 plan 3 — per-day recognition, termination, settlement" (same body structure as Plans 1–2; note D13 month-end dating and the derived-fields decision; attribution footer).

---

## Self-review

**Spec coverage:** §8.1 RentSegment (Tasks 1, 3) ✔ · §8.2 RecognitionEntry precomputed, remainder on last, client table as fixture, lease tab (Tasks 2, 3, 8) ✔ · §8.3 CIL pair Dr Advance Rent / Cr Rental Income with override, dated period_end (Task 3) ✔ · §8.4 nightly job per tenant + manual run with preview + lock respected (Tasks 3, 4) ✔ · §8.5 re-planning on amend / extend / termination (Tasks 3, 5) ✔ · §9.1 termination: default return of post-T cheques with override, truncation, TCR unearned, receivable = earned − received, TERMINATED + unit freed (Task 5) ✔ · §9.2 statement, deduction accounts, STL n-line, balance-due collection row, expiry path (Tasks 6, 7) ✔ · §11 screens: Recognition schedule tab, Run recognition, termination screen, settlement (Task 8) ✔ · §12 tests: proration unit with fixture, ITs per document, invariants (each IT asserts trial balance balances via `LedgerQueryService.trialBalance` after the scenario — add that assertion to Tasks 3, 5, 6 ITs) ✔.
**Placeholders:** none. Numeric expectations in Tasks 5–6 are derived in-line from the day rate; the implementer re-derives them with `ProrationEngine` before asserting (Task 2 Step 1 shows how).
**Type consistency:** `RecognitionEntryDTO`, `RecognitionRunResult`, `TerminationPreviewDTO`, `TerminateLeaseRequest`, `SettlementStatementDTO`, `FinalizeSettlementRequest` defined once and used by the same names in the web client; `ProrationEngine.truncate` takes the segment's stored `dayRate` (Task 2 Step 2 correction).
