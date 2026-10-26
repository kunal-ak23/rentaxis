import type { GridItem, ReportGrid } from "@/lib/api/reports";

/** One `ReportGridDTO` page in the tenant ledger's shape, synthetic data only. */
function item(type: GridItem["type"], level: number, over: Partial<GridItem> = {}): GridItem {
    return { type, level, band: null, label: null, continued: false, carriedForward: false, cells: {}, style: "PLAIN", refs: {}, ...over };
}

export const grid: ReportGrid = {
    meta: {
        reportKey: "tenant-ledger",
        title: "Tenant Ledger",
        orgName: "Sample Org",
        lines: ["Period: 01/09/2026 to 30/09/2027"],
        generated: "Generated 26/10/2026 10:05 by Test User",
        lang: "en",
    },
    columns: [
        { key: "docDate", label: "Doc Date", kind: "DATE", hidden: false, link: null },
        { key: "particular", label: "Particular", kind: "TEXT", hidden: false, link: null },
        { key: "debit", label: "Debit", kind: "MONEY", hidden: false, link: null },
        { key: "credit", label: "Credit", kind: "MONEY", hidden: false, link: null },
        { key: "balance", label: "Balance", kind: "BALANCE", hidden: false, link: null },
        { key: "docNo", label: "Doc No", kind: "ID_TEXT", hidden: false, link: "journal" },
    ],
    items: [
        item("band", 1, { band: "ACCOUNT", label: "Rent Receivable - Sample Tower" }),
        item("band", 2, { band: "PARTY", label: "Tenant Name : Tenant A" }),
        item("opening", 2, { label: "Balance brought forward", cells: { balance: "0.00" } }),
        item("row", 2, {
            cells: { docDate: "2026-09-16", particular: "Advance Rent - Sample Tower", debit: "60000.00", balance: "60000.00", docNo: "TCO-26/1" },
            refs: { entryId: "e1" },
        }),
        item("row", 2, { cells: { credit: "68000.00", balance: "-8000.00", docNo: "PDR-26/1" } }),
        // 999.00 is deliberately not the rows' debit: the table prints the server's figure, it never sums.
        item("subtotal", 2, { label: "Sub Total", cells: { debit: "999.00", credit: "68000.00", balance: "-8000.00" } }),
        item("total", 0, { label: "REPORT TOTAL", cells: { debit: "999.00", credit: "68000.00", balance: "-8000.00" } }),
    ],
    page: {
        page: 0, pageCount: 4, pageRows: 200, totalRows: 500, totalGroups: 41,
        firstGroup: 0, lastGroup: 15, truncated: false, continuesOnNextPage: false,
    },
};

export { item };
