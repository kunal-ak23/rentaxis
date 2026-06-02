# Configurable Installment Distribution + Live Preview — Design

**Date:** 2026-06-02
**Status:** Approved (design)
**Author:** Kunal Sharma (with Claude)
**Branch:** `feat/lease-charges-rework` (stacked on the charges work, PR #59)

## Problem

Rent is split across cheques by `ChequeRoundingCalculator`, which floors non-last cheques to clean steps (1000/500/100) and puts the remainder on the **last** cheque — a fixed "last-larger" behavior. Landlords sometimes want the remainder elsewhere (uniform, first-larger, or both-larger). There's also no way to **see** the computed installments before creating the draft lease.

## Goals

- Let the user choose how the rounding remainder is distributed: **UNIFORM**, **FIRST_LARGER**, **LAST_LARGER** (current default), **FIRST_AND_LAST_LARGER**.
- Show a **live installment preview** on the wizard's Payment Plan step, *before* the draft is created, reflecting the chosen strategy and including folded per-installment charges + the security-deposit / one-time-charge rows.
- Persist the chosen strategy on the lease.

## Non-goals

- No user-entered custom amounts or percentage front-loading (remainder placement only).
- Strategy affects the **rent** portion only; charges/deposit handling is unchanged.
- No change to existing leases (default LAST_LARGER reproduces today's output).

## Decisions (from brainstorming)

| Question | Decision |
|---|---|
| Meaning of "bigger" | Remainder placement (as-equal-as-possible base; remainder concentrated where chosen). |
| Preview | Live table on the Payment Plan wizard step. |
| Persistence | Persist `installment_distribution` enum on the lease. |

## Design

### Distribution strategies (`InstallmentDistribution` enum)
Applied to the **rent** total only, across `n` cheques:
- **UNIFORM** — `per = totalRent / n` rounded to 2dp; distribute the cent remainder (`totalRent - per*n`) one cent at a time across the earliest cheques so the sum is exact. All cheques essentially equal.
- **LAST_LARGER** (default) — non-last cheques floored to the clean step; remainder on the last cheque. **Identical to current behavior.**
- **FIRST_LARGER** — non-first cheques floored to the clean step; remainder on the first.
- **FIRST_AND_LAST_LARGER** — middle cheques floored to the clean step; remainder split across first + last (first gets `floor(remainder/2)` to a clean cent, last gets the rest).
- `n == 1` → single cheque = `totalRent` for every strategy.

Deposit cap: applies to the remainder-bearing cheque(s) (the largest). If no clean step satisfies the cap for the chosen strategy, throw `BusinessRuleViolationException` as today; the preview endpoint catches it and returns a message rather than 500.

### Backend
- **Enum** `com.datagami.rentaxis.domain.entity.enums.InstallmentDistribution { UNIFORM, FIRST_LARGER, LAST_LARGER, FIRST_AND_LAST_LARGER }`.
- **Migration `62-installment-distribution.yaml`**: add `installment_distribution VARCHAR(30) NOT NULL DEFAULT 'LAST_LARGER'` to `leases`.
- **`Lease`** entity: add `@Enumerated(STRING) installmentDistribution = LAST_LARGER`.
- **DTOs**: `CreateLeaseDTO` + `LeaseDTO` add `installmentDistribution` (nullable in DTO → default LAST_LARGER when null).
- **`ChequeRoundingCalculator.distribute(totalRent, n, depositCap, strategy)`** — new param. Refactor the existing clean-step search into a helper; place the remainder per strategy. Keep a `distribute(total, n, cap)` overload delegating with `LAST_LARGER` so existing callers/tests are unchanged.
- **`PaymentScheduleService.generateScheduleForLease`** passes `lease.getInstallmentDistribution()`.
- **`previewSchedule(...)`** gains a `strategy` param and uses the same calculator.
- **`PaymentScheduleController` `/payments/preview`**: add optional `strategy` query param (default LAST_LARGER); map `BusinessRuleViolationException` to a 200 response carrying an error message field (or 422 with a JSON body the frontend can show) — pick 422 with `{error}` for consistency with other validation responses.

### Frontend — Payment Plan step (`LeaseWizard.tsx`)
- **Distribution selector** (radio/select): Uniform / First larger / Last larger / Both larger. Stored in wizard state (`installmentDistribution`, default `LAST_LARGER`), sent in the create body.
- **Live preview table**: on change of rent / start / end / paymentTerms / strategy (debounced ~300ms), call `/api/proxy/v1/payments/preview?propertyId=…&startDate=…&endDate=…&monthlyRent=…&paymentTerms=…&depositAmount=…&strategy=…`. Render installment # · due date (DD/MM/YYYY via `formatDate`) · amount.
- **Augment with charges**: add the per-installment charge total (incl additive VAT) to each rent line; append one-time-charge lines and a security-deposit line, so the preview equals what will be generated. (The wizard already holds `charges` + `depositAmount`.)
- **Cap rejection**: if the preview call returns the cap error, show an inline message ("Can't distribute rent across N cheques without exceeding the deposit — increase deposit or cheque count").

### Testing
- `ChequeRoundingCalculatorTest`: for each strategy — sum equals total exactly; remainder lands on the expected cheque(s); UNIFORM cheques differ by ≤ 0.01; LAST_LARGER output is byte-identical to the pre-change behavior on representative inputs; cap rejection per strategy.
- `PaymentScheduleServiceGenerateTest` / preview: strategy drives generated amounts; preview matches generation for the same inputs.
- Frontend: selector updates the preview; preview includes folded per-installment charges + SD + one-time lines; cap-error message renders.

### Scope guards / risks
- Default LAST_LARGER → existing behavior preserved; existing leases unaffected (and not regenerated).
- Preview is advisory; the authoritative amounts are computed at draft generation from the same calculator + strategy, so preview and result agree.
- Separate workstream from PR #59's other pending review fixes.
