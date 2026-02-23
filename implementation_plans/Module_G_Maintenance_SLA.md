# Module G: Maintenance & SLA

## 1. Overview
A specialized helpdesk allowing Tenants and Landlords to raise maintenance tickets, route them to appropriate internal staff or external vendors, and track adherence to Resolution SLAs. Supports cross-language communication.

## 2. Architecture & Technical Decisions
- **Event-Driven Workflow:** State changes (`OPENED -> ASSIGNED -> IN_PROGRESS -> RESOLVED -> CLOSED`) publish Spring ApplicationEvents.
- **Cross-Language Collaboration:** Tenants may raise tickets in Arabic, while maintenance vendors might use English or another language (like Hindi/Urdu, though MVP focuses on EN/AR UI). The system stores textual descriptions universally (UTF-8) but displays the UI chrome surrounding it localized to the active user's preference.
- **Attachment Handling:** Re-uses the `DocumentStorageService` established in Module F for ticket photo/video evidence.

## 3. Data Model
### Core Tables:
- `maintenance_requests`:
  - `id` (UUID), `unit_id` (UUID, nullable for common areas)
  - `reported_by` (UUID), `tenant_org_id` (UUID)
  - `category` (Enum: AC, PLUMBING, ELECTRICAL, CARPENTRY, GENERAL)
  - `title`, `description` (Text)
  - `status` (Enum), `priority` (Enum: LOW, MEDIUM, CRITICAL)
- `maintenance_activity`:
  - `id`, `request_id`, `actor_id`, `action`, `timestamp`
- `maintenance_attachments`:
  - `id`, `request_id`, `file_path`

## 4. API Specification
- `POST /api/v1/maintenance` (Tenant/Landlord: Raise new request)
- `PUT /api/v1/maintenance/{id}/assign` (Assign to internal staff or Vendor)
- `PUT /api/v1/maintenance/{id}/status` (Update status)
- `POST /api/v1/maintenance/{id}/comments` (Add log/message)

## 5. UI Flows & Interfaces
- **Tenant Portal:** Bilingual form to report issues mapped to their specific Unit. Camera integration capability for mobile browsers.
- **Landlord Portal (Helpdesk):** Kanban board or searchable ticket list. Drawer/Modal for viewing full ticket context and timeline.
- **Vendor Work Order (PDF/Email):** System generates an automated translated summary (English + Arabic) dispatch email to external vendors ensuring clarity.

## 6. Security Constraints
- Tenants can strictly only fetch `maintenance_requests` opened for their active `unit_id`.
- Vendors can strictly only view `maintenance_requests` explicitly assigned to their vendor ID.

## 7. Execution Plan (MVP Phase)
- Setup baseline ticket CRUD, categories, and status enumeration.
- Build activity log pattern to track conversation and transition history.
- Ensure all notification templates associated with SLA changes are built in both `en` and `ar`.
