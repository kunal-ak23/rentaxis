"use client";

import VoucherDocumentPage from "@/components/finance/VoucherDocumentPage";

/**
 * F14-40: Supplier Credit Note (PCN) — a vendor's credit against what they
 * billed, shaped like a PISR (vendor, credit-note number, VAT, expense/asset
 * lines) but with the same Allocate panel a BPV uses: on post it settles the
 * vendor's open invoices, or sits unallocated as a credit.
 */
export default function SupplierCreditNotePage() {
    return <VoucherDocumentPage type="PCN" titleKey="supplierCreditNote" descKey="supplierCreditNoteDesc" />;
}
