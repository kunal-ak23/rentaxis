# Dynamic Payment Schedule - Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the fixed cheque-count dropdown (1/2/4/6/12) with automatic monthly payment schedule generation that respects property-level due day configuration, supports pro-rata first/last months, and shows an editable preview table in the lease form.

**Architecture:** The backend `PaymentScheduleService.generateScheduleForLease()` is rewritten to calculate months between lease dates, look up the property's `RentCollectionSettings.dueDayOfMonth` (default 1st), and generate one payment per month with pro-rata amounts for partial first/last months. The frontend removes the fixed dropdown and replaces it with a live preview table that recomputes whenever start date, end date, or rent amount changes. Amounts are editable before saving. The payment method (CHEQUE/ONLINE) defaults based on the property's `RentCollectionSettings.onlinePaymentEnabled` but can be overridden per lease.

**Tech Stack:** Java 21 + Spring Boot 4.0.3 + Spring Data JPA (backend), Next.js 16 + TypeScript + Tailwind CSS 4 (frontend)

---

## Task 1: Add Preview Endpoint for Payment Schedule

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/PaymentPreviewDTO.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/PaymentScheduleService.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/PaymentScheduleController.java`

**Step 1: Create PaymentPreviewDTO**

```java
package com.datagami.rentaxis.api.dto;

import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
public class PaymentPreviewDTO {
    private List<PaymentPreviewLine> lines;
    private BigDecimal totalAmount;
    private int totalPayments;
    private int dueDayOfMonth;
    private String defaultPaymentMethod; // "CHEQUE" or "ONLINE" based on property settings

