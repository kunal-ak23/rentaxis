# Cheque Amount Extraction + Wizard Bulk Upload — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract the cheque amount via OCR and surface it (suggest-not-fill) in the cheque-capture flows, use it to warn on cheque↔installment amount mismatches, and add the existing bulk cheque upload to the lease wizard's Schedule & finalize step.

**Architecture:** Add `amount` to the Azure OpenAI extraction schema/DTO. Frontend shows the amount as a suggestion chip (booking deposit) and a non-blocking mismatch warning (bulk + per-row). Reuse `BulkChequeUploadFlow` behind a button in the wizard.

**Tech Stack:** Java 21, Spring Boot, Azure OpenAI SDK; Next.js 16 + TypeScript; JUnit + Vitest.

**Spec:** `docs/superpowers/specs/2026-06-02-cheque-amount-extraction-and-wizard-bulk-upload-design.md`

**Dependency:** Implement **after** `feat/lease-charges-rework` is merged — installment amounts then include folded per-installment charges, which the mismatch warning compares against. Work this plan on `feat/cheque-amount-extraction` rebased on the updated `main`.

---

## File Structure

**Backend (modify):**
- `core/service/cheque/AzureOpenAIChequeExtractor.java` — schema + prompt + parse
- `api/dto/ExtractedChequeDTO.java` — add `amount`

**Frontend (modify):**
- `web/src/types/cheque.ts` — add `amount`
- `web/src/components/cheques/ChequeScanner.tsx` — review row + result
- `web/src/app/[locale]/dashboard/leases/LeaseWizard.tsx` — deposit amount suggestion chip + bulk-upload entry button
- `web/src/components/cheques/BulkChequeUploadFlow.tsx` — amount column + mismatch chip
- `web/src/app/[locale]/dashboard/leases/PaymentScheduleEditor.tsx` — per-row mismatch chip
- `web/messages/en.json` — labels

---

## Task 1: Backend — extract amount

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/cheque/AzureOpenAIChequeExtractor.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/dto/ExtractedChequeDTO.java`
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/cheque/AzureOpenAIChequeExtractorParseTest.java` (create)

- [ ] **Step 1: Add `amount` to the DTO** — `ExtractedChequeDTO.java`:

```java
package com.datagami.rentaxis.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ExtractedChequeDTO(
        String chequeNumber,
        String bankName,
        String payerName,
        LocalDate chequeDate,
        BigDecimal amount,
        Confidence confidence
) {
    public enum Confidence { HIGH, MEDIUM, LOW }
}
```

- [ ] **Step 2: Add `amount` to the JSON schema** — in `AzureOpenAIChequeExtractor.SCHEMA`, add the property and include it in `required`:

```
"amount": { "type": ["number", "null"] },
```
and update the `required` array to:
```
"required": ["chequeNumber", "bankName", "payerName", "chequeDate", "amount", "confidence", "warnings"]
```

- [ ] **Step 3: Extend the system prompt** — append to `SYSTEM_PROMPT`:

```
amount is the numeric cheque value from the figures (AED) box; cross-check it against the amount in words. Return a plain number with no thousands separators or currency symbol. Use null if unreadable.
```

- [ ] **Step 4: Parse `amount` in `parseResponse`** — after the `chequeDate` parse block, before constructing the DTO:

```java
            BigDecimal amount = null;
            if (root.hasNonNull("amount")) {
                amount = BigDecimal.valueOf(root.get("amount").asDouble());
            }
```
and update the `ExtractedChequeDTO` constructor call to pass `amount` before `confidence`:
```java
            ExtractedChequeDTO dto = new ExtractedChequeDTO(
                    textOrNull(root, "chequeNumber"),
                    textOrNull(root, "bankName"),
                    textOrNull(root, "payerName"),
                    chequeDate,
                    amount,
                    confidence
            );
```
Add import `java.math.BigDecimal`.

- [ ] **Step 5: Write the parse test** — extract `parseResponse` is private; make it package-private (`ExtractionResult parseResponse(String content)`) so the test can call it.

