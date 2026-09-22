import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import type { ReconciliationRow } from "@/lib/api/cutover";

/**
 * The reconciliation report (spec §10.3): our balance against the PACT trial
 * balance the accountant uploaded.
 *
 * The honesty requirement is the point of this screen. Until the cut-over
 * contracts are imported AND bulk-posted, every DERIVED account reads 0.00 on
 * our side against PACT's real figure — so a fresh report looks like the books
 * are badly wrong when in fact a step has not run yet. The page has to say so.
 */

let role: string | null = "ACCOUNTANT";

vi.mock("next-auth/react", () => ({ useSession: () => ({ data: role ? { user: { role } } : null }) }));
vi.mock("@/i18n/routing", () => ({
    useRouter: () => ({ push: vi.fn() }),
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>
            {children}
        </a>
    ),
}));

const api = vi.hoisted(() => ({ reconcile: vi.fn(), grid: vi.fn() }));
vi.mock("@/lib/api/cutover", async orig => {
    const m = await orig<typeof import("@/lib/api/cutover")>();
    return {
        ...m,
        cutoverApi: {
            ...m.cutoverApi,
            reconciliation: api.reconcile,
            openingBalances: { ...m.cutoverApi.openingBalances, grid: api.grid },
        },
    };
});

import ReconciliationPage from "../page";
import { ApiError } from "@/lib/api/facilities";

function row(over: Partial<ReconciliationRow> & { code: string }): ReconciliationRow {
    return {
        accountId: "a1", name: "Cash", derived: false,
        derivedBalance: 0, pactBalance: 0, difference: 0,
        ...over,
    };
}

/** Deliberately out of code order, so the page's sort has something to do. */
const ROWS: ReconciliationRow[] = [
    row({ code: "210100", accountId: "a-pay", name: "Trade payables", derivedBalance: -5000, pactBalance: -5000 }),
    row({ code: "110100", accountId: "a-cash", name: "Cash", derivedBalance: 5000, pactBalance: 5000 }),
    row({
        code: "120100", accountId: "a-rent", name: "Rent receivable", derived: true,
        derivedBalance: 0, pactBalance: 82000, difference: -82000,
    }),
    row({ code: "9001", accountId: null, name: "PACT suspense", derivedBalance: 0, pactBalance: 1200, difference: -1200 }),
];

function renderPage() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <ReconciliationPage />
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    vi.clearAllMocks();
    role = "ACCOUNTANT";
    api.reconcile.mockResolvedValue(ROWS);
    api.grid.mockResolvedValue({
        asOf: "2026-08-31", posted: false, journalId: null, journalNumber: null,
        rows: [], totalDebit: 0, totalCredit: 0, difference: 0, problems: [],
    });
});
afterEach(cleanup);

describe("reconciliation — the table", () => {
    it("shows the server's as-at date", async () => {
        renderPage();
        expect(await screen.findByTestId("rec-as-of")).toHaveTextContent("31/08/2026");
    });

    it("sorts by account code", async () => {
        renderPage();
        await screen.findByTestId("rec-row-110100");
        const codes = Array.from(document.querySelectorAll("[data-testid^='rec-row-']")).map(
            el => el.getAttribute("data-testid"),
        );
        expect(codes).toEqual(["rec-row-110100", "rec-row-120100", "rec-row-210100", "rec-row-9001"]);
    });

    /**
     * `derivedBalances` takes the LIVE opening journal's own lines back out, so
     * the column is our books at D−1 WITHOUT the opening entry — not "our
     * balance", which is what the header used to claim. Without the caveat the
     * report reads as if the cut-over were wrong by the whole opening balance.
     */
    it("says the derived column excludes the opening entry", async () => {
        renderPage();
        await screen.findByTestId("rec-row-110100");
        expect(screen.getByText(en.Cutover.derivedBalance)).toBeInTheDocument();
        expect(en.Cutover.derivedBalance).toContain("excl. opening entry");
    });

    it("renders both balances and the difference", async () => {
        renderPage();
        const rent = await screen.findByTestId("rec-row-120100");
        expect(within(rent).getByTestId("rec-derived-120100")).toHaveTextContent("0.00");
        expect(within(rent).getByTestId("rec-pact-120100")).toHaveTextContent("82,000.00 Dr");
        expect(within(rent).getByTestId("rec-difference-120100")).toHaveTextContent("82,000.00 Cr");
    });

    it("highlights only the rows that differ", async () => {
        renderPage();
        await screen.findByTestId("rec-row-110100");
        expect(screen.getByTestId("rec-difference-110100")).not.toHaveAttribute("data-differs", "true");
        expect(screen.getByTestId("rec-difference-120100")).toHaveAttribute("data-differs", "true");
    });

    /** accountId null = a PACT code our chart has no account for; kept, not dropped. */
    it("flags a code that is not in our chart", async () => {
        renderPage();
        const suspense = await screen.findByTestId("rec-row-9001");
        expect(within(suspense).getByText(en.Cutover.notInOurChart)).toBeInTheDocument();
    });

    it("totals both columns and the difference", async () => {
        renderPage();
        await screen.findByTestId("rec-row-110100");
        // 5000 + (-5000) + 0 + 0 = 0 on our side; 5000 + (-5000) + 82000 + 1200 on PACT's.
        expect(screen.getByTestId("rec-total-derived")).toHaveTextContent("0.00");
        expect(screen.getByTestId("rec-total-pact")).toHaveTextContent("83,200.00 Dr");
        expect(screen.getByTestId("rec-total-difference")).toHaveTextContent("83,200.00 Cr");
    });
});

