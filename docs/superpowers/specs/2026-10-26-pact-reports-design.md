# PACT report parity: design (2026-10-26)

The client's operations and accounts team runs seven reports and letters from PACT RevenU every week. They asked that Miftah's
versions be "as close as possible" to what they use today. This spec maps each one onto our data. It also fixes one shared
report engine so all seven look and behave the same, and it records the decisions we took on the client's behalf. The client
can overturn any of them; §9 lists the questions to send.

The client's source files are real data. They stay in `~/Downloads` and are never committed: no tenant, property or person
names, unit codes or amounts from them go into code, tests, fixtures, docs or commit messages. Tests use synthetic data
("Sample Tower", "Tenant A").

| # | Client file | What it is | Status today | PR |
|---|---|---|---|---|
| R1 | Tenant Ledger.xlsx | Receivable sub-ledger by account → tenant, with cheque columns | Single-tenant, all-accounts statement; no export | A |
| R4 | petty cash … General Ledger (LS).xlsx | GL of one account, one row per counter line, 14 columns | GL exists; multi-line vouchers collapse to one row; client-side CSV only | B |
| R3 | VAT Purchase Register.xlsx | One row per purchase-invoice line, with VAT and supplier bill references | Missing; data captured on PISR lines | B |
| R2 | Return/Replace Cheque Register | Every bounced cheque with amount recovered, settling documents and age | Worklist of open bounces only; no history, no export | C |
| R5 | Unit Details Report.xlsx | Rent roll by property: unit, type, tenant, fees, deposit, rent, dates, duration | Missing; no Ejari charge, no unit-type wording, no unit edit | D |
| R7 | RentalIndexResult.pdf | DLD rental-index check (Decree 43/2013 permitted increase) | Missing; escalation exists | E |
| R6 | RENEWAL NOTICE FORMAT.docx | Renewal notice letter (A4) | Missing; renewal drafts exist | E |
| — | Position.docx | An unrelated job description | Out of scope | — |

## 1. Shared report engine (PR A)

### 1.1 Backend: `core/service/report/grid`
A report service feeds rows once to a `ReportWriter`. The writer does all the arithmetic in BigDecimal and forwards events to a
`ReportSink`, so the screen, Excel, PDF and CSV cannot disagree. This extends the house rule that exports come from the same
DTO as the JSON.

- `ReportColumn(key, labelKey, Kind, widthChars, TotalRule, hidden)`.
  - `Kind`: `TEXT`, `ID_TEXT` (always a text cell, so leading zeros survive), `DATE`, `MONEY`, `BALANCE`, `INT`, `SERIAL`,
    `PERCENT`.
  - `TotalRule`: `NONE`, `SUM`, `CLOSING_BALANCE`, `COUNT`.
  - `DATE` columns carry a display pattern per report: `dd/MM/yyyy`, `dd-MM-yyyy` or `dd/MMM/yyyy`.
- `ReportMeta(reportKey, titleKey, orgName, filters[(labelKey, value)], from, to, asOf, generatedAt (Asia/Dubai), generatedBy, lang)`.
- `ReportWriter` events:
  - `begin(meta, columns)`
  - `openGroup(level, Band.ACCOUNT|PARTY|GROUP, label)`
  - `opening(balance)`: the brought-forward row. It is excluded from Debit/Credit sums and included in the balance.
  - `row(cells, RowStyle.PLAIN|HIGHLIGHT, refs)`
  - `closeGroup(subtotalLabel)`: may be called for nested levels.
  - `end()`
  The writer computes:
  - the running balance (`opening + Σdr − Σcr`, debit-positive) for a `BALANCE` column that names its debit and credit keys;
  - `SERIAL` counts, reset per group;
  - a Sub Total per column `TotalRule`;
  - the REPORT TOTAL: Σ of the SUM columns, and Σ of the group closing balances for BALANCE;
  - the row cap and the `truncated` flag.
  A service may hand in **authoritative totals** from a SQL aggregate (needed when JSON is paged). The writer asserts they equal
  its own sums whenever the full set was written.
