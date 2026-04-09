# Deduction Attachments & Draft Settlement Design

**Date:** 2026-04-09
**Status:** Approved

## Overview

Add file attachment support (images, videos, PDFs) to individual manual deductions in the lease termination settlement flow. Introduce a DRAFT state for settlements so property managers can build the settlement over time before finalizing. Implement across backend, web frontend, and mobile admin app.

## Requirements

- Each manual deduction can have 0-10 attachments
- Max file size: 250MB per file
- Allowed types: Images (JPEG, PNG, HEIC), Videos (MP4, MOV), PDFs
- Attachments can be added during DRAFT and after FINALIZED
- Attachments can only be deleted while in DRAFT state
- Amounts and deductions are locked after FINALIZED
- Full parity between web and mobile admin app

## Data Model

### New Table: `settlement_deduction_attachments` (Migration 39)

| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID | PK |
| `deduction_id` | UUID | FK -> `lease_settlement_deductions.id`, NOT NULL |
| `tenant_id` | UUID | FK -> `tenants`, NOT NULL |
| `name` | VARCHAR(255) | Original filename |
| `file_url` | VARCHAR(1024) | Azure Blob URL or local serve URL |
| `file_type` | VARCHAR(100) | MIME type |
| `file_size` | BIGINT | Bytes |
| `uploaded_at` | TIMESTAMP | DEFAULT now() |

Index on `deduction_id`. Blob path: `settlement-deductions/{tenantId}/{deductionId}/{uuid}.{ext}`

### Modified Table: `lease_settlements`

- Add column: `status VARCHAR(20) DEFAULT 'DRAFT' NOT NULL`
- New enum `SettlementStatus`: `DRAFT`, `FINALIZED`

## API Design

### New/Modified DTOs

- **`DeductionAttachmentDTO`**: id, name, fileUrl, fileType, fileSize, uploadedAt
- **`SettlementResponseDTO`** updated: each deduction includes `attachments: List<DeductionAttachmentDTO>`
- **`SaveSettlementDTO`**: notes, deductions list (for creating/updating drafts)

### New Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/api/v1/leases/{leaseId}/settlement/draft` | Create or update draft settlement |
| `POST` | `/api/v1/leases/{leaseId}/settlement/finalize` | Finalize draft, terminates lease |
| `POST` | `/api/v1/settlements/deductions/{deductionId}/attachments` | Upload attachment (multipart) |
| `GET` | `/api/v1/settlements/deductions/{deductionId}/attachments` | List attachments |
| `GET` | `/api/v1/settlements/attachments/{id}/download` | Download attachment |
| `DELETE` | `/api/v1/settlements/attachments/{id}` | Delete attachment (DRAFT only) |

### Rules

- Existing `POST /leases/{id}/terminate` remains for quick termination without draft (backwards compatible)
- DRAFT: full edit of deductions + attachments
- FINALIZED: amounts/deductions locked, can still add attachments, cannot delete attachments

## Web UI Design

### Settlement Page (`/dashboard/leases/[id]/settlement`)

Replaces the current modal with a full page for more room. Three sections:

1. **Auto-Calculated Deductions** - read-only with "Auto" badge, editable amounts
2. **Manual Deductions** - expandable cards with category, amount, description, and attachment area
   - Each deduction shows attachment list with thumbnails
   - Drag-and-drop + click-to-browse file upload
   - Upload progress indicator
   - Client-side type/size validation
   - Image lightbox and inline video preview
3. **Summary** - total deductions, refund amount, notes field

### Actions

- **Save Draft** - persists current state, stays on page
- **Finalize & Terminate** - confirmation dialog, then finalizes settlement and terminates lease
- Post-finalization: read-only view, can still add attachments per deduction

## Mobile Admin App Design (Flutter)

### Reworked `LeaseSettlementScreen`

Three modes based on state:

1. **No Settlement (ACTIVE lease)** - shows preview, "Start Settlement Draft" button
2. **DRAFT** - full editing: add/remove deductions, edit amounts, manage attachments
3. **FINALIZED** - read-only, can still add attachments

### Deduction Detail (bottom sheet or sub-screen)

- Category, amount, description fields
- Attachment grid with thumbnails
- Camera, gallery, and file picker buttons
- Full-screen preview (pinch-to-zoom images, video player)
- Swipe-to-delete attachments (DRAFT only)
- Counter: "3/10 attachments"

### Navigation Changes

- `LeaseDetailScreen` -> "Settlement" button -> `LeaseSettlementScreen`
- Remove old basic terminate dialog

### Flutter Packages

- `image_picker` for camera + gallery
- `file_picker` for videos/PDFs
- Built-in video player for preview

## Architecture Notes

- New `DeductionAttachmentService` following `LeaseAttachmentService` pattern (Azure Blob + local fallback)
- New `DeductionAttachmentController` for attachment CRUD
- New `SettlementDeductionAttachment` entity extending `BaseTenantEntity`
- New `SettlementDeductionAttachmentRepository`
- `SettlementStatus` enum added to `LeaseSettlement` entity
- `SettlementService` updated with `saveDraft()` and `finalizeSettlement()` methods
- All operations scoped by `tenant_id` for multi-tenant isolation
