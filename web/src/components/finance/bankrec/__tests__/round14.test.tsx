import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { NextIntlClientProvider, useTranslations } from "next-intl";
import ar from "../../../../../messages/ar.json";
import en from "../../../../../messages/en.json";

/**
 * Round-14 production findings on bank reconciliation: re-mapping to a new file
 * layout (F14-03), a semicolon CSV (F14-04), refused candidates said out loud
 * (F14-06) and server refusals in Arabic (F14-09).
 */

vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "ACCOUNTANT" } } }) }));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
    useRouter: () => ({ push: vi.fn() }),
}));
vi.mock("@/components/finance/AccountPicker", () => ({ loadAccounts: async () => [] }));

import { StatementImportDialog, relevantColumns } from "../StatementImportDialog";
import { LineActionDialog } from "../LineActionDialog";
import { serverText } from "../serverText";
import { ApiError } from "@/lib/api/facilities";
import type { StatementLine } from "@/lib/api/bankRec";

type Route = { match: string; method?: string; body: unknown; status?: number };
let calls: { method: string; url: string; body: Record<string, unknown> | undefined }[] = [];

function stubFetch(routes: (url: string, init?: RequestInit) => Route | undefined) {
    calls = [];
    vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
        const u = String(url);
        const method = init?.method ?? "GET";
        const body = init?.body instanceof FormData ? Object.fromEntries(init.body.entries()) as Record<string, unknown>
            : init?.body ? JSON.parse(String(init.body)) : undefined;
        calls.push({ method, url: u, body });
        const r = routes(u, init);
        return new Response(r?.body === null ? null : JSON.stringify(r ? r.body : []),
            { status: r?.status ?? 200, headers: { "Content-Type": "application/json" } });
    }));
}

function renderIn(locale: "en" | "ar", ui: React.ReactElement) {
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === "ar" ? ar : en} onError={() => {}}>
            <div dir={locale === "ar" ? "rtl" : "ltr"}>{ui}</div>
        </NextIntlClientProvider>,
    );
}

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

const result = (grid: string[][], extra: object = {}) => ({
    status: "PROFILE_REQUIRED", reason: "The file's header row no longer matches the saved mapping",
    reasonCode: "bankrec.headerChanged", reasonArgs: {}, grid, sheetNames: [], sheetName: null, fileKind: "CSV",
    missingColumns: [], errors: [], warnings: [], linesRead: 0, linesNew: 0, linesDuplicate: 0, firstDate: null,
    lastDate: null, openingBalance: null, closingBalance: null, order: null, rows: [], importId: null, csvDelimiter: ",",
    ...extra,
});

const profileOf = (c: { body: Record<string, unknown> | undefined }) => JSON.parse(String(c.body!.profile));

