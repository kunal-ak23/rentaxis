import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
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
vi.mock("next/link", () => ({
    default: ({ href, children }: { href: string; children: React.ReactNode }) => <a href={href}>{children}</a>,
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
        periodStart: "2026-08-01", periodEnd: "2026-08-31", days: 31, amount: 10191.78,
        status: "PLANNED", journalId: null, journalNumber: null, postedAt: null,
        ...over,
    };
}

const PENDING: RecognitionEntry[] = [
    entry({ id: "e1", leaseId: "lease-1", periodStart: "2026-07-01", periodEnd: "2026-07-31" }),
    entry({ id: "e2", leaseId: "lease-1" }),
    entry({ id: "e3", leaseId: "lease-2", amount: 5000 }),
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
    it("lists the pending entries grouped by contract, oldest period first", async () => {
        renderPage();
        await waitFor(() => expect(screen.getByTestId("recognition-group-lease-1")).toBeInTheDocument());

        expect(screen.getByTestId("recognition-group-lease-2")).toBeInTheDocument();
        expect(screen.getAllByTestId(/^recognition-pending-row-/)).toHaveLength(3);
        expect(screen.getByTestId("recognition-pending-total")).toHaveTextContent("25,383.56");
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
