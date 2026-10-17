import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
    useRouter: () => ({ push: vi.fn() }),
}));
import RenewalQueue, { type QueueFilters } from "../RenewalQueue";
import { fetchRouter } from "@/test/fetchRouter";
import type { LeaseDetail } from "@/lib/api/leasing";

const lease = (id: string, over: Partial<LeaseDetail> = {}) => ({
    id, unitIdentifier: `U-${id}`, renterName: `Tenant ${id}`, renterId: `r-${id}`, propertyName: "Marina", propertyId: "p1",
    startDate: "2025-11-01", endDate: "2026-11-15", status: "ACTIVE", rentAmount: 80000, currentRentAmount: 80000,
    renewedToLeaseId: null, renewedToStatus: null, transferredToLeaseId: null, lines: [], version: 1, ...over,
}) as unknown as LeaseDetail;

const FILTERS: QueueFilters = { month: "2026-11", stage: "todo", propertyId: "", buildingId: "", floor: "", search: "" };
const ALL = { renew: true, extend: true, notice: true };

let api: ReturnType<typeof fetchRouter>;
let rows: LeaseDetail[];

beforeEach(() => {
    rows = [lease("a"), lease("b"), lease("c", { renewedToLeaseId: "succ-c", renewedToStatus: "DRAFT" })];
    api = fetchRouter();
    api.on("GET", "/finance/charge-types", { body: [] });
    api.on("GET", "/leases/paged", call => {
        const q = new URL(call.url, "http://x").searchParams;
        if (q.get("size") === "1") {
            const successor = q.get("successor");
            const total = successor === "NONE" && q.get("excludeStatus")!.split(",").includes("NOTICE_GIVEN") ? 4
                : successor === "NONE" ? 2 : successor === "DRAFTED" ? 1 : q.get("excludeStatus")!.split(",").includes("ACTIVE") ? 5 : 12;
            return { body: { totalElements: total, content: [] } };
        }
        const page = Number(q.get("page"));
        return { body: { totalElements: rows.length, content: page === 0 ? rows : [] } };
    });
});
afterEach(cleanup);

