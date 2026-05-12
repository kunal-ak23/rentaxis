# Bulk Cheque Upload — Design Spec

**Date:** 2026-05-07
**Status:** Draft, pending implementation plan
**Scope:** Web only (manager dashboard); backend adds one new endpoint; mobile out of scope for v1

---

## 1. Goal

A property manager often receives a stack of post-dated cheques (typically one per month for a 12-month lease) at lease signing. Today they must run the single-cheque scan wizard once per cheque — slow and error-prone for a 12-cheque batch. This feature adds a "Bulk upload cheques" flow on the lease detail page that:

- Accepts a folder of cheque images for one lease in one action.
- Runs the existing AI extractor on each image in parallel.
- Auto-suggests a mapping from each cheque to one of the lease's `PENDING` payment schedules using closest cheque-date → due-date.
- Lets the user review, correct any extraction mistakes, override mapping suggestions, and remove rows.
- On approve, attaches every reviewed cheque to its assigned schedule in a single atomic transaction.

Side benefit: a small `<DueDateDelta>` badge (green if cheque date ≤ due date, red if after, with day-difference) is reused on the lease detail timeline and the global payments page so existing already-collected cheques get the same visual treatment.

---

## 2. Non-goals

- No mobile bulk upload in v1. The 4-step single-cheque wizard in `mobile/apps/manager` stays as-is.
- No new entity. Cheques continue to live as columns on `payment_schedules`.
- No bulk overwrite of already-collected schedules. To correct a previously-attached cheque, the user uses the existing single-cheque edit path. Bulk upload only assigns to `PENDING` schedules.
- No PDF / multi-cheque-image parsing. One image = one cheque. Folder upload only.
- No new "bulk batch ID" or audit-trail entity. Existing `cheque_image_uploaded_at` clustering is sufficient for tracing a batch.
- No bulk summary email. The existing per-cheque `CHEQUE_RECEIVED` event fires N times, governed by the existing tenant kill-switch.

---

## 3. Architecture

### 3.1 Components

| Layer | Component | New / Reuse |
|---|---|---|
| Backend extract | `POST /api/v1/cheques/extract` | Reuse, unchanged |
| Backend bulk-attach | `POST /api/v1/leases/{leaseId}/cheques/bulk-attach` | New |
| Backend service | `LeaseChequeBulkAttachService` (or method on `PaymentScheduleService`, TBD by where the single-cheque collect lives) | New |
| Frontend entry | "Bulk upload cheques" button on `leases/[id]/page.tsx` | New |
| Frontend flow | `BulkChequeUploadFlow` page or wide modal — 3 screens (Pick → Extract → Review) | New |
| Frontend hook | `useBulkChequeExtract` — orchestrates parallel calls to `/extract`, mirrors the existing `useChequeExtraction` style | New |
| Frontend mapping | `autoMapChequesToSchedules` pure function — greedy by closest date | New |
| Frontend shared | `<DueDateDelta>` badge | New, used in 3 places |

### 3.2 Data flow (happy path, 12-cheque example)

1. User clicks "Bulk upload cheques" on the lease detail page. The page already has the lease's payment schedules in scope; we filter to `status === 'PENDING'`.
2. **Pick screen:** `<input type="file" webkitdirectory multiple accept="image/*">`. Non-image files filtered client-side. User can remove individual thumbnails. Continue when ≥1 image selected.
3. **Extract screen:** for each image, frontend POSTs to `/api/v1/cheques/extract` with concurrency capped at 4. Each response (`{image: {url, blobPath, uploadedAt}, extracted: {chequeNumber, bankName, payerName, chequeDate, confidence}, warnings}`) is appended to the client-side `extractedItems` array. Per-row state: `pending` → `extracting` → `extracted` | `failed`.
4. Auto-mapping runs once all extractions resolve (or the user clicks "Continue with what we have"). Pure client-side function.
5. **Review screen:** wide editable table (Section 4). User edits any AI-misread cell, overrides assignments via per-row dropdown, removes rows that don't have a target schedule.
6. User clicks "Approve all". Frontend assembles the array and POSTs to `/api/v1/leases/{leaseId}/cheques/bulk-attach`. Server validates and writes in one transaction. Response includes the updated `PaymentScheduleDTO[]`.
7. UI navigates back to lease detail, shows toast "12 cheques attached".

### 3.3 Permissions

`SUPER_ADMIN`, `TENANT_ADMIN`, `PROPERTY_MANAGER`. Mirrors the single-cheque collect path exactly. `TENANT_USER` and `RENTER` cannot bulk-attach.

---

## 4. Auto-mapping algorithm

Pure client-side, runs once on entering the review screen and again whenever a non-pinned row's cheque date changes.

