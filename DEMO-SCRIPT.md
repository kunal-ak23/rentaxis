# RentAxis Demo Script

**URL:** http://localhost:3000 (local) or https://rentaxis.uaenorth.cloudapp.azure.com (production)
**Duration:** ~15 minutes
**Super Admin:** kunal.sharma@datagami.in / Surat@01

---

## Act 1: Landing Page (1 min)

1. Open the app URL — you land on the **marketing page**
2. Walk through the sections:
   - **Hero** — "Property management, simplified" with dashboard preview
   - **Stats bar** — UAE Compliant, AR/EN Bilingual Contracts, Real-time Reports, Ejari Ready
   - **Features grid** — 6 capabilities: Property Mgmt, Lease Lifecycle, Payments, Reports, Renter Portal, Multi-Tenant
   - **How It Works** — 3 steps: Add Properties → Draft & Sign Leases → Collect & Report
   - **Feature Showcase** — bilingual contract preview, payment lifecycle tracker
   - **Pricing** — Starter (Free), Professional (AED 299/mo), Enterprise (Custom)
3. Click **"Get Started"** to enter the app

---

## Act 2: Super Admin — Create Demo Tenant (2 min)

1. **Login** with: `kunal.sharma@datagami.in` / `Surat@01`
2. You're logged in as **Super Admin** — you see the admin sidebar
3. Go to **Tenants** page in the sidebar
4. Click **"Add Tenant"** → Enter: `Al Fahim Properties`
5. Show the tenant is created — this represents a real property management company
6. Go to **Users** page
7. Click **"Add User"** → Create the Tenant Admin:
   - Name: `Ahmed Al Fahim`
   - Email: `ahmed@alfahim.ae`
   - Password: `Demo@123`
   - Role: `TENANT_ADMIN`
   - Tenant: `Al Fahim Properties`
8. **Log out** (bottom of sidebar)

---

## Act 3: Tenant Admin — Set Up the Organisation (5 min)

### 3a. Login as Tenant Admin
1. Login with: `ahmed@alfahim.ae` / `Demo@123`
2. If prompted, select tenant **Al Fahim Properties**
3. You land on the **Dashboard** — it's empty for now

### 3b. Seed Chart of Accounts
1. Go to **Chart of Accounts** (under Finance in sidebar)
2. Click **"Seed Default Accounts"** — this creates the full UAE-standard account hierarchy:
   - Assets (Bank, Receivables, PDCs)
   - Liabilities (Deposits, Advance Rent, Creditors)
   - Income (Rental, Other)
   - Expenses (DEWA, Maintenance, Security, Insurance, etc.)
   - Equity (Capital Account)
3. Point out: *"This also auto-configures the account mappings — when a cheque clears, the system knows to debit Bank and credit Rental Income automatically."*

### 3c. Create a Property
1. Go to **Properties** in the sidebar
2. Click **"Add Property"**
3. Fill in:
   - Name (English): `Marina Bay Residence`
   - Name (Arabic): `مارينا باي ريزيدنس`
   - Type: `Residential`
   - Emirate: `Dubai`
   - Address: `Dubai Marina, Plot 42`
   - Makani Number: `12345-67890`
4. Click **Create**
5. Click into the property to see its detail page

### 3d. Add Units
1. On the property detail page, scroll to the **Units** section
2. Click **"Add Unit"** and create 3 units:

   | Unit # | Type | Size (SqFt) | Expected Rent |
   |--------|------|-------------|---------------|
   | 101 | 1 BHK | 650 | 55,000 |
   | 201 | 2 BHK | 1100 | 85,000 |
   | 301 | 3 BHK | 1800 | 130,000 |

3. Point out the property stats update: total units, vacancy count, revenue at capacity

### 3e. Add a Renter
1. Go to **Renters** in the sidebar
2. Click **"Add Renter"**
3. Fill in:
   - Name (English): `Sarah Johnson`
   - Name (Arabic): `سارة جونسون`
   - Email: `sarah@example.com`
   - Phone: `+971501234567`
   - Preferred Language: `English`
   - Check: **Create Portal Account** (this will let her log in later)
4. Click **Create**

---

## Act 4: Lease Lifecycle (4 min)

