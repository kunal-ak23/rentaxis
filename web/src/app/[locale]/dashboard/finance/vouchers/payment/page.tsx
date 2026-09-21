"use client";

import VoucherDocumentPage from "@/components/finance/VoucherDocumentPage";

/**
 * Bank / Cash Payment Voucher (BPV) — money leaving a bank or cash account.
 * The same document shell as the purchase invoice, with the one difference the
 * server insists on: a payment line carries no VAT.
 */
export default function PaymentVoucherPage() {
    return <VoucherDocumentPage type="BPV" titleKey="paymentVoucher" descKey="paymentVoucherDesc" />;
}
