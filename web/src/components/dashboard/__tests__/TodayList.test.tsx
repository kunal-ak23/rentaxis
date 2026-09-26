import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
}));
vi.mock("@/components/dashboard/FollowUpsWidget", () => ({ default: () => <div data-testid="follow-ups-widget" /> }));
const recognition = vi.hoisted(() => ({ behind: 0, lastRunFailed: 0, behindAmount: 0, oldestPeriodEnd: null as string | null }));
const cq = vi.hoisted(() => (id: string, over: Record<string, unknown> = {}) => ({ id, leaseId: `l-${id}`, unitIdentifier: `A-${id}`, renterName: `Renter ${id}`, amount: 1000, chequeNumber: `N${id}`, overdue: false, ...over }));
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return {
        ...m,
        chequeApi: {
            ...m.chequeApi,
            toDeposit: vi.fn().mockResolvedValue({ totalElements: 3, content: [cq("1"), cq("2"), cq("3")] }),
            due: vi.fn().mockResolvedValue({ content: [cq("4", { overdue: true, amount: 5000 }), cq("5"), cq("6", { overdue: true })] }),
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
    recognition.behindAmount = 0;
    recognition.oldestPeriodEnd = null;
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

    it("lists the top rows the old Home widgets showed, each linking to its contract (R1 P3-6)", async () => {
        render(<TodayList role="TENANT_ADMIN" pipeline={null} />);
        const deposit = await screen.findByTestId("today-deposit-rows");
        expect(deposit.querySelectorAll("a")).toHaveLength(3);
        expect(deposit.querySelector("a")).toHaveAttribute("href", "/dashboard/leases/l-1");
        expect(deposit).toHaveTextContent("A-1 · Renter 1 · #N1");
        const overdue = await screen.findByTestId("today-overdue-rows");
        expect(Array.from(overdue.querySelectorAll("a")).map(a => a.getAttribute("href"))).toEqual(["/dashboard/leases/l-4", "/dashboard/leases/l-6"]);
    });

    it("keeps the recognition warnings the old widget showed (behind with amount and oldest period, and a failed last run)", async () => {
        recognition.behind = 4;
        recognition.lastRunFailed = 2;
        recognition.behindAmount = 12500;
        recognition.oldestPeriodEnd = "2026-06-30";
        render(<TodayList role="ACCOUNTANT" pipeline={null} />);
        expect(await screen.findByTestId("today-recognition")).toHaveTextContent("4");
        expect(screen.getByTestId("today-recognition-detail")).toHaveTextContent("12,500.00 not yet recognised · oldest period 30/06/2026");
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
        vi.mocked(chequeApi.toDeposit).mockResolvedValue({ totalElements: 0, content: [] } as never);
        vi.mocked(chequeApi.due).mockResolvedValue({ content: [] } as never);
        vi.mocked(chequeApi.summary).mockResolvedValueOnce({ overdueCount: 0, dueCount: 0, bouncedCount: 0 } as never);
        render(<TodayList role="PROPERTY_MANAGER" pipeline={[stage("expiring", 0), stage("draft", 0)]} />);
        await waitFor(() => expect(chequeApi.summary).toHaveBeenCalled());
        await waitFor(() => expect(global.fetch).toHaveBeenCalledTimes(5)); // 4 ticket counts + follow-ups
        expect(screen.getByTestId("today-all-clear")).toBeInTheDocument();
    });
});
