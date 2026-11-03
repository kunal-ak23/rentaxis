# Finance operations: bank reconciliation, supplier payments, property P&L — design

> **Product-owner decisions (2026-09-24). These are binding and override any conflicting text below.**
> - **Bank reconciliation:** import CSV/Excel statements first, with the column mapping saved per bank account.
>   Import de-duplicates by hash. Matching is automatic plus manual (1:1, 1:many, many:1). Create-from-line actions
>   go through the existing services. Finalize is allowed only when the difference is zero, and it sets a
>   per-bank-account "reconciled through" lock. A cheque can still be cleared without statement evidence, but the
>   reconciliation shows it as unreconciled.
> - **Supplier payments:** due dates and payment terms; invoice status (open, part-paid, paid) derived from
>   allocations; allocation of payments to invoices, with unallocated advances; payables aging; payment runs that
>   produce one voucher per vendor; post-dated supplier cheques held in a PDC-payable account until they are
>   presented; a duplicate-invoice guard; ACCOUNTANT access to vendors; vendor TRN required for input VAT.
>   **No approvals yet.**
> - **Per-property P&L and statement pack:** the owner model is deferred. The P&L must be built so that an owner
>   layer can sit on top of it later.

**Status: DRAFT 2026-09-24.** For review. No code has been written.
**Inputs:** the finance-ops audit of `origin/main` @ `baf4bef5` (gaps B1–B10, S1–S14, O1–O8), the accounting-v2
spec (`2026-09-17-accounting-v2-design.md`), and the production-readiness spec
(`2026-09-24-prod-readiness-features-design.md`). Where this spec makes a choice the decisions leave open, it
is marked **Decision**. The few choices that need the client's accountant are repeated in *Open questions*,
each with a recommended default.

| Audit gaps | Feature | Section |
|---|---|---|
| O6, O7, O8, S12 | Per-property P&L, statement pack, unassigned costs | §1 |
| S2–S8, S14 | Supplier AP: terms, allocation, aging, payment runs, PDC payable, duplicate guard, TRN | §2 |
| B1, B2, B6, B7, B8, B9, B10 | Bank statement import and matching | §3 |
| B3, B5 | Reconciliation statement, finalize, per-bank lock | §4 |
| S1 | ACCOUNTANT cannot load vendors (**separate hotfix PR, not designed here**) | *Build order*, PR 1 |

Ledger rules from the accounting-v2 spec hold throughout:

- `PostingService` is the only journal writer.
- `reverse` is the only mutation.
- Callers name `AccountRole`s, and `AccountResolver` maps each role per property.
- The golden gate (`./gradlew test -PincludeTags=golden`) stays green on every PR.

On tenancy and scope:

- Every new table extends `BaseTenantEntity`.
- Every service read runs inside `@Transactional`. The tenant filter is off outside a transaction (memory
  `project_tenant_filter_outside_tx`).
- Property-bound reads go through `PropertyScope`.

**Liquibase:** the next free changeset is **109**. Changeset 108 is VAT per instalment and is merged. The
numbers below follow the build order. If PRs land out of order, renumber at merge time and never edit a merged
changeset.

**Verified on `origin/main` (f1ec8aa0):**

- `JournalDocType`: TCO, TCR, PDR, CRT, CBR, CIL, RCP, STL, PEN, PISR, BPV, OB, JV, VTP. There is no YEC: the
  year-end close from the prod-readiness spec §3 is not built, and neither is its `GET /finance/profit-and-loss`.
  §1 below *is* that P&L, and the YEC PR must reuse it.
- `JournalDocType` and `JournalSourceType` are stored as VARCHAR with no CHECK constraint, so new values need no
  DDL.
- `AccountRole` has no bank-charge, interest, suspense or PDC-payable role.
- The seeded chart already has the group `B-02 PDC Payables` (LIABILITY) with no leaf under it. `D-02-003 Bank
  Charges` exists as a tenant-level leaf. `C-02 Indirect Income` has no leaf.
- Per-property direct-expense leaves are named `"<category> - <property>"` under `D-01`
  (`PropertyAccountService.DIRECT_EXPENSE_CATEGORIES`: Repairs & Maintenance, Cleaning, Security, Utilities,
  Insurance, Management Fees).
- `vouchers` has `invoice_number`, `cheque_number` and `cheque_date`. It has no due date, payment method or
  reference.
- `VoucherService.post` credits `payment_account` on `doc_date` for every BPV.
- For a PISR, `INPUT_VAT` and the vendor credit carry the **header** property.
- A deposit posts **no** journal. The bank is debited only by `CRT`, on the clearing date.
- `CBR` from CLEARED credits the bank.
- `STL` credits the bank for a refund.
- `VendorController` and `BankAccountController` are gated to SUPER_ADMIN and TENANT_ADMIN at class level.
- `ChequeController` staff routes include PROPERTY_MANAGER.
- Apache POI (`poi-ooxml 5.3.0`) and openhtmltopdf are already dependencies. `TaxInvoicePdfRenderer` and
  `ContractGenerationService` already render bilingual PDFs.
- `LandlordOrgService.deleteTenant` discovers tenanted tables by querying for them and purges them in several
  passes. New tables with `tenant_id` are picked up automatically, but they must be deletable in some order, so
  there must be no FK cycle.

---

## Shared additions

| Kind | Addition | Used by |
|---|---|---|
| `AccountRole` | `PDC_PAYABLE`: tenant-level liability "PDC payable – issued cheques", seeded leaf `B-02-001` under the existing `B-02 PDC Payables` | §2 |
| `AccountRole` | `BANK_CHARGES`: tenant-level expense, default mapping to the existing `D-02-003 Bank Charges` | §3 |
| `AccountRole` | `BANK_INTEREST_INCOME`: tenant-level income, new leaf `C-02-001 Bank Interest` under `C-02 Indirect Income` | §3 |
| `AccountRole` | `BANK_SUSPENSE`: tenant-level liability "Unidentified bank receipts", new leaf `B-01-06`. `B-01-05` stays free for the later owner layer. | §3 |
| `JournalDocType` | `BPC`: an issued (supplier) cheque presented. Dr `PDC_PAYABLE` / Cr bank. | §2 |
| `JournalDocType` | `BNK`: a bank-only item booked from a statement line (charges, interest, unidentified receipt) | §3 |
| `JournalSourceType` | `ISSUED_CHEQUE`, `BANK_STATEMENT` | §2, §3 |
| Column | `accounts.report_line VARCHAR(40) NULL`: the P&L row key used across properties | §1 |

All four roles return `isPropertyScoped() == false` and are added to `PropertyAccountService.defaultIfMissing`.
The leaves and default mappings are inserted per tenant by the changesets below, and only where the parent group
exists (the same per-row tolerance `template()` has). `PDC_PAYABLE` is **not** added to the opening-balance
derived-role exclusion list. PACT's PDC-payable balance is a real opening balance, and nothing in the cut-over
import derives it.

---

## 1. Per-property P&L and statement pack (O6, O7, O8, S12)

### Goal

The accountant picks a period and one or more properties and gets:

- income by type, expenses by type, and NOI per property;
- the same figures for the prior period, with the variance;
- one click from any figure to the journal lines behind it;
- a bilingual **property statement** PDF or CSV (rent billed, collected and outstanding; deposits held;
  expenses; VAT);
- a multi-property summary with an honest "Unassigned" column.

Today the only way to get a monthly property P&L is to subtract two cumulative trial balances in Excel.

### Decisions

- **Reports are computed live from `journal_lines`, and nothing is stored.** Every figure is a sum of posted
  lines, so the report ties to the GL by construction.
  - Reversed entries and their mirrors both count, as in the TB (`balancesAsOf` has no status predicate).
  - `YEC` lines are excluded once that doc type exists, so a closed year still shows its P&L.
- **Effective property of a line = `coalesce(journal_lines.property_id, accounts.property_id)`.**
  - An expense posted to "Cleaning - Marina Tower" without a line dimension still belongs to Marina Tower.
  - When both are null, the line is **Unassigned**.
  - When the line and the account disagree, the line wins. The report's data-quality footer counts such lines.
  - The TB's `propertyId` filter uses the line dimension only, so a P&L column can differ from the
    property-filtered TB movement by exactly those lines. The footer states this.
- **Rows are "report lines", not raw accounts, so properties line up.** Each property has its own leaf per role
  and category ("Rental Income Marina", "Rental Income Palm"), so a summary grouped by account id would never
  align. The new column `accounts.report_line` gives the shared key:

  | Leaf | `report_line` |
  |---|---|
  | Mapped to a property role (`property_account_mappings`) | the role name, for example `RENTAL_INCOME`, `ADMIN_FEE`, `PARKING_INCOME`, `CHEQUE_RETURN_PENALTY` |
  | Under `D-01`, named `"<category> - "` | `EXP_<CATEGORY>`, for example `EXP_REPAIRS_MAINTENANCE` |
  | Anything else (tenant-level leaves, hand-made leaves) | null: the row is the account itself |

  Labels come from the role or category's EN/AR label table. A null report line uses the account's `name_en`
  and `name_ar`.

  The CoA screen can edit `report_line` (a "Report line" picker), so an imported PACT chart can be grouped too.
  `PropertyAccountService` sets it when it creates a leaf.

