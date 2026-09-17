import type { JournalEntry } from "@/lib/api/ledger";

/**
 * Badge classes for a journal entry's status, shared by the list and the detail
 * page so a reversed voucher looks the same wherever it is shown.
 *
 * It lives here rather than being exported from `journals/page.tsx`: Next.js
 * validates the export surface of a route file, so a page must not also be a
 * module other pages import from.
 */
export function journalStatusClass(status: JournalEntry["status"]): string {
    return status === "POSTED"
        ? "bg-success/10 text-success border-success/20"
        : "bg-input text-muted border-border";
}