```java
package com.datagami.rentaxis.core.service.cheque;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AzureOpenAIChequeExtractorParseTest {

    private final AzureOpenAIChequeExtractor extractor = new AzureOpenAIChequeExtractor(null, null);

    @Test
    void parsesAmount() {
        String json = "{\"chequeNumber\":\"CHQ-1\",\"bankName\":\"Emirates NBD\",\"payerName\":\"A\",\"chequeDate\":\"2026-06-01\",\"amount\":15000,\"confidence\":\"HIGH\",\"warnings\":[]}";
        var result = extractor.parseResponse(json);
        assertThat(result.extracted().amount()).isEqualByComparingTo("15000");
    }

    @Test
    void tolueratesNullAmount() {
        String json = "{\"chequeNumber\":\"CHQ-1\",\"bankName\":null,\"payerName\":null,\"chequeDate\":null,\"amount\":null,\"confidence\":\"LOW\",\"warnings\":[]}";
        var result = extractor.parseResponse(json);
        assertThat(result.extracted().amount()).isNull();
    }
}
```
(If constructing `AzureOpenAIChequeExtractor(null, null)` is unsafe because the constructor dereferences args, instead instantiate with a Mockito mock `OpenAIClient`/`AzureOpenAIConfig`; the parse path doesn't use them.)

- [ ] **Step 6: Run the test**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.cheque.AzureOpenAIChequeExtractorParseTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src
git commit -m "feat(cheque): extract cheque amount via OCR"
```

---

## Task 2: Frontend types

**Files:**
- Modify: `web/src/types/cheque.ts`

- [ ] **Step 1: Add `amount`**

```ts
export type ExtractedCheque = {
  chequeNumber: string | null;
  bankName: string | null;
  payerName: string | null;
  chequeDate: string | null;
  amount: number | null;
  confidence: ChequeConfidence;
};
```

- [ ] **Step 2: Build**

Run: `cd web && npx tsc --noEmit 2>&1 | grep cheque | head`
Expected: surfaces consumers needing `amount` (ChequeScanner). Fixed next task.

---

## Task 3: ChequeScanner shows amount + passes it through

**Files:**
- Modify: `web/src/components/cheques/ChequeScanner.tsx`
- Modify: `web/messages/en.json` (cheque.scanner)

- [ ] **Step 1: Add a label** — in `web/messages/en.json` under `cheque.scanner`, add `"fieldAmount": "Amount (AED)"` (and the matching Arabic file if present).

- [ ] **Step 2: Show amount in Step-2 review** — in `Step2Review`, after the `fieldChequeDate` row, add:

```tsx
<FieldRow label={t("fieldAmount")} value={extracted.amount != null ? String(extracted.amount) : null} t={t} />
```
And add `t("fieldAmount")` to the `fields` list in `Step1Upload` (the "What we'll extract" panel).

- [ ] **Step 3: Pass amount in `apply()`** — in the `extracted` branch of `apply()` (lines 74-84), add `amount: extracted.amount,` to the `onExtracted({...})` object.

- [ ] **Step 4: Build**

Run: `cd web && npx tsc --noEmit 2>&1 | grep ChequeScanner | head`
Expected: no errors.

- [ ] **Step 5: Commit**

```bash
git add web/src/components/cheques/ChequeScanner.tsx web/messages/en.json
git commit -m "feat(cheque): show extracted amount in scanner review + result"
```

---

## Task 4: Booking-deposit amount suggestion chip (wizard)

**Files:**
- Modify: `web/src/app/[locale]/dashboard/leases/LeaseWizard.tsx` (booking deposit block, lines ~452-486)

- [ ] **Step 1: Store the scanned amount without overwriting the field** — extend the `bookingDeposit` state shape with `scannedAmount?: number | null` (init `null`). In the `ChequeScanner` `onExtracted` handler (lines 455-466), add `scannedAmount: result.amount ?? null` to the patch (do NOT set `amount`).

- [ ] **Step 2: Render the suggestion chip** — directly under the Amount input (after line 471), add:

```tsx
{data.bookingDeposit.scannedAmount != null && data.bookingDeposit.scannedAmount !== data.bookingDeposit.amount && (
    <button type="button"
        onClick={() => update({ bookingDeposit: { ...data.bookingDeposit, amount: data.bookingDeposit.scannedAmount! } })}
        className="mt-1 inline-flex items-center gap-1 rounded-full border border-primary/40 bg-primary/10 px-2 py-0.5 text-[11px] text-primary">
        From cheque: AED {data.bookingDeposit.scannedAmount} · Apply
    </button>
)}
```

- [ ] **Step 3: Build**

Run: `cd web && npx tsc --noEmit 2>&1 | grep LeaseWizard | head`
Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git add web/src/app/\[locale\]/dashboard/leases/LeaseWizard.tsx
git commit -m "feat(cheque): suggest scanned amount for booking deposit (apply on click)"
```

---

## Task 5: Bulk flow — amount column + mismatch warning

**Files:**
- Modify: `web/src/components/cheques/BulkChequeUploadFlow.tsx`
- Modify: `web/src/components/cheques/useBulkChequeExtract.ts` (if it shapes the per-cheque row, carry `amount` through)

- [ ] **Step 1: Carry `amount` into the row** — wherever the extraction result is mapped into a row (the `Row` type and `initialRows` building), add `amount: number | null` from the extraction response (`extracted.amount ?? null`).

- [ ] **Step 2: Add an Amount column** — add a header `<th>{tBulk("colAmount")}</th>` (add `"colAmount": "Amount"` to `web/messages/en.json` under `bulkChequeUpload`) and a cell rendering `row.amount != null ? formatCurrency(row.amount) : "—"`.

- [ ] **Step 3: Mismatch chip** — where each row's mapped schedule is resolved (`const sched = pendingSchedules.find(...)`, line ~369), compute and render:

```tsx
{sched && row.amount != null && Math.round(row.amount * 100) !== Math.round(Number(sched.amount) * 100) && (
    <span className="ml-2 inline-flex items-center gap-1 rounded-full border border-amber-300 bg-amber-50 px-2 py-0.5 text-[11px] text-amber-800">
        Cheque {formatCurrency(row.amount)} ≠ installment {formatCurrency(Number(sched.amount))}
    </span>
)}
```
This is display-only — it must NOT block the approve/attach action.

- [ ] **Step 4: Build**

Run: `cd web && npx tsc --noEmit 2>&1 | grep BulkChequeUpload | head`
Expected: no errors.

- [ ] **Step 5: Update/extend the bulk test** — in `web/src/components/cheques/__tests__/BulkChequeUploadFlow.test.tsx`, add a case: an extraction with `amount` differing from the mapped installment renders the mismatch text; approve is still enabled.

Run: `cd web && npx vitest run src/components/cheques/__tests__/BulkChequeUploadFlow.test.tsx`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add web/src/components/cheques web/messages/en.json
git commit -m "feat(cheque): bulk flow amount column + non-blocking mismatch warning"
```

---

## Task 6: Per-row scan mismatch chip (PaymentScheduleEditor)

**Files:**
- Modify: `web/src/app/[locale]/dashboard/leases/PaymentScheduleEditor.tsx`

- [ ] **Step 1: Capture scanned amount per row** — the editor already wires `ChequeScanner` per row via `onExtracted` (search `ChequeScanner`). Store the scanned amount in component state keyed by row id: `const [scannedAmounts, setScannedAmounts] = useState<Record<string, number | null>>({})`; in the per-row `onExtracted`, set `setScannedAmounts(prev => ({ ...prev, [r.id]: result.amount ?? null }))`.

- [ ] **Step 2: Render the chip** near the row's amount input:

```tsx
{scannedAmounts[r.id] != null && Math.round(scannedAmounts[r.id]! * 100) !== Math.round(r.amount * 100) && (
    <span className="ml-2 rounded-full border border-amber-300 bg-amber-50 px-2 py-0.5 text-[11px] text-amber-800">
        Cheque {formatCurrency(scannedAmounts[r.id]!)} ≠ {formatCurrency(r.amount)}
    </span>
)}
```

- [ ] **Step 3: Build + commit**

Run: `cd web && npx tsc --noEmit 2>&1 | grep PaymentScheduleEditor | head` (expect none)
```bash
git add web/src/app/\[locale\]/dashboard/leases/PaymentScheduleEditor.tsx
git commit -m "feat(cheque): per-row amount mismatch warning in schedule editor"
```

---

## Task 7: Bulk upload in the wizard's Schedule & finalize step

**Files:**
- Modify: `web/src/app/[locale]/dashboard/leases/LeaseWizard.tsx` (finalize step, around lines 494-531)

- [ ] **Step 1: Fetch schedules for the draft + add the button** — in the finalize step (where `PaymentScheduleEditor leaseId={savedLeaseId}` renders), add a "Bulk upload cheques" button above the editor that opens `BulkChequeUploadFlow`. Fetch the lease's schedules via the lease-scoped endpoint (the fix branch's endpoint):

```tsx
const [bulkOpen, setBulkOpen] = useState(false);
const [wizardSchedules, setWizardSchedules] = useState<any[]>([]);
const loadWizardSchedules = async () => {
    const res = await fetch(`/api/proxy/v1/payments/lease/${savedLeaseId}`);
    if (res.ok) setWizardSchedules(await res.json());
};
```
Button + modal (near the editor):
```tsx
<button type="button" onClick={async () => { await loadWizardSchedules(); setBulkOpen(true); }}
    className="rounded border border-border px-3 py-1 text-xs">Bulk upload cheques</button>
{bulkOpen && savedLeaseId && (
    <BulkChequeUploadFlow
        leaseId={savedLeaseId}
        schedules={wizardSchedules.map(p => ({ id: p.id, installmentNumber: p.installmentNumber, dueDate: p.dueDate, amount: p.amount, status: p.status }))}
        onSuccess={() => { setBulkOpen(false); setScheduleRefreshKey(k => k + 1); }}
        onClose={() => setBulkOpen(false)}
    />
)}
```
Import `BulkChequeUploadFlow`; reuse/define `scheduleRefreshKey` passed to `PaymentScheduleEditor` `refreshKey` so the table reloads after attach.

- [ ] **Step 2: Build**

Run: `cd web && npx tsc --noEmit 2>&1 | grep LeaseWizard | head`
Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git add web/src/app/\[locale\]/dashboard/leases/LeaseWizard.tsx
git commit -m "feat(cheque): bulk upload cheques entry in lease wizard schedule step"
```

---

## Task 8: Manual verification

- [ ] **Step 1:** Run backend + web. Regenerate the 7 test cheque images if needed (`python3 gen_test_cheques.py`).
- [ ] **Step 2:** Booking deposit: scan `CHQ-2026-007` → the AMOUNT field stays as typed, but a "From cheque: AED 15000 · Apply" chip appears; clicking sets 15000.
- [ ] **Step 3:** Wizard schedule step → "Bulk upload cheques" → pick the test folder → OCR extracts amounts → map → a cheque whose amount ≠ the installment amount shows the mismatch chip; attach still works.
- [ ] **Step 4:** Single per-row "Scan Cheque" on an installment whose amount differs shows the per-row mismatch chip.

---

## Self-Review notes (author)
- Spec coverage: backend amount (T1), types (T2), scanner review+result (T3), deposit suggestion chip (T4), bulk column+mismatch (T5), per-row mismatch (T6), wizard bulk entry (T7), manual (T8).
- Suggest-not-fill honored: T3/T4 never auto-set the amount field; only the chip applies it.
- Mismatch is non-blocking everywhere (T5/T6).
- Depends on lease-charges merge so installment amounts (incl. folded per-installment charges) are what cheques are compared against.
```