- **NOI = Σ income − Σ expenses with the property as effective property.** There is no separate below-NOI
  section. The chart has no reliable "operating" flag, and an imported PACT chart has none either.

  Rows are grouped by the level-2 group of the account tree ("Direct Income", "Indirect Income", "Direct
  Expense", "Indirect Expense" on the standard seed). The accountant therefore sees financing items such as bank
  charges in their own group.

- **Unassigned costs are shown separately, never silently spread.**
  - The summary has an **Unassigned** column. The Total is Σ properties + Unassigned.
  - An optional **Allocate unassigned** toggle adds a report-only allocation row per property, labelled
    "Shared costs allocated (report only, not posted)". The basis can be units, recognised rent share, or
    equal.
  - Nothing is posted. When an owner layer arrives, it decides whether allocation becomes a journal.
- **Going forward, voucher lines must say where they belong.** A PISR or BPV line whose account is INCOME or
  EXPENSE must carry a property, or the user must explicitly tick **Shared / head office** on that line. This is
  a web-form rule plus a server check: a null property is refused unless the request says `shared: true`, which
  is not persisted because null already means shared. This closes S12 and O8 without inventing a pseudo-property.
- **The owner seam.** The service returns cells rather than a finished layout:
  - `PnlCell(propertyId | null, reportLine, accountId, debit, credit)`.
  - The statement pack is a list of `StatementSection` providers.

  A later owner layer only needs to supply its own pieces:
  - a `PropertyOwnership(propertyId, ownerId, sharePct, from, to)` source;
  - an owner-level fold (Σ over properties × share);
  - its own sections (management fee, payouts).

  The P&L itself does not change.

### Data model (changeset `109-pl-report-lines.yaml`)

| Change | Notes |
|---|---|
| `accounts.report_line VARCHAR(40) NULL` | The P&L row key |
| Data migration, role leaves | For every `property_account_mappings` row, set `report_line = role` on its account where `report_line` is null. **Decision:** a leaf mapped to two roles keeps the first role by role ordinal. |
| Data migration, expense leaves | For leaves under `D-01` with `property_id` set, match the name prefix `"<category> - "` for each seeded category and set `EXP_<CATEGORY>` |
| Index | `journal_lines (tenant_id, property_id)` already exists as `idx_jl_property`, and `journal_entries (tenant_id, entry_date)` exists. No new index. The expected volume is tens of thousands of lines per tenant per year. |

### Figures

**P&L (per property, period `[from, to]`)**

```
for each line l joined entry e, account a
  where e.entry_date in [from, to] and a.account_type in (INCOME, EXPENSE)
        and e.doc_type <> 'YEC'                         -- once YEC exists
  property = coalesce(l.property_id, a.property_id)     -- null → Unassigned
  row      = coalesce(a.report_line, a.id)
  income amount  = credit − debit ; expense amount = debit − credit
```

**Comparison.** `compare=PREVIOUS` is the immediately preceding period of the same shape. A calendar month
compares with the previous month, a quarter with the previous quarter, and a custom range with the same number
of days ending on `from − 1`. `compare=LAST_YEAR` is the same dates one year earlier; 29 February maps to
28 February. The variance is shown both in AED and as a percentage. The percentage is blank when the base is 0.

**Check row.** Σ over every column including Unassigned equals the net movement of INCOME and EXPENSE accounts
in `[from, to]`, computed tenant-wide without a property filter. The report shows ✓ or the difference. With
the coalesce rule the difference is always 0 unless the query is wrong. The row is there as a regression guard,
the same idea as the VAT return's ledger check.

### Worked example: Marina Tower (P1), September 2026 vs August 2026

| Report line | Sep 2026 | Aug 2026 | Δ |
|---|---:|---:|---:|
| Rental income (`CIL`) | 82,191.78 | 82,191.78 | 0.00 |
| Admin fee (`TCO` of a new lease) | 1,500.00 | 0.00 | +1,500.00 |
| Additional parking | 2,000.00 | 2,000.00 | 0.00 |
| Cheque return penalty (`PEN`) | 500.00 | 0.00 | +500.00 |
| **Income** | **86,191.78** | **84,191.78** | **+2,000.00** |
| Repairs & maintenance (`PISR` INV-7781) | 0.00 | 1,000.00 | −1,000.00 |
| Cleaning (`PISR` INV-7781 in Aug; AN-311 in Sep) | 3,000.00 | 400.00 | +2,600.00 |
| Utilities (`PISR` DEWA) | 4,200.00 | 0.00 | +4,200.00 |
| **Expenses** | **7,200.00** | **1,400.00** | **+5,800.00** |
| **NOI** | **78,991.78** | **82,791.78** | **−3,800.00 (−4.59%)** |

The Unassigned column for September holds items posted from the bank reconciliation in §3's example, which
carry no property:

- Bank charges 50.00
- Bank interest (120.00)

With **Allocate unassigned** by recognised rent share, P1 would receive `50 × 82,191.78 ÷ Σ rent` and so on, on
the report-only row.

**Drill-down.** Every amount cell links to the general ledger with `accountIds`, `propertyId`, `from` and
`to`. For a report line spanning several leaves, the link lists them all. The GL row links to the journal.

The GL endpoint filters on the line dimension only, so a cell made up partly of lines with a null dimension on
a property leaf would drill to a shorter list. **Decision:** the GL endpoint gains
`effectiveProperty=true`, which applies the same coalesce, and the drill link sets it.

### Property statement pack (per property, period)

Every section is computed live. The sections marked "ledger" tie to the GL. The sections marked "register" are
operational figures from the cheque register (spec v2 §7.5), and the pack labels them so.

| # | Section | Source | Definition |
|---|---|---|---|
| 1 | Income, expenses, NOI | ledger | the P&L column above, with the prior-period comparison |
| 2 | Instalments due | register | live rows (not CANCELLED, RETURNED or TRANSFERRED) with `row_kind <> DEPOSIT` and `cheque_date ∈ [from, to]`. Σ `amount` (gross), of which Σ `vat_amount` is VAT. Counted by status: cleared, pending, bounced. |
| 3 | Collected | ledger | Σ credits to `PDC_RECEIVABLE` for the property from `CRT` entries dated in the period, less `CBR` debits to the bank in the period (a cleared cheque that bounced later). Broken down by mode (cheque, transfer, cash, online) from the cheque row. |
| 4 | Outstanding at `to` | register and ledger | Register: rows overdue at `to` by `overdue(row)` (v2 §7.5), listed by renter and unit with days overdue. Ledger: the `RENT_RECEIVABLE` balance as of `to` for the property, shown next to it. The two differ by design (advance-billed TCO), and the pack says so. |
| 5 | Deposits held | ledger | `SECURITY_DEPOSIT` + `PARKING_DEPOSIT` balance as of `from − 1`, plus received, less refunded or forfeited (`STL`), carried (`JV` transfer), and the balance as of `to` |
| 6 | Expenses incurred | ledger | Section 1's expense rows, listed by voucher (number, vendor, invoice number, net, VAT) |
| 7 | Expenses paid | sub-ledger (§2) | Payments allocated in the period to invoices whose lines carry this property. A payment is split across properties by the invoice lines' gross share. Direct BPV lines to expense leaves are added. Unallocated vendor payments are listed below as "not attributable to a property yet". **Arrives with §2.** Until §2 lands, only the direct BPV lines are shown. |
| 8 | VAT | ledger | Output VAT: credits to `OUTPUT_VAT` in the period for the property (`VTP` and legacy `TCO`), by emirate. Input VAT: debits to `INPUT_VAT` with the property as effective property. Input VAT on a voucher with no header property falls under Unassigned; §2 makes the header property follow the lines when they all name one property. |
| 9 | Net cash | derived | Collected (3), less expenses paid (7), less deposits refunded (5). Labelled "net property cash movement", not "cash at bank", because a bank account can serve several properties. |

The footer shows:

- "Figures final" when `books_locked_through ≥ to`, otherwise "Provisional — period not locked";
- the generation time and the user.

### Services and endpoints

- `PropertyPnlService` (new, read-only, `@Transactional(readOnly = true)`):
  - `cells(from, to, propertyIds)`;
  - `pnl(from, to, propertyIds, compare, allocateBasis)`;
  - `statement(propertyId, from, to)`.
- `GET /api/v1/finance/reports/property-pl?from&to&propertyId=…&compare=NONE|PREVIOUS|LAST_YEAR&allocate=NONE|UNITS|RENT|EQUAL`.
  Repeat `propertyId` for several properties, or omit it for all. This is the prod-readiness spec's
  `/finance/profit-and-loss`, renamed. The YEC PR reuses it for its close preview.
- `GET /api/v1/finance/reports/property-pl.csv` has the same parameters.
- `GET /api/v1/finance/reports/property-statement?propertyId&from&to` returns JSON.
- `GET /api/v1/finance/reports/property-statement.pdf?propertyId&from&to&lang=en|ar`.
- `GET /api/v1/finance/reports/property-statement.csv`: one CSV with one block per section.
- `LedgerController` general ledger gains `effectiveProperty`.

**Roles:**

- SUPER_ADMIN, TENANT_ADMIN and ACCOUNTANT: all properties plus Unassigned.
- **PROPERTY_MANAGER: read-only, for assigned properties only.**
  - `PropertyScope.requireCanAccessProperty` runs on each named property.
  - With no property named, the list is `scopedPropertyIds()`.
  - The Unassigned column, the Total column, the allocation toggle and the check row are **omitted** for a
    manager, because they are tenant-wide figures.
  - The drill-down GL link stays in the same scope: the GL endpoint already rejects a manager, so for a manager
    the drill opens a scoped read-only line list served by the P&L endpoint (`/property-pl/lines?…`).

### Web UI

- **Finance → Reports** is a new group containing **Property P&L** and **Property statement**. The VAT return
  (prod-readiness §5a) joins this group when it lands.
- **Property P&L page:**
  - Controls: a period picker (month, quarter, year, custom), a property multi-select, Compare (None /
    Previous / Last year), Allocate unassigned (Off / by units / by rent / equal), and CSV.
  - The table has report lines as rows under the level-2 group headers. Columns: one per property, plus
    Unassigned and Total. With comparison on, each property shows a sub-column group
    `This period | Prior | Δ | Δ%`.
  - Amounts are drill links. The header shows the check-row badge.
  - With one property selected, the layout pivots to a single column with the comparison.
- **Property statement page:** property and period pickers, the nine sections as cards with tables, a
  Download PDF button (EN or AR), and CSV.
- **Voucher form:** each line gets a property picker with a **Shared / head office** option. The line cannot be
  saved with the property left blank.
- **Arabic and RTL:**
  - Column order uses logical start and end.
  - Amounts stay LTR inside `<bdi>`.
  - The PDF template uses `dir="rtl"` and the Arabic font setup that `TaxInvoicePdfRenderer` already embeds.
  - Report-line labels use AR names.
  - Numbers use Latin digits in both languages, matching the tax invoice.

### Edge cases

- **A property with no activity in the period** shows zero rows, not an empty page. Rows with a non-zero value
  in either period are shown.
- **A cross-property unit transfer** (prod-readiness §2), if it lands: each lease's lines carry their own
  property, so earned rent splits correctly by date.
- **A PISR line posted to P1's leaf with property P2:** it counts under P2 (the line wins), and the footer
  counts it.
- **Reversals:** a PISR amended in September for an August invoice shows the August expense in August and the
  reversal plus the replacement in September. That is correct ledger behaviour. The drill-down shows why.
- **Deleted or inactive properties** still appear when they have lines in the period.
- **Performance:** one grouped native query per period, with the comparison as a second query. The CSV and PDF
  use the same data.

### Tests

- Unit tests: the report-line backfill matcher (prefix, rename survival, hand-made "Security Deposit Refunds"
  not matched); the period shapes for comparison (month, quarter, custom, 29 February); allocation rounding (the
  largest remainder, so the Σ of the allocation equals the Unassigned amount exactly).
- ITs:
  - The worked example: post the August and September documents for P1 and a shared bank charge, then assert
    every cell, NOI and the variance, and that the check row is 0.
  - The line-versus-account property precedence.
  - Unassigned plus the allocation row.
  - A manager sees only assigned properties and no Unassigned or Total. A named foreign property returns 404.
    The response has no tenant-wide totals.
  - Tenant isolation: tenant B's lines are never summed into A's report. Two tenants post identical documents,
    and each P&L shows one set.
  - Statement pack: sections 3 and 5 tie to the ledger movement, and section 2 ties to the register.
  - The voucher line property rule: blank without `shared` is refused, and `shared` is accepted.
- Web tests: the pivot, the comparison columns, RTL snapshot, and that the manager view has no Unassigned column.
- **Golden gate:** this is read-only, apart from the `report_line` backfill, which touches no journal. Run it
  anyway.

---

## 2. Supplier AP: terms, allocation, aging, payment runs, PDC payable (S2–S8, S14)

### Goal

The accountant should be able to answer "what do we owe, to whom, and when is it due" from the system:

- pay everything due this week in one run;
- pay a contractor by post-dated cheque without the bank book dropping on the issue date;
- be stopped from booking the same supplier invoice twice;
- be stopped from claiming input VAT on a supplier with no TRN.

### Decisions

- **Allocation is a sub-ledger index, never a journal writer.** A BPV posts exactly as today, through
  `PostingService`, against the vendor's payable leaf. `voucher_allocations` only records which invoice a
  payment settles. Invoice status is derived: `open = gross − Σ live allocations`. It is PAID when open = 0,
  PART_PAID when 0 < open < gross, and OPEN otherwise.
- **An allocation has a date and can be released, so aging is reproducible as of any date.** An allocation
  counts as of `allocated_on` until `released_on`.
  - `allocated_on` defaults to max(payment date, invoice date).
  - It must be after `books_locked_through`.
  - An allocation dated inside a locked period cannot be released. This keeps a closed period's aging stable.
- **Payment method is explicit.** `vouchers.payment_method` is `TRANSFER | CHEQUE | CASH`, and
  `vouchers.payment_reference` holds the transfer reference (closes S14).
  - CASH requires a CASH leaf.
  - TRANSFER and CHEQUE require a BANK leaf.
- **Post-dated supplier cheques go to `PDC_PAYABLE` and hit the bank when presented.** This mirrors the
  receivable side (PDR, then CRT on clearing). A BPV with `payment_method = CHEQUE` and `cheque_date > doc_date`
  credits `PDC_PAYABLE` instead of the bank, and writes an `issued_cheques` row.
  - **Presented** posts `BPC` (Dr `PDC_PAYABLE` / Cr the bank leaf) on the presentation date.
  - Presentation is recorded from a bank statement line (§3) or by hand. By hand counts as unreconciled until
    it is matched.
  - **Decision: nothing posts automatically on the cheque date.** Evidence governs, as it does for clearing.
    See *Open questions* Q1.
  - A cheque dated on or before the voucher date posts Cr bank as today. It shows in the reconciliation as an
    unpresented payment until the statement debits it.
- **One vendor per BPV** when it touches a vendor payable. This is already true through
  `requirePayableLinesMatchTheVendor`.
- **Duplicate guard:** a POSTED PISR is unique on `(tenant_id, vendor_id, normalised invoice_number)`, where
  normalised means trimmed, upper-cased, with inner spaces and hyphens removed. `invoice_number` becomes
  **required** on PISR from this change.
- **TRN rule:** a PISR with any `vat_amount > 0` is refused at draft save and at post when `vendor.trn` is
  blank.
  - The message: "Input VAT needs the supplier's TRN from their tax invoice. Add the TRN to <vendor> or post
    the invoice without VAT."
  - The TRN is validated on the vendor as 15 digits (UAE format) when present.
- **Terms:** `vendors.payment_terms_days` defaults to 30. On a PISR, `due_date` defaults to
  `supplier_invoice_date + terms`, and the accountant can edit it. `supplier_invoice_date` is the date on the
  supplier's document and defaults to `doc_date`. `doc_date` remains the posting date.
- **The header property follows the lines.** When every line of a PISR names the same property and the header
  names none, the header takes it, so `INPUT_VAT` and the vendor credit carry it (§1, section 8).
- **No approvals** (decision). The payment-run preview is the only checkpoint.
- **ACCOUNTANT access to vendors** is the S1 hotfix (PR 1). Everything in this section assumes it has landed.
  AP screens are available to SUPER_ADMIN, TENANT_ADMIN and ACCOUNTANT.

### Data model (changeset `110-ap-open-items.yaml`)

| Change | Notes |
|---|---|
| `vendors.payment_terms_days INT NOT NULL DEFAULT 30` | |
| `vouchers.supplier_invoice_date DATE NULL` | PISR only. Backfill = `doc_date`. |
| `vouchers.due_date DATE NULL` | PISR only. Backfill = `doc_date` for existing PISRs, so they show as due, which is the conservative choice. |
| `vouchers.payment_method VARCHAR(10) NULL` | BPV only. Backfill: `CHEQUE` where `cheque_number` is not null, `CASH` where the payment account's sub-type is CASH, otherwise `TRANSFER` |
| `vouchers.payment_reference VARCHAR(60) NULL` | Transfer reference. Matched by §3. |
| `vouchers.payment_run_id UUID NULL FK payment_runs` | Added in 111 |
| `vouchers.invoice_no_norm VARCHAR(60) NULL` | Normalised invoice number, written by the service. Backfill with the same SQL normalisation. |
| `vouchers.duplicate_grandfathered BOOLEAN NOT NULL DEFAULT FALSE` | Data migration sets it to true on every POSTED PISR after the first in an existing duplicate group, so the index can be built without touching posted data |
| Partial unique index `ux_vouchers_pisr_invoice` | `(tenant_id, vendor_id, invoice_no_norm) WHERE doc_type = 'PISR' AND status = 'POSTED' AND NOT duplicate_grandfathered` |
| `voucher_allocations` (new) | `id, tenant_id, vendor_id, payment_voucher_id FK vouchers, invoice_voucher_id FK vouchers NULL, opening_item_id FK ap_opening_items NULL, amount NUMERIC(14,2) > 0, allocated_on DATE, released_on DATE NULL, release_reason TEXT, payment_run_id NULL, created_by, created_at, released_by`. CHECK: exactly one of `invoice_voucher_id` and `opening_item_id` is set. Indexes on `(payment_voucher_id)`, `(invoice_voucher_id)` and `(tenant_id, vendor_id)`. |
| `ap_opening_items` (new) | `id, tenant_id, vendor_id, invoice_number, invoice_date, due_date, amount, property_id NULL, created_by, created_at`. Supplier invoices open at cut-over. Allocatable like a PISR. |

The amend path (`VoucherService.amend`) reverses the original first. The original becomes REVERSED before the
replacement's row is inserted in the same transaction, so the partial unique index sees at most one POSTED row.
The flush order is asserted in a test.

### Allocation rules

`VoucherAllocationService`, the only writer of `voucher_allocations`:

1. Payment and invoice belong to the same vendor. Both are POSTED. The invoice is a PISR or an opening item.
2. Σ live allocations of a payment ≤ the payment's amount to the vendor. That amount is Σ of the BPV lines on
   `vendor.payable_account`.
3. Σ live allocations of an invoice ≤ its gross (net + VAT).
4. `allocated_on > books_locked_through`, and `allocated_on ≥` both documents' dates.
5. Rows are locked with `SELECT … FOR UPDATE` on both vouchers, in id order, before the sums are checked. Two
   concurrent allocations therefore cannot over-allocate one invoice.
6. The unallocated part of a payment is an **advance**. `POST /allocations` can apply it to a later invoice
   with no journal, because both are already on the same payable leaf.

**Lifecycle hooks:**

| Event | Effect on allocations |
|---|---|
| BPV posted with `allocations[]` (form or payment run) | Written in the same transaction as the post |
| BPV amended or reversed | Every live allocation is released on the reversal date, and the invoices re-open. The amend dialog warns: "This payment settles INV-7781 and INV-7790. They will show as unpaid." |
| PISR amended (replacement voucher) | Live allocations **carry** to the replacement, capped at the new gross. Any excess is released, and that part of the payment becomes an advance. |
| PISR reversed without replacement | Allocations are released. The payments become advances. |
| Issued cheque cancelled (below) | Its BPV is reversed, so the row above applies |

### Postings

| Event | Doc | Date | Lines |
|---|---|---|---|
| Supplier invoice | `PISR` | `doc_date` | unchanged: Dr expense lines (with property), Dr `INPUT_VAT` Σ VAT, Cr vendor payable gross |
| Payment by transfer, cash or current-dated cheque | `BPV` | `doc_date` | unchanged: Dr vendor payable / Cr bank or cash leaf |
| **Payment by post-dated cheque** (`CHEQUE`, `cheque_date > doc_date`) | `BPV` | `doc_date` | Dr vendor payable / **Cr `PDC_PAYABLE`**. The line carries the narration "PDC <no> dated <date> on <bank leaf>". An `issued_cheques` row is written as ISSUED. |
| **Issued cheque presented** | `BPC` | presentation date (≥ `cheque_date`) | Dr `PDC_PAYABLE` / Cr the bank leaf named on the BPV. Source `ISSUED_CHEQUE`, with `source_id` = the issued cheque. |
| Issued cheque cancelled or stopped (ISSUED) | reversal of the `BPV` | chosen date | Mirror: Dr `PDC_PAYABLE` / Cr vendor payable. Allocations are released. |
| Presented cheque returned unpaid by our bank (rare: insufficient funds) | reversal of the `BPC` | return date | Dr bank / Cr `PDC_PAYABLE`. The status goes back to ISSUED, and the accountant then cancels or re-presents. |

`issued_cheques` (changeset `112-pdc-payable.yaml`):

- Columns: `id, tenant_id, voucher_id, vendor_id, bank_account_id (GL leaf), cheque_number, cheque_date, amount, status (ISSUED | PRESENTED | CANCELLED), presented_on, bpc_journal_id, cancelled_on, cancel_reason, opening BOOLEAN DEFAULT FALSE, created_at`.
- Unique `(tenant_id, bank_account_id, cheque_number)` among non-CANCELLED rows.
- The same changeset adds leaf `B-02-001 PDC Payable – issued cheques` / `شيكات مؤجلة صادرة` and the
  `PDC_PAYABLE` tenant default mapping.

**Cut-over:** PACT's open issued PDCs arrive as an OB balance on `B-02-001`. They are registered as
`issued_cheques` rows with `opening = true` and no BPV. The check is Σ = the OB balance on `PDC_PAYABLE`, and the
screen shows the difference. Presenting them posts `BPC` like any other.

`PostingService.post` is unchanged. `VoucherService.post` chooses the credit account. `IssuedChequeService`
posts `BPC` and reversals through `PostingService`.

### Worked example

Vendors:

- **Gulf AC Services LLC**, TRN 100123456700003, terms 30.
- **Al Noor Cleaning**, TRN 100765432100003, terms 30.

The bank leaf is "Emirates Islamic - Marina Tower", which belongs to bank account *EI 0123* in §3.

| # | Doc | Date | Lines | Sub-ledger |
|---|---|---|---|---|
| 1 | `PISR-26/19` Gulf AC INV-7702 (annual AC AMC, P2) | 01/08/2026 | Dr R&M – P2 19,047.62, Dr Input VAT 952.38 / Cr Gulf AC 20,000.00 | due 31/08 |
| 2 | `PISR-26/20` Gulf AC INV-7781 (P1) | 01/08/2026 | Dr R&M – P1 1,000.00, Dr Cleaning – P1 400.00, Dr Input VAT 50.00 / Cr Gulf AC 1,450.00 | due 31/08 |
| 3 | `PISR-26/21` Gulf AC INV-7790 (P2) | 20/08/2026 | Dr Security – P2 2,000.00, Dr Input VAT 100.00 / Cr Gulf AC 2,100.00 | due 19/09 |
| 4 | `BPV-26/50` PDC 000031 dated 28/09 to Gulf AC | 15/08/2026 | Dr Gulf AC 20,000.00 / **Cr PDC_PAYABLE 20,000.00** | allocation INV-7702 20,000 → PAID. `issued_cheques` 000031 ISSUED. |
| 5 | `PISR-26/24` Al Noor AN-311 (P1) | 05/09/2026 | Dr Cleaning – P1 3,000.00, Dr Input VAT 150.00 / Cr Al Noor 3,150.00 | due 05/10 |
| 6 | Payment run `PR-26/4` (due by 30/09, transfer from EI – Marina): INV-7781 in full, INV-7790 part (600; the rest is disputed) | 10/09/2026 | `BPV-26/55`: Dr Gulf AC 2,050.00 / Cr Emirates Islamic - Marina Tower 2,050.00, reference `TRF-7781` | allocations 1,450 + 600. INV-7781 PAID, INV-7790 PART_PAID (open 1,500). |
| 7 | 000031 presented (from the statement, §3) | 28/09/2026 | `BPC-26/3`: Dr PDC_PAYABLE 20,000.00 / Cr Emirates Islamic - Marina Tower 20,000.00 | `issued_cheques` PRESENTED |

Row 1's gross is 20,000 including VAT. The VAT is `20,000 × 5 ÷ 105 = 952.38`, and the net is 19,047.62.

Without row 4's PDC treatment, the Marina bank leaf would have dropped by 20,000 on 15/08 while the money sat in
the bank until 28/09.

**Payables aging as of 30/09/2026:**

| Vendor | Current | 1–30 | 31–60 | 61–90 | 90+ | Unallocated advances | Open total | Ledger balance | Δ |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Al Noor Cleaning | 3,150.00 | | | | | | 3,150.00 | Cr 3,150.00 | 0.00 |
| Gulf AC Services | | 1,500.00 | | | | | 1,500.00 | Cr 1,500.00 | 0.00 |
| **Total** | **3,150.00** | **1,500.00** | | | | | **4,650.00** | **4,650.00** | **0.00** |

Issued PDCs outstanding at 30/09 are listed on the same page: none (000031 was presented). At 31/08 the list
would show 000031 for 20,000, which ties to the `PDC_PAYABLE` balance.

### Aging definition

`GET /api/v1/finance/reports/payables-aging?asOf&propertyId?&vendorId?`:

- **Items:** POSTED PISRs and opening items with `supplier_invoice_date ≤ asOf` (a PISR's `doc_date ≤ asOf`
  too). Open amount as of `asOf` is gross − Σ allocations with `allocated_on ≤ asOf` and `released_on` either
  null or > `asOf`. A PISR reversed on or before `asOf` is excluded.
- **Buckets** by `asOf − due_date`:
  - Current (not yet due, including due today);
  - 1–30, 31–60, 61–90 and 90+ days overdue.
- **Advances:** the unallocated amount of each POSTED BPV to the vendor, as of `asOf`, as a negative column.
- **Tie-out per vendor:** open total − advances = the credit balance of `vendor.payable_account` as of `asOf`.
  - A non-zero Δ means something other than a PISR, BPV or opening item moved the leaf, for example a JV or an
    OB without opening items.
  - The row shows Δ with a drill to the vendor ledger.
  - It is a warning, not a block.
- **Property filter:** an item belongs to property P when any of its lines names P. The amount shown is the
  gross share of P's lines. Header-only property is used for opening items. With a property filter, the
  advances column and the tie-out are hidden, because they are vendor-level.
- **Roles:**
  - SUPER_ADMIN, TENANT_ADMIN and ACCOUNTANT see everything.
  - PROPERTY_MANAGER gets read-only access to the property-filtered view for their assigned properties only,
    through `PropertyScope`. `propertyId` is required for a manager (`requirePropertyNamedByManager`).

### Payment run

Changeset `111-payment-runs.yaml`:

- `payment_runs`: `id, tenant_id, run_number (PR-<fy2>/<seq>), payment_date, payment_account_id, method, cheque_date NULL, first_cheque_number NULL, narration, status (DRAFT | POSTED | CANCELLED), created_by/at, posted_by/at`.
- `payment_run_items`: `id, tenant_id, run_id, vendor_id, invoice_voucher_id NULL, opening_item_id NULL, amount, apply_advance BOOLEAN`.
- `vouchers.payment_run_id` FK.

**Flow:**

1. **New run.** Filters: due on or before a date (default today + 7), vendor, property, and "include
   part-paid". The list shows every open item with open amount, due date, days overdue and vendor. Items
   already in another DRAFT run are flagged.
2. **Select.** Tick items. The amount defaults to the open amount and can be reduced for a partial payment.
   Choose the payment account (a BANK leaf, or CASH for method CASH), the payment date, and the method. For
   CHEQUE, also the cheque date (default: the payment date) and the first cheque number. Vendors with an
   unallocated advance show "apply advance 5,000 first" (default on). An applied advance creates allocations
   only and reduces that vendor's payment.
3. **Preview.** For each vendor: items, amount, advance applied, net payment, assigned cheque number (sequential
   in vendor-name order), the vendor's IBAN, and the journal lines each BPV would post (bank, or `PDC_PAYABLE`
   when the cheque date is after the payment date). The preview lists every problem at once:
   - a vendor inactive or without a payable leaf;
   - a missing IBAN for TRANSFER (warning);
   - a date in a locked period, fiscal lock or bank lock (§4);
   - an open amount changed since selection;
   - a cheque number already used on that bank leaf.
