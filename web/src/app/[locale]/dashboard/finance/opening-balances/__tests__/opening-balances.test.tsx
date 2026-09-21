import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import type { OpeningBalanceGrid, OpeningBalanceRow } from "@/lib/api/cutover";

/**
 * The opening-balance grid (spec §10.3, §11).
 *
 * The rules it wears, each from `OpeningBalanceService`:
 *  - a DERIVED account cannot be typed into (`setRow:256-260` 400s it);
 *  - nor can the OPENING_BALANCE_DIFFERENCE account, which `postFresh:365` skips
 *    because the server computes that figure — a cell whose value is silently
 *    discarded is worse than one that refuses;
 *  - `post:292-301` refuses a second post and `repost:311-322` replaces, so the
 *    screen offers exactly one of Post / Replace and never both;
 *  - a POSTED grid stays EDITABLE, because `setRow` has no posted check and
 *    editing the snapshot is how a correction is prepared before Replace;
 *  - `asOf` comes from the server (`asOf:503-510`), never from this page.
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

const api = vi.hoisted(() => ({
    grid: vi.fn(),
    setRow: vi.fn(),
    upload: vi.fn(),
    post: vi.fn(),
    repost: vi.fn(),
    defaults: vi.fn(),
}));

vi.mock("@/lib/api/cutover", async orig => {
    const m = await orig<typeof import("@/lib/api/cutover")>();
    return {
        ...m,
        cutoverApi: {
            ...m.cutoverApi,
            openingBalances: {
                ...m.cutoverApi.openingBalances,
                grid: api.grid,
                setRow: api.setRow,
                uploadSnapshot: api.upload,
                post: api.post,
                repost: api.repost,
            },
        },
    };
});
vi.mock("@/lib/api/ledger", async orig => {
    const m = await orig<typeof import("@/lib/api/ledger")>();
    return { ...m, ledgerApi: { ...m.ledgerApi, defaults: { ...m.ledgerApi.defaults, get: api.defaults } } };
});

import OpeningBalancesPage from "../page";
import { ApiError } from "@/lib/api/facilities";

function row(over: Partial<OpeningBalanceRow> & { accountId: string }): OpeningBalanceRow {
    return {
        code: "110100", name: "Cash", accountType: "ASSET", propertyId: null,
        derived: false, derivedRole: null, enteredDebit: 0, enteredCredit: 0,
        ...over,
    };
}

const ROWS: OpeningBalanceRow[] = [
    row({ accountId: "a-cash", code: "110100", name: "Cash", enteredDebit: 5000 }),
    row({ accountId: "a-bank", code: "110200", name: "ENBD Current", enteredDebit: 120000.55 }),
    row({
        accountId: "a-rent", code: "120100", name: "Rent receivable",
        derived: true, derivedRole: "RENT_RECEIVABLE",
    }),
    row({ accountId: "a-diff", code: "F-02", name: "Opening Balance Difference", accountType: "EQUITY" }),
    row({ accountId: "a-cap", code: "F-01", name: "Capital", accountType: "EQUITY", enteredCredit: 125000.55 }),
];

function grid(over: Partial<OpeningBalanceGrid> = {}): OpeningBalanceGrid {
    return {
        asOf: "2026-08-31", posted: false, journalId: null, journalNumber: null,
        rows: ROWS, totalDebit: 125000.55, totalCredit: 125000.55, difference: 0, problems: [],
        ...over,
    };
}

function renderPage() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <OpeningBalancesPage />
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    vi.clearAllMocks();
    role = "ACCOUNTANT";
    api.grid.mockResolvedValue(grid());
    api.setRow.mockResolvedValue(undefined);
    api.defaults.mockResolvedValue([
        { role: "OPENING_BALANCE_DIFFERENCE", accountId: "a-diff", accountCode: "F-02", accountName: "Opening Balance Difference", inherited: false },
    ]);
});
afterEach(cleanup);

describe("opening balances — the grid", () => {
    it("shows the server's as-at date rather than inventing one", async () => {
        renderPage();
        expect(await screen.findByTestId("ob-as-of")).toHaveTextContent("31/08/2026");
    });

    it("renders one row per account with its entered figures", async () => {
        renderPage();
        const bank = await screen.findByTestId("ob-row-a-bank");
        expect(bank).toHaveTextContent("110200");
        expect(within(bank).getByTestId("ob-debit-a-bank")).toHaveValue("120000.55");
    });

    /** setRow:256-260 — "derived from the contract import, so it cannot be entered by hand". */
    it("makes a derived row read-only and says why", async () => {
        renderPage();
        const derived = await screen.findByTestId("ob-row-a-rent");
        expect(within(derived).queryByTestId("ob-debit-a-rent")).not.toBeInTheDocument();
        expect(within(derived).getByTestId("ob-readonly-a-rent")).toHaveTextContent(
            en.Cutover.derivedWhy.replace("{role}", "RENT_RECEIVABLE"),
        );
    });

    /** postFresh:365 folds this account into the balancing line, discarding anything typed. */
    it("makes the opening-balance difference account read-only and says why", async () => {
        renderPage();
        const diff = await screen.findByTestId("ob-row-a-diff");
        expect(within(diff).queryByTestId("ob-debit-a-diff")).not.toBeInTheDocument();
        expect(within(diff).getByTestId("ob-readonly-a-diff")).toHaveTextContent(
            en.Cutover.differenceAccountWhy,
        );
    });

    it("leaves every other row editable", async () => {
        renderPage();
        expect(await screen.findByTestId("ob-debit-a-cash")).toBeEnabled();
        expect(screen.getByTestId("ob-credit-a-cap")).toBeEnabled();
    });

    /** setRow has no "already posted" check — editing is how a Replace is prepared. */
    it("keeps the grid editable after the books are open", async () => {
        api.grid.mockResolvedValue(grid({ posted: true, journalId: "j1", journalNumber: "OB/2026/0001" }));
        renderPage();
        expect(await screen.findByTestId("ob-debit-a-cash")).toBeEnabled();
    });
});

