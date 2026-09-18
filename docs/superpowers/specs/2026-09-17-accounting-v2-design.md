# Accounting v2 — Design

**Date:** 2026-09-17
**Status:** approved in review, pending written sign-off
**Client driver:** Al Ashram Real Estate (PACT RevenU migration). Sources: call walkthrough (33 screenshots), PACT General Ledger exports for two tenants, PACT chart of accounts (826 rows), property↔ledger mapping sheet, WhatsApp follow-ups and voice note from Anil (accountant), 2026-09-17.

## 1. Why

The client has used RentAxis v1 and reports that the biggest deviation from their current system is how accounts are handled. In PACT every business document posts balanced journal entries into a per-property chart of accounts; income is deferred and recognised monthly; post-dated cheques are a control account. In RentAxis v1:

- `financial_transactions` is one-legged (a row has both `debit` and `credit` columns, no journal header, nothing enforces balance);
- the chart of accounts is per tenant with no property dimension, and account codes are hard-coded as string literals in four services;
- a cheque is a set of columns on `payment_schedules`, conflated with installment / deposit / charge via three booleans;
- rental income is booked in full when a cheque clears; `Rent Receivable`, `PDC Receivable` and `Advance Rent` are seeded, reported on, and never posted to;
- there is no renewal chain between leases, no vendor→account link, no period lock.

v2 replaces the finance model outright. The goal is that the client can leave PACT: the client's accountant should recognise every screen, document type and ledger.

## 2. Decisions taken in review

| # | Decision | Choice |
|---|---|---|
| D1 | GL scope | Rent-side workflows in v2; ledger core generic enough that expense/payables/payroll slot in later without redesign. Seeded from the client's actual CoA. |
| D2 | Per-property accounts | Auto-generated from a tenant template on property creation, editable; manual mapping to any existing account also supported. |
| D3 | Cut-over | Cut-over date; import active contracts + outstanding PDCs; opening-balance journal for the rest. History before the date stays in PACT. |
| D4 | v1 compatibility | Replace, no data migration. New schema; old finance tables dropped; demo tenant re-seeded. |
| D5 | Posting model | Lease is DRAFT until explicitly **posted**; posting writes journals; amendments after posting are reversal + re-post. |
| D6 | Contract scope | Core only: lines with per-line credit account and discount, cheque grid, renew with chain, terminate, extend. Multi-unit, sub-contract, landlord commission, salesman deferred. |
| D7 | Platforms | Web only. Mobile apps are updated after the web is stable. |
| D8 | Expense vouchers | Purchase/Service Invoice and Bank/Cash Payment Voucher are in v2 (thin over the posting engine). |
| D9 | Vendors | Plain master form like renters. The vendor's ledger account is created silently; no mapping UI. |
| D10 | Per-day rent | Client hard requirement: day rate = rent ÷ actual days in term; monthly recognition = day rate × actual days in the month; last period absorbs rounding. |
| D11 | Penalties | Never auto-post. Proposed (by rule or by hand) → approved by finance → posted. Waive = no posting; reverse after approval = mirror journal. |
| D12 | Architecture | Posting-document engine (approach A): every document produces an immutable balanced `JournalEntry` through one `PostingService`; callers name **roles**, an `AccountResolver` turns (role, property) into an account. |
| D13 | Recognition entry date | Month-end (`period_end`). PACT dates on the 1st; month-end is the correct treatment. |
| D14 | Reports | Out of scope for v2 except the control views needed to trust inputs: General Ledger, Tenant Ledger, Vendor Ledger, Trial Balance. |

## 3. Vocabulary

| Term | Meaning |
|---|---|
| Tenant | A RentAxis customer organisation (multi-tenant SaaS). Never a renter. |
| Renter | The person renting a unit. PACT calls them "tenant". |
| Property | A building/tower. PACT: Property/Tower. |
| Role | Abstract account purpose (`RENT_RECEIVABLE`, `ADVANCE_RENT` …). Posting rules speak roles; the resolver maps them to accounts. |
| Doc type | Prefix on a journal number, matching PACT: `TCO` contract, `TCR` contract reversal, `PDR` PDC registered, `CRT` cheque cleared, `CBR` cheque returned, `CIL` monthly income, `RCP` receipt, `STL` settlement, `PEN` penalty, `PISR` purchase invoice, `BPV` bank/cash payment, `OB` opening balance, `JV` manual journal. |

## 4. Ledger core

