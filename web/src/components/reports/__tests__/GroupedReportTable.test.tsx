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
        const { unmount } = render(<GroupedReportTable grid={grid} onPage={onPage} groupsLabel="Tenants 1–16 of 41" />);
        expect(screen.getByRole("button", { name: "Previous" })).toBeDisabled();
        expect(screen.getByText("Tenants 1–16 of 41")).toBeInTheDocument();
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
        expect(screen.getByTestId("grid-row")).toHaveClass("bg-report-highlight");
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

    it("rules the sticky header with the theme's border token", () => {
        render(<GroupedReportTable grid={grid} />);
        const th = document.querySelector("thead th");
        expect(th).toHaveClass("shadow-[inset_0_-1px_0_var(--color-border)]");
    });
});
