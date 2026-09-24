import { throwIfNotOk } from "@/lib/api/facilities";
import { apiGet, apiSend, qs } from "@/lib/api/ledger";

/**
 * Bank statement import and matching (finance-ops spec §3). Types read off
 * `api/dto/bank/BankRecDTOs.java`, routes from `api/BankReconciliationController.java`.
 * Amounts are signed: + credit (money in), − debit. Dates are `yyyy-MM-dd`.
 */

const BASE = "/api/proxy/v1";
const ROOT = "/finance/bank-reconciliation";

export type Leaf = { id: string; code: string; name: string; propertyId: string | null };

export type BankAccountRow = {
    id: string;
    bankName: string;
    accountNumber: string;
    iban: string | null;
    currency: string | null;
    bankTrn: string | null;
    active: boolean;
    leaves: Leaf[];
    needsLeaf: boolean;
    lastImportAt: string | null;
    lastImportFile: string | null;
    lastLineDate: string | null;
    unmatchedLines: number;
    hasProfile: boolean;
    /** The lock (spec §4): postings on the leaves up to this day are refused. */
    reconciledThrough: string | null;
    recStartDate: string | null;
    draftReconciliationId: string | null;
    latestFinalizedReconciliationId: string | null;
};

export const STATEMENT_FIELDS = ["txnDate", "valueDate", "description", "reference", "debit", "credit", "amount",
    "amountSign", "balance", "chequeNo"] as const;
export type StatementField = (typeof STATEMENT_FIELDS)[number];
export type AmountMode = "SPLIT" | "SIGNED" | "DRCR_FLAG";

export type Profile = {
    fileKind: "CSV" | "XLSX";
    sheetName?: string | null;
    headerRow: number;
    firstDataRow: number;
    csvDelimiter?: string | null;
    dateFormats?: string[] | null;
    columns: Partial<Record<StatementField, string>>;
    amountMode: AmountMode;
    chequeNoPattern?: string | null;
    matchWindowDays?: number | null;
    /** "." or ","; the other one groups thousands. */
    decimalSeparator?: "." | "," | null;
};

export type PreviewRow = {
    fileRow: number;
    txnDate: string;
    valueDate: string | null;
    description: string;
    reference: string | null;
    chequeNo: string | null;
    amount: number;
    balance: number | null;
    duplicate: boolean;
};

export type ImportStatus = "PROFILE_REQUIRED" | "INVALID" | "PREVIEW" | "IMPORTED" | "ALREADY_IMPORTED";

export type ImportResult = {
    status: ImportStatus;
    reason: string | null;
    grid: string[][];
    sheetNames: string[];
    sheetName: string | null;
    fileKind: "CSV" | "XLSX";
    missingColumns: string[];
    errors: string[];
    warnings: string[];
    linesRead: number;
    linesNew: number;
    linesDuplicate: number;
    firstDate: string | null;
    lastDate: string | null;
    openingBalance: number | null;
    closingBalance: number | null;
    order: "FILE" | "REVERSED" | "DATE" | null;
    rows: PreviewRow[];
    importId: string | null;
};

export type ImportRow = {
    id: string;
    fileName: string;
    linesRead: number;
    linesNew: number;
    linesDuplicate: number;
    firstDate: string | null;
    lastDate: string | null;
    openingBalance: number | null;
    closingBalance: number | null;
    warnings: string[];
    importedAt: string;
    deletable: boolean;
};

export type MatchStatus = "SUGGESTED" | "CONFIRMED" | "UNDONE";

export type StatementLine = {
    id: string;
    seq: number;
    txnDate: string;
    valueDate: string | null;
    description: string;
    reference: string | null;
    chequeNo: string | null;
    amount: number;
    runningBalance: number | null;
    matchId: string | null;
    matchStatus: MatchStatus | null;
};

export type BookItem = {
    journalLineId: string;
    entryId: string;
    entryNumber: string;
    docType: string;
    entryDate: string;
    narration: string | null;
    accountId: string;
    accountName: string;
    counterAccount: string | null;
    amount: number;
    chequeNo: string | null;
    matchId: string | null;
    matchStatus: MatchStatus | null;
    reversalOfId: string | null;
    reversedById: string | null;
};