### 4.1 Account

Rework of `accounts`, not a new table.

| Column | Notes |
|---|---|
| `id`, `tenant_id` | UUID; tenant isolation via `TenantAspect` as everywhere |
| `code` | unique per tenant. Imported PACT codes verbatim (e.g. `166269`); new accounts from a per-tenant numeric sequence seeded above the max imported code |
| `name_en`, `name_ar`, `alias` | |
| `account_type` | `ASSET / LIABILITY / INCOME / EXPENSE / EQUITY` (existing enum) |
| `parent_id` | FK to `accounts`; replaces the string `parent_code`. Groups form the tree `A → A-02 → A-02-01 → leaf` |
| `is_group`, `is_active`, `is_system` | groups cannot carry lines; system leaves (vendor, bank, template-generated) are not deletable from the CoA screen |
| `property_id` | nullable FK. Set on template-generated leaves and on any leaf the user assigns to a property (expense leaves such as `PEST CONTROL AMC OCEAN RESIDENCIA`). First-class filter for ledgers and tower-wise grouping. **Not** used for resolution |

No stored balance. Balances are sums over `journal_lines`.

### 4.2 JournalEntry / JournalLine

```
journal_entries
  id, tenant_id
  entry_number       TEXT      -- "TCO-26/1629": <doc_type>-<fy2>/<seq>, seq per tenant per doc_type per FY
  doc_type           ENUM      -- see §3
  entry_date         DATE
  narration          TEXT
  property_id, unit_id, lease_id, renter_id   -- dimensions, nullable
  source_type        ENUM      -- LEASE, CHEQUE, RECOGNITION, PENALTY, SETTLEMENT, VOUCHER, OPENING_BALANCE, IMPORT, MANUAL
  source_id          UUID
  status             ENUM      -- POSTED, REVERSED
  reversal_of_id     UUID FK   -- set on the reversing entry
  reversed_by_id     UUID FK   -- set on the reversed entry
  import_batch_id    UUID FK   -- nullable
  posted_by, posted_at, created_at

journal_lines
  id, journal_entry_id, line_no
  account_id
  debit  NUMERIC(18,2) NOT NULL DEFAULT 0
  credit NUMERIC(18,2) NOT NULL DEFAULT 0
  CHECK ((debit > 0 AND credit = 0) OR (credit > 0 AND debit = 0))
  property_id, unit_id, lease_id, renter_id, cheque_id   -- dimensions
  narration
```

Invariants:

- Entries are immutable. No update or delete endpoint. The only mutation is `reverse(entryId, date, reason)`, which inserts a mirror entry (lines swapped) and links both.
- Σdebit = Σcredit per entry, enforced in `PostingService` **and** by a deferrable constraint trigger in Postgres (Liquibase SQL changeset).
- Lines may only reference leaf accounts (`is_group = false`).
- `entry_date` must be `> books_locked_through` for the tenant, except doc types `OB` and entries in an import batch.

### 4.3 PostingService

The single write path.

```java
JournalEntry post(PostingRequest r);       // doc type, date, narration, dimensions, source, lines
JournalEntry reverse(UUID entryId, LocalDate date, String reason);
```

A `PostingRequest` line is `(AccountRef, DR|CR, amount, dimensions, narration)` where `AccountRef` is either a role or an explicit `account_id`. Roles are resolved by `AccountResolver` before insert. Posting rules live in code inside each document service (`LeasePostingService`, `ChequeService`, `RecognitionService`, …). Only *resolution* is data-driven. There is no rules table.

### 4.4 AccountResolver

```
resolve(role, propertyId):
  property_account_mappings[propertyId, role]   → account
  else tenant_default_account_mappings[role]    → account
  else throw UnmappedAccountRole(role, property)
```

### 4.5 Fiscal settings (tenant-level)

`fiscal_year_start_month` (default 1), `books_start_date` (cut-over, §10), `books_locked_through` (period lock, initially cut-over − 1). Entry numbers use the fiscal year of `entry_date`.

### 4.6 Manual Journal Voucher (`JV`)

Any leaf accounts, any dimensions, must balance. Kept in v2 so the accountant can record anything not yet covered by a voucher type.

### 4.7 Removed

`financial_transactions`, `account_mappings`, `transaction_splits` and their services, controllers and UI. `payment_schedules` (replaced by `cheques`, §7). `lease_charges` (replaced by `lease_lines`, §6). Hard-coded account codes in `PaymentScheduleService`, `FinancialTransactionService`, `SettlementService`, `OnlinePaymentService` disappear with those services.

