# Multi-receipt download Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Staff and renters, on web and mobile, tick several cleared receipts of one contract (or a whole year in one tap) and download them as one PDF.

**Architecture:** Every client posts the ticked ids to the existing `POST /api/v1/cheques/receipts/combined` (one lease, max 100, merged PDF). A small pure "year" helper per platform (TS in `web/src/lib/receipts/`, Dart in `rentaxis_core`) decides which rows a year ticks. The backend gains one field on `RenterChequeDTO` so renter clients can skip rows the server refuses.

**Tech Stack:** Spring Boot + Testcontainers (backend), Next.js + vitest + Testing Library (web), Flutter + Riverpod + flutter_test (mobile, melos workspace).

**Spec:** `docs/superpowers/specs/2026-11-20-multi-receipt-download-design.md`

## Global Constraints

- One download = one contract. Never send ids of more than one lease.
- Max 100 ids per download (`RentReceiptService.MAX_COMBINED = 100`); clients disable the button above 100 and say why.
- "Year" = calendar year of the received date: `clearedAt`, else `chequeDate` (staff rows) / `dueDate` (renter rows).
- Receiptable row: `status == CLEARED` and not `settledBeforeAcquisition`. Staff web keeps its existing `registerActionsFor(...).includes("receipt")` rule.
- Renters may download receipts whatever the lease status.
- All new user-facing copy in en and ar (`web/messages/en.json` + `ar.json`; mobile inline `ar ? … : …` like the surrounding code).
- Staff mobile stays behind `MOBILE_FINANCE` (no flag change).
- Gradle tests: inside WSL `Ubuntu-24.04`, from `/mnt/c/rentaxis-wt/multi-receipts/backend`, with `JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`.
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. No push.

## Review Focus

- A renter with two leases ticks rows on lease A, then switches contract: ticks of A must drop, never be sent with B's ids (Task 4, Task 6 tests).
- An acquired lease with pre-acquisition cleared rows: "Select year" must not tick them, and no receipt link shows (Task 1, Task 4, Task 6 tests).
- More than 100 rows in a year: the button is disabled with a hint, not a server error (Task 2 / Task 5 helper tests + Task 4 test).
- Server refusal (e.g. a row bounced/reversed since the page loaded): the server's message shows and the ticks stay (Task 4 test).
- Year with no cleared rows / contract with no cleared rows: no selection bar shown (Task 3, Task 4 tests).

---