- Sinks:
  - `JsonGridSink` builds `ReportGridDTO`: meta, columns, a page of items (`band`, `opening`, `row`, `subtotal`, `total`;
    amounts as decimal strings), `page{offset, limit, totalRows, truncated}`, and a continued-band plus carried-forward
    balance on page 2+.
  - `XlsxGridSink` writes a PACT-layout `SXSSFWorkbook` (§1.2).
  - `PdfGridSink` renders through an upgraded `ReportPdf` (§1.3).
  - `CsvGridSink` uses `ReportCsv.encode` with the BOM and formula guard, plain decimals, and the balance as a signed number
    plus a Dr/Cr column.
- `ReportFormat`:
  - `money`;
  - `drCr` → `12,345.00Dr`, `8,000.00Cr`, `0.00Dr` (no space, Latin digits, as PACT);
  - `day(pattern)`;
  - `duration(from, to)` → `1 Years `, `1 Years 1 Months `, `1 Years 8 Days` (PACT spelling, including the trailing space);
  - `cellText`: strips control and bidi characters and truncates at 32,767;
  - `sheetName`: 31 characters, without `[]:*?/\`.
- `ReportLabels`: `resources/messages/reports_{en,ar}.properties`, behind its own MessageSource bean, plus a key-parity test.
  Excel headers use PACT's exact English spelling ("Chq. Amount", "BDDay", "Tenant - Customer").
- `ReportFiles`:
  - `ResponseEntity` with an RFC 5987 `Content-Disposition` and the media type for xlsx, pdf or csv;
  - `ExportFormat` parsed from a `{format:xlsx|pdf|csv}` path variable;
  - the row caps.
- Caps: screen pages are ≤ 200 rows, Excel and CSV ≤ 50,000 rows, PDF ≤ 5,000 rows. Above a cap the response is a 422 with
  "too many rows for a PDF — export Excel or narrow the filter", or "narrow the filter" for Excel.
- `PropertyScope.requireSelection(Collection<UUID> requested)` → `Selection(unrestricted, ids, empty)`:
  - a named foreign property is a 404;
  - a PROPERTY_MANAGER with no assignment gets an empty result without running a query.
  The method name matches the enforcing regex of `PropertyScopeCoverageTest`.

### 1.2 Excel layout (PACT house style, one implementation)
- **Row 1** is a merged title band across every column. PACT leaves it blank; we fill it with the organisation name, the report
  title, the period or as-of date and the filters, and "Generated dd/MM/yyyy HH:mm by <user>". Data still starts at row 3.
- **Row 2** holds the headers: bold, centred, wrapped, thin borders. The default fill is navy `#000080` with white text. The
  Tenant Ledger profile uses white with black text, as PACT does. Row 2 has an autofilter, panes are frozen at A3, and row 2
  repeats as the print title in landscape, fit to one page wide.
- **Bands** are merged across all columns:
  - ACCOUNT: fill `#D2691E`, font `#F0F8FF`, bold 14;
  - PARTY: `#FFFF00`, 13 pt;
  - GROUP: `#7F7F7F`, bold.
  A HIGHLIGHT row has fill `#FAC08F`. This is used for vacant units.
- **Cells**:
  - Money is a numeric cell, `#,##0.00`.
  - Running balance is a **numeric** cell with format `#,##0.00"Dr";#,##0.00"Cr";0.00"Dr"`. It looks identical to PACT's text
    but stays summable.
  - Dates are real date cells in the report's PACT pattern.
  - Identifiers (cheque, unit, doc and bill numbers) are text.
  - Data rows use Calibri 9 with thin borders.
- **Totals**: `Sub Total` and `REPORT TOTAL` merge their label from column A to the column before the first totalled column.
  REPORT TOTAL is bold. A blank merged row closes the sheet.
- **Arabic** (`lang=ar`): the sheet is right-to-left with Arabic headers; digits stay Latin and Dr/Cr stays as written.
- **Safety**:
  - Never write a formula or hyperlink built from data.
  - Prefix data starting with `= + - @` with an apostrophe.
  - Compress SXSSF temp files, and `close()`/`dispose()` them in `finally`.
  - Build in the request thread. Never use `StreamingResponseBody` or `@Async` with DB work: the tenant filter and the PM
    scope live on the request thread.

### 1.3 PDF
`ReportPdf` gains:
- a `<thead>` that repeats on every page;
- a "Page x / y" footer;
- an organisation and title header;
- band rows with `colspan` (the same three styles), opening rows and highlight rows;
- landscape automatically above 9 columns.
Existing callers are unchanged.

