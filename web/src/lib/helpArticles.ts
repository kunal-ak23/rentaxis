// web/src/lib/helpArticles.ts
// The help article registry: the only source of the articles the Help center renders.
// Each article's markdown is a template literal with a small frontmatter block (see helpLoader.ts).
// Links to /dashboard/... open in the app; /dashboard/help/videos?play=<id> opens a video tutorial.
import { registerArticle } from './helpLoader';

// ─── Getting Started ────────────────────────────────────────────────────────

registerArticle('getting-started--start-here', `---
title: Start here — set up a new organisation
description: The order to set up Miftah in, from the chart of accounts to the first bank reconciliation
category: getting-started
roles: [TENANT_ADMIN, ACCOUNTANT]
order: 0
---

## Start here

Setting up Miftah goes in this order. Each step needs the one before it, so work down the list. The videos show each step on screen.

> New to the accounting? Watch [How accounting works in Miftah](/dashboard/help/videos?play=38) first (2 minutes).

### 1. Your organisation

- **Settings › Organisation** holds the organisation name and the tenant portal address. Your System Admin creates the organisation and turns features (listings, meetings, gate passes) on or off.
- Add the people who will sign in under **Settings › Users & staff** — see [Managing users and staff](/dashboard/help/admin--managing-staff).

### 2. The account template (before any property)

1. Go to **Accounting › One-time setup › Property account template**.
2. Check, for each account role (rent income, rent receivable, bank, security deposits and so on), the name pattern and the parent group. Every new property gets its own set of accounts from this template.
3. Check **Accounting › One-time setup › Charge types** for the fees a contract can carry.

Video: [Chart of accounts, property account sets and charge types](/dashboard/help/videos?play=17)

### 3. When the books start

Pick one:

- **Starting fresh** — set the **Books start date** under **Accounting › Year End Closing**, then enter **Accounting › Accounts › Opening Balances** (balances as at the day before the books start).
- **Moving from another system** — use the cut-over workbook under **Accounting › One-time setup › Import Batches**. It creates the properties, units, tenants, contracts and cheques in one batch, and **Cut-over reconciliation** checks the result against the trial balance you upload.

Video: [Start your books: opening balances and cut-over](/dashboard/help/videos?play=39). More detail: [Starting your books](/dashboard/help/finance--books-start).

### 4. Properties, units and parking

Skip this if the cut-over import created them.

1. **Leasing › Properties & Units** › **More › Add Project** creates each building (a *project*), with its emirate, address and Makani number.
2. Open the project and add its **Buildings** (towers) and **Units**, each with its expected rent a year. **Add Property** on the list page adds one unit to a project.
3. Add parking bays on the project's **Parking** tab — attach a bay to a unit when it comes with the unit.
4. A whole portfolio can come in one Excel workbook: **More › Import Portfolio** (sheets Properties, Units, Tenants, Tenancy Contracts, and an optional Parking sheet).

Videos: [Create a project and property portfolio](/dashboard/help/videos?play=06) · [Import a property portfolio in bulk](/dashboard/help/videos?play=08)

### 5. Tenants

**Leasing › Tenants › Add Tenant**. A tenant saved with an email address gets an invite to the tenant portal.

Video: [Manage Tenants and portal access](/dashboard/help/videos?play=09)

### 6. Contracts and cheques

1. **Leasing › Tenancy Contracts › Draft Tenancy Contract** — the tenant, the unit, the dates, the rent and the number of cheques. The **Cheques** step builds the cheque rows.
2. **Post Contract** writes the contract's journal. Until a contract is posted it is a draft and nothing is on the books.

Videos: [Draft a tenancy contract and generate its cheques](/dashboard/help/videos?play=10) · [Post a tenancy contract](/dashboard/help/videos?play=34)

### 7. Every month

- **Cheque / Cash Collection** — deposit the cheques that are due and mark them cleared or returned.
- **Accounting › Receivables & Payables › Month-end recognition** — post the rent each contract has earned.
- **Accounting › Bank › Bank reconciliation** — match the bank statement to the books.

Videos: [Month-end recognition](/dashboard/help/videos?play=36) · [Bank reconciliation](/dashboard/help/videos?play=45). More detail: [Month-end in the books](/dashboard/help/finance--month-end).
`);

registerArticle('getting-started--welcome', `---
title: Welcome to Miftah
description: Get started with your property management portal
category: getting-started
roles: [SUPER_ADMIN, TENANT_ADMIN, PROPERTY_MANAGER, ACCOUNTANT, SECURITY_GUARD, TENANT_USER, RENTER]
order: 1
relatedTour: admin-onboarding
---

## Welcome to Miftah

Miftah is your all-in-one property management platform built for UAE landlords. Whether you manage a single building or an entire portfolio, Miftah helps you stay on top of your properties, contracts, and finances.

> **Setting up a new organisation?** Follow [Start here](/dashboard/help/getting-started--start-here) — the setup steps in order, each with its video.

### What You Can Do

Depending on your role, you'll have access to different features:

- **Company Admins** — Full control over properties, contracts, accounting, staff, and settings
- **Accountants** — The books: posting contracts, cheques, month-end recognition, VAT, bank reconciliation and reports
- **Property Managers** — Properties and units, the contract list and renewals, cheque collection, tickets, bookings and gate passes
- **Security Guards** — The gate desk: verify passes, see expected visitors and register walk-ins at the properties they are posted to
- **Company Users** — Limited access within the organisation
- **Tenants** — The tenant portal: your contract, your cheques and receipts, penalties, tickets and gate passes

### Quick Start

1. **Start on Home** — for staff, the **Contract pipeline** (draft to settlement), **Needs you now** (today's work), **Unit Status** with a floor-by-floor board of each property, one collections chart, and recent activity. A tenant's Home is the tenant portal; a security guard's is the gate desk.
2. **Check the Navigation** — Pick a section on the rail, then a page in the panel beside it
3. **Use Help Anytime** — Click the floating **?** button on any page for that page's article, its videos and its tour

### Need a Guided Tour?

Click the **?** button in the bottom corner and select "Take a Tour" to walk through the portal step-by-step. The **Video tutorials** tab of the Help center has a short video for most workflows.
`);

