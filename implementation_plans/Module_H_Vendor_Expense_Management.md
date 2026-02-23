# Module H: Vendor & Expense Management

## 1. Overview
Manage relationships with external service providers (Vendors), log operational and maintenance expenses, and tie costs back to specific units or properties for accurate profitability (NOI) reporting.

## 2. Architecture & Technical Decisions
- **Direct Linking:** Expenses must optionally reference a `unit_id` or `property_id`. Unlinked expenses define overheads across the entire tenant organization.
- **Approval Workflow:** Triggered sequentially: When an Expense is recorded, if the `amount` > Tenant `org_settings.approval_threshold`, it forces a `PENDING_APPROVAL` state requiring Manager intervention before moving to `PAID`.

## 3. Data Model
### Core Tables:
- `vendors`:
  - `id` (UUID), `tenant_org_id` (UUID)
  - `name`, `contact_name`, `email`, `phone`
  - `bank_details` (Encrypted string or JSON)
- `expenses`:
  - `id` (UUID), `tenant_org_id` (UUID)
  - `property_id` (UUID, Nullable), `unit_id` (UUID, Nullable)
  - `vendor_id` (UUID)
  - `amount` (Numeric), `category` (Enum: MAINTENANCE, UTILITIES, LEGAL, COMMISSION, OVERHEAD)
  - `status` (Enum: PENDING_APPROVAL, PENDING_PAYMENT, PAID)
  - `invoice_date`, `due_date` (Dates)
- `vendor_jobs`:
  - Link table joining `maintenance_requests` to `vendors`.

## 4. API Specification
- `GET /api/v1/vendors` (Directory list)
- `POST /api/v1/expenses` (Log an expense)
- `PATCH /api/v1/expenses/{id}/approve` (Manager approval)
- `POST /api/v1/expenses/link-ticket` (Associate expense with a resolved maintenance ticket)

## 5. UI Flows & Interfaces
- **Vendor Management:** Standard master data CRUD datatable.
- **Expense Log Form:** Advanced form with Cascading dropdowns (Select Property -> filters Unit list), categorized tagging, and invoice PDF attachment.
- **Approvals Dashboard:** A dedicated view for Owners/Managers highlighting stalled/pending high-value expenses.

## 6. Security Constraints
- Users possessing only `OPS` or `VIEWER` roles cannot invoke `/approve` actions on high-value expenses.
- Bank details payload strictly encrypted on write to prevent PII bleeding on database dumps.

## 7. Execution Plan (MVP Phase)
- Implement vendor and basic expense recording structure.
- Construct the threshold-based approval logic.
- Introduce Expense Log screens.
