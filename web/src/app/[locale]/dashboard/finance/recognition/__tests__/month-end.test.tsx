import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import type { RecognitionEntry, RecognitionRunResult } from "@/lib/api/leasing";

/**
 * The month-end close (spec §8.4).
 *
 * The rule this file exists for: **the screen must never offer a run the
 * server would refuse.** `RecognitionController.notInTheFuture` (:145-157)
 * 400s a `to` later than today on both `pending` and `run` — "Cannot recognise
 * income for periods that have not ended" — so a future date disables both
 * buttons here rather than sending the request and rendering the Java string.
 *
 * And the second: a preview reports `posted: 0` / `wouldPost: n` on purpose
 * (`RecognitionRunResultDTO`'s own doc), because "a preview that reported
 * itself as 3 posted is how a close gets signed off twice".
 */

let role = "ACCOUNTANT";

vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role } } }) }));
// `...rest` matters: the contract links carry an aria-label, and a mock that
// swallowed it would make the accessible-name assertion untestable.
vi.mock("next/link", () => ({
    default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));

const api = vi.hoisted(() => ({ pending: vi.fn(), run: vi.fn(), fiscal: vi.fn() }));

vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, recognitionApi: { ...m.recognitionApi, pending: api.pending, run: api.run } };
});
vi.mock("@/lib/api/ledger", async orig => {
    const m = await orig<typeof import("@/lib/api/ledger")>();
    return { ...m, ledgerApi: { ...m.ledgerApi, fiscal: { ...m.ledgerApi.fiscal, get: api.fiscal } } };
});

import RecognitionPage from "../page";
import { ApiError } from "@/lib/api/leasing";

function entry(over: Partial<RecognitionEntry> & { id: string }): RecognitionEntry {
    return {
        leaseId: "lease-1", segmentId: "seg-1",
        propertyId: "prop-olv", propertyName: "L'Olivier", unitName: "204",
        periodStart: "2026-08-01", periodEnd: "2026-08-31", days: 31, amount: 10191.78,
        status: "PLANNED", journalId: null, journalNumber: null, postedAt: null,
        ...over,
    };
}

/**
 * Deliberately out of order on every axis the page has to sort: Marina before
 * L'Olivier, unit 512 before 101, August before July.
 */
const PENDING: RecognitionEntry[] = [
    entry({ id: "e3", leaseId: "lease-2", propertyId: "prop-mar", propertyName: "Marina Heights", unitName: "512", amount: 5000 }),
    entry({ id: "e2", leaseId: "lease-1", unitName: "204" }),
    entry({ id: "e1", leaseId: "lease-1", unitName: "204", periodStart: "2026-07-01", periodEnd: "2026-07-31" }),
];

const RUN: RecognitionRunResult = {
    preview: false, posted: 3, wouldPost: 3, amount: 25383.56,
    entries: PENDING.map(e => ({ ...e, status: "POSTED", journalId: "j1", journalNumber: "CIL/2026/0009" })),
    skippedLocked: 0, skippedLockedEntries: [], booksLockedThrough: null,
    failed: 0, errors: [],
};

