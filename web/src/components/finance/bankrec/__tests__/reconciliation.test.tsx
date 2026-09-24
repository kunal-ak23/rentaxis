import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import ar from "../../../../../messages/ar.json";
import en from "../../../../../messages/en.json";

/**
 * Finance-ops spec §4 on the web: the reconciliation statement of EI 0123 for
 * September 2026 (the spec's worked example), the finalize button with its
 * blocking checklist, the first reconciliation's opening items, the history
 * with reopen (admins only), opening items in the matcher, and RTL.
 */

const session = vi.hoisted(() => ({ role: "ACCOUNTANT" }));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: session.role } } }) }));
vi.mock("next/navigation", () => ({ useParams: () => ({ id: "ba-1" }) }));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
    useRouter: () => ({ push: vi.fn() }),
}));
vi.mock("@/components/finance/AccountPicker", () => ({ loadAccounts: async () => [] }));

import { ReconciliationPanel, addDays } from "../ReconciliationPanel";
import { BankReconciliationWorkspace } from "../BankReconciliationWorkspace";
import BankReconciliationPage from "@/app/[locale]/dashboard/finance/bank-reconciliation/page";
import { failingChecks, type RecCheck, type RecCheckCode, type Reconciliation } from "@/lib/api/bankRec";

type Route = { match: string; method?: string; body: unknown; status?: number };
let calls: { method: string; url: string; body: unknown }[] = [];

