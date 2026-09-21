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
 *
 * `GET /leases/{id}/recognition` returns **every entry the lease has ever had,
 * in every status** (`RecognitionService.scheduleFor`, :169-171). An amend
 * retires the old rows — REVERSED for the posted ones, CANCELLED for the
 * planned — *keeping their amounts* — and lays a whole new schedule beside them
 * (`rebuildAfterAmend`, :238-259). So the footer's Σ has to be over the LIVE
 * plan alone; adding the retired rows in makes an amended contract look long by
 * exactly the amount that was amended away.
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
 *
 * Every row is LIVE — two months closed and eleven still planned — because that
 * is what an un-amended, un-terminated contract's schedule looks like. The
 * retired shapes live in {@link afterAmend}, which is what the endpoint really
 * returns once a contract has been amended.
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
            propertyId: "prop-olv", propertyName: "L'Olivier", unitName: "204",
            periodStart, periodEnd, days, amount, status,
            journalId: journalNumber ? `j${rows.length + 1}` : null,
            journalNumber,
            postedAt: journalNumber ? "2026-03-01T00:00:00Z" : null,
        });

    push("2026-02-15", "2026-02-28", 14, 4602.74, "POSTED", "CIL/2026/0001");
    push("2026-03-01", "2026-03-31", 31, 10191.78, "POSTED", "CIL/2026/0002");
    const later = [
        ["2026-04-01", "2026-04-30", 30, 9863.01],
        ["2026-05-01", "2026-05-31", 31, 10191.78],
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

/**
 * The shape `rebuildAfterAmend` actually produces, which the old fixture could
 * not: a 51,000 contract amended to 60,000.
 *
 * Twelve retired rows carrying the OLD amounts (the first POSTED month reversed,
 * the eleven planned ones cancelled) and twelve fresh PLANNED rows for the new
 * rent, interleaved by `periodStart` exactly as the endpoint returns them.
 * Σ over everything is 111,000 — the number the footer must NOT show.
 */
function afterAmend(): RecognitionEntry[] {
    const rows: RecognitionEntry[] = [];
    let n = 0;
    const months = [
        "2026-01", "2026-02", "2026-03", "2026-04", "2026-05", "2026-06",
        "2026-07", "2026-08", "2026-09", "2026-10", "2026-11", "2026-12",
    ];
    const ends: Record<string, string> = {
        "2026-01": "31", "2026-02": "28", "2026-03": "31", "2026-04": "30",
        "2026-05": "31", "2026-06": "30", "2026-07": "31", "2026-08": "31",
        "2026-09": "30", "2026-10": "31", "2026-11": "30", "2026-12": "31",
    };
    for (const m of months) {
        const start = `${m}-01`;
        const end = `${m}-${ends[m]}`;
        const days = Number(ends[m]);
        const old: RecognitionStatus = m === "2026-01" ? "REVERSED" : "CANCELLED";
        rows.push({
            id: `old-${++n}`, leaseId: "lease-1", segmentId: "seg-old",
            propertyId: "prop-olv", propertyName: "L'Olivier", unitName: "204",
            periodStart: start, periodEnd: end, days, amount: 4250, status: old,
            journalId: old === "REVERSED" ? "j-old-1" : null,
            journalNumber: old === "REVERSED" ? "CIL/2026/0001" : null,
            postedAt: old === "REVERSED" ? "2026-02-01T00:00:00Z" : null,
        });
        rows.push({
            id: `new-${n}`, leaseId: "lease-1", segmentId: "seg-new",
            propertyId: "prop-olv", propertyName: "L'Olivier", unitName: "204",
            periodStart: start, periodEnd: end, days, amount: 5000, status: "PLANNED",
            journalId: null, journalNumber: null, postedAt: null,
        });
    }
    return rows;
}

function renderTab(props: { contractRent?: number | null; terminated?: boolean } = {}) {
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

    it("sums the LIVE plan on an amended contract, not every row the lease ever had", async () => {
        api.leaseSchedule.mockResolvedValue(afterAmend());
        renderTab({ contractRent: 60000 });

        const total = await screen.findByTestId("recognition-schedule-total");
        // 111,000.00 is Σ over everything — what the footer used to say, against
        // a contract rent of 60,000, flagged warning-yellow.
        expect(total).not.toHaveTextContent("111,000.00");
        expect(total).toHaveTextContent("60,000.00");
        expect(screen.getByTestId("recognition-schedule-check")).toHaveTextContent("Matches the contract rent.");
    });

    it("keeps the superseded rows visible, struck, and subtotalled on their own", async () => {
        api.leaseSchedule.mockResolvedValue(afterAmend());
        renderTab({ contractRent: 60000 });

        await waitFor(() => expect(screen.getByTestId("recognition-schedule")).toBeInTheDocument());
        // Nothing is dropped: 24 rows, 12 of them retired.
        expect(screen.getAllByTestId(/^recognition-row-/)).toHaveLength(24);
        expect(screen.getByTestId("recognition-schedule-superseded")).toHaveTextContent("51,000.00");

        // A REVERSED row reads as retired, not as part of the plan.
        const reversed = screen.getByTestId("recognition-row-0");
        expect(within(reversed).getByTestId("recognition-amount-0").className).toContain("line-through");
    });

    it("reports recognised-to-date against the plan on a terminated contract", async () => {
        renderTab({ contractRent: 120000, terminated: true });
        const check = await screen.findByTestId("recognition-schedule-check");
        // Two POSTED months of the thirteen-slice fixture: 4,602.74 + 10,191.78.
        expect(check).toHaveTextContent("14,794.52");
        // Never the equality claim: after a truncation the plan IS shorter than
        // the contract's rent, and saying so as a mismatch is a false alarm.
        expect(check).not.toHaveTextContent("Does not match");
        expect(check).not.toHaveTextContent("Matches the contract rent");
    });

    it("surfaces a failed load instead of an empty table", async () => {
        api.leaseSchedule.mockRejectedValue(new Error("boom"));
        renderTab();
        expect(await screen.findByRole("alert")).toHaveTextContent(
            "Couldn't load the recognition schedule. Please try again.",
        );
    });
});