## 5. Property account sets

### 5.1 AccountRole

```
RENT_RECEIVABLE, ADVANCE_RENT, RENTAL_INCOME, PDC_RECEIVABLE, BANK,
SECURITY_DEPOSIT, ADMIN_FEE, PARKING_INCOME, PARKING_DEPOSIT, COOLING_CHARGES,
MAINTENANCE_CHARGES, RENT_PENALTY, CHEQUE_RETURN_PENALTY, OTHER_INCOME,
FORFEITED_INCOME, DISCOUNT_ALLOWED, ROUNDING_OFF, CASH, OUTPUT_VAT, INPUT_VAT,
OPENING_BALANCE_DIFFERENCE
```

Adding an expense-side role later is an enum value plus a template row.

### 5.2 Mappings

```
property_account_mappings (tenant_id, property_id, role, account_id)   UNIQUE (property_id, role)
tenant_default_account_mappings (tenant_id, role, account_id)           UNIQUE (tenant_id, role)
```

Tenant defaults serve tenant-wide roles (`CASH`, `ROUNDING_OFF`, `OUTPUT_VAT`, `INPUT_VAT`, `DISCOUNT_ALLOWED`, `OPENING_BALANCE_DIFFERENCE`) and properties that deliberately share generic accounts (PACT's Galah 2 → `Rent Receivable 105590`, `Advance Rent 125620`, `Rental Income A/c 145661`).

Remapping is always allowed. Posted lines hold `account_id`; history is untouched, only future postings move. The UI warns when a role with posted history is remapped.

### 5.3 Template

`property_account_template_rows (tenant_id, role, name_pattern, parent_account_id, enabled)`. One template per tenant, seeded from PACT's conventions:

| Role | Pattern | Parent group |
|---|---|---|
| RENT_RECEIVABLE | `Rent Receivable - {property}` | A-02-01 Rental Receivable A/c |
| ADVANCE_RENT | `Advance Rent - {property}` | B LIABILITY |
| RENTAL_INCOME | `Rental Income {property}` | C-01-01 Rental Income Group |
| PDC_RECEIVABLE | `PDC Receivable {property}` | A-02-03 PDCS |
| BANK | `Emirates Islamic - {property}` | A-02-02 BANK |
| SECURITY_DEPOSIT | `Security Deposit {property}` | B-01-02 SECURITY DEPOSITS |
| ADMIN_FEE | `Admin Fee - {property}` | C-01-01 |
| PARKING_INCOME | `Additional Parking - {property}` | C-01-01 |
| PARKING_DEPOSIT | `Parking Security Deposit {property}` | B-01-02 |
| COOLING_CHARGES | `Cooling Charges - {property}` | C-01 |
| MAINTENANCE_CHARGES | `Maintenance Charges - {property}` | C-01-02 OTHER INCOME |
| RENT_PENALTY | `Rent Penalty - {property}` | C-01-02 |
| CHEQUE_RETURN_PENALTY | `Cheque Return Penalty - {property}` | C-01-02 |

Account type is inherited from the parent group.

### 5.4 Flow

- Creating a property (or *Generate missing accounts* on an existing one) creates one leaf per enabled template row, sets `property_id`, and writes the mapping. Existing leaves with the same name under the same parent are reused rather than duplicated.
- The property's **Accounts** tab (PACT's Receivables tab) shows role → account; any row can be swapped to any leaf of the same account type.
- `BankAccount.coa_account_id` defaults to the property's `BANK` leaf when a bank account is created for a property.
- **Posting guard:** before a lease posts, every role used by its lines plus `RENT_RECEIVABLE`, `PDC_RECEIVABLE`, `BANK` must resolve for its property. Failure lists every unmapped role.

### 5.5 Vendors

`vendors.coa_account_id` — created silently under group `B-01-04 VENDORS` (type LIABILITY, sub-type creditor) when a vendor is saved; renamed when the vendor is renamed; deactivated with the vendor. Never shown as a mapping. Renters are **not** accounts; they are a dimension on lines.

## 6. Lease as posting document

### 6.1 ChargeType catalogue (tenant-level, seeded)

`charge_types (tenant_id, code, name_en, name_ar, role, behaviour, vat_applicable_default, active)`