registerArticle('getting-started--roles-and-permissions', `---
title: Roles & Permissions
description: Understanding the different user roles in Miftah
category: getting-started
roles: [SUPER_ADMIN, TENANT_ADMIN, PROPERTY_MANAGER, ACCOUNTANT, SECURITY_GUARD, TENANT_USER, RENTER]
order: 2
---

## Roles & Permissions

Miftah uses role-based access control to ensure each user sees only what they need.

### Role Hierarchy

| Role | Access Level |
|------|-------------|
| **System Admin** | Full system access across all organisations. Manages organisations and users. |
| **Company Admin** | Full access within their organisation: properties, contracts, accounting, staff and settings. |
| **Accountant** | The books: accounting, posting, cheques, recognition, VAT and reports. Cannot create properties or draft contracts. |
| **Property Manager** | Properties and units, contracts (view), renewals, cheque collection, tickets, bookings and gate passes. |
| **Security Guard** | The gate desk at the properties they are posted to. Signs in with a mobile number and an SMS code. |
| **Company User** | Limited access within the organisation. |
| **Tenant** | Self-service tenant portal. |

### What Each Role Can Do

**Company Admin** has access to:
- Create and manage projects, buildings, units and parking
- Draft, post and manage tenancy contracts and tenants
- The whole Accounting area, from the chart of accounts to year-end closing
- Users, staff and organisation settings

**Accountant** has access to:
- The Accounting area: chart of accounts, opening balances and cut-over, journal vouchers, ledgers, payables, bank, VAT and reports
- Posting contracts, approving penalties, terminating and settling contracts
- The cheque register and the collection queues

**Property Manager** has access to:
- View properties and units, and manage amenities and parking
- View contracts; renew, transfer and give notice; work the renewal season
- Register and track cheques in Cheque / Cash Collection, and propose penalties
- Resolve maintenance tickets, approve bookings, schedule meetings and manage gate passes

**Security Guard** has access to:
- The **Gate desk**: verify a pass by QR or numeric code, see visitors expected today, register walk-in visitors and admit them once the tenant approves
- **Gate pass approvals** for recurring passes at their properties

**Tenant** has access to:
- Their tenancy contracts (accept, download), cheques, receipts and tax invoices
- Penalties, maintenance tickets, meetings, renewals and gate passes
`);

registerArticle('getting-started--first-property-setup', `---
title: First Property Setup
description: Step-by-step guide to adding your first property
category: getting-started
roles: [TENANT_ADMIN]
order: 3
relatedTour: property-workflow
---

## Setting Up Your First Property

Follow these steps to get your first property up and running in Miftah. Set up the **Property account template** first (see [Start here](/dashboard/help/getting-started--start-here)) — each new property's accounts are created from it.

Miftah uses the PACT names: a **project** is a building or development, and each rentable unit in it is a **property** (shown as a unit).

### Step 1: Add a Project

1. Go to **Leasing › Properties & Units**
2. Click **More › Add Project**
3. Fill in the project details:
   - Name in English and Arabic
   - Emirate and property type (Residential, Commercial or Mixed use)
   - Address and Makani number
4. Click **Create**

### Step 2: Add Buildings (Optional)

If the project has more than one tower:
1. Open the project you just created
2. Go to the **Buildings** tab
3. Click **Add Building** and enter its name (English and Arabic) and its number of floors

### Step 3: Add Units

1. On the project's **Units** tab, click **Add Unit** (or use **Add Property** on the list page and pick the project)
2. Enter the unit details:
   - Unit number
   - Type (Studio, 1 BHK, 2 BHK, 3 BHK, Penthouse, Retail, Office)
   - Size in square feet
   - **Expected rent (AED a year)** — the asking rent for a full year
   - The building, if the project has more than one
3. Repeat for all units, or import them from a CSV file

### Step 4: Add a Tenant

1. Go to **Leasing › Tenants**
2. Click **Add Tenant**
3. Enter the tenant's name (English and Arabic), email, phone, and Emirates ID. With an email address, the tenant is invited to the tenant portal.

### Step 5: Draft a Tenancy Contract

1. Go to **Leasing › Tenancy Contracts** and click **Draft Tenancy Contract**
2. Select the unit and the tenant
3. Set the contract dates, the rent and the number of cheques
4. The contract is saved as a **Draft** — review it, then **Post Contract** when the cheques are in

### Importing Instead

To bring in many projects at once, use **More › Import Portfolio**: one Excel workbook with Properties, Units, Tenants and Tenancy Contracts sheets, and an optional Parking sheet.

Your property is now set up and ready to manage!
`);

// ─── Properties ─────────────────────────────────────────────────────────────

registerArticle('properties--managing-properties', `---
title: Managing Properties
description: How to add, edit, and organize your properties
category: properties
roles: [TENANT_ADMIN, PROPERTY_MANAGER, ACCOUNTANT]
order: 1
relatedTour: property-workflow
---

## Managing Properties

**Leasing › Properties & Units** is your central hub for your real estate portfolio. In Miftah a **project** is a building or development; each rentable unit in it is a **property**. Company Admins add and edit projects; Property Managers and Accountants see them read-only.

### The List

The page shows your projects as cards or a table with:
- Name, type and location
- The number of units, occupied and vacant
- Expected and actual rent, and revenue
- Quick actions (open, manage)

### Adding a Project (Company Admins)

1. Click **More › Add Project** (**Add Property** adds a unit to an existing project; **Import Property** and **Import Portfolio** are also under **More**)
2. Fill in the fields:
   - **Name** — in English and Arabic
   - **Emirate** and **Type** — Residential, Commercial or Mixed use
   - **Address** and **Makani number**
   - **Fixed expenses**, if any
3. Click **Create**

There is no unit count to type: occupancy is worked out from the units you add.

### Project Detail View

Click any project to open it. The tabs are:
- **Overview** — Key counts, contacts and the property managers assigned
- **Buildings** — The towers, with their floors
- **Units** — Every unit with its status, and **Add Unit**
- **Contracts** — The tenancy contracts on this project
- **Amenities** — Bookable facilities such as a gym or pool
- **Parking** — Parking bays, bookable or attached to a unit (see [Parking](/dashboard/help/properties--parking))
- **Accounts** — The ledger account each role posts to for this property

Amenities and Parking show for roles that manage facilities; Accounts shows for roles that manage the account setup.

### Tips
- Keep project names clear and consistent (e.g., "Marina Tower" not "Property 1")
- Set up the property account template before adding the first project, so its accounts are created right
`);

