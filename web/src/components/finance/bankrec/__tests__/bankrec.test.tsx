import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import ar from "../../../../../messages/ar.json";
import en from "../../../../../messages/en.json";

/**
 * Finance → Bank reconciliation (finance-ops spec §3): the mapping wizard, the
 * workspace's Σ footer and pane order in both directions, and the
 * create-from-line dialogs' posting previews. Figures follow §4's September
 * example for EI 0123.
 */

const session = vi.hoisted(() => ({ role: "ACCOUNTANT" }));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: session.role } } }) }));
vi.mock("next/navigation", () => ({ useParams: () => ({ id: "ba-1" }) }));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
    useRouter: () => ({ push: vi.fn() }),
}));
vi.mock("@/components/finance/AccountPicker", () => ({
    loadAccounts: async () => [
        { id: "leaf-m", code: "A-02-02-001", name: "Emirates Islamic - Marina Tower", accountType: "ASSET", accountSubType: "BANK", group: false, active: true },
        { id: "leaf-p", code: "A-02-02-002", name: "Emirates Islamic - Palm Court", accountType: "ASSET", accountSubType: "BANK", group: false, active: true },
        { id: "exp", code: "D-02-004", name: "Sundry", accountType: "EXPENSE", accountSubType: "OTHER_EXPENSE", group: false, active: true },
        { id: "vend", code: "B-01-04-001", name: "Gulf AC", accountType: "LIABILITY", accountSubType: "PAYABLE", group: false, active: true },
    ],
}));

import { StatementImportDialog, guessColumns } from "../StatementImportDialog";
import { BankReconciliationWorkspace } from "../BankReconciliationWorkspace";
import { LineActionDialog, actionsFor } from "../LineActionDialog";
import BankReconciliationPage from "@/app/[locale]/dashboard/finance/bank-reconciliation/page";
import { chargeSplit, type StatementLine } from "@/lib/api/bankRec";

type Route = { match: string; method?: string; body: unknown; status?: number };
let calls: { method: string; url: string; body: unknown }[] = [];

function stubFetch(routes: Route[] | ((url: string, init?: RequestInit) => Route | undefined)) {
    calls = [];
    vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
        const u = String(url);
        const method = init?.method ?? "GET";
        const body = init?.body instanceof FormData ? Object.fromEntries(init.body.entries())
            : init?.body ? JSON.parse(String(init.body)) : undefined;
        calls.push({ method, url: u, body });
        const r = typeof routes === "function" ? routes(u, init)
            : routes.find(x => u.includes(x.match) && (!x.method || x.method === method));
        return new Response(JSON.stringify(r ? r.body : []), { status: r?.status ?? 200, headers: { "Content-Type": "application/json" } });
    }));
}

function renderIn(locale: "en" | "ar", ui: React.ReactElement) {
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === "ar" ? ar : en}>
            <div dir={locale === "ar" ? "rtl" : "ltr"}>{ui}</div>
        </NextIntlClientProvider>,
    );
}

const line = (id: string, description: string, amount: number, extra: Partial<StatementLine> = {}): StatementLine => ({
    id, seq: 1, txnDate: "2026-09-15", valueDate: "2026-09-15", description, reference: null, chequeNo: null, amount,
    runningBalance: null, matchId: null, matchStatus: null, ...extra,
});

const LEAVES = [
    { id: "leaf-m", code: "A-02-02-001", name: "Emirates Islamic - Marina Tower", propertyId: "p1" },
    { id: "leaf-p", code: "A-02-02-002", name: "Emirates Islamic - Palm Court", propertyId: "p2" },
];

const GRID = [
    ["Synthetic ENBD-style export"],
    ["Transaction Date", "Value Date", "Narration", "Reference", "Debit", "Credit", "Running Balance"],
    ["30/09/2026", "30/09/2026", "CREDIT INTEREST", "", "", "120.00", "328,017.50"],
];