export type Match = {
    id: string;
    method: "AUTO_CHEQUE" | "AUTO_REFERENCE" | "AUTO_AMOUNT_DATE" | "AUTO_GROUP" | "MANUAL" | "CREATED" | "CONTRA";
    status: MatchStatus;
    confidence: "HIGH" | "MEDIUM" | null;
    statementLineIds: string[];
    journalLineIds: string[];
    statementTotal: number;
    bookTotal: number;
    createdAt: string;
    confirmedAt: string | null;
    /** A CREATED match's documents. */
    createdDocTypes: string[];
    /** The date "undo and reverse" defaults to; null when this match's entries are not reversed from here. */
    reverseOnDefault: string | null;
    /** Opening items (spec §4) on the book side of this match. */
    openingItemIds?: string[];
};

export type Workspace = {
    bankAccountId: string;
    leaves: Leaf[];
    needsLeaf: boolean;
    statementLines: StatementLine[];
    bookItems: BookItem[];
    matches: Match[];
    /** The first reconciliation's outstanding items, matchable like book items. */
    openingItems?: OpeningItem[];
    reconciledThrough?: string | null;
};

// ---------------------------------------------------------------- §4 reconciliation

/** Signed book-side: + a deposit in transit, − an unpresented payment. */
export type OpeningItem = {
    id: string;
    bankAccountId: string;
    itemDate: string;
    description: string;
    reference: string | null;
    chequeNo: string | null;
    amount: number;
    matchId: string | null;
    matchStatus: MatchStatus | null;
};

export type OpeningItemInput = { itemDate: string; description: string; reference?: string | null; chequeNo?: string | null; amount: number };

export type RecStatus = "DRAFT" | "FINALIZED" | "REOPENED";

export type ReconciliationRow = {
    id: string;
    periodFrom: string;
    periodTo: string;
    status: RecStatus;
    statementClosing: number | null;
    bookBalance: number | null;
    difference: number | null;
    createdAt: string;
    finalizedAt: string | null;
    finalizedByName: string | null;
    reopenedAt: string | null;
    reopenedByName: string | null;
    reopenReason: string | null;
};

export type RecItem = {
    kind: "JOURNAL" | "OPENING" | "STATEMENT";
    id: string;
    date: string | null;
    document: string | null;
    narration: string | null;
    chequeNo: string | null;
    amount: number;
    /** A cheque cleared by hand that no statement line shows yet. */
    withoutEvidence: boolean;
};

export type RecCheckCode = "CONTINUITY" | "UNRECORDED" | "DIFFERENCE" | "SUGGESTED" | "NOT_FUTURE" | "OPENING_ITEMS" | "CHAIN";
export type RecCheck = { code: RecCheckCode; ok: boolean; message: string };

/** BankRecDTOs.Reconciliation: live while DRAFT, the finalize snapshot afterwards. */
export type Reconciliation = {
    id: string;
    bankAccountId: string;
    bankLabel: string;
    bankName: string;
    ibanMasked: string;
    leaves: Leaf[];
    periodFrom: string;
    periodTo: string;
    status: RecStatus;
    first: boolean;
    statementOpening: number | null;
    statementClosing: number | null;
    closingTyped: boolean;
    statementMovement: number;
    bookBalance: number;
    bookBalanceAtStart: number | null;
    openingItemsTotal: number;
    depositsInTransit: number;
    unpresentedPayments: number;
    bookedAfterPeriod: number;
    unrecordedCredits: number;
    unrecordedDebits: number;
    adjustedBank: number | null;
    adjustedBook: number;
    difference: number | null;
    depositsInTransitItems: RecItem[];
    unpresentedItems: RecItem[];
    bookedAfterItems: RecItem[];
    unrecordedItems: RecItem[];
    withoutEvidenceCount: number;
    matchedByMethod: Record<string, number>;
    checks: RecCheck[];
    canFinalize: boolean;
    preparedAt: string | null;
    preparedByName: string | null;
    finalizedAt: string | null;
    finalizedByName: string | null;
    reopenedAt: string | null;
    reopenedByName: string | null;
    reopenReason: string | null;
};

export type ReconciliationInput = {
    periodFrom?: string | null;
    periodTo: string;
    statementOpening?: number | null;
    statementClosing?: number | null;
};

/** The failing finalize preconditions, in the server's order: what the Finalize button's tooltip lists. */
export function failingChecks(r: Pick<Reconciliation, "checks">): RecCheck[] {
    return r.checks.filter(c => !c.ok);
}

export type Candidate = {
    id: string;
    kind: string;
    label: string;
    amount: number;
    date: string | null;
    chequeNo: string | null;
    propertyId: string | null;
    status: string;
    preselected: boolean;
};

