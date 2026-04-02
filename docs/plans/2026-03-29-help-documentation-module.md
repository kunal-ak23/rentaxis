# Help & Documentation Module — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a role-aware in-app help system with guided tours (Shepherd.js) and a searchable markdown help center.

**Architecture:** Frontend-only module. Shepherd.js powers guided tours with `data-tour` attribute selectors. Markdown articles in `web/src/content/help/` rendered by `react-markdown`. A floating help button (FAB) provides contextual links on every dashboard page. All content is role-filtered using the existing RBAC system.

**Tech Stack:** Next.js 16, React 19, Tailwind v4, Framer Motion, react-shepherd, react-markdown, Lucide icons

**Design Doc:** `docs/plans/2026-03-29-help-documentation-module-design.md`

---

### Task 1: Install Dependencies

**Files:**
- Modify: `web/package.json`

**Step 1: Install react-shepherd and react-markdown**

```bash
cd web && npm install react-shepherd react-markdown
```

**Step 2: Verify installation**

```bash
cd web && node -e "require('react-shepherd'); require('react-markdown'); console.log('OK')"
```
Expected: `OK`

**Step 3: Commit**

```bash
git add web/package.json web/package-lock.json
git commit -m "feat(help): install react-shepherd and react-markdown"
```

---

### Task 2: Content Utilities — Markdown Loader + Help Map

**Files:**
- Create: `web/src/lib/help.ts`

**Step 1: Create the help content types and page-to-article mapping**

```typescript
// web/src/lib/help.ts
import type { UserRole } from './rbac';

export interface HelpArticle {
  slug: string;
  title: string;
  description: string;
  category: string;
  roles: UserRole[];
  order: number;
  relatedTour?: string;
  content: string;
}

export interface HelpCategory {
  id: string;
  label: string;
  icon: string; // Lucide icon name
}

export const HELP_CATEGORIES: HelpCategory[] = [
  { id: 'getting-started', label: 'Getting Started', icon: 'Rocket' },
  { id: 'properties', label: 'Properties', icon: 'Building2' },
  { id: 'leases', label: 'Leases', icon: 'FileText' },
  { id: 'finance', label: 'Finance', icon: 'CreditCard' },
  { id: 'renter', label: 'Renter Portal', icon: 'User' },
  { id: 'admin', label: 'Administration', icon: 'Settings' },
];

/**
 * Maps dashboard routes to their contextual help article slug and related tour ID.
 * Used by the HelpFAB to show page-relevant help.
 */
export const HELP_PAGE_MAP: Record<string, { article?: string; tour?: string }> = {
  '/dashboard': { article: 'getting-started--welcome', tour: 'admin-onboarding' },
  '/dashboard/properties': { article: 'properties--managing-properties', tour: 'property-workflow' },
  '/dashboard/leases': { article: 'leases--creating-a-lease', tour: 'lease-workflow' },
  '/dashboard/renters': { article: 'properties--managing-properties' },
  '/dashboard/tickets': { article: 'renter--submitting-tickets' },
  '/dashboard/finance/accounts': { article: 'finance--chart-of-accounts', tour: 'finance-overview' },
  '/dashboard/finance/transactions': { article: 'finance--recording-transactions', tour: 'finance-overview' },
  '/dashboard/finance/reports': { article: 'finance--financial-reports' },
  '/dashboard/finance/payments': { article: 'leases--payment-schedules' },
  '/dashboard/renter-portal': { article: 'renter--renter-portal-overview', tour: 'renter-portal' },
  '/dashboard/renter-portal/payments': { article: 'renter--making-payments' },
  '/dashboard/staff': { article: 'admin--managing-staff' },
  '/dashboard/settings/account-mappings': { article: 'finance--chart-of-accounts' },
  '/dashboard/settings/gateway': { article: 'admin--tenant-settings' },
  '/dashboard/settings/rent-settings': { article: 'admin--tenant-settings' },
  '/superadmin/tenants': { article: 'admin--super-admin-guide', tour: 'super-admin' },
  '/superadmin/users': { article: 'admin--super-admin-guide' },
};

/**
 * Get the contextual help for the current route.
 * Strips locale prefix (e.g. /en/dashboard -> /dashboard) and matches against HELP_PAGE_MAP.
 */
export function getContextualHelp(pathname: string): { article?: string; tour?: string } {
  // Strip locale prefix: /en/dashboard/properties -> /dashboard/properties
  const stripped = pathname.replace(/^\/(en|ar)/, '');
  // Try exact match first, then progressively shorter paths
  if (HELP_PAGE_MAP[stripped]) return HELP_PAGE_MAP[stripped];
  const parts = stripped.split('/');
  while (parts.length > 2) {
    parts.pop();
    const parent = parts.join('/');
    if (HELP_PAGE_MAP[parent]) return HELP_PAGE_MAP[parent];
  }
  return {};
}

/**
 * Filter articles by user role. Returns articles where the role is in the article's roles array.
 */
export function filterArticlesByRole(articles: HelpArticle[], role?: UserRole): HelpArticle[] {
  if (!role) return [];
  return articles.filter(a => a.roles.includes(role));
}

/**
 * Simple client-side search across title, description, and content.
 */
export function searchArticles(articles: HelpArticle[], query: string): HelpArticle[] {
  const q = query.toLowerCase().trim();
  if (!q) return articles;
  return articles.filter(a =>
    a.title.toLowerCase().includes(q) ||
    a.description.toLowerCase().includes(q) ||
    a.content.toLowerCase().includes(q)
  );
}

/**
 * Estimate reading time in minutes.
 */
export function readingTime(content: string): number {
  const words = content.split(/\s+/).length;
  return Math.max(1, Math.ceil(words / 200));
}
```

**Step 2: Commit**

```bash
git add web/src/lib/help.ts
git commit -m "feat(help): add help content types, page mapping, and search utilities"
```

---

### Task 3: Write All Markdown Help Articles

**Files:**
- Create: `web/src/content/help/` — 17 markdown files across 6 categories

Each article uses this frontmatter format parsed by the article loader (Task 4):

```
---
title: Article Title
description: One-line summary
category: category-id
roles: [TENANT_ADMIN, PROPERTY_MANAGER]
order: 1
relatedTour: tour-id
---
```

**Step 1: Create getting-started articles**

Create `web/src/content/help/getting-started--welcome.md`:
```markdown
---
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
```

Create `web/src/content/help/getting-started--roles-and-permissions.md`:
```markdown
---
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
```

Create `web/src/content/help/getting-started--first-property-setup.md`:
```markdown
---
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
```

**Step 2: Create properties articles**

Create `web/src/content/help/properties--managing-properties.md`:
```markdown
---
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
```

Create `web/src/content/help/properties--units-and-buildings.md`:
```markdown
---
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
```

**Step 3: Create leases articles**

Create `web/src/content/help/leases--creating-a-lease.md`:
```markdown
---
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
```

Create `web/src/content/help/leases--lease-lifecycle.md`:
```markdown
---
title: Lease Lifecycle
description: Understanding lease statuses and transitions
category: leases
roles: [TENANT_ADMIN, PROPERTY_MANAGER]
order: 2
---

## Lease Lifecycle

Every lease in RentAxis follows a defined lifecycle.

### Status Flow

```
DRAFT → PENDING_SIGNATURE → ACTIVE → NOTICE_GIVEN → TERMINATED/EXPIRED → CLOSED
```

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
```

Create `web/src/content/help/leases--payment-schedules.md`:
```markdown
---
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
```

**Step 4: Create finance articles**

Create `web/src/content/help/finance--chart-of-accounts.md`:
```markdown
---
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
```