const importResult = (status: string, extra: object = {}) => ({
    status, reason: null, grid: GRID, sheetNames: [], sheetName: null, fileKind: "CSV", missingColumns: [], errors: [],
    warnings: [], linesRead: 1, linesNew: 1, linesDuplicate: 0, firstDate: "2026-09-30", lastDate: "2026-09-30",
    openingBalance: 327897.5, closingBalance: 328017.5, order: "FILE",
    rows: [{ fileRow: 3, txnDate: "2026-09-30", valueDate: "2026-09-30", description: "CREDIT INTEREST", reference: null,
        chequeNo: null, amount: 120, balance: 328017.5, duplicate: false }], importId: null, ...extra,
});

beforeEach(() => { session.role = "ACCOUNTANT"; });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe("the mapping wizard", () => {
    it("guesses the columns from the header, previews with the unsaved mapping, then saves and imports", async () => {
        expect(guessColumns(GRID[1])).toEqual({
            txnDate: "Transaction Date", valueDate: "Value Date", description: "Narration", reference: "Reference",
            debit: "Debit", credit: "Credit", balance: "Running Balance",
        });
        let saved = false;
        stubFetch((u, init) => {
            if (u.endsWith("/profile") && init?.method === "PUT") { saved = true; return { match: "", body: {} }; }
            if (u.endsWith("/profile")) return { match: "", body: null, status: 204 };
            if (u.includes("/imports")) {
                const fd = init?.body as FormData;
                const dry = fd.get("dryRun") === "true";
                if (!saved && !fd.get("profile")) return { match: "", body: importResult("PROFILE_REQUIRED") };
                if (dry) return { match: "", body: importResult("PREVIEW") };
                return { match: "", body: importResult("IMPORTED", { importId: "imp-1" }) };
            }
            return undefined;
        });
        const onImported = vi.fn();
        renderIn("en", <StatementImportDialog bankAccountId="ba-1" bankName="Emirates Islamic" onClose={() => {}} onImported={onImported} />);
        const file = new File(["x"], "september.csv", { type: "text/csv" });
        fireEvent.change(screen.getByTestId("import-file"), { target: { files: [file] } });
        expect(await screen.findByTestId("mapping-wizard")).toBeInTheDocument();
        // The header row was found (row 2) and the guesses applied.
        expect(screen.getByTestId("map-header-row")).toHaveValue(2);
        expect(screen.getByTestId("map-txnDate")).toHaveValue("Transaction Date");
        expect(screen.getByTestId("map-credit")).toHaveValue("Credit");
        expect(screen.getByTestId("map-date-format")).toHaveValue("dd/MM/yyyy");
        // Live preview from a dry run carrying the unsaved mapping.
        expect(await screen.findByTestId("import-preview")).toHaveTextContent("CREDIT INTEREST");
        const previewCall = calls.find(c => c.url.includes("/imports") && c.body && (c.body as Record<string, unknown>).profile);
        expect(JSON.parse((previewCall!.body as Record<string, string>).profile).columns.debit).toBe("Debit");
        // Unmapping a required column blocks saving.
        fireEvent.change(screen.getByTestId("map-debit"), { target: { value: "" } });
        expect(screen.getByTestId("map-save")).toBeDisabled();
        fireEvent.change(screen.getByTestId("map-debit"), { target: { value: "Debit" } });
        fireEvent.click(screen.getByTestId("map-save"));
        fireEvent.click(await screen.findByTestId("import-commit"));
        expect(await screen.findByTestId("import-done")).toHaveTextContent("Imported: 1 new, 0 already imported.");
        expect(onImported).toHaveBeenCalled();
    });

    it("shows every row error and imports nothing when the balance breaks", async () => {
        stubFetch([{ match: "/profile", body: null, status: 204 },
            { match: "/imports", body: importResult("INVALID", { errors: ["Row 38: balance 101,250.00 does not follow 98,000.00 + 2,050.00"], rows: [] }) }]);
        renderIn("en", <StatementImportDialog bankAccountId="ba-1" bankName="EI" onClose={() => {}} onImported={() => {}} />);
        fireEvent.change(screen.getByTestId("import-file"), { target: { files: [new File(["x"], "s.csv")] } });
        expect(await screen.findByTestId("import-errors")).toHaveTextContent("Row 38: balance 101,250.00 does not follow 98,000.00 + 2,050.00");
        expect(screen.queryByTestId("import-commit")).not.toBeInTheDocument();
    });
});