describe("opening balances — totals", () => {
    it("recomputes the footer from the edited cells, in fils", async () => {
        renderPage();
        await screen.findByTestId("ob-debit-a-cash");
        expect(screen.getByTestId("ob-total-debit")).toHaveTextContent("125,000.55");
        expect(screen.getByTestId("ob-difference")).toHaveTextContent("0.00");

        fireEvent.change(screen.getByTestId("ob-debit-a-cash"), { target: { value: "5000.10" } });
        await waitFor(() => expect(screen.getByTestId("ob-total-debit")).toHaveTextContent("125,000.65"));
        expect(screen.getByTestId("ob-difference")).toHaveTextContent("0.10");
    });

    /** 0.1 + 0.2 must read 0.30, not 0.30000000000000004. */
    it("does not drift on a column of fils", async () => {
        api.grid.mockResolvedValue(
            grid({
                rows: [
                    row({ accountId: "a1", code: "1", enteredDebit: 0.1 }),
                    row({ accountId: "a2", code: "2", enteredDebit: 0.2 }),
                ],
                totalDebit: 0.3, totalCredit: 0, difference: 0.3,
            }),
        );
        renderPage();
        await screen.findByTestId("ob-debit-a1");
        expect(screen.getByTestId("ob-total-debit")).toHaveTextContent("0.30");
        expect(screen.getByTestId("ob-total-debit").textContent).not.toContain("0000");
    });

    /**
     * Where float drift actually bites a user: `fmtAmount` would round
     * 0.30000000000000004 down to "0.30" and hide it, but the imbalance warning
     * is driven by `difference !== 0` — so a grid that balances to the fil would
     * light up "this does not balance" over 5.5e-17. Summed in fils it is zero.
     */
    it("does not claim an imbalance on a column that balances to the fil", async () => {
        api.grid.mockResolvedValue(
            grid({
                rows: [
                    row({ accountId: "a1", code: "1", enteredDebit: 0.1 }),
                    row({ accountId: "a2", code: "2", enteredDebit: 0.2 }),
                    row({ accountId: "a3", code: "3", enteredCredit: 0.3 }),
                ],
                totalDebit: 0.3, totalCredit: 0.3, difference: 0,
            }),
        );
        renderPage();
        await screen.findByTestId("ob-debit-a1");
        expect(screen.getByTestId("ob-difference")).toHaveTextContent("0.00");
        expect(screen.queryByTestId("ob-difference-warning")).not.toBeInTheDocument();
    });
});

