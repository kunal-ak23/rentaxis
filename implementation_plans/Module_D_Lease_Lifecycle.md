# Module D: Lease Lifecycle

## 1. Overview
The Lease Lifecycle module digitally mirrors the real-world tenancy lifecycle. It handles converting an available unit to "Occupied" by drafting a lease, linking a renter, stipulating terms, maintaining actively billed tenancies, handling early renewals/terminations, and finally closing out the lease.

## 2. Architecture & Technical Decisions
- **State Machine Concept:** Lease objects will act as a state machine (`DRAFT -> PENDING_SIGNATURE -> ACTIVE -> NOTICE_GIVEN -> TERMINATED -> EXPIRED -> CLOSED`).
- **Audit/Events Log:** State transitions will trigger immutable records written to `lease_events` to provide absolute historical clarity.
- **Scheduled Transitions:** A Spring `@Scheduled` cron job will run daily at midnight to systematically convert logic-driven states (e.g., shifting ACTIVE leases passing their `end_date` to EXPIRED).

## 3. Data Model
### Core Tables:
- `tenants (Renters)`:
  - `id` (UUID, Primary Key)
  - `tenant_org_id` (UUID)
  - `name`, `email`, `phone` (Strings)
- `leases`:
  - `id` (UUID)
  - `tenant_org_id` (UUID)
  - `unit_id` (UUID)
  - `renter_id` (UUID)
  - `start_date`, `end_date` (Dates)
  - `status` (Enum)
  - `rent_amount`, `deposit_amount` (Numeric)
- `lease_events`:
  - `id`, `lease_id`, `previous_state`, `new_state`, `notes`, `created_at`, `created_by`
- `lease_documents`:
  - `id`, `lease_id`, `document_url`, `type` (e.g., CONTRACT, ADDENDUM, ID_COPY)

## 4. API Specification
- `POST /api/v1/leases` (Drafts a new lease)
- `PUT /api/v1/leases/{id}/activate` (Transitions Draft to Active)
- `POST /api/v1/leases/{id}/terminate` (Files for early exit; applies early move-out penalty calculations)
- `GET /api/v1/leases/{id}/events` (Retrieves full audit timeline of the lease lifecycle)
- `POST /api/v1/leases/{id}/documents` (Upload lease artifacts)

## 5. UI Flows & Interfaces
- **Lease Wizard (Landlord Portal):** Step-by-step UI integrating unit assignment, renter selection, dates, amounts, and document uploading.
- **Lease Dashboard:** Board or segmented list separating leases into categories (Drafts, Active, Expiring Soon, Closed) to visually guide ops teams.
- **Tenant Portal (Read-only):** Tenancy record view showing contract dates, uploaded documents, and rules.

## 6. Security Constraints
- Active leasing constraint: A unit cannot have overlapping `start_date` to `end_date` periods for `ACTIVE` status leases. Attempts must throw domain validation errors.

## 7. Execution Plan (MVP Phase)
- Implement baseline schema, relationships, and state transition logic.
- Deliver CRUD for Renters and Leases, integrating standard document association.
- Deploy daily cron jobs for automated expiration evaluation.