### Task 1: Backend — renter rows say when they were banked by the previous owner; pin renter combined access

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/dto/RenterChequeDTO.java` (add last component `boolean settledBeforeAcquisition`, with a `@param` line)
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/OnlinePaymentService.java:207` (pass `c.isSettledBeforeAcquisition()`)
- Modify any other `new RenterChequeDTO(` call sites (grep `backend/src`, including tests)
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/OnlinePaymentServiceIT.java`
- Test: `backend/src/test/java/com/datagami/rentaxis/api/ChequeControllerIT.java`

**Interfaces:**
- Produces: JSON field `settledBeforeAcquisition: boolean` on every item of `GET /api/v1/online-payments/my-payments`.

- [ ] **Step 1: Write failing tests**
  - `OnlinePaymentServiceIT.myPaymentsSaysWhichRowsThePreviousOwnerBanked`: a lease with one cleared row flagged `settledBeforeAcquisition=true` and one not; `getMyPayments()` returns the flag `true` / `false` respectively.
  - `ChequeControllerIT.aRenterGetsACombinedReceiptOfTheirOwnClearedRows`: renter linked to the lease, two cleared rows → `POST /api/v1/cheques/receipts/combined` as `renter` returns 200, `application/pdf`, 2 pages (reuse the page-count helper of `aCombinedReceiptHasAPagePerClearedRowInTheOrderTheMoneyLanded`).
  - `ChequeControllerIT.aRenterCannotCombineAnotherRentersRow`: one own cleared row + one cleared row of another renter's lease → 404.
  - `ChequeControllerIT.aCombinedReceiptRefusesMixedLeasesAndMoreThanAHundred`: only if not already covered (grep `one contract` / `at most`): two leases → 400 (BusinessRuleViolation status as the existing tests assert); 101 ids → same.
- [ ] **Step 2: Run, expect the DTO test to fail to compile / fail**
  Run (WSL): `./gradlew test --tests '*OnlinePaymentServiceIT' --tests '*ChequeControllerIT'`
- [ ] **Step 3: Add the field and pass it**
- [ ] **Step 4: Run the same command; expect PASS**
- [ ] **Step 5: Commit** `feat(receipts): renter rows carry settledBeforeAcquisition; pin renter combined receipt access`

### Task 2: Web — year helper

**Files:**
- Create: `web/src/lib/receipts/receiptYears.ts`
- Test: `web/src/lib/receipts/__tests__/receiptYears.test.ts`

**Interfaces:**
- Produces:
  - `type ReceiptRow = { id: string; clearedAt: string | null; date: string }` (`date` = chequeDate or dueDate, ISO `YYYY-MM-DD`)
  - `receivedYear(row: ReceiptRow): number`
  - `receiptYears(rows: ReceiptRow[]): number[]` — distinct, newest first
  - `idsInYear(rows: ReceiptRow[], year: number): string[]` — input order kept
  - `MAX_COMBINED_RECEIPTS = 100`

- [ ] **Step 1: Write failing tests** — `clearedAt` wins over `date`; `clearedAt` null falls back to `date`; `receiptYears` of rows cleared 2025-12-31, 2026-01-01, 2026-03-01 is `[2026, 2025]`; `idsInYear(rows, 2026)` returns the two 2026 ids; empty input gives `[]`. Parse the year from the string's first 4 chars (no `Date`, no timezone shift).
- [ ] **Step 2: Run** `cd web && npx vitest run src/lib/receipts` → FAIL
- [ ] **Step 3: Implement**
- [ ] **Step 4: Run** → PASS
- [ ] **Step 5: Commit** `feat(web): receipt year helper`

### Task 3: Staff web — "select year" on the lease cheque grid

**Files:**
- Modify: `web/src/components/leases/ChequeGrid.tsx` (combined-receipt bar, `data-testid="combined-receipt-bar"`)
- Modify: `web/messages/en.json`, `web/messages/ar.json` (`Cheques` namespace next to `combinedReceipt`)
- Test: `web/src/components/leases/__tests__/ChequeGrid.receipt.test.tsx`

**Interfaces:**
- Consumes: `receiptYears`, `idsInYear`, `MAX_COMBINED_RECEIPTS` (Task 2), with `date = cheque.chequeDate`.

- [ ] **Step 1: Write failing tests**
  - `selecting a year ticks that year's receiptable rows only`: rows cleared 2025-11-05, 2026-02-05, 2026-03-05 and one REGISTERED 2026 row; choose 2026 in `data-testid="combined-receipt-year"` → the two 2026 cleared checkboxes checked, others not; button reads count 2.
  - `choosing a year replaces earlier ticks`.
  - `no year control when no row offers a receipt` (bar absent, as today).
  - `over 100 picked disables download and shows the limit hint` (`data-testid="combined-receipt-limit"`).
- [ ] **Step 2: Run** `cd web && npx vitest run src/components/leases/__tests__/ChequeGrid.receipt.test.tsx` → FAIL
- [ ] **Step 3: Implement** a `<select>` listing `receiptYears(receiptable rows)` with a placeholder option; on change set `receiptPicked` to `idsInYear(...)`. Keep the existing per-row checkboxes and download button.
- [ ] **Step 4: Run** → PASS
- [ ] **Step 5: Commit** `feat(web): select a year of receipts on the lease cheque grid`

### Task 4: Renter web — tick and download on the Payments page

**Files:**
- Create: `web/src/components/renter/RenterReceiptPicker.tsx` (the bar: contract select, year select, download button; owns the ticked set)
- Modify: `web/src/app/[locale]/dashboard/renter-portal/payments/page.tsx` (history list gets a checkbox per receiptable row; hide receipt link when `settledBeforeAcquisition`)
- Modify: `web/src/lib/api/leasing.ts` (`RenterCheque.settledBeforeAcquisition?: boolean`)
- Modify: `web/messages/en.json`, `web/messages/ar.json` (`OnlinePayments` namespace)
- Test: `web/src/app/[locale]/dashboard/renter-portal/payments/__tests__/receipts.test.tsx`