| Behaviour | Meaning |
|---|---|
| `RENT` | credited to `ADVANCE_RENT`; recognised per day (§8) |
| `DEPOSIT` | liability; refundable at settlement |
| `FEE` | income in full on posting |

Seeded: Rent (RENT→ADVANCE_RENT), Security Deposit (DEPOSIT→SECURITY_DEPOSIT), Admin Fee (FEE→ADMIN_FEE), Parking Security Deposit (DEPOSIT→PARKING_DEPOSIT), Cooling Charges (FEE→COOLING_CHARGES), Parking Fee (FEE→PARKING_INCOME), Maintenance Charges (FEE→MAINTENANCE_CHARGES).

### 6.2 LeaseLine

`lease_lines (lease_id, seq_no, charge_type_id, credit_account_id, gross_amount, discount_amount, net_amount, narration, vat_applicable)`.
`credit_account_id` is pre-filled by the resolver when the line is added and remains editable. `lease.contract_value = Σ net_amount`. Discount is informational: receivable and income are both net; nothing posts to `DISCOUNT_ALLOWED` in v2.

### 6.3 Lease header changes

Added: `contract_date` (document date; may differ from `start_date`), `total_days` (derived), `grace_period_days` (payment grace for overdue calculation), `contract_number` (per-property prefix + sequence, e.g. `GLA_B1/681`), `renewed_from_lease_id`, `chain_id` (root lease of the renewal chain — PACT's tracking number), `receivable_account_id` and `income_account_id` overrides (default from property mapping), `posting_journal_id`, `posted_at`, `posted_by`.
Kept, but derived: `rent_amount` and `deposit_amount` stay on the lease as read-only mirrors of the RENT and DEPOSIT-behaviour lines, recomputed by `LeaseService.syncDerivedTotals` whenever the lines change and never accepted from a request body. Too much already reads them — reports, the unit's `actual_rent`, the renter portal — for removing them to be worth it, and as a mirror they cannot drift from the lines.
Removed: `monthly_rent` (a second source of truth for the same money; a monthly figure is derived where it is displayed), and the `payment_terms`-driven schedule fields that live on the cheque grid now; `installment_distribution` moves to the generator input.

Statuses: `DRAFT, PENDING_SIGNATURE, ACTIVE, RENEWED, NOTICE_GIVEN, TERMINATED, EXPIRED, CLOSED`.
`DRAFT → ACTIVE` only via **Post**. `PENDING_SIGNATURE` and renter accept/reject stay a pre-post step. `ACTIVE → RENEWED` when the successor lease posts. `LeaseExpirationJob` continues to flip `ACTIVE/NOTICE_GIVEN → EXPIRED`.

### 6.4 Post

Validation:
1. every line has a resolvable `credit_account_id`;
2. `RENT_RECEIVABLE`, `PDC_RECEIVABLE`, `BANK` resolve for the property;
3. Σ cheque-grid amounts = `contract_value` (all modes count);
4. `contract_date > books_locked_through`.

Postings, all dated `contract_date`:

| Journal | Lines |
|---|---|
| `TCO` (one) | per line: `Dr RENT_RECEIVABLE net` / `Cr line.credit_account net`; if `vat_applicable`: `Dr RENT_RECEIVABLE vat` / `Cr OUTPUT_VAT vat` (5 %, additive) |
| `PDR` (one per cheque row) | `Dr PDC_RECEIVABLE amount` / `Cr RENT_RECEIVABLE amount` — regardless of mode |

After posting the renter's ledger nets to zero, as in PACT. Overdue and "amount due" are derived from the cheque register (§7.5), never from the ledger.

Post also creates `rent_segments` and `recognition_entries` (§8) and sets `unit.current_lease_id`.

### 6.5 Amend after post

- Line changes → `TCR` reversal of the `TCO` + fresh `TCO`; recognition schedule regenerated (§8.5).
- Cheque-grid changes → only for rows in `REGISTERED`: reverse that row's `PDR`, post a new one. Rows beyond `REGISTERED` are changed through the cheque lifecycle (§7), never edited.

### 6.6 Renew

*Renew* on an ACTIVE/EXPIRED lease creates a DRAFT pre-filled from it with `renewed_from_lease_id` set and the same `chain_id`; new `contract_number`. When the new lease posts, the old one becomes `RENEWED` (unit stays occupied, `current_lease_id` moves). Deposits carried over are a `DEPOSIT` line on the new lease with a matching JV moving the liability, or a settlement on the old lease — accountant's choice; the UI offers "carry deposit forward" which posts `Dr SECURITY_DEPOSIT(old lease dims) / Cr SECURITY_DEPOSIT(new lease dims)` as a `JV`.

### 6.7 Extend

Additive: `end_date` moves, one or more new `RENT` lines for the extension window, new cheque rows; posts a further `TCO`/`PDR` set dated the extension's contract date. No reversal. The extension becomes its own `rent_segment`.

### 6.8 Generator

`generateSchedule(lease, installments, first_due_date, distribution)` produces cheque rows: rent split with the existing `ChequeRoundingCalculator` (recurring cheques rounded to tens, first cheque absorbs the remainder — matches PACT's `10,200 + 5 × 10,160 = 61,000`); deposit and fee lines are folded into the first cheque by default. *Generate cheque numbers* fills `cheque_number` sequentially from a starting number over rows with mode `PDC`.

## 7. PDC register and cheque lifecycle

### 7.1 Cheque

`cheques` replaces `payment_schedules`.

```
id, tenant_id, lease_id, property_id, unit_id, renter_id
seq_no, posting_date, cheque_number, cheque_date (maturity / due)
payee_bank            -- drawer's bank, free text / lookup (ENBD, DIB …)
debit_account_id      -- our bank or cash leaf; defaults to property BANK
amount, narration     -- "Rent - 2nd Installment"
mode                  -- PDC, CASH, TRANSFER, ONLINE
status                -- see 7.2
failure_reason        -- existing ChequeFailureReason
replaces_id, replaced_by_id
image_url, image_blob_path, image_uploaded_at, ocr fields   -- kept from v1
deposited_at, cleared_at, bounced_at, returned_at
pdr_journal_id, crt_journal_id, cbr_journal_id
penalty_assessment_id  -- nullable, for rows created to collect a penalty
```

### 7.2 Status machine

| From → To | Trigger | Journal |
|---|---|---|
| DRAFT → REGISTERED | lease posts, or row added to a posted lease | `PDR` Dr PDC_RECEIVABLE / Cr RENT_RECEIVABLE, dated `posting_date` |
| REGISTERED → DEPOSITED | Cheque/Cash Collection batch | none (operational) |
| DEPOSITED → CLEARED | bank confirms | `CRT` Dr `debit_account` / Cr PDC_RECEIVABLE, dated `cleared_at` |
| DEPOSITED → BOUNCED | bank returns | `CBR` Dr RENT_RECEIVABLE / Cr PDC_RECEIVABLE |
| CLEARED → BOUNCED | bank reverses after clearing | `CBR` Dr RENT_RECEIVABLE / Cr `debit_account` |
| BOUNCED → REPLACED | one or more new rows with `replaces_id` | each new row posts its own `PDR`; a residual (replacement total ≠ bounced amount) stays in RENT_RECEIVABLE |
| REGISTERED → CANCELLED | finance cancels | reversal of `PDR` |
| REGISTERED/DEPOSITED → RETURNED | termination (§9.1) | reversal of `PDR` |
| REGISTERED → CLEARED | CASH/TRANSFER "Received" | `CRT` Dr `debit_account` (bank or CASH) / Cr PDC_RECEIVABLE |
| REGISTERED → ONLINE_PENDING → CLEARED | Razorpay (§9.3) | `CRT` on capture |

Transitions not listed are rejected. Each transition posts at most one journal and records the actor and timestamp.

### 7.3 Penalties

```
penalty_assessments
  id, tenant_id, lease_id, cheque_id (nullable), renter_id, property_id
  reason        -- CHEQUE_RETURN, LATE_PAYMENT, OTHER
  amount, description
  status        -- PROPOSED, APPROVED, WAIVED, REVERSED
  proposed_by / proposed_at (user or SYSTEM), approved_by / approved_at, journal_id
```

- Raised automatically when a rule fires — `fine_config` gains `bounces_before_penalty` (default 2, counted per lease) and `auto_propose` per reason — or manually by finance.
- **Only `APPROVED` posts:** `PEN` `Dr RENT_RECEIVABLE / Cr CHEQUE_RETURN_PENALTY | RENT_PENALTY | OTHER_INCOME` for the property. Approval also creates a register row (mode CASH/TRANSFER/ONLINE, `penalty_assessment_id` set) so the money follows the normal receipt path.
- WAIVED from PROPOSED posts nothing. REVERSED from APPROVED posts the mirror journal and cancels the register row if uncleared.
- Late-payment penalties show as a running *estimate* on the register (existing `PenaltyCalculationService` logic) but are only *proposed* once per cheque when it is cleared late or replaced; nothing posts nightly. `PenaltyService.calculateDailyPenalties` is reduced to proposing, never posting.
- The renter portal shows APPROVED penalties only.

### 7.4 Register screens

Register (all rows; filters status, property, maturity range, mode), **Cheque / Cash Collection** (select REGISTERED rows → DEPOSITED in one batch with deposit date and bank), **Return / Replace** (BOUNCED rows → replacement rows), **Post-Dated Received** (REGISTERED/DEPOSITED by maturity month), bulk OCR attach (kept: `POST /leases/{id}/cheques/bulk-attach`, REGISTERED rows only).

### 7.5 Operational predicates (from the register, not the ledger)

- `due(row)` = status ∈ {REGISTERED, DEPOSITED} and `cheque_date ≤ today`, or status = BOUNCED, or an approved-penalty row uncleared.
- `overdue(row)` = `due(row)` and `cheque_date + lease.grace_period_days < today`.
- Renter "amount due" = Σ amount over `due` rows. Aging report and reminders use `overdue`.

## 8. Per-day income recognition

### 8.1 RentSegment

One per `RENT` line: `(lease_line_id, lease_id, from_date, to_date, amount, days, day_rate)`. `days` = actual calendar days inclusive (366-day terms possible). `day_rate = amount / days` stored at 6 dp. The base term is one segment; an extension adds one; termination truncates one. Rent-free days inside the term need no special case — they are already in `days`.

### 8.2 RecognitionEntry

Precomputed on post, one per calendar-month slice of each segment:

```
recognition_entries
  id, tenant_id, lease_id, segment_id, period_start, period_end, days
  amount, status (PLANNED, POSTED, REVERSED, CANCELLED), journal_id
```

`amount = round(day_rate × days, 2)`; the segment's final row = `segment.amount − Σ previous rows`, so Σ = segment amount exactly.

Reference fixture (client's example — 51,000, 24 Sep 2026 → 23 Sep 2027, 365 days, day rate 139.726027):

| Period | Days | Amount |
|---|---|---|
| 24–30 Sep 2026 | 7 | 978.08 |
| Oct 2026 | 31 | 4,331.51 |
| Nov 2026 | 30 | 4,191.78 |
| Dec 2026 | 31 | 4,331.51 |
| Jan 2027 | 31 | 4,331.51 |
| Feb 2027 | 28 | 3,912.33 |
| Mar 2027 | 31 | 4,331.51 |
| Apr 2027 | 30 | 4,191.78 |
| May 2027 | 31 | 4,331.51 |
| Jun 2027 | 30 | 4,191.78 |
| Jul 2027 | 31 | 4,331.51 |
| Aug 2027 | 31 | 4,331.51 |
| 1–23 Sep 2027 | 23 | 3,213.68 |
| **Total** | **365** | **51,000.00** |

The lease page shows this as a **Recognition schedule** tab.

### 8.3 Posting

Each row → one `CIL` journal: `Dr ADVANCE_RENT(property) / Cr RENTAL_INCOME` (lease `income_account_id` override, else property mapping); narration `Advance rent adjustment – <Mon YYYY>`; dimensions lease/unit/renter/property; `entry_date = period_end` (D13).

### 8.4 RevenueRecognitionJob

Nightly, per tenant: post every `PLANNED` row with `period_end ≤ today` and `period_end > books_locked_through`, in `period_end` order. Backdated leases catch up on the next run. **Run recognition to date** (manual, with preview) exists for month-end close and for the cut-over catch-up.

### 8.5 Re-planning

- `TCO` amended → POSTED rows reversed, all rows regenerated from the new lines.
- Extension → new segment appended; existing rows untouched.
- Termination at T → PLANNED rows after T `CANCELLED`; the row containing T re-sliced to end at T; POSTED rows with `period_start > T` reversed; a POSTED row straddling T is reversed and re-posted at the truncated amount.

## 9. Termination, settlement, receipts

### 9.1 Termination at T

One screen, one confirm:
1. **Cheques.** Uncleared rows with `cheque_date > T` default to **RETURNED** (reversal of `PDR`). Uncleared rows dated `≤ T` default to *keep for collection*. Finance can flip any row before confirming. BOUNCED rows stay bounced; their amount is owed.
2. **Recognition** truncated per §8.5. **Unearned rent** = segment amount − recognised through T, posted as `TCR`: `Dr ADVANCE_RENT / Cr RENT_RECEIVABLE`.
3. Lease → `TERMINATED`, `unit.current_lease_id` cleared, settlement opens.

After (1)+(2) the renter's receivable balance is exactly *earned − received*.

### 9.2 Settlement

Reworks `lease_settlements`. Statement: earned rent to T, cleared receipts, deposits held, approved penalties outstanding, deduction lines (each with an income account defaulting to `MAINTENANCE_CHARGES` or `OTHER_INCOME` for the property; early-termination charge → `RENT_PENALTY`; forfeiture → `FORFEITED_INCOME`), net refund or net due.

**Finalize** posts one `STL`: `Dr SECURITY_DEPOSIT` (and `PARKING_DEPOSIT`) for deposits released; `Cr` each deduction's account; `Cr RENT_RECEIVABLE` for any balance the deposit absorbs; `Cr BANK` for the net refund (bank picked on the screen). If net is due from the renter, the residual stays open in `RENT_RECEIVABLE` and a CASH/TRANSFER register row is created to collect it. Natural expiry uses the same settlement without §9.1 steps 1–2.

### 9.3 Receipts — one path

Every receipt clears a register row.
- **Online (Razorpay):** renter selects a `due` row → `OnlinePaymentService.createOrder` (row → `ONLINE_PENDING`) → capture/webhook → row `CLEARED` via `CRT` `Dr BANK(gateway settlement account — tenant setting) / Cr PDC_RECEIVABLE`. A bounced row paid online gets a new ONLINE replacement row. Cancel reverts to the prior status. Gateway fees are a bank-reconciliation `JV`, out of v2.
- **Cash Receipt Voucher – Rent:** creates a CASH/TRANSFER row against the lease and clears it in one step.

## 10. Expense vouchers, opening balances, cut-over

### 10.1 Purchase / Service Invoice (`PISR`)

`vouchers` (shared table for PISR/BPV/RCP: id, tenant_id, doc_type, doc_date, vendor_id, narration, property_id, unit_id, status DRAFT/POSTED, journal_id) + `voucher_lines` (account_id, description, amount, vat_rate, vat_amount, property_id, unit_id).
Posts `Dr each expense line`, `Dr INPUT_VAT Σ vat`, `Cr vendor.coa_account`. Attachments via existing attachment infra. Amend = reversal + new voucher.

### 10.2 Bank / Cash Payment Voucher (`BPV`)

Header adds `payment_account_id` (bank leaf or CASH), `cheque_number`, `cheque_date`. Lines: any leaf (vendor, expense, salary…), amount, remarks. Posts `Dr lines / Cr payment_account`.
**Not in v2:** bill-wise allocation of payments to invoices; post-dated *payables* (PDC Payables). Vendor ledger shows the running balance.

### 10.3 Cut-over

Tenant setting `books_start_date = D`; `books_locked_through = D − 1`. Two loaders, in order:

1. **Active-contract import** — extends the multi-sheet Excel portfolio import. Sheets: Properties (with the six account-name columns of the "property mapping ledgers" sheet → mappings by account-name match; unmatched names reported, not guessed), Units, Renters, Contracts (contract no, tracking no, property, unit, renter, contract/start/end dates, lines), Cheques (contract ref, seq, number, date, bank, amount, narration, status, cleared/bounced date). Produces DRAFT leases; **Bulk post** writes `TCO`/`PDR` dated `contract_date`, applies cheque statuses with `CRT`/`CBR` on the given dates, and runs recognition catch-up for every period ending before D. All journals carry `source_type = IMPORT` and `import_batch_id`; **Reverse batch** reverses every journal in the batch and returns leases to DRAFT. Import journals are exempt from the period lock. Only active contracts are imported.
2. **Opening-balance journal (`OB`)** — grid of every leaf with Dr/Cr as at D − 1, importable from PACT's trial-balance CSV (code, name, debit, credit). Accounts mapped to roles `RENT_RECEIVABLE, PDC_RECEIVABLE, ADVANCE_RENT, SECURITY_DEPOSIT, PARKING_DEPOSIT, RENTAL_INCOME, ADMIN_FEE, *_PENALTY` are **excluded** from manual entry (derived by step 1). The difference posts to `OPENING_BALANCE_DIFFERENCE` (equity) so the books open balanced.

**Reconciliation screen:** per account — derived balance, PACT figure (from the uploaded TB), difference.

## 11. Web UI

Table-first, paginated, AR/EN, per the project UI standard. Mobile apps are not changed in v2; their finance/lease screens are hidden behind a "coming soon" flag until the web is stable.

**Finance:** Chart of Accounts (tree + flat, property filter, CSV import); Property → Accounts tab; Settings → Property account template / Charge types / Fiscal & period lock / Fine rules; General Ledger; Tenant Ledger; Vendor Ledger; Trial Balance; Journal Vouchers (list, detail, Reverse, New JV); Purchase/Service Invoice; Bank/Cash Payment Voucher; Cash Receipt Voucher – Rent; Opening Balances; Reconciliation; Import Batches.

**Leasing:** lease editor rebuilt around PACT's layout — header, lines grid (Particulars, Credit A/c, Amount, Discount, Net, Narration, VAT), cheque grid (Posting date, Cheque no, Date, Payee bank, Debit A/c, Amount, Narration, Mode) with *Generate cheques* / *Generate cheque numbers*; tabs Recognition schedule, Journals, Documents; actions Save, Post, Amend, Renew, Extend, Terminate, Ledger. Lease list: status incl. RENEWED, chain column, Bulk post, Run recognition.

**Cheque register:** Register, Cheque/Cash Collection, Return/Replace, Post-Dated Received, bulk OCR attach; **Penalties** queue (Approve / Waive / Reverse).

**Renter portal:** amounts due from the register, approved penalties, Razorpay pay, read-only tenant ledger.

**RBAC:** new role `ACCOUNTANT`. Post, reverse, approve/reverse penalty, OB, JV, period lock, import batch reverse → `TENANT_ADMIN` or `ACCOUNTANT`. `PROPERTY_MANAGER` drafts leases, registers/deposits cheques, proposes penalties.

## 12. Testing

- **Unit:** proration engine (day rate, month slicing, remainder, leap year; §8.2 table as fixture); cheque rounding; resolver (property → default → hard fail); balanced-journal check; period lock; entry numbering.
- **Service / integration (Testcontainers Postgres):** one test class per document — lease post/amend/renew/extend/terminate; every cheque transition asserts the exact journal; recognition catch-up and re-planning; settlement; PISR/BPV; OB; import batch reverse. Invariants asserted after every scenario: trial balance balances; a posted lease's tenant ledger nets to zero; Σ recognition = rent; no line on a group account.
- **Golden ledger tests:** replay the client's two GL exports (ISLAM MAMANOV / LE BOULEVARD, ANUM ISHTIAQ / GALAH 2) through the API and diff our ledger against PACT's line by line; `CIL` amounts are expected to differ by the per-day rule only and are asserted against the per-day fixture instead.
- **E2E:** `scripts/seed_demo_tenant.py` rewritten for v2; Playwright walkthrough extended to post a lease, deposit/clear/bounce/replace, run month-end, terminate and settle.

## 13. Build order

Each is a separate implementation plan; later ones depend on earlier ones.

1. **Ledger core** — Account rework, JournalEntry/Line, PostingService, resolver, roles, mappings, template, fiscal settings, JV, CoA/GL/TB screens. Drops v1 finance tables.
2. **Lease posting + PDC register** — ChargeType, LeaseLine, Cheque, generator, post/amend/renew/extend, cheque lifecycle, register screens, penalties with approval, renter portal due/pay rewire.
3. **Recognition + termination + settlement** — segments, recognition entries, job, termination reversal, settlement posting.
4. **Vouchers + cut-over** — PISR, BPV, RCP, vendor account link, OB, reconciliation, contract import + batch reverse.
5. **Seed + golden tests + walkthrough** — demo re-seed, golden ledger replay, E2E.

## 14. Out of scope for v2

Reports beyond the four control views; multi-unit contracts; sub-contracts; landlord/owner commission; salesman; bill-wise vendor allocation; PDC payables; gateway fee posting; gateway refunds; mobile app changes; any historical (closed-contract) data from PACT.

## 15. Open items to confirm with the client

- Exact penalty rule with the property manager (`bounces_before_penalty`, whether late-payment penalties are proposed at all).
- Whether recognition entries dated month-end (D13) rather than the 1st is acceptable to their accountant.
- Whether any building is managed for a third-party landlord (would pull D6's landlord item forward).
