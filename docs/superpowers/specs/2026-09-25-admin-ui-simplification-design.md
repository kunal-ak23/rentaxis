# Admin UI simplification — design

**Date:** 2026-09-25 · **Status:** draft for review · **Scope:** web dashboard only (mobile out of scope)

## Intent

The client finds the web admin overwhelming. The target user is an **operations manager** who runs the
building day to day: leases, renewals, move-outs, cheques, tickets and bookings. Accounting is done by an
accountant (or rarely by the same person) and must stay fully available, one door away.

**Success means:** an ops manager sees ~9 sidebar entries instead of 46, lands on a home page that lists
today's work, and finds every lease action from a short header plus one "More actions" menu — **with no
feature removed, no permission changed, and no existing URL broken.**

What the user said: go with approach A; accounting behind one "Accounting" door; scope = sidebar + busiest
pages; don't break any functionality. Assumptions (correct me): role permissions unchanged; Arabic/RTL
treated identically; mobile apps untouched.

## Current state (inventory, 2026-09-25)

- TENANT_ADMIN sidebar: 46 flat links (Workspace 10, Finance 17, Reports 10, HR 1, Settings 6, + Dashboard,
  Help). Three groups share the heading "Operations"; payables/bank tasks sit under "Reports".
- 72 dashboard routes; 29 reachable only via links; `properties/[id]/units` has no inbound link;
  TENANT_USER "My Unit" → `/dashboard/my-unit` 404s; "Users" links to `/superadmin/users`; Gate Pass shows
  without its feature flag.
- Busiest pages: properties (26 buttons), leases list (23), chart of accounts (22), lease detail (up to 15
  header actions, 9 tabs, 3 action cards), ticket detail (18), property detail (15, 7 tabs).
- Overlaps: fines configured in 3 places; accounts setup in 3; cheques in 5+ places; Users vs Staff;
  "Reconciliation" (cut-over) vs "Bank Reconciliation"; cut-over tools in the daily nav.
Full inventory: session scratchpad `ui-inventory.md`.

## Design

### 1. Sidebar
Top-level entries (TENANT_ADMIN): **Home · Properties · Leases · Renters · Collections · Tickets · Bookings ·
Accounting · Settings**, plus a collapsed **More** group shown only when it has content: Listings
[LISTINGS], Meetings [MEETINGS], Gate pass [GATEPASS flag — now actually gated], Promotions.
Help stays in the header (not the sidebar). Every entry keeps its existing `rbac.ts` gate; the menu only
regroups — a role never gains a link it couldn't reach before. Resulting counts: PROPERTY_MANAGER ≈ 8,
ACCOUNTANT ≈ 4 (Home, Leases, Collections, Accounting), TENANT_USER: Home + a working "My unit" (or the
link removed if no page is intended — decide in plan; either way no 404).

### 1a. Navigation shell: icon rail + section panel (client suggestion, 2026-09-25 — supersedes the flat sidebar in §1)
Two levels, modelled on the client's reference admin:
- **Icon rail** (narrow, always visible): **Home · Leasing · Collection · Accounting · Operations · Settings**
  (+ "More" only when Listings/Meetings/Gate pass/Promotions are enabled). A badge shows actionable counts
  (e.g. cheques to deposit + overdue on Collection).
- **Section panel** (second column, per rail section) — org/company switcher at the top; the section's pages;
  a **Pinned / saved views** group (e.g. "Expiring in 60 days", "Drafts to post", "Overdue"); a small status card
  at the bottom (e.g. "Books locked through 31/12/2025" or "3 cheques to deposit").
  - Home: Today · Unit Status
  - Leasing: Tenancy Contracts · Tenants · Properties & Units · Enquiry · Unit Reservation
  - Collection: Cheque / Cash Collection · Security Deposit · Penalties
  - Accounting: the PACT Finance groups (Accounts · Receipts & Payments · Journal Entries · Registers ·
    Receivables & Payables · Bank · Final Reports · Year End Closing · One-time setup) — **this replaces the
    Accounting top tab bar**
  - Operations: Tickets · Bookings · Staff
  - Settings: Organisation · Users & staff · Rent & fines · Payments · Notifications
- Collapses to the rail only below ~1280px and to a drawer on phones; RTL mirrors (rail on the right).
- Page header: breadcrumb ("Leasing › Tenancy Contracts"), global search, one primary action.

**List pages use status pill tabs with counts** (e.g. Contracts: All · Draft · Active · Expiring · Notice ·
Ended; Collection: To deposit · Due · Overdue · Returned · Post-dated · Penalties), then a property filter and a
search box — replacing scattered filter controls. Counts come from data the page already loads (frontend only).

**Home gets a "Contract pipeline" strip** (Draft → Posted/upcoming → Active → Expiring ≤60d → Notice →
Settlement due) with each stage's count and oldest item, above "Needs you now" (the Today list) and the KPI cards.

