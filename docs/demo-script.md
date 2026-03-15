# RentAxis Demo Script

> **Duration:** ~30 minutes | **Date:** March 15, 2026
> **URL:** https://rentaxis.uaenorth.cloudapp.azure.com
> **Focus:** Full platform walkthrough with emphasis on new COA & finance features

---

## Pre-Demo Setup

Make sure you have these credentials ready:

| Role | Email | Password | Tenant |
|------|-------|----------|--------|
| Super Admin | *(your super admin)* | *(your password)* | System |
| Tenant Admin | admin@alashram.ae | password123 | Al Ashram Real Estate |

> **Tip:** Open two browser tabs — one for Super Admin, one for Tenant Admin. Use incognito for the second.

---

## Act 1: Platform Overview (5 min)

### 1.1 — Super Admin Portal

**Login as Super Admin**

> "RentAxis is a multi-tenant property management platform built for UAE landlords. Let me start by showing the Super Admin view."

- Show **Tenants** page — list of all landlord organizations
- Show **Users** page — all users across tenants with role assignments
- Point out the **5 RBAC roles**: Super Admin, Tenant Admin, Property Manager, Tenant User, Renter

> "Each tenant is completely isolated — a landlord can never see another landlord's data. This is enforced at the database level on every query."

### 1.2 — Switch to Tenant Admin

**Login as Al Ashram Real Estate (admin@alashram.ae)**

> "Now let me switch to a tenant admin — Al Ashram Real Estate, a Dubai-based landlord managing residential and commercial properties."

- Show the **Dashboard** — KPI cards (properties, units, occupancy, revenue, overdue)
- Point out the **sidebar** — Overview, Finance, HR, Settings sections
- Toggle to **Arabic** (click locale switcher) — show full RTL support
- Toggle back to English

---

## Act 2: Property & Lease Management (5 min)

### 2.1 — Properties

> "Al Ashram manages two properties — a residential tower and a commercial business centre."

- Open **Properties** page
- Click into **Al Barsha Tower** — show property details, unit breakdown
- Show **JLT Business Centre** — commercial property
- Point out: unit types (Studio, 1BHK, 2BHK, 3BHK, Penthouse, Office), occupancy stats

### 2.2 — Renters & Leases

> "We have 5 active tenants across both properties."

- Open **Renters** page — show the 5 renters
- Open **Leases** page — show 5 active leases
- Click into a lease — show:
  - Lease details (unit, renter, dates, rent amount, payment terms)
  - **Generate Contract** button → generates a bilingual PDF
  - Download the contract PDF
  - Payment schedule auto-generated on activation

---

## Act 3: Payment Collection Workflow (5 min)

### 3.1 — Payment Schedule

> "When a lease is activated, the system automatically generates a payment schedule based on the number of cheques."

- Open **Finance → Payments**
- Show the payment list — mix of statuses (Pending, Collected, Deposited, Cleared)
- Point out the **summary bar** — total, collected, cleared, overdue counts

### 3.2 — Cheque Lifecycle

> "Let me walk through the cheque collection workflow."

- Find a **Pending** payment
- Click **Collect** — enter cheque number, cheque date, bank name, payer name
- Click **Deposit** — cheque sent to bank
- Click **Clear** — cheque cleared, financial transaction auto-created
- Show that the cleared amount now appears in the **Transactions** ledger

> "Every cleared payment automatically creates a double-entry financial transaction — no manual bookkeeping needed."

---

## Act 4: Chart of Accounts — NEW (5 min)

### 4.1 — Bilingual COA with Hierarchy

> "This is our enhanced Chart of Accounts, designed for the UAE market with bilingual support."

- Open **Finance → Chart of Accounts**
- Show **Tree View** — expandable hierarchy with indentation
  - Expand **Assets → Current Assets** to show nested accounts
  - Point out: code, English name, Arabic name, account type badges, sub-types