**Interfaces:**
- Consumes: Task 2 helpers with `date = row.dueDate`; `chequeApi.combinedReceipt(ids)`; `saveResponse(res, "receipts.pdf")` from `web/src/lib/download.ts`.
- Receiptable renter row: `status === "CLEARED" && !settledBeforeAcquisition`.

- [ ] **Step 1: Write failing tests** (mock `chequeApi`/`onlinePaymentApi` as `page.test.tsx` does)
  - `one lease: tick two cleared rows and download posts their ids` → `combinedReceipt` called with exactly those ids; ticks cleared after a 200.
  - `select year ticks only that year's cleared rows`.
  - `pre-acquisition rows have no checkbox and no receipt link`.
  - `two leases: rows of the other contract are not tickable, switching contract drops ticks` → after switching, `combinedReceipt` receives only the new lease's ids.
  - `server refusal shows the message and keeps ticks` (`res.ok=false`, body `{message:"Receipt can only be generated for cleared payments"}`).
  - `no cleared rows → no picker bar`.
- [ ] **Step 2: Run** `cd web && npx vitest run "src/app/[locale]/dashboard/renter-portal/payments"` → FAIL
- [ ] **Step 3: Implement.** Contract select only when cleared rows span more than one `leaseId`; default to the lease of the most recent cleared row. Label each contract by `propertyName · unitIdentifier`.
- [ ] **Step 4: Run** → PASS (existing `page.test.tsx`, `bank-dates.test.tsx` still green)
- [ ] **Step 5: Commit** `feat(web): renters download several receipts of one contract`

### Task 5: Mobile core — year helper and combined download call

**Files:**
- Create: `mobile/packages/rentaxis_core/lib/utils/receipt_selection.dart` (export from `lib/rentaxis_core.dart`)
- Modify: `mobile/packages/rentaxis_core/lib/api/services/lease_service.dart` (add `getLeaseCheques`)
- Create: `mobile/packages/rentaxis_core/lib/api/services/receipt_service.dart` (export)
- Test: `mobile/packages/rentaxis_core/test/utils/receipt_selection_test.dart`

**Interfaces:**
- Produces:
  - `int receivedYear(Map<String, dynamic> row)` — year of `clearedAt` else `chequeDate` else `dueDate` (first 4 chars)
  - `bool isReceiptable(Map<String, dynamic> row)` — `status == 'CLEARED' && settledBeforeAcquisition != true`
  - `List<int> receiptYears(Iterable<Map<String, dynamic>> rows)` — receiptable only, distinct, newest first
  - `List<String> idsInYear(Iterable<Map<String, dynamic>> rows, int year)` — receiptable only
  - `const maxCombinedReceipts = 100;`
  - `Future<List<dynamic>> LeaseService.getLeaseCheques(String leaseId)` → `GET /v1/leases/$leaseId/cheques`
  - `class CombinedReceipt { final List<int> bytes; final String fileName; }`
  - `Future<CombinedReceipt> ReceiptService(Dio).combined(List<String> chequeIds)` → `POST /v1/cheques/receipts/combined`, `ResponseType.bytes`; file name from `content-disposition` `filename=`, else `receipts.pdf`.

- [ ] **Step 1: Write failing tests** for the four helpers (same cases as Task 2 plus BOUNCED and pre-acquisition rows excluded).
- [ ] **Step 2: Run** `cd mobile/packages/rentaxis_core && flutter test test/utils/receipt_selection_test.dart` → FAIL
- [ ] **Step 3: Implement**
- [ ] **Step 4: Run** → PASS
- [ ] **Step 5: Commit** `feat(mobile): receipt selection helpers and combined receipt call`

### Task 6: Renter mobile — tick and download on the Payments screen

