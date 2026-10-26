import { apiGet, qs } from "@/lib/api/ledger";

/**
 * Report grids (PACT report spec §1). Every report the shared engine produces
 * arrives as one `ReportGridDTO`: the columns, then a flat list of items
 * (bands, opening rows, data rows, subtotals, the report total) in print
 * order. Amounts are decimal strings ("12345.00", "-8000.00") the server has
 * already summed — the client formats them and never adds them up, so the
 * screen cannot disagree with the Excel, PDF and CSV files.
 */

const BASE = "/api/proxy/v1";
const TENANT_LEDGER = "/finance/reports/tenant-ledger";

// ---- grid (mirrors api/dto/report/ReportGridDTO.java) ----

export type GridKind = "TEXT" | "ID_TEXT" | "DATE" | "MONEY" | "BALANCE" | "INT" | "SERIAL" | "PERCENT";

export interface GridColumn {
    key: string;
    label: string;
    kind: GridKind;
    hidden: boolean;
    /** Which row ref the cell drills into: `journal` → refs.entryId, `lease` → refs.leaseId, `cheque` → refs.chequeId. */
    link: "journal" | "lease" | "cheque" | null;
    /**
     * The column's Excel width in characters (`ReportColumn.widthChars`); the screen takes it as the
     * column's minimum width. Optional: a payload without it gets a width by kind.
     */
    widthChars?: number;
}

export interface GridItem {
    type: "band" | "opening" | "row" | "subtotal" | "total";
    level: number;
    band: "ACCOUNT" | "PARTY" | "GROUP" | null;
    label: string | null;
    /** A band re-opened on a later page; the server's label already says "(continued)". */
    continued: boolean;
    /** An opening row that carries the last balance of the previous page rather than the brought-forward one. */
    carriedForward: boolean;
    /** Column key → value: MONEY/BALANCE as decimal strings, DATE as ISO days, the rest as text. Blank cells are absent. */
    cells: Record<string, string>;
    style: "PLAIN" | "HIGHLIGHT";
    refs: Record<string, string>;
}

export interface GridPage {
    /** 0-based. */
    page: number;
    pageCount: number;
    pageRows: number;
    totalRows: number;
    totalGroups: number;
    firstGroup: number;
    lastGroup: number;
    truncated: boolean;
    continuesOnNextPage: boolean;
}

export interface ReportGrid {
    meta: { reportKey: string; title: string; orgName: string; lines: string[]; generated: string; lang: "en" | "ar" };
    columns: GridColumn[];
    items: GridItem[];
    page: GridPage;
}

// ---- R1 Tenant Ledger ----

export interface TenantLedgerQuery {
    from: string;
    to: string;
    propertyId?: string;
    accountIds?: string[];
    renterId?: string;
    leaseId?: string;
    onlyWithBalance?: boolean;
    hideReversed?: boolean;
}

export interface ReceivableAccount {
    id: string;
    code: string;
    name: string;
    nameAr: string | null;
    propertyId: string | null;
    propertyName: string | null;
}

export type FileFormat = "xlsx" | "pdf" | "csv";

/** The filter as query parameters; an empty pick and an unticked switch stay out of the URL. */
function tenantLedgerParams(q: TenantLedgerQuery) {
    return {
        from: q.from,
        to: q.to,
        propertyId: q.propertyId,
        accountIds: q.accountIds?.length ? q.accountIds : undefined,
        renterId: q.renterId,
        leaseId: q.leaseId,
        onlyWithBalance: q.onlyWithBalance ? true : undefined,
        hideReversed: q.hideReversed ? true : undefined,
    };
}

export const reportsApi = {
    tenantLedger: {
        /** One screen page (≤ 200 rows, whole tenant groups where they fit). `page` is 0-based. */
        get: (q: TenantLedgerQuery, page: number, lang: string, signal?: AbortSignal) =>
            apiGet<ReportGrid>(`${TENANT_LEDGER}${qs({ ...tenantLedgerParams(q), page, lang })}`, signal),
        /** The receivable leaves the account picker offers, optionally one property's. */
        accounts: (propertyId?: string) =>
            apiGet<ReceivableAccount[]>(`${TENANT_LEDGER}/accounts${qs({ propertyId })}`),
        /** The whole report as a file — fetched by `ExportMenu`, never opened in a tab. */
        fileUrl: (format: FileFormat, q: TenantLedgerQuery, lang: string) =>
            `${BASE}${TENANT_LEDGER}.${format}${qs({ ...tenantLedgerParams(q), lang })}`,
    },
};