registerArticle('properties--units-and-buildings', `---
title: Units & Buildings
description: Managing units and building structures within properties
category: properties
roles: [TENANT_ADMIN, PROPERTY_MANAGER]
order: 2
---

## Units & Buildings

### Units

Units are the individual rentable spaces within a project (apartments, offices, shops, etc.).

**Unit Information:**
- **Unit Number** — Unique within the project
- **Building** — The tower it is in, when the project has more than one
- **Type** — Studio, 1 BHK, 2 BHK, 3 BHK, Penthouse, Retail or Office
- **Size** — In square feet
- **Expected rent** — The asking rent **a year**, in AED. When a contract works out below it, the contract form says by how much (it never blocks).
- **Status** — Vacant, Occupied or Maintenance

**Adding Units:**
1. Open the project
2. Go to the **Units** tab
3. Click **Add Unit** and fill in the details, or import a CSV file

### Unit Status

| Status | Meaning |
|--------|---------|
| **Vacant** | No contract covers the unit today |
| **Occupied** | A posted contract covers the unit today |
| **Reserved** | A contract is posted but has not started yet — the badge shows the date and the tenant |
| **Maintenance** | The unit is under maintenance |

**Home › Unit Status** shows the same statuses on a floor-by-floor board of each property.

### Buildings

Buildings (towers) organise the units of a project that has more than one structure.

**Adding Buildings:**
1. Open the project
2. Go to the **Buildings** tab
3. Click **Add Building** and enter its name and number of floors
4. Pick the building when you add each unit

### Occupancy Tracking

- A unit is **Occupied** while a posted contract covers it
- It returns to **Vacant** when the contract is terminated or ends
- Occupancy rate is occupied units out of all units
`);

registerArticle('properties--parking', `---
title: Parking
description: Parking bays — attached to a unit, or booked by date range — and the Parking sheet in the import template
category: properties
roles: [TENANT_ADMIN, PROPERTY_MANAGER]
order: 3
---

## Parking

Each project's **Parking** tab lists its parking bays. A bay is one of two kinds:

| Kind | How it works |
|------|-------------|
| **Attached to a unit** | The bay comes with the unit. It is allocated to that unit's current tenant and cannot be booked. The tenant sees it under **Allocated parking** — no booking needed. |
| **Not attached (bookable)** | Tenants of the project request it for a date range; you approve the request under **Operations › Bookings**. |

### Adding bays

1. Open the project and go to the **Parking** tab
2. **Add Spot** for one bay, or **Bulk Add Spots** for many (up to 500 numbers at a time)
3. For a bay that comes with a unit, choose the unit under **Attached unit**; leave it **Not attached (bookable)** otherwise

To change a bay's attachment later, edit the spot. A bay you no longer use can be deactivated.

### Bookings

A tenant books a bookable bay from **Facilities & Parking** in the tenant portal, for the dates they need. Two approved bookings of the same bay cannot overlap. The request waits as **Pending** in **Operations › Bookings** until a Property Manager or Company Admin approves or rejects it. A tenant can give a bay up early with **Release Spot**.

### The Parking sheet

The portfolio import workbook (**Leasing › Properties & Units › More › Import Portfolio**) and the cut-over workbook have an optional **Parking** sheet. Each row adds or updates a bay; re-importing a bay updates it in place. Fill **AttachedUnitNumber** to attach the bay to a unit, or leave it blank for a bookable bay. The import result counts the bays created and updated.

The parking video was recorded before bays could be attached to units.
`);

// ─── Leases ─────────────────────────────────────────────────────────────────

registerArticle('leases--tenants-and-portal-access', `---
title: Tenants & Portal Access
description: Adding tenants and giving them a tenant portal login
category: leases
roles: [TENANT_ADMIN, PROPERTY_MANAGER, ACCOUNTANT]
order: 1
---

## Tenants & Portal Access

**Leasing › Tenants** lists everyone who rents from you. Open a tenant to see their contracts, cheques, tickets and ledger in one place.

### Adding a tenant

1. Go to **Leasing › Tenants** and click **Add Tenant** (Company Admins)
2. Enter the name in English and Arabic, the email, phone, Emirates ID and preferred language
3. If a tenant with the same details already exists, Miftah warns you — check it is not the same person before creating another record

Tenants can also come in through the portfolio or cut-over import.

### Portal access

Saving a tenant **with an email address** invites them to the tenant portal: they get an email to choose their own password. What you see after saving tells you what happened:

- **Invite sent** — a new portal account was created
- **Linked** — the email already has a Miftah login, so the tenant signs in with their existing password and chooses your organisation; no invite is sent
- **Saved without a portal account** — no email was given, or the email belongs to a deactivated account

An invite that expired can be sent again with **Resend invite**; **Send password reset** emails a link to choose a new password. One person has one Miftah login across every organisation they belong to.
`);

