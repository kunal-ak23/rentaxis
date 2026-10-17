import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
    useRouter: () => ({ push: vi.fn() }),
}));
import BulkChequeNumbersDialog, { type NumberingTarget } from "../BulkChequeNumbersDialog";
import { fetchRouter } from "@/test/fetchRouter";

const cheque = (draft: string, i: number, mode = "PDC") => ({
    id: `${draft}-c${i}`, seqNo: i, chequeDate: `2028-0${i}-01`, amount: 20000, mode, status: "DRAFT", chequeNumber: null, payeeBank: null,
});
const grid = (draft: string) => ({
    leaseId: draft, ok: true, version: 3, contractNumber: null, unitIdentifier: `U-${draft}`, renterName: `T ${draft}`,
    propertyName: "Marina", cheques: [cheque(draft, 1), cheque(draft, 2)],
});
const target = (draft: string, i = 0): NumberingTarget => ({ draftId: draft, predecessorContract: `GLA-${String(i).padStart(4, "0")}`, unitIdentifier: `U-${draft}`, renterName: `T ${draft}` });

let api: ReturnType<typeof fetchRouter>;
beforeEach(() => {
    api = fetchRouter();
    api.on("POST", "/leases/cheques-batch/load", c => ({ body: (c.body as { leaseIds: string[] }).leaseIds.map(grid) }));
    api.on("POST", /\/leases\/cheques-batch$/, c => ({
        body: (c.body as { drafts: { leaseId: string }[] }).drafts.map(d => ({ leaseId: d.leaseId, ok: true, version: 4, cheques: null, rowErrors: [] })),
    }));
});
afterEach(cleanup);

const type = (id: string, value: string) => fireEvent.change(screen.getByTestId(id), { target: { value } });