describe("F14-03 re-mapping to a new layout", () => {
    it("keeps only the columns the mode uses and the header has", () => {
        const header = ["Date", "Description", "Amount"];
        expect(relevantColumns({ txnDate: "Date", valueDate: "Value Date", debit: "Debit", credit: "Credit", amount: "Amount" },
            "SIGNED", header)).toEqual({ txnDate: "Date", amount: "Amount" });
        expect(relevantColumns({ txnDate: "C", debit: "Amount", amount: "Amount" }, "SPLIT", header))
            .toEqual({ txnDate: "C", debit: "Amount" });
    });

    it("drops stale columns when seeding, clears the fields a new mode hides, and saves only the mode's columns", async () => {
        const grid = [["Date", "Description", "Amount", "Balance"], ["01/09/2026", "CHARGE", "-10.50", "989.50"]];
        stubFetch((u, init) => {
            if (u.endsWith("/profile") && init?.method === "PUT") return { match: "", body: {} };
            if (u.endsWith("/profile")) return { match: "", body: {
                fileKind: "CSV", headerRow: 1, firstDataRow: 2, csvDelimiter: ",", dateFormats: ["dd/MM/yyyy"],
                columns: { txnDate: "Date", valueDate: "Value Date", description: "Description", debit: "Debit", credit: "Credit", balance: "Balance" },
                amountMode: "SPLIT", decimalSeparator: "." } };
            if (u.includes("/imports")) return { match: "", body: result(grid, { fileKind: "XLSX", csvDelimiter: null }) };
            return undefined;
        });
        renderIn("en", <StatementImportDialog bankAccountId="ba-1" bankName="EI" onClose={() => {}} onImported={() => {}} />);
        fireEvent.change(screen.getByTestId("import-file"), { target: { files: [new File(["x"], "s.xlsx")] } });
        expect(await screen.findByTestId("mapping-wizard")).toBeInTheDocument();
        expect(screen.getByTestId("map-valueDate")).toHaveValue("");
        expect(screen.getByTestId("map-txnDate")).toHaveValue("Date");
        expect(screen.queryByTestId("map-delimiter")).not.toBeInTheDocument();

        // A column picked under SPLIT does not survive a trip through SIGNED.
        fireEvent.change(screen.getByTestId("map-debit"), { target: { value: "Amount" } });
        fireEvent.change(screen.getByTestId("map-amount-mode"), { target: { value: "SIGNED" } });
        fireEvent.change(screen.getByTestId("map-amount-mode"), { target: { value: "SPLIT" } });
        expect(screen.getByTestId("map-debit")).toHaveValue("");

        fireEvent.change(screen.getByTestId("map-amount-mode"), { target: { value: "SIGNED" } });
        fireEvent.change(screen.getByTestId("map-amount"), { target: { value: "Amount" } });
        // Back to SPLIT and on again: debit/credit do not come back from the saved profile.
        fireEvent.change(screen.getByTestId("map-amount-mode"), { target: { value: "SPLIT" } });
        expect(screen.getByTestId("map-debit")).toHaveValue("");
        fireEvent.change(screen.getByTestId("map-amount-mode"), { target: { value: "SIGNED" } });
        fireEvent.change(screen.getByTestId("map-amount"), { target: { value: "Amount" } });
        await waitFor(() => expect(calls.some(c => c.body?.profile && profileOf(c).amountMode === "SIGNED")).toBe(true));
        fireEvent.click(screen.getByTestId("map-save"));
        await waitFor(() => expect(calls.some(c => c.method === "PUT")).toBe(true));
        const saved = calls.find(c => c.method === "PUT")!.body as { columns: Record<string, string>; amountMode: string };
        expect(saved.amountMode).toBe("SIGNED");
        expect(saved.columns).toEqual({ txnDate: "Date", description: "Description", amount: "Amount", balance: "Balance" });
        const previews = calls.filter(c => c.body?.profile).map(profileOf);
        expect(previews.at(-1)!.columns).not.toHaveProperty("debit");
    });
});

describe("F14-59 a saved profile does not fit the uploaded file's kind", () => {
    it("ignores a CSV-shaped saved profile when the upload is XLSX, and does not seed a column from data-row text", async () => {
        // Saved against a CSV that had a title row: header on row 2, and a
        // cheque-number column the CSV called "REF".
        const grid = [["Date", "Description", "Amount"], ["01/09/2026", "Payment", "-500.00"]];
        stubFetch((u, init) => {
            if (u.endsWith("/profile") && init?.method === "PUT") return { match: "", body: {} };
            if (u.endsWith("/profile")) return { match: "", body: {
                fileKind: "CSV", headerRow: 2, firstDataRow: 3, csvDelimiter: ",", dateFormats: ["dd/MM/yyyy"],
                columns: { chequeNo: "REF" }, amountMode: "SPLIT", decimalSeparator: "." } };
            if (u.includes("/imports")) return { match: "", body: result(grid, { fileKind: "XLSX", csvDelimiter: null }) };
            return undefined;
        });
        renderIn("en", <StatementImportDialog bankAccountId="ba-1" bankName="EI" onClose={() => {}} onImported={() => {}} />);
        fireEvent.change(screen.getByTestId("import-file"), { target: { files: [new File(["x"], "s.xlsx")] } });
        expect(await screen.findByTestId("mapping-wizard")).toBeInTheDocument();

        // Not row 2 (the CSV profile's header row, which is a DATA row in this
        // XLSX) — the detected row, 1.
        expect(screen.getByTestId("map-header-row")).toHaveValue(1);
        expect(screen.getByTestId("map-txnDate")).toHaveValue("Date");
        // "REF" never appears in this file's header, so the saved chequeNo
        // mapping (and the data-row text it would otherwise be read against)
        // must not survive into the seeded columns.
        expect(screen.getByTestId("map-chequeNo")).toHaveValue("");
    });
});