registerArticle('leases--creating-a-lease', `---
title: Drafting and Posting a Tenancy Contract
description: Step-by-step guide to drafting a tenancy contract and posting it
category: leases
roles: [TENANT_ADMIN, ACCOUNTANT]
order: 2
relatedTour: lease-workflow
---

## Drafting and Posting a Tenancy Contract

Tenancy Contracts are the core of Miftah — they link a tenant to a unit with payment terms. Company Admins draft contracts; Company Admins and Accountants post them.

### Drafting a Tenancy Contract

1. Go to **Leasing › Tenancy Contracts**
2. Click **Draft Tenancy Contract** (Home's **New Contract** opens the same form)
3. Fill in the contract details:
   - **Unit** — Select from your vacant units
   - **Tenant** — Choose the tenant (add a new one under **Leasing › Tenants** first)
   - **Tenancy Contract Dates** — Start date and end date
   - **Rent Amount** — the rent for the whole term in AED; if it works out below the unit's asking rent a year, the form says by how much (it never blocks)
   - **VAT and charges** — VAT where the contract charges it, and any fees the contract carries
   - **Security Deposit** — If applicable
   - **Number of cheques** — how many instalments the rent is paid in

### Cheques

The **Cheques** step generates one row per instalment from the contract duration, the rent and the number of cheques. Edit any row, then **Save cheques**.

To fill the cheque numbers, banks and dates from the paper cheques, click **Scan cheques** and choose photos of them (one photo may hold several cheques). Each cheque is read, matched to its row by date and amount, and shown for you to check before it is attached with its image.

### Posting the Tenancy Contract

1. Open the draft — the **Draft & to sign** pill on the contract list, or **Contracts to post** in the side panel, lists drafts and contracts awaiting the tenant's signature
2. Review the contract details and the cheques (the **Cheques** tab)
3. Click **Post Contract**; a dry run checks it first, then its journal is written
4. The unit shows as **Occupied** from the start date (or **Reserved** until then)
`);

registerArticle('leases--lease-lifecycle', `---
title: Tenancy Contract Lifecycle
description: Understanding contract statuses and transitions
category: leases
roles: [TENANT_ADMIN, PROPERTY_MANAGER, ACCOUNTANT]
order: 3
---

## Tenancy Contract Lifecycle

Every contract in Miftah follows a defined lifecycle.

### Status Flow

DRAFT → PENDING_SIGNATURE → ACTIVE → NOTICE_GIVEN → TERMINATED / EXPIRED / RENEWED → CLOSED

| Status | Meaning |
|--------|---------|
| **Draft** | Being prepared. Can still be edited freely; nothing is on the books. |
| **Pending Signature** | Sent to the tenant to review and accept. |
| **Active** | Posted and live. The unit is occupied and the cheques are expected. |
| **Notice Given** | Notice to end the contract has been recorded. |
| **Terminated** | Ended before its natural end date. |
| **Expired** | Reached its end date without a renewal. An expired contract still occupying the unit shows as *holding over* in the renewal season. |
| **Renewed** | Its renewal has been posted: the next contract takes over the unit (and the deposit). |
| **Closed** | Settled: all financial obligations are closed. |

### Key Actions

The buttons at the top of a contract show the next step for its status: **Edit** and **Post Contract** for a draft; **Record payment** and **Renew** for an active contract (or one under notice); **Settlement** once it has ended. Everything else is under **More actions**: Extend Contract, Amend lines, Add charge, Transfer, Assignment, Reduce, Raise penalty, Give notice, Terminate, Write off, Download contract, Ledger, and Delete for a draft. Which actions you see depends on your role.

- **Post Contract** — Checks the contract (a dry run), then writes its journal; this is how a draft becomes active
- **Renew** — Drafts the next contract, starting the day after this one ends; see [Renewal season](/dashboard/help/leases--renewal-season)
- **Transfer** — Moves the tenant to another unit; see [Unit transfer](/dashboard/help/leases--unit-transfer)
- **Give notice** — Records that notice has been given, with a notice date
- **Terminate** — Prices the move-out on its own page, then ends the contract
- **Settlement** — Settles the deposit and remaining balances once the contract has ended

### The Contract Page

A contract has four tabs:

- **General** — the contract and tenant details, the Particulars and any rent-free periods
- **Cheques** — the cheque grid, then Penalties, Journal Vouchers, Revenue recognition and, when the contract charges VAT, Vat — each a section you can open or close
- **Attachments** — the contract document, supporting documents, and Addenda & Ejari
- **Activities** — Notes and Maintenance

A link to one of the old tabs (for example "?tab=journals") opens the tab that holds it now, with that section open.
`);

registerArticle('leases--payment-schedules', `---
title: Cheques & Collection
description: How a contract's cheques are built, and how to deposit, clear and replace them
category: leases
roles: [TENANT_ADMIN, PROPERTY_MANAGER, ACCOUNTANT]
order: 4
---

## Cheques & Collection

Rent is collected by cheque (cash and bank transfer rows work the same way). Each contract has its own cheque grid.

### How the cheques are built

When you draft a contract you choose the **number of cheques**. Miftah splits the rent over that many instalments — at most one a month — and builds one row per instalment with its date and amount. Edit any row before posting.

The cheques are how the rent is **paid**. How it is **earned** is separate: the rent is earned day by day (the rent for the term divided by its days), and month-end recognition posts what each contract has earned. A cheque's amount does not change what a month earns.

### Cheque statuses

| Status | Meaning |
|--------|---------|
| **Draft** | A row on a contract that is not posted yet |
| **Registered** | The cheque is in hand (or the cash/transfer is expected) and on the books |
| **Online Pending** | An online payment was started and has not come back yet |
| **Deposited** | The cheque has been deposited at the bank |
| **Cleared** | The money has arrived |
| **Bounced** | The cheque was returned unpaid |
| **Replaced** | A bounced cheque was replaced by a new one |
| **Returned** | The cheque was handed back to the tenant |
| **Transferred** | The cheque moved to a new contract in a unit transfer |
| **Cancelled** | The row was cancelled and its entry reversed |

A cheque past its date and not yet cleared is shown as **Overdue**.

### Scanning cheques

- On a contract's **Cheques** tab, **Scan cheques** (draft) or **Bulk upload cheques** (posted) reads a set of cheque photos onto the rows
- Each registered cheque has its own **Attach scan**, for one cheque at a time
- **Cheque / Cash Collection › Scan cheques** does the same for any contract — pick the contract, then the photos
- With the **Payee name check** on (**Settings › Organisation**), a scanned cheque is checked against the payee names you accept

### Working the queues

1. Go to **Cheque / Cash Collection** on the left rail
2. Pick a status pill — **To deposit**, **Due**, **Overdue**, **Returned / replace**, **Post-dated** or **Penalties** — or open the **Cheque register** for every cheque; each pill shows how many are waiting
3. Narrow any view to one property, or search a cheque number, tenant or unit (the search opens the register)
4. When a cheque bounces, the same dialog lets you replace it and charge the bounce fee
`);

