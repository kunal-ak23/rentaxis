# 26/27: listing photos never load (bare blob URLs in a private container)

**Tutorials blocked:** 26 (Publish and manage property listings), 27 (Browse the marketplace and manage a wishlist).

**Found:** 2026-10-05, recording stack (:3004/:8084, built from fcf0160 = main incl. #398/#399), Oasis Crest
Properties, as the Company Admin. Pages: Leasing → Enquiry → Listings (table thumbnails), a listing's Media tab;
the marketplace cards use the same URLs.

## What happens
Every listing photo renders as broken alt text. The `<img>` src is the bare blob URL, e.g.
`http://127.0.0.1:10000/devstoreaccount1/tenant-<tenantId>/listings/<listingId>/<uuid>.jpg`, and an anonymous GET
returns 403 AuthorizationFailure. The blob exists; the container is private.

## Why (code)
- `BlobStorageService.upload` (listing media) returns `blobClient.getBlobUrl()` — no SAS, no serve route.
- `BlobStorageService.getContainerClient` creates `tenant-<id>` with `containerClient.create()` — no public
  access level (PublicAccessType NONE).
- `infra/azure/provision.sh` creates the prod storage account with `--allow-blob-public-access false`, and
  `docker-compose.prod.yml` passes `AZURE_STORAGE_CONNECTION_STRING`, so prod is almost certainly the same:
  listing photos (admin and public marketplace) do not load. (Not verified on prod — recording agents stay local.)

## Expected
Listing photos load for the admin, the Tenant marketplace and the public listing page — e.g. serve listing media
through a public backend route (as branding/assets do), or SAS URLs, rather than bare blob URLs.

## Reproduce
`tutorials/recording-stack.sh restore pre25`; sign in as the Oasis Crest admin; open /en/dashboard/listings.
Local explore script: `tutorials/work/ops/e26c.mjs` (prints each thumbnail src + naturalWidth 0).
