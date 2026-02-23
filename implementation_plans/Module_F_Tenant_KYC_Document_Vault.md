# Module F: Tenant KYC & Document Vault

## 1. Overview
Securely manage the collection, review, and expiration tracking of renter Know Your Customer (KYC) documents and lease attachments.

## 2. Architecture & Technical Decisions
- **Storage Abstraction:** Create a `DocumentStorageService` interface in Spring Boot. Implement `LocalFileSystemStorageService` initially mapped to a Docker volume for MVP cost savings. Later, seamlessly inject `AzureBlobStorageService`.
- **Pre-signed Access:** Files are never served statically. Next.js requests a short-lived Pre-Signed URL for rendering PDF/images, or streams it directly through a secure Spring Boot endpoint that evaluates `tenant_id` context.
- **Expiration Cron Timer:** A daily background job finds documents expiring within the `org_settings` reminder threshold and queues notifications.

## 3. Data Model
### Core Tables:
- `kyc_documents`: 
  - `id` (UUID), `renter_id` (UUID)
  - `tenant_org_id` (UUID)
  - `document_type` (Enum: PASSPORT, EMIRATES_ID, TRADE_LICENSE)
  - `status` (Enum: PENDING, APPROVED, REJECTED)
  - `expiry_date` (Date)
  - `file_path` (String, internal relative path)
- `document_metadata`:
  - Extracted metadata JSON for future AI capability (Module Optional).

## 4. API Specification
- `POST /api/v1/documents/upload` (Multipart form-data)
- `GET /api/v1/documents/{id}/url` (Get temporary access link)
- `PATCH /api/v1/documents/{id}/status` (Approve/Reject)
- `GET /api/v1/documents/expiring` (List expiring/expired documents)

## 5. UI Flows & Interfaces
- **Tenant Portal:** Simple, mobile-friendly upload zone for ID front/back. Badge indicator (Green/Red) showing their current KYC status.
- **Landlord Portal:** A work queue of `PENDING` approval KYC documents. Split-screen UI: Document viewer on the left, Approve/Reject/Notes on the right.

## 6. Security Constraints
- Strict file typing allowed (PDF, JPG, PNG).
- Max file size limits strictly enforced at the Nginx reverse-proxy and Spring Boot level to prevent DoS via storage exhaustion.
- Cross-tenant data viewing prevention on the document stream endpoint.

## 7. Execution Plan (MVP Phase)
- Implement `DocumentStorageService` using local volume.
- Build Upload and Read endpoints with `tenant_id` constraints.
- Create Landlord Approval Queue UI.
