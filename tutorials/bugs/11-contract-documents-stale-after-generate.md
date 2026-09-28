# Contract page: a generated contract is not listed under Contract documents until the page is reloaded

- Found by: tutorial 11 refresh (batch 3), 2026-09-29.
- Stack: local recording stack (web :3004 / backend :8084, built from c5532cf), organisation Oasis Crest Properties.
- Who: Company Admin (Noura Al Suwaidi).
- Blocks: tutorial 11 "Generate, review and sign a tenancy contract". Its proof asserts the list and fails there; nothing was
  recorded around it.

## Repro

1. Open a DRAFT tenancy contract (the scenario drafts A-201 for Rajesh Kumar; the seeded M-1501 draft behaves the same).
2. Attachments tab, Contract section: select **Preview Contract**, then **Confirm & Save** in the preview.
3. The preview closes. The header updates (status **Pending Signature**, badge **Contract No. N**), and More actions now
   offers **Download Contract**.
4. The Contract section still shows only *Preview Contract* and *View settlement*. The **CONTRACT DOCUMENTS** list with the
   new *Contract* row (date, Download) is missing.
5. Reload the page: the list appears.

## Why

`ContractDocuments` (`web/src/components/leases/ContractDocuments.tsx`) fetches `GET /leases/{id}/documents` once, in a
`useEffect` keyed on `leaseId`. `handleConfirmContract` in `web/src/app/[locale]/dashboard/leases/[id]/page.tsx` closes the
preview and calls `loadLease()`, which does not change `leaseId`, so the list is never re-fetched. It also returns `null`
while it holds no CONTRACT/EXECUTED_COPY document, which is why nothing at all shows.

Likely fix: re-fetch after generation (for example a `refreshKey` prop, or `key={lease.contractDocumentId}` on
`<ContractDocuments>`), with a component test that confirms a generated contract appears without a reload.

## How to verify / unblock

`bash tutorials/work/batch3/proof.sh 11` (or `bash tutorials/record-local.sh 11`) on a stack rebuilt with the fix: the
proof's "Pending Signature" scene waits for `contract-documents` right after Confirm & Save. With a reload patched in, the
rest of the scenario (tenant accepts in the portal, admin sees *Accepted by tenant*) was proved twice on 2026-09-29, so 11
is proof-ready once the list refreshes. The scenario drafts its own contract off camera and clears leftovers, so it needs no
snapshot.

## Seen alongside (not blocking)

- The same contract page offers **Delete draft** in More actions for a *Pending Signature* contract, but the backend refuses:
  `400 "Only DRAFT leases can be deleted"`.
- **Preview Contract** (and its Confirm & Save) is still offered on an Active contract, where generation is refused
  ("Contract can only be generated for DRAFT or PENDING_SIGNATURE leases"). **View settlement** is offered on a draft.
- The generated PDF is titled "LEASE AGREEMENT" with "Lease Start Date" / "Lease Period Information" (binding terminology
  says tenancy contract; the PDF is backend-rendered, outside `terminology.test.ts`).
