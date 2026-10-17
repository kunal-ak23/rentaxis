import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
    useRouter: () => ({ push: vi.fn() }),
}));
import BulkTerminate from "../BulkTerminate";
import { fetchRouter } from "@/test/fetchRouter";

const lease = (id: string) => ({ id, unitIdentifier: `U-${id}`, renterName: `Tenant ${id}`, propertyName: "Marina", status: "ACTIVE", endDate: "2026-10-31", rentAmount: 60000 });
const cheque = (amount: number, id = `c${amount}`) => ({ id, amount });
const preview = (over: Record<string, unknown> = {}) => ({
    terminationDate: "2026-10-31", earnedRentThroughDate: 60000, recognisedSoFar: 55000, unearnedRent: 0, unearnedVat: 0,
    chequesToReturn: [], chequesToKeep: [cheque(15000)], bouncedOutstanding: [], receivableAfter: 15000,
    vatSettlement: null, problems: [], notices: [], ...over,
});
const FILTERS = { date: "2026-10-31", propertyId: "", buildingId: "", floor: "", search: "" };

let api: ReturnType<typeof fetchRouter>;
beforeEach(() => {
    api = fetchRouter();
    api.on("GET", "/leases/paged", { body: { totalElements: 3, content: [lease("a"), lease("b"), lease("c")] } });
    api.on("POST", "/leases/terminate-batch/preview", {
        body: [
            { leaseId: "a", ok: true, terminationDate: "2026-10-31", preview: preview() },
            { leaseId: "b", ok: true, terminationDate: "2026-10-31", preview: preview({ problems: ["The landlord has no TRN for the credit note."], notices: ["The renewal draft for 01/11/2026 – 31/10/2027 will be discarded."] }) },
            { leaseId: "c", ok: false, terminationDate: null, preview: null, message: "Cannot terminate on 2026-10-31: books are locked through 2026-10-31." },
        ],
    });
});
afterEach(cleanup);