describe("F14-04 a semicolon CSV", () => {
    it("defaults the delimiter to the detected one, re-splits on change, and saves it", async () => {
        const semi = [["Date", "Description", "Amount"], ["01/09/2026", "CHARGE", "-10,50"]];
        const tabbed = [["Date;Description;Amount"], ["01/09/2026;CHARGE;-10,50"]];
        stubFetch((u, init) => {
            if (u.endsWith("/profile") && init?.method === "PUT") return { match: "", body: {} };
            if (u.endsWith("/profile")) return { match: "", body: null, status: 204 };
            if (u.includes("/imports")) {
                const fd = init?.body as FormData;
                const p = fd.get("profile") ? JSON.parse(String(fd.get("profile"))) : null;
                return { match: "", body: result(p?.csvDelimiter === "\t" ? tabbed : semi, { csvDelimiter: ";", reason: "No column mapping for this bank account yet", reasonCode: "bankrec.noMapping" }) };
            }
            return undefined;
        });
        renderIn("en", <StatementImportDialog bankAccountId="ba-1" bankName="EI" onClose={() => {}} onImported={() => {}} />);
        fireEvent.change(screen.getByTestId("import-file"), { target: { files: [new File(["x"], "s.csv")] } });
        expect(await screen.findByTestId("mapping-wizard")).toBeInTheDocument();
        expect(screen.getByTestId("map-delimiter")).toHaveValue(";");
        expect(screen.getByTestId("map-txnDate")).toHaveValue("Date");
        await waitFor(() => expect(calls.some(c => c.body?.profile && profileOf(c).csvDelimiter === ";")).toBe(true));

        fireEvent.change(screen.getByTestId("map-amount-mode"), { target: { value: "SIGNED" } });
        fireEvent.change(screen.getByTestId("map-txnDate"), { target: { value: "Date" } });
        fireEvent.change(screen.getByTestId("map-description"), { target: { value: "Description" } });
        fireEvent.change(screen.getByTestId("map-amount"), { target: { value: "Amount" } });
        fireEvent.change(screen.getByTestId("map-date-format"), { target: { value: "dd/MM/yyyy" } });
        expect(screen.getByTestId("map-save")).toBeEnabled();

        fireEvent.change(screen.getByTestId("map-delimiter"), { target: { value: "\t" } });
        await waitFor(() => expect(calls.some(c => c.body?.profile && profileOf(c).csvDelimiter === "\t")).toBe(true));
        // The grid follows the file as split by the chosen delimiter: one column now,
        // so the mapped columns are gone and nothing can be saved.
        await waitFor(() => expect(screen.getByTestId("map-txnDate").querySelectorAll("option")).toHaveLength(2));
        expect(screen.getByTestId("map-save")).toBeDisabled();
        fireEvent.change(screen.getByTestId("map-balance"), { target: { value: "Date;Description;Amount" } });

        fireEvent.change(screen.getByTestId("map-delimiter"), { target: { value: ";" } });
        await waitFor(() => expect(screen.getByTestId("map-txnDate").querySelectorAll("option")).toHaveLength(4));
        fireEvent.click(screen.getByTestId("map-save"));
        await waitFor(() => expect(calls.some(c => c.method === "PUT")).toBe(true));
        // The balance picked from the tab-split header is not in this one: not saved.
        expect(calls.find(c => c.method === "PUT")!.body).toMatchObject({ csvDelimiter: ";",
            columns: { txnDate: "Date", description: "Description", amount: "Amount" } });
        expect((calls.find(c => c.method === "PUT")!.body as { columns: object }).columns).not.toHaveProperty("balance");
    });
});