### 1.4 Conventions every report follows
- **Transactions.** The report service is class-level `@Transactional(readOnly = true, timeout = 60)`. All name and DTO mapping
  happens inside the transaction (OSIV is off), and sinks only read immutable rows.
- **Native SQL** binds `tenant_id` on every table it reaches through a user-supplied id or a non-FK join. Names (property EN/AR,
  unit, renter, account EN/AR, user) are resolved by SQL joins; there are no web `/names` round trips.
- **Endpoints.** Each report has a JSON endpoint plus one `/<key>.{format}` file handler, with an explicit `@PreAuthorize` on
  every method. There is no generic `{key}` dispatcher: it would hide role checks from review and from the bytecode coverage
  test.
- **Organisation.** New endpoints are organisation-required (`OrganisationRequiredInterceptor`). They are not added to the
  super-admin platform allow-list.
- **Read-only.** Reports never call `PostingService`. A ledger report's REPORT TOTAL for an account equals the trial-balance
  figure as of `to`, and an IT asserts this.

### 1.5 Web report kit
- **`lib/reports/catalog.ts`** is the single list of reports. Each entry has a key, group, title and description keys, href,
  permission, formats and whether it is PM-scoped. It drives:
  - the **Reports hub** at `/dashboard/reports`, with role-filtered cards, a search box, one column at 390 px and 2–3 columns
    on desktop;
  - the Accounting nav items;
  - the help map.
- **`components/reports/ReportShell`** provides:
  - the title, period and scope note;
  - access denied;
  - a filter-bar slot with draft/applied state persisted in the URL;
  - an `ExportMenu` (Excel in PACT layout, PDF English, PDF العربية, CSV) that downloads via `fetch` + `saveResponse`,
    shows a spinner, and puts a server 422/429 message inline instead of opening a raw JSON tab;
  - skeleton, empty and error states, and a truncated banner.
- **`components/reports/GroupedReportTable`** renders `ReportGridDTO` items:
  - one `<table>` inside an `overflow-x-auto` container, so the page never scrolls sideways at 390 px;
  - a sticky header;
  - `tabular-nums` amounts in `<bdi dir="ltr">`;
  - drill links from row refs (journal, lease, cheque);
  - pagination with a continued band and the carried-forward balance.
  There is no client arithmetic.
- **`lib/reports/format.ts`**:
  - `fmtDrCr` (PACT form);
  - `fmtReportDate`, which gives dd/mm/yyyy in both locales and fixes the `ar-AE` locale output.
- **Tokens.** New `--color-report-band/-band-fg/-party/-group/-highlight` tokens, with dark-mode variants, replace the hard-coded
  `#C8651B`.
- **Gates.** A new `Reports` i18n namespace (EN/AR parity). Every new page passes routeRegistry, help-coverage (an article per
  role), rbacParity and i18n parity, and works at 390 px in LTR and RTL.

## 2. R1 Tenant Ledger (PR A)

**Shape (PACT).**
- Groups nest as receivable account band (account name) → `Tenant Name : <name>` band → brought-forward row → lines → tenant
  Sub Total. When an account has several tenants, an account Sub Total follows. The report ends with a REPORT TOTAL.
- Excel columns, in PACT order: Doc Date (`dd-MM-yyyy`) · Particular · Debit · Credit · Balance · Chq No · Payee Bank · BDDays ·
  ConvertedDate (`dd/MM/yyyy`). We append Doc No as column J.
- The ledger already produces PACT's rows, and they are golden-verified:
  - TCO lines Dr receivable / Cr each charge;
  - PDR per cheque Cr receivable / Dr PDC Receivable;
  - CBR re-debits on bounce.
  Particular = `journal_lines.contra_account_id` → account name (Arabic name under `ar`), falling back to the counter accounts.

**Rules.**
- Accounts default to every non-group RECEIVABLE-subtype leaf. A picker may add any other account, for example Security
  Deposit, so the same screen works as a party ledger.
- Grouping uses the line's own account, then `renter_id` ordered by name. Receivable lines with no renter (vouchers, manual
  JVs) go to a "No tenant" group, so the account sub-total ties to the GL.
- The running balance and the opening are per (account, renter), in `ledgerRows` order (`entry_date, created_at,
  entry_number, line_no`).
- Chq No is `cheques.cheque_number`, else `TT` for a transfer or `CASH`. Payee Bank is `cheques.payee_bank`, else
  `TRANSFER`/`CASH`.
