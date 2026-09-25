// web/src/lib/helpArticles.ts
// Auto-generated article registry. Each article's markdown content is stored as a template literal.
import { registerArticle } from './helpLoader';

// ─── Getting Started ────────────────────────────────────────────────────────

registerArticle('getting-started--welcome', `---
title: Welcome to RentAxis
description: Get started with your property management portal
category: getting-started
roles: [SUPER_ADMIN, TENANT_ADMIN, PROPERTY_MANAGER, SECURITY_GUARD, TENANT_USER, RENTER]
order: 1
relatedTour: admin-onboarding
---

## Welcome to RentAxis

RentAxis is your all-in-one property management platform built for UAE landlords. Whether you manage a single building or an entire portfolio, RentAxis helps you stay on top of your properties, contracts, and finances.

### What You Can Do

Depending on your role, you'll have access to different features:

- **Company Admins** — Full control over properties, contracts, finance, staff, and settings
- **Property Managers** — Manage properties, units, handle maintenance tickets, and view contracts
- **Security Guards** — Manage gate access, expected visitors, pass scans, and walk-ins for assigned properties
- **Company Users** — View your assigned unit details
- **Tenants** — Access your contract, track rent payments, and submit maintenance tickets

### Quick Start

1. **Explore the Dashboard** — Your home screen shows key metrics, recent activity, and quick links
2. **Check the Navigation** — Pick a section on the left rail, then a page in the panel beside it
3. **Use Help Anytime** — Click the floating **?** button on any page for contextual guidance

### Need a Guided Tour?

Click the **?** button in the bottom-right corner and select "Take a Tour" to walk through the portal step-by-step.
`);

registerArticle('getting-started--roles-and-permissions', `---
title: Roles & Permissions
description: Understanding the different user roles in RentAxis
category: getting-started
roles: [SUPER_ADMIN, TENANT_ADMIN, PROPERTY_MANAGER, SECURITY_GUARD, TENANT_USER, RENTER]
order: 2
---

## Roles & Permissions

RentAxis uses role-based access control to ensure each user sees only what they need.

### Role Hierarchy

| Role | Access Level |
|------|-------------|
| **System Admin** | Full system access across all tenants. Manages organizations and users. |
| **Company Admin** | Full access within their organization. Manages properties, contracts, finance, and staff. |
| **Property Manager** | Manages assigned properties, views contracts, handles maintenance tickets. |
| **Security Guard** | Manages gate access at assigned properties. Scans passes and handles approved visitors. |
| **Company User** | Limited access. Can view their assigned unit details. |
| **Tenant** | Self-service portal. Views contracts, tracks payments, submits tickets. |

### What Each Role Can Do

**Company Admin** has access to:
- Create and manage properties, buildings, and units
- Create and manage contracts and tenants
- Full accounting module: chart of accounts, journal vouchers, general ledger, tenant ledger and trial balance
- Staff management and settings configuration
- Payment gateway and rent settings

**Property Manager** has access to:
- View properties and units
- View contracts
- Resolve maintenance tickets

**Security Guard** has access to:
- View assigned gate postings and expected visitors
- Approve recurring resident gate passes for assigned properties
- Scan QR or numeric pass codes for entry and exit
- Register walk-in visitors and admit them after resident approval

**Tenant** has access to:
- View their active contracts
- Track rent payments and download receipts
- Submit and track maintenance tickets
- Download contracts
`);

registerArticle('getting-started--first-property-setup', `---
title: First Property Setup
description: Step-by-step guide to adding your first property
category: getting-started
roles: [TENANT_ADMIN, PROPERTY_MANAGER]
order: 3
relatedTour: property-workflow
---

## Setting Up Your First Property

Follow these steps to get your first property up and running in RentAxis.

### Step 1: Add a Property

1. Go to **Leasing › Properties & Units**
2. Click **Add Property**
3. Fill in the property details:
   - Property name and type (Residential, Commercial, Mixed)
   - Address and location
   - Total units count
4. Click **Save**

### Step 2: Add Buildings (Optional)

If your property has multiple buildings:
1. Open the property you just created
2. Go to the **Buildings** section
3. Click **Add Building** and enter the building name and details

### Step 3: Add Units

1. From your property page, go to the **Units** section
2. Click **Add Unit**
3. Enter unit details:
   - Unit number and floor
   - Type (Studio, 1BR, 2BR, etc.)
   - Area in square feet
   - Monthly rent amount
4. Repeat for all units

### Step 4: Add a Tenant

1. Go to **Leasing › Tenants**
2. Click **Add Tenant**
3. Enter the renter's name, email, phone, and Emirates ID

### Step 5: Create a Tenancy Contract

1. Go to **Tenancy Contracts** and click **Create Tenancy Contract**
2. Select the property, unit, and tenant
3. Set the contract dates, rent amount, and payment schedule
4. The contract starts in **Draft** status — review and activate when ready

Your property is now set up and ready to manage!
`);