- Toggle to **Flat View** — grouped cards by type
- Toggle **Active Only** filter
- Show **account type color coding**: green (Asset), red (Liability), blue (Income), amber (Expense), violet (Equity)

### 4.2 — Add & Edit Accounts

> "Tenant admins can extend the COA with custom accounts."

- Click **Add Account** — show the form:
  - Code, English name, Arabic name
  - Account type → sub-type dropdown (contextual)
  - Parent account selection
  - Group account checkbox
- Click **Cancel** (don't actually create during demo)
- Show **Edit** icon on a non-system account
- Show **Delete** icon (with protection — can't delete system accounts or accounts with children)

### 4.3 — Bulk Import

> "For clients migrating from another system, we support bulk import."

- Click **Import** button
- Show the upload modal — drag & drop area
- Mention: supports CSV and Excel (XLSX) formats
- Click **Cancel**

---

## Act 5: Financial Transactions & VAT (5 min)

### 5.1 — Simple vs Accounting View

> "Our transactions page has two views — Simple for non-accountants, and Accounting for the finance team."

- Open **Finance → Transactions**
- Show **Simple View** — Money In / Money Out / Running Balance
  - Clean, intuitive for property managers
- Switch to **Accounting View** — Debit / Credit / Account codes
  - Professional double-entry view for accountants
- Show **pagination** at the bottom (25 per page)

### 5.2 — Add Transaction with VAT

> "Let's record an expense. UAE has 5% VAT, and the system auto-calculates it."

- Click **Add Transaction**
- Fill in:
  - Date: today
  - Account: D-01-10 DEWA
  - Description: "DEWA March 2026 - Al Barsha Tower"
  - Debit: 5000
  - Property: Al Barsha Tower
  - Check **VAT Applicable** — watch the auto-calculation appear:
    - Net: 5,000 | VAT (5%): 250 | Gross: 5,250
- Click **Create**
- Show the new transaction in the list

### 5.3 — Filters

- Click **Filter** — show property, account type, date range filters
- Filter by **Property: Al Barsha Tower** — show only that property's transactions
- Clear filters

---

## Act 6: Financial Reports — NEW (3 min)

> "All this data feeds into comprehensive financial reports."

- Open **Finance → Reports**

### 6.1 — P&L Report

- Show **P&L tab** (default) — set date range to cover all data
- Point out: Total Income, Total Expenses, NOI, Net Profit
- Show income breakdown and expense breakdown

### 6.2 — Trial Balance

- Click **Trial Balance** tab
- Show account-level debit/credit/balance table
- Point out the totals row

### 6.3 — Aging Report

- Click **Aging Report** tab
- Show the 5 aging buckets: Current, 1-30, 31-60, 61-90, 90+ days
- Show total outstanding amount
- Expand a bucket to see renter-level details

### 6.4 — VAT Return

- Click **VAT Return** tab
- Show: Output VAT, Input VAT, Net VAT Payable
- Point out: this feeds directly into FTA VAT return filing

> "Every report updates in real-time as transactions are recorded. No month-end close needed."

---

## Act 7: Vendor & Staff Management — NEW (3 min)

### 7.1 — Vendors

> "We track all service providers and contractors."

- Open **Finance → Vendors**
- Show the vendor list: DEWA, Al Rashid Security, Green Clean, Al Masood Maintenance
- Click **Edit** on a vendor — show fields: TRN, trade license, bank details, linked payable account
- Mention: vendors are linked to transactions for payables tracking

### 7.2 — Bank Accounts

- Open **Finance → Bank Accounts**
- Show accounts linked to properties: Emirates Islamic (Al Barsha), Emirates NBD (JLT)
- Point out: linked to COA account, property assignment, default account flag

### 7.3 — Staff (HR)

- Open **Staff** (under HR section)
- Show **summary cards** — total staff count, total monthly salary bill
- Show staff table: name, employee ID, designation, property, salary
- Click **Edit** on a staff member — show property assignment dropdown now working correctly
- Show **property filter** — filter staff by property

---

## Act 8: Bilingual & RTL Support (1 min)

> "Everything works in Arabic too."

- Switch locale to **Arabic** (top bar or URL)
- Show:
  - Sidebar labels in Arabic
  - Account names in Arabic in the COA
  - Reports with Arabic labels
  - Full RTL layout
- Switch back to English

---

## Act 9: Online Rent Payment (2 min)

> "Renters can pay online through a self-service portal."

- *(If a renter account is available, show the renter portal)*
- Otherwise, describe:
  - Renter logs in → sees their leases and payment schedule
  - Click **Pay Now** → Razorpay checkout opens
  - Payment confirmation → auto-updates payment status
  - Webhook verification for tamper-proof payments

- Show **Settings → Payment Gateway** — Razorpay configuration
- Show **Settings → Rent Settings** — due day, grace period, penalty config

---

## Closing (1 min)

> "To summarize — RentAxis gives UAE landlords everything they need in one platform:"

- **Multi-tenant isolation** — each landlord's data is completely separate
- **Full lease lifecycle** — from draft to payment collection to financial reporting
- **UAE-compliant accounting** — VAT handling, bilingual COA, FTA-ready reports
- **Self-service renter portal** — online payments, contract acceptance
- **Role-based access** — 5 roles from super admin to renter
- **Arabic-first** — full RTL and bilingual support throughout

---

## Quick Reference: Demo Steps Checklist

```
PRE-DEMO
[ ] Open browser tab 1 — Super Admin login
[ ] Open browser tab 2 (incognito) — Tenant Admin (admin@alashram.ae / password123)
[ ] Verify site is up: https://rentaxis.uaenorth.cloudapp.azure.com

ACT 1 — OVERVIEW (5 min)
[ ] Super Admin → Tenants page
[ ] Super Admin → Users page
[ ] Switch to Tenant Admin tab
[ ] Show Dashboard KPIs
[ ] Toggle Arabic locale → back to English

ACT 2 — PROPERTIES & LEASES (5 min)
[ ] Properties → Al Barsha Tower details
[ ] Properties → JLT Business Centre
[ ] Renters page
[ ] Leases page → click into a lease
[ ] Show contract generation / download

ACT 3 — PAYMENTS (5 min)
[ ] Finance → Payments
[ ] Show payment summary bar
[ ] Walk through Collect → Deposit → Clear on a pending payment
[ ] Show auto-created transaction

ACT 4 — CHART OF ACCOUNTS (5 min)
[ ] Finance → Chart of Accounts
[ ] Tree View → expand hierarchy
[ ] Toggle to Flat View
[ ] Show Add Account form (don't save)
[ ] Show Edit/Delete icons
[ ] Show Import modal (don't upload)

ACT 5 — TRANSACTIONS & VAT (5 min)
[ ] Finance → Transactions
[ ] Show Simple View (Money In/Out)
[ ] Switch to Accounting View (Debit/Credit)
[ ] Show pagination
[ ] Add Transaction with VAT checkbox → show auto-calc
[ ] Apply property filter

ACT 6 — REPORTS (3 min)
[ ] Finance → Reports
[ ] P&L tab with date range
[ ] Trial Balance tab
[ ] Aging Report tab → show buckets
[ ] VAT Return tab → show Output/Input/Net

ACT 7 — VENDORS, BANKS, STAFF (3 min)
[ ] Finance → Vendors → edit one
[ ] Finance → Bank Accounts → show property links
[ ] HR → Staff → show summary cards
[ ] Staff → edit → show property dropdown working
[ ] Staff → filter by property

ACT 8 — ARABIC (1 min)
[ ] Switch to Arabic
[ ] Show sidebar + COA + reports in Arabic
[ ] Switch back to English

ACT 9 — ONLINE PAYMENTS (2 min)
[ ] Describe renter portal flow
[ ] Settings → Payment Gateway config
[ ] Settings → Rent Collection settings

CLOSING (1 min)
[ ] Summary slide / talking points
```
