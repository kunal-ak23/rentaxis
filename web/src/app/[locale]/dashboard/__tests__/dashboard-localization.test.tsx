import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import * as fs from "node:fs";
import * as path from "node:path";

import ar from "../../../../../messages/ar.json";
import en from "../../../../../messages/en.json";

vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { name: "Tutorial Admin", role: "TENANT_ADMIN" } } }),
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));
vi.mock("@/components/dashboard/FollowUpsWidget", () => ({ default: () => null }));

import DashboardPage from "../page";

/**
 * The dashboard home is the first screen anyone sees after signing in, and it
 * had no useTranslations call at all — every label was an English literal, even
 * though a Dashboard namespace with 24 translated keys already existed and sat
 * unused.
 */

const SUMMARY = {
    totalProperties: 1,
    totalUnits: 3,
    occupiedUnits: 0,
    vacantUnits: 3,
    occupancyRate: 0,
    activeLeases: 0,
    draftLeases: 3,
    expiringLeases: 0,
    overdueAmount: 0,
    collectedAmount: 0,
    pendingThisMonthAmount: 0,
    totalRentRevenue: 0,
    dueThisMonth: 3667,
    collectedAgainstDueThisMonth: 0,
    collectedArrears: 139550,
    collectedAdvance: 0,
    recentActivity: [],
};

beforeEach(() => {
    global.fetch = vi.fn(async (url: unknown) => {
        const u = String(url);
        if (u.includes("monthly")) return { ok: true, json: async () => [] } as Response;
        if (u.includes("/leases/paged")) return { ok: true, json: async () => ({ content: [], totalElements: 2, totalPages: 1, number: 0, size: 1 }) } as Response;
        if (u.includes("/dashboard/summary")) return { ok: true, json: async () => SUMMARY } as Response;
        return { ok: true, json: async () => [] } as Response;
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.clearAllMocks();
});

function renderAr() {
    return render(
        <NextIntlClientProvider locale="ar" messages={ar}>
            <DashboardPage />
        </NextIntlClientProvider>,
    );
}

describe("dashboard home localization", () => {
    it("renders its cards and totals in Arabic", async () => {
        renderAr();

        await waitFor(() =>
            expect(screen.getByText(ar.Dashboard.collectedAgainstDues)).toBeInTheDocument(),
        );
        expect(screen.getByText(ar.Dashboard.pendingThisMonth)).toBeInTheDocument();
        // Spec §1a/§6: the pipeline, "Needs you now" and Unit Status replaced the portfolio snapshot.
        expect(await screen.findByText(ar.Today.pipelineTitle)).toBeInTheDocument();
        expect(screen.getByText(ar.Today.title)).toBeInTheDocument();
        // "Unit Status" heads both the KPI tile and the board (same words in both catalogs).
        expect(screen.getAllByText(ar.Today.unitStatus)).toHaveLength(2);
        expect(screen.getByTestId("unit-board")).toHaveTextContent(ar.UnitBoard.title);
        expect(screen.getByText(ar.Dashboard.newLease)).toBeInTheDocument();
    });

    it("leaves no English card label on the Arabic page", async () => {
        const { container } = renderAr();
        await waitFor(() =>
            expect(screen.getByText(ar.Dashboard.collectedAgainstDues)).toBeInTheDocument(),
        );
        const text = container.textContent ?? "";

        for (const literal of [
            "Collected against this month", "arrears collected", "Pending this month", "Portfolio snapshot",
            "Current totals", "Draft leases", "Vacant units", "New lease", "Export",
            "Recent activity", "Requires follow-up",
            // The two summary widgets, previously stubbed out of this test and
            // therefore the last English text left on the Arabic dashboard.
            "Overdue payments", "Cheques to deposit", "View all",
            // PR 3's Home blocks.
            "Contract pipeline", "Needs you now", "Unit Status", "Settlement due", "Draft contracts to post",
            "Nothing needs you right now", "No properties yet", "Occupied", "Vacant",
        ]) {
            expect(text, `"${literal}" should not appear on the Arabic dashboard`).not.toContain(literal);
        }
    });

    it("greets by time of day rather than always saying good morning", async () => {
        // The greeting was the literal "Good morning, {name}" at every hour, so
        // an afternoon demo opened on "Good morning".
        vi.useFakeTimers({ shouldAdvanceTime: true });
        vi.setSystemTime(new Date("2026-09-10T15:30:00"));

        renderAr();
        await waitFor(() =>
            expect(screen.getByText(ar.Dashboard.collectedAgainstDues)).toBeInTheDocument(),
        );

        const afternoon = ar.Dashboard.greetingAfternoon.replace("{name}", "Tutorial");
        const morning = ar.Dashboard.greetingMorning.replace("{name}", "Tutorial");
        expect(screen.getByText(afternoon)).toBeInTheDocument();
        expect(screen.queryByText(morning)).toBeNull();
    });

    it("has a genuinely different Arabic string for every key the page calls", () => {
        const source = fs.readFileSync(path.join(__dirname, "..", "page.tsx"), "utf8");
        const keys = [...new Set([...source.matchAll(/\bt\("([^"]+)"/g)].map((m) => m[1]))];
        expect(keys.length).toBeGreaterThan(15);

        const arNs = ar.Dashboard as Record<string, string>;
        const enNs = en.Dashboard as Record<string, string>;
        const todayKeys = [...new Set([...source.matchAll(/\btToday\("([^"]+)"/g)].map((m) => m[1]))];
        const arToday = ar.Today as Record<string, string>;
        expect(todayKeys.length).toBeGreaterThan(2);
        expect(todayKeys.filter((k) => !(k in arToday) || arToday[k] === (en.Today as Record<string, string>)[k])).toEqual([]);

        // A key that is missing renders as its own key path; a key whose Arabic
        // value is still the English text renders English. Parity alone sees
        // neither.
        expect(keys.filter((k) => !(k in arNs)), "keys absent from ar.json").toEqual([]);
        expect(keys.filter((k) => arNs[k] === enNs[k]), "keys still holding English").toEqual([]);
    });
});
