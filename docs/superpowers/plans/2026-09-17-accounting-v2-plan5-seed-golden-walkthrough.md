# Accounting v2 — Plan 5: Seed, Golden Ledger Tests, Walkthrough — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove accounting v2 reproduces the client's own PACT General Ledger line for line by replaying their two real contracts through our API, then re-seed the demo tenant, the Playwright suites and the tutorial library on the v2 endpoints so nothing in the product demo or the recordings is still describing v1 finance.

**Architecture:** Two Testcontainers integration tests (`GoldenLedgerLeBoulevardIT`, `GoldenLedgerGalah2IT`) drive the real services — seed chart of accounts, create a property, map the PACT account names, create a lease with lines + a cheque grid, post, walk the cheques through deposit/clear/bounce on the PACT dates, run recognition to a cut-off — and then diff `LedgerQueryService.renterLedger()` against CSV fixtures transcribed verbatim from the client's exports. Everything else in the plan hangs off the same v2 endpoints: the demo seed script, a dev Playwright spec, a production spec, four tutorial recordings, and a tenant feature flag that hides the untouched mobile finance screens until the web is stable.

**Tech Stack:** Java 21, Spring Boot 4.0.3, JUnit 5 + Testcontainers (`postgres:16-alpine`), AssertJ; Python 3 + `requests` (`scripts/seed_demo_tenant.py`); Next.js 16 + TypeScript + Playwright (dev suite `web/e2e`, production suite `web/e2e-prod`, recorder `tutorials/capture/record-tutorial.mjs`); Flutter + Riverpod + GoRouter (mobile gating only).

**Spec:** `docs/superpowers/specs/2026-09-17-accounting-v2-design.md` — this plan implements §12 "Golden ledger tests" and §12 "E2E", plus §13 build-order item 5. Read §6 (lease as posting document), §7 (PDC register and cheque lifecycle), §8 (per-day recognition) and §9 (termination/settlement/receipts) carefully before Task 2: the golden tests assert the exact journals those sections define, and every deviation from PACT below is traced to a numbered spec decision.

**This plan executes last.** It depends on Plans 1–4 being merged. Plan 1 (`…-plan1-ledger-core.md`) and Plan 4 (`…-plan4-vouchers-cutover.md`) exist and their names below are copied from them verbatim. **Plans 2 and 3 do not exist yet**, so their public surfaces are pinned below as assumptions; each task's `Consumes` block names them exactly, and where the merged code differs the merged code wins and the call site is fixed in the same task. Note Plan 4's own ordering constraint: its Tasks 10–11 must themselves run after Plans 2–3, so the real merge order is 1 → 2 → 3 → 4 → 5.

## Global Constraints

- **Never bypass tenant isolation** — every query scopes by `tenant_id` (project rule 1). The golden tests create their own `LandlordOrg` and set `TenantContextHolder`; they never read another tenant's rows.
- **Liquibase changesets are append-only.** Plan 1 consumed `81-` and `82-`. This plan adds **no** changeset: `TenantFeature` is a `varchar(100)` column with no CHECK and no Postgres enum type (changeset `38-tenant-feature.yaml`), so a new constant needs no migration and no backfill. Task 14 proves that rather than asserting it.
- Amounts are `BigDecimal` `precision = 14, scale = 2`; DB `decimal(14,2)`. Currency AED, no multi-currency. Fixture comparisons use `compareTo == 0`, never `equals`, so `9000` and `9000.00` match.
- Day rate is stored at **6 dp** (spec §8.1). `amount = round(day_rate × days, 2)` HALF_UP; the segment's final row = `segment.amount − Σ previous rows` (spec §8.2).
- Recognition `entry_date = period_end` (spec D13), **not** the 1st of the following month as PACT does. For the final slice of a segment `period_end` is the lease end date, not a month end.
- Backend tests: `…Test` = unit (Mockito), `…IT` = Testcontainers `@SpringBootTest`. Run one class with `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.<pkg>.<Class>'`.
- Web calls go through `fetch("/api/proxy/v1/...")`; never hardcode the backend URL in a client component (project rule 6).
- Python seed script stays **API-driven and idempotent** — get-or-create per resource, safe to re-run against a tenant that already has data. No SQL, no direct DB access.
- Git: conventional commits. Work on plain branch `feat/accounting-v2-seed-golden` cut from `main` after Plan 4 merges — **plain branch, not a git worktree** (user preference `feedback_avoid_worktrees`).
- Counts reported in the PR body come from the test runner's own output, never from `grep` (user preference `feedback_verify_by_running`).
- Commit after every task.

---

## Consumed public surfaces

Everything this plan calls that another plan ships. Names are exact; if an executor finds a different name in the merged code, the merged code wins and this plan's callers are updated in the same task.

### From Plan 1 — Ledger core (merged; `docs/superpowers/plans/2026-09-17-accounting-v2-plan1-ledger-core.md`)

```java
// core/service/ledger/PostingService.java
JournalEntry post(PostingRequest r);
JournalEntry reverse(UUID entryId, LocalDate date, String reason);

// core/service/ledger/PostingRequest.java
record PostingRequest(JournalDocType docType, LocalDate entryDate, String narration, Dimensions dims,
                      JournalSourceType sourceType, UUID sourceId, UUID importBatchId, List<Line> lines)
record Dimensions(UUID propertyId, UUID unitId, UUID leaseId, UUID renterId, UUID chequeId)
enum Side { DR, CR }
record Line(AccountRef account, Side side, BigDecimal amount, Dimensions dims, String narration)

// core/service/ledger/LedgerQueryService.java
record LedgerFilter(LocalDate from, LocalDate to, UUID propertyId, UUID unitId, UUID leaseId, UUID renterId)
record LedgerRowDTO(UUID entryId, String entryNumber, LocalDate entryDate, String docType, String particular,
                    String narration, BigDecimal debit, BigDecimal credit, BigDecimal balance,
                    UUID propertyId, UUID unitId, UUID leaseId, UUID renterId, UUID chequeId)
record AccountLedgerDTO(UUID accountId, String accountCode, String accountName, String accountType,
                        BigDecimal openingBalance, List<LedgerRowDTO> rows, BigDecimal totalDebit,
                        BigDecimal totalCredit, BigDecimal closingBalance, boolean truncated)
record TrialBalanceRowDTO(UUID accountId, String code, String name, String accountType, UUID parentId,
                          UUID propertyId, BigDecimal debit, BigDecimal credit, BigDecimal balance)
AccountLedgerDTO       accountLedger(UUID accountId, LedgerFilter f)
List<AccountLedgerDTO> generalLedger(List<UUID> accountIds, LedgerFilter f)
List<AccountLedgerDTO> renterLedger(UUID renterId, LocalDate from, LocalDate to)
AccountLedgerDTO       vendorLedger(UUID vendorId, LocalDate from, LocalDate to)
List<TrialBalanceRowDTO> trialBalance(LocalDate asOf, UUID propertyId)

// core/service/ledger/AccountResolver.java
Account resolve(AccountRole role, UUID propertyId);
Map<AccountRole, Account> resolveAll(Set<AccountRole> roles, UUID propertyId);   // UnmappedAccountRoleException

// core/service/ledger/PropertyAccountService.java
List<RoleMappingDTO> generateMissing(UUID propertyId);
List<RoleMappingDTO> getMappings(UUID propertyId);
void setMapping(UUID propertyId, AccountRole role, UUID accountId);
void clearMapping(UUID propertyId, AccountRole role);
List<RoleMappingDTO> getTenantDefaults();
void setTenantDefault(AccountRole role, UUID accountId);
void seedDefaultTemplateAndDefaults();

// core/service/AccountService.java
void seedDefaultAccounts();
Account createLeaf(String name, Account parent, UUID propertyId);
Account getAccountByCode(String code);

// core/service/ledger/TenantFiscalSettingsService.java
TenantFiscalSettings get();
void setBooksStartDate(LocalDate d);
void lockThrough(LocalDate d);
void assertOpen(LocalDate d);
```

`JournalDocType` values: `TCO, TCR, PDR, CRT, CBR, CIL, RCP, STL, PEN, PISR, BPV, OB, JV`.
`AccountRole` values: `RENT_RECEIVABLE, ADVANCE_RENT, RENTAL_INCOME, PDC_RECEIVABLE, BANK, SECURITY_DEPOSIT, ADMIN_FEE, PARKING_INCOME, PARKING_DEPOSIT, COOLING_CHARGES, MAINTENANCE_CHARGES, RENT_PENALTY, CHEQUE_RETURN_PENALTY, OTHER_INCOME, FORFEITED_INCOME, DISCOUNT_ALLOWED, ROUNDING_OFF, CASH, OUTPUT_VAT, INPUT_VAT, OPENING_BALANCE_DIFFERENCE`.

HTTP:
- `POST /api/v1/finance/accounts/seed` — seeds the chart of accounts **and** (Plan 1 Task 6) the property-account template rows and the tenant default mappings. Idempotent; a no-op when accounts already exist.
- `GET /api/v1/finance/accounts`, `GET /api/v1/finance/accounts/code/{code}`
- `GET /api/v1/finance/properties/{propertyId}/accounts` → `RoleMappingDTO[]`, `POST …/generate`, `PUT …/{role}` `{accountId}`
- `GET /api/v1/finance/ledger?accountIds=&from=&to=&propertyId=&unitId=&leaseId=&renterId=`
- `GET /api/v1/finance/ledger/account/{accountId}`, `GET /api/v1/finance/ledger/renter/{renterId}`, `GET /api/v1/finance/ledger/vendor/{vendorId}`
- `GET /api/v1/finance/trial-balance?asOf=&propertyId=`
- `GET /api/v1/finance/journals?docType=&from=&to=&page=&size=`, `GET /api/v1/finance/journals/{id}`, `POST /api/v1/finance/journals` (manual JV), `POST /api/v1/finance/journals/{id}/reverse` `{date, reason}`
- `GET /api/v1/finance/fiscal`, `PUT /api/v1/finance/fiscal`, `POST /api/v1/finance/fiscal/lock` `{through}`

Web client `web/src/lib/api/ledger.ts` exports `ledgerApi` with `accounts`, `propertyAccounts`, `template`, `defaults`, `roles()`, `ledger.{general,account,renter,vendor}`, `trialBalance`, `journals.{list,get,postManual,reverse,docTypes}`, `fiscal.{get,update,lock}`, plus `fmtAmount(n)` → `"61,000.00"` and `fmtBalance(n)` → `"61,000.00 Dr"` / `"3,000.00 Cr"` / `"0.00"`. New role `ACCOUNTANT`.

### Consumes (from plan 2) — Lease posting + PDC register

Assumed from spec §6 and §7. Plan 5 calls only these.

```
POST   /api/v1/leases
       { unitId, renterId, contractDate, startDate, endDate, contractNumber?, gracePeriodDays?,
         lines:   [ { chargeTypeCode, grossAmount, discountAmount, narration } ],
         cheques: [ { seqNo, postingDate, chequeNumber, chequeDate, payeeBank, amount, narration, mode } ] }
       → 201 { id, status: "DRAFT", contractValue, lines[], cheques[] }
       `chargeTypeCode` is a seeded `charge_types.code` (§6.1): RENT, SECURITY_DEPOSIT, ADMIN_FEE,
       PARKING_DEPOSIT, COOLING_CHARGES, PARKING_FEE, MAINTENANCE_CHARGES.
       `mode` ∈ PDC, CASH, TRANSFER, ONLINE.  Σ cheque amounts must equal Σ line net amounts.
POST   /api/v1/leases/{id}/post          → 200 { id, status: "ACTIVE", postingJournalId, postedAt }
GET    /api/v1/leases/{id}               → includes lines[], cheques[] (each with id, seqNo, status)
GET    /api/v1/leases/{id}/cheques       → Cheque[] ordered by seqNo
POST   /api/v1/cheques/{id}/deposit      { depositDate, bankAccountId? }  → { status: "DEPOSITED" }
POST   /api/v1/cheques/{id}/clear        { clearedDate }                  → { status: "CLEARED", crtJournalId }
POST   /api/v1/cheques/{id}/bounce       { bouncedDate, failureReason }   → { status: "BOUNCED", cbrJournalId }
POST   /api/v1/cheques/{id}/replace      { replacements: [ { seqNo, postingDate, chequeNumber, chequeDate,
                                            payeeBank, amount, narration, mode } ] } → Cheque[]
GET    /api/v1/finance/ledger/renter/{renterId}?from=&to=  → AccountLedgerDTO[]   (Plan 1 endpoint, Plan 2 fills it)
```

### Consumes (from plan 3) — Recognition + termination + settlement

```
POST   /api/v1/finance/recognition/run?to=YYYY-MM-DD
       Posts one CIL journal per PLANNED recognition_entry with period_end <= to, in period_end order,
       entry_date = period_end. Returns { posted: n, skipped: n, entries: [...] }.
       It must NOT additionally clamp `to` to the system date — the golden tests post a full
       2026-09→2027-09 schedule on a machine whose clock reads 2026-09-17.
GET    /api/v1/leases/{id}/recognition   → RecognitionEntryDTO[]
       { id, periodStart, periodEnd, days, amount, status, journalId }
POST   /api/v1/leases/{id}/terminate     { terminationDate, chequeDispositions: [{chequeId, action}] }
GET    /api/v1/leases/{id}/settlement    → settlement statement preview
POST   /api/v1/leases/{id}/settlement/finalize  { bankAccountId, deductions: [...] }
```

### Consumes (from plan 4) — Vouchers

Plan 4 is written, so these are its names, not assumptions. Nothing in the golden tests needs them;
Task Group B's demo seed creates one vendor invoice and one payment voucher (spec §10.1/§10.2):

```
GET  /api/v1/finance/vouchers?docType&status&vendorId&propertyId&from&to&page&size → Page<VoucherDTO>
POST /api/v1/finance/vouchers            body VoucherInputDTO  → VoucherDetailDTO (201)
POST /api/v1/finance/vouchers/{id}/post                        → VoucherDetailDTO
```
```java
record VoucherInputDTO(VoucherType docType, LocalDate docDate, UUID vendorId, String invoiceNumber,
                       String narration, UUID propertyId, UUID unitId, UUID paymentAccountId,
                       String chequeNumber, LocalDate chequeDate, List<VoucherLineInputDTO> lines)
record VoucherLineInputDTO(UUID accountId, String description, BigDecimal amount,
                           BigDecimal vatRate, UUID propertyId, UUID unitId)
```
`VoucherType` is `PISR | BPV | RCP`. A line carries `vatRate` only — `vatAmount` is derived on the server,
so the seed must not send it. The vendor's ledger leaf is `vendor.payableAccount`, created silently by
Plan 1's `VendorService.createVendor` under group `B-01-04`; Plan 4 refuses a `PISR` for a vendor
without one.

---

## Deviations from PACT, and why

The golden fixtures are PACT's own output. Three columns intentionally differ. Everything else must match line for line; a diff anywhere else is a product bug, not a fixture bug.

| # | What differs | Ours | PACT | Authority |
|---|---|---|---|---|
| D-a | `CIL` amount | per-day rule: `rent ÷ actual term days`, monthly slice = day rate × actual days in the month, last slice absorbs the rounding | 30/360: `rent ÷ 12`, first month prorated by `days/30` | spec D10, §8.1, §8.2 — **client hard requirement** |
| D-b | `CIL` entry date | `period_end` (30 Sep 2025, 31 Oct 2025 …, and the lease end date for the final slice) | the 1st of the following month (5 Sep 2025, 1 Oct 2025 …) | spec D13 |
| D-c | PDC registration doc type | `PDR` for every contract | `IRV` on the LE BOULEVARD export, `PDR` on the GALAH 2 export — PACT is inconsistent between the two | spec §3 defines exactly one: `PDR`. The fixtures normalise `IRV` → `PDR`. |
| D-d | Row order on an account that mixes dated and post-dated rows | strict `entry_date` order | PACT's GALAH 2 "Rent Receivable" report prints a main block then a separate "List Of PDCs / Receipts" sub-block, so its 11-09-2026 PDR appears *after* the 16-09-2026 TCO | presentational only. Totals, closing balance and the row set are identical; only the interleaving and therefore the running-balance column differ. The fixture carries our order with the running balances recomputed. |

Nothing else changes. `TCO` lines, one `PDR` per cheque row, `CRT` `Dr bank / Cr PDC`, `CBR` after clearing `Dr RENT_RECEIVABLE / Cr bank`, per-account running balances and the report total all match PACT exactly.

---

## File structure

**Backend — new**
- `backend/src/test/java/com/datagami/rentaxis/golden/GoldenLedgerFixture.java` — the fixture records and the CSV loader. One responsibility: turn a CSV into typed expectations.
- `backend/src/test/java/com/datagami/rentaxis/golden/LedgerDiff.java` — the comparator. One responsibility: produce a readable, aligned diff between expected and actual rows.
- `backend/src/test/java/com/datagami/rentaxis/golden/GoldenLedgerLeBoulevardIT.java` — ISLAM MAMANOV / LE BOULEVARD replay.
- `backend/src/test/java/com/datagami/rentaxis/golden/GoldenLedgerGalah2IT.java` — ANUM ISHTIAQ / GALAH 2 replay.
- `backend/src/test/resources/golden/le-boulevard-ledger.csv`, `le-boulevard-recognition.csv`
- `backend/src/test/resources/golden/galah2-ledger.csv`, `galah2-recognition.csv`
- `backend/src/test/java/com/datagami/rentaxis/core/service/TenantFeatureMobileFinanceIT.java`

**Backend — modified**
- `backend/build.gradle` — JUnit tag filtering so `-PincludeTags=golden` works
- `backend/src/main/java/com/datagami/rentaxis/core/service/ledger/LedgerQueryService.java` — deterministic row order + per-line `particular`
- `backend/src/main/java/com/datagami/rentaxis/domain/repository/JournalLineRepository.java` — ordering in `ledgerRows`, new `counterAccountsPerLine`
- `backend/src/test/java/com/datagami/rentaxis/core/service/ledger/LedgerQueryServiceIT.java` — the `particular` assertion Plan 1 wrote for the joined form
- `backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/TenantFeature.java` — `MOBILE_FINANCE`
- `backend/src/main/java/com/datagami/rentaxis/core/service/TenantFeatureService.java` — `toLabel` case

**Scripts — modified**
- `scripts/seed_demo_tenant.py` — rewritten finance sections (chart + template + mappings, leases with lines/cheque grid, post, cheque lifecycle, recognition, vendor + PISR + BPV, new `out.json` keys)

**Web — new**
- `web/e2e/finance/accounting-v2.spec.ts` — dev suite
- `web/e2e-prod/tests/13h-accounting-v2.spec.ts` — production suite

**Web — modified**
- `web/e2e-prod/helpers/prod-client.ts` — v2 `api.*` helpers, v1 finance helpers removed

**Tutorials — new**
- `tutorials/rentaxis-adapter.md` — the walkthrough adapter this repo has never had
- narration tracks `34-post-a-tenancy-contract.txt` … `37-tenant-ledger.txt` (generated, not hand-written)

**Tutorials — modified**
- `tutorials/capability-route-map.json`, `tutorials/verify-capability-routes.mjs`, `tutorials/verify-tutorial-library.mjs`, `tutorials/tutorial-storyboards.md`, `tutorials/rentaxis-capability-matrix.md`, `tutorials/capture/record-tutorial.mjs`

**Mobile — new**
- `mobile/packages/rentaxis_core/lib/api/services/tenant_feature_service.dart`
- `mobile/packages/rentaxis_core/lib/providers/tenant_features_provider.dart`
- `mobile/packages/rentaxis_core/test/tenant_features_provider_test.dart`
- `mobile/apps/manager/test/finance_gate_test.dart`, `mobile/apps/renter/test/finance_gate_test.dart`

**Mobile — modified**
- `mobile/packages/rentaxis_core/lib/rentaxis_core.dart` (exports)
- `mobile/apps/manager/lib/screens/shell_screen.dart`, `more_screen.dart`, `dashboard_screen.dart`, `queue_screen.dart`, `router.dart`
- `mobile/apps/renter/lib/screens/shell_screen.dart`, `services_hub_screen.dart`, `home_screen.dart`, `router.dart`

---

## Task group A — Backend golden ledger tests

### Task 1: Golden fixture format, readable comparator, deterministic ledger order

The two replay tests are useless if the ledger returns rows in an arbitrary order or if a failure prints two 60-element lists. This task builds the harness and closes the two product gaps the harness exposes.

**Gap 1 — row order.** Plan 1's `JournalLineRepository.ledgerRows` does not pin a total order. PACT's ledger reads chronologically and, within a day, in the order the documents were entered. Our order must be `entry_date ASC, journal_entries.created_at ASC, journal_lines.line_no ASC`.

**Gap 2 — `particular`.** Plan 1 computes one `particular` per *entry* (every counter account joined with `" / "`). PACT prints one counter account per *row*. On a `TCO` with three lease lines that is the difference between three identical cells and the three names the client's accountant actually reads. Rule: two-line entry → the other line's account name; otherwise, if exactly one opposite-side line carries the same amount → that line's account name; otherwise fall back to Plan 1's joined list.

**Files:**
- Create: `backend/src/test/java/com/datagami/rentaxis/golden/GoldenLedgerFixture.java`
- Create: `backend/src/test/java/com/datagami/rentaxis/golden/LedgerDiff.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/domain/repository/JournalLineRepository.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/ledger/LedgerQueryService.java`
- Modify: `backend/src/test/java/com/datagami/rentaxis/core/service/ledger/LedgerQueryServiceIT.java`
- Modify: `backend/build.gradle`

**Interfaces:**
- Consumes (from plan 1): `LedgerQueryService.accountLedger/renterLedger`, `AccountLedgerDTO`, `LedgerRowDTO`, `JournalLineRepository.ledgerRows/counterAccounts`, `PostingService.post`, `AccountService.seedDefaultAccounts`.
- Produces:
  ```java
  public record GoldenRow(String account, LocalDate entryDate, String docType, String particular,
                          String narration, BigDecimal debit, BigDecimal credit, BigDecimal balance) {}
  public record GoldenRecognitionRow(LocalDate periodStart, LocalDate periodEnd, int days, BigDecimal amount) {}
  public final class GoldenLedgerFixture {
      public static List<GoldenRow> ledger(String resource);                       // "golden/le-boulevard-ledger.csv"
      public static Map<String, List<GoldenRow>> ledgerByAccount(String resource); // LinkedHashMap, file order
      public static List<GoldenRecognitionRow> recognition(String resource);
      public static BigDecimal reportTotal(List<GoldenRow> rows);                  // Σ debit, which must equal Σ credit
  }
  public final class LedgerDiff {
      public static void assertAccountMatches(String accountName, List<GoldenRow> expected, AccountLedgerDTO actual);
      public static void assertRecognitionMatches(List<GoldenRecognitionRow> expected, List<RecognitionRow> actual);
      public record RecognitionRow(LocalDate periodStart, LocalDate periodEnd, int days, BigDecimal amount) {}
  }
  ```
  `JournalLineRepository.counterAccountsPerLine(Collection<UUID> entryIds, UUID accountId) : List<LineCounterRow>`
  with `interface LineCounterRow { UUID getLineId(); String getNames(); }`.
- Tag: every class in `com.datagami.rentaxis.golden` carries `@Tag("golden")`. `./gradlew test -PincludeTags=golden` runs only them; a plain `./gradlew test` still runs everything.

- [ ] **Step 1: Write the fixture CSV for LE BOULEVARD**

The CSV is the client's export, transcribed. Columns: `account,entry_date,doc_type,particular,narration,debit,credit,balance`. `balance` is signed debit-positive (negative = PACT's `Cr`). Blank narration is an empty field. Lines starting with `#` are comments; blank lines are skipped.

Create `backend/src/test/resources/golden/le-boulevard-ledger.csv`:

```csv
# PACT General Ledger export, tenant "ISLAM MAMANOV", property LE BOULEVARD,
# contract TCO-25/251 dated 28-08-2025, term 05-09-2025 -> 04-09-2026 (365 days).
# Source: ~/Downloads/"General Ledger- tenant1.xlsx".
# Deviations from the export, all authorised by the spec (see "Deviations from PACT"):
#   D-a  CIL amounts use the per-day rule (55,000 / 365 = 150.684932/day), not PACT's 30/360.
#   D-b  CIL entry_date is period_end, not the 1st of the following month.
#   D-c  PACT wrote these PDC registrations as IRV; spec 3 names the doc type PDR.
# REPORT TOTAL: debit 248,150.00 = credit 248,150.00 (unchanged by D-a: the CIL rows still sum to 55,000).
account,entry_date,doc_type,particular,narration,debit,credit,balance
Security Deposit-Warsan,2025-08-28,TCO,Rent Receivable LE BOULEVARD,,0.00,2750.00,-2750.00
Rent Receivable LE BOULEVARD,2025-08-28,TCO,Security Deposit-Warsan,,2750.00,0.00,2750.00
Rent Receivable LE BOULEVARD,2025-08-28,TCO,Advance Rent LE BOULEVARD,,55000.00,0.00,57750.00
Rent Receivable LE BOULEVARD,2025-08-28,TCO,Admin charge-LE BOULEVERD,,300.00,0.00,58050.00
Rent Receivable LE BOULEVARD,2025-08-28,PDR,PDC Receivable LE BOULEVARD,Rent - 1st Installment,0.00,9300.00,48750.00
Rent Receivable LE BOULEVARD,2025-08-28,PDR,PDC Receivable LE BOULEVARD,Security Deposit,0.00,2750.00,46000.00
Rent Receivable LE BOULEVARD,2025-08-28,PDR,PDC Receivable LE BOULEVARD,Rent - 2nd Installment,0.00,9000.00,37000.00
Rent Receivable LE BOULEVARD,2025-08-28,PDR,PDC Receivable LE BOULEVARD,Rent - 3rd Installment,0.00,9000.00,28000.00
Rent Receivable LE BOULEVARD,2025-08-28,PDR,PDC Receivable LE BOULEVARD,Rent - 4th Installment,0.00,9000.00,19000.00
Rent Receivable LE BOULEVARD,2025-08-28,PDR,PDC Receivable LE BOULEVARD,Rent - 5th Installment,0.00,9000.00,10000.00
Rent Receivable LE BOULEVARD,2025-08-28,PDR,PDC Receivable LE BOULEVARD,Rent - 6th Installment,0.00,10000.00,0.00
Rent Receivable LE BOULEVARD,2026-05-05,CBR,Emirates Islamic - LE BOULEVARD,Rent - 5th Installment,9000.00,0.00,9000.00
Rent Receivable LE BOULEVARD,2026-07-06,CBR,Emirates Islamic - LE BOULEVARD,Rent - 6th Installment,10000.00,0.00,19000.00
Advance Rent LE BOULEVARD,2025-08-28,TCO,Rent Receivable LE BOULEVARD,,0.00,55000.00,-55000.00
Advance Rent LE BOULEVARD,2025-09-30,CIL,Rental Income LE BOULEVARD,Advance rent adjustment – Sep 2025,3917.81,0.00,-51082.19
Advance Rent LE BOULEVARD,2025-10-31,CIL,Rental Income LE BOULEVARD,Advance rent adjustment – Oct 2025,4671.23,0.00,-46410.96
Advance Rent LE BOULEVARD,2025-11-30,CIL,Rental Income LE BOULEVARD,Advance rent adjustment – Nov 2025,4520.55,0.00,-41890.41
Advance Rent LE BOULEVARD,2025-12-31,CIL,Rental Income LE BOULEVARD,Advance rent adjustment – Dec 2025,4671.23,0.00,-37219.18
Advance Rent LE BOULEVARD,2026-01-31,CIL,Rental Income LE BOULEVARD,Advance rent adjustment – Jan 2026,4671.23,0.00,-32547.95
Advance Rent LE BOULEVARD,2026-02-28,CIL,Rental Income LE BOULEVARD,Advance rent adjustment – Feb 2026,4219.18,0.00,-28328.77
Advance Rent LE BOULEVARD,2026-03-31,CIL,Rental Income LE BOULEVARD,Advance rent adjustment – Mar 2026,4671.23,0.00,-23657.54
Advance Rent LE BOULEVARD,2026-04-30,CIL,Rental Income LE BOULEVARD,Advance rent adjustment – Apr 2026,4520.55,0.00,-19136.99
Advance Rent LE BOULEVARD,2026-05-31,CIL,Rental Income LE BOULEVARD,Advance rent adjustment – May 2026,4671.23,0.00,-14465.76
Advance Rent LE BOULEVARD,2026-06-30,CIL,Rental Income LE BOULEVARD,Advance rent adjustment – Jun 2026,4520.55,0.00,-9945.21
Advance Rent LE BOULEVARD,2026-07-31,CIL,Rental Income LE BOULEVARD,Advance rent adjustment – Jul 2026,4671.23,0.00,-5273.98
Advance Rent LE BOULEVARD,2026-08-31,CIL,Rental Income LE BOULEVARD,Advance rent adjustment – Aug 2026,4671.23,0.00,-602.75
Advance Rent LE BOULEVARD,2026-09-04,CIL,Rental Income LE BOULEVARD,Advance rent adjustment – Sep 2026,602.75,0.00,0.00
Rental Income LE BOULEVARD,2025-09-30,CIL,Advance Rent LE BOULEVARD,Advance rent adjustment – Sep 2025,0.00,3917.81,-3917.81
Rental Income LE BOULEVARD,2025-10-31,CIL,Advance Rent LE BOULEVARD,Advance rent adjustment – Oct 2025,0.00,4671.23,-8589.04
Rental Income LE BOULEVARD,2025-11-30,CIL,Advance Rent LE BOULEVARD,Advance rent adjustment – Nov 2025,0.00,4520.55,-13109.59
Rental Income LE BOULEVARD,2025-12-31,CIL,Advance Rent LE BOULEVARD,Advance rent adjustment – Dec 2025,0.00,4671.23,-17780.82
Rental Income LE BOULEVARD,2026-01-31,CIL,Advance Rent LE BOULEVARD,Advance rent adjustment – Jan 2026,0.00,4671.23,-22452.05
Rental Income LE BOULEVARD,2026-02-28,CIL,Advance Rent LE BOULEVARD,Advance rent adjustment – Feb 2026,0.00,4219.18,-26671.23
Rental Income LE BOULEVARD,2026-03-31,CIL,Advance Rent LE BOULEVARD,Advance rent adjustment – Mar 2026,0.00,4671.23,-31342.46
Rental Income LE BOULEVARD,2026-04-30,CIL,Advance Rent LE BOULEVARD,Advance rent adjustment – Apr 2026,0.00,4520.55,-35863.01
Rental Income LE BOULEVARD,2026-05-31,CIL,Advance Rent LE BOULEVARD,Advance rent adjustment – May 2026,0.00,4671.23,-40534.24
Rental Income LE BOULEVARD,2026-06-30,CIL,Advance Rent LE BOULEVARD,Advance rent adjustment – Jun 2026,0.00,4520.55,-45054.79
Rental Income LE BOULEVARD,2026-07-31,CIL,Advance Rent LE BOULEVARD,Advance rent adjustment – Jul 2026,0.00,4671.23,-49726.02
Rental Income LE BOULEVARD,2026-08-31,CIL,Advance Rent LE BOULEVARD,Advance rent adjustment – Aug 2026,0.00,4671.23,-54397.25
Rental Income LE BOULEVARD,2026-09-04,CIL,Advance Rent LE BOULEVARD,Advance rent adjustment – Sep 2026,0.00,602.75,-55000.00
Emirates Islamic - LE BOULEVARD,2025-09-03,CRT,PDC Receivable LE BOULEVARD,Rent - 1st Installment,9300.00,0.00,9300.00
Emirates Islamic - LE BOULEVARD,2025-10-06,CRT,PDC Receivable LE BOULEVARD,Security Deposit,2750.00,0.00,12050.00
Emirates Islamic - LE BOULEVARD,2025-11-05,CRT,PDC Receivable LE BOULEVARD,Rent - 2nd Installment,9000.00,0.00,21050.00
Emirates Islamic - LE BOULEVARD,2026-01-05,CRT,PDC Receivable LE BOULEVARD,Rent - 3rd Installment,9000.00,0.00,30050.00
Emirates Islamic - LE BOULEVARD,2026-03-05,CRT,PDC Receivable LE BOULEVARD,Rent - 4th Installment,9000.00,0.00,39050.00
Emirates Islamic - LE BOULEVARD,2026-05-05,CRT,PDC Receivable LE BOULEVARD,Rent - 5th Installment,9000.00,0.00,48050.00
Emirates Islamic - LE BOULEVARD,2026-05-05,CBR,Rent Receivable LE BOULEVARD,Rent - 5th Installment,0.00,9000.00,39050.00
Emirates Islamic - LE BOULEVARD,2026-07-06,CRT,PDC Receivable LE BOULEVARD,Rent - 6th Installment,10000.00,0.00,49050.00
Emirates Islamic - LE BOULEVARD,2026-07-06,CBR,Rent Receivable LE BOULEVARD,Rent - 6th Installment,0.00,10000.00,39050.00
PDC Receivable LE BOULEVARD,2025-08-28,PDR,Rent Receivable LE BOULEVARD,Rent - 1st Installment,9300.00,0.00,9300.00
PDC Receivable LE BOULEVARD,2025-08-28,PDR,Rent Receivable LE BOULEVARD,Security Deposit,2750.00,0.00,12050.00
PDC Receivable LE BOULEVARD,2025-08-28,PDR,Rent Receivable LE BOULEVARD,Rent - 2nd Installment,9000.00,0.00,21050.00
PDC Receivable LE BOULEVARD,2025-08-28,PDR,Rent Receivable LE BOULEVARD,Rent - 3rd Installment,9000.00,0.00,30050.00
PDC Receivable LE BOULEVARD,2025-08-28,PDR,Rent Receivable LE BOULEVARD,Rent - 4th Installment,9000.00,0.00,39050.00
PDC Receivable LE BOULEVARD,2025-08-28,PDR,Rent Receivable LE BOULEVARD,Rent - 5th Installment,9000.00,0.00,48050.00
PDC Receivable LE BOULEVARD,2025-08-28,PDR,Rent Receivable LE BOULEVARD,Rent - 6th Installment,10000.00,0.00,58050.00
PDC Receivable LE BOULEVARD,2025-09-03,CRT,Emirates Islamic - LE BOULEVARD,Rent - 1st Installment,0.00,9300.00,48750.00
PDC Receivable LE BOULEVARD,2025-10-06,CRT,Emirates Islamic - LE BOULEVARD,Security Deposit,0.00,2750.00,46000.00
PDC Receivable LE BOULEVARD,2025-11-05,CRT,Emirates Islamic - LE BOULEVARD,Rent - 2nd Installment,0.00,9000.00,37000.00
PDC Receivable LE BOULEVARD,2026-01-05,CRT,Emirates Islamic - LE BOULEVARD,Rent - 3rd Installment,0.00,9000.00,28000.00
PDC Receivable LE BOULEVARD,2026-03-05,CRT,Emirates Islamic - LE BOULEVARD,Rent - 4th Installment,0.00,9000.00,19000.00
PDC Receivable LE BOULEVARD,2026-05-05,CRT,Emirates Islamic - LE BOULEVARD,Rent - 5th Installment,0.00,9000.00,10000.00
PDC Receivable LE BOULEVARD,2026-07-06,CRT,Emirates Islamic - LE BOULEVARD,Rent - 6th Installment,0.00,10000.00,0.00
Admin charge-LE BOULEVERD,2025-08-28,TCO,Rent Receivable LE BOULEVARD,,0.00,300.00,-300.00
```