const line = (id: string, description: string, amount: number): StatementLine => ({
    id, seq: 1, txnDate: "2026-09-10", valueDate: "2026-09-10", description, reference: null, chequeNo: null, amount,
    runningBalance: null, matchId: null, matchStatus: null,
});

const REFUSED = {
    id: "ic-9", kind: "present", label: "500031 Gulf AC", amount: 20000, date: "2026-10-15",
    code: "bankrec.presentBeforeChequeDate", args: { cheque: "500031 Gulf AC", chequeDate: "15/10/2026", date: "10/09/2026" },
    reason: "Issued cheque 500031 Gulf AC is dated 15/10/2026; a cheque cannot be presented before its date (this line is 10/09/2026).",
};

describe("F14-06 a refused candidate says why", () => {
    const cands = (refused: unknown[]) => ({ statementLineId: "d", clear: [], receive: [], bounce: [], present: [],
        suspenseBalance: 0, bankTrnSet: true, leaves: [], refused });

    it("lists the issued cheque dated after the line instead of the bare 'nothing fits'", async () => {
        stubFetch(u => (u.includes("/candidates") ? { match: "", body: cands([REFUSED, { ...REFUSED, id: "r2", kind: "receive" }]) } : undefined));
        renderIn("en", <LineActionDialog lines={[line("d", "CHQ 500031", -20000)]} initial="present" onClose={() => {}} onDone={() => {}} />);
        expect(await screen.findByTestId("refused-ic-9")).toHaveTextContent(REFUSED.reason);
        // Only this action's refusals.
        expect(screen.queryByTestId("refused-r2")).not.toBeInTheDocument();
        expect(screen.queryByTestId("no-candidates")).not.toBeInTheDocument();
    });

    it("says it in Arabic, and falls back to the reason for an unknown code", async () => {
        stubFetch(u => (u.includes("/candidates")
            ? { match: "", body: cands([REFUSED, { ...REFUSED, id: "ic-x", code: "bankrec.noSuchCode", reason: "Plain English reason" }]) }
            : undefined));
        renderIn("ar", <LineActionDialog lines={[line("d", "CHQ 500031", -20000)]} initial="present" onClose={() => {}} onDone={() => {}} />);
        const li = await screen.findByTestId("refused-ic-9");
        expect(li).toHaveTextContent("الشيك الصادر 500031 Gulf AC مؤرخ في 15/10/2026");
        expect(screen.getByTestId("refused-ic-x")).toHaveTextContent("Plain English reason");
    });

    it("keeps 'nothing fits' when nothing is refused either", async () => {
        stubFetch(u => (u.includes("/candidates") ? { match: "", body: cands([]) } : undefined));
        renderIn("en", <LineActionDialog lines={[line("d", "CHQ 500031", -20000)]} initial="present" onClose={() => {}} onDone={() => {}} />);
        expect(await screen.findByTestId("no-candidates")).toHaveTextContent(en.BankRec.noCandidates);
    });
});

