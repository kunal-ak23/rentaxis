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
const cheque = (amount: number) => ({ id: `c${amount}`, amount });
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
        expect(posted[0].body).toEqual({ leaseIds: ["a"], terminationDate: "2026-10-31", notes: "Season move-out" });
        expect(within(results).getByTestId("bulk-terminate-result-a")).toHaveTextContent("terminated on");
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