const WS = {
    bankAccountId: "ba-1", leaves: LEAVES, needsLeaf: false,
    statementLines: [
        line("a", "CHQ DEP 000451", 50000, { txnDate: "2026-09-03", valueDate: "2026-09-03", chequeNo: "000451" }),
        line("b", "CLG CREDIT 2 CHQS", 50000, { txnDate: "2026-09-04", valueDate: "2026-09-04", matchId: "m1", matchStatus: "SUGGESTED" }),
        line("d1", "SERVICE CHARGE", -50),
        line("d2", "VAT ON SERVICE CHARGE", -2.5),
    ],
    bookItems: [
        { journalLineId: "j1", entryId: "e1", entryNumber: "CRT-26/102", docType: "CRT", entryDate: "2026-09-03", narration: "118822",
          accountId: "leaf-p", accountName: "Palm", counterAccount: "PDCs", amount: 25000, chequeNo: "118822", matchId: "m1",
          matchStatus: "SUGGESTED", reversalOfId: null, reversedById: null },
        { journalLineId: "j2", entryId: "e2", entryNumber: "CRT-26/103", docType: "CRT", entryDate: "2026-09-03", narration: "118823",
          accountId: "leaf-p", accountName: "Palm", counterAccount: "PDCs", amount: 25000, chequeNo: "118823", matchId: "m1",
          matchStatus: "SUGGESTED", reversalOfId: null, reversedById: null },
        { journalLineId: "j3", entryId: "e3", entryNumber: "JV-26/9", docType: "JV", entryDate: "2026-09-03", narration: "Cash",
          accountId: "leaf-m", accountName: "Marina", counterAccount: "Capital", amount: 50000, chequeNo: null, matchId: null,
          matchStatus: null, reversalOfId: null, reversedById: null },
    ],
    matches: [{ id: "m1", method: "AUTO_GROUP", status: "SUGGESTED", confidence: "MEDIUM", statementLineIds: ["b"],
        journalLineIds: ["j1", "j2"], statementTotal: 50000, bookTotal: 50000, createdAt: "2026-09-30T10:00:00Z", confirmedAt: null,
        createdDocTypes: [], reverseOnDefault: null }],
};

describe("the same file again", () => {
    it("says it was already imported and offers nothing to commit", async () => {
        stubFetch([{ match: "/imports", body: { ...importResult("ALREADY_IMPORTED", { rows: [], linesNew: 0, linesDuplicate: 7 }),
            reason: "This file was already imported on 30/09/2026 (september.csv); nothing was imported" } }]);
        renderIn("en", <StatementImportDialog bankAccountId="ba-1" bankName="EI" onClose={() => {}} onImported={() => {}} />);
        fireEvent.change(screen.getByTestId("import-file"), { target: { files: [new File(["x"], "s.csv")] } });
        expect(await screen.findByTestId("already-imported")).toHaveTextContent("already imported on 30/09/2026");
        expect(screen.queryByTestId("import-commit")).not.toBeInTheDocument();
    });
});