Create `web/src/content/help/finance--recording-transactions.md`:
```markdown
---
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
```

Create `web/src/content/help/finance--financial-reports.md`:
```markdown
---
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
```

**Step 5: Create renter articles**

Create `web/src/content/help/renter--renter-portal-overview.md`:
```markdown
---
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
```

Create `web/src/content/help/renter--submitting-tickets.md`:
```markdown
---
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
```

Create `web/src/content/help/renter--making-payments.md`:
```markdown
---
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
```

**Step 6: Create admin articles**

Create `web/src/content/help/admin--managing-staff.md`:
```markdown
---
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
```

Create `web/src/content/help/admin--tenant-settings.md`:
```markdown
---
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
```

Create `web/src/content/help/admin--super-admin-guide.md`:
```markdown
---
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
```

**Step 7: Commit all articles**

```bash
git add web/src/content/help/
git commit -m "feat(help): add 17 role-specific help articles across 6 categories"
```

---

### Task 4: Article Loader Utility

**Files:**
- Create: `web/src/lib/helpLoader.ts`

**Step 1: Create the loader that reads markdown files and parses frontmatter**

```typescript
// web/src/lib/helpLoader.ts
import type { HelpArticle } from './help';

/**
 * All help articles imported statically.
 * Each entry: slug derived from filename (category--article-name), raw content string.
 *
 * We parse frontmatter manually to avoid adding a dependency like gray-matter.
 */

interface RawArticle {
  slug: string;
  raw: string;
}

function parseFrontmatter(raw: string): { metadata: Record<string, string | string[]>; content: string } {
  const match = raw.match(/^---\n([\s\S]*?)\n---\n([\s\S]*)$/);
  if (!match) return { metadata: {}, content: raw };

  const metadata: Record<string, string | string[]> = {};
  const lines = match[1].split('\n');
  for (const line of lines) {
    const colonIdx = line.indexOf(':');
    if (colonIdx === -1) continue;
    const key = line.slice(0, colonIdx).trim();
    let value = line.slice(colonIdx + 1).trim();
    // Parse array values like [TENANT_ADMIN, PROPERTY_MANAGER]
    if (value.startsWith('[') && value.endsWith(']')) {
      metadata[key] = value.slice(1, -1).split(',').map(v => v.trim());
    } else {
      metadata[key] = value;
    }
  }

  return { metadata, content: match[2].trim() };
}

function toArticle(slug: string, raw: string): HelpArticle {
  const { metadata, content } = parseFrontmatter(raw);
  return {
    slug,
    title: (metadata.title as string) || slug,
    description: (metadata.description as string) || '',
    category: (metadata.category as string) || 'uncategorized',
    roles: (metadata.roles as string[]) || [],
    order: parseInt((metadata.order as string) || '99', 10),
    relatedTour: (metadata.relatedTour as string) || undefined,
    content,
  };
}

// Static imports — Next.js will bundle these at build time.
// Using require.context is not available in Next.js, so we use explicit imports.
// This approach is maintenance-friendly: add a new import + entry when adding an article.

const articleModules: RawArticle[] = [];

// We'll populate this in the actual implementation using a dynamic approach.
// For now, each article is imported explicitly.

let _articles: HelpArticle[] | null = null;

export function getAllArticles(): HelpArticle[] {
  if (_articles) return _articles;
  _articles = articleModules.map(a => toArticle(a.slug, a.raw));
  _articles.sort((a, b) => {
    if (a.category !== b.category) return a.category.localeCompare(b.category);
    return a.order - b.order;
  });
  return _articles;
}

export function getArticleBySlug(slug: string): HelpArticle | undefined {
  return getAllArticles().find(a => a.slug === slug);
}

export function getArticlesByCategory(category: string): HelpArticle[] {
  return getAllArticles().filter(a => a.category === category);
}

export { parseFrontmatter, toArticle };
```

Note: The actual static imports of all 17 `.md` files will be wired in during implementation. Next.js supports raw text imports via `?raw` suffix or a webpack loader config. The implementer should choose the simplest approach — either:
- (a) Add a `raw-loader` rule in `next.config.ts` for `.md` files, or
- (b) Read files at build time in a `generateStaticParams` / server component approach, or
- (c) Store article content as TypeScript string constants (simplest, no config changes)

The implementer should pick (b) or (c) based on what works cleanest with Next.js 16.

**Step 2: Commit**

```bash
git add web/src/lib/helpLoader.ts
git commit -m "feat(help): add article loader with frontmatter parsing"
```

---

### Task 5: Tour Definitions

**Files:**
- Create: `web/src/components/tour/tours/admin-onboarding.ts`
- Create: `web/src/components/tour/tours/property-workflow.ts`
- Create: `web/src/components/tour/tours/finance-overview.ts`
- Create: `web/src/components/tour/tours/renter-portal.ts`
- Create: `web/src/components/tour/tours/super-admin.ts`

**Step 1: Create the shared tour step type**

Create `web/src/components/tour/tours/types.ts`:

```typescript
import type { UserRole } from '@/lib/rbac';

export interface TourStepDef {
  id: string;
  /** CSS selector — prefer [data-tour="..."] attributes */
  target: string;
  title: string;
  text: string;
  position: 'top' | 'bottom' | 'left' | 'right';
  /** If set, navigate to this route before showing this step */
  nextRoute?: string;
}

export interface TourDef {
  id: string;
  name: string;
  description: string;
  roles: UserRole[];
  steps: TourStepDef[];
}
```

**Step 2: Create admin onboarding tour**

Create `web/src/components/tour/tours/admin-onboarding.ts`:

```typescript
import type { TourDef } from './types';

export const adminOnboardingTour: TourDef = {
  id: 'admin-onboarding',
  name: 'Welcome Tour',
  description: 'Quick overview of your RentAxis dashboard',
  roles: ['TENANT_ADMIN', 'PROPERTY_MANAGER', 'TENANT_USER'],
  steps: [
    {
      id: 'welcome',
      target: '[data-tour="dashboard-header"]',
      title: 'Welcome to RentAxis!',
      text: 'This is your dashboard — it shows key metrics about your properties, leases, and finances at a glance.',
      position: 'bottom',
    },
    {
      id: 'sidebar-nav',
      target: '[data-tour="sidebar-nav"]',
      title: 'Sidebar Navigation',
      text: 'Use the sidebar to navigate between modules. You can collapse it for more screen space.',
      position: 'right',
    },
    {
      id: 'sidebar-properties',
      target: '[data-tour="sidebar-properties"]',
      title: 'Properties',
      text: 'Manage your property portfolio here — add properties, buildings, and units.',
      position: 'right',
    },
    {
      id: 'sidebar-leases',
      target: '[data-tour="sidebar-leases"]',
      title: 'Leases',
      text: 'Create and manage leases, link renters to units, and track payment schedules.',
      position: 'right',
    },
    {
      id: 'sidebar-finance',
      target: '[data-tour="sidebar-finance"]',
      title: 'Finance',
      text: 'Track income, expenses, and generate financial reports for your properties.',
      position: 'right',
    },
    {
      id: 'help-fab',
      target: '[data-tour="help-fab"]',
      title: 'Need Help?',
      text: 'Click this button anytime for contextual help, guided tours, or to browse the Help Center.',
      position: 'top',
    },
  ],
};
```

**Step 3: Create property workflow tour**

Create `web/src/components/tour/tours/property-workflow.ts`:

```typescript
import type { TourDef } from './types';

export const propertyWorkflowTour: TourDef = {
  id: 'property-workflow',
  name: 'Property Setup Tour',
  description: 'Learn how to add properties, units, and buildings',
  roles: ['TENANT_ADMIN', 'PROPERTY_MANAGER'],
  steps: [
    {
      id: 'properties-page',
      target: '[data-tour="properties-header"]',
      title: 'Properties List',
      text: 'This page shows all your properties. You can see the name, type, location, and occupancy for each one.',
      position: 'bottom',
      nextRoute: '/dashboard/properties',
    },
    {
      id: 'add-property-btn',
      target: '[data-tour="add-property-btn"]',
      title: 'Add a Property',
      text: 'Click here to add a new property. You\'ll enter the name, type, address, and unit count.',
      position: 'bottom',
    },
    {
      id: 'property-table',
      target: '[data-tour="properties-table"]',
      title: 'Property Table',
      text: 'Your properties are listed here. Click on any property to view its details, units, and buildings.',
      position: 'top',
    },
  ],
};
```

**Step 4: Create finance overview tour**

Create `web/src/components/tour/tours/finance-overview.ts`:

```typescript
import type { TourDef } from './types';

export const financeOverviewTour: TourDef = {
  id: 'finance-overview',
  name: 'Finance Module Tour',
  description: 'Understanding accounts, transactions, and reports',
  roles: ['TENANT_ADMIN'],
  steps: [
    {
      id: 'accounts-page',
      target: '[data-tour="accounts-header"]',
      title: 'Chart of Accounts',
      text: 'This is your chart of accounts — the foundation of financial tracking. Accounts are grouped by type: Asset, Liability, Equity, Income, and Expense.',
      position: 'bottom',
      nextRoute: '/dashboard/finance/accounts',
    },
    {
      id: 'transactions-link',
      target: '[data-tour="sidebar-transactions"]',
      title: 'Transactions',
      text: 'View and record financial transactions. Some are created automatically when payment statuses change.',
      position: 'right',
    },
    {
      id: 'reports-link',
      target: '[data-tour="sidebar-reports"]',
      title: 'Financial Reports',
      text: 'Generate reports at the organization, property, or unit level to track your financial performance.',
      position: 'right',
    },
    {
      id: 'payments-link',
      target: '[data-tour="sidebar-payments"]',
      title: 'Payments',
      text: 'Track rent collection across all leases. Update payment statuses as you collect cheques or receive transfers.',
      position: 'right',
    },
  ],
};
```

**Step 5: Create renter portal tour**

Create `web/src/components/tour/tours/renter-portal.ts`:

```typescript
import type { TourDef } from './types';

export const renterPortalTour: TourDef = {
  id: 'renter-portal',
  name: 'Renter Portal Tour',
  description: 'Your self-service portal walkthrough',
  roles: ['RENTER'],
  steps: [
    {
      id: 'renter-home',
      target: '[data-tour="dashboard-header"]',
      title: 'Your Renter Portal',
      text: 'Welcome! This is your self-service portal where you can view leases, make payments, and submit maintenance requests.',
      position: 'bottom',
    },
    {
      id: 'my-leases',
      target: '[data-tour="sidebar-my-leases"]',
      title: 'My Leases',
      text: 'View your active lease details including rent amount, dates, and contract documents.',
      position: 'right',
    },
    {
      id: 'my-payments',
      target: '[data-tour="sidebar-my-payments"]',
      title: 'My Payments',
      text: 'See your payment schedule and make online rent payments securely.',
      position: 'right',
    },
    {
      id: 'my-tickets',
      target: '[data-tour="sidebar-my-tickets"]',
      title: 'My Tickets',
      text: 'Report maintenance issues or make requests. Track the status of your tickets here.',
      position: 'right',
    },
  ],
};
```

**Step 6: Create super admin tour**

Create `web/src/components/tour/tours/super-admin.ts`:

```typescript
import type { TourDef } from './types';

export const superAdminTour: TourDef = {
  id: 'super-admin',
  name: 'Super Admin Tour',
  description: 'System administration overview',
  roles: ['SUPER_ADMIN'],
  steps: [
    {
      id: 'admin-dashboard',
      target: '[data-tour="dashboard-header"]',
      title: 'System Dashboard',
      text: 'As a System Admin, you have full access across all tenants. This dashboard shows system-wide metrics.',
      position: 'bottom',
    },
    {
      id: 'tenants-link',
      target: '[data-tour="sidebar-tenants"]',
      title: 'Tenants',
      text: 'Manage all registered organizations. Create new tenants, view their details, and monitor usage.',
      position: 'right',
    },
    {
      id: 'users-link',
      target: '[data-tour="sidebar-users"]',
      title: 'Users',
      text: 'Manage user accounts across all tenants. Create users, assign roles, and control access.',
      position: 'right',
    },
    {
      id: 'tenant-switcher',
      target: '[data-tour="tenant-switcher"]',
      title: 'Tenant Switcher',
      text: 'Switch between tenants to view their data and manage their settings.',
      position: 'bottom',
    },
  ],
};
```

**Step 7: Create tour index**

Create `web/src/components/tour/tours/index.ts`:

```typescript
import { adminOnboardingTour } from './admin-onboarding';
import { propertyWorkflowTour } from './property-workflow';
import { financeOverviewTour } from './finance-overview';
import { renterPortalTour } from './renter-portal';
import { superAdminTour } from './super-admin';
import type { TourDef } from './types';
import type { UserRole } from '@/lib/rbac';

export const ALL_TOURS: TourDef[] = [
  adminOnboardingTour,
  propertyWorkflowTour,
  financeOverviewTour,
  renterPortalTour,
  superAdminTour,
];

/**
 * Get tours available for a specific role.
 */
export function getToursForRole(role?: UserRole): TourDef[] {
  if (!role) return [];
  return ALL_TOURS.filter(t => t.roles.includes(role));
}

/**
 * Get a specific tour by ID.
 */
export function getTourById(id: string): TourDef | undefined {
  return ALL_TOURS.find(t => t.id === id);
}

export type { TourDef, TourStepDef } from './types';
```

**Step 8: Commit**

```bash
git add web/src/components/tour/
git commit -m "feat(help): add 5 role-specific tour definitions with shared types"
```

---

### Task 6: TourProvider Component (Shepherd.js Wrapper)

**Files:**
- Create: `web/src/components/tour/TourProvider.tsx`
- Create: `web/src/components/tour/TourTrigger.tsx`
- Create: `web/src/components/tour/tour-styles.css`

**Step 1: Create Shepherd theme CSS**

Create `web/src/components/tour/tour-styles.css`:

```css
/* Shepherd.js theme overrides for RentAxis */

.shepherd-element {
  max-width: 360px;
  border-radius: 12px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.15);
  border: 1px solid rgba(15, 118, 110, 0.2);
  z-index: 10000;
}

.shepherd-element .shepherd-content {
  border-radius: 12px;
  padding: 0;
}

.shepherd-element .shepherd-header {
  background: #0F766E;
  padding: 12px 16px;
  border-radius: 12px 12px 0 0;
}

.shepherd-element .shepherd-title {
  color: white;
  font-size: 14px;
  font-weight: 600;
  font-family: var(--font-heading), 'Cinzel', serif;
}

.shepherd-element .shepherd-cancel-icon {
  color: rgba(255, 255, 255, 0.7);
}

.shepherd-element .shepherd-cancel-icon:hover {
  color: white;
}

.shepherd-element .shepherd-text {
  padding: 16px;
  font-size: 13px;
  line-height: 1.6;
  color: #374151;
}

.shepherd-element .shepherd-footer {
  padding: 0 16px 12px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.shepherd-element .shepherd-button {
  border-radius: 6px;
  padding: 6px 14px;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: all 150ms;
}

.shepherd-element .shepherd-button-secondary {
  background: transparent;
  color: #6B7280;
  border: 1px solid #E5E7EB;
}

.shepherd-element .shepherd-button-secondary:hover {
  background: #F3F4F6;
}

.shepherd-element .shepherd-button-primary {
  background: #C8A951;
  color: white;
  border: none;
}

.shepherd-element .shepherd-button-primary:hover {
  background: #B89A42;
}

/* Progress indicator */
.shepherd-progress {
  font-size: 11px;
  color: #9CA3AF;
  padding: 0 16px 8px;
}

/* Modal overlay */
.shepherd-modal-overlay-container {
  z-index: 9999;
}

.shepherd-has-active-tour .shepherd-modal-overlay-container .shepherd-modal-mask-rect {
  fill: rgba(0, 0, 0, 0.5);
}
```