4. **Post.** One transaction, all or nothing, like `clearBatch`:
   1. Lock every selected invoice's voucher row `FOR UPDATE` in id order.
   2. Re-validate the open amounts.
   3. Create one BPV per vendor (`payment_run_id` set, one line Dr vendor payable for the net payment,
      `payment_reference = run_number`) and post it through `VoucherService.post`.
   4. Write the allocations, and the `issued_cheques` rows for PDCs.
   5. Mark the run POSTED.

   Any failure rolls the whole run back.
5. **After posting.** Download the **bank upload CSV**: vendor name, IBAN, bank, amount, reference
   (`run_number/vendor`). There is no WPS or SIF format; that is out of scope. For cheques, print the **cheque
   list**. Reversing one BPV from a posted run is an ordinary voucher amend or reverse. The run stays POSTED and
   shows that voucher as reversed.

A DRAFT run can be edited or deleted. It holds no reservation. Step 4 is the real guard against paying an
invoice twice.

### Services and endpoints

- `VoucherAllocationService`, `PaymentRunService`, `IssuedChequeService` (new).
- `VoucherService` changes:
  - due date, terms, TRN, duplicate and method validation;
  - the PDC credit branch;
  - `allocations[]` on BPV create;
  - the allocation hooks in `amend`.
- Endpoints (all under `/api/v1/finance`, SUPER_ADMIN, TENANT_ADMIN, ACCOUNTANT unless noted):