registerArticle('leases--renewal-season', `---
title: Renewal Season
description: Work the month's expiring contracts from one queue — renew, extend, mark not renewing, or bulk terminate
category: leases
roles: [TENANT_ADMIN, PROPERTY_MANAGER, ACCOUNTANT]
order: 5
---

## Renewal Season

**Leasing › Renewal season** (also **Renewal season** on the contract list) lists the tenancy contracts ending in a month, so the whole month can be worked from one queue.

### The queue

Choose the month under **Expiring in**, then narrow it by property, floor, or a search on unit, tenant or Ejari. The stages are:

| Stage | Meaning |
|-------|---------|
| **To do** | No renewal yet |
| **Renewal drafted** | A renewal (or a transfer) is drafted, not yet posted |
| **Not renewing** | Marked not renewing |
| **Renewed** | The renewal is posted |

A contract that has ended with no renewal shows as **Expired — holding over**.

Each row offers **Renew**, **Extend**, **Mark not renewing** and **Contact** (the tenant's phone).

### Mark not renewing

A note for the renewal season only: it gives no notice and changes nothing on the contract or the unit. **Undo not renewing**, **Renew** or **Extend** removes it. When the tenant serves notice, use **Give notice** on the contract as well.

### Bulk renew

1. Select the contracts (**Select all** takes every renewable one)
2. Click **Bulk renew**
3. Choose the rent change — **No change**, **+ percent** or **+ amount (AED)** — and the term (the same length as now, or a number of months). The new rent is rounded to whole dirhams; discounts and rent-free periods do not renew.
4. Each contract is renewed on its own, exactly as Renew on the contract does: from the day after it ends. Tick **Generate the cheque schedule** to build the cheque rows with the same number of instalments as now.

The renewals are **drafts**; nothing is posted.

### Cheque numbers for the drafts

When the tenants hand over their new cheques, **Enter cheque numbers** fills them for many drafts at once: type each number and date, or import a CSV. Each draft is saved on its own and amounts cannot change here. Then post the drafts from **Tenancy Contracts › Draft** with Post selected.

### Bulk terminate

The **Bulk terminate** tab ends many contracts at once, with the cheques each one returns and keeps. Only Company Admins and Accountants can confirm terminations.
`);

registerArticle('leases--unit-transfer', `---
title: Unit Transfer
description: Moving a tenant to another unit without ending their tenancy
category: leases
roles: [TENANT_ADMIN, PROPERTY_MANAGER, ACCOUNTANT]
order: 6
---

## Unit Transfer

When a tenant moves to another of your units mid-term, transfer the contract instead of terminating it.

### How it works

The current contract ends on the move date with no exit fee; the new one starts the next day. The deposit, the balance and the cheques you carry move across. Nothing is posted until you post the new contract.

### Steps

1. Open the posted contract and choose **More actions › Transfer to another unit**
2. **The move** — the last night in the current unit, the new (vacant) unit, when the new term ends, and the rent for the new term
3. **Cheques** — for each uncleared cheque choose **Carry** (it moves to the new contract), **Keep** (it stays with the current one) or **Return** (it goes back to the tenant)
4. Check the summary: what was earned on the current unit, what was paid ahead or owed and carried, the deposit carried, and any **Gap to collect**
5. **Create draft**, add cheques for any gap on the new contract's grid, then post the new contract (Company Admins and Accountants)

Each contract links to the other: the old one shows **Transferred to** the new unit, the new one **Transfer from the previous unit**. Carried cheques show as **Transferred** on the old contract.
`);

// ─── Finance ────────────────────────────────────────────────────────────────

registerArticle('finance--chart-of-accounts', `---
title: Chart of Accounts
description: Understanding and managing your accounting structure
category: finance
roles: [TENANT_ADMIN, ACCOUNTANT]
order: 1
relatedTour: finance-overview
---

## Chart of Accounts

The Chart of Accounts is the foundation of your financial tracking in Miftah.

### Account Types

Miftah uses standard double-entry accounting with five account types:

| Type | Purpose | Example |
|------|---------|---------|
| **Asset** | What you own | Bank Account, Rent Receivable |
| **Liability** | What you owe | Security Deposits Held |
| **Equity** | Owner's investment | Owner's Capital |
| **Income** | Money earned | Rental Income, Late Fees |
| **Expense** | Money spent | Maintenance, Insurance |

### Managing Accounts

1. Go to **Accounting › Accounts › Chart of Accounts**
2. View all accounts organized by type
3. Click **Add Account** to create a new account
4. Each account has a code, name, type, and optional description

### How the Tree is Organised

Accounts form a tree: each account names its parent, group accounts hold children, and only the leaves are posted to. A leaf inherits its parent group's type, and it cannot be given a different one.

- **Group accounts** (for example *Current Assets*, *Bank*, *Security Deposits*) are headings. You cannot post to them.
- **Leaf accounts** are where entries land. Most of them are created per property, so *Bank* holds one leaf per building rather than one shared account.
- An **alias** is an optional short name you can search and pick an account by, without changing its code or name.
- An account cannot be deleted once it carries journal lines or is mapped to a role.

### Account Roles and Mappings

Roles tell Miftah which account to post to when it raises an entry — rental income, rent receivable, bank, security deposits, and so on. You never pick accounts entry by entry; you map the roles once.

- **Accounting › One-time setup › Property account template** sets, for each role, the name pattern and the parent group under which each property's leaf is created.
- A property's **Accounts** tab shows the leaf resolved for each role on that property, and lets you re-map one to a different account or generate the ones that are missing.
- A role with no property-level mapping falls back to the organisation-wide default account for that role.

### Journals, Not Edits

Posted entries are immutable. A mistake is corrected by reversing the entry from **Accounting › Journal Entries › Journal Voucher**, which posts a mirror entry — the original stays on the books and is marked reversed.
`);