export type LineCandidates = {
    statementLineId: string;
    clear: Candidate[];
    receive: Candidate[];
    bounce: Candidate[];
    present: Candidate[];
    suspenseBalance: number;
    bankTrnSet: boolean;
    leaves: Leaf[];
};

export type ActionResult = { matchId: string | null; journalEntryIds: string[]; entryNumbers: string[] };
export type PostKind = "CHARGE" | "INTEREST" | "SUSPENSE" | "OTHER";

export type ChequeEvidence = {
    chequeId: string;
    state: "CONFIRMED" | "NOT_ON_STATEMENT" | "CASH" | "SUSPENSE";
    statementDate: string | null;
};

export const bankRecApi = {
    accounts: () => apiGet<BankAccountRow[]>(`${ROOT}/bank-accounts`),
    setLeaves: (id: string, accountIds: string[]) => apiSend<Leaf[]>("PUT", `${ROOT}/bank-accounts/${id}/ledgers`, { accountIds }),
    setBankTrn: (id: string, bankTrn: string | null) =>
        apiSend<{ id: string; bankTrn: string | null }>("PUT", `${ROOT}/bank-accounts/${id}/bank-trn`, { bankTrn }),
    profile: async (id: string): Promise<Profile | null> => {
        const res = await fetch(`${BASE}${ROOT}/bank-accounts/${id}/profile`);
        await throwIfNotOk(res);
        return res.status === 204 ? null : res.json();
    },
    saveProfile: (id: string, p: Profile) => apiSend<Profile>("PUT", `${ROOT}/bank-accounts/${id}/profile`, p),
    /** `profile`: the wizard's unsaved mapping. `dryRun`: parse and check only. No Content-Type: the browser writes the boundary. */
    importFile: async (id: string, file: File, opts: { profile?: Profile | null; dryRun?: boolean } = {}) => {
        const fd = new FormData();
        fd.append("file", file);
        if (opts.profile) fd.append("profile", JSON.stringify(opts.profile));
        fd.append("dryRun", String(!!opts.dryRun));
        const res = await fetch(`${BASE}${ROOT}/bank-accounts/${id}/imports`, { method: "POST", body: fd });
        await throwIfNotOk(res);
        return res.json() as Promise<ImportResult>;
    },
    imports: (id: string) => apiGet<ImportRow[]>(`${ROOT}/bank-accounts/${id}/imports`),
    deleteImport: (importId: string) => apiSend<void>("DELETE", `${ROOT}/imports/${importId}`),
    workspace: (id: string, q: { from?: string; to?: string; state?: "UNMATCHED" | "SUGGESTED" | "ALL" }) =>
        apiGet<Workspace>(`${ROOT}/bank-accounts/${id}/workspace${qs(q)}`),
    linesCsvUrl: (id: string, q: { from?: string; to?: string }) => `${BASE}${ROOT}/bank-accounts/${id}/lines.csv${qs(q)}`,
    autoMatch: (id: string, q: { from?: string; to?: string }) =>
        apiSend<{ proposed: number; byMethod: Record<string, number> }>("POST", `${ROOT}/bank-accounts/${id}/auto-match${qs(q)}`),
    match: (body: { statementLineIds: string[]; journalLineIds: string[]; openingItemIds?: string[] }) =>
        apiSend<Match>("POST", `${ROOT}/matches`, body),
    confirm: (matchId: string) => apiSend<Match>("POST", `${ROOT}/matches/${matchId}/confirm`),
    confirmAll: (bankAccountId: string, q: { from?: string; to?: string }) =>
        apiSend<{ confirmed: number }>("POST", `${ROOT}/matches/confirm${qs({ bankAccountId, confidence: "HIGH", ...q })}`),
    undo: (matchId: string, opts: { reverseCreated?: boolean; reverseOn?: string; reason?: string } = {}) =>
        apiSend<Match>("DELETE", `${ROOT}/matches/${matchId}${qs(opts)}`),
    candidates: (lineId: string) => apiGet<LineCandidates>(`${ROOT}/lines/${lineId}/candidates`),
    clearCheques: (statementLineIds: string[], chequeIds: string[]) =>
        apiSend<ActionResult>("POST", `${ROOT}/lines/actions/clear-cheques`, { statementLineIds, chequeIds }),
    receive: (body: { statementLineId: string; chequeId: string; fromSuspense?: boolean; bankLeafId?: string | null }) =>
        apiSend<ActionResult>("POST", `${ROOT}/lines/actions/receive`, body),
    bounce: (body: { statementLineId: string; chequeId: string; reason?: string | null }) =>
        apiSend<ActionResult>("POST", `${ROOT}/lines/actions/bounce`, body),
    present: (body: { statementLineId: string; issuedChequeId: string }) =>
        apiSend<ActionResult>("POST", `${ROOT}/lines/actions/present`, body),
    post: (body: { statementLineIds: string[]; kind: PostKind; vatIncluded?: boolean; accountId?: string | null;
                   propertyId?: string | null; bankLeafId?: string | null; shared?: boolean; narration?: string | null;
                   net?: number | null; vat?: number | null }) =>
        apiSend<ActionResult>("POST", `${ROOT}/lines/actions/post`, body),
    reconciliations: (id: string) => apiGet<ReconciliationRow[]>(`${ROOT}/bank-accounts/${id}/reconciliations`),
    createReconciliation: (id: string, body: ReconciliationInput) =>
        apiSend<Reconciliation>("POST", `${ROOT}/bank-accounts/${id}/reconciliations`, body),
    reconciliation: (recId: string) => apiGet<Reconciliation>(`${ROOT}/reconciliations/${recId}`),
    updateReconciliation: (recId: string, body: ReconciliationInput) =>
        apiSend<Reconciliation>("PUT", `${ROOT}/reconciliations/${recId}`, body),
    discardReconciliation: (recId: string) => apiSend<void>("DELETE", `${ROOT}/reconciliations/${recId}`),
    finalizeReconciliation: (recId: string) => apiSend<Reconciliation>("POST", `${ROOT}/reconciliations/${recId}/finalize`),
    reopenReconciliation: (recId: string, reason: string) =>
        apiSend<Reconciliation>("POST", `${ROOT}/reconciliations/${recId}/reopen`, { reason }),
    reconciliationPdfUrl: (recId: string, lang: "en" | "ar") => `${BASE}${ROOT}/reconciliations/${recId}.pdf${qs({ lang })}`,
    reconciliationCsvUrl: (recId: string) => `${BASE}${ROOT}/reconciliations/${recId}.csv`,
    openingItems: (id: string) => apiGet<OpeningItem[]>(`${ROOT}/bank-accounts/${id}/opening-items`),
    addOpeningItem: (id: string, body: OpeningItemInput) => apiSend<OpeningItem>("POST", `${ROOT}/bank-accounts/${id}/opening-items`, body),
    deleteOpeningItem: (id: string, itemId: string) => apiSend<void>("DELETE", `${ROOT}/bank-accounts/${id}/opening-items/${itemId}`),
    chequeEvidence: (ids: string[]) =>
        ids.length === 0 ? Promise.resolve([] as ChequeEvidence[]) : apiGet<ChequeEvidence[]>(`/cheques/bank-evidence${qs({ ids })}`),
};