**Inputs:** `pendingSchedules` (sorted by `dueDate` asc), `extractedItems` (with `chequeDate | null`).

**Algorithm — greedy by absolute day-distance:**

```ts
function autoMap(items, schedules) {
  // Reserve schedules already locked by pinned rows so non-pinned rows don't compete for them.
  const remaining = new Set(schedules.map(s => s.id));
  for (const item of items) {
    if (item.pinned && item.assignedScheduleId) {
      remaining.delete(item.assignedScheduleId);
    }
  }

  const pairs = [];
  for (const item of items) {
    if (item.pinned || !item.chequeDate) continue;
    for (const s of schedules) {
      if (!remaining.has(s.id)) continue;
      pairs.push({ itemId: item.id, scheduleId: s.id,
                   dist: Math.abs(daysBetween(item.chequeDate, s.dueDate)) });
    }
  }
  pairs.sort((a, b) => a.dist - b.dist);

  const assigned = new Set();
  const result = new Map(); // itemId → scheduleId, only for non-pinned rows
  for (const p of pairs) {
    if (assigned.has(p.itemId) || !remaining.has(p.scheduleId)) continue;
    result.set(p.itemId, p.scheduleId);
    assigned.add(p.itemId);
    remaining.delete(p.scheduleId);
  }
  return result;
}
```

The caller merges this `result` into the row state without touching pinned rows, so pinned assignments are preserved.

**Pinning rule:** when a user manually overrides a row's assignment dropdown, that row gets `pinned = true` and is excluded from re-runs. Cheque-date edits on a non-pinned row trigger a re-run.

**Edge cases (each surfaces as visible row state):**

| Situation | Row state |
|---|---|
| Cheque date present, schedule available | `Mapped` — green/red Δ badge |
| Cheque date missing (extractor failure) | `Needs cheque date` — date cell highlighted |
| More cheques than `PENDING` schedules | `No schedule available` — user must remove the row |
| Two cheques nearest the same due date | Greedy picks closer; the other gets next-closest. User can fix via dropdown |
| Lease has zero `PENDING` schedules | Bulk button hidden on lease detail; user never reaches this screen |

---

## 5. `<DueDateDelta>` badge (existing rows extension)

`web/src/components/payments/DueDateDelta.tsx`:

```tsx
// Renders nothing if chequeDate is null. Otherwise:
//   chequeDate <= dueDate → green pill "−{n}d" (or "0d")
//   chequeDate >  dueDate → red pill "+{n}d"
```

Wired into:

1. **Bulk review table** (Section 6).
2. **Lease detail page** — `web/src/app/[locale]/dashboard/leases/[id]/page.tsx` payment timeline rows.
3. **Global payments page** — `web/src/app/[locale]/dashboard/finance/payments/page.tsx`, only if the table already shows `chequeDate` alongside `dueDate`. If it doesn't, skip — adding the column would be unrelated scope.

Pure rendering. Existing `COLLECTED` / `DEPOSITED` / `CLEARED` / `BOUNCED` rows already carry both dates from the single-cheque flow.

---

## 6. Review table UI

```
┌──────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│ Review 12 cheques · Lease #LSE-0042 · 12 PENDING installments                              [Cancel] [Approve]│
├──────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│  Img    Cheque #     Bank          Payer            Cheque date    →  Installment (due)        Δ        ⋯   │
│ ┌───┐                                                                                                       │
│ │📷 │  [123456  ]   [Emirates NBD]  [John Renter ]  [2026-06-01 ▾]  ▸ [Jun 2026 (Jun 5) ▾]   −4d 🟢    [✕] │
│ └───┘                                                                                                       │
│ ┌───┐                                                                                                       │
│ │📷 │  [123457  ]   [Emirates NBD]  [John Renter ]  [2026-07-08 ▾]  ▸ [Jul 2026 (Jul 5) ▾]   +3d 🔴    [✕] │
│ └───┘                                                                                                       │
│ ┌───┐                                                                                                       │
│ │📷 │  [        ]   [             ] [            ]  [—         ▾]  ▸ [— Pick installment ▾]   —  ⚠     [✕] │
│ │   │  Extraction failed — please fill in or remove                                                        │
│ └───┘                                                                                                       │
└──────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
   Approve all (10/12 ready)    [12 selected · 1 needs date · 1 has no schedule]
```

**Column rules:**

