# Spec: Wire Maintenance to Vendors & Staff (Phase 1 of vendor/staff integration)

**Date:** 2026-08-23
**Origin:** Gap analysis of vendor/staff coupling. The `Vendor` and `Staff` entities are
finance-only tags today (`financial_transactions.vendor_id` / `staff_id`); maintenance,
the module that most needs them, cannot reference them at all.

## Problems this fixes

1. `maintenance_tickets.assigned_to` is a bare UUID pointing at `users`. Tickets cannot be
   assigned to a Vendor (external contractor) or Staff (in-house technician).
2. There is no cost anywhere on a ticket and no link from a financial transaction to a
   ticket, so "what did this repair cost?" and "maintenance spend per vendor" are
   unanswerable.
3. `GET /api/v1/vendors` and `/api/v1/staff` are `TENANT_ADMIN`/`SUPER_ADMIN` only, so a
   `PROPERTY_MANAGER` (the dispatcher persona) cannot even list candidates — the web
   ticket page documents this today in a comment in `fetchStaff`.

## Requirements

### Data model (Liquibase changeset 72 — append-only)
- `maintenance_tickets.vendor_id` UUID NULL, FK → `vendors.id`
- `maintenance_tickets.staff_id` UUID NULL, FK → `staff.id`
- `maintenance_tickets.actual_cost` decimal(14,2) NULL
- `financial_transactions.maintenance_ticket_id` UUID NULL, FK → `maintenance_tickets.id`, indexed

### Backend behavior
- **Dispatch:** `PUT /api/v1/tickets/{id}/dispatch` with body `{vendorId}` XOR `{staffId}`
  (`PROPERTY_MANAGER`/`TENANT_ADMIN`/`SUPER_ADMIN`). Exactly one id required. Target must
  exist and be active. Setting one clears the other. `OPEN`/`REOPENED` tickets move to
  `ASSIGNED`. History records action `DISPATCHED` with the assignee name. Dispatch does
  NOT touch `assignedTo` (internal user ownership stays independent). No notification is
  sent — vendors/staff have no user accounts (that bridge is Phase 2).
- **Status rule:** moving to `IN_PROGRESS` is allowed when the ticket has an assigned user
  OR a dispatched vendor/staff (today it requires `assignedTo` only).
- **Cost:** `PUT /api/v1/tickets/{id}/cost` body `{actualCost}` (≥ 0, same three roles).
  History records `COST_RECORDED`.
- **Transaction link:** `FinancialTransaction` gains plain-UUID `maintenanceTicketId`
  (validated to exist on create; deliberately NOT a JPA association — nothing navigates
  transaction→ticket, and a plain column avoids serialization side effects on existing
  finance payloads). Split parents accept it via `CreateSplitTransactionDTO`.
  `GET /api/v1/tickets/{id}/transactions` (`TENANT_ADMIN`/`SUPER_ADMIN` — finance data)
  returns linked transactions, newest first.
- **Pickers:** `GET /api/v1/vendors/picker` and `GET /api/v1/staff/picker`
  (`PROPERTY_MANAGER`/`TENANT_ADMIN`/`SUPER_ADMIN`) return slim active-only
  `{id, nameEn, nameAr, detail}` items (detail = contactPerson / designation). No bank,
  license, salary, or identity fields — this is the PM-safe subset.
- **DTO:** `MaintenanceTicketDTO` gains `vendorId`, `vendorName`, `staffId`, `staffName`,
  `actualCost`.

### Web (Next.js dashboard)
- Ticket detail page (`dashboard/tickets/[id]`): "Dispatch to Vendor…" / "Dispatch to
  Staff…" dropdowns next to "Assign To…", fed by the picker endpoints (now works for PMs);
  Vendor / Technician / Actual Cost detail rows; a "Record Cost" input; an admin-only
  "Maintenance Expenses" card listing linked transactions with a total and a
  "Record Vendor Payment" button that opens `VendorPaymentDialog` prefilled with the
  dispatched vendor and the ticket.
- `VendorPaymentDialog` gains optional `ticketId`/`ticketTitle` props; when set, the
  created transaction carries `maintenanceTicketId` and the dialog shows a linked-ticket
  note (new i18n keys in `web/messages/en.json` + `ar.json`, `Vendors` namespace —
  the ticket page itself is hardcoded English like its existing copy).

## Out of scope (follow-up phases)
- Mobile (manager app) dispatch UI — separate plan once the API is live.
- Gate-vendor ↔ finance-vendor linking, Staff↔User bridge, payroll automation,
  vendor lifecycle (license expiry, categories), vendor/staff DTO & pagination cleanup.