/**
 * The charge split the server would post (BankStatementPostingService.chargeSplit):
 * one line whole, or VAT = 5/105 of it with vatIncluded; several lines only with
 * the net and VAT stated. `error` says why the server would refuse.
 */
export function chargeSplit(debits: number[], vatIncluded: boolean, bankTrnSet: boolean,
                            stated?: { net: number; vat: number } | null): { net: number; vat: number; gross: number; error: string | null } {
    const cents = (n: number) => Math.round(Math.abs(n) * 100);
    const gross = debits.reduce((s, d) => s + cents(d), 0);
    if (stated) {
        const net = Math.round(stated.net * 100);
        const vat = Math.round(stated.vat * 100);
        let error: string | null = null;
        if (net + vat !== gross) error = "sum";
        else if (vat > 0 && !bankTrnSet) error = "trn";
        else if (vat > Math.round(net * 0.05) + 1) error = "rate";
        return { net: net / 100, vat: vat / 100, gross: gross / 100, error };
    }
    if (debits.length > 1) return { net: gross / 100, vat: 0, gross: gross / 100, error: "split" };
    if (!bankTrnSet || !vatIncluded) return { net: gross / 100, vat: 0, gross: gross / 100, error: null };
    const vat = Math.round((gross * 5) / 105);
    return { net: (gross - vat) / 100, vat: vat / 100, gross: gross / 100, error: null };
}

/** dd/MM/yyyy for a `yyyy-MM-dd`. */
export function dmy(iso: string | null | undefined): string {
    if (!iso) return "";
    const [y, m, d] = iso.slice(0, 10).split("-");
    return `${d}/${m}/${y}`;
}

/** Σ in cents, so the footer's 0.00 is exact. */
export function sumCents(values: number[]): number {
    return values.reduce((s, v) => s + Math.round(v * 100), 0);
}