- **Img** — thumbnail; click opens the blob URL in a new tab.
- **Cheque #, Bank, Payer** — text inputs, pre-filled. Editable. Yellow border when extractor confidence = LOW.
- **Cheque date** — date picker, pre-filled. Editing a non-pinned row's date re-runs auto-mapping for non-pinned rows.
- **Installment dropdown** — sorted by `installmentNumber`, format `Jul 2026 (Jul 5)` showing month label + due date + monthly amount in the option list. Only `PENDING` schedules. Selecting one removes it from every other row's dropdown. Picking from the dropdown pins the row.
- **Δ column** — `<DueDateDelta>`. Recomputes on date or installment change.
- **Pin indicator** — small icon next to Δ on rows with a manual override. Click to unpin.
- **Row delete (✕)** — removes the row. Blob is **not** deleted; the existing `ChequeImageRetentionJob` prunes orphans.

**Approve all** — disabled if any row is in error state (no cheque date, no schedule selected, duplicate cheque #). Tooltip lists why. Click → POST `bulk-attach` → toast → navigate back.

**Counts strip** at the bottom: `N selected · X needs date · Y has no schedule · Z duplicate cheque #`.

**RTL:** column direction reverses; sign character (`−`/`+`) and color stay the same; date picker uses `next-intl` locale.

---

## 7. API contract

### 7.1 Reused, unchanged

```
POST /api/v1/cheques/extract
Content-Type: multipart/form-data
field: file
```

Response: `ChequeExtractionResponseDTO { image, extracted, warnings }`.

### 7.2 New: bulk-attach

```
POST /api/v1/leases/{leaseId}/cheques/bulk-attach
Content-Type: application/json
@PreAuthorize hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','PROPERTY_MANAGER')
```

**Request:**

```json
{
  "items": [
    {
      "scheduleId": "uuid",
      "chequeNumber": "123456",
      "chequeDate": "2026-06-01",
      "bankName": "Emirates NBD",
      "payerName": "John Renter",
      "imageUrl": "https://blob.../cheque.jpg",
      "imageBlobPath": "tenant/cheques/abc.jpg",
      "imageUploadedAt": "2026-05-07T10:23:11Z"
    }
  ]
}
```

**Server validation (all-or-nothing — single transaction, any failure aborts the whole batch):**

1. Lease exists and belongs to the caller's tenant. (`403` cross-tenant; `404` not found.)
2. `items` non-empty (`400`).
3. Every `scheduleId` belongs to the lease and is currently `status = PENDING` (`400` not in lease; `409` not PENDING — body lists offending IDs).
4. No duplicate `scheduleId` within the request (`400`).
5. No duplicate `chequeNumber` within the request (`400`).
6. `chequeNumber` not already used by any other schedule on the same lease — mirrors the single-cheque collect rule (`400`).
7. Required fields present per row (`400`).

**On success:** each schedule gets cheque fields stamped, `status` flips `PENDING → COLLECTED`, `statusChangedAt = now()`. Each row fires the existing `CHEQUE_RECEIVED` email event. Response `200`:

```json
{ "schedules": [PaymentScheduleDTO, ...] }
```

The client uses this to refresh the lease detail without a separate fetch.

**Error response shape** (uniform across `400` / `409`):

```json
{
  "error": "validation_failed",
  "rows": [{ "scheduleId": "uuid", "reason": "schedule_not_pending" }, ...]
}
```

The frontend maps these back to specific row error states.

---

## 8. Edge cases

| # | Situation | Behavior |
|---|---|---|
| 1 | Folder contains non-image files | Filtered client-side via `accept`; informational toast lists how many were dropped |
| 2 | Image > 10 MB | `/extract` returns 413; row marked "File too large", removable |
| 3 | All extractions fail | Approve disabled; "Retry all" button re-runs `/extract` only on `failed` rows |
| 4 | Network drops mid-extract | Per-row state stays `extracting`; row-level "Retry" button |
| 5 | User leaves the page mid-flow | `beforeunload` warning if any row is `extracting` or `extracted`-not-yet-approved. Already-uploaded blobs get cleaned by `ChequeImageRetentionJob` |
| 6 | Concurrent: schedule collected by another user between extract and approve | Server returns `409` with the offending `scheduleId`; UI marks that row red ("Already collected — remove or re-assign"); other rows untouched; user can re-Approve |
| 7 | Duplicate cheque # within batch | Caught client-side; both rows show "Duplicate cheque #"; Approve disabled |
| 8 | Cheque # already exists on a different schedule | Server returns `400`; row gets inline error |
| 9 | Lease has zero `PENDING` schedules | Bulk button hidden / disabled on lease detail |
| 10 | Tenant `EMAIL_NOTIFICATIONS=false` | `CHEQUE_RECEIVED` fires per row, outbox skips per existing kill-switch — bulk doesn't bypass anything |
| 11 | RTL / AR locale | Column direction reverses; badge colors and sign characters unchanged; date picker uses `next-intl` locale |

---

## 9. Testing

### 9.1 Backend

`backend/src/test/java/com/datagami/rentaxis/api/LeaseChequeBulkAttachControllerTest.java` — Spring + Testcontainers, mirrors `AuthControllerSetPasswordTest` pattern (PR #45).

Cases:
- Happy path: 3 cheques attached, statuses flip to `COLLECTED`, `chequeImageUploadedAt` stamped, 3 `CHEQUE_RECEIVED` events fired.
- Cross-tenant lease → `403`.
- Schedule not in this lease → `400` with row-level reason.
- Schedule not `PENDING` → `409` with row-level reason.
- Duplicate `scheduleId` in payload → `400`.
- Duplicate `chequeNumber` in payload → `400`.
- `chequeNumber` already used elsewhere on the lease → `400`.
- Empty `items` array → `400`.
- One bad row aborts the whole batch (no partial writes) — verified by reading `payment_schedules` after the failed call and confirming none were updated.

### 9.2 Frontend (web)

`web/src/components/cheques/__tests__/BulkChequeUploadFlow.test.tsx` — Vitest + Testing Library, MSW mocks for `/extract` and `/bulk-attach`, mirrors `ChequeScanner.test.tsx` style.

Cases:
- Folder pick filters non-image files.
- Auto-mapping greedily assigns 3 cheques to 3 closest due dates.
- Manual dropdown override pins the row; subsequent date edits don't unpin it.
- Δ badge color matches direction (cheque before due → green; after → red).
- Approve disabled when any row missing date / schedule / duplicate cheque #.
- 409 from server marks the offending row but leaves the rest editable.
- "Retry all" re-runs `/extract` only on failed rows.

`web/src/components/payments/__tests__/DueDateDelta.test.tsx` — render snapshots for before / equal / after / null cases.

### 9.3 Manual smoke

After merge, before announcing:

1. Create a 6-month test lease on the QA tenant (`Email QA Phase 1`, `bdcbe284-52dd-46f8-a63a-286022c12962`).
2. Upload 6 cheque images via the bulk flow.
3. Verify auto-mapping assigns each to the closest due date.
4. Verify Δ badge colors match expectations.
5. Approve. Verify all 6 `payment_schedules` rows show `COLLECTED` on lease detail with the new badge.
6. Verify 6 `CHEQUE_RECEIVED` events landed in the outbox.

---

## 10. File touch list

**Backend (new):**
- `backend/src/main/java/com/datagami/rentaxis/api/LeaseChequeBulkAttachController.java` (or method added to existing `LeaseController` — TBD by code organization at task time)
- `backend/src/main/java/com/datagami/rentaxis/api/dto/BulkAttachChequesRequest.java`
- `backend/src/main/java/com/datagami/rentaxis/api/dto/BulkAttachChequeItem.java`
- `backend/src/test/java/com/datagami/rentaxis/api/LeaseChequeBulkAttachControllerTest.java`

**Backend (modify):**
- The service that today implements single-cheque collect (TBD — likely `PaymentScheduleService` or a `LeaseChequeService`) gains a `bulkAttach(leaseId, items)` method that runs the validation pipeline and the transactional write.

**Web (new):**
- `web/src/app/[locale]/dashboard/leases/[id]/cheques/bulk/page.tsx` (or modal in lease detail — TBD by existing patterns at task time)
- `web/src/components/cheques/BulkChequeUploadFlow.tsx`
- `web/src/components/cheques/useBulkChequeExtract.ts`
- `web/src/components/cheques/autoMapChequesToSchedules.ts`
- `web/src/components/payments/DueDateDelta.tsx`
- Tests: `BulkChequeUploadFlow.test.tsx`, `DueDateDelta.test.tsx`, `autoMapChequesToSchedules.test.ts`

**Web (modify):**
- `web/src/app/[locale]/dashboard/leases/[id]/page.tsx` — add "Bulk upload cheques" entry point + render `<DueDateDelta>` on payment timeline rows
- `web/src/app/[locale]/dashboard/finance/payments/page.tsx` — render `<DueDateDelta>` on rows that show both dates (skip if column not present)
- `web/src/messages/en.json` + `web/src/messages/ar.json` — new `BulkChequeUpload` and `DueDateDelta` namespaces

---

## 11. Out of scope / follow-ups

- Mobile bulk upload (manager app). Defer until web flow has been used in production for at least one billing cycle.
- Bulk overwrite of already-collected schedules. Single-cheque edit covers this for now.
- Cross-lease bulk (e.g., upload an entire month's cheques across multiple leases). Different mental model; address only if requested.
- Server-side amount extraction. The extractor doesn't read amounts today; adding it would let us flag value mismatches automatically. Worth doing later as a separate enhancement to the extractor.