function renderPage() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <RecognitionPage />
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    role = "ACCOUNTANT";
    api.pending.mockResolvedValue(PENDING);
    api.run.mockResolvedValue(RUN);
    api.fiscal.mockResolvedValue({ fiscalYearStartMonth: 1, booksStartDate: null, booksLockedThrough: "2026-06-30" });
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("Month-end recognition page", () => {
    it("groups the pending entries by property, each with its own subtotal", async () => {
        renderPage();
        await waitFor(() => expect(screen.getByTestId("recognition-group-prop-olv")).toBeInTheDocument());

        expect(screen.getByTestId("recognition-group-prop-mar")).toBeInTheDocument();
        expect(screen.getAllByTestId(/^recognition-pending-row-/)).toHaveLength(3);

        // 10,191.78 × 2 against L'Olivier, 5,000 against Marina — and the grand
        // total is the sum of the subtotals, not of one of them.
        expect(screen.getByTestId("recognition-group-total-prop-olv")).toHaveTextContent("20,383.56");
        expect(screen.getByTestId("recognition-group-total-prop-mar")).toHaveTextContent("5,000.00");
        expect(screen.getByTestId("recognition-pending-total")).toHaveTextContent("25,383.56");
    });

    it("sorts the groups by property name, and the rows inside one by unit then period", async () => {
        renderPage();
        await waitFor(() => expect(screen.getByTestId("recognition-group-prop-olv")).toBeInTheDocument());

        const groups = screen.getAllByTestId(/^recognition-group-(?!total)/).map(g => g.getAttribute("data-testid"));
        expect(groups).toEqual(["recognition-group-prop-olv", "recognition-group-prop-mar"]);

        const rows = screen.getAllByTestId(/^recognition-pending-row-/).map(r => r.getAttribute("data-testid"));
        // e1 (July) before e2 (August), both on unit 204, then Marina's row.
        expect(rows).toEqual([
            "recognition-pending-row-e1",
            "recognition-pending-row-e2",
            "recognition-pending-row-e3",
        ]);
    });

    it("shows the unit and a link to the contract inside a group", async () => {
        renderPage();
        const row = await screen.findByTestId("recognition-pending-row-e1");
        expect(within(row).getByTestId("recognition-row-unit-e1")).toHaveTextContent("204");
        expect(within(row).getByRole("link", { name: "Open the contract for 204" })).toHaveAttribute(
            "href",
            "/en/dashboard/leases/lease-1",
        );
    });

    it("keeps an entry whose property is missing rather than dropping it", async () => {
        api.pending.mockResolvedValue([
            ...PENDING,
            entry({ id: "e4", leaseId: "lease-9", propertyId: null, propertyName: null, unitName: null, amount: 1000 }),
        ]);
        renderPage();

        // Last, and still counted: a row the page cannot place is a row the
        // close would post anyway.
        await waitFor(() => expect(screen.getByTestId("recognition-group-unassigned")).toBeInTheDocument());
        const groups = screen.getAllByTestId(/^recognition-group-(?!total)/).map(g => g.getAttribute("data-testid"));
        expect(groups[groups.length - 1]).toBe("recognition-group-unassigned");
        expect(screen.getByTestId("recognition-pending-row-e4")).toBeInTheDocument();
        expect(screen.getByTestId("recognition-pending-total")).toHaveTextContent("26,383.56");
    });

    it("shows the lock date the books carry", async () => {
        renderPage();
        expect(await screen.findByTestId("recognition-locked-through")).toHaveTextContent("30/06/2026");
    });

    it("refuses a future date without asking the server", async () => {
        renderPage();
        await waitFor(() => expect(api.pending).toHaveBeenCalled());
        api.pending.mockClear();

        fireEvent.change(screen.getByTestId("recognition-to-date"), { target: { value: "2099-12-31" } });

        expect(await screen.findByTestId("recognition-future-warning")).toHaveTextContent(
            "Cannot recognise income for periods that have not ended.",
        );
        expect(screen.getByTestId("recognition-run")).toBeDisabled();
        expect(screen.getByTestId("recognition-preview")).toBeDisabled();
        expect(api.pending).not.toHaveBeenCalled();
        expect(api.run).not.toHaveBeenCalled();
    });

    it("previews without claiming anything was posted", async () => {
        api.run.mockResolvedValue({ ...RUN, preview: true, posted: 0, wouldPost: 3 });
        renderPage();

        fireEvent.click(await screen.findByTestId("recognition-preview"));

        await waitFor(() => expect(screen.getByTestId("recognition-result")).toBeInTheDocument());
        expect(api.run).toHaveBeenCalledWith(expect.any(String), true);
        expect(screen.getByTestId("recognition-result-title")).toHaveTextContent("Preview — nothing was written");
        expect(screen.getByTestId("recognition-would-post")).toHaveTextContent("3");
        expect(screen.queryByTestId("recognition-posted")).toBeNull();
    });

    it("runs only through the confirmation, and reports what was posted", async () => {
        renderPage();

        fireEvent.click(await screen.findByTestId("recognition-run"));
        expect(api.run).not.toHaveBeenCalled();

        fireEvent.click(screen.getByTestId("recognition-run-confirm"));

        await waitFor(() => expect(api.run).toHaveBeenCalledWith(expect.any(String), false));
        expect(await screen.findByTestId("recognition-posted")).toHaveTextContent("3");
        expect(screen.getByTestId("recognition-result-amount")).toHaveTextContent("25,383.56");
    });

    it("names the locked rows and the failures the run reported", async () => {
        api.run.mockResolvedValue({
            ...RUN, posted: 1, wouldPost: 1, amount: 5000,
            skippedLocked: 2, skippedLockedEntries: [PENDING[0], PENDING[1]], booksLockedThrough: "2026-08-31",
            failed: 1, errors: ["Lease 3f2a: no RENT_INCOME account is mapped for this property."],
        });
        renderPage();

        fireEvent.click(await screen.findByTestId("recognition-run"));
        fireEvent.click(screen.getByTestId("recognition-run-confirm"));

        await waitFor(() => expect(screen.getByTestId("recognition-skipped")).toBeInTheDocument());
        expect(screen.getByTestId("recognition-skipped")).toHaveTextContent("2 entries fall in a period that is closed");
        expect(screen.getByTestId("recognition-errors")).toHaveTextContent("no RENT_INCOME account is mapped");
    });

    it("counts a single skipped row in the singular", async () => {
        // A close that catches exactly one row inside a shut period is the
        // ordinary case — one contract, one month — and "1 entries fall in a
        // period that is closed" is the sentence an accountant reads while
        // deciding whether to reopen it.
        api.run.mockResolvedValue({
            ...RUN, posted: 2, wouldPost: 2, amount: 5000,
            skippedLocked: 1, skippedLockedEntries: [PENDING[0]], booksLockedThrough: "2026-06-30",
        });
        renderPage();

        fireEvent.click(await screen.findByTestId("recognition-run"));
        fireEvent.click(screen.getByTestId("recognition-run-confirm"));

        await waitFor(() => expect(screen.getByTestId("recognition-skipped")).toBeInTheDocument());
        expect(screen.getByTestId("recognition-skipped")).toHaveTextContent(
            "1 entry falls in a period that is closed",
        );
    });

    it("is closed to a PROPERTY_MANAGER, who may read a lease's schedule but not run a close", async () => {
        role = "PROPERTY_MANAGER";
        renderPage();

        expect(await screen.findByTestId("recognition-access-denied")).toBeInTheDocument();
        expect(screen.queryByTestId("recognition-run")).toBeNull();
        expect(api.pending).not.toHaveBeenCalled();
    });

    it("surfaces the server's own refusal — a platform admin with no organisation picked", async () => {
        api.pending.mockRejectedValue(new ApiError(400, "Select an organisation first"));
        renderPage();
        expect(await screen.findByRole("alert")).toHaveTextContent("Select an organisation first");
    });
});
