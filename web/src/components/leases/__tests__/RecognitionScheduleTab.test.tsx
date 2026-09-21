import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import type { RecognitionEntry, RecognitionStatus } from "@/lib/api/leasing";

/**
 * The lease page's Recognition schedule tab (spec §8.2, §11).
 *
 * A twelve-month contract starting mid-month is cut into **13** calendar-month
 * slices, and the schedule's whole job is to add back up to the contract's rent
 * — the accountant reading it is checking exactly that. So the two things
 * pinned here are the row count and the footer total.
 */

const api = vi.hoisted(() => ({ leaseSchedule: vi.fn() }));

vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, recognitionApi: { ...m.recognitionApi, ...api } };
});

vi.mock("next/link", () => ({
    default: ({ href, children }: { href: string; children: React.ReactNode }) => <a href={href}>{children}</a>,
}));

import RecognitionScheduleTab from "../RecognitionScheduleTab";

/**
 * 120,000 over 2026-02-15 → 2027-02-14: eleven whole months of 10,000 either
 * side of a first and last stub that add to one. Thirteen rows, 120,000.00.
 */
function schedule(): RecognitionEntry[] {
    const rows: RecognitionEntry[] = [];
    const push = (
        periodStart: string,
        periodEnd: string,
        days: number,
        amount: number,
        status: RecognitionStatus,
        journalNumber: string | null,
    ) =>
        rows.push({
            id: `e${rows.length + 1}`, leaseId: "lease-1", segmentId: "seg-1",
            periodStart, periodEnd, days, amount, status,
            journalId: journalNumber ? `j${rows.length + 1}` : null,
            journalNumber,
            postedAt: journalNumber ? "2026-03-01T00:00:00Z" : null,
        });

    push("2026-02-15", "2026-02-28", 14, 4602.74, "POSTED", "CIL/2026/0001");
    push("2026-03-01", "2026-03-31", 31, 10191.78, "POSTED", "CIL/2026/0002");
    push("2026-04-01", "2026-04-30", 30, 9863.01, "REVERSED", "CIL/2026/0003");
    push("2026-05-01", "2026-05-31", 31, 10191.78, "CANCELLED", null);
    const later = [
        ["2026-06-01", "2026-06-30", 30, 9863.01],
        ["2026-07-01", "2026-07-31", 31, 10191.78],
        ["2026-08-01", "2026-08-31", 31, 10191.78],
        ["2026-09-01", "2026-09-30", 30, 9863.01],
        ["2026-10-01", "2026-10-31", 31, 10191.78],
        ["2026-11-01", "2026-11-30", 30, 9863.01],
        ["2026-12-01", "2026-12-31", 31, 10191.78],
        ["2027-01-01", "2027-01-31", 31, 10191.78],
        ["2027-02-01", "2027-02-14", 14, 4602.76],
    ] as const;
    for (const [s, e, d, a] of later) push(s, e, d as number, a as number, "PLANNED", null);
    return rows;
}

function renderTab(props: { contractRent?: number | null } = {}) {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <RecognitionScheduleTab leaseId="lease-1" {...props} />
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    api.leaseSchedule.mockResolvedValue(schedule());
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("RecognitionScheduleTab", () => {
    it("renders one row per calendar-month slice — thirteen for a mid-month year", async () => {
        renderTab();
        await waitFor(() => expect(screen.getByTestId("recognition-schedule")).toBeInTheDocument());
        expect(screen.getAllByTestId(/^recognition-row-/)).toHaveLength(13);
    });

    it("totals the schedule and says whether it adds back to the contract rent", async () => {
        renderTab({ contractRent: 120000 });
        const total = await screen.findByTestId("recognition-schedule-total");
        expect(total).toHaveTextContent("120,000.00");
        expect(screen.getByTestId("recognition-schedule-check")).toHaveTextContent("Matches the contract rent.");
    });

    it("says so, with the difference, when the schedule does not add back", async () => {
        renderTab({ contractRent: 119000 });
        expect(await screen.findByTestId("recognition-schedule-check")).toHaveTextContent("1,000.00");
    });

    it("links a posted row to its journal and leaves a planned one unlinked", async () => {
        renderTab();
        const posted = await screen.findByTestId("recognition-row-0");
        expect(within(posted).getByRole("link", { name: "CIL/2026/0001" })).toHaveAttribute(
            "href",
            "/en/dashboard/finance/journals/j1",
        );
        expect(within(screen.getByTestId("recognition-row-12")).queryByRole("link")).toBeNull();
    });

    it("surfaces a failed load instead of an empty table", async () => {
        api.leaseSchedule.mockRejectedValue(new Error("boom"));
        renderTab();
        expect(await screen.findByRole("alert")).toHaveTextContent(
            "Couldn't load the recognition schedule. Please try again.",
        );
    });
});