| Method | Path | Notes |
|---|---|---|
| GET | `/vouchers/open-items?vendorId&dueBefore&propertyId&includePartPaid` | PISRs and opening items with their open amount |
| POST | `/vouchers` (BPV) | accepts `allocations[{invoiceId \| openingItemId, amount}]` |
| POST | `/voucher-allocations` | `{paymentId, invoiceId \| openingItemId, amount, allocatedOn?}`: apply an advance |
| DELETE | `/voucher-allocations/{id}` | release, with a reason. Refused when `allocated_on ≤` the lock. |
| GET | `/vouchers/{id}/allocations` | on either side |
| GET/POST/PUT/DELETE | `/ap-opening-items` | cut-over open items. Σ per vendor is compared with the vendor leaf's OB line. |
| GET | `/reports/payables-aging` (+ `.csv`) | see above. PROPERTY_MANAGER is scoped. |
| GET/POST/PUT/DELETE | `/payment-runs`, `/payment-runs/{id}` | DRAFT only for PUT and DELETE |
| GET | `/payment-runs/{id}/preview` | |
| POST | `/payment-runs/{id}/post` | |
| GET | `/payment-runs/{id}/bank-file.csv` | |
| GET | `/issued-cheques?status&bankAccountId&from&to` | register |
| POST | `/issued-cheques/{id}/present` `{date}` | manual presentation (unreconciled until matched) |
| POST | `/issued-cheques/{id}/cancel` `{date, reason}` | reverses the BPV |
| POST | `/issued-cheques/{id}/unpresent` `{date, reason}` | reverses the BPC |
| POST | `/issued-cheques/opening` | cut-over rows |

### Web UI

- **Purchase invoice form:**
  - supplier invoice date, terms (pre-filled from the vendor), and due date;
  - invoice number is required, and a duplicate is refused inline: "INV-7781 from Gulf AC is already posted as
    PISR-26/20";
  - the VAT column is disabled with an explanation when the vendor has no TRN;
  - status chip: Open / Part-paid / Paid.
- **Payment voucher form:**
  - method (Transfer, Cheque, Cash), reference or cheque number and date;
  - a PDC banner when the cheque date is after the voucher date: "Held in PDC payable until presented";
  - an **Allocate** panel listing the vendor's open items, with auto-fill oldest-first and "leave as advance".
- **Vendors:**
  - payment terms field;
  - the list gains Open, Overdue and Advance columns;
  - the vendor page gains an **Open items** tab and a statement of account (PDF, EN/AR: open items plus the
    ledger for a period), which closes S11 cheaply.
- **Finance → Payables:**
  - **Aging:** as-of date, property, bucket table, tie-out column, CSV;
  - **Payment runs:** list and the new-run wizard (select, preview, post);
  - **Issued cheques:** register with Present, Cancel and Unpresent actions, and a "Past date, not presented"
    filter;
  - **Opening items:** a cut-over grid.
- AR and EN strings. RTL-safe tables. Amounts inside `<bdi>`.

### Edge cases

- **Partial payment, then an amended invoice.** INV-7790 (2,100) has 600 paid. It is amended to 1,800. The
  allocation of 600 carries to the replacement, whose open amount is 1,200. If it were amended to 500, the
  allocation would carry 500 and release 100, which becomes an advance.
- **An advance with no invoice yet.** A BPV with no allocations is an advance. Aging shows it as negative, and
  the tie-out holds.
- **One BPV paying a vendor and an expense.** Only the vendor-payable lines count as the allocatable amount.
- **A credit note from a supplier** is not a document type yet. It is a JV to the vendor leaf. Aging then shows
  a Δ in the tie-out. It is listed under *Out of scope*.
- **A PDC cheque dated in a period that later gets locked.** Presentation is dated when it happens, and the lock
  applies to that date only. The BPV is already posted.
- **A PDC presented before its date.** Refused ("a cheque cannot be presented before 28/09/2026").
- **A payment run whose cheque numbers collide** with an issued cheque on the same bank leaf. The preview flags
  it, and the index enforces it.
- **A vendor deactivated after invoices were posted.** Their open items stay in aging. Pay them with a manual
  BPV. A run refuses an inactive vendor.
- **The same supplier invoice number from two vendors** is allowed. The key includes the vendor.
- **Locked periods:** the BPV date is checked by `PostingService`, the allocation date by rule 4, and the BPC
  date by `PostingService` plus the bank lock (§4).
- **Demo seed:** `scripts/seed_demo_tenant.py` posts one PISR and one BPV. It must send `invoiceNumber`,
  `supplierInvoiceDate` and `paymentMethod: TRANSFER`, and allocate the BPV. The seed is updated in the same
  PR.

### Tests

- Unit tests: invoice-number normalisation; due-date defaulting; bucket boundaries (0, 1, 30, 31, 90, 91); the
  allocation arithmetic.