### 2. Collections (new hub page `/dashboard/collections`)
Tabs: **To deposit · Due · Overdue · Returned / replace · Post-dated · Penalties**. Each tab renders the
existing page's content component (no logic rewritten). Old routes `/finance/cheques`, `…/collection`,
`…/return-replace`, `…/post-dated`, `/finance/penalties` redirect to the matching tab. Dashboard KPI links
("view overdue", "cheques to deposit") point at the tabs.

### 3. Accounting area (`/dashboard/finance/**`, unchanged URLs)
One sidebar entry opens a second-level menu (left sub-nav on desktop, dropdown on mobile widths):
- **Ledger:** Journals, General ledger, Tenant ledger, Trial balance, Chart of accounts
- **Receivables:** Recognition (cheques/penalties link to Collections)
- **Payables:** Vendors, Vouchers, Payment runs, Issued cheques, Aging, Opening items
- **Bank:** Bank accounts, Bank reconciliation
- **Reports:** Balance sheet, Company P&L, Property P&L, Owner statement, VAT return
- **Year-end:** Fiscal years / close
- **One-time setup:** Account template, Charge types, Opening balances, Import batches, Cut-over
  reconciliation (renamed from "Reconciliation" to remove the clash). Shown expanded until the org's books
  have a lock date past the cut-over (i.e. live), then collapsed.
Accounting-setup pages that live under `/settings/*` today (account-template, fiscal, charge-types) move
under this menu; their old URLs redirect.

### 4. Settings (one page with sections, `/dashboard/settings`)
Sections: **Organisation · Users & staff · Rent & fines · Payments · Notifications**.
- Rent & fines merges org fines (`settings/fines`) and per-property rent settings/late fees
  (`settings/rent-settings`) into one screen: org defaults + per-property overrides.
- Payments holds the gateway config and the online-payment switch together.
- Users & staff: a tenant-scoped users list (no `/superadmin` path for tenant admins) + the HR staff records.
Old `settings/*` URLs redirect to the matching section anchor.

### 5. Lease detail
- **Header: ≤ 3 primary buttons by status** — Draft: Edit · Post · (Delete in menu); Active/notice: Record
  payment · Renew; Ending/terminated: Settlement. All other actions in **More actions ▾**: Extend, Amend
  lines, Add charge, Transfer, Assign, Reduce, Raise penalty, Give notice, Terminate, Write off,
  Download contract, Ledger. Menu items keep their current gates and confirm dialogs.
- **Tabs 9 → 4:** **Overview** (contract, renter, lines, rent-free) · **Payments** (cheque grid, penalties,
  links to the lease's journals, recognition, VAT schedule/tax invoices) · **Documents** (contract +
  generate, attachments, addenda, Ejari) · **Activity** (interactions, events, maintenance).
  Journals/Recognition/VAT tabs become sections inside Payments (collapsed), not removed.
- Assignment and bad-debt cards open from the menu as dialogs/drawers instead of always-visible cards.
- Deep links `?tab=journals|recognition|vat|penalties|contract|maintenance|documents|interactions` map to
  the new tab + section.

### 6. Home
Four KPI cards stay. Widgets replaced by one **Today** list: cheques to deposit, overdue, leases ending ≤ 60
days, drafts to post, open tickets, approvals waiting (penalties, write-offs). One chart (collections vs
expected); occupancy shown on its KPI card. "New lease" stays the primary action.

### 7. List pages (properties, leases, renters)
Row actions collapse into one ⋯ menu per row; the list keeps its primary create button; rarely-used filters
move behind a "Filters" button (active filters shown as chips). Table/card toggles stay.

## Terminology — match PACT RevenU (client's current app), labels only

The client's users know PACT RevenU. Screen labels (EN; AR uses the matching Arabic terms already in the
glossary, e.g. عقد الإيجار for tenancy contract) follow PACT where a PACT term exists. **Only visible
labels change** — route paths, API fields, code identifiers and i18n key names stay as they are (keys get new
values, or new keys where a label splits). In code "tenant" means the organisation; in the UI the
organisation is always shown as "Organisation"/"Company" so "Tenant" can mean the renter without clashing.

| Today (UI label) | PACT term to use |
|---|---|
| Renters / Renter | **Tenants / Tenant** |
| Leases / Lease | **Tenancy Contracts / Tenancy Contract** (short "Contracts" in the sidebar) |
| New lease | **New Contract** |
| Extend | **Extend Contract** · Renew stays **Renew** · Terminate stays **Terminate** |
| Collections (hub) | **Cheque / Cash Collection** |
| Security deposit | **Security Deposit** |
| Listing enquiries | **Enquiry** |
| Reserved unit (posted future lease) | **Unit Reservation** / "Reserved" |
| Lease lines grid | **Particulars** — columns Particulars · Credit A/c · Rent Amount · Discount Amount · After Discount Amount · Narration |
| Cheque grid | columns Posting Date · Cheque No · Date · Payee Bank · Debit A/c · Amount · Narration |
| Tenant ledger | **Tenant Ledger** (button on the contract: "Ledger") |
| Lease detail tabs Overview / Payments / Documents / Activity | **General · Cheques · Attachments · Activities** (VAT shown as a **Vat** section inside Cheques, Notes inside Activities) |
| Receipt (CRT) | **Receipt Voucher** |
| Payment voucher (BPV) | **Payment Voucher** |
| Purchase invoice (PISR) | **Purchase Invoice** |
| Journal | **Journal Voucher** · credit/debit notes **Credit Note / Debit Note** |
| Property P&L | **Property Profit Report** |
| Year-end close | **Year End Closing** |
| Home dashboard | sections **Unit Status** (Occupied / Expiring / Vacant) and **Today** |

