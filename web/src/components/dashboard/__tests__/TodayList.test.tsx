import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
}));
vi.mock("@/components/dashboard/FollowUpsWidget", () => ({ default: () => <div data-testid="follow-ups-widget" /> }));
const recognition = vi.hoisted(() => ({ behind: 0, lastRunFailed: 0 }));
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return {
        ...m,
        chequeApi: {
            ...m.chequeApi,
            toDeposit: vi.fn().mockResolvedValue({ totalElements: 3 }),
            summary: vi.fn().mockResolvedValue({ overdueCount: 2, dueCount: 0, bouncedCount: 0 }),
        },
        penaltyApi: { ...m.penaltyApi, list: vi.fn().mockResolvedValue({ totalElements: 1 }) },
        recognitionApi: { ...m.recognitionApi, status: vi.fn(async () => ({ ...recognition })) },
    };
});
import TodayList, { todayRowDefs } from "../TodayList";
import { chequeApi } from "@/lib/api/leasing";
import type { PipelineStage } from "@/lib/dashboard/pipeline";

let ticketCalls: string[] = [];
beforeEach(() => {
    ticketCalls = [];
    recognition.behind = 0;
    recognition.lastRunFailed = 0;
    global.fetch = vi.fn(async (u: RequestInfo | URL) => {
        const url = String(u);
        if (url.includes("/tickets/paged")) {
            ticketCalls.push(url);
            const status = new URL(url, "http://x").searchParams.get("status");
            return { ok: true, status: 200, json: async () => ({ totalElements: status === "OPEN" ? 1 : status === "REOPENED" ? 1 : 0 }) };
        }
        return { ok: true, status: 200, json: async () => [{}, {}] };
    }) as unknown as typeof fetch;
});
afterEach(cleanup);

const stage = (id: string, count: number, capped = false) => ({ id, count, capped, oldest: null, href: "" }) as PipelineStage;

describe("Needs you now", () => {
    it("gates rows by the pages they open", () => {
        expect(todayRowDefs("TENANT_ADMIN").map(r => r.id)).toEqual(
            ["deposit", "overdue", "ending", "drafts", "tickets", "penalties", "recognition", "recognitionFailed", "followUps"]);
        expect(todayRowDefs("PROPERTY_MANAGER").map(r => r.id)).toEqual(["deposit", "overdue", "ending", "drafts", "tickets", "followUps"]);
        expect(todayRowDefs("ACCOUNTANT").map(r => r.id)).toEqual(
            ["deposit", "overdue", "ending", "drafts", "penalties", "recognition", "recognitionFailed", "followUps"]);
        expect(todayRowDefs("TENANT_USER")).toEqual([]);
        expect(todayRowDefs("RENTER")).toEqual([]);
    });

    it("links the cheque rows at the Collection tabs and hides zero rows", async () => {
        render(<TodayList role="TENANT_ADMIN" pipeline={[stage("expiring", 5), stage("draft", 0)]} />);
        expect(await screen.findByTestId("today-deposit")).toHaveAttribute("href", "/dashboard/collections?tab=deposit");
        expect(screen.getByTestId("today-deposit")).toHaveTextContent("3");
        expect(await screen.findByTestId("today-overdue")).toHaveAttribute("href", "/dashboard/collections?tab=overdue");
        expect(screen.getByTestId("today-ending")).toHaveTextContent("5");
        expect(await screen.findByTestId("today-tickets")).toHaveTextContent("2");
        expect(await screen.findByTestId("today-penalties")).toHaveAttribute("href", "/dashboard/collections?tab=penalties");
        expect(screen.queryByTestId("today-drafts")).toBeNull();
        expect(screen.queryByTestId("today-recognition")).toBeNull();
        // Counts only: one size=1 read per open ticket status, never the whole list.
        expect(ticketCalls).toHaveLength(4);
        expect(ticketCalls.every(u => u.includes("size=1"))).toBe(true);
    });

    it("keeps the recognition warnings the old widget showed (behind, and a failed last run)", async () => {
        recognition.behind = 4;
        recognition.lastRunFailed = 2;
        render(<TodayList role="ACCOUNTANT" pipeline={null} />);
        expect(await screen.findByTestId("today-recognition")).toHaveTextContent("4");
        expect(screen.getByTestId("today-recognition-failed")).toHaveTextContent("2");
        expect(screen.getByTestId("today-recognition-failed")).toHaveAttribute("href", "/dashboard/finance/recognition");
    });

    it("shows a capped expiring count as N+ and opens follow-ups in place", async () => {
        render(<TodayList role="PROPERTY_MANAGER" pipeline={[stage("expiring", 100, true)]} />);
        expect(await screen.findByTestId("today-ending")).toHaveTextContent("100+");
        expect(await screen.findByTestId("today-follow-ups")).toBeInTheDocument();
        expect(screen.getByTestId("follow-ups-widget")).toBeInTheDocument();
    });

    it("says so when nothing needs attention, and renders nothing for a role with no rows", async () => {
        global.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => ({ totalElements: 0 }) })) as unknown as typeof fetch;
        const { container, unmount } = render(<TodayList role="TENANT_USER" pipeline={null} />);
        expect(container).toBeEmptyDOMElement();
        unmount();
        vi.mocked(chequeApi.toDeposit).mockResolvedValueOnce({ totalElements: 0 } as never);
        vi.mocked(chequeApi.summary).mockResolvedValueOnce({ overdueCount: 0, dueCount: 0, bouncedCount: 0 } as never);
        render(<TodayList role="PROPERTY_MANAGER" pipeline={[stage("expiring", 0), stage("draft", 0)]} />);
        await waitFor(() => expect(chequeApi.summary).toHaveBeenCalled());
        await waitFor(() => expect(global.fetch).toHaveBeenCalledTimes(5)); // 4 ticket counts + follow-ups
        expect(screen.getByTestId("today-all-clear")).toBeInTheDocument();
    });
});