describe("opening balances — saving a cell", () => {
    it("sends the edited row and marks it saved", async () => {
        renderPage();
        await screen.findByTestId("ob-debit-a-cash");
        fireEvent.change(screen.getByTestId("ob-debit-a-cash"), { target: { value: "7500" } });
        expect(screen.getByTestId("ob-unsaved-a-cash")).toBeInTheDocument();

        fireEvent.click(screen.getByTestId("ob-save-a-cash"));
        await waitFor(() =>
            expect(api.setRow).toHaveBeenCalledWith("a-cash", { debit: 7500, credit: null }),
        );
        await waitFor(() => expect(screen.queryByTestId("ob-unsaved-a-cash")).not.toBeInTheDocument());
    });

    it("sends the credit side as its own column", async () => {
        renderPage();
        await screen.findByTestId("ob-credit-a-cap");
        fireEvent.change(screen.getByTestId("ob-credit-a-cap"), { target: { value: "99" } });
        fireEvent.click(screen.getByTestId("ob-save-a-cap"));
        await waitFor(() => expect(api.setRow).toHaveBeenCalledWith("a-cap", { debit: null, credit: 99 }));
    });

    /** The plan 3 trap: a refetch must not wipe what is half-typed. */
    it("keeps an unsaved cell across a refresh", async () => {
        renderPage();
        await screen.findByTestId("ob-debit-a-cash");
        fireEvent.change(screen.getByTestId("ob-debit-a-cash"), { target: { value: "8888" } });

        fireEvent.click(screen.getByTestId("ob-refresh"));
        await waitFor(() => expect(api.grid).toHaveBeenCalledTimes(2));
        expect(screen.getByTestId("ob-debit-a-cash")).toHaveValue("8888");
    });

    it("blocks Post while a cell is unsaved and says how many", async () => {
        renderPage();
        await screen.findByTestId("ob-debit-a-cash");
        fireEvent.change(screen.getByTestId("ob-debit-a-cash"), { target: { value: "1" } });
        await waitFor(() => expect(screen.getByTestId("ob-post")).toBeDisabled());
        expect(screen.getByTestId("ob-blocker")).toHaveTextContent("1 unsaved cell");
    });
});

describe("opening balances — post and replace", () => {
    it("offers Post when the books are closed and not Replace", async () => {
        renderPage();
        expect(await screen.findByTestId("ob-post")).toBeInTheDocument();
        expect(screen.queryByTestId("ob-replace")).not.toBeInTheDocument();
        expect(screen.queryByTestId("ob-posted-banner")).not.toBeInTheDocument();
    });

    it("offers Replace when the books are open and not Post", async () => {
        api.grid.mockResolvedValue(grid({ posted: true, journalId: "j1", journalNumber: "OB/2026/0001" }));
        renderPage();
        expect(await screen.findByTestId("ob-replace")).toBeInTheDocument();
        expect(screen.queryByTestId("ob-post")).not.toBeInTheDocument();
        expect(screen.getByTestId("ob-posted-banner")).toHaveTextContent("OB/2026/0001");
    });

    it("posts behind a confirmation naming the date", async () => {
        api.post.mockResolvedValue({ id: "j1", entryNumber: "OB/2026/0001", entryDate: "2026-08-31" });
        renderPage();
        fireEvent.click(await screen.findByTestId("ob-post"));
        expect(await screen.findByTestId("confirm-ob-post")).toBeInTheDocument();
        fireEvent.click(screen.getByTestId("confirm-ob-post"));

        await waitFor(() => expect(api.post).toHaveBeenCalledTimes(1));
        expect(await screen.findByTestId("ob-success")).toHaveTextContent("OB/2026/0001");
        await waitFor(() => expect(api.grid).toHaveBeenCalledTimes(2));
    });

    /** repost takes a reason; the reversal's narration is what it ends up on. */
    it("requires a reason before it will replace", async () => {
        api.grid.mockResolvedValue(grid({ posted: true, journalId: "j1", journalNumber: "OB/2026/0001" }));
        api.repost.mockResolvedValue({ id: "j2", entryNumber: "OB/2026/0002", entryDate: "2026-08-31" });
        renderPage();
        fireEvent.click(await screen.findByTestId("ob-replace"));

        expect(await screen.findByTestId("confirm-ob-replace")).toBeDisabled();
        expect(screen.getByTestId("ob-replace-blocker")).toHaveTextContent(en.Cutover.replaceReasonRequired);

        fireEvent.change(screen.getByTestId("ob-replace-reason"), { target: { value: "corrected file" } });
        await waitFor(() => expect(screen.getByTestId("confirm-ob-replace")).toBeEnabled());
        fireEvent.click(screen.getByTestId("confirm-ob-replace"));

        await waitFor(() => expect(api.repost).toHaveBeenCalledWith({ reason: "corrected file" }));
    });

    /** "The opening balances are being posted right now; try again" — RowLockedException. */
    it("surfaces the server's refusal on a post", async () => {
        api.post.mockRejectedValue(
            new ApiError(409, "The opening balances are being posted right now; try again"),
        );
        renderPage();
        fireEvent.click(await screen.findByTestId("ob-post"));
        fireEvent.click(await screen.findByTestId("confirm-ob-post"));
        expect(await screen.findByRole("alert")).toHaveTextContent("being posted right now");
    });

    /** grid.problems are config faults that would make posting fail. */
    it("shows the grid's problems and blocks Post", async () => {
        api.grid.mockResolvedValue(
            grid({ problems: ["The role RENT_RECEIVABLE is mapped to an account that no longer exists"] }),
        );
        renderPage();
        expect(await screen.findByTestId("ob-problems")).toHaveTextContent("no longer exists");
        expect(screen.getByTestId("ob-post")).toBeDisabled();
    });
});

