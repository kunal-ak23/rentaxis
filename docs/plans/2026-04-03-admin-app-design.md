# RentAxis Admin App — Design Document

**Date:** 2026-04-03
**Status:** Approved

## Overview

Evolve `mobile/apps/manager` into **RentAxis Admin** — a full-featured mobile admin app for TENANT_ADMIN and PROPERTY_MANAGER roles. Reuses `rentaxis_core` shared package (theme, API services, widgets, providers). Adds the ~40% of web admin capabilities currently missing from the manager app.

## Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| App strategy | Evolve manager app (Option A) | Manager already has 60% of admin features; separate app = duplicate maintenance |
| Target roles | TENANT_ADMIN + PROPERTY_MANAGER | Core field-ops roles; SUPER_ADMIN is desktop-only work |
| CRUD scope | Full CRUD for field-relevant features; read-only for config/setup | Complex setup stays on desktop; field work gets full capability |
| Navigation | 5-tab bottom nav, "More" as hub | Primary workflows stay one tap away; secondary features organized in More |

## Navigation Structure

**5-tab bottom nav:**
1. **Dashboard** — KPIs, alerts, recent activity, quick actions
2. **Properties** — Property list → detail → units, contacts, buildings (read-only)
3. **Leases** — Lease list → detail → documents, attachments, penalties, settlements
4. **Payments** — Collection workflow, aging report, receipt download
5. **More** — Hub screen with sections:
   - **People**: Staff (full CRUD), Renters (existing)
   - **Finance**: Accounts (existing), Bank Accounts (view), Vendors (view+create), Transactions (existing), Reports (view)
   - **Tickets**: Existing ticket management
   - **Settings**: Rent Settings (view), Gateway Config (view), Account Mappings (view)
   - **Profile**: Existing profile management

## New Screens (14)

### Staff Management (full CRUD)
- `staff_screen.dart` — List with search, filter by property
- `staff_detail_screen.dart` — View/edit staff, property assignments

### Vendors (view + create)
- `vendors_screen.dart` — Vendor list with search
- `vendor_detail_screen.dart` — View vendor info, create new

### Bank Accounts (view only)
- `bank_accounts_screen.dart` — List bank accounts, filter by property

### Financial Reports (view only)
- `finance_reports_screen.dart` — Report hub: trial balance, VAT, property/unit reports, vendor ledger
- `report_detail_screen.dart` — Rendered report view

### Lease Enhancements (sub-screens from lease detail)
- `lease_documents_screen.dart` — Upload/view/delete lease documents & attachments
- `lease_penalties_screen.dart` — View penalties, waive action
- `lease_settlement_screen.dart` — Settlement preview, initiate settlement

### Settings (view only)
- `settings_hub_screen.dart` — Settings sections list
- `rent_settings_screen.dart` — View rent collection config per property
- `gateway_config_screen.dart` — View payment gateway status
- `account_mappings_screen.dart` — View GL account mappings

## Screens to Enhance (existing)

- **`more_screen.dart`** — Transform into rich hub with icon sections (People, Finance, Tickets, Settings)
- **`property_detail_screen.dart`** — Add contacts tab, buildings section (read-only)
- **`lease_detail_screen.dart`** — Add tabs/sections for documents, penalties, settlement
- **`dashboard_screen.dart`** — Add quick action links to new features

## New Core Services (in `rentaxis_core`)

Add to `rentaxis_core/lib/api/services/`:
- `staff_service.dart` — CRUD staff, filter by property
- `vendor_service.dart` — CRUD vendors
- `bank_account_service.dart` — List/get bank accounts
- `building_service.dart` — List buildings by property
- `settings_service.dart` — Rent settings, gateway config, account mappings
- `lease_attachment_service.dart` — Upload/download/delete lease attachments
- `penalty_service.dart` — Get/waive/recalculate penalties
- `settlement_service.dart` — Preview/get settlements
- `report_service.dart` — All financial report endpoints

## Branding Changes

- App name: "RentAxis Admin" (was "RentAxis Manager")
- Package name update in Android/iOS configs
- Same theme from `rentaxis_core` (teal/gold/navy palette, Cinzel + JosefinSans fonts)
- Same animations and widgets from shared package

## Out of Scope (Phase 1)

- OCR / camera-to-data features (cheque scanning, KYC upload, rental agreement parsing) — Phase 2
- Bulk CSV import/export on mobile
- Bulk unit creation
- SUPER_ADMIN panel
- Offline mode
- Arabic RTL (follows core package support)

## API Endpoints Required

All endpoints already exist in the backend. No backend changes needed for Phase 1.

### Staff
- `GET/POST /api/v1/staff` — List/create staff
- `GET/PUT/DELETE /api/v1/staff/{id}` — Get/update/delete staff
- `GET /api/v1/staff/by-property/{propertyId}` — Filter by property

### Vendors
- `GET/POST /api/v1/vendors` — List/create vendors
- `GET /api/v1/vendors/{id}` — Get vendor detail

### Bank Accounts
- `GET /api/v1/bank-accounts` — List bank accounts
- `GET /api/v1/bank-accounts/{id}` — Get detail
- `GET /api/v1/bank-accounts/by-property/{propertyId}` — Filter by property

### Buildings
- `GET /api/v1/buildings/property/{propertyId}` — List buildings

### Financial Reports
- `GET /api/v1/finance/reports/trial-balance` — Trial balance
- `GET /api/v1/finance/reports/vat-return` — VAT return
- `GET /api/v1/finance/reports/property/{propertyId}` — Property report
- `GET /api/v1/finance/reports/unit/{unitId}` — Unit report
- `GET /api/v1/finance/reports/organisation` — Org summary
- `GET /api/v1/finance/ledger/vendor/{vendorId}` — Vendor ledger

### Lease Attachments
- `GET/POST /api/v1/leases/{leaseId}/attachments` — List/upload
- `DELETE /api/v1/leases/{leaseId}/attachments/{id}` — Delete
- `GET /api/v1/leases/{leaseId}/attachments/{id}/download` — Download

### Penalties
- `GET /api/v1/leases/{leaseId}/penalties` — List penalties
- `PUT /api/v1/leases/{leaseId}/penalties/{id}/waive` — Waive
- `POST /api/v1/leases/{leaseId}/penalties/recalculate` — Recalculate

### Settlements
- `GET /api/v1/leases/{id}/settlement/preview` — Preview
- `GET /api/v1/leases/{id}/settlement` — Get settlement

### Settings
- `GET /api/v1/rent-settings/{propertyId}` — Rent settings
- `GET /api/v1/gateway-config` — Gateway config
- `GET /api/v1/gateway-config/gateways` — Available gateways
- `GET /api/v1/finance/account-mappings` — Account mappings
- `GET /api/v1/finance/account-mappings/natures` — Transaction natures

### Property Contacts
- `GET/POST /api/v1/properties/{propertyId}/contacts` — List/create
- `PUT/DELETE /api/v1/properties/{propertyId}/contacts/{id}` — Update/delete