**Accounting area groups follow the PACT Finance ribbon:**
**Accounts** (Chart of Accounts, Opening Balance) · **Receipts & Payments** (Receipt/Payment Vouchers,
Payment Runs, Issued Cheques) · **Journal Entries** (Journal Voucher, Credit Note, Debit Note) ·
**Registers** (General Ledger, Tenant Ledger, Trial Balance, Cheque registers) · **Receivables & Payables**
(Aging, Opening Items, Vendors, Recognition) · **Bank** (Bank Accounts, Bank Reconciliation) ·
**Final Reports** (Balance Sheet, Profit & Loss, Property Profit Report, Owner Statement, VAT Return) ·
**Year End Closing** · **One-time setup**.

**Ledger report layout follows PACT (frontend-only, computed from data the existing endpoints return):**
General Ledger and Tenant Ledger render as one table grouped by account (a coloured band "Account Code ::
<code> Name :: <name>"; Tenant Ledger adds a "Tenant Name : <name>" band), rows with columns **Doc Date ·
Doc No · Particular · Debit · Credit · Balance · Unit · Tower (property) · Tenant**, running balance shown with a
**Dr/Cr** suffix, a **Sub Total** row per account and a **REPORT TOTAL** row; Chart of Accounts keeps its tree
view with **Account Code · Account Name · Account Type** columns. If an existing endpoint does not return a field
needed for a column (e.g. tenant name on GL lines), that column is omitted — no backend change.

**Ledger reports load on demand (client feedback 2026-09-25, scale to 1000s of buildings):** General Ledger
opens with an **account picker** (searchable by code/name, multi-select up to 20, optional property/tower filter)
and loads nothing until an account is chosen; Tenant Ledger opens with a **tenant picker**. Default period is the
**last 12 months ending today** (editable). Each account section starts with the **balance brought forward** at the
period start so the running Dr/Cr balance is right. Uses the existing `/finance/ledger?accountIds=&from=&to=&propertyId=`
and `/finance/ledger/renter/{id}` parameters — frontend only. The picker/period are kept in the URL so a view can
be bookmarked. For a whole-company view the user goes to Trial Balance.

PACT reports we don't have (Book Case Income, Daily Vacant Flat Report, Floor Wise Expiry) are **not** added —
they would need backend work, which is out of scope.

## No-regression safeguards

1. **Route map + redirects.** A single `routeMap.ts` lists every current dashboard route and its new home;
   moved routes get permanent redirects (Next.js `redirects` / middleware), preserving query strings and
   `?tab=` deep links. Help-guide links and emails keep working.
2. **Action coverage test.** A checked-in list of every action in today's inventory (lease header, cards,
   tabs, cheque grid, list-row actions, settings) mapped to its new location; a vitest test renders each
   location and fails if any listed action is missing.
3. **RBAC parity test.** For each role, the set of reachable routes (nav + in-page links) before == after;
   `rbac.ts` unchanged.
4. **Browser sweep.** Playwright run over every dashboard route in EN and AR as TENANT_ADMIN,
   PROPERTY_MANAGER and ACCOUNTANT (using existing seeded test users locally), asserting 200, no console
   errors, no horizontal overflow at 1366 and 390 px.
5. **Existing gates stay green:** vitest, tsc, EN/AR key parity, backend suite (unaffected), golden.
6. Help centre, guided tour and walkthrough scripts updated to the new names/paths.
7. **Production check** after each PR on the test org, walking the ops-manager day: create lease → post →
   deposit → overdue → renew → settle; and the accountant path through Accounting.

## Delivery (3 PRs, each reviewed, merged, deployed and checked on prod)

1. **Sidebar + route map/redirects + Settings page** (+ fix My Unit 404, orphan units page, Gate pass flag,
   Users path).
2. **Collections hub + Accounting area** (sub-nav, setup group, renamed cut-over reconciliation).
3. **Lease detail + Home "Today" + list pages.**

## Out of scope
Mobile (Flutter) apps; **any backend change** (frontend only — the client's instruction; the Users & staff
section uses existing endpoints only, and if a tenant-scoped users list doesn't exist it keeps linking to the
existing users page); new features or reports; visual rebrand.

## Decisions on the former open points (2026-09-25, approved with the spec)
- TENANT_USER "My unit": the link is removed (no page exists; nothing else points at it).
- Accounting sub-nav: ~~a top tab bar~~ superseded by §1a — the Accounting pages live in the section panel.
