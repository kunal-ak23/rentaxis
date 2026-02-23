# Module F: Tenant KYC & Document Vault

## 1. Overview
Securely manage the collection, review, and expiration tracking of renter Know Your Customer (KYC) documents. In the UAE context, emphasis is placed heavily on Emirates ID and valid Passports/Visas.

## 2. Architecture & Technical Decisions
- **Storage Abstraction:** Create a `DocumentStorageService` using `LocalFileSystemStorageService` initially, with planned migration to `AzureBlobStorageService`.
- **Targeted Expiry Tracking:** UAE Residency Visas and Emirates IDs expire independently of lease dates. Background jobs explicitly track the `expiry_date` tagged to KYC artifacts, sending localized warning emails to the tenant 30 days prior.
- **AI Preparedness:** Structuring the database to accommodate OCR data for automatic Emirates ID reading (Name, Number, Expiry) in a later optional phase.

## 3. Data Model
### Core Tables:
- `kyc_documents`: 
  - `id` (UUID), `renter_id` (UUID)
  - `tenant_org_id` (UUID)
  - `document_type` (Enum: EMIRATES_ID_FRONT, EMIRATES_ID_BACK, PASSPORT, RESIDENCY_VISA, TRADE_LICENSE)
  - `status` (Enum: PENDING, APPROVED, REJECTED)
  - `expiry_date` (Date, Extremely Critical)
  - `id_number` (String, e.g., '784-XXXX-XXXXXXX-X' for EID)
  - `file_path` (String, internal relative path)

## 4. API Specification
- `POST /api/v1/documents/upload` (Multipart form-data)
- `GET /api/v1/documents/{id}/url` (Get temporary access link)
- `PATCH /api/v1/documents/{id}/status` (Approve/Reject)
- `GET /api/v1/documents/expiring` (List expiring/expired documents across the portfolio)

## 5. UI Flows & Interfaces
- **Tenant Portal:** Bilingual, mobile-friendly upload zone for ID front/back. Provides clear instructions in Arabic/English on how to photograph the Emirates ID.
- **Landlord Portal:** A work queue of `PENDING` approval KYC documents. Split-screen UI: Document viewer on the left, Approve/Reject/Notes on the right, mapped directly against the Emirates ID card design.

## 6. Security Constraints
- Strict file typing allowed (PDF, JPG, PNG).
- Max file size limits strictly enforced at the Nginx reverse-proxy to prevent DoS via storage exhaustion.
- Cross-tenant data viewing prevention on the document stream endpoint.

## 7. Execution Plan (MVP Phase)
- Implement `DocumentStorageService` using local volume.
- Build Upload and Read endpoints with `tenant_id` constraints and specialized EID enums.
- Create localized reminder email templates.
