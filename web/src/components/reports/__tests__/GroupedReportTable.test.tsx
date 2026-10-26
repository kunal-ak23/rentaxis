import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
}));
import { GroupedReportTable } from "../GroupedReportTable";
import { grid, item } from "./grid.fixture";

const onPage = vi.fn();
afterEach(() => { cleanup(); onPage.mockReset(); });

describe("GroupedReportTable", () => {
    it("renders bands, opening, rows, subtotal and total in order", () => {
        render(<GroupedReportTable grid={grid} onPage={onPage} />);
        const ids = [...document.querySelectorAll("[data-testid^='grid-']")].map(e => e.getAttribute("data-testid"));
        expect(ids).toEqual(["grid-band-1", "grid-band-2", "grid-opening", "grid-row", "grid-row", "grid-subtotal-2", "grid-total"]);
        expect(screen.getByText("60,000.00Dr")).toBeInTheDocument();
        expect(screen.getAllByText("8,000.00Cr").length).toBeGreaterThan(0);
        expect(screen.getByText("16/09/2026")).toBeInTheDocument();
        expect(screen.getByRole("link", { name: "TCO-26/1" })).toHaveAttribute("href", "/dashboard/finance/journals/e1");
        expect(screen.getByTestId("grid-subtotal-2")).toHaveTextContent("999.00"); // the server's figure, not a client sum
        expect(screen.getByText("60,000.00Dr").closest("bdi")).toHaveAttribute("dir", "ltr");
        expect(document.querySelector(".overflow-x-auto table")).not.toBeNull();
    });

    it("prints the opening in the balance column and colours bands by kind", () => {
        render(<GroupedReportTable grid={grid} />);
        const opening = screen.getByTestId("grid-opening");
        expect(opening).toHaveTextContent("Balance brought forward");
        expect(opening).toHaveTextContent("0.00Dr");
        // The label spans every column before Balance (Doc Date, Particular, Debit, Credit).
        expect(opening.querySelector("td")).toHaveAttribute("colspan", "4");
        expect(screen.getByTestId("grid-band-1").querySelector("td")).toHaveClass("bg-report-band", "text-report-band-fg");
        expect(screen.getByTestId("grid-band-2").querySelector("td")).toHaveClass("bg-report-party", "text-report-party-fg");
        expect(screen.getByTestId("grid-total")).toHaveTextContent("REPORT TOTAL");
        expect(screen.getByTestId("grid-total")).toHaveClass("font-bold");
        // No pager callback, no clickable pager; the header is sticky.
        expect(document.querySelector("thead")).toHaveClass("sticky");
    });

    it("pages forward with a 1-based label", () => {
        render(<GroupedReportTable grid={{ ...grid, page: { ...grid.page, page: 1 } }} onPage={onPage} />);
        expect(screen.getByText("Page 2 of 4")).toBeInTheDocument();
        fireEvent.click(screen.getByRole("button", { name: "Next" }));
        expect(onPage).toHaveBeenCalledWith(2);
        fireEvent.click(screen.getByRole("button", { name: "Previous" }));
        expect(onPage).toHaveBeenCalledWith(0);
    });

    it("cannot page before the first or past the last page, and shows no pager for one page", () => {
        const { unmount } = render(<GroupedReportTable grid={grid} onPage={onPage} groupsLabel="Account–tenant groups 1–16 of 41" />);
        expect(screen.getByRole("button", { name: "Previous" })).toBeDisabled();
        expect(screen.getByText("Account–tenant groups 1–16 of 41")).toBeInTheDocument();
        unmount();
        render(<GroupedReportTable grid={{ ...grid, page: { ...grid.page, page: 3 } }} onPage={onPage} />);
        expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
        cleanup();
        render(<GroupedReportTable grid={{ ...grid, page: { ...grid.page, pageCount: 1 } }} onPage={onPage} />);
        expect(screen.queryByRole("button", { name: "Next" })).toBeNull();
    });

    it("shows a continued band as the server labels it", () => {
        const items = [
            item("band", 2, { band: "PARTY", label: "Tenant Name : Tenant A (continued)", continued: true }),
            item("opening", 2, { label: "Balance carried forward", carriedForward: true, cells: { balance: "-8000.00" } }),
            ...grid.items.slice(3),
        ];
        render(<GroupedReportTable grid={{ ...grid, items, page: { ...grid.page, page: 1, continuesOnNextPage: true } }} onPage={onPage} />);
        expect(screen.getByTestId("grid-band-2")).toHaveTextContent(/^Tenant Name : Tenant A \(continued\)$/);
        expect(screen.getByTestId("grid-opening")).toHaveTextContent("Balance carried forward");
        expect(screen.getByTestId("grid-opening")).toHaveTextContent("8,000.00Cr");
        expect(screen.getByText("Continues on the next page")).toBeInTheDocument();
    });

    it("leaves hidden columns out, links leases and highlights flagged rows", () => {
        const columns = [
            ...grid.columns,
            { key: "contract", label: "Contract", kind: "ID_TEXT" as const, hidden: false, link: "lease" as const },
            { key: "internal", label: "Internal Col", kind: "TEXT" as const, hidden: true, link: null },
        ];
        const items = [
            item("row", 1, { cells: { particular: "Bounced cheque", contract: "L-7", internal: "secret" }, refs: { leaseId: "l7" }, style: "HIGHLIGHT" }),
        ];
        render(<GroupedReportTable grid={{ ...grid, columns, items }} />);
        expect(screen.queryByText("Internal Col")).toBeNull();
        expect(screen.queryByText("secret")).toBeNull();
        expect(screen.getByRole("link", { name: "L-7" })).toHaveAttribute("href", "/dashboard/leases/l7");
        // The highlight carries its own text colour, links included: under dark mode the band is dark and the text light.
        expect(screen.getByTestId("grid-row")).toHaveClass("bg-report-highlight", "text-report-highlight-fg", "[&_a]:text-report-highlight-fg");
        // A journal column without an entry id is plain text, not a dead link.
        expect(screen.queryByRole("link", { name: "PDR-26/1" })).toBeNull();
    });

    it("keeps the label and the figure when the first visible column carries a total", () => {
        const columns = [
            { key: "cheques", label: "Cheques", kind: "INT" as const, hidden: false, link: null },
            { key: "particular", label: "Particular", kind: "TEXT" as const, hidden: false, link: null },
            { key: "amount", label: "Amount", kind: "MONEY" as const, hidden: false, link: null },
        ];
        const items = [
            item("row", 2, { cells: { cheques: "3", particular: "Cheques - Sample Tower", amount: "1500.00" } }),
            item("subtotal", 2, { label: "Sub Total", cells: { cheques: "3", amount: "1500.00" } }),
            item("total", 0, { label: "REPORT TOTAL", cells: { cheques: "7", amount: "4200.00" } }),
        ];
        render(<GroupedReportTable grid={{ ...grid, columns, items }} />);
        const sub = screen.getByTestId("grid-subtotal-2");
        expect(sub.querySelectorAll("td")).toHaveLength(3);
        expect(sub.querySelectorAll("td")[0]).toHaveTextContent("Sub Total");
        expect(sub.querySelectorAll("td")[0]).toHaveTextContent("3");
        expect(sub.querySelectorAll("td")[2]).toHaveTextContent("1,500.00");
        const total = screen.getByTestId("grid-total");
        expect(total.querySelectorAll("td")).toHaveLength(3);
        expect(total.querySelectorAll("td")[0]).toHaveTextContent("REPORT TOTAL");
        expect(total.querySelectorAll("td")[0]).toHaveTextContent("7");
        expect(total.querySelectorAll("td")[0].querySelector("bdi[dir='ltr']")).toHaveTextContent(/^7$/);
        expect(total.querySelectorAll("td")[2]).toHaveTextContent("4,200.00");
    });

    it("keeps the opening's label when Balance is the first visible column", () => {
        const columns = [
            { key: "balance", label: "Balance", kind: "BALANCE" as const, hidden: false, link: null },
            { key: "particular", label: "Particular", kind: "TEXT" as const, hidden: false, link: null },
        ];
        const items = [item("opening", 2, { label: "Balance brought forward", cells: { balance: "500.00" } })];
        render(<GroupedReportTable grid={{ ...grid, columns, items }} />);
        const opening = screen.getByTestId("grid-opening");
        expect(opening.querySelectorAll("td")).toHaveLength(2);
        expect(opening.querySelectorAll("td")[0]).toHaveTextContent("Balance brought forward");
        expect(opening.querySelectorAll("td")[0]).toHaveTextContent("500.00Dr");
    });

    it("sets its own text colour on the card and the pager, so dark mode's light body text never lands on the light surface", () => {
        render(<GroupedReportTable grid={grid} onPage={onPage} />);
        expect(document.querySelector("table")!.closest("div")).toHaveClass("bg-surface", "text-foreground");
        expect(screen.getByRole("button", { name: "Next" })).toHaveClass("bg-surface", "text-foreground");
        expect(screen.getByRole("button", { name: "Previous" })).toHaveClass("bg-surface", "text-foreground");
        // Bands keep their own pair of tokens over the card's text colour.
        expect(screen.getByTestId("grid-band-1").querySelector("td")).toHaveClass("text-report-band-fg");
    });

    it("gives text columns a 20ch floor and leaves every other kind at its natural width", () => {
        const columns = [
            { key: "particular", label: "Particular", kind: "TEXT" as const, hidden: false, link: null },
            // The Excel width is not the screen's: a text column gets 20ch whatever the server says.
            { key: "bank", label: "Payee Bank", kind: "TEXT" as const, hidden: false, link: null, widthChars: 36 },
            { key: "ref", label: "Ref", kind: "ID_TEXT" as const, hidden: false, link: null, widthChars: 14 },
            { key: "date", label: "Date", kind: "DATE" as const, hidden: false, link: null, widthChars: 12 },
            { key: "amount", label: "Amount", kind: "MONEY" as const, hidden: false, link: null, widthChars: 14 },
            { key: "balance", label: "Balance", kind: "BALANCE" as const, hidden: false, link: null },
            { key: "days", label: "Days", kind: "INT" as const, hidden: false, link: null },
            { key: "serial", label: "Sr", kind: "SERIAL" as const, hidden: false, link: null },
            { key: "pct", label: "Pct", kind: "PERCENT" as const, hidden: false, link: null },
        ];
        const items = [item("row", 1, { cells: {
            particular: "PDC Receivable - Sample Tower", bank: "Sample Bank", ref: "PDR-26/1", date: "2026-10-26",
            amount: "12000.00", balance: "-8000.00", days: "1", serial: "3", pct: "5",
        } })];
        render(<GroupedReportTable grid={{ ...grid, columns, items }} />);
        const header = (name: string) => screen.getByRole("columnheader", { name });
        for (const name of ["Particular", "Payee Bank"]) {
            expect((header(name).firstElementChild as HTMLElement).style.minWidth).toBe("20ch");
        }
        const cells = screen.getByTestId("grid-row").querySelectorAll("td");
        expect(cells[0]).not.toHaveClass("whitespace-nowrap");
        expect(cells[1]).not.toHaveClass("whitespace-nowrap");
        ["Ref", "Date", "Amount", "Balance", "Days", "Sr", "Pct"].forEach((name, i) => {
            expect(header(name).querySelector("[style]")).toBeNull();
            expect(header(name)).toHaveClass("whitespace-nowrap");
            expect(cells[i + 2]).toHaveClass("whitespace-nowrap");
        });
    });

    it("fits the tenant ledger with tight padding and scrolls inside its own box, never the page", () => {
        render(<GroupedReportTable grid={grid} />);
        expect(screen.getByRole("columnheader", { name: "Particular" }).firstElementChild).toHaveStyle({ minWidth: "20ch" });
        expect(screen.getByRole("columnheader", { name: "Debit" })).toHaveClass("px-2");
        expect(screen.getAllByTestId("grid-row")[0].querySelector("td")).toHaveClass("px-2");
        expect(screen.getByTestId("grid-band-1").querySelector("td")).toHaveClass("px-2");
        // No fixed table width: the columns set it, and the table scrolls inside its own box.
        expect(document.querySelector("table")).not.toHaveClass("min-w-[900px]");
        expect(document.querySelector("table")!.parentElement).toHaveClass("overflow-x-auto");
    });

    it("rules the sticky header with the theme's border token", () => {
        render(<GroupedReportTable grid={grid} />);
        const th = document.querySelector("thead th");
        expect(th).toHaveClass("shadow-[inset_0_-1px_0_var(--color-border)]");
    });
});