**Step 2: Create TourProvider**

Create `web/src/components/tour/TourProvider.tsx`:

```typescript
'use client';

import { createContext, useContext, useCallback, useEffect, useRef } from 'react';
import { ShepherdTour, ShepherdTourContext } from 'react-shepherd';
import { useSession } from 'next-auth/react';
import { useRouter } from '@/i18n/routing';
import { usePathname } from 'next/navigation';
import { getTourById, getToursForRole } from './tours';
import type { TourDef } from './tours/types';
import type { UserRole } from '@/lib/rbac';
import 'shepherd.js/dist/css/shepherd.css';
import './tour-styles.css';

const STORAGE_KEY = 'rentaxis_tours_completed';

function getCompletedTours(): string[] {
  if (typeof window === 'undefined') return [];
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]');
  } catch {
    return [];
  }
}

function markTourCompleted(tourId: string) {
  const completed = getCompletedTours();
  if (!completed.includes(tourId)) {
    completed.push(tourId);
    localStorage.setItem(STORAGE_KEY, JSON.stringify(completed));
  }
}

export function isTourCompleted(tourId: string): boolean {
  return getCompletedTours().includes(tourId);
}

interface TourContextValue {
  startTour: (tourId: string) => void;
  availableTours: TourDef[];
  completedTourIds: string[];
}

const TourCtx = createContext<TourContextValue>({
  startTour: () => {},
  availableTours: [],
  completedTourIds: [],
});

export function useTour() {
  return useContext(TourCtx);
}

function TourManager({ children }: { children: React.ReactNode }) {
  const tour = useContext(ShepherdTourContext);
  const { data: session } = useSession();
  const router = useRouter();
  const pathname = usePathname();
  const userRole = session?.user?.role as UserRole | undefined;
  const hasAutoTriggered = useRef(false);

  const availableTours = getToursForRole(userRole);
  const completedTourIds = getCompletedTours();

  const startTour = useCallback((tourId: string) => {
    if (!tour) return;

    const tourDef = getTourById(tourId);
    if (!tourDef) return;

    // Cancel any active tour
    if (tour.isActive()) tour.cancel();

    // Clear existing steps
    while (tour.steps.length > 0) {
      tour.removeStep(tour.steps[0].id);
    }

    const totalSteps = tourDef.steps.length;

    tourDef.steps.forEach((step, index) => {
      tour.addStep({
        id: step.id,
        title: step.title,
        text: `${step.text}<div class="shepherd-progress">Step ${index + 1} of ${totalSteps}</div>`,
        attachTo: { element: step.target, on: step.position },
        canClickTarget: false,
        modalOverlayOpeningPadding: 8,
        modalOverlayOpeningRadius: 8,
        buttons: [
          ...(index > 0 ? [{
            text: 'Back',
            classes: 'shepherd-button-secondary',
            action: () => tour.back(),
          }] : []),
          {
            text: 'Skip',
            classes: 'shepherd-button-secondary',
            action: () => {
              markTourCompleted(tourId);
              tour.cancel();
            },
          },
          {
            text: index === totalSteps - 1 ? 'Done' : 'Next',
            classes: 'shepherd-button-primary',
            action: () => {
              if (index === totalSteps - 1) {
                markTourCompleted(tourId);
                tour.complete();
              } else {
                // Check if next step needs navigation
                const nextStep = tourDef.steps[index + 1];
                if (nextStep?.nextRoute) {
                  router.push(nextStep.nextRoute);
                  // Small delay for page transition
                  setTimeout(() => tour.next(), 500);
                } else {
                  tour.next();
                }
              }
            },
          },
        ],
        ...(step.nextRoute && index === 0 ? {
          beforeShowPromise: () => {
            return new Promise<void>((resolve) => {
              const stripped = pathname.replace(/^\/(en|ar)/, '');
              if (stripped !== step.nextRoute) {
                router.push(step.nextRoute!);
                setTimeout(resolve, 500);
              } else {
                resolve();
              }
            });
          },
        } : {}),
      });
    });

    tour.start();
  }, [tour, router, pathname]);

  // Auto-trigger onboarding tour on first visit
  useEffect(() => {
    if (hasAutoTriggered.current || !userRole || !tour) return;
    hasAutoTriggered.current = true;

    const onboardingTour = availableTours.find(t =>
      t.id.includes('onboarding') || t.id === 'renter-portal' || t.id === 'super-admin'
    );

    if (onboardingTour && !isTourCompleted(onboardingTour.id)) {
      // Delay to let the page fully render
      setTimeout(() => startTour(onboardingTour.id), 1500);
    }
  }, [userRole, tour, availableTours, startTour]);

  return (
    <TourCtx.Provider value={{ startTour, availableTours, completedTourIds }}>
      {children}
    </TourCtx.Provider>
  );
}

export default function TourProvider({ children }: { children: React.ReactNode }) {
  const tourOptions = {
    defaultStepOptions: {
      cancelIcon: { enabled: true },
      scrollTo: { behavior: 'smooth' as const, block: 'center' as const },
    },
    useModalOverlay: true,
  };

  return (
    <ShepherdTour steps={[]} tourOptions={tourOptions}>
      <TourManager>{children}</TourManager>
    </ShepherdTour>
  );
}
```

**Step 3: Create TourTrigger button**

Create `web/src/components/tour/TourTrigger.tsx`:

```typescript
'use client';

import { Play, CheckCircle2 } from 'lucide-react';
import { useTour, isTourCompleted } from './TourProvider';
import { cn } from '@/lib/utils';
import type { TourDef } from './tours/types';

interface TourTriggerProps {
  tour: TourDef;
  variant?: 'card' | 'inline';
}

export default function TourTrigger({ tour, variant = 'card' }: TourTriggerProps) {
  const { startTour } = useTour();
  const completed = isTourCompleted(tour.id);

  if (variant === 'inline') {
    return (
      <button
        onClick={() => startTour(tour.id)}
        className="inline-flex items-center gap-1.5 text-sm text-primary hover:text-primary/80 font-medium transition-colors cursor-pointer"
      >
        <Play size={14} />
        {completed ? 'Retake Tour' : 'Start Tour'}
      </button>
    );
  }

  return (
    <button
      onClick={() => startTour(tour.id)}
      className={cn(
        "flex flex-col gap-2 p-4 rounded-lg border transition-all cursor-pointer text-left",
        "hover:shadow-md hover:border-primary/30",
        completed
          ? "bg-primary/5 border-primary/20"
          : "bg-white border-gray-200 hover:bg-gray-50"
      )}
    >
      <div className="flex items-center justify-between">
        <span className="text-sm font-semibold text-gray-900">{tour.name}</span>
        {completed ? (
          <CheckCircle2 size={16} className="text-primary" />
        ) : (
          <Play size={16} className="text-accent" />
        )}
      </div>
      <p className="text-xs text-gray-500 leading-relaxed">{tour.description}</p>
    </button>
  );
}
```