describe("bulk cheque numbering", () => {
    it("loads the drafts' rows, saves numbers, banks and dates with the amounts shown, and links to bulk post", async () => {
        render(<BulkChequeNumbersDialog targets={[target("d1", 7)]} onClose={() => {}} />);
        await screen.findByTestId("numbering-row-d1-c1");
        type("numbering-number-d1-c1", "500001");
        type("numbering-bank-d1-c1", "Emirates NBD");
        type("numbering-number-d1-c2", "500002");
        type("numbering-date-d1-c2", "2028-02-05");
        fireEvent.click(screen.getByTestId("numbering-save"));
        await waitFor(() => expect(screen.getByTestId("numbering-draft-d1")).toHaveAttribute("data-ok", "true"));
        const [save] = api.callsTo("POST", "/leases/cheques-batch").filter(c => !c.url.includes("load"));
        expect(save.body).toEqual({ drafts: [{ leaseId: "d1", version: 3, rows: [
            { chequeId: "d1-c1", chequeNumber: "500001", payeeBank: "Emirates NBD", chequeDate: "2028-01-01", amount: 20000 },
            { chequeId: "d1-c2", chequeNumber: "500002", payeeBank: null, chequeDate: "2028-02-05", amount: 20000 },
        ] }] });
        expect(screen.getByTestId("numbering-summary")).toHaveTextContent("1 of 1 saved, 0 need attention.");
        expect(screen.getByTestId("numbering-to-post")).toHaveAttribute("href", "/dashboard/leases?view=draft");
    });

    it("fills the grid from a CSV keyed by contract and due date", async () => {
        render(<BulkChequeNumbersDialog targets={[target("d1", 7), target("d2", 8)]} onClose={() => {}} />);
        await screen.findByTestId("numbering-row-d2-c2");
        type("numbering-csv-text", "contract,due_date,cheque_number,bank\nGLA-0007,01/01/2028,111,ADCB\nU-d2,2028-02-01,222,\nGLA-0099,2028-01-01,333");
        fireEvent.click(screen.getByTestId("numbering-csv-apply"));
        expect(screen.getByTestId("numbering-number-d1-c1")).toHaveValue("111");
        expect(screen.getByTestId("numbering-bank-d1-c1")).toHaveValue("ADCB");
        expect(screen.getByTestId("numbering-number-d2-c2")).toHaveValue("222");
        expect(screen.getByTestId("numbering-csv-report")).toHaveTextContent("2 rows filled, 1 lines not matched. Lines: 4.");
    });

    it("never sends a date that is not real: the CSV line is reported, and a bad row date is a row error", async () => {
        render(<BulkChequeNumbersDialog targets={[target("d1", 7)]} onClose={() => {}} />);
        await screen.findByTestId("numbering-row-d1-c2");
        type("numbering-csv-text", "contract,due_date,cheque_number,bank,cheque_date\nGLA-0007,01/01/2028,111,ADCB,1/15/2028\nGLA-0007,2028-02-01,112,ADCB,31/02/2028");
        fireEvent.click(screen.getByTestId("numbering-csv-apply"));
        expect(screen.getByTestId("numbering-csv-report")).toHaveTextContent("not applied: 2, 3.");
        expect(screen.getByTestId("numbering-number-d1-c1")).toHaveValue("");
        // A row whose date is not real (as a pasted value could leave it) is held back with a row error.
        type("numbering-number-d1-c1", "111");
        type("numbering-number-d1-c2", "112");
        fireEvent.change(screen.getByTestId("numbering-date-d1-c2"), { target: { value: "" } });
        expect(screen.getByTestId("numbering-error-d1-c2")).toHaveTextContent("Enter the cheque date.");
        fireEvent.click(screen.getByTestId("numbering-save"));
        await waitFor(() => expect(screen.getByTestId("numbering-draft-d1")).toHaveAttribute("data-ok", "false"));
        expect(api.callsTo("POST", "/leases/cheques-batch").filter(c => !c.url.includes("load"))).toHaveLength(0);
    });

    it("warns when a number is on two drafts, saves digits as ASCII, and shows the server's refusals in the user's words", async () => {
        api.on("POST", /\/leases\/cheques-batch$/, () => ({
            body: [
                { leaseId: "d1", ok: true, version: 4, cheques: null, rowErrors: [] },
                { leaseId: "d2", ok: false, version: 3, code: "cheque.rowsRefused", args: { count: 1 }, message: "1 row(s) need attention",
                    rowErrors: [{ chequeId: "d2-c1", field: "amount", code: "cheque.amountChanged", message: "The amount is 21000 now" }] },
            ],
        }));
        render(<BulkChequeNumbersDialog targets={[target("d1"), target("d2")]} onClose={() => {}} />);
        await screen.findByTestId("numbering-row-d2-c2");
        type("numbering-number-d1-c1", "\u0665\u0660\u0660\u0660\u0660\u0661");
        type("numbering-number-d1-c2", "500002");
        type("numbering-number-d2-c1", "500001");
        type("numbering-number-d2-c2", "600002");
        expect(screen.getByTestId("numbering-warning-d1-c1")).toHaveTextContent("The same number is on");
        expect(screen.getByTestId("numbering-warning-d2-c1")).toBeInTheDocument();
        fireEvent.click(screen.getByTestId("numbering-save"));
        await waitFor(() => expect(screen.getByTestId("numbering-draft-d2")).toHaveAttribute("data-ok", "false"));
        const sent = api.callsTo("POST", "/leases/cheques-batch").filter(c => !c.url.includes("load"))[0].body as { drafts: { rows: { chequeNumber: string }[] }[] };
        expect(sent.drafts[0].rows[0].chequeNumber).toBe("500001");
        expect(screen.getByTestId("numbering-error-d2-c1")).toHaveTextContent("The amount changed since the grid was loaded; reload the grid.");
        expect(screen.getByTestId("numbering-draft-d2")).toHaveTextContent("1 row(s) need attention; nothing was saved for this contract.");
    });

    it("holds back a draft with a repeated number, saves the others, and shows the server's row errors", async () => {
        api.on("POST", /\/leases\/cheques-batch$/, c => ({
            body: (c.body as { drafts: { leaseId: string }[] }).drafts.map(d => d.leaseId === "d3"
                ? { leaseId: "d3", ok: false, version: 3, rowErrors: [{ chequeId: "d3-c2", field: "amount", code: "cheque.amountChanged", message: "The amount is 21000 now" }],
                    code: "cheque.rowsRefused", message: "1 row(s) need attention; nothing was saved for this contract." }
                : { leaseId: d.leaseId, ok: true, version: 4, cheques: null, rowErrors: [] }),
        }));
        render(<BulkChequeNumbersDialog targets={[target("d1"), target("d2"), target("d3")]} onClose={() => {}} />);
        await screen.findByTestId("numbering-row-d3-c2");
        for (const d of ["d1", "d2", "d3"]) { type(`numbering-number-${d}-c1`, `${d}1`); type(`numbering-number-${d}-c2`, `${d}2`); }
        type("numbering-number-d2-c2", "d21");
        expect(screen.getByTestId("numbering-error-d2-c2")).toHaveTextContent("used twice");
        fireEvent.click(screen.getByTestId("numbering-save"));
        await waitFor(() => expect(screen.getByTestId("numbering-draft-d1")).toHaveAttribute("data-ok", "true"));
        const sent = api.callsTo("POST", "/leases/cheques-batch").filter(c => !c.url.includes("load"))[0].body as { drafts: { leaseId: string }[] };
        expect(sent.drafts.map(d => d.leaseId)).toEqual(["d1", "d3"]);
        expect(screen.getByTestId("numbering-draft-d2")).toHaveAttribute("data-ok", "false");
        expect(screen.getByTestId("numbering-draft-d3")).toHaveAttribute("data-ok", "false");
        // The server's code is shown in the user's language, not the server's English.
        expect(screen.getByTestId("numbering-error-d3-c2")).toHaveTextContent("The amount changed since the grid was loaded");
        // What was typed in the refused drafts is kept.
        expect(screen.getByTestId("numbering-number-d3-c1")).toHaveValue("d31");
        expect(screen.getByTestId("numbering-summary")).toHaveTextContent("1 of 3 saved, 2 need attention.");
    });

    it("marks every draft of a failed call as not processed, carries on, and retries only the unsaved", async () => {
        const ids = Array.from({ length: 30 }, (_, i) => `d${i}`);
        let calls = 0;
        api.on("POST", /\/leases\/cheques-batch$/, c => {
            calls++;
            if (calls === 1) return { status: 504, body: { message: "Gateway timeout" } };
            return { body: (c.body as { drafts: { leaseId: string }[] }).drafts.map(d => ({ leaseId: d.leaseId, ok: true, version: 4, cheques: null, rowErrors: [] })) };
        });
        render(<BulkChequeNumbersDialog targets={ids.map(id => target(id))} onClose={() => {}} />);
        await screen.findByTestId("numbering-row-d29-c2");
        for (const d of ids) { type(`numbering-number-${d}-c1`, `${d}a`); type(`numbering-number-${d}-c2`, `${d}b`); }
        fireEvent.click(screen.getByTestId("numbering-save"));
        await waitFor(() => expect(screen.getByTestId("numbering-summary")).toHaveTextContent("5 of 30 saved, 25 need attention."));
        expect(within(screen.getByTestId("numbering-draft-d0")).getByText(/Not processed/)).toBeInTheDocument();
        expect(screen.getByText(/Some contracts could not be sent/)).toBeInTheDocument();
        fireEvent.click(screen.getByTestId("numbering-save"));
        await waitFor(() => expect(screen.getByTestId("numbering-summary")).toHaveTextContent("30 of 30 saved, 0 need attention."));
        const saves = api.callsTo("POST", "/leases/cheques-batch").filter(c => !c.url.includes("load"))
            .map(c => (c.body as { drafts: { leaseId: string }[] }).drafts.map(d => d.leaseId));
        expect(saves.map(s => s.length)).toEqual([25, 5, 25]);
        expect(saves[2]).toEqual(ids.slice(0, 25));
    });

    it("lists a draft whose grid could not be loaded", async () => {
        api.on("POST", "/leases/cheques-batch/load", { status: 502, body: { message: "Bad gateway" } });
        render(<BulkChequeNumbersDialog targets={[target("d1")]} onClose={() => {}} />);
        expect(await screen.findByTestId("numbering-draft-d1")).toHaveTextContent("Not processed");
    });
});