- ITs:
  - the worked example end to end, asserting every journal, the allocation states, aging as of 31/08 and
    30/09, and a tie-out Δ of 0;
  - over-allocation refused, for an invoice and for a payment;
  - a concurrent double allocation, with two threads and one winning;
  - BPV amend releases allocations;
  - PISR amend carries them, capped;
  - an allocation in a locked period cannot be released;
  - a duplicate PISR refused, and the amend of a PISR keeping the same number is allowed;
  - a grandfathered duplicate does not block the index;
  - TRN missing plus VAT refused;
  - PDC: ISSUED → PRESENTED → unpresented → cancelled, with `PDC_PAYABLE` at 0 at the end and the bank moving
    only on `BPC`;
  - presentation before the cheque date refused;
  - payment run all or nothing: one vendor invalid means no BPV posted;
  - payment run with an advance applied;
  - payment run racing a manual BPV on the same invoice: one of them fails re-validation;
  - opening items: allocatable, and Σ checked against OB;
  - tenant isolation: tenant B's allocations and runs are invisible, and allocating a payment of A to an
    invoice id of B returns 404;
  - a manager's aging view is scoped and requires `propertyId`.
- **Golden gate:** vouchers are not in the golden fixtures, but `VoucherService.post` changes. Run it.

---

## 3. Bank statement import and matching (B1, B2, B6, B7, B8, B9, B10)

### Goal

The accountant uploads the month's bank statement (CSV or Excel), and the system lines up each statement line
with the ledger movements it represents. The accountant then books the bank-only items (charges, interest,
returned cheques, presented supplier cheques, transfers received) from the line itself, through the existing
services. §4 turns the result into a reconciliation statement and a lock.

### Decision: one bank account = one real account, owning one or more ledger leaves

The audit (B7) found the mismatch:

- `BankAccount.coa_account_id` defaults to the property's own BANK leaf, so one IBAN that serves three
  buildings has three GL leaves.
- Deposits, clearings and vouchers name a GL leaf, never a `BankAccount`.

Options considered:

| Option | Verdict |
|---|---|
| Reconcile at the parent group (`A-02-02 Bank`) | Rejected. The parent covers every bank the tenant has, so one statement can never equal it. |
| Force one leaf per IBAN and re-point property BANK mappings | Rejected for now. It changes which account future CRTs hit, it is disruptive for tenants with history, and it loses the per-property bank balance the client uses. |
| **A `BankAccount` owns a set of ledger leaves** | **Chosen.** Book balance = Σ over the set. Statement lines match journal lines on any leaf in the set. Per-property BANK roles stay as they are. |

**Rules:**

- A ledger leaf belongs to **at most one** bank account. This is a unique index. As a result, every bank journal
  line belongs to exactly one reconciliation, and the lock (§4) is unambiguous.
- A leaf in the set must be of sub-type BANK. CASH leaves are refused, because cash is counted, not
  reconciled.
- Creating a bank account adds its `coa_account_id` to its set, as today's default.
- The Bank Accounts page gets a **Ledger accounts** multi-select. Adding a leaf that another bank account owns
  is refused with the other account's name.
- Backfill: for each bank account, insert `coa_account_id`.
  - Where two bank accounts share a leaf (possible today), the default one keeps it, or else the oldest.
  - The other account gets no leaf and shows "Assign a ledger account before reconciling". Its import works,
    but matching and reconciliation are refused until a leaf is assigned.

**Worked set:** bank account **EI 0123** (Emirates Islamic, AE07 0260 …0123, AED) owns two leaves:

- "Emirates Islamic - Marina Tower" (P1's BANK leaf);
- "Emirates Islamic - Palm Court" (P2's BANK leaf).

Both buildings' cheques are banked into one real account.

### Data model (changeset `113-bank-statements.yaml`)

| Change | Notes |
|---|---|
| `bank_account_ledgers` (new) | `tenant_id, bank_account_id FK, account_id FK accounts, created_at`. PK `(bank_account_id, account_id)`. **Unique `(tenant_id, account_id)`.** Backfill as above. |
| `bank_accounts.bank_trn VARCHAR(20) NULL` | The bank's TRN. Required before a charge can be booked with input VAT. |
| `bank_statement_profiles` (new) | `id, tenant_id, bank_account_id UNIQUE, file_kind (CSV \| XLSX), sheet_name NULL, header_row INT, first_data_row INT, csv_delimiter, date_formats TEXT[]` (default `{dd/MM/yyyy, dd-MMM-yyyy, dd/MM/yy}`), `columns JSONB` (`txnDate, valueDate, description, reference, debit, credit, amount, amountSign, balance, chequeNo`: each a column letter or header name, nullable), `amount_mode (SPLIT \| SIGNED \| DRCR_FLAG)`, `cheque_no_pattern` (default `\b\d{6}\b`), `match_window_days INT DEFAULT 3`, `updated_by/at` |
| `bank_statement_imports` (new) | `id, tenant_id, bank_account_id, file_name, blob_path, file_sha256, lines_read, lines_new, lines_duplicate, first_date, last_date, opening_balance NULL, closing_balance NULL, warnings JSONB, imported_by, imported_at` |
| `bank_statement_lines` (new) | `id, tenant_id, bank_account_id, import_id, seq BIGINT` (order within the account: txn date, then file order), `txn_date, value_date NULL, description, reference NULL, cheque_no NULL, amount NUMERIC(14,2)` (signed: + credit, − debit), `running_balance NULL, line_hash CHAR(64)`. **Unique `(bank_account_id, line_hash)`.** Index `(tenant_id, bank_account_id, txn_date)`. |
| `bank_matches` (new) | `id, tenant_id, bank_account_id, method (AUTO_CHEQUE \| AUTO_REFERENCE \| AUTO_AMOUNT_DATE \| AUTO_GROUP \| MANUAL \| CREATED \| CONTRA), status (SUGGESTED \| CONFIRMED \| UNDONE), confidence (HIGH \| MEDIUM), created_by/at, confirmed_by/at, undone_by/at, undo_reason` |
| `bank_match_statement_lines` (new) | `tenant_id, match_id, statement_line_id, released BOOLEAN DEFAULT FALSE`. Partial unique `(statement_line_id) WHERE NOT released`. |
| `bank_match_book_items` (new) | `tenant_id, match_id, journal_line_id NULL, opening_item_id NULL` (§4), `released BOOLEAN DEFAULT FALSE`. Partial unique `(journal_line_id) WHERE NOT released` and `(opening_item_id) WHERE NOT released`. |
| Roles, leaves, mappings | `BANK_CHARGES` → `D-02-003`. `C-02-001 Bank Interest` / `فوائد بنكية` → `BANK_INTEREST_INCOME`. `B-01-06 Unidentified bank receipts` / `مقبوضات بنكية غير محددة` → `BANK_SUSPENSE`. |

**How matches are stored:**

- A SUGGESTED match holds its children too. The partial unique indexes cover SUGGESTED rows, so one line
  cannot appear in two suggestions.
- Re-running auto-match first releases every SUGGESTED match.
- Undo sets `status = UNDONE` and `released = true` on its children. The row stays as the audit trail.
- **Journal lines are never touched.** "Reconciled" is derived from the join, so the ledger stays immutable.
- **Match balance invariant:** Σ statement `amount` = Σ over book items of (debit − credit), plus the signed
  amount of opening items. This is enforced in `BankMatchService` on every create or confirm. A CONTRA match
  has only one side, and that side sums to 0.

### Import

`BankStatementImportService.import(bankAccountId, file, profileOverride?)`:

1. **Refusals.** The bank account's currency must be AED ("multi-currency statements are out of scope"). The
   file must be at most 5 MB and at most 20,000 lines.
2. **Profile.** If the account has none, or the header row no longer matches the saved column names, the call
   returns `PROFILE_REQUIRED` with the first 20 rows as a grid, and the web shows the mapping wizard. Saving the
   wizard saves the profile and re-runs the import.
3. **Parse, per row:**
   - **Dates:**
     - An XLSX date-formatted cell is read through `DateUtil.getLocalDateTimeCellValue`.
     - A numeric cell in a date column is read as an Excel serial date.
     - A string is parsed with the profile's `date_formats` in order. Day-first is always assumed.
     - `03/09/2026` is 3 September. A string that parses under no format is an error that names the row.
   - **Amounts:**
     - Strip `AED`, thousands separators and spaces.
     - `(1,234.50)` and a trailing `-` mean negative. A trailing `DR`/`CR` sets the sign in `DRCR_FLAG` mode.
     - SPLIT mode gives `amount = credit − debit`. Exactly one of the two must be non-zero; otherwise the row is
       an error.
   - **Rows that are not transactions:** blank rows, "Opening balance" and "Closing balance" rows (detected by
     the description when the amount is empty), and total rows are skipped. A skipped opening or closing row's
     balance is recorded on the import.
   - **Cheque number:** taken from the `chequeNo` column if mapped, else by `cheque_no_pattern` from reference,
     then description.
4. **Order.** Many UAE exports are newest-first. The file order is used when the running balance continues
   top-down; reversed when it continues bottom-up; otherwise date order with a warning.
5. **Running-balance continuity.** When a balance column exists:
   - `balance[i] = balance[i−1] + amount[i]` must hold for every row. A break refuses the import and names the
     first broken row: "Row 38: balance 101,250.00 does not follow 98,000.00 + 2,050.00".
   - The first row is also checked against the latest earlier-dated line already stored for the account. A gap
     there is a **warning** at import ("lines may be missing between 15/09 and 16/09: the balance jumps by
     1,200.00"). It becomes a blocker at finalize (§4).
6. **De-duplication.**
   - `line_hash = sha256(bank_account_id | txn_date | value_date | amount | normalised description | reference | running_balance | occurrence)`.
   - `normalised description` is upper-cased with whitespace collapsed.
   - `occurrence` is the ordinal among identical tuples in the same file. It is only needed when there is no
     running-balance column, to keep two identical same-day charges distinct.
   - A line whose hash exists for the account is skipped and counted.
   - A re-import of the same file is therefore a no-op that reports "0 new, 214 already imported". An
     overlapping export only adds what is new.
7. **Storage.** The file goes to blob storage (the voucher-attachment path; Azurite locally), and the import row
   records it.

**Lines never change after import.** A wrong import is removed with **Delete import**, which deletes its lines.
That is allowed only while none of them is in a CONFIRMED match or inside a finalized reconciliation. Lines are
not ledger rows, so delete is permitted.

### Book side

The candidate book items for bank account BA are the journal lines on any leaf in BA's set, dated on or after
BA's reconciliation start (§4), that are not in a live match. By the posting rules in v2 §7.2, §9 and the
additions above, they come from these documents:

| Doc | Typical bank-side line | Statement side |
|---|---|---|
| `CRT` | Dr bank: cheque cleared, transfer or cash received | credit |
| `CBR` (from CLEARED) | Cr bank: cheque returned after clearing | debit |
| `BPV` | Cr bank: supplier or other payment | debit |
| `BPC` | Cr bank: our post-dated cheque presented | debit |
| `STL` | Cr bank: deposit refund | debit |
| `BNK` | charges, interest, unidentified receipts booked from a line | either |
| `JV` | anything manual | either |
| `OB` | not matchable: the opening balance is handled by the first reconciliation's opening items (§4) | none |

A reversal mirror and its original, both unmatched, are a natural **CONTRA** pair. Auto-match pairs them (book
side only, Σ = 0), so a CRT that was reversed does not show as a deposit in transit.

### Matching

`BankMatchService.autoMatch(bankAccountId, from, to)` releases SUGGESTED matches in the window, then proposes in
rule order. A statement line or book item used by an earlier rule is not offered to a later one. **Every
auto-match is a SUGGESTED proposal.** The accountant confirms each one, or uses **Confirm all HIGH**.

| # | Rule | Confidence |
|---|---|---|
| 1 | **Cheque number.** A statement line with `cheque_no` equal to a cheque's number, the same absolute amount, and the right direction. Credit → the cheque's `CRT` line on a leaf in the set. Debit → an `issued_cheques` row's `BPC` line, or a `CBR` bank line for a returned receivable cheque. The candidate must be unique. | HIGH |
| 2 | **Reference.** Statement `reference`, or the description containing a BPV's `payment_reference` or a payment run number, with equal amount | HIGH |
| 3 | **Deposit group (many book lines : one statement line).** A credit equal to Σ of the `CRT` lines of cheques sharing one `deposited_at` and one debit leaf in the set, with every `CRT` dated within the window of the line. Groups come from the register, not subset-sum, so the search stays linear. | MEDIUM |
| 4 | **Amount and date.** Exact amount, `abs(value_date or txn_date − entry_date) ≤ match_window_days`, and a unique candidate in both directions (the line has one candidate and the candidate has one line). With several candidates, nothing is proposed, and the manual matcher lists them. | MEDIUM |
| 5 | **Contra pairs.** Book: an original and its reversal mirror, both unmatched and both in the window. Statement: a credit and a debit with the same amount, same reference and ≤ 3 days apart (a bank's own error and correction). | HIGH |

**Manual match.** Select any statement lines and any book items (including opening items) whose sums balance,
then **Match**. This covers:

- 1:1;
- 1:many (one statement credit ↔ several CRTs);
- many:1 (one CRT ↔ two partial statement credits, as when the bank splits a cheque, or a charge and its VAT ↔
  one `BNK`);
- many:many.

The footer shows Σ statement, Σ book, and the difference, and **Match** is enabled at 0.00.

**Undo.** Available on any match not inside a finalized reconciliation (§4). A CREATED match's undo offers
"also reverse the entry it created". That reversal goes through `PostingService.reverse`, or through the owning
service for cheques: see below.

### Create-from-line actions

Each action runs in **one transaction**: it calls the existing service, then writes a CONFIRMED match with
`method = CREATED` between the selected statement line or lines and the bank-side journal line the service just
posted. Dates default to the line's `value_date`, falling back to `txn_date`. Services refuse exactly as they do
today (future date, before deposit, locked period), plus the bank lock in §4.

| Action | Offered when | Calls | Posts |
|---|---|---|---|
| **Clear cheque(s)** | A credit, and one or more **DEPOSITED** cheques whose `debit_account` is in the set, with Σ amount = the line. Pre-selected by rule 1 or 3 candidates. | `ChequeService.clear` (one) or `clearBatch` (several), with `clearingDate = value date` | `CRT` per cheque. The match is many:1 when several. |
| **Receive transfer** | A credit, and a **REGISTERED** row of mode TRANSFER or CASH, or a penalty or collection row, with equal amount. The candidate list is ranked by amount, then by renter name found in the description. | `ChequeService.receive` with `debitAccountId` = the leaf in the set for the row's property | `CRT` |
| **Bounce cheque** | A debit, and a **CLEARED** cheque with equal amount (description contains RTN, RETURN, UNPAID or similar, or the cheque number matches) | `ChequeService.bounce` | `CBR`: Dr RENT_RECEIVABLE / Cr bank |
| **Present supplier cheque** | A debit, and an `issued_cheques` row ISSUED with equal amount and number (rule 1), with `cheque_date ≤` the line date | `IssuedChequeService.present` | `BPC` |
| **Bank charge** | A debit (one line, or a charge line plus its VAT line) | `BankStatementPostingService.charge(lines, vatIncluded)` | `BNK`: Dr `BANK_CHARGES` net, Dr `INPUT_VAT` VAT (only when `bank_trn` is set; otherwise the whole amount goes to charges), Cr the bank leaf. The property dimension is that of the bank leaf, if the leaf has one and the user leaves "Shared" unticked. The default is Shared, when the set has several leaves. |
| **Interest** | A credit | `BankStatementPostingService.interest` | `BNK`: Dr bank leaf / Cr `BANK_INTEREST_INCOME` |
| **Unidentified receipt** | A credit | `BankStatementPostingService.suspense` | `BNK`: Dr bank leaf / Cr `BANK_SUSPENSE`. The reference is kept in the narration. |
| **Other** | Either | `BankStatementPostingService.other(accountId, propertyId)` | `BNK` to any active leaf that is **not** a control account. Refused for RENT_RECEIVABLE, PDC_RECEIVABLE, deposit, vendor-payable, `PDC_PAYABLE` and `BANK_SUSPENSE` leaves, because those have their own documents. |

In every row where the set has several leaves, the posting leaf is the one for the relevant property (the
cheque's `debit_account`, or the row's property's BANK mapping) when that leaf is in the set. Otherwise the user
picks a leaf from the set.

**Clearing the suspense.** When an unidentified receipt is identified as a renter's payment, the register row
is **received from suspense**:

- `ChequeService.receive` with `debitAccountId` = the `BANK_SUSPENSE` leaf posts `CRT` Dr BANK_SUSPENSE / Cr
  PDC_RECEIVABLE. That clears the suspense and settles the row.
- The bank line stays matched to the original `BNK`.
- This is the one extension to the cheque service: a new predicate `isReceiptSource(account)` = settlement
  account, or the `BANK_SUSPENSE` leaf. It is used by `receive` only. `isSettlementAccount`, and therefore BPV,
  is unchanged.

**Evidence on the cheque register (B4 context).**

- Clearing by hand stays possible (decision) for every role that can clear today.
- The register gains a **Bank** column:
  - "Confirmed <date>" when the row's `CRT` bank line is in a CONFIRMED match;
  - "Not on a statement yet" when it is not;
  - "—" for rows cleared to a CASH leaf.
- A hand-cleared cheque with no statement line shows as a *deposit in transit* in the reconciliation (§4) until
  it is matched. That is how "the rec marks it unreconciled" is implemented.
- Whether property managers should keep the Clear action is Q2.

### Services and endpoints

- New services:
  - `BankStatementImportService` (parsers `CsvStatementParser` and `XlsxStatementParser` behind one
    `StatementParser` interface, so MT940 and camt.053 can slot in later);
  - `BankMatchService`;
  - `BankStatementPostingService`, which posts `BNK` only through `PostingService`;
  - `BankAccountLedgerService`.
- All reads are `@Transactional(readOnly = true)`.
- Every write re-checks that the statement line, the bank account and the journal line share the caller's
  tenant: an explicit `tenant_id` equality on top of the Hibernate filter, because a journal line id arrives
  in the request body.
- Endpoints (`/api/v1/finance/bank-reconciliation`, SUPER_ADMIN, TENANT_ADMIN, ACCOUNTANT):

| Method | Path | Notes |
|---|---|---|
| GET/PUT | `/bank-accounts/{id}/ledgers` | the leaf set |
| GET/PUT | `/bank-accounts/{id}/profile` | column mapping |
| POST | `/bank-accounts/{id}/imports` (multipart) | returns counts and warnings, or `PROFILE_REQUIRED` with a preview grid |
| GET | `/bank-accounts/{id}/imports` | history |
| DELETE | `/imports/{id}` | refused once any line is in a CONFIRMED match or a finalized reconciliation |
| GET | `/bank-accounts/{id}/workspace?from&to&state=UNMATCHED\|SUGGESTED\|ALL` | statement lines and book items, with their matches |
| POST | `/bank-accounts/{id}/auto-match?from&to` | |
| POST | `/matches` | `{statementLineIds[], journalLineIds[], openingItemIds[]}` manual |
| POST | `/matches/{id}/confirm` | also `POST /matches/confirm?confidence=HIGH&from&to` |
| DELETE | `/matches/{id}?reverseCreated=false` | undo |
| POST | `/lines/actions/clear-cheques` | `{statementLineIds[], chequeIds[]}` |
| POST | `/lines/actions/receive` | `{statementLineId, chequeId, fromSuspense?}` |
| POST | `/lines/actions/bounce` | `{statementLineId, chequeId, reason}` |
| POST | `/lines/actions/present` | `{statementLineId, issuedChequeId}` |
| POST | `/lines/actions/post` | `{statementLineIds[], kind: CHARGE\|INTEREST\|SUSPENSE\|OTHER, vatIncluded?, accountId?, propertyId?, narration}` |

- `BankAccountController`: GET opens to ACCOUNTANT (B10), as part of PR 1. PUT on the leaf set is TENANT_ADMIN
  and ACCOUNTANT.

### Web UI

- **Finance → Bank reconciliation** (new), listing bank accounts, each with its leaves, last import, reconciled
  through, and unmatched count.
- **Import** button: drop the file. The first time, a mapping wizard: pick the sheet and header row, assign
  columns by dropdown, see a live preview of 20 parsed rows with dates and signs, then save. After that the
  result card shows new, duplicate and warning counts.
- **Workspace**, a two-pane matcher:
  - On the start side, statement lines (date, value date, description, reference, cheque no., amount).
  - On the end side, book items (date, doc number, narration, counter-account, amount).
  - Both panes have filters: unmatched only, amount, date, text.
  - Suggested pairs are tinted and linked by a shared badge, with Confirm and Reject on each.
  - Selecting rows on both sides shows the Σ footer with **Match**.
  - A statement line's overflow menu has the create-from-line actions. Each opens a small dialog pre-filled
    with the candidate documents and the posting preview.
  - The pane order follows the reading direction: statement lines are the start pane in both LTR and RTL.
  - Amounts stay LTR inside `<bdi>`, and dates are dd/MM/yyyy.
- **Rename:** the existing Finance → *Reconciliation* page (the PACT tie-out) becomes **Cut-over
  reconciliation**, and the sidebar moves it under Cut-over (B9).
- **Cheque register:** the Bank column above.

### Edge cases

- **A lump credit for cheques deposited on different days:** rule 3 does not fire. Match it manually.
- **A cheque cleared by hand on the wrong date** (30/09, while the bank credited it 01/10): it matches through
  rule 1 regardless of date, and §4 reports it as "booked before the bank".
- **A statement debit for a returned cheque the bank shows as credit then debit:** the credit matches the
  `CRT`, and the debit is booked through **Bounce**, which matches the `CBR`.
- **A cheque deposited and bounced without ever being credited** (DEPOSITED → BOUNCED): no bank line on either
  side, so nothing to match.
- **A transfer received for two renters in one credit:** use **Receive** twice (two rows), then match the line
  to both `CRT`s manually, or use the dialog's multi-select.
- **A bank fee on a returned cheque** is a separate debit, booked through **Bank charge**. The renter penalty
  stays the existing `PEN` proposal flow and is unrelated.
- **A leaf removed from the set after matches exist:** refused while any live match references a line on it.
- **Two tenants with the same bank file:** the hash includes `bank_account_id`, and the account belongs to one
  tenant, so the two cannot interfere.

### Tests

- **Parser golden files** in `src/test/resources/bank-statements/`, all synthetic and anonymised:
  - ENBD-style CSV (SPLIT, dd/MM/yyyy, newest-first);
  - an Emirates Islamic-style XLSX (date cells, a signed amount, opening and closing rows);
  - ADCB-style `DR`/`CR` flags with `dd-MMM-yyyy`;
  - a file with a balance break;
  - a file with two identical charges and no balance column.
- Unit tests: the amount parser (parentheses, trailing minus, AED, thousands); the date parser (Excel serial,
  text, two-digit year, ambiguous day-first); order detection; hash stability; the cheque-number extractor.
- ITs:
  - Import, re-import the same file (0 new), then import an overlapping file (only the new lines).
  - Auto-match rules 1–5 on §4's worked example, asserting each proposal and its confidence.
  - Ambiguous amounts produce no proposal.
  - Every create-from-line action posts exactly the documented journal and a CREATED match.
  - Undo with reverse.
  - Clear-cheques many:1.
  - Receive from suspense clears `BANK_SUSPENSE` to 0.
  - An unbalanced manual match is refused.
  - One journal line in two live matches is refused by the index.
- **Tenant isolation IT:** a statement line of tenant A cannot be matched to a journal line id of tenant B.
  Both the service's tenant check and the filter refuse it; the test asserts a 404 and that no row was written.
  The same applies to leaf-set assignment with B's account id.
- Web tests: the mapping wizard, the workspace Σ footer, RTL pane order, and the create-dialog previews.
- **Golden gate:** matching only reads the ledger. The new `BNK` posting path is not used by the fixtures. Run
  it.

---

## 4. Reconciliation statement, finalize, per-bank lock (B3, B5)

### Goal

For a bank account and a period, produce the reconciliation statement an auditor expects:

```
Balance per bank statement at <to>
+ deposits in transit (in the books, not yet on the statement)
− unpresented payments (in the books, not yet on the statement)
= adjusted bank balance
Balance per books at <to>                                  (Σ over the leaf set)
+/− items on the statement not yet in the books            (must be zero to finalize)
= adjusted book balance
Difference = adjusted bank − adjusted book                 (must be 0.00 to finalize)
```

Then **Finalize** records it and locks the bank account's ledger leaves through `<to>`.

### Data model (changeset `114-bank-reconciliations.yaml`)

| Change | Notes |
|---|---|
| `bank_reconciliations` (new) | `id, tenant_id, bank_account_id, period_from, period_to, statement_opening, statement_closing, book_balance, deposits_in_transit, unpresented_payments, unrecorded_items, difference, status (DRAFT \| FINALIZED \| REOPENED), finalized_by/at, reopened_by/at, reopen_reason, snapshot JSONB` (the item lists at finalize, for the PDF), `created_at`. Partial unique `(bank_account_id) WHERE status = 'DRAFT'`: one draft at a time. |
| `bank_rec_opening_items` (new) | `id, tenant_id, bank_account_id, item_date, description, reference, cheque_no, amount` (signed book-side: + a deposit in transit, − an unpresented payment), `created_by/at`. Items outstanding at the reconciliation start that were booked before the system, for example PACT-era unpresented cheques inside the OB bank balance. They are matchable like journal lines. |
| `bank_accounts.rec_start_date DATE NULL` | Set by the first reconciliation's `period_from`. It must be ≥ `books_start_date`. |
| `bank_accounts.reconciled_through DATE NULL` | The lock |

### Periods and figures

- **Periods are contiguous.** For the first reconciliation of a bank account, `period_from` is any date ≥
  `books_start_date`. After that, `period_from` = the previous finalized `period_to + 1`. The length is free: a
  month is typical, but any length is allowed.
- **Statement opening** is the previous reconciliation's `statement_closing`. For the first reconciliation it
  is the running balance before the first line on or after `period_from`, or it is typed in.
- **Statement closing** is the running balance of the last line with `txn_date ≤ period_to`. With no balance
  column, it is typed from the paper statement.
- **Continuity check:** `statement_opening + Σ amount of lines with txn_date ∈ [from, to] = statement_closing`.
  A mismatch means lines are missing and blocks finalize: "Statement lines add up to 326,017.50, the closing
  balance is 328,017.50: import the missing lines."
- **Book balance** B = Σ (debit − credit) over journal lines on the leaf set with `entry_date ≤ to`, all
  statuses, the same as the TB.
- **Outstanding book items** are journal lines (and opening items) with date ≤ `to` that are not in a CONFIRMED
  match with at least one statement line dated ≤ `to`:
  - Debits are **deposits in transit**. Examples: CRT cleared by hand but not on the statement, or cleared on a
    date before the bank's.
  - Credits are **unpresented payments**. Examples: BPV transfers and current-dated cheques not yet debited.
  - CONTRA-matched book pairs are excluded.
- **Unrecorded statement items** are statement lines with `txn_date ∈ [from, to]` in no CONFIRMED match. Lines
  matched only to book items dated after `to` are listed separately as "Booked after the period". They do not
  block, because the bank recorded them in the period and the books caught up later. They count on the bank
  side of the formula, as the mirror of deposits in transit.
- **Difference** = (S + deposits in transit − unpresented payments) − (B + unrecorded statement credits −
  unrecorded statement debits).

**Finalize preconditions**, all listed together:

1. The continuity check passes.
2. Unrecorded statement items = 0 lines, meaning every statement line in the period is matched. Bank-only items
   must be booked; that is the purpose of §3's actions.
3. Difference = 0.00.
4. No SUGGESTED match remains in the period. Confirm or reject each one.
5. The period's `to` ≤ today.
6. For the first reconciliation: Σ opening items + statement balance at `from − 1` = book balance at `from − 1`.
   In words, the opening items explain exactly the cut-over gap.

**Finalize** (TENANT_ADMIN or ACCOUNTANT) runs in one transaction:

1. `SELECT … FOR UPDATE` the `bank_accounts` row.
2. Recompute every figure, since a posting may have landed since the screen loaded.
3. Re-check the preconditions.
4. Store the figures and the `snapshot`.
5. Set `status = FINALIZED` and `reconciled_through = period_to`.

### The lock

`PostingService.post` and `PostingService.reverse` gain one call, `bankLock.assertOpen(accountIds, entryDate)`:

- It collects the account ids of the entry's lines (after role resolution).
- It reads `bank_account_ledgers` joined to `bank_accounts` for those ids, and locks the matched
  `bank_accounts` rows `FOR SHARE`. This is the same pattern VAT tax points use against the fiscal lock. As a
  result, a concurrent finalize, which takes `FOR UPDATE`, and a back-dated posting serialise: either the
  posting lands first and finalize recomputes with it, or finalize commits first and the posting is refused.
- It refuses when any line's bank account has `reconciled_through ≥ entryDate`:

  > "Emirates Islamic 0123 is reconciled through 30/09/2026. A posting dated 29/09/2026 on 'Emirates Islamic -
  > Marina Tower' would change a signed-off reconciliation. Reopen that reconciliation first."

- **No doc-type exemptions.** This includes OB and import batches. A reconciliation can only start on or after
  `books_start_date`, so the cut-over always precedes the first lock. After the lock, re-posting an opening
  balance or reversing an import that touches the bank *should* be refused, because it would change the
  reconciled opening.
- Reads are cheap: one indexed query per posting. The unique `(tenant_id, account_id)` index serves it.
- `BankLockService` lives in the ledger package and reads the repositories directly, the same dependency
  direction `TenantFiscalSettingsService` uses for VAT tax points. `PostingService` stays the only writer.

The early-refusal paths get friendly messages before any work is done:

- `ChequeService.clear`, `clearBatch`, `bounce` and `receive`;
- `VoucherService.post`;
- `PaymentRunService.post`;
- `IssuedChequeService.present`.

These all call the same `assertOpen` during validation.

**Matches inside a finalized period are frozen.** Undoing a match any of whose statement lines is dated ≤
`reconciled_through` is refused. Deleting an import with such lines is refused.

**Reopen** (TENANT_ADMIN only, with a reason, audited): only the latest FINALIZED reconciliation of the bank
account can be reopened.

- It sets `status = REOPENED` and `reconciled_through` = the previous finalized reconciliation's `period_to`, or
  null.
- Matches stay as they are.
- A new DRAFT for the same period can then be finalized again.

### Worked example: EI 0123, September 2026

The previous reconciliation (August) was finalized with statement closing 250,000.00 = book 250,000.00 and no
outstanding items. `reconciled_through` = 31/08/2026.

**Statement (imported CSV):**

| Line | Txn | Value | Description | Amount | Balance |
|---|---|---|---|---:|---:|
| a | 03/09 | 03/09 | CHQ DEP 000451 | +50,000.00 | 300,000.00 |
| b | 04/09 | 04/09 | CLG CREDIT 2 CHQS | +50,000.00 | 350,000.00 |
| c | 10/09 | 10/09 | TRF OUT PR-26/4 GULF AC TRF-7781 | −2,050.00 | 347,950.00 |
| d1 | 15/09 | 15/09 | SERVICE CHARGE | −50.00 | 347,900.00 |
| d2 | 15/09 | 15/09 | VAT ON SERVICE CHARGE | −2.50 | 347,897.50 |
| e | 28/09 | 28/09 | CHQ 000031 PRESENTED | −20,000.00 | 327,897.50 |
| f | 30/09 | 30/09 | CREDIT INTEREST | +120.00 | 328,017.50 |

**Books (leaves Marina + Palm) and matching:**

| Book item | Date | Amount | How it matched |
|---|---|---:|---|
| `CRT-26/101` cheque 000451 (P1) | 03/09 | +50,000.00 | rule 1 → line a (HIGH) |
| `CRT-26/102` cheque 118822 (P2) + `CRT-26/103` cheque 118823 (P2), deposited together 01/09 | 03/09 | +25,000.00 ×2 | rule 3 → line b (MEDIUM) |
| `BPV-26/55` payment run PR-26/4 | 10/09 | −2,050.00 | rule 2 (reference) → line c (HIGH) |
| `BNK-26/7` Dr Bank charges 50.00, Dr Input VAT 2.50 / Cr bank 52.50 | 15/09 | −52.50 | **Bank charge** on lines d1 + d2 (CREATED, many:1). `bank_trn` is set. |
| `BPC-26/3` PDC 000031 | 28/09 | −20,000.00 | **Present supplier cheque** on line e (CREATED) |
| `BNK-26/8` Dr bank / Cr Bank interest 120.00 | 30/09 | +120.00 | **Interest** on line f (CREATED) |
| `CRT-26/104` cheque 000452 (P1), cleared by hand 30/09; the bank credits it 01/10 | 30/09 | +15,000.00 | unmatched → **deposit in transit** |
| `BPV-26/60` current-dated cheque 000077 to Al Noor | 29/09 | −8,000.00 | unmatched → **unpresented payment** |

**Statement:**

| | AED |
|---|---:|
| Balance per statement 30/09/2026 | 328,017.50 |
| + Deposits in transit: CRT-26/104 | 15,000.00 |
| − Unpresented payments: BPV-26/60 chq 000077 | (8,000.00) |
| **Adjusted bank balance** | **335,017.50** |
| Balance per books 30/09/2026: 250,000 + 100,000 − 2,050 − 52.50 − 20,000 + 120 + 15,000 − 8,000 | 335,017.50 |
| ± Unrecorded statement items | 0.00 |
| **Adjusted book balance** | **335,017.50** |
| **Difference** | **0.00** |

The continuity check is 250,000.00 + 78,017.50 = 328,017.50 ✓.

**Finalize** sets `reconciled_through = 30/09/2026`. A later attempt to clear a cheque on 29/09 into
"Emirates Islamic - Palm Court" is refused. In October, `CRT-26/104` matches the 01/10 credit, and 000077 matches
when it is presented.

For comparison, without the PDC-payable treatment in §2, `BPV-26/50` would have credited the bank on 15/08. The
August reconciliation would then have carried 20,000 as an unpresented payment for six weeks, and the book
balance would have understated the funds actually available.

### Reconciliation report

- `GET /bank-accounts/{id}/reconciliations` lists the reconciliations with their status.
- `POST /bank-accounts/{id}/reconciliations {periodTo, statementClosing?}` creates a DRAFT.
- `GET /reconciliations/{id}` returns the live figures and item lists while DRAFT, and the snapshot once
  FINALIZED.
- `GET /reconciliations/{id}.pdf?lang=en|ar` and `.csv`.
- `POST /reconciliations/{id}/finalize` (TENANT_ADMIN, ACCOUNTANT).
- `POST /reconciliations/{id}/reopen {reason}` (TENANT_ADMIN).
- `GET/POST/PUT/DELETE /bank-accounts/{id}/opening-items`. Editable until the first reconciliation is
  finalized.

**PDF contents:**

- a header with the bank, IBAN (masked except the last 4 digits), leaf set and period;
- the formula table above;
- the item lists: deposits in transit, unpresented payments, and booked after the period, each with date,
  document, narration and amount;
- matched counts by method;
- "Prepared by <user> <time>" and, when finalized, "Finalized by … on …";
- bilingual, with RTL for Arabic, using the tax-invoice renderer's font setup.

### Web UI

- The workspace (§3) gains a **Reconciliation** footer bar for the selected period: statement closing, book
  balance, in transit, unpresented, unrecorded, difference.
  - Each figure opens its item list.
  - **Finalize** is enabled only when every precondition passes. When disabled, the button's tooltip lists the
    failing ones.
- The bank account list shows **Reconciled through 30/09/2026** and a **Reopen** action (TENANT_ADMIN).
- First-reconciliation setup: a step "Outstanding at <start date>", a grid of opening items with the live check
  "statement balance + items = book balance at <date>".
- Settings → Fiscal & period lock shows each bank account's reconciled-through date next to the tenant lock,
  for orientation.

### Edge cases

- **A reversal dated after the lock of an entry inside it** (for example, a September CRT bounced in October) is
  allowed. The lock checks the new entry's date. The October CBR becomes an October book item.
- **A back-dated voucher on a leaf in a locked set** is refused. The same voucher to a CASH leaf is not.
- **Cheque clearing into a leaf outside every set** is not affected by any bank lock. It is still checked by the
  fiscal lock.
- **The tenant fiscal lock is behind the bank lock:** both apply independently, and the stricter one wins for
  bank leaves.
- **Reopen, then re-finalize with changed items:** the new snapshot replaces the old one. The REOPENED row stays
  for audit.
- **A bank account deactivated:** its reconciliations and lock remain. Postings to its leaves are still locked
  through the date.
- **The YEC PR, when it lands:** `YEC` lines never touch bank leaves, because they close income and expense
  only, so the bank lock never blocks a year close.

### Tests

- ITs:
  - The worked example end to end (import, auto-match, the three create actions, finalize), asserting every
    figure and the snapshot.
  - Finalize refused for each precondition: continuity, an unmatched line, a difference, a SUGGESTED match
    left, first-reconciliation opening items.
  - After finalize: a back-dated `CRT`, `BPV`, `BNK`, JV and reversal are all refused with the message; the
    same posting dated after the lock succeeds.
  - A concurrent finalize and back-dated post, with two threads: either the post is refused, or finalize's
    recomputed figures include it.
  - Reopen restores postability. Only the latest reconciliation can be reopened.
  - Undoing a match inside the lock is refused.
  - The OB re-post refused after a lock.
  - Opening items explain a PACT-era unpresented cheque and match its later statement debit.
  - Tenant isolation: A's lock never refuses B's postings, even with identical leaf codes, and A's
    reconciliation lists never include B's lines.
- **Golden gate:** `PostingService` changes. The fixtures never set `reconciled_through`, so `assertOpen` is a
  no-op for them. Run it on the PR. A `PostingServiceIT` case asserts the no-set, no-query fast path: no bank
  set means no row lock taken.

---

## Changesets

| # | File | Feature |
|---|---|---|
| 109 | `109-pl-report-lines.yaml` | `accounts.report_line` + backfill from role mappings and `D-01` category prefixes |
| 110 | `110-ap-open-items.yaml` | vendor terms; voucher `supplier_invoice_date`, `due_date`, `payment_method`, `payment_reference`, `invoice_no_norm`, `duplicate_grandfathered` + backfills; `ux_vouchers_pisr_invoice`; `voucher_allocations`; `ap_opening_items` |
| 111 | `111-payment-runs.yaml` | `payment_runs`, `payment_run_items`, `vouchers.payment_run_id` |
| 112 | `112-pdc-payable.yaml` | `issued_cheques`; `B-02-001` leaf + `PDC_PAYABLE` mapping |
| 113 | `113-bank-statements.yaml` | `bank_account_ledgers` + backfill; `bank_accounts.bank_trn`; profiles, imports, lines, matches (+ children); `C-02-001`, `B-01-06` leaves; `BANK_CHARGES`, `BANK_INTEREST_INCOME`, `BANK_SUSPENSE` mappings |
| 114 | `114-bank-reconciliations.yaml` | `bank_reconciliations`, `bank_rec_opening_items`, `bank_accounts.rec_start_date`, `bank_accounts.reconciled_through` |

**Purge.** Every new table has `tenant_id` and is purged by `deleteTenant`'s pass loop. The FKs point from child
to parent (matches → lines → imports → bank account; allocations → vouchers), and there are no cycles, so the
multi-pass purge clears them. An IT deletes a tenant with every table populated.

**Tenant isolation.** Every new query goes through the Hibernate tenant filter inside a transaction. Every
endpoint that takes a foreign id in a body (journal line, cheque, voucher, account) re-checks the tenant
explicitly.

## Build order and PR split

Each PR runs the review loop, the full backend suite, the golden gate and the web tests before merge and deploy
(memory `feedback_pr_review_merge_deploy`). After each one, record the scenario walkthrough (memory
`feedback_phase_walkthrough_videos`).

| PR | Contents | Changesets | Depends on | Why this order |
|---|---|---|---|---|
| 1 | **Hotfix S1 + B10.** ACCOUNTANT on `VendorController` GET, POST and PUT (DELETE stays TENANT_ADMIN) and on `BankAccountController` GET; sidebar links for ACCOUNTANT; `VoucherForm` shows a banner when the vendor fetch fails instead of swallowing a 403; IT: ACCOUNTANT GET `/vendors` → 200. *Scoped separately; listed here for order only.* | none | none | P0: an accountant cannot enter a supplier bill today. Everything in §2 assumes it. |
| 2 | **§1 Property P&L + statement pack.** `report_line`; `PropertyPnlService`; P&L, statement, CSV and PDF endpoints; GL `effectiveProperty`; voucher line property-or-shared rule; Finance → Reports pages | 109 | none | Read-only and low-risk, and the most visible to landlords. Section 7 of the pack shows direct BPV lines until PR 3. |
| 3a | **§2 AP core.** Terms and due dates, TRN rule, duplicate guard, payment method and reference, allocations (form, hooks, API), opening items, aging, vendor open-items tab, SOA PDF; statement-pack section 7 in full | 110 | PR 1 | Allocation is the foundation for runs and for "paid" per property |
| 3b | **§2 Payment runs + PDC payable.** Runs wizard, preview, post, bank CSV; `issued_cheques`; BPV PDC branch; `BPC` present, cancel and unpresent; cut-over issued cheques | 111, 112 | PR 3a | PDC payable must exist **before** bank reconciliation, or recs for landlords who pay by PDC carry phantom unpresented items |
| 4 | **§3 Import + matching.** Leaf sets, profiles and wizard, CSV/XLSX parsers, de-duplication, auto and manual match, create-from-line actions (including present-cheque from PR 3b), receive-from-suspense, register Bank column, rename of the cut-over reconciliation page | 113 | PR 3b | Usable on its own as a matching tool. Nothing is locked yet, so the risk is low. |
| 5 | **§4 Reconciliation statement + finalize + lock.** Reconciliations, opening items, figures, PDF, finalize and reopen, `BankLockService` in `PostingService` and early checks | 114 | PR 4 | Touches `PostingService`, so it goes last and in isolation, with one cause for any golden-gate diff |

PR 4 is large enough to split into backend and web. The web half lands without a flag, because the endpoints
are additive.

Mobile: no changes. Finance stays behind `TenantFeature.MOBILE_FINANCE`.

**Walkthrough beats:**

1. PR 2: the Marina P&L for September against August, with a drill to the Utilities invoice and the Arabic PDF.
2. PR 3: Gulf AC's invoices, the run PR-26/4, aging before and after, and PDC 000031 issued.
3. PR 4: import the September CSV, re-import it (0 new), auto-match, clear, charge, interest and present.
4. PR 5: the reconciliation at 0.00, finalize, a back-dated clearing refused, and reopen.

## Out of scope

- MT940 and camt.053 import. The parser interface is ready for them.
- Direct bank feeds and APIs.
- Multi-currency bank accounts.
- WPS/SIF or bank-specific payment files. There is a plain CSV only.
- Approvals and maker-checker on payments (decision). S9 stays open.
- Supplier credit and debit notes as documents. Use a JV; aging shows the Δ.
- Maintenance ticket → vendor bill link (S10).
- The owner model: owners, shares, management fee, owner statement, payouts, owner portal (O1–O5). §1 leaves the
  seam described above.
- Posted allocation of shared costs. It is report-only here.
- Budget comparison on the P&L.
- Mobile.

## Open questions for the product owner / client accountant

Each question has a recommended default. The build proceeds on the default unless someone objects.

1. **When does a post-dated supplier cheque hit the bank?** **Default: when presented** (statement line or
   manual Present), which mirrors clearing on the receivable side. The alternative is a nightly job that posts
   `BPC` on the cheque date. That is simpler for accountants who do not reconcile, but it books money out of the
   bank on days it has not left.
2. **Should property managers keep the Clear / Receive / Bounce actions (B4)?** **Default: yes, unchanged.**
   The reconciliation now exposes an unevidenced clearing as a deposit in transit. Restricting the actions is a
   one-line change to `ChequeController.STAFF` if the client wants finance-only clearing.
3. **Input VAT on bank charges.** **Default: recoverable only when the bank's TRN is recorded on the bank
   account.** Without it, the whole debit goes to Bank charges. The accountant should confirm that the bank's
   monthly tax invoice is on file.
4. **Existing PISRs with no due date.** **Default: due on their posting date**, so they appear overdue in aging
   until they are paid or allocated. Moot after the planned prod data wipe (memory `project_prod_data_wipe`),
   but safe before it.
5. **Allocation basis for shared costs in the P&L.** **Default: off; when on, by recognised rent share.** Units
   and equal split are offered as well. The owner layer may later want a posted allocation per management
   agreement, which is a separate decision.