describe("bulk terminate", () => {
    it("lists the running contracts ending on the date", async () => {
        render(<BulkTerminate filters={FILTERS} onDateChange={() => {}} canTerminate />);
        expect(await screen.findByTestId("bulk-terminate-row-a")).toBeInTheDocument();
        const q = new URL(api.callsTo("GET", "/leases/paged")[0].url, "http://x").searchParams;
        expect(q.get("endDateFrom")).toBe("2026-10-31");
        expect(q.get("endDateTo")).toBe("2026-10-31");
        expect(q.get("excludeStatus")!.split(",")).toEqual(["DRAFT", "PENDING_SIGNATURE", "RENEWED", "TERMINATED", "EXPIRED", "CLOSED"]);
        expect(screen.getByTestId("bulk-terminate-count")).toHaveTextContent("3 ending on");
    });

    it("previews each contract, leaves out the ones that would be refused, and terminates only what is included", async () => {
        render(<BulkTerminate filters={FILTERS} onDateChange={() => {}} canTerminate />);
        fireEvent.click(await screen.findByTestId("bulk-terminate-select-page"));
        fireEvent.click(screen.getByTestId("bulk-terminate-preview"));
        const table = await screen.findByTestId("bulk-terminate-previews");
        expect(api.callsTo("POST", "/terminate-batch/preview")[0].body).toEqual({ leaseIds: ["a", "b", "c"], terminationDate: "2026-10-31" });

        const a = within(table).getByTestId("bulk-terminate-preview-a");
        expect(a).toHaveTextContent("AED 60,000.00");
        expect(within(a).getByRole("checkbox")).toBeChecked();
        const b = within(table).getByTestId("bulk-terminate-preview-b");
        expect(b).toHaveTextContent("no TRN");
        expect(b).toHaveTextContent("will be discarded");
        expect(within(b).getByRole("checkbox")).not.toBeChecked();
        const c = within(table).getByTestId("bulk-terminate-preview-c");
        expect(c).toHaveTextContent("books are locked");
        expect(within(c).getByRole("checkbox")).toBeDisabled();
        expect(screen.getByText("1 of 3 included")).toBeInTheDocument();

        api.on("POST", "/leases/terminate-batch", { body: [{ leaseId: "a", ok: true, terminationDate: "2026-10-31", status: "TERMINATED", terminationJournalId: null }] });
        fireEvent.click(screen.getByTestId("bulk-terminate-confirm-open"));
        fireEvent.change(screen.getByTestId("bulk-terminate-notes"), { target: { value: "Season move-out" } });
        fireEvent.click(screen.getByTestId("bulk-terminate-confirm"));
        const results = await screen.findByTestId("bulk-terminate-results");
        const posted = api.callsTo("POST", "/leases/terminate-batch").filter(x => !x.url.includes("preview"));
        expect(posted).toHaveLength(1);
        expect(posted[0].body).toEqual({ leaseIds: ["a"], terminationDate: "2026-10-31", notes: "Season move-out",
            splits: { a: { returnChequeIds: [], keepChequeIds: ["c15000"] } } });
        expect(within(results).getByTestId("bulk-terminate-result-a")).toHaveTextContent("terminated on");
        // Named by unit and tenant even though the selection was cleared after the run.
        expect(within(results).getByTestId("bulk-terminate-result-a")).toHaveTextContent("U-a · Tenant a");
    });

    it("confirms with each preview's cheque split", async () => {
        api.on("POST", "/leases/terminate-batch/preview", { body: [
            { leaseId: "a", ok: true, terminationDate: "2026-10-31", preview: preview({ chequesToReturn: [cheque(5000, "r1")], chequesToKeep: [cheque(15000, "k1")] }) },
        ] });
        api.on("POST", /\/leases\/terminate-batch$/, { body: [{ leaseId: "a", ok: true, terminationDate: "2026-10-31", status: "TERMINATED" }] });
        render(<BulkTerminate filters={FILTERS} onDateChange={() => {}} canTerminate />);
        fireEvent.click(await screen.findByTestId("bulk-terminate-select-page"));
        fireEvent.click(screen.getByTestId("bulk-terminate-preview"));
        await screen.findByTestId("bulk-terminate-previews");
        fireEvent.click(screen.getByTestId("bulk-terminate-confirm-open"));
        fireEvent.click(screen.getByTestId("bulk-terminate-confirm"));
        await screen.findByTestId("bulk-terminate-results");
        const posted = api.callsTo("POST", "/leases/terminate-batch").filter(x => !x.url.includes("preview"));
        expect(posted[0].body).toMatchObject({ leaseIds: ["a"], splits: { a: { returnChequeIds: ["r1"], keepChequeIds: ["k1"] } } });
    });

    it("lists every contract of a failed chunk, carries on, and keeps the unfinished ones selected and previewed", async () => {
        const ids = Array.from({ length: 25 }, (_, i) => `t${i}`);
        api.on("GET", "/leases/paged", { body: { totalElements: 25, content: ids.map(lease) } });
        api.on("POST", "/leases/terminate-batch/preview", c => ({
            body: (c.body as { leaseIds: string[] }).leaseIds.map(id => ({ leaseId: id, ok: true, terminationDate: "2026-10-31", preview: preview() })),
        }));
        let calls = 0;
        api.on("POST", /\/leases\/terminate-batch$/, c => {
            calls++;
            if (calls === 1) return { status: 504, body: { message: "Gateway timeout" } };
            return { body: (c.body as { leaseIds: string[] }).leaseIds.map(id => ({ leaseId: id, ok: true, terminationDate: "2026-10-31", status: "TERMINATED" })) };
        });
        render(<BulkTerminate filters={FILTERS} onDateChange={() => {}} canTerminate />);
        fireEvent.click(await screen.findByTestId("bulk-terminate-select-all"));
        await waitFor(() => expect(screen.getByTestId("bulk-terminate-preview")).toHaveTextContent("Preview 25"));
        fireEvent.click(screen.getByTestId("bulk-terminate-preview"));
        await screen.findByTestId("bulk-terminate-previews");
        fireEvent.click(screen.getByTestId("bulk-terminate-confirm-open"));
        fireEvent.click(screen.getByTestId("bulk-terminate-confirm"));
        const results = await screen.findByTestId("bulk-terminate-results");
        // The first chunk (20) failed and is listed; the second (5) still went through.
        expect(within(results).getByText("5 terminated, 20 refused.")).toBeInTheDocument();
        expect(within(results).getByTestId("bulk-terminate-result-t0")).toHaveTextContent("Not processed");
        expect(within(results).getByTestId("bulk-terminate-result-t0")).toHaveTextContent("U-t0 · Tenant t0");
        // The 20 not processed stay previewed and included, ready to retry; the 5 ended are gone.
        const table = screen.getByTestId("bulk-terminate-previews");
        expect(within(table).getByTestId("bulk-terminate-preview-t0")).toBeInTheDocument();
        expect(within(table).queryByTestId("bulk-terminate-preview-t24")).toBeNull();
        expect(screen.getByText("20 of 20 included")).toBeInTheDocument();
        fireEvent.click(screen.getByTestId("bulk-terminate-confirm-open"));
        fireEvent.click(screen.getByTestId("bulk-terminate-confirm"));
        await waitFor(() => expect(screen.getByText("20 terminated, 0 refused.")).toBeInTheDocument());
        const posted = api.callsTo("POST", "/leases/terminate-batch").filter(x => !x.url.includes("preview"));
        expect((posted[2].body as { leaseIds: string[] }).leaseIds).toEqual(ids.slice(0, 20));
    });

    it("lets a manager preview but not confirm", async () => {
        render(<BulkTerminate filters={FILTERS} onDateChange={() => {}} canTerminate={false} />);
        fireEvent.click(await screen.findByTestId("bulk-terminate-select-page"));
        fireEvent.click(screen.getByTestId("bulk-terminate-preview"));
        await screen.findByTestId("bulk-terminate-previews");
        expect(screen.getByTestId("bulk-terminate-finance-only")).toBeInTheDocument();
        expect(screen.queryByTestId("bulk-terminate-confirm-open")).toBeNull();
        await waitFor(() => expect(api.callsTo("POST", "/leases/terminate-batch").filter(x => !x.url.includes("preview"))).toHaveLength(0));
    });
});