- ConvertedDate is the cheque's maturity date, printed only on the cheque-registration (PDR) row, as PACT does.
- BDDays comes from the shared `ChequeReturnClass`:
  - 1 = the cheque bounced on or before `to` (`cbr_journal_id` set);
  - 2 = replaced before deposit (no such flow yet, so never produced today);
  - blank otherwise.
  It is printed on every row of that cheque and is **not** summed in Sub Totals.
- Reversed documents are shown by default, because that ties to the trial balance. "Hide reversed pairs" removes both legs of
  a reversal from the openings and the rows.
- Filters: period (12-month default), property, receivable account(s), tenant (optional once a property or account is chosen),
  contract, "only tenants with a balance" and "hide reversed". Nothing loads until a tenant, property or account is chosen.
- Access: SA, TA and ACCOUNTANT, as today (D6).

**Screen.** The existing Tenant Ledger page defaults to the new "Receivable (PACT)" view. The "All accounts" toggle keeps
today's single-renter statement, so the lease "Ledger" deep link and LeaseJournalsTab behave as before. Paging is by tenant
groups. The export menu covers Excel, PDF EN/AR and CSV.

**Tests.**
- A service IT on synthetic data:
  - one lease with deposit, advance rent and admin fee, 7 instalments including a transfer, and two bounces after clearing;
  - two tenants with a window that starts mid-history;
  - a renter-less JV ("No tenant");
  - a lease with a receivable override;
  - hide-reversed;
  - paging totals stable;
  - tie-out to `accountLedger` closing;
  - org-isolation with identical names;
  - foreign ids return 404 or empty.
- Golden extension: the report on the golden fixture renter reproduces the receivable rows and balances.
- An xlsx test: headers, merges, formats, Dr/Cr, RTL.
- Web vitest for the page and table.

## 3. R4 General Ledger, line-wise (PR B)

**Shape (PACT).**
- Band `Account Code :: <code>      Name :: <name>` → brought forward → lines → `Sub Total` →
  `Sub Total (Account Code :: … Name :: …)` → REPORT TOTAL.
- 14 columns: Doc Date (`dd/MMM/yyyy`) · Doc No · Particular · Debit · Credit · Balance · Che. No · M. Date · Unit · Tower ·
  AR Name · Narration · Remarks · Tenant - Customer.

**Rules.**
- **Line-wise explosion (read-only).** When a journal touches the account in one line and several counter lines (BPV, PISR, JV,
  OB, BNK), the report prints one row per counter line. The counter line gives the Particular, Unit, Tower, Remarks and Tenant;
  the account-side amount is split pro rata by counter amount, and the last row absorbs the rounding. Paired lines keep using
  `contra_account_id`. Inter-property clearing legs are never shown as a Particular. Posting is unchanged, so history is
  covered.
- Che. No / M. Date come from one resolver, in this order:
  1. `journal_lines.cheque_id` → cheque number and maturity date;
  2. the voucher's cheque number or payment reference;
  3. the issued supplier cheque;
  4. the mode label (`CASH`).
- AR Name = `journal_entries.posted_by` → the user's display name, or "System".
- Narration = the entry (header) narration. Remarks = the line narration.
- Tower = property name. Tenant - Customer = the line's renter.
- The inner Sub Total and the account Sub Total print the same figures until the client explains PACT's inner grouping (Q4).
- Server-side Excel, PDF and CSV replace the client-side CSV on the General Ledger page. The page gets the new columns, keeps its
  account picker and 12-month default, and can switch on a "line-wise" view.
- Access: SA, TA and ACCOUNTANT.

**Tests.**
- A multi-line petty-cash BPV across two properties gives one row per line.
- Pro-rata splitting with a rounding check.
- REPORT TOTAL ties to the TB.
- Che. No resolution.
- Golden stays green.

## 4. R3 VAT Purchase Register (PR B)

**Shape (PACT).** One row per purchase-invoice line. 16 columns: Doc No · Doc Date (`dd/MM/yyyy`) · Product Name ·
VATPostingType · Description · UnitPrice · Value · Taxable Amount · VAT · VAT Amount · Tower · Unit · Tenant · BillNo · BillDate
· Narration. We append Supplier and Supplier TRN. The report closes with a REPORT TOTAL.

