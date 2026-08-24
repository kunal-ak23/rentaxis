# Production tenant artifact-cleanup audit

Date: 2026-08-25
Source baseline: exact deployed `origin/main` `54b03b0` (production run 32783019338 succeeded)
Scope: read-only source and schema audit; no production storage or tenant state was changed

## Why this is a recording gate

The production E2E suite creates a disposable tenant and deletes it at the end. Database deletion is tenant-scoped and contract PDFs are already captured before deletion and removed after commit. Other uploaded objects can survive because deleting their database rows does not delete the referenced Azure blob or local file. The real cheque-image scenario proves this gap is reachable.

Final tutorial recording must wait until exact non-contract cleanup is explicitly approved, implemented, tested, deployed, and exercised by the disposable production run.

## Baseline deletion behavior before this cleanup PR

`LandlordOrgService.deleteTenant`:

1. Requires an exact `confirmName` match.
2. Captures `lease_documents.document_url` while those rows still exist.
3. Deletes every row with a literal `tenant_id` column, then the `landlord_org` row, in one transaction.
4. Schedules contract-document cleanup only after that transaction commits.

This correctly protects live database rows from pointing at a file removed by a rolled-back deletion. It deliberately documents that every other external store needs its own cleanup hook.

The existing `BlobStorageService.delete(tenantId, blobPath)` deletes one exact path from `tenant-{tenantId}` and never creates or deletes a container. Contract cleanup independently validates the expected tenant container or configured local root before deleting one exact object.

## Complete persisted artifact inventory

| Capability | Owning row / columns | Storage shape | Current tenant-delete cleanup |
|---|---|---|---|
| Generated contracts | `lease_documents.document_url` | `tenant-{tenantId}/contracts/...` or configured contracts root | Implemented after commit in #104 |
| Cheque extraction/upload | `payment_schedules.cheque_image_blob_path`, `cheque_image_url` | `tenant-{tenantId}/cheques/{uuid}.{ext}` | Missing |
| Listing media | `unit_listing_media.blob_path`, `url`, joined through `unit_listings.tenant_id` | `tenant-{tenantId}/listings/{listingId}/{uuid}.{ext}` | Missing |
| Walk-in visitor photo | `gate_visitor_profiles.photo_blob_path`, `photo_url` | `tenant-{tenantId}/gate-visitors/{profileId}/{uuid}.{ext}` | Missing |
| Gate-pass photo reference | `gate_passes.guest_photo_blob_path`, `guest_photo_url` | Usually the same gate-visitor object; may duplicate the profile reference | Missing; must deduplicate |
| Lease attachments | `lease_attachments.file_url` | `tenant-{tenantId}/lease-docs/{uuid}_{name}` or `rentaxis.assets.storage-path/lease-docs/...` | Missing |
| Ticket attachments | `ticket_attachments.file_url` | `tenant-{tenantId}/ticket-attachments/{uuid}_{name}` or assets root equivalent | Missing |
| Settlement evidence | `settlement_deduction_attachments.file_url` | `tenant-{tenantId}/settlement-deductions/{deductionId}/{uuid}_{name}` or assets root equivalent | Missing |
| Organization logo/stamp | `landlord_org.logo_url`, `stamp_image_url` | `tenant-{tenantId}` or `shared` Azure container; local assets root in non-Azure mode | Missing |
| Promotion media URL | `promo_businesses.logo_url`, `promo_ads.background_image_url` | May be external or an AssetController URL | Missing for storage-owned URLs only |
| Listing OG image URL | `unit_listings.og_image_url` | May duplicate listing media or be external | Missing for storage-owned URLs only; deduplicate |

Excluded URL fields are `promo_ads.cta_url`, `payment_gateways.sdk_js_url`, and `app_versions.store_url`: these are navigation/provider URLs, not uploaded tenant artifacts.

## Required capture queries

The implementation must capture references before database deletion. Directly tenanted tables can use `WHERE tenant_id = ?`. Listing media requires an ownership join because `unit_listing_media` has no `tenant_id` column:

```sql
SELECT m.blob_path, m.url
FROM unit_listing_media m
JOIN unit_listings l ON l.id = m.listing_id
WHERE l.tenant_id = ?
```

The landlord row must be captured by `landlord_org.id = ?`, not `tenant_id`. All captured values must be copied into an immutable, deduplicated cleanup plan before the purge begins.

## Required deletion contract

Approval should authorize only the following behavior:

- Run external cleanup only after the database transaction commits.
- Delete exact captured objects; never enumerate or delete a whole container, prefix, directory, account, or workspace.
- For Azure paths, accept only the configured storage account and either:
  - container exactly `tenant-{tenantId}`, or
  - the `shared` container when the exact URL came from a row owned by the deleted tenant and no surviving database row resolves to that same normalized container/object path (even when SAS/query strings differ).
- For stored `blob_path` values, reject blank, absolute, traversal (`..`), backslash, or leading-slash paths and allow only the capability prefixes inventoried above.
- For local URLs, decode only `/api/v1/assets/serve/...`, resolve beneath the normalized configured `rentaxis.assets.storage-path`, and require the resolved file to remain under that root.
- For local contract files, retain the separate normalized `rentaxis.contracts.storage-path` guard already implemented.
- Ignore external HTTPS URLs instead of attempting to delete them.
- Deduplicate identical objects across URL and blob-path columns, especially gate visitor/pass photos and listing cover/OG references.
- Treat a missing object as success (idempotency).
- Log a structured per-object result without exposing storage credentials.
- Do not make tenant deletion fail after its database transaction has committed. Persist or otherwise surface cleanup failures so an operator can retry the same exact plan.

## Minimum automated evidence

The cleanup PR is not ready to deploy until tests prove:

1. A database rollback schedules and performs no external deletion.
2. A committed tenant delete invokes each exact captured, deduplicated artifact once.
3. Paths in another tenant container are skipped.
4. Shared-container/local objects are skipped when still referenced by a surviving tenant.
5. External URLs, traversal paths, absolute paths, and unknown prefixes are skipped.
6. Missing objects are idempotent successes.
7. One storage failure does not prevent remaining exact objects from being attempted and is reported for retry.
8. The existing tenant database cascade and contract cleanup tests remain green.

## Production acceptance sequence

After the product recording gates #102, #108, and #111 deploy:

1. Deploy the approved exact-artifact cleanup.
2. Run #96 against one uniquely named disposable TEST-E2E tenant.
3. Capture the artifact manifest before tenant deletion.
4. Confirm every database row for the tenant is gone.
5. Confirm every exact created blob/local file is absent.
6. Confirm a known object belonging to another tenant is still present.
7. Confirm the disposable tenant and its storage references cannot be found.
8. Only then begin final tutorial capture.