registerArticle('finance--books-start', `---
title: Starting Your Books
description: The books start date, opening balances, and the cut-over import from another system
category: finance
roles: [TENANT_ADMIN, ACCOUNTANT]
order: 2
---

## Starting Your Books

Before anything is posted, decide the day your books start in Miftah.

### The books start date

**Accounting › Year End Closing** holds the **Books start date** and how far back the books are locked (**Books locked through**). Nothing dated before the books start can be posted. The rail shows **Books locked through** a date once a lock is set.

### Opening balances

**Accounting › Accounts › Opening Balances** takes the balances as at the day before your books start.

- **Post opening balances** writes one opening-balance journal. Any difference posts to *Opening Balance Difference* so the books open balanced.
- To correct them, edit the figures and **Replace opening balances** — the old journal is reversed and a corrected one posted in one step.
- **Reverse opening balances** takes them off the books; the figures are kept so you can post again.
- Supplier invoices still open at cut-over go in **Accounting › Receivables & Payables › AP opening items**, so payments can be allocated to them.

### Cut-over from another system

**Accounting › One-time setup › Import Batches** takes the cut-over workbook (**Download cut-over template** first):

- One workbook creates the properties, units, tenants, contracts, cheques and, optionally, parking bays. Every property in it must be new.
- If the workbook does not validate, **nothing** is saved; correct the rows listed and upload again.
- Each import is one batch. Reversing a batch reverses every journal it wrote, newest first, and returns its contracts to draft.
- **Accounting › One-time setup › Cut-over reconciliation** compares the balances as at the cut-over date with the trial balance you upload from the old system.
`);

registerArticle('finance--month-end', `---
title: Month-end in the Books
description: Recognition, VAT, bank reconciliation, the period lock and year-end closing
category: finance
roles: [TENANT_ADMIN, ACCOUNTANT]
order: 3
---

## Month-end in the Books

### 1. Month-end recognition

**Accounting › Receivables & Payables › Month-end recognition** posts the rent every contract has earned up to a date. Rent is earned per day: the rent for the term divided by its days, so a month earns its days at that rate. Nothing before the date is skipped, and nothing after it is invented. A contract's **Revenue recognition** section shows its own schedule.

### 2. VAT

Each instalment's VAT is declared on its tax point — its due date, or the day it was received if earlier — and issues a numbered tax invoice. The same run happens every night; run it by hand before closing a period. **Accounting › Final Reports › VAT return** shows the quarter in the FTA VAT 201 layout; each box opens its documents.

### 3. Bank reconciliation

**Accounting › Bank › Bank reconciliation** — import each bank account's statement and match its lines to the books. Finalising a reconciliation locks that bank account's postings up to its date; a Company Admin can reopen the latest one, with a reason.

### 4. Lock the period

**Accounting › Year End Closing** sets **Lock through**: nothing dated up to that day can be posted afterwards. Closing a year moves its income and expense into Retained Earnings (one line per property) and locks it; re-opening reverses that entry.
`);

registerArticle('finance--payables', `---
title: Suppliers, Payments & Bank Accounts
description: Vendors, purchase invoices, payment runs, issued cheques and bank accounts
category: finance
roles: [TENANT_ADMIN, ACCOUNTANT]
order: 4
---

## Suppliers, Payments & Bank Accounts

### Vendors and bank accounts

- **Accounting › Receivables & Payables › Vendors** — suppliers, contractors and service providers. Input VAT on a supplier's invoice needs the supplier's TRN, so add it to the vendor.
- **Accounting › Bank › Bank Accounts** — the bank accounts linked to your properties.

### Vouchers

**Accounting › Receipts & Payments › Receipt / Payment Vouchers** records supplier (purchase) invoices, payments and receipts. **Accounting › Journal Entries › Credit Note** raises a credit note.

### Paying suppliers

- **Payment runs** — pay due supplier invoices in one run: one payment voucher per vendor, posted all or nothing.
- **Issued cheques** — post-dated cheques written to suppliers. They are held in PDC payable until the bank pays them.
- **Payables aging** — what is owed to suppliers, by days past due, as of any date, tied to each vendor's ledger.
`);

registerArticle('finance--ledgers-and-reports', `---
title: Journals, Ledgers & Reports
description: Where every posting can be read, and the final reports
category: finance
roles: [TENANT_ADMIN, ACCOUNTANT, PROPERTY_MANAGER]
order: 5
---

## Journals, Ledgers & Reports

### Journals and registers (Company Admins and Accountants)

- **Accounting › Journal Entries › Journal Voucher** — every entry posted to the books, manual and automatic. Post a manual journal here, or reverse one.
- **Accounting › Registers › General Ledger** — every posted entry, grouped by account.
- **Accounting › Registers › Tenant Ledger** — one tenant's postings across every account they touch.
- **Accounting › Registers › Trial Balance** — account balances as of a date. Debits must equal credits.

### Final reports

| Report | Shows | Who |
|--------|-------|-----|
| **Property Profit Report** | Income, expenses and NOI per property for a period | Company Admins, Accountants, Property Managers |
| **Owner Statement** | Rent billed, collected and outstanding, deposits, expenses and VAT for one property | Company Admins, Accountants, Property Managers |
| **Balance sheet** | Assets, liabilities and equity as at a date, with the A = L + E check | Company Admins, Accountants, Property Managers |
| **Profit & Loss** | Income, expenses and the net result for the whole company | Company Admins, Accountants |
| **VAT return** | The quarter in the FTA VAT 201 layout | Company Admins, Accountants |

Property Managers also see **Payables aging**. Report figures open the journal lines behind them.
`);

// ─── Operations ─────────────────────────────────────────────────────────────