### 4a. Draft a Lease
1. Go to **Leases** in the sidebar
2. Click **"Draft Lease"**
3. Fill in:
   - Unit: `Marina Bay Residence — Unit 201` (the 2BHK)
   - Renter: `Sarah Johnson`
   - Start Date: today's date
   - End Date: 1 year from today
   - Rent Amount: `85000`
   - Security Deposit: `5000`
   - Number of Installments: `4` (quarterly cheques)
   - Rent Payment Method: `Cheque`
   - Deposit Payment Method: `Online`
   - Payment Reference: `CHQ-2025-001`
   - Ejari Number: (leave blank — optional)
4. Click **Create** — lease appears as **DRAFT**

### 4b. Generate Contract
1. On the lease card, click **"Generate Contract"**
2. Wait for it to generate — a **"Download Contract"** button appears
3. Click **Download Contract** — open the PDF
4. Walk through the contract:
   - **Bilingual** — English on left, Arabic on right
   - All lease details filled in (landlord, tenant, property, unit, financial terms)
   - Payment method and reference number included
   - Signature blocks at the bottom
5. Point out: *"This PDF is generated server-side with proper Arabic font rendering — no browser dependency."*

### 4c. Activate the Lease
1. Click **"Activate"** on the lease card
2. Confirm in the dialog: *"This will mark the unit as occupied and generate the payment schedule."*
3. Lease status changes to **ACTIVE**
4. Point out: the unit is now marked as **Occupied** on the properties page

---

## Act 5: Payment Management (3 min)

### 5a. View Payment Schedule
1. Go to **Payments** (under Finance in sidebar)
2. You see 4 installments generated (quarterly for AED 85,000 total):
   - Each shows installment number, due date, amount, and status (Pending)
3. Point out the **summary cards** at top: Total Due, Collected, Deposited, Cleared, Overdue

### 5b. Process a Cheque Payment
Walk through the cheque lifecycle for Installment #1:

1. Click **"Collect"** on Installment #1
   - Enter cheque details: Cheque No `001234`, Bank `Emirates NBD`, Payer `Sarah Johnson`
   - Status changes to **Collected** (cheque received from tenant)

2. Click **"Deposit"** on the same installment
   - Status changes to **Deposited** (cheque submitted to bank)

3. Click **"Clear"** on the same installment
   - Status changes to **Cleared** (bank confirmed funds received)
   - Point out: *"This automatically creates two accounting entries — Bank debit and Rental Income credit. No manual bookkeeping needed."*

### 5c. View Auto-Generated Transactions
1. Go to **Transactions** (under Finance)
2. The **Simple Ledger** view is shown by default — show:
   - **Money In: +21,250.00** (quarter of 85K)
   - Running balance
3. Toggle to **Accounting** view — show the double-entry:
   - Debit to Bank/Cash, Credit to Rental Income
4. Point out: *"Users who don't understand accounting see the simple view. Accountants can switch to the full double-entry view."*

---

## Act 6: Financial Reports (1 min)

1. Go to **Reports** (under Finance)
2. Select **Income Statement** report type
3. Select property: **Marina Bay Residence**
4. Click **Generate Report**
5. Show the income statement:
   - Rental Income: AED 21,250
   - Expenses: AED 0 (nothing recorded yet)
   - NOI (Net Operating Income): AED 21,250
6. Point out: *"Reports work at org level, property level, or unit level."*

---

## Act 7: Settings — Account Mappings (1 min)

1. Go to **Settings → Account Mappings** in the sidebar
2. Show the 4 pre-configured mappings:
   - **Rent Payment Cleared** → Bank Accounts / Rental Income
   - **Security Deposit Received** → Bank Accounts / Security Deposits
   - **Security Deposit Refunded** → Security Deposits / Bank Accounts
   - **Cheque Bounced** → Rental Income / Bank Accounts
3. Point out: *"These are pre-configured with UAE best practices, but each org can customise which accounts are tagged for each event."*
4. Show the dropdown — all Chart of Accounts codes available, grouped by type

---

## Act 8: Dashboard Overview (30 sec)

1. Go to **Dashboard** (first item in sidebar)
2. Show the populated metrics:
   - Properties count, units, vacancies
   - Occupancy rate
   - Revenue collected
   - Active leases
   - Overdue payments
   - Quick links

---

## Closing Talking Points

- **Multi-tenant** — each org gets completely isolated data
- **Bilingual** — full Arabic/English support across the app and contracts
- **UAE-focused** — Ejari integration ready, Emirates-aware, AED currency
- **End-to-end** — from property setup to financial reporting in one platform
- **Renter Portal** — tenants can view their leases and pay online
- **Role-based access** — Super Admin, Tenant Admin, Property Manager, Renter
- **Configurable accounting** — auto-mapping with org-customisable rules