// ─── Properties ─────────────────────────────────────────────────────────────

registerArticle('properties--managing-properties', `---
title: Managing Properties
description: How to add, edit, and organize your properties
category: properties
roles: [TENANT_ADMIN, PROPERTY_MANAGER]
order: 1
relatedTour: property-workflow
---

## Managing Properties

The Properties module is your central hub for managing your real estate portfolio.

### Properties List

The properties page shows all your properties in a table view with:
- Property name and type
- Location/address
- Total units and occupancy rate
- Quick actions (view, edit)

### Adding a Property

1. Click **Add Property** on the properties page
2. Fill in the required fields:
   - **Name** — A descriptive name for the property
   - **Type** — Residential, Commercial, or Mixed Use
   - **Address** — Full property address
   - **Total Units** — Number of units in the property
3. Click **Save** to create the property

### Property Detail View

Click on any property to see its detail page, which includes:
- **Overview** — Key metrics (occupancy, revenue, contract status)
- **Units** — All units with their status (Vacant/Occupied)
- **Buildings** — Building breakdown if applicable

### Editing a Property

1. Open the property detail page
2. Click **Edit** to modify any property information
3. Save your changes

### Tips
- Keep property names clear and consistent (e.g., "Marina Tower" not "Property 1")
- Set the correct unit count when creating — this affects occupancy calculations
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

Units are the individual rentable spaces within a property (apartments, offices, shops, etc.).

**Unit Information:**
- **Unit Number** — Unique identifier within the property
- **Floor** — Which floor the unit is on
- **Type** — Studio, 1BR, 2BR, 3BR, Office, Shop, etc.
- **Area** — Size in square feet
- **Rent** — Monthly rent amount
- **Status** — Vacant or Occupied (automatically updated when a contract is activated)

**Adding Units:**
1. Navigate to the property detail page
2. Go to the Units tab
3. Click **Add Unit** and fill in the details

### Buildings

Buildings help organize units within larger properties that have multiple structures.

**Adding Buildings:**
1. Open the property detail page
2. Go to the Buildings section
3. Click **Add Building**
4. Assign units to buildings as needed

### Occupancy Tracking

- Units are automatically marked **Occupied** when an active contract exists
- Units return to **Vacant** when a contract is terminated or expires
- Occupancy rate is calculated as: occupied units / total units
`);

// ─── Leases ─────────────────────────────────────────────────────────────────

registerArticle('leases--creating-a-lease', `---
title: Creating a Lease
description: Step-by-step guide to creating and activating a lease
category: leases
roles: [TENANT_ADMIN, PROPERTY_MANAGER]
order: 1
relatedTour: lease-workflow
---

## Creating a Tenancy Contract

Tenancy Contracts are the core of RentAxis — they link a tenant to a unit with payment terms.

### Creating a New Tenancy Contract

1. Go to **Leasing › Tenancy Contracts**
2. Click **Create Tenancy Contract**
3. Fill in the contract details:
   - **Property & Unit** — Select from your existing properties and vacant units
   - **Tenant** — Choose an existing tenant or create a new one
   - **Tenancy Contract Dates** — Start date and end date
   - **Rent Amount** — Monthly rent in AED
   - **Security Deposit** — If applicable
   - **Payment Method** — Cheque, Online, Cash, or Bank Transfer

### Payment Schedule

When you create a contract, RentAxis automatically generates a payment schedule based on:
- The contract duration
- Monthly rent amount
- Selected payment method
- Pro-rata calculation for the first partial month (if applicable)

You can review and edit the payment schedule before activating the contract.

### Activating the Tenancy Contract

1. Review the contract details and payment schedule
2. Change the status from **Draft** to **Active**
3. The unit will automatically be marked as **Occupied**
`);