registerArticle('operations--tickets', `---
title: Maintenance Tickets
description: Assigning, resolving and closing tenants' maintenance tickets
category: operations
roles: [TENANT_ADMIN, PROPERTY_MANAGER, ACCOUNTANT, TENANT_USER]
order: 1
---

## Maintenance Tickets

**Operations › Tickets** holds every maintenance ticket — raised by a tenant in the portal, or by your team. Company Admins and Property Managers assign and resolve tickets; a maintenance team member with a Company User login works the tickets assigned to them.

### Ticket statuses

| Status | Meaning |
|--------|---------|
| **Open** | Raised, not yet assigned |
| **Assigned** | Given to a member of staff |
| **In Progress** | Being worked on |
| **Resolved** | Fixed; waiting for the tenant to confirm |
| **Closed** | Done |
| **Reopened** | Opened again after it was resolved |

### Working a ticket

1. Open the ticket and **Assign to Me**, or **Assign To...** someone else. Only staff with a login are listed — a staff record without one can be given a login on the Staff page (see [Managing users and staff](/dashboard/help/admin--managing-staff)).
2. Work the ticket and keep the tenant up to date in its thread
3. **Mark Resolved** when it is fixed

### Closing with a code

When closure codes are on for your organisation, resolving a ticket sends the tenant a 6-digit code. The tenant shares it with you, and you **Close with OTP**. Code lost? **Send a new closure code**. After too many wrong codes the code closure locks, and a Company Admin can close the ticket instead; a ticket with no tenant to confirm can be closed without a code. Every closure is recorded in the ticket's history.

**Tickets › Reports** shows the tickets by category and status.
`);

registerArticle('operations--gate-passes', `---
title: Gate Passes & the Gate Desk
description: Visitor passes, approvals, the guard's gate desk and the access policy
category: operations
roles: [TENANT_ADMIN, PROPERTY_MANAGER, SECURITY_GUARD, RENTER]
order: 2
---

## Gate Passes & the Gate Desk

### For security guards

You sign in with your mobile number and an SMS code. Your Home is the **Gate desk**, for the properties you are posted to (shown under **Posted at**). It has three tabs:

- **Verify a pass** — scan the visitor's QR code or type the numeric gate code, then record the entry or the exit
- **Expected today** — the visitors with a pass for today
- **Walk-in visitors** — register a visitor without a pass. If the property's policy asks the tenant to approve, the visitor waits until the tenant of the unit approves; then use **Admit**. Take a photo first when the policy requires one.

**Gate pass approvals** lists recurring passes (a maid, a driver) waiting for a decision. Check the guest, unit, visit window, vehicle and purpose before you **Approve**.

### For Company Admins and Property Managers

Gate passes live under **More** on the rail:

- **Gate Passes** — the report of passes and scans
- **Gate pass approvals** — the same approvals queue the guards see
- **Gate policy & visitors** — the **Access policy** for walk-ins at each property (a tower can have its own): whether the tenant approves new visitors, or registered visitors too; whether the tenant is told when a registered visitor is let in; whether a photo is required; and how long an approval may take. **Guard postings** (Company Admins) choose the properties each guard works at — a guard sees only those properties' visitors.

### For tenants

**Gate passes** in the tenant portal: **Request pass** for a visitor, see **My passes** and their gate codes, and answer **Visitors at the gate** waiting for your approval.
`);

registerArticle('operations--bookings-meetings-promotions', `---
title: Bookings, Meetings, Listings & Promotions
description: Amenity and parking requests, meetings with tenants, marketplace listings and partner offers
category: operations
roles: [TENANT_ADMIN, PROPERTY_MANAGER]
order: 3
---

## Bookings, Meetings, Listings & Promotions

Some of these pages appear only when your organisation has the feature turned on.

### Bookings

**Operations › Bookings** — review amenity and parking requests from tenants and approve or reject each **Pending** one. The amenities themselves are set up on each project's **Amenities** tab; parking on its **Parking** tab (see [Parking](/dashboard/help/properties--parking)).

### Meetings

**More › Meetings** — schedule and manage meetings with tenants: office visits (offered on the half hour within the office hours under **Settings › Organisation**) and property visits. Tenants can request a meeting from the portal.

### Listings

**Leasing › Enquiry** — manage your property listings for the marketplace.

### Promotions

**More › Promotions** (Company Admins) — add a partner business, create a coupon ad shown to one building's tenants, and read its views and taps.
`);

// ─── Renter Portal ──────────────────────────────────────────────────────────

registerArticle('renter--renter-portal-overview', `---
title: Tenant Portal Overview
description: Your self-service portal for managing your tenancy
category: renter
roles: [RENTER]
order: 1
relatedTour: renter-portal
---

## Tenant Portal

The Tenant Portal is your self-service hub for everything related to your tenancy.

### What You Can Do

- **View Your Tenancy Contracts** — your contract details, accept a contract sent for your signature, and download it
- **Track Your Cheques** — what is due now, what is at the bank, and your payment history with receipts
- **See Penalties** — any penalty raised on your contract
- **Submit Tickets** — report maintenance issues
- **Renew** — when a contract is near its end, tell your landlord whether you want to renew, move out, or discuss
- **Book Facilities & Parking** — request an amenity or a parking bay; parking that comes with your unit is listed as allocated

### Navigation

Your portal menu shows:
- **Home** — your contracts and your next payment
- **My Tenancy Contracts** — your contract details and history
- **My Payments** — your cheques, receipts and tax invoices
- **My Penalties** — penalties raised on your contract
- **My Tickets** — maintenance requests and their status
- **Listings**, **Meetings** and **Gate passes** — when your landlord offers them

### Getting Help

If you have questions about your contract or payments, submit a ticket through the portal or request a meeting, and your landlord's team will respond.
`);