describe("reconciliation — the derived-accounts honesty banner", () => {
    /**
     * A derived account reading 0.00 against a real PACT figure means the
     * contract import has not been posted yet — not that the books are wrong.
     */
    it("explains the zeroes when a derived account has not been filled in", async () => {
        renderPage();
        expect(await screen.findByTestId("rec-derived-notice")).toHaveTextContent(
            en.Cutover.derivedNotYetPosted,
        );
    });

    /**
     * The banner must key off the FIGURES, not a hardcoded state: once the
     * cut-over batch is bulk-posted the derived accounts carry real balances and
     * the explanation is no longer true.
     */
    it("counts the posted contracts once derived figures arrive", async () => {
        api.reconcile.mockResolvedValue([
            row({ code: "110100", derivedBalance: 5000, pactBalance: 5000 }),
            row({ code: "120100", derived: true, derivedBalance: 80000, pactBalance: 82000, difference: -2000 }),
        ]);
        renderPage();
        await screen.findByTestId("rec-row-110100");
        // Derived figures are present, so the "nothing has been posted" notice goes.
        expect(screen.queryByTestId("rec-derived-notice")).not.toBeInTheDocument();
    });

    it("drops the notice once the derived accounts carry figures", async () => {
        api.reconcile.mockResolvedValue([
            row({ code: "110100", derivedBalance: 5000, pactBalance: 5000 }),
            row({ code: "120100", derived: true, derivedBalance: 82000, pactBalance: 82000 }),
        ]);
        renderPage();
        await screen.findByTestId("rec-row-110100");
        expect(screen.queryByTestId("rec-derived-notice")).not.toBeInTheDocument();
    });
});

describe("reconciliation — the differences filter", () => {
    it("shows every row by default and filters down on request", async () => {
        renderPage();
        await screen.findByTestId("rec-row-110100");
        expect(document.querySelectorAll("[data-testid^='rec-row-']")).toHaveLength(4);

        fireEvent.click(screen.getByTestId("rec-differences-only"));
        await waitFor(() => expect(document.querySelectorAll("[data-testid^='rec-row-']")).toHaveLength(2));
        expect(screen.getByTestId("rec-row-120100")).toBeInTheDocument();
        expect(screen.queryByTestId("rec-row-110100")).not.toBeInTheDocument();
    });

    it("counts the accounts that differ", async () => {
        renderPage();
        expect(await screen.findByTestId("rec-out-of-balance")).toHaveTextContent("2 accounts differ");
    });

    it("says so when everything agrees", async () => {
        api.reconcile.mockResolvedValue([row({ code: "110100", derivedBalance: 5000, pactBalance: 5000 })]);
        renderPage();
        expect(await screen.findByTestId("rec-reconciled")).toHaveTextContent(en.Cutover.reconciled);
    });

    it("shows an empty state rather than a bare table", async () => {
        api.reconcile.mockResolvedValue([]);
        renderPage();
        expect(await screen.findByTestId("rec-empty")).toBeInTheDocument();
        expect(screen.queryByTestId("rec-table")).not.toBeInTheDocument();
    });
});

describe("reconciliation — access", () => {
    it.each([
        ["SUPER_ADMIN", true],
        ["TENANT_ADMIN", true],
        ["ACCOUNTANT", true],
        ["PROPERTY_MANAGER", false],
        ["RENTER", false],
    ])("admits %s: %s", async (r, allowed) => {
        role = r;
        renderPage();
        if (allowed) {
            expect(await screen.findByTestId("rec-row-110100")).toBeInTheDocument();
        } else {
            expect(await screen.findByTestId("rec-access-denied")).toBeInTheDocument();
            expect(api.reconcile).not.toHaveBeenCalled();
        }
    });

    it("does not fetch until the session resolves", async () => {
        role = null;
        renderPage();
        expect(screen.getByTestId("rec-loading")).toBeInTheDocument();
        expect(api.reconcile).not.toHaveBeenCalled();
    });

    it("surfaces the tenant-less 400 in a retryable banner", async () => {
        api.reconcile.mockRejectedValue(new ApiError(400, "Select an organisation first"));
        renderPage();
        expect(await screen.findByRole("alert")).toHaveTextContent("Select an organisation first");
    });
});