describe("F14-09 server refusals in the user's language", () => {
    function Probe({ err }: { err: unknown }) {
        const t = useTranslations("BankRec");
        return <p data-testid="probe">{serverText(t, err)}</p>;
    }
    const apiError = (code: string, args: object, message: string) =>
        new ApiError(400, message, JSON.stringify({ error: true, message, status: 400, code, args }));

    it("renders a coded ApiError in Arabic and an unknown code as its English message", () => {
        renderIn("ar", <Probe err={apiError("cheque.receiveBeforeBooked", { row: "000451", booked: "05/09/2026", date: "01/09/2026" }, "English")} />);
        expect(screen.getByTestId("probe")).toHaveTextContent("أُدرج 000451 في الدفاتر بتاريخ 05/09/2026");
        cleanup();
        renderIn("ar", <Probe err={apiError("bankrec.notAThing", {}, "The server said so")} />);
        expect(screen.getByTestId("probe")).toHaveTextContent("The server said so");
        cleanup();
        renderIn("ar", <Probe err={new ApiError(502, "Bad gateway", "<html>")} />);
        expect(screen.getByTestId("probe")).toHaveTextContent("Bad gateway");
    });

    it("shows an action's refusal from the server in Arabic", async () => {
        stubFetch((u, init) => {
            if (u.includes("/candidates")) return { match: "", body: { statementLineId: "d", clear: [], receive: [], bounce: [],
                present: [{ id: "ic-1", kind: "ISSUED_CHEQUE", label: "500031", amount: 20000, date: "2026-09-01", chequeNo: "500031",
                    propertyId: null, status: "ISSUED", preselected: true }], suspenseBalance: 0, bankTrnSet: true, leaves: [], refused: [] } };
            if (u.includes("/present") && init?.method === "POST") return { match: "", status: 400, body: { error: true, status: 400,
                message: "Cheque 500031 is drawn on another bank account", code: "bankrec.issuedChequeOtherBank", args: { cheque: "500031" } } };
            return undefined;
        });
        renderIn("ar", <LineActionDialog lines={[line("d", "CHQ 500031", -20000)]} initial="present" onClose={() => {}} onDone={() => {}} />);
        await waitFor(() => expect(screen.getByTestId("cand-500031")).toBeChecked());
        fireEvent.click(screen.getByTestId("action-submit"));
        expect(await screen.findByRole("alert")).toHaveTextContent("الشيك 500031 مسحوب على حساب بنكي آخر");
    });

    it("shows the import dialog's 'already imported' reason in Arabic", async () => {
        stubFetch(u => (u.includes("/imports") ? { match: "", body: result([], {
            status: "ALREADY_IMPORTED", reason: "This file was already imported on 24/09/2026 (EI-2026-09.csv); nothing was imported",
            reasonCode: "bankrec.alreadyImported", reasonArgs: { on: "24/09/2026", file: "EI-2026-09.csv" } }) } : undefined));
        renderIn("ar", <StatementImportDialog bankAccountId="ba-1" bankName="EI" onClose={() => {}} onImported={() => {}} />);
        fireEvent.change(screen.getByTestId("import-file"), { target: { files: [new File(["x"], "EI-2026-09.csv")] } });
        expect(await screen.findByTestId("already-imported")).toHaveTextContent("استُورد هذا الملف بالفعل بتاريخ 24/09/2026 (EI-2026-09.csv)");
    });
});

