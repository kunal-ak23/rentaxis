// web/src/lib/helpArticles.ts
// Auto-generated article registry. Each article's markdown content is stored as a template literal.
import { registerArticle } from './helpLoader';

// ─── Getting Started ────────────────────────────────────────────────────────

registerArticle('getting-started--welcome', `---
title: Welcome to RentAxis
description: Get started with your property management portal
category: getting-started
roles: [SUPER_ADMIN, TENANT_ADMIN, PROPERTY_MANAGER, TENANT_USER, RENTER]
order: 1
relatedTour: admin-onboarding
---

## Welcome to RentAxis

RentAxis is your all-in-one property management platform built for UAE landlords. Whether you manage a single building or an entire portfolio, RentAxis helps you stay on top of your properties, leases, and finances.

### What You Can Do

Depending on your role, you'll have access to different features:

- **Tenant Admins** — Full control over properties, leases, finance, staff, and settings
- **Property Managers** — Manage properties, units, handle maintenance tickets, and view leases
- **Tenant Users** — View your assigned unit details
- **Renters** — Access your lease, make payments, and submit maintenance tickets

### Quick Start

1. **Explore the Dashboard** — Your home screen shows key metrics, recent activity, and quick links
2. **Check the Sidebar** — Navigate between modules using the left sidebar menu
3. **Use Help Anytime** — Click the floating **?** button on any page for contextual guidance

### Need a Guided Tour?

Click the **?** button in the bottom-right corner and select "Take a Tour" to walk through the portal step-by-step.
`);

registerArticle('getting-started--roles-and-permissions', `---
title: Roles & Permissions
description: Understanding the different user roles in RentAxis
category: getting-started
roles: [SUPER_ADMIN, TENANT_ADMIN, PROPERTY_MANAGER, TENANT_USER, RENTER]
order: 2
---

## Roles & Permissions

RentAxis uses role-based access control to ensure each user sees only what they need.

### Role Hierarchy

| Role | Access Level |
|------|-------------|
| **System Admin** | Full system access across all tenants. Manages organizations and users. |
| **Tenant Admin** | Full access within their organization. Manages properties, leases, finance, and staff. |
| **Property Manager** | Manages assigned properties, views leases, handles maintenance tickets. |
| **Tenant User** | Limited access. Can view their assigned unit details. |
| **Renter** | Self-service portal. Views leases, makes payments, submits tickets. |

### What Each Role Can Do

**Tenant Admin** has access to:
- Create and manage properties, buildings, and units
- Create and manage leases and renters
- Full financial module (accounts, transactions, reports)
- Staff management and settings configuration
- Payment gateway and rent settings

**Property Manager** has access to:
- View properties and units
- View leases
- Resolve maintenance tickets

**Renter** has access to:
- View their active leases
- Make online payments
- Submit and track maintenance tickets
- Download lease contracts
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

1. Go to **Properties** from the sidebar
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

### Step 4: Add a Renter

1. Go to **Renters** from the sidebar
2. Click **Add Renter**
3. Enter the renter's name, email, phone, and Emirates ID

### Step 5: Create a Lease

1. Go to **Leases** and click **Create Lease**
2. Select the property, unit, and renter
3. Set the lease dates, rent amount, and payment schedule
4. The lease starts in **Draft** status — review and activate when ready

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
- **Overview** — Key metrics (occupancy, revenue, lease status)
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
- **Status** — Vacant or Occupied (automatically updated when a lease is activated)

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

- Units are automatically marked **Occupied** when an active lease exists
- Units return to **Vacant** when a lease is terminated or expires
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

## Creating a Lease

Leases are the core of RentAxis — they link a renter to a unit with payment terms.

### Creating a New Lease

1. Go to **Leases** from the sidebar
2. Click **Create Lease**
3. Fill in the lease details:
   - **Property & Unit** — Select from your existing properties and vacant units
   - **Renter** — Choose an existing renter or create a new one
   - **Lease Dates** — Start date and end date
   - **Rent Amount** — Monthly rent in AED
   - **Security Deposit** — If applicable
   - **Payment Method** — Cheque, Online, Cash, or Bank Transfer

### Payment Schedule

When you create a lease, RentAxis automatically generates a payment schedule based on:
- The lease duration
- Monthly rent amount
- Selected payment method
- Pro-rata calculation for the first partial month (if applicable)

You can review and edit the payment schedule before activating the lease.

### Activating the Lease

1. Review the lease details and payment schedule
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

## Lease Lifecycle

Every lease in RentAxis follows a defined lifecycle.

### Status Flow

DRAFT → PENDING_SIGNATURE → ACTIVE → NOTICE_GIVEN → TERMINATED/EXPIRED → CLOSED

| Status | Meaning |
|--------|---------|
| **Draft** | Lease is being prepared. Can still be edited freely. |
| **Pending Signature** | Sent to renter for review/acceptance. |
| **Active** | Lease is live. Unit is marked occupied. Payments are expected. |
| **Notice Given** | Either party has given notice to end the lease. |
| **Terminated** | Lease was ended before its natural expiry date. |
| **Expired** | Lease reached its end date. |
| **Closed** | All financial obligations settled. Lease is archived. |

### Key Actions

- **Activate** — Move from Draft to Active (requires all fields complete)
- **Give Notice** — Mark that notice has been given with a notice date
- **Terminate** — End the lease early
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

Each lease has an associated payment schedule that tracks all expected rent payments.

### Auto-Generated Schedule

When a lease is created, RentAxis automatically generates monthly payment entries:
- The first month is pro-rated if the lease doesn't start on the 1st
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

1. Go to **Finance > Payments** from the sidebar
2. View all payment schedules across leases
3. Update payment status as you collect rent
4. For cheque payments, track the deposit and clearance process
`);