**Step 4: Commit**

```bash
git add web/src/components/tour/
git commit -m "feat(help): add TourProvider, TourTrigger, and Shepherd theme"
```

---

### Task 7: Help Center Page

**Files:**
- Create: `web/src/app/[locale]/dashboard/help/page.tsx`
- Create: `web/src/app/[locale]/dashboard/help/[slug]/page.tsx`
- Create: `web/src/components/help/HelpCenter.tsx`
- Create: `web/src/components/help/HelpArticle.tsx`
- Create: `web/src/components/help/HelpSearch.tsx`
- Create: `web/src/components/help/RoleFilter.tsx`

**Step 1: Create the HelpSearch component**

Create `web/src/components/help/HelpSearch.tsx`:

```typescript
'use client';

import { Search, X } from 'lucide-react';
import { useState, useEffect, useRef } from 'react';
import { cn } from '@/lib/utils';

interface HelpSearchProps {
  onSearch: (query: string) => void;
}

export default function HelpSearch({ onSearch }: HelpSearchProps) {
  const [query, setQuery] = useState('');
  const debounceRef = useRef<NodeJS.Timeout>();

  useEffect(() => {
    clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => onSearch(query), 200);
    return () => clearTimeout(debounceRef.current);
  }, [query, onSearch]);

  return (
    <div className="relative">
      <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
      <input
        type="text"
        value={query}
        onChange={e => setQuery(e.target.value)}
        placeholder="Search help articles..."
        className={cn(
          "w-full pl-10 pr-10 py-2.5 rounded-lg border border-gray-200",
          "text-sm placeholder:text-gray-400",
          "focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary/40",
          "transition-all"
        )}
      />
      {query && (
        <button
          onClick={() => setQuery('')}
          className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600 cursor-pointer"
        >
          <X size={14} />
        </button>
      )}
    </div>
  );
}
```

**Step 2: Create the RoleFilter component**

Create `web/src/components/help/RoleFilter.tsx`:

```typescript
'use client';

import { cn } from '@/lib/utils';
import { getRoleLabel, type UserRole } from '@/lib/rbac';

interface RoleFilterProps {
  roles: UserRole[];
  compact?: boolean;
}

export default function RoleFilter({ roles, compact = false }: RoleFilterProps) {
  return (
    <div className="flex flex-wrap gap-1">
      {roles.map(role => (
        <span
          key={role}
          className={cn(
            "inline-flex items-center rounded-full font-medium",
            "bg-primary/10 text-primary",
            compact
              ? "px-1.5 py-0.5 text-[10px]"
              : "px-2 py-0.5 text-[11px]"
          )}
        >
          {getRoleLabel(role)}
        </span>
      ))}
    </div>
  );
}
```

**Step 3: Create the HelpArticle renderer**

Create `web/src/components/help/HelpArticle.tsx`:

```typescript
'use client';

import ReactMarkdown from 'react-markdown';
import { cn } from '@/lib/utils';
import type { HelpArticle as HelpArticleType } from '@/lib/help';
import RoleFilter from './RoleFilter';
import { readingTime } from '@/lib/help';
import { Clock, ArrowLeft } from 'lucide-react';
import { Link } from '@/i18n/routing';
import TourTrigger from '@/components/tour/TourTrigger';
import { getTourById } from '@/components/tour/tours';

interface HelpArticleProps {
  article: HelpArticleType;
}

export default function HelpArticleView({ article }: HelpArticleProps) {
  const relatedTour = article.relatedTour ? getTourById(article.relatedTour) : undefined;

  return (
    <div>
      {/* Back link */}
      <Link
        href="/dashboard/help"
        className="inline-flex items-center gap-1.5 text-sm text-gray-500 hover:text-primary mb-6 transition-colors"
      >
        <ArrowLeft size={14} />
        Back to Help Center
      </Link>

      {/* Article header */}
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-gray-900 mb-2">{article.title}</h1>
        <p className="text-sm text-gray-500 mb-3">{article.description}</p>
        <div className="flex items-center gap-3">
          <RoleFilter roles={article.roles} />
          <span className="flex items-center gap-1 text-xs text-gray-400">
            <Clock size={12} />
            {readingTime(article.content)} min read
          </span>
        </div>
      </div>

      {/* Related tour */}
      {relatedTour && (
        <div className="mb-6 p-4 rounded-lg bg-accent/5 border border-accent/20">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm font-medium text-gray-900">Interactive Tour Available</p>
              <p className="text-xs text-gray-500 mt-0.5">Walk through this feature step-by-step</p>
            </div>
            <TourTrigger tour={relatedTour} variant="inline" />
          </div>
        </div>
      )}

      {/* Article content */}
      <article className={cn(
        "prose prose-sm max-w-none",
        "prose-headings:font-semibold prose-headings:text-gray-900",
        "prose-h2:text-lg prose-h2:mt-8 prose-h2:mb-4",
        "prose-h3:text-base prose-h3:mt-6 prose-h3:mb-3",
        "prose-p:text-gray-600 prose-p:leading-relaxed",
        "prose-li:text-gray-600",
        "prose-strong:text-gray-900",
        "prose-table:text-sm",
        "prose-th:text-left prose-th:font-semibold prose-th:text-gray-900 prose-th:bg-gray-50 prose-th:px-3 prose-th:py-2",
        "prose-td:px-3 prose-td:py-2 prose-td:border-t",
        "prose-code:text-primary prose-code:bg-primary/5 prose-code:px-1.5 prose-code:py-0.5 prose-code:rounded prose-code:text-xs",
      )}>
        <ReactMarkdown>{article.content}</ReactMarkdown>
      </article>
    </div>
  );
}
```

**Step 4: Create the HelpCenter layout**

Create `web/src/components/help/HelpCenter.tsx`:

```typescript
'use client';

import { useState, useCallback, useMemo } from 'react';
import { useSession } from 'next-auth/react';
import {
  Rocket, Building2, FileText, CreditCard, User, Settings,
  BookOpen, ChevronRight,
} from 'lucide-react';
import { Link } from '@/i18n/routing';
import { cn } from '@/lib/utils';
import { HELP_CATEGORIES, filterArticlesByRole, searchArticles, readingTime } from '@/lib/help';
import type { HelpArticle } from '@/lib/help';
import type { UserRole } from '@/lib/rbac';
import HelpSearch from './HelpSearch';
import RoleFilter from './RoleFilter';
import TourTrigger from '@/components/tour/TourTrigger';
import { useTour } from '@/components/tour/TourProvider';

const CATEGORY_ICONS: Record<string, React.ElementType> = {
  'getting-started': Rocket,
  'properties': Building2,
  'leases': FileText,
  'finance': CreditCard,
  'renter': User,
  'admin': Settings,
};

interface HelpCenterProps {
  articles: HelpArticle[];
}

export default function HelpCenter({ articles }: HelpCenterProps) {
  const { data: session } = useSession();
  const userRole = session?.user?.role as UserRole | undefined;
  const { availableTours } = useTour();

  const [searchQuery, setSearchQuery] = useState('');
  const [selectedCategory, setSelectedCategory] = useState<string | null>(null);
  const [showAllRoles, setShowAllRoles] = useState(false);

  const filteredArticles = useMemo(() => {
    let result = showAllRoles ? articles : filterArticlesByRole(articles, userRole);
    if (selectedCategory) {
      result = result.filter(a => a.category === selectedCategory);
    }
    if (searchQuery) {
      result = searchArticles(result, searchQuery);
    }
    return result;
  }, [articles, userRole, selectedCategory, searchQuery, showAllRoles]);

  const handleSearch = useCallback((q: string) => setSearchQuery(q), []);

  // Count articles per category for the current role
  const categoryCounts = useMemo(() => {
    const roleArticles = showAllRoles ? articles : filterArticlesByRole(articles, userRole);
    const counts: Record<string, number> = {};
    for (const a of roleArticles) {
      counts[a.category] = (counts[a.category] || 0) + 1;
    }
    return counts;
  }, [articles, userRole, showAllRoles]);

  return (
    <div>
      {/* Header */}
      <div className="mb-8">
        <div className="flex items-center gap-3 mb-2">
          <BookOpen size={24} className="text-primary" />
          <h1 className="text-2xl font-bold text-gray-900">Help Center</h1>
        </div>
        <p className="text-sm text-gray-500">
          Find answers, take guided tours, and learn how to get the most out of RentAxis.
        </p>
      </div>

      <div className="flex gap-8">
        {/* Sidebar */}
        <aside className="w-56 shrink-0">
          <nav className="space-y-1">
            <button
              onClick={() => setSelectedCategory(null)}
              className={cn(
                "w-full flex items-center justify-between px-3 py-2 rounded-lg text-sm font-medium transition-colors cursor-pointer",
                !selectedCategory
                  ? "bg-primary/10 text-primary"
                  : "text-gray-600 hover:bg-gray-100"
              )}
            >
              <span>All Articles</span>
              <span className="text-xs text-gray-400">{Object.values(categoryCounts).reduce((a, b) => a + b, 0)}</span>
            </button>
            {HELP_CATEGORIES.map(cat => {
              const Icon = CATEGORY_ICONS[cat.id] || BookOpen;
              const count = categoryCounts[cat.id] || 0;
              if (count === 0) return null;
              return (
                <button
                  key={cat.id}
                  onClick={() => setSelectedCategory(cat.id)}
                  className={cn(
                    "w-full flex items-center gap-2.5 px-3 py-2 rounded-lg text-sm font-medium transition-colors cursor-pointer",
                    selectedCategory === cat.id
                      ? "bg-primary/10 text-primary"
                      : "text-gray-600 hover:bg-gray-100"
                  )}
                >
                  <Icon size={14} className="shrink-0" />
                  <span className="flex-1 text-left">{cat.label}</span>
                  <span className="text-xs text-gray-400">{count}</span>
                </button>
              );
            })}
          </nav>

          {/* Show all toggle for admins */}
          {(userRole === 'SUPER_ADMIN' || userRole === 'TENANT_ADMIN') && (
            <label className="flex items-center gap-2 mt-6 px-3 text-xs text-gray-500 cursor-pointer">
              <input
                type="checkbox"
                checked={showAllRoles}
                onChange={e => setShowAllRoles(e.target.checked)}
                className="rounded border-gray-300 text-primary focus:ring-primary/20"
              />
              Show all roles
            </label>
          )}
        </aside>

        {/* Main content */}
        <div className="flex-1 min-w-0">
          {/* Search */}
          <div className="mb-6">
            <HelpSearch onSearch={handleSearch} />
          </div>

          {/* Article grid */}
          {filteredArticles.length > 0 ? (
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4 mb-10">
              {filteredArticles.map(article => (
                <Link
                  key={article.slug}
                  href={`/dashboard/help/${article.slug}`}
                  className="group block p-4 rounded-lg border border-gray-200 hover:border-primary/30 hover:shadow-md transition-all cursor-pointer"
                >
                  <div className="flex items-start justify-between gap-2 mb-2">
                    <h3 className="text-sm font-semibold text-gray-900 group-hover:text-primary transition-colors">
                      {article.title}
                    </h3>
                    <ChevronRight size={14} className="text-gray-300 group-hover:text-primary shrink-0 mt-0.5 transition-colors" />
                  </div>
                  <p className="text-xs text-gray-500 mb-3 line-clamp-2">{article.description}</p>
                  <div className="flex items-center justify-between">
                    <RoleFilter roles={article.roles} compact />
                    <span className="text-[10px] text-gray-400">{readingTime(article.content)} min</span>
                  </div>
                </Link>
              ))}
            </div>
          ) : (
            <div className="text-center py-12 text-gray-400">
              <BookOpen size={32} className="mx-auto mb-2 opacity-40" />
              <p className="text-sm">No articles found</p>
            </div>
          )}

          {/* Tours section */}
          {availableTours.length > 0 && (
            <div>
              <h2 className="text-lg font-semibold text-gray-900 mb-4">Guided Tours</h2>
              <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
                {availableTours.map(tour => (
                  <TourTrigger key={tour.id} tour={tour} variant="card" />
                ))}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
```

**Step 5: Create the Help Center page route**

Create `web/src/app/[locale]/dashboard/help/page.tsx`:

```typescript
'use client';

import AuthenticatedLayout from '@/components/layout/AuthenticatedLayout';
import HelpCenter from '@/components/help/HelpCenter';
import { getAllArticles } from '@/lib/helpLoader';

export default function HelpPage() {
  const articles = getAllArticles();

  return (
    <AuthenticatedLayout>
      <HelpCenter articles={articles} />
    </AuthenticatedLayout>
  );
}
```

**Step 6: Create the article detail page route**

Create `web/src/app/[locale]/dashboard/help/[slug]/page.tsx`:

```typescript
'use client';

import { useParams } from 'next/navigation';
import AuthenticatedLayout from '@/components/layout/AuthenticatedLayout';
import HelpArticleView from '@/components/help/HelpArticle';
import { getArticleBySlug } from '@/lib/helpLoader';
import { BookOpen } from 'lucide-react';
import { Link } from '@/i18n/routing';

export default function HelpArticlePage() {
  const params = useParams();
  const slug = params.slug as string;
  const article = getArticleBySlug(slug);

  if (!article) {
    return (
      <AuthenticatedLayout>
        <div className="text-center py-16">
          <BookOpen size={40} className="mx-auto mb-3 text-gray-300" />
          <h2 className="text-lg font-semibold text-gray-900 mb-1">Article Not Found</h2>
          <p className="text-sm text-gray-500 mb-4">The help article you're looking for doesn't exist.</p>
          <Link
            href="/dashboard/help"
            className="text-sm text-primary hover:text-primary/80 font-medium"
          >
            Back to Help Center
          </Link>
        </div>
      </AuthenticatedLayout>
    );
  }

  return (
    <AuthenticatedLayout>
      <HelpArticleView article={article} />
    </AuthenticatedLayout>
  );
}
```

**Step 7: Commit**

```bash
git add web/src/components/help/ web/src/app/*/dashboard/help/
git commit -m "feat(help): add Help Center page with search, role filtering, and article views"
```

---

### Task 8: Floating Help Button (FAB)

**Files:**
- Create: `web/src/components/help/HelpFAB.tsx`
- Modify: `web/src/components/layout/AuthenticatedLayout.tsx`

**Step 1: Create the HelpFAB component**

Create `web/src/components/help/HelpFAB.tsx`:

```typescript
'use client';

import { useState, useRef, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { HelpCircle, BookOpen, Play, X } from 'lucide-react';
import { usePathname } from 'next/navigation';
import { Link } from '@/i18n/routing';
import { cn } from '@/lib/utils';
import { getContextualHelp } from '@/lib/help';
import { useTour } from '@/components/tour/TourProvider';
import { getTourById } from '@/components/tour/tours';

export default function HelpFAB() {
  const [isOpen, setIsOpen] = useState(false);
  const popoverRef = useRef<HTMLDivElement>(null);
  const pathname = usePathname();
  const { startTour } = useTour();

  const contextual = getContextualHelp(pathname);
  const contextualTour = contextual.tour ? getTourById(contextual.tour) : undefined;

  // Close popover on click outside
  useEffect(() => {
    function handleClickOutside(e: MouseEvent) {
      if (popoverRef.current && !popoverRef.current.contains(e.target as Node)) {
        setIsOpen(false);
      }
    }
    if (isOpen) document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [isOpen]);

  return (
    <div className="fixed bottom-6 right-6 z-50" ref={popoverRef} data-tour="help-fab">
      <AnimatePresence>
        {isOpen && (
          <motion.div
            initial={{ opacity: 0, y: 10, scale: 0.95 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: 10, scale: 0.95 }}
            transition={{ duration: 0.15 }}
            className="absolute bottom-16 right-0 w-64 bg-white rounded-xl shadow-xl border border-gray-200 overflow-hidden"
          >
            <div className="p-3 bg-primary text-white">
              <p className="text-sm font-semibold">Need Help?</p>
              <p className="text-xs opacity-80">Get guidance for this page</p>
            </div>

            <div className="p-2">
              {contextual.article && (
                <Link
                  href={`/dashboard/help/${contextual.article}`}
                  onClick={() => setIsOpen(false)}
                  className="flex items-center gap-3 px-3 py-2.5 rounded-lg hover:bg-gray-50 transition-colors cursor-pointer"
                >
                  <BookOpen size={16} className="text-primary shrink-0" />
                  <div>
                    <p className="text-sm font-medium text-gray-900">View Page Help</p>
                    <p className="text-[11px] text-gray-500">Read documentation for this page</p>
                  </div>
                </Link>
              )}

              {contextualTour && (
                <button
                  onClick={() => {
                    setIsOpen(false);
                    startTour(contextualTour.id);
                  }}
                  className="w-full flex items-center gap-3 px-3 py-2.5 rounded-lg hover:bg-gray-50 transition-colors cursor-pointer text-left"
                >
                  <Play size={16} className="text-accent shrink-0" />
                  <div>
                    <p className="text-sm font-medium text-gray-900">Take a Tour</p>
                    <p className="text-[11px] text-gray-500">{contextualTour.name}</p>
                  </div>
                </button>
              )}

              <Link
                href="/dashboard/help"
                onClick={() => setIsOpen(false)}
                className="flex items-center gap-3 px-3 py-2.5 rounded-lg hover:bg-gray-50 transition-colors cursor-pointer"
              >
                <HelpCircle size={16} className="text-gray-400 shrink-0" />
                <div>
                  <p className="text-sm font-medium text-gray-900">Help Center</p>
                  <p className="text-[11px] text-gray-500">Browse all guides and articles</p>
                </div>
              </Link>
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      {/* FAB Button */}
      <motion.button
        whileHover={{ scale: 1.05 }}
        whileTap={{ scale: 0.95 }}
        onClick={() => setIsOpen(!isOpen)}
        className={cn(
          "w-12 h-12 rounded-full shadow-lg flex items-center justify-center transition-colors cursor-pointer",
          "focus:outline-none focus:ring-2 focus:ring-primary/30",
          isOpen
            ? "bg-gray-100 text-gray-600"
            : "bg-primary text-white hover:bg-primary/90"
        )}
      >
        {isOpen ? <X size={20} /> : <HelpCircle size={20} />}
      </motion.button>
    </div>
  );
}
```

**Step 2: Add HelpFAB and TourProvider to AuthenticatedLayout**

Modify `web/src/components/layout/AuthenticatedLayout.tsx`:

Add imports at top:
```typescript
import TourProvider from '@/components/tour/TourProvider';
import HelpFAB from '@/components/help/HelpFAB';
```

Wrap the return JSX with TourProvider and add HelpFAB:
```typescript
return (
  <TourProvider>
    <div className="flex h-screen overflow-hidden bg-background">
      <MvpSidebar />
      <div className="flex flex-col flex-1 min-w-0">
        <TopHeader />
        <main className="flex-1 overflow-y-auto">
          <div className="max-w-7xl mx-auto py-8 px-4 md:px-8">
            {children}
          </div>
        </main>
      </div>
      <HelpFAB />
    </div>
  </TourProvider>
);
```

**Step 3: Commit**

```bash
git add web/src/components/help/HelpFAB.tsx web/src/components/layout/AuthenticatedLayout.tsx
git commit -m "feat(help): add floating help button and wire TourProvider into layout"
```

---

### Task 9: Add data-tour Attributes to Existing UI

**Files:**
- Modify: `web/src/components/ui/MvpSidebar.tsx` — add `data-tour` to nav elements
- Modify: `web/src/components/ui/TopHeader.tsx` — add `data-tour` to tenant switcher
- Modify: `web/src/app/[locale]/dashboard/page.tsx` — add `data-tour` to dashboard header

**Step 1: Add data-tour attributes to MvpSidebar**

In `MvpSidebar.tsx`, add `data-tour="sidebar-nav"` to the `<nav>` element:
```typescript
<nav data-tour="sidebar-nav" className="flex-1 py-1 px-3 space-y-0.5 overflow-y-auto">
```

Add `data-tour` attributes to individual menu items. The sidebar renders items dynamically, so add `data-tour` to the `<Link>` elements via a data attribute on each menu item config. Update the menu item type to include an optional `tourId` field and render it:

Add `tourId` to each relevant menu item:
```typescript
const menuItems = [
  ...(hasPermission(userRole, 'canViewProperties')
    ? [
      { name: t("properties"), href: "/dashboard/properties", icon: LayoutDashboard, tourId: 'sidebar-properties' },
      { name: t("renters"), href: "/dashboard/renters", icon: Contact, tourId: 'sidebar-renters' },
      { name: t("leases"), href: "/dashboard/leases", icon: FileText, tourId: 'sidebar-leases' },
      { name: "Tickets", href: "/dashboard/tickets", icon: Wrench, tourId: 'sidebar-tickets' },
    ] : []),
  ...(hasPermission(userRole, 'canManageTenants')
    ? [{ name: "Tenants", href: "/superadmin/tenants", icon: ShieldCheck, tourId: 'sidebar-tenants' }]
    : []),
  ...(hasPermission(userRole, 'canManageUsers')
    ? [{ name: "Users", href: "/superadmin/users", icon: Users, tourId: 'sidebar-users' }]
    : []),
];
```

Add `tourId` to finance items:
```typescript
const financeItems = hasPermission(userRole, 'canAccessFinance') ? [
  { name: t("chartOfAccounts"), href: "/dashboard/finance/accounts", icon: BookOpen, tourId: 'sidebar-accounts' },
  { name: t("transactions"), href: "/dashboard/finance/transactions", icon: Receipt, tourId: 'sidebar-transactions' },
  { name: t("reports"), href: "/dashboard/finance/reports", icon: BarChart3, tourId: 'sidebar-reports' },
  { name: tPayments("payments"), href: "/dashboard/finance/payments", icon: CreditCard, tourId: 'sidebar-payments' },
  { name: tVendors("title"), href: "/dashboard/finance/vendors", icon: Users },
  { name: tBankAccounts("title"), href: "/dashboard/finance/bank-accounts", icon: Landmark },
] : [];
```

Add `tourId` to renter items:
```typescript
const renterItems = hasPermission(userRole, 'canViewRenterPortal') ? [
  { name: "My Leases", href: "/dashboard/renter-portal", icon: FileText, tourId: 'sidebar-my-leases' },
  { name: tOnlinePayments("myPayments"), href: "/dashboard/renter-portal/payments", icon: CreditCard, tourId: 'sidebar-my-payments' },
  { name: "My Tickets", href: "/dashboard/tickets", icon: Wrench, tourId: 'sidebar-my-tickets' },
] : [];
```