function stubFetch(routes: Route[]) {
    calls = [];
    vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
        const u = String(url);
        const method = init?.method ?? "GET";
        calls.push({ method, url: u, body: init?.body ? JSON.parse(String(init.body)) : undefined });
        const r = routes.find(x => u.includes(x.match) && (!x.method || x.method === method));
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

const OK = (code: RecCheckCode, message: string): RecCheck => ({ code, ok: true, message });

/** §4's September statement: 328,017.50 + 15,000 − 8,000 = 335,017.50 = books; difference 0.00. */
const SEPT: Reconciliation = {
    id: "rec-9", bankAccountId: "ba-1", bankLabel: "Emirates Islamic 0123", bankName: "Emirates Islamic", ibanMasked: "••••••••0123",
    leaves: [], periodFrom: "2026-09-01", periodTo: "2026-09-30", status: "DRAFT", first: false,
    statementOpening: 250000, statementClosing: 328017.5, closingTyped: false, statementMovement: 78017.5,
    bookBalance: 335017.5, bookBalanceAtStart: null, openingItemsTotal: 0, depositsInTransit: 15000, unpresentedPayments: 8000,
    bookedAfterPeriod: 0, unrecordedCredits: 0, unrecordedDebits: 0, adjustedBank: 335017.5, adjustedBook: 335017.5, difference: 0,
    depositsInTransitItems: [{ kind: "JOURNAL", id: "jl-104", date: "2026-09-30", document: "CRT-26/104", narration: "Cheque 000452",
        chequeNo: "000452", amount: 15000, withoutEvidence: true }],
    unpresentedItems: [{ kind: "JOURNAL", id: "jl-60", date: "2026-09-29", document: "BPV-26/60", narration: "Cheque 000077",
        chequeNo: "000077", amount: -8000, withoutEvidence: false }],
    bookedAfterItems: [], unrecordedItems: [], withoutEvidenceCount: 1, matchedByMethod: { CREATED: 4 },
    checks: [OK("CHAIN", "Follows the reconciliation finalized through 31/08/2026"),
        OK("CONTINUITY", "250,000.00 + 78,017.50 = 328,017.50"), OK("UNRECORDED", "Every statement line in the period is matched"),
        OK("DIFFERENCE", "The difference is 0.00"), OK("SUGGESTED", "No suggested match is waiting"), OK("NOT_FUTURE", "The period has ended")],
    canFinalize: true, preparedAt: null, preparedByName: null, finalizedAt: null, finalizedByName: null, reopenedAt: null,
    reopenedByName: null, reopenReason: null,
};

const BLOCKED: Reconciliation = {
    ...SEPT, canFinalize: false, difference: 120, adjustedBank: 335137.5, unrecordedCredits: 120,
    unrecordedItems: [{ kind: "STATEMENT", id: "line-f", date: "2026-09-30", document: null, narration: "CREDIT INTEREST",
        chequeNo: null, amount: 120, withoutEvidence: false }],
    checks: [OK("CHAIN", "Follows"), { code: "UNRECORDED" as const, ok: false, message: "1 statement line(s) in the period are not matched" },
        { code: "DIFFERENCE" as const, ok: false, message: "The difference is 120.00; it must be 0.00" }, OK("NOT_FUTURE", "The period has ended")],
};

const ROWS = [
    { id: "rec-9", periodFrom: "2026-09-01", periodTo: "2026-09-30", status: "DRAFT", statementClosing: null, bookBalance: null,
      difference: null, createdAt: "2026-10-01T06:00:00Z", finalizedAt: null, finalizedByName: null, reopenedAt: null,
      reopenedByName: null, reopenReason: null },
    { id: "rec-8", periodFrom: "2026-08-01", periodTo: "2026-08-31", status: "FINALIZED", statementClosing: 250000,
      bookBalance: 250000, difference: 0, createdAt: "2026-09-01T06:00:00Z", finalizedAt: "2026-09-02T06:00:00Z",
      finalizedByName: "Accountant", reopenedAt: null, reopenedByName: null, reopenReason: null },
];

beforeEach(() => { session.role = "ACCOUNTANT"; });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe("the reconciliation statement", () => {
    it("shows the worked example at 0.00, opens each figure's items and finalizes after a confirmation", async () => {
        stubFetch([{ match: "/bank-accounts/ba-1/reconciliations", body: ROWS },
            { method: "POST", match: "/reconciliations/rec-9/finalize", body: { ...SEPT, status: "FINALIZED" } },
            { match: "/reconciliations/rec-9", body: SEPT }]);
        renderIn("en", <ReconciliationPanel bankAccountId="ba-1" />);
        expect(await screen.findByTestId("rec-figure-statementClosing")).toHaveTextContent("328,017.50");
        expect(screen.getByTestId("rec-figure-depositsInTransit")).toHaveTextContent("15,000.00");
        expect(screen.getByTestId("rec-figure-unpresentedPayments")).toHaveTextContent("-8,000.00");
        expect(screen.getByTestId("rec-figure-adjustedBank")).toHaveTextContent("335,017.50");
        expect(screen.getByTestId("rec-figure-bookBalance")).toHaveTextContent("335,017.50");
        expect(screen.getByTestId("rec-figure-difference")).toHaveTextContent("0.00");
        expect(screen.getByTestId("rec-locked-through")).toHaveTextContent("31/08/2026");
        // The hand-cleared cheque is exposed: in transit, "no statement line yet".
        expect(screen.getByTestId("rec-without-evidence")).toHaveTextContent("1 cheque(s) cleared by hand");
        fireEvent.click(screen.getByTestId("rec-open-depositsInTransit"));
        const items = within(screen.getByTestId("rec-items"));
        expect(items.getByTestId("rec-item-jl-104")).toHaveTextContent("CRT-26/104");
        expect(items.getByTestId("rec-item-jl-104")).toHaveTextContent("no statement line yet");

        expect(screen.getByTestId("rec-finalize")).toBeEnabled();
        fireEvent.click(screen.getByTestId("rec-finalize"));
        // F14-47: the app's own confirm dialog, not window.confirm — the
        // finalize call must wait for an explicit confirm click.
        expect(screen.getByText(/Finalize and lock this bank account through/)).toHaveTextContent("30/09/2026");
        expect(calls.some(c => c.method === "POST" && c.url.endsWith("/reconciliations/rec-9/finalize"))).toBe(false);
        fireEvent.click(screen.getByTestId("rec-finalize-confirm"));
        await waitFor(() => expect(calls.some(c => c.method === "POST" && c.url.endsWith("/reconciliations/rec-9/finalize"))).toBe(true));
    });

    it("blocks Finalize while a check fails and lists the failing ones in its tooltip", async () => {
        stubFetch([{ match: "/bank-accounts/ba-1/reconciliations", body: ROWS }, { match: "/reconciliations/rec-9", body: BLOCKED }]);
        renderIn("en", <ReconciliationPanel bankAccountId="ba-1" />);
        const finalize = await screen.findByTestId("rec-finalize");
        expect(finalize).toBeDisabled();
        expect(finalize.getAttribute("title")).toBe("• 1 statement line(s) in the period are not matched\n• The difference is 120.00; it must be 0.00");
        expect(screen.getByTestId("rec-blocked")).toHaveTextContent("2 check(s) still failing");
        expect(screen.getByTestId("rec-check-UNRECORDED")).toHaveAttribute("data-ok", "false");
        expect(screen.getByTestId("rec-check-CHAIN")).toHaveAttribute("data-ok", "true");
        expect(failingChecks(BLOCKED).map(c => c.code)).toEqual(["UNRECORDED", "DIFFERENCE"]);
    });

    it("starts the next period the day after the last finalized one, and the first on a typed day", async () => {
        stubFetch([{ match: "/bank-accounts/ba-1/reconciliations", method: "GET", body: [ROWS[1]] },
            { match: "/bank-accounts/ba-1/reconciliations", method: "POST", body: SEPT }]);
        renderIn("en", <ReconciliationPanel bankAccountId="ba-1" />);
        const from = await screen.findByTestId("rec-from");
        expect(from).toHaveValue("2026-09-01");
        expect(from).toBeDisabled();
        fireEvent.change(screen.getByTestId("rec-to"), { target: { value: "2026-09-30" } });
        fireEvent.click(screen.getByTestId("rec-create"));
        await waitFor(() => expect(calls.some(c => c.method === "POST")).toBe(true));
        expect(calls.find(c => c.method === "POST")!.body).toEqual({ periodFrom: null, periodTo: "2026-09-30",
            statementOpening: null, statementClosing: null });
        expect(addDays("2026-02-28", 1)).toBe("2026-03-01");
        expect(addDays("2026-09-01", -1)).toBe("2026-08-31");
    });

    it("takes the first reconciliation's opening items and shows the live check", async () => {
        const first: Reconciliation = { ...SEPT, first: true, checks: [...SEPT.checks.slice(1),
            { code: "OPENING_ITEMS" as const, ok: false, message: "Statement balance at 31/08/2026 5,000.00 + outstanding items 0.00 = 5,000.00; the books show 4,000.00." }],
            canFinalize: false };
        stubFetch([{ match: "/bank-accounts/ba-1/reconciliations", body: [ROWS[0]] }, { match: "/reconciliations/rec-9", body: first },
            { match: "/opening-items", method: "POST", body: { id: "oi-1" } }, { match: "/opening-items", body: [] }]);
        renderIn("en", <ReconciliationPanel bankAccountId="ba-1" />);
        expect(await screen.findByTestId("rec-opening-check")).toHaveTextContent("the books show 4,000.00");
        expect(screen.getByTestId("rec-opening-items")).toHaveTextContent("Outstanding at 31/08/2026");
        fireEvent.change(screen.getByTestId("opening-date"), { target: { value: "2026-08-20" } });
        fireEvent.change(screen.getByTestId("opening-description"), { target: { value: "PACT cheque 000009" } });
        fireEvent.change(screen.getByTestId("opening-cheque"), { target: { value: "000009" } });
        fireEvent.change(screen.getByTestId("opening-amount"), { target: { value: "-1000" } });
        fireEvent.click(screen.getByTestId("opening-save"));
        await waitFor(() => expect(calls.some(c => c.method === "POST" && c.url.includes("/opening-items"))).toBe(true));
        expect(calls.find(c => c.method === "POST")!.body).toEqual({ itemDate: "2026-08-20", description: "PACT cheque 000009",
            reference: null, chequeNo: "000009", amount: -1000 });
    });

    it("offers Reopen only to an admin, only on the latest finalized one, and sends the reason", async () => {
        const rows = [ROWS[1], { ...ROWS[1], id: "rec-7", periodFrom: "2026-07-01", periodTo: "2026-07-31" }];
        stubFetch([{ match: "/reconciliations/rec-8/reopen", method: "POST", body: SEPT }, { match: "/bank-accounts/ba-1/reconciliations", body: rows }]);
        renderIn("en", <ReconciliationPanel bankAccountId="ba-1" />);
        await screen.findByTestId("rec-row-rec-8");
        expect(screen.queryByTestId("rec-reopen-rec-8")).not.toBeInTheDocument();
        cleanup();

        session.role = "TENANT_ADMIN";
        renderIn("en", <ReconciliationPanel bankAccountId="ba-1" />);
        await screen.findByTestId("rec-row-rec-8");
        expect(screen.queryByTestId("rec-reopen-rec-7")).not.toBeInTheDocument();
        fireEvent.click(screen.getByTestId("rec-reopen-rec-8"));
        expect(screen.getByTestId("rec-reopen-go")).toBeDisabled();
        fireEvent.change(screen.getByTestId("rec-reopen-reason"), { target: { value: "000452 credited on 30/09" } });
        fireEvent.click(screen.getByTestId("rec-reopen-go"));
        await waitFor(() => expect(calls.some(c => c.method === "POST" && c.url.endsWith("/reconciliations/rec-8/reopen"))).toBe(true));
        expect(calls.find(c => c.method === "POST")!.body).toEqual({ reason: "000452 credited on 30/09" });
    });

    it("reads right to left in Arabic with the amounts kept left to right", async () => {
        stubFetch([{ match: "/bank-accounts/ba-1/reconciliations", body: ROWS }, { match: "/reconciliations/rec-9", body: SEPT }]);
        renderIn("ar", <ReconciliationPanel bankAccountId="ba-1" />);
        const closing = await screen.findByTestId("rec-figure-statementClosing");
        expect(closing.closest("bdi") ?? closing).toHaveAttribute("dir", "ltr");
        expect(screen.getByTestId("rec-panel").closest("[dir]")).toHaveAttribute("dir", "rtl");
        expect(screen.getByTestId("rec-panel")).toHaveTextContent(ar.BankRec.recTitle);
        expect(screen.getByTestId("rec-finalize")).toHaveTextContent(ar.BankRec.finalize);
        expect(screen.getByTestId("rec-check-CHAIN")).toHaveTextContent(ar.BankRec.check_CHAIN);
    });
});

describe("the reconciliation panel refresh (F14-47)", () => {
    it("re-fetches the panel's figures after the workspace confirms a match, not just on first load", async () => {
        const ws = {
            bankAccountId: "ba-1", leaves: [], needsLeaf: false, bookItems: [], matches: [], reconciledThrough: "2026-08-31",
            statementLines: [{ id: "l9", seq: 1, txnDate: "2026-09-05", valueDate: "2026-09-05", description: "CHQ 000009 PRESENTED",
                reference: null, chequeNo: "000009", amount: -1000, runningBalance: 4000, matchId: null, matchStatus: null }],
            openingItems: [{ id: "oi-1", bankAccountId: "ba-1", itemDate: "2026-08-20", description: "PACT cheque 000009", reference: null,
                chequeNo: "000009", amount: -1000, matchId: null, matchStatus: null }],
        };
        stubFetch([
            { match: "/workspace", body: ws },
            { method: "POST", match: "/matches", body: { id: "m9" } },
            { match: "/bank-accounts/ba-1/reconciliations", body: ROWS },
            { match: "/reconciliations/rec-9", body: BLOCKED },
        ]);
        renderIn("en", <BankReconciliationWorkspace bankAccountId="ba-1" />);
        await screen.findByTestId("oi-PACT cheque 000009");
        // The panel loads once on mount — reset the call log so the count
        // below only reflects the refetch the match below should trigger.
        await screen.findByTestId("rec-figure-difference");
        const before = calls.filter(c => c.url.includes("/reconciliations/rec-9")).length;

        fireEvent.click(screen.getByTestId("sel-sl-CHQ 000009 PRESENTED"));
        fireEvent.click(screen.getByTestId("sel-oi-PACT cheque 000009"));
        fireEvent.click(screen.getByTestId("match"));
        await waitFor(() => expect(calls.some(c => c.method === "POST" && c.url.endsWith("/matches"))).toBe(true));

        await waitFor(() => expect(calls.filter(c => c.url.includes("/reconciliations/rec-9")).length).toBeGreaterThan(before));
    });
});

describe("opening items in the matcher", () => {
    it("lists them on the book side and matches one to a statement line", async () => {
        const ws = {
            bankAccountId: "ba-1", leaves: [], needsLeaf: false, bookItems: [], matches: [], reconciledThrough: "2026-08-31",
            statementLines: [{ id: "l9", seq: 1, txnDate: "2026-09-05", valueDate: "2026-09-05", description: "CHQ 000009 PRESENTED",
                reference: null, chequeNo: "000009", amount: -1000, runningBalance: 4000, matchId: null, matchStatus: null }],
            openingItems: [{ id: "oi-1", bankAccountId: "ba-1", itemDate: "2026-08-20", description: "PACT cheque 000009", reference: null,
                chequeNo: "000009", amount: -1000, matchId: null, matchStatus: null }],
        };
        stubFetch([{ match: "/workspace", body: ws }, { method: "POST", match: "/matches", body: { id: "m9" } }]);
        renderIn("en", <BankReconciliationWorkspace bankAccountId="ba-1" />);
        await screen.findByTestId("oi-PACT cheque 000009");
        expect(screen.getByTestId("ws-locked")).toHaveTextContent("31/08/2026");
        fireEvent.click(screen.getByTestId("sel-sl-CHQ 000009 PRESENTED"));
        fireEvent.click(screen.getByTestId("sel-oi-PACT cheque 000009"));
        expect(screen.getByTestId("sum-difference")).toHaveTextContent("0.00");
        fireEvent.click(screen.getByTestId("match"));
        await waitFor(() => expect(calls.some(c => c.method === "POST" && c.url.endsWith("/matches"))).toBe(true));
        expect(calls.find(c => c.method === "POST" && c.url.endsWith("/matches"))!.body)
            .toEqual({ statementLineIds: ["l9"], journalLineIds: [], openingItemIds: ["oi-1"] });
    });
});

describe("the bank account list", () => {
    const ROW = { id: "ba-1", bankName: "Emirates Islamic", accountNumber: "0123", iban: null, currency: "AED", bankTrn: null,
        active: true, leaves: [], needsLeaf: false, lastImportAt: null, lastImportFile: null, lastLineDate: null, unmatchedLines: 0,
        hasProfile: true, reconciledThrough: "2026-09-30", recStartDate: "2026-09-01", draftReconciliationId: null,
        latestFinalizedReconciliationId: "rec-9" };

    it("shows Reconciled through, and Reopen for an admin with a reason", async () => {
        session.role = "TENANT_ADMIN";
        stubFetch([{ match: "/reconciliations/rec-9/reopen", method: "POST", body: SEPT }, { match: "/bank-accounts", body: [ROW] }]);
        renderIn("en", <BankReconciliationPage />);
        expect(await screen.findByTestId("reconciled-0123")).toHaveTextContent("30/09/2026");
        fireEvent.click(screen.getByTestId("reopen-0123"));
        fireEvent.change(screen.getByTestId("reopen-reason"), { target: { value: "wrong leaf" } });
        fireEvent.click(screen.getByTestId("reopen-go"));
        await waitFor(() => expect(calls.some(c => c.method === "POST" && c.url.endsWith("/reconciliations/rec-9/reopen"))).toBe(true));
    });

    it("has no Reopen for an accountant", async () => {
        stubFetch([{ match: "/bank-accounts", body: [ROW] }]);
        renderIn("en", <BankReconciliationPage />);
        expect(await screen.findByTestId("reconciled-0123")).toHaveTextContent("30/09/2026");
        expect(screen.queryByTestId("reopen-0123")).not.toBeInTheDocument();
    });
});