**Rules.**
- Rows come from posted purchase invoices (PISR), plus supplier credit notes (PCN) as negative rows, so the register's VAT equals
  the Input VAT booked on purchases.
- "As posted" is the default: originals on their date and reversal mirrors as negative rows on the reversal date. This ties to
  the filed return. The "current documents only" switch hides reversed originals and their mirrors.
- Column sources:
  - Product Name = the line's expense head (report-line label, e.g. "Repairs & Maintenance"), else the account name.
  - VATPostingType = `Standard rated` when the rate > 0, else `Zero rated / out of scope`.
  - UnitPrice = Value = Taxable Amount = line net amount (quantity 1).
  - VAT = rate %.
  - Tower and Unit = the line's property and unit.
  - Tenant = the renter from a linked maintenance ticket, else blank.
  - BillNo and BillDate = the supplier bill number and date on the voucher.
  - Narration = the voucher narration.
- Blank cells stay blank; we do not print PACT's literal `None`. The REPORT TOTAL of the VAT % column is blank.
- Filters: doc-date range (current VAT quarter by default), property, supplier, VAT/non-VAT.
- Access: SA, TA and ACCOUNTANT.
- Deferred until the client asks (Q6): line-level bill number and date, quantity × unit price, and an item master.

**Tests.**
- A mixed-rate PISR, a PCN, and an amended (reversed) PISR in both views.
- Σ VAT Amount equals the Input VAT ledger movement from purchases for the period.
- Org isolation, including the vendor join.

## 5. R2 Return / Replace Cheque Register (PR C, phase A: derived, no ledger writes)

**Shape (PACT).** A flat list with one REPORT TOTAL. 14 columns: Chq Date (`dd/MM/yyyy`) · Doc No · Tenant Name · Tower · Unit ·
Chq No · Chq. Amount · Rcd. Amount · Cheque Bounce · Days · Adjusted DocNos · BDDay (hidden in Excel, as PACT) · Status ·
Remarks.

**Rules.**
- A row is every cheque returned on or before the as-of date (`cbr_journal_id` set, `bounced_at ≤ asOf`). Status is
  "Bounced", BDDay 1. A cheque keeps "Bounced" even when fully recovered, as PACT does.
- A replacement that bounced again is its own row.
- Rows settled by the previous owner before an acquisition are excluded (`settled_before_acquisition`).
- Doc No = the cheque's PDR entry number.
- **Rcd. Amount** = Σ of the live settling items dated on or before the as-of date, capped at face value:
  - replacement rows (`replaces_id = c.id`, not cancelled), using each one's PDR number, or RR receipt number for cash or
    transfer;
  - write-offs (BDW);
  - settlement absorption (STL).
  Adjusted DocNos lists those document numbers in date order. Cheque Bounce = Chq. Amount − Rcd.
- Days = as-of date − cheque date.
- Remarks = the cheque narration, for example "Rent - 2nd Installment".
- Sort by event date, then Doc No in natural order, then id. Grouping by property is optional.
- Totals come from one SQL aggregate: Σ Chq, Σ Rcd, Σ Bounce, the row count, and the none/partial/full counts.
- Access: STAFF, i.e. SA, TA, ACCOUNTANT and PROPERTY_MANAGER, with PM scoping in SQL as `ChequeQueryService` does.
- A new page under Accounting › Registers, with a "View register" link from the Returned/replace worklist.
- New migration: `idx_cheques_replaces` (on `replaces_id`, partial) and `idx_cheques_returned` (on `tenant_id, bounced_at`,
  partial).

**Deferred to phase B** (it writes to the ledger and needs client rulings, Q7–Q9):
- a `cheque_recoveries` table;
- recoveries in several stages and top-ups;
- a counter receipt or JV recorded against a specific cheque;
- a swap before deposit (BDDay 2 "Replaced");
- re-presenting a bounced cheque;
- PACT doc numbers for imported history.

## 6. R5 Unit Details Report (PR D)

**Shape (PACT).**
- Groups go property band (grey) → units → `Sub Total :: <PROPERTY>` → REPORT TOTAL. Vacant rows are highlighted `#FAC08F`.
- 16 columns: S.No (restarts per property) · UnitName · UnitType · TenantName (`-` if vacant) · MonthlyRent · AnnualRent ·
  NoOfUnits · Additional Parking fee · Admin Fee · Ejari · Security Deposit · Rent · TotalAmount · FromDate (`dd/MMM/yyyy`, or
  `Vacant`) · ToDate (or `vacant`) · RentDuration.