describe("renewal season queue", () => {
    it("reads the month's window and the stage from the server, nearest expiry first, and counts every stage", async () => {
        render(<RenewalQueue filters={FILTERS} onStage={() => {}} perms={ALL} />);
        expect(await screen.findByTestId("renewal-row-a")).toBeInTheDocument();
        expect(await screen.findByText("Renewals due in November 2026: 12")).toBeInTheDocument();
        await waitFor(() => expect(screen.getByTestId("renewal-stage-todo")).toHaveTextContent("To do 4"));
        expect(screen.getByTestId("renewal-stage-notRenewing")).toHaveTextContent("2");
        const list = api.callsTo("GET", "/leases/paged").map(c => new URL(c.url, "http://x").searchParams).find(q => q.get("size") === "25")!;
        expect(list.get("endDateFrom")).toBe("2026-11-01");
        expect(list.get("endDateTo")).toBe("2026-11-30");
        expect(list.get("sort")).toBe("endDate,asc");
        expect(list.get("successor")).toBe("NONE");
        // A contract with a renewal drafted shows it, links to it, and cannot be selected again.
        const drafted = screen.getByTestId("renewal-row-c");
        expect(within(drafted).getByText("Renewal Draft").closest("a")).toHaveAttribute("href", "/dashboard/leases/succ-c");
        expect(within(drafted).queryByRole("checkbox")).toBeNull();
    });

    it("bulk renews the selection with one rule, in chunks, and lists each contract's outcome", async () => {
        render(<RenewalQueue filters={FILTERS} onStage={() => {}} perms={ALL} />);
        fireEvent.click(await screen.findByTestId("renewal-select-page"));
        expect(screen.getByTestId("renewal-selected-count")).toHaveTextContent("2 selected");
        api.on("POST", "/leases/renew-batch", {
            body: [
                { leaseId: "a", ok: true, renewalLeaseId: "na", startDate: "2026-11-16", endDate: "2027-11-15", previousRent: 80000, newRent: 84000, changePercent: 5, chequeRows: 4 },
                { leaseId: "b", ok: false, renewalLeaseId: null, code: "lease.notFound", message: "Lease not found", chequeRows: 0 },
            ],
        });
        fireEvent.click(screen.getByTestId("renewal-bulk-renew"));
        fireEvent.click(screen.getByTestId("bulk-renew-mode-PERCENT"));
        fireEvent.change(screen.getByTestId("bulk-renew-percent"), { target: { value: "5" } });
        expect(screen.getByTestId("bulk-renew-example")).toHaveTextContent("AED 80,000.00 → AED 84,000.00");
        fireEvent.click(screen.getByTestId("bulk-renew-confirm"));

        const results = await screen.findByTestId("bulk-renew-results");
        const [post] = api.callsTo("POST", "/leases/renew-batch");
        expect(post.body).toMatchObject({
            leaseIds: ["a", "b"], rule: { mode: "PERCENT", percent: 5, amount: null }, termMonths: null,
            carryDepositForward: true, generateGrid: true, additionalLines: null,
        });
        expect(within(results).getByTestId("bulk-renew-result-a")).toHaveTextContent("drafted AED 80,000.00 → AED 84,000.00");
        expect(within(results).getByTestId("bulk-renew-result-b")).toHaveTextContent("Contract not found.");
        expect(within(results).getByTestId("bulk-renew-review-drafts")).toHaveAttribute("href", "/dashboard/leases?view=draft");

        // Retry sends only the refused contract, and keeps the others' results.
        api.on("POST", "/leases/renew-batch", { body: [{ leaseId: "b", ok: true, renewalLeaseId: "nb", newRent: 84000, previousRent: 80000, chequeRows: 4 }] });
        fireEvent.click(within(results).getByTestId("bulk-renew-retry"));
        await waitFor(() => expect(screen.getByTestId("bulk-renew-result-b")).toHaveAttribute("data-ok", "true"));
        expect(api.callsTo("POST", "/leases/renew-batch")[1].body).toMatchObject({ leaseIds: ["b"] });
        expect(screen.getByTestId("bulk-renew-result-a")).toHaveAttribute("data-ok", "true");
    });

    it("selects every renewable contract of the stage across pages and sends them 25 at a time", async () => {
        rows = Array.from({ length: 30 }, (_, i) => lease(`l${i}`));
        api.on("POST", "/leases/renew-batch", call => ({
            body: (call.body as { leaseIds: string[] }).leaseIds.map(id => ({ leaseId: id, ok: true, renewalLeaseId: `n-${id}`, newRent: 81235, previousRent: 80000, chequeRows: 0 })),
        }));
        render(<RenewalQueue filters={FILTERS} onStage={() => {}} perms={ALL} />);
        fireEvent.click(await screen.findByTestId("renewal-select-all"));
        await waitFor(() => expect(screen.getByTestId("renewal-selected-count")).toHaveTextContent("30 selected"));
        fireEvent.click(screen.getByTestId("renewal-bulk-renew"));
        fireEvent.click(screen.getByTestId("bulk-renew-mode-ADD"));
        fireEvent.change(screen.getByTestId("bulk-renew-amount"), { target: { value: "1234.50" } });
        fireEvent.click(screen.getByTestId("bulk-renew-same-term"));
        fireEvent.change(screen.getByTestId("bulk-renew-months"), { target: { value: "24" } });
        fireEvent.click(screen.getByTestId("bulk-renew-grid"));
        fireEvent.click(screen.getByTestId("bulk-renew-confirm"));
        await screen.findByTestId("bulk-renew-results");
        const posts = api.callsTo("POST", "/leases/renew-batch").map(c => c.body as { leaseIds: string[]; rule: unknown; termMonths: number; generateGrid: boolean });
        expect(posts.map(p => p.leaseIds.length)).toEqual([25, 5]);
        expect(posts[0]).toMatchObject({ rule: { mode: "ADD", amount: 1234.5, percent: null }, termMonths: 24, generateGrid: false });
        expect(screen.getByText("30 drafted, 0 refused.")).toBeInTheDocument();
    });

    it("offers only the actions the role may take", async () => {
        render(<RenewalQueue filters={FILTERS} onStage={() => {}} perms={{ renew: false, extend: false, notice: true }} />);
        const row = await screen.findByTestId("renewal-row-a");
        expect(within(row).queryByRole("checkbox")).toBeNull();
        expect(screen.queryByTestId("renewal-select-all")).toBeNull();
        fireEvent.click(screen.getByTestId("renewal-actions-trigger-a"));
        expect(screen.queryByTestId("queue-renew-a")).toBeNull();
        expect(screen.queryByTestId("queue-extend-a")).toBeNull();
        expect(screen.getByTestId("queue-not-renewing-a")).toBeInTheDocument();
        expect(screen.getByTestId("queue-contact-a")).toBeInTheDocument();
    });

    it("marks a contract not renewing by giving notice, and re-reads the queue", async () => {
        api.on("POST", "/leases/a/notice", { body: lease("a", { status: "NOTICE_GIVEN" }) });
        render(<RenewalQueue filters={FILTERS} onStage={() => {}} perms={ALL} />);
        fireEvent.click(await screen.findByTestId("renewal-actions-trigger-a"));
        fireEvent.click(screen.getByTestId("queue-not-renewing-a"));
        const reads = api.callsTo("GET", "/leases/paged").length;
        fireEvent.click(screen.getByTestId("lease-give-notice-confirm"));
        await waitFor(() => expect(api.callsTo("POST", "/leases/a/notice")).toHaveLength(1));
        expect(api.callsTo("POST", "/leases/a/notice")[0].body).toMatchObject({ givenBy: "RENTER", notes: "Not renewing" });
        await waitFor(() => expect(api.callsTo("GET", "/leases/paged").length).toBeGreaterThan(reads));
    });

    it("shows the tenant's phone, WhatsApp and email for Contact", async () => {
        api.on("GET", "/renters/r-a", { body: { nameEn: "Tenant a", phone: "+971 50 123 4567", email: "a@example.com" } });
        render(<RenewalQueue filters={FILTERS} onStage={() => {}} perms={ALL} />);
        fireEvent.click(await screen.findByTestId("renewal-actions-trigger-a"));
        fireEvent.click(screen.getByTestId("queue-contact-a"));
        expect(await screen.findByTestId("renewal-contact-phone")).toHaveAttribute("href", "tel:+971 50 123 4567");
        expect(screen.getByTestId("renewal-contact-whatsapp")).toHaveAttribute("href", "https://wa.me/971501234567");
        expect(screen.getByTestId("renewal-contact-email")).toHaveAttribute("href", "mailto:a@example.com");
    });
});