describe("F14-05 the statement pane fits its half of the workspace", () => {
    it("keeps the actions column pinned to the inline end and cuts long text with the full text on hover", async () => {
        const { BankReconciliationWorkspace } = await import("../BankReconciliationWorkspace");
        const long = "INWARD REMITTANCE FROM A VERY LONG COUNTERPARTY NAME THAT GOES ON AND ON REF 99887766";
        stubFetch(u => (u.includes("/workspace") ? { match: "", body: {
            bankAccountId: "ba-1", leaves: [], needsLeaf: false, bookItems: [],
            matches: [{ id: "m1", method: "CREATED", status: "CONFIRMED", confidence: null, statementLineIds: ["a"], journalLineIds: [],
                statementTotal: -52.5, bookTotal: -52.5, createdAt: "", confirmedAt: null, createdDocTypes: ["BNK"], reverseOnDefault: "2026-09-04" }],
            statementLines: [
                { ...line("a", "SERVICE CHARGE", -52.5), matchId: "m1", matchStatus: "CONFIRMED" },
                { ...line("b", long, 5000), valueDate: "2026-09-11" },
            ] } } : undefined));
        renderIn("ar", <BankReconciliationWorkspace bankAccountId="ba-1" />);
        const cell = await screen.findByTestId("sl-actions-a");
        // Logical, so in Arabic it pins to the left edge; never a physical right-0.
        expect(cell.className).toMatch(/\bsticky\b/);
        expect(cell.className).toMatch(/\bend-0\b/);
        expect(cell.className).not.toMatch(/\bright-0\b/);
        // F14-05 (still failing at 1568px after eb03863b): every action sits behind one
        // menu button, so the column is one button wide whatever the row offers.
        expect(cell.querySelectorAll("button")).toHaveLength(1);
        fireEvent.click(screen.getByTestId("row-menu-a"));
        expect(screen.getByTestId("undo-m1")).toBeInTheDocument();
        expect(screen.getByTestId("undo-reverse-m1")).toHaveAttribute("title", ar.BankRec.undoReverse);
        expect(screen.getByTestId("row-menu-list-a")).not.toBeNull();
        // The panes stack below a 1700-px window instead of splitting a 1568-px one in two.
        expect(screen.getByTestId("panes").className).toContain("min-[1700px]:grid-cols-2");
        expect(screen.getByTestId("panes").className).not.toMatch(/\bxl:grid-cols-2\b/);
        expect(screen.getByTitle(long)).toHaveClass("truncate");
        // The value date shows only where it differs from the date.
        expect(screen.getByTestId("vd-b")).toHaveTextContent("11/09/2026");
        expect(screen.queryByTestId("vd-a")).not.toBeInTheDocument();

        // F14-05 (still failing at 1568px before this fix): the statement table
        // is `table-fixed` with an explicit width on every column but the
        // truncating description one, so the table can never lay out wider
        // than its container — nothing can end up rendered under the sticky
        // Actions column, at any pane width, without a sideways scroll.
        const table = cell.closest("table")!;
        expect(table).toHaveClass("table-fixed");
        expect(cell).toHaveClass("bg-surface"); // opaque — a row's tint must not show through
        // The sticky column's own z-index must stay below the Σ footer's, so a
        // tall pane's sticky cell never paints over the footer bar.
        expect(cell.className).toContain("z-[1]");
        expect(screen.getByTestId("sigma-footer").className).toMatch(/\bz-10\b/);
    });
});

describe("F14-48 the CSV export carries the current locale", () => {
    const ws = {
        bankAccountId: "ba-1", leaves: [], needsLeaf: false, bookItems: [], matches: [], reconciledThrough: null,
        statementLines: [], openingItems: [],
    };

    it("passes lang=en in English", async () => {
        const { BankReconciliationWorkspace } = await import("../BankReconciliationWorkspace");
        stubFetch(u => (u.includes("/workspace") ? { match: "", body: ws } : undefined));
        renderIn("en", <BankReconciliationWorkspace bankAccountId="ba-1" />);
        await waitFor(() => expect(screen.getByText("Export CSV").closest("a"))
            .toHaveAttribute("href", expect.stringContaining("lang=en")));
    });

    it("passes lang=ar in Arabic", async () => {
        const { BankReconciliationWorkspace } = await import("../BankReconciliationWorkspace");
        stubFetch(u => (u.includes("/workspace") ? { match: "", body: ws } : undefined));
        renderIn("ar", <BankReconciliationWorkspace bankAccountId="ba-1" />);
        await waitFor(() => expect(screen.getByText(ar.BankRec.exportCsv).closest("a"))
            .toHaveAttribute("href", expect.stringContaining("lang=ar")));
    });
});