// ─── Finance ────────────────────────────────────────────────────────────────

registerArticle('finance--chart-of-accounts', `---
title: Chart of Accounts
description: Understanding and managing your accounting structure
category: finance
roles: [TENANT_ADMIN]
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

1. Go to **Finance > Chart of Accounts**
2. View all accounts organized by type
3. Click **Add Account** to create a new account
4. Each account has a code, name, type, and optional description

### Account Mappings

Account mappings tell RentAxis which accounts to use for automatic transactions. Configure these in **Settings > Account Mappings**:
- **Rent Income Account** — Where rental income is recorded
- **Rent Receivable Account** — For tracking unpaid rent
- **Security Deposit Account** — For deposit liabilities
`);

registerArticle('finance--recording-transactions', `---
title: Recording Transactions
description: How to record and manage financial transactions
category: finance
roles: [TENANT_ADMIN]
order: 2
---

## Recording Transactions

RentAxis tracks all financial activity as double-entry transactions.

### Creating a Transaction

1. Go to **Finance > Transactions**
2. Click **Add Transaction**
3. Fill in:
   - **Date** — When the transaction occurred
   - **Description** — What the transaction is for
   - **Debit Account** — The account being debited
   - **Credit Account** — The account being credited
   - **Amount** — Transaction amount in AED
   - **Property/Unit** — Optional, link to a specific property or unit

### Automatic Transactions

Some transactions are created automatically:
- When a payment status is updated (e.g., Collected, Cleared)
- When a lease is activated (security deposit entries)

### Viewing Transaction History

- Filter by date range, account, property, or unit
- View organization-level, property-level, or unit-level reports
- Export transaction data for external accounting
`);

registerArticle('finance--financial-reports', `---
title: Financial Reports
description: Understanding the available financial reports
category: finance
roles: [TENANT_ADMIN]
order: 3
---

## Financial Reports

RentAxis provides financial reports at three levels.

### Organization Report

Go to **Finance > Reports** for a high-level view:
- Total income and expenses
- Rent collection summary
- Account balances

### Property Report

Click on a specific property in the reports section:
- Revenue generated by the property
- Occupancy-based income analysis
- Property-specific expenses

### Unit Report

Drill down to individual units:
- Lease-associated income
- Payment collection history
- Unit-specific costs

### Tips
- Review reports monthly to track cash flow
- Compare property performance to identify underperformers
- Use date filters to analyze specific periods
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

## Renter Portal

The Renter Portal is your self-service hub for everything related to your tenancy.

### What You Can Do

- **View Your Leases** — See your active lease details, including rent amount, dates, and contract terms
- **Make Payments** — Pay your rent online securely
- **Submit Tickets** — Report maintenance issues or make requests
- **Download Documents** — Access your lease contract and payment receipts

### Navigation

Your portal sidebar shows:
- **My Leases** — Your lease details and history
- **My Payments** — Payment schedule and online payment
- **My Tickets** — Maintenance requests and their status

### Getting Help

If you have questions about your lease or payments, submit a ticket through the portal and your landlord's team will respond.
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
title: Making Payments
description: How to make online rent payments
category: renter
roles: [RENTER]
order: 3
---

## Making Payments

Pay your rent online through the secure payment portal.

### Viewing Your Payment Schedule

1. Go to **My Payments** from the sidebar
2. See all upcoming and past payment entries
3. Each entry shows: due date, amount, status

### Making an Online Payment

1. Find the payment entry you want to pay
2. Click **Pay Now**
3. You'll be redirected to the secure payment gateway
4. Complete the payment using your preferred method
5. Once confirmed, the payment status updates automatically

### Payment Confirmation

- Successful payments are marked as **Collected** immediately
- You'll receive a notification confirming the payment
- Payment receipts are available for download

### Important Notes

- Payments are processed through Razorpay's secure gateway
- Your payment information is not stored by RentAxis
- Contact your landlord if you have questions about payment amounts
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

1. Go to **Staff** from the sidebar
2. Click **Add Staff Member**
3. Enter their details:
   - Name and email
   - Role (Property Manager or Tenant User)
   - Assign to specific properties if applicable

### Role Assignment

- **Property Manager** — Can view properties, manage units, and handle tickets
- **Tenant User** — Limited access, can view assigned unit information

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

## Tenant Settings

Configure your organization's settings from the Settings section in the sidebar.

### Account Mappings

Map your chart of accounts to RentAxis's automatic transaction types:
1. Go to **Settings > Account Mappings**
2. Set the default accounts for rent income, receivables, and deposits
3. These mappings are used when payments are automatically recorded

### Payment Gateway

Configure online payment collection:
1. Go to **Settings > Gateway Config**
2. Enter your Razorpay API credentials
3. Enable or disable online payments for renters

### Rent Settings

Customize how rent is calculated and scheduled:
1. Go to **Settings > Rent Settings**
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

1. Go to **Tenants** from the sidebar
2. View all registered organizations
3. Create new tenant organizations
4. View tenant details and their users

### Managing Users

1. Go to **Users** from the sidebar
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