In `renderSection`, add the `data-tour` attribute to the `<Link>`:
```typescript
<Link
  href={item.href}
  data-tour={item.tourId}
  className={...}
>
```

**Step 2: Add data-tour to TopHeader tenant switcher**

In `TopHeader.tsx`, add `data-tour="tenant-switcher"` to the TenantSwitcher wrapper div.

**Step 3: Add data-tour to dashboard header**

In `web/src/app/[locale]/dashboard/page.tsx`, add `data-tour="dashboard-header"` to the header section div.

**Step 4: Commit**

```bash
git add web/src/components/ui/MvpSidebar.tsx web/src/components/ui/TopHeader.tsx web/src/app/*/dashboard/page.tsx
git commit -m "feat(help): add data-tour attributes to sidebar, header, and dashboard"
```

---

### Task 10: Add Help Center to Sidebar Navigation

**Files:**
- Modify: `web/src/components/ui/MvpSidebar.tsx`

**Step 1: Add HelpCircle import and Help & Guides link**

Add to Lucide imports:
```typescript
import { ..., HelpCircle } from 'lucide-react';
```

Add a new "Support" section before the footer in the sidebar. Insert just before the `{/* Footer */}` comment:

```typescript
{/* Support */}
<div className="mt-auto px-3 pt-4 pb-2">
  <div className={cn(
    "px-4 mb-2.5 text-[9px] font-semibold uppercase tracking-[0.2em]",
    "text-sidebar-muted",
    isCollapsed && "hidden"
  )}>
    Support
  </div>
  <SidebarTooltip label="Help & Guides" enabled={isCollapsed}>
    <Link
      href="/dashboard/help"
      data-tour="sidebar-help"
      className={cn(
        "group flex items-center gap-3 px-3 py-2.5 rounded-lg transition-all duration-200 text-[13px] font-medium relative cursor-pointer",
        "focus:outline-none focus:ring-2 focus:ring-accent/30",
        pathname.includes("/dashboard/help")
          ? "bg-white/10 text-white"
          : "text-sidebar-muted hover:bg-white/5 hover:text-white",
        isCollapsed && "justify-center"
      )}
    >
      <HelpCircle size={16} className={cn(
        "shrink-0 transition-colors",
        pathname.includes("/dashboard/help") ? "text-accent" : "group-hover:text-white/80"
      )} />
      {!isCollapsed && <span className="flex-1">Help & Guides</span>}
    </Link>
  </SidebarTooltip>
</div>
```

**Step 2: Commit**

```bash
git add web/src/components/ui/MvpSidebar.tsx
git commit -m "feat(help): add Help & Guides link to sidebar navigation"
```

---

### Task 11: Welcome Banner for First-Time Users

**Files:**
- Create: `web/src/components/help/WelcomeBanner.tsx`
- Modify: `web/src/app/[locale]/dashboard/page.tsx`

**Step 1: Create the WelcomeBanner component**

Create `web/src/components/help/WelcomeBanner.tsx`:

```typescript
'use client';

import { useState, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { Sparkles, X, Play } from 'lucide-react';
import { useTour, isTourCompleted } from '@/components/tour/TourProvider';
import { useSession } from 'next-auth/react';
import { getToursForRole } from '@/components/tour/tours';
import type { UserRole } from '@/lib/rbac';

const BANNER_DISMISSED_KEY = 'rentaxis_welcome_dismissed';

export default function WelcomeBanner() {
  const [visible, setVisible] = useState(false);
  const { startTour } = useTour();
  const { data: session } = useSession();
  const userRole = session?.user?.role as UserRole | undefined;

  useEffect(() => {
    if (typeof window === 'undefined') return;
    const dismissed = localStorage.getItem(BANNER_DISMISSED_KEY);
    if (dismissed) return;

    // Show banner if no onboarding tour completed
    const tours = getToursForRole(userRole);
    const onboarding = tours.find(t =>
      t.id.includes('onboarding') || t.id === 'renter-portal' || t.id === 'super-admin'
    );
    if (onboarding && !isTourCompleted(onboarding.id)) {
      setVisible(true);
    }
  }, [userRole]);

  const dismiss = () => {
    setVisible(false);
    localStorage.setItem(BANNER_DISMISSED_KEY, 'true');
  };

  const handleStartTour = () => {
    const tours = getToursForRole(userRole);
    const onboarding = tours.find(t =>
      t.id.includes('onboarding') || t.id === 'renter-portal' || t.id === 'super-admin'
    );
    if (onboarding) {
      dismiss();
      startTour(onboarding.id);
    }
  };

  return (
    <AnimatePresence>
      {visible && (
        <motion.div
          initial={{ opacity: 0, y: -10 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: -10 }}
          className="mb-6 p-4 rounded-xl bg-gradient-to-r from-primary/10 to-accent/10 border border-primary/20"
        >
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-lg bg-primary/15 flex items-center justify-center">
                <Sparkles size={20} className="text-primary" />
              </div>
              <div>
                <h3 className="text-sm font-semibold text-gray-900">Welcome to RentAxis!</h3>
                <p className="text-xs text-gray-500 mt-0.5">Take a quick tour to learn your way around the portal.</p>
              </div>
            </div>
            <div className="flex items-center gap-2">
              <button
                onClick={handleStartTour}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-primary text-white text-xs font-medium hover:bg-primary/90 transition-colors cursor-pointer"
              >
                <Play size={12} />
                Start Tour
              </button>
              <button
                onClick={dismiss}
                className="p-1.5 rounded-lg text-gray-400 hover:text-gray-600 hover:bg-gray-100 transition-colors cursor-pointer"
              >
                <X size={14} />
              </button>
            </div>
          </div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
```

**Step 2: Add WelcomeBanner to dashboard page**

In `web/src/app/[locale]/dashboard/page.tsx`, import and render the WelcomeBanner at the top of the page content (before the KPI cards):

```typescript
import WelcomeBanner from '@/components/help/WelcomeBanner';
```

Then add `<WelcomeBanner />` right after the page header div.

**Step 3: Commit**

```bash
git add web/src/components/help/WelcomeBanner.tsx web/src/app/*/dashboard/page.tsx
git commit -m "feat(help): add welcome banner with tour trigger for first-time users"
```

---

### Task 12: Build Verification & Smoke Test

**Step 1: Verify the build compiles**

```bash
cd web && npm run build
```
Expected: Build succeeds without errors.

**Step 2: Manual smoke test checklist**

Run dev server and verify:
```bash
cd web && npm run dev
```

Check:
- [ ] Help FAB visible on dashboard (bottom-right teal "?" button)
- [ ] FAB popover opens on click with 3 options
- [ ] Sidebar shows "Help & Guides" link under Support section
- [ ] `/dashboard/help` loads the Help Center page
- [ ] Articles filtered by current user role
- [ ] Search works (type and results filter live)
- [ ] Category sidebar filters articles
- [ ] Click an article card → article detail page renders markdown correctly
- [ ] Guided Tours section visible with tour cards
- [ ] Click "Start Tour" on a tour card → Shepherd tour starts with spotlight overlay
- [ ] Tour has Back, Skip, Next buttons and progress indicator
- [ ] Welcome banner appears on dashboard for first visit
- [ ] "Start Tour" on banner launches onboarding tour
- [ ] Dismiss banner → it doesn't reappear on refresh

**Step 3: Final commit**

```bash
git add -A
git commit -m "feat(help): complete help & documentation module with tours and help center"
```