registerArticle('leases--lease-lifecycle', `---
title: Lease Lifecycle
description: Understanding lease statuses and transitions
category: leases
roles: [TENANT_ADMIN, PROPERTY_MANAGER]
order: 2
---

## Tenancy Contract Lifecycle

Every contract in RentAxis follows a defined lifecycle.

### Status Flow

DRAFT → PENDING_SIGNATURE → ACTIVE → NOTICE_GIVEN → TERMINATED/EXPIRED → CLOSED

| Status | Meaning |
|--------|---------|
| **Draft** | Tenancy Contract is being prepared. Can still be edited freely. |
| **Pending Signature** | Sent to tenant for review/acceptance. |
| **Active** | Tenancy Contract is live. Unit is marked occupied. Payments are expected. |
| **Notice Given** | Either party has given notice to end the contract. |
| **Terminated** | Tenancy Contract was ended before its natural expiry date. |
| **Expired** | Tenancy Contract reached its end date. |
| **Closed** | All financial obligations settled. Tenancy Contract is archived. |

### Key Actions

- **Activate** — Move from Draft to Active (requires all fields complete)
- **Give Notice** — Mark that notice has been given with a notice date
- **Terminate** — End the contract early
- **Close** — Settle remaining balances and archive
`);

registerArticle('leases--payment-schedules', `---
title: Payment Schedules
description: How payment schedules work and how to manage them
category: leases
roles: [TENANT_ADMIN, PROPERTY_MANAGER]
order: 3
---

## Payment Schedules

Each contract has an associated payment schedule that tracks all expected rent payments.

### Auto-Generated Schedule

When a contract is created, RentAxis automatically generates monthly payment entries:
- The first month is pro-rated if the contract doesn't start on the 1st
- Each entry includes: due date, amount, payment method, and status

### Payment Statuses

| Status | Meaning |
|--------|---------|
| **Pending** | Payment is expected but not yet due or collected |
| **Collected** | Payment has been received |
| **Deposited** | Payment (cheque) has been deposited at the bank |
| **Cleared** | Payment has fully cleared |
| **Overdue** | Payment is past due and not collected |
| **Bounced** | Cheque was returned/bounced |
| **Rejected** | Payment was rejected |

### Managing Payments

1. Go to **Cheque / Cash Collection** from the left rail
2. View all payment schedules across contracts
3. Update payment status as you collect rent
4. For cheque payments, track the deposit and clearance process
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

The Chart of Accounts is the foundation of your financial tracking in RentAxis.

### Account Types

RentAxis uses standard double-entry accounting with five account types:

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

Roles tell RentAxis which account to post to when it raises an entry — rental income, rent receivable, bank, security deposits, and so on. You never pick accounts entry by entry; you map the roles once.

- **Accounting › One-time setup › Property account template** sets, for each role, the name pattern and the parent group under which each property's leaf is created.
- A property's **Accounts** tab shows the leaf resolved for each role on that property, and lets you re-map one to a different account or generate the ones that are missing.
- A role with no property-level mapping falls back to the organisation-wide default account for that role.

### Journals, Not Edits

Posted entries are immutable. A mistake is corrected by reversing the entry from **Accounting › Journal Entries › Journal Voucher**, which posts a mirror entry — the original stays on the books and is marked reversed.
`);

// ─── Renter Portal ──────────────────────────────────────────────────────────

registerArticle('renter--renter-portal-overview', `---
title: Renter Portal Overview
description: Your self-service portal for managing your tenancy
category: renter
roles: [RENTER]
order: 1
relatedTour: renter-portal
---

## Tenant Portal

The Tenant Portal is your self-service hub for everything related to your tenancy.

### What You Can Do

- **View Your Tenancy Contracts** — See your active contract details, including rent amount, dates, and contract terms
- **Track Payments** — Follow your payment schedule and the status of each payment
- **Submit Tickets** — Report maintenance issues or make requests
- **Download Documents** — Access your contract and payment receipts

### Navigation

Your portal sidebar shows:
- **My Tenancy Contracts** — Your contract details and history
- **My Payments** — Payment schedule and history
- **My Tickets** — Maintenance requests and their status

### Getting Help

If you have questions about your contract or payments, submit a ticket through the portal and your landlord's team will respond.
`);

registerArticle('renter--submitting-tickets', `---
title: Submitting Tickets
description: How to report maintenance issues and make requests
category: renter
roles: [RENTER, TENANT_ADMIN, PROPERTY_MANAGER]
order: 2
---

## Submitting Tickets

Use the ticketing system to report maintenance issues, ask questions, or make requests.

### Creating a Ticket

1. Go to **Tickets** from the sidebar
2. Click **New Ticket**
3. Fill in:
   - **Subject** — Brief summary of the issue
   - **Category** — Maintenance, Security, General, or other
   - **Priority** — Low, Medium, High, or Urgent
   - **Description** — Detailed explanation of the issue
4. Submit the ticket

### Tracking Your Tickets

- View all your tickets with their current status
- Open tickets show the full conversation thread
- You'll receive notifications when your ticket is updated

### Ticket Statuses

| Status | Meaning |
|--------|---------|
| **Open** | Ticket submitted, awaiting response |
| **In Progress** | Team is working on it |
| **Resolved** | Issue has been fixed |
| **Closed** | Ticket is complete and archived |
`);

