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

    it("drafts a VAT-able renewal fee with its charge type's VAT, as the single renew does", async () => {
        api.on("GET", "/finance/charge-types", { body: [
            { id: "ct-fee", code: "ADMIN_FEE", nameEn: "Admin Fee", nameAr: null, behaviour: "FEE", active: true, vatApplicableDefault: true },
        ] });
        api.on("POST", "/leases/renew-batch", call => ({
            body: (call.body as { leaseIds: string[] }).leaseIds.map(id => ({ leaseId: id, ok: true, renewalLeaseId: `n-${id}`, newRent: 80000, previousRent: null, chequeRows: 0 })),
        }));
        render(<RenewalQueue filters={FILTERS} onStage={() => {}} perms={ALL} />);
        fireEvent.click(await screen.findByTestId("renewal-select-page"));
        fireEvent.click(screen.getByTestId("renewal-bulk-renew"));
        fireEvent.change(await screen.findByTestId("bulk-renew-fee-type"), { target: { value: "ct-fee" } });
        fireEvent.change(screen.getByTestId("bulk-renew-fee-amount"), { target: { value: "1000" } });
        fireEvent.click(screen.getByTestId("bulk-renew-confirm"));
        await screen.findByTestId("bulk-renew-results");
        expect(api.callsTo("POST", "/leases/renew-batch")[0].body).toMatchObject({
            additionalLines: [{ chargeTypeId: "ct-fee", grossAmount: 1000, discountAmount: 0, vatApplicable: true }],
        });
    });

    it("lists every contract of a chunk whose call failed, carries on with the next chunks, and retries them", async () => {
        rows = Array.from({ length: 60 }, (_, i) => lease(`l${i}`));
        let call = 0;
        api.on("POST", "/leases/renew-batch", c => {
            call++;
            if (call === 2) return { status: 502, body: { message: "Bad gateway" } };
            return { body: (c.body as { leaseIds: string[] }).leaseIds.map(id => ({ leaseId: id, ok: true, renewalLeaseId: `n-${id}`, newRent: 80000, previousRent: null, chequeRows: 0 })) };
        });
        render(<RenewalQueue filters={FILTERS} onStage={() => {}} perms={ALL} />);
        fireEvent.click(await screen.findByTestId("renewal-select-all"));
        await waitFor(() => expect(screen.getByTestId("renewal-selected-count")).toHaveTextContent("60 selected"));
        fireEvent.click(screen.getByTestId("renewal-bulk-renew"));
        fireEvent.click(screen.getByTestId("bulk-renew-confirm"));
        await screen.findByTestId("bulk-renew-results");
        // Three chunks were sent although the second failed; all 60 are counted.
        expect(api.callsTo("POST", "/leases/renew-batch").map(c => (c.body as { leaseIds: string[] }).leaseIds.length)).toEqual([25, 25, 10]);
        expect(screen.getByText("35 drafted, 25 refused.")).toBeInTheDocument();
        expect(screen.getByTestId("bulk-renew-result-l30")).toHaveAttribute("data-ok", "false");
        expect(screen.getByTestId("bulk-renew-result-l30")).toHaveTextContent("Not processed");
        expect(screen.getByText(/Some contracts could not be sent/)).toBeInTheDocument();
        // Retry sends exactly the 25 that were not processed.
        fireEvent.click(screen.getByTestId("bulk-renew-retry"));
        await waitFor(() => expect(screen.getByText("60 drafted, 0 refused.")).toBeInTheDocument());
        const retry = api.callsTo("POST", "/leases/renew-batch")[3].body as { leaseIds: string[] };
        expect(retry.leaseIds).toEqual(Array.from({ length: 25 }, (_, i) => `l${i + 25}`));
    });

    it("never sweeps a contract under notice into a bulk renew", async () => {
        rows = [lease("a"), lease("n", { status: "NOTICE_GIVEN" })];
        render(<RenewalQueue filters={{ ...FILTERS, stage: "all" }} onStage={() => {}} perms={ALL} />);
        const notice = await screen.findByTestId("renewal-row-n");
        expect(within(notice).queryByRole("checkbox")).toBeNull();
        fireEvent.click(screen.getByTestId("renewal-select-all"));
        await waitFor(() => expect(screen.getByTestId("renewal-selected-count")).toHaveTextContent("1 selected"));
        // The row action still renews it on its own.
        fireEvent.click(screen.getByTestId("renewal-actions-trigger-n"));
        expect(screen.getByTestId("queue-renew-n")).toBeInTheDocument();
    });

    it("offers only the actions the role may take", async () => {
        render(<RenewalQueue filters={FILTERS} onStage={() => {}} perms={{ renew: false, extend: false, notice: true }} />);
        const row = await screen.findByTestId("renewal-row-a");
        expect(within(row).queryByRole("checkbox")).toBeNull();
        expect(screen.queryByTestId("renewal-select-all")).toBeNull();
        fireEvent.click(screen.getByTestId("renewal-actions-trigger-a"));
        expect(screen.queryByTestId("queue-renew-a")).toBeNull();
        expect(screen.queryByTestId("queue-extend-a")).toBeNull();
        expect(screen.queryByTestId("queue-not-renewing-a")).toBeNull();
        expect(screen.getByTestId("queue-give-notice-a")).toBeInTheDocument();
        expect(screen.getByTestId("queue-contact-a")).toBeInTheDocument();
    });

    it("marks a contract not renewing with a soft, reversible note that gives no notice", async () => {
        api.on("POST", "/leases/a/renewal/not-renewing", { body: { leaseId: "a", notRenewing: true, at: "2026-10-17T08:00:00Z", by: "u1", note: "Moving" } });
        render(<RenewalQueue filters={FILTERS} onStage={() => {}} perms={ALL} />);
        fireEvent.click(await screen.findByTestId("renewal-actions-trigger-a"));
        fireEvent.click(screen.getByTestId("queue-not-renewing-a"));
        const reads = api.callsTo("GET", "/leases/paged").length;
        fireEvent.change(screen.getByTestId("renewal-mark-note"), { target: { value: "Moving" } });
        fireEvent.click(screen.getByTestId("renewal-mark-confirm"));
        await waitFor(() => expect(api.callsTo("POST", "/leases/a/renewal/not-renewing")).toHaveLength(1));
        expect(api.callsTo("POST", "/leases/a/renewal/not-renewing")[0].body).toEqual({ note: "Moving" });
        // Not a notice: the notice endpoint is never called.
        expect(api.callsTo("POST", "/notice")).toHaveLength(0);
        await waitFor(() => expect(api.callsTo("GET", "/leases/paged").length).toBeGreaterThan(reads));
    });

    it("shows who marked a contract, lets it be undone, and keeps Renew and Extend available", async () => {
        rows = [lease("m", { notRenewingAt: "2026-10-10T08:00:00Z", notRenewingByName: "Paul PM", notRenewingNote: "Moving" })];
        api.on("DELETE", "/leases/m/renewal/not-renewing", { body: { leaseId: "m", notRenewing: false } });
        render(<RenewalQueue filters={{ ...FILTERS, stage: "notRenewing" }} onStage={() => {}} perms={ALL} />);
        const row = await screen.findByTestId("renewal-row-m");
        expect(row).toHaveTextContent("Not renewing — marked by Paul PM on 10/10/2026 · Moving");
        expect(within(row).queryByRole("checkbox")).toBeNull();
        fireEvent.click(screen.getByTestId("renewal-actions-trigger-m"));
        expect(screen.getByTestId("queue-renew-m")).toBeInTheDocument();
        expect(screen.getByTestId("queue-extend-m")).toBeInTheDocument();
        expect(screen.getByTestId("queue-give-notice-m")).toBeInTheDocument();
        expect(screen.queryByTestId("queue-not-renewing-m")).toBeNull();
        fireEvent.click(screen.getByTestId("queue-undo-not-renewing-m"));
        await waitFor(() => expect(api.callsTo("DELETE", "/leases/m/renewal/not-renewing")).toHaveLength(1));
    });

    it("gives notice as its own action", async () => {
        api.on("POST", "/leases/a/notice", { body: lease("a", { status: "NOTICE_GIVEN" }) });
        render(<RenewalQueue filters={FILTERS} onStage={() => {}} perms={ALL} />);
        fireEvent.click(await screen.findByTestId("renewal-actions-trigger-a"));
        fireEvent.click(screen.getByTestId("queue-give-notice-a"));
        fireEvent.click(screen.getByTestId("lease-give-notice-confirm"));
        await waitFor(() => expect(api.callsTo("POST", "/leases/a/notice")).toHaveLength(1));
        expect(api.callsTo("POST", "/renewal/not-renewing")).toHaveLength(0);
    });

    it("numbers the cheques of the selected renewal drafts from the drafted stage", async () => {
        rows = [lease("p1", { renewedToLeaseId: "draft-1", renewedToStatus: "DRAFT" }), lease("p2", { renewedToLeaseId: "draft-2", renewedToStatus: "PENDING_SIGNATURE" })];
        api.on("POST", "/leases/cheques-batch/load", c => ({
            body: (c.body as { leaseIds: string[] }).leaseIds.map(id => ({ leaseId: id, ok: true, version: 1, contractNumber: null, cheques: [] })),
        }));
        render(<RenewalQueue filters={{ ...FILTERS, stage: "drafted" }} onStage={() => {}} perms={ALL} />);
        const pending = await screen.findByTestId("renewal-row-p2");
        expect(within(pending).queryByRole("checkbox")).toBeNull();
        fireEvent.click(screen.getByTestId("renewal-select-page"));
        expect(screen.queryByTestId("renewal-bulk-renew")).toBeNull();
        fireEvent.click(screen.getByTestId("renewal-number-cheques"));
        await screen.findByTestId("numbering-dialog");
        await waitFor(() => expect(api.callsTo("POST", "/leases/cheques-batch/load")).toHaveLength(1));
        expect(api.callsTo("POST", "/leases/cheques-batch/load")[0].body).toEqual({ leaseIds: ["draft-1"] });
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
