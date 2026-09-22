# Accounting v2 — Plan 4: Vouchers & Cut-over — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the accountant the two expense documents (Purchase/Service Invoice `PISR`, Bank/Cash Payment Voucher `BPV`) and everything needed to leave PACT on a cut-over date: an active-contract importer that posts a whole portfolio as one reversible batch, an opening-balance journal (`OB`) that closes the books balanced, and a reconciliation screen that proves our derived balances against PACT's trial balance.

**Architecture:** Both vouchers are thin documents over Plan 1's `PostingService` — a `Voucher` header plus `VoucherLine`s in DRAFT, one `PostingService.post(PostingRequest)` call on Post, and `reverse + new voucher` on amend. Nothing in this plan writes a journal by hand. The cut-over rides on the *existing* multi-sheet Excel portfolio importer (`PortfolioImportService` → `PortfolioImportPersistService`, one `import_jobs` row, one async executor, one polling endpoint): we add v2 sheets and a second persist path, we do not write a second importer. Every journal written by the importer carries `import_batch_id`, which Plan 1 already exempts from the period lock and which makes "Reverse batch" a mechanical walk of `JournalEntryRepository.findByImportBatchIdOrderByCreatedAtAsc`.

**Tech Stack:** Java 21, Spring Boot 4.0.3, Spring Data JPA (Hibernate 7, `BaseTenantEntity` + `tenantFilter`), Liquibase YAML, PostgreSQL 16, Apache POI (XSSF), Azure Blob / local disk for attachments, JUnit 5 + Testcontainers (`postgres:16-alpine`), AssertJ, Mockito; Next.js 16 + TypeScript + Tailwind 4 + next-intl, Vitest + Testing Library.

**Spec:** `docs/superpowers/specs/2026-09-17-accounting-v2-design.md` — §10 (expense vouchers, opening balances, cut-over), §11 (Purchase/Service Invoice, Bank/Cash Payment Voucher, Opening Balances, Reconciliation, Import Batches screens), §12 (testing), §13 item 4. Read it first.

**Depends on:** `docs/superpowers/plans/2026-09-17-accounting-v2-plan1-ledger-core.md` (ledger core — merged before this plan starts). Tasks 10–11 additionally depend on `…-plan2-lease-posting-pdc.md` and `…-plan3-recognition-termination-settlement.md`, which are written but not yet merged; see **Ordering** below.

## Global Constraints

- **Ordering.** Tasks 1–9 and 12–16 depend only on Plan 1 and may be built as soon as Plan 1 is merged. **Tasks 10 and 11 (contract import, bulk post) must be executed after Plans 2 and 3 are merged** — they consume `Lease`/`LeaseLine`/`Cheque`/`ChargeType`, `LeasePostingService`, `ChequeService`, `LeaseService` and `RecognitionService`. Do not start Task 10 before `git log --oneline main | grep -i "accounting v2 plan 3"` shows Plan 3 merged, and re-read both plans' Interfaces blocks first — the signatures quoted here are from their plan documents, not from merged code.
- **Cash Receipt Voucher – Rent (`RCP`) is not in this plan.** Spec §9.3 puts it on the receipt path, which Plan 2 owns. The `vouchers` table created here carries `RCP` in its `doc_type` enum so Plan 2 has somewhere to put it; nothing in this plan writes or reads an `RCP` row.
- Liquibase changesets are **append-only**; Plan 1 used `81-`/`82-`, Plans 2–3 use `83-` … `86-`. **This plan uses `87-vouchers.yaml` and `88-cutover.yaml` only.** `changeSet.id` = filename stem; `author: claude`; a `#` comment block above the changeset explains why, in the style of `80-one-active-lease-per-unit.yaml`.
- Java paths in this plan are relative to `backend/src/main/java/com/datagami/rentaxis/` (tests: `backend/src/test/java/com/datagami/rentaxis/`). Web paths are relative to the repo root.
- Every tenant-scoped entity extends `BaseTenantEntity` (Hibernate `tenantFilter`, `tenant_id` filled in `@PrePersist` from `TenantContextHolder`). Repositories live in `domain/repository` so `TenantAspect` enables the filter.
- Amounts are `BigDecimal` `precision = 14, scale = 2`; DB `decimal(14,2)`. Rounding is always `RoundingMode.HALF_UP`. Currency AED, no multi-currency.
- Exceptions: `NotFoundException` → 404, `BusinessRuleViolationException` → 400 (`api/exception`). Response bodies are `{error, message, status}`.
- Endpoint auth is Spring `@PreAuthorize("hasAnyRole(...)")`. Every endpoint in this plan is `hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT')` — vouchers, opening balances, reconciliation and batch reverse are all "post/reverse" actions, which spec §11 gives to `TENANT_ADMIN` or `ACCOUNTANT`. `PROPERTY_MANAGER` gets none of them.
- **Never write a `JournalEntry` by hand.** The only write path is `PostingService.post(PostingRequest)`; the only mutation is `PostingService.reverse(entryId, date, reason)`. Both come from Plan 1 unchanged.
- Roles, not account codes, in code. The only account *names* that may appear as literals are the six column headers of the client's property-mapping sheet (Task 10), which are data read from a spreadsheet, not constants.
- Web calls go through `fetch("/api/proxy/v1/...")`; never hardcode the backend URL. UI is table-first with `Pagination`, AR/EN, RTL-safe, `LoadErrorBanner` on load failure, `ConfirmDialog` for destructive actions — matching `web/src/app/[locale]/dashboard/finance/vendors/page.tsx`.
- **i18n namespace:** everything new in this plan goes in a new `Vouchers` namespace in `web/messages/en.json` and `web/messages/ar.json`. Plan 1's `Ledger` namespace is reused unchanged for anything it already defines (`debit`, `credit`, `balance`, `account`, `narration`, `posted`, `reversed`, `reverse`, `addLine`, `removeLine`, `totalDebit`, `totalCredit`, `unbalanced`, `docDate`, `docNo`, `docType`, `from`, `to`, `asOf`, `propertyFilter`, `noRows`). Do not duplicate a key that already exists in `Ledger`.
- Backend tests: `…Test` = unit (no Spring), `…IT` = Testcontainers `@SpringBootTest`. Run one class with `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.<pkg>.<Class>'`.
- Web tests: `cd web && npx vitest run <path>`; type check with `npx tsc --noEmit`.
- Commit after every task with a conventional-commit message. Work on a plain branch `feat/accounting-v2-vouchers-cutover` cut from `main` (no git worktree).
- Commit messages end with:
  `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>`

---

## Interfaces consumed from Plan 1 (do not redefine — copy these signatures)

```java
// core/service/ledger/PostingRequest.java
public record PostingRequest(JournalDocType docType, LocalDate entryDate, String narration, Dimensions dims,
                             JournalSourceType sourceType, UUID sourceId, UUID importBatchId, List<Line> lines) {
    public record Dimensions(UUID propertyId, UUID unitId, UUID leaseId, UUID renterId, UUID chequeId) {
        public static Dimensions none();  public static Dimensions ofProperty(UUID p);
    }
    public sealed interface AccountRef permits ByRole, ById {}
    public record ByRole(AccountRole role) implements AccountRef {}   // resolved against dims.propertyId()
    public record ById(UUID accountId) implements AccountRef {}
    public enum Side { DR, CR }
    public record Line(AccountRef account, Side side, BigDecimal amount, Dimensions dims, String narration) {
        public Line withDims(Dimensions d);  public Line withNarration(String n);
    }
    public static Line dr(AccountRole role, BigDecimal amount);   public static Line cr(AccountRole role, BigDecimal amount);
    public static Line dr(UUID accountId, BigDecimal amount);     public static Line cr(UUID accountId, BigDecimal amount);
}

// core/service/ledger/PostingService.java
JournalEntry post(PostingRequest r);                                   // enforces balance, leaf-only, period lock
JournalEntry reverse(UUID entryId, LocalDate date, String reason);     // mirror entry; copies importBatchId; refuses
                                                                       // to reverse a reversal or an already-REVERSED entry
// core/service/ledger/AccountResolver.java
Account resolve(AccountRole role, UUID propertyId);
Map<AccountRole, Account> resolveAll(Set<AccountRole> roles, UUID propertyId);   // throws UnmappedAccountRoleException once

// core/service/ledger/TenantFiscalSettingsService.java
TenantFiscalSettings get();  int fiscalYearOf(LocalDate d);  void assertOpen(LocalDate d);
void lockThrough(LocalDate d);  void setBooksStartDate(LocalDate d);  void setFiscalYearStartMonth(int m);
// TenantFiscalSettings: getFiscalYearStartMonth(), getBooksStartDate(), getBooksLockedThrough()

// core/service/ledger/LedgerQueryService.java
record LedgerFilter(LocalDate from, LocalDate to, UUID propertyId, UUID unitId, UUID leaseId, UUID renterId) {}
record TrialBalanceRowDTO(UUID accountId, String code, String name, String accountType, UUID parentId,
                          UUID propertyId, BigDecimal debit, BigDecimal credit, BigDecimal balance) {}  // balance signed, debit-positive
List<TrialBalanceRowDTO> trialBalance(LocalDate asOf, UUID propertyId);
AccountLedgerDTO vendorLedger(UUID vendorId, LocalDate from, LocalDate to);

// domain/repository/JournalEntryRepository.java
List<JournalEntry> findByImportBatchIdOrderByCreatedAtAsc(UUID importBatchId);
List<JournalEntry> findBySourceTypeAndSourceIdOrderByEntryDateAscCreatedAtAsc(JournalSourceType t, UUID sourceId);
// domain/repository/JournalLineRepository.java
List<JournalLine> findByEntry_IdOrderByLineNoAsc(UUID entryId);

// enums
JournalDocType   { TCO, TCR, PDR, CRT, CBR, CIL, RCP, STL, PEN, PISR, BPV, OB, JV }
JournalSourceType{ LEASE, CHEQUE, RECOGNITION, PENALTY, SETTLEMENT, VOUCHER, OPENING_BALANCE, IMPORT, MANUAL, REVERSAL }
JournalStatus    { POSTED, REVERSED }
AccountRole      { …, DISCOUNT_ALLOWED, ROUNDING_OFF, CASH, OUTPUT_VAT, INPUT_VAT, OPENING_BALANCE_DIFFERENCE }
                 // INPUT_VAT and OPENING_BALANCE_DIFFERENCE are isPropertyScoped() == false → tenant defaults
UserRole         { …, ACCOUNTANT }

// domain/entity/Vendor.java — Plan 1 Task 7 creates the leaf silently under group B-01-04
Account Vendor.getPayableAccount();
```

```ts
// web/src/lib/api/ledger.ts (Plan 1 Task 11)
export const ledgerApi = { accounts, propertyAccounts, template, defaults, roles, ledger, trialBalance, journals, fiscal }
export function fmtAmount(n: number): string    // "61,000.00"
export function fmtBalance(n: number): string   // "61,000.00 Dr" / "3,000.00 Cr" / "0.00"
export type Account = { id; code; name; nameEn; nameAr; alias; accountType; accountSubType; parentId; propertyId; system; group; active; displayOrder; description }
export type TrialBalanceRow = { accountId; code; name; accountType; parentId; propertyId; debit; credit; balance }
export type Page<T> = { content: T[]; totalElements: number; totalPages: number; number: number; size: number }
export type FiscalSettings = { fiscalYearStartMonth: number; booksStartDate: string|null; booksLockedThrough: string|null }
// web/src/components/finance/AccountPicker.tsx (Plan 1 Task 12)
export default function AccountPicker({ value, onChange, accountType?, leafOnly = true, placeholder?, autoFocus? })
export function invalidateAccounts(): void
// web/src/components/finance/LedgerTable.tsx (Plan 1 Task 13)
export default function LedgerTable({ ledgers: AccountLedger[], showTenantColumns?: boolean })
// web/src/lib/rbac.ts (Plan 1 Task 11)
PERMISSIONS.canPostJournals        = ['SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT']
PERMISSIONS.canManageAccountSetup  = ['SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT']
hasPermission(role, permission): boolean
```

## Interfaces consumed from Plans 2 and 3 (Tasks 10–11 only)

Copied verbatim from `docs/superpowers/plans/2026-09-17-accounting-v2-plan2-lease-posting-pdc.md` and `…-plan3-recognition-termination-settlement.md`. Neither is merged yet, so **re-read both plans' Interfaces blocks before starting Task 10** and, if a signature moved, fix **this plan's call sites** — never Plan 2's or Plan 3's semantics.

```java
// ---- Plan 2 ----
Lease                     // domain/entity/Lease.java; statuses DRAFT, PENDING_SIGNATURE, ACTIVE, RENEWED,
                          // NOTICE_GIVEN, TERMINATED, EXPIRED, CLOSED. Fields used here: contractDate,
                          // contractNumber, chainId, gracePeriodDays, firstDueDate, startDate, endDate,
                          // unit, renter, property, status, contractValue, lines.
                          // NOTE: leases.rent_amount / deposit_amount survive as DERIVED MIRRORS, kept in
                          // step by LeaseService.syncDerivedTotals — call it after writing lines.
LeaseLine                 // (lease, seqNo, chargeType, creditAccount, grossAmount, discountAmount,
                          //  netAmount, narration, vatApplicable, periodStart, periodEnd); net = gross − discount
ChargeType                // (code, nameEn, nameAr, role, behaviour RENT|DEPOSIT|FEE, vatApplicableDefault, active)
                          // domain/repository/ChargeTypeRepository — look a row up by its code
Cheque                    // domain/entity/Cheque.java; ChequeStatus { DRAFT, REGISTERED, DEPOSITED, CLEARED,
                          // BOUNCED, REPLACED, CANCELLED, RETURNED, ONLINE_PENDING }; ChequeMode { PDC, CASH,
                          // TRANSFER, ONLINE }. depositedAt / clearedAt / bouncedAt / returnedAt are LocalDate.

// core/service/lease/LeasePostingService.java
record PostLeaseResponse(LeaseDTO lease, UUID tcoJournalId, String tcoEntryNumber, List<ChequeDTO> cheques) {}
PostLeaseResponse LeasePostingService.post(UUID leaseId);          // writes TCO + one PDR per cheque row
Set<AccountRole>  LeasePostingService.requiredRoles(Lease lease);

// core/service/cheque/ChequeService.java  — note the package, and that every action takes a request record
record ChequeActionRequest(LocalDate date, String notes, ChequeFailureReason failureReason, UUID debitAccountId) {}
ChequeDTO ChequeService.deposit(UUID chequeId, ChequeActionRequest r);   // REGISTERED(PDC) -> DEPOSITED, no journal
ChequeDTO ChequeService.clear(UUID chequeId, ChequeActionRequest r);     // DEPOSITED -> CLEARED: posts CRT
ChequeDTO ChequeService.bounce(UUID chequeId, ChequeActionRequest r);    // DEPOSITED|CLEARED -> BOUNCED: posts CBR

// core/service/LeaseService.java  (NOT core/service/lease/) — lines, derived totals, terminate
void LeaseService.syncDerivedTotals(UUID leaseId);
// revertToDraft does NOT exist in Plan 2. Task 11 Step 3 adds it; see below.

// ---- Plan 3 ----
// core/service/recognition/RecognitionService.java
record RecognitionRunResult(int posted, BigDecimal amount, List<RecognitionEntryDTO> entries, List<String> errors) {}
RecognitionRunResult RecognitionService.runTo(LocalDate to, boolean preview);
// posts PLANNED entries with periodEnd <= to and > booksLockedThrough; preview = no posting.
// runTo does NOT clamp `to` to today, which is what lets the cut-over catch-up run at books_start_date − 1.
// Each entry posts in its own REQUIRES_NEW transaction: one unmapped income account lands in `errors`
// instead of rolling back the rest — so Task 11 must surface `errors`, not assume an empty list.
```

**Two extensions this plan makes to Plans 2/3 (in Task 11 Step 3, and nothing else):**

1. **`importBatchId` overloads.** `LeasePostingService.post`, `ChequeService.deposit/clear/bounce` and `RecognitionService.runTo` each gain an overload with a trailing `UUID importBatchId` threaded straight into `PostingRequest.importBatchId()`; the existing arities delegate with `null`. Journals are immutable after insert (Plan 1's `trg_journal_entries_immutable`), so the batch id cannot be stamped on afterwards — it has to travel in on the way down, and without it the import journals are neither exempt from the period lock nor findable by "Reverse batch".
2. **`LeaseService.revertToDraft(UUID leaseId)`.** Plan 2 has no such method — it has `terminateLease`, which is a different thing with its own journals. Reversing an import must put the lease back exactly as the importer created it, so Task 11 adds this method and has `LeaseService` implement `cutover.LeaseReverter`.

**Also new in Plan 1 since this plan was drafted:** `PostingRequest.ofPairs(docType, entryDate, narration, dims, sourceType, sourceId, importBatchId, List<Pair> pairs)` plus `PostingRequest.pair(debitLine, creditLine)`, which record a contra account per line so the ledger's *Particular* column names the other side. The 8-argument `new PostingRequest(...)` constructor this plan uses is unchanged and still correct: a PISR is many debits against one credit and an OB journal is dozens of one-sided lines, so neither has the 1:1 pairing `ofPairs` describes. Do **not** convert them.

---

## File structure

**Backend — new (Tasks 1–9)**
- `domain/entity/enums/VoucherType.java` — `PISR, BPV, RCP`
- `domain/entity/enums/VoucherStatus.java` — `DRAFT, POSTED, REVERSED`
- `domain/entity/enums/ImportBatchKind.java` — `CONTRACT_IMPORT`
- `domain/entity/enums/ImportBatchStatus.java` — `DRAFT, POSTED, REVERSED`
- `domain/entity/Voucher.java`, `VoucherLine.java`, `VoucherAttachment.java`
- `domain/entity/ImportBatch.java`, `ImportBatchLease.java`
- `domain/entity/OpeningBalanceSnapshotRow.java`, `OpeningBalancePosting.java`
- `domain/repository/VoucherRepository.java`, `VoucherLineRepository.java`, `VoucherAttachmentRepository.java`,
  `ImportBatchRepository.java`, `ImportBatchLeaseRepository.java`,
  `OpeningBalanceSnapshotRowRepository.java`, `OpeningBalancePostingRepository.java`
- `core/service/voucher/VoucherMath.java` — pure VAT / total arithmetic, no Spring
- `core/service/voucher/VoucherService.java` — draft lifecycle, `post`, `amend`, list/detail
- `core/service/voucher/VoucherAttachmentService.java` — blob/local upload, mirrors `DeductionAttachmentService`
- `core/service/cutover/ImportBatchService.java` — create/link/list/reverse
- `core/service/cutover/OpeningBalanceService.java` — grid, snapshot CSV, post/reverse, reconcile
- `api/VoucherController.java`, `api/OpeningBalanceController.java`, `api/ImportBatchController.java`
- `api/dto/voucher/VoucherDTO.java`, `VoucherLineDTO.java`, `VoucherDetailDTO.java`, `VoucherInputDTO.java`,
  `VoucherLineInputDTO.java`, `VoucherAttachmentDTO.java`
- `api/dto/cutover/ImportBatchDTO.java`, `OpeningBalanceRowDTO.java`, `OpeningBalanceGridDTO.java`,
  `ReconciliationRowDTO.java`, `SnapshotUploadResultDTO.java`, `ManualOpeningBalanceDTO.java`
- `db/changelog/changesets/87-vouchers.yaml`, `db/changelog/changesets/88-cutover.yaml`

**Backend — new (Tasks 10–11, after Plans 2–3)**
- `core/service/cutover/ContractImportValidator.java` — validates the v2 sheets
- `core/service/cutover/ContractImportPersistService.java` — writes DRAFT leases/lines/cheques + property mappings
- `core/service/cutover/ContractImportPostService.java` — bulk post + cheque statuses + recognition catch-up

**Backend — modified**
- `core/service/PortfolioImportService.java` — v2 mode detection in `validateAll` and `processImportAsync`
- `core/service/PortfolioTemplateService.java` — six account columns on Properties; `Contracts` sheet; v2 `Cheques` sheet
- `domain/entity/ImportJob.java` + `api/dto/PortfolioImportResultDTO.java` + `PortfolioImportJobDetailsDTO.java` — carry `importBatchId`, `contractsCreated`
- `api/PortfolioImportController.java` — surface `importBatchId` on the status response
- `core/service/lease/LeasePostingService.java`, `core/service/lease/ChequeService.java`, `core/service/lease/LeaseService.java`, `core/service/recognition/RecognitionService.java` — `importBatchId` overloads (Task 11 Step 3)
- `db/changelog/db.changelog-master.yaml` — two `include` lines

**Web — new**
- `web/src/lib/api/vouchers.ts` — typed client for every endpoint in this plan
- `web/src/components/finance/VoucherForm.tsx` — shared PISR/BPV form (header + lines grid + totals + attachments)
- `web/src/components/finance/__tests__/VoucherForm.test.tsx`
- `web/src/app/[locale]/dashboard/finance/vouchers/page.tsx` — list (both types, status filter)
- `web/src/app/[locale]/dashboard/finance/vouchers/purchase-invoice/page.tsx`
- `web/src/app/[locale]/dashboard/finance/vouchers/payment/page.tsx`
- `web/src/app/[locale]/dashboard/finance/opening-balances/page.tsx`
- `web/src/app/[locale]/dashboard/finance/reconciliation/page.tsx`
- `web/src/app/[locale]/dashboard/finance/import-batches/page.tsx`

**Web — modified**
- `web/src/lib/api/ledger.ts` — export the three private helpers (`qs`, `apiGet`, `apiSend`) so `vouchers.ts` reuses them
- `web/src/components/ui/MvpSidebar.tsx` — five new finance entries
- `web/messages/en.json`, `web/messages/ar.json` — new `Vouchers` namespace

---

### Task 1: Changeset 87 — voucher schema

**Files:**
- Create: `backend/src/main/resources/db/changelog/changesets/87-vouchers.yaml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml` (append one `include`)
- Test: `backend/src/test/java/com/datagami/rentaxis/domain/VoucherSchemaIT.java`

**Interfaces:**
- Consumes: Plan 1's `accounts`, `journal_entries` tables; the existing `vendors`, `properties`, `units`, `landlord_org` tables.
- Produces: tables `vouchers`, `voucher_lines`, `voucher_attachments`.

- [ ] **Step 1: Write the failing schema test**

`backend/src/test/java/com/datagami/rentaxis/domain/VoucherSchemaIT.java`:

```java
package com.datagami.rentaxis.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class VoucherSchemaIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired JdbcTemplate jdbc;

    private UUID tenant() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO landlord_org (id, name) VALUES (?, ?)", id, "T-" + id);
        return id;
    }

    private UUID account(UUID tenant, String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, tenant_id, code, name, account_type, is_group, is_active, is_system, display_order) "
                + "VALUES (?,?,?,?,?,false,true,false,0)", id, tenant, code, "Acct " + code, "EXPENSE");
        return id;
    }

    private UUID voucher(UUID tenant, String docType, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO vouchers (id, tenant_id, doc_type, doc_date, status, created_at) "
                + "VALUES (?,?,?,CURRENT_DATE,?,now())", id, tenant, docType, status);
        return id;
    }

    @Test
    void voucherAndLinesRoundTrip() {
        UUID t = tenant();
        UUID v = voucher(t, "PISR", "DRAFT");
        UUID a = account(t, "VS-1");
        jdbc.update("INSERT INTO voucher_lines (id, tenant_id, voucher_id, line_no, account_id, amount, vat_rate, vat_amount) "
                + "VALUES (?,?,?,1,?,1000.00,5.00,50.00)", UUID.randomUUID(), t, v, a);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM voucher_lines WHERE voucher_id = ?", Integer.class, v))
                .isEqualTo(1);
    }

    @Test
    void deletingAVoucherDeletesItsLinesAndAttachments() {
        UUID t = tenant();
        UUID v = voucher(t, "BPV", "DRAFT");
        UUID a = account(t, "VS-2");
        jdbc.update("INSERT INTO voucher_lines (id, tenant_id, voucher_id, line_no, account_id, amount, vat_rate, vat_amount) "
                + "VALUES (?,?,?,1,?,500.00,0,0)", UUID.randomUUID(), t, v, a);
        jdbc.update("INSERT INTO voucher_attachments (id, tenant_id, voucher_id, name, file_url, uploaded_at) "
                + "VALUES (?,?,?,'Invoice.pdf','/x',now())", UUID.randomUUID(), t, v);
        jdbc.update("DELETE FROM vouchers WHERE id = ?", v);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM voucher_lines WHERE voucher_id = ?", Integer.class, v)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM voucher_attachments WHERE voucher_id = ?", Integer.class, v)).isZero();
    }

    @Test
    void aLineAmountMustBePositive() {
        UUID t = tenant();
        UUID v = voucher(t, "PISR", "DRAFT");
        UUID a = account(t, "VS-3");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO voucher_lines (id, tenant_id, voucher_id, line_no, account_id, amount, vat_rate, vat_amount) "
                        + "VALUES (?,?,?,1,?,0,0,0)", UUID.randomUUID(), t, v, a))
                .hasMessageContaining("ck_voucher_lines_amount_positive");
    }

    @Test
    void lineNumbersAreUniqueWithinAVoucher() {
        UUID t = tenant();
        UUID v = voucher(t, "BPV", "DRAFT");
        UUID a = account(t, "VS-4");
        jdbc.update("INSERT INTO voucher_lines (id, tenant_id, voucher_id, line_no, account_id, amount, vat_rate, vat_amount) "
                + "VALUES (?,?,?,1,?,10.00,0,0)", UUID.randomUUID(), t, v, a);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO voucher_lines (id, tenant_id, voucher_id, line_no, account_id, amount, vat_rate, vat_amount) "
                        + "VALUES (?,?,?,1,?,20.00,0,0)", UUID.randomUUID(), t, v, a))
                .hasMessageContaining("ux_voucher_lines_voucher_line_no");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.domain.VoucherSchemaIT'`
Expected: FAIL — `ERROR: relation "vouchers" does not exist`.

- [ ] **Step 3: Write the changeset**

`backend/src/main/resources/db/changelog/changesets/87-vouchers.yaml`:

```yaml
databaseChangeLog:
  # Purchase/Service Invoice (PISR) and Bank/Cash Payment Voucher (BPV) — spec §10.1, §10.2.
  #
  # ONE TABLE FOR ALL VOUCHER TYPES. The spec makes `vouchers` shared for
  # PISR/BPV/RCP because all three are the same shape: a dated header with a
  # counterparty, a set of amount lines, and exactly one journal. Splitting them
  # would give three near-identical tables and three list queries. The
  # type-specific columns (payment_account_id / cheque_number / cheque_date for
  # BPV, vendor_id / invoice_number for PISR) are nullable here and made
  # required by VoucherService per doc_type, where the rule is readable.
  #
  # RCP is in the doc_type check constraint but unused until accounting v2
  # plan 2 wires the rent receipt path (spec §9.3). Leaving it out would force
  # a second changeset later for a one-word change.
  #
  # journal_id is NOT a foreign key with ON DELETE CASCADE, and vouchers are
  # never deleted after posting: journal entries are immutable (changeset 81)
  # and a posted voucher's journal must outlive any edit to the voucher.
  # Amending posts a reversal and creates a NEW voucher row pointing back via
  # amended_from_id, so the audit trail is a chain, not an overwrite.
  - changeSet:
      id: 87-vouchers
      author: claude
      comment: Voucher header/line/attachment tables for PISR and BPV
      changes:
        - createTable:
            tableName: vouchers
            columns:
              - column: { name: id, type: uuid, constraints: { primaryKey: true, nullable: false } }
              - column: { name: tenant_id, type: uuid, constraints: { nullable: false } }
              - column: { name: doc_type, type: varchar(10), constraints: { nullable: false } }
              - column: { name: doc_date, type: date, constraints: { nullable: false } }
              - column: { name: vendor_id, type: uuid }
              - column: { name: invoice_number, type: varchar(60) }
              - column: { name: narration, type: text }
              - column: { name: property_id, type: uuid }
              - column: { name: unit_id, type: uuid }
              - column: { name: payment_account_id, type: uuid }
              - column: { name: cheque_number, type: varchar(50) }
              - column: { name: cheque_date, type: date }
              - column: { name: status, type: varchar(10), defaultValue: DRAFT, constraints: { nullable: false } }
              - column: { name: journal_id, type: uuid }
              - column: { name: voucher_number, type: varchar(40) }
              - column: { name: amended_from_id, type: uuid }
              - column: { name: posted_by, type: uuid }
              - column: { name: posted_at, type: timestamptz }
              - column: { name: created_at, type: timestamptz, defaultValueComputed: now(), constraints: { nullable: false } }
              - column: { name: updated_at, type: timestamptz }
        - addForeignKeyConstraint:
            baseTableName: vouchers
            baseColumnNames: tenant_id
            referencedTableName: landlord_org
            referencedColumnNames: id
            constraintName: fk_vouchers_tenant
        - addForeignKeyConstraint:
            baseTableName: vouchers
            baseColumnNames: vendor_id
            referencedTableName: vendors
            referencedColumnNames: id
            constraintName: fk_vouchers_vendor
        - addForeignKeyConstraint:
            baseTableName: vouchers
            baseColumnNames: payment_account_id
            referencedTableName: accounts
            referencedColumnNames: id
            constraintName: fk_vouchers_payment_account
        - sql:
            comment: doc_type and status are closed sets; a CHECK keeps a typo out of the ledger
            sql: >-
              ALTER TABLE vouchers
                ADD CONSTRAINT ck_vouchers_doc_type CHECK (doc_type IN ('PISR','BPV','RCP')),
                ADD CONSTRAINT ck_vouchers_status   CHECK (status IN ('DRAFT','POSTED','REVERSED'))
        - createIndex: { tableName: vouchers, indexName: idx_vouchers_tenant_type_date, columns: [ { column: { name: tenant_id } }, { column: { name: doc_type } }, { column: { name: doc_date } } ] }
        - createIndex: { tableName: vouchers, indexName: idx_vouchers_vendor, columns: [ { column: { name: vendor_id } } ] }
        - createIndex: { tableName: vouchers, indexName: idx_vouchers_journal, columns: [ { column: { name: journal_id } } ] }

        - createTable:
            tableName: voucher_lines
            columns:
              - column: { name: id, type: uuid, constraints: { primaryKey: true, nullable: false } }
              - column: { name: tenant_id, type: uuid, constraints: { nullable: false } }
              - column: { name: voucher_id, type: uuid, constraints: { nullable: false } }
              - column: { name: line_no, type: int, constraints: { nullable: false } }
              - column: { name: account_id, type: uuid, constraints: { nullable: false } }
              - column: { name: description, type: text }
              - column: { name: amount, type: "decimal(14,2)", constraints: { nullable: false } }
              - column: { name: vat_rate, type: "decimal(5,2)", defaultValueNumeric: 0, constraints: { nullable: false } }
              - column: { name: vat_amount, type: "decimal(14,2)", defaultValueNumeric: 0, constraints: { nullable: false } }
              - column: { name: property_id, type: uuid }
              - column: { name: unit_id, type: uuid }
        - addForeignKeyConstraint:
            baseTableName: voucher_lines
            baseColumnNames: voucher_id
            referencedTableName: vouchers
            referencedColumnNames: id
            constraintName: fk_voucher_lines_voucher
            onDelete: CASCADE
        - addForeignKeyConstraint:
            baseTableName: voucher_lines
            baseColumnNames: account_id
            referencedTableName: accounts
            referencedColumnNames: id
            constraintName: fk_voucher_lines_account
        - sql:
            comment: >-
              A zero or negative line would post a zero-amount or sign-flipped journal line.
              PostingService rejects it too, but a draft should never be able to hold one.
            sql: >-
              ALTER TABLE voucher_lines
                ADD CONSTRAINT ck_voucher_lines_amount_positive CHECK (amount > 0),
                ADD CONSTRAINT ck_voucher_lines_vat_non_negative CHECK (vat_rate >= 0 AND vat_amount >= 0)
        - sql:
            comment: Line numbers are what the UI renders and what the journal narration cites
            sql: >-
              CREATE UNIQUE INDEX ux_voucher_lines_voucher_line_no ON voucher_lines (voucher_id, line_no)
        - createIndex: { tableName: voucher_lines, indexName: idx_voucher_lines_account, columns: [ { column: { name: account_id } } ] }

        - createTable:
            tableName: voucher_attachments
            columns:
              - column: { name: id, type: uuid, constraints: { primaryKey: true, nullable: false } }
              - column: { name: tenant_id, type: uuid, constraints: { nullable: false } }
              - column: { name: voucher_id, type: uuid, constraints: { nullable: false } }
              - column: { name: name, type: varchar(255), constraints: { nullable: false } }
              - column: { name: file_url, type: varchar(1024), constraints: { nullable: false } }
              - column: { name: file_type, type: varchar(100) }
              - column: { name: file_size, type: bigint }
              - column: { name: uploaded_at, type: timestamptz, defaultValueComputed: now() }
        - addForeignKeyConstraint:
            baseTableName: voucher_attachments
            baseColumnNames: voucher_id
            referencedTableName: vouchers
            referencedColumnNames: id
            constraintName: fk_voucher_attachments_voucher
            onDelete: CASCADE
        - createIndex: { tableName: voucher_attachments, indexName: idx_voucher_attachments_voucher, columns: [ { column: { name: voucher_id } } ] }
      rollback:
        - dropTable: { tableName: voucher_attachments }
        - dropTable: { tableName: voucher_lines }
        - dropTable: { tableName: vouchers }
```

Append to `backend/src/main/resources/db/changelog/db.changelog-master.yaml`:

```yaml
  - include:
      file: db/changelog/changesets/87-vouchers.yaml
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.domain.VoucherSchemaIT'`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/changelog backend/src/test/java/com/datagami/rentaxis/domain/VoucherSchemaIT.java
git commit -m "feat(finance): changeset 87 — voucher header, line and attachment tables

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Voucher entities, VAT arithmetic, draft lifecycle

**Files:**
- Create: `domain/entity/enums/VoucherType.java`, `domain/entity/enums/VoucherStatus.java`
- Create: `domain/entity/Voucher.java`, `domain/entity/VoucherLine.java`
- Create: `domain/repository/VoucherRepository.java`, `domain/repository/VoucherLineRepository.java`
- Create: `core/service/voucher/VoucherMath.java`, `core/service/voucher/VoucherService.java`
- Test: `core/service/voucher/VoucherMathTest.java`, `core/service/voucher/VoucherDraftIT.java`

**Interfaces:**
- Consumes: Task 1 tables; Plan 1's `Account`, `AccountRepository`, `Vendor`, `BusinessRuleViolationException`, `NotFoundException`, `TenantContextHolder`.
- Produces:
  ```java
  enum VoucherType { PISR, BPV, RCP }
  enum VoucherStatus { DRAFT, POSTED, REVERSED }

  // core/service/voucher/VoucherMath.java — pure, no Spring
  static BigDecimal vat(BigDecimal amount, BigDecimal vatRatePercent);   // HALF_UP, 2dp; null rate => ZERO
  static BigDecimal netTotal(List<? extends HasAmounts> lines);
  static BigDecimal vatTotal(List<? extends HasAmounts> lines);
  static BigDecimal grossTotal(List<? extends HasAmounts> lines);
  interface HasAmounts { BigDecimal getAmount(); BigDecimal getVatAmount(); }

  // core/service/voucher/VoucherService.java
  record VoucherLineInput(UUID accountId, String description, BigDecimal amount,
                          BigDecimal vatRate, UUID propertyId, UUID unitId) {}
  record VoucherInput(VoucherType docType, LocalDate docDate, UUID vendorId, String invoiceNumber,
                      String narration, UUID propertyId, UUID unitId, UUID paymentAccountId,
                      String chequeNumber, LocalDate chequeDate, List<VoucherLineInput> lines) {}
  Voucher createDraft(VoucherInput in);
  Voucher updateDraft(UUID voucherId, VoucherInput in);
  void    deleteDraft(UUID voucherId);
  Voucher get(UUID voucherId);
  Page<Voucher> list(VoucherType docType, VoucherStatus status, UUID vendorId, UUID propertyId,
                     LocalDate from, LocalDate to, Pageable pageable);
  ```
  `Voucher` exposes `getLines() : List<VoucherLine>` ordered by `lineNo`, and `VoucherLine` implements `VoucherMath.HasAmounts`.

- [ ] **Step 1: Write the failing unit test for the VAT arithmetic**

`backend/src/test/java/com/datagami/rentaxis/core/service/voucher/VoucherMathTest.java`:

```java
package com.datagami.rentaxis.core.service.voucher;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VoucherMathTest {

    private record Line(BigDecimal getAmount, BigDecimal getVatAmount) implements VoucherMath.HasAmounts {
        @Override public BigDecimal getAmount() { return getAmount; }
        @Override public BigDecimal getVatAmount() { return getVatAmount; }
    }

    private static Line line(String amount, String vat) {
        return new Line(new BigDecimal(amount), new BigDecimal(vat));
    }

    @Test
    void fivePercentOfARoundNumber() {
        assertThat(VoucherMath.vat(new BigDecimal("1000.00"), new BigDecimal("5")))
                .isEqualByComparingTo("50.00");
    }

    @Test
    void zeroRatedLinesCarryNoVat() {
        assertThat(VoucherMath.vat(new BigDecimal("1000.00"), BigDecimal.ZERO)).isEqualByComparingTo("0.00");
        assertThat(VoucherMath.vat(new BigDecimal("1000.00"), null)).isEqualByComparingTo("0.00");
    }

    /**
     * 5% of 1,234.57 is 61.7285 exactly. HALF_UP gives 61.73; the ledger takes two
     * decimals, so this is the line the journal carries and the number the FTA return
     * is built from. HALF_EVEN would give 61.73 here too — the case that separates them
     * is the .005 boundary below.
     */
    @Test
    void vatRoundsHalfUpToTwoDecimals() {
        assertThat(VoucherMath.vat(new BigDecimal("1234.57"), new BigDecimal("5")))
                .isEqualByComparingTo("61.73");
        // 5% of 100.10 = 5.005 -> HALF_UP -> 5.01 (HALF_EVEN would give 5.00)
        assertThat(VoucherMath.vat(new BigDecimal("100.10"), new BigDecimal("5")))
                .isEqualByComparingTo("5.01");
    }

    /**
     * VAT is computed and rounded PER LINE, then summed — never computed on the net
     * total. Three lines of 100.10 each round to 5.01, so the header VAT is 15.03,
     * not the 15.02 you would get from 5% of 300.30. PACT does it per line and the
     * vendor's own invoice will show the per-line figures.
     */
    @Test
    void headerVatIsTheSumOfPerLineVatNotVatOfTheSum() {
        List<Line> lines = List.of(line("100.10", "5.01"), line("100.10", "5.01"), line("100.10", "5.01"));
        assertThat(VoucherMath.vatTotal(lines)).isEqualByComparingTo("15.03");
        assertThat(VoucherMath.netTotal(lines)).isEqualByComparingTo("300.30");
        assertThat(VoucherMath.grossTotal(lines)).isEqualByComparingTo("315.33");
    }

    @Test
    void totalsOfAnEmptyListAreZeroNotNull() {
        assertThat(VoucherMath.netTotal(List.of())).isEqualByComparingTo("0.00");
        assertThat(VoucherMath.vatTotal(List.of())).isEqualByComparingTo("0.00");
        assertThat(VoucherMath.grossTotal(List.of())).isEqualByComparingTo("0.00");
    }

    @Test
    void mixedRatedAndZeroRatedLinesAddUp() {
        List<Line> lines = List.of(line("5000.00", "250.00"), line("1200.00", "0.00"));
        assertThat(VoucherMath.netTotal(lines)).isEqualByComparingTo("6200.00");
        assertThat(VoucherMath.vatTotal(lines)).isEqualByComparingTo("250.00");
        assertThat(VoucherMath.grossTotal(lines)).isEqualByComparingTo("6450.00");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.voucher.VoucherMathTest'`
Expected: FAIL — compilation error, `VoucherMath` does not exist.

- [ ] **Step 3: Write `VoucherMath`**

`core/service/voucher/VoucherMath.java`:

```java
package com.datagami.rentaxis.core.service.voucher;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * VAT and total arithmetic for vouchers. Deliberately a plain final class with
 * static methods and no Spring: this is the only arithmetic in the voucher path
 * that can be wrong in a way the ledger will not catch (a balanced journal with
 * the wrong VAT split is still balanced), so it is tested in isolation.
 *
 * <p>VAT is rounded per line, then summed. Computing 5% of the net total instead
 * would disagree with the vendor's invoice by a fil or two on multi-line
 * invoices; see VoucherMathTest#headerVatIsTheSumOfPerLineVatNotVatOfTheSum.
 */
public final class VoucherMath {

    private VoucherMath() {}

    public static final int SCALE = 2;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);

    /** Anything carrying a net amount and its already-computed VAT. Implemented by VoucherLine. */
    public interface HasAmounts {
        BigDecimal getAmount();
        BigDecimal getVatAmount();
    }

    /** {@code amount * rate/100}, HALF_UP to 2dp. A null or zero rate gives 0.00. */
    public static BigDecimal vat(BigDecimal amount, BigDecimal vatRatePercent) {
        if (amount == null || vatRatePercent == null || vatRatePercent.signum() == 0) return ZERO;
        return amount.multiply(vatRatePercent)
                .divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal netTotal(List<? extends HasAmounts> lines) {
        return sum(lines, HasAmounts::getAmount);
    }

    public static BigDecimal vatTotal(List<? extends HasAmounts> lines) {
        return sum(lines, HasAmounts::getVatAmount);
    }

    public static BigDecimal grossTotal(List<? extends HasAmounts> lines) {
        return netTotal(lines).add(vatTotal(lines));
    }

    private static BigDecimal sum(List<? extends HasAmounts> lines,
                                  java.util.function.Function<HasAmounts, BigDecimal> f) {
        BigDecimal total = ZERO;
        if (lines == null) return total;
        for (HasAmounts l : lines) {
            BigDecimal v = f.apply(l);
            if (v != null) total = total.add(v);
        }
        return total.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
```

- [ ] **Step 4: Run the unit test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.voucher.VoucherMathTest'`
Expected: PASS, 6 tests.

- [ ] **Step 5: Write the enums, entities and repositories**

`domain/entity/enums/VoucherType.java`:

```java
package com.datagami.rentaxis.domain.entity.enums;

/**
 * Voucher document types. Each maps 1:1 to the {@link JournalDocType} of the same
 * name, so the journal an accountant sees in the GL carries the same prefix PACT
 * used. RCP is reserved for the rent receipt path (spec §9.3, accounting v2 plan 2).
 */
public enum VoucherType {
    PISR, BPV, RCP;

    public JournalDocType toDocType() {
        return switch (this) {
            case PISR -> JournalDocType.PISR;
            case BPV -> JournalDocType.BPV;
            case RCP -> JournalDocType.RCP;
        };
    }
}
```

`domain/entity/enums/VoucherStatus.java`:

```java
package com.datagami.rentaxis.domain.entity.enums;

public enum VoucherStatus { DRAFT, POSTED, REVERSED }
```

`domain/entity/Voucher.java`:

```java
package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.VoucherStatus;
import com.datagami.rentaxis.domain.entity.enums.VoucherType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A Purchase/Service Invoice (PISR) or a Bank/Cash Payment Voucher (BPV).
 * Mutable only while {@code status == DRAFT}; posting freezes it and links the
 * journal. Amending never edits a posted row — it reverses the journal, marks
 * this row REVERSED and creates a new row with {@code amendedFromId} set.
 */
@Entity
@Table(name = "vouchers")
@Getter
@Setter
public class Voucher extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 10)
    private VoucherType docType;

    @Column(name = "doc_date", nullable = false)
    private LocalDate docDate;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "vendor_id")
    private Vendor vendor;

    /** The vendor's own invoice number, for matching against the paper. PISR only. */
    @Column(name = "invoice_number", length = 60)
    private String invoiceNumber;

    @Column(columnDefinition = "text")
    private String narration;

    @Column(name = "property_id") private UUID propertyId;
    @Column(name = "unit_id") private UUID unitId;

    /** BPV: the bank or cash leaf the money leaves from. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "payment_account_id")
    private Account paymentAccount;

    @Column(name = "cheque_number", length = 50) private String chequeNumber;
    @Column(name = "cheque_date") private LocalDate chequeDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private VoucherStatus status = VoucherStatus.DRAFT;

    @Column(name = "journal_id") private UUID journalId;

    /**
     * Copy of the journal's entry_number, written on post. Denormalised on purpose:
     * the voucher list is one table scan this way instead of a join per row, and a
     * journal number never changes once assigned (entries are immutable).
     */
    @Column(name = "voucher_number", length = 40) private String voucherNumber;

    @Column(name = "amended_from_id") private UUID amendedFromId;

    @Column(name = "posted_by") private UUID postedBy;
    @Column(name = "posted_at") private Instant postedAt;

    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at") private Instant updatedAt;

    @OneToMany(mappedBy = "voucher", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("lineNo ASC")
    private List<VoucherLine> lines = new ArrayList<>();

    public void replaceLines(List<VoucherLine> newLines) {
        lines.clear();
        int n = 1;
        for (VoucherLine l : newLines) {
            l.setVoucher(this);
            l.setLineNo(n++);
            lines.add(l);
        }
    }
}
```

`domain/entity/VoucherLine.java`:

```java
package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.core.service.voucher.VoucherMath;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "voucher_lines")
@Getter
@Setter
public class VoucherLine extends BaseTenantEntity implements VoucherMath.HasAmounts {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "voucher_id", nullable = false)
    @JsonIgnore
    private Voucher voucher;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "vat_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal vatRate = BigDecimal.ZERO;

    @Column(name = "vat_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal vatAmount = BigDecimal.ZERO;

    @Column(name = "property_id") private UUID propertyId;
    @Column(name = "unit_id") private UUID unitId;
}
```

`domain/repository/VoucherRepository.java`:

```java
package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.Voucher;
import com.datagami.rentaxis.domain.entity.enums.VoucherStatus;
import com.datagami.rentaxis.domain.entity.enums.VoucherType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface VoucherRepository extends JpaRepository<Voucher, UUID> {

    @Query("""
        select v from Voucher v
        where (:docType is null or v.docType = :docType)
          and (:status is null or v.status = :status)
          and (:vendorId is null or v.vendor.id = :vendorId)
          and (:propertyId is null or v.propertyId = :propertyId)
          and (:from is null or v.docDate >= :from)
          and (:to is null or v.docDate <= :to)
        order by v.docDate desc, v.createdAt desc
        """)
    Page<Voucher> search(VoucherType docType, VoucherStatus status, UUID vendorId, UUID propertyId,
                         LocalDate from, LocalDate to, Pageable pageable);

    List<Voucher> findByJournalId(UUID journalId);

    boolean existsByPaymentAccount_Id(UUID accountId);
}
```

`domain/repository/VoucherLineRepository.java`:

```java
package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.VoucherLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface VoucherLineRepository extends JpaRepository<VoucherLine, UUID> {
    List<VoucherLine> findByVoucher_IdOrderByLineNoAsc(UUID voucherId);
    boolean existsByAccount_Id(UUID accountId);
}
```

- [ ] **Step 6: Write the failing draft-lifecycle IT**

`backend/src/test/java/com/datagami/rentaxis/core/service/voucher/VoucherDraftIT.java`:

```java
package com.datagami.rentaxis.core.service.voucher;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.VendorService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class VoucherDraftIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired VoucherService vouchers;
    @Autowired AccountService accounts;
    @Autowired VendorService vendorService;
    @Autowired LandlordOrgRepository orgRepo;

    UUID tenantId;
    Account cleaningExpense;
    Account groupAccount;
    Vendor vendor;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("Voucher-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
        accounts.seedDefaultAccounts();
        groupAccount = accounts.getAccountByCode("D-01");           // an EXPENSE group from the seed
        cleaningExpense = accounts.createLeaf("Cleaning Expense", groupAccount, null);
        Vendor v = new Vendor();
        v.setNameEn("Emrill Services");
        vendor = vendorService.createVendor(v);                      // Plan 1 creates payableAccount silently
    }

    @AfterEach void clear() { TenantContextHolder.clear(); }

    private VoucherService.VoucherInput pisr(BigDecimal amount, BigDecimal rate) {
        return new VoucherService.VoucherInput(
                VoucherType.PISR, LocalDate.of(2026, 10, 5), vendor.getId(), "INV-8812",
                "Monthly cleaning", null, null, null, null, null,
                List.of(new VoucherService.VoucherLineInput(
                        cleaningExpense.getId(), "October cleaning", amount, rate, null, null)));
    }

    @Test
    void creatingADraftComputesPerLineVatAndNumbersTheLines() {
        Voucher v = vouchers.createDraft(pisr(new BigDecimal("1000.00"), new BigDecimal("5")));
        assertThat(v.getStatus()).isEqualTo(VoucherStatus.DRAFT);
        assertThat(v.getJournalId()).isNull();
        assertThat(v.getLines()).singleElement().satisfies(l -> {
            assertThat(l.getLineNo()).isEqualTo(1);
            assertThat(l.getVatAmount()).isEqualByComparingTo("50.00");
        });
    }

    @Test
    void updatingADraftReplacesItsLinesAndRenumbersFromOne() {
        Voucher v = vouchers.createDraft(pisr(new BigDecimal("1000.00"), new BigDecimal("5")));
        VoucherService.VoucherInput two = new VoucherService.VoucherInput(
                VoucherType.PISR, LocalDate.of(2026, 10, 5), vendor.getId(), "INV-8812",
                "Monthly cleaning", null, null, null, null, null,
                List.of(new VoucherService.VoucherLineInput(cleaningExpense.getId(), "A", new BigDecimal("200.00"), new BigDecimal("5"), null, null),
                        new VoucherService.VoucherLineInput(cleaningExpense.getId(), "B", new BigDecimal("300.00"), BigDecimal.ZERO, null, null)));
        Voucher updated = vouchers.updateDraft(v.getId(), two);
        assertThat(updated.getLines()).extracting(VoucherLine::getLineNo).containsExactly(1, 2);
        assertThat(VoucherMath.grossTotal(updated.getLines())).isEqualByComparingTo("510.00");
    }

    @Test
    void aPisrWithoutAVendorIsRejected() {
        VoucherService.VoucherInput noVendor = new VoucherService.VoucherInput(
                VoucherType.PISR, LocalDate.of(2026, 10, 5), null, null, "x", null, null, null, null, null,
                List.of(new VoucherService.VoucherLineInput(cleaningExpense.getId(), null, new BigDecimal("10.00"), BigDecimal.ZERO, null, null)));
        assertThatThrownBy(() -> vouchers.createDraft(noVendor))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("vendor");
    }

    @Test
    void aBpvWithoutAPaymentAccountIsRejected() {
        VoucherService.VoucherInput noBank = new VoucherService.VoucherInput(
                VoucherType.BPV, LocalDate.of(2026, 10, 5), null, null, "x", null, null, null, null, null,
                List.of(new VoucherService.VoucherLineInput(cleaningExpense.getId(), null, new BigDecimal("10.00"), BigDecimal.ZERO, null, null)));
        assertThatThrownBy(() -> vouchers.createDraft(noBank))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("payment account");
    }

    /** Spec §10.2: a BPV has no VAT column — VAT on a payment is an invoice's job. */
    @Test
    void aBpvLineMayNotCarryVat() {
        Account bank = accounts.createLeaf("Emirates Islamic - Ops", accounts.getAccountByCode("A-02-02"), null);
        VoucherService.VoucherInput vatOnBpv = new VoucherService.VoucherInput(
                VoucherType.BPV, LocalDate.of(2026, 10, 5), null, null, "x", null, null, bank.getId(), null, null,
                List.of(new VoucherService.VoucherLineInput(cleaningExpense.getId(), null, new BigDecimal("10.00"), new BigDecimal("5"), null, null)));
        assertThatThrownBy(() -> vouchers.createDraft(vatOnBpv))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("VAT");
    }

    /** Group accounts cannot carry journal lines (spec §4.2); catch it at draft time, not at post time. */
    @Test
    void aLineOnAGroupAccountIsRejected() {
        VoucherService.VoucherInput onGroup = new VoucherService.VoucherInput(
                VoucherType.PISR, LocalDate.of(2026, 10, 5), vendor.getId(), null, "x", null, null, null, null, null,
                List.of(new VoucherService.VoucherLineInput(groupAccount.getId(), null, new BigDecimal("10.00"), BigDecimal.ZERO, null, null)));
        assertThatThrownBy(() -> vouchers.createDraft(onGroup))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("group");
    }

    @Test
    void aVoucherWithNoLinesIsRejected() {
        VoucherService.VoucherInput empty = new VoucherService.VoucherInput(
                VoucherType.PISR, LocalDate.of(2026, 10, 5), vendor.getId(), null, "x", null, null, null, null, null, List.of());
        assertThatThrownBy(() -> vouchers.createDraft(empty))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("at least one line");
    }

    @Test
    void draftsCanBeDeletedAndAreFoundByTheListQuery() {
        Voucher v = vouchers.createDraft(pisr(new BigDecimal("1000.00"), new BigDecimal("5")));
        assertThat(vouchers.list(VoucherType.PISR, VoucherStatus.DRAFT, null, null, null, null, PageRequest.of(0, 25))
                .getContent()).extracting(Voucher::getId).contains(v.getId());
        vouchers.deleteDraft(v.getId());
        assertThat(vouchers.list(VoucherType.PISR, null, null, null, null, null, PageRequest.of(0, 25))
                .getContent()).extracting(Voucher::getId).doesNotContain(v.getId());
    }
}
```

- [ ] **Step 7: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.voucher.VoucherDraftIT'`
Expected: FAIL — `VoucherService` does not exist.

- [ ] **Step 8: Write `VoucherService` (draft half only)**

`core/service/voucher/VoucherService.java` — posting comes in Tasks 3 and 4; write the class now with the draft methods and the shared validation:

```java
package com.datagami.rentaxis.core.service.voucher;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.VoucherStatus;
import com.datagami.rentaxis.domain.entity.enums.VoucherType;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.VendorRepository;
import com.datagami.rentaxis.domain.repository.VoucherRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VoucherService {

    private final VoucherRepository vouchers;
    private final AccountRepository accounts;
    private final VendorRepository vendors;

    /** UAE standard rate is 5%; zero-rated and exempt supplies are 0. Nothing else is legal today. */
    private static final Set<BigDecimal> ALLOWED_VAT_RATES =
            Set.of(new BigDecimal("0.00"), new BigDecimal("5.00"));

    public record VoucherLineInput(UUID accountId, String description, BigDecimal amount,
                                   BigDecimal vatRate, UUID propertyId, UUID unitId) {}

    public record VoucherInput(VoucherType docType, LocalDate docDate, UUID vendorId, String invoiceNumber,
                               String narration, UUID propertyId, UUID unitId, UUID paymentAccountId,
                               String chequeNumber, LocalDate chequeDate, List<VoucherLineInput> lines) {}

    @Transactional(readOnly = true)
    public Voucher get(UUID voucherId) {
        Voucher v = vouchers.findById(voucherId).orElseThrow(() -> new NotFoundException("Voucher not found"));
        v.getLines().size();   // initialise before the session closes
        return v;
    }

    @Transactional(readOnly = true)
    public Page<Voucher> list(VoucherType docType, VoucherStatus status, UUID vendorId, UUID propertyId,
                              LocalDate from, LocalDate to, Pageable pageable) {
        return vouchers.search(docType, status, vendorId, propertyId, from, to, pageable);
    }

    @Transactional
    public Voucher createDraft(VoucherInput in) {
        validate(in);
        Voucher v = new Voucher();
        apply(v, in);
        v.setStatus(VoucherStatus.DRAFT);
        return vouchers.save(v);
    }

    @Transactional
    public Voucher updateDraft(UUID voucherId, VoucherInput in) {
        Voucher v = get(voucherId);
        requireDraft(v);
        validate(in);
        apply(v, in);
        v.setUpdatedAt(Instant.now());
        return vouchers.save(v);
    }

    @Transactional
    public void deleteDraft(UUID voucherId) {
        Voucher v = get(voucherId);
        requireDraft(v);
        vouchers.delete(v);   // lines and attachments cascade (changeset 87)
    }

    // ---- internals shared with post() in Tasks 3 and 4 ----

    void requireDraft(Voucher v) {
        if (v.getStatus() != VoucherStatus.DRAFT) {
            throw new BusinessRuleViolationException(
                    "Voucher " + (v.getVoucherNumber() == null ? v.getId() : v.getVoucherNumber())
                            + " is " + v.getStatus() + "; only a DRAFT can be edited. Amend it instead.");
        }
    }

    private void validate(VoucherInput in) {
        if (in.docType() == null) throw new BusinessRuleViolationException("Document type is required");
        if (in.docType() == VoucherType.RCP) {
            throw new BusinessRuleViolationException(
                    "Cash Receipt Vouchers are created from the lease receipt screen, not here");
        }
        if (in.docDate() == null) throw new BusinessRuleViolationException("Document date is required");
        if (in.lines() == null || in.lines().isEmpty()) {
            throw new BusinessRuleViolationException("A voucher needs at least one line");
        }
        if (in.docType() == VoucherType.PISR) {
            if (in.vendorId() == null) throw new BusinessRuleViolationException("A purchase invoice needs a vendor");
            Vendor vendor = vendors.findById(in.vendorId())
                    .orElseThrow(() -> new NotFoundException("Vendor not found"));
            if (vendor.getPayableAccount() == null) {
                throw new BusinessRuleViolationException(
                        "Vendor " + vendor.getNameEn() + " has no payable account. Re-save the vendor to create one.");
            }
        }
        if (in.docType() == VoucherType.BPV) {
            if (in.paymentAccountId() == null) {
                throw new BusinessRuleViolationException("A payment voucher needs a payment account (bank or cash)");
            }
            requireLeaf(in.paymentAccountId(), "Payment account");
        }
        for (VoucherLineInput l : in.lines()) {
            if (l.accountId() == null) throw new BusinessRuleViolationException("Every line needs an account");
            requireLeaf(l.accountId(), "Line account");
            if (l.amount() == null || l.amount().signum() <= 0) {
                throw new BusinessRuleViolationException("Every line needs an amount greater than zero");
            }
            BigDecimal rate = l.vatRate() == null ? BigDecimal.ZERO : l.vatRate();
            if (in.docType() == VoucherType.BPV && rate.signum() != 0) {
                throw new BusinessRuleViolationException(
                        "A payment voucher line cannot carry VAT. Record the VAT on the purchase invoice.");
            }
            if (ALLOWED_VAT_RATES.stream().noneMatch(r -> r.compareTo(rate) == 0)) {
                throw new BusinessRuleViolationException("VAT rate must be 0 or 5, got " + rate);
            }
        }
    }

    private void requireLeaf(UUID accountId, String label) {
        Account a = accounts.findById(accountId)
                .orElseThrow(() -> new NotFoundException(label + " not found: " + accountId));
        if (a.isGroup()) {
            throw new BusinessRuleViolationException(
                    label + " " + a.getCode() + " " + a.getName() + " is a group; pick a leaf account");
        }
        if (!a.isActive()) {
            throw new BusinessRuleViolationException(label + " " + a.getCode() + " is inactive");
        }
    }

    private void apply(Voucher v, VoucherInput in) {
        v.setDocType(in.docType());
        v.setDocDate(in.docDate());
        v.setVendor(in.vendorId() == null ? null : vendors.findById(in.vendorId()).orElseThrow(
                () -> new NotFoundException("Vendor not found")));
        v.setInvoiceNumber(in.invoiceNumber());
        v.setNarration(in.narration());
        v.setPropertyId(in.propertyId());
        v.setUnitId(in.unitId());
        v.setPaymentAccount(in.paymentAccountId() == null ? null : accounts.findById(in.paymentAccountId())
                .orElseThrow(() -> new NotFoundException("Payment account not found")));
        v.setChequeNumber(in.chequeNumber());
        v.setChequeDate(in.chequeDate());

        List<VoucherLine> newLines = new ArrayList<>();
        for (VoucherLineInput li : in.lines()) {
            VoucherLine l = new VoucherLine();
            l.setAccount(accounts.findById(li.accountId()).orElseThrow(
                    () -> new NotFoundException("Account not found: " + li.accountId())));
            l.setDescription(li.description());
            l.setAmount(li.amount().setScale(2, java.math.RoundingMode.HALF_UP));
            BigDecimal rate = li.vatRate() == null ? BigDecimal.ZERO : li.vatRate();
            l.setVatRate(rate.setScale(2, java.math.RoundingMode.HALF_UP));
            l.setVatAmount(VoucherMath.vat(l.getAmount(), rate));
            l.setPropertyId(li.propertyId() == null ? in.propertyId() : li.propertyId());
            l.setUnitId(li.unitId() == null ? in.unitId() : li.unitId());
            newLines.add(l);
        }
        v.replaceLines(newLines);
    }
}
```

- [ ] **Step 9: Run both tests to verify they pass**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.voucher.*'`
Expected: PASS — `VoucherMathTest` 6 tests, `VoucherDraftIT` 8 tests.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/domain backend/src/main/java/com/datagami/rentaxis/core/service/voucher backend/src/test/java/com/datagami/rentaxis/core/service/voucher
git commit -m "feat(finance): voucher entities, per-line VAT arithmetic and draft lifecycle

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Post a Purchase/Service Invoice (`PISR`)

**Files:**
- Modify: `core/service/voucher/VoucherService.java` (add `post`)
- Test: `core/service/voucher/PurchaseInvoicePostingIT.java`

**Interfaces:**
- Consumes: `PostingService.post(PostingRequest)`, `PostingRequest.{Dimensions,Line,dr,cr,ByRole,ById}`, `AccountRole.INPUT_VAT`, `JournalSourceType.VOUCHER`, `JournalDocType.PISR`, `Vendor.getPayableAccount()`; Task 2's `VoucherService.requireDraft`, `VoucherMath`.
- Produces: `Voucher VoucherService.post(UUID voucherId)` — posts the journal, sets `status = POSTED`, `journalId`, `voucherNumber`, `postedAt`, `postedBy`, and returns the reloaded voucher.

- [ ] **Step 1: Write the failing IT that asserts the exact journal**

`backend/src/test/java/com/datagami/rentaxis/core/service/voucher/PurchaseInvoicePostingIT.java`:

```java
package com.datagami.rentaxis.core.service.voucher;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.VendorService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@SpringBootTest
@Testcontainers
class PurchaseInvoicePostingIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired VoucherService vouchers;
    @Autowired AccountService accounts;
    @Autowired VendorService vendorService;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired JournalEntryRepository entries;
    @Autowired JournalLineRepository lines;
    @Autowired TenantDefaultAccountMappingRepository defaults;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired PropertyRepository propertyRepo;

    UUID tenantId, propertyId;
    Account pestControl, lifeguard, inputVat;
    Vendor vendor;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("PISR-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
        accounts.seedDefaultAccounts();

        Property p = new Property();
        p.setNameEn("Ocean Residencia");
        p.setEmirate(Emirate.DUBAI);
        propertyId = propertyRepo.save(p).getId();

        Account expenseGroup = accounts.getAccountByCode("D-01");
        pestControl = accounts.createLeaf("PEST CONTROL AMC OCEAN RESIDENCIA", expenseGroup, propertyId);
        lifeguard = accounts.createLeaf("LIFEGUARD EXP - OCEAN RESIDENCIA", expenseGroup, propertyId);
        inputVat = accounts.createLeaf("VAT Receivable", accounts.getAccountByCode("A-02"), null);
        mapDefault(AccountRole.INPUT_VAT, inputVat);

        Vendor v = new Vendor();
        v.setNameEn("Emrill Services LLC");
        vendor = vendorService.createVendor(v);

        fiscal.setBooksStartDate(LocalDate.of(2026, 10, 1));
        fiscal.lockThrough(LocalDate.of(2026, 9, 30));
    }

    @AfterEach void clear() { TenantContextHolder.clear(); }

    private void mapDefault(AccountRole role, Account a) {
        TenantDefaultAccountMapping m = new TenantDefaultAccountMapping();
        m.setRole(role);
        m.setAccount(a);
        defaults.save(m);
    }

    private Voucher draftTwoLineInvoice() {
        return vouchers.createDraft(new VoucherService.VoucherInput(
                VoucherType.PISR, LocalDate.of(2026, 10, 15), vendor.getId(), "EMR-4471",
                "Pest control and lifeguard — October", propertyId, null, null, null, null,
                List.of(new VoucherService.VoucherLineInput(pestControl.getId(), "Pest control AMC",
                                new BigDecimal("2000.00"), new BigDecimal("5"), propertyId, null),
                        new VoucherService.VoucherLineInput(lifeguard.getId(), "Lifeguard — zero rated",
                                new BigDecimal("3000.00"), BigDecimal.ZERO, propertyId, null))));
    }

    /**
     * Spec §10.1: Dr each expense line, Dr INPUT_VAT for the summed VAT, Cr the
     * vendor's payable account for the gross. Asserting the exact line set is the
     * point of this test — a balanced journal with the VAT on the wrong side or
     * folded into the expense would still satisfy the balance trigger.
     */
    @Test
    void postingWritesExpenseLinesInputVatAndTheVendorCredit() {
        Voucher posted = vouchers.post(draftTwoLineInvoice().getId());

        assertThat(posted.getStatus()).isEqualTo(VoucherStatus.POSTED);
        assertThat(posted.getVoucherNumber()).startsWith("PISR-");
        assertThat(posted.getPostedAt()).isNotNull();

        JournalEntry e = entries.findById(posted.getJournalId()).orElseThrow();
        assertThat(e.getDocType()).isEqualTo(JournalDocType.PISR);
        assertThat(e.getEntryDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(e.getSourceType()).isEqualTo(JournalSourceType.VOUCHER);
        assertThat(e.getSourceId()).isEqualTo(posted.getId());
        assertThat(e.getPropertyId()).isEqualTo(propertyId);
        assertThat(e.getEntryNumber()).isEqualTo(posted.getVoucherNumber());

        assertThat(lines.findByEntry_IdOrderByLineNoAsc(e.getId()))
                .extracting(l -> l.getAccount().getId(), JournalLine::getDebit, JournalLine::getCredit)
                .containsExactly(
                        tuple(pestControl.getId(), new BigDecimal("2000.00"), new BigDecimal("0.00")),
                        tuple(lifeguard.getId(), new BigDecimal("3000.00"), new BigDecimal("0.00")),
                        tuple(inputVat.getId(), new BigDecimal("100.00"), new BigDecimal("0.00")),
                        tuple(vendor.getPayableAccount().getId(), new BigDecimal("0.00"), new BigDecimal("5100.00")));
    }

    /** A fully zero-rated invoice must not emit a 0.00 INPUT_VAT line — the CHECK forbids it anyway. */
    @Test
    void aZeroRatedInvoiceHasNoInputVatLine() {
        Voucher v = vouchers.createDraft(new VoucherService.VoucherInput(
                VoucherType.PISR, LocalDate.of(2026, 10, 15), vendor.getId(), null, "Zero rated",
                propertyId, null, null, null, null,
                List.of(new VoucherService.VoucherLineInput(lifeguard.getId(), null,
                        new BigDecimal("1500.00"), BigDecimal.ZERO, propertyId, null))));
        Voucher posted = vouchers.post(v.getId());
        assertThat(lines.findByEntry_IdOrderByLineNoAsc(posted.getJournalId()))
                .hasSize(2)
                .noneMatch(l -> l.getAccount().getId().equals(inputVat.getId()));
    }

    /** Lines carry their own property dimension so the property-filtered GL is right. */
    @Test
    void everyJournalLineCarriesThePropertyDimension() {
        Voucher posted = vouchers.post(draftTwoLineInvoice().getId());
        assertThat(lines.findByEntry_IdOrderByLineNoAsc(posted.getJournalId()))
                .allSatisfy(l -> assertThat(l.getPropertyId()).isEqualTo(propertyId));
    }

    @Test
    void postingTwiceIsRejected() {
        Voucher posted = vouchers.post(draftTwoLineInvoice().getId());
        assertThatThrownBy(() -> vouchers.post(posted.getId()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("POSTED");
    }

    /** The period lock belongs to PostingService; this proves the voucher path does not bypass it. */
    @Test
    void postingIntoALockedPeriodIsRejected() {
        Voucher v = vouchers.createDraft(new VoucherService.VoucherInput(
                VoucherType.PISR, LocalDate.of(2026, 9, 15), vendor.getId(), null, "Before cut-over",
                propertyId, null, null, null, null,
                List.of(new VoucherService.VoucherLineInput(pestControl.getId(), null,
                        new BigDecimal("100.00"), BigDecimal.ZERO, propertyId, null))));
        assertThatThrownBy(() -> vouchers.post(v.getId()))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThat(vouchers.get(v.getId()).getStatus()).isEqualTo(VoucherStatus.DRAFT);
    }

    /** Spec §5.5: the vendor leaf is created silently — if it is missing, say so instead of NPEing. */
    @Test
    void aVendorWithoutAPayableAccountIsRejectedAtDraftTime() {
        Vendor bare = new Vendor();
        bare.setNameEn("No Ledger Co");
        Vendor saved = vendorService.createVendor(bare);
        saved.setPayableAccount(null);
        vendorService.updateVendor(saved.getId(), saved);
        assertThatThrownBy(() -> vouchers.createDraft(new VoucherService.VoucherInput(
                VoucherType.PISR, LocalDate.of(2026, 10, 15), saved.getId(), null, "x",
                propertyId, null, null, null, null,
                List.of(new VoucherService.VoucherLineInput(pestControl.getId(), null,
                        new BigDecimal("10.00"), BigDecimal.ZERO, null, null)))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("payable account");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.voucher.PurchaseInvoicePostingIT'`
Expected: FAIL — `VoucherService.post` does not exist.

- [ ] **Step 3: Add `post` to `VoucherService`**

Add these fields to the constructor injection list (they are `final` fields; `@RequiredArgsConstructor` picks them up):

```java
    private final com.datagami.rentaxis.core.service.ledger.PostingService posting;
    private final com.datagami.rentaxis.domain.repository.JournalEntryRepository journals;
```

Add the method:

```java
    /**
     * Freeze the draft and write its journal. Spec §10.1 / §10.2.
     *
     * <p>PISR: Dr every expense line, Dr INPUT_VAT with the summed per-line VAT,
     * Cr the vendor's payable leaf with the gross. BPV: Dr every line, Cr the
     * payment account with the total.
     *
     * <p>The balance check, the leaf-only check, the period lock and the entry
     * number all live in PostingService — this method's only job is to turn a
     * voucher into a PostingRequest.
     */
    @Transactional
    public Voucher post(UUID voucherId) {
        Voucher v = get(voucherId);
        requireDraft(v);

        BigDecimal net = VoucherMath.netTotal(v.getLines());
        BigDecimal vat = VoucherMath.vatTotal(v.getLines());
        BigDecimal gross = net.add(vat);

        PostingRequest.Dimensions headerDims =
                new PostingRequest.Dimensions(v.getPropertyId(), v.getUnitId(), null, null, null);

        List<PostingRequest.Line> journalLines = new ArrayList<>();
        for (VoucherLine l : v.getLines()) {
            journalLines.add(PostingRequest.dr(l.getAccount().getId(), l.getAmount())
                    .withDims(new PostingRequest.Dimensions(l.getPropertyId(), l.getUnitId(), null, null, null))
                    .withNarration(l.getDescription()));
        }

        switch (v.getDocType()) {
            case PISR -> {
                if (vat.signum() > 0) {
                    // INPUT_VAT is not property-scoped (AccountRole#isPropertyScoped), so the
                    // resolver falls through to the tenant default. One line for the whole
                    // invoice: the FTA return is filed per period, not per expense account.
                    journalLines.add(PostingRequest.dr(AccountRole.INPUT_VAT, vat)
                            .withDims(headerDims)
                            .withNarration("Input VAT"));
                }
                journalLines.add(PostingRequest.cr(v.getVendor().getPayableAccount().getId(), gross)
                        .withDims(headerDims)
                        .withNarration(v.getInvoiceNumber() == null
                                ? v.getVendor().getNameEn()
                                : v.getVendor().getNameEn() + " — " + v.getInvoiceNumber()));
            }
            case BPV -> journalLines.add(PostingRequest.cr(v.getPaymentAccount().getId(), net)
                    .withDims(headerDims)
                    .withNarration(v.getChequeNumber() == null
                            ? v.getPaymentAccount().getName()
                            : "Cheque " + v.getChequeNumber()));
            case RCP -> throw new BusinessRuleViolationException(
                    "Cash Receipt Vouchers are posted from the lease receipt screen");
        }

        JournalEntry entry = posting.post(new PostingRequest(
                v.getDocType().toDocType(), v.getDocDate(), v.getNarration(), headerDims,
                JournalSourceType.VOUCHER, v.getId(), null, journalLines));

        v.setStatus(VoucherStatus.POSTED);
        v.setJournalId(entry.getId());
        v.setVoucherNumber(entry.getEntryNumber());
        v.setPostedAt(entry.getPostedAt() == null ? Instant.now() : entry.getPostedAt());
        v.setPostedBy(entry.getPostedBy());
        v.setUpdatedAt(Instant.now());
        return vouchers.save(v);
    }
```

Add the imports: `com.datagami.rentaxis.core.service.ledger.PostingService`, `com.datagami.rentaxis.core.service.ledger.PostingRequest`, `com.datagami.rentaxis.domain.entity.JournalEntry`, `com.datagami.rentaxis.domain.entity.enums.AccountRole`, `com.datagami.rentaxis.domain.entity.enums.JournalSourceType`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.voucher.PurchaseInvoicePostingIT'`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/voucher/VoucherService.java backend/src/test/java/com/datagami/rentaxis/core/service/voucher/PurchaseInvoicePostingIT.java
git commit -m "feat(finance): post purchase/service invoices (PISR) through PostingService

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Post a Bank/Cash Payment Voucher (`BPV`) and amend either type

**Files:**
- Modify: `core/service/voucher/VoucherService.java` (add `amend`)
- Test: `core/service/voucher/PaymentVoucherPostingIT.java`, `core/service/voucher/VoucherAmendIT.java`

**Interfaces:**
- Consumes: Task 3's `VoucherService.post`; `PostingService.reverse(UUID entryId, LocalDate date, String reason)`.
- Produces: `Voucher VoucherService.amend(UUID voucherId, LocalDate reversalDate, String reason, VoucherInput replacement)` — reverses the posted journal, marks the original `REVERSED`, creates and posts a new voucher with `amendedFromId = voucherId`, and returns the **new** voucher.

- [ ] **Step 1: Write the failing BPV IT**

`backend/src/test/java/com/datagami/rentaxis/core/service/voucher/PaymentVoucherPostingIT.java`:

```java
package com.datagami.rentaxis.core.service.voucher;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.VendorService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@SpringBootTest
@Testcontainers
class PaymentVoucherPostingIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired VoucherService vouchers;
    @Autowired AccountService accounts;
    @Autowired VendorService vendorService;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired JournalEntryRepository entries;
    @Autowired JournalLineRepository lines;
    @Autowired LandlordOrgRepository orgRepo;

    UUID tenantId;
    Account bank, salaries;
    Vendor vendor;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("BPV-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
        accounts.seedDefaultAccounts();
        bank = accounts.createLeaf("Emirates Islamic - Head Office", accounts.getAccountByCode("A-02-02"), null);
        salaries = accounts.createLeaf("Staff Salaries", accounts.getAccountByCode("D-01"), null);
        Vendor v = new Vendor();
        v.setNameEn("Emrill Services LLC");
        vendor = vendorService.createVendor(v);
        fiscal.setBooksStartDate(LocalDate.of(2026, 10, 1));
        fiscal.lockThrough(LocalDate.of(2026, 9, 30));
    }

    @AfterEach void clear() { TenantContextHolder.clear(); }

    /**
     * Spec §10.2: Dr the lines, Cr the payment account. The lines here are a vendor
     * payable (settling an invoice) and an expense (a direct payment with no invoice)
     * in one voucher — that mix is the reason BPV lines accept any leaf rather than
     * being restricted to vendors.
     */
    @Test
    void postingDebitsEveryLineAndCreditsThePaymentAccount() {
        Voucher v = vouchers.createDraft(new VoucherService.VoucherInput(
                VoucherType.BPV, LocalDate.of(2026, 10, 20), null, null,
                "October payment run", null, null, bank.getId(), "000451", LocalDate.of(2026, 10, 22),
                List.of(new VoucherService.VoucherLineInput(vendor.getPayableAccount().getId(),
                                "Settle EMR-4471", new BigDecimal("5100.00"), BigDecimal.ZERO, null, null),
                        new VoucherService.VoucherLineInput(salaries.getId(),
                                "Watchman salary", new BigDecimal("2500.00"), BigDecimal.ZERO, null, null))));

        Voucher posted = vouchers.post(v.getId());
        JournalEntry e = entries.findById(posted.getJournalId()).orElseThrow();

        assertThat(e.getDocType()).isEqualTo(JournalDocType.BPV);
        assertThat(posted.getVoucherNumber()).startsWith("BPV-");
        assertThat(lines.findByEntry_IdOrderByLineNoAsc(e.getId()))
                .extracting(l -> l.getAccount().getId(), JournalLine::getDebit, JournalLine::getCredit)
                .containsExactly(
                        tuple(vendor.getPayableAccount().getId(), new BigDecimal("5100.00"), new BigDecimal("0.00")),
                        tuple(salaries.getId(), new BigDecimal("2500.00"), new BigDecimal("0.00")),
                        tuple(bank.getId(), new BigDecimal("0.00"), new BigDecimal("7600.00")));
    }

    /** The cheque number belongs on the bank line's narration — that is where a bank rec looks for it. */
    @Test
    void theChequeNumberReachesTheBankLineNarration() {
        Voucher v = vouchers.createDraft(new VoucherService.VoucherInput(
                VoucherType.BPV, LocalDate.of(2026, 10, 20), null, null, "x", null, null,
                bank.getId(), "000451", LocalDate.of(2026, 10, 22),
                List.of(new VoucherService.VoucherLineInput(salaries.getId(), null,
                        new BigDecimal("100.00"), BigDecimal.ZERO, null, null))));
        Voucher posted = vouchers.post(v.getId());
        assertThat(lines.findByEntry_IdOrderByLineNoAsc(posted.getJournalId()))
                .filteredOn(l -> l.getAccount().getId().equals(bank.getId()))
                .singleElement()
                .satisfies(l -> assertThat(l.getNarration()).isEqualTo("Cheque 000451"));
    }

    /** Paying out of petty cash is the same document with CASH as the payment account. */
    @Test
    void cashPaymentsUseTheCashLeafAsThePaymentAccount() {
        Account cash = accounts.createLeaf("Petty Cash", accounts.getAccountByCode("A-02"), null);
        Voucher v = vouchers.createDraft(new VoucherService.VoucherInput(
                VoucherType.BPV, LocalDate.of(2026, 10, 20), null, null, "Petty cash", null, null,
                cash.getId(), null, null,
                List.of(new VoucherService.VoucherLineInput(salaries.getId(), null,
                        new BigDecimal("300.00"), BigDecimal.ZERO, null, null))));
        Voucher posted = vouchers.post(v.getId());
        assertThat(lines.findByEntry_IdOrderByLineNoAsc(posted.getJournalId()))
                .extracting(l -> l.getAccount().getId(), JournalLine::getCredit)
                .contains(tuple(cash.getId(), new BigDecimal("300.00")));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.voucher.PaymentVoucherPostingIT'`
Expected: FAIL at `theChequeNumberReachesTheBankLineNarration` or earlier if Task 3's `switch` is incomplete. If Task 3 was implemented as written, all three pass immediately — that is fine; record it and move to Step 3.

- [ ] **Step 3: Write the failing amend IT**

`backend/src/test/java/com/datagami/rentaxis/core/service/voucher/VoucherAmendIT.java`:

```java
package com.datagami.rentaxis.core.service.voucher;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.VendorService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class VoucherAmendIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired VoucherService vouchers;
    @Autowired AccountService accounts;
    @Autowired VendorService vendorService;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired JournalEntryRepository entries;
    @Autowired JournalLineRepository lines;
    @Autowired TenantDefaultAccountMappingRepository defaults;
    @Autowired LandlordOrgRepository orgRepo;

    UUID tenantId;
    Account expense, inputVat;
    Vendor vendor;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("Amend-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
        accounts.seedDefaultAccounts();
        expense = accounts.createLeaf("Repairs & Maintenance", accounts.getAccountByCode("D-01"), null);
        inputVat = accounts.createLeaf("VAT Receivable", accounts.getAccountByCode("A-02"), null);
        TenantDefaultAccountMapping m = new TenantDefaultAccountMapping();
        m.setRole(AccountRole.INPUT_VAT);
        m.setAccount(inputVat);
        defaults.save(m);
        Vendor v = new Vendor();
        v.setNameEn("Al Shirawi FM");
        vendor = vendorService.createVendor(v);
        fiscal.setBooksStartDate(LocalDate.of(2026, 10, 1));
        fiscal.lockThrough(LocalDate.of(2026, 9, 30));
    }

    @AfterEach void clear() { TenantContextHolder.clear(); }

    private VoucherService.VoucherInput invoice(String amount) {
        return new VoucherService.VoucherInput(
                VoucherType.PISR, LocalDate.of(2026, 10, 12), vendor.getId(), "ASF-11",
                "AC servicing", null, null, null, null, null,
                List.of(new VoucherService.VoucherLineInput(expense.getId(), "AC servicing",
                        new BigDecimal(amount), new BigDecimal("5"), null, null)));
    }

    /**
     * Spec §10.1: "Amend = reversal + new voucher." The original journal is never
     * edited (entries are immutable); the ledger keeps three documents — the original,
     * its mirror, and the corrected one — which is exactly what an auditor expects to
     * find when an invoice amount changes after posting.
     */
    @Test
    void amendingReversesTheOriginalAndPostsAReplacement() {
        Voucher original = vouchers.post(vouchers.createDraft(invoice("4000.00")).getId());
        UUID originalJournalId = original.getJournalId();

        Voucher replacement = vouchers.amend(original.getId(), LocalDate.of(2026, 10, 18),
                "Vendor re-issued at the correct rate", invoice("3600.00"));

        Voucher reloadedOriginal = vouchers.get(original.getId());
        assertThat(reloadedOriginal.getStatus()).isEqualTo(VoucherStatus.REVERSED);
        assertThat(reloadedOriginal.getJournalId()).isEqualTo(originalJournalId);

        JournalEntry originalEntry = entries.findById(originalJournalId).orElseThrow();
        assertThat(originalEntry.getStatus()).isEqualTo(JournalStatus.REVERSED);
        assertThat(originalEntry.getReversedById()).isNotNull();

        JournalEntry mirror = entries.findById(originalEntry.getReversedById()).orElseThrow();
        assertThat(mirror.getEntryDate()).isEqualTo(LocalDate.of(2026, 10, 18));
        assertThat(mirror.getNarration()).contains("Vendor re-issued at the correct rate");

        assertThat(replacement.getId()).isNotEqualTo(original.getId());
        assertThat(replacement.getStatus()).isEqualTo(VoucherStatus.POSTED);
        assertThat(replacement.getAmendedFromId()).isEqualTo(original.getId());
        assertThat(lines.findByEntry_IdOrderByLineNoAsc(replacement.getJournalId()))
                .filteredOn(l -> l.getAccount().getId().equals(vendor.getPayableAccount().getId()))
                .singleElement()
                .satisfies(l -> assertThat(l.getCredit()).isEqualByComparingTo("3780.00"));
    }

    /** The three journals net to the corrected figure — 4000+200, −4200, +3600+180. */
    @Test
    void theVendorsNetPayableAfterAmendingIsTheCorrectedGross() {
        Voucher original = vouchers.post(vouchers.createDraft(invoice("4000.00")).getId());
        vouchers.amend(original.getId(), LocalDate.of(2026, 10, 18), "correction", invoice("3600.00"));

        BigDecimal net = lines.findAll().stream()
                .filter(l -> l.getAccount().getId().equals(vendor.getPayableAccount().getId()))
                .map(l -> l.getCredit().subtract(l.getDebit()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(net).isEqualByComparingTo("3780.00");
    }

    @Test
    void aDraftCannotBeAmended() {
        Voucher draft = vouchers.createDraft(invoice("4000.00"));
        assertThatThrownBy(() -> vouchers.amend(draft.getId(), LocalDate.of(2026, 10, 18), "x", invoice("100.00")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("DRAFT");
    }

    @Test
    void anAlreadyAmendedVoucherCannotBeAmendedAgain() {
        Voucher original = vouchers.post(vouchers.createDraft(invoice("4000.00")).getId());
        vouchers.amend(original.getId(), LocalDate.of(2026, 10, 18), "x", invoice("3600.00"));
        assertThatThrownBy(() -> vouchers.amend(original.getId(), LocalDate.of(2026, 10, 19), "y", invoice("3000.00")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("REVERSED");
    }
}
```

- [ ] **Step 4: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.voucher.VoucherAmendIT'`
Expected: FAIL — `VoucherService.amend` does not exist.

- [ ] **Step 5: Add `amend` to `VoucherService`**

```java
    /**
     * Correct a posted voucher: reverse its journal, mark it REVERSED, and post a
     * fresh voucher carrying the corrected figures with {@code amendedFromId} set
     * back to it (spec §10.1). Never edits the posted row — journal entries are
     * immutable, so an "edit" that left the original journal in place would put the
     * document and the ledger permanently out of step.
     *
     * @return the NEW voucher, already POSTED.
     */
    @Transactional
    public Voucher amend(UUID voucherId, LocalDate reversalDate, String reason, VoucherInput replacement) {
        Voucher original = get(voucherId);
        if (original.getStatus() != VoucherStatus.POSTED) {
            throw new BusinessRuleViolationException(
                    "Only a POSTED voucher can be amended; this one is " + original.getStatus());
        }
        if (reversalDate == null) throw new BusinessRuleViolationException("A reversal date is required");

        posting.reverse(original.getJournalId(), reversalDate, reason);
        original.setStatus(VoucherStatus.REVERSED);
        original.setUpdatedAt(Instant.now());
        vouchers.save(original);

        Voucher fresh = createDraft(replacement);
        fresh.setAmendedFromId(original.getId());
        vouchers.save(fresh);
        return post(fresh.getId());
    }
```

- [ ] **Step 6: Run the whole voucher package to verify everything passes**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.voucher.*'`
Expected: PASS — `VoucherMathTest` 6, `VoucherDraftIT` 8, `PurchaseInvoicePostingIT` 6, `PaymentVoucherPostingIT` 3, `VoucherAmendIT` 4.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/voucher backend/src/test/java/com/datagami/rentaxis/core/service/voucher
git commit -m "feat(finance): post bank/cash payment vouchers (BPV) and amend by reversal

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Voucher API — controller, DTOs, attachments

**Files:**
- Create: `api/dto/voucher/VoucherLineInputDTO.java`, `VoucherInputDTO.java`, `VoucherLineDTO.java`, `VoucherDTO.java`, `VoucherDetailDTO.java`, `VoucherAttachmentDTO.java`, `AmendVoucherDTO.java`
- Create: `domain/entity/VoucherAttachment.java`, `domain/repository/VoucherAttachmentRepository.java`
- Create: `core/service/voucher/VoucherAttachmentService.java`
- Create: `api/VoucherController.java`
- Test: `api/VoucherControllerIT.java`

**Interfaces:**
- Consumes: Tasks 2–4's `VoucherService`; the attachment pattern of `core/service/DeductionAttachmentService.java` (Azure blob when `AZURE_STORAGE_CONNECTION_STRING` is set, local disk under `rentaxis.assets.storage-path` otherwise) and `api/DeductionAttachmentController.java`.
- Produces:
  ```
  GET    /api/v1/finance/vouchers?docType&status&vendorId&propertyId&from&to&page&size  -> Page<VoucherDTO>
  GET    /api/v1/finance/vouchers/{id}                                                  -> VoucherDetailDTO
  POST   /api/v1/finance/vouchers            body VoucherInputDTO                       -> VoucherDetailDTO (201)
  PUT    /api/v1/finance/vouchers/{id}       body VoucherInputDTO                       -> VoucherDetailDTO
  DELETE /api/v1/finance/vouchers/{id}                                                  -> 204
  POST   /api/v1/finance/vouchers/{id}/post                                             -> VoucherDetailDTO
  POST   /api/v1/finance/vouchers/{id}/amend body AmendVoucherDTO                       -> VoucherDetailDTO (the new voucher)
  POST   /api/v1/finance/vouchers/{id}/attachments   multipart name,file                -> VoucherAttachmentDTO (201)
  GET    /api/v1/finance/vouchers/{id}/attachments                                      -> List<VoucherAttachmentDTO>
  GET    /api/v1/finance/vouchers/attachments/{attachmentId}/download                   -> file stream
  DELETE /api/v1/finance/vouchers/attachments/{attachmentId}                            -> 204
  ```
  All `@PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT')")`.

- [ ] **Step 1: Write the failing controller IT**

`backend/src/test/java/com/datagami/rentaxis/api/VoucherControllerIT.java`:

```java
package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.VendorService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@Testcontainers
class VoucherControllerIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccountService accounts;
    @Autowired VendorService vendorService;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired TenantDefaultAccountMappingRepository defaults;
    @Autowired LandlordOrgRepository orgRepo;

    UUID tenantId;
    Account expense;
    Vendor vendor;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("VCtl-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
        accounts.seedDefaultAccounts();
        expense = accounts.createLeaf("Security Services", accounts.getAccountByCode("D-01"), null);
        Account vat = accounts.createLeaf("VAT Receivable", accounts.getAccountByCode("A-02"), null);
        TenantDefaultAccountMapping m = new TenantDefaultAccountMapping();
        m.setRole(AccountRole.INPUT_VAT);
        m.setAccount(vat);
        defaults.save(m);
        Vendor v = new Vendor();
        v.setNameEn("Transguard");
        vendor = vendorService.createVendor(v);
        fiscal.setBooksStartDate(LocalDate.of(2026, 10, 1));
        fiscal.lockThrough(LocalDate.of(2026, 9, 30));
    }

    @AfterEach void clear() { TenantContextHolder.clear(); }

    private String body() throws Exception {
        return json.writeValueAsString(Map.of(
                "docType", "PISR",
                "docDate", "2026-10-09",
                "vendorId", vendor.getId().toString(),
                "invoiceNumber", "TG-99",
                "narration", "Security — October",
                "lines", List.of(Map.of(
                        "accountId", expense.getId().toString(),
                        "description", "Guards",
                        "amount", new BigDecimal("8000.00"),
                        "vatRate", new BigDecimal("5")))));
    }

    @Test
    @WithMockUser(roles = "ACCOUNTANT")
    void createPostAndReadBackAVoucher() throws Exception {
        String created = mvc.perform(post("/api/v1/finance/vouchers")
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.netTotal").value(8000.00))
                .andExpect(jsonPath("$.vatTotal").value(400.00))
                .andExpect(jsonPath("$.grossTotal").value(8400.00))
                .andExpect(jsonPath("$.lines[0].accountCode").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(created).get("id").asText();

        mvc.perform(post("/api/v1/finance/vouchers/{id}/post", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.voucherNumber").value(org.hamcrest.Matchers.startsWith("PISR-")));

        mvc.perform(get("/api/v1/finance/vouchers").param("docType", "PISR").param("page", "0").param("size", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].vendorName").value("Transguard"));
    }

    @Test
    @WithMockUser(roles = "ACCOUNTANT")
    void editingAPostedVoucherIsA400NotA500() throws Exception {
        String created = mvc.perform(post("/api/v1/finance/vouchers")
                .contentType(MediaType.APPLICATION_JSON).content(body())).andReturn().getResponse().getContentAsString();
        String id = json.readTree(created).get("id").asText();
        mvc.perform(post("/api/v1/finance/vouchers/{id}/post", id)).andExpect(status().isOk());
        mvc.perform(put("/api/v1/finance/vouchers/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("POSTED")));
    }

    @Test
    @WithMockUser(roles = "PROPERTY_MANAGER")
    void aPropertyManagerCannotTouchVouchers() throws Exception {
        mvc.perform(get("/api/v1/finance/vouchers")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/finance/vouchers")
                .contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ACCOUNTANT")
    void attachmentsUploadListAndDelete() throws Exception {
        String created = mvc.perform(post("/api/v1/finance/vouchers")
                .contentType(MediaType.APPLICATION_JSON).content(body())).andReturn().getResponse().getContentAsString();
        String id = json.readTree(created).get("id").asText();

        MockMultipartFile file = new MockMultipartFile(
                "file", "invoice.pdf", "application/pdf", "%PDF-1.4 fake".getBytes());
        String att = mvc.perform(multipart("/api/v1/finance/vouchers/{id}/attachments", id)
                        .file(file).param("name", "Vendor invoice"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Vendor invoice"))
                .andReturn().getResponse().getContentAsString();
        String attachmentId = json.readTree(att).get("id").asText();

        mvc.perform(get("/api/v1/finance/vouchers/{id}/attachments", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(delete("/api/v1/finance/vouchers/attachments/{aid}", attachmentId))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "ACCOUNTANT")
    void anExecutableUploadIsRejected() throws Exception {
        String created = mvc.perform(post("/api/v1/finance/vouchers")
                .contentType(MediaType.APPLICATION_JSON).content(body())).andReturn().getResponse().getContentAsString();
        String id = json.readTree(created).get("id").asText();
        MockMultipartFile bad = new MockMultipartFile(
                "file", "payload.sh", "application/x-sh", "rm -rf /".getBytes());
        mvc.perform(multipart("/api/v1/finance/vouchers/{id}/attachments", id)
                        .file(bad).param("name", "nope"))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.api.VoucherControllerIT'`
Expected: FAIL — 404/compilation; `VoucherController` does not exist.

- [ ] **Step 3: Write the DTOs**

`api/dto/voucher/VoucherLineInputDTO.java`:

```java
package com.datagami.rentaxis.api.dto.voucher;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

public record VoucherLineInputDTO(
        @NotNull UUID accountId,
        String description,
        @NotNull @Positive BigDecimal amount,
        BigDecimal vatRate,
        UUID propertyId,
        UUID unitId) {}
```

`api/dto/voucher/VoucherInputDTO.java`:

```java
package com.datagami.rentaxis.api.dto.voucher;

import com.datagami.rentaxis.domain.entity.enums.VoucherType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record VoucherInputDTO(
        @NotNull VoucherType docType,
        @NotNull LocalDate docDate,
        UUID vendorId,
        String invoiceNumber,
        String narration,
        UUID propertyId,
        UUID unitId,
        UUID paymentAccountId,
        String chequeNumber,
        LocalDate chequeDate,
        @NotEmpty @Valid List<VoucherLineInputDTO> lines) {}
```

`api/dto/voucher/AmendVoucherDTO.java`:

```java
package com.datagami.rentaxis.api.dto.voucher;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record AmendVoucherDTO(@NotNull LocalDate reversalDate, String reason,
                              @NotNull @Valid VoucherInputDTO replacement) {}
```

`api/dto/voucher/VoucherLineDTO.java`:

```java
package com.datagami.rentaxis.api.dto.voucher;

import com.datagami.rentaxis.domain.entity.VoucherLine;

import java.math.BigDecimal;
import java.util.UUID;

public record VoucherLineDTO(int lineNo, UUID accountId, String accountCode, String accountName,
                             String description, BigDecimal amount, BigDecimal vatRate,
                             BigDecimal vatAmount, UUID propertyId, UUID unitId) {

    public static VoucherLineDTO of(VoucherLine l) {
        return new VoucherLineDTO(l.getLineNo(), l.getAccount().getId(), l.getAccount().getCode(),
                l.getAccount().getName(), l.getDescription(), l.getAmount(), l.getVatRate(),
                l.getVatAmount(), l.getPropertyId(), l.getUnitId());
    }
}
```

`api/dto/voucher/VoucherDTO.java`:

```java
package com.datagami.rentaxis.api.dto.voucher;

import com.datagami.rentaxis.core.service.voucher.VoucherMath;
import com.datagami.rentaxis.domain.entity.Voucher;
import com.datagami.rentaxis.domain.entity.enums.VoucherStatus;
import com.datagami.rentaxis.domain.entity.enums.VoucherType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** List row. Totals are derived from the lines, never stored — one source of truth. */
public record VoucherDTO(UUID id, VoucherType docType, LocalDate docDate, UUID vendorId, String vendorName,
                         String invoiceNumber, String narration, UUID propertyId, UUID unitId,
                         UUID paymentAccountId, String paymentAccountName, String chequeNumber, LocalDate chequeDate,
                         VoucherStatus status, UUID journalId, String voucherNumber, UUID amendedFromId,
                         BigDecimal netTotal, BigDecimal vatTotal, BigDecimal grossTotal, Instant postedAt) {

    public static VoucherDTO of(Voucher v) {
        return new VoucherDTO(v.getId(), v.getDocType(), v.getDocDate(),
                v.getVendor() == null ? null : v.getVendor().getId(),
                v.getVendor() == null ? null : v.getVendor().getNameEn(),
                v.getInvoiceNumber(), v.getNarration(), v.getPropertyId(), v.getUnitId(),
                v.getPaymentAccount() == null ? null : v.getPaymentAccount().getId(),
                v.getPaymentAccount() == null ? null : v.getPaymentAccount().getName(),
                v.getChequeNumber(), v.getChequeDate(), v.getStatus(), v.getJournalId(),
                v.getVoucherNumber(), v.getAmendedFromId(),
                VoucherMath.netTotal(v.getLines()), VoucherMath.vatTotal(v.getLines()),
                VoucherMath.grossTotal(v.getLines()), v.getPostedAt());
    }
}
```

`api/dto/voucher/VoucherDetailDTO.java` — **flat**, not `{header, lines}`. `VoucherControllerIT` reads `$.status`, `$.netTotal` and `$.lines[0].accountCode` at the top level, and `VoucherForm.tsx` binds the header fields directly; a nested wrapper would put every field access two hops deep for no gain:

```java
package com.datagami.rentaxis.api.dto.voucher;

import com.datagami.rentaxis.core.service.voucher.VoucherMath;
import com.datagami.rentaxis.domain.entity.Voucher;
import com.datagami.rentaxis.domain.entity.enums.VoucherStatus;
import com.datagami.rentaxis.domain.entity.enums.VoucherType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Detail response. Deliberately flat rather than {header, lines}: the web form binds
 * the header fields directly and a nested wrapper would make every field access in
 * VoucherForm.tsx two hops deep for no gain.
 */
public record VoucherDetailDTO(UUID id, VoucherType docType, LocalDate docDate,
                               UUID vendorId, String vendorName, String invoiceNumber, String narration,
                               UUID propertyId, UUID unitId, UUID paymentAccountId, String paymentAccountName,
                               String chequeNumber, LocalDate chequeDate, VoucherStatus status,
                               UUID journalId, String voucherNumber, UUID amendedFromId,
                               BigDecimal netTotal, BigDecimal vatTotal, BigDecimal grossTotal, Instant postedAt,
                               List<VoucherLineDTO> lines, List<VoucherAttachmentDTO> attachments) {

    public static VoucherDetailDTO of(Voucher v, List<VoucherAttachmentDTO> attachments) {
        return new VoucherDetailDTO(v.getId(), v.getDocType(), v.getDocDate(),
                v.getVendor() == null ? null : v.getVendor().getId(),
                v.getVendor() == null ? null : v.getVendor().getNameEn(),
                v.getInvoiceNumber(), v.getNarration(), v.getPropertyId(), v.getUnitId(),
                v.getPaymentAccount() == null ? null : v.getPaymentAccount().getId(),
                v.getPaymentAccount() == null ? null : v.getPaymentAccount().getName(),
                v.getChequeNumber(), v.getChequeDate(), v.getStatus(), v.getJournalId(),
                v.getVoucherNumber(), v.getAmendedFromId(),
                VoucherMath.netTotal(v.getLines()), VoucherMath.vatTotal(v.getLines()),
                VoucherMath.grossTotal(v.getLines()), v.getPostedAt(),
                v.getLines().stream().map(VoucherLineDTO::of).toList(), attachments);
    }
}
```

`api/dto/voucher/VoucherAttachmentDTO.java`:

```java
package com.datagami.rentaxis.api.dto.voucher;

import com.datagami.rentaxis.domain.entity.VoucherAttachment;

import java.time.Instant;
import java.util.UUID;

public record VoucherAttachmentDTO(UUID id, UUID voucherId, String name, String fileUrl,
                                   String fileType, Long fileSize, Instant uploadedAt) {

    public static VoucherAttachmentDTO of(VoucherAttachment a) {
        return new VoucherAttachmentDTO(a.getId(), a.getVoucherId(), a.getName(), a.getFileUrl(),
                a.getFileType(), a.getFileSize(), a.getUploadedAt());
    }
}
```

- [ ] **Step 4: Write the attachment entity, repository and service**

`domain/entity/VoucherAttachment.java` — a copy of `SettlementDeductionAttachment` keyed on `voucher_id`:

```java
package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "voucher_attachments")
@Getter
@Setter
public class VoucherAttachment extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "voucher_id", nullable = false)
    private UUID voucherId;

    @Column(nullable = false)
    private String name;

    @Column(name = "file_url", nullable = false, length = 1024)
    private String fileUrl;

    @Column(name = "file_type", length = 100)
    private String fileType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "uploaded_at")
    private Instant uploadedAt = Instant.now();
}
```

`domain/repository/VoucherAttachmentRepository.java`:

```java
package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.VoucherAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface VoucherAttachmentRepository extends JpaRepository<VoucherAttachment, UUID> {
    List<VoucherAttachment> findByVoucherIdOrderByUploadedAtAsc(UUID voucherId);
    long countByVoucherId(UUID voucherId);
}
```

`core/service/voucher/VoucherAttachmentService.java` — same storage strategy as `DeductionAttachmentService` (Azure blob when configured, local disk otherwise), narrowed to document types and re-pointed at `vouchers/{id}/`:

```java
package com.datagami.rentaxis.core.service.voucher;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.datagami.rentaxis.api.dto.voucher.VoucherAttachmentDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Voucher;
import com.datagami.rentaxis.domain.entity.VoucherAttachment;
import com.datagami.rentaxis.domain.repository.VoucherAttachmentRepository;
import com.datagami.rentaxis.domain.repository.VoucherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Invoice scans and payment proofs. Storage is the same two-mode arrangement as
 * DeductionAttachmentService — Azure blob under {@code tenant-<uuid>} when a
 * connection string is configured, local disk otherwise — so nothing new has to be
 * provisioned for this feature.
 *
 * <p>The allowed type list is narrower than the deduction one on purpose: a voucher
 * attachment is paperwork (a PDF or a photo of an invoice), never a video walkthrough.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VoucherAttachmentService {

    private final VoucherAttachmentRepository attachments;
    private final VoucherRepository vouchers;

    private static final int MAX_ATTACHMENTS_PER_VOUCHER = 10;
    private static final long MAX_FILE_SIZE = 25L * 1024 * 1024;   // 25MB — an invoice scan, not a video
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf", "image/jpeg", "image/png", "image/heic", "image/heif", "image/webp");

    @Value("${AZURE_STORAGE_CONNECTION_STRING:}")
    private String azureConnectionString;

    @Value("${AZURE_STORAGE_CONTAINER_PREFIX:tenant-}")
    private String containerPrefix;

    @Value("${rentaxis.assets.storage-path:./data/assets}")
    private String localStoragePath;

    @Transactional
    public VoucherAttachmentDTO upload(UUID voucherId, String docName, MultipartFile file) throws IOException {
        Voucher v = requireVoucher(voucherId);

        if (attachments.countByVoucherId(voucherId) >= MAX_ATTACHMENTS_PER_VOUCHER) {
            throw new BusinessRuleViolationException(
                    "Maximum " + MAX_ATTACHMENTS_PER_VOUCHER + " attachments per voucher");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessRuleViolationException("File size exceeds maximum of 25MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            throw new BusinessRuleViolationException("File type not allowed. Accepted: PDF, JPEG, PNG, HEIC, WEBP");
        }

        String fileName = UUID.randomUUID() + extension(file.getOriginalFilename());
        String fileUrl = (azureConnectionString != null && !azureConnectionString.isBlank())
                ? uploadToAzure(v.getId(), fileName, file.getInputStream(), file.getSize())
                : saveToLocal(v.getId(), fileName, file.getInputStream());

        VoucherAttachment a = new VoucherAttachment();
        a.setVoucherId(voucherId);
        a.setName(docName);
        a.setFileUrl(fileUrl);
        a.setFileType(contentType);
        a.setFileSize(file.getSize());
        a.setUploadedAt(Instant.now());
        return VoucherAttachmentDTO.of(attachments.save(a));
    }

    @Transactional(readOnly = true)
    public List<VoucherAttachmentDTO> list(UUID voucherId) {
        requireVoucher(voucherId);
        return attachments.findByVoucherIdOrderByUploadedAtAsc(voucherId).stream()
                .map(VoucherAttachmentDTO::of).toList();
    }

    @Transactional(readOnly = true)
    public VoucherAttachmentDTO get(UUID attachmentId) {
        return VoucherAttachmentDTO.of(requireAttachment(attachmentId));
    }

    public InputStream download(UUID attachmentId) throws IOException {
        VoucherAttachment a = requireAttachment(attachmentId);
        String url = a.getFileUrl();
        if (url.startsWith("https://") && url.contains(".blob.core.windows.net")) {
            return downloadFromAzure(url);
        }
        return Files.newInputStream(Path.of(localStoragePath)
                .resolve(url.replace("/api/v1/assets/serve/", "")));
    }

    @Transactional
    public void delete(UUID attachmentId) {
        attachments.delete(requireAttachment(attachmentId));
    }

    private Voucher requireVoucher(UUID voucherId) {
        Voucher v = vouchers.findById(voucherId).orElseThrow(() -> new NotFoundException("Voucher not found"));
        UUID current = TenantContextHolder.getTenantId();
        if (current != null && !current.equals(v.getTenantId())) throw new NotFoundException("Voucher not found");
        return v;
    }

    private VoucherAttachment requireAttachment(UUID attachmentId) {
        VoucherAttachment a = attachments.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException("Attachment not found"));
        UUID current = TenantContextHolder.getTenantId();
        if (current != null && !current.equals(a.getTenantId())) throw new NotFoundException("Attachment not found");
        return a;
    }

    private String uploadToAzure(UUID voucherId, String fileName, InputStream in, long size) {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) throw new IllegalStateException("Cannot upload: tenant context is not set");
        String containerName = containerPrefix + tenantId;
        BlobServiceClient svc = new BlobServiceClientBuilder().connectionString(azureConnectionString).buildClient();
        BlobContainerClient container = svc.getBlobContainerClient(containerName);
        if (!container.exists()) container.create();
        String blobPath = "vouchers/" + voucherId + "/" + fileName;
        container.getBlobClient(blobPath).upload(in, size, true);
        return svc.getAccountUrl() + "/" + containerName + "/" + blobPath;
    }

    private InputStream downloadFromAzure(String blobUrl) {
        String marker = ".blob.core.windows.net/";
        String path = blobUrl.substring(blobUrl.indexOf(marker) + marker.length());
        int slash = path.indexOf('/');
        BlobClient blob = new BlobServiceClientBuilder().connectionString(azureConnectionString).buildClient()
                .getBlobContainerClient(path.substring(0, slash))
                .getBlobClient(path.substring(slash + 1));
        return blob.openInputStream();
    }

    private String saveToLocal(UUID voucherId, String fileName, InputStream in) throws IOException {
        Path dir = Path.of(localStoragePath, "vouchers", voucherId.toString());
        Files.createDirectories(dir);
        Files.copy(in, dir.resolve(fileName), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return "/api/v1/assets/serve/vouchers/" + voucherId + "/" + fileName;
    }

    private String extension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }
}
```

- [ ] **Step 5: Write the controller**

`api/VoucherController.java`:

```java
package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.voucher.*;
import com.datagami.rentaxis.core.service.voucher.VoucherAttachmentService;
import com.datagami.rentaxis.core.service.voucher.VoucherService;
import com.datagami.rentaxis.domain.entity.Voucher;
import com.datagami.rentaxis.domain.entity.enums.VoucherStatus;
import com.datagami.rentaxis.domain.entity.enums.VoucherType;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/finance/vouchers")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT')")
public class VoucherController {

    private final VoucherService vouchers;
    private final VoucherAttachmentService attachments;

    @GetMapping
    public ResponseEntity<Page<VoucherDTO>> list(
            @RequestParam(required = false) VoucherType docType,
            @RequestParam(required = false) VoucherStatus status,
            @RequestParam(required = false) UUID vendorId,
            @RequestParam(required = false) UUID propertyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(vouchers.list(docType, status, vendorId, propertyId, from, to,
                PageRequest.of(page, Math.min(size, 200))).map(VoucherDTO::of));
    }

    @GetMapping("/{id}")
    public ResponseEntity<VoucherDetailDTO> get(@PathVariable UUID id) {
        return ResponseEntity.ok(detail(vouchers.get(id)));
    }

    @PostMapping
    public ResponseEntity<VoucherDetailDTO> create(@Valid @RequestBody VoucherInputDTO body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(detail(vouchers.createDraft(toInput(body))));
    }

    @PutMapping("/{id}")
    public ResponseEntity<VoucherDetailDTO> update(@PathVariable UUID id, @Valid @RequestBody VoucherInputDTO body) {
        return ResponseEntity.ok(detail(vouchers.updateDraft(id, toInput(body))));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        vouchers.deleteDraft(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/post")
    public ResponseEntity<VoucherDetailDTO> post(@PathVariable UUID id) {
        return ResponseEntity.ok(detail(vouchers.post(id)));
    }

    @PostMapping("/{id}/amend")
    public ResponseEntity<VoucherDetailDTO> amend(@PathVariable UUID id, @Valid @RequestBody AmendVoucherDTO body) {
        return ResponseEntity.ok(detail(
                vouchers.amend(id, body.reversalDate(), body.reason(), toInput(body.replacement()))));
    }

    @PostMapping(path = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<VoucherAttachmentDTO> upload(@PathVariable UUID id,
                                                       @RequestParam("name") String name,
                                                       @RequestParam("file") MultipartFile file) throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED).body(attachments.upload(id, name, file));
    }

    @GetMapping("/{id}/attachments")
    public ResponseEntity<List<VoucherAttachmentDTO>> listAttachments(@PathVariable UUID id) {
        return ResponseEntity.ok(attachments.list(id));
    }

    @GetMapping("/attachments/{attachmentId}/download")
    public ResponseEntity<Resource> download(@PathVariable UUID attachmentId) throws IOException {
        VoucherAttachmentDTO meta = attachments.get(attachmentId);
        String contentType = meta.fileType() == null ? "application/octet-stream" : meta.fileType();
        String safeName = meta.name().replaceAll("[\"\\r\\n\\\\/:*?<>|]", "_");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeName + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(new InputStreamResource(attachments.download(attachmentId)));
    }

    @DeleteMapping("/attachments/{attachmentId}")
    public ResponseEntity<Void> deleteAttachment(@PathVariable UUID attachmentId) {
        attachments.delete(attachmentId);
        return ResponseEntity.noContent().build();
    }

    private VoucherDetailDTO detail(Voucher v) {
        return VoucherDetailDTO.of(v, attachments.list(v.getId()));
    }

    private VoucherService.VoucherInput toInput(VoucherInputDTO d) {
        return new VoucherService.VoucherInput(d.docType(), d.docDate(), d.vendorId(), d.invoiceNumber(),
                d.narration(), d.propertyId(), d.unitId(), d.paymentAccountId(), d.chequeNumber(), d.chequeDate(),
                d.lines().stream().map(l -> new VoucherService.VoucherLineInput(
                        l.accountId(), l.description(), l.amount(), l.vatRate(), l.propertyId(), l.unitId())).toList());
    }
}
```

- [ ] **Step 6: Run the IT to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.api.VoucherControllerIT'`
Expected: PASS, 5 tests. If `anExecutableUploadIsRejected` returns 500 instead of 400, the `BusinessRuleViolationException` mapping in `GlobalExceptionHandler` is not reached — check that `VoucherAttachmentService` throws `BusinessRuleViolationException` and not `IllegalStateException`.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api backend/src/main/java/com/datagami/rentaxis/core/service/voucher backend/src/main/java/com/datagami/rentaxis/domain backend/src/test/java/com/datagami/rentaxis/api/VoucherControllerIT.java
git commit -m "feat(finance): voucher REST API with invoice attachments

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Changeset 88 — cut-over schema

**Files:**
- Create: `backend/src/main/resources/db/changelog/changesets/88-cutover.yaml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml` (append one `include`)
- Test: `backend/src/test/java/com/datagami/rentaxis/domain/CutoverSchemaIT.java`

**Interfaces:**
- Produces: tables `import_batches`, `import_batch_leases`, `opening_balance_snapshots`, `opening_balance_postings`; column `import_jobs.import_batch_id`.

- [ ] **Step 1: Write the failing schema test**

`backend/src/test/java/com/datagami/rentaxis/domain/CutoverSchemaIT.java`:

```java
package com.datagami.rentaxis.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class CutoverSchemaIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired JdbcTemplate jdbc;

    private UUID tenant() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO landlord_org (id, name) VALUES (?, ?)", id, "T-" + id);
        return id;
    }

    private UUID batch(UUID tenant, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO import_batches (id, tenant_id, kind, status, label, created_at) "
                + "VALUES (?,?,'CONTRACT_IMPORT',?,'Al Ashram cut-over',now())", id, tenant, status);
        return id;
    }

    @Test
    void aBatchCarriesItsLeasesAndDroppingItDropsTheLinks() {
        UUID t = tenant();
        UUID b = batch(t, "DRAFT");
        UUID leaseId = UUID.randomUUID();
        jdbc.update("INSERT INTO import_batch_leases (batch_id, lease_id) VALUES (?,?)", b, leaseId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM import_batch_leases WHERE batch_id = ?", Integer.class, b))
                .isEqualTo(1);
        jdbc.update("DELETE FROM import_batches WHERE id = ?", b);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM import_batch_leases WHERE batch_id = ?", Integer.class, b))
                .isZero();
    }

    @Test
    void theSameLeaseCannotBeLinkedToTheSameBatchTwice() {
        UUID t = tenant();
        UUID b = batch(t, "DRAFT");
        UUID leaseId = UUID.randomUUID();
        jdbc.update("INSERT INTO import_batch_leases (batch_id, lease_id) VALUES (?,?)", b, leaseId);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO import_batch_leases (batch_id, lease_id) VALUES (?,?)", b, leaseId))
                .hasMessageContaining("import_batch_leases_pkey");
    }

    @Test
    void aSnapshotRowIsUniquePerTenantAndAccountCode() {
        UUID t = tenant();
        jdbc.update("INSERT INTO opening_balance_snapshots (id, tenant_id, account_code, account_name, debit, credit, uploaded_at) "
                + "VALUES (?,?,'166269','Rent Receivable - Tulip 7',15000.00,0,now())", UUID.randomUUID(), t);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO opening_balance_snapshots (id, tenant_id, account_code, account_name, debit, credit, uploaded_at) "
                        + "VALUES (?,?,'166269','dup',1,0,now())", UUID.randomUUID(), t))
                .hasMessageContaining("ux_opening_balance_snapshots_tenant_code");
    }

    /** A tenant opens its books once. A second OB row would mean two opening journals. */
    @Test
    void thereIsAtMostOneOpeningBalancePostingPerTenant() {
        UUID t = tenant();
        jdbc.update("INSERT INTO opening_balance_postings (id, tenant_id, as_of, created_at) VALUES (?,?,CURRENT_DATE,now())",
                UUID.randomUUID(), t);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO opening_balance_postings (id, tenant_id, as_of, created_at) VALUES (?,?,CURRENT_DATE,now())",
                UUID.randomUUID(), t))
                .hasMessageContaining("ux_opening_balance_postings_tenant");
    }

    @Test
    void importJobsCarryTheBatchId() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'import_jobs' AND column_name = 'import_batch_id'",
                Integer.class);
        assertThat(n).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.domain.CutoverSchemaIT'`
Expected: FAIL — `relation "import_batches" does not exist`.

- [ ] **Step 3: Write the changeset**

`backend/src/main/resources/db/changelog/changesets/88-cutover.yaml`:

```yaml
databaseChangeLog:
  # Cut-over: import batches, opening-balance snapshot and the OB posting marker.
  # Spec §10.3.
  #
  # WHY import_batch_leases HAS NO FOREIGN KEY ON lease_id: accounting v2 plan 2
  # rebuilds the lease model (lease_lines replace lease_charges, cheques replace
  # payment_schedules, several lease columns are dropped). A FK from this table
  # into `leases` would make that rework drag this changeset along with it, and a
  # dangling row here is harmless — ImportBatchService skips a lease id that no
  # longer resolves and says so in the reverse result. The batch_id side IS a FK
  # with ON DELETE CASCADE because a batch owns its links outright.
  #
  # WHY opening_balance_postings EXISTS AT ALL rather than deriving "has the tenant
  # opened its books?" from the journal: the reconciliation screen has to compare
  # PACT's figures against balances DERIVED FROM THE CONTRACT IMPORT ONLY, which
  # means subtracting the OB journal's own lines from the trial balance as at D-1.
  # Finding that journal by scanning for doc_type = 'OB' would also match a
  # reversal, so the id is recorded explicitly. The unique index on tenant_id is
  # the "books open once" rule in the place that can actually enforce it.
  - changeSet:
      id: 88-cutover
      author: claude
      comment: Import batches, opening-balance snapshot rows and the one-per-tenant OB posting marker
      changes:
        - createTable:
            tableName: import_batches
            columns:
              - column: { name: id, type: uuid, constraints: { primaryKey: true, nullable: false } }
              - column: { name: tenant_id, type: uuid, constraints: { nullable: false } }
              - column: { name: kind, type: varchar(30), defaultValue: CONTRACT_IMPORT, constraints: { nullable: false } }
              - column: { name: status, type: varchar(10), defaultValue: DRAFT, constraints: { nullable: false } }
              - column: { name: label, type: varchar(120) }
              - column: { name: import_job_id, type: uuid }
              - column: { name: leases_imported, type: int, defaultValueNumeric: 0, constraints: { nullable: false } }
              - column: { name: journals_posted, type: int, defaultValueNumeric: 0, constraints: { nullable: false } }
              - column: { name: posted_at, type: timestamptz }
              - column: { name: posted_by, type: uuid }
              - column: { name: reversed_at, type: timestamptz }
              - column: { name: reversed_by, type: uuid }
              - column: { name: created_at, type: timestamptz, defaultValueComputed: now(), constraints: { nullable: false } }
        - addForeignKeyConstraint:
            baseTableName: import_batches
            baseColumnNames: tenant_id
            referencedTableName: landlord_org
            referencedColumnNames: id
            constraintName: fk_import_batches_tenant
        - addForeignKeyConstraint:
            baseTableName: import_batches
            baseColumnNames: import_job_id
            referencedTableName: import_jobs
            referencedColumnNames: id
            constraintName: fk_import_batches_job
        - sql:
            sql: >-
              ALTER TABLE import_batches
                ADD CONSTRAINT ck_import_batches_kind   CHECK (kind IN ('CONTRACT_IMPORT')),
                ADD CONSTRAINT ck_import_batches_status CHECK (status IN ('DRAFT','POSTED','REVERSED'))
        - createIndex: { tableName: import_batches, indexName: idx_import_batches_tenant, columns: [ { column: { name: tenant_id } } ] }

        - createTable:
            tableName: import_batch_leases
            columns:
              - column: { name: batch_id, type: uuid, constraints: { nullable: false } }
              - column: { name: lease_id, type: uuid, constraints: { nullable: false } }
        - addPrimaryKey:
            tableName: import_batch_leases
            columnNames: "batch_id, lease_id"
            constraintName: import_batch_leases_pkey
        - addForeignKeyConstraint:
            baseTableName: import_batch_leases
            baseColumnNames: batch_id
            referencedTableName: import_batches
            referencedColumnNames: id
            constraintName: fk_import_batch_leases_batch
            onDelete: CASCADE

        - addColumn:
            tableName: import_jobs
            columns:
              - column: { name: import_batch_id, type: uuid }

        - createTable:
            tableName: opening_balance_snapshots
            columns:
              - column: { name: id, type: uuid, constraints: { primaryKey: true, nullable: false } }
              - column: { name: tenant_id, type: uuid, constraints: { nullable: false } }
              - column: { name: account_code, type: varchar(40), constraints: { nullable: false } }
              - column: { name: account_name, type: varchar(255) }
              - column: { name: debit, type: "decimal(14,2)", defaultValueNumeric: 0, constraints: { nullable: false } }
              - column: { name: credit, type: "decimal(14,2)", defaultValueNumeric: 0, constraints: { nullable: false } }
              - column: { name: uploaded_at, type: timestamptz, defaultValueComputed: now() }
              - column: { name: uploaded_by, type: uuid }
        - addForeignKeyConstraint:
            baseTableName: opening_balance_snapshots
            baseColumnNames: tenant_id
            referencedTableName: landlord_org
            referencedColumnNames: id
            constraintName: fk_ob_snapshots_tenant
        - sql:
            comment: >-
              Keyed by CODE, not account_id, so a PACT trial balance can be uploaded before
              every account exists in our chart. Unmatched codes are reported on the
              reconciliation screen rather than silently dropped on import.
            sql: >-
              CREATE UNIQUE INDEX ux_opening_balance_snapshots_tenant_code
              ON opening_balance_snapshots (tenant_id, account_code)
        - sql:
            sql: >-
              ALTER TABLE opening_balance_snapshots
                ADD CONSTRAINT ck_ob_snapshots_one_side CHECK (debit = 0 OR credit = 0)

        - createTable:
            tableName: opening_balance_postings
            columns:
              - column: { name: id, type: uuid, constraints: { primaryKey: true, nullable: false } }
              - column: { name: tenant_id, type: uuid, constraints: { nullable: false } }
              - column: { name: as_of, type: date, constraints: { nullable: false } }
              - column: { name: journal_id, type: uuid }
              - column: { name: posted_by, type: uuid }
              - column: { name: posted_at, type: timestamptz }
              - column: { name: created_at, type: timestamptz, defaultValueComputed: now(), constraints: { nullable: false } }
        - addForeignKeyConstraint:
            baseTableName: opening_balance_postings
            baseColumnNames: tenant_id
            referencedTableName: landlord_org
            referencedColumnNames: id
            constraintName: fk_ob_postings_tenant
        - sql:
            sql: >-
              CREATE UNIQUE INDEX ux_opening_balance_postings_tenant ON opening_balance_postings (tenant_id)
      rollback:
        - dropTable: { tableName: opening_balance_postings }
        - dropTable: { tableName: opening_balance_snapshots }
        - dropColumn: { tableName: import_jobs, columnName: import_batch_id }
        - dropTable: { tableName: import_batch_leases }
        - dropTable: { tableName: import_batches }

  # SEPARATE CHANGESET, SAME FILE, ON PURPOSE. `cheques` is created by accounting v2
  # plan 2, and tasks 6-9 of plan 4 ship before plan 2 lands, so on a database without
  # that table this column cannot be added yet. A precondition on the changeset above
  # would skip the whole cut-over schema; here it skips only this one column, and
  # onFail: CONTINUE (not MARK_RAN) means Liquibase retries it on the next deploy
  # rather than marking it permanently applied — the same pattern as changeset 80.
  #
  # WHY THE COLUMN EXISTS: the contract import writes every cheque row as DRAFT and
  # parks the status the spreadsheet asked for here. Bulk post then REPLAYS the
  # transitions through ChequeService so each one writes its CRT/CBR, instead of
  # stamping a status into the column and leaving the ledger without the journal.
  - changeSet:
      id: 88-cutover-cheque-imported-status
      author: claude
      comment: Parks the PACT cheque status on an imported row so bulk post can replay it
      preConditions:
        - onFail: CONTINUE
        - onFailMessage: >-
            Skipped 88-cutover-cheque-imported-status: the cheques table does not exist yet
            (accounting v2 plan 2 creates it). The rest of 88-cutover applied. Re-deploy after
            plan 2 and this changeset installs itself.
        - tableExists: { tableName: cheques }
      changes:
        - addColumn:
            tableName: cheques
            columns:
              - column: { name: imported_status, type: varchar(20) }
      rollback:
        - dropColumn: { tableName: cheques, columnName: imported_status }
```

Append to `db.changelog-master.yaml`:

```yaml
  - include:
      file: db/changelog/changesets/88-cutover.yaml
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.domain.CutoverSchemaIT'`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/changelog backend/src/test/java/com/datagami/rentaxis/domain/CutoverSchemaIT.java
git commit -m "feat(finance): changeset 88 — import batches, opening-balance snapshot and OB marker

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Import batches — entities, service, reverse

**Files:**
- Create: `domain/entity/enums/ImportBatchKind.java`, `domain/entity/enums/ImportBatchStatus.java`
- Create: `domain/entity/ImportBatch.java`, `domain/entity/ImportBatchLease.java`
- Create: `domain/repository/ImportBatchRepository.java`, `domain/repository/ImportBatchLeaseRepository.java`
- Create: `core/service/cutover/ImportBatchService.java`, `api/dto/cutover/ImportBatchDTO.java`, `api/ImportBatchController.java`
- Test: `core/service/cutover/ImportBatchReverseIT.java`

**Interfaces:**
- Consumes: `PostingService.post`, `PostingService.reverse`, `JournalEntryRepository.findByImportBatchIdOrderByCreatedAtAsc(UUID)`; Task 6 tables.
- Consumes (from plan 2, Task 10 onwards only): `LeaseService.revertToDraft(UUID leaseId)`. Until Plan 2 exists, `ImportBatchService` calls it through the small interface `LeaseReverter` defined below, and Plan 2's `LeaseService` implements it. **This is the only seam this plan introduces**; it exists so Tasks 7–9 can be built and tested before Plan 2 lands.
- Produces:
  ```java
  public interface LeaseReverter { void revertToDraft(UUID leaseId); }   // core/service/cutover/LeaseReverter.java
  ImportBatch ImportBatchService.create(UUID importJobId, String label);
  void        ImportBatchService.linkLease(UUID batchId, UUID leaseId);
  List<UUID>  ImportBatchService.leaseIds(UUID batchId);
  List<ImportBatch> ImportBatchService.list();
  ImportBatch ImportBatchService.get(UUID batchId);
  ImportBatch ImportBatchService.markPosted(UUID batchId, int journalsPosted);
  ImportBatch ImportBatchService.reverse(UUID batchId, LocalDate date, String reason);
  ```
  Endpoints (`hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT')`):
  - `GET  /api/v1/finance/import-batches` → `List<ImportBatchDTO>`
  - `GET  /api/v1/finance/import-batches/{id}` → `ImportBatchDTO`
  - `POST /api/v1/finance/import-batches/{id}/reverse` body `{date, reason}` → `ImportBatchDTO`

- [ ] **Step 1: Write the failing reverse IT**

`backend/src/test/java/com/datagami/rentaxis/core/service/cutover/ImportBatchReverseIT.java`:

```java
package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Testcontainers
class ImportBatchReverseIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired ImportBatchService batches;
    @Autowired PostingService posting;
    @Autowired AccountService accounts;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired JournalEntryRepository entries;
    @Autowired LedgerQueryHelper ledgerHelper;   // see Step 2 note — or inject LedgerQueryService directly
    @Autowired LandlordOrgRepository orgRepo;

    /**
     * Plan 2 owns LeaseService. Mocking the seam keeps this IT runnable before Plan 2
     * exists and keeps the assertion honest: what matters here is that reverse() asks
     * for every imported lease to go back to DRAFT, exactly once each.
     */
    @MockitoBean LeaseReverter leaseReverter;

    UUID tenantId;
    Account receivable, advanceRent;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("Batch-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
        accounts.seedDefaultAccounts();
        receivable = accounts.createLeaf("Rent Receivable - Tulip 7", accounts.getAccountByCode("A-02-01"), null);
        advanceRent = accounts.createLeaf("Advance Rent - Tulip 7", accounts.getAccountByCode("B-01-01"), null);
        fiscal.setBooksStartDate(LocalDate.of(2026, 10, 1));
        fiscal.lockThrough(LocalDate.of(2026, 9, 30));
    }

    @AfterEach void clear() { TenantContextHolder.clear(); }

    private JournalEntry importJournal(UUID batchId, String amount, LocalDate date) {
        return posting.post(new PostingRequest(
                JournalDocType.TCO, date, "Imported contract", PostingRequest.Dimensions.none(),
                JournalSourceType.IMPORT, UUID.randomUUID(), batchId,
                List.of(PostingRequest.dr(receivable.getId(), new BigDecimal(amount)),
                        PostingRequest.cr(advanceRent.getId(), new BigDecimal(amount)))));
    }

    /**
     * Spec §10.3: "Reverse batch reverses every journal in the batch and returns leases
     * to DRAFT." The journals are dated inside the locked period on purpose — import
     * journals are exempt from the period lock, and so is their reversal, because
     * PostingService.reverse copies importBatchId onto the mirror entry.
     */
    @Test
    void reversingABatchReversesEveryJournalAndReturnsEveryLeaseToDraft() {
        ImportBatch b = batches.create(null, "Al Ashram cut-over");
        UUID leaseA = UUID.randomUUID(), leaseB = UUID.randomUUID();
        batches.linkLease(b.getId(), leaseA);
        batches.linkLease(b.getId(), leaseB);
        JournalEntry e1 = importJournal(b.getId(), "61000.00", LocalDate.of(2026, 9, 11));
        JournalEntry e2 = importJournal(b.getId(), "45000.00", LocalDate.of(2026, 9, 12));
        batches.markPosted(b.getId(), 2);

        ImportBatch reversed = batches.reverse(b.getId(), LocalDate.of(2026, 9, 30), "Re-import with corrected rents");

        assertThat(reversed.getStatus()).isEqualTo(ImportBatchStatus.REVERSED);
        assertThat(entries.findById(e1.getId()).orElseThrow().getStatus()).isEqualTo(JournalStatus.REVERSED);
        assertThat(entries.findById(e2.getId()).orElseThrow().getStatus()).isEqualTo(JournalStatus.REVERSED);
        verify(leaseReverter).revertToDraft(leaseA);
        verify(leaseReverter).revertToDraft(leaseB);
    }

    /** After a reverse, every account touched by the batch nets to zero. */
    @Test
    void theLedgerIsFlatAfterReversingABatch() {
        ImportBatch b = batches.create(null, "cut-over");
        importJournal(b.getId(), "61000.00", LocalDate.of(2026, 9, 11));
        batches.markPosted(b.getId(), 1);
        batches.reverse(b.getId(), LocalDate.of(2026, 9, 30), "redo");

        assertThat(ledgerHelper.balanceOf(receivable.getId(), LocalDate.of(2026, 9, 30))).isEqualByComparingTo("0.00");
        assertThat(ledgerHelper.balanceOf(advanceRent.getId(), LocalDate.of(2026, 9, 30))).isEqualByComparingTo("0.00");
    }

    /** The mirror entries carry the batch id too, or a second reverse would miss them. */
    @Test
    void theReversingEntriesBelongToTheSameBatch() {
        ImportBatch b = batches.create(null, "cut-over");
        importJournal(b.getId(), "1000.00", LocalDate.of(2026, 9, 11));
        batches.markPosted(b.getId(), 1);
        batches.reverse(b.getId(), LocalDate.of(2026, 9, 30), "redo");
        assertThat(entries.findByImportBatchIdOrderByCreatedAtAsc(b.getId())).hasSize(2);
    }

    @Test
    void reversingTwiceIsRejectedRatherThanDoublePosting() {
        ImportBatch b = batches.create(null, "cut-over");
        importJournal(b.getId(), "1000.00", LocalDate.of(2026, 9, 11));
        batches.markPosted(b.getId(), 1);
        batches.reverse(b.getId(), LocalDate.of(2026, 9, 30), "redo");
        assertThatThrownBy(() -> batches.reverse(b.getId(), LocalDate.of(2026, 9, 30), "again"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("REVERSED");
        assertThat(entries.findByImportBatchIdOrderByCreatedAtAsc(b.getId())).hasSize(2);
    }

    @Test
    void aDraftBatchHasNothingToReverse() {
        ImportBatch b = batches.create(null, "cut-over");
        assertThatThrownBy(() -> batches.reverse(b.getId(), LocalDate.of(2026, 9, 30), "x"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("DRAFT");
        verify(leaseReverter, times(0)).revertToDraft(org.mockito.ArgumentMatchers.any());
    }
}
```

`LedgerQueryHelper` in the test above is a two-line test fixture — add it to the same package:

```java
package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestComponent;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Test-only shorthand: the signed (debit-positive) balance of one account as at a date. */
@TestComponent
@RequiredArgsConstructor
class LedgerQueryHelper {
    private final LedgerQueryService ledger;

    BigDecimal balanceOf(UUID accountId, LocalDate asOf) {
        return ledger.trialBalance(asOf, null).stream()
                .filter(r -> r.accountId().equals(accountId))
                .map(r -> r.balance())
                .findFirst().orElse(BigDecimal.ZERO);
    }
}
```

Register it on the test class with `@org.springframework.boot.test.context.SpringBootTest(classes = {})`? No — instead annotate `ImportBatchReverseIT` with `@Import(LedgerQueryHelper.class)` (`org.springframework.context.annotation.Import`).

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.cutover.ImportBatchReverseIT'`
Expected: FAIL — `ImportBatchService` does not exist.

- [ ] **Step 3: Write the enums, entities and repositories**

```java
package com.datagami.rentaxis.domain.entity.enums;

/** Room for future loaders (a PDC-only batch, a vendor-balance batch); one value today. */
public enum ImportBatchKind { CONTRACT_IMPORT }
```

```java
package com.datagami.rentaxis.domain.entity.enums;

public enum ImportBatchStatus { DRAFT, POSTED, REVERSED }
```

`domain/entity/ImportBatch.java`:

```java
package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.ImportBatchKind;
import com.datagami.rentaxis.domain.entity.enums.ImportBatchStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * The unit of undo for the cut-over. Every journal written while importing carries
 * this row's id in {@code journal_entries.import_batch_id}, which is also what
 * exempts those journals from the period lock (spec §4.2, §10.3).
 */
@Entity
@Table(name = "import_batches")
@Getter
@Setter
public class ImportBatch extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ImportBatchKind kind = ImportBatchKind.CONTRACT_IMPORT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ImportBatchStatus status = ImportBatchStatus.DRAFT;

    @Column(length = 120) private String label;
    @Column(name = "import_job_id") private UUID importJobId;
    @Column(name = "leases_imported", nullable = false) private int leasesImported;
    @Column(name = "journals_posted", nullable = false) private int journalsPosted;
    @Column(name = "posted_at") private Instant postedAt;
    @Column(name = "posted_by") private UUID postedBy;
    @Column(name = "reversed_at") private Instant reversedAt;
    @Column(name = "reversed_by") private UUID reversedBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
}
```

`domain/entity/ImportBatchLease.java`:

```java
package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Link row. Not tenant-filtered: it is reachable only through its batch, which is. */
@Entity
@Table(name = "import_batch_leases")
@IdClass(ImportBatchLease.Key.class)
@Getter
@Setter
public class ImportBatchLease {

    @Id @Column(name = "batch_id", nullable = false) private UUID batchId;
    @Id @Column(name = "lease_id", nullable = false) private UUID leaseId;

    public static class Key implements Serializable {
        private UUID batchId;
        private UUID leaseId;
        public Key() {}
        public Key(UUID batchId, UUID leaseId) { this.batchId = batchId; this.leaseId = leaseId; }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return Objects.equals(batchId, k.batchId) && Objects.equals(leaseId, k.leaseId);
        }
        @Override public int hashCode() { return Objects.hash(batchId, leaseId); }
    }
}
```

`domain/repository/ImportBatchRepository.java`:

```java
package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.ImportBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ImportBatchRepository extends JpaRepository<ImportBatch, UUID> {
    List<ImportBatch> findAllByOrderByCreatedAtAsc();
}
```

`domain/repository/ImportBatchLeaseRepository.java`:

```java
package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.ImportBatchLease;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ImportBatchLeaseRepository extends JpaRepository<ImportBatchLease, ImportBatchLease.Key> {
    List<ImportBatchLease> findByBatchId(UUID batchId);
}
```

- [ ] **Step 4: Write `LeaseReverter` and `ImportBatchService`**

`core/service/cutover/LeaseReverter.java`:

```java
package com.datagami.rentaxis.core.service.cutover;

import java.util.UUID;

/**
 * The one thing reversing an import batch needs from the lease module: put an
 * imported lease back into DRAFT so the corrected spreadsheet can be re-imported.
 *
 * <p>Plan 2's LeaseService does not have this method; plan 4 Task 11 Step 3 adds it and
 * declares {@code LeaseService implements LeaseReverter}. The interface exists so the
 * cut-over package does not depend on the whole lease module, and so batch reverse can
 * be built and tested (Task 7) before plan 2 lands.
 *
 * <p>Deliberately NOT {@code terminateLease}: termination posts unearned-rent and
 * settlement journals, which is the opposite of an undo. The batch's journals have
 * already been reversed by the time this is called, so an implementation must post
 * nothing.
 */
public interface LeaseReverter {
    /** ACTIVE (or any posted state) → DRAFT. A lease id that no longer exists is ignored. */
    void revertToDraft(UUID leaseId);
}
```

`core/service/cutover/ImportBatchService.java`:

```java
package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.domain.entity.ImportBatch;
import com.datagami.rentaxis.domain.entity.ImportBatchLease;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.enums.ImportBatchStatus;
import com.datagami.rentaxis.domain.entity.enums.JournalStatus;
import com.datagami.rentaxis.domain.repository.ImportBatchLeaseRepository;
import com.datagami.rentaxis.domain.repository.ImportBatchRepository;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ImportBatchService {

    private final ImportBatchRepository batches;
    private final ImportBatchLeaseRepository links;
    private final JournalEntryRepository journals;
    private final PostingService posting;

    /**
     * ObjectProvider, not a direct dependency: plan 2 supplies the implementation and
     * this service must start without it (tasks 6-9 ship before plan 2). Reversing a
     * batch that has leases with no reverter configured is a hard error, not a silent
     * half-undo — see reverse().
     */
    private final ObjectProvider<LeaseReverter> leaseReverter;

    @Transactional
    public ImportBatch create(UUID importJobId, String label) {
        ImportBatch b = new ImportBatch();
        b.setImportJobId(importJobId);
        b.setLabel(label);
        b.setStatus(ImportBatchStatus.DRAFT);
        return batches.save(b);
    }

    @Transactional
    public void linkLease(UUID batchId, UUID leaseId) {
        ImportBatch b = get(batchId);
        ImportBatchLease link = new ImportBatchLease();
        link.setBatchId(batchId);
        link.setLeaseId(leaseId);
        links.save(link);
        b.setLeasesImported(links.findByBatchId(batchId).size());
        batches.save(b);
    }

    @Transactional(readOnly = true)
    public List<UUID> leaseIds(UUID batchId) {
        return links.findByBatchId(batchId).stream().map(ImportBatchLease::getLeaseId).toList();
    }

    @Transactional(readOnly = true)
    public List<ImportBatch> list() {
        return batches.findAllByOrderByCreatedAtAsc();
    }

    @Transactional(readOnly = true)
    public ImportBatch get(UUID batchId) {
        return batches.findById(batchId).orElseThrow(() -> new NotFoundException("Import batch not found"));
    }

    @Transactional
    public ImportBatch markPosted(UUID batchId, int journalsPosted) {
        ImportBatch b = get(batchId);
        b.setStatus(ImportBatchStatus.POSTED);
        b.setJournalsPosted(journalsPosted);
        b.setPostedAt(Instant.now());
        return batches.save(b);
    }

    /**
     * Undo the whole cut-over import (spec §10.3). Every journal the batch wrote is
     * reversed newest-first, then every lease it created goes back to DRAFT.
     *
     * <p>Newest-first matters: a CBR posted after a CRT for the same cheque has to come
     * off before the CRT does, or the intermediate ledger state is nonsense even though
     * the end state balances.
     *
     * <p>Entries whose {@code reversalOfId} is set are skipped — those are the mirrors
     * from an earlier partial reverse, and PostingService refuses to reverse a reversal.
     */
    @Transactional
    public ImportBatch reverse(UUID batchId, LocalDate date, String reason) {
        ImportBatch b = get(batchId);
        if (b.getStatus() != ImportBatchStatus.POSTED) {
            throw new BusinessRuleViolationException(
                    "Import batch is " + b.getStatus() + "; only a POSTED batch can be reversed");
        }
        if (date == null) throw new BusinessRuleViolationException("A reversal date is required");

        List<UUID> leases = leaseIds(batchId);
        if (!leases.isEmpty() && leaseReverter.getIfAvailable() == null) {
            throw new BusinessRuleViolationException(
                    "This batch created " + leases.size() + " leases but no lease module is available to "
                            + "return them to DRAFT. Reversing the journals alone would leave posted leases "
                            + "with no journals.");
        }

        List<JournalEntry> toReverse = new ArrayList<>(
                journals.findByImportBatchIdOrderByCreatedAtAsc(batchId).stream()
                        .filter(e -> e.getStatus() == JournalStatus.POSTED && e.getReversalOfId() == null)
                        .toList());
        java.util.Collections.reverse(toReverse);
        for (JournalEntry e : toReverse) {
            posting.reverse(e.getId(), date, reason == null ? "Import batch reversed" : reason);
        }
        log.info("Reversed {} journals for import batch {}", toReverse.size(), batchId);

        LeaseReverter reverter = leaseReverter.getIfAvailable();
        if (reverter != null) {
            for (UUID leaseId : leases) reverter.revertToDraft(leaseId);
        }

        b.setStatus(ImportBatchStatus.REVERSED);
        b.setReversedAt(Instant.now());
        return batches.save(b);
    }
}
```

- [ ] **Step 5: Write the DTO and the controller**

`api/dto/cutover/ImportBatchDTO.java`:

```java
package com.datagami.rentaxis.api.dto.cutover;

import com.datagami.rentaxis.domain.entity.ImportBatch;
import com.datagami.rentaxis.domain.entity.enums.ImportBatchKind;
import com.datagami.rentaxis.domain.entity.enums.ImportBatchStatus;

import java.time.Instant;
import java.util.UUID;

public record ImportBatchDTO(UUID id, ImportBatchKind kind, ImportBatchStatus status, String label,
                             UUID importJobId, int leasesImported, int journalsPosted,
                             Instant postedAt, Instant reversedAt, Instant createdAt) {

    public static ImportBatchDTO of(ImportBatch b) {
        return new ImportBatchDTO(b.getId(), b.getKind(), b.getStatus(), b.getLabel(), b.getImportJobId(),
                b.getLeasesImported(), b.getJournalsPosted(), b.getPostedAt(), b.getReversedAt(), b.getCreatedAt());
    }
}
```

`api/ImportBatchController.java`:

```java
package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.cutover.ImportBatchDTO;
import com.datagami.rentaxis.core.service.cutover.ImportBatchService;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/finance/import-batches")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT')")
public class ImportBatchController {

    private final ImportBatchService batches;

    public record ReverseBatchDTO(@NotNull LocalDate date, String reason) {}

    @GetMapping
    public ResponseEntity<List<ImportBatchDTO>> list() {
        return ResponseEntity.ok(batches.list().stream().map(ImportBatchDTO::of).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ImportBatchDTO> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ImportBatchDTO.of(batches.get(id)));
    }

    @PostMapping("/{id}/reverse")
    public ResponseEntity<ImportBatchDTO> reverse(@PathVariable UUID id,
                                                  @RequestBody ReverseBatchDTO body) {
        return ResponseEntity.ok(ImportBatchDTO.of(batches.reverse(id, body.date(), body.reason())));
    }
}
```

- [ ] **Step 6: Run the IT to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.cutover.ImportBatchReverseIT'`
Expected: PASS, 5 tests.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis backend/src/test/java/com/datagami/rentaxis/core/service/cutover
git commit -m "feat(finance): import batches with a whole-batch journal reverse

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Opening balances — grid, PACT trial-balance CSV, OB journal

**Files:**
- Create: `domain/entity/OpeningBalanceSnapshotRow.java`, `domain/entity/OpeningBalancePosting.java`
- Create: `domain/repository/OpeningBalanceSnapshotRowRepository.java`, `domain/repository/OpeningBalancePostingRepository.java`
- Create: `core/service/cutover/TrialBalanceCsvParser.java`, `core/service/cutover/OpeningBalanceService.java`
- Test: `core/service/cutover/TrialBalanceCsvParserTest.java`, `core/service/cutover/OpeningBalanceIT.java`

**Interfaces:**
- Consumes: `PostingService.post`, `PostingService.reverse`, `AccountResolver`, `AccountRole.OPENING_BALANCE_DIFFERENCE`, `TenantFiscalSettingsService.get().getBooksStartDate()`, `LedgerQueryService.trialBalance(LocalDate, UUID)`, `PropertyAccountMappingRepository`, `TenantDefaultAccountMappingRepository`, `AccountRepository`; Task 6 tables.
- Produces:
  ```java
  // core/service/cutover/TrialBalanceCsvParser.java — pure, no Spring
  record CsvRow(String code, String name, BigDecimal debit, BigDecimal credit) {}
  record CsvParseResult(List<CsvRow> rows, List<String> problems) {}
  static CsvParseResult parse(InputStream in);      // header row optional; columns code,name,debit,credit

  // core/service/cutover/OpeningBalanceService.java
  record OpeningBalanceRow(UUID accountId, String code, String name, String accountType, UUID propertyId,
                           boolean derived, AccountRole derivedRole,
                           BigDecimal derivedDebit, BigDecimal derivedCredit,
                           BigDecimal enteredDebit, BigDecimal enteredCredit) {}
  record OpeningBalanceGrid(LocalDate asOf, boolean posted, UUID journalId, String journalNumber,
                            List<OpeningBalanceRow> rows, BigDecimal totalDebit, BigDecimal totalCredit,
                            BigDecimal difference) {}
  record SnapshotUploadResult(int stored, List<String> unmatchedCodes, List<String> problems) {}

  OpeningBalanceGrid grid();
  SnapshotUploadResult uploadSnapshot(InputStream csv);
  void setRow(UUID accountId, BigDecimal debit, BigDecimal credit);
  JournalEntry post();
  JournalEntry reverse(LocalDate date, String reason);
  Set<AccountRole> DERIVED_ROLES;    // public static final
  ```
  `difference` is `totalDebit − totalCredit` over the **entered** figures; the OB journal closes it against `OPENING_BALANCE_DIFFERENCE`.

- [ ] **Step 1: Write the failing CSV parser unit test**

`backend/src/test/java/com/datagami/rentaxis/core/service/cutover/TrialBalanceCsvParserTest.java`:

```java
package com.datagami.rentaxis.core.service.cutover;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TrialBalanceCsvParserTest {

    private static TrialBalanceCsvParser.CsvParseResult parse(String csv) {
        return TrialBalanceCsvParser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parsesCodeNameDebitCredit() {
        var r = parse("""
                Account Code,Account Name,Debit,Credit
                166269,Rent Receivable - Tulip 7,15000.00,0.00
                145661,Rental Income Tulip 7,0.00,61000.00
                """);
        assertThat(r.problems()).isEmpty();
        assertThat(r.rows()).hasSize(2);
        assertThat(r.rows().get(0).code()).isEqualTo("166269");
        assertThat(r.rows().get(0).debit()).isEqualByComparingTo("15000.00");
        assertThat(r.rows().get(1).credit()).isEqualByComparingTo("61000.00");
    }

    /** PACT exports without a header when the report is saved rather than printed. */
    @Test
    void aFileWithNoHeaderRowStillParses() {
        var r = parse("166269,Rent Receivable - Tulip 7,15000.00,0.00\n");
        assertThat(r.problems()).isEmpty();
        assertThat(r.rows()).singleElement().satisfies(row ->
                assertThat(row.code()).isEqualTo("166269"));
    }

    /** PACT prints thousands separators, parentheses for credits and (AED) suffixes. */
    @Test
    void thousandsSeparatorsQuotesAndParenthesesAreUnderstood() {
        var r = parse("""
                Account Code,Account Name,Debit,Credit
                "166269","Rent Receivable - Tulip 7","1,015,000.00","0.00"
                "145661","Rental Income Tulip 7","0.00","(61,000.00)"
                """);
        assertThat(r.problems()).isEmpty();
        assertThat(r.rows().get(0).debit()).isEqualByComparingTo("1015000.00");
        assertThat(r.rows().get(1).credit()).isEqualByComparingTo("61000.00");
    }

    @Test
    void blankLinesAndTotalRowsAreSkippedNotReportedAsErrors() {
        var r = parse("""
                Account Code,Account Name,Debit,Credit
                166269,Rent Receivable - Tulip 7,15000.00,0.00

                ,,15000.00,15000.00
                """);
        assertThat(r.rows()).hasSize(1);
        assertThat(r.problems()).isEmpty();
    }

    @Test
    void anUnparseableAmountIsReportedWithItsLineNumberAndTheRowIsDropped() {
        var r = parse("""
                Account Code,Account Name,Debit,Credit
                166269,Rent Receivable,abc,0.00
                145661,Rental Income,0.00,100.00
                """);
        assertThat(r.rows()).hasSize(1);
        assertThat(r.problems()).singleElement().asString().contains("line 2").contains("abc");
    }

    /** Both sides filled is a PACT export artefact and must not become two journal lines. */
    @Test
    void aRowWithBothSidesIsNettedToOneSide() {
        var r = parse("""
                Account Code,Account Name,Debit,Credit
                166269,Rent Receivable,15000.00,3000.00
                """);
        assertThat(r.rows()).singleElement().satisfies(row -> {
            assertThat(row.debit()).isEqualByComparingTo("12000.00");
            assertThat(row.credit()).isEqualByComparingTo("0.00");
        });
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.cutover.TrialBalanceCsvParserTest'`
Expected: FAIL — `TrialBalanceCsvParser` does not exist.

- [ ] **Step 3: Write the parser**

`core/service/cutover/TrialBalanceCsvParser.java`:

```java
package com.datagami.rentaxis.core.service.cutover;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads PACT's trial-balance export: {@code code, name, debit, credit}.
 *
 * <p>Deliberately hand-rolled rather than a CSV library: the only quoting PACT emits
 * is double quotes around fields containing its own thousands separators, and the
 * shapes that actually break a naive split (a parenthesised credit, a totals row with
 * no code, a header that may or may not be there) are cheaper to handle here than to
 * configure around. Every rejected line is reported with its number so the accountant
 * can fix the file rather than wonder why a balance is missing.
 */
public final class TrialBalanceCsvParser {

    private TrialBalanceCsvParser() {}

    public record CsvRow(String code, String name, BigDecimal debit, BigDecimal credit) {}

    public record CsvParseResult(List<CsvRow> rows, List<String> problems) {}

    public static CsvParseResult parse(InputStream in) {
        List<CsvRow> rows = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        int lineNo = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) continue;
                String[] parts = splitCsv(line);
                if (parts.length < 4) {
                    problems.add("line " + lineNo + ": expected 4 columns (code, name, debit, credit), got " + parts.length);
                    continue;
                }
                String code = parts[0].trim();
                if (code.isEmpty()) continue;                              // totals row
                if (isHeader(code, parts[2], parts[3])) continue;          // header row, present or not

                BigDecimal debit, credit;
                try {
                    // abs(): a minus sign or a bracket inside a column means "this is really
                    // the other side", and the column already told us which side it is. PACT
                    // never exports a genuinely negative debit. Without abs(), "(61,000.00)"
                    // in the credit column would net to a 61,000 DEBIT — the exact opposite.
                    debit = amount(parts[2]).abs();
                    credit = amount(parts[3]).abs();
                } catch (NumberFormatException e) {
                    problems.add("line " + lineNo + ": '" + e.getMessage() + "' is not an amount");
                    continue;
                }
                // Net a two-sided row down to one side; a journal line can only have one.
                BigDecimal net = debit.subtract(credit);
                rows.add(new CsvRow(code, parts[1].trim(),
                        net.signum() > 0 ? net : BigDecimal.ZERO.setScale(2),
                        net.signum() < 0 ? net.negate() : BigDecimal.ZERO.setScale(2)));
            }
        } catch (IOException e) {
            problems.add("could not read the file: " + e.getMessage());
        }
        return new CsvParseResult(rows, problems);
    }

    private static boolean isHeader(String code, String debit, String credit) {
        String d = unquote(debit).toLowerCase();
        String c = unquote(credit).toLowerCase();
        return d.contains("debit") || c.contains("credit") || code.toLowerCase().contains("code");
    }

    /** Strips quotes, spaces, thousands separators, currency suffixes; treats (x) as -x. */
    private static BigDecimal amount(String raw) {
        String s = unquote(raw).replace(",", "").replace("AED", "").replace("aed", "").trim();
        if (s.isEmpty() || s.equals("-")) return BigDecimal.ZERO.setScale(2);
        boolean negative = s.startsWith("(") && s.endsWith(")");
        if (negative) s = s.substring(1, s.length() - 1).trim();
        try {
            BigDecimal v = new BigDecimal(s).setScale(2, RoundingMode.HALF_UP);
            return negative ? v.negate() : v;
        } catch (NumberFormatException e) {
            throw new NumberFormatException(raw.trim());
        }
    }

    private static String unquote(String s) {
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length() - 1);
        return t.trim();
    }

    /** Splits on commas that are not inside double quotes. */
    private static String[] splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (char ch : line.toCharArray()) {
            if (ch == '"') { inQuotes = !inQuotes; cur.append(ch); }
            else if (ch == ',' && !inQuotes) { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(ch);
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }
}
```

- [ ] **Step 4: Run the parser test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.cutover.TrialBalanceCsvParserTest'`
Expected: PASS, 6 tests.

- [ ] **Step 5: Write the snapshot / posting entities and repositories**

`domain/entity/OpeningBalanceSnapshotRow.java`:

```java
package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One line of the PACT trial balance as at (books_start_date − 1), keyed by PACT's
 * account code. This is both the upload target and the store the grid edits, so the
 * reconciliation screen can be re-run at any time against the same figures the OB
 * journal was built from (spec §10.3).
 */
@Entity
@Table(name = "opening_balance_snapshots")
@Getter
@Setter
public class OpeningBalanceSnapshotRow extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "account_code", nullable = false, length = 40)
    private String accountCode;

    @Column(name = "account_name")
    private String accountName;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal debit = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal credit = BigDecimal.ZERO;

    @Column(name = "uploaded_at") private Instant uploadedAt = Instant.now();
    @Column(name = "uploaded_by") private UUID uploadedBy;
}
```

`domain/entity/OpeningBalancePosting.java`:

```java
package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** At most one per tenant (unique index, changeset 88): the books open once. */
@Entity
@Table(name = "opening_balance_postings")
@Getter
@Setter
public class OpeningBalancePosting extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "as_of", nullable = false) private LocalDate asOf;
    @Column(name = "journal_id") private UUID journalId;
    @Column(name = "posted_by") private UUID postedBy;
    @Column(name = "posted_at") private Instant postedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
}
```

```java
package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.OpeningBalanceSnapshotRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OpeningBalanceSnapshotRowRepository extends JpaRepository<OpeningBalanceSnapshotRow, UUID> {
    List<OpeningBalanceSnapshotRow> findAllByOrderByAccountCodeAsc();
    Optional<OpeningBalanceSnapshotRow> findByAccountCode(String accountCode);
}
```

```java
package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.OpeningBalancePosting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OpeningBalancePostingRepository extends JpaRepository<OpeningBalancePosting, UUID> {
    /** At most one row per tenant; the tenant filter makes findAll().stream().findFirst() equivalent. */
    Optional<OpeningBalancePosting> findFirstByOrderByCreatedAtAsc();
}
```

- [ ] **Step 6: Write the failing opening-balance IT**

`backend/src/test/java/com/datagami/rentaxis/core/service/cutover/OpeningBalanceIT.java`:

```java
package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class OpeningBalanceIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired OpeningBalanceService ob;
    @Autowired AccountService accounts;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired LedgerQueryService ledger;
    @Autowired JournalEntryRepository entries;
    @Autowired JournalLineRepository lines;
    @Autowired TenantDefaultAccountMappingRepository defaults;
    @Autowired PropertyAccountMappingRepository propertyMappings;
    @Autowired PropertyRepository propertyRepo;
    @Autowired LandlordOrgRepository orgRepo;

    static final LocalDate BOOKS_START = LocalDate.of(2026, 10, 1);
    static final LocalDate AS_OF = LocalDate.of(2026, 9, 30);

    UUID tenantId, propertyId;
    Account cashInHand, vatPayable, obDifference, rentReceivable;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("OB-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
        accounts.seedDefaultAccounts();

        Property p = new Property();
        p.setNameEn("Tulip Oasis 7");
        p.setEmirate(Emirate.DUBAI);
        propertyId = propertyRepo.save(p).getId();

        cashInHand = accounts.createLeaf("Cash In Hand", accounts.getAccountByCode("A-02"), null);
        vatPayable = accounts.createLeaf("VAT Payable", accounts.getAccountByCode("B-01"), null);
        obDifference = accounts.createLeaf("Opening Balance Difference", accounts.getAccountByCode("E"), null);
        rentReceivable = accounts.createLeaf("Rent Receivable - Tulip 7", accounts.getAccountByCode("A-02-01"), propertyId);

        mapDefault(AccountRole.OPENING_BALANCE_DIFFERENCE, obDifference);
        mapProperty(AccountRole.RENT_RECEIVABLE, rentReceivable);

        fiscal.setBooksStartDate(BOOKS_START);
        fiscal.lockThrough(AS_OF);
    }

    @AfterEach void clear() { TenantContextHolder.clear(); }

    private void mapDefault(AccountRole role, Account a) {
        TenantDefaultAccountMapping m = new TenantDefaultAccountMapping();
        m.setRole(role); m.setAccount(a); defaults.save(m);
    }

    private void mapProperty(AccountRole role, Account a) {
        PropertyAccountMapping m = new PropertyAccountMapping();
        m.setPropertyId(propertyId); m.setRole(role); m.setAccount(a); propertyMappings.save(m);
    }

    private OpeningBalanceService.SnapshotUploadResult upload(String csv) {
        return ob.uploadSnapshot(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Spec §10.3: accounts mapped to RENT_RECEIVABLE (and the other eight roles the
     * contract import derives) are excluded from manual entry. The grid still SHOWS
     * them so the accountant can see they are accounted for — it just marks them
     * derived, and post() ignores whatever figure a CSV put against them.
     */
    @Test
    void theGridMarksRoleMappedAccountsAsDerived() {
        var grid = ob.grid();
        assertThat(grid.asOf()).isEqualTo(AS_OF);
        assertThat(grid.rows()).filteredOn(r -> r.accountId().equals(rentReceivable.getId()))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.derived()).isTrue();
                    assertThat(r.derivedRole()).isEqualTo(AccountRole.RENT_RECEIVABLE);
                });
        assertThat(grid.rows()).filteredOn(r -> r.accountId().equals(cashInHand.getId()))
                .singleElement()
                .satisfies(r -> assertThat(r.derived()).isFalse());
    }

    @Test
    void uploadingTheTrialBalanceFillsTheGridAndReportsUnmatchedCodes() {
        var result = upload("""
                Account Code,Account Name,Debit,Credit
                %s,Cash In Hand,50000.00,0.00
                %s,VAT Payable,0.00,12000.00
                999999,Some PACT Account We Do Not Have,0.00,777.00
                """.formatted(cashInHand.getCode(), vatPayable.getCode()));

        assertThat(result.stored()).isEqualTo(3);
        assertThat(result.unmatchedCodes()).containsExactly("999999");
        assertThat(result.problems()).isEmpty();

        var grid = ob.grid();
        assertThat(grid.rows()).filteredOn(r -> r.accountId().equals(cashInHand.getId()))
                .singleElement().satisfies(r -> assertThat(r.enteredDebit()).isEqualByComparingTo("50000.00"));
        assertThat(grid.totalDebit()).isEqualByComparingTo("50000.00");
        assertThat(grid.totalCredit()).isEqualByComparingTo("12000.00");
        assertThat(grid.difference()).isEqualByComparingTo("38000.00");
    }

    /**
     * The whole point of the OB journal: whatever the manual figures do not balance by
     * goes to OPENING_BALANCE_DIFFERENCE (equity) so the books open balanced. 50,000 Dr
     * of cash against 12,000 Cr of VAT leaves 38,000 that has to be credited somewhere.
     */
    @Test
    void postingClosesTheDifferenceAgainstEquityAndTheTrialBalanceBalances() {
        upload("""
                Account Code,Account Name,Debit,Credit
                %s,Cash In Hand,50000.00,0.00
                %s,VAT Payable,0.00,12000.00
                """.formatted(cashInHand.getCode(), vatPayable.getCode()));

        JournalEntry e = ob.post();

        assertThat(e.getDocType()).isEqualTo(JournalDocType.OB);
        assertThat(e.getEntryDate()).isEqualTo(AS_OF);
        assertThat(e.getSourceType()).isEqualTo(JournalSourceType.OPENING_BALANCE);
        assertThat(lines.findByEntry_IdOrderByLineNoAsc(e.getId()))
                .filteredOn(l -> l.getAccount().getId().equals(obDifference.getId()))
                .singleElement()
                .satisfies(l -> assertThat(l.getCredit()).isEqualByComparingTo("38000.00"));

        BigDecimal net = ledger.trialBalance(AS_OF, null).stream()
                .map(r -> r.balance()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(net).isEqualByComparingTo("0.00");
    }

    /** The mirror case — more credits than debits — debits the difference account. */
    @Test
    void anExcessOfCreditsDebitsTheDifferenceAccount() {
        upload("""
                Account Code,Account Name,Debit,Credit
                %s,Cash In Hand,5000.00,0.00
                %s,VAT Payable,0.00,12000.00
                """.formatted(cashInHand.getCode(), vatPayable.getCode()));
        JournalEntry e = ob.post();
        assertThat(lines.findByEntry_IdOrderByLineNoAsc(e.getId()))
                .filteredOn(l -> l.getAccount().getId().equals(obDifference.getId()))
                .singleElement()
                .satisfies(l -> assertThat(l.getDebit()).isEqualByComparingTo("7000.00"));
    }

    /** A figure typed against a derived account is ignored, not posted twice. */
    @Test
    void aFigureAgainstADerivedAccountIsNotPosted() {
        upload("""
                Account Code,Account Name,Debit,Credit
                %s,Cash In Hand,50000.00,0.00
                %s,Rent Receivable - Tulip 7,15000.00,0.00
                %s,VAT Payable,0.00,12000.00
                """.formatted(cashInHand.getCode(), rentReceivable.getCode(), vatPayable.getCode()));
        JournalEntry e = ob.post();
        assertThat(lines.findByEntry_IdOrderByLineNoAsc(e.getId()))
                .noneMatch(l -> l.getAccount().getId().equals(rentReceivable.getId()));
    }

    @Test
    void postingTwiceIsBlockedUntilTheFirstOneIsReversed() {
        upload("""
                Account Code,Account Name,Debit,Credit
                %s,Cash In Hand,50000.00,0.00
                %s,VAT Payable,0.00,12000.00
                """.formatted(cashInHand.getCode(), vatPayable.getCode()));
        ob.post();
        assertThatThrownBy(ob::post)
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("already");

        ob.reverse(AS_OF, "corrected trial balance");
        JournalEntry again = ob.post();
        assertThat(again).isNotNull();
        assertThat(ob.grid().posted()).isTrue();
    }

    @Test
    void postingWithNoFiguresIsRejected() {
        assertThatThrownBy(ob::post)
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("at least two");
    }

    @Test
    void postingWithoutABooksStartDateIsRejected() {
        fiscal.setBooksStartDate(null);
        assertThatThrownBy(ob::post)
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("books start date");
    }
}
```

- [ ] **Step 7: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.cutover.OpeningBalanceIT'`
Expected: FAIL — `OpeningBalanceService` does not exist.

- [ ] **Step 8: Write `OpeningBalanceService` (grid, upload, post, reverse)**

`core/service/cutover/OpeningBalanceService.java`:

```java
package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.JournalStatus;
import com.datagami.rentaxis.domain.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class OpeningBalanceService {

    private final AccountRepository accounts;
    private final PropertyAccountMappingRepository propertyMappings;
    private final TenantDefaultAccountMappingRepository defaultMappings;
    private final OpeningBalanceSnapshotRowRepository snapshots;
    private final OpeningBalancePostingRepository postings;
    private final JournalEntryRepository journals;
    private final JournalLineRepository journalLines;
    private final PostingService posting;
    private final TenantFiscalSettingsService fiscal;

    /**
     * Spec §10.3: these roles' accounts are produced by the active-contract import
     * (step 1) and must not be entered by hand (step 2), or the balance would be
     * counted twice. Everything else is manual.
     */
    public static final Set<AccountRole> DERIVED_ROLES = Collections.unmodifiableSet(EnumSet.of(
            AccountRole.RENT_RECEIVABLE, AccountRole.PDC_RECEIVABLE, AccountRole.ADVANCE_RENT,
            AccountRole.SECURITY_DEPOSIT, AccountRole.PARKING_DEPOSIT, AccountRole.RENTAL_INCOME,
            AccountRole.ADMIN_FEE, AccountRole.RENT_PENALTY, AccountRole.CHEQUE_RETURN_PENALTY));

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    public record OpeningBalanceRow(UUID accountId, String code, String name, String accountType, UUID propertyId,
                                    boolean derived, AccountRole derivedRole,
                                    BigDecimal derivedDebit, BigDecimal derivedCredit,
                                    BigDecimal enteredDebit, BigDecimal enteredCredit) {}

    public record OpeningBalanceGrid(LocalDate asOf, boolean posted, UUID journalId, String journalNumber,
                                     List<OpeningBalanceRow> rows, BigDecimal totalDebit,
                                     BigDecimal totalCredit, BigDecimal difference) {}

    public record SnapshotUploadResult(int stored, List<String> unmatchedCodes, List<String> problems) {}

    // ---------- grid ----------

    @Transactional(readOnly = true)
    public OpeningBalanceGrid grid() {
        LocalDate asOf = asOf();
        Map<UUID, AccountRole> derivedByAccount = derivedAccountRoles();
        Map<String, OpeningBalanceSnapshotRow> byCode = new HashMap<>();
        for (OpeningBalanceSnapshotRow r : snapshots.findAllByOrderByAccountCodeAsc()) {
            byCode.put(r.getAccountCode(), r);
        }

        List<OpeningBalanceRow> rows = new ArrayList<>();
        BigDecimal totalDebit = ZERO, totalCredit = ZERO;
        for (Account a : accounts.findAll()) {
            if (a.isGroup() || !a.isActive()) continue;
            AccountRole role = derivedByAccount.get(a.getId());
            OpeningBalanceSnapshotRow s = byCode.get(a.getCode());
            BigDecimal enteredDebit = s == null ? ZERO : s.getDebit();
            BigDecimal enteredCredit = s == null ? ZERO : s.getCredit();
            if (role == null) {
                totalDebit = totalDebit.add(enteredDebit);
                totalCredit = totalCredit.add(enteredCredit);
            }
            rows.add(new OpeningBalanceRow(a.getId(), a.getCode(), a.getName(),
                    a.getAccountType() == null ? null : a.getAccountType().name(), a.getPropertyId(),
                    role != null, role, ZERO, ZERO, enteredDebit, enteredCredit));
        }
        rows.sort(Comparator.comparing(OpeningBalanceRow::code, Comparator.nullsLast(String::compareTo)));

        Optional<OpeningBalancePosting> p = livePosting();
        JournalEntry e = p.map(x -> journals.findById(x.getJournalId()).orElse(null)).orElse(null);
        return new OpeningBalanceGrid(asOf, e != null, e == null ? null : e.getId(),
                e == null ? null : e.getEntryNumber(), rows, totalDebit, totalCredit,
                totalDebit.subtract(totalCredit));
    }

    // ---------- snapshot ----------

    /** Replaces the stored snapshot wholesale: a re-upload is a correction, not an addition. */
    @Transactional
    public SnapshotUploadResult uploadSnapshot(InputStream csv) {
        TrialBalanceCsvParser.CsvParseResult parsed = TrialBalanceCsvParser.parse(csv);
        Set<String> knownCodes = new HashSet<>();
        for (Account a : accounts.findAll()) knownCodes.add(a.getCode());

        snapshots.deleteAll(snapshots.findAllByOrderByAccountCodeAsc());

        List<String> unmatched = new ArrayList<>();
        int stored = 0;
        for (TrialBalanceCsvParser.CsvRow r : parsed.rows()) {
            if (!knownCodes.contains(r.code())) unmatched.add(r.code());
            OpeningBalanceSnapshotRow row = new OpeningBalanceSnapshotRow();
            row.setAccountCode(r.code());
            row.setAccountName(r.name());
            row.setDebit(r.debit());
            row.setCredit(r.credit());
            row.setUploadedAt(Instant.now());
            snapshots.save(row);
            stored++;
        }
        log.info("Opening-balance snapshot stored {} rows, {} unmatched codes", stored, unmatched.size());
        return new SnapshotUploadResult(stored, unmatched, parsed.problems());
    }

    /** Hand-edit one row of the grid. Writes into the same snapshot the CSV fills. */
    @Transactional
    public void setRow(UUID accountId, BigDecimal debit, BigDecimal credit) {
        Account a = accounts.findById(accountId).orElseThrow(() -> new NotFoundException("Account not found"));
        if (derivedAccountRoles().containsKey(accountId)) {
            throw new BusinessRuleViolationException(
                    a.getCode() + " " + a.getName() + " is derived from the contract import and cannot be entered by hand");
        }
        BigDecimal d = debit == null ? ZERO : debit.setScale(2, RoundingMode.HALF_UP);
        BigDecimal c = credit == null ? ZERO : credit.setScale(2, RoundingMode.HALF_UP);
        if (d.signum() != 0 && c.signum() != 0) {
            BigDecimal net = d.subtract(c);
            d = net.signum() > 0 ? net : ZERO;
            c = net.signum() < 0 ? net.negate() : ZERO;
        }
        OpeningBalanceSnapshotRow row = snapshots.findByAccountCode(a.getCode())
                .orElseGet(OpeningBalanceSnapshotRow::new);
        row.setAccountCode(a.getCode());
        row.setAccountName(a.getName());
        row.setDebit(d);
        row.setCredit(c);
        row.setUploadedAt(Instant.now());
        snapshots.save(row);
    }

    // ---------- post / reverse ----------

    @Transactional
    public JournalEntry post() {
        LocalDate asOf = asOf();
        if (livePosting().isPresent()) {
            throw new BusinessRuleViolationException(
                    "Opening balances have already been posted. Reverse the existing opening-balance journal first.");
        }

        Map<UUID, AccountRole> derived = derivedAccountRoles();
        Map<String, Account> byCode = new HashMap<>();
        for (Account a : accounts.findAll()) byCode.put(a.getCode(), a);

        List<PostingRequest.Line> lines = new ArrayList<>();
        BigDecimal totalDebit = ZERO, totalCredit = ZERO;
        for (OpeningBalanceSnapshotRow r : snapshots.findAllByOrderByAccountCodeAsc()) {
            Account a = byCode.get(r.getAccountCode());
            if (a == null || a.isGroup() || derived.containsKey(a.getId())) continue;
            if (r.getDebit().signum() > 0) {
                lines.add(PostingRequest.dr(a.getId(), r.getDebit())
                        .withNarration("Opening balance as at " + asOf));
                totalDebit = totalDebit.add(r.getDebit());
            } else if (r.getCredit().signum() > 0) {
                lines.add(PostingRequest.cr(a.getId(), r.getCredit())
                        .withNarration("Opening balance as at " + asOf));
                totalCredit = totalCredit.add(r.getCredit());
            }
        }

        BigDecimal difference = totalDebit.subtract(totalCredit);
        if (difference.signum() > 0) {
            lines.add(PostingRequest.cr(AccountRole.OPENING_BALANCE_DIFFERENCE, difference)
                    .withNarration("Opening balance difference"));
        } else if (difference.signum() < 0) {
            lines.add(PostingRequest.dr(AccountRole.OPENING_BALANCE_DIFFERENCE, difference.negate())
                    .withNarration("Opening balance difference"));
        }
        if (lines.size() < 2) {
            throw new BusinessRuleViolationException(
                    "Opening balances need at least two accounts with a figure before they can be posted");
        }

        OpeningBalancePosting marker = postings.findFirstByOrderByCreatedAtAsc()
                .orElseGet(OpeningBalancePosting::new);
        marker.setAsOf(asOf);
        marker = postings.save(marker);

        // docType OB is exempt from the period lock in PostingService — the opening
        // journal is dated the day BEFORE the books open, which is by definition locked.
        JournalEntry entry = posting.post(new PostingRequest(
                JournalDocType.OB, asOf, "Opening balances as at " + asOf, PostingRequest.Dimensions.none(),
                JournalSourceType.OPENING_BALANCE, marker.getId(), null, lines));

        marker.setJournalId(entry.getId());
        marker.setPostedAt(Instant.now());
        marker.setPostedBy(entry.getPostedBy());
        postings.save(marker);
        return entry;
    }

    @Transactional
    public JournalEntry reverse(LocalDate date, String reason) {
        OpeningBalancePosting marker = livePosting().orElseThrow(
                () -> new BusinessRuleViolationException("There is no posted opening-balance journal to reverse"));
        JournalEntry mirror = posting.reverse(marker.getJournalId(),
                date == null ? asOf() : date, reason == null ? "Opening balances reversed" : reason);
        marker.setJournalId(null);
        marker.setPostedAt(null);
        postings.save(marker);
        return mirror;
    }

    // ---------- internals, shared with the reconciliation in Task 9 ----------

    LocalDate asOf() {
        LocalDate booksStart = fiscal.get().getBooksStartDate();
        if (booksStart == null) {
            throw new BusinessRuleViolationException(
                    "Set the books start date in Settings → Fiscal before working on opening balances");
        }
        return booksStart.minusDays(1);
    }

    /** The POSTED opening-balance marker, if the tenant has one that has not been reversed. */
    Optional<OpeningBalancePosting> livePosting() {
        return postings.findFirstByOrderByCreatedAtAsc()
                .filter(p -> p.getJournalId() != null)
                .filter(p -> journals.findById(p.getJournalId())
                        .map(e -> e.getStatus() == JournalStatus.POSTED)
                        .orElse(false));
    }

    /** accountId → the derived role it is mapped to, across property mappings and tenant defaults. */
    Map<UUID, AccountRole> derivedAccountRoles() {
        Map<UUID, AccountRole> out = new HashMap<>();
        for (PropertyAccountMapping m : propertyMappings.findAll()) {
            if (DERIVED_ROLES.contains(m.getRole())) out.putIfAbsent(m.getAccount().getId(), m.getRole());
        }
        for (TenantDefaultAccountMapping m : defaultMappings.findAll()) {
            if (DERIVED_ROLES.contains(m.getRole())) out.putIfAbsent(m.getAccount().getId(), m.getRole());
        }
        return out;
    }

    List<JournalLine> openingJournalLines() {
        return livePosting()
                .map(p -> journalLines.findByEntry_IdOrderByLineNoAsc(p.getJournalId()))
                .orElseGet(List::of);
    }
}
```

- [ ] **Step 9: Run the IT to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.cutover.OpeningBalanceIT'`
Expected: PASS, 8 tests.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis backend/src/test/java/com/datagami/rentaxis/core/service/cutover
git commit -m "feat(finance): opening-balance grid, PACT trial-balance CSV and the OB journal

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: Reconciliation and the opening-balance API

**Files:**
- Modify: `core/service/cutover/OpeningBalanceService.java` (add `reconcile`)
- Create: `api/dto/cutover/OpeningBalanceRowDTO.java`, `OpeningBalanceGridDTO.java`, `ReconciliationRowDTO.java`, `SnapshotUploadResultDTO.java`, `ManualOpeningBalanceDTO.java`
- Create: `api/OpeningBalanceController.java`
- Test: `core/service/cutover/ReconciliationIT.java`

**Interfaces:**
- Consumes: Task 8's `OpeningBalanceService.{asOf,livePosting,derivedAccountRoles,openingJournalLines}`; `LedgerQueryService.trialBalance(LocalDate asOf, UUID propertyId)`.
- Produces:
  ```java
  record ReconciliationRow(UUID accountId, String code, String name, boolean derived,
                           BigDecimal derivedBalance, BigDecimal pactBalance, BigDecimal difference) {}
  List<ReconciliationRow> OpeningBalanceService.reconcile();
  ```
  Balances are signed, debit-positive, matching `TrialBalanceRowDTO.balance`. `difference = derivedBalance − pactBalance`.
  Endpoints (`hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT')`):
  - `GET    /api/v1/finance/opening-balances` → `OpeningBalanceGridDTO`
  - `POST   /api/v1/finance/opening-balances/snapshot` multipart `file` → `SnapshotUploadResultDTO`
  - `PUT    /api/v1/finance/opening-balances/{accountId}` body `{debit, credit}` → 204
  - `POST   /api/v1/finance/opening-balances/post` → `JournalEntry` summary (`{id, entryNumber, entryDate}`)
  - `POST   /api/v1/finance/opening-balances/reverse` body `{date, reason}` → same shape
  - `GET    /api/v1/finance/reconciliation` → `List<ReconciliationRowDTO>`

- [ ] **Step 1: Write the failing reconciliation IT**

`backend/src/test/java/com/datagami/rentaxis/core/service/cutover/ReconciliationIT.java`:

```java
package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class ReconciliationIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired OpeningBalanceService ob;
    @Autowired ImportBatchService batches;
    @Autowired PostingService posting;
    @Autowired AccountService accounts;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired PropertyAccountMappingRepository propertyMappings;
    @Autowired TenantDefaultAccountMappingRepository defaults;
    @Autowired PropertyRepository propertyRepo;
    @Autowired LandlordOrgRepository orgRepo;

    static final LocalDate BOOKS_START = LocalDate.of(2026, 10, 1);
    static final LocalDate AS_OF = LocalDate.of(2026, 9, 30);

    UUID tenantId, propertyId;
    Account rentReceivable, advanceRent, cashInHand, obDifference;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("Recon-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
        accounts.seedDefaultAccounts();

        Property p = new Property();
        p.setNameEn("Tulip Oasis 7");
        p.setEmirate(Emirate.DUBAI);
        propertyId = propertyRepo.save(p).getId();

        rentReceivable = accounts.createLeaf("Rent Receivable - Tulip 7", accounts.getAccountByCode("A-02-01"), propertyId);
        advanceRent = accounts.createLeaf("Advance Rent - Tulip 7", accounts.getAccountByCode("B-01-01"), propertyId);
        cashInHand = accounts.createLeaf("Cash In Hand", accounts.getAccountByCode("A-02"), null);
        obDifference = accounts.createLeaf("Opening Balance Difference", accounts.getAccountByCode("E"), null);

        PropertyAccountMapping m1 = new PropertyAccountMapping();
        m1.setPropertyId(propertyId); m1.setRole(AccountRole.RENT_RECEIVABLE); m1.setAccount(rentReceivable);
        propertyMappings.save(m1);
        PropertyAccountMapping m2 = new PropertyAccountMapping();
        m2.setPropertyId(propertyId); m2.setRole(AccountRole.ADVANCE_RENT); m2.setAccount(advanceRent);
        propertyMappings.save(m2);
        TenantDefaultAccountMapping d = new TenantDefaultAccountMapping();
        d.setRole(AccountRole.OPENING_BALANCE_DIFFERENCE); d.setAccount(obDifference);
        defaults.save(d);

        fiscal.setBooksStartDate(BOOKS_START);
        fiscal.lockThrough(AS_OF);
    }

    @AfterEach void clear() { TenantContextHolder.clear(); }

    /** Stands in for the contract import: an IMPORT-sourced journal dated before the cut-over. */
    private void importedContract(String amount) {
        ImportBatch b = batches.create(null, "cut-over");
        posting.post(new PostingRequest(
                JournalDocType.TCO, LocalDate.of(2026, 9, 11), "Imported contract",
                PostingRequest.Dimensions.ofProperty(propertyId), JournalSourceType.IMPORT, UUID.randomUUID(),
                b.getId(),
                List.of(PostingRequest.dr(rentReceivable.getId(), new BigDecimal(amount)),
                        PostingRequest.cr(advanceRent.getId(), new BigDecimal(amount)))));
        batches.markPosted(b.getId(), 1);
    }

    private void upload(String csv) {
        ob.uploadSnapshot(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Spec §10.3: "per account — derived balance, PACT figure, difference." The derived
     * balance comes from step 1 (the imported contracts); PACT's figure comes from the
     * uploaded trial balance; a clean cut-over has zero in the difference column for
     * every derived account.
     */
    @Test
    void aCleanCutOverReconcilesToZeroOnTheDerivedAccounts() {
        importedContract("61000.00");
        upload("""
                Account Code,Account Name,Debit,Credit
                %s,Rent Receivable - Tulip 7,61000.00,0.00
                %s,Advance Rent - Tulip 7,0.00,61000.00
                """.formatted(rentReceivable.getCode(), advanceRent.getCode()));

        List<OpeningBalanceService.ReconciliationRow> rows = ob.reconcile();
        assertThat(rows).filteredOn(r -> r.accountId().equals(rentReceivable.getId()))
                .singleElement().satisfies(r -> {
                    assertThat(r.derived()).isTrue();
                    assertThat(r.derivedBalance()).isEqualByComparingTo("61000.00");
                    assertThat(r.pactBalance()).isEqualByComparingTo("61000.00");
                    assertThat(r.difference()).isEqualByComparingTo("0.00");
                });
        assertThat(rows).filteredOn(r -> r.accountId().equals(advanceRent.getId()))
                .singleElement().satisfies(r -> assertThat(r.difference()).isEqualByComparingTo("0.00"));
    }

    /** A contract left out of the import shows up as exactly the missing amount. */
    @Test
    void aMissingContractShowsAsTheDifference() {
        importedContract("45000.00");
        upload("""
                Account Code,Account Name,Debit,Credit
                %s,Rent Receivable - Tulip 7,61000.00,0.00
                """.formatted(rentReceivable.getCode()));
        assertThat(ob.reconcile()).filteredOn(r -> r.accountId().equals(rentReceivable.getId()))
                .singleElement()
                .satisfies(r -> assertThat(r.difference()).isEqualByComparingTo("-16000.00"));
    }

    /**
     * The OB journal must not contaminate the derived column. It posts ON the
     * reconciliation date, so a naive trial balance would include it and every manual
     * account would read back its own figure as "derived".
     */
    @Test
    void postingTheObJournalDoesNotChangeTheDerivedColumn() {
        importedContract("61000.00");
        upload("""
                Account Code,Account Name,Debit,Credit
                %s,Rent Receivable - Tulip 7,61000.00,0.00
                %s,Advance Rent - Tulip 7,0.00,61000.00
                %s,Cash In Hand,50000.00,0.00
                """.formatted(rentReceivable.getCode(), advanceRent.getCode(), cashInHand.getCode()));

        List<OpeningBalanceService.ReconciliationRow> before = ob.reconcile();
        ob.post();
        List<OpeningBalanceService.ReconciliationRow> after = ob.reconcile();

        assertThat(after).filteredOn(r -> r.accountId().equals(cashInHand.getId()))
                .singleElement().satisfies(r -> {
                    assertThat(r.derivedBalance()).isEqualByComparingTo("0.00");
                    assertThat(r.pactBalance()).isEqualByComparingTo("50000.00");
                    assertThat(r.difference()).isEqualByComparingTo("-50000.00");
                });
        assertThat(after).filteredOn(r -> r.accountId().equals(rentReceivable.getId()))
                .singleElement().satisfies(r -> assertThat(r.difference()).isEqualByComparingTo("0.00"));
        assertThat(after).hasSameSizeAs(before);
    }

    /** A PACT code with no account of ours still gets a row, so nothing is silently lost. */
    @Test
    void anUnmatchedPactCodeStillAppearsWithANullAccountId() {
        upload("""
                Account Code,Account Name,Debit,Credit
                999999,Some PACT Account,0.00,777.00
                """);
        assertThat(ob.reconcile()).filteredOn(r -> "999999".equals(r.code()))
                .singleElement().satisfies(r -> {
                    assertThat(r.accountId()).isNull();
                    assertThat(r.pactBalance()).isEqualByComparingTo("-777.00");
                    assertThat(r.derivedBalance()).isEqualByComparingTo("0.00");
                });
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.cutover.ReconciliationIT'`
Expected: FAIL — `OpeningBalanceService.reconcile` does not exist.

- [ ] **Step 3: Add `reconcile` to `OpeningBalanceService`**

Add the field `private final LedgerQueryService ledger;` and the record plus method:

```java
    public record ReconciliationRow(UUID accountId, String code, String name, boolean derived,
                                    BigDecimal derivedBalance, BigDecimal pactBalance, BigDecimal difference) {}

    /**
     * Spec §10.3: per account — the balance our books derive from the contract import,
     * PACT's figure from the uploaded trial balance, and the gap.
     *
     * <p>The opening-balance journal is dated the same day as this report, so a raw
     * trial balance would count it in the "derived" column and every manually entered
     * account would trivially reconcile against itself. Its own lines are therefore
     * subtracted out. Once it has been reversed the original and its mirror already net
     * to zero, so nothing is subtracted — which is why only a LIVE posting is considered.
     */
    @Transactional(readOnly = true)
    public List<ReconciliationRow> reconcile() {
        LocalDate asOf = asOf();
        Map<UUID, AccountRole> derived = derivedAccountRoles();

        Map<UUID, BigDecimal> derivedBalances = new HashMap<>();
        Map<UUID, String[]> identity = new HashMap<>();     // accountId -> {code, name}
        for (var row : ledger.trialBalance(asOf, null)) {
            derivedBalances.merge(row.accountId(), row.balance(), BigDecimal::add);
            identity.put(row.accountId(), new String[]{row.code(), row.name()});
        }
        for (JournalLine l : openingJournalLines()) {
            derivedBalances.merge(l.getAccount().getId(),
                    l.getDebit().subtract(l.getCredit()).negate(), BigDecimal::add);
        }

        Map<String, Account> byCode = new HashMap<>();
        for (Account a : accounts.findAll()) {
            byCode.put(a.getCode(), a);
            identity.putIfAbsent(a.getId(), new String[]{a.getCode(), a.getName()});
        }

        List<ReconciliationRow> rows = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (OpeningBalanceSnapshotRow s : snapshots.findAllByOrderByAccountCodeAsc()) {
            Account a = byCode.get(s.getAccountCode());
            BigDecimal pact = s.getDebit().subtract(s.getCredit());
            if (a == null) {
                rows.add(new ReconciliationRow(null, s.getAccountCode(), s.getAccountName(), false,
                        ZERO, pact, ZERO.subtract(pact)));
                continue;
            }
            seen.add(a.getId());
            BigDecimal d = derivedBalances.getOrDefault(a.getId(), ZERO);
            rows.add(new ReconciliationRow(a.getId(), a.getCode(), a.getName(),
                    derived.containsKey(a.getId()), d, pact, d.subtract(pact)));
        }
        // Accounts we have a derived balance for that PACT did not export — the other
        // half of the same question, and the one that catches an over-import.
        for (Map.Entry<UUID, BigDecimal> e : derivedBalances.entrySet()) {
            if (seen.contains(e.getKey()) || e.getValue().signum() == 0) continue;
            String[] id = identity.getOrDefault(e.getKey(), new String[]{null, null});
            rows.add(new ReconciliationRow(e.getKey(), id[0], id[1],
                    derived.containsKey(e.getKey()), e.getValue(), ZERO, e.getValue()));
        }
        rows.sort(Comparator.comparing(ReconciliationRow::code, Comparator.nullsLast(String::compareTo)));
        return rows;
    }
```

Add the import `com.datagami.rentaxis.core.service.ledger.LedgerQueryService`.

> **`derivedBalances.merge(..., negate())`:** `trialBalance` returns a debit-positive signed balance, so subtracting the OB journal's own contribution means adding the negation of `debit − credit`. Written out this way rather than as a `subtract` so the sign convention is visible at the call site.

- [ ] **Step 4: Run the IT to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.cutover.ReconciliationIT'`
Expected: PASS, 4 tests.

- [ ] **Step 5: Write the DTOs**

`api/dto/cutover/OpeningBalanceRowDTO.java`:

```java
package com.datagami.rentaxis.api.dto.cutover;

import com.datagami.rentaxis.core.service.cutover.OpeningBalanceService;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;

import java.math.BigDecimal;
import java.util.UUID;

public record OpeningBalanceRowDTO(UUID accountId, String code, String name, String accountType, UUID propertyId,
                                   boolean derived, AccountRole derivedRole,
                                   BigDecimal enteredDebit, BigDecimal enteredCredit) {

    public static OpeningBalanceRowDTO of(OpeningBalanceService.OpeningBalanceRow r) {
        return new OpeningBalanceRowDTO(r.accountId(), r.code(), r.name(), r.accountType(), r.propertyId(),
                r.derived(), r.derivedRole(), r.enteredDebit(), r.enteredCredit());
    }
}
```

`api/dto/cutover/OpeningBalanceGridDTO.java`:

```java
package com.datagami.rentaxis.api.dto.cutover;

import com.datagami.rentaxis.core.service.cutover.OpeningBalanceService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record OpeningBalanceGridDTO(LocalDate asOf, boolean posted, UUID journalId, String journalNumber,
                                    List<OpeningBalanceRowDTO> rows, BigDecimal totalDebit,
                                    BigDecimal totalCredit, BigDecimal difference) {

    public static OpeningBalanceGridDTO of(OpeningBalanceService.OpeningBalanceGrid g) {
        return new OpeningBalanceGridDTO(g.asOf(), g.posted(), g.journalId(), g.journalNumber(),
                g.rows().stream().map(OpeningBalanceRowDTO::of).toList(),
                g.totalDebit(), g.totalCredit(), g.difference());
    }
}
```

`api/dto/cutover/ReconciliationRowDTO.java`:

```java
package com.datagami.rentaxis.api.dto.cutover;

import com.datagami.rentaxis.core.service.cutover.OpeningBalanceService;

import java.math.BigDecimal;
import java.util.UUID;

public record ReconciliationRowDTO(UUID accountId, String code, String name, boolean derived,
                                   BigDecimal derivedBalance, BigDecimal pactBalance, BigDecimal difference) {

    public static ReconciliationRowDTO of(OpeningBalanceService.ReconciliationRow r) {
        return new ReconciliationRowDTO(r.accountId(), r.code(), r.name(), r.derived(),
                r.derivedBalance(), r.pactBalance(), r.difference());
    }
}
```

`api/dto/cutover/SnapshotUploadResultDTO.java`:

```java
package com.datagami.rentaxis.api.dto.cutover;

import com.datagami.rentaxis.core.service.cutover.OpeningBalanceService;

import java.util.List;

public record SnapshotUploadResultDTO(int stored, List<String> unmatchedCodes, List<String> problems) {

    public static SnapshotUploadResultDTO of(OpeningBalanceService.SnapshotUploadResult r) {
        return new SnapshotUploadResultDTO(r.stored(), r.unmatchedCodes(), r.problems());
    }
}
```

`api/dto/cutover/ManualOpeningBalanceDTO.java`:

```java
package com.datagami.rentaxis.api.dto.cutover;

import java.math.BigDecimal;

public record ManualOpeningBalanceDTO(BigDecimal debit, BigDecimal credit) {}
```

- [ ] **Step 6: Write the controller**

`api/OpeningBalanceController.java`:

```java
package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.cutover.*;
import com.datagami.rentaxis.core.service.cutover.OpeningBalanceService;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/finance")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT')")
public class OpeningBalanceController {

    private final OpeningBalanceService service;

    public record ReverseObDTO(@NotNull LocalDate date, String reason) {}

    /** Just enough of the journal for a toast and a link to the GL. */
    public record PostedJournalDTO(UUID id, String entryNumber, LocalDate entryDate) {
        static PostedJournalDTO of(JournalEntry e) {
            return new PostedJournalDTO(e.getId(), e.getEntryNumber(), e.getEntryDate());
        }
    }

    @GetMapping("/opening-balances")
    public ResponseEntity<OpeningBalanceGridDTO> grid() {
        return ResponseEntity.ok(OpeningBalanceGridDTO.of(service.grid()));
    }

    @PostMapping(path = "/opening-balances/snapshot", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SnapshotUploadResultDTO> uploadSnapshot(@RequestParam("file") MultipartFile file)
            throws IOException {
        return ResponseEntity.ok(SnapshotUploadResultDTO.of(service.uploadSnapshot(file.getInputStream())));
    }

    @PutMapping("/opening-balances/{accountId}")
    public ResponseEntity<Void> setRow(@PathVariable UUID accountId, @RequestBody ManualOpeningBalanceDTO body) {
        service.setRow(accountId, body.debit(), body.credit());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/opening-balances/post")
    public ResponseEntity<PostedJournalDTO> post() {
        return ResponseEntity.ok(PostedJournalDTO.of(service.post()));
    }

    @PostMapping("/opening-balances/reverse")
    public ResponseEntity<PostedJournalDTO> reverse(@RequestBody ReverseObDTO body) {
        return ResponseEntity.ok(PostedJournalDTO.of(service.reverse(body.date(), body.reason())));
    }

    @GetMapping("/reconciliation")
    public ResponseEntity<List<ReconciliationRowDTO>> reconcile() {
        return ResponseEntity.ok(service.reconcile().stream().map(ReconciliationRowDTO::of).toList());
    }
}
```

- [ ] **Step 7: Run the whole cut-over package**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.cutover.*'`
Expected: PASS — `TrialBalanceCsvParserTest` 6, `ImportBatchReverseIT` 5, `OpeningBalanceIT` 8, `ReconciliationIT` 4.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis backend/src/test/java/com/datagami/rentaxis/core/service/cutover
git commit -m "feat(finance): reconciliation report and the opening-balance API

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: Active-contract import — extend the portfolio importer with the v2 sheets

> **BLOCKED until Plans 2 and 3 are merged.** See **Ordering** in the Global Constraints.

**Files:**
- Modify: `core/service/PortfolioImportService.java`, `core/service/PortfolioTemplateService.java`
- Modify: `domain/entity/ImportJob.java`, `api/dto/PortfolioImportResultDTO.java`, `api/dto/PortfolioImportJobDetailsDTO.java`, `api/PortfolioImportController.java`
- Create: `core/service/cutover/ContractImportValidator.java`, `core/service/cutover/ContractImportPersistService.java`
- Test: `core/service/cutover/ContractImportValidatorTest.java`, `core/service/cutover/ContractImportIT.java`

**Interfaces:**
- Consumes (existing, do not rewrite): the portfolio import pipeline exactly as it stands today —
  - `PortfolioImportController.importPortfolio(MultipartFile file, @RequestHeader("X-User-Id") UUID userId)` accepts `.xlsx` up to 5 MB, creates an `ImportJob` with `status = "VALIDATING"`, returns `{jobId}`, and the client polls `GET /api/v1/import/portfolio/{jobId}/status`.
  - `PortfolioImportService.processImportAsync(byte[] fileBytes, ImportJob job, UUID tenantId)` — `@Async("importExecutor")`, sets `TenantContextHolder`, opens an `XSSFWorkbook`, runs `validateAll` (status `VALIDATING` → `VALIDATION_FAILED` on hard errors), then `persistService.persistWorkbook(workbook, job, warnings)` under `status = "PERSISTING"`, then `COMPLETED`; on any exception it resets every counter to 0, writes the message into `job.errors` and sets `FAILED`. Always `TenantContextHolder.clear()` in `finally`.
  - `PortfolioImportService.HeaderIndex` — header-name → column index, case-insensitive, `col(name)` returns `-1` when absent, so appending columns never breaks an older workbook. Read cells with the private `cell(row, hi, header)` / `getCellString(row, col)` helpers (the latter already converts date-formatted numeric cells to `YYYY-MM-DD`).
  - `ImportErrorDTO(String sheet, int row, String field, String message)`; row numbers are 1-based for display (`rowNum = i + 1`).
  - `PortfolioImportJobDetailsDTO` — the JSON **object** form of `import_jobs.errors` carrying `errors`, `warnings` and counters; the controller's `mapToResult` discriminates object-vs-array on the first non-whitespace character. **Any new counter must go on this wrapper, never as a new array element.**
  - Existing v1 sheets and columns, unchanged:
    - `Properties`: `PropertyName, PropertyNameAr, Emirate, Address, Type, MakaniNumber` (read positionally, columns 0–5)
    - `Units`: `PropertyName, BuildingName, UnitNumber, UnitType, SizeSqft, ExpectedRent` (positional 0–5)
    - `Renters`: `Name, NameAr, Email, Phone` (positional 0–3)
    - `Leases`: by header — `PropertyName, BuildingName, UnitNumber, RenterEmail, StartDate, EndDate, RentAmount, DepositAmount, PaymentTerms, PaymentMethod, EjariNumber, MonthlyRent, AdminFee, ParkingRemoteFee, RentVatApplicable, …`
    - `Cheques` (v1): by header — `PropertyName, UnitNumber, RenterEmail, InstallmentNo, DueDate, ChequeOrPaymentDate, UniqueId, Bank, Amount, Method`
- Consumes (from Plans 2/3): `Lease`, `LeaseLine`, `ChargeType` + its repository's by-code finder, `Cheque`, `LeaseService.syncDerivedTotals` — see the Plans 2/3 interface block at the top.
- **Plan 2 already touches this file.** Its Task list includes `PortfolioImportPersistService.java (minimal: lines + cheque rows, no schedules)` — the v1 path is rewired onto `lease_lines` and `cheques` there. Read that change before starting: this task adds the v2 sheets *beside* it, and must not undo it.
- Consumes (from this plan): `ImportBatchService.create/linkLease`, `AccountRepository`, `PropertyAccountMappingRepository`.
- Produces:
  ```java
  // core/service/cutover/ContractImportValidator.java
  static boolean isV2Workbook(Workbook wb);                       // true when a "Contracts" sheet is present
  PortfolioImportService.ValidationOutcome validate(Workbook wb); // same record the v1 path returns

  // core/service/cutover/ContractImportPersistService.java
  record ContractImportSummary(UUID batchId, int propertiesCreated, int unitsCreated, int rentersCreated,
                               int contractsCreated, int chequesCreated, int mappingsCreated,
                               List<ImportErrorDTO> warnings) {}
  @Transactional ContractImportSummary persist(Workbook wb, ImportJob job);
  ```

**v2 sheet contract (spec §10.3 step 1) — this is what `PortfolioTemplateService` must emit and what the validator must accept:**

| Sheet | Columns |
|---|---|
| `Properties` | v1's six, then six **optional** account-name columns: `RentalIncomeAccount`, `RentalReceivableAccount`, `AdvanceRentAccount`, `BankAccount`, `PdcReceivableAccount`, `SecurityDepositAccount` |
| `Units` | unchanged from v1 |
| `Renters` | unchanged from v1 |
| `Contracts` | `ContractNumber, TrackingNumber, PropertyName, BuildingName, UnitNumber, RenterEmail, ContractDate, StartDate, EndDate, GracePeriodDays, LineNo, ChargeTypeCode, CreditAccount, GrossAmount, DiscountAmount, VatApplicable, Narration` — **one row per contract line**; rows sharing a `ContractNumber` are one lease, and the header fields are read from that contract's first row |
| `Cheques` (v2) | `ContractNumber, SeqNo, PostingDate, ChequeNumber, ChequeDate, PayeeBank, DebitAccount, Amount, Narration, Mode, Status, ClearedDate, BouncedDate` |

The client's property-mapping export (`property maping ledgers.xls`, an HTML table) has the headers `Property Code | Name | Rental Income A/c | Rental Receivable A/c | Advance Rent A/c | Bank A/c | LandLord` and rows like:

```
Tulip 7 | Tulip Oasis 7 | Rental Income Tulip 7 | Rent Receivable - Tulip 7 | Advance Rent - Tulip 7 | Emirates Islamic - Tulip 7 | Landlord
Olivier | L'Olivier     | Rental Income L'Olivier | Rent Receivable - L'Olivier | Advance Rent - L'Olivier | Emirates Islamic - L'Olivier | Landlord
```

Four of the six account columns come straight from that sheet (`Rental Income A/c` → `RentalIncomeAccount`, `Rental Receivable A/c` → `RentalReceivableAccount`, `Advance Rent A/c` → `AdvanceRentAccount`, `Bank A/c` → `BankAccount`); `PdcReceivableAccount` and `SecurityDepositAccount` are the two further template roles a lease needs before it can post (spec §5.4 posting guard) and are added so the accountant can supply them in the same pass. `LandLord` is ignored — landlord/owner commission is out of scope (spec §14). Names are matched **exactly** against `accounts.name` (trimmed, case-insensitive) within the tenant; an unmatched name is a **warning** naming the column and the value, never a guess (spec §10.3: "unmatched names reported, not guessed").

- [ ] **Step 1: Write the failing validator unit test**

`backend/src/test/java/com/datagami/rentaxis/core/service/cutover/ContractImportValidatorTest.java`:

```java
package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.api.dto.ImportErrorDTO;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContractImportValidatorTest {

    private static void row(Sheet s, int r, String... values) {
        Row row = s.createRow(r);
        for (int c = 0; c < values.length; c++) row.createCell(c).setCellValue(values[c]);
    }

    private static Workbook workbook(boolean withContracts) {
        Workbook wb = new XSSFWorkbook();
        Sheet props = wb.createSheet("Properties");
        row(props, 0, "PropertyName", "PropertyNameAr", "Emirate", "Address", "Type", "MakaniNumber",
                "RentalIncomeAccount", "RentalReceivableAccount", "AdvanceRentAccount", "BankAccount",
                "PdcReceivableAccount", "SecurityDepositAccount");
        row(props, 1, "Tulip Oasis 7", "", "DUBAI", "", "RESIDENTIAL", "",
                "Rental Income Tulip 7", "Rent Receivable - Tulip 7", "Advance Rent - Tulip 7",
                "Emirates Islamic - Tulip 7", "PDC Receivable Tulip 7", "Security Deposit Tulip 7");

        Sheet units = wb.createSheet("Units");
        row(units, 0, "PropertyName", "BuildingName", "UnitNumber", "UnitType", "SizeSqft", "ExpectedRent");
        row(units, 1, "Tulip Oasis 7", "", "101", "BHK1", "", "");

        Sheet renters = wb.createSheet("Renters");
        row(renters, 0, "Name", "NameAr", "Email", "Phone");
        row(renters, 1, "Islam Mamanov", "", "islam@example.com", "");

        if (!withContracts) return wb;

        Sheet contracts = wb.createSheet("Contracts");
        row(contracts, 0, "ContractNumber", "TrackingNumber", "PropertyName", "BuildingName", "UnitNumber",
                "RenterEmail", "ContractDate", "StartDate", "EndDate", "GracePeriodDays",
                "LineNo", "ChargeTypeCode", "CreditAccount", "GrossAmount", "DiscountAmount",
                "VatApplicable", "Narration");
        row(contracts, 1, "TLP7/681", "TRK-1", "Tulip Oasis 7", "", "101", "islam@example.com",
                "2026-09-11", "2026-09-24", "2027-09-23", "5",
                "1", "RENT", "Rental Income Tulip 7", "51000.00", "0", "false", "Annual rent");
        row(contracts, 2, "TLP7/681", "", "", "", "", "", "", "", "", "",
                "2", "SECURITY_DEPOSIT", "", "5000.00", "0", "false", "Security deposit");

        Sheet cheques = wb.createSheet("Cheques");
        row(cheques, 0, "ContractNumber", "SeqNo", "PostingDate", "ChequeNumber", "ChequeDate",
                "PayeeBank", "DebitAccount", "Amount", "Narration", "Mode", "Status",
                "ClearedDate", "BouncedDate");
        row(cheques, 1, "TLP7/681", "1", "2026-09-11", "000101", "2026-09-24", "ENBD", "",
                "31000.00", "Rent - 1st Installment", "PDC", "CLEARED", "2026-09-25", "");
        row(cheques, 2, "TLP7/681", "2", "2026-09-11", "000102", "2027-03-24", "ENBD", "",
                "25000.00", "Rent - 2nd Installment", "PDC", "REGISTERED", "", "");
        return wb;
    }

    @Test
    void aWorkbookWithAContractsSheetIsAV2Workbook() throws Exception {
        try (Workbook v2 = workbook(true); Workbook v1 = workbook(false)) {
            assertThat(ContractImportValidator.isV2Workbook(v2)).isTrue();
            assertThat(ContractImportValidator.isV2Workbook(v1)).isFalse();
        }
    }

    @Test
    void aWellFormedWorkbookHasNoErrors() throws Exception {
        try (Workbook wb = workbook(true)) {
            var outcome = new ContractImportValidator().validate(wb);
            assertThat(outcome.errors()).isEmpty();
        }
    }

    /** Σ cheque amounts must equal Σ net line amounts (spec §6.4 validation rule 3). */
    @Test
    void chequesThatDoNotAddUpToTheContractValueAreAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            wb.getSheet("Cheques").getRow(2).getCell(7).setCellValue("20000.00");
            var outcome = new ContractImportValidator().validate(wb);
            assertThat(outcome.errors())
                    .extracting(ImportErrorDTO::getMessage)
                    .anyMatch(m -> m.contains("51000") || m.contains("56000"));
        }
    }

    @Test
    void aChequeReferencingAnUnknownContractIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            wb.getSheet("Cheques").getRow(1).getCell(0).setCellValue("NOPE/1");
            var outcome = new ContractImportValidator().validate(wb);
            assertThat(outcome.errors())
                    .extracting(ImportErrorDTO::getSheet, ImportErrorDTO::getField)
                    .contains(org.assertj.core.groups.Tuple.tuple("Cheques", "ContractNumber"));
        }
    }

    @Test
    void aContractWhoseUnitIsNotOnTheUnitsSheetIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            wb.getSheet("Contracts").getRow(1).getCell(4).setCellValue("999");
            var outcome = new ContractImportValidator().validate(wb);
            assertThat(outcome.errors()).extracting(ImportErrorDTO::getField).contains("UnitNumber");
        }
    }

    @Test
    void contractDateAfterStartDateIsAllowedButEndBeforeStartIsNot() throws Exception {
        try (Workbook wb = workbook(true)) {
            wb.getSheet("Contracts").getRow(1).getCell(8).setCellValue("2026-09-01");
            var outcome = new ContractImportValidator().validate(wb);
            assertThat(outcome.errors()).extracting(ImportErrorDTO::getField).contains("EndDate");
        }
    }

    @Test
    void anUnknownChequeStatusIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            wb.getSheet("Cheques").getRow(1).getCell(10).setCellValue("SETTLED");
            var outcome = new ContractImportValidator().validate(wb);
            assertThat(outcome.errors()).extracting(ImportErrorDTO::getField).contains("Status");
        }
    }

    /** A CLEARED row without a cleared date cannot be replayed through ChequeService.clear. */
    @Test
    void aClearedChequeWithoutAClearedDateIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            wb.getSheet("Cheques").getRow(1).getCell(11).setCellValue("");
            var outcome = new ContractImportValidator().validate(wb);
            assertThat(outcome.errors()).extracting(ImportErrorDTO::getField).contains("ClearedDate");
        }
    }

    @Test
    void anUnknownChargeTypeCodeIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            wb.getSheet("Contracts").getRow(1).getCell(11).setCellValue("MAGIC");
            var outcome = new ContractImportValidator().validate(wb);
            assertThat(outcome.errors()).extracting(ImportErrorDTO::getField).contains("ChargeTypeCode");
        }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.cutover.ContractImportValidatorTest'`
Expected: FAIL — `ContractImportValidator` does not exist.

- [ ] **Step 3: Write `ContractImportValidator`**

`core/service/cutover/ContractImportValidator.java`. It reuses `PortfolioImportService.HeaderIndex` (make that nested class and the `cell`/`getCellString`/`isRowEmpty` helpers **package-private static** on `PortfolioImportService` — they are already package-private or private static; change the three private ones to package-private `static` and move them nowhere else):

```java
package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.api.dto.ImportErrorDTO;
import com.datagami.rentaxis.core.service.PortfolioImportService;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Validates the v2 cut-over workbook (spec §10.3 step 1) before anything is written.
 *
 * <p>This is a second VALIDATOR, not a second IMPORTER: it plugs into
 * PortfolioImportService's existing async orchestrator, writes into the same
 * import_jobs row, and reports through the same ImportErrorDTO shape, so the polling
 * UI, the error table and the failure handling are all unchanged.
 *
 * <p>Statuses the sheet may carry are the subset of ChequeStatus that the bulk post
 * can actually replay through ChequeService: REGISTERED (the lease post creates it),
 * DEPOSITED, CLEARED and BOUNCED. A cheque that is already REPLACED or RETURNED in
 * PACT belongs to closed history, which is out of scope (spec §14).
 */
@Component
@RequiredArgsConstructor
public class ContractImportValidator {

    static final Set<String> IMPORTABLE_STATUSES = Set.of("REGISTERED", "DEPOSITED", "CLEARED", "BOUNCED");

    static final List<String> ACCOUNT_COLUMNS = List.of(
            "RentalIncomeAccount", "RentalReceivableAccount", "AdvanceRentAccount",
            "BankAccount", "PdcReceivableAccount", "SecurityDepositAccount");

    /** The Contracts sheet is the discriminator: no other v2-only sheet is mandatory. */
    public static boolean isV2Workbook(Workbook wb) {
        return wb.getSheet("Contracts") != null;
    }

    /** Header fields are read from a contract's FIRST row; later rows carry only line columns. */
    record ContractSummary(String propertyName, String unitKey, String renterEmail,
                           LocalDate contractDate, LocalDate startDate, LocalDate endDate,
                           BigDecimal netTotal, int firstRowNum) {}

    public PortfolioImportService.ValidationOutcome validate(Workbook wb) {
        List<ImportErrorDTO> errors = new ArrayList<>();
        List<ImportErrorDTO> warnings = new ArrayList<>();

        Sheet properties = wb.getSheet("Properties");
        Sheet units = wb.getSheet("Units");
        Sheet renters = wb.getSheet("Renters");
        Sheet contracts = wb.getSheet("Contracts");
        Sheet cheques = wb.getSheet("Cheques");

        if (properties == null) errors.add(new ImportErrorDTO("Properties", 0, "", "Sheet 'Properties' is missing"));
        if (units == null) errors.add(new ImportErrorDTO("Units", 0, "", "Sheet 'Units' is missing"));
        if (renters == null) errors.add(new ImportErrorDTO("Renters", 0, "", "Sheet 'Renters' is missing"));
        if (contracts == null) errors.add(new ImportErrorDTO("Contracts", 0, "", "Sheet 'Contracts' is missing"));
        if (!errors.isEmpty()) return new PortfolioImportService.ValidationOutcome(errors, warnings);

        Set<String> propertyNames = new HashSet<>();
        for (int i = 1; i <= properties.getLastRowNum(); i++) {
            Row r = properties.getRow(i);
            if (r == null || PortfolioImportService.isRowEmpty(r)) continue;
            String name = PortfolioImportService.getCellString(r, 0);
            if (!name.isEmpty()) propertyNames.add(name.toLowerCase(Locale.ROOT));
        }

        Set<String> unitKeys = new HashSet<>();     // "property|building|unit", lowercased
        for (int i = 1; i <= units.getLastRowNum(); i++) {
            Row r = units.getRow(i);
            if (r == null || PortfolioImportService.isRowEmpty(r)) continue;
            unitKeys.add((PortfolioImportService.getCellString(r, 0) + "|"
                    + PortfolioImportService.getCellString(r, 1) + "|"
                    + PortfolioImportService.getCellString(r, 2)).toLowerCase(Locale.ROOT));
        }

        Set<String> renterEmails = new HashSet<>();
        for (int i = 1; i <= renters.getLastRowNum(); i++) {
            Row r = renters.getRow(i);
            if (r == null || PortfolioImportService.isRowEmpty(r)) continue;
            String email = PortfolioImportService.getCellString(r, 2);
            if (!email.isEmpty()) renterEmails.add(email.toLowerCase(Locale.ROOT));
        }

        Map<String, ContractSummary> byNumber = validateContracts(
                contracts, propertyNames, unitKeys, renterEmails, errors);
        if (cheques != null) validateCheques(cheques, byNumber, errors, warnings);

        return new PortfolioImportService.ValidationOutcome(errors, warnings);
    }

    private Map<String, ContractSummary> validateContracts(Sheet sheet, Set<String> propertyNames,
                                                           Set<String> unitKeys, Set<String> renterEmails,
                                                           List<ImportErrorDTO> errors) {
        PortfolioImportService.HeaderIndex hi = new PortfolioImportService.HeaderIndex(sheet);
        Map<String, ContractSummary> byNumber = new LinkedHashMap<>();
        Map<String, BigDecimal> netByNumber = new LinkedHashMap<>();
        Map<String, Set<Integer>> lineNos = new HashMap<>();

        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null || PortfolioImportService.isRowEmpty(row)) continue;
            int rowNum = i + 1;

            String number = PortfolioImportService.cell(row, hi, "ContractNumber");
            if (number.isEmpty()) {
                errors.add(new ImportErrorDTO("Contracts", rowNum, "ContractNumber", "ContractNumber is required"));
                continue;
            }

            // ---- line columns, required on every row ----
            String lineNoStr = PortfolioImportService.cell(row, hi, "LineNo");
            int lineNo;
            try {
                lineNo = Integer.parseInt(lineNoStr);
                if (lineNo < 1) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                errors.add(new ImportErrorDTO("Contracts", rowNum, "LineNo", "LineNo must be a positive integer"));
                continue;
            }
            if (!lineNos.computeIfAbsent(number, k -> new HashSet<>()).add(lineNo)) {
                errors.add(new ImportErrorDTO("Contracts", rowNum, "LineNo",
                        "Duplicate LineNo " + lineNo + " for contract " + number));
            }

            String chargeCode = PortfolioImportService.cell(row, hi, "ChargeTypeCode");
            if (chargeCode.isEmpty()) {
                errors.add(new ImportErrorDTO("Contracts", rowNum, "ChargeTypeCode", "ChargeTypeCode is required"));
            } else if (!chargeTypeExists(chargeCode)) {
                errors.add(new ImportErrorDTO("Contracts", rowNum, "ChargeTypeCode",
                        "No charge type with code '" + chargeCode + "'. Seed it under Settings → Charge types first."));
            }

            BigDecimal gross = amountOrNull(PortfolioImportService.cell(row, hi, "GrossAmount"));
            BigDecimal discount = amountOrNull(PortfolioImportService.cell(row, hi, "DiscountAmount"));
            if (gross == null || gross.signum() <= 0) {
                errors.add(new ImportErrorDTO("Contracts", rowNum, "GrossAmount", "GrossAmount must be a number > 0"));
                continue;
            }
            if (discount == null) discount = BigDecimal.ZERO;
            if (discount.signum() < 0 || discount.compareTo(gross) > 0) {
                errors.add(new ImportErrorDTO("Contracts", rowNum, "DiscountAmount",
                        "DiscountAmount must be between 0 and GrossAmount"));
                continue;
            }
            netByNumber.merge(number, gross.subtract(discount), BigDecimal::add);

            // ---- header columns, read from the contract's first row only ----
            if (byNumber.containsKey(number)) continue;

            String propertyName = PortfolioImportService.cell(row, hi, "PropertyName");
            String buildingName = PortfolioImportService.cell(row, hi, "BuildingName");
            String unitNumber = PortfolioImportService.cell(row, hi, "UnitNumber");
            String renterEmail = PortfolioImportService.cell(row, hi, "RenterEmail");

            if (!propertyNames.contains(propertyName.toLowerCase(Locale.ROOT))) {
                errors.add(new ImportErrorDTO("Contracts", rowNum, "PropertyName",
                        "Property '" + propertyName + "' not found on the Properties sheet"));
            }
            String unitKey = (propertyName + "|" + buildingName + "|" + unitNumber).toLowerCase(Locale.ROOT);
            if (!unitKeys.contains(unitKey)) {
                errors.add(new ImportErrorDTO("Contracts", rowNum, "UnitNumber",
                        "Unit '" + unitNumber + "' in building '" + buildingName + "' of property '"
                                + propertyName + "' not found on the Units sheet"));
            }
            if (!renterEmails.contains(renterEmail.toLowerCase(Locale.ROOT))) {
                errors.add(new ImportErrorDTO("Contracts", rowNum, "RenterEmail",
                        "Renter email '" + renterEmail + "' not found on the Renters sheet"));
            }

            LocalDate contractDate = dateOrError(PortfolioImportService.cell(row, hi, "ContractDate"),
                    "Contracts", rowNum, "ContractDate", true, errors);
            LocalDate startDate = dateOrError(PortfolioImportService.cell(row, hi, "StartDate"),
                    "Contracts", rowNum, "StartDate", true, errors);
            LocalDate endDate = dateOrError(PortfolioImportService.cell(row, hi, "EndDate"),
                    "Contracts", rowNum, "EndDate", true, errors);
            if (startDate != null && endDate != null && !endDate.isAfter(startDate)) {
                errors.add(new ImportErrorDTO("Contracts", rowNum, "EndDate", "EndDate must be after StartDate"));
            }

            String grace = PortfolioImportService.cell(row, hi, "GracePeriodDays");
            if (!grace.isEmpty()) {
                try { Integer.parseInt(grace); } catch (NumberFormatException e) {
                    errors.add(new ImportErrorDTO("Contracts", rowNum, "GracePeriodDays",
                            "GracePeriodDays must be a whole number"));
                }
            }

            byNumber.put(number, new ContractSummary(propertyName, unitKey, renterEmail,
                    contractDate, startDate, endDate, BigDecimal.ZERO, rowNum));
        }

        // Fold the accumulated net totals back onto the summaries.
        Map<String, ContractSummary> out = new LinkedHashMap<>();
        byNumber.forEach((number, s) -> out.put(number, new ContractSummary(s.propertyName(), s.unitKey(),
                s.renterEmail(), s.contractDate(), s.startDate(), s.endDate(),
                netByNumber.getOrDefault(number, BigDecimal.ZERO), s.firstRowNum())));
        return out;
    }

    private void validateCheques(Sheet sheet, Map<String, ContractSummary> contracts,
                                 List<ImportErrorDTO> errors, List<ImportErrorDTO> warnings) {
        PortfolioImportService.HeaderIndex hi = new PortfolioImportService.HeaderIndex(sheet);
        Map<String, BigDecimal> sumByContract = new LinkedHashMap<>();
        Map<String, Set<Integer>> seqByContract = new HashMap<>();
        Set<String> contractsWithCheques = new LinkedHashSet<>();

        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null || PortfolioImportService.isRowEmpty(row)) continue;
            int rowNum = i + 1;

            String number = PortfolioImportService.cell(row, hi, "ContractNumber");
            ContractSummary contract = contracts.get(number);
            if (contract == null) {
                errors.add(new ImportErrorDTO("Cheques", rowNum, "ContractNumber",
                        "No Contracts row with ContractNumber '" + number + "'"));
                continue;
            }
            contractsWithCheques.add(number);

            String seqStr = PortfolioImportService.cell(row, hi, "SeqNo");
            try {
                int seq = Integer.parseInt(seqStr);
                if (seq < 1) throw new NumberFormatException();
                if (!seqByContract.computeIfAbsent(number, k -> new HashSet<>()).add(seq)) {
                    errors.add(new ImportErrorDTO("Cheques", rowNum, "SeqNo",
                            "Duplicate SeqNo " + seq + " for contract " + number));
                }
            } catch (NumberFormatException e) {
                errors.add(new ImportErrorDTO("Cheques", rowNum, "SeqNo", "SeqNo must be a positive integer"));
            }

            String mode = PortfolioImportService.cell(row, hi, "Mode").toUpperCase(Locale.ROOT);
            if (mode.isEmpty()) mode = "PDC";
            if (Arrays.stream(ChequeMode.values()).noneMatch(m -> m.name().equals(mode))) {
                errors.add(new ImportErrorDTO("Cheques", rowNum, "Mode",
                        "Mode must be one of " + Arrays.toString(ChequeMode.values())));
            }
            if ("PDC".equals(mode) && PortfolioImportService.cell(row, hi, "ChequeNumber").isEmpty()) {
                errors.add(new ImportErrorDTO("Cheques", rowNum, "ChequeNumber",
                        "A PDC row needs a cheque number"));
            }

            String status = PortfolioImportService.cell(row, hi, "Status").toUpperCase(Locale.ROOT);
            if (status.isEmpty()) status = "REGISTERED";
            if (!IMPORTABLE_STATUSES.contains(status)) {
                errors.add(new ImportErrorDTO("Cheques", rowNum, "Status",
                        "Status must be one of " + IMPORTABLE_STATUSES
                                + " — closed history stays in PACT (spec §14)"));
            }
            LocalDate cleared = dateOrError(PortfolioImportService.cell(row, hi, "ClearedDate"),
                    "Cheques", rowNum, "ClearedDate", "CLEARED".equals(status), errors);
            LocalDate bounced = dateOrError(PortfolioImportService.cell(row, hi, "BouncedDate"),
                    "Cheques", rowNum, "BouncedDate", "BOUNCED".equals(status), errors);
            if ("CLEARED".equals(status) && cleared == null) {
                // dateOrError already logged the missing-value case when required
            }
            if ("BOUNCED".equals(status) && bounced == null) {
                // same
            }

            dateOrError(PortfolioImportService.cell(row, hi, "PostingDate"),
                    "Cheques", rowNum, "PostingDate", true, errors);
            dateOrError(PortfolioImportService.cell(row, hi, "ChequeDate"),
                    "Cheques", rowNum, "ChequeDate", true, errors);

            BigDecimal amount = amountOrNull(PortfolioImportService.cell(row, hi, "Amount"));
            if (amount == null || amount.signum() <= 0) {
                errors.add(new ImportErrorDTO("Cheques", rowNum, "Amount", "Amount must be a number > 0"));
            } else {
                sumByContract.merge(number, amount, BigDecimal::add);
            }
        }

        // Spec §6.4 rule 3: the cheque grid must add up to the contract value exactly.
        for (String number : contractsWithCheques) {
            BigDecimal sum = sumByContract.getOrDefault(number, BigDecimal.ZERO);
            BigDecimal expected = contracts.get(number).netTotal();
            if (sum.compareTo(expected) != 0) {
                errors.add(new ImportErrorDTO("Cheques", contracts.get(number).firstRowNum(), "Amount",
                        "Cheques for " + number + " total " + sum + " but the contract lines total " + expected));
            }
        }
        for (Map.Entry<String, ContractSummary> e : contracts.entrySet()) {
            if (!contractsWithCheques.contains(e.getKey())) {
                warnings.add(new ImportErrorDTO("Cheques", e.getValue().firstRowNum(), "ContractNumber",
                        "Contract " + e.getKey() + " has no cheque rows; it will import but cannot be posted"));
            }
        }
    }

    /** Overridden in ContractImportPersistService's own validation pass; here it is a repository lookup. */
    protected boolean chargeTypeExists(String code) {
        return chargeTypes.findByCodeIgnoreCase(code).isPresent();
    }

    private final com.datagami.rentaxis.domain.repository.ChargeTypeRepository chargeTypes;

    private static BigDecimal amountOrNull(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return new BigDecimal(raw.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static LocalDate dateOrError(String raw, String sheet, int rowNum, String field,
                                         boolean required, List<ImportErrorDTO> errors) {
        if (raw == null || raw.isBlank()) {
            if (required) errors.add(new ImportErrorDTO(sheet, rowNum, field, field + " is required"));
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            errors.add(new ImportErrorDTO(sheet, rowNum, field, field + " must be ISO format (YYYY-MM-DD)"));
            return null;
        }
    }
}
```

> **On `chargeTypeExists`:** `ContractImportValidatorTest` constructs the validator with `new ContractImportValidator()`, which will not compile against the constructor-injected repository. Give the class a second constructor for the unit test:
> ```java
>     /** Test constructor: every ChargeTypeCode is accepted except the literal "MAGIC" sentinel. */
>     ContractImportValidator() { this.chargeTypes = null; }
>     @Override protected boolean chargeTypeExists(String code) {
>         return chargeTypes == null ? !"MAGIC".equalsIgnoreCase(code) : chargeTypes.findByCodeIgnoreCase(code).isPresent();
>     }
> ```
> Fold that null check into the single `chargeTypeExists` above rather than overriding, and drop `protected`. This keeps the unit test free of Spring while the production path still checks the real catalogue.

Also change three members of `PortfolioImportService` from `private` to package-private `static` so the validator can reuse them verbatim rather than copying them:

```java
    static String getCellString(Row row, int col) { … }      // was: private String
    static boolean isRowEmpty(Row row) { … }                 // was: private boolean
    static String cell(Row row, HeaderIndex hi, String header) { … }   // was: private String
```

`HeaderIndex` is already `static final class` with package-private members — make the class `public static final` and its `col`/`has` methods `public`, since `ContractImportValidator` lives in a different package.

> **`findByCodeIgnoreCase`:** Plan 2 creates `ChargeTypeRepository` but its plan does not pin the finder's name. Before writing this task, open `domain/repository/ChargeTypeRepository.java` and use whatever by-code finder is there; add `Optional<ChargeType> findByCodeIgnoreCase(String code)` to it only if none exists. Three call sites in this task depend on it.

- [ ] **Step 4: Run the validator test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.cutover.ContractImportValidatorTest'`
Expected: PASS, 9 tests.

- [ ] **Step 5: Wire the v2 mode into the existing orchestrator**

In `PortfolioImportService`, inject the two new collaborators and branch once in `validateAll` and once in `processImportAsync`:

```java
    private final ContractImportValidator contractValidator;
    private final ContractImportPersistService contractPersistService;

    public ValidationOutcome validateAll(Workbook workbook) {
        // A workbook with a Contracts sheet is an accounting-v2 cut-over import
        // (spec §10.3). Same job row, same async executor, same error shape — only
        // the sheet set and the persist target differ.
        if (ContractImportValidator.isV2Workbook(workbook)) {
            return contractValidator.validate(workbook);
        }
        … existing v1 body unchanged …
    }
```

and inside `processImportAsync`, replace the single persist call with:

```java
            job.setStatus("PERSISTING");
            importJobRepository.save(job);

            if (ContractImportValidator.isV2Workbook(workbook)) {
                ContractImportPersistService.ContractImportSummary summary =
                        contractPersistService.persist(workbook, job);
                job.setImportBatchId(summary.batchId());
            } else {
                persistService.persistWorkbook(workbook, job, outcome.warnings());
            }
```

Add `import_batch_id` to `ImportJob`:

```java
    /** Set by the accounting-v2 contract import; null for a v1 portfolio import. */
    @Column(name = "import_batch_id")
    private UUID importBatchId;
```

Add `importBatchId` and `contractsCreated` to `PortfolioImportResultDTO`, and `contractsCreated` to `PortfolioImportJobDetailsDTO` (as an `Integer`, so the existing `NON_NULL` inclusion keeps old payloads byte-identical). In `PortfolioImportController.mapToResult`, after the wrapper branch reads `details`, add:

```java
                    if (details.getContractsCreated() != null) dto.setContractsCreated(details.getContractsCreated());
```

and unconditionally, before returning:

```java
        dto.setImportBatchId(job.getImportBatchId());
```

- [ ] **Step 6: Write `ContractImportPersistService`**

`core/service/cutover/ContractImportPersistService.java` — the same shape as `PortfolioImportPersistService` (one `@Transactional` method, `LinkedHashMap` lookups keyed on lowercased names, counters written onto the job):

```java
package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.api.dto.ImportErrorDTO;
import com.datagami.rentaxis.api.dto.PortfolioImportJobDetailsDTO;
import com.datagami.rentaxis.core.service.PortfolioImportService;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Writes the v2 cut-over workbook: properties (with their role mappings), units,
 * renters, DRAFT leases with their lines, and DRAFT cheques — all linked to one
 * ImportBatch. Nothing posts here; posting is a separate, explicit action
 * (ContractImportPostService, spec §10.3 "Bulk post").
 *
 * <p>Property account mappings are matched by NAME against the existing chart of
 * accounts, exactly as the client's "property mapping ledgers" sheet lists them. An
 * unmatched name produces a warning naming the column and the value — the spec is
 * explicit that unmatched names are reported, not guessed, because guessing would
 * silently route a whole tower's rent into the wrong ledger.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContractImportPersistService {

    private final PropertyRepository properties;
    private final BuildingRepository buildings;
    private final UnitRepository units;
    private final RenterRepository renters;
    private final LeaseRepository leases;
    private final LeaseLineRepository leaseLines;
    private final ChequeRepository cheques;
    private final ChargeTypeRepository chargeTypes;
    private final AccountRepository accounts;
    private final PropertyAccountMappingRepository propertyMappings;
    private final ImportBatchRepository batchRepository;
    private final ImportBatchService batches;
    private final ImportJobRepository importJobRepository;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** Column header → the role its account name maps to. Order matches the client's sheet. */
    private static final Map<String, AccountRole> ACCOUNT_COLUMN_ROLES = new LinkedHashMap<>(Map.of(
            "RentalIncomeAccount", AccountRole.RENTAL_INCOME,
            "RentalReceivableAccount", AccountRole.RENT_RECEIVABLE,
            "AdvanceRentAccount", AccountRole.ADVANCE_RENT,
            "BankAccount", AccountRole.BANK,
            "PdcReceivableAccount", AccountRole.PDC_RECEIVABLE,
            "SecurityDepositAccount", AccountRole.SECURITY_DEPOSIT));

    public record ContractImportSummary(UUID batchId, int propertiesCreated, int unitsCreated, int rentersCreated,
                                        int contractsCreated, int chequesCreated, int mappingsCreated,
                                        List<ImportErrorDTO> warnings) {}

    @Transactional
    public ContractImportSummary persist(Workbook wb, ImportJob job) {
        List<ImportErrorDTO> warnings = new ArrayList<>();
        ImportBatch batch = batches.create(job.getId(), "Contract import — " + job.getFileName());

        Map<String, Account> accountsByName = new HashMap<>();
        for (Account a : accounts.findAll()) {
            if (!a.isGroup()) accountsByName.putIfAbsent(a.getName().trim().toLowerCase(Locale.ROOT), a);
        }

        // ---- 1. Properties + role mappings ----
        Sheet propertiesSheet = wb.getSheet("Properties");
        PortfolioImportService.HeaderIndex propsHi = new PortfolioImportService.HeaderIndex(propertiesSheet);
        Map<String, Property> propertyByName = new LinkedHashMap<>();
        int mappingsCreated = 0;
        for (int i = 1; i <= propertiesSheet.getLastRowNum(); i++) {
            Row row = propertiesSheet.getRow(i);
            if (row == null || PortfolioImportService.isRowEmpty(row)) continue;
            int rowNum = i + 1;

            Property p = new Property();
            p.setNameEn(PortfolioImportService.getCellString(row, 0));
            String nameAr = PortfolioImportService.getCellString(row, 1);
            p.setNameAr(nameAr.isEmpty() ? null : nameAr);
            p.setEmirate(Emirate.valueOf(PortfolioImportService.getCellString(row, 2)
                    .trim().toUpperCase(Locale.ROOT).replace(" ", "_")));
            String address = PortfolioImportService.getCellString(row, 3);
            p.setAddress(address.isEmpty() ? null : address);
            p.setType(PropertyType.valueOf(PortfolioImportService.getCellString(row, 4)
                    .trim().toUpperCase(Locale.ROOT).replace(" ", "_")));
            String makani = PortfolioImportService.getCellString(row, 5);
            p.setMakaniNumber(makani.isEmpty() ? null : makani);
            Property saved = properties.save(p);
            propertyByName.put(saved.getNameEn().toLowerCase(Locale.ROOT), saved);

            for (Map.Entry<String, AccountRole> e : ACCOUNT_COLUMN_ROLES.entrySet()) {
                String accountName = PortfolioImportService.cell(row, propsHi, e.getKey());
                if (accountName.isEmpty()) continue;
                Account account = accountsByName.get(accountName.trim().toLowerCase(Locale.ROOT));
                if (account == null) {
                    warnings.add(new ImportErrorDTO("Properties", rowNum, e.getKey(),
                            "No ledger account named '" + accountName + "' — "
                                    + e.getValue() + " left unmapped for " + saved.getNameEn()));
                    continue;
                }
                PropertyAccountMapping m = new PropertyAccountMapping();
                m.setPropertyId(saved.getId());
                m.setRole(e.getValue());
                m.setAccount(account);
                propertyMappings.save(m);
                mappingsCreated++;
            }
        }

        // ---- 2. Buildings + Units (identical rules to the v1 importer) ----
        Sheet unitsSheet = wb.getSheet("Units");
        Map<String, Building> buildingByKey = new LinkedHashMap<>();
        Map<String, Unit> unitByKey = new LinkedHashMap<>();   // "property|building|unit"
        for (int i = 1; i <= unitsSheet.getLastRowNum(); i++) {
            Row row = unitsSheet.getRow(i);
            if (row == null || PortfolioImportService.isRowEmpty(row)) continue;
            String propertyName = PortfolioImportService.getCellString(row, 0);
            String buildingName = PortfolioImportService.getCellString(row, 1);
            String unitNumber = PortfolioImportService.getCellString(row, 2);
            String unitType = PortfolioImportService.getCellString(row, 3);
            Property property = propertyByName.get(propertyName.toLowerCase(Locale.ROOT));

            Building building = null;
            if (!buildingName.isEmpty()) {
                String bKey = (propertyName + "|" + buildingName).toLowerCase(Locale.ROOT);
                building = buildingByKey.computeIfAbsent(bKey, k -> {
                    Building b = new Building();
                    b.setProperty(property);
                    b.setNameEn(buildingName);
                    return buildings.save(b);
                });
            }
            Unit u = new Unit();
            u.setProperty(property);
            u.setBuilding(building);
            u.setUnitNumber(unitNumber);
            u.setStatus(UnitStatus.VACANT);
            if (!unitType.isEmpty()) u.setUnitType(UnitType.valueOf(unitType.trim().toUpperCase(Locale.ROOT)));
            unitByKey.put((propertyName + "|" + buildingName + "|" + unitNumber).toLowerCase(Locale.ROOT),
                    units.save(u));
        }

        // ---- 3. Renters ----
        Sheet rentersSheet = wb.getSheet("Renters");
        Map<String, Renter> renterByEmail = new LinkedHashMap<>();
        for (int i = 1; i <= rentersSheet.getLastRowNum(); i++) {
            Row row = rentersSheet.getRow(i);
            if (row == null || PortfolioImportService.isRowEmpty(row)) continue;
            Renter r = new Renter();
            r.setNameEn(PortfolioImportService.getCellString(row, 0));
            String nameAr = PortfolioImportService.getCellString(row, 1);
            r.setNameAr(nameAr.isEmpty() ? null : nameAr);
            r.setEmail(PortfolioImportService.getCellString(row, 2));
            String phone = PortfolioImportService.getCellString(row, 3);
            r.setPhone(phone.isEmpty() ? null : phone);
            Renter saved = renters.save(r);
            renterByEmail.put(saved.getEmail().toLowerCase(Locale.ROOT), saved);
        }

        // ---- 4. Contracts → DRAFT leases + lines ----
        Sheet contractsSheet = wb.getSheet("Contracts");
        PortfolioImportService.HeaderIndex cHi = new PortfolioImportService.HeaderIndex(contractsSheet);
        Map<String, Lease> leaseByNumber = new LinkedHashMap<>();
        Map<String, BigDecimal> valueByNumber = new HashMap<>();
        for (int i = 1; i <= contractsSheet.getLastRowNum(); i++) {
            Row row = contractsSheet.getRow(i);
            if (row == null || PortfolioImportService.isRowEmpty(row)) continue;
            String number = PortfolioImportService.cell(row, cHi, "ContractNumber");

            Lease lease = leaseByNumber.get(number);
            if (lease == null) {
                String propertyName = PortfolioImportService.cell(row, cHi, "PropertyName");
                String buildingName = PortfolioImportService.cell(row, cHi, "BuildingName");
                String unitNumber = PortfolioImportService.cell(row, cHi, "UnitNumber");
                Unit unit = unitByKey.get((propertyName + "|" + buildingName + "|" + unitNumber)
                        .toLowerCase(Locale.ROOT));
                Renter renter = renterByEmail.get(
                        PortfolioImportService.cell(row, cHi, "RenterEmail").toLowerCase(Locale.ROOT));

                lease = new Lease();
                lease.setContractNumber(number);
                lease.setChainId(UUID.randomUUID());          // a fresh chain: PACT history stays in PACT
                lease.setProperty(propertyByName.get(propertyName.toLowerCase(Locale.ROOT)));
                lease.setUnit(unit);
                lease.setRenter(renter);
                lease.setContractDate(LocalDate.parse(PortfolioImportService.cell(row, cHi, "ContractDate")));
                lease.setStartDate(LocalDate.parse(PortfolioImportService.cell(row, cHi, "StartDate")));
                lease.setEndDate(LocalDate.parse(PortfolioImportService.cell(row, cHi, "EndDate")));
                String grace = PortfolioImportService.cell(row, cHi, "GracePeriodDays");
                lease.setGracePeriodDays(grace.isEmpty() ? 0 : Integer.parseInt(grace));
                lease.setStatus(LeaseStatus.DRAFT);
                lease = leases.save(lease);
                leaseByNumber.put(number, lease);
                batches.linkLease(batch.getId(), lease.getId());
            }

            ChargeType chargeType = chargeTypes.findByCodeIgnoreCase(
                    PortfolioImportService.cell(row, cHi, "ChargeTypeCode")).orElseThrow();
            BigDecimal gross = new BigDecimal(PortfolioImportService.cell(row, cHi, "GrossAmount"));
            String discountStr = PortfolioImportService.cell(row, cHi, "DiscountAmount");
            BigDecimal discount = discountStr.isEmpty() ? BigDecimal.ZERO : new BigDecimal(discountStr);
            BigDecimal net = gross.subtract(discount);

            LeaseLine line = new LeaseLine();
            line.setLease(lease);
            line.setSeqNo(Integer.parseInt(PortfolioImportService.cell(row, cHi, "LineNo")));
            line.setChargeType(chargeType);
            String creditAccountName = PortfolioImportService.cell(row, cHi, "CreditAccount");
            if (!creditAccountName.isEmpty()) {
                Account credit = accountsByName.get(creditAccountName.trim().toLowerCase(Locale.ROOT));
                if (credit == null) {
                    warnings.add(new ImportErrorDTO("Contracts", i + 1, "CreditAccount",
                            "No ledger account named '" + creditAccountName
                                    + "' — the line will use the property's mapping for " + chargeType.getRole()));
                } else {
                    line.setCreditAccount(credit);
                }
            }
            line.setGrossAmount(gross);
            line.setDiscountAmount(discount);
            line.setNetAmount(net);
            line.setNarration(PortfolioImportService.cell(row, cHi, "Narration"));
            line.setVatApplicable("true".equalsIgnoreCase(PortfolioImportService.cell(row, cHi, "VatApplicable")));
            leaseLines.save(line);
            valueByNumber.merge(number, net, BigDecimal::add);
        }
        leaseByNumber.forEach((number, lease) -> {
            lease.setContractValue(valueByNumber.getOrDefault(number, BigDecimal.ZERO));
            leases.save(lease);
        });

        // ---- 5. Cheques → DRAFT rows (the lease post registers them) ----
        int chequesCreated = 0;
        Sheet chequesSheet = wb.getSheet("Cheques");
        if (chequesSheet != null) {
            PortfolioImportService.HeaderIndex qHi = new PortfolioImportService.HeaderIndex(chequesSheet);
            for (int i = 1; i <= chequesSheet.getLastRowNum(); i++) {
                Row row = chequesSheet.getRow(i);
                if (row == null || PortfolioImportService.isRowEmpty(row)) continue;
                Lease lease = leaseByNumber.get(PortfolioImportService.cell(row, qHi, "ContractNumber"));
                if (lease == null) continue;

                Cheque c = new Cheque();
                c.setLease(lease);
                c.setPropertyId(lease.getProperty().getId());
                c.setUnitId(lease.getUnit() == null ? null : lease.getUnit().getId());
                c.setRenterId(lease.getRenter() == null ? null : lease.getRenter().getId());
                c.setSeqNo(Integer.parseInt(PortfolioImportService.cell(row, qHi, "SeqNo")));
                c.setPostingDate(LocalDate.parse(PortfolioImportService.cell(row, qHi, "PostingDate")));
                String chequeNumber = PortfolioImportService.cell(row, qHi, "ChequeNumber");
                c.setChequeNumber(chequeNumber.isEmpty() ? null : chequeNumber);
                c.setChequeDate(LocalDate.parse(PortfolioImportService.cell(row, qHi, "ChequeDate")));
                String payeeBank = PortfolioImportService.cell(row, qHi, "PayeeBank");
                c.setPayeeBank(payeeBank.isEmpty() ? null : payeeBank);
                String debitAccountName = PortfolioImportService.cell(row, qHi, "DebitAccount");
                if (!debitAccountName.isEmpty()) {
                    Account debit = accountsByName.get(debitAccountName.trim().toLowerCase(Locale.ROOT));
                    if (debit == null) {
                        warnings.add(new ImportErrorDTO("Cheques", i + 1, "DebitAccount",
                                "No ledger account named '" + debitAccountName
                                        + "' — the row will use the property's BANK mapping"));
                    } else {
                        c.setDebitAccount(debit);
                    }
                }
                c.setAmount(new BigDecimal(PortfolioImportService.cell(row, qHi, "Amount")));
                c.setNarration(PortfolioImportService.cell(row, qHi, "Narration"));
                String mode = PortfolioImportService.cell(row, qHi, "Mode");
                c.setMode(ChequeMode.valueOf(mode.isEmpty() ? "PDC" : mode.toUpperCase(Locale.ROOT)));
                c.setStatus(ChequeStatus.DRAFT);
                cheques.save(c);
                chequesCreated++;
            }
        }

        job.setPropertiesCreated(propertyByName.size());
        job.setBuildingsCreated(buildingByKey.size());
        job.setUnitsCreated(unitByKey.size());
        job.setRentersCreated(renterByEmail.size());
        job.setLeasesCreated(leaseByNumber.size());
        job.setSchedulesCreated(chequesCreated);
        job.setImportBatchId(batch.getId());

        PortfolioImportJobDetailsDTO details = new PortfolioImportJobDetailsDTO();
        details.setWarnings(warnings);
        details.setContractsCreated(leaseByNumber.size());
        details.setChequesFromSheet(chequesCreated);
        try {
            job.setErrors(objectMapper.writeValueAsString(details));
        } catch (Exception e) {
            log.warn("Could not serialise import details for job {}", job.getId(), e);
        }
        importJobRepository.save(job);

        ImportBatch reloaded = batchRepository.findById(batch.getId()).orElseThrow();
        return new ContractImportSummary(reloaded.getId(), propertyByName.size(), unitByKey.size(),
                renterByEmail.size(), leaseByNumber.size(), chequesCreated, mappingsCreated, warnings);
    }
}
```

- [ ] **Step 7: Extend `PortfolioTemplateService` with the v2 sheets**

Add the six account columns to the Properties header array and two new sheet builders. The existing `createHeaderRow`, `addRow`, `addDropdown`, `autoSizeColumns` helpers are reused unchanged:

```java
    private void createPropertiesSheet(XSSFWorkbook workbook, CellStyle headerStyle) {
        XSSFSheet sheet = workbook.createSheet("Properties");
        String[] headers = {"PropertyName", "PropertyNameAr", "Emirate", "Address", "Type", "MakaniNumber",
                // Accounting v2 cut-over (spec §10.3): optional, matched by exact account NAME.
                // These are the columns of the client's "property mapping ledgers" export plus the
                // two further roles a lease needs before it can post (spec §5.4).
                "RentalIncomeAccount", "RentalReceivableAccount", "AdvanceRentAccount",
                "BankAccount", "PdcReceivableAccount", "SecurityDepositAccount"};
        … rest unchanged …
    }

    private void createContractsSheet(XSSFWorkbook workbook, CellStyle headerStyle) {
        XSSFSheet sheet = workbook.createSheet("Contracts");
        String[] headers = {
                "ContractNumber", "TrackingNumber", "PropertyName", "BuildingName", "UnitNumber",
                "RenterEmail", "ContractDate", "StartDate", "EndDate", "GracePeriodDays",
                "LineNo", "ChargeTypeCode", "CreditAccount", "GrossAmount", "DiscountAmount",
                "VatApplicable", "Narration"};
        createHeaderRow(sheet, headers, headerStyle);
        // One row per LINE; rows sharing a ContractNumber are one contract and only the
        // first carries the header fields.
        addRow(sheet, 1, "TLP7/681", "TRK-1", "Tulip Oasis 7", "", "101", "islam@example.com",
                "2026-09-11", "2026-09-24", "2027-09-23", "5",
                "1", "RENT", "Rental Income Tulip 7", "51000.00", "0", "false", "Annual rent");
        addRow(sheet, 2, "TLP7/681", "", "", "", "", "", "", "", "", "",
                "2", "SECURITY_DEPOSIT", "", "5000.00", "0", "false", "Security deposit");
        autoSizeColumns(sheet, headers.length);
    }

    private void createContractChequesSheet(XSSFWorkbook workbook, CellStyle headerStyle) {
        XSSFSheet sheet = workbook.createSheet("Cheques");
        String[] headers = {
                "ContractNumber", "SeqNo", "PostingDate", "ChequeNumber", "ChequeDate",
                "PayeeBank", "DebitAccount", "Amount", "Narration", "Mode", "Status",
                "ClearedDate", "BouncedDate"};
        createHeaderRow(sheet, headers, headerStyle);
        addDropdown(sheet, 1, 1000, 9, 9, new String[]{"PDC", "CASH", "TRANSFER", "ONLINE"});
        addDropdown(sheet, 1, 1000, 10, 10, new String[]{"REGISTERED", "DEPOSITED", "CLEARED", "BOUNCED"});
        addRow(sheet, 1, "TLP7/681", "1", "2026-09-11", "000101", "2026-09-24", "ENBD", "",
                "31000.00", "Rent - 1st Installment", "PDC", "CLEARED", "2026-09-25", "");
        addRow(sheet, 2, "TLP7/681", "2", "2026-09-11", "000102", "2027-03-24", "ENBD", "",
                "25000.00", "Rent - 2nd Installment", "PDC", "REGISTERED", "", "");
        autoSizeColumns(sheet, headers.length);
    }
```

`generateTemplate()` currently builds one workbook. Give it a parameter so the two templates stay one method:

```java
    /** @param cutOver true for the accounting-v2 contract-import template (Contracts + v2 Cheques). */
    public byte[] generateTemplate(boolean cutOver) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            CellStyle headerStyle = createHeaderStyle(workbook);
            createPropertiesSheet(workbook, headerStyle);
            createUnitsSheet(workbook, headerStyle);
            createRentersSheet(workbook, headerStyle);
            if (cutOver) {
                createContractsSheet(workbook, headerStyle);
                createContractChequesSheet(workbook, headerStyle);
            } else {
                createLeasesSheet(workbook, headerStyle);
                createChequesSheet(workbook, headerStyle);
            }
            …existing write-to-bytes tail…
        }
    }

    public byte[] generateTemplate() throws IOException { return generateTemplate(false); }
```

and in `PortfolioImportController`:

```java
    @GetMapping("/template")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<byte[]> downloadTemplate(
            @RequestParam(name = "cutOver", defaultValue = "false") boolean cutOver) {
        try {
            byte[] template = templateService.generateTemplate(cutOver);
            String filename = cutOver ? "contract-import-template.xlsx" : "portfolio-import-template.xlsx";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(template);
        } catch (Exception e) {
            log.error("Failed to generate template", e);
            return ResponseEntity.internalServerError().build();
        }
    }
```

- [ ] **Step 8: Write the end-to-end import IT**

`backend/src/test/java/com/datagami/rentaxis/core/service/cutover/ContractImportIT.java` — builds the template workbook via `PortfolioTemplateService.generateTemplate(true)`, seeds the accounts the sample names refer to, runs `PortfolioImportService.processImportAsync(bytes, job, tenantId)` synchronously (call it directly; `@Async` is a no-op when invoked in-process on the same bean reference — inject it and call `validateAll` + `contractPersistService.persist` if the proxy defeats that), and asserts:

```java
    @Test
    void theCutOverTemplateImportsAsDraftLeasesInOneBatch() throws Exception {
        byte[] bytes = templateService.generateTemplate(true);
        ImportJob job = new ImportJob();
        job.setStatus("VALIDATING");
        job.setFileName("cutover.xlsx");
        job = importJobs.save(job);

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(importService.validateAll(wb).errors()).isEmpty();
            var summary = contractPersist.persist(wb, job);

            assertThat(summary.contractsCreated()).isEqualTo(1);
            assertThat(summary.chequesCreated()).isEqualTo(2);
            assertThat(summary.mappingsCreated()).isEqualTo(6);
            assertThat(batches.leaseIds(summary.batchId())).hasSize(1);
            UUID leaseId = batches.leaseIds(summary.batchId()).get(0);
            assertThat(leaseRepo.findById(leaseId).orElseThrow().getStatus()).isEqualTo(LeaseStatus.DRAFT);
            assertThat(leaseRepo.findById(leaseId).orElseThrow().getContractValue()).isEqualByComparingTo("56000.00");
            assertThat(chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId))
                    .allSatisfy(c -> assertThat(c.getStatus()).isEqualTo(ChequeStatus.DRAFT));
            assertThat(entries.findByImportBatchIdOrderByCreatedAtAsc(summary.batchId())).isEmpty();
        }
    }

    /** An account name the chart does not have is a warning naming the column, never a guess. */
    @Test
    void anUnmatchedAccountNameIsReportedAndTheRoleIsLeftUnmapped() throws Exception {
        byte[] bytes = templateService.generateTemplate(true);
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            // Column index 9 on Properties is BankAccount.
            wb.getSheet("Properties").getRow(1).getCell(9).setCellValue("Bank Of Nowhere");
            ImportJob job = importJobs.save(newJob());
            var summary = contractPersist.persist(wb, job);
            assertThat(summary.warnings())
                    .extracting(ImportErrorDTO::getField, ImportErrorDTO::getMessage)
                    .anySatisfy(t -> {
                        assertThat(t).element(0).isEqualTo("BankAccount");
                        assertThat((String) t[1]).contains("Bank Of Nowhere");
                    });
            assertThat(summary.mappingsCreated()).isEqualTo(5);
        }
    }
```

Seed the six account names the template's sample row references in `@BeforeEach` (`Rental Income Tulip 7`, `Rent Receivable - Tulip 7`, `Advance Rent - Tulip 7`, `Emirates Islamic - Tulip 7`, `PDC Receivable Tulip 7`, `Security Deposit Tulip 7`) with `accounts.createLeaf(name, accounts.getAccountByCode(<matching group>), null)`, and seed the `RENT` and `SECURITY_DEPOSIT` charge types through Plan 2's seeder.

- [ ] **Step 9: Run everything in the package**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.cutover.*' --tests 'com.datagami.rentaxis.core.service.PortfolioImport*'`
Expected: PASS, including every pre-existing v1 portfolio-import test — the v1 path must be untouched.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis backend/src/test/java/com/datagami/rentaxis
git commit -m "feat(finance): active-contract import sheets on the portfolio importer

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 11: Bulk post the imported batch

> **BLOCKED until Plans 2 and 3 are merged.** Depends on Task 10.

**Files:**
- Create: `core/service/cutover/ContractImportPostService.java`
- Modify: `api/ImportBatchController.java` (add `POST /{id}/post`)
- Modify (Plans 2/3 seam): `core/service/lease/LeasePostingService.java`, `core/service/lease/ChequeService.java`, `core/service/lease/LeaseService.java`, `core/service/recognition/RecognitionService.java`
- Test: `core/service/cutover/ContractImportPostIT.java`

**Interfaces:**
- Consumes: Task 7's `ImportBatchService.{get,leaseIds,markPosted}`; Task 10's import; `TenantFiscalSettingsService.get().getBooksStartDate()`.
- Consumes (from Plans 2/3), each gaining an `importBatchId` overload in Step 3:
  ```java
  PostLeaseResponse    LeasePostingService.post(UUID leaseId, UUID importBatchId);
  ChequeDTO            ChequeService.deposit(UUID chequeId, ChequeActionRequest r, UUID importBatchId);
  ChequeDTO            ChequeService.clear(UUID chequeId, ChequeActionRequest r, UUID importBatchId);
  ChequeDTO            ChequeService.bounce(UUID chequeId, ChequeActionRequest r, UUID importBatchId);
  RecognitionRunResult RecognitionService.runTo(LocalDate to, boolean preview, UUID importBatchId);
  void                 LeaseService.revertToDraft(UUID leaseId);    // new; implements cutover.LeaseReverter
  ```
- Produces:
  ```java
  record BulkPostResult(UUID batchId, int leasesPosted, int chequesCleared, int chequesBounced,
                        int chequesDeposited, int recognitionEntriesPosted, int journalsPosted,
                        List<ImportErrorDTO> failures) {}
  BulkPostResult ContractImportPostService.post(UUID batchId);
  ```
  Endpoint: `POST /api/v1/finance/import-batches/{id}/post` → `BulkPostResult` (`hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT')`).

- [ ] **Step 1: Write the failing bulk-post IT**

`backend/src/test/java/com/datagami/rentaxis/core/service/cutover/ContractImportPostIT.java`. Build on Task 10's IT fixture (same template workbook, same seeded accounts and charge types, `books_start_date = 2026-10-01`, `books_locked_through = 2026-09-30`), then:

```java
    /**
     * Spec §10.3: "Bulk post writes TCO/PDR dated contract_date, applies cheque statuses
     * with CRT/CBR on the given dates, and runs recognition catch-up for every period
     * ending before D." Every date in this scenario is inside the locked period —
     * which is the point: import journals are exempt, and they are only exempt because
     * they carry importBatchId.
     */
    @Test
    void bulkPostWritesEveryJournalIntoTheBatchAndCatchesRecognitionUpToTheCutOver() {
        var summary = importTheTemplate();               // Task 10's helper
        var result = postService.post(summary.batchId());

        assertThat(result.leasesPosted()).isEqualTo(1);
        assertThat(result.chequesCleared()).isEqualTo(1);     // the sample's first cheque is CLEARED
        assertThat(result.failures()).isEmpty();
        assertThat(result.recognitionEntriesPosted()).isGreaterThan(0);

        UUID leaseId = batches.leaseIds(summary.batchId()).get(0);
        assertThat(leaseRepo.findById(leaseId).orElseThrow().getStatus()).isEqualTo(LeaseStatus.ACTIVE);

        List<JournalEntry> journals = entries.findByImportBatchIdOrderByCreatedAtAsc(summary.batchId());
        assertThat(journals).isNotEmpty()
                .allSatisfy(e -> assertThat(e.getImportBatchId()).isEqualTo(summary.batchId()));
        assertThat(journals).extracting(JournalEntry::getDocType)
                .contains(JournalDocType.TCO, JournalDocType.PDR, JournalDocType.CRT, JournalDocType.CIL);
        assertThat(journals).filteredOn(e -> e.getDocType() == JournalDocType.TCO)
                .singleElement()
                .satisfies(e -> assertThat(e.getEntryDate()).isEqualTo(LocalDate.of(2026, 9, 11)));
        assertThat(batches.get(summary.batchId()).getStatus()).isEqualTo(ImportBatchStatus.POSTED);
    }

    /** No CIL may be dated on or after the cut-over: the client's live books start there. */
    @Test
    void recognitionCatchUpStopsTheDayBeforeTheBooksOpen() {
        var summary = importTheTemplate();
        postService.post(summary.batchId());
        assertThat(entries.findByImportBatchIdOrderByCreatedAtAsc(summary.batchId()))
                .filteredOn(e -> e.getDocType() == JournalDocType.CIL)
                .allSatisfy(e -> assertThat(e.getEntryDate()).isBefore(LocalDate.of(2026, 10, 1)));
    }

    /** A bounced cheque replays as CBR on its bounced date, not as a clear. */
    @Test
    void aBouncedChequeReplaysAsCbr() {
        var summary = importTemplateWithSecondChequeBounced("2026-09-28");
        var result = postService.post(summary.batchId());
        assertThat(result.chequesBounced()).isEqualTo(1);
        assertThat(entries.findByImportBatchIdOrderByCreatedAtAsc(summary.batchId()))
                .filteredOn(e -> e.getDocType() == JournalDocType.CBR)
                .singleElement()
                .satisfies(e -> assertThat(e.getEntryDate()).isEqualTo(LocalDate.of(2026, 9, 28)));
    }

    /** The whole round trip: import, post, reverse — and the trial balance is flat again. */
    @Test
    void postingThenReversingTheBatchLeavesAFlatLedgerAndDraftLeases() {
        var summary = importTheTemplate();
        postService.post(summary.batchId());
        batches.reverse(summary.batchId(), LocalDate.of(2026, 9, 30), "re-import");

        BigDecimal net = ledger.trialBalance(LocalDate.of(2026, 9, 30), null).stream()
                .map(r -> r.balance()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(net).isEqualByComparingTo("0.00");
        assertThat(ledger.trialBalance(LocalDate.of(2026, 9, 30), null))
                .allSatisfy(r -> assertThat(r.balance()).isEqualByComparingTo("0.00"));
        UUID leaseId = batches.leaseIds(summary.batchId()).get(0);
        assertThat(leaseRepo.findById(leaseId).orElseThrow().getStatus()).isEqualTo(LeaseStatus.DRAFT);
    }

    @Test
    void postingAnAlreadyPostedBatchIsRejected() {
        var summary = importTheTemplate();
        postService.post(summary.batchId());
        assertThatThrownBy(() -> postService.post(summary.batchId()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("POSTED");
    }

    /** One bad contract must not abort the run — it is reported and the rest post. */
    @Test
    void aLeaseThatFailsToPostIsReportedAndTheOthersStillPost() {
        var summary = importTwoContractsOneWithAnUnmappedRole();
        var result = postService.post(summary.batchId());
        assertThat(result.leasesPosted()).isEqualTo(1);
        assertThat(result.failures()).singleElement()
                .satisfies(f -> assertThat(f.getMessage()).contains("RENT_RECEIVABLE"));
        assertThat(batches.get(summary.batchId()).getStatus()).isEqualTo(ImportBatchStatus.POSTED);
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.cutover.ContractImportPostIT'`
Expected: FAIL — `ContractImportPostService` does not exist.

- [ ] **Step 3: Add the `importBatchId` overloads to Plans 2/3's services**

Journal entries are immutable after insert (Plan 1's `trg_journal_entries_immutable`), so the batch id cannot be stamped on afterwards — it has to be passed down into the `PostingRequest`. Each change is mechanical: add a trailing parameter, thread it into `new PostingRequest(..., importBatchId, lines)`, and keep the old arity as a delegate.

`core/service/lease/LeasePostingService.java`:

```java
    /** @param importBatchId non-null only for a cut-over import; it exempts the entry from the period lock. */
    @Transactional
    public PostLeaseResponse post(UUID leaseId, UUID importBatchId) {
        … existing body, with every `new PostingRequest(...)` / `PostingRequest.ofPairs(...)`
          passing importBatchId in place of the null it passes today …
    }

    @Transactional
    public PostLeaseResponse post(UUID leaseId) { return post(leaseId, null); }
```

`core/service/cheque/ChequeService.java` — the same treatment for the three transitions the import replays:

```java
    @Transactional public ChequeDTO deposit(UUID chequeId, ChequeActionRequest r) { return deposit(chequeId, r, null); }
    @Transactional public ChequeDTO deposit(UUID chequeId, ChequeActionRequest r, UUID importBatchId) { … }  // no journal

    @Transactional public ChequeDTO clear(UUID chequeId, ChequeActionRequest r) { return clear(chequeId, r, null); }
    @Transactional public ChequeDTO clear(UUID chequeId, ChequeActionRequest r, UUID importBatchId) { … }    // posts CRT

    @Transactional public ChequeDTO bounce(UUID chequeId, ChequeActionRequest r) { return bounce(chequeId, r, null); }
    @Transactional public ChequeDTO bounce(UUID chequeId, ChequeActionRequest r, UUID importBatchId) { … }   // posts CBR
```

`core/service/recognition/RecognitionService.java`:

```java
    @Transactional public RecognitionRunResult runTo(LocalDate to, boolean preview) { return runTo(to, preview, null); }
    @Transactional public RecognitionRunResult runTo(LocalDate to, boolean preview, UUID importBatchId) { … }
```

`core/service/LeaseService.java` — **new method.** Plan 2 has `terminateLease`, which posts its own journals and is not an undo; reversing an import has to leave the lease exactly as the importer created it, so this is a distinct path:

```java
public class LeaseService implements com.datagami.rentaxis.core.service.cutover.LeaseReverter {
    …
    /**
     * Put a posted lease back into DRAFT without posting anything: clears
     * postingJournalId / postedAt / postedBy, releases unit.currentLeaseId, sets every
     * cheque on the lease back to DRAFT, and cancels its recognition entries.
     *
     * <p>This is the undo path for a reversed import batch (accounting v2 plan 4) and
     * nothing else — the journals were already reversed by ImportBatchService before
     * this is called, so it must NOT post. Deliberately not exposed over HTTP.
     */
    @Override
    @Transactional
    public void revertToDraft(UUID leaseId) { … }
```

- [ ] **Step 4: Write `ContractImportPostService`**

`core/service/cutover/ContractImportPostService.java`:

```java
package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.api.dto.ImportErrorDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.recognition.RecognitionService;
import com.datagami.rentaxis.core.service.recognition.RecognitionService.RecognitionRunResult;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.ImportBatch;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.ImportBatchStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Step 1 of the cut-over (spec §10.3): turn an imported batch of DRAFT leases into
 * posted contracts, replay each cheque's PACT status onto the register, and catch
 * recognition up to the day before the books open.
 *
 * <p>Every call threads {@code batchId} down into the PostingRequest, which is what
 * exempts these journals from the period lock — all of them are dated before the
 * cut-over by definition — and what makes "Reverse batch" able to find them again.
 *
 * <p>One failing contract does not abort the run. A portfolio import is a hundred
 * contracts at a time and an all-or-nothing failure on contract 87 tells the
 * accountant nothing useful; each failure is reported with its contract number and
 * the batch is still marked POSTED so it can be reversed as a whole.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContractImportPostService {

    private final ImportBatchService batches;
    private final LeaseRepository leases;
    private final ChequeRepository cheques;
    private final LeasePostingService leasePosting;
    private final ChequeService chequeService;
    private final RecognitionService recognition;
    private final TenantFiscalSettingsService fiscal;
    private final JournalEntryRepository journals;

    public record BulkPostResult(UUID batchId, int leasesPosted, int chequesCleared, int chequesBounced,
                                 int chequesDeposited, int recognitionEntriesPosted, int journalsPosted,
                                 List<ImportErrorDTO> failures) {}

    @Transactional
    public BulkPostResult post(UUID batchId) {
        ImportBatch batch = batches.get(batchId);
        if (batch.getStatus() != ImportBatchStatus.DRAFT) {
            throw new BusinessRuleViolationException(
                    "Import batch is " + batch.getStatus() + "; only a DRAFT batch can be posted");
        }
        LocalDate booksStart = fiscal.get().getBooksStartDate();
        if (booksStart == null) {
            throw new BusinessRuleViolationException(
                    "Set the books start date in Settings → Fiscal before posting a cut-over batch");
        }
        LocalDate through = booksStart.minusDays(1);

        List<ImportErrorDTO> failures = new ArrayList<>();
        int leasesPosted = 0, cleared = 0, bounced = 0, deposited = 0;

        for (UUID leaseId : batches.leaseIds(batchId)) {
            Lease lease = leases.findById(leaseId).orElse(null);
            if (lease == null) {
                failures.add(new ImportErrorDTO("Contracts", 0, "ContractNumber",
                        "Lease " + leaseId + " no longer exists"));
                continue;
            }
            try {
                leasePosting.post(leaseId, batchId);
                leasesPosted++;
            } catch (RuntimeException e) {
                failures.add(new ImportErrorDTO("Contracts", 0, "ContractNumber",
                        "Contract " + lease.getContractNumber() + ": " + e.getMessage()));
                continue;
            }

            // Cheque statuses are replayed in seq order so a deposit precedes its clear.
            // Each step goes through ChequeService so it writes its own CRT/CBR; setting the
            // status column directly would leave the register right and the ledger empty.
            for (Cheque c : cheques.findByLease_IdOrderBySeqNoAsc(leaseId)) {
                try {
                    switch (importedStatusOf(c)) {
                        case DEPOSITED -> {
                            chequeService.deposit(c.getId(), action(c.getChequeDate()), batchId);
                            deposited++;
                        }
                        case CLEARED -> {
                            chequeService.deposit(c.getId(), action(c.getChequeDate()), batchId);
                            chequeService.clear(c.getId(), action(c.getClearedAt()), batchId);
                            cleared++;
                        }
                        case BOUNCED -> {
                            chequeService.deposit(c.getId(), action(c.getChequeDate()), batchId);
                            chequeService.bounce(c.getId(), new ChequeActionRequest(
                                    c.getBouncedAt(), "Imported as bounced", null, null), batchId);
                            bounced++;
                        }
                        default -> { /* REGISTERED — the lease post already created the PDR */ }
                    }
                } catch (RuntimeException e) {
                    failures.add(new ImportErrorDTO("Cheques", c.getSeqNo(), "Status",
                            "Contract " + lease.getContractNumber() + " cheque " + c.getSeqNo() + ": " + e.getMessage()));
                }
            }
        }

        // preview = false. runTo does not clamp `to` to today, which is what lets the
        // catch-up stop at the day before the books open rather than running to now.
        RecognitionRunResult recognised = recognition.runTo(through, false, batchId);
        for (String problem : recognised.errors()) {
            failures.add(new ImportErrorDTO("Recognition", 0, "", problem));
        }

        int journalsPosted = journals.findByImportBatchIdOrderByCreatedAtAsc(batchId).size();
        batches.markPosted(batchId, journalsPosted);

        log.info("Bulk-posted import batch {}: {} leases, {} cleared, {} bounced, {} recognition rows, {} journals, {} failures",
                batchId, leasesPosted, cleared, bounced, recognised.posted(), journalsPosted, failures.size());

        return new BulkPostResult(batchId, leasesPosted, cleared, bounced, deposited,
                recognised.posted(), journalsPosted, failures);
    }

    /** The import supplies only a date; notes, failure reason and debit-account override stay null. */
    private ChequeActionRequest action(LocalDate date) {
        return new ChequeActionRequest(date, "Imported from PACT", null, null);
    }

    /**
     * The status the spreadsheet asked for. ContractImportPersistService stores every
     * row as DRAFT and parks the requested status on {@code importedStatus}; the lease
     * post moves DRAFT → REGISTERED, and this replays the rest.
     */
    private ChequeStatus importedStatusOf(Cheque c) {
        return c.getImportedStatus() == null ? ChequeStatus.REGISTERED : c.getImportedStatus();
    }
}
```

> **`Cheque.importedStatus` / `clearedAt` / `bouncedAt`:** `clearedAt` and `bouncedAt` are already on Plan 2's `Cheque` (spec §7.1). `importedStatus` is not. Its column ships in Task 6's `88-cutover-cheque-imported-status` changeset; add the mapping to Plan 2's entity here:
>
> ```java
>     /**
>      * The status this row had in PACT, parked here by the contract import so the bulk
>      * post can replay the transitions through ChequeService rather than writing a
>      * status straight into the column and leaving the ledger without the matching
>      * CRT/CBR. Null for every cheque created in the app.
>      */
>     @Enumerated(EnumType.STRING)
>     @Column(name = "imported_status", length = 20)
>     private ChequeStatus importedStatus;
> ```
>
> and set it in `ContractImportPersistService`'s cheque loop (Task 10 Step 6), next to `c.setStatus(ChequeStatus.DRAFT)`:
>
> ```java
>                 String status = PortfolioImportService.cell(row, qHi, "Status");
>                 c.setImportedStatus(ChequeStatus.valueOf(
>                         status.isEmpty() ? "REGISTERED" : status.toUpperCase(Locale.ROOT)));
>                 String clearedDate = PortfolioImportService.cell(row, qHi, "ClearedDate");
>                 if (!clearedDate.isEmpty()) c.setClearedAt(LocalDate.parse(clearedDate));
>                 String bouncedDate = PortfolioImportService.cell(row, qHi, "BouncedDate");
>                 if (!bouncedDate.isEmpty()) c.setBouncedAt(LocalDate.parse(bouncedDate));
> ```

- [ ] **Step 5: Add the endpoint**

In `api/ImportBatchController.java`:

```java
    private final ContractImportPostService postService;

    @PostMapping("/{id}/post")
    public ResponseEntity<ContractImportPostService.BulkPostResult> post(@PathVariable UUID id) {
        return ResponseEntity.ok(postService.post(id));
    }
```

- [ ] **Step 6: Run the IT to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.cutover.ContractImportPostIT'`
Expected: PASS, 6 tests.

- [ ] **Step 7: Run the full backend suite**

Run: `cd backend && ./gradlew test`
Expected: PASS. Lease, cheque and recognition tests from Plans 2–3 must be unaffected — the new overloads delegate with `null`.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis backend/src/test/java/com/datagami/rentaxis
git commit -m "feat(finance): bulk post a cut-over batch with cheque replay and recognition catch-up

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 12: Web — typed voucher/cut-over API client, i18n, RBAC gating helper

**Files:**
- Create: `web/src/lib/api/vouchers.ts`
- Modify: `web/src/lib/api/ledger.ts` (export three helpers)
- Modify: `web/messages/en.json`, `web/messages/ar.json`
- Test: `web/src/lib/api/__tests__/vouchers.test.ts`

**Interfaces:**
- Consumes: `throwIfNotOk` and `ApiError` from `@/lib/api/facilities`; `hasPermission` and `PERMISSIONS.canPostJournals` from `@/lib/rbac` (Plan 1 Task 11).
- Produces:
  ```ts
  export type VoucherType = "PISR" | "BPV" | "RCP"
  export type VoucherStatus = "DRAFT" | "POSTED" | "REVERSED"
  export type VoucherLine = { lineNo: number; accountId: string; accountCode: string; accountName: string;
                              description: string | null; amount: number; vatRate: number; vatAmount: number;
                              propertyId: string | null; unitId: string | null }
  export type VoucherLineInput = { accountId: string; description?: string; amount: number; vatRate: number;
                                   propertyId?: string | null; unitId?: string | null }
  export type VoucherInput = { docType: VoucherType; docDate: string; vendorId?: string | null;
                               invoiceNumber?: string | null; narration?: string | null;
                               propertyId?: string | null; unitId?: string | null;
                               paymentAccountId?: string | null; chequeNumber?: string | null;
                               chequeDate?: string | null; lines: VoucherLineInput[] }
  export type Voucher = { id; docType; docDate; vendorId; vendorName; invoiceNumber; narration; propertyId;
                          unitId; paymentAccountId; paymentAccountName; chequeNumber; chequeDate; status;
                          journalId; voucherNumber; amendedFromId; netTotal; vatTotal; grossTotal; postedAt }
  export type VoucherDetail = Voucher & { lines: VoucherLine[]; attachments: VoucherAttachment[] }
  export type VoucherAttachment = { id; voucherId; name; fileUrl; fileType; fileSize; uploadedAt }
  export type OpeningBalanceRow = { accountId; code; name; accountType; propertyId; derived;
                                    derivedRole: string | null; enteredDebit: number; enteredCredit: number }
  export type OpeningBalanceGrid = { asOf; posted; journalId; journalNumber; rows: OpeningBalanceRow[];
                                     totalDebit; totalCredit; difference }
  export type SnapshotUploadResult = { stored: number; unmatchedCodes: string[]; problems: string[] }
  export type ReconciliationRow = { accountId: string | null; code; name; derived;
                                    derivedBalance; pactBalance; difference }
  export type ImportBatch = { id; kind; status: "DRAFT"|"POSTED"|"REVERSED"; label; importJobId;
                              leasesImported; journalsPosted; postedAt; reversedAt; createdAt }
  export type BulkPostResult = { batchId; leasesPosted; chequesCleared; chequesBounced; chequesDeposited;
                                 recognitionEntriesPosted; journalsPosted;
                                 failures: { sheet: string; row: number; field: string; message: string }[] }

  export const voucherApi = {
    list(q), get(id), create(body), update(id, body), remove(id), post(id), amend(id, body),
    attachments: { list(voucherId), upload(voucherId, name, file), remove(attachmentId), downloadUrl(attachmentId) },
  }
  export const cutoverApi = {
    openingBalances: { grid(), uploadSnapshot(file), setRow(accountId, debit, credit), post(), reverse(body) },
    reconciliation(),
    batches: { list(), get(id), post(id), reverse(id, body) },
  }
  export function vatOf(amount: number, rate: number): number   // mirrors VoucherMath.vat exactly
  ```

- [ ] **Step 1: Write the failing test**

`web/src/lib/api/__tests__/vouchers.test.ts`:

```ts
import { describe, it, expect, vi, beforeEach } from "vitest";
import { voucherApi, cutoverApi, vatOf } from "../vouchers";

describe("vatOf", () => {
  it("matches the backend's per-line HALF_UP rounding", () => {
    expect(vatOf(1000, 5)).toBe(50);
    expect(vatOf(1234.57, 5)).toBe(61.73);
    expect(vatOf(100.1, 5)).toBe(5.01);   // 5.005 rounds up, as HALF_UP does
    expect(vatOf(1000, 0)).toBe(0);
  });
});

describe("voucherApi", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn(async () =>
      new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } })));
  });

  it("builds the list query and drops empty filters", async () => {
    await voucherApi.list({ docType: "PISR", status: "", page: 0, size: 25 });
    expect(fetch).toHaveBeenCalledWith(
      "/api/proxy/v1/finance/vouchers?docType=PISR&page=0&size=25", expect.anything());
  });

  it("posts a voucher by id", async () => {
    await voucherApi.post("abc");
    expect(fetch).toHaveBeenCalledWith("/api/proxy/v1/finance/vouchers/abc/post",
      expect.objectContaining({ method: "POST" }));
  });

  it("sends an attachment as multipart without a JSON content-type", async () => {
    const file = new File(["x"], "invoice.pdf", { type: "application/pdf" });
    await voucherApi.attachments.upload("abc", "Vendor invoice", file);
    const [url, init] = (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.at(-1)!;
    expect(url).toBe("/api/proxy/v1/finance/vouchers/abc/attachments");
    expect((init as RequestInit).body).toBeInstanceOf(FormData);
    expect((init as RequestInit).headers).toBeUndefined();
  });
});

describe("cutoverApi", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn(async () =>
      new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } })));
  });

  it("reads the opening-balance grid", async () => {
    await cutoverApi.openingBalances.grid();
    expect(fetch).toHaveBeenCalledWith("/api/proxy/v1/finance/opening-balances", expect.anything());
  });

  it("reverses an import batch with a date and a reason", async () => {
    await cutoverApi.batches.reverse("b1", { date: "2026-09-30", reason: "re-import" });
    const [url, init] = (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.at(-1)!;
    expect(url).toBe("/api/proxy/v1/finance/import-batches/b1/reverse");
    expect(JSON.parse((init as RequestInit).body as string))
      .toEqual({ date: "2026-09-30", reason: "re-import" });
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd web && npx vitest run src/lib/api/__tests__/vouchers.test.ts`
Expected: FAIL — cannot resolve `../vouchers`.

- [ ] **Step 3: Export the three helpers from `ledger.ts`**

In `web/src/lib/api/ledger.ts`, change three declarations from local to exported so this plan's client reuses them instead of copying twenty lines of query-string and fetch plumbing:

```ts
export function qs(params: Record<string, string | number | boolean | string[] | undefined | null>): string { … }
export async function apiGet<T>(path: string): Promise<T> { … }          // was: async function get<T>
export async function apiSend<T>(method: "POST" | "PUT" | "DELETE", path: string, body?: unknown): Promise<T> { … }  // was: send
```

Update `ledger.ts`'s own call sites (`get(` → `apiGet(`, `send(` → `apiSend(`) and re-run Plan 1's test: `cd web && npx vitest run src/lib/api/__tests__/ledger.test.ts`.

- [ ] **Step 4: Write `vouchers.ts`**

```ts
import { apiGet, apiSend, qs } from "@/lib/api/ledger";
import { throwIfNotOk } from "@/lib/api/facilities";

const BASE = "/api/proxy/v1";

// ---- types: write out every type from the Interfaces block in full here ----

/**
 * The backend computes VAT per line with HALF_UP to two decimals
 * (VoucherMath.vat). The form previews the same number before the round trip, so
 * the total the accountant sees while typing is the total that posts. Number
 * arithmetic is safe at these magnitudes — an AED invoice line is far below
 * 2^53 fils — but the rounding has to match exactly, hence the epsilon nudge for
 * the .005 boundary that binary floating point would otherwise round down.
 */
export function vatOf(amount: number, rate: number): number {
  if (!amount || !rate) return 0;
  return Math.round((amount * rate) / 100 * 100 + Number.EPSILON * 100) / 100;
}

export const voucherApi = {
  list: (q: { docType?: VoucherType | ""; status?: VoucherStatus | ""; vendorId?: string; propertyId?: string;
              from?: string; to?: string; page: number; size: number }) =>
    apiGet<Page<Voucher>>(`/finance/vouchers${qs(q)}`),
  get: (id: string) => apiGet<VoucherDetail>(`/finance/vouchers/${id}`),
  create: (body: VoucherInput) => apiSend<VoucherDetail>("POST", "/finance/vouchers", body),
  update: (id: string, body: VoucherInput) => apiSend<VoucherDetail>("PUT", `/finance/vouchers/${id}`, body),
  remove: (id: string) => apiSend<void>("DELETE", `/finance/vouchers/${id}`),
  post: (id: string) => apiSend<VoucherDetail>("POST", `/finance/vouchers/${id}/post`),
  amend: (id: string, body: { reversalDate: string; reason: string; replacement: VoucherInput }) =>
    apiSend<VoucherDetail>("POST", `/finance/vouchers/${id}/amend`, body),
  attachments: {
    list: (voucherId: string) => apiGet<VoucherAttachment[]>(`/finance/vouchers/${voucherId}/attachments`),
    upload: async (voucherId: string, name: string, file: File) => {
      const fd = new FormData();
      fd.append("name", name);
      fd.append("file", file);
      // No Content-Type header: the browser must set the multipart boundary itself.
      const res = await fetch(`${BASE}/finance/vouchers/${voucherId}/attachments`, { method: "POST", body: fd });
      await throwIfNotOk(res);
      return res.json() as Promise<VoucherAttachment>;
    },
    remove: (attachmentId: string) => apiSend<void>("DELETE", `/finance/vouchers/attachments/${attachmentId}`),
    downloadUrl: (attachmentId: string) => `${BASE}/finance/vouchers/attachments/${attachmentId}/download`,
  },
};

export const cutoverApi = {
  openingBalances: {
    grid: () => apiGet<OpeningBalanceGrid>("/finance/opening-balances"),
    uploadSnapshot: async (file: File) => {
      const fd = new FormData();
      fd.append("file", file);
      const res = await fetch(`${BASE}/finance/opening-balances/snapshot`, { method: "POST", body: fd });
      await throwIfNotOk(res);
      return res.json() as Promise<SnapshotUploadResult>;
    },
    setRow: (accountId: string, debit: number, credit: number) =>
      apiSend<void>("PUT", `/finance/opening-balances/${accountId}`, { debit, credit }),
    post: () => apiSend<{ id: string; entryNumber: string; entryDate: string }>("POST", "/finance/opening-balances/post"),
    reverse: (body: { date: string; reason: string }) =>
      apiSend<{ id: string; entryNumber: string; entryDate: string }>("POST", "/finance/opening-balances/reverse", body),
  },
  reconciliation: () => apiGet<ReconciliationRow[]>("/finance/reconciliation"),
  batches: {
    list: () => apiGet<ImportBatch[]>("/finance/import-batches"),
    get: (id: string) => apiGet<ImportBatch>(`/finance/import-batches/${id}`),
    post: (id: string) => apiSend<BulkPostResult>("POST", `/finance/import-batches/${id}/post`),
    reverse: (id: string, body: { date: string; reason: string }) =>
      apiSend<ImportBatch>("POST", `/finance/import-batches/${id}/reverse`, body),
  },
};
```

- [ ] **Step 5: Add the `Vouchers` i18n namespace**

Add to `web/messages/en.json` (and the Arabic equivalents to `web/messages/ar.json`):

```json
"Vouchers": {
  "vouchers": "Vouchers",
  "purchaseInvoice": "Purchase / Service Invoice",
  "paymentVoucher": "Bank / Cash Payment Voucher",
  "newPurchaseInvoice": "New purchase invoice",
  "newPaymentVoucher": "New payment voucher",
  "vendor": "Vendor",
  "selectVendor": "Select a vendor",
  "invoiceNumber": "Invoice no",
  "paymentAccount": "Pay from",
  "selectPaymentAccount": "Select a bank or cash account",
  "chequeNumber": "Cheque no",
  "chequeDate": "Cheque date",
  "property": "Property",
  "allProperties": "All properties",
  "description": "Description",
  "amount": "Amount",
  "vatRate": "VAT %",
  "vatAmount": "VAT",
  "lineTotal": "Line total",
  "netTotal": "Net total",
  "vatTotal": "VAT total",
  "grossTotal": "Total",
  "attachments": "Attachments",
  "attachmentName": "Document name",
  "addAttachment": "Attach a document",
  "attachmentTypes": "PDF, JPEG, PNG, HEIC or WEBP, up to 25MB",
  "removeAttachment": "Remove",
  "saveDraft": "Save draft",
  "post": "Post",
  "amend": "Amend",
  "amendReason": "Why is this being amended?",
  "amendDate": "Reversal date",
  "amendHint": "Posting an amendment reverses this voucher's journal and posts a new one. The original stays in the ledger.",
  "draftSaved": "Draft saved",
  "voucherPosted": "Voucher {number} posted",
  "deleteDraft": "Delete draft",
  "confirmDeleteDraft": "Delete this draft voucher? Nothing has been posted, so nothing is removed from the ledger.",
  "noVouchers": "No vouchers yet",
  "createFirstVoucher": "Record a purchase invoice or a payment to get started",
  "type": "Type",
  "allTypes": "All types",
  "allStatuses": "All statuses",
  "viewJournal": "View journal",

  "openingBalances": "Opening Balances",
  "openingBalancesDesc": "Balances as at {date}, the day before your books start. Accounts filled by the contract import are shown but cannot be edited.",
  "uploadTrialBalance": "Upload PACT trial balance",
  "trialBalanceFormat": "CSV with four columns: account code, name, debit, credit",
  "uploadedRows": "{n} rows read",
  "unmatchedCodes": "{n} account codes are not in your chart of accounts: {codes}",
  "uploadProblems": "{n} rows could not be read",
  "derived": "From contract import",
  "manual": "Manual",
  "postOpeningBalances": "Post opening balances",
  "reverseOpeningBalances": "Reverse opening balances",
  "openingBalancesPosted": "Opening balances posted as {number}",
  "alreadyPosted": "Opening balances were posted as {number}. Reverse that journal before posting again.",
  "difference": "Difference",
  "differenceGoesToEquity": "The difference posts to the opening-balance difference account so the books open balanced.",
  "confirmPostOpeningBalances": "Post the opening balances dated {date}? This can be reversed, but not edited.",
  "confirmReverseOpeningBalances": "Reverse opening-balance journal {number}? A mirror entry is posted; the original stays in the ledger.",

  "reconciliation": "Reconciliation",
  "reconciliationDesc": "Our balance as at {date} against the PACT trial balance you uploaded.",
  "derivedBalance": "Our balance",
  "pactBalance": "PACT",
  "notInOurChart": "Not in our chart of accounts",
  "reconciled": "Reconciled",
  "outOfBalance": "{n} accounts differ",

  "importBatches": "Import Batches",
  "importBatchesDesc": "Each cut-over import is one batch. Reversing a batch reverses every journal it wrote and returns its contracts to draft.",
  "batchLabel": "Batch",
  "leasesImported": "Contracts",
  "journalsPosted": "Journals",
  "bulkPost": "Bulk post",
  "reverseBatch": "Reverse batch",
  "confirmBulkPost": "Post all {n} contracts in this batch? Journals are dated on each contract's own date and recognition catches up to {date}.",
  "confirmReverseBatch": "Reverse this batch? {n} journals are reversed and {m} contracts go back to draft.",
  "batchPosted": "Posted {n} contracts and {m} journals",
  "batchReversed": "Batch reversed",
  "batchFailures": "{n} contracts could not be posted",
  "noBatches": "No import batches yet",
  "downloadCutoverTemplate": "Download the contract-import template",

  "notAllowed": "You do not have permission to view this page"
}
```

- [ ] **Step 6: Run the tests and type check**

Run: `cd web && npx vitest run src/lib/api/__tests__/vouchers.test.ts src/lib/api/__tests__/ledger.test.ts && npx tsc --noEmit`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add web/src/lib web/messages
git commit -m "feat(web): typed voucher and cut-over API client, Vouchers i18n namespace

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 13: Web — the shared voucher form and the Purchase/Service Invoice page

**Files:**
- Create: `web/src/components/finance/VoucherForm.tsx`
- Create: `web/src/app/[locale]/dashboard/finance/vouchers/purchase-invoice/page.tsx`
- Test: `web/src/components/finance/__tests__/VoucherForm.test.tsx`

**Interfaces:**
- Consumes: `voucherApi`, `vatOf`, and the types from Task 12; `AccountPicker` (Plan 1 Task 12, props `{ value, onChange, accountType?, leafOnly?, placeholder?, autoFocus? }`); `fmtAmount` from `@/lib/api/ledger`; `ApiError`/`throwIfNotOk` from `@/lib/api/facilities`; `LoadErrorBanner`, `ConfirmDialog`.
- Produces:
  ```tsx
  export default function VoucherForm({ type, voucherId, onPosted }: {
    type: "PISR" | "BPV";
    voucherId?: string;                       // edit an existing draft
    onPosted?: (v: VoucherDetail) => void;
  })
  ```
  Renders a header block, a lines grid, a live totals footer and (PISR only) an attachments panel. The **Post** button is disabled until every line has an account and an amount greater than zero.

- [ ] **Step 1: Write the failing component test**

`web/src/components/finance/__tests__/VoucherForm.test.tsx`:

```tsx
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import VoucherForm from "../VoucherForm";

vi.mock("@/components/finance/AccountPicker", () => ({
  __esModule: true,
  default: ({ value, onChange }: { value: string | null; onChange: (id: string) => void }) => (
    <button data-testid="account-picker" onClick={() => onChange("acct-1")}>{value ?? "pick"}</button>
  ),
  invalidateAccounts: () => {},
}));

const post = vi.fn();
vi.mock("@/lib/api/vouchers", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/vouchers")>();
  return {
    ...actual,
    voucherApi: {
      list: vi.fn(), get: vi.fn(), update: vi.fn(), remove: vi.fn(), amend: vi.fn(),
      create: vi.fn(async () => ({ id: "v1", status: "DRAFT", lines: [], attachments: [] })),
      post,
      attachments: { list: vi.fn(async () => []), upload: vi.fn(), remove: vi.fn(), downloadUrl: () => "" },
    },
  };
});

function renderForm(type: "PISR" | "BPV" = "PISR") {
  return render(
    <NextIntlClientProvider locale="en" messages={en}>
      <VoucherForm type={type} />
    </NextIntlClientProvider>
  );
}

describe("VoucherForm", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.stubGlobal("fetch", vi.fn(async () =>
      new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } })));
  });

  it("shows zero totals before anything is entered", async () => {
    renderForm();
    expect(await screen.findByTestId("net-total")).toHaveTextContent("0.00");
    expect(screen.getByTestId("vat-total")).toHaveTextContent("0.00");
    expect(screen.getByTestId("gross-total")).toHaveTextContent("0.00");
  });

  it("computes per-line VAT and the header totals as the user types", async () => {
    renderForm();
    fireEvent.click(screen.getAllByTestId("account-picker")[0]);
    fireEvent.change(screen.getByTestId("line-amount-0"), { target: { value: "2000" } });
    fireEvent.change(screen.getByTestId("line-vat-rate-0"), { target: { value: "5" } });

    await waitFor(() => expect(screen.getByTestId("line-vat-amount-0")).toHaveTextContent("100.00"));
    expect(screen.getByTestId("net-total")).toHaveTextContent("2,000.00");
    expect(screen.getByTestId("vat-total")).toHaveTextContent("100.00");
    expect(screen.getByTestId("gross-total")).toHaveTextContent("2,100.00");
  });

  it("sums per-line VAT rather than taking VAT of the total", async () => {
    renderForm();
    for (const i of [0, 1, 2]) {
      if (i > 0) fireEvent.click(screen.getByTestId("add-line"));
      fireEvent.click(screen.getAllByTestId("account-picker")[i]);
      fireEvent.change(screen.getByTestId(`line-amount-${i}`), { target: { value: "100.10" } });
      fireEvent.change(screen.getByTestId(`line-vat-rate-${i}`), { target: { value: "5" } });
    }
    // 3 x round(5.005) = 3 x 5.01 = 15.03, not 5% of 300.30 = 15.02
    await waitFor(() => expect(screen.getByTestId("vat-total")).toHaveTextContent("15.03"));
    expect(screen.getByTestId("gross-total")).toHaveTextContent("315.33");
  });

  it("keeps Post disabled until every line has an account and an amount", async () => {
    renderForm();
    expect(await screen.findByTestId("post-voucher")).toBeDisabled();
    fireEvent.click(screen.getAllByTestId("account-picker")[0]);
    expect(screen.getByTestId("post-voucher")).toBeDisabled();
    fireEvent.change(screen.getByTestId("line-amount-0"), { target: { value: "2000" } });
    await waitFor(() => expect(screen.getByTestId("post-voucher")).toBeEnabled());
  });

  it("hides the VAT columns on a payment voucher", async () => {
    renderForm("BPV");
    expect(await screen.findByTestId("net-total")).toBeInTheDocument();
    expect(screen.queryByTestId("line-vat-rate-0")).not.toBeInTheDocument();
    expect(screen.queryByTestId("vat-total")).not.toBeInTheDocument();
  });

  it("removes a line and recomputes the totals", async () => {
    renderForm();
    fireEvent.click(screen.getAllByTestId("account-picker")[0]);
    fireEvent.change(screen.getByTestId("line-amount-0"), { target: { value: "500" } });
    fireEvent.click(screen.getByTestId("add-line"));
    fireEvent.click(screen.getAllByTestId("account-picker")[1]);
    fireEvent.change(screen.getByTestId("line-amount-1"), { target: { value: "300" } });
    await waitFor(() => expect(screen.getByTestId("net-total")).toHaveTextContent("800.00"));
    fireEvent.click(screen.getByTestId("remove-line-1"));
    await waitFor(() => expect(screen.getByTestId("net-total")).toHaveTextContent("500.00"));
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd web && npx vitest run src/components/finance/__tests__/VoucherForm.test.tsx`
Expected: FAIL — cannot resolve `../VoucherForm`.

- [ ] **Step 3: Write `VoucherForm.tsx`**

```tsx
"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useTranslations } from "next-intl";
import { Loader2, Plus, Trash2, Paperclip, Upload } from "lucide-react";
import AccountPicker from "@/components/finance/AccountPicker";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { ApiError } from "@/lib/api/facilities";
import { fmtAmount } from "@/lib/api/ledger";
import {
  voucherApi, vatOf,
  type VoucherDetail, type VoucherAttachment, type VoucherInput, type VoucherLineInput,
} from "@/lib/api/vouchers";

type DraftLine = { key: string; accountId: string | null; description: string; amount: string; vatRate: string };

const newLine = (): DraftLine => ({
  key: Math.random().toString(36).slice(2), accountId: null, description: "", amount: "", vatRate: "0",
});

const today = () => new Date().toISOString().slice(0, 10);
const num = (s: string) => { const n = Number.parseFloat(s); return Number.isFinite(n) ? n : 0; };

export default function VoucherForm({ type, voucherId, onPosted }: {
  type: "PISR" | "BPV";
  voucherId?: string;
  onPosted?: (v: VoucherDetail) => void;
}) {
  const t = useTranslations("Vouchers");
  const tLedger = useTranslations("Ledger");

  const [docDate, setDocDate] = useState(today());
  const [vendorId, setVendorId] = useState<string>("");
  const [vendors, setVendors] = useState<{ id: string; nameEn: string }[]>([]);
  const [invoiceNumber, setInvoiceNumber] = useState("");
  const [narration, setNarration] = useState("");
  const [propertyId, setPropertyId] = useState<string>("");
  const [properties, setProperties] = useState<{ id: string; nameEn: string }[]>([]);
  const [paymentAccountId, setPaymentAccountId] = useState<string | null>(null);
  const [chequeNumber, setChequeNumber] = useState("");
  const [chequeDate, setChequeDate] = useState("");
  const [lines, setLines] = useState<DraftLine[]>([newLine()]);
  const [attachments, setAttachments] = useState<VoucherAttachment[]>([]);
  const [savedId, setSavedId] = useState<string | undefined>(voucherId);
  const [busy, setBusy] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);

  const withVat = type === "PISR";

  useEffect(() => {
    // Both lists are small master tables; the vendors page fetches them the same way.
    fetch("/api/proxy/v1/vendors").then(r => r.ok ? r.json() : []).then(setVendors).catch(() => setVendors([]));
    fetch("/api/proxy/v1/properties").then(r => r.ok ? r.json() : []).then(setProperties).catch(() => setProperties([]));
  }, []);

  useEffect(() => {
    if (!voucherId) return;
    voucherApi.get(voucherId).then(v => {
      setDocDate(v.docDate);
      setVendorId(v.vendorId ?? "");
      setInvoiceNumber(v.invoiceNumber ?? "");
      setNarration(v.narration ?? "");
      setPropertyId(v.propertyId ?? "");
      setPaymentAccountId(v.paymentAccountId);
      setChequeNumber(v.chequeNumber ?? "");
      setChequeDate(v.chequeDate ?? "");
      setAttachments(v.attachments);
      setLines(v.lines.length ? v.lines.map(l => ({
        key: String(l.lineNo), accountId: l.accountId, description: l.description ?? "",
        amount: String(l.amount), vatRate: String(l.vatRate),
      })) : [newLine()]);
    }).catch(() => setLoadError(t("notAllowed")));
  }, [voucherId, t]);

  const computed = useMemo(() => {
    const perLine = lines.map(l => {
      const amount = num(l.amount);
      const vat = withVat ? vatOf(amount, num(l.vatRate)) : 0;
      return { amount, vat, total: amount + vat };
    });
    return {
      perLine,
      net: perLine.reduce((s, l) => s + l.amount, 0),
      vat: perLine.reduce((s, l) => s + l.vat, 0),
      gross: perLine.reduce((s, l) => s + l.total, 0),
    };
  }, [lines, withVat]);

  const complete = lines.length > 0
    && lines.every(l => l.accountId && num(l.amount) > 0)
    && (type === "PISR" ? !!vendorId : !!paymentAccountId);

  const body = useCallback((): VoucherInput => ({
    docType: type,
    docDate,
    vendorId: type === "PISR" ? vendorId : null,
    invoiceNumber: type === "PISR" ? invoiceNumber || null : null,
    narration: narration || null,
    propertyId: propertyId || null,
    paymentAccountId: type === "BPV" ? paymentAccountId : null,
    chequeNumber: type === "BPV" ? chequeNumber || null : null,
    chequeDate: type === "BPV" ? chequeDate || null : null,
    lines: lines.map<VoucherLineInput>(l => ({
      accountId: l.accountId as string,
      description: l.description || undefined,
      amount: num(l.amount),
      vatRate: withVat ? num(l.vatRate) : 0,
      propertyId: propertyId || null,
    })),
  }), [type, docDate, vendorId, invoiceNumber, narration, propertyId, paymentAccountId,
       chequeNumber, chequeDate, lines, withVat]);

  const run = async (fn: () => Promise<void>) => {
    setBusy(true); setFormError(null);
    try { await fn(); }
    catch (e) { setFormError(e instanceof ApiError ? e.message : String(e)); }
    finally { setBusy(false); }
  };

  const saveDraft = () => run(async () => {
    const saved = savedId ? await voucherApi.update(savedId, body()) : await voucherApi.create(body());
    setSavedId(saved.id);
  });

  const postVoucher = () => run(async () => {
    const saved = savedId ? await voucherApi.update(savedId, body()) : await voucherApi.create(body());
    const posted = await voucherApi.post(saved.id);
    setSavedId(posted.id);
    onPosted?.(posted);
  });

  const uploadAttachment = (file: File) => run(async () => {
    // An attachment needs a voucher to hang off, so save the draft first if it is new.
    const id = savedId ?? (await voucherApi.create(body())).id;
    setSavedId(id);
    const a = await voucherApi.attachments.upload(id, file.name, file);
    setAttachments(prev => [...prev, a]);
  });

  return (
    <div className="space-y-6">
      {loadError && <LoadErrorBanner message={loadError} onRetry={() => location.reload()} />}

      {/* Header */}
      <div className="bg-surface border border-border rounded-xl p-5 grid grid-cols-1 md:grid-cols-3 gap-4">
        <label className="text-xs font-semibold text-muted">
          {tLedger("docDate")}
          <input type="date" data-testid="doc-date" value={docDate} onChange={e => setDocDate(e.target.value)}
                 className="mt-1 w-full bg-input border border-border rounded-lg px-3 py-2 text-xs text-foreground" />
        </label>

        {type === "PISR" ? (
          <>
            <label className="text-xs font-semibold text-muted">
              {t("vendor")}
              <select data-testid="vendor" value={vendorId} onChange={e => setVendorId(e.target.value)}
                      className="mt-1 w-full bg-input border border-border rounded-lg px-3 py-2 text-xs text-foreground">
                <option value="">{t("selectVendor")}</option>
                {vendors.map(v => <option key={v.id} value={v.id}>{v.nameEn}</option>)}
              </select>
            </label>
            <label className="text-xs font-semibold text-muted">
              {t("invoiceNumber")}
              <input data-testid="invoice-number" value={invoiceNumber} onChange={e => setInvoiceNumber(e.target.value)}
                     className="mt-1 w-full bg-input border border-border rounded-lg px-3 py-2 text-xs text-foreground" />
            </label>
          </>
        ) : (
          <>
            <div className="text-xs font-semibold text-muted">
              {t("paymentAccount")}
              <div className="mt-1">
                <AccountPicker value={paymentAccountId} onChange={setPaymentAccountId}
                               placeholder={t("selectPaymentAccount")} />
              </div>
            </div>
            <label className="text-xs font-semibold text-muted">
              {t("chequeNumber")}
              <input data-testid="cheque-number" value={chequeNumber} onChange={e => setChequeNumber(e.target.value)}
                     className="mt-1 w-full bg-input border border-border rounded-lg px-3 py-2 text-xs text-foreground" />
            </label>
            <label className="text-xs font-semibold text-muted">
              {t("chequeDate")}
              <input type="date" data-testid="cheque-date" value={chequeDate} onChange={e => setChequeDate(e.target.value)}
                     className="mt-1 w-full bg-input border border-border rounded-lg px-3 py-2 text-xs text-foreground" />
            </label>
          </>
        )}

        <label className="text-xs font-semibold text-muted">
          {t("property")}
          <select data-testid="property" value={propertyId} onChange={e => setPropertyId(e.target.value)}
                  className="mt-1 w-full bg-input border border-border rounded-lg px-3 py-2 text-xs text-foreground">
            <option value="">{t("allProperties")}</option>
            {properties.map(p => <option key={p.id} value={p.id}>{p.nameEn}</option>)}
          </select>
        </label>

        <label className="text-xs font-semibold text-muted md:col-span-2">
          {tLedger("narration")}
          <input data-testid="narration" value={narration} onChange={e => setNarration(e.target.value)}
                 className="mt-1 w-full bg-input border border-border rounded-lg px-3 py-2 text-xs text-foreground" />
        </label>
      </div>

      {/* Lines */}
      <div className="bg-surface border border-border rounded-xl overflow-hidden">
        <table className="w-full">
          <thead>
            <tr className="bg-input/50">
              <th className="text-left px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider">{tLedger("account")}</th>
              <th className="text-left px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("description")}</th>
              <th className="text-right px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("amount")}</th>
              {withVat && <th className="text-right px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("vatRate")}</th>}
              {withVat && <th className="text-right px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("vatAmount")}</th>}
              <th className="text-right px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("lineTotal")}</th>
              <th />
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {lines.map((l, i) => (
              <tr key={l.key}>
                <td className="px-4 py-2 min-w-[240px]">
                  <AccountPicker value={l.accountId} accountType={withVat ? "EXPENSE" : undefined}
                    onChange={id => setLines(ls => ls.map((x, j) => j === i ? { ...x, accountId: id } : x))} />
                </td>
                <td className="px-4 py-2">
                  <input data-testid={`line-description-${i}`} value={l.description}
                         onChange={e => setLines(ls => ls.map((x, j) => j === i ? { ...x, description: e.target.value } : x))}
                         className="w-full bg-input border border-border rounded-lg px-3 py-2 text-xs text-foreground" />
                </td>
                <td className="px-4 py-2 text-right">
                  <input data-testid={`line-amount-${i}`} inputMode="decimal" value={l.amount}
                         onChange={e => setLines(ls => ls.map((x, j) => j === i ? { ...x, amount: e.target.value } : x))}
                         className="w-32 text-right bg-input border border-border rounded-lg px-3 py-2 text-xs text-foreground" />
                </td>
                {withVat && (
                  <td className="px-4 py-2 text-right">
                    <select data-testid={`line-vat-rate-${i}`} value={l.vatRate}
                            onChange={e => setLines(ls => ls.map((x, j) => j === i ? { ...x, vatRate: e.target.value } : x))}
                            className="w-20 bg-input border border-border rounded-lg px-2 py-2 text-xs text-foreground">
                      <option value="0">0%</option>
                      <option value="5">5%</option>
                    </select>
                  </td>
                )}
                {withVat && (
                  <td data-testid={`line-vat-amount-${i}`} className="px-4 py-2 text-right text-xs font-mono text-muted">
                    {fmtAmount(computed.perLine[i].vat)}
                  </td>
                )}
                <td data-testid={`line-total-${i}`} className="px-4 py-2 text-right text-xs font-mono text-foreground">
                  {fmtAmount(computed.perLine[i].total)}
                </td>
                <td className="px-2 py-2 text-right">
                  <button type="button" data-testid={`remove-line-${i}`} aria-label={tLedger("removeLine")}
                          disabled={lines.length === 1}
                          onClick={() => setLines(ls => ls.filter((_, j) => j !== i))}
                          className="p-2 text-muted hover:text-destructive disabled:opacity-30 cursor-pointer">
                    <Trash2 size={14} />
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="px-4 py-3 border-t border-border">
          <button type="button" data-testid="add-line" onClick={() => setLines(ls => [...ls, newLine()])}
                  className="flex items-center gap-2 text-xs font-semibold text-primary cursor-pointer">
            <Plus size={14} />{tLedger("addLine")}
          </button>
        </div>
      </div>

      {/* Totals */}
      <div className="bg-surface border border-border rounded-xl p-5 flex flex-col items-end gap-1 text-xs">
        <div className="flex gap-8"><span className="text-muted">{t("netTotal")}</span>
          <span data-testid="net-total" className="font-mono w-32 text-right">{fmtAmount(computed.net)}</span></div>
        {withVat && (
          <div className="flex gap-8"><span className="text-muted">{t("vatTotal")}</span>
            <span data-testid="vat-total" className="font-mono w-32 text-right">{fmtAmount(computed.vat)}</span></div>
        )}
        <div className="flex gap-8 font-bold"><span>{t("grossTotal")}</span>
          <span data-testid="gross-total" className="font-mono w-32 text-right">{fmtAmount(computed.gross)}</span></div>
      </div>

      {/* Attachments — invoices carry paper; a payment voucher usually does not, but both allow it. */}
      <div className="bg-surface border border-border rounded-xl p-5">
        <div className="flex items-center justify-between mb-3">
          <h3 className="text-xs font-bold flex items-center gap-2"><Paperclip size={14} />{t("attachments")}</h3>
          <label className="flex items-center gap-2 text-xs font-semibold text-primary cursor-pointer">
            <Upload size={14} />{t("addAttachment")}
            <input type="file" data-testid="attachment-input" className="hidden"
                   accept="application/pdf,image/jpeg,image/png,image/heic,image/webp"
                   onChange={e => { const f = e.target.files?.[0]; if (f) uploadAttachment(f); }} />
          </label>
        </div>
        <p className="text-[10px] text-muted mb-2">{t("attachmentTypes")}</p>
        <ul className="space-y-1">
          {attachments.map(a => (
            <li key={a.id} className="flex items-center justify-between text-xs">
              <a href={voucherApi.attachments.downloadUrl(a.id)} className="text-primary hover:underline">{a.name}</a>
              <button type="button" onClick={() => run(async () => {
                        await voucherApi.attachments.remove(a.id);
                        setAttachments(prev => prev.filter(x => x.id !== a.id));
                      })}
                      className="text-muted hover:text-destructive cursor-pointer">{t("removeAttachment")}</button>
            </li>
          ))}
        </ul>
      </div>

      {formError && (
        <div className="bg-destructive/10 border border-destructive/20 text-destructive rounded-lg px-4 py-3 text-xs">
          {formError}
        </div>
      )}

      <div className="flex items-center justify-end gap-3">
        <button type="button" data-testid="save-draft" disabled={busy} onClick={saveDraft}
                className="px-5 py-2.5 rounded-lg text-xs font-semibold border border-border text-foreground cursor-pointer disabled:opacity-50">
          {busy ? <Loader2 size={14} className="animate-spin" /> : t("saveDraft")}
        </button>
        <button type="button" data-testid="post-voucher" disabled={busy || !complete} onClick={postVoucher}
                className="px-5 py-2.5 rounded-lg text-xs font-semibold bg-primary text-primary-foreground cursor-pointer disabled:opacity-50">
          {t("post")}
        </button>
      </div>
    </div>
  );
}
```

- [ ] **Step 4: Run the component test to verify it passes**

Run: `cd web && npx vitest run src/components/finance/__tests__/VoucherForm.test.tsx`
Expected: PASS, 6 tests.

- [ ] **Step 5: Write the Purchase/Service Invoice page**

`web/src/app/[locale]/dashboard/finance/vouchers/purchase-invoice/page.tsx`:

```tsx
"use client";

import { useSearchParams, useRouter } from "next/navigation";
import { useSession } from "next-auth/react";
import { useTranslations } from "next-intl";
import VoucherForm from "@/components/finance/VoucherForm";
import { hasPermission, type UserRole } from "@/lib/rbac";

export default function PurchaseInvoicePage() {
  const t = useTranslations("Vouchers");
  const router = useRouter();
  const params = useSearchParams();
  const { data: session } = useSession();
  const role = session?.user?.role as UserRole | undefined;

  if (!hasPermission(role, "canPostJournals")) {
    return (
      <div className="bg-surface border border-border rounded-xl p-8 text-center text-sm text-muted">
        {t("notAllowed")}
      </div>
    );
  }

  return (
    <div>
      <div className="mb-8">
        <h1 className="mb-1">{t("purchaseInvoice")}</h1>
        <p className="text-sm text-muted">{t("newPurchaseInvoice")}</p>
      </div>
      <VoucherForm
        type="PISR"
        voucherId={params.get("id") ?? undefined}
        onPosted={() => router.push("/dashboard/finance/vouchers")}
      />
    </div>
  );
}
```

- [ ] **Step 6: Type check and commit**

Run: `cd web && npx tsc --noEmit && npx vitest run src/components/finance`

```bash
git add web/src/components/finance web/src/app
git commit -m "feat(web): purchase/service invoice form with live per-line VAT

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 14: Web — Bank/Cash Payment Voucher page and the voucher list

**Files:**
- Create: `web/src/app/[locale]/dashboard/finance/vouchers/payment/page.tsx`
- Create: `web/src/app/[locale]/dashboard/finance/vouchers/page.tsx`

**Interfaces:**
- Consumes: Task 13's `VoucherForm`; Task 12's `voucherApi`; `Pagination` from `@/components/ui/Pagination`; `LoadErrorBanner`; `fmtAmount`; `hasPermission`.
- Produces: no new exports — two pages.

- [ ] **Step 1: Write the payment page**

`web/src/app/[locale]/dashboard/finance/vouchers/payment/page.tsx` — identical to the purchase-invoice page with `type="BPV"` and the `paymentVoucher` / `newPaymentVoucher` keys:

```tsx
"use client";

import { useSearchParams, useRouter } from "next/navigation";
import { useSession } from "next-auth/react";
import { useTranslations } from "next-intl";
import VoucherForm from "@/components/finance/VoucherForm";
import { hasPermission, type UserRole } from "@/lib/rbac";

export default function PaymentVoucherPage() {
  const t = useTranslations("Vouchers");
  const router = useRouter();
  const params = useSearchParams();
  const { data: session } = useSession();
  const role = session?.user?.role as UserRole | undefined;

  if (!hasPermission(role, "canPostJournals")) {
    return (
      <div className="bg-surface border border-border rounded-xl p-8 text-center text-sm text-muted">
        {t("notAllowed")}
      </div>
    );
  }

  return (
    <div>
      <div className="mb-8">
        <h1 className="mb-1">{t("paymentVoucher")}</h1>
        <p className="text-sm text-muted">{t("newPaymentVoucher")}</p>
      </div>
      <VoucherForm
        type="BPV"
        voucherId={params.get("id") ?? undefined}
        onPosted={() => router.push("/dashboard/finance/vouchers")}
      />
    </div>
  );
}
```

- [ ] **Step 2: Write the list page**

`web/src/app/[locale]/dashboard/finance/vouchers/page.tsx`:

```tsx
"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useSession } from "next-auth/react";
import { useTranslations } from "next-intl";
import { FileText, Plus, Receipt } from "lucide-react";
import { Pagination } from "@/components/ui/Pagination";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { ApiError } from "@/lib/api/facilities";
import { fmtAmount } from "@/lib/api/ledger";
import { voucherApi, type Voucher, type VoucherType, type VoucherStatus } from "@/lib/api/vouchers";
import { hasPermission, type UserRole } from "@/lib/rbac";

const STATUS_CLASS: Record<VoucherStatus, string> = {
  DRAFT: "bg-input text-muted border-border",
  POSTED: "bg-success/10 text-success border-success/20",
  REVERSED: "bg-input text-muted border-border",
};

export default function VoucherListPage() {
  const t = useTranslations("Vouchers");
  const tLedger = useTranslations("Ledger");
  const { data: session } = useSession();
  const role = session?.user?.role as UserRole | undefined;

  const [docType, setDocType] = useState<VoucherType | "">("");
  const [status, setStatus] = useState<VoucherStatus | "">("");
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(25);
  const [rows, setRows] = useState<Voucher[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    setLoadError(null);
    voucherApi.list({ docType, status, page, size })
      .then(p => { setRows(p.content); setTotal(p.totalElements); })
      .catch(e => setLoadError(e instanceof ApiError ? e.message : String(e)))
      .finally(() => setLoading(false));
  }, [docType, status, page, size]);

  useEffect(load, [load]);

  if (!hasPermission(role, "canPostJournals")) {
    return <div className="bg-surface border border-border rounded-xl p-8 text-center text-sm text-muted">{t("notAllowed")}</div>;
  }

  return (
    <div>
      {loadError && <LoadErrorBanner message={loadError} onRetry={load} />}

      <div className="flex items-center justify-between mb-8">
        <div>
          <h1 className="mb-1">{t("vouchers")}</h1>
          <p className="text-sm text-muted">{t("purchaseInvoice")} · {t("paymentVoucher")}</p>
        </div>
        <div className="flex items-center gap-3">
          <Link href="/dashboard/finance/vouchers/purchase-invoice"
                className="bg-primary text-primary-foreground px-4 py-2 rounded-lg text-xs font-semibold flex items-center gap-2">
            <Plus size={14} />{t("newPurchaseInvoice")}
          </Link>
          <Link href="/dashboard/finance/vouchers/payment"
                className="border border-border px-4 py-2 rounded-lg text-xs font-semibold flex items-center gap-2">
            <Plus size={14} />{t("newPaymentVoucher")}
          </Link>
        </div>
      </div>

      <div className="flex items-center gap-3 mb-4">
        <select value={docType} onChange={e => { setDocType(e.target.value as VoucherType | ""); setPage(0); }}
                className="bg-surface border border-border rounded-lg px-3 py-2 text-xs">
          <option value="">{t("allTypes")}</option>
          <option value="PISR">{t("purchaseInvoice")}</option>
          <option value="BPV">{t("paymentVoucher")}</option>
        </select>
        <select value={status} onChange={e => { setStatus(e.target.value as VoucherStatus | ""); setPage(0); }}
                className="bg-surface border border-border rounded-lg px-3 py-2 text-xs">
          <option value="">{t("allStatuses")}</option>
          <option value="DRAFT">{tLedger("status")}: DRAFT</option>
          <option value="POSTED">{tLedger("posted")}</option>
          <option value="REVERSED">{tLedger("reversed")}</option>
        </select>
      </div>

      {loading && <div className="space-y-3 animate-pulse">{[1, 2, 3, 4].map(i => <div key={i} className="bg-input rounded-xl h-14" />)}</div>}

      {!loading && rows.length === 0 && (
        <div className="text-center py-24 bg-background border border-dashed border-border rounded-xl flex flex-col items-center">
          <div className="w-16 h-16 bg-surface rounded-xl flex items-center justify-center text-muted shadow-sm mb-6"><Receipt size={28} /></div>
          <h3 className="text-sm font-bold mb-1">{t("noVouchers")}</h3>
          <p className="text-xs text-muted font-medium">{t("createFirstVoucher")}</p>
        </div>
      )}

      {!loading && rows.length > 0 && (
        <div className="bg-surface border border-border rounded-xl overflow-hidden shadow-sm">
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead>
                <tr className="bg-input/50">
                  {[tLedger("docDate"), tLedger("docNo"), t("type"), t("vendor"), tLedger("narration"),
                    t("grossTotal"), tLedger("status"), ""].map((h, i) => (
                    <th key={i} className="text-left px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {rows.map(v => (
                  <tr key={v.id} className="hover:bg-input/30 transition-colors">
                    <td className="px-5 py-3 text-xs">{v.docDate}</td>
                    <td className="px-5 py-3 text-xs font-mono">{v.voucherNumber ?? "—"}</td>
                    <td className="px-5 py-3 text-xs">{v.docType === "PISR" ? t("purchaseInvoice") : t("paymentVoucher")}</td>
                    <td className="px-5 py-3 text-xs">{v.vendorName ?? v.paymentAccountName ?? "—"}</td>
                    <td className="px-5 py-3 text-xs text-muted">{v.narration ?? "—"}</td>
                    <td className="px-5 py-3 text-xs font-mono text-right">{fmtAmount(v.grossTotal)}</td>
                    <td className="px-5 py-3">
                      <span className={`px-2 py-1 rounded-md border text-[10px] font-semibold ${STATUS_CLASS[v.status]}`}>{v.status}</span>
                    </td>
                    <td className="px-5 py-3 text-xs">
                      {v.status === "DRAFT" ? (
                        <Link className="text-primary hover:underline"
                              href={`/dashboard/finance/vouchers/${v.docType === "PISR" ? "purchase-invoice" : "payment"}?id=${v.id}`}>
                          {t("saveDraft")}
                        </Link>
                      ) : v.journalId ? (
                        <Link className="text-primary hover:underline flex items-center gap-1"
                              href={`/dashboard/finance/journals/${v.journalId}`}>
                          <FileText size={12} />{t("viewJournal")}
                        </Link>
                      ) : "—"}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {total > 0 && (
        <Pagination currentPage={page + 1} totalItems={total} itemsPerPage={size}
                    onPageChange={p => setPage(p - 1)} onItemsPerPageChange={n => { setSize(n); setPage(0); }} />
      )}
    </div>
  );
}
```

> Check `web/src/components/ui/Pagination.tsx`'s actual prop names before wiring it; the vendors page calls it with `currentPage` (1-based), `totalItems`, `itemsPerPage`, `onPageChange`, `onItemsPerPageChange`. Match whatever that file exports.

- [ ] **Step 3: Type check and commit**

Run: `cd web && npx tsc --noEmit && npx vitest run src/components/finance`

```bash
git add web/src/app
git commit -m "feat(web): bank/cash payment voucher page and the voucher list

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 15: Web — Opening Balances and Reconciliation pages

**Files:**
- Create: `web/src/app/[locale]/dashboard/finance/opening-balances/page.tsx`
- Create: `web/src/app/[locale]/dashboard/finance/reconciliation/page.tsx`

**Interfaces:**
- Consumes: Task 12's `cutoverApi`; `fmtAmount` / `fmtBalance` from `@/lib/api/ledger`; `ConfirmDialog` from `@/components/ui/confirm-dialog`; `LoadErrorBanner`; `hasPermission`.
- Produces: no new exports — two pages.

- [ ] **Step 1: Write the Opening Balances page**

`web/src/app/[locale]/dashboard/finance/opening-balances/page.tsx`:

```tsx
"use client";

import { useCallback, useEffect, useState } from "react";
import { useSession } from "next-auth/react";
import { useTranslations } from "next-intl";
import { Upload, Loader2, Lock } from "lucide-react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { ApiError } from "@/lib/api/facilities";
import { fmtAmount } from "@/lib/api/ledger";
import { cutoverApi, type OpeningBalanceGrid, type SnapshotUploadResult } from "@/lib/api/vouchers";
import { hasPermission, type UserRole } from "@/lib/rbac";

export default function OpeningBalancesPage() {
  const t = useTranslations("Vouchers");
  const tLedger = useTranslations("Ledger");
  const { data: session } = useSession();
  const role = session?.user?.role as UserRole | undefined;

  const [grid, setGrid] = useState<OpeningBalanceGrid | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [banner, setBanner] = useState<string | null>(null);
  const [upload, setUpload] = useState<SnapshotUploadResult | null>(null);
  const [confirm, setConfirm] = useState<{ title: string; description: string; confirmText: string;
                                           isDestructive: boolean; onConfirm: () => void } | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    setLoadError(null);
    cutoverApi.openingBalances.grid()
      .then(setGrid)
      .catch(e => setLoadError(e instanceof ApiError ? e.message : String(e)))
      .finally(() => setLoading(false));
  }, []);

  useEffect(load, [load]);

  const run = async (fn: () => Promise<void>) => {
    setBusy(true);
    try { await fn(); }
    catch (e) { setLoadError(e instanceof ApiError ? e.message : String(e)); }
    finally { setBusy(false); }
  };

  const onUpload = (file: File) => run(async () => {
    setUpload(await cutoverApi.openingBalances.uploadSnapshot(file));
    load();
  });

  const onEdit = (accountId: string, debit: number, credit: number) =>
    run(async () => { await cutoverApi.openingBalances.setRow(accountId, debit, credit); load(); });

  const onPost = () => setConfirm({
    title: t("postOpeningBalances"),
    description: t("confirmPostOpeningBalances", { date: grid?.asOf ?? "" }),
    confirmText: t("post"),
    isDestructive: false,
    onConfirm: () => { setConfirm(null); run(async () => {
      const e = await cutoverApi.openingBalances.post();
      setBanner(t("openingBalancesPosted", { number: e.entryNumber }));
      load();
    }); },
  });

  const onReverse = () => setConfirm({
    title: t("reverseOpeningBalances"),
    description: t("confirmReverseOpeningBalances", { number: grid?.journalNumber ?? "" }),
    confirmText: tLedger("reverse"),
    isDestructive: true,
    onConfirm: () => { setConfirm(null); run(async () => {
      await cutoverApi.openingBalances.reverse({ date: grid!.asOf, reason: "Opening balances corrected" });
      setBanner(null);
      load();
    }); },
  });

  if (!hasPermission(role, "canPostJournals")) {
    return <div className="bg-surface border border-border rounded-xl p-8 text-center text-sm text-muted">{t("notAllowed")}</div>;
  }

  return (
    <div>
      {loadError && <LoadErrorBanner message={loadError} onRetry={load} />}

      <div className="flex items-start justify-between mb-8">
        <div>
          <h1 className="mb-1">{t("openingBalances")}</h1>
          <p className="text-sm text-muted">{t("openingBalancesDesc", { date: grid?.asOf ?? "" })}</p>
        </div>
        <div className="flex items-center gap-3">
          <label className="border border-border px-4 py-2 rounded-lg text-xs font-semibold flex items-center gap-2 cursor-pointer">
            <Upload size={14} />{t("uploadTrialBalance")}
            <input type="file" accept=".csv,text/csv" className="hidden"
                   onChange={e => { const f = e.target.files?.[0]; if (f) onUpload(f); }} />
          </label>
          {grid?.posted ? (
            <button onClick={onReverse} disabled={busy}
                    className="border border-border px-4 py-2 rounded-lg text-xs font-semibold cursor-pointer disabled:opacity-50">
              {t("reverseOpeningBalances")}
            </button>
          ) : (
            <button onClick={onPost} disabled={busy}
                    className="bg-primary text-primary-foreground px-4 py-2 rounded-lg text-xs font-semibold cursor-pointer disabled:opacity-50">
              {busy ? <Loader2 size={14} className="animate-spin" /> : t("postOpeningBalances")}
            </button>
          )}
        </div>
      </div>

      <p className="text-xs text-muted mb-4">{t("trialBalanceFormat")}</p>

      {banner && <div className="mb-4 bg-success/10 border border-success/20 text-success rounded-lg px-4 py-3 text-xs">{banner}</div>}
      {grid?.posted && (
        <div className="mb-4 bg-input border border-border text-muted rounded-lg px-4 py-3 text-xs flex items-center gap-2">
          <Lock size={14} />{t("alreadyPosted", { number: grid.journalNumber ?? "" })}
        </div>
      )}
      {upload && (
        <div className="mb-4 space-y-1 text-xs">
          <p className="text-muted">{t("uploadedRows", { n: upload.stored })}</p>
          {upload.unmatchedCodes.length > 0 && (
            <p className="text-warning">{t("unmatchedCodes", { n: upload.unmatchedCodes.length, codes: upload.unmatchedCodes.slice(0, 10).join(", ") })}</p>
          )}
          {upload.problems.length > 0 && (
            <ul className="text-destructive list-disc ms-5">{upload.problems.map((p, i) => <li key={i}>{p}</li>)}</ul>
          )}
        </div>
      )}

      {loading && <div className="space-y-3 animate-pulse">{[1, 2, 3, 4, 5].map(i => <div key={i} className="bg-input rounded-xl h-12" />)}</div>}

      {!loading && grid && (
        <>
          <div className="bg-surface border border-border rounded-xl overflow-hidden shadow-sm">
            <div className="overflow-x-auto max-h-[60vh]">
              <table className="w-full">
                <thead className="sticky top-0">
                  <tr className="bg-input/50">
                    {["Code", tLedger("account"), tLedger("debit"), tLedger("credit"), tLedger("status")].map((h, i) => (
                      <th key={i} className="text-left px-5 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider">{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-border">
                  {grid.rows.map(r => (
                    <tr key={r.accountId} className={r.derived ? "bg-input/20" : "hover:bg-input/30"}>
                      <td className="px-5 py-2 text-xs font-mono text-muted">{r.code}</td>
                      <td className="px-5 py-2 text-xs">{r.name}</td>
                      <td className="px-5 py-2 text-right">
                        {r.derived ? <span className="text-xs text-muted">—</span> : (
                          <input inputMode="decimal" defaultValue={r.enteredDebit || ""} disabled={grid.posted}
                                 onBlur={e => onEdit(r.accountId, Number(e.target.value) || 0, 0)}
                                 className="w-28 text-right bg-input border border-border rounded-lg px-2 py-1 text-xs disabled:opacity-50" />
                        )}
                      </td>
                      <td className="px-5 py-2 text-right">
                        {r.derived ? <span className="text-xs text-muted">—</span> : (
                          <input inputMode="decimal" defaultValue={r.enteredCredit || ""} disabled={grid.posted}
                                 onBlur={e => onEdit(r.accountId, 0, Number(e.target.value) || 0)}
                                 className="w-28 text-right bg-input border border-border rounded-lg px-2 py-1 text-xs disabled:opacity-50" />
                        )}
                      </td>
                      <td className="px-5 py-2 text-[10px] text-muted">{r.derived ? t("derived") : t("manual")}</td>
                    </tr>
                  ))}
                </tbody>
                <tfoot>
                  <tr className="bg-input/60 font-bold">
                    <td colSpan={2} className="px-5 py-3 text-xs">{tLedger("reportTotal")}</td>
                    <td className="px-5 py-3 text-xs font-mono text-right">{fmtAmount(grid.totalDebit)}</td>
                    <td className="px-5 py-3 text-xs font-mono text-right">{fmtAmount(grid.totalCredit)}</td>
                    <td />
                  </tr>
                </tfoot>
              </table>
            </div>
          </div>
          <div className="mt-4 flex items-center justify-end gap-8 text-xs">
            <span className="text-muted">{t("differenceGoesToEquity")}</span>
            <span className="font-bold">{t("difference")}: <span className="font-mono">{fmtAmount(grid.difference)}</span></span>
          </div>
        </>
      )}

      {confirm && <ConfirmDialog {...confirm} onCancel={() => setConfirm(null)} />}
    </div>
  );
}
```

> Check `web/src/components/ui/confirm-dialog.tsx`'s exported prop names (the vendors page passes `{title, description, confirmText, isDestructive, onConfirm}` and closes by setting the state to `null`); match that file exactly rather than the `onCancel` guessed above if it differs.

- [ ] **Step 2: Write the Reconciliation page**

`web/src/app/[locale]/dashboard/finance/reconciliation/page.tsx`:

```tsx
"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useSession } from "next-auth/react";
import { useTranslations } from "next-intl";
import { CheckCircle2, AlertTriangle } from "lucide-react";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { ApiError } from "@/lib/api/facilities";
import { fmtBalance } from "@/lib/api/ledger";
import { cutoverApi, type ReconciliationRow } from "@/lib/api/vouchers";
import { hasPermission, type UserRole } from "@/lib/rbac";

export default function ReconciliationPage() {
  const t = useTranslations("Vouchers");
  const tLedger = useTranslations("Ledger");
  const { data: session } = useSession();
  const role = session?.user?.role as UserRole | undefined;

  const [rows, setRows] = useState<ReconciliationRow[]>([]);
  const [asOf, setAsOf] = useState<string>("");
  const [onlyDifferences, setOnlyDifferences] = useState(true);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    setLoadError(null);
    Promise.all([cutoverApi.reconciliation(), cutoverApi.openingBalances.grid()])
      .then(([r, g]) => { setRows(r); setAsOf(g.asOf); })
      .catch(e => setLoadError(e instanceof ApiError ? e.message : String(e)))
      .finally(() => setLoading(false));
  }, []);

  useEffect(load, [load]);

  const outOfBalance = useMemo(() => rows.filter(r => Math.abs(r.difference) >= 0.005), [rows]);
  const shown = onlyDifferences ? outOfBalance : rows;

  if (!hasPermission(role, "canPostJournals")) {
    return <div className="bg-surface border border-border rounded-xl p-8 text-center text-sm text-muted">{t("notAllowed")}</div>;
  }

  return (
    <div>
      {loadError && <LoadErrorBanner message={loadError} onRetry={load} />}

      <div className="flex items-start justify-between mb-8">
        <div>
          <h1 className="mb-1">{t("reconciliation")}</h1>
          <p className="text-sm text-muted">{t("reconciliationDesc", { date: asOf })}</p>
        </div>
        <label className="flex items-center gap-2 text-xs text-muted">
          <input type="checkbox" checked={onlyDifferences} onChange={e => setOnlyDifferences(e.target.checked)} />
          {t("outOfBalance", { n: outOfBalance.length })}
        </label>
      </div>

      {!loading && outOfBalance.length === 0 && (
        <div className="mb-4 bg-success/10 border border-success/20 text-success rounded-lg px-4 py-3 text-xs flex items-center gap-2">
          <CheckCircle2 size={14} />{t("reconciled")}
        </div>
      )}

      {loading && <div className="space-y-3 animate-pulse">{[1, 2, 3, 4].map(i => <div key={i} className="bg-input rounded-xl h-12" />)}</div>}

      {!loading && (
        <div className="bg-surface border border-border rounded-xl overflow-hidden shadow-sm">
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead>
                <tr className="bg-input/50">
                  {["Code", tLedger("account"), t("derivedBalance"), t("pactBalance"), t("difference")].map((h, i) => (
                    <th key={i} className="text-left px-5 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {shown.map(r => (
                  <tr key={`${r.accountId ?? "x"}-${r.code}`} className="hover:bg-input/30">
                    <td className="px-5 py-2 text-xs font-mono text-muted">{r.code}</td>
                    <td className="px-5 py-2 text-xs">
                      {r.name}
                      {r.accountId === null && (
                        <span className="ms-2 text-[10px] text-warning inline-flex items-center gap-1">
                          <AlertTriangle size={10} />{t("notInOurChart")}
                        </span>
                      )}
                      {r.derived && <span className="ms-2 text-[10px] text-muted">{t("derived")}</span>}
                    </td>
                    <td className="px-5 py-2 text-xs font-mono text-right">{fmtBalance(r.derivedBalance)}</td>
                    <td className="px-5 py-2 text-xs font-mono text-right">{fmtBalance(r.pactBalance)}</td>
                    <td className={`px-5 py-2 text-xs font-mono text-right ${Math.abs(r.difference) >= 0.005 ? "text-destructive font-bold" : "text-muted"}`}>
                      {fmtBalance(r.difference)}
                    </td>
                  </tr>
                ))}
                {shown.length === 0 && (
                  <tr><td colSpan={5} className="px-5 py-10 text-center text-xs text-muted">{tLedger("noRows")}</td></tr>
                )}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 3: Type check and commit**

Run: `cd web && npx tsc --noEmit`

```bash
git add web/src/app
git commit -m "feat(web): opening balances and reconciliation screens

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 16: Web — Import Batches page and the sidebar entries

**Files:**
- Create: `web/src/app/[locale]/dashboard/finance/import-batches/page.tsx`
- Modify: `web/src/components/ui/MvpSidebar.tsx`

**Interfaces:**
- Consumes: Task 12's `cutoverApi`; `ConfirmDialog`; `LoadErrorBanner`; `hasPermission`; the sidebar's existing `financeItems` array.
- Produces: no new exports — one page plus five nav entries.

- [ ] **Step 1: Write the Import Batches page**

`web/src/app/[locale]/dashboard/finance/import-batches/page.tsx`:

```tsx
"use client";

import { useCallback, useEffect, useState } from "react";
import { useSession } from "next-auth/react";
import { useTranslations } from "next-intl";
import { Download, Loader2, Layers, Undo2 } from "lucide-react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { ApiError } from "@/lib/api/facilities";
import { cutoverApi, type ImportBatch, type BulkPostResult } from "@/lib/api/vouchers";
import { hasPermission, type UserRole } from "@/lib/rbac";

const STATUS_CLASS: Record<ImportBatch["status"], string> = {
  DRAFT: "bg-input text-muted border-border",
  POSTED: "bg-success/10 text-success border-success/20",
  REVERSED: "bg-input text-muted border-border",
};

export default function ImportBatchesPage() {
  const t = useTranslations("Vouchers");
  const tLedger = useTranslations("Ledger");
  const { data: session } = useSession();
  const role = session?.user?.role as UserRole | undefined;

  const [batches, setBatches] = useState<ImportBatch[]>([]);
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [banner, setBanner] = useState<string | null>(null);
  const [failures, setFailures] = useState<BulkPostResult["failures"]>([]);
  const [confirm, setConfirm] = useState<{ title: string; description: string; confirmText: string;
                                           isDestructive: boolean; onConfirm: () => void } | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    setLoadError(null);
    cutoverApi.batches.list()
      .then(setBatches)
      .catch(e => setLoadError(e instanceof ApiError ? e.message : String(e)))
      .finally(() => setLoading(false));
  }, []);

  useEffect(load, [load]);

  const run = async (id: string, fn: () => Promise<void>) => {
    setBusyId(id);
    try { await fn(); }
    catch (e) { setLoadError(e instanceof ApiError ? e.message : String(e)); }
    finally { setBusyId(null); }
  };

  const onBulkPost = (b: ImportBatch) => setConfirm({
    title: t("bulkPost"),
    description: t("confirmBulkPost", { n: b.leasesImported, date: "" }),
    confirmText: t("bulkPost"),
    isDestructive: false,
    onConfirm: () => { setConfirm(null); run(b.id, async () => {
      const r = await cutoverApi.batches.post(b.id);
      setBanner(t("batchPosted", { n: r.leasesPosted, m: r.journalsPosted }));
      setFailures(r.failures);
      load();
    }); },
  });

  const onReverse = (b: ImportBatch) => setConfirm({
    title: t("reverseBatch"),
    description: t("confirmReverseBatch", { n: b.journalsPosted, m: b.leasesImported }),
    confirmText: tLedger("reverse"),
    isDestructive: true,
    onConfirm: () => { setConfirm(null); run(b.id, async () => {
      await cutoverApi.batches.reverse(b.id, {
        date: new Date().toISOString().slice(0, 10),
        reason: "Batch reversed from the Import Batches screen",
      });
      setBanner(t("batchReversed"));
      setFailures([]);
      load();
    }); },
  });

  if (!hasPermission(role, "canPostJournals")) {
    return <div className="bg-surface border border-border rounded-xl p-8 text-center text-sm text-muted">{t("notAllowed")}</div>;
  }

  return (
    <div>
      {loadError && <LoadErrorBanner message={loadError} onRetry={load} />}

      <div className="flex items-start justify-between mb-8">
        <div>
          <h1 className="mb-1">{t("importBatches")}</h1>
          <p className="text-sm text-muted">{t("importBatchesDesc")}</p>
        </div>
        <a href="/api/proxy/v1/import/portfolio/template?cutOver=true" download
           className="border border-border px-4 py-2 rounded-lg text-xs font-semibold flex items-center gap-2">
          <Download size={14} />{t("downloadCutoverTemplate")}
        </a>
      </div>

      {banner && <div className="mb-4 bg-success/10 border border-success/20 text-success rounded-lg px-4 py-3 text-xs">{banner}</div>}
      {failures.length > 0 && (
        <div className="mb-4 bg-destructive/10 border border-destructive/20 text-destructive rounded-lg px-4 py-3 text-xs">
          <p className="font-bold mb-1">{t("batchFailures", { n: failures.length })}</p>
          <ul className="list-disc ms-5">{failures.map((f, i) => <li key={i}>{f.message}</li>)}</ul>
        </div>
      )}

      {loading && <div className="space-y-3 animate-pulse">{[1, 2, 3].map(i => <div key={i} className="bg-input rounded-xl h-14" />)}</div>}

      {!loading && batches.length === 0 && (
        <div className="text-center py-24 bg-background border border-dashed border-border rounded-xl flex flex-col items-center">
          <div className="w-16 h-16 bg-surface rounded-xl flex items-center justify-center text-muted shadow-sm mb-6"><Layers size={28} /></div>
          <h3 className="text-sm font-bold mb-1">{t("noBatches")}</h3>
          <p className="text-xs text-muted font-medium">{t("importBatchesDesc")}</p>
        </div>
      )}

      {!loading && batches.length > 0 && (
        <div className="bg-surface border border-border rounded-xl overflow-hidden shadow-sm">
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead>
                <tr className="bg-input/50">
                  {[t("batchLabel"), t("leasesImported"), t("journalsPosted"), tLedger("status"), ""].map((h, i) => (
                    <th key={i} className="text-left px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {batches.map(b => (
                  <tr key={b.id} className="hover:bg-input/30">
                    <td className="px-5 py-3 text-xs">{b.label ?? b.id.slice(0, 8)}</td>
                    <td className="px-5 py-3 text-xs font-mono">{b.leasesImported}</td>
                    <td className="px-5 py-3 text-xs font-mono">{b.journalsPosted}</td>
                    <td className="px-5 py-3">
                      <span className={`px-2 py-1 rounded-md border text-[10px] font-semibold ${STATUS_CLASS[b.status]}`}>{b.status}</span>
                    </td>
                    <td className="px-5 py-3">
                      {busyId === b.id ? <Loader2 size={14} className="animate-spin text-muted" />
                        : b.status === "DRAFT" ? (
                          <button onClick={() => onBulkPost(b)}
                                  className="text-primary text-xs font-semibold cursor-pointer">{t("bulkPost")}</button>
                        ) : b.status === "POSTED" ? (
                          <button onClick={() => onReverse(b)}
                                  className="text-destructive text-xs font-semibold flex items-center gap-1 cursor-pointer">
                            <Undo2 size={12} />{t("reverseBatch")}
                          </button>
                        ) : <span className="text-xs text-muted">—</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {confirm && <ConfirmDialog {...confirm} onCancel={() => setConfirm(null)} />}
    </div>
  );
}
```

- [ ] **Step 2: Add the sidebar entries**

In `web/src/components/ui/MvpSidebar.tsx`, `financeItems` is built inside `hasPermission(userRole, 'canAccessFinance') ? [...] : []` around line 112. Append five items, gated on `canPostJournals` so a finance-reader without posting rights does not see links that 403:

```tsx
    const tVouchers = useTranslations("Vouchers");
    …
    const financeItems = hasPermission(userRole, 'canAccessFinance') ? [
        { name: t("chartOfAccounts"), href: "/dashboard/finance/accounts", icon: BookOpen, tourId: 'sidebar-accounts' },
        …existing entries…
        { name: tBankAccounts("title"), href: "/dashboard/finance/bank-accounts", icon: Landmark },
        ...(hasPermission(userRole, 'canPostJournals') ? [
            { name: tVouchers("vouchers"), href: "/dashboard/finance/vouchers", icon: Receipt },
            { name: tVouchers("openingBalances"), href: "/dashboard/finance/opening-balances", icon: Scale },
            { name: tVouchers("reconciliation"), href: "/dashboard/finance/reconciliation", icon: GitCompare },
            { name: tVouchers("importBatches"), href: "/dashboard/finance/import-batches", icon: Layers },
        ] : []),
    ] : [];
```

Add `Scale`, `GitCompare` and `Layers` to the existing `lucide-react` import (`Receipt` is already imported for the transactions entry).

- [ ] **Step 3: Type check, run the web suite, commit**

Run: `cd web && npx tsc --noEmit && npx vitest run`
Expected: PASS — including every pre-existing web test.

```bash
git add web/src
git commit -m "feat(web): import batches screen and finance sidebar entries

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 17: Full verification and pull request

**Files:**
- Modify: none (verification only)

**Interfaces:**
- Consumes: everything built in Tasks 1–16.
- Produces: a green build and one PR.

- [ ] **Step 1: Run the full backend suite**

```bash
cd /Users/kunalsharma/datagami/rentaxis/backend && ./gradlew clean test
```

Expected: `BUILD SUCCESSFUL`. Read the counts from the runner's summary, not from a grep: open `backend/build/reports/tests/test/index.html` and record the total, failed and skipped numbers. The classes this plan adds are `VoucherSchemaIT`, `VoucherMathTest`, `VoucherDraftIT`, `PurchaseInvoicePostingIT`, `PaymentVoucherPostingIT`, `VoucherAmendIT`, `VoucherControllerIT`, `CutoverSchemaIT`, `ImportBatchReverseIT`, `TrialBalanceCsvParserTest`, `OpeningBalanceIT`, `ReconciliationIT`, and (after Plans 2–3) `ContractImportValidatorTest`, `ContractImportIT`, `ContractImportPostIT`.

- [ ] **Step 2: Run the full web suite and type check**

```bash
cd /Users/kunalsharma/datagami/rentaxis/web && npx tsc --noEmit && npx vitest run && npm run lint
```

Expected: PASS with no new type errors and no new lint warnings.

- [ ] **Step 3: Boot the stack and smoke the migrations**

```bash
cd /Users/kunalsharma/datagami/rentaxis && docker compose up -d db
cd backend && ./gradlew bootRun
```

Watch the Liquibase output for `87-vouchers` and `88-cutover` applying cleanly (and `88-cutover-cheque-imported-status` skipping with its `onFailMessage` if Plans 2–3 are not in). Stop the app once `Started RentaxisApplication` appears.

- [ ] **Step 4: Click through the five new screens**

```bash
cd /Users/kunalsharma/datagami/rentaxis/web && npm run dev
```

As a `TENANT_ADMIN`, confirm by hand:
1. `/dashboard/finance/vouchers/purchase-invoice` — pick a vendor and an expense account, enter `2000` at 5%, see `100.00` VAT and `2,100.00` total, attach a PDF, Post; the toast carries a `PISR-` number.
2. `/dashboard/finance/vouchers` — the posted invoice is listed with its number and links to its journal; the journal shows Dr expense, Dr input VAT, Cr vendor.
3. `/dashboard/finance/vouchers/payment` — no VAT columns; posting gives a `BPV-` number and a journal crediting the chosen bank.
4. `/dashboard/finance/opening-balances` — upload a two-row CSV, see the totals and the difference, Post, then confirm the button flips to Reverse.
5. `/dashboard/finance/reconciliation` and `/dashboard/finance/import-batches` — both render, and the batches page's Reverse opens a confirm dialog.

Then switch the locale to `ar` and confirm every one of the five pages renders RTL with no English strings and no horizontal overflow.

- [ ] **Step 5: Open the pull request**

```bash
cd /Users/kunalsharma/datagami/rentaxis
git push -u origin feat/accounting-v2-vouchers-cutover
gh pr create --base main --title "feat(finance): accounting v2 plan 4 — vouchers and cut-over" --body "$(cat <<'BODY'
## Summary

Implements §10 and the matching §11 screens of `docs/superpowers/specs/2026-09-17-accounting-v2-design.md`, on top of Plan 1's ledger core.

- **Purchase / Service Invoice (`PISR`)** — Dr expense lines, Dr `INPUT_VAT` for the summed per-line VAT, Cr the vendor's payable leaf. VAT is computed and rounded per line, then summed, so the header agrees with the vendor's own invoice.
- **Bank / Cash Payment Voucher (`BPV`)** — Dr any leaf, Cr the chosen bank or cash account. No VAT: that belongs on the invoice.
- **Amend = reverse + new voucher.** Posted vouchers are never edited; the ledger keeps the original, its mirror and the correction.
- **Opening balances (`OB`)** — a grid of every leaf, importable from PACT's trial-balance CSV, with the nine role-mapped accounts marked derived and excluded from manual entry. The difference posts to `OPENING_BALANCE_DIFFERENCE` so the books open balanced. Re-posting is blocked until the existing journal is reversed.
- **Reconciliation** — per account: our balance as at `books_start_date − 1` (with the OB journal's own lines netted out), PACT's uploaded figure, and the gap.
- **Contract import** — the existing multi-sheet portfolio importer gains `Properties` account columns, `Contracts` and a v2 `Cheques` sheet. One importer, one `import_jobs` row, one polling endpoint.
- **Import batches** — every journal the import writes carries `import_batch_id`; **Reverse batch** reverses them newest-first and returns the contracts to DRAFT.

## Schema

`87-vouchers.yaml` (vouchers, voucher_lines, voucher_attachments) and `88-cutover.yaml` (import_batches, import_batch_leases, opening_balance_snapshots, opening_balance_postings, `import_jobs.import_batch_id`, `cheques.imported_status`). Append-only, both with rollbacks.

## Tests

Unit: per-line VAT rounding including the `.005` boundary, and the PACT CSV's separators, parentheses and totals rows. Integration (Testcontainers): one class per document asserting the **exact** journal line set, the opening-balance difference in both directions, batch reverse leaving a flat trial balance, and the import→post→reverse round trip. Web: Vitest over the voucher form's live VAT and totals.

## Not in this PR

Cash Receipt Voucher – Rent (`RCP`) — it sits on the receipt path, which Plan 2 owns; the `vouchers` table carries the doc type for it. Bill-wise allocation of payments to invoices and PDC payables are out of scope for v2 (spec §10.2, §14).

🤖 Generated with [Claude Code](https://claude.com/claude-code)
BODY
)"
```

- [ ] **Step 6: Confirm CI is green**

```bash
gh pr checks --watch
```

Expected: every check passes. Do not claim the work is done before this command has printed a green result.

---

## Self-review

**1. Spec coverage**

| Spec item | Task |
|---|---|
| §10.1 `vouchers` / `voucher_lines` tables | 1 |
| §10.1 PISR posting (Dr expense, Dr INPUT_VAT, Cr vendor payable) | 3 |
| §10.1 attachments via the existing infra | 5 (reuses `DeductionAttachmentService`'s Azure/local strategy) |
| §10.1 amend = reversal + new voucher | 4 |
| §10.2 BPV header (`payment_account_id`, `cheque_number`, `cheque_date`) | 1, 2 |
| §10.2 BPV posting (Dr lines / Cr payment account) | 4 |
| §10.2 "not in v2": bill-wise allocation, PDC payables | Deliberately absent; called out in the PR body |
| §10.3 `books_start_date` / `books_locked_through` | Consumed from Plan 1's `TenantFiscalSettingsService`; used in 8, 9, 11 |
| §10.3 step 1 sheets (Properties + 6 account columns, Units, Renters, Contracts, Cheques) | 10 |
| §10.3 step 1 mapping by account-name match, unmatched reported not guessed | 10 (warning naming the column and the value) |
| §10.3 step 1 produces DRAFT leases | 10 |
| §10.3 Bulk post: TCO/PDR at `contract_date`, CRT/CBR on given dates, recognition catch-up | 11 |
| §10.3 all import journals carry `source_type = IMPORT` and `import_batch_id` | 10, 11 |
| §10.3 Reverse batch | 7 |
| §10.3 import journals exempt from the period lock | Plan 1's `PostingService` already exempts `importBatchId != null`; Task 11's IT dates everything inside the locked period to prove it |
| §10.3 step 2 OB grid of every leaf, CSV import (code, name, debit, credit) | 8 |
| §10.3 step 2 nine derived roles excluded from manual entry | 8 (`DERIVED_ROLES`) |
| §10.3 step 2 difference → `OPENING_BALANCE_DIFFERENCE` | 8 |
| §10.3 Reconciliation screen: derived / PACT / difference | 9, 15 |
| §11 Purchase/Service Invoice screen | 13 |
| §11 Bank/Cash Payment Voucher screen | 14 |
| §11 Opening Balances screen | 15 |
| §11 Reconciliation screen | 15 |
| §11 Import Batches screen | 16 |
| §11 RBAC: post/OB/batch-reverse → `TENANT_ADMIN` or `ACCOUNTANT` | Every controller `@PreAuthorize`s the three roles; every page gates on `canPostJournals` |
| §12 unit: VAT math, OB difference | 2 (`VoucherMathTest`), 8 (`OpeningBalanceIT` both directions) |
| §12 IT per document asserting the exact journal | 3, 4 |
| §12 import batch reverse IT | 7, 11 |
| §12 invariant "trial balance balances" | 8 (`postingClosesTheDifference…`), 7 and 11 (flat after reverse) |
| Web vitest for the voucher form's balance/VAT display | 13 |

**Not covered, with the reason:**

- **§11 Cash Receipt Voucher – Rent.** Out of scope by the task brief and by the spec's own build order: §9.3 makes every receipt clear a cheque-register row, which is Plan 2's machinery. The `vouchers` table carries `RCP` in its `doc_type` CHECK so Plan 2 adds a service, not a migration.
- **The "six account-name columns" count.** The client's `property maping ledgers.xls` has **four** account columns (`Rental Income A/c`, `Rental Receivable A/c`, `Advance Rent A/c`, `Bank A/c`) plus `Property Code`, `Name` and `LandLord`. The spec says six. Task 10 accepts six — those four plus `PdcReceivableAccount` and `SecurityDepositAccount`, which are the remaining roles §5.4's posting guard requires before a lease can post — and all six are optional, so the client's four-column sheet imports unchanged. `LandLord` is ignored (§14 defers landlord commission). Flagged here because it is a judgement call about the spec, not a silent gap.
- **Golden ledger replay against the client's two GL exports (§12).** Spec §13 assigns that to Plan 5 along with the demo re-seed and the Playwright walkthrough.
- **Mobile.** Spec D7 and §11: web only in v2.

**2. Placeholder scan**

No "TBD", "implement later", "add validation", or "similar to Task N" anywhere. Three places deliberately defer to a file rather than restating it, and each names the file and the exact thing to read: `Pagination`'s prop names (Task 14), `ConfirmDialog`'s prop names (Task 15), and Plan 2/3's merged signatures (the interface block, Task 11). Those are reads against the repo at execution time, not unwritten decisions. Task 10 Step 8 and Task 11 Step 1 give the IT assertions in full but name three fixture helpers (`importTheTemplate`, `importTemplateWithSecondChequeBounced`, `importTwoContractsOneWithAnUnmappedRole`) whose bodies are the workbook-building code shown in Task 10 Step 1 — build them from that.

**3. Type consistency**

- `PostingRequest.dr/cr/withDims/withNarration/Dimensions` are used identically in Tasks 3, 4, 7, 8 and match Plan 1 Task 5 exactly.
- `VoucherService.VoucherInput` / `VoucherLineInput` field order is the same in Tasks 2, 3, 4, 5 and in `VoucherController.toInput`.
- `VoucherMath.HasAmounts` is implemented by `VoucherLine` (Task 2) and consumed by `VoucherDTO`/`VoucherDetailDTO` (Task 5).
- `VoucherDetailDTO` is **flat** (header fields at the top level plus `lines` and `attachments`), used by `VoucherController`'s six returning endpoints and by `VoucherDetail` in `vouchers.ts`; `VoucherDTO` is the list row only, and `VoucherDetail = Voucher & {lines, attachments}` in TypeScript mirrors that exactly.
- `OpeningBalanceService.OpeningBalanceRow` field order matches `OpeningBalanceRowDTO.of` (Task 9) and `OpeningBalanceRow` in `vouchers.ts` (Task 12).
- `ReconciliationRow` is `(accountId, code, name, derived, derivedBalance, pactBalance, difference)` in Tasks 9, 12 and 15.
- `ImportBatchStatus` is `DRAFT | POSTED | REVERSED` in the enum (Task 7), the CHECK constraint (Task 6), the DTO (Task 7) and the web type (Task 12).
- `ImportBatchService.reverse(UUID, LocalDate, String)` is called with the same arity from `ImportBatchController` (Task 7) and `ContractImportPostIT` (Task 11).
- `LeaseReverter.revertToDraft(UUID)` is declared in Task 7 and implemented by `LeaseService` in Task 11 Step 3 under the same name. It is a **new** method — Plan 2 has `terminateLease`, which posts journals and is not an undo.
- The Plans 2/3 signatures used in Tasks 10–11 are the ones those plan documents publish: `LeasePostingService.post(UUID) : PostLeaseResponse`, `ChequeService.{deposit,clear,bounce}(UUID, ChequeActionRequest) : ChequeDTO` in `core/service/cheque/`, `RecognitionService.runTo(LocalDate, boolean) : RecognitionRunResult` in `core/service/recognition/`. Each is extended with a trailing `UUID importBatchId` in Task 11 Step 3 and called with that arity in `ContractImportPostService`.
- `vatOf(amount, rate)` in `vouchers.ts` (Task 12) mirrors `VoucherMath.vat(amount, rate)` (Task 2); both are asserted against `1234.57 → 61.73` and `100.10 → 5.01`.
- `cutoverApi.batches.reverse(id, {date, reason})` matches `ReverseBatchDTO(date, reason)` (Task 7).