describe("opening balances — snapshot upload", () => {
    it("refuses a non-CSV before it travels", async () => {
        renderPage();
        const input = (await screen.findByTestId("ob-upload")) as HTMLInputElement;
        const wrong = new File(["x"], "tb.xlsx", { type: "application/vnd.ms-excel" });
        Object.defineProperty(input, "files", { value: [wrong] });
        fireEvent.change(input);
        await waitFor(() =>
            expect(screen.getByTestId("ob-upload-error")).toHaveTextContent(en.Cutover.snapshotWrongType),
        );
        expect(api.upload).not.toHaveBeenCalled();
    });

    it("refuses a file over 10MB before it travels", async () => {
        renderPage();
        const input = (await screen.findByTestId("ob-upload")) as HTMLInputElement;
        const big = new File(["x"], "tb.csv", { type: "text/csv" });
        Object.defineProperty(big, "size", { value: 11 * 1024 * 1024 });
        Object.defineProperty(input, "files", { value: [big] });
        fireEvent.change(input);
        await waitFor(() =>
            expect(screen.getByTestId("ob-upload-error")).toHaveTextContent(en.Cutover.snapshotTooBig),
        );
        expect(api.upload).not.toHaveBeenCalled();
    });

    it("uploads a CSV and lists every rejected line with its number", async () => {
        api.upload.mockResolvedValue({
            stored: 40,
            unmatchedCodes: ["9001", "9002"],
            problems: [
                "line 7: expected 4 columns (code, name, debit, credit), got 3",
                "line 9: 'abc' is not an amount",
            ],
        });
        renderPage();
        const input = (await screen.findByTestId("ob-upload")) as HTMLInputElement;
        const ok = new File(["code,name,debit,credit"], "tb.csv", { type: "text/csv" });
        Object.defineProperty(input, "files", { value: [ok] });
        fireEvent.change(input);

        await waitFor(() => expect(api.upload).toHaveBeenCalledWith(ok));
        expect(await screen.findByTestId("ob-upload-stored")).toHaveTextContent("40 rows read");
        expect(screen.getByTestId("ob-upload-unmatched")).toHaveTextContent("2 account codes are not");
        const problems = screen.getByTestId("ob-upload-problems");
        expect(within(problems).getByText(/line 7/)).toBeInTheDocument();
        expect(within(problems).getByText(/line 9/)).toBeInTheDocument();
        // The grid reloads so the new figures are on screen.
        await waitFor(() => expect(api.grid).toHaveBeenCalledTimes(2));
    });

    /**
     * A trial balance whose two sides disagree is accepted and flagged, never
     * refused — "that disagreement is what the reconciliation report exists to
     * show, and refusing the upload would hide it". So an imbalance is a warning.
     */
    it("flags a file that does not balance as a warning, not an error", async () => {
        // Rows that genuinely do not balance: the footer is computed from what is
        // on screen, so an imbalance has to be in the rows, not just in the
        // server's summary fields.
        api.grid.mockResolvedValue(
            grid({
                rows: [
                    row({ accountId: "a1", code: "1", enteredDebit: 100 }),
                    row({ accountId: "a2", code: "2", enteredCredit: 90 }),
                ],
                totalDebit: 100, totalCredit: 90, difference: 10,
            }),
        );
        renderPage();
        await waitFor(() => expect(screen.getByTestId("ob-difference")).toHaveTextContent("10.00"));
        const warning = await screen.findByTestId("ob-difference-warning");
        expect(warning).toHaveTextContent(en.Cutover.differenceGoesToEquity);
        expect(warning.getAttribute("role")).not.toBe("alert");
    });
});

describe("opening balances — access", () => {
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
            expect(await screen.findByTestId("ob-row-a-cash")).toBeInTheDocument();
        } else {
            expect(await screen.findByTestId("ob-access-denied")).toBeInTheDocument();
            expect(api.grid).not.toHaveBeenCalled();
        }
    });

    it("does not fetch until the session resolves", async () => {
        role = null;
        renderPage();
        expect(screen.getByTestId("ob-loading")).toBeInTheDocument();
        expect(api.grid).not.toHaveBeenCalled();
    });

    it("surfaces the tenant-less 400 in a retryable banner", async () => {
        api.grid.mockRejectedValue(new ApiError(400, "Select an organisation first"));
        renderPage();
        expect(await screen.findByRole("alert")).toHaveTextContent("Select an organisation first");
    });

    /** asOf:505-508 — the books start date has to be set first. */
    it("surfaces the missing books-start-date refusal", async () => {
        api.grid.mockRejectedValue(
            new ApiError(400, "Set the books start date in Settings → Fiscal before working on opening balances"),
        );
        renderPage();
        expect(await screen.findByRole("alert")).toHaveTextContent("books start date");
    });
});
