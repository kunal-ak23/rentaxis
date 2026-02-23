# Module G: Maintenance & SLA

## 1. Overview
A specialized helpdesk allowing Tenants and Landlords to raise maintenance tickets, route them to appropriate internal staff or external vendors, and track adherence to Resolution SLAs.

## 2. Architecture & Technical Decisions
- **Event-Driven Workflow:** State changes (`OPENED -> ASSIGNED -> IN_PROGRESS -> RESOLVED -> CLOSED`) publish Spring ApplicationEvents, decoupling the core logic from side-effects (like dispatching an email to the Vendor).
- **Time Tracking:** Capturing precise Timestamps upon state entry to power SLA reporting analytics later.
- **Attachment Handling:** Re-uses the `DocumentStorageService` established in Module F for ticket photo/video evidence.

## 3. Data Model
### Core Tables:
- `maintenance_requests`:
  - `id` (UUID), `unit_id` (UUID, nullable for common areas)
  - `reported_by` (UUID), `tenant_org_id` (UUID)
  - `title`, `description` (Text)
  - `status` (Enum), `priority` (Enum: LOW, MEDIUM, CRITICAL)
- `maintenance_activity`:
  - `id`, `request_id`, `actor_id`, `action` (e.g., Status Changed, Comment Added), `timestamp`
- `maintenance_attachments`:
  - `id`, `request_id`, `file_path`

## 4. API Specification
- `POST /api/v1/maintenance` (Tenant/Landlord: Raise new request)
- `PUT /api/v1/maintenance/{id}/assign` (Assign to internal staff or Vendor)
- `PUT /api/v1/maintenance/{id}/status` (Update status)
- `POST /api/v1/maintenance/{id}/comments` (Add log/message)

## 5. UI Flows & Interfaces
- **Tenant Portal:** Form to report issues mapped to their specific Unit. Camera integration capability for mobile browsers.
- **Landlord Portal (Helpdesk):** Kanban board or searchable ticket list. Drawer/Modal for viewing full ticket context and timeline.
- **Vendor Portal/View:** Simplified view to update assigned ticket status to `RESOLVED` and optionally attach an invoice.

## 6. Security Constraints
- Tenants can strictly only fetch `maintenance_requests` opened for their active `unit_id`.
- Vendors can strictly only view `maintenance_requests` explicitly assigned to their vendor ID.

## 7. Execution Plan (MVP Phase)
- Setup baseline ticket CRUD and status enumeration.
- Build activity log pattern to track conversation and transition history.
- Deliver Tenant creation UI and Landlord assignment UI.
