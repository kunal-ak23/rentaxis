# Module D: Lease Lifecycle

> **STATUS: ⬜ NOT STARTED**
> 
> | Item | Status |
> |------|--------|
> | `tenants (Renters)` entity | ⬜ Not Started |
> | `leases` entity + state machine | ⬜ Not Started |
> | `lease_events` audit trail | ⬜ Not Started |
> | `lease_documents` table | ⬜ Not Started |
> | Lease CRUD endpoints | ⬜ Not Started |
> | Lease activation/termination APIs | ⬜ Not Started |
> | Bilingual PDF contract generation | ⬜ Not Started |
> | Lease Wizard UI | ⬜ Not Started |
> | Renter Portal (bilingual) | ⬜ Not Started |
> | Lease Dashboard (board view) | ⬜ Not Started |
> | Ejari integration fields | ⬜ Not Started |
> | Daily cron for expiration evaluation | ⬜ Not Started |
> 
> *Last reviewed: 2026-02-26*

## 1. Overview
The Lease Lifecycle module digitally mirrors the real-world tenancy lifecycle. It handles leasing terms, early renewals/terminations, and closing out leases. Given the UAE context, it supports capturing details relevant to Ejari (Dubai's regulatory system) and generating bilingual Tenancy Contracts matching local standards.

## 2. Architecture & Technical Decisions
- **State Machine Concept:** Lease objects act as a state machine (`DRAFT -> PENDING_SIGNATURE -> ACTIVE -> NOTICE_GIVEN -> TERMINATED -> EXPIRED -> CLOSED`).
- **Post-Dated Cheques (PDC):** Commonly in the UAE, leases are secured via a bundle of post-dated cheques (e.g., 4 cheques/year). The lifecycle explicitly hooks into generating and collecting these PDC records upon lease activation.
- **Contract Generation:** An integrated PDF generator will output bilingual (English/Arabic) unified tenancy contract documents upon reaching `PENDING_SIGNATURE`.

## 3. Data Model
### Core Tables:
- `tenants (Renters)`:
  - `id` (UUID, Primary Key)
  - `tenant_org_id` (UUID)
  - `name_en`, `name_ar` (Strings)
  - `email`, `phone` (Strings)
  - `primary_language` (Enum: EN, AR)
- `leases`:
  - `id` (UUID)
  - `tenant_org_id` (UUID)
  - `unit_id` (UUID)
  - `renter_id` (UUID)
  - `start_date`, `end_date` (Dates)
  - `status` (Enum)
  - `rent_amount`, `deposit_amount` (Numeric, implied AED unless overridden)
  - `ejari_number` (String, Optional)
  - `payment_terms` (Integer, e.g., 4 meaning 4 cheques/installments)
- `lease_events`:
  - `id`, `lease_id`, `previous_state`, `new_state`, `notes`, `created_at`, `created_by`
- `lease_documents`:
  - `id`, `lease_id`, `document_url`, `type` (e.g., CONTRACT, ADDENDUM, SIGNATURE_DOC)

## 4. API Specification
- `POST /api/v1/leases` (Drafts a new lease)
- `PUT /api/v1/leases/{id}/activate` (Transitions Draft to Active)
- `POST /api/v1/leases/{id}/generate-contract` (Yields a bilingual PDF URL)
- `POST /api/v1/leases/{id}/terminate` (Files for early exit; applies early move-out penalty calculations)
- `GET /api/v1/leases/{id}/events` (Retrieves full audit timeline of the lease lifecycle)

## 5. UI Flows & Interfaces
- **Lease Wizard (Landlord Portal):** Step-by-step UI capturing unit assignment, payment terms (number of cheques), and amounts.
- **Renter Portal (Bilingual):** Review the drafted lease, view the bilingual PDF, and accept terms. The portal renders entirely in English or Arabic based on the renter's `primary_language`.
- **Lease Dashboard:** Board separating leases into categories, alerting heavily on expired Ejari or upcoming renewals.

## 6. Security Constraints
- Active leasing constraint: A unit cannot have overlapping `start_date` to `end_date` periods for `ACTIVE` status leases.

## 7. Execution Plan (MVP Phase)
- Implement baseline schema, integrating ejari/cheque term fields.
- Setup PDF generation service handling dual LTR/RTL font rendering via standard tools (e.g., Flying Saucer or Puppeteer).
- Deploy daily cron jobs for automated expiration evaluation.