registerArticle('renter--submitting-tickets', `---
title: Submitting Tickets
description: How to report maintenance issues and make requests
category: renter
roles: [RENTER]
order: 2
---

## Submitting Tickets

Use tickets to report maintenance issues or make requests to your landlord's team.

### Creating a Ticket

1. Go to **My Tickets**
2. Click **Create Ticket**
3. Fill in:
   - **Title** — a brief summary of the issue
   - **Category** — Plumbing, Electrical, HVAC, Appliance, Structural, Pest Control, Cleaning, Security or Other
   - **Priority** — Low, Medium, High or Urgent
   - **Description** — the details, with photos if they help
4. Submit the ticket

### Tracking Your Tickets

- See all your tickets with their current status
- Open a ticket to see its full conversation thread
- You get a notification when your ticket is updated

### Ticket Statuses

| Status | Meaning |
|--------|---------|
| **Open** | Submitted, waiting for the team |
| **Assigned** | Given to a member of the team |
| **In Progress** | The team is working on it |
| **Resolved** | Fixed — confirm it with your code |
| **Closed** | Complete |
| **Reopened** | Opened again after it was resolved |

### Closing a resolved ticket

When your landlord uses closure codes, a resolved ticket shows a 6-digit code. Share it with your property manager once you are happy the issue is fixed — they close the ticket with it.
`);

registerArticle('renter--making-payments', `---
title: Understanding Your Payments
description: Your cheques, what is due now, receipts and tax invoices
category: renter
roles: [RENTER]
order: 3
---

## Understanding Your Payments

**My Payments** shows your rent cheques. Rent is usually paid by cheque, handed to your landlord's team; each cheque's progress is shown here.

### Due now

The cheques you owe today, with the amount due and a running total. A cheque past its date shows how many days it is overdue; a penalty approved on your contract appears here too. **All caught up** means nothing is due right now.

### Payment History

- **At the bank** — the cheque has been deposited and is awaiting clearance. Nothing to pay.
- **Cleared** — the money has arrived. Download the **Receipt** for it.
- **Bounced** — the cheque was returned unpaid. Contact your landlord's team; a returned cheque is replaced, not paid here.

Where a cheque was scanned, **View scan** opens the image of it.

### Tax invoices

When your contract charges VAT, **My tax invoices** lists each tax invoice issued to you.

### Online payment

Online payment is available only where your landlord has turned it on for your property; then **Pay Now** appears on a cheque that is due. Otherwise, pay by cheque as agreed with your landlord.
`);

// ─── Administration ─────────────────────────────────────────────────────────

registerArticle('admin--managing-staff', `---
title: Managing Users and Staff
description: Logins with a role, staff records, and giving a staff member a login
category: admin
roles: [TENANT_ADMIN]
order: 1
---

## Managing Users and Staff

Miftah keeps two lists, both under **Settings › Users & staff**:

- **Users** are logins: people who sign in, each with a role
- **Staff** are HR records: name, designation, department, salary and assigned property, for salary tracking. A staff record is not a login.

### Adding a user (a login)

1. Go to **Settings › Users & staff** and click **New User**
2. Enter the name, email and phone, and choose the role: **Company Admin**, **Accountant**, **Property Manager**, **Security Guard** or **Company User**
3. **Assign Properties** where the role works per property
4. No password is set here: the user gets an email invite to choose their own. Security guards instead sign in with their mobile number and an SMS code, so enter their mobile number.

One person has one Miftah login across every organisation: if the email already has a login, it is linked and no invite is sent. **Send password reset** emails a link to choose a new password; **Deactivate** stops a login signing in to your organisation.

### Adding a staff record

1. Go to **Operations › Staff** (also under **Settings › Users & staff**)
2. Click **Add Staff**
3. Enter the name in English and Arabic, employee ID, designation, department, monthly salary, join date, Emirates ID or passport, the assigned property and the salary account

### Give login

A staff member can only be assigned tickets once they have a login. On their staff record, **Give login** invites them by email to set a password; they then appear under **Assign To** on tickets, with their designation. An existing login with the same email is linked instead.

### Keeping the lists current

- Mark staff who have left **Inactive**
- Deactivate the login of anyone who no longer needs access
`);

registerArticle('admin--tenant-settings', `---
title: Organisation Settings
description: Configuring your organisation settings
category: admin
roles: [TENANT_ADMIN]
order: 2
---

## Organisation Settings

Configure your organisation from **Settings** on the left rail — one page with **Organisation**, **Users & staff**, **Rent & fines** and **Payments** sections. Accounting setup lives under **Accounting › One-time setup**.

### Organisation

- The organisation name and the **Portal address** of your tenant portal
- **Payee name check** — check the payee name on scanned cheques against the names you accept (list every spelling, Arabic and English)
- **Office hours** — the times office-visit meetings are offered

### Users & staff

See [Managing users and staff](/dashboard/help/admin--managing-staff).

### Rent & fines

- The default grace period for new contracts (the days before a cheque counts as overdue)
- Cheque-failure fines and late-payment penalties

### Payments

Rent is collected by cheque. Online rent payment is optional and **off by default** for every property:
- **Online payments by property** turns it on or off for each property
- If you use it, enter your payment gateway's details (and test the connection) in the same section

### Property account template

Map your chart of accounts to Miftah's automatic postings:
1. Go to **Accounting › One-time setup › Property account template**
2. Set, for each account role, the name pattern and the parent group of each property's account
3. Each new property's accounts are created from it
`);

registerArticle('admin--super-admin-guide', `---
title: System Admin Guide
description: System administration and multi-tenant management
category: admin
roles: [SUPER_ADMIN]
order: 3
relatedTour: super-admin
---

## System Admin Guide

As a System Admin, you have full access across all organisations in Miftah.

### Managing Organisations

1. Go to **Settings › Administration › Organisations**
2. View all registered organisations
3. Create new organisations, and turn features such as listings, meetings and gate passes on or off
4. Turn on ticket closure codes where the organisation wants them
5. View an organisation's details and its users

### Managing Users

1. Go to **Settings › Administration › Users**
2. View all users across all organisations
3. Create, edit, or deactivate user accounts
4. Assign roles and organisation memberships

### Switching Organisation

Use the organisation switcher in the top header to:
- Switch between different organisations
- View the dashboard and data from any organisation's perspective
- Manage properties and settings for a specific organisation

A new organisation's Company Admin then follows [Start here](/dashboard/help/getting-started--start-here).
`);
