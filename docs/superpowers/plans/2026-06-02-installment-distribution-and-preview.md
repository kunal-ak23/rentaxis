# Installment Distribution + Live Preview — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use `- [ ]` checkboxes.

**Goal:** Let users choose how the rent rounding-remainder is distributed (UNIFORM / FIRST_LARGER / LAST_LARGER / FIRST_AND_LAST_LARGER), persist it on the lease, and show a live installment preview on the wizard's Payment Plan step before drafting.

**Spec:** `docs/superpowers/specs/2026-06-02-installment-distribution-and-preview-design.md`
**Branch:** `feat/lease-charges-rework` (stacked on PR #59).

---

## Task 1: Enum + migration + Lease entity

**Files:**
- Create `backend/.../domain/entity/enums/InstallmentDistribution.java`
- Create `backend/src/main/resources/db/changelog/changesets/62-installment-distribution.yaml`
- Modify `backend/.../domain/entity/Lease.java`

- [ ] **Step 1: Enum**
```java
package com.datagami.rentaxis.domain.entity.enums;

public enum InstallmentDistribution {
    UNIFORM, FIRST_LARGER, LAST_LARGER, FIRST_AND_LAST_LARGER
}
```
- [ ] **Step 2: Migration**
```yaml
databaseChangeLog:
  - changeSet:
      id: 62-installment-distribution
      author: rentaxis-system
      changes:
        - addColumn:
            tableName: leases
            columns:
              - column: { name: installment_distribution, type: varchar(30), constraints: { nullable: false }, defaultValue: "LAST_LARGER" }
```
Confirm it's picked up (same include mechanism as `61-lease-charges.yaml`).
- [ ] **Step 3: Lease entity** — add near `paymentTerms`:
```java
    @Enumerated(EnumType.STRING)
    @Column(name = "installment_distribution", nullable = false, length = 30)
    private InstallmentDistribution installmentDistribution = InstallmentDistribution.LAST_LARGER;
```
- [ ] **Step 4:** `cd backend && ./gradlew compileJava` → SUCCESSFUL. Commit.

---

## Task 2: ChequeRoundingCalculator strategies (TDD)

**Files:**
- Modify `backend/.../core/service/ChequeRoundingCalculator.java`
- Test `backend/.../core/service/ChequeRoundingCalculatorStrategyTest.java` (create)

- [ ] **Step 1: Write failing tests** covering each strategy (exact sum, remainder placement, cap):
```java
package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.enums.InstallmentDistribution;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ChequeRoundingCalculatorStrategyTest {
    private static BigDecimal sum(List<BigDecimal> xs){ return xs.stream().reduce(BigDecimal.ZERO, BigDecimal::add); }

    @Test void lastLargerUnchanged() {
        var r = ChequeRoundingCalculator.distribute(new BigDecimal("35000"), 6, null, InstallmentDistribution.LAST_LARGER).amounts();
        assertThat(sum(r)).isEqualByComparingTo("35000");
        assertThat(r.get(r.size()-1)).isEqualByComparingTo(r.stream().max(BigDecimal::compareTo).get()); // last is largest
        // identical to legacy 3-arg overload
        assertThat(r).isEqualTo(ChequeRoundingCalculator.distribute(new BigDecimal("35000"),6,null).amounts());
    }
    @Test void firstLarger() {
        var r = ChequeRoundingCalculator.distribute(new BigDecimal("35000"), 6, null, InstallmentDistribution.FIRST_LARGER).amounts();
        assertThat(sum(r)).isEqualByComparingTo("35000");
        assertThat(r.get(0)).isEqualByComparingTo(r.stream().max(BigDecimal::compareTo).get());
    }
    @Test void uniformAllEqualWithinACent() {
        var r = ChequeRoundingCalculator.distribute(new BigDecimal("35000"), 6, null, InstallmentDistribution.UNIFORM).amounts();
        assertThat(sum(r)).isEqualByComparingTo("35000");
        BigDecimal min = r.stream().min(BigDecimal::compareTo).get(), max = r.stream().max(BigDecimal::compareTo).get();
        assertThat(max.subtract(min)).isLessThanOrEqualTo(new BigDecimal("0.01"));
    }
    @Test void bothLargerSplitsRemainder() {
        var r = ChequeRoundingCalculator.distribute(new BigDecimal("35000"), 6, null, InstallmentDistribution.FIRST_AND_LAST_LARGER).amounts();
        assertThat(sum(r)).isEqualByComparingTo("35000");
        assertThat(r.get(0)).isGreaterThan(r.get(1));
        assertThat(r.get(5)).isGreaterThan(r.get(1));
    }
    @Test void singleCheque() {
        var r = ChequeRoundingCalculator.distribute(new BigDecimal("35000"), 1, null, InstallmentDistribution.UNIFORM).amounts();
        assertThat(r).containsExactly(new BigDecimal("35000"));
    }
}
```
- [ ] **Step 2: Run → fail** (`distribute` 4-arg not defined). `cd backend && ./gradlew test --tests '*ChequeRoundingCalculatorStrategyTest'`
- [ ] **Step 3: Implement** — add the strategy overload, keep the 3-arg delegating to LAST_LARGER:
```java
private static final BigDecimal CENT = new BigDecimal("0.01");

public static Result distribute(BigDecimal totalRent, int n, BigDecimal depositCap) {
    return distribute(totalRent, n, depositCap, com.datagami.rentaxis.domain.entity.enums.InstallmentDistribution.LAST_LARGER);
}

public static Result distribute(BigDecimal totalRent, int n, BigDecimal depositCap,
                                com.datagami.rentaxis.domain.entity.enums.InstallmentDistribution strategy) {
    if (totalRent == null || totalRent.signum() <= 0) throw new IllegalArgumentException("totalRent must be > 0");
    if (n < 1) throw new IllegalArgumentException("n must be >= 1");
    if (strategy == null) strategy = com.datagami.rentaxis.domain.entity.enums.InstallmentDistribution.LAST_LARGER;
    boolean capActive = depositCap != null && depositCap.signum() > 0;
    BigDecimal nBd = BigDecimal.valueOf(n);

    if (n == 1) return new Result(List.of(totalRent), null);

    if (strategy == com.datagami.rentaxis.domain.entity.enums.InstallmentDistribution.UNIFORM) {
        BigDecimal per = totalRent.divide(nBd, 2, RoundingMode.DOWN);
        int extraCents = totalRent.subtract(per.multiply(nBd)).movePointRight(2).intValueExact();
        List<BigDecimal> amounts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) amounts.add(i < extraCents ? per.add(CENT) : per);
        if (capActive && amounts.stream().max(BigDecimal::compareTo).orElse(per).compareTo(depositCap) > 0) {
            throw new BusinessRuleViolationException(capMsg(totalRent, n, depositCap));
        }
        return new Result(amounts, CENT);
    }

    for (BigDecimal step : STEPS) {
        BigDecimal per = floorToStep(totalRent.divide(nBd, 2, RoundingMode.FLOOR), step);
        if (per.signum() <= 0) continue;
        List<BigDecimal> amounts = new ArrayList<>(n);
        BigDecimal big;
        switch (strategy) {
            case FIRST_LARGER -> {
                big = totalRent.subtract(per.multiply(BigDecimal.valueOf(n - 1L)));
                amounts.add(big);
                for (int i = 0; i < n - 1; i++) amounts.add(per);
            }
            case FIRST_AND_LAST_LARGER -> {
                BigDecimal middle = per.multiply(BigDecimal.valueOf(Math.max(n - 2, 0)));
                BigDecimal rem = totalRent.subtract(middle);
                BigDecimal first = floorToStep(rem.divide(BigDecimal.valueOf(2), 2, RoundingMode.FLOOR), step);
                BigDecimal last = rem.subtract(first);
                amounts.add(first);
                for (int i = 0; i < n - 2; i++) amounts.add(per);
                amounts.add(last);
                big = first.max(last);
            }
            default -> { // LAST_LARGER
                big = totalRent.subtract(per.multiply(BigDecimal.valueOf(n - 1L)));
                for (int i = 0; i < n - 1; i++) amounts.add(per);
                amounts.add(big);
            }
        }
        if (!capActive || big.compareTo(depositCap) <= 0) return new Result(amounts, step);
    }
    throw new BusinessRuleViolationException(capMsg(totalRent, n, depositCap));
}

private static String capMsg(BigDecimal totalRent, int n, BigDecimal cap) {
    return "Cannot distribute rent " + totalRent + " across " + n
            + " cheques without a cheque exceeding the deposit (" + cap
            + "). Increase the deposit or the cheque count.";
}
```
(Reuse the existing `capMsg` text if the original message differs — keep the existing wording for LAST_LARGER's message to avoid breaking any test asserting on it.)
- [ ] **Step 4: Run → pass.** Also run the existing calculator test if any (`*ChequeRoundingCalculator*Test`) to confirm LAST_LARGER unchanged. Commit.

---

## Task 3: Thread strategy through generation, preview, DTOs, controller

**Files:** `PaymentScheduleService.java` (`generateScheduleForLease` ~line 125 + `previewSchedule` overload ~966/985), `CreateLeaseDTO.java`, `LeaseDTO.java`, `LeaseService.java` (map strategy on create/update + into LeaseDTO), `PaymentScheduleController.java` (`/payments/preview`).

- [ ] **Step 1: generateScheduleForLease** — change the distribute call (currently `ChequeRoundingCalculator.distribute(totalRent, n, lease.getDepositAmount())`) to pass `lease.getInstallmentDistribution()`.
- [ ] **Step 2: previewSchedule(...)** — add a trailing `InstallmentDistribution strategy` param (nullable → default LAST_LARGER) and pass it to `distribute(totalRent, n, depositAmount, strategy)` (line ~985). Keep the existing 6-arg behavior by overloading or defaulting.
- [ ] **Step 3: DTOs** — `CreateLeaseDTO` + `LeaseDTO`: add `private InstallmentDistribution installmentDistribution;`.
- [ ] **Step 4: LeaseService** — on create AND update, set `lease.setInstallmentDistribution(dto.getInstallmentDistribution() != null ? dto.getInstallmentDistribution() : InstallmentDistribution.LAST_LARGER)`. In `mapToDTO`, copy it back.
- [ ] **Step 5: Controller** — `/payments/preview` add `@RequestParam(required = false) InstallmentDistribution strategy` and pass to the service (default LAST_LARGER when null). Wrap the call so `BusinessRuleViolationException` returns **HTTP 422** with body `{"error": ex.getMessage()}` (add an `@ExceptionHandler(BusinessRuleViolationException.class)` on this controller returning 422, if one doesn't already exist app-wide — check first; if a global handler already maps it, reuse that).
- [ ] **Step 6:** `cd backend && ./gradlew compileJava test --tests '*PaymentScheduleServiceGenerateTest' --tests '*ChequeRoundingCalculator*'` → pass. Add a generate test asserting FIRST_LARGER vs LAST_LARGER produce different first/last amounts for the same lease. Commit.

---

## Task 4: Frontend — selector + live preview on Payment Plan step

**Files:** `web/src/app/[locale]/dashboard/leases/LeaseWizard.tsx`

- [ ] **Step 1: State** — add `installmentDistribution: "LAST_LARGER"` (type `"UNIFORM"|"FIRST_LARGER"|"LAST_LARGER"|"FIRST_AND_LAST_LARGER"`) to wizard data; include it in the create submit body.
- [ ] **Step 2: Selector** — on the Payment Plan step (the step with NUMBER OF INSTALLMENTS), add a select: Uniform / First larger / Last larger / Both larger, bound to `data.installmentDistribution`.
- [ ] **Step 3: Live preview** — add a preview table on that step. Add a debounced (~300ms) effect that, when `unitId` (for propertyId), `startDate`, `endDate`, `rentAmount`, `paymentTerms`, `depositAmount`, or `installmentDistribution` are valid, GETs:
  `/api/proxy/v1/payments/preview?propertyId={selectedUnit.property.id}&startDate=…&endDate=…&monthlyRent={rentAmount}&paymentTerms={paymentTerms}&depositAmount={depositAmount}&strategy={installmentDistribution}`
  Render lines as `#{installmentNumber} · {formatDate(dueDate)} · {formatCurrency(amount + perInstallmentChargeTotal)}` where `perInstallmentChargeTotal = Σ charges(PER_INSTALLMENT) × (vat?1.05:1)`. Below the rent lines, render one row per ONE_TIME charge (`formatCurrency(amount×(vat?1.05:1))`) and a Security Deposit row (`formatCurrency(depositAmount)`) when > 0. Import `formatDate`, `formatCurrency` from `@/lib/format`.
- [ ] **Step 4: Cap error** — if the preview response is non-OK (422), read `{error}` and show it inline in red instead of the table.
- [ ] **Step 5:** `cd web && npx tsc --noEmit` clean. Manually verify the preview updates when changing the selector. Commit.

---

## Task 5: Manual verification
- [ ] Backend + web running. Wizard → Payment Plan: change installments and the distribution selector; preview table updates live and matches (dates DD/MM/YYYY, amounts include folded per-installment charges; SD + one-time rows listed).
- [ ] Draft the lease; confirm the generated schedule equals the preview for the chosen strategy.
- [ ] Try a strategy/deposit combo that violates the cap → inline error shown, no 500.

## Self-review (author)
- LAST_LARGER default + delegating 3-arg overload → existing behavior/tests unchanged (Task 2 asserts equality).
- Strategy persisted (migration 62) so generation and any regeneration agree with the preview.
- Preview augmentation (charges + SD) is client-side; the authoritative amounts come from the same calculator at generation.