describe("the workspace", () => {
    it("sums both sides and enables Match only at a zero difference", async () => {
        stubFetch([{ match: "/workspace", body: WS }, { method: "POST", match: "/matches", body: WS.matches[0] }]);
        renderIn("en", <BankReconciliationWorkspace bankAccountId="ba-1" />);
        await screen.findByTestId("sl-CHQ DEP 000451");
        // The suggestion is tinted and badged on both sides, with Confirm and Reject.
        expect(within(screen.getByTestId("sl-CLG CREDIT 2 CHQS")).getByTestId("badge-1")).toBeInTheDocument();
        expect(within(screen.getByTestId("bi-CRT-26/102")).getByTestId("badge-1")).toBeInTheDocument();
        expect(screen.getByTestId("suggestion-1")).toHaveTextContent("Deposit group");
        fireEvent.click(screen.getByTestId("sel-sl-CHQ DEP 000451"));
        expect(screen.getByTestId("sum-statement")).toHaveTextContent("50,000.00");
        expect(screen.getByTestId("match")).toBeDisabled();
        fireEvent.click(screen.getByTestId("sel-sl-SERVICE CHARGE"));
        fireEvent.click(screen.getByTestId("sel-bi-JV-26/9"));
        expect(screen.getByTestId("sum-difference")).toHaveTextContent("-50.00");
        expect(screen.getByTestId("match")).toBeDisabled();
        fireEvent.click(screen.getByTestId("sel-sl-SERVICE CHARGE"));
        expect(screen.getByTestId("sum-difference")).toHaveTextContent("0.00");
        fireEvent.click(screen.getByTestId("match"));
        await waitFor(() => expect(calls.some(c => c.method === "POST" && c.url.endsWith("/matches"))).toBe(true));
        const body = calls.find(c => c.method === "POST" && c.url.endsWith("/matches"))!.body as Record<string, string[]>;
        expect(body).toEqual({ statementLineIds: ["a"], journalLineIds: ["j3"] });
    });

    it("puts the statement pane at the start in both directions", async () => {
        stubFetch([{ match: "/workspace", body: WS }]);
        renderIn("ar", <BankReconciliationWorkspace bankAccountId="ba-1" />);
        await screen.findByTestId("sl-CHQ DEP 000451");
        const panes = screen.getByTestId("panes");
        expect(panes.closest("[dir]")).toHaveAttribute("dir", "rtl");
        // First in the grid's flow — which RTL lays out on the right, the reading start.
        expect(panes.children[0]).toBe(screen.getByTestId("pane-statement"));
        expect(panes.children[1]).toBe(screen.getByTestId("pane-books"));
        expect(screen.getByTestId("pane-statement")).toHaveTextContent(ar.BankRec.statement);
        // Amounts stay LTR inside the RTL page.
        expect(within(screen.getByTestId("sl-SERVICE CHARGE")).getByText("-50.00").closest("bdi")).toHaveAttribute("dir", "ltr");
    });
});

describe("undo and reverse", () => {
    const confirmed = (id: string, desc: string, docs: string[], reverseOnDefault: string | null) => ({
        line: line(id, desc, -10, { matchId: `m-${id}`, matchStatus: "CONFIRMED" }),
        match: { id: `m-${id}`, method: "CREATED", status: "CONFIRMED", confidence: null, statementLineIds: [id],
            journalLineIds: [], statementTotal: -10, bookTotal: -10, createdAt: "2026-09-30T10:00:00Z", confirmedAt: null,
            createdDocTypes: docs, reverseOnDefault },
    });

    it("is offered only where the server can reverse, and asks for the date first", async () => {
        const bnk = confirmed("c1", "SERVICE CHARGE", ["BNK"], "2026-09-15");
        const cbr = confirmed("c2", "RTN CHQ", ["CBR"], null);
        stubFetch([{ match: "/workspace", body: { ...WS, statementLines: [bnk.line, cbr.line], bookItems: [], matches: [bnk.match, cbr.match] } },
            { method: "DELETE", match: "/matches/", body: bnk.match }]);
        renderIn("en", <BankReconciliationWorkspace bankAccountId="ba-1" />);
        await screen.findByTestId("sl-SERVICE CHARGE");
        expect(screen.queryByTestId("undo-reverse-m-c2")).not.toBeInTheDocument();
        expect(screen.getByTestId("undo-m-c2")).toBeInTheDocument();
        fireEvent.click(screen.getByTestId("undo-reverse-m-c1"));
        expect(calls.some(c => c.method === "DELETE")).toBe(false);
        expect(screen.getByTestId("reverse-on")).toHaveValue("2026-09-15");
        fireEvent.change(screen.getByTestId("reverse-on"), { target: { value: "2026-09-20" } });
        fireEvent.click(screen.getByTestId("reverse-go"));
        await waitFor(() => expect(calls.some(c => c.method === "DELETE")).toBe(true));
        const url = calls.find(c => c.method === "DELETE")!.url;
        expect(url).toContain("reverseCreated=true");
        expect(url).toContain("reverseOn=2026-09-20");
    });
});