    @Getter
    @Setter
    public static class PaymentPreviewLine {
        private int installmentNumber;
        private LocalDate dueDate;
        private LocalDate periodStart;
        private LocalDate periodEnd;
        private BigDecimal amount;
        private boolean proRata;
    }
}
```

**Step 2: Add preview calculation method to PaymentScheduleService**

Add a new public method `previewSchedule` that calculates the schedule without saving:

```java
@Transactional(readOnly = true)
public PaymentPreviewDTO previewSchedule(UUID propertyId, LocalDate startDate, LocalDate endDate, BigDecimal rentAmount) {
    // Look up property's due day (default to 1 if not configured)
    int dueDay = rentCollectionSettingsRepository.findByPropertyId(propertyId)
            .map(RentCollectionSettings::getDueDayOfMonth)
            .orElse(1);
    if (dueDay < 1 || dueDay > 28) dueDay = 1; // safety: avoid 29/30/31 edge cases

    List<PaymentPreviewDTO.PaymentPreviewLine> lines = new ArrayList<>();
    BigDecimal dailyRate = rentAmount.divide(
            BigDecimal.valueOf(startDate.until(endDate).getDays()), 10, RoundingMode.HALF_UP);

    // Calculate first due date after lease start
    LocalDate firstDueDate;
    if (startDate.getDayOfMonth() == dueDay) {
        firstDueDate = startDate;
    } else if (startDate.getDayOfMonth() < dueDay) {
        firstDueDate = startDate.withDayOfMonth(dueDay);
    } else {
        firstDueDate = startDate.plusMonths(1).withDayOfMonth(dueDay);
    }

    int installment = 1;
    LocalDate periodStart = startDate;

    // If lease start != first due date, create pro-rata first payment
    if (!startDate.equals(firstDueDate)) {
        long days = startDate.until(firstDueDate).getDays();
        BigDecimal proRataAmount = dailyRate.multiply(BigDecimal.valueOf(days)).setScale(2, RoundingMode.HALF_UP);

        PaymentPreviewDTO.PaymentPreviewLine line = new PaymentPreviewDTO.PaymentPreviewLine();
        line.setInstallmentNumber(installment++);
        line.setDueDate(startDate);
        line.setPeriodStart(startDate);
        line.setPeriodEnd(firstDueDate.minusDays(1));
        line.setAmount(proRataAmount);
        line.setProRata(true);
        lines.add(line);
        periodStart = firstDueDate;
    }

    // Generate monthly payments from first due date
    LocalDate currentDue = periodStart;
    while (currentDue.isBefore(endDate)) {
        LocalDate nextDue = currentDue.plusMonths(1).withDayOfMonth(dueDay);
        LocalDate periodEnd;

        if (!nextDue.isAfter(endDate)) {
            periodEnd = nextDue.minusDays(1);
        } else {
            // Last period: goes to lease end
            periodEnd = endDate;
            nextDue = endDate.plusDays(1); // sentinel to exit loop
        }

        long days = currentDue.until(periodEnd.plusDays(1)).getDays();
        BigDecimal amount = dailyRate.multiply(BigDecimal.valueOf(days)).setScale(2, RoundingMode.HALF_UP);

        PaymentPreviewDTO.PaymentPreviewLine line = new PaymentPreviewDTO.PaymentPreviewLine();
        line.setInstallmentNumber(installment++);
        line.setDueDate(currentDue);
        line.setPeriodStart(currentDue);
        line.setPeriodEnd(periodEnd);
        line.setAmount(amount);
        line.setProRata(periodEnd.equals(endDate) && days < 28);
        lines.add(line);

        currentDue = nextDue.isAfter(endDate) ? endDate.plusDays(1) : nextDue;
    }

    // Adjust last payment so total exactly equals rentAmount
    BigDecimal calculatedTotal = lines.stream()
            .map(PaymentPreviewDTO.PaymentPreviewLine::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal diff = rentAmount.subtract(calculatedTotal);
    if (!diff.equals(BigDecimal.ZERO) && !lines.isEmpty()) {
        PaymentPreviewDTO.PaymentPreviewLine last = lines.get(lines.size() - 1);
        last.setAmount(last.getAmount().add(diff));
    }

    PaymentPreviewDTO dto = new PaymentPreviewDTO();
    dto.setLines(lines);
    dto.setTotalAmount(rentAmount);
    dto.setTotalPayments(lines.size());
    return dto;
}
```

Inject `RentCollectionSettingsRepository` into `PaymentScheduleService` constructor.

**Step 3: Add preview endpoint to PaymentScheduleController**

```java
@GetMapping("/preview")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
public ResponseEntity<PaymentPreviewDTO> previewSchedule(
        @RequestParam UUID propertyId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
        @RequestParam BigDecimal rentAmount) {
    return ResponseEntity.ok(paymentScheduleService.previewSchedule(propertyId, startDate, endDate, rentAmount));
}
```

Add necessary imports: `PaymentPreviewDTO`, `DateTimeFormat`, `LocalDate`, `BigDecimal`.

**Step 4: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api/dto/PaymentPreviewDTO.java
git add backend/src/main/java/com/datagami/rentaxis/core/service/PaymentScheduleService.java
git add backend/src/main/java/com/datagami/rentaxis/api/PaymentScheduleController.java
git commit -m "feat: add payment schedule preview endpoint with pro-rata support"
```

---

## Task 2: Rewrite generateScheduleForLease to Use Pro-Rata Logic

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/PaymentScheduleService.java`

**Step 1: Rewrite generateScheduleForLease**

Replace the existing `generateScheduleForLease` method. The new version:
- Ignores `lease.getPaymentTerms()` — always calculates months from dates
- Looks up property's due day from `RentCollectionSettings`
- Generates pro-rata first/last payments when needed
- Accepts optional custom amounts from the DTO (for landlord overrides)

```java
@Transactional
public List<PaymentSchedule> generateScheduleForLease(Lease lease) {
    List<PaymentSchedule> existing = paymentScheduleRepository.findByLeaseId(lease.getId());
    if (!existing.isEmpty()) {
        return existing;
    }

    PaymentPreviewDTO preview = previewSchedule(
            lease.getUnit().getProperty().getId(),
            lease.getStartDate(),
            lease.getEndDate(),
            lease.getRentAmount());

    List<PaymentSchedule> schedules = new ArrayList<>();
    for (PaymentPreviewDTO.PaymentPreviewLine line : preview.getLines()) {
        PaymentSchedule ps = new PaymentSchedule();
        ps.setLease(lease);
        ps.setUnit(lease.getUnit());
        ps.setProperty(lease.getUnit().getProperty());
        ps.setInstallmentNumber(line.getInstallmentNumber());
        ps.setDueDate(line.getDueDate());
        ps.setAmount(line.getAmount());
        ps.setStatus(PaymentStatus.PENDING);
        ps.setPaymentMethod(lease.getPaymentMethod() != null ? lease.getPaymentMethod().name() : "CHEQUE");
        schedules.add(ps);
    }

    return paymentScheduleRepository.saveAll(schedules);
}
```

**Step 2: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/PaymentScheduleService.java
git commit -m "feat: rewrite schedule generation to use pro-rata monthly logic"
```

---

## Task 3: Remove paymentTerms Dropdown, Add Preview Table to Frontend

**Files:**
- Modify: `web/src/app/[locale]/dashboard/leases/page.tsx`

**Step 1: Remove the paymentTerms dropdown**

Replace the `<select>` dropdown for paymentTerms (lines 569-578) with an auto-calculated display:

```tsx
<div className="col-span-1">
    <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">{t("paymentTerms")}</label>
    <div className="w-full bg-gray-50 border border-border p-3 rounded-xl text-xs text-gray-600 font-medium">
        {paymentPreview ? `${paymentPreview.totalPayments} ${formData.paymentMethod === 'CHEQUE' ? 'Cheques' : 'Payments'}` : '—'}
    </div>
</div>
```

**Step 2: Add preview state and fetch logic**

Add state and effect near other state declarations:

```tsx
const [paymentPreview, setPaymentPreview] = useState<{
    lines: { installmentNumber: number; dueDate: string; periodStart: string; periodEnd: string; amount: number; proRata: boolean }[];
    totalAmount: number;
    totalPayments: number;
} | null>(null);
const [previewLoading, setPreviewLoading] = useState(false);
```

Add a `useEffect` that fetches the preview whenever start date, end date, rent amount, or property changes:

```tsx
useEffect(() => {
    if (!formData.startDate || !formData.endDate || !formData.rentAmount || !formData.unitId) {
        setPaymentPreview(null);
        return;
    }
    // Find propertyId from selected unit
    const unit = unitOptions.find(u => u.id === formData.unitId);
    const propertyId = unit?.propertyId;
    if (!propertyId) return;

    const params = new URLSearchParams({
        propertyId,
        startDate: formData.startDate,
        endDate: formData.endDate,
        rentAmount: String(formData.rentAmount),
    });

    setPreviewLoading(true);
    fetch(`/api/proxy/v1/payments/preview?${params}`)
        .then(res => res.ok ? res.json() : null)
        .then(data => setPaymentPreview(data))
        .catch(() => setPaymentPreview(null))
        .finally(() => setPreviewLoading(false));
}, [formData.startDate, formData.endDate, formData.rentAmount, formData.unitId]);
```

Note: The unit options list needs to carry `propertyId`. Check the existing unit fetching logic and ensure `propertyId` is available on each unit option. If not, derive it from the `selectedProperty` or the unit's property relation in the API response.

**Step 3: Add the preview table**

Insert after the payment reference field and before the submit buttons (before line 590):

```tsx
{/* Payment Schedule Preview */}
{paymentPreview && paymentPreview.lines.length > 0 && (
    <div className="col-span-2 mt-2">
        <label className="block text-[10px] font-bold text-gray-400 uppercase mb-2 ml-1">
            Payment Schedule Preview
        </label>
        <div className="border border-border rounded-xl overflow-hidden">
            <table className="w-full">
                <thead>
                    <tr className="bg-gray-50 border-b border-gray-100">
                        <th className="text-left px-4 py-2 text-[10px] font-bold text-gray-400 uppercase">#</th>
                        <th className="text-left px-4 py-2 text-[10px] font-bold text-gray-400 uppercase">Due Date</th>
                        <th className="text-left px-4 py-2 text-[10px] font-bold text-gray-400 uppercase">Period</th>
                        <th className="text-right px-4 py-2 text-[10px] font-bold text-gray-400 uppercase">Amount (AED)</th>
                        <th className="text-center px-4 py-2 text-[10px] font-bold text-gray-400 uppercase">Type</th>
                    </tr>
                </thead>
                <tbody className="divide-y divide-gray-50">
                    {paymentPreview.lines.map((line, i) => (
                        <tr key={i} className="hover:bg-gray-50/50">
                            <td className="px-4 py-2 text-xs text-gray-500">{line.installmentNumber}</td>
                            <td className="px-4 py-2 text-xs font-medium">{line.dueDate}</td>
                            <td className="px-4 py-2 text-[10px] text-gray-400">
                                {line.periodStart} → {line.periodEnd}
                            </td>
                            <td className="px-4 py-2 text-right">
                                <input
                                    type="number"
                                    step="0.01"
                                    className="w-28 text-right bg-input border border-border p-1.5 rounded-lg text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                    value={line.amount}
                                    onChange={(ev) => {
                                        const updated = { ...paymentPreview };
                                        updated.lines = [...updated.lines];
                                        updated.lines[i] = { ...updated.lines[i], amount: Number(ev.target.value) };
                                        updated.totalAmount = updated.lines.reduce((s, l) => s + l.amount, 0);
                                        setPaymentPreview(updated);
                                    }}
                                />
                            </td>
                            <td className="px-4 py-2 text-center">
                                {line.proRata ? (
                                    <span className="text-[9px] bg-amber-50 text-amber-600 px-2 py-0.5 rounded-full font-bold">Pro-rata</span>
                                ) : (
                                    <span className="text-[9px] bg-gray-100 text-gray-500 px-2 py-0.5 rounded-full font-bold">Full</span>
                                )}
                            </td>
                        </tr>
                    ))}
                </tbody>
                <tfoot>
                    <tr className="bg-gray-50 border-t-2 border-gray-200">
                        <td colSpan={3} className="px-4 py-2 text-xs font-black uppercase">Total</td>
                        <td className={`px-4 py-2 text-right text-xs font-black ${
                            Math.abs(paymentPreview.totalAmount - formData.rentAmount) > 0.01
                                ? 'text-red-500' : 'text-emerald-600'
                        }`}>
                            {paymentPreview.totalAmount.toLocaleString(undefined, { minimumFractionDigits: 2 })}
                        </td>
                        <td className="px-4 py-2 text-center text-[10px] text-gray-400">
                            {paymentPreview.totalPayments} payments
                        </td>
                    </tr>
                </tfoot>
            </table>
            {Math.abs(paymentPreview.totalAmount - formData.rentAmount) > 0.01 && (
                <div className="px-4 py-2 bg-red-50 text-red-600 text-[10px] font-bold">
                    ⚠ Total ({paymentPreview.totalAmount.toFixed(2)}) does not match rent amount ({formData.rentAmount.toFixed(2)}). Adjust amounts to match.
                </div>
            )}
        </div>
    </div>
)}
{previewLoading && (
    <div className="col-span-2 flex items-center gap-2 text-xs text-gray-400">
        <Loader2 size={14} className="animate-spin" />
        Calculating payment schedule...
    </div>
)}
```

**Step 4: Remove paymentTerms from formData and submission**

In the `formData` state initialization, remove `paymentTerms` or leave it unused. In the `handleSubmit`, the DTO still has `paymentTerms` but the backend will now ignore it.

**Step 5: Commit**

```bash
git add web/src/app/[locale]/dashboard/leases/page.tsx
git commit -m "feat: replace fixed payment terms with live preview table and editable amounts"
```

---

## Task 4: Remove paymentTerms from Backend DTO (Optional Cleanup)

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/dto/CreateLeaseDTO.java`

**Step 1: Mark paymentTerms as deprecated (don't remove yet for backward compat)**

The field can stay in the DTO but is now ignored by the schedule generation. Add a comment:

```java
/** @deprecated Payment terms are now auto-calculated from lease duration. */
@Min(1)
private Integer paymentTerms;
```

**Step 2: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api/dto/CreateLeaseDTO.java
git commit -m "chore: deprecate paymentTerms field (auto-calculated from lease dates)"
```

---

## Summary

| Task | Description | Files Changed |
|------|-------------|---------------|
| 1 | Preview endpoint with pro-rata logic | PaymentPreviewDTO (new), PaymentScheduleService, PaymentScheduleController |
| 2 | Rewrite generateScheduleForLease | PaymentScheduleService |
| 3 | Frontend preview table + remove dropdown | leases/page.tsx |
| 4 | Deprecate paymentTerms DTO field | CreateLeaseDTO |

**Total:** 4 tasks, 1 new file, 4 modified files, 0 migrations needed