**Files:**
- Modify: `mobile/apps/renter/lib/screens/payments_screen.dart`
- Create: `mobile/apps/renter/lib/widgets/receipt_picker_bar.dart`
- Modify: `mobile/apps/renter/lib/widgets/renter_document_button.dart` only if the share call needs reuse (keep `shareRenterDocumentProvider` as the share seam)
- Test: `mobile/apps/renter/test/payments_receipts_test.dart`

**Interfaces:**
- Consumes: Task 5 helpers and `ReceiptService.combined`; `shareRenterDocumentProvider(bytes, fileName, origin)`.
- Add `final combinedReceiptProvider = Provider<Future<CombinedReceipt> Function(List<String>)>` in `mobile/apps/renter/lib/providers/renter_data.dart` (overridable in tests).

- [ ] **Step 1: Write failing widget tests** (override `renterPaymentsProvider`, `renterLeasesProvider`, `combinedReceiptProvider`, `shareRenterDocumentProvider`)
  - `ticking two cleared rows and tapping download shares one PDF of those ids`.
  - `select year ticks only that year`.
  - `pre-acquisition and bounced rows have no checkbox`.
  - `with two leases and no lease picked, the picker bar is hidden and a hint says to pick a contract`.
  - `changing the picked lease clears ticks`.
- [ ] **Step 2: Run** `cd mobile/apps/renter && flutter test test/payments_receipts_test.dart` → FAIL
- [ ] **Step 3: Implement.** Checkbox keys `receipt-pick-<id>`; bar keys `receipt-year`, `receipt-download`. Hide the single `RenterDocumentButton` receipt for pre-acquisition rows.
- [ ] **Step 4: Run** → PASS, then `flutter test` for the whole renter app
- [ ] **Step 5: Commit** `feat(renter-app): download several receipts of one contract`

### Task 7: Staff mobile — lease detail schedule on cheques, with receipt selection

**Files:**
- Modify: `mobile/apps/manager/lib/screens/lease_detail_screen.dart` (`_loadData` uses `leaseService.getLeaseCheques`; `_buildPaymentSchedule` adds checkboxes + bar; `_ChequeTimelineRow` reads `chequeDate` where it read `dueDate`)
- Test: `mobile/apps/manager/test/lease_detail_receipts_test.dart` (follow `lease_detail_role_gating_test.dart` for setup)

**Interfaces:**
- Consumes: Task 5 helpers, `LeaseService.getLeaseCheques`, `ReceiptService.combined`.
- Download: write bytes to `getTemporaryDirectory()/<fileName>` and `OpenFilex.open`, as `_downloadDocument` does.
- Sort: by `seqNo` (replaces the v1 kind/installment sort; `rowKind` deposits last if present).

- [ ] **Step 1: Write failing widget tests**
  - `schedule loads from /v1/leases/{id}/cheques` (fake service returns two rows; both render).
  - `ticking cleared rows and tapping download calls combined with those ids`.
  - `select year ticks that year's cleared rows`.
- [ ] **Step 2: Run** `cd mobile/apps/manager && flutter test test/lease_detail_receipts_test.dart` → FAIL
- [ ] **Step 3: Implement.** Leave the DEPOSITED mark-failed tap as it is (dead v1 call, separate follow-up).
- [ ] **Step 4: Run** → PASS, then `flutter test` for the manager app (`lease_detail_role_gating_test.dart` must stay green)
- [ ] **Step 5: Commit** `feat(manager-app): lease schedule from cheques; download several receipts`

### Task 8: Full verification

- [ ] Backend (WSL): `cd /mnt/c/rentaxis-wt/multi-receipts/backend && JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true ./gradlew test` → BUILD SUCCESSFUL
- [ ] Web: `cd web && npx vitest run` and `npx tsc --noEmit` → all pass
- [ ] Mobile: `cd mobile && melos run analyze` (or `flutter analyze` per package) and `flutter test` in `packages/rentaxis_core`, `apps/renter`, `apps/manager` → all pass
- [ ] Note in the hand-over: staff mobile Payments tab, mark-failed dialog and single `downloadReceipt` still call the removed `/v1/payments/*`.
