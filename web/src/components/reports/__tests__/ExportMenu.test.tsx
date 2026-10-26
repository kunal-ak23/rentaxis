import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("@/lib/download", () => ({ saveResponse: vi.fn() }));
import { saveResponse } from "@/lib/download";
import { ExportMenu, type ExportOption } from "../ExportMenu";

const options: ExportOption[] = [
    { id: "xlsx", url: "/api/proxy/v1/finance/reports/tenant-ledger.xlsx?from=a", fallbackName: "tenant-ledger.xlsx" },
    { id: "pdfEn", url: "/api/proxy/v1/finance/reports/tenant-ledger.pdf?from=a&lang=en", fallbackName: "tenant-ledger.pdf" },
    { id: "pdfAr", url: "/api/proxy/v1/finance/reports/tenant-ledger.pdf?from=a&lang=ar", fallbackName: "tenant-ledger-ar.pdf" },
    { id: "csv", url: "/api/proxy/v1/finance/reports/tenant-ledger.csv?from=a", fallbackName: "tenant-ledger.csv" },
];

const realFetch = global.fetch;
let opened: ReturnType<typeof vi.spyOn>;
beforeEach(() => { opened = vi.spyOn(window, "open").mockImplementation(() => null); });
afterEach(() => { cleanup(); global.fetch = realFetch; vi.mocked(saveResponse).mockReset(); opened.mockRestore(); });

function choose(name: string) {
    fireEvent.click(screen.getByRole("button", { name: "Export" }));
    fireEvent.click(screen.getByRole("menuitem", { name }));
}