registerArticle('renter--making-payments', `---
title: Understanding Your Payments
description: How to track your rent payment schedule and download receipts
category: renter
roles: [RENTER]
order: 3
---

## Understanding Your Payments

Track your rent payment schedule and history from the portal. Rent collection is handled directly by your landlord's team (typically via cheques), and each payment's status is reflected here.

### Viewing Your Payment Schedule

1. Go to **My Payments** from the sidebar
2. See all upcoming and past payment entries
3. Each entry shows: due date, amount, status

### Payment Statuses

| Status | Meaning |
|--------|---------|
| **Pending** | Payment is scheduled but not yet collected |
| **Overdue** | The due date has passed without collection |
| **Collected** | Your landlord's team has received the payment |
| **Deposited** | The cheque has been deposited at the bank |
| **Cleared** | The payment has cleared |
| **Bounced** | The cheque was returned — contact your landlord's team |

### Receipts

Once a payment is **Cleared**, a **Receipt** button appears on the entry so you can download the payment receipt as a PDF.

### Important Notes

- Online card payments are not currently available through the portal
- Contact your landlord if you have questions about payment amounts or cheque handling
`);

// ─── Administration ─────────────────────────────────────────────────────────

registerArticle('admin--managing-staff', `---
title: Managing Staff
description: How to add and manage staff members
category: admin
roles: [TENANT_ADMIN]
order: 1
---

## Managing Staff

Add team members to your organization and assign them appropriate roles.

### Adding Staff

1. Go to **Operations › Staff** (also under **Settings › Users & staff**)
2. Click **Add Staff Member**
3. Enter their details:
   - Name and email
   - Role (Property Manager or Company User)
   - Assign to specific properties if applicable

### Role Assignment

- **Property Manager** — Can view properties, manage units, and handle tickets
- **Company User** — Limited access, can view assigned unit information

### Managing Existing Staff

- View all staff members in the list
- Edit roles or reassign properties
- Deactivate staff members who no longer need access
`);

registerArticle('admin--tenant-settings', `---
title: Tenant Settings
description: Configuring your organization settings
category: admin
roles: [TENANT_ADMIN]
order: 2
---

## Organisation Settings

Configure your organisation's settings from **Settings** on the left rail — one page with **Organisation**, **Users & staff**, **Rent & fines** and **Payments** sections. Accounting setup lives under **Accounting › One-time setup**.

### Property account template

Map your chart of accounts to RentAxis's automatic transaction types:
1. Go to **Accounting › One-time setup › Property account template**
2. Set the default accounts for rent income, receivables, and deposits
3. These mappings are used when payments are automatically recorded

### Payment Gateway

Configure online payment collection:
1. Go to **Settings › Payments**
2. Enter your Razorpay API credentials
3. Enable or disable the gateway configuration

> **Note:** Online payment initiation is not yet available in the tenant portal — tenants currently track their payment schedule and download receipts there.

### Rent & fines

Customize how rent is calculated and scheduled:
1. Go to **Settings › Rent & fines**
2. Configure pro-rata calculation preferences
3. Set default payment methods and schedules
`);

registerArticle('admin--super-admin-guide', `---
title: Super Admin Guide
description: System administration and multi-tenant management
category: admin
roles: [SUPER_ADMIN]
order: 3
relatedTour: super-admin
---

## Super Admin Guide

As a System Admin, you have full access across all tenants in RentAxis.

### Managing Tenants

1. Go to **Settings › Administration › Organisations**
2. View all registered organizations
3. Create new tenant organizations
4. View tenant details and their users

### Managing Users

1. Go to **Settings › Administration › Users**
2. View all users across all tenants
3. Create, edit, or deactivate user accounts
4. Assign roles and tenant memberships

### Tenant Switching

Use the tenant switcher in the top header to:
- Switch between different organizations
- View the dashboard and data from any tenant's perspective
- Manage properties and settings for a specific tenant

### System Monitoring

- Check the main dashboard for system-wide metrics
- Monitor tenant activity and usage
- Ensure data isolation between tenants
`);
