# Module H: Vendor & Expense Management

## 1. Overview
Manage relationships with external service providers (Vendors), log operational and maintenance expenses, and tie costs back to specific units or properties for accurate profitability (NOI) reporting. Ensures compliance with UAE VAT reporting requirements via Tax Registration Numbers (TRN).

## 2. Architecture & Technical Decisions
- **Direct Linking:** Expenses must optionally reference a `unit_id` or `property_id`. Unlinked expenses define overheads across the entire tenant organization.
- **VAT Accounting Support:** UAE mandates 5% VAT on many services. The expense engine distinctly splits the `base_amount` from the `vat_amount` to feed accurate Input VAT reports back to the landlord's finance department.
- **Approval Workflow:** Triggered sequentially: When an Expense is recorded, if the `base_amount` > Tenant `org_settings.approval_threshold`, it forces a `PENDING_APPROVAL` state.

## 3. Data Model
### Core Tables:
- `vendors`:
  - `id` (UUID), `tenant_org_id` (UUID)
  - `name_en`, `name_ar`
  - `contact_name`, `email`, `phone`
  - `trn_number` (String, UAE Tax Registration Number - 15 digits)
  - `bank_details` (Encrypted string or JSON)
- `expenses`:
  - `id` (UUID), `tenant_org_id` (UUID)
  - `property_id` (UUID, Nullable), `unit_id` (UUID, Nullable)
  - `vendor_id` (UUID)
  - `base_amount`, `vat_amount`, `total_amount` (Numeric)
  - `category` (Enum: MAINTENANCE, UTILITIES, LEGAL, COMMISSION, OVERHEAD)
  - `status` (Enum: PENDING_APPROVAL, PENDING_PAYMENT, PAID)
  - `invoice_date`, `due_date` (Dates)
- `vendor_jobs`:
  - Link table joining `maintenance_requests` to `vendors`.

## 4. API Specification
- `GET /api/v1/vendors` (Directory list)
- `POST /api/v1/expenses` (Log an expense including VAT breakdown)
- `PATCH /api/v1/expenses/{id}/approve` (Manager approval)
- `POST /api/v1/expenses/link-ticket` (Associate expense with a resolved maintenance ticket)

## 5. UI Flows & Interfaces
- **Vendor Management:** Datatable exposing vendor details and flagging missing TRNs visually for compliance.
- **Expense Log Form:** Form with base vs. tax calculations, automatically computing 5% VAT if the vendor has a valid TRN attached.
- **Approvals Dashboard:** A dedicated view for Owners/Managers highlighting stalled/pending high-value expenses.

## 6. Security Constraints
- Users possessing only `OPS` or `VIEWER` roles cannot invoke `/approve` actions on high-value expenses.
- Bank details payload strictly encrypted on write.

## 7. Execution Plan (MVP Phase)
- Implement vendor and basic expense recording structure, extending it for TRN compliance.
- Construct the threshold-based approval logic.
- Deliver Expense Log screens handling the TRN and VAT logic splits.
