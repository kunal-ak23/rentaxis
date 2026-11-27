import { chequeApi } from "@/lib/api/leasing";
import { saveResponse } from "@/lib/download";

/**
 * QA-26 (26 Nov): the receipt endpoint answers with `Content-Disposition:
 * attachment`, so `window.open` flashed a tab that closed itself while the PDF
 * went to the downloads bar, and a refusal showed nothing at all. Fetch it and
 * save it in place instead, as the combined receipt does, and hand back the
 * server's reason when it says no.
 *
 * Resolves to null when the file was saved, else the message to show (the
 * server's own, or `fallback`).
 */
export async function downloadChequeReceipt(chequeId: string, fallback: string): Promise<string | null> {
    let res: Response;
    try {
        res = await fetch(chequeApi.receiptUrl(chequeId));
    } catch {
        return fallback;
    }
    if (!res.ok) {
        const body = await res.json().catch(() => null) as { message?: string } | null;
        return body?.message || fallback;
    }
    await saveResponse(res, `receipt-${chequeId.slice(0, 8)}.pdf`);
    return null;
}
