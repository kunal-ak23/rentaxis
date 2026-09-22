"use client";

import VoucherDocumentPage from "@/components/finance/VoucherDocumentPage";

/**
 * Purchase / Service Invoice (PISR) — what a supplier billed, with VAT per line.
 * Posting raises the vendor's payable; the whole document lives in
 * `VoucherDocumentPage` and `VoucherForm`, which the payment voucher shares.
 */
export default function PurchaseInvoicePage() {
    return <VoucherDocumentPage type="PISR" titleKey="purchaseInvoice" descKey="purchaseInvoiceDesc" />;
}