**Rules (as of a date, default today).**
- The covering contract is the posted lease whose term covers the as-of date, in any status after posting (including EXPIRED and
  CLOSED). A terminated lease covers up to its termination date. After an assignment, the renter is the one at the as-of date.
- Column sources:
  - UnitType = `units.type_label` when set (the client's own wording, e.g. "01 BHK", "1 B/R"), else the translated type.
  - MonthlyRent is blank, as PACT (Q10).
  - AnnualRent = `units.expected_rent`, the unit's asking rent, shown for vacant units too.
  - Rent = the contract rent amount.
- The fee columns come from the contract's charge lines, through a new `charge_types.report_column`. It takes one of RENT,
  SECURITY_DEPOSIT, PARKING_FEE, ADMIN_FEE, EJARI_FEE or null for other charges. It is backfilled as follows:
  - RENT behaviour → RENT;
  - SECURITY_DEPOSIT → SECURITY_DEPOSIT;
  - ADMIN_FEE and RENEWAL_FEE → ADMIN_FEE;
  - PARKING_FEE → PARKING_FEE;
  - codes like `%EJARI%` → EJARI_FEE.
  The column is editable on the Charge Types page.
- A new default charge type, `EJARI_FEE` "Ejari Fee" / "رسوم إيجاري": FEE, one-off, VAT off, admin-fee role. Existing
  organisations get it through an idempotent changeset.
- TotalAmount = Parking + Admin + Ejari + Deposit + Rent (PACT's five).
- RentDuration uses `ReportFormat.duration`.
- **Unit master:**
  - new columns `units.type_label` (free text, ≤ 40) and `units.dewa_premise_number` (digits, ≤ 20, used by R7);
  - a `PUT /api/v1/units/{id}` edit endpoint for SA and TA (type, label, size, asking rent, building, DEWA no.), which wires the
    unit page's dead "Edit details" button;
  - importers keep the original type wording, and a tolerant parser maps "01 BHK", "1 B/R" and "1 Bed" to BHK1.
- Access: SA, TA, ACCOUNTANT and PROPERTY_MANAGER, with PM scoping. A PDF needs a property filter (page budget).

**Tests.**
- An occupied unit, a vacant unit, an expired-but-covering lease, a terminated lease, an assignment, a 13-month term duration,
  a charge mapped to Ejari, and a property total.
- PM scoping.
- xlsx highlight and merges.

## 7. R7 DLD Rental Index check (PR E)

**Rule (Dubai Decree 43/2013).**
- average = (index min + index max) ÷ 2.
- gap = (average − current annual rent) ÷ average × 100.
- The permitted increase follows the gap:

  | gap | permitted increase |
  |---|---|
  | current rent ≥ average, or gap ≤ 10 | 0% |
  | gap ≤ 20 | 5% |
  | gap ≤ 30 | 10% |
  | gap ≤ 40 | 15% |
  | above 40 | 20% |

- increase = current × % (whole AED, half-up). rent after = current + increase.
- Worked example (synthetic figures; the client's printout was checked the same way and matched): 100,000 against an index
  of 112,000–140,000 gives an average of 126,000 and a gap of 20.63%, so 10% → +10,000 → 110,000.

**Data.** `rental_index_checks` is immutable; re-checking adds a row. It holds:
- lease, property and unit;
- checked on;
- index type (Residential - Unit), property category, building name, DEWA premise no.;
- contract end date and current annual rent;
- range min/max, average, gap, permitted %, increase and rent after;
- an optional uploaded DLD PDF (evidence);
- a note, entered by and created at.

A renewal draft can point at the check it used (`leases.rental_index_check_id`).

**Behaviour.**
- The Renew dialog and the renewal-preview offer "Use DLD maximum (+10% = AED 10,000)". This **advises only**, per the
  2026-09-24 ruling "no RERA cap" (Q11).
- The check prints as a one-page PDF in the DLD layout, EN and AR.
- The check is shown for Dubai properties (`emirate = Dubai`), and "Not applicable" elsewhere.
- Bug fixed on the way: `GET /leases/{id}/renewal-preview` now admits ACCOUNTANT, matching `POST /renew`.
- Deferred: a per-building index catalogue and bulk "renew at DLD maximum" (Q12).

## 8. R6 Renewal notice letter (PR E)

**Template.** It mirrors the client's Word letter:
- header with the letterhead (legal) name;
- date · `Mr/Mrs.` · tenant name · `Flat No: <unit>` · building;
- **Sub: Renewal of Tenancy Contract for the Year YYYY – YYYY** (underlined);
- "Dear Tenant," and the expiry sentence;
- a table: Sr. No. | Particulars | Amount (AED);
- "Please Note:" — instalments and payee;
- the late renewal fee sentence;
- the documents required;
- the closing, **Sincerely,** **Management**;
- the footer with company name, address and phone.

There is an Arabic version; the client approves its wording.

**Source.** The letter is generated from the **renewal draft** (the successor DRAFT lease): rent and fee lines, number of
instalments, start and end. This makes the letter equal the contract that will be signed (Q13). The table lists rent first,
then the fees, with their charge-type labels.

**Settings** (`org_settings`, TA/SA). Each falls back to the organisation record when blank.
- payee name (default: the first valid payee name);
- late renewal fee per day (blank omits the sentence);
- documents required EN/AR;
- signatory EN/AR (default "Management");
- letterhead title EN/AR;
- footer lines EN/AR;
- show logo.

**Issuing.**
- Single: from the contract's renewal panel.
- Bulk: from the renewal-season queue selection, as one merged PDF in batches of ≤ 50.
- Every issue is recorded in `renewal_notices` (lease, successor, date, language, amounts, snapshot, issued by). It also shows
  as a LETTER interaction on the contract timeline.
- When a DLD check is linked, an optional paragraph states the permitted increase.

## 9. Decisions taken for the client (defaults) and questions to send

Defaults we applied (D):
- **D1** Every report exports Excel (PACT layout), PDF (EN/AR) and CSV.
- **D2** Excel uses real numbers and dates with PACT's display formats; the balance looks like `12,345.00Dr`.
- **D3** Row 1 carries the title, filters and generated-by line.
- **D4** Dr/Cr in PACT form on the new report screens and exports, in both languages.
- **D5** "Tower" = our Property.
- **D6** Access:
  - finance registers (R1, R3, R4) stay SA, TA and ACCOUNTANT;
  - R2, R5 and R7 also admit PROPERTY_MANAGER, scoped to their properties;
  - R6 is admitted wherever renewals are.
- **D7** A Reports hub at `/dashboard/reports`.
- **D8** Caps: 200 rows per screen page, 50k rows per Excel/CSV, 5k rows per PDF.
- **D9** No export audit and no saved presets for now.

Questions for the client (Q):
1. BDDays on TCO rows (value 28 in the sample). What is it, and should Sub Totals sum BDDays?
2. Tenant Ledger: show Doc No in the Excel (appended), or drop it?
3. Reversed or amended contracts: show TCO + TCR + new TCO (ties to the TB), or hide reversed pairs by default?
4. GL: what does PACT's inner "Sub Total" group by when both Sub Total lines print?
5. Document series: are our prefixes acceptable (BPV/CRT/JV), or do new documents need PACT's PCV/CPO/CRV/JRN series?
6. VAT register: do they need a bill number and date per line (several supplier bills on one voucher), quantity × unit price,
   or an item master like "Service Item VAT" / "CHILLER CHARGES"?
7. Return/Replace: what exactly is "Replaced" (BDDay 2)? Is it a cheque swapped before deposit?
8. Return/Replace: do write-offs and settlement absorption count as "Rcd.", or should they go in a separate "Adjusted" column?
   May a bounced cheque be re-presented?
9. Should PACT's bounced-cheque history before go-live be imported, or does the register start at go-live?
10. Unit Details: keep MonthlyRent blank, or fill it with rent ÷ 12? Rent: as signed, or current after addenda? Deposit: charged
    on this contract, or held?
11. Rental index: advise only (current ruling), or block renewals above the DLD maximum unless a reason is given?
12. Rental index: is DLD looked up per unit, or one range per building and category per season?
13. Renewal notice: must a renewal draft exist before printing, or should a notice be printable from a proposed rule (e.g. +5%)
    with no draft? Which language? Who signs?
14. Are "Ejari & Admin Fees" one charge or two? Is Gas recurring?