describe("create-from-line dialogs", () => {
    it("offers what fits the line's direction", () => {
        expect(actionsFor([line("x", "C", 10)])).toEqual(["clear", "receive", "interest", "suspense", "other"]);
        expect(actionsFor([line("x", "D", -10)])).toEqual(["bounce", "present", "charge", "other"]);
        expect(actionsFor([line("x", "D", -10), line("y", "V", -1)])).toEqual(["charge", "other"]);
        // Several lines only with the split stated; VAT at most 5% of the net.
        expect(chargeSplit([-50, -2.5], false, true).error).toBe("split");
        expect(chargeSplit([-50, -2.5], false, true, { net: 50, vat: 2.5 })).toEqual({ net: 50, vat: 2.5, gross: 52.5, error: null });
        expect(chargeSplit([-25, -25], false, true, { net: 25, vat: 25 }).error).toBe("rate");
        expect(chargeSplit([-25, -25], false, true, { net: 40, vat: 2 }).error).toBe("sum");
        expect(chargeSplit([-50, -2.5], false, false, { net: 50, vat: 2.5 }).error).toBe("trn");
        expect(chargeSplit([-52.5], true, true)).toEqual({ net: 50, vat: 2.5, gross: 52.5, error: null });
        expect(chargeSplit([-52.5], true, false)).toEqual({ net: 52.5, vat: 0, gross: 52.5, error: null });
    });

    it("previews the charge with its VAT, and posts one BNK for both lines on the chosen leaf", async () => {
        stubFetch([
            { match: "/candidates", body: { statementLineId: "d1", clear: [], receive: [], bounce: [], present: [],
                suspenseBalance: 0, bankTrnSet: true, leaves: LEAVES } },
            { method: "POST", match: "/lines/actions/post", body: { matchId: "m9", journalEntryIds: ["e9"], entryNumbers: ["BNK-26/7"] } },
        ]);
        const onDone = vi.fn();
        renderIn("en", <LineActionDialog lines={[line("d1", "SERVICE CHARGE", -50), line("d2", "VAT ON SERVICE CHARGE", -2.5)]}
                                          onClose={() => {}} onDone={onDone} />);
        const preview = await screen.findByTestId("posting-preview");
        await screen.findByTestId("bank-leaf");
        await waitFor(() => expect(preview).toHaveTextContent("Dr Charge50.00"));
        expect(preview).toHaveTextContent("Dr Input VAT2.50");
        expect(preview).toHaveTextContent("52.50");
        // Two leaves: pick one first.
        expect(screen.getByTestId("action-submit")).toBeDisabled();
        fireEvent.change(screen.getByTestId("bank-leaf"), { target: { value: "leaf-m" } });
        expect(preview).toHaveTextContent("Cr Emirates Islamic - Marina Tower52.50");
        fireEvent.click(screen.getByTestId("action-submit"));
        await waitFor(() => expect(onDone).toHaveBeenCalled());
        expect(calls.find(c => c.url.includes("/lines/actions/post"))!.body).toMatchObject({
            statementLineIds: ["d1", "d2"], kind: "CHARGE", bankLeafId: "leaf-m", net: 50, vat: 2.5,
        });
    });

    it("refuses a two-line charge whose VAT would be more than 5% before sending it", async () => {
        stubFetch([{ match: "/candidates", body: { statementLineId: "x", clear: [], receive: [], bounce: [], present: [],
            suspenseBalance: 0, bankTrnSet: true, leaves: [LEAVES[0]] } }]);
        renderIn("en", <LineActionDialog lines={[line("x", "SMS ALERT FEE", -25), line("y", "SMS ALERT FEE", -25)]}
                                          onClose={() => {}} onDone={() => {}} />);
        // Not prefilled as 25 + 25 VAT: the whole 50 is the charge until the user says otherwise.
        expect(await screen.findByTestId("charge-net")).toHaveValue("50.00");
        expect(screen.getByTestId("charge-vat")).toHaveValue("0.00");
        fireEvent.change(screen.getByTestId("charge-net"), { target: { value: "25" } });
        fireEvent.change(screen.getByTestId("charge-vat"), { target: { value: "25" } });
        expect(screen.getByTestId("charge-error")).toHaveTextContent(en.BankRec.chargeError_rate);
        expect(screen.getByTestId("action-submit")).toBeDisabled();
    });

    it("pre-selects the cheque by number, and says when there is no bank TRN", async () => {
        stubFetch([{ match: "/candidates", body: { statementLineId: "e", clear: [], receive: [], bounce: [],
            present: [{ id: "ic-1", kind: "ISSUED_CHEQUE", label: "000031 Gulf AC", amount: 20000, date: "2026-09-28",
                chequeNo: "000031", propertyId: null, status: "ISSUED", preselected: true }],
            suspenseBalance: 0, bankTrnSet: false, leaves: [LEAVES[0]] } },
            { method: "POST", match: "/lines/actions/present", body: { matchId: "m", journalEntryIds: [], entryNumbers: ["BPC-26/3"] } }]);
        renderIn("en", <LineActionDialog lines={[line("e", "CHQ 000031 PRESENTED", -20000, { chequeNo: "000031" })]}
                                          initial="present" onClose={() => {}} onDone={() => {}} />);
        await waitFor(() => expect(screen.getByTestId("cand-000031")).toBeChecked());
        expect(screen.getByTestId("posting-preview")).toHaveTextContent("Dr PDC payable20,000.00");
        fireEvent.click(screen.getByTestId("action-submit"));
        await waitFor(() => expect(calls.some(c => c.url.includes("/lines/actions/present"))).toBe(true));
        expect(calls.find(c => c.url.includes("/present"))!.body).toEqual({ statementLineId: "e", issuedChequeId: "ic-1" });
        fireEvent.click(screen.getByTestId("tab-charge"));
        expect(screen.getByTestId("no-trn")).toHaveTextContent(en.BankRec.noTrnNoVat);
    });
});

describe("the list page", () => {
    it("is for finance roles only", () => {
        session.role = "PROPERTY_MANAGER";
        stubFetch([]);
        renderIn("en", <BankReconciliationPage />);
        expect(screen.getByTestId("bankrec-denied")).toHaveTextContent(en.BankRec.accessDenied);
        expect(calls).toHaveLength(0);
    });

    it("lists each bank account with its ledger accounts, in Arabic too", async () => {
        stubFetch([{ match: "/bank-accounts", body: [{ id: "ba-1", bankName: "Emirates Islamic", accountNumber: "0123",
            iban: "AE07", currency: "AED", bankTrn: null, active: true, leaves: LEAVES, needsLeaf: false, lastImportAt: null,
            lastImportFile: "september.csv", lastLineDate: "2026-09-30", unmatchedLines: 2, hasProfile: true }] }]);
        renderIn("ar", <BankReconciliationPage />);
        const row = await screen.findByTestId("bankrec-row-0123");
        expect(row).toHaveTextContent("Emirates Islamic - Palm Court");
        expect(row).toHaveTextContent("30/09/2026");
        expect(screen.getByText(ar.BankRec.title)).toBeInTheDocument();
    });
});