(The misspelling `BOULEVERD` on the admin-fee account is PACT's; keep it verbatim so the mapping test is real.)

- [ ] **Step 2: Write the recognition fixture for LE BOULEVARD**

`backend/src/test/resources/golden/le-boulevard-recognition.csv` — this is the per-day table the spec's §8.2 rule produces for 55,000 over 05-09-2025 → 04-09-2026 (365 days, day rate 150.684932):

```csv
# 55,000.00 over 365 days (05-09-2025 .. 04-09-2026 inclusive); day rate 150.684932.
# The last slice absorbs the rounding: 55,000.00 - 54,397.25 = 602.75.
period_start,period_end,days,amount
2025-09-05,2025-09-30,26,3917.81
2025-10-01,2025-10-31,31,4671.23
2025-11-01,2025-11-30,30,4520.55
2025-12-01,2025-12-31,31,4671.23
2026-01-01,2026-01-31,31,4671.23
2026-02-01,2026-02-28,28,4219.18
2026-03-01,2026-03-31,31,4671.23
2026-04-01,2026-04-30,30,4520.55
2026-05-01,2026-05-31,31,4671.23
2026-06-01,2026-06-30,30,4520.55
2026-07-01,2026-07-31,31,4671.23
2026-08-01,2026-08-31,31,4671.23
2026-09-01,2026-09-04,4,602.75
```

- [ ] **Step 3: Write the two GALAH 2 fixtures**

`backend/src/test/resources/golden/galah2-ledger.csv`:

```csv
# PACT General Ledger export, tenant "ANUM ISHTIAQ ISHTIAQ AHMED KHAN", property GALAH 2,
# contract TCO-26/1629 dated 16-09-2026, term 24-09-2026 -> 23-09-2027 (365 days).
# Source: ~/Downloads/"General Ledger tenant 2.xlsx".
# GALAH 2 deliberately shares the generic accounts (spec 5.2): Rent Receivable 105590,
# Advance Rent 125620, PDC Receivable EIB 125636, Rental Income A/c 145661, Admin Fee 145663.
# Deviations: D-a per-day CIL, D-b CIL entry_date = period_end,
#   D-d "Rent Receivable" rows are in strict entry_date order; PACT prints the post-dated
#        receipts in a separate sub-block after the dated rows, so its running balance differs
#        on those seven rows. Totals, row set and closing balance are identical.
# PACT prints the admin-fee income account with a trailing space ("Admin Fee "); trimmed here.
# REPORT TOTAL: debit 157,000.00 = credit 157,000.00.
account,entry_date,doc_type,particular,narration,debit,credit,balance
Rent Receivable,2026-09-11,PDR,PDC Receivable EIB,Admin Fees,0.00,2000.00,-2000.00
Rent Receivable,2026-09-16,TCO,Advance Rent,,51000.00,0.00,49000.00
Rent Receivable,2026-09-16,TCO,Admin Fee,,2000.00,0.00,51000.00
Rent Receivable,2026-10-02,PDR,PDC Receivable EIB,Rent - 1st Installment,0.00,12750.00,38250.00
Rent Receivable,2027-01-02,PDR,PDC Receivable EIB,Rent - 2nd Installment,0.00,12750.00,25500.00
Rent Receivable,2027-04-02,PDR,PDC Receivable EIB,Rent - 3rd Installment,0.00,12750.00,12750.00
Rent Receivable,2027-07-02,PDR,PDC Receivable EIB,Rent - 4th Installment,0.00,12750.00,0.00
Advance Rent,2026-09-16,TCO,Rent Receivable,,0.00,51000.00,-51000.00
Advance Rent,2026-09-30,CIL,Rental Income A/c,Advance rent adjustment – Sep 2026,978.08,0.00,-50021.92
Advance Rent,2026-10-31,CIL,Rental Income A/c,Advance rent adjustment – Oct 2026,4331.51,0.00,-45690.41
Advance Rent,2026-11-30,CIL,Rental Income A/c,Advance rent adjustment – Nov 2026,4191.78,0.00,-41498.63
Advance Rent,2026-12-31,CIL,Rental Income A/c,Advance rent adjustment – Dec 2026,4331.51,0.00,-37167.12
Advance Rent,2027-01-31,CIL,Rental Income A/c,Advance rent adjustment – Jan 2027,4331.51,0.00,-32835.61
Advance Rent,2027-02-28,CIL,Rental Income A/c,Advance rent adjustment – Feb 2027,3912.33,0.00,-28923.28
Advance Rent,2027-03-31,CIL,Rental Income A/c,Advance rent adjustment – Mar 2027,4331.51,0.00,-24591.77
Advance Rent,2027-04-30,CIL,Rental Income A/c,Advance rent adjustment – Apr 2027,4191.78,0.00,-20399.99
Advance Rent,2027-05-31,CIL,Rental Income A/c,Advance rent adjustment – May 2027,4331.51,0.00,-16068.48
Advance Rent,2027-06-30,CIL,Rental Income A/c,Advance rent adjustment – Jun 2027,4191.78,0.00,-11876.70
Advance Rent,2027-07-31,CIL,Rental Income A/c,Advance rent adjustment – Jul 2027,4331.51,0.00,-7545.19
Advance Rent,2027-08-31,CIL,Rental Income A/c,Advance rent adjustment – Aug 2027,4331.51,0.00,-3213.68
Advance Rent,2027-09-23,CIL,Rental Income A/c,Advance rent adjustment – Sep 2027,3213.68,0.00,0.00
PDC Receivable EIB,2026-09-11,PDR,Rent Receivable,Admin Fees,2000.00,0.00,2000.00
PDC Receivable EIB,2026-10-02,PDR,Rent Receivable,Rent - 1st Installment,12750.00,0.00,14750.00
PDC Receivable EIB,2027-01-02,PDR,Rent Receivable,Rent - 2nd Installment,12750.00,0.00,27500.00
PDC Receivable EIB,2027-04-02,PDR,Rent Receivable,Rent - 3rd Installment,12750.00,0.00,40250.00
PDC Receivable EIB,2027-07-02,PDR,Rent Receivable,Rent - 4th Installment,12750.00,0.00,53000.00
Rental Income A/c,2026-09-30,CIL,Advance Rent,Advance rent adjustment – Sep 2026,0.00,978.08,-978.08
Rental Income A/c,2026-10-31,CIL,Advance Rent,Advance rent adjustment – Oct 2026,0.00,4331.51,-5309.59
Rental Income A/c,2026-11-30,CIL,Advance Rent,Advance rent adjustment – Nov 2026,0.00,4191.78,-9501.37
Rental Income A/c,2026-12-31,CIL,Advance Rent,Advance rent adjustment – Dec 2026,0.00,4331.51,-13832.88
Rental Income A/c,2027-01-31,CIL,Advance Rent,Advance rent adjustment – Jan 2027,0.00,4331.51,-18164.39
Rental Income A/c,2027-02-28,CIL,Advance Rent,Advance rent adjustment – Feb 2027,0.00,3912.33,-22076.72
Rental Income A/c,2027-03-31,CIL,Advance Rent,Advance rent adjustment – Mar 2027,0.00,4331.51,-26408.23
Rental Income A/c,2027-04-30,CIL,Advance Rent,Advance rent adjustment – Apr 2027,0.00,4191.78,-30600.01
Rental Income A/c,2027-05-31,CIL,Advance Rent,Advance rent adjustment – May 2027,0.00,4331.51,-34931.52
Rental Income A/c,2027-06-30,CIL,Advance Rent,Advance rent adjustment – Jun 2027,0.00,4191.78,-39123.30
Rental Income A/c,2027-07-31,CIL,Advance Rent,Advance rent adjustment – Jul 2027,0.00,4331.51,-43454.81
Rental Income A/c,2027-08-31,CIL,Advance Rent,Advance rent adjustment – Aug 2027,0.00,4331.51,-47786.32
Rental Income A/c,2027-09-23,CIL,Advance Rent,Advance rent adjustment – Sep 2027,0.00,3213.68,-51000.00
Admin Fee,2026-09-16,TCO,Rent Receivable,,0.00,2000.00,-2000.00
```

`backend/src/test/resources/golden/galah2-recognition.csv` — this is the spec's own §8.2 reference fixture:

```csv
# 51,000.00 over 365 days (24-09-2026 .. 23-09-2027 inclusive); day rate 139.726027.
# Matches the table printed in spec section 8.2 verbatim.
period_start,period_end,days,amount
2026-09-24,2026-09-30,7,978.08
2026-10-01,2026-10-31,31,4331.51
2026-11-01,2026-11-30,30,4191.78
2026-12-01,2026-12-31,31,4331.51
2027-01-01,2027-01-31,31,4331.51
2027-02-01,2027-02-28,28,3912.33
2027-03-01,2027-03-31,31,4331.51
2027-04-01,2027-04-30,30,4191.78
2027-05-01,2027-05-31,31,4331.51
2027-06-01,2027-06-30,30,4191.78
2027-07-01,2027-07-31,31,4331.51
2027-08-01,2027-08-31,31,4331.51
2027-09-01,2027-09-23,23,3213.68
```

- [ ] **Step 4: Write the fixture loader**

`backend/src/test/java/com/datagami/rentaxis/golden/GoldenLedgerFixture.java`:

```java
package com.datagami.rentaxis.golden;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads a PACT General Ledger export transcribed as CSV. The fixtures are the
 * client's own numbers; the loader deliberately does nothing clever, so a
 * failure always points at the product rather than at the parser.
 *
 * Fields never contain a comma or a quote (account names, doc types, narrations
 * and decimals only), so a plain split is correct and a CSV library is not.
 */
public final class GoldenLedgerFixture {

    private GoldenLedgerFixture() {}

    public record GoldenRow(String account, LocalDate entryDate, String docType, String particular,
                            String narration, BigDecimal debit, BigDecimal credit, BigDecimal balance) {}

    public record GoldenRecognitionRow(LocalDate periodStart, LocalDate periodEnd, int days, BigDecimal amount) {}

    public static List<GoldenRow> ledger(String resource) {
        List<GoldenRow> rows = new ArrayList<>();
        for (String line : dataLines(resource)) {
            String[] c = split(line, 8);
            rows.add(new GoldenRow(c[0], LocalDate.parse(c[1]), c[2], c[3], c[4],
                    new BigDecimal(c[5]), new BigDecimal(c[6]), new BigDecimal(c[7])));
        }
        return List.copyOf(rows);
    }

    /** Account name -> its rows, in the order the file lists them (which is the order we assert). */
    public static Map<String, List<GoldenRow>> ledgerByAccount(String resource) {
        Map<String, List<GoldenRow>> byAccount = new LinkedHashMap<>();
        for (GoldenRow r : ledger(resource)) {
            byAccount.computeIfAbsent(r.account(), k -> new ArrayList<>()).add(r);
        }
        return byAccount;
    }

    public static List<GoldenRecognitionRow> recognition(String resource) {
        List<GoldenRecognitionRow> rows = new ArrayList<>();
        for (String line : dataLines(resource)) {
            String[] c = split(line, 4);
            rows.add(new GoldenRecognitionRow(LocalDate.parse(c[0]), LocalDate.parse(c[1]),
                    Integer.parseInt(c[2]), new BigDecimal(c[3])));
        }
        return List.copyOf(rows);
    }

    /** PACT's "REPORT TOTAL" debit column. Sum of credits must equal it. */
    public static BigDecimal reportTotal(List<GoldenRow> rows) {
        return rows.stream().map(GoldenRow::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static List<String> dataLines(String resource) {
        InputStream in = GoldenLedgerFixture.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) throw new IllegalArgumentException("Golden fixture not on the test classpath: " + resource);
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            boolean headerSeen = false;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                if (!headerSeen) { headerSeen = true; continue; }   // the column header row
                out.add(line);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static String[] split(String line, int expected) {
        String[] c = line.split(",", -1);
        if (c.length != expected) {
            throw new IllegalStateException("Expected " + expected + " fields, got " + c.length + ": " + line);
        }
        return c;
    }
}
```

- [ ] **Step 5: Write the comparator**

`backend/src/test/java/com/datagami/rentaxis/golden/LedgerDiff.java`. A failure prints both ledgers side by side with the first differing row marked, because a 60-row `assertThat(list).isEqualTo(list)` failure is unreadable.

```java
package com.datagami.rentaxis.golden;

import com.datagami.rentaxis.api.dto.ledger.AccountLedgerDTO;
import com.datagami.rentaxis.api.dto.ledger.LedgerRowDTO;
import com.datagami.rentaxis.golden.GoldenLedgerFixture.GoldenRecognitionRow;
import com.datagami.rentaxis.golden.GoldenLedgerFixture.GoldenRow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.fail;

public final class LedgerDiff {

    private LedgerDiff() {}

    public record RecognitionRow(LocalDate periodStart, LocalDate periodEnd, int days, BigDecimal amount) {}

    public static void assertAccountMatches(String accountName, List<GoldenRow> expected, AccountLedgerDTO actual) {
        List<LedgerRowDTO> got = actual.rows();
        int n = Math.max(expected.size(), got.size());
        int firstBad = -1;
        for (int i = 0; i < n; i++) {
            if (i >= expected.size() || i >= got.size() || !same(expected.get(i), got.get(i))) { firstBad = i; break; }
        }
        if (firstBad < 0) return;

        StringBuilder sb = new StringBuilder();
        sb.append("Ledger mismatch on account \"").append(accountName).append("\" at row ")
          .append(firstBad + 1).append(" of ").append(n).append("\n\n")
          .append(String.format("%-4s %-46s | %-46s%n", "", "PACT (expected)", "RentAxis (actual)"));
        for (int i = 0; i < n; i++) {
            String mark = (i == firstBad) ? " >> " : "    ";
            sb.append(String.format("%-4s %-46s | %-46s%n", mark, fmt(i < expected.size() ? expected.get(i) : null),
                    fmt(i < got.size() ? got.get(i) : null)));
        }
        fail(sb.toString());
    }

    public static void assertRecognitionMatches(List<GoldenRecognitionRow> expected, List<RecognitionRow> got) {
        int n = Math.max(expected.size(), got.size());
        int firstBad = -1;
        for (int i = 0; i < n; i++) {
            if (i >= expected.size() || i >= got.size()) { firstBad = i; break; }
            GoldenRecognitionRow e = expected.get(i);
            RecognitionRow a = got.get(i);
            if (!e.periodStart().equals(a.periodStart()) || !e.periodEnd().equals(a.periodEnd())
                    || e.days() != a.days() || e.amount().compareTo(a.amount()) != 0) { firstBad = i; break; }
        }
        if (firstBad < 0) return;

        StringBuilder sb = new StringBuilder("Recognition schedule mismatch at row ")
                .append(firstBad + 1).append(" of ").append(n).append("\n\n")
                .append(String.format("%-4s %-40s | %-40s%n", "", "per-day fixture", "RentAxis"));
        for (int i = 0; i < n; i++) {
            String mark = (i == firstBad) ? " >> " : "    ";
            GoldenRecognitionRow e = i < expected.size() ? expected.get(i) : null;
            RecognitionRow a = i < got.size() ? got.get(i) : null;
            sb.append(String.format("%-4s %-40s | %-40s%n", mark,
                    e == null ? "-" : e.periodStart() + ".." + e.periodEnd() + "  " + e.days() + "d  " + e.amount(),
                    a == null ? "-" : a.periodStart() + ".." + a.periodEnd() + "  " + a.days() + "d  " + a.amount()));
        }
        fail(sb.toString());
    }

    private static boolean same(GoldenRow e, LedgerRowDTO a) {
        return e.entryDate().equals(a.entryDate())
                && e.docType().equals(a.docType())
                && e.particular().equals(nullToEmpty(a.particular()))
                && e.narration().equals(nullToEmpty(a.narration()))
                && e.debit().compareTo(a.debit()) == 0
                && e.credit().compareTo(a.credit()) == 0
                && e.balance().compareTo(a.balance()) == 0;
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    private static String fmt(GoldenRow r) {
        return r == null ? "-" : String.format("%s %-4s %-26s %10s %10s %12s",
                r.entryDate(), r.docType(), trunc(r.particular()), r.debit(), r.credit(), r.balance());
    }

    private static String fmt(LedgerRowDTO r) {
        return r == null ? "-" : String.format("%s %-4s %-26s %10s %10s %12s",
                r.entryDate(), r.docType(), trunc(nullToEmpty(r.particular())), r.debit(), r.credit(), r.balance());
    }

    private static String trunc(String s) { return s.length() <= 26 ? s : s.substring(0, 25) + "…"; }
}
```

- [ ] **Step 6: Write the failing test for the two product gaps**

Append to `backend/src/test/java/com/datagami/rentaxis/core/service/ledger/LedgerQueryServiceIT.java` (the class already has the container, the tenant, the property and the seeded accounts from Plan 1):

```java
    /**
     * PACT prints one counter account per ledger row, not the whole other side of the
     * journal. The golden replays diff against PACT's column, so a TCO with three lease
     * lines must show "Advance Rent", "Security Deposit" and "Admin Fee" on three rows,
     * paired by amount — not the same joined string three times.
     */
    @Test
    void particularNamesTheMatchingCounterLineOnAMultiLineEntry() {
        UUID rentRecv = resolver.resolve(AccountRole.RENT_RECEIVABLE, propertyId).getId();
        posting.post(new PostingRequest(JournalDocType.TCO, LocalDate.of(2026, 9, 16), "Contract",
                new Dimensions(propertyId, null, leaseId, renterId, null),
                JournalSourceType.LEASE, leaseId, null, List.of(
                        dr(AccountRole.RENT_RECEIVABLE, new BigDecimal("51000.00")),
                        cr(AccountRole.ADVANCE_RENT,    new BigDecimal("51000.00")),
                        dr(AccountRole.RENT_RECEIVABLE, new BigDecimal("2000.00")),
                        cr(AccountRole.ADMIN_FEE,       new BigDecimal("2000.00")))));

        List<LedgerRowDTO> rows = ledger.accountLedger(rentRecv,
                new LedgerQueryService.LedgerFilter(null, null, null, null, null, null)).rows();

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).particular()).isEqualTo(resolver.resolve(AccountRole.ADVANCE_RENT, propertyId).getName());
        assertThat(rows.get(1).particular()).isEqualTo(resolver.resolve(AccountRole.ADMIN_FEE, propertyId).getName());
    }

    /**
     * Two entries on the same date must come back in the order they were posted, so a
     * cheque that cleared and then bounced on 05-05-2026 reads CRT then CBR — the order
     * the client's ledger prints and the only order whose running balance makes sense.
     */
    @Test
    void rowsOnTheSameDateAreOrderedByPostingTime() {
        UUID bank = resolver.resolve(AccountRole.BANK, propertyId).getId();
        LocalDate d = LocalDate.of(2026, 5, 5);
        posting.post(new PostingRequest(JournalDocType.CRT, d, "Cleared", Dimensions.ofProperty(propertyId),
                JournalSourceType.CHEQUE, UUID.randomUUID(), null,
                List.of(dr(AccountRole.BANK, new BigDecimal("9000.00")),
                        cr(AccountRole.PDC_RECEIVABLE, new BigDecimal("9000.00")))));
        posting.post(new PostingRequest(JournalDocType.CBR, d, "Returned", Dimensions.ofProperty(propertyId),
                JournalSourceType.CHEQUE, UUID.randomUUID(), null,
                List.of(dr(AccountRole.RENT_RECEIVABLE, new BigDecimal("9000.00")),
                        cr(AccountRole.BANK, new BigDecimal("9000.00")))));

        List<LedgerRowDTO> rows = ledger.accountLedger(bank,
                new LedgerQueryService.LedgerFilter(null, null, null, null, null, null)).rows();

        assertThat(rows).extracting(LedgerRowDTO::docType).containsExactly("CRT", "CBR");
        assertThat(rows.get(1).balance()).isEqualByComparingTo("0.00");
    }
```

Also change the existing Plan 1 assertion, which asserted the joined form:

```java
        // was: assertThat(l.rows().get(0).particular()).contains("Advance Rent - L'Olivier")
        //          .contains("Security Deposit L'Olivier").contains("Admin Fee - L'Olivier");
        assertThat(l.rows()).extracting(LedgerRowDTO::particular)
                .containsExactly("Advance Rent - L'Olivier", "Security Deposit L'Olivier", "Admin Fee - L'Olivier",
                                 "PDC Receivable L'Olivier");
```

- [ ] **Step 7: Run the test to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.ledger.LedgerQueryServiceIT'`
Expected: FAIL — `particularNamesTheMatchingCounterLineOnAMultiLineEntry` gets the joined `"Advance Rent - … / Admin Fee - …"` on both rows, and `rowsOnTheSameDateAreOrderedByPostingTime` fails intermittently because nothing pins the same-date order.

- [ ] **Step 8: Pin the row order in the repository**

In `backend/src/main/java/com/datagami/rentaxis/domain/repository/JournalLineRepository.java`, the `ledgerRows` native query's `order by` becomes:

```sql
        order by e.entry_date, e.created_at, l.line_no
```

and add the per-line counter-account query next to `counterAccounts`:

```java
    interface LineCounterRow { UUID getLineId(); String getNames(); }

    /**
     * The counter account(s) for each line of the given entries, from the point of view of
     * :accountId. A two-line entry has exactly one; a multi-line entry pairs by amount when
     * that is unambiguous (the TCO case: Dr receivable 51,000 pairs with Cr advance rent
     * 51,000) and otherwise falls back to every account on the other side, joined.
     */
    @Query(value = """
        with mine as (
            select l.id, l.journal_entry_id, l.debit, l.credit
            from journal_lines l
            where l.journal_entry_id in (:entryIds) and l.account_id = :accountId
        ),
        others as (
            select l.journal_entry_id, a.name, l.debit, l.credit
            from journal_lines l join accounts a on a.id = l.account_id
            where l.journal_entry_id in (:entryIds) and l.account_id <> :accountId
        )
        select m.id as lineId,
               coalesce(
                 (select o.name from others o
                   where o.journal_entry_id = m.journal_entry_id
                     and ((m.debit > 0 and o.credit = m.debit) or (m.credit > 0 and o.debit = m.credit))
                   group by o.name
                   having count(*) = 1
                   limit 1),
                 (select string_agg(distinct o.name, ' / ' order by o.name) from others o
                   where o.journal_entry_id = m.journal_entry_id),
                 ''
               ) as names
        from mine m
        """, nativeQuery = true)
    List<LineCounterRow> counterAccountsPerLine(Collection<UUID> entryIds, UUID accountId);
```

The `having count(*) = 1` is what makes "pair by amount" safe: if two credit lines both carry 9,000 the sub-select returns nothing and the joined fallback kicks in, so an ambiguous entry is never mislabelled.

- [ ] **Step 9: Use it in `LedgerQueryService`**

In `accountLedger`, replace the per-entry map with a per-line one. `LineRow` gains `getLineId()` (add `l.id as lineId` to the `ledgerRows` select list):

```java
        Map<UUID, String> particulars = raw.isEmpty() ? Map.of() : lines.counterAccountsPerLine(
                        raw.stream().map(LineRow::getEntryId).collect(Collectors.toSet()), accountId)
                .stream().collect(Collectors.toMap(LineCounterRow::getLineId, LineCounterRow::getNames));
        ...
            rows.add(new LedgerRowDTO(r.getEntryId(), r.getEntryNumber(), r.getEntryDate(), r.getDocType(),
                    particulars.getOrDefault(r.getLineId(), ""),
                    r.getLineNarration() != null ? r.getLineNarration() : r.getEntryNarration(),
                    r.getDebit(), r.getCredit(), running, r.getPropertyId(), r.getUnitId(), r.getLeaseId(),
                    r.getRenterId(), r.getChequeId()));
```

Delete the now-unused `counterAccounts` query and its `CounterRow` projection.

- [ ] **Step 10: Run the test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.ledger.LedgerQueryServiceIT'`
Expected: PASS, all tests in the class.

- [ ] **Step 11: Wire the `golden` JUnit tag into Gradle**

Replace the `tasks.named('test')` block in `backend/build.gradle`:

```groovy
tasks.named('test') {
	useJUnitPlatform {
		// Golden ledger replays are slow (two full contracts through Testcontainers) but
		// they are the client-acceptance gate, so they stay in the default run. The
		// properties exist so CI can split them out: -PincludeTags=golden / -PexcludeTags=golden.
		if (project.hasProperty('includeTags')) {
			includeTags((project.property('includeTags') as String).split(',') as String[])
		}
		if (project.hasProperty('excludeTags')) {
			excludeTags((project.property('excludeTags') as String).split(',') as String[])
		}
	}
	// Gradle defaults test workers to 512m, which the full suite now exhausts:
	// several @SpringBootTest contexts plus the Testcontainers integration tests
	// (GatePassScanConcurrencyIT, OtpLoginRollbackIT) exceed it and the worker
	// dies with OutOfMemoryError part-way through rather than reporting failures.
	maxHeapSize = '2g'
}
```

- [ ] **Step 12: Prove the tag filter works**

Run: `cd backend && ./gradlew test -PincludeTags=golden`
Expected: PASS with **0 tests executed** (no `@Tag("golden")` class exists yet). If it errors instead, the `includeTags` wiring is wrong — fix it now, because Task 2 depends on it.

- [ ] **Step 13: Commit**

```bash
git add backend/build.gradle backend/src/main/java/com/datagami/rentaxis/domain/repository/JournalLineRepository.java \
        backend/src/main/java/com/datagami/rentaxis/core/service/ledger/LedgerQueryService.java \
        backend/src/test/java/com/datagami/rentaxis/core/service/ledger/LedgerQueryServiceIT.java \
        backend/src/test/java/com/datagami/rentaxis/golden backend/src/test/resources/golden
git commit -m "test(ledger): golden fixture harness, per-line counter account, deterministic row order

The client's General Ledger prints one counter account per row and reads
chronologically. Ours joined every counter account onto every row of a
multi-line contract and left same-day rows unordered, so a line-by-line diff
against their export was not possible. Pairs by amount when unambiguous,
orders by (entry_date, created_at, line_no).

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: `GoldenLedgerLeBoulevardIT` — replay ISLAM MAMANOV / LE BOULEVARD

The harder of the two contracts: property-scoped named accounts (four of which do **not** match the template pattern, so they exercise manual remapping), seven post-dated cheques all registered on the contract date, seven clearances spread over ten months, and two cheques that bounce *after* clearing.

**Files:**
- Create: `backend/src/test/java/com/datagami/rentaxis/golden/GoldenLedgerLeBoulevardIT.java`
- Test: itself.

**Interfaces:**
- Consumes (from plan 1): `AccountService.seedDefaultAccounts/createLeaf/getAccountByCode`, `PropertyAccountService.seedDefaultTemplateAndDefaults/generateMissing/setMapping`, `AccountResolver.resolve`, `TenantFiscalSettingsService.setBooksStartDate/lockThrough`, `LedgerQueryService.renterLedger/trialBalance`, `AccountLedgerDTO`, `TrialBalanceRowDTO`, `AccountRole`.
- Consumes (from plan 2) — the service classes behind `POST /api/v1/leases`, `POST /api/v1/leases/{id}/post` and `POST /api/v1/cheques/{id}/{deposit,clear,bounce,replace}`; each request record mirrors the JSON body field for field:
  ```java
  record LeaseLineRequest(String chargeTypeCode, BigDecimal grossAmount, BigDecimal discountAmount, String narration)
  record ChequeRowRequest(int seqNo, LocalDate postingDate, String chequeNumber, LocalDate chequeDate,
                          String payeeBank, BigDecimal amount, String narration, ChequeMode mode)
  record CreateLeaseRequest(UUID unitId, UUID renterId, LocalDate contractDate, LocalDate startDate,
                            LocalDate endDate, String contractNumber, Integer gracePeriodDays,
                            List<LeaseLineRequest> lines, List<ChequeRowRequest> cheques)
  Lease         LeaseService.createDraft(CreateLeaseRequest r);
  Lease         LeaseService.post(UUID leaseId);
  List<Cheque>  ChequeService.findByLease(UUID leaseId);            // ordered by seqNo
  Cheque        ChequeService.deposit(UUID chequeId, LocalDate depositDate, UUID bankAccountId);
  Cheque        ChequeService.clear(UUID chequeId, LocalDate clearedDate);
  Cheque        ChequeService.bounce(UUID chequeId, LocalDate bouncedDate, ChequeFailureReason reason);
  List<Cheque>  ChequeService.replace(UUID chequeId, List<ChequeRowRequest> replacements);
  ```
- Consumes (from plan 3): `RecognitionService.runTo(LocalDate to) : RecognitionRunResult` (`record RecognitionRunResult(int posted, int skipped)`) and `RecognitionService.scheduleFor(UUID leaseId) : List<RecognitionEntry>` with `getPeriodStart()/getPeriodEnd()/getDays()/getAmount()/getStatus()`.
- Consumes (from plan 4): nothing.
- Produces: nothing other tasks import. It is a gate.

- [ ] **Step 1: Write the replay test**

`backend/src/test/java/com/datagami/rentaxis/golden/GoldenLedgerLeBoulevardIT.java`:

```java
package com.datagami.rentaxis.golden;

import com.datagami.rentaxis.api.dto.ledger.AccountLedgerDTO;
import com.datagami.rentaxis.api.dto.ledger.TrialBalanceRowDTO;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.UnitService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.service.lease.LeaseService;
import com.datagami.rentaxis.core.service.lease.LeaseService.ChequeRowRequest;
import com.datagami.rentaxis.core.service.lease.LeaseService.CreateLeaseRequest;
import com.datagami.rentaxis.core.service.lease.LeaseService.LeaseLineRequest;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.recognition.RecognitionService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.golden.GoldenLedgerFixture.GoldenRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Replays the client's own contract TCO-25/251 (ISLAM MAMANOV, LE BOULEVARD, 28-08-2025)
 * through the v2 API and diffs the result against their PACT General Ledger export,
 * line by line.
 *
 * <p>This is the acceptance gate for the PACT migration: if it passes, their accountant
 * can open our Tenant Ledger next to their old one and read the same document numbers,
 * the same counter accounts and the same running balances. Three columns differ on
 * purpose and only three — see "Deviations from PACT" in the plan and the header of
 * {@code golden/le-boulevard-ledger.csv}.
 *
 * <p>The contract is worth replaying specifically because of its awkward parts: four of
 * its seven ledger accounts are named nothing like the property-account template
 * ("Security Deposit-Warsan", "Admin charge-LE BOULEVERD"), so it proves manual
 * remapping (spec D2); the first cheque folds the admin fee into the rent installment;
 * and two cheques bounce <em>after</em> clearing, which is the one cheque transition
 * whose journal credits the bank rather than PDC receivable (spec 7.2).
 */
@SpringBootTest
@Testcontainers
@Tag("golden")
class GoldenLedgerLeBoulevardIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String LEDGER = "golden/le-boulevard-ledger.csv";
    private static final String RECOGNITION = "golden/le-boulevard-recognition.csv";

    private static final LocalDate CONTRACT_DATE = LocalDate.of(2025, 8, 28);
    private static final LocalDate TERM_START = LocalDate.of(2025, 9, 5);
    private static final LocalDate TERM_END = LocalDate.of(2026, 9, 4);
    private static final LocalDate CUT_OFF = LocalDate.of(2026, 9, 30);

    @Autowired AccountService accounts;
    @Autowired PropertyAccountService propertyAccounts;
    @Autowired AccountResolver resolver;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired PropertyService properties;
    @Autowired UnitService units;
    @Autowired LeaseService leases;
    @Autowired ChequeService cheques;
    @Autowired RecognitionService recognition;
    @Autowired LedgerQueryService ledger;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired RenterRepository renterRepo;

    UUID propertyId;
    UUID renterId;
    UUID leaseId;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("Al Ashram Golden " + UUID.randomUUID());
        TenantContextHolder.setTenantId(orgRepo.save(org).getId());

        accounts.seedDefaultAccounts();
        propertyAccounts.seedDefaultTemplateAndDefaults();
        // The books open before the contract date, otherwise the period lock rejects the TCO.
        fiscal.setBooksStartDate(LocalDate.of(2025, 8, 1));
        fiscal.lockThrough(LocalDate.of(2025, 7, 31));

        Property p = new Property();
        p.setNameEn("LE BOULEVARD");
        p.setEmirate(Emirate.DUBAI);
        propertyId = properties.createProperty(p).getId();

        // Creating the property generates one leaf per enabled template row (spec 5.4).
        assertThat(propertyAccounts.getMappings(propertyId)).isNotEmpty();

        // Four of PACT's accounts are named nothing like the template. Create them under the
        // same parent groups and swap the mapping, which is exactly what the Accounts tab does.
        remap(AccountRole.RENT_RECEIVABLE, "Rent Receivable LE BOULEVARD", "A-02-01");
        remap(AccountRole.ADVANCE_RENT,    "Advance Rent LE BOULEVARD",    "B");
        remap(AccountRole.SECURITY_DEPOSIT, "Security Deposit-Warsan",     "B-01-02");
        remap(AccountRole.ADMIN_FEE,       "Admin charge-LE BOULEVERD",    "C-01-01");

        // The remaining three already match PACT because the template patterns match.
        assertThat(resolver.resolve(AccountRole.RENTAL_INCOME, propertyId).getName())
                .isEqualTo("Rental Income LE BOULEVARD");
        assertThat(resolver.resolve(AccountRole.PDC_RECEIVABLE, propertyId).getName())
                .isEqualTo("PDC Receivable LE BOULEVARD");
        assertThat(resolver.resolve(AccountRole.BANK, propertyId).getName())
                .isEqualTo("Emirates Islamic - LE BOULEVARD");

        Unit u = new Unit();
        u.setProperty(properties.getPropertyById(propertyId));
        u.setUnitNumber("1206");
        u.setStatus(UnitStatus.VACANT);
        UUID unitId = units.createUnit(u).getId();

        Renter r = new Renter();
        r.setNameEn("ISLAM MAMANOV");
        r.setEmail("islam.mamanov@example.invalid");
        renterId = renterRepo.save(r).getId();

        leaseId = leases.createDraft(new CreateLeaseRequest(
                unitId, renterId, CONTRACT_DATE, TERM_START, TERM_END, "LEB/251", 0,
                // Line order is PACT's TCO row order on the Rent Receivable account.
                List.of(new LeaseLineRequest("SECURITY_DEPOSIT", new BigDecimal("2750.00"), BigDecimal.ZERO, ""),
                        new LeaseLineRequest("RENT",             new BigDecimal("55000.00"), BigDecimal.ZERO, ""),
                        new LeaseLineRequest("ADMIN_FEE",        new BigDecimal("300.00"), BigDecimal.ZERO, "")),
                // Seven post-dated cheques, all registered on the contract date. The first folds
                // the 300 admin fee into the 9,000 first rent installment, exactly as PACT's does.
                List.of(cheque(1, "000101", LocalDate.of(2025, 9, 3),  "9300.00",  "Rent - 1st Installment"),
                        cheque(2, "000102", LocalDate.of(2025, 10, 6), "2750.00",  "Security Deposit"),
                        cheque(3, "000103", LocalDate.of(2025, 11, 5), "9000.00",  "Rent - 2nd Installment"),
                        cheque(4, "000104", LocalDate.of(2026, 1, 5),  "9000.00",  "Rent - 3rd Installment"),
                        cheque(5, "000105", LocalDate.of(2026, 3, 5),  "9000.00",  "Rent - 4th Installment"),
                        cheque(6, "000106", LocalDate.of(2026, 5, 5),  "9000.00",  "Rent - 5th Installment"),
                        cheque(7, "000107", LocalDate.of(2026, 7, 6),  "10000.00", "Rent - 6th Installment"))))
                .getId();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private void remap(AccountRole role, String pactName, String parentCode) {
        Account leaf = accounts.createLeaf(pactName, accounts.getAccountByCode(parentCode), propertyId);
        propertyAccounts.setMapping(propertyId, role, leaf.getId());
        assertThat(resolver.resolve(role, propertyId).getName()).isEqualTo(pactName);
    }

    private static ChequeRowRequest cheque(int seq, String number, LocalDate date, String amount, String narration) {
        return new ChequeRowRequest(seq, CONTRACT_DATE, number, date, "Emirates Islamic",
                new BigDecimal(amount), narration, ChequeMode.PDC);
    }

    /** Drives the contract through its whole life, in the order the client's office did. */
    private void replay() {
        leases.post(leaseId);

        List<Cheque> rows = cheques.findByLease(leaseId);
        assertThat(rows).hasSize(7);

        // Cheques 1-5 bank and clear cleanly, on their maturity dates.
        for (int i = 0; i < 5; i++) {
            LocalDate maturity = rows.get(i).getChequeDate();
            cheques.deposit(rows.get(i).getId(), maturity, null);
            cheques.clear(rows.get(i).getId(), maturity);
        }
        // Cheques 6 and 7 clear and are returned by the bank the same day (spec 7.2,
        // CLEARED -> BOUNCED: Dr RENT_RECEIVABLE / Cr the bank, not PDC receivable).
        for (int i = 5; i < 7; i++) {
            LocalDate maturity = rows.get(i).getChequeDate();
            cheques.deposit(rows.get(i).getId(), maturity, null);
            cheques.clear(rows.get(i).getId(), maturity);
            cheques.bounce(rows.get(i).getId(), maturity, ChequeFailureReason.BOUNCE);
        }

        recognition.runTo(CUT_OFF);
    }

    private Map<String, AccountLedgerDTO> renterLedgerByAccount() {
        return ledger.renterLedger(renterId, LocalDate.of(2025, 1, 1), LocalDate.of(2027, 12, 31))
                .stream().collect(Collectors.toMap(AccountLedgerDTO::accountName, Function.identity(),
                        (a, b) -> { throw new IllegalStateException("duplicate account " + a.accountName()); },
                        LinkedHashMap::new));
    }

    @Test
    void everyAccountMatchesThePactExportLineForLine() {
        replay();

        Map<String, List<GoldenRow>> expected = GoldenLedgerFixture.ledgerByAccount(LEDGER);
        Map<String, AccountLedgerDTO> actual = renterLedgerByAccount();

        assertThat(actual.keySet())
                .as("the renter's ledger must touch exactly the accounts PACT's does")
                .containsExactlyInAnyOrderElementsOf(expected.keySet());

        expected.forEach((account, rows) ->
                LedgerDiff.assertAccountMatches(account, rows, actual.get(account)));
    }

    @Test
    void theReportTotalMatchesPact() {
        replay();

        Map<String, AccountLedgerDTO> actual = renterLedgerByAccount();
        BigDecimal debit = actual.values().stream().map(AccountLedgerDTO::totalDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = actual.values().stream().map(AccountLedgerDTO::totalCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // PACT's own REPORT TOTAL row. The per-day rule redistributes the CIL rows but does
        // not change their sum, so this number is unaffected by the deviation.
        assertThat(debit).isEqualByComparingTo("248150.00");
        assertThat(credit).isEqualByComparingTo(debit);
        assertThat(GoldenLedgerFixture.reportTotal(GoldenLedgerFixture.ledger(LEDGER)))
                .isEqualByComparingTo(debit);
    }

    @Test
    void theTrialBalanceBalancesAndShowsTheExpectedClosingPositions() {
        replay();

        List<TrialBalanceRowDTO> tb = ledger.trialBalance(CUT_OFF, propertyId);
        BigDecimal debit = tb.stream().map(TrialBalanceRowDTO::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = tb.stream().map(TrialBalanceRowDTO::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debit).isEqualByComparingTo(credit);

        Map<String, BigDecimal> balance = tb.stream()
                .collect(Collectors.toMap(TrialBalanceRowDTO::name, TrialBalanceRowDTO::balance));
        // Two bounced cheques (9,000 + 10,000) are still owed; everything else has settled.
        assertThat(balance.get("Rent Receivable LE BOULEVARD")).isEqualByComparingTo("19000.00");
        assertThat(balance.get("Emirates Islamic - LE BOULEVARD")).isEqualByComparingTo("39050.00");
        assertThat(balance.get("PDC Receivable LE BOULEVARD")).isEqualByComparingTo("0.00");
        assertThat(balance.get("Advance Rent LE BOULEVARD")).isEqualByComparingTo("0.00");
        assertThat(balance.get("Rental Income LE BOULEVARD")).isEqualByComparingTo("-55000.00");
        assertThat(balance.get("Security Deposit-Warsan")).isEqualByComparingTo("-2750.00");
        assertThat(balance.get("Admin charge-LE BOULEVERD")).isEqualByComparingTo("-300.00");
    }

    @Test
    void theRecognitionScheduleFollowsThePerDayRuleNotPacts() {
        replay();

        List<LedgerDiff.RecognitionRow> got = recognition.scheduleFor(leaseId).stream()
                .map(e -> new LedgerDiff.RecognitionRow(e.getPeriodStart(), e.getPeriodEnd(),
                        e.getDays(), e.getAmount()))
                .toList();

        LedgerDiff.assertRecognitionMatches(GoldenLedgerFixture.recognition(RECOGNITION), got);

        // 55,000 over 365 days, remainder in the last slice — never a 366th day of rent.
        assertThat(got.stream().map(LedgerDiff.RecognitionRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("55000.00");
        assertThat(got.stream().mapToInt(LedgerDiff.RecognitionRow::days).sum()).isEqualTo(365);
    }

    @Test
    void thePostedLeasesTenantLedgerNetsToZeroBeforeAnyCheque() {
        // Spec 6.4: "After posting the renter's ledger nets to zero, as in PACT." Asserted
        // before the cheque lifecycle runs, because that is the only moment it holds.
        leases.post(leaseId);

        Map<String, AccountLedgerDTO> actual = renterLedgerByAccount();
        assertThat(actual.get("Rent Receivable LE BOULEVARD").closingBalance()).isEqualByComparingTo("0.00");
        assertThat(actual.get("PDC Receivable LE BOULEVARD").closingBalance()).isEqualByComparingTo("58050.00");
    }
}
```

- [ ] **Step 2: Run it and watch it fail on real numbers, not on wiring**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.golden.GoldenLedgerLeBoulevardIT'`
Expected on the first run: compile errors only if a Plan 2/3 name differs from the `Consumes` block above — fix the *call site*, never the fixture. Once it compiles, any assertion failure prints the aligned diff from `LedgerDiff` and names the product bug.

- [ ] **Step 3: Fix what the diff names, one row at a time**

Work top-down through the first differing row. The failure modes worth naming in advance, because each has a single correct fix in Plan 2/3 code:
- `PDR` dated the cheque's `chequeDate` instead of its `postingDate` → spec §7.2 says `posting_date`.
- A `CBR` after clearing crediting `PDC_RECEIVABLE` → spec §7.2 row `CLEARED → BOUNCED` credits the `debit_account`.
- A `CIL` dated the 1st → spec D13 says `period_end`.
- The final `CIL` dated 2026-09-30 instead of 2026-09-04 → the last slice's `period_end` is the lease end date, not the month end.
- `TCO` lines emitted in a different order than the lease lines → `line_no` must follow `lease_lines.seq_no`.

Never edit a fixture to make a test pass. The fixture is the client's export.

- [ ] **Step 4: Run the golden tag on its own**

Run: `cd backend && ./gradlew test -PincludeTags=golden`
Expected: 5 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add backend/src/test/java/com/datagami/rentaxis/golden/GoldenLedgerLeBoulevardIT.java
git commit -m "test(golden): replay the client's LE BOULEVARD contract against their PACT ledger

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: `GoldenLedgerGalah2IT` — replay ANUM ISHTIAQ / GALAH 2

The mirror case: GALAH 2 deliberately shares the generic tenant-wide accounts instead of getting its own set, so this contract proves the resolver's second hop (`property_account_mappings` miss → `tenant_default_account_mappings` hit, spec §4.4/§5.2). No cheque ever clears, so the whole 53,000 sits in PDC Receivable at the end — and one cheque is registered five days *before* the contract is dated, which is what makes the row-ordering deviation D-d visible.

**Files:**
- Create: `backend/src/test/java/com/datagami/rentaxis/golden/GoldenLedgerGalah2IT.java`
- Test: itself.

**Interfaces:**
- Consumes: identical to Task 2, plus `PropertyAccountService.clearMapping(UUID propertyId, AccountRole role)` and `PropertyAccountService.setTenantDefault(AccountRole role, UUID accountId)` from Plan 1.
- Produces: nothing.

- [ ] **Step 1: Write the replay test**

`backend/src/test/java/com/datagami/rentaxis/golden/GoldenLedgerGalah2IT.java`:

```java
package com.datagami.rentaxis.golden;

import com.datagami.rentaxis.api.dto.ledger.AccountLedgerDTO;
import com.datagami.rentaxis.api.dto.ledger.TrialBalanceRowDTO;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.UnitService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.lease.LeaseService;
import com.datagami.rentaxis.core.service.lease.LeaseService.ChequeRowRequest;
import com.datagami.rentaxis.core.service.lease.LeaseService.CreateLeaseRequest;
import com.datagami.rentaxis.core.service.lease.LeaseService.LeaseLineRequest;
import com.datagami.rentaxis.core.service.recognition.RecognitionService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.golden.GoldenLedgerFixture.GoldenRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Replays the client's contract TCO-26/1629 (ANUM ISHTIAQ, GALAH 2, 16-09-2026) and diffs
 * it against their PACT General Ledger export.
 *
 * <p>Where {@link GoldenLedgerLeBoulevardIT} covers a property with its own account set,
 * this one covers the opposite arrangement the client actually runs: GALAH 2 posts into the
 * generic Rent Receivable 105590, Advance Rent 125620, PDC Receivable EIB 125636, Rental
 * Income A/c 145661 and Admin Fee 145663. Nothing is mapped on the property, so every role
 * resolves through the tenant defaults (spec 4.4 second hop). It is also the contract the
 * spec's own per-day reference table in 8.2 was computed from, so the recognition assertion
 * here is a direct check against the design document.
 *
 * <p>The admin-fee cheque is dated 11-09-2026, five days before the contract itself. Our
 * ledger orders strictly by entry date, so that PDR leads the account; PACT segregates
 * post-dated receipts into a sub-block and prints it last. The row set, the totals and the
 * closing balance are identical — only the running-balance column on those rows differs
 * (deviation D-d).
 */
@SpringBootTest
@Testcontainers
@Tag("golden")
class GoldenLedgerGalah2IT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String LEDGER = "golden/galah2-ledger.csv";
    private static final String RECOGNITION = "golden/galah2-recognition.csv";

    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 9, 16);
    private static final LocalDate TERM_START = LocalDate.of(2026, 9, 24);
    private static final LocalDate TERM_END = LocalDate.of(2027, 9, 23);
    private static final LocalDate CUT_OFF = LocalDate.of(2027, 9, 30);

    @Autowired AccountService accounts;
    @Autowired PropertyAccountService propertyAccounts;
    @Autowired AccountResolver resolver;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired PropertyService properties;
    @Autowired UnitService units;
    @Autowired LeaseService leases;
    @Autowired ChequeService cheques;
    @Autowired RecognitionService recognition;
    @Autowired LedgerQueryService ledger;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired RenterRepository renterRepo;

    UUID propertyId;
    UUID renterId;
    UUID leaseId;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("Al Ashram Golden " + UUID.randomUUID());
        TenantContextHolder.setTenantId(orgRepo.save(org).getId());

        accounts.seedDefaultAccounts();
        propertyAccounts.seedDefaultTemplateAndDefaults();
        // The first cheque registers on 11-09-2026, so the books must be open before then.
        fiscal.setBooksStartDate(LocalDate.of(2026, 9, 1));
        fiscal.lockThrough(LocalDate.of(2026, 8, 31));

        Property p = new Property();
        p.setNameEn("GALAH 2");
        p.setEmirate(Emirate.ABU_DHABI);
        propertyId = properties.createProperty(p).getId();

        // GALAH 2 shares the generic accounts. Drop every property mapping the template made
        // and point the tenant defaults at the five PACT accounts instead, so each role has to
        // fall through to the second hop of the resolver.
        for (AccountRole role : List.of(AccountRole.RENT_RECEIVABLE, AccountRole.ADVANCE_RENT,
                AccountRole.RENTAL_INCOME, AccountRole.PDC_RECEIVABLE, AccountRole.ADMIN_FEE)) {
            propertyAccounts.clearMapping(propertyId, role);
        }
        tenantDefault(AccountRole.RENT_RECEIVABLE, "Rent Receivable",     "A-02-01");
        tenantDefault(AccountRole.ADVANCE_RENT,    "Advance Rent",        "B");
        tenantDefault(AccountRole.PDC_RECEIVABLE,  "PDC Receivable EIB",  "A-02-03");
        tenantDefault(AccountRole.RENTAL_INCOME,   "Rental Income A/c",   "C-01-01");
        tenantDefault(AccountRole.ADMIN_FEE,       "Admin Fee",           "C-01-01");

        Unit u = new Unit();
        u.setProperty(properties.getPropertyById(propertyId));
        u.setUnitNumber("B1-681");
        u.setStatus(UnitStatus.VACANT);
        UUID unitId = units.createUnit(u).getId();

        Renter r = new Renter();
        r.setNameEn("ANUM ISHTIAQ ISHTIAQ AHMED KHAN");
        r.setEmail("anum.ishtiaq@example.invalid");
        renterId = renterRepo.save(r).getId();

        leaseId = leases.createDraft(new CreateLeaseRequest(
                unitId, renterId, CONTRACT_DATE, TERM_START, TERM_END, "GLA_B1/681", 0,
                List.of(new LeaseLineRequest("RENT",      new BigDecimal("51000.00"), BigDecimal.ZERO, ""),
                        new LeaseLineRequest("ADMIN_FEE", new BigDecimal("2000.00"),  BigDecimal.ZERO, "")),
                // Five cheques, each registered on its own posting date rather than all on the
                // contract date — the admin fee five days before the contract is even dated.
                List.of(cheque(1, "512001", LocalDate.of(2026, 9, 11),  "2000.00",  "Admin Fees"),
                        cheque(2, "512002", LocalDate.of(2026, 10, 2),  "12750.00", "Rent - 1st Installment"),
                        cheque(3, "512003", LocalDate.of(2027, 1, 2),   "12750.00", "Rent - 2nd Installment"),
                        cheque(4, "512004", LocalDate.of(2027, 4, 2),   "12750.00", "Rent - 3rd Installment"),
                        cheque(5, "512005", LocalDate.of(2027, 7, 2),   "12750.00", "Rent - 4th Installment"))))
                .getId();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private void tenantDefault(AccountRole role, String pactName, String parentCode) {
        Account leaf = accounts.createLeaf(pactName, accounts.getAccountByCode(parentCode), null);
        propertyAccounts.setTenantDefault(role, leaf.getId());
        assertThat(resolver.resolve(role, propertyId).getName())
                .as("role %s must fall through to the tenant default", role)
                .isEqualTo(pactName);
    }

    /** Both the posting date and the maturity date are the PACT date; nothing ever clears. */
    private static ChequeRowRequest cheque(int seq, String number, LocalDate date, String amount, String narration) {
        return new ChequeRowRequest(seq, date, number, date, "EIB", new BigDecimal(amount), narration, ChequeMode.PDC);
    }

    private void replay() {
        leases.post(leaseId);
        assertThat(cheques.findByLease(leaseId)).hasSize(5);
        recognition.runTo(CUT_OFF);
    }

    private Map<String, AccountLedgerDTO> renterLedgerByAccount() {
        return ledger.renterLedger(renterId, LocalDate.of(2026, 1, 1), LocalDate.of(2028, 12, 31))
                .stream().collect(Collectors.toMap(AccountLedgerDTO::accountName, Function.identity(),
                        (a, b) -> { throw new IllegalStateException("duplicate account " + a.accountName()); },
                        LinkedHashMap::new));
    }

    @Test
    void everyAccountMatchesThePactExportLineForLine() {
        replay();

        Map<String, List<GoldenRow>> expected = GoldenLedgerFixture.ledgerByAccount(LEDGER);
        Map<String, AccountLedgerDTO> actual = renterLedgerByAccount();

        assertThat(actual.keySet()).containsExactlyInAnyOrderElementsOf(expected.keySet());
        expected.forEach((account, rows) ->
                LedgerDiff.assertAccountMatches(account, rows, actual.get(account)));
    }

    @Test
    void theReportTotalMatchesPact() {
        replay();

        Map<String, AccountLedgerDTO> actual = renterLedgerByAccount();
        BigDecimal debit = actual.values().stream().map(AccountLedgerDTO::totalDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = actual.values().stream().map(AccountLedgerDTO::totalCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(debit).isEqualByComparingTo("157000.00");
        assertThat(credit).isEqualByComparingTo(debit);
    }

    @Test
    void theTrialBalanceBalancesAndLeavesTheWholeContractInPdcReceivable() {
        replay();

        List<TrialBalanceRowDTO> tb = ledger.trialBalance(CUT_OFF, propertyId);
        BigDecimal debit = tb.stream().map(TrialBalanceRowDTO::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = tb.stream().map(TrialBalanceRowDTO::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debit).isEqualByComparingTo(credit);

        Map<String, BigDecimal> balance = tb.stream()
                .collect(Collectors.toMap(TrialBalanceRowDTO::name, TrialBalanceRowDTO::balance));
        assertThat(balance.get("Rent Receivable")).isEqualByComparingTo("0.00");
        assertThat(balance.get("Advance Rent")).isEqualByComparingTo("0.00");
        assertThat(balance.get("PDC Receivable EIB")).isEqualByComparingTo("53000.00");
        assertThat(balance.get("Rental Income A/c")).isEqualByComparingTo("-51000.00");
        assertThat(balance.get("Admin Fee")).isEqualByComparingTo("-2000.00");
    }

    @Test
    void theRecognitionScheduleMatchesTheSpecsOwnReferenceTable() {
        replay();

        List<LedgerDiff.RecognitionRow> got = recognition.scheduleFor(leaseId).stream()
                .map(e -> new LedgerDiff.RecognitionRow(e.getPeriodStart(), e.getPeriodEnd(),
                        e.getDays(), e.getAmount()))
                .toList();

        LedgerDiff.assertRecognitionMatches(GoldenLedgerFixture.recognition(RECOGNITION), got);

        // The four numbers the client quoted on the call, checked individually so a
        // failure names which one moved rather than "the list differs".
        assertThat(got.get(0).amount()).isEqualByComparingTo("978.08");    // 24-30 Sep 2026, 7 days
        assertThat(got.get(1).amount()).isEqualByComparingTo("4331.51");   // Oct 2026, 31 days
        assertThat(got.get(2).amount()).isEqualByComparingTo("4191.78");   // Nov 2026, 30 days
        assertThat(got.get(12).amount()).isEqualByComparingTo("3213.68");  // 1-23 Sep 2027, 23 days
        assertThat(got.stream().map(LedgerDiff.RecognitionRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("51000.00");
    }

    @Test
    void thePostedLeasesTenantLedgerNetsToZero() {
        leases.post(leaseId);

        Map<String, AccountLedgerDTO> actual = renterLedgerByAccount();
        assertThat(actual.get("Rent Receivable").closingBalance()).isEqualByComparingTo("0.00");
        assertThat(actual.get("PDC Receivable EIB").closingBalance()).isEqualByComparingTo("53000.00");
    }
}
```

- [ ] **Step 2: Run it**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.golden.GoldenLedgerGalah2IT'`
Expected: PASS. The most likely first failure is `recognition.runTo(2027-09-30)` posting nothing because the implementation clamped `to` to the system date — Plan 3's manual run must honour the argument (see the `Consumes (from plan 3)` note). The second most likely is `PDR` all dated the contract date, which is only correct for LE BOULEVARD.

- [ ] **Step 3: Run both golden classes together**

Run: `cd backend && ./gradlew test -PincludeTags=golden`
Expected: 10 tests, 0 failures.

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/java/com/datagami/rentaxis/golden/GoldenLedgerGalah2IT.java
git commit -m "test(golden): replay the client's GALAH 2 contract on shared tenant-default accounts

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task group B — Demo seed on v2

### Task 4: Seed script — chart of accounts, property account sets, and the v1 finance removals

`scripts/seed_demo_tenant.py` is the only way the Al Ashram demo tenant on production gets its data, and it doubles as a smoke test of the prod API (its own docstring says so). Three of its calls no longer exist after Plan 1 — `/api/v1/finance/account-mappings`, `/api/v1/finance/transactions`, `/api/v1/finance/transactions/split` — and a fourth, `/api/v1/payments/*`, no longer exists after Plan 2. This task fixes the finance foundation; Task 5 rewrites the leases and cheques on top of it.

Read `~/.claude/projects/-Users-kunalsharma-datagami-rentaxis/memory/project_demo_tenant.md` before running this against production: the demo tenant is also the App Store reviewer's tenant, and the four named renters must not be deleted.

**Files:**
- Modify: `scripts/seed_demo_tenant.py` — the block at `# ── 1.` (line ~386, `finance/accounts/seed`) and the block at `# ── 6b. Vendors + split expenses` (line ~820)
- Test: re-running the script against a local stack is the test; there is no unit test for it.

**Interfaces:**
- Consumes (from plan 1): `POST /api/v1/finance/accounts/seed` (now also seeds the property-account template and the tenant default mappings — no separate call needed), `GET /api/v1/finance/accounts`, `GET /api/v1/finance/accounts/code/{code}`, `GET/POST/PUT /api/v1/finance/properties/{id}/accounts`, `PUT /api/v1/finance/fiscal`.
- Produces: `out["accounts"]` = `{ "<propertyKey>": { "<ROLE>": "<accountId>" } }` and `out["fiscal"] = {"booksStartDate": ..., "booksLockedThrough": ...}` in `scripts/seed_demo_tenant.out.json`, both read by Task 5 and by the tutorial recorder in Task 10.

- [ ] **Step 1: Replace the chart-of-accounts block**

In `main()`, replace the two-line block that currently reads

```python
    api.post("/api/v1/finance/accounts/seed")
    log("chart of accounts seeded")
```

with:

```python
    # Chart of accounts + property-account template + tenant default mappings.
    # One idempotent call since accounting v2 (plan 1 task 6); a no-op when the
    # tenant already has accounts.
    api.post("/api/v1/finance/accounts/seed")
    log("chart of accounts, property template and tenant defaults seeded")

    # Open the books on 1 January of the demo year and lock everything before it,
    # so back-dated demo contracts post and nothing can be written into last year.
    api.put(
        "/api/v1/finance/fiscal",
        json={
            "fiscalYearStartMonth": 1,
            "booksStartDate": iso(dt.date(TODAY.year, 1, 1)),
            "booksLockedThrough": iso(dt.date(TODAY.year - 1, 12, 31)),
        },
    )
    out["fiscal"] = {
        "booksStartDate": iso(dt.date(TODAY.year, 1, 1)),
        "booksLockedThrough": iso(dt.date(TODAY.year - 1, 12, 31)),
    }
    log("fiscal year opened 1 Jan, books locked through 31 Dec last year")
```

- [ ] **Step 2: Record the generated property account set**

Creating a property already generates its account set (spec §5.4). Immediately after the `tower = make_property(...)` / `marina = make_property(...)` pair, add:

```python
    def property_accounts(prop):
        """Role -> account id for one property. Creating a property generates the
        set from the tenant template; *Generate missing* fills any gap left by a
        property created before the template existed. Both are idempotent."""
        api.post(f"/api/v1/finance/properties/{prop['id']}/accounts/generate")
        rows = api.get(f"/api/v1/finance/properties/{prop['id']}/accounts") or []
        mapped = {r["role"]: r["accountId"] for r in rows if r.get("accountId")}
        missing = [r["role"] for r in rows if not r.get("accountId")]
        for role in ("RENT_RECEIVABLE", "ADVANCE_RENT", "RENTAL_INCOME",
                     "PDC_RECEIVABLE", "BANK", "SECURITY_DEPOSIT", "ADMIN_FEE"):
            if role not in mapped:
                raise RuntimeError(
                    f"{prop['nameEn']}: role {role} is unmapped, a lease on it cannot post. "
                    f"Unmapped roles: {missing}"
                )
        return mapped

    out["accounts"] = {
        "tower": property_accounts(tower),
        "marina": property_accounts(marina),
    }
    log(f"property account sets ready ({len(out['accounts']['tower'])} roles on the tower)")
```

- [ ] **Step 3: Replace the split-expense block with a vendor invoice and a payment voucher**

`POST /api/v1/finance/transactions/split` is gone with `FinancialTransaction`. Spec §10.1/§10.2 replace it with a Purchase/Service Invoice and a Bank/Cash Payment Voucher. Replace everything from `def account_id(code):` through the third `split_expense(...)` call with:

```python
    def account_id(code):
        return api.get(f"/api/v1/finance/accounts/code/{code}")["id"]

    existing_vouchers = {
        v.get("narration"): v
        for v in (page_items(api.get("/api/v1/finance/vouchers?size=200")) or [])
        if isinstance(v, dict)
    }

    def make_voucher(narration, body):
        """Create-and-post a voucher, keyed on its narration so a re-run is a no-op."""
        if narration in existing_vouchers:
            return existing_vouchers[narration]
        voucher = api.post("/api/v1/finance/vouchers",
                           json={**body, "narration": narration})
        api.post(f"/api/v1/finance/vouchers/{voucher['id']}/post")
        log(f"voucher posted: {narration}")
        return voucher

    # Purchase / Service Invoice — Dr expense + Dr input VAT / Cr the vendor's
    # silently created payable account (spec 10.1). 5% VAT, additive.
    invoice = make_voucher(
        "Fire safety AMC — Residence Tower",
        {
            "docType": "PISR",
            "docDate": iso(dt.date(TODAY.year, 2, 10)),
            "vendorId": fm_vendor["id"],
            "propertyId": tower["id"],
            # vatAmount is derived on the server from vatRate — do not send it.
            "lines": [{
                "accountId": account_id("D-01-08"),
                "description": "Annual fire safety maintenance contract",
                "amount": 9000.0,
                "vatRate": 5.0,
                "propertyId": tower["id"],
            }],
        },
    )

    # Bank / Cash Payment Voucher — Dr the vendor / Cr the tower's bank leaf (spec 10.2).
    payment = make_voucher(
        "Payment — fire safety AMC",
        {
            "docType": "BPV",
            "docDate": iso(dt.date(TODAY.year, 3, 5)),
            "paymentAccountId": out["accounts"]["tower"]["BANK"],
            "chequeNumber": "700001",
            "chequeDate": iso(dt.date(TODAY.year, 3, 5)),
            "lines": [{
                "accountId": vendor_account_id(fm_vendor["id"]),
                "description": "Fire safety AMC — invoice settled in full",
                "amount": 9450.0,
            }],
        },
    )
    out["vouchers"] = {"purchaseInvoice": invoice["id"], "paymentVoucher": payment["id"]}
```

and add the vendor-account lookup next to `account_id` (the vendor's ledger leaf is created silently on save, spec §5.5, and surfaces on the vendor record):

```python
    def vendor_account_id(vendor_id):
        vendor = api.get(f"/api/v1/vendors/{vendor_id}")
        acc = vendor.get("payableAccountId") or vendor.get("coaAccountId")
        if not acc:
            raise RuntimeError(
                f"vendor {vendor_id} has no ledger account; VendorService creates one under "
                f"B-01-04 on save (spec 5.5), but it skips that silently when the chart of "
                f"accounts is not seeded — re-run the seed step above"
            )
        return acc
```

Delete `cleaning_vendor` and the second/third `split_expense` calls — one invoice and one payment are enough to show the voucher path, and the demo script is not the place to exercise multi-property allocation (spec §14 defers bill-wise allocation anyway).

- [ ] **Step 4: Update the module docstring**

The docstring's "What it creates" list still describes v1. Replace its finance lines:

```python
  - Chart of accounts + per-property account sets (auto-generated from the
    tenant template) + fiscal year opened and prior year locked
  - 4 leases posted as tenancy contracts (TCO + one PDR per cheque), covering
    the cheque lifecycle: cleared / deposited / registered / bounced+replaced
  - Month-end income recognition run to the end of last month (CIL journals)
  - 1 vendor with a purchase invoice (PISR) and its payment voucher (BPV)
```

- [ ] **Step 5: Run it against a local stack**

```bash
cd /Users/kunalsharma/datagami/rentaxis && docker compose up -d
PROD_BASE_URL=http://localhost:8080 \
PROD_SUPERADMIN_EMAIL=admin@rentaxis.com PROD_SUPERADMIN_PASSWORD=admin123 \
python3 scripts/seed_demo_tenant.py
```
Expected: it fails inside section 4 (leases) because the lease payload is still v1 — that is Task 5. Everything up to and including `property account sets ready (13 roles on the tower)` must succeed, and `scripts/seed_demo_tenant.out.json` must contain `accounts.tower.RENT_RECEIVABLE`.

- [ ] **Step 6: Commit**

```bash
git add scripts/seed_demo_tenant.py
git commit -m "chore(seed): demo tenant on the v2 chart of accounts, property account sets and vouchers

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Seed script — leases with lines and a cheque grid, posting, cheque lifecycle, recognition

**Files:**
- Modify: `scripts/seed_demo_tenant.py` — the block at `# ── 4. Leases` (line ~522) and `# ── 5. Cheque lifecycle on payment schedules` (line ~588)
- Test: the run in Step 6.

**Interfaces:**
- Consumes (from plan 2): `POST /api/v1/leases` (draft with `lines[]` and `cheques[]`), `POST /api/v1/leases/{id}/post`, `GET /api/v1/leases/{id}/cheques`, `POST /api/v1/cheques/{id}/deposit|clear|bounce|replace`.
- Consumes (from plan 3): `POST /api/v1/finance/recognition/run?to=YYYY-MM-DD`.
- Produces: `out["leases"]` gains `status` per lease, `out["cheques"]` = `{ "<leaseKey>": [ {"id", "seqNo", "status"} ] }`, `out["recognitionRunTo"]`.

- [ ] **Step 1: Replace the lease block**

Replace `def make_lease(...)` and the four `make_lease(...)` calls with the v2 shape. Every amount is chosen so `Σ cheque amounts == Σ line net amounts`, which is validation rule 3 of spec §6.4 — a mismatch is rejected at post time, not at save time, so the arithmetic has to be right here.

```python
    # ── 4. Leases as posting documents ───────────────────────────────────────
    year_start = dt.date(TODAY.year, 1, 1)
    year_end = dt.date(TODAY.year, 12, 31)
    contract_date = year_start - dt.timedelta(days=10)

    existing_leases = {
        l.get("unitId"): l for l in (api.get("/api/v1/leases") or [])
    }

    def line(code, gross, narration="", discount=0.0):
        return {"chargeTypeCode": code, "grossAmount": float(gross),
                "discountAmount": float(discount), "narration": narration}

    def cheque(seq, number, date, amount, narration, mode="PDC",
               bank="Emirates NBD", posting_date=None):
        return {"seqNo": seq, "postingDate": iso(posting_date or contract_date),
                "chequeNumber": number, "chequeDate": iso(date), "payeeBank": bank,
                "amount": float(amount), "narration": narration, "mode": mode}

    def make_lease(unit, renter, contract_number, lines, cheques, post=True):
        """Create-or-fetch a DRAFT lease, then post it. Both halves are
        idempotent: an existing lease is returned untouched, and a lease already
        ACTIVE is not posted twice."""
        lease = existing_leases.get(unit["id"])
        if not lease:
            total_lines = sum(l["grossAmount"] - l["discountAmount"] for l in lines)
            total_cheques = sum(c["amount"] for c in cheques)
            if abs(total_lines - total_cheques) > 0.005:
                raise RuntimeError(
                    f"{contract_number}: cheque grid {total_cheques:,.2f} does not equal "
                    f"contract value {total_lines:,.2f} — the post would be rejected"
                )
            lease = api.post("/api/v1/leases", json={
                "unitId": unit["id"],
                "renterId": renter["id"],
                "contractNumber": contract_number,
                "contractDate": iso(contract_date),
                "startDate": iso(year_start),
                "endDate": iso(year_end),
                "gracePeriodDays": 5,
                "lines": lines,
                "cheques": cheques,
            })
        if post and lease.get("status") == "DRAFT":
            lease = api.post(f"/api/v1/leases/{lease['id']}/post")
        return lease

    # Ahmed — quarterly rent, admin fee folded into the first rent cheque, deposit
    # on its own row. Contract value 108,500 = 22,000 + 22,750 + 3 x 21,250.
    lease_ahmed = make_lease(
        a101, ahmed, "ART/1001",
        [line("SECURITY_DEPOSIT", 22000), line("RENT", 85000), line("ADMIN_FEE", 1500)],
        [cheque(1, "200100", year_start, 22000, "Security Deposit"),
         cheque(2, "200101", year_start, 22750, "Rent - 1st Installment"),
         cheque(3, "200102", dt.date(TODAY.year, 4, 1), 21250, "Rent - 2nd Installment"),
         cheque(4, "200103", dt.date(TODAY.year, 7, 1), 21250, "Rent - 3rd Installment"),
         cheque(5, "200104", dt.date(TODAY.year, 10, 1), 21250, "Rent - 4th Installment")],
    )

    # Fatima — quarterly, one cheque returned and replaced by two. 78,000.
    lease_fatima = make_lease(
        a102, fatima, "ART/1002",
        [line("SECURITY_DEPOSIT", 16000), line("RENT", 62000)],
        [cheque(1, "300200", year_start, 16000, "Security Deposit", bank="FAB"),
         cheque(2, "300201", year_start, 15500, "Rent - 1st Installment", bank="FAB"),
         cheque(3, "300202", dt.date(TODAY.year, 4, 1), 15500, "Rent - 2nd Installment", bank="FAB"),
         cheque(4, "300203", dt.date(TODAY.year, 7, 1), 15500, "Rent - 3rd Installment", bank="FAB"),
         cheque(5, "300204", dt.date(TODAY.year, 10, 1), 15500, "Rent - 4th Installment", bank="FAB")],
    )

    # Rajesh — monthly, annual parking fee folded into January. 135,000.
    rajesh_cheques = [cheque(1, "400300", year_start, 12000, "Security Deposit",
                             bank="Dubai Islamic Bank")]
    for m in range(1, 13):
        rajesh_cheques.append(cheque(
            m + 1, f"4003{m:02d}", dt.date(TODAY.year, m, 1),
            13000 if m == 1 else 10000,
            f"Rent - {m}{'st' if m == 1 else 'nd' if m == 2 else 'rd' if m == 3 else 'th'} Installment",
            bank="Dubai Islamic Bank"))
    lease_rajesh = make_lease(
        a103, rajesh, "ART/1003",
        [line("SECURITY_DEPOSIT", 12000), line("RENT", 120000),
         line("PARKING_FEE", 3000, "Annual parking — bay B2-18")],
        rajesh_cheques,
    )

    # Sara — left in DRAFT on purpose. The demo posts it live, which is the one
    # moment the audience sees TCO and PDR journals appear. 140,000.
    lease_sara = make_lease(
        m1501, sara, "MH/2001",
        [line("SECURITY_DEPOSIT", 30000), line("RENT", 110000)],
        [cheque(1, "500400", year_start, 30000, "Security Deposit"),
         cheque(2, "500401", year_start, 27500, "Rent - 1st Installment"),
         cheque(3, "500402", dt.date(TODAY.year, 4, 1), 27500, "Rent - 2nd Installment"),
         cheque(4, "500403", dt.date(TODAY.year, 7, 1), 27500, "Rent - 3rd Installment"),
         cheque(5, "500404", dt.date(TODAY.year, 10, 1), 27500, "Rent - 4th Installment")],
        post=False,
    )
    log("3 posted contracts (quarterly, quarterly, monthly) + 1 draft to post live")

    out["leases"] = {
        "ahmed": lease_ahmed["id"], "fatima": lease_fatima["id"],
        "rajesh": lease_rajesh["id"], "sara": lease_sara["id"],
    }
    out["leaseStatus"] = {
        k: api.get(f"/api/v1/leases/{v}")["status"] for k, v in out["leases"].items()
    }
```

- [ ] **Step 2: Replace the cheque-lifecycle block**

```python
    # ── 5. Cheque lifecycle on the PDC register ──────────────────────────────
    RANK = {"DRAFT": 0, "REGISTERED": 1, "DEPOSITED": 2,
            "CLEARED": 3, "BOUNCED": 3, "REPLACED": 4}

    def rows_for(lease_id):
        rows = api.get(f"/api/v1/leases/{lease_id}/cheques") or []
        return sorted(rows, key=lambda r: r["seqNo"])

    def value_date(cheque_date, days_after):
        """Never post a future date — the demo must look like a real ledger."""
        d = dt.date.fromisoformat(cheque_date) + dt.timedelta(days=days_after)
        return iso(min(d, TODAY))

    def advance(row, target):
        """Walk one register row REGISTERED -> DEPOSITED -> CLEARED/BOUNCED,
        skipping transitions already done. Safe to re-run."""
        status = row.get("status", "REGISTERED")
        if RANK.get(status, 0) >= RANK[target]:
            return row
        cid = row["id"]
        if status == "REGISTERED" and RANK[target] >= 2:
            row = api.post(f"/api/v1/cheques/{cid}/deposit",
                           json={"depositDate": value_date(row["chequeDate"], 1)})
            status = row["status"]
        if status == "DEPOSITED" and target == "CLEARED":
            row = api.post(f"/api/v1/cheques/{cid}/clear",
                           json={"clearedDate": value_date(row["chequeDate"], 4)})
        if status == "DEPOSITED" and target == "BOUNCED":
            row = api.post(f"/api/v1/cheques/{cid}/bounce",
                           json={"bouncedDate": value_date(row["chequeDate"], 5),
                                 "failureReason": "BOUNCE"})
        return row

    # Ahmed: deposit + first two rent cheques cleared, Q2 banked and awaiting the
    # bank, Q3/Q4 still in the register.
    rows = rows_for(lease_ahmed["id"])
    for r in rows[:3]:
        advance(r, "CLEARED")
    advance(rows[3], "DEPOSITED")
    log("Ahmed: 3 cleared, 1 deposited, 1 registered")

    # Fatima: deposit + Q1 cleared; Q2 returned by the bank and replaced by two
    # smaller cheques, which register their own PDR journals (spec 7.2).
    rows = rows_for(lease_fatima["id"])
    for r in rows[:2]:
        advance(r, "CLEARED")
    bounced = advance(rows[2], "BOUNCED")
    if bounced["status"] == "BOUNCED" and not bounced.get("replacedById"):
        api.post(f"/api/v1/cheques/{bounced['id']}/replace", json={"replacements": [
            cheque(90, "300290", dt.date(TODAY.year, 5, 1), 10000,
                   "Replacement 1 of 2 — returned cheque 300202", bank="FAB",
                   posting_date=dt.date(TODAY.year, 4, 20)),
            cheque(91, "300291", dt.date(TODAY.year, 6, 1), 5500,
                   "Replacement 2 of 2 — returned cheque 300202", bank="FAB",
                   posting_date=dt.date(TODAY.year, 4, 20)),
        ]})
        log("Fatima: Q2 cheque returned and replaced by two rows")

    # Rajesh: deposit + five months cleared; the sixth is past its date and
    # unpaid, so it shows as overdue on the register (spec 7.5).
    rows = rows_for(lease_rajesh["id"])
    for r in rows[:6]:
        advance(r, "CLEARED")
    log("Rajesh: deposit + 5 monthly cheques cleared, the next one overdue")

    out["cheques"] = {
        key: [{"id": r["id"], "seqNo": r["seqNo"], "status": r["status"]}
              for r in rows_for(lease_id)]
        for key, lease_id in out["leases"].items()
    }

    # ── 5b. Month-end income recognition ─────────────────────────────────────
    # Run to the end of last month, so the demo always has a closed period to
    # show and an open one to run live. Idempotent: rows already POSTED are
    # skipped by the endpoint.
    recognise_to = dt.date(TODAY.year, TODAY.month, 1) - dt.timedelta(days=1)
    result = api.post(f"/api/v1/finance/recognition/run?to={iso(recognise_to)}")
    out["recognitionRunTo"] = iso(recognise_to)
    log(f"recognition run to {iso(recognise_to)}: "
        f"{(result or {}).get('posted', 0)} CIL journals posted")
```

- [ ] **Step 3: Delete the v1 leftovers**

Delete the `# Security deposits, booking deposit and charges on the ACTIVE leases…` block (the `banks = {...}` / `seq = 900001` loop) — deposits and charges are ordinary cheque rows now, already handled above. Delete `generate-contract` on Sara's lease: `DRAFT → ACTIVE` goes through **Post** in v2 (spec §6.3), and leaving her in DRAFT is the point.

- [ ] **Step 4: Run it end to end**

```bash
PROD_BASE_URL=http://localhost:8080 \
PROD_SUPERADMIN_EMAIL=admin@rentaxis.com PROD_SUPERADMIN_PASSWORD=admin123 \
python3 scripts/seed_demo_tenant.py
```
Expected: completes, prints the credentials, and writes `scripts/seed_demo_tenant.out.json` containing `leaseStatus` = `{"ahmed": "ACTIVE", "fatima": "ACTIVE", "rajesh": "ACTIVE", "sara": "DRAFT"}`.

- [ ] **Step 5: Run it a second time and prove idempotency**

```bash
PROD_BASE_URL=http://localhost:8080 \
PROD_SUPERADMIN_EMAIL=admin@rentaxis.com PROD_SUPERADMIN_PASSWORD=admin123 \
python3 scripts/seed_demo_tenant.py
```
Expected: completes again with no error and no duplicate. Then confirm the second run posted nothing new:

```bash
curl -s "http://localhost:8080/api/v1/finance/trial-balance?asOf=$(date +%Y-%m-%d)" \
  -H "X-User-Id: $(python3 -c "import json;print(json.load(open('scripts/seed_demo_tenant.out.json'))['adminUserId'])")" \
  -H "X-User-Role: TENANT_ADMIN" | python3 -c "
import json,sys
rows=json.load(sys.stdin)
d=sum(float(r['debit']) for r in rows); c=sum(float(r['credit']) for r in rows)
print(f'debit {d:,.2f} credit {c:,.2f} diff {d-c:,.2f}')
assert abs(d-c) < 0.005, 'trial balance does not balance'
"
```
Expected: `diff 0.00`. (Add `out["adminUserId"] = admin_user_id` next to `out["adminLogin"]` if it is not already written.)

- [ ] **Step 6: Commit**

```bash
git add scripts/seed_demo_tenant.py
git commit -m "chore(seed): demo leases as posting documents with a cheque grid and month-end recognition

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task group C — Playwright

### Task 6: Dev suite — `web/e2e/finance/accounting-v2.spec.ts`

One serial spec that walks a contract from draft to a balanced trial balance through the real UI. The dev suite runs against `http://localhost:3000` with a seeded tenant from `e2e/global-setup.ts`, one Playwright project per role, and reads IDs from `e2e/.test-context.json` via `fixtures/auth.fixture.ts`.

`global-setup.ts` also has to move: its `createLease` helper posts the v1 body, which Plan 2 rejects.

**Files:**
- Create: `web/e2e/finance/accounting-v2.spec.ts`
- Modify: `web/e2e/helpers/api-client.ts` (`createLease`), `web/e2e/global-setup.ts` (context keys)
- Delete: `web/e2e/finance/payments-pdc.spec.ts` (the v1 PDC tracker page is gone with `payment_schedules`)

**Interfaces:**
- Consumes (from plan 1): pages `/[locale]/dashboard/finance/general-ledger`, `/finance/tenant-ledger`, `/finance/trial-balance`, `/finance/journals`, the property **Accounts** tab, and `ledgerApi`'s `fmtBalance` output format (`"61,000.00 Dr"` / `"3,000.00 Cr"` / `"0.00"`) which the assertions match on.
- Consumes (from plan 2): pages `/[locale]/dashboard/leases/[id]` with its **Post** action and cheque grid, and `/[locale]/dashboard/finance/cheques` (Register) with **Deposit**, **Clear**, **Mark returned** and **Replace**.
- Consumes (from plan 3): `/[locale]/dashboard/finance/recognition` with **Run recognition to date**.
- Produces: nothing other tasks import.

- [ ] **Step 1: Update the shared lease helper**

In `web/e2e/helpers/api-client.ts`, replace the body `createLease` sends:

```ts
export async function createLease(
  userId: string,
  role: string,
  tenantId: string | null,
  lease: {
    unitId: string;
    renterId: string;
    contractDate: string;
    startDate: string;
    endDate: string;
    contractNumber: string;
    rent: number;
    deposit: number;
  },
) {
  // Accounting v2: a lease is a posting document. It is created as a DRAFT with
  // charge lines and a cheque grid whose total must equal the contract value,
  // then posted separately.
  const cheques = [
    { seqNo: 1, postingDate: lease.contractDate, chequeNumber: 'E2E-D1', chequeDate: lease.startDate,
      payeeBank: 'Emirates NBD', amount: lease.deposit, narration: 'Security Deposit', mode: 'PDC' },
    { seqNo: 2, postingDate: lease.contractDate, chequeNumber: 'E2E-R1', chequeDate: lease.startDate,
      payeeBank: 'Emirates NBD', amount: lease.rent / 2, narration: 'Rent - 1st Installment', mode: 'PDC' },
    { seqNo: 3, postingDate: lease.contractDate, chequeNumber: 'E2E-R2', chequeDate: lease.endDate,
      payeeBank: 'Emirates NBD', amount: lease.rent / 2, narration: 'Rent - 2nd Installment', mode: 'PDC' },
  ];
  return apiPost(userId, role, tenantId, '/v1/leases', {
    unitId: lease.unitId,
    renterId: lease.renterId,
    contractNumber: lease.contractNumber,
    contractDate: lease.contractDate,
    startDate: lease.startDate,
    endDate: lease.endDate,
    gracePeriodDays: 5,
    lines: [
      { chargeTypeCode: 'SECURITY_DEPOSIT', grossAmount: lease.deposit, discountAmount: 0, narration: '' },
      { chargeTypeCode: 'RENT', grossAmount: lease.rent, discountAmount: 0, narration: '' },
    ],
    cheques,
  });
}
```

Update its one caller in `global-setup.ts` to pass the new shape and to store `leaseId` exactly as before, plus `propertyId` (already there) — `.test-context.json` keys do not change, so no other spec breaks.

- [ ] **Step 2: Write the spec**

`web/e2e/finance/accounting-v2.spec.ts`:

```ts
import { test, expect } from '../fixtures/auth.fixture';

/**
 * Accounting v2, end to end through the UI: a draft contract becomes journals,
 * its cheques move through the register, the renter's ledger nets to zero and
 * then shows the returned cheque, month-end recognition posts, a journal is
 * reversed, and the trial balance still balances.
 *
 * Serial on purpose — every step builds on the one before, and the last
 * assertion is only meaningful if all of them ran.
 */
test.describe.serial('Accounting v2', () => {
  const ADMINS = ['super-admin', 'tenant-admin'];
  let leaseHref = '';

  test.beforeEach(async ({}, testInfo) => {
    test.skip(!ADMINS.includes(testInfo.project.name), 'finance is admin-only');
  });

  test('the chart of accounts is seeded and the property has its account set', async ({ page, testContext }) => {
    await page.goto('/en/dashboard/finance/accounts');
    await page.waitForLoadState('networkidle');

    // Seed on demand — a tenant registered by global-setup starts empty.
    const seed = page.getByRole('button', { name: /seed (default )?accounts/i });
    if (await seed.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await seed.click();
      await expect(page.getByText(/rental income/i).first()).toBeVisible();
    }

    await page.goto(`/en/dashboard/properties/${testContext.propertyId}`);
    await page.getByRole('tab', { name: /accounts/i }).click();

    // Every role a lease needs must resolve, or the post is refused (spec 5.4).
    for (const role of ['Rent Receivable', 'Advance Rent', 'Rental Income', 'PDC Receivable', 'Bank']) {
      await expect(page.getByRole('row', { name: new RegExp(role, 'i') })).toBeVisible();
    }
    await expect(page.getByText(/unmapped/i)).toHaveCount(0);
  });

  test('posting the contract writes TCO and one PDR per cheque', async ({ page, testContext }) => {
    leaseHref = `/en/dashboard/leases/${testContext.leaseId}`;
    await page.goto(leaseHref);
    await page.waitForLoadState('networkidle');

    await expect(page.getByText(/draft/i).first()).toBeVisible();
    await page.getByRole('button', { name: 'Post', exact: true }).click();
    await page.getByRole('button', { name: /confirm|post/i }).last().click();

    await expect(page.getByText(/active/i).first()).toBeVisible({ timeout: 15_000 });

    await page.getByRole('tab', { name: /journals/i }).click();
    await expect(page.getByRole('row').filter({ hasText: 'TCO' })).toHaveCount(1);
    await expect(page.getByRole('row').filter({ hasText: 'PDR' })).toHaveCount(3);
  });

  test('the renter ledger nets to zero the moment the contract is posted', async ({ page, testContext }) => {
    await page.goto(`/en/dashboard/finance/tenant-ledger?renterId=${testContext.renterId}`);
    await page.waitForLoadState('networkidle');

    // Spec 6.4 — receivable and PDC receivable cancel out until a cheque moves.
    const receivable = page.getByRole('region', { name: /rent receivable/i });
    await expect(receivable.getByText('0.00', { exact: true }).last()).toBeVisible();
  });

  test('the register deposits, clears, returns and replaces a cheque', async ({ page }) => {
    await page.goto('/en/dashboard/finance/cheques');
    await page.waitForLoadState('networkidle');

    const row = (n: string) => page.getByRole('row').filter({ hasText: n });

    await row('E2E-D1').getByRole('checkbox').check();
    await page.getByRole('button', { name: /deposit/i }).click();
    await page.getByRole('button', { name: /confirm/i }).click();
    await expect(row('E2E-D1')).toContainText(/deposited/i);

    await row('E2E-D1').getByRole('button', { name: /clear/i }).click();
    await page.getByRole('button', { name: /confirm/i }).click();
    await expect(row('E2E-D1')).toContainText(/cleared/i);

    await row('E2E-R1').getByRole('checkbox').check();
    await page.getByRole('button', { name: /deposit/i }).click();
    await page.getByRole('button', { name: /confirm/i }).click();
    await row('E2E-R1').getByRole('button', { name: /return|bounce/i }).click();
    await page.getByRole('button', { name: /confirm/i }).click();
    await expect(row('E2E-R1')).toContainText(/bounced|returned/i);

    await row('E2E-R1').getByRole('button', { name: /replace/i }).click();
    await page.getByLabel(/cheque number/i).fill('E2E-R1A');
    await page.getByRole('button', { name: /save|confirm/i }).click();
    await expect(row('E2E-R1A')).toContainText(/registered/i);
  });

  test('the renter ledger shows the returned cheque as a receivable again', async ({ page, testContext }) => {
    await page.goto(`/en/dashboard/finance/tenant-ledger?renterId=${testContext.renterId}`);
    await page.waitForLoadState('networkidle');

    // CBR after clearing: Dr rent receivable / Cr the bank (spec 7.2).
    await expect(page.getByRole('row').filter({ hasText: 'CBR' })).toHaveCount(1);
    await expect(page.getByRole('row').filter({ hasText: 'CRT' }).first()).toBeVisible();
  });

  test('running recognition posts CIL journals dated month-end', async ({ page }) => {
    await page.goto('/en/dashboard/finance/recognition');
    await page.waitForLoadState('networkidle');

    const lastMonthEnd = new Date(new Date().getFullYear(), new Date().getMonth(), 0)
      .toISOString().slice(0, 10);
    await page.getByLabel(/to date|run to/i).fill(lastMonthEnd);
    await page.getByRole('button', { name: /preview/i }).click();
    await expect(page.getByText(/advance rent adjustment/i).first()).toBeVisible();
    await page.getByRole('button', { name: /^run|post/i }).click();

    await page.goto(`/en/dashboard/finance/journals?docType=CIL`);
    await page.waitForLoadState('networkidle');
    const first = page.getByRole('row').filter({ hasText: 'CIL' }).first();
    await expect(first).toBeVisible();
    // Month-end, never the 1st (spec D13).
    await expect(first).not.toContainText(/-01$/);
  });

  test('a journal can be reversed and the trial balance still balances', async ({ page }) => {
    await page.goto('/en/dashboard/finance/journals?docType=JV');
    await page.waitForLoadState('networkidle');

    await page.getByRole('link', { name: /new journal|new jv/i }).click();
    await page.getByLabel(/narration/i).fill('E2E reversible entry');
    await page.getByLabel(/account/i).first().fill('Emirates Islamic');
    await page.getByRole('option').first().click();
    await page.getByLabel(/debit/i).first().fill('1000');
    await page.getByLabel(/account/i).nth(1).fill('Capital');
    await page.getByRole('option').first().click();
    await page.getByLabel(/credit/i).nth(1).fill('1000');
    await page.getByRole('button', { name: /^post$/i }).click();
    await expect(page.getByText(/JV-/)).toBeVisible();

    await page.getByRole('button', { name: /reverse/i }).click();
    await page.getByLabel(/reason/i).fill('e2e');
    await page.getByRole('button', { name: /confirm/i }).click();
    await expect(page.getByText(/reversed/i).first()).toBeVisible();

    await page.goto('/en/dashboard/finance/trial-balance');
    await page.waitForLoadState('networkidle');
    const totals = page.getByRole('row').filter({ hasText: /total/i }).last();
    const cells = await totals.getByRole('cell').allTextContents();
    const nums = cells.map((c) => Number(c.replace(/[^0-9.-]/g, ''))).filter((n) => !Number.isNaN(n) && n !== 0);
    expect(nums.length).toBeGreaterThanOrEqual(2);
    expect(nums[0]).toBeCloseTo(nums[1], 2);
    await expect(page.getByText(/out of balance|does not balance/i)).toHaveCount(0);
  });
});
```

- [ ] **Step 3: Delete the v1 PDC spec**

```bash
git rm web/e2e/finance/payments-pdc.spec.ts
```
It drives `/en/dashboard/finance/payments`, a page that goes away with `payment_schedules`. Its coverage moves to the register test above.

- [ ] **Step 4: Run the dev suite**

```bash
cd web && npm run dev &     # or docker compose up -d
npx playwright test e2e/finance --project=tenant-admin
```
Expected: `accounting-v2.spec.ts` 7 passed, `accounts.spec.ts` 1 passed.

- [ ] **Step 5: Commit**

```bash
git add web/e2e/finance/accounting-v2.spec.ts web/e2e/helpers/api-client.ts web/e2e/global-setup.ts
git rm --cached web/e2e/finance/payments-pdc.spec.ts 2>/dev/null || true
git commit -m "test(e2e): accounting v2 walk from draft contract to a balanced trial balance

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Production suite — `13h-accounting-v2.spec.ts`

The production suite is API-first, sequential, and builds cumulative state in one disposable tenant created by `01-provision.spec.ts` and read from `.test-context.json`. It never uses the UI for state changes. `02-cheque-lifecycle.spec.ts` is the model: read the context file, log in as the role who really does the job, call the same endpoints the product calls, assert the status.

That spec's own header explains why it stops at `deposit`: clearing and bouncing needed account mappings a fresh tenant did not have. Accounting v2 removes that excuse — a property generates its account set on creation — so the new spec closes the gap and the old one shrinks to what it still covers.

**Files:**
- Create: `web/e2e-prod/tests/13h-accounting-v2.spec.ts`
- Modify: `web/e2e-prod/helpers/prod-client.ts` (add v2 helpers, delete v1 finance helpers), `web/e2e-prod/tests/02-cheque-lifecycle.spec.ts`
- Modify: `web/e2e-prod/tests/13-finance-and-settings.spec.ts` (drops `saveAccountMapping` / `createFinancialTransaction`)

**Interfaces:**
- Consumes: the Plan 1–3 HTTP endpoints listed in "Consumed public surfaces".
- Produces: `api.postLease`, `api.getLeaseCheques`, `api.depositCheque`, `api.clearCheque`, `api.bounceCheque`, `api.replaceCheque`, `api.runRecognition`, `api.getRenterLedger`, `api.getTrialBalance`, `api.getPropertyAccounts` on the shared `api` object in `prod-client.ts`, used by this spec and by any later prod spec.

- [ ] **Step 1: Add the v2 helpers to `prod-client.ts`**

Replace `seedAccounts`, `saveAccountMapping`, `getAccountMappings`, `createFinancialTransaction` and `getPaymentScheduleForLease`/`collectPayment`/`depositPayment` with:

```ts
  // ── Accounting v2 ────────────────────────────────────────────────────────
  // Seeds the chart of accounts, the property-account template and the tenant
  // default mappings in one idempotent call (spec 5.3).
  seedAccounts: (pctx: ProdContext) => postJson<void>(pctx, '/v1/finance/accounts/seed', {}),

  getPropertyAccounts: (pctx: ProdContext, propertyId: string) =>
    getJson<Array<{ role: string; accountId: string | null; accountCode: string | null;
                    accountName: string | null; inherited: boolean }>>(
      pctx, `/v1/finance/properties/${propertyId}/accounts`),

  createLease: (
    pctx: ProdContext,
    body: {
      unitId: string; renterId: string; contractNumber: string; contractDate: string;
      startDate: string; endDate: string; gracePeriodDays?: number;
      lines: Array<{ chargeTypeCode: string; grossAmount: number; discountAmount: number; narration: string }>;
      cheques: Array<{ seqNo: number; postingDate: string; chequeNumber: string; chequeDate: string;
                       payeeBank: string; amount: number; narration: string; mode: string }>;
    },
  ) => postJson<{ id: string; status: string; contractValue: number }>(pctx, '/v1/leases', body),

  postLease: (pctx: ProdContext, leaseId: string) =>
    postJson<{ id: string; status: string; postingJournalId: string }>(pctx, `/v1/leases/${leaseId}/post`, {}),

  getLeaseCheques: (pctx: ProdContext, leaseId: string) =>
    getJson<Array<{ id: string; seqNo: number; status: string; amount: number;
                    chequeNumber: string; chequeDate: string; narration: string }>>(
      pctx, `/v1/leases/${leaseId}/cheques`),

  depositCheque: (pctx: ProdContext, chequeId: string, depositDate: string) =>
    postJson<{ id: string; status: string }>(pctx, `/v1/cheques/${chequeId}/deposit`, { depositDate }),

  clearCheque: (pctx: ProdContext, chequeId: string, clearedDate: string) =>
    postJson<{ id: string; status: string; crtJournalId: string }>(
      pctx, `/v1/cheques/${chequeId}/clear`, { clearedDate }),

  bounceCheque: (pctx: ProdContext, chequeId: string, bouncedDate: string, failureReason = 'BOUNCE') =>
    postJson<{ id: string; status: string; cbrJournalId: string }>(
      pctx, `/v1/cheques/${chequeId}/bounce`, { bouncedDate, failureReason }),

  replaceCheque: (
    pctx: ProdContext,
    chequeId: string,
    replacements: Array<{ seqNo: number; postingDate: string; chequeNumber: string; chequeDate: string;
                          payeeBank: string; amount: number; narration: string; mode: string }>,
  ) => postJson<Array<{ id: string; seqNo: number; status: string }>>(
        pctx, `/v1/cheques/${chequeId}/replace`, { replacements }),

  runRecognition: (pctx: ProdContext, to: string) =>
    postJson<{ posted: number; skipped: number }>(pctx, `/v1/finance/recognition/run?to=${to}`, {}),

  getRenterLedger: (pctx: ProdContext, renterId: string, from: string, to: string) =>
    getJson<Array<{ accountName: string; totalDebit: number; totalCredit: number; closingBalance: number;
                    rows: Array<{ entryDate: string; docType: string; particular: string; narration: string;
                                  debit: number; credit: number; balance: number }> }>>(
      pctx, `/v1/finance/ledger/renter/${renterId}?from=${from}&to=${to}`),

  getTrialBalance: (pctx: ProdContext, asOf: string, propertyId?: string) =>
    getJson<Array<{ code: string; name: string; debit: number; credit: number; balance: number }>>(
      pctx, `/v1/finance/trial-balance?asOf=${asOf}${propertyId ? `&propertyId=${propertyId}` : ''}`),

  getJournals: (pctx: ProdContext, q: { docType?: string; leaseId?: string }) =>
    getJson<{ content: Array<{ id: string; entryNumber: string; docType: string; entryDate: string; total: number }> }>(
      pctx, `/v1/finance/journals?size=200${q.docType ? `&docType=${q.docType}` : ''}${q.leaseId ? `&leaseId=${q.leaseId}` : ''}`),

  reverseJournal: (pctx: ProdContext, journalId: string, date: string, reason: string) =>
    postJson<{ id: string; entryNumber: string }>(pctx, `/v1/finance/journals/${journalId}/reverse`, { date, reason }),
```

- [ ] **Step 2: Shrink `02-cheque-lifecycle.spec.ts` to what it still covers**

Its schedule rows no longer exist. Replace the whole file body with a pointer plus the one thing it uniquely tested — that a `PROPERTY_MANAGER`, not only an admin, can bank a cheque:

```ts
/**
 * 02 — A property manager can move a cheque through the register.
 *
 * The full lifecycle (post, deposit, clear, bounce, replace, recognise) lives in
 * 13h-accounting-v2.spec.ts, which runs as ACCOUNTANT. This one keeps the role
 * check that used to be its whole reason for existing: cheque collection is a
 * PROPERTY_MANAGER job in the product, so a PM-only restriction on the cheque
 * controller has to surface somewhere.
 *
 * The v1 note about account mappings blocking clear/bounce is obsolete: a
 * property generates its account set on creation now (spec 5.4).
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { api, loginAsNextAuth, setActiveTenant } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('a property manager can deposit a registered cheque', async () => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  expect(ctx.lease?.id, '01-provision must run first').toBeTruthy();
  expect(ctx.pmEmail, '01-provision must have created a PROPERTY_MANAGER').toBeTruthy();

  const pctx = await loginAsNextAuth(ctx.baseURL, ctx.pmEmail, ctx.pmPassword);
  await setActiveTenant(pctx, ctx.tenant.id);

  const rows = await api.getLeaseCheques(pctx, ctx.lease.id);
  const registered = rows.find((r) => /REGISTERED/i.test(r.status));
  expect(registered, 'posting the lease should have registered the cheque grid').toBeTruthy();

  const deposited = await api.depositCheque(pctx, registered!.id, new Date().toISOString().slice(0, 10));
  expect(deposited.status).toMatch(/DEPOSITED/i);

  await pctx.request.dispose();
});
```

`01-provision.spec.ts` must now create and **post** the lease so the cheques exist — change its `createLease` call to the v2 body (same shape as Task 6 Step 1) and add `await api.postLease(su, lease.id)` right after, storing `ctx.renterId` alongside `ctx.lease`.

- [ ] **Step 3: Write the production spec**

`web/e2e-prod/tests/13h-accounting-v2.spec.ts`:

```ts
/**
 * 13h — Accounting v2 against production.
 *
 * A second contract inside the suite's disposable tenant, driven the way the
 * client's office drives one: post it, bank the cheques, let one come back,
 * replace it, close the month, then check the two invariants that make the
 * ledger trustworthy — the trial balance balances, and a posted contract's
 * renter ledger nets to zero before any money moves (spec 6.4, 12).
 *
 * Conventions follow 02-cheque-lifecycle.spec.ts: read .test-context.json,
 * go through /api/proxy with a NextAuth session, assert on API responses.
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { api, loginAsNextAuth, setActiveTenant, type ProdContext } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

function iso(d: Date): string {
  return d.toISOString().slice(0, 10);
}

test('a contract posts, its cheques clear and bounce, and the month closes', async () => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  expect(ctx.tenant?.id, '01-provision must run first').toBeTruthy();

  const pctx: ProdContext = await loginAsNextAuth(ctx.baseURL, ctx.adminEmail, ctx.adminPassword);
  await setActiveTenant(pctx, ctx.tenant.id);

  try {
    // Every role a contract needs must already resolve for the property.
    const mappings = await api.getPropertyAccounts(pctx, ctx.property.id);
    const mapped = new Set(mappings.filter((m) => m.accountId).map((m) => m.role));
    for (const role of ['RENT_RECEIVABLE', 'ADVANCE_RENT', 'RENTAL_INCOME', 'PDC_RECEIVABLE', 'BANK']) {
      expect(mapped, `role ${role} must resolve or the post is refused`).toContain(role);
    }

    const unit = await api.createUnit(pctx, {
      propertyId: ctx.property.id,
      unitNumber: `ACC-${ctx.runSuffix}`,
    });
    const renter = await api.createRenter(pctx, {
      nameEn: `TEST-Accounting ${ctx.runSuffix}`,
      email: `acct-${ctx.runSuffix}@example.invalid`,
    });

    const year = new Date().getFullYear();
    const contractDate = `${year}-01-01`;
    const lease = await api.createLease(pctx, {
      unitId: unit.id,
      renterId: renter.id,
      contractNumber: `ACC/${ctx.runSuffix}`,
      contractDate,
      startDate: `${year}-01-01`,
      endDate: `${year}-12-31`,
      gracePeriodDays: 5,
      // 60,000 + 12,000 deposit = 72,000, matched exactly by the grid below.
      lines: [
        { chargeTypeCode: 'SECURITY_DEPOSIT', grossAmount: 12000, discountAmount: 0, narration: '' },
        { chargeTypeCode: 'RENT', grossAmount: 60000, discountAmount: 0, narration: '' },
      ],
      cheques: [
        { seqNo: 1, postingDate: contractDate, chequeNumber: `ACC-${ctx.runSuffix}-D`, chequeDate: `${year}-01-05`,
          payeeBank: 'Emirates NBD', amount: 12000, narration: 'Security Deposit', mode: 'PDC' },
        { seqNo: 2, postingDate: contractDate, chequeNumber: `ACC-${ctx.runSuffix}-1`, chequeDate: `${year}-01-05`,
          payeeBank: 'Emirates NBD', amount: 30000, narration: 'Rent - 1st Installment', mode: 'PDC' },
        { seqNo: 3, postingDate: contractDate, chequeNumber: `ACC-${ctx.runSuffix}-2`, chequeDate: `${year}-07-05`,
          payeeBank: 'Emirates NBD', amount: 30000, narration: 'Rent - 2nd Installment', mode: 'PDC' },
      ],
    });
    expect(lease.status).toMatch(/DRAFT/i);
    expect(Number(lease.contractValue)).toBe(72000);

    const posted = await api.postLease(pctx, lease.id);
    expect(posted.status).toMatch(/ACTIVE/i);

    // One TCO, one PDR per cheque row (spec 6.4).
    const journals = await api.getJournals(pctx, { leaseId: lease.id });
    const byType = journals.content.reduce<Record<string, number>>((a, j) => {
      a[j.docType] = (a[j.docType] ?? 0) + 1;
      return a;
    }, {});
    expect(byType.TCO).toBe(1);
    expect(byType.PDR).toBe(3);

    // Before any cheque moves, the renter owes nothing and holds nothing.
    const atPost = await api.getRenterLedger(pctx, renter.id, `${year}-01-01`, `${year}-12-31`);
    const receivable = atPost.find((a) => /rent receivable/i.test(a.accountName));
    expect(receivable, 'the renter ledger must include rent receivable').toBeTruthy();
    expect(Number(receivable!.closingBalance)).toBeCloseTo(0, 2);

    const rows = await api.getLeaseCheques(pctx, lease.id);
    const [deposit, first, second] = rows;

    await api.depositCheque(pctx, deposit.id, `${year}-01-06`);
    const cleared = await api.clearCheque(pctx, deposit.id, `${year}-01-08`);
    expect(cleared.status).toMatch(/CLEARED/i);
    expect(cleared.crtJournalId, 'clearing must post a CRT').toBeTruthy();

    await api.depositCheque(pctx, first.id, `${year}-01-06`);
    await api.clearCheque(pctx, first.id, `${year}-01-08`);
    const bounced = await api.bounceCheque(pctx, first.id, `${year}-01-12`);
    expect(bounced.status).toMatch(/BOUNCED/i);
    expect(bounced.cbrJournalId, 'a return must post a CBR').toBeTruthy();

    const replacements = await api.replaceCheque(pctx, first.id, [
      { seqNo: 90, postingDate: `${year}-01-15`, chequeNumber: `ACC-${ctx.runSuffix}-1R`,
        chequeDate: `${year}-02-05`, payeeBank: 'Emirates NBD', amount: 30000,
        narration: 'Replacement — returned 1st installment', mode: 'PDC' },
    ]);
    expect(replacements).toHaveLength(1);
    expect(replacements[0].status).toMatch(/REGISTERED/i);

    // The returned cheque is owed again; the deposit cheque is money in the bank.
    const afterBounce = await api.getRenterLedger(pctx, renter.id, `${year}-01-01`, `${year}-12-31`);
    const bank = afterBounce.find((a) => /emirates|bank/i.test(a.accountName));
    expect(Number(bank!.closingBalance)).toBeCloseTo(12000, 2);
    expect(afterBounce.flatMap((a) => a.rows).filter((r) => r.docType === 'CBR')).toHaveLength(1);

    // Close every month that has already ended.
    const now = new Date();
    const lastMonthEnd = iso(new Date(now.getFullYear(), now.getMonth(), 0));
    const run = await api.runRecognition(pctx, lastMonthEnd);
    expect(run.posted).toBeGreaterThan(0);

    const cil = await api.getJournals(pctx, { docType: 'CIL', leaseId: lease.id });
    expect(cil.content.length).toBe(run.posted);
    // Month-end, never the 1st (spec D13).
    for (const j of cil.content) {
      expect(j.entryDate.endsWith('-01'), `CIL ${j.entryNumber} is dated the 1st`).toBeFalsy();
    }

    // The invariant that makes everything above trustworthy.
    const tb = await api.getTrialBalance(pctx, iso(now));
    const debit = tb.reduce((a, r) => a + Number(r.debit), 0);
    const credit = tb.reduce((a, r) => a + Number(r.credit), 0);
    expect(debit).toBeCloseTo(credit, 2);
    expect(debit).toBeGreaterThan(0);

    // Reversing the TCO must leave it balanced too.
    const tco = journals.content.find((j) => j.docType === 'TCO')!;
    const reversal = await api.reverseJournal(pctx, tco.id, iso(now), 'e2e reversal check');
    expect(reversal.entryNumber).toMatch(/^TCR-/);
    const tbAfter = await api.getTrialBalance(pctx, iso(now));
    expect(tbAfter.reduce((a, r) => a + Number(r.debit), 0))
      .toBeCloseTo(tbAfter.reduce((a, r) => a + Number(r.credit), 0), 2);
  } finally {
    await pctx.request.dispose();
  }
});
```

- [ ] **Step 4: Run the production suite**

```bash
cd web && npx playwright test --config=e2e-prod/playwright.config.ts
```
Expected: all specs pass. The suite is sequential and cumulative, so run the whole thing — running `13h` alone leaves `.test-context.json` unpopulated.

- [ ] **Step 5: Commit**

```bash
git add web/e2e-prod
git commit -m "test(e2e-prod): cover the accounting v2 contract, cheque and recognition path

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task group D — Walkthrough and tutorials

The walkthrough skill's first instruction is `ls <project>/tutorials/*-adapter.md`, and this repo has never had one — every capability run so far has improvised its surfaces from `tutorials/rentaxis-capability-matrix.md`. Accounting v2 is the worst possible moment to keep improvising: three routes disappear, thirteen appear, and two of the thirty-three existing tutorials describe screens that no longer exist. Task 8 writes the adapter and re-points the route map; Task 9 writes the scripts; Tasks 10–13 record one tutorial each.

**The Iron Law applies: no capture without a passing proof.** A recording task may only run after its route loads and its assertion passes in the same run.

### Task 8: Adapter, capability route map, and the verifiers

**Files:**
- Create: `tutorials/rentaxis-adapter.md`
- Modify: `tutorials/capability-route-map.json`, `tutorials/verify-capability-routes.mjs`, `tutorials/verify-tutorial-library.mjs`
- Test: `node tutorials/verify-capability-routes.mjs`

**Interfaces:**
- Consumes (from plans 1–4): the finance and leasing routes under `web/src/app/[locale]/dashboard/`. The verifier discovers them from the filesystem, so its `unmapped routes:` line is the authority on the exact paths — the list below is what spec §11 implies and what to expect.
- Produces: an adapter every later walkthrough run reads first, and a route map that accepts tutorials 1–37.

- [ ] **Step 1: Raise the tutorial ceiling in both verifiers**

`tutorials/verify-capability-routes.mjs` hard-codes 33 twice. Change:

```js
      if (!Number.isInteger(tutorial) || tutorial < 1 || tutorial > 37) {
```
```js
const missingTutorials = Array.from({ length: 37 }, (_, index) => index + 1)
  .filter((tutorial) => !tutorialCoverage.has(tutorial));
```
```js
  console.log(`\nCapability-route verification passed: ${total} shipped routes mapped to tutorials 01–37.`);
```

`tutorials/verify-tutorial-library.mjs` hard-codes it three times. Change:

```js
const expectedNumbers = Array.from({ length: 37 }, (_, index) =>
  String(index + 1).padStart(2, '0'),
);
```
```js
if (sections.length !== 37) {
  failures.push(`Expected 37 storyboards, found ${sections.length}`);
}
```
```js
  failures.push('Narration directory does not contain exactly the 37 expected tracks');
```
```js
  console.log('\nTutorial library verification passed: 37 complete narrated tutorials.');
```

- [ ] **Step 2: Re-point the three groups whose routes are gone**

In `tutorials/capability-route-map.json`, plans 1 and 2 delete `/[locale]/dashboard/finance/transactions`, `/[locale]/dashboard/finance/reports`, `/[locale]/dashboard/settings/account-mappings` and `/[locale]/dashboard/finance/payments`. A stale mapped route fails the verifier, and a tutorial left with no mapped route fails it too — so 15, 18 and 20 have to land somewhere real.

Replace those four groups with:

```json
   {
    "tutorials": [17],
    "evidence": ["web/e2e/finance/accounts.spec.ts", "web/e2e/finance/accounting-v2.spec.ts"],
    "routes": [
     "/[locale]/dashboard/finance/accounts",
     "/[locale]/dashboard/settings/account-template",
     "/[locale]/dashboard/settings/fiscal",
     "/[locale]/dashboard/settings/charge-types"
    ]
   },
   {
    "tutorials": [18, 34],
    "evidence": ["web/e2e/finance/accounting-v2.spec.ts"],
    "routes": [
     "/[locale]/dashboard/finance/journals",
     "/[locale]/dashboard/finance/journals/[id]",
     "/[locale]/dashboard/finance/journals/new"
    ]
   },
   {
    "tutorials": [20, 37],
    "evidence": [
     "web/e2e/finance/accounting-v2.spec.ts",
     "backend/src/test/java/com/datagami/rentaxis/golden/GoldenLedgerLeBoulevardIT.java"
    ],
    "routes": [
     "/[locale]/dashboard/finance/general-ledger",
     "/[locale]/dashboard/finance/tenant-ledger",
     "/[locale]/dashboard/finance/vendor-ledger",
     "/[locale]/dashboard/finance/trial-balance"
    ]
   },
   {
    "tutorials": [15, 35],
    "evidence": [
     "web/e2e/finance/accounting-v2.spec.ts",
     "web/e2e-prod/tests/13h-accounting-v2.spec.ts"
    ],
    "routes": [
     "/[locale]/dashboard/finance/cheques",
     "/[locale]/dashboard/finance/cheques/collection",
     "/[locale]/dashboard/finance/cheques/return-replace",
     "/[locale]/dashboard/finance/cheques/post-dated",
     "/[locale]/dashboard/finance/penalties"
    ]
   },
   {
    "tutorials": [36],
    "evidence": ["web/e2e-prod/tests/13h-accounting-v2.spec.ts"],
    "routes": ["/[locale]/dashboard/finance/recognition"]
   },
   {
    "tutorials": [19],
    "evidence": ["web/e2e-prod/tests/13-finance-and-settings.spec.ts"],
    "routes": [
     "/[locale]/dashboard/finance/vendors",
     "/[locale]/dashboard/finance/bank-accounts",
     "/[locale]/dashboard/finance/vouchers",
     "/[locale]/dashboard/finance/vouchers/purchase-invoice",
     "/[locale]/dashboard/finance/vouchers/payment"
    ]
   },
   {
    "tutorials": [8],
    "evidence": ["web/e2e-prod/tests/07-bulk-portfolio-import.spec.ts"],
    "routes": [
     "/[locale]/dashboard/finance/opening-balances",
     "/[locale]/dashboard/finance/reconciliation",
     "/[locale]/dashboard/finance/import-batches"
    ]
   }
```

Add `34` to the existing lease group's `tutorials` array (`/[locale]/dashboard/leases`, `/[locale]/dashboard/leases/[id]`).

The voucher, opening-balance, reconciliation and import-batch paths above are copied from Plan 4's own file list, so they are exact. There is no `/finance/vouchers/receipt` — spec §9.3 puts the Cash Receipt Voucher on Plan 2's receipt path, and Plan 4 only reserves `RCP` in the `doc_type` enum. The cheque-register and recognition paths are the only ones still inferred from spec §11, because Plans 2 and 3 are unwritten; step 3 settles them against the filesystem.

- [ ] **Step 3: Run the verifier and reconcile against reality**

Run: `node tutorials/verify-capability-routes.mjs`
Expected: it either passes or prints `web: unmapped routes: …` / `web: stale mapped routes: …`. The filesystem is the authority — if Plan 2 shipped `/finance/cheque-register` rather than `/finance/cheques`, change the **map**, not the app. Repeat until it prints `Capability-route verification passed`.

Mobile surfaces are untouched by this plan (Task Group E hides nav entries; it removes no `GoRoute`), so `manager`, `renter` and `security` must still report `0 unmapped, 0 stale`. If they do not, Task Group E went too far.

- [ ] **Step 4: Write the adapter**

`tutorials/rentaxis-adapter.md`, following `~/.claude/skills/walkthrough/templates/adapter.template.md`:

```markdown
# RentAxis walkthrough adapter

Hand-written for the accounting v2 cut-over; regenerate with `walkthrough-adapter` only on request.

## Environments

| Env | Base URLs | Default |
|---|---|---|
| local | web `http://localhost:3000`, backend `http://localhost:8081` (a stale instance squats on 8080) | yes |
| staging | none — this product has no staging tier | |
| production | `https://rentaxis.uaenorth.cloudapp.azure.com` | gated — needs per-run confirmation |

## Product surfaces

- **web** — Next.js 16 dashboard, renter portal, superadmin and public marketplace. `web/src/app/[locale]/…`. Every operator role and the renter self-service portal live here. This is the only surface accounting v2 changes.
- **manager** — Flutter admin app, `mobile/apps/manager`. Property managers and tenant admins on the move.
- **renter** — Flutter resident app, `mobile/apps/renter`.
- **security** — Flutter guard app, `mobile/apps/security`. Gate passes only.
- **backend** — Spring Boot modular monolith, `backend/`, 27 controllers under `com.datagami.rentaxis.api`.

## Roles

| Role | Can | Must not |
|---|---|---|
| `SUPER_ADMIN` | provision organisations, flip tenant features, pivot into any tenant | be used for a tenant-level tutorial — it hides per-tenant permission behaviour |
| `TENANT_ADMIN` | everything inside one organisation, including finance | see another organisation |
| `ACCOUNTANT` | post and reverse journals, approve penalties, opening balances, period lock, reverse import batches | create leases, renters or properties |
| `PROPERTY_MANAGER` | draft leases, register and deposit cheques, propose penalties | post a lease, post or reverse a journal, open the chart of accounts |
| `TENANT_USER` | read-only operational views | anything financial |
| `RENTER` | own portal: amounts due, approved penalties, pay, read-only tenant ledger | see another renter's ledger |
| `SECURITY_GUARD` | scan and approve gate passes | everything else |

Provisioning: `SUPER_ADMIN` from `DataInitializer` locally (`admin@rentaxis.com` / `admin123`); every other role from `scripts/seed_demo_tenant.py`, which creates the users and sets their passwords through `/api/admin/users`.

## Routes by surface

`tutorials/capability-route-map.json` is the machine-checked list; `node tutorials/verify-capability-routes.mjs` fails if it drifts from the filesystem. Read the map, not a copy of it.

Accounting v2 route groups, for orientation:
- Setup — `/dashboard/finance/accounts`, `/dashboard/settings/account-template`, `/dashboard/settings/charge-types`, `/dashboard/settings/fiscal`, property → **Accounts** tab
- Documents — `/dashboard/leases/[id]` (lines, cheque grid, Post, Recognition schedule, Journals), `/dashboard/finance/journals`, `/dashboard/finance/vouchers/*`
- Register — `/dashboard/finance/cheques` and its collection / return-replace / post-dated views, `/dashboard/finance/penalties`
- Close — `/dashboard/finance/recognition`
- Control views — `/dashboard/finance/general-ledger`, `/tenant-ledger`, `/vendor-ledger`, `/trial-balance`
- Cut-over — `/dashboard/finance/opening-balances`, `/reconciliation`, `/import-batches`

## Seed contract

`scripts/seed_demo_tenant.py`, API-driven and idempotent. Order matters:

1. organisation (`POST /api/admin/tenants`) + `TENANT_ADMIN`
2. chart of accounts, property-account template, tenant default mappings (`POST /api/v1/finance/accounts/seed`)
3. fiscal year + period lock (`PUT /api/v1/finance/fiscal`)
4. properties — each generates its own account set on creation
5. units, renters (with portal logins)
6. leases as DRAFT contracts with `lines[]` and `cheques[]`, then **Post**
7. cheque lifecycle: deposit / clear / bounce / replace
8. month-end recognition to the end of last month
9. vendor + purchase invoice + payment voucher
10. listings, meetings, tickets, bookings, gate passes, promotions

Naming: organisations `TUTORIAL-…` or `VERIFY-…` plus a date and a random suffix; emails on `example.invalid`; renters prefixed `TEST-`. Never reuse the demo tenant's four named renters (Ahmed, Fatima, Rajesh, Sara) for a destructive proof — they are also the App Store reviewer's data.

Output: `scripts/seed_demo_tenant.out.json` (gitignored) with `tenant`, `adminLogin`, `properties`, `units`, `accounts`, `leases`, `leaseStatus`, `cheques`, `recognitionRunTo`, `vouchers`, `renterLogins`. The tutorial recorder reads it via `TUTORIAL_SEED_MANIFEST`.

## API boundaries

| Service | Path prefixes |
|---|---|
| backend, tenant-scoped | `/api/v1/**` |
| backend, superadmin | `/api/admin/**` |
| Next.js proxy (injects `X-User-*` from the NextAuth cookie) | `/api/proxy/**` |
| NextAuth | `/api/auth/**` |

Caddy routes `/api/v1`, `/api/admin` and part of `/api/auth` straight to the backend; everything else must go through `/api/proxy`.

## Auth

- Web: NextAuth credentials. CSRF → `POST /api/auth/callback/credentials` (form-encoded) → session cookie `__Secure-next-auth.session-token` (HTTPS) or `next-auth.session-token` (HTTP). Saved to `web/e2e-prod/.auth/<role>.json`. **That file holds live session tokens; it is a secret and never goes in a commit, a log or a frame.**
- `SUPER_ADMIN` pivots tenant with the `active_tenant_id` cookie, which the proxy forwards as `X-Tenant-Id`.
- Mobile: `POST /api/auth/login`, then `X-User-Id` / `X-User-Role` / `X-Tenant-Id` headers on every call.

## Capture policy

- Web viewport 1440x900, `tutorials/capture/record-tutorial.mjs`, one browser context per tutorial.
- Mobile: iOS Simulator, one `flutter drive` at a time — two concurrent builds flake on destination resolution.
- Never on screen: any real renter's full name or phone number, any bank account number, the contents of `.auth/*.json`, any password field with visible text, the superadmin's email.
- Every recording runs against the seeded demo tenant, never against a customer organisation.

## Test commands

| Scope | Command |
|---|---|
| backend | `cd backend && ./gradlew test` |
| backend, golden ledger only | `cd backend && ./gradlew test -PincludeTags=golden` |
| web unit | `cd web && npx vitest run` |
| web types + lint | `cd web && npx tsc --noEmit && npm run lint` |
| web e2e (dev) | `cd web && npx playwright test e2e` |
| web e2e (production) | `cd web && npx playwright test --config=e2e-prod/playwright.config.ts` |
| route + tutorial verifiers | `node tutorials/verify-capability-routes.mjs && node tutorials/verify-tutorial-library.mjs` |
| mobile | `cd mobile && melos run test` |

## Known hazards

- **Period lock.** A journal dated on or before `books_locked_through` is rejected. Set the fiscal window before any back-dated demo contract, or every post fails with the same unhelpful message.
- **Cheque grid total.** `POST /api/v1/leases` accepts a grid that does not add up; `POST …/post` refuses it. Arithmetic errors surface one step later than you expect.
- **Recognition is precomputed.** Rows exist from the moment a lease posts, but nothing is in the ledger until the job or a manual run posts them. A ledger that looks empty is usually an unrun recognition.
- **Journals are immutable.** There is no edit and no delete, only `reverse`. A bad take that posted a journal cannot be cleaned up by deleting it — reverse it, or delete the whole disposable organisation.
- **Deleting an organisation** (`DELETE /api/admin/tenants/{id}?confirmName=…`) requires the name to match exactly. That is the cleanup path; use it, and account for every ID in the run manifest.
- `flutter build ios --simulator` without `-d <udid>` produces an x86_64 build that will not launch on an arm64 simulator and poisons the cache; `flutter clean` fixes it.
```

- [ ] **Step 5: Run both verifiers**

Run: `node tutorials/verify-capability-routes.mjs`
Expected: `Capability-route verification passed: <n> shipped routes mapped to tutorials 01–37.`

Run: `node tutorials/verify-tutorial-library.mjs`
Expected: FAIL with `Expected 37 storyboards, found 33` — that is Task 9.

- [ ] **Step 6: Commit**

```bash
git add tutorials/rentaxis-adapter.md tutorials/capability-route-map.json \
        tutorials/verify-capability-routes.mjs tutorials/verify-tutorial-library.mjs
git commit -m "docs(tutorials): walkthrough adapter, and re-point the route map onto accounting v2

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: Storyboards, narration and capability matrix for tutorials 34–37

`verify-tutorial-library.mjs` is strict and worth reading before writing a word: a storyboard needs `## NN — Title`, `- Audience: <who>; <n>–<m> minutes.`, `- Capture: …`, `- Narration: “…”`; the narration `.txt` file is **generated** from the storyboard by `extract-narrations.mjs` and its name is the title slugified; and the word count must satisfy `words ≥ 100 × min-minutes` and `min − 0.4 ≤ words/135 ≤ max + 0.5`. For a 2–3 minute tutorial that is 216–472 words. Each script below is ~330.

**Files:**
- Modify: `tutorials/tutorial-storyboards.md` (insert before `## Recording acceptance checklist`), `tutorials/rentaxis-capability-matrix.md`
- Create (generated): `tutorials/narration/34-post-a-tenancy-contract.txt`, `35-register-and-clear-cheques.txt`, `36-month-end-recognition.txt`, `37-tenant-ledger.txt`
- Test: `node tutorials/verify-tutorial-library.mjs`

**Interfaces:**
- Consumes: the routes mapped in Task 8.
- Produces: four narration tracks the recorder in Tasks 10–13 reads, and four storyboard IDs `34`–`37`.

- [ ] **Step 1: Append the four storyboards**

Insert into `tutorials/tutorial-storyboards.md` immediately before `## Recording acceptance checklist`:

```markdown
## 34 — Post a tenancy contract

- Audience: finance admins and property managers; 2–3 minutes.
- Capture: open the prepared draft contract, review its charge lines and cheque
  grid, post it, then show the two journal types the post produced and the
  recognition schedule it planned.
- Narration: “In this tutorial you will turn a draft tenancy contract into
  accounting entries. Open Leases and select the prepared draft. A contract in
  RentAxis has two grids. The first is its charge lines. Each line names what is
  being charged, the account the income or liability is credited to, the gross
  amount, any discount, and the net. Rent, security deposit and administration
  fee are separate lines because they behave differently: rent is recognised
  across the term, a deposit is a liability you hold, and a fee is income the
  day you post. The second grid is the cheque register for this contract.
  Every row carries a posting date, a cheque number, a maturity date, the
  drawer's bank, the amount and a narration such as Rent, first installment.
  Notice that the cheque grid adds up to exactly the contract value. If it does
  not, posting is refused, because the register is how the money is tracked and
  it has to account for all of it. Nothing you have looked at so far has touched
  the ledger. The contract is still a draft, and a draft can be edited freely.
  Now select Post. Confirm the contract date. RentAxis writes one tenancy
  contract journal debiting rent receivable and crediting each line's own
  account, and one post-dated cheque journal for every row of the grid, moving
  the amount from rent receivable into post-dated cheques receivable. Open the
  Journals tab and read them. The contract status is now Active. Open the
  Recognition schedule tab. RentAxis has already planned how the rent will be
  earned, one row per calendar month, using the actual number of days in each.
  Nothing there is posted yet. Posting is deliberate: after a contract is
  posted it is never edited, only amended, which reverses the original and
  writes a fresh one.”

## 35 — Register and clear cheques

- Audience: finance admins and property managers; 2–3 minutes.
- Capture: open the cheque register, filter by status, bank a batch, clear one
  row, mark another returned, and replace the returned cheque with two rows.
- Narration: “This tutorial follows a cheque from the drawer's hand to the bank
  and back again. Open Finance and choose Cheques. Every cheque from every
  posted contract is here, with its status, its maturity date, the property and
  the renter. Use the filters to narrow the list to registered cheques maturing
  this month. Registered means the cheque is recorded and its journal is
  written, but the paper has not left the office. Select the rows you are taking
  to the bank and choose Cheque and cash collection. Enter the deposit date and
  the account you are banking into, then confirm. The selected rows move to
  deposited. Depositing is an operational step and writes no accounting entry,
  because nothing has changed about what you are owed. When the bank confirms a
  cheque, open its row and choose Clear. Enter the value date the bank gave you.
  RentAxis debits your bank account and credits post-dated cheques receivable.
  That is the moment the money becomes yours. Now take a cheque the bank has
  returned. Open its row and choose Mark returned, give the reason, and confirm.
  Because this cheque had already cleared, RentAxis reverses the bank side:
  it debits rent receivable and credits the bank. The amount is owed again, and
  the register shows it as due. Open Return and replace. A returned cheque is
  usually settled with new paper, and often more than one. Add two replacement
  rows, each with its own number, maturity date and amount, then save. Each
  replacement registers its own journal. If the replacements do not add up to
  the returned amount, the difference stays in rent receivable rather than
  disappearing. Penalties are separate and never automatic: a returned cheque
  proposes a penalty that finance approves, waives or reverses from the
  Penalties queue.”

## 36 — Month-end recognition

- Audience: finance admins; 2–3 minutes.
- Capture: show a contract's planned recognition schedule, preview a run to a
  chosen date, post it, then find the resulting journals in the general ledger.
- Narration: “Rent is collected in a handful of cheques but earned every day, so
  this tutorial closes a month. Start on a posted contract and open its
  Recognition schedule tab. RentAxis divided the term by its actual number of
  days to get a daily rate, then multiplied that rate by the real number of days
  in each calendar month. A twelve month contract that starts mid month
  therefore opens with a short period and closes with another, and the last row
  absorbs any rounding so the schedule adds up to the rent exactly. Every row
  shows its period, its day count, its amount and its status. Rows are planned
  until they are posted. Now open Finance and choose Recognition. Enter the date
  you are closing to, normally the last day of last month, and select Preview.
  RentAxis lists every planned row across every contract that ends on or before
  that date, with a total. Read the total before you post it; this is the rental
  income you are about to recognise for the period. Select Run. Each row becomes
  one journal dated the last day of its own period, debiting advance rent and
  crediting rental income, with the narration Advance rent adjustment and the
  month. Dating these entries at the period end rather than the first of the
  next month is deliberate, so a month's income falls inside the month it was
  earned in. Open the General Ledger and filter to the advance rent account. The
  balance falls by exactly what you just recognised, and the rental income
  account rises by the same amount. Recognition also runs automatically each
  night, so a backdated contract catches up on its own. Running it by hand is
  for closing a period on purpose, and for the first catch-up after you move
  your books across.”

## 37 — Tenant ledger

- Audience: finance admins and accountants; 2–3 minutes.
- Capture: open a renter's ledger, read each account, explain the running
  balance, drill into a journal, and compare the trial balance totals.
- Narration: “This tutorial reads a renter's account the way an accountant
  does. Open Finance and choose Tenant Ledger, then select the prepared renter.
  RentAxis shows one block per ledger account the renter has touched, and inside
  each block one row per entry. Every row carries the document date, the
  document number, the account on the other side of the entry, the narration,
  the debit or the credit, and the running balance after it. Start with rent
  receivable. The tenancy contract debited it with the whole contract value,
  then one post-dated cheque entry per cheque credited it back, so immediately
  after posting the balance is zero. That is correct and it is the point: what
  the renter owes lives in the cheque register, not in this account. A balance
  appears here only when something goes wrong, such as a returned cheque, which
  debits it again. Read the post-dated cheques receivable block. It rises with
  every cheque registered and falls with every cheque cleared, so its balance is
  the paper you are still holding. Read advance rent. The contract credited the
  whole year, and each month-end recognition debits back the portion earned, so
  it winds down to zero across the term. Select any document number to open the
  journal behind it. A journal shows every line, always balancing, and it cannot
  be edited or deleted, only reversed, which writes a mirror entry and links the
  two. Finally open Trial Balance. Choose a date and confirm that total debits
  equal total credits. That single check is what tells you the ledger behind
  every screen in this tutorial is sound.”
```

- [ ] **Step 2: Generate the narration tracks**

Run: `node tutorials/extract-narrations.mjs`
Expected: four new files in `tutorials/narration/` named `34-post-a-tenancy-contract.txt`, `35-register-and-clear-cheques.txt`, `36-month-end-recognition.txt`, `37-tenant-ledger.txt`.

- [ ] **Step 3: Add the four capability-matrix rows**

`verify-tutorial-library.mjs` requires `tutorials/rentaxis-capability-matrix.md` to contain rows numbered exactly `01.`–`37.`. Append four rows in the same shape as the existing ones:

```markdown
| 34. Post a tenancy contract | Web | TENANT_ADMIN, ACCOUNTANT | `/dashboard/leases/[id]` | Draft contract with lines and a cheque grid posts one TCO and one PDR per cheque; status becomes Active and the recognition schedule is planned |
| 35. Register and clear cheques | Web | PROPERTY_MANAGER, ACCOUNTANT | `/dashboard/finance/cheques` | A cheque banks, clears with a CRT, is returned with a CBR, and is replaced by two rows that each register their own PDR |
| 36. Month-end recognition | Web | ACCOUNTANT | `/dashboard/finance/recognition` | Running to a cut-off posts one CIL per planned period, dated the period end; advance rent falls and rental income rises by the same total |
| 37. Tenant ledger | Web | TENANT_ADMIN, ACCOUNTANT | `/dashboard/finance/tenant-ledger` | A posted contract's renter ledger nets to zero on rent receivable; a returned cheque reopens it; trial balance debits equal credits |
```

- [ ] **Step 4: Run the verifier**

Run: `node tutorials/verify-tutorial-library.mjs`
Expected: `Tutorial library verification passed: 37 complete narrated tutorials.` If a word count falls outside the band the verifier prints the calibrated minutes — adjust the storyboard narration, re-run `extract-narrations.mjs`, re-verify.

- [ ] **Step 5: Retire the two obsolete scripts**

Tutorials 17 and 18 still describe account **mappings** and the **financial transactions** screen, neither of which exists. Rewrite their storyboard `- Capture:` and `- Narration:` blocks in place for the v2 screens — 17 becomes the chart of accounts plus the property account template and the charge-type catalogue; 18 becomes journal vouchers, reading a journal and reversing one. Re-run `extract-narrations.mjs` and the verifier after each. Their existing MP4s in `tutorials/output/` are now wrong and are re-recorded in a follow-up run, not this plan; note that in the PR body.

- [ ] **Step 6: Commit**

```bash
git add tutorials/tutorial-storyboards.md tutorials/rentaxis-capability-matrix.md tutorials/narration
git commit -m "docs(tutorials): scripts for posting contracts, cheques, recognition and the tenant ledger

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: Record tutorial 34 — Post a tenancy contract

`tutorials/capture/record-tutorial.mjs` drives the capture. A tutorial is an array of scenes under `scenarios['NN']`; each scene is `roleRouteScene(role, pathname, title, body, { weight, afterNavigation })`, `weight` splits the narration's running time between scenes, and `afterNavigation` is where a scene does something rather than just landing on a page. The recorder reads IDs from the seed manifest, so Task 5's `out.json` keys are the contract between the seed and the recording.

**Files:**
- Modify: `tutorials/capture/record-tutorial.mjs` (`scenarios['34']`, `roleByTutorial['34']`)
- Output: `tutorials/output/34-post-a-tenancy-contract.mp4` + `.srt`, QA evidence under `tutorials/qa/review/34/`

**Interfaces:**
- Consumes: `seed.leases.sara` (the DRAFT contract Task 5 leaves unposted — this is the only tutorial that can post it, so it records before 35–37), `seed.tenant.id`, `seed.tenant.name`.
- Produces: an approved MP4. Posting Sara's lease is a **state change**: after this recording the seed manifest's `leaseStatus.sara` is `ACTIVE`, so re-running the recording requires re-seeding a fresh draft.

- [ ] **Step 1: Prove the flow before recording anything**

Run: `cd web && npx playwright test e2e/finance/accounting-v2.spec.ts --project=tenant-admin -g "posting the contract"`
Expected: PASS. **If it fails, stop.** Fix the product, re-prove, then record. No capture without a passing proof.

- [ ] **Step 2: Add the scenario**

In `tutorials/capture/record-tutorial.mjs`, next to the other `scenarios` entries add:

```js
  '34': [
    roleRouteScene('tenantAdmin', `/en/dashboard/leases/${draftLeaseId}`, 'Open the draft contract',
      'A tenancy contract starts as a draft. Nothing it holds has reached the ledger yet, so it can still be edited freely.', {
      weight: 70,
    }),
    roleRouteScene('tenantAdmin', `/en/dashboard/leases/${draftLeaseId}`, 'Read the charge lines',
      'Rent, security deposit and administration fee are separate lines because each is credited to its own account and each behaves differently.', {
      weight: 70,
      afterNavigation: async (page) => {
        await page.getByRole('tab', { name: 'Lines' }).click();
        await page.getByRole('row').filter({ hasText: 'Rent' }).first().scrollIntoViewIfNeeded();
      },
    }),
    roleRouteScene('tenantAdmin', `/en/dashboard/leases/${draftLeaseId}`, 'Read the cheque grid',
      'The grid records every cheque the renter handed over. It has to add up to the contract value or the contract will not post.', {
      weight: 60,
      afterNavigation: async (page) => {
        await page.getByRole('tab', { name: 'Cheques' }).click();
        await page.getByRole('row').filter({ hasText: 'Rent - 1st Installment' }).scrollIntoViewIfNeeded();
      },
    }),
    roleRouteScene('tenantAdmin', `/en/dashboard/leases/${draftLeaseId}`, 'Post the contract',
      'Posting writes the accounting entries and makes the contract active.', {
      weight: 60,
      afterNavigation: async (page) => {
        await page.getByRole('button', { name: 'Post', exact: true }).click();
        await page.getByRole('button', { name: /confirm|post/i }).last().click();
        await page.getByText(/active/i).first().waitFor({ state: 'visible', timeout: 20_000 });
      },
    }),
    roleRouteScene('tenantAdmin', `/en/dashboard/leases/${draftLeaseId}`, 'Read the journals it wrote',
      'One tenancy contract journal, and one post-dated cheque journal for every row of the grid.', {
      weight: 40,
      afterNavigation: async (page) => {
        await page.getByRole('tab', { name: /journals/i }).click();
        await page.getByRole('row').filter({ hasText: 'TCO' }).first().waitFor({ state: 'visible' });
      },
    }),
    roleRouteScene('tenantAdmin', `/en/dashboard/leases/${draftLeaseId}`, 'Read the planned recognition',
      'The rent is already divided across the term by its real day count. None of it is posted yet.', {
      weight: 30,
      afterNavigation: async (page) => {
        await page.getByRole('tab', { name: /recognition/i }).click();
        await page.getByRole('row').nth(1).waitFor({ state: 'visible' });
      },
    }),
  ],
```

and define the ID next to the other seed lookups near `const towerId = seed.properties?.tower;`:

```js
const draftLeaseId = seed.leases?.sara;
const postedLeaseId = seed.leases?.ahmed;
const ledgerRenterId = seed.renterIds?.ahmed;
```

(Add `out["renterIds"] = {"ahmed": ahmed["id"], "fatima": fatima["id"], "rajesh": rajesh["id"], "sara": sara["id"]}` to `scripts/seed_demo_tenant.py` next to `out["leases"]` — Tasks 11–13 need it too.)

Add to `roleByTutorial`:

```js
  '34': 'tenantAdmin',
```

- [ ] **Step 3: Dry-run the scenes without recording**

Run:
```bash
cd /Users/kunalsharma/datagami/rentaxis && \
TUTORIAL_CAPTURE_VALIDATE_ONLY=1 \
TUTORIAL_SEED_MANIFEST=scripts/seed_demo_tenant.out.json \
node tutorials/capture/record-tutorial.mjs 34 \
  tutorials/narration/34-post-a-tenancy-contract.txt /dev/null 125
```
Expected: every scene navigates and every `afterNavigation` selector resolves. A selector miss here is cheap; a selector miss during capture wastes the take.

- [ ] **Step 4: Record**

Run: `cd /Users/kunalsharma/datagami/rentaxis && TUTORIAL_SEED_MANIFEST=scripts/seed_demo_tenant.out.json tutorials/capture-tutorial.sh 34`
Expected: `tutorials/output/34-post-a-tenancy-contract.mp4` and `.srt`.

- [ ] **Step 5: QA the take**

```bash
mkdir -p tutorials/qa/review/34
ffprobe -v error -show_entries format=duration,size -of default=nw=1 \
  tutorials/output/34-post-a-tenancy-contract.mp4
for t in 5 40 80 120 145; do
  ffmpeg -loglevel error -y -ss "$t" -i tutorials/output/34-post-a-tenancy-contract.mp4 \
    -frames:v 1 "tutorials/qa/review/34/34-frame-${t}s.png"
done
head -20 tutorials/output/34-post-a-tenancy-contract.srt
```
Check every frame: 1920x1080, no blank or loading state, no error banner, the English locale, no renter phone number or bank account number on screen, and the subtitle at that timestamp describing what is visible. Any failure → write `tutorials/qa/review/34/REJECTED.md` saying which frame and why, re-seed, re-record. Never overwrite a rejected take silently.

- [ ] **Step 6: Commit**

```bash
git add tutorials/capture/record-tutorial.mjs scripts/seed_demo_tenant.py \
        tutorials/output/34-post-a-tenancy-contract.mp4 tutorials/output/34-post-a-tenancy-contract.srt \
        tutorials/qa/review/34
git commit -m "docs(tutorials): record 34 — post a tenancy contract

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 11: Record tutorial 35 — Register and clear cheques

**Files:**
- Modify: `tutorials/capture/record-tutorial.mjs` (`scenarios['35']`, `roleByTutorial['35']`)
- Output: `tutorials/output/35-register-and-clear-cheques.mp4` + `.srt`, QA evidence under `tutorials/qa/review/35/`

**Interfaces:**
- Consumes: `seed.cheques.fatima` — Task 5 leaves Fatima's third cheque bounced and replaced, so the register already has a row in every status this tutorial names. The recording only *reads* the returned row and demonstrates banking on rows Task 5 left registered; it does not create the bounce on camera, because a bounce cannot be undone for a retake.
- Produces: an approved MP4. Deposits two registered rows, which is reversible by re-seeding.

- [ ] **Step 1: Prove the flow before recording anything**

Run: `cd web && npx playwright test e2e/finance/accounting-v2.spec.ts --project=tenant-admin -g "the register deposits"`
Expected: PASS. If it fails, fix and re-prove before recording.

- [ ] **Step 2: Add the scenario**

```js
  '35': [
    roleRouteScene('tenantAdmin', '/en/dashboard/finance/cheques', 'Open the cheque register',
      'Every cheque from every posted contract, with its status, its maturity date, the property and the renter.', {
      weight: 70,
    }),
    roleRouteScene('tenantAdmin', '/en/dashboard/finance/cheques', 'Filter to what matures now',
      'Registered means the cheque is recorded and its journal is written, but the paper has not left the office.', {
      weight: 55,
      afterNavigation: async (page) => {
        await page.getByLabel(/status/i).selectOption({ label: 'Registered' });
        await page.getByRole('row').nth(1).waitFor({ state: 'visible' });
      },
    }),
    roleRouteScene('tenantAdmin', '/en/dashboard/finance/cheques/collection', 'Bank a batch',
      'Selecting rows and entering a deposit date moves them to deposited. Nothing is posted, because nothing has changed about what you are owed.', {
      weight: 65,
      afterNavigation: async (page) => {
        await page.getByRole('row').nth(1).getByRole('checkbox').check();
        await page.getByRole('row').nth(2).getByRole('checkbox').check();
        await page.getByRole('button', { name: /deposit|collect/i }).click();
        await page.getByRole('button', { name: /confirm/i }).click();
        await page.getByText(/deposited/i).first().waitFor({ state: 'visible', timeout: 15_000 });
      },
    }),
    roleRouteScene('tenantAdmin', '/en/dashboard/finance/cheques', 'Clear a cheque',
      'When the bank confirms, clearing debits your bank account and credits post-dated cheques receivable. That is when the money becomes yours.', {
      weight: 60,
      afterNavigation: async (page) => {
        const row = page.getByRole('row').filter({ hasText: /deposited/i }).first();
        await row.getByRole('button', { name: /clear/i }).click();
        await page.getByRole('button', { name: /confirm/i }).click();
        await page.getByText(/cleared/i).first().waitFor({ state: 'visible', timeout: 15_000 });
      },
    }),
    roleRouteScene('tenantAdmin', '/en/dashboard/finance/cheques/return-replace', 'Read a returned cheque',
      'A cheque the bank sent back is owed again. Its replacements each register their own journal, and any shortfall stays on the renter.', {
      weight: 50,
      afterNavigation: async (page) => {
        await page.getByRole('row').filter({ hasText: /bounced|returned/i }).first()
          .scrollIntoViewIfNeeded();
      },
    }),
    roleRouteScene('tenantAdmin', '/en/dashboard/finance/penalties', 'Penalties are never automatic',
      'A returned cheque proposes a penalty. Finance approves, waives or reverses it from this queue.', {
      weight: 30,
    }),
  ],
```

Add to `roleByTutorial`:

```js
  '35': 'tenantAdmin',
```

- [ ] **Step 3: Dry-run the scenes without recording**

```bash
cd /Users/kunalsharma/datagami/rentaxis && \
TUTORIAL_CAPTURE_VALIDATE_ONLY=1 \
TUTORIAL_SEED_MANIFEST=scripts/seed_demo_tenant.out.json \
node tutorials/capture/record-tutorial.mjs 35 \
  tutorials/narration/35-register-and-clear-cheques.txt /dev/null 125
```
Expected: every scene navigates and every selector resolves.

- [ ] **Step 4: Record**

Run: `cd /Users/kunalsharma/datagami/rentaxis && TUTORIAL_SEED_MANIFEST=scripts/seed_demo_tenant.out.json tutorials/capture-tutorial.sh 35`

- [ ] **Step 5: QA the take**

```bash
mkdir -p tutorials/qa/review/35
ffprobe -v error -show_entries format=duration,size -of default=nw=1 \
  tutorials/output/35-register-and-clear-cheques.mp4
for t in 5 40 80 120 145; do
  ffmpeg -loglevel error -y -ss "$t" -i tutorials/output/35-register-and-clear-cheques.mp4 \
    -frames:v 1 "tutorials/qa/review/35/35-frame-${t}s.png"
done
head -20 tutorials/output/35-register-and-clear-cheques.srt
```
Same checks as tutorial 34, plus one specific to this recording: **no cheque image is opened on camera.** A scanned cheque carries the drawer's account number and signature. Any failure → `tutorials/qa/review/35/REJECTED.md`, re-seed, re-record.

- [ ] **Step 6: Commit**

```bash
git add tutorials/capture/record-tutorial.mjs \
        tutorials/output/35-register-and-clear-cheques.mp4 tutorials/output/35-register-and-clear-cheques.srt \
        tutorials/qa/review/35
git commit -m "docs(tutorials): record 35 — register and clear cheques

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 12: Record tutorial 36 — Month-end recognition

**Files:**
- Modify: `tutorials/capture/record-tutorial.mjs` (`scenarios['36']`, `roleByTutorial['36']`)
- Output: `tutorials/output/36-month-end-recognition.mp4` + `.srt`, QA evidence under `tutorials/qa/review/36/`

**Interfaces:**
- Consumes: `seed.leases.ahmed` (posted, so it has a recognition schedule) and `seed.recognitionRunTo`. Task 5 runs recognition to the end of **last** month, which leaves the current month unposted — that is what this tutorial runs on camera, so there is always something to post and the take is repeatable as long as the recording happens in the same month as the seed.
- Produces: an approved MP4. Posts the current month's CIL journals, which is undone by reversing them or by re-seeding a fresh tenant.

- [ ] **Step 1: Prove the flow before recording anything**

Run: `cd web && npx playwright test e2e/finance/accounting-v2.spec.ts --project=tenant-admin -g "running recognition"`
Expected: PASS. If it fails, fix and re-prove before recording.

- [ ] **Step 2: Add the scenario**

```js
  '36': [
    roleRouteScene('tenantAdmin', `/en/dashboard/leases/${postedLeaseId}`, 'Read the recognition schedule',
      'The term is divided by its real day count to get a daily rate, then multiplied by the real number of days in each month.', {
      weight: 80,
      afterNavigation: async (page) => {
        await page.getByRole('tab', { name: /recognition/i }).click();
        await page.getByRole('row').nth(1).waitFor({ state: 'visible' });
      },
    }),
    roleRouteScene('tenantAdmin', `/en/dashboard/leases/${postedLeaseId}`, 'A short first period and a rounding row',
      'A contract that starts mid month opens short and closes short, and the last row absorbs the rounding so the schedule adds up exactly.', {
      weight: 55,
      afterNavigation: async (page) => {
        await page.getByRole('tab', { name: /recognition/i }).click();
        await page.getByRole('row').last().scrollIntoViewIfNeeded();
      },
    }),
    roleRouteScene('tenantAdmin', '/en/dashboard/finance/recognition', 'Preview the close',
      'Entering a cut-off date lists every planned period that ends on or before it, with a total. Read the total before posting it.', {
      weight: 70,
      afterNavigation: async (page) => {
        const monthEnd = new Date(new Date().getFullYear(), new Date().getMonth() + 1, 0)
          .toISOString().slice(0, 10);
        await page.getByLabel(/to date|run to/i).fill(monthEnd);
        await page.getByRole('button', { name: /preview/i }).click();
        await page.getByText(/advance rent adjustment/i).first().waitFor({ state: 'visible' });
      },
    }),
    roleRouteScene('tenantAdmin', '/en/dashboard/finance/recognition', 'Run it',
      'Each period becomes one journal dated the last day of that period, debiting advance rent and crediting rental income.', {
      weight: 55,
      afterNavigation: async (page) => {
        const monthEnd = new Date(new Date().getFullYear(), new Date().getMonth() + 1, 0)
          .toISOString().slice(0, 10);
        await page.getByLabel(/to date|run to/i).fill(monthEnd);
        await page.getByRole('button', { name: /preview/i }).click();
        await page.getByRole('button', { name: /^run|post/i }).click();
        await page.getByText(/posted/i).first().waitFor({ state: 'visible', timeout: 20_000 });
      },
    }),
    roleRouteScene('tenantAdmin', '/en/dashboard/finance/general-ledger', 'Watch advance rent fall',
      'The advance rent balance drops by exactly what was recognised, and rental income rises by the same amount.', {
      weight: 45,
      afterNavigation: async (page) => {
        await page.getByLabel(/account/i).first().fill('Advance Rent');
        await page.getByRole('option').first().click();
        await page.getByRole('row').nth(1).waitFor({ state: 'visible' });
      },
    }),
    roleRouteScene('tenantAdmin', '/en/dashboard/finance/journals', 'It also runs nightly',
      'A backdated contract catches up on its own. Running it by hand is for closing a period on purpose.', {
      weight: 25,
      afterNavigation: async (page) => {
        await page.getByLabel(/document type/i).selectOption('CIL');
        await page.getByRole('row').nth(1).waitFor({ state: 'visible' });
      },
    }),
  ],
```

Add to `roleByTutorial`:

```js
  '36': 'tenantAdmin',
```

- [ ] **Step 3: Dry-run the scenes without recording**

```bash
cd /Users/kunalsharma/datagami/rentaxis && \
TUTORIAL_CAPTURE_VALIDATE_ONLY=1 \
TUTORIAL_SEED_MANIFEST=scripts/seed_demo_tenant.out.json \
node tutorials/capture/record-tutorial.mjs 36 \
  tutorials/narration/36-month-end-recognition.txt /dev/null 125
```
Expected: every scene navigates and every selector resolves. The preview scene runs twice (once to preview, once to run) — confirm the second run still finds planned rows; if the first dry run already posted them, re-seed before recording.

- [ ] **Step 4: Record**

Run: `cd /Users/kunalsharma/datagami/rentaxis && TUTORIAL_SEED_MANIFEST=scripts/seed_demo_tenant.out.json tutorials/capture-tutorial.sh 36`

- [ ] **Step 5: QA the take**

```bash
mkdir -p tutorials/qa/review/36
ffprobe -v error -show_entries format=duration,size -of default=nw=1 \
  tutorials/output/36-month-end-recognition.mp4
for t in 5 40 80 120 145; do
  ffmpeg -loglevel error -y -ss "$t" -i tutorials/output/36-month-end-recognition.mp4 \
    -frames:v 1 "tutorials/qa/review/36/36-frame-${t}s.png"
done
head -20 tutorials/output/36-month-end-recognition.srt
```
Same checks as tutorial 34, plus one specific to this recording: **every visible CIL entry date is a month end**, never the 1st. If the product dates them on the 1st the recording is documenting a bug — stop, fix spec D13 in Plan 3, re-prove, re-record. Any failure → `tutorials/qa/review/36/REJECTED.md`.

- [ ] **Step 6: Commit**

```bash
git add tutorials/capture/record-tutorial.mjs \
        tutorials/output/36-month-end-recognition.mp4 tutorials/output/36-month-end-recognition.srt \
        tutorials/qa/review/36
git commit -m "docs(tutorials): record 36 — month-end recognition

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 13: Record tutorial 37 — Tenant ledger

**Files:**
- Modify: `tutorials/capture/record-tutorial.mjs` (`scenarios['37']`, `roleByTutorial['37']`)
- Output: `tutorials/output/37-tenant-ledger.mp4` + `.srt`, QA evidence under `tutorials/qa/review/37/`

**Interfaces:**
- Consumes: `seed.renterIds.fatima` — Fatima's ledger is the interesting one because Task 5 leaves her with a cleared cheque, a returned cheque and two replacements, so rent receivable does not sit flat at zero. Read-only; nothing in this recording changes state, which makes it the safest of the four to retake.
- Produces: an approved MP4.

- [ ] **Step 1: Prove the flow before recording anything**

Run: `cd web && npx playwright test e2e/finance/accounting-v2.spec.ts --project=tenant-admin -g "the renter ledger shows the returned cheque"`
Expected: PASS. If it fails, fix and re-prove before recording.

- [ ] **Step 2: Add the scenario**

```js
  '37': [
    roleRouteScene('tenantAdmin', `/en/dashboard/finance/tenant-ledger?renterId=${ledgerRenterId}`,
      'Open a renter ledger',
      'One block per account the renter has touched, and inside each one row per entry with its document, its counter account and a running balance.', {
      weight: 80,
    }),
    roleRouteScene('tenantAdmin', `/en/dashboard/finance/tenant-ledger?renterId=${ledgerRenterId}`,
      'Rent receivable nets to zero',
      'The contract debited the whole value and the cheque journals credited it back, so what the renter owes lives in the register, not here.', {
      weight: 65,
      afterNavigation: async (page) => {
        await page.getByRole('region', { name: /rent receivable/i }).scrollIntoViewIfNeeded();
      },
    }),
    roleRouteScene('tenantAdmin', `/en/dashboard/finance/tenant-ledger?renterId=${ledgerRenterId}`,
      'Until a cheque comes back',
      'A returned cheque debits rent receivable again. That balance is the only thing standing between this renter and a clean account.', {
      weight: 55,
      afterNavigation: async (page) => {
        await page.getByRole('row').filter({ hasText: 'CBR' }).first().scrollIntoViewIfNeeded();
      },
    }),
    roleRouteScene('tenantAdmin', `/en/dashboard/finance/tenant-ledger?renterId=${ledgerRenterId}`,
      'Post-dated cheques and advance rent',
      'One block holds the paper you still have; the other winds down to zero as the rent is earned month by month.', {
      weight: 55,
      afterNavigation: async (page) => {
        await page.getByRole('region', { name: /advance rent/i }).scrollIntoViewIfNeeded();
      },
    }),
    roleRouteScene('tenantAdmin', `/en/dashboard/finance/tenant-ledger?renterId=${ledgerRenterId}`,
      'Open the journal behind a row',
      'A journal always balances, and it is never edited or deleted, only reversed, which writes a mirror entry and links the two.', {
      weight: 45,
      afterNavigation: async (page) => {
        await page.getByRole('link', { name: /^TCO-/ }).first().click();
        await page.getByText(/reverse/i).first().waitFor({ state: 'visible' });
      },
    }),
    roleRouteScene('tenantAdmin', '/en/dashboard/finance/trial-balance', 'Check the whole ledger',
      'Total debits equal total credits. That single check is what tells you the ledger behind every screen is sound.', {
      weight: 30,
      afterNavigation: async (page) => {
        await page.getByRole('row').filter({ hasText: /total/i }).last().scrollIntoViewIfNeeded();
      },
    }),
  ],
```

Add to `roleByTutorial`:

```js
  '37': 'tenantAdmin',
```

and point `ledgerRenterId` at Fatima rather than Ahmed, since hers is the ledger with the returned cheque:

```js
const ledgerRenterId = seed.renterIds?.fatima;
```

(Task 10's scenario does not use `ledgerRenterId`, so this is safe to change here.)

- [ ] **Step 3: Dry-run the scenes without recording**

```bash
cd /Users/kunalsharma/datagami/rentaxis && \
TUTORIAL_CAPTURE_VALIDATE_ONLY=1 \
TUTORIAL_SEED_MANIFEST=scripts/seed_demo_tenant.out.json \
node tutorials/capture/record-tutorial.mjs 37 \
  tutorials/narration/37-tenant-ledger.txt /dev/null 125
```
Expected: every scene navigates and every selector resolves.

- [ ] **Step 4: Record**

Run: `cd /Users/kunalsharma/datagami/rentaxis && TUTORIAL_SEED_MANIFEST=scripts/seed_demo_tenant.out.json tutorials/capture-tutorial.sh 37`

- [ ] **Step 5: QA the take**

```bash
mkdir -p tutorials/qa/review/37
ffprobe -v error -show_entries format=duration,size -of default=nw=1 \
  tutorials/output/37-tenant-ledger.mp4
for t in 5 40 80 120 145; do
  ffmpeg -loglevel error -y -ss "$t" -i tutorials/output/37-tenant-ledger.mp4 \
    -frames:v 1 "tutorials/qa/review/37/37-frame-${t}s.png"
done
head -20 tutorials/output/37-tenant-ledger.srt
```
Same checks as tutorial 34, plus one specific to this recording: the renter's **email address and phone number must not be readable** in any frame — the tenant-ledger header shows the renter's contact details, so if they are on screen, crop the scene by scrolling past the header before the hold. Any failure → `tutorials/qa/review/37/REJECTED.md`.

- [ ] **Step 6: Run the whole tutorial library verifier one more time**

Run: `node tutorials/verify-tutorial-library.mjs && node tutorials/verify-capability-routes.mjs`
Expected: both pass. All four new tutorials are now `approved`, not merely `captured`.

- [ ] **Step 7: Commit**

```bash
git add tutorials/capture/record-tutorial.mjs \
        tutorials/output/37-tenant-ledger.mp4 tutorials/output/37-tenant-ledger.srt \
        tutorials/qa/review/37
git commit -m "docs(tutorials): record 37 — tenant ledger

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task group E — Hide the mobile finance screens

Spec D7 and §11: "Mobile apps are not changed in v2; their finance/lease screens are hidden behind a *coming soon* flag until the web is stable." The Flutter apps call `/v1/finance/transactions`, `/v1/finance/reports/*` and `/v1/payments/*` — all of which Plans 1 and 2 delete. Left alone they would show Dio errors on every finance tab the day v2 ships.

**This group hides screens. It does not rewrite them, and it deletes no `GoRoute`** — `tutorials/verify-capability-routes.mjs` discovers mobile routes from `router.dart` and would fail on a stale mapping, and Plan 6 will want the screens back.

One trap worth naming up front: `LeaseService` is used by **non-lease** features in both apps — the manager's meeting picker (`create_meeting_screen.dart:15`), the renter's ticket and meeting unit pickers, and the renter's gate-pass unit resolution (`providers/gate_pass_provider.dart:79`). The flag gates **screens and navigation entries**, never the service class.

### Task 14: `TenantFeature.MOBILE_FINANCE`

`tenant_feature.feature` is `varchar(100)` with no CHECK and no Postgres enum type (changeset `38-tenant-feature.yaml`), and there is no seed or backfill — a tenant with no row reads the enum's own default. So a new constant needs no migration. `TenantFeatureGatePassIT` exists precisely to make that claim checkable rather than asserted; this task does the same for the new flag.

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/TenantFeature.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/TenantFeatureService.java` (the `toLabel` switch)
- Modify: `web/e2e-prod/helpers/prod-client.ts` (`setTenantFeature`'s union type)
- Create: `backend/src/test/java/com/datagami/rentaxis/core/service/TenantFeatureMobileFinanceIT.java`

**Interfaces:**
- Consumes: `TenantFeatureService.isEnabled(UUID, TenantFeature)`, `setEnabled(UUID, TenantFeature, boolean)`, `getAll(UUID)`; `GET /api/v1/tenant/features` → a flat `{"<CONSTANT>": boolean}` map; `PUT /api/admin/tenants/{id}/features/{feature}` `{enabled}`.
- Produces: `TenantFeature.MOBILE_FINANCE`, default `false`, surfaced by both endpoints with no other change.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/datagami/rentaxis/core/service/TenantFeatureMobileFinanceIT.java`:

```java
package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves MOBILE_FINANCE works against a real schema without a migration.
 *
 * <p>Accounting v2 is web-only (spec D7). The Flutter apps still call the v1 finance
 * and payments endpoints, which plans 1 and 2 delete, so their finance, lease and
 * cheque screens are hidden until plan 6 rewrites them. The switch has to be a
 * per-tenant flag rather than a build constant, because the demo tenant and the App
 * Store reviewer's tenant are the same tenant and it will be flipped back on first.
 *
 * <p>The claim under test is the same one {@code TenantFeatureGatePassIT} makes for
 * GATEPASS: adding an enum constant needs no changeset and no backfill.
 * {@code tenant_feature.feature} is a plain {@code varchar(100)} with no CHECK and no
 * Postgres enum type (changeset 38), and {@code upsert} is a native INSERT that
 * validates nothing. This test is what fails if someone later adds a constraint.
 */
@SpringBootTest
@Testcontainers
class TenantFeatureMobileFinanceIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired TenantFeatureService service;
    @Autowired LandlordOrgRepository orgRepo;

    private UUID makeTenant() {
        LandlordOrg org = new LandlordOrg();
        org.setName("MobileFinance-" + UUID.randomUUID());
        return orgRepo.save(org).getId();
    }

    /**
     * <b>No backfill needed.</b> Every existing tenant is off the moment the constant
     * exists, with nothing written for them — which is the safe default, because an
     * app pointed at a v2 backend must not show a screen that cannot load.
     */
    @Test
    void mobileFinanceIsOffForATenantWithNoFeatureRow() {
        assertThat(service.isEnabled(makeTenant(), TenantFeature.MOBILE_FINANCE)).isFalse();
    }

    /** <b>No migration needed.</b> The round trip through the real column and upsert. */
    @Test
    void mobileFinanceCanBeFlippedOnAndBackOff() {
        UUID tenant = makeTenant();
        service.setEnabled(tenant, TenantFeature.MOBILE_FINANCE, true);
        assertThat(service.isEnabled(tenant, TenantFeature.MOBILE_FINANCE)).isTrue();
        service.setEnabled(tenant, TenantFeature.MOBILE_FINANCE, false);
        assertThat(service.isEnabled(tenant, TenantFeature.MOBILE_FINANCE)).isFalse();
    }

    /** The toggle list the superadmin screen renders must carry a readable label. */
    @Test
    void mobileFinanceAppearsInTheToggleListWithALabel() {
        UUID tenant = makeTenant();
        service.setEnabled(tenant, TenantFeature.MOBILE_FINANCE, true);

        assertThat(service.getAll(tenant))
                .filteredOn(f -> f.feature() == TenantFeature.MOBILE_FINANCE)
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.enabled()).isTrue();
                    assertThat(f.defaultEnabled()).isFalse();
                    assertThat(f.label()).isNotBlank();
                });
        assertThat(service.isEnabled(tenant, TenantFeature.GATEPASS)).isFalse();
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.TenantFeatureMobileFinanceIT'`
Expected: compile error — `MOBILE_FINANCE` is not a `TenantFeature`.

- [ ] **Step 3: Add the constant and its label**

`TenantFeature.java`:

```java
    GATEPASS(false),             // guest gate passes + guard app — off by default, per-tenant rollout
    MOBILE_FINANCE(false);       // mobile finance/lease/cheque screens — off until the apps are rewritten for accounting v2 (spec D7)
```

`TenantFeatureService.toLabel` is an exhaustive switch, so the compiler already refuses to build until the case exists:

```java
            case MOBILE_FINANCE -> "Mobile finance screens";
```

- [ ] **Step 4: Run it to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.TenantFeature*'`
Expected: PASS — both the new IT and the existing `TenantFeatureGatePassIT` / `TenantFeatureServiceTest`.

- [ ] **Step 5: Widen the prod-client's feature union**

In `web/e2e-prod/helpers/prod-client.ts`, `api.setTenantFeature`'s `feature` parameter is a string union:

```ts
    feature:
      | 'EMAIL_NOTIFICATIONS'
      | 'LISTINGS'
      | 'MEETINGS'
      | 'LEASE_RENEWALS'
      | 'GATEPASS'
      | 'MOBILE_FINANCE',
```

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/TenantFeature.java \
        backend/src/main/java/com/datagami/rentaxis/core/service/TenantFeatureService.java \
        backend/src/test/java/com/datagami/rentaxis/core/service/TenantFeatureMobileFinanceIT.java \
        web/e2e-prod/helpers/prod-client.ts
git commit -m "feat(features): MOBILE_FINANCE flag, off by default, no migration needed

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 15: Mobile — tenant-features provider and the manager app's gating

There is currently **no** feature-flag infrastructure in Dart: nothing reads `/v1/tenant/features`, and every conditional nav item in the apps is role-based. The closest precedent is `mobile/packages/rentaxis_core/lib/providers/app_version_provider.dart` — a `Provider<Service>` built off `ref.watch(apiClientProvider)` plus a `FutureProvider` the router consumes with `ref.watch(...)` and `ref.read(....future)`. This task copies that shape.

One deliberate difference: the version gate **fails open** (a network error must not lock a user out of their own app). This one **fails closed**, matching `web/src/hooks/useTenantFeatures.ts`, because an unreachable backend is exactly when a finance screen would blow up.

**Files:**
- Create: `mobile/packages/rentaxis_core/lib/api/services/tenant_feature_service.dart`
- Create: `mobile/packages/rentaxis_core/lib/providers/tenant_features_provider.dart`
- Create: `mobile/packages/rentaxis_core/test/tenant_features_provider_test.dart`
- Create: `mobile/apps/manager/test/finance_gate_test.dart`
- Modify: `mobile/packages/rentaxis_core/lib/rentaxis_core.dart`
- Modify: `mobile/apps/manager/lib/screens/shell_screen.dart`, `more_screen.dart`, `dashboard_screen.dart`, `queue_screen.dart`, `lib/router.dart`

**Interfaces:**
- Consumes: `apiClientProvider` (`mobile/packages/rentaxis_core/lib/providers/auth_provider.dart:14`), `GET /v1/tenant/features` from Task 14.
- Produces:
  ```dart
  class TenantFeatureService { TenantFeatureService(this._dio); Future<Map<String, bool>> getFeatures(); }
  final tenantFeatureServiceProvider = Provider<TenantFeatureService>(...);
  final tenantFeaturesProvider = FutureProvider<Map<String, bool>>(...);   // {} on any error
  final mobileFinanceEnabledProvider = Provider<bool>(...);                 // false while loading or on error
  ```
  `hasFullFinanceAccess(String? role)` in `manager/lib/screens/shell_screen.dart` keeps its signature; a new `managerFinanceEnabled(String? role, bool flag)` combines it with the flag, and `managerFinanceRouteForRole` gains a `bool financeEnabled` parameter.

- [ ] **Step 1: Write the failing provider test**

`mobile/packages/rentaxis_core/test/tenant_features_provider_test.dart`:

```dart
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

class _FakeAdapter implements HttpClientAdapter {
  _FakeAdapter(this.responder);
  final Future<ResponseBody> Function(RequestOptions) responder;
  @override
  void close({bool force = false}) {}
  @override
  Future<ResponseBody> fetch(RequestOptions options, Stream<Uint8List>? requestStream,
      Future<void>? cancelFuture) => responder(options);
}

ProviderContainer _containerReturning(Future<ResponseBody> Function(RequestOptions) responder) {
  final client = ApiClient();
  client.dio.httpClientAdapter = _FakeAdapter(responder);
  return ProviderContainer(overrides: [apiClientProvider.overrideWithValue(client)]);
}

void main() {
  test('reads the flag map the backend returns', () async {
    final c = _containerReturning((_) async => ResponseBody.fromString(
        '{"LISTINGS":true,"MOBILE_FINANCE":true,"GATEPASS":false}', 200,
        headers: {Headers.contentTypeHeader: [Headers.jsonContentType]}));

    expect(await c.read(tenantFeaturesProvider.future), containsPair('MOBILE_FINANCE', true));
  });

  test('fails closed when the backend cannot be reached', () async {
    final c = _containerReturning((_) async => throw DioException(
        requestOptions: RequestOptions(path: '/v1/tenant/features'), message: 'offline'));

    expect(await c.read(tenantFeaturesProvider.future), isEmpty);
  });

  test('an absent flag reads as off, never as on', () async {
    final c = _containerReturning((_) async => ResponseBody.fromString(
        '{"LISTINGS":true}', 200,
        headers: {Headers.contentTypeHeader: [Headers.jsonContentType]}));

    await c.read(tenantFeaturesProvider.future);
    expect(c.read(mobileFinanceEnabledProvider), isFalse);
  });
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd mobile/packages/rentaxis_core && flutter test test/tenant_features_provider_test.dart`
Expected: FAIL — `tenantFeaturesProvider` and `mobileFinanceEnabledProvider` are undefined.

- [ ] **Step 3: Write the service and the providers**

`mobile/packages/rentaxis_core/lib/api/services/tenant_feature_service.dart`:

```dart
import 'package:dio/dio.dart';

/// Reads the per-tenant feature flags. The endpoint returns a flat map of every
/// flag constant to a boolean, e.g. {"LISTINGS": true, "MOBILE_FINANCE": false}.
class TenantFeatureService {
  TenantFeatureService(this._dio);

  final Dio _dio;

  Future<Map<String, bool>> getFeatures() async {
    final res = await _dio.get('/v1/tenant/features');
    final data = res.data;
    if (data is! Map) return const {};
    return data.map((key, value) => MapEntry('$key', value == true));
  }
}
```

`mobile/packages/rentaxis_core/lib/providers/tenant_features_provider.dart`:

```dart
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../api/services/tenant_feature_service.dart';
import 'auth_provider.dart';

final tenantFeatureServiceProvider = Provider<TenantFeatureService>(
  (ref) => TenantFeatureService(ref.watch(apiClientProvider).dio),
);

/// Per-tenant flags, or an empty map if they cannot be read.
///
/// Unlike the app-version gate, this one **fails closed**: an unreachable
/// backend is exactly when a screen that depends on it would throw, so an
/// unknown flag is treated as off. That matches the web's useTenantFeatures.
final tenantFeaturesProvider = FutureProvider<Map<String, bool>>((ref) async {
  try {
    return await ref.watch(tenantFeatureServiceProvider).getFeatures();
  } catch (_) {
    return const <String, bool>{};
  }
});

/// Whether this tenant's mobile finance, lease and cheque screens are available.
///
/// Off for every tenant until the apps are rewritten for accounting v2 (spec D7).
/// While the flags are still loading this reads false, so a screen is never shown
/// and then snatched away.
final mobileFinanceEnabledProvider = Provider<bool>((ref) {
  return ref.watch(tenantFeaturesProvider).maybeWhen(
        data: (flags) => flags['MOBILE_FINANCE'] == true,
        orElse: () => false,
      );
});
```

Export both from `mobile/packages/rentaxis_core/lib/rentaxis_core.dart`:

```dart
export 'api/services/tenant_feature_service.dart';
export 'providers/tenant_features_provider.dart';
```

- [ ] **Step 4: Run it to verify it passes**

Run: `cd mobile/packages/rentaxis_core && flutter test test/tenant_features_provider_test.dart`
Expected: PASS, 3 tests.

- [ ] **Step 5: Write the failing manager gate test**

`mobile/apps/manager/test/finance_gate_test.dart`:

```dart
import 'package:flutter_test/flutter_test.dart';
import 'package:manager/screens/shell_screen.dart';

void main() {
  group('manager finance gating', () {
    test('an admin still has no finance tab while the flag is off', () {
      expect(managerFinanceEnabled('TENANT_ADMIN', false), isFalse);
      expect(managerFinanceEnabled('PROPERTY_MANAGER', false), isFalse);
    });

    test('with the flag on, the existing role rule decides the destination', () {
      expect(managerFinanceEnabled('TENANT_ADMIN', true), isTrue);
      expect(managerFinanceRouteForRole('TENANT_ADMIN', true), '/finance');
      expect(managerFinanceRouteForRole('PROPERTY_MANAGER', true), '/payments');
    });

    test('with the flag off there is no finance destination at all', () {
      expect(managerFinanceRouteForRole('TENANT_ADMIN', false), isNull);
      expect(managerFinanceRouteForRole('PROPERTY_MANAGER', false), isNull);
    });

    test('a gated route is one the shell reports no index for', () {
      expect(managerShellIndexForLocation('/finance', financeEnabled: false), isNull);
      expect(managerShellIndexForLocation('/payments', financeEnabled: false), isNull);
      expect(managerShellIndexForLocation('/properties', financeEnabled: false), 1);
    });
  });
}
```

- [ ] **Step 6: Run it to verify it fails**

Run: `cd mobile/apps/manager && flutter test test/finance_gate_test.dart`
Expected: FAIL — `managerFinanceEnabled` is undefined and the other two helpers have different signatures.

- [ ] **Step 7: Gate the manager app**

`shell_screen.dart` — keep `hasFullFinanceAccess` untouched (other code reads it) and add the flag on top:

```dart
bool hasFullFinanceAccess(String? role) =>
    role == 'TENANT_ADMIN' || role == 'SUPER_ADMIN';

/// Finance, lease and cheque screens are hidden until the apps are rewritten for
/// accounting v2 (spec D7). The role rule still applies on top of the flag.
bool managerFinanceEnabled(String? role, bool flag) => flag;

/// Null when finance is hidden — callers must not fall back to a route.
String? managerFinanceRouteForRole(String? role, bool financeEnabled) {
  if (!financeEnabled) return null;
  return hasFullFinanceAccess(role) ? '/finance' : '/payments';
}

int? managerShellIndexForLocation(String location, {required bool financeEnabled}) {
  if (location == '/') return 0;
  if (location.startsWith('/properties')) return 1;
  if (!financeEnabled && (location.startsWith('/finance') || location.startsWith('/payments')
      || location.startsWith('/leases'))) return null;
  if (location.startsWith('/finance') || location.startsWith('/payments')) return 2;
  if (location.startsWith('/queue')) return 3;
  return null;
}
```

In the widget, read the flag and drop the tab when it is off, so the bar shows three items rather than a dead one:

```dart
    final financeEnabled = ref.watch(mobileFinanceEnabledProvider);
    final financeRoute = managerFinanceRouteForRole(authState.role, financeEnabled);
```
and build the `MiftahNavBar` items from a list that only includes the Finance entry `if (financeRoute != null)`, with the tap map keyed off the same list so the indices cannot drift.

`more_screen.dart` — wrap the finance `_MenuCard` (currently `if (isAdmin) ...[ _sectionLabel(l.finance, ...), _MenuCard(...) ]`) so it reads:

```dart
                if (isAdmin && financeEnabled) ...[
```
and add a single disabled row in its place when the flag is off, so the capability is visibly "coming soon" rather than silently missing:

```dart
                if (isAdmin && !financeEnabled) ...[
                  _sectionLabel(l.finance, l.ar, m),
                  _MenuCard(items: [
                    _MenuRow(
                      icon: Icons.account_balance_outlined,
                      label: l.accountsTransactions,
                      sub: l.comingSoon,
                      onTap: null,
                    ),
                  ]),
                ],
```
(`l.comingSoon` already exists — `manager/lib/screens/cheque_scan/steps/step3_confirm.dart:965`. Move that getter to the shared `_L` used by `more_screen.dart` rather than duplicating the string.)

`dashboard_screen.dart` — the task cards at the `/payments` and `/leases` pushes and the quick actions for `/scan`, `/leases`, `/payments` all need `if (financeEnabled)`; the `context.go('/payments')` at line ~426 needs a guard so a tap on a stale card does nothing.

`queue_screen.dart` — the `route: '/leases'` queue entry (line ~150) and the `LeaseService(client.dio).getAllLeases()` badge count (line ~38) are both skipped when the flag is off. **Do not remove the `LeaseService` import**: `create_meeting_screen.dart` needs it for the unit picker.

`router.dart` — widen the existing finance redirect (lines ~93–103) instead of adding a second one:

```dart
      final financeEnabled = ref.watch(mobileFinanceEnabledProvider);
      final isFinanceRoute = state.matchedLocation.startsWith('/finance')
          || state.matchedLocation.startsWith('/payments')
          || state.matchedLocation.startsWith('/leases')
          || state.matchedLocation == '/scan'
          || state.matchedLocation == '/bank-accounts'
          || state.matchedLocation == '/portfolio-pnl'
          || state.matchedLocation == '/finance-reports'
          || state.matchedLocation == '/settings/mappings';
      if (isLoggedIn && !financeEnabled && isFinanceRoute) {
        return '/';
      }
      final canAccessFullFinance =
          authState.role == 'TENANT_ADMIN' || authState.role == 'SUPER_ADMIN';
      final isAdminFinanceRoute =
          state.matchedLocation == '/finance' ||
          state.matchedLocation == '/finance-reports';
      if (isLoggedIn && !canAccessFullFinance && isAdminFinanceRoute) {
        return '/payments';
      }
```

Every `GoRoute` stays declared — only the redirect and the nav entries change — so `verify-capability-routes.mjs` still finds them.

- [ ] **Step 8: Run the manager tests**

Run: `cd mobile/apps/manager && flutter test`
Expected: PASS, including the ~12 existing tests that override `apiClientProvider`. Any that now fail because the shell lost a tab get their expectation updated to the three-tab bar.

- [ ] **Step 9: Verify the mobile route map is unchanged**

Run: `node tutorials/verify-capability-routes.mjs`
Expected: `manager: <n> discovered, <n> mapped` with no `unmapped` or `stale` line. A `stale mapped routes` failure means a `GoRoute` was deleted — put it back.

- [ ] **Step 10: Commit**

```bash
git add mobile/packages/rentaxis_core mobile/apps/manager
git commit -m "feat(mobile): hide the manager finance, lease and cheque screens behind MOBILE_FINANCE

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 16: Mobile — the renter app's gating

The renter app has no lease list. Its finance surfaces are the Wallet tab (`/payments`, the cheque schedule), `/penalties`, and the lease hero plus next-payment card on the home screen. `services_hub_screen.dart` builds a flat `final entries = <_Service>[…]` list with no conditionals at all today, so an `if (…)` inside that literal is the natural gating point — the same style `more_screen.dart` already uses in the manager app.

**Files:**
- Create: `mobile/apps/renter/test/finance_gate_test.dart`
- Modify: `mobile/apps/renter/lib/screens/shell_screen.dart`, `services_hub_screen.dart`, `home_screen.dart`, `lib/router.dart`

**Interfaces:**
- Consumes: `mobileFinanceEnabledProvider` from Task 15.
- Produces: `renterShellRoutes(bool financeEnabled) : List<String>` and `renterShellIndexForLocation(String location, {required bool financeEnabled}) : int?` in `renter/lib/screens/shell_screen.dart`, replacing the `static const _routes` list.

- [ ] **Step 1: Write the failing test**

`mobile/apps/renter/test/finance_gate_test.dart`:

```dart
import 'package:flutter_test/flutter_test.dart';
import 'package:renter/screens/shell_screen.dart';

void main() {
  group('renter finance gating', () {
    test('the wallet tab is present only while the flag is on', () {
      expect(renterShellRoutes(true), ['/', '/browse', '/payments', '/services']);
      expect(renterShellRoutes(false), ['/', '/browse', '/services']);
    });

    test('services keeps its index whichever way the flag sits', () {
      expect(renterShellIndexForLocation('/services', financeEnabled: true), 3);
      expect(renterShellIndexForLocation('/services', financeEnabled: false), 2);
    });

    test('a gated route reports no index rather than a wrong one', () {
      expect(renterShellIndexForLocation('/payments', financeEnabled: false), isNull);
      expect(renterShellIndexForLocation('/penalties', financeEnabled: false), isNull);
      expect(renterShellIndexForLocation('/payments', financeEnabled: true), 2);
    });
  });
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd mobile/apps/renter && flutter test test/finance_gate_test.dart`
Expected: FAIL — `renterShellRoutes` and `renterShellIndexForLocation` are undefined.

- [ ] **Step 3: Gate the renter app**

`shell_screen.dart` — replace `static const _routes = ['/', '/browse', '/payments', '/services'];` with:

```dart
/// The bottom-nav destinations. The Wallet tab reads the renter's cheque
/// schedule, which accounting v2 replaces, so it is hidden until the app is
/// rewritten (spec D7). Dropping the entry rather than disabling it keeps the
/// remaining tabs' indices contiguous.
List<String> renterShellRoutes(bool financeEnabled) => financeEnabled
    ? const ['/', '/browse', '/payments', '/services']
    : const ['/', '/browse', '/services'];

int? renterShellIndexForLocation(String location, {required bool financeEnabled}) {
  if (!financeEnabled && (location.startsWith('/payments') || location.startsWith('/penalties'))) {
    return null;
  }
  final index = renterShellRoutes(financeEnabled).indexOf(location);
  return index < 0 ? null : index;
}
```

and build the nav items from `renterShellRoutes(ref.watch(mobileFinanceEnabledProvider))` so labels, icons and tap targets all come from the same list. The centre `Pass` action (`context.go('/gatepass')`) is unaffected — gate passes are not finance.

`services_hub_screen.dart` — the entries list gains two conditionals:

```dart
    final financeEnabled = ref.watch(mobileFinanceEnabledProvider);
    final entries = <_Service>[
      // … unchanged entries …
      if (financeEnabled) _Service(icon: Icons.receipt_long_outlined, label: l.payments, route: '/payments'),
      if (financeEnabled) _Service(icon: Icons.gavel_outlined, label: l.penalties, route: '/penalties'),
      // … unchanged entries …
    ];
```
(The screen is currently a `ConsumerWidget`/`ConsumerStatefulWidget` per the project's mobile convention, so `ref` is already in scope; if it is not, convert it — that is the pattern the rest of the app uses.)

`home_screen.dart` — the lease hero (`_MaybeHero`, lines ~282) and the next-payment card both read `_myLeasesProvider` and the payment schedule. Wrap both in `if (financeEnabled)` and leave the rest of the home screen alone. **Do not remove the `LeaseService` import**: `providers/gate_pass_provider.dart`, `create_ticket_screen.dart` and `create_meeting_screen.dart` all need it to resolve the renter's unit.

`router.dart` — add the redirect next to the existing app-version gate:

```dart
      final financeEnabled = ref.watch(mobileFinanceEnabledProvider);
      if (isLoggedIn && !financeEnabled &&
          (state.matchedLocation.startsWith('/payments') ||
           state.matchedLocation.startsWith('/penalties'))) {
        return '/';
      }
```

Every `GoRoute` stays declared.

- [ ] **Step 4: Run it to verify it passes**

Run: `cd mobile/apps/renter && flutter test`
Expected: PASS, including the existing widget tests. Any that assert a four-tab bar get updated to three.

- [ ] **Step 5: Run the whole mobile suite and the route verifier**

```bash
cd mobile && melos run test
node tutorials/verify-capability-routes.mjs
```
Expected: all three apps green; `renter: <n> discovered, <n> mapped` with no unmapped or stale routes.

- [ ] **Step 6: Flip the flag on the demo tenant deliberately**

Add to `scripts/seed_demo_tenant.py`, next to the other feature toggles in section 1:

```python
    # Mobile finance/lease/cheque screens stay hidden until the apps are rewritten
    # for accounting v2 (spec D7). Set explicitly rather than relying on the default,
    # because this tenant is also the App Store reviewer's tenant and the flag is the
    # one thing that decides what the reviewer sees.
    sa.put(f"/api/admin/tenants/{tenant_id}/features/MOBILE_FINANCE",
           json={"enabled": False})
```

and note it in `docs/app-store/app-review-information.md` so the next submission does not claim screens the reviewer cannot reach.

- [ ] **Step 7: Commit**

```bash
git add mobile/apps/renter scripts/seed_demo_tenant.py docs/app-store/app-review-information.md
git commit -m "feat(mobile): hide the renter wallet and penalties behind MOBILE_FINANCE

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 17: Full suites, demo re-seed, PR

**Files:**
- Modify: `docs/superpowers/plans/2026-09-17-accounting-v2-plan5-seed-golden-walkthrough.md` (tick the boxes), `CLAUDE.md` (the graphify note's counts are stale after five plans — re-run the graph or drop the stale numbers)

**Interfaces:**
- Consumes: everything above.
- Produces: a PR, and a re-seeded demo tenant on production.

- [ ] **Step 1: Backend**

```bash
cd backend && ./gradlew test
```
Expected: 0 failures. Record the test count from the runner's own summary line, not from `grep`.

- [ ] **Step 2: Golden ledger on its own, so the PR can quote it separately**

```bash
cd backend && ./gradlew test -PincludeTags=golden
```
Expected: 10 tests, 0 failures.

- [ ] **Step 3: Web**

```bash
cd web && npx tsc --noEmit && npm run lint && npx vitest run
```
Expected: no type errors, no lint errors, 0 vitest failures.

- [ ] **Step 4: Mobile**

```bash
cd mobile && melos run analyze && melos run test
```
Expected: all three apps and `rentaxis_core` green.

- [ ] **Step 5: Tutorial and route verifiers**

```bash
node tutorials/verify-capability-routes.mjs && node tutorials/verify-tutorial-library.mjs
```
Expected: both print `passed`.

- [ ] **Step 6: Dev Playwright**

```bash
cd web && npx playwright test e2e
```
Expected: 0 failures across every project. Note the pass count.

- [ ] **Step 7: Re-seed the demo tenant on production and prove it balances**

Per spec D4, the demo tenant is re-seeded rather than migrated. Read `~/.claude/projects/-Users-kunalsharma-datagami-rentaxis/memory/project_demo_tenant.md` first — the four named renters are the App Store reviewer's data and must survive.

```bash
cd /Users/kunalsharma/datagami/rentaxis && python3 scripts/seed_demo_tenant.py --redact-credentials
```
Expected: completes. Then confirm the books are sound:

```bash
python3 - <<'PY'
import json, os, requests, datetime as dt
out = json.load(open('scripts/seed_demo_tenant.out.json'))
h = {'X-User-Id': out['adminUserId'], 'X-User-Role': 'TENANT_ADMIN',
     'X-Tenant-Id': out['tenant']['id'], 'X-User-Tenant-Id': out['tenant']['id']}
tb = requests.get(f"{out['baseUrl']}/api/v1/finance/trial-balance"
                  f"?asOf={dt.date.today().isoformat()}", headers=h, timeout=60).json()
d = sum(float(r['debit']) for r in tb); c = sum(float(r['credit']) for r in tb)
print(f'trial balance: debit {d:,.2f} credit {c:,.2f} diff {d-c:,.2f} over {len(tb)} accounts')
assert abs(d - c) < 0.005, 'the demo tenant does not balance'
print('lease statuses:', out['leaseStatus'])
PY
```
Expected: `diff 0.00` and `{'ahmed': 'ACTIVE', 'fatima': 'ACTIVE', 'rajesh': 'ACTIVE', 'sara': 'DRAFT'}`.

- [ ] **Step 8: Production Playwright**

```bash
cd web && npx playwright test --config=e2e-prod/playwright.config.ts
```
Expected: 0 failures. This suite provisions and deletes its own disposable tenant; confirm `99-cleanup.spec.ts` ran and reported the tenant deleted before calling it done.

- [ ] **Step 9: Tick this plan's boxes and refresh `CLAUDE.md`**

Tick every `- [ ]` in this file. In `CLAUDE.md`, the graphify section quotes "4,000+ nodes / 8,500+ edges … 353 communities" — five plans of accounting v2 have invalidated that. Either re-run `/graphify --update` or replace the counts with a pointer to `graphify-out/GRAPH_REPORT.md`.

- [ ] **Step 10: Open the PR**

```bash
git push -u origin feat/accounting-v2-seed-golden
gh pr create --base main --title "test(accounting): plan 5 — golden ledger replay, demo re-seed, walkthrough" --body-file <(cat <<'EOF'
## Summary
Plan 5 of the accounting v2 spec (`docs/superpowers/specs/2026-09-17-accounting-v2-design.md` §12, §13.5), and the last of the five. It closes the loop: the client's two real PACT contracts are replayed through our API and diffed against their own General Ledger export line by line, the demo tenant is re-seeded on the v2 endpoints, the Playwright suites and the tutorial library move off the deleted v1 finance screens, and the mobile apps' finance surfaces are hidden until Plan 6 rewrites them.

## What changed
- **Golden ledger replays** — `GoldenLedgerLeBoulevardIT` (ISLAM MAMANOV / LE BOULEVARD, TCO-25/251) and `GoldenLedgerGalah2IT` (ANUM ISHTIAQ / GALAH 2, TCO-26/1629), with the exports transcribed as CSV fixtures under `backend/src/test/resources/golden/`. `@Tag("golden")`, so `./gradlew test -PincludeTags=golden` runs the acceptance gate alone.
- **Two ledger fixes the replay exposed** — one counter account per row rather than every counter account on every row of a multi-line contract, and a deterministic `(entry_date, created_at, line_no)` order so a cheque that cleared and bounced the same day reads in that order.
- **`scripts/seed_demo_tenant.py`** rebuilt on v2: chart of accounts + property account sets + fiscal window, leases as posting documents with charge lines and a cheque grid, post, the cheque lifecycle including a return and a two-row replacement, month-end recognition, and a vendor invoice with its payment voucher. Still API-driven and idempotent.
- **Playwright** — `web/e2e/finance/accounting-v2.spec.ts` (dev) and `web/e2e-prod/tests/13h-accounting-v2.spec.ts` (production); `02-cheque-lifecycle.spec.ts` shrinks to the PROPERTY_MANAGER role check it uniquely covered, and its obsolete note about account mappings blocking clear/bounce is gone.
- **Walkthrough** — the repo's first `tutorials/rentaxis-adapter.md`, the capability route map re-pointed onto the v2 routes, both verifiers raised to 37 tutorials, and four new recorded tutorials: post a tenancy contract, register and clear cheques, month-end recognition, tenant ledger.
- **Mobile** — `TenantFeature.MOBILE_FINANCE` (default off, no migration needed), a `tenantFeaturesProvider` in `rentaxis_core` that fails closed, and nav/redirect gating in the manager and renter apps. No mobile screen was rewritten and no `GoRoute` was removed.

## Deviations from PACT, all authorised by the spec
| Column | Ours | PACT | Why |
|---|---|---|---|
| `CIL` amount | per-day: rent ÷ actual term days | 30/360 | spec D10 — client hard requirement |
| `CIL` entry date | period end | the 1st of the next month | spec D13 |
| PDC registration doc type | `PDR` | `IRV` on one export, `PDR` on the other | spec §3 defines one doc type |
| Row order where dated and post-dated rows mix | strict entry date | a separate "List Of PDCs" sub-block | presentational; totals and closing balances are identical |

Everything else matches line for line, including both report totals (248,150.00 and 157,000.00).

## Verification
- backend: `./gradlew test` → <n> tests, 0 failures
- golden only: `./gradlew test -PincludeTags=golden` → 10 tests, 0 failures
- web: `tsc --noEmit` clean, `npm run lint` clean, vitest <n> passed
- mobile: `melos run analyze` clean, `melos run test` <n> passed
- verifiers: capability routes passed, tutorial library passed (37 tracks)
- playwright dev: <n> passed · playwright production: <n> passed
- demo tenant re-seeded on production; trial balance diff 0.00 across <n> accounts

## Follow-ups, not in this PR
- Tutorials 17 and 18 have new scripts but their existing MP4s still show the v1 chart-of-accounts and financial-transactions screens; both need re-recording.
- Plan 6: rewrite the mobile finance, lease and cheque screens for v2 and flip `MOBILE_FINANCE` on.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)
```

---

## Self-review

### 1. Spec coverage

Plan 5's scope is spec §12 ("Golden ledger tests", "E2E") and §13 build-order item 5.

| Spec requirement | Task |
|---|---|
| §12 — golden ledger tests replaying both GL exports, diffed line by line | Tasks 2, 3 ✔ |
| §12 — `CIL` amounts asserted against the per-day fixture instead of PACT's | Tasks 2 (step 1, `theRecognitionScheduleFollowsThePerDayRuleNotPacts`), 3 ✔ |
| §12 — invariant: trial balance balances | Tasks 2, 3, 6, 7 ✔ |
| §12 — invariant: a posted lease's tenant ledger nets to zero | Tasks 2, 3, 6, 7 ✔ |
| §12 — invariant: Σ recognition = rent | Tasks 2, 3 ✔ |
| §12 — E2E: `scripts/seed_demo_tenant.py` rewritten for v2 | Tasks 4, 5 ✔ |
| §12 — E2E: Playwright extended to post a lease, deposit/clear/bounce/replace, run month-end | Tasks 6, 7 ✔ |
| §13.5 — demo re-seed | Tasks 4, 5, 17 step 7 ✔ |
| §11 — mobile finance screens behind a "coming soon" flag | Tasks 14, 15, 16 ✔ |
| §8.2 — the spec's own reference table used as a fixture | Task 3, `theRecognitionScheduleMatchesTheSpecsOwnReferenceTable` ✔ |
| §5.2 — property mapping and tenant-default fallback both exercised | Task 2 (property-scoped, four manual remaps), Task 3 (all five via tenant defaults) ✔ |
| §7.2 — every cheque transition's journal | `REGISTERED→DEPOSITED` (no journal), `DEPOSITED→CLEARED`, `CLEARED→BOUNCED`, `BOUNCED→REPLACED` in Tasks 2, 5, 6, 7 ✔ |
| §10.1/§10.2 — PISR and BPV | Task 4 step 3 ✔ |

**Gaps, stated rather than hidden:**

- **§12's "E2E … terminate and settle"** is *not* covered. `POST /api/v1/leases/{id}/terminate` and the settlement endpoints are listed under "Consumes (from plan 3)" but no task calls them. Neither PACT export contains a terminated contract, so there is no golden fixture to diff against, and inventing one would assert our own arithmetic rather than the client's. Termination and settlement are covered by Plan 3's own service tests (spec §12 "one test class per document"); a Plan 5 E2E for them needs a client fixture we do not have. **Raise with the client: ask Anil for one terminated contract's GL export.**
- **§7.2's `REGISTERED→CANCELLED`, `REGISTERED/DEPOSITED→RETURNED`, `REGISTERED→ONLINE_PENDING→CLEARED`** are likewise untested here — cancellation and return are termination paths, and the Razorpay path cannot run in a Testcontainers IT or against the production suite without live gateway credentials.
- **§10.3 cut-over (contract import, opening balances, reconciliation)** — Plan 4's territory, and Plan 4 tests it. Task 8 maps its three routes so the verifier passes, but no Plan 5 task exercises them, and the demo seed deliberately does **not** post an opening balance: the demo tenant's books start clean on 1 January, so an `OB` journal would be a fabrication rather than a demonstration.
- **Tutorials 17 and 18** get new scripts in Task 9 step 5 but are not re-recorded; their published MP4s stay stale until a follow-up run. Called out in the PR body.

### 2. Placeholder scan

No "TBD", "TODO", "implement later", "similar to Task N", or "add appropriate error handling". Four places tell the executor to *reconcile against a runner's output* rather than guess, and each names the authority and what to do with it:
- Task 8 step 3 — the filesystem is the authority on Plan 2–4's exact route paths; `verify-capability-routes.mjs` prints them.
- Task 2 step 2 — if a Plan 2/3 service name differs from the `Consumes` block, fix the call site, never the fixture.
- Task 5 — `page_items` is an existing helper in the seed script; the new code reuses it.
- Task 15 step 7 — `l.comingSoon` exists at a named line and is moved, not invented.

These are lookups with a stated fallback, not gaps.

### 3. Type consistency

- `GoldenRow(account, entryDate, docType, particular, narration, debit, credit, balance)` — declared in Task 1, produced by the four CSVs, consumed by `LedgerDiff.same()` and by Tasks 2 and 3. Field order matches the CSV column order in all four files.
- `GoldenRecognitionRow(periodStart, periodEnd, days, amount)` and `LedgerDiff.RecognitionRow` carry the same four fields; Tasks 2 and 3 map `RecognitionEntry` into the latter with `getPeriodStart/getPeriodEnd/getDays/getAmount`, exactly the accessors declared in Task 2's `Consumes`.
- `AccountLedgerDTO.accountName/rows/totalDebit/totalCredit/closingBalance` and `LedgerRowDTO.entryDate/docType/particular/narration/debit/credit/balance` are Plan 1's record components, used identically in Tasks 1, 2, 3, 6 and 7 — and `prod-client.ts`'s `getRenterLedger` return type mirrors them field for field.
- `LeaseLineRequest(chargeTypeCode, grossAmount, discountAmount, narration)` and `ChequeRowRequest(seqNo, postingDate, chequeNumber, chequeDate, payeeBank, amount, narration, mode)` — identical in the Java `Consumes` blocks (Tasks 2, 3), the Python `line()`/`cheque()` builders (Task 5), the TypeScript `createLease` helpers (Tasks 6, 7) and the prod-client type.
- `mobileFinanceEnabledProvider` is defined once in Task 15 and consumed by name in Tasks 15 and 16; `managerFinanceRouteForRole(role, financeEnabled)` has the same two-argument signature in its definition, its test and its call site; `renterShellRoutes(financeEnabled)` likewise.
- Seed manifest keys are written in Tasks 4, 5 and 10 (`accounts`, `fiscal`, `leases`, `leaseStatus`, `cheques`, `recognitionRunTo`, `vouchers`, `renterIds`, `adminUserId`) and read in Tasks 5, 10, 11, 12, 13 and 17 under exactly those names.
- `TenantFeature.MOBILE_FINANCE` is the same string in the Java enum, the `toLabel` case, the `prod-client.ts` union, the Dart `flags['MOBILE_FINANCE']` lookup and the seed script's `PUT …/features/MOBILE_FINANCE`.

### 4. One thing worth a second look before execution

Task 3 posts journals dated up to 23 September 2027 on a machine whose clock reads 2026. Nothing in spec §4.2 forbids a future `entry_date` — the only date rule is `entry_date > books_locked_through` — but if Plan 3 or Plan 5's reviewer decides a future-dated journal should be refused, `GoldenLedgerGalah2IT` cannot replay the client's own contract, which is dated in their future too. Settle that before executing Task 3, not during it.

---

## Addendum A — reconciled Plan 2 / Plan 3 surfaces (authoritative; overrides the "Consumes" guesses above)

Plans 2 and 3 now exist. Where a `Consumes` block above differs from this table, use this table and fix the call site.

| Guessed above | Actual (Plan 2 / Plan 3) |
|---|---|
| `POST /api/v1/cheques/{id}/deposit { depositDate, bankAccountId? }` | `PUT /api/v1/cheques/{id}/deposit` body `ChequeActionRequest { date, notes, failureReason, debitAccountId }` |
| `POST /api/v1/cheques/{id}/clear { clearedDate }` | `PUT /api/v1/cheques/{id}/clear` body `ChequeActionRequest { date }` |
| `POST /api/v1/cheques/{id}/bounce { bouncedDate, failureReason }` | `PUT /api/v1/cheques/{id}/bounce` body `ChequeActionRequest { date, failureReason }` |
| `POST /api/v1/cheques/{id}/replace { replacements: [...] }` | `POST /api/v1/cheques/{id}/replace` body `ReplaceChequeRequest { replacements: ChequeRowInput[], date, notes }` |
| `POST /api/v1/leases` draft with `lines[]` **and** `cheques[]` | `POST /api/v1/leases` takes `lines[]` only (`LeaseLineInput { chargeTypeCode | chargeTypeId, grossAmount, discountAmount, narration, vatApplicable, creditAccountId, periodStart, periodEnd }`); cheques are then `PUT /api/v1/leases/{id}/cheques` with `ChequeRowInput[] { seqNo, postingDate, chequeNumber, chequeDate, payeeBank, payerName, debitAccountId, amount, narration, mode }` (or `POST /api/v1/leases/{id}/cheques/generate`) |
| `POST /api/v1/leases/{id}/post → { id, status, postingJournalId }` | `POST /api/v1/leases/{id}/post → PostLeaseResponse { lease, tcoJournalId, tcoEntryNumber, cheques[] }` |
| `ChequeService.deposit(UUID, LocalDate, UUID)` etc. | `ChequeService.deposit/clear/receive/bounce/cancel(UUID chequeId, ChequeActionRequest r)`, `replace(UUID, ReplaceChequeRequest)`, `returnToTenant(UUID, LocalDate, String)`; list via `ChequeRepository.findByLease_IdOrderBySeqNoAsc` |
| `RecognitionService.runTo(LocalDate) : RecognitionRunResult(int posted, int skipped)` | `RecognitionService.runTo(LocalDate to, boolean preview) : RecognitionRunResult(int posted, BigDecimal amount, List<RecognitionEntryDTO> entries, List<String> errors)`; `scheduleFor(UUID) : List<RecognitionEntryDTO>` (records: `periodStart(), periodEnd(), days(), amount(), status(), journalNumber()`) |
| `POST /api/v1/finance/recognition/run?to=` | same path; add `&preview=false`; **rejects `to > today` at the HTTP layer** — the golden ITs call `RecognitionService.runTo` directly under a fixed `Clock`, not the endpoint |
| `POST /api/v1/leases/{id}/terminate { terminationDate, chequeDispositions[] }` | `POST /api/v1/leases/{id}/terminate` body `TerminateLeaseRequest { terminationDate, returnChequeIds[], keepChequeIds[], notes }`; preview `GET /api/v1/leases/{id}/terminate/preview?date=` |
| `GET /api/v1/leases/{id}/settlement` preview; `POST …/finalize { bankAccountId, deductions }` | `GET /api/v1/leases/{id}/settlement/statement` → `SettlementStatementDTO`; `POST …/settlement/draft` body `SaveSettlementDTO` (lines with `accountId`); `POST …/settlement/finalize` body `FinalizeSettlementRequest { settlementDate, refundBankAccountId }` |
| `PUT /api/v1/finance/fiscal` | `PUT /api/v1/finance/fiscal-settings` (Plan 1 Task 10) |
| `GET/POST/PUT /api/v1/finance/properties/{id}/accounts` | `GET /api/v1/properties/{id}/accounts`, `POST …/accounts/generate`, `PUT …/accounts/{role}` (Plan 1 Task 6) |

`ChequeStatus` and `ChequeMode` names, `Cheque` date columns and `PenaltyAssessment` are as guessed. PDR `entry_date` = the row's `postingDate` (Plan 2 Task 6), so a fixture whose PDRs are dated on the cheque date (GALAH 2) must set each row's `postingDate` accordingly — this is the second failure the note at line ~1502 anticipates, and the fix is in the fixture's `ChequeRowInput.postingDate`, not in Plan 2.