describe("ExportMenu", () => {
    it("lists the four formats in a menu", () => {
        render(<ExportMenu options={options} />);
        expect(screen.queryByRole("menu")).toBeNull();
        fireEvent.click(screen.getByRole("button", { name: "Export" }));
        expect(screen.getAllByRole("menuitem").map(e => e.textContent)).toEqual(["Excel (PACT layout)", "PDF — English", "PDF — Arabic", "CSV"]);
    });

    it("downloads through fetch and saveResponse", async () => {
        global.fetch = vi.fn(async () => new Response("x", { status: 200 })) as unknown as typeof fetch;
        render(<ExportMenu options={[options[0]]} />);
        choose("Excel (PACT layout)");
        await waitFor(() => expect(saveResponse).toHaveBeenCalledWith(expect.any(Response), "tenant-ledger.xlsx"));
        expect(global.fetch).toHaveBeenCalledWith("/api/proxy/v1/finance/reports/tenant-ledger.xlsx?from=a");
        expect(opened).not.toHaveBeenCalled();
        expect(screen.queryByRole("alert")).toBeNull();
    });

    it("is busy while the file is prepared", async () => {
        let finish!: (r: Response) => void;
        global.fetch = vi.fn(() => new Promise<Response>(r => { finish = r; })) as unknown as typeof fetch;
        render(<ExportMenu options={options} />);
        choose("CSV");
        const trigger = await screen.findByRole("button", { name: /Preparing/ });
        expect(trigger).toBeDisabled();
        expect(trigger).toHaveAttribute("aria-busy", "true");
        finish(new Response("a,b", { status: 200 }));
        await waitFor(() => expect(screen.getByRole("button", { name: "Export" })).not.toBeDisabled());
        expect(saveResponse).toHaveBeenCalledWith(expect.any(Response), "tenant-ledger.csv");
    });

    it("puts the server's 422 inline", async () => {
        global.fetch = vi.fn(async () => new Response(JSON.stringify({ code: "report.tooManyRowsPdf", args: { rows: 6000, cap: 5000 }, message: "x" }),
            { status: 422, headers: { "Content-Type": "application/json" } })) as unknown as typeof fetch;
        render(<ExportMenu options={options} />);
        choose("PDF — English");
        expect(await screen.findByRole("alert")).toHaveTextContent("Too many rows for a PDF (6,000 rows, the limit is 5,000)");
        expect(saveResponse).not.toHaveBeenCalled();
        expect(opened).not.toHaveBeenCalled();
    });

    it("names the row cap for Excel and CSV", async () => {
        global.fetch = vi.fn(async () => new Response(JSON.stringify({ code: "report.tooManyRows", args: { rows: 50001, cap: 50000 }, message: "x" }),
            { status: 422, headers: { "Content-Type": "application/json" } })) as unknown as typeof fetch;
        render(<ExportMenu options={options} />);
        choose("Excel (PACT layout)");
        expect(await screen.findByRole("alert")).toHaveTextContent("Too many rows (50,001 rows, the limit is 50,000). Narrow the filter.");
    });

    it("says try later on a 429", async () => {
        global.fetch = vi.fn(async () => new Response("", { status: 429 })) as unknown as typeof fetch;
        render(<ExportMenu options={options} />);
        choose("CSV");
        expect(await screen.findByRole("alert")).toHaveTextContent("Too many exports in a row. Try again in a minute.");
        expect(saveResponse).not.toHaveBeenCalled();
    });

    it("shows the server's message, else a generic failure", async () => {
        global.fetch = vi.fn(async () => new Response(JSON.stringify({ error: true, message: "Property not found", status: 404 }),
            { status: 404, headers: { "Content-Type": "application/json" } })) as unknown as typeof fetch;
        const { unmount } = render(<ExportMenu options={options} />);
        choose("CSV");
        expect(await screen.findByRole("alert")).toHaveTextContent("Property not found");
        unmount();
        global.fetch = vi.fn(async () => new Response("<html>bad gateway</html>", { status: 502 })) as unknown as typeof fetch;
        render(<ExportMenu options={options} />);
        choose("CSV");
        expect(await screen.findByRole("alert")).toHaveTextContent("The export failed. Try again.");
        global.fetch = vi.fn(async () => { throw new TypeError("offline"); }) as unknown as typeof fetch;
        choose("CSV");
        await waitFor(() => expect(global.fetch).toHaveBeenCalled());
        expect(await screen.findByRole("alert")).toHaveTextContent("The export failed. Try again.");
    });

    it("moves focus into the menu and through it with the arrow, Home and End keys", () => {
        render(<ExportMenu options={options} />);
        fireEvent.click(screen.getByRole("button", { name: "Export" }));
        const items = screen.getAllByRole("menuitem");
        expect(items[0]).toHaveFocus();
        const press = (key: string) => fireEvent.keyDown(document.activeElement!, { key });
        press("ArrowDown");
        expect(items[1]).toHaveFocus();
        press("End");
        expect(items[3]).toHaveFocus();
        press("ArrowDown"); // wraps to the first
        expect(items[0]).toHaveFocus();
        press("ArrowUp"); // wraps to the last
        expect(items[3]).toHaveFocus();
        press("Home");
        expect(items[0]).toHaveFocus();
        // The focused item shows it: a tint, and a ring when focus came from the keyboard.
        for (const item of items) {
            expect(item).toHaveClass("focus:bg-primary/10", "focus-visible:ring-2", "focus-visible:ring-primary/40");
        }
    });

    it("closes on Escape and gives focus back to the trigger", () => {
        render(<ExportMenu options={options} />);
        const trigger = screen.getByRole("button", { name: "Export" });
        fireEvent.click(trigger);
        expect(screen.getAllByRole("menuitem")[0]).toHaveFocus();
        fireEvent.keyDown(document.activeElement!, { key: "Escape" });
        expect(screen.queryByRole("menu")).toBeNull();
        expect(trigger).toHaveFocus();
    });

    it("closes on Tab", () => {
        render(<ExportMenu options={options} />);
        fireEvent.click(screen.getByRole("button", { name: "Export" }));
        fireEvent.keyDown(document.activeElement!, { key: "Tab" });
        expect(screen.queryByRole("menu")).toBeNull();
    });

    it("gives focus back to the trigger once a chosen export finishes", async () => {
        let finish!: (r: Response) => void;
        global.fetch = vi.fn(() => new Promise<Response>(r => { finish = r; })) as unknown as typeof fetch;
        render(<ExportMenu options={options} />);
        const trigger = screen.getByRole("button", { name: "Export" });
        choose("CSV");
        expect(screen.queryByRole("menu")).toBeNull();
        // A browser drops focus to <body> while the trigger is disabled (jsdom keeps it, so drop it by hand).
        const gone = document.body.appendChild(document.createElement("button"));
        gone.focus();
        gone.remove();
        expect(document.activeElement).toBe(document.body);
        finish(new Response("a,b", { status: 200 }));
        await waitFor(() => expect(trigger).not.toBeDisabled());
        expect(trigger).toHaveFocus();
    });

    it("leaves focus where the user moved it while the export ran", async () => {
        let finish!: (r: Response) => void;
        global.fetch = vi.fn(() => new Promise<Response>(r => { finish = r; })) as unknown as typeof fetch;
        render(<><ExportMenu options={options} /><input aria-label="Tenant" /></>);
        const trigger = screen.getByRole("button", { name: "Export" });
        choose("CSV");
        const elsewhere = screen.getByRole("textbox", { name: "Tenant" });
        elsewhere.focus();
        finish(new Response("a,b", { status: 200 }));
        await waitFor(() => expect(trigger).not.toBeDisabled());
        expect(elsewhere).toHaveFocus();
    });

    it("can be disabled", () => {
        render(<ExportMenu options={options} disabled />);
        expect(screen.getByRole("button", { name: "Export" })).toBeDisabled();
    });
});
