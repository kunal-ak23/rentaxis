import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
import { ReportShell } from "../ReportShell";

afterEach(cleanup);

const base = {
    title: "Tenant Ledger",
    description: "Receivable accounts by tenant, with cheque columns.",
    allowed: true,
    filterBar: <div data-testid="filters">filters</div>,
    idleMessage: "Pick a tenant, a property or an account to run the ledger",
};

describe("ReportShell", () => {
    it("denies a role that may not open the report", () => {
        render(<ReportShell {...base} allowed={false} state="ready"><table data-testid="report-table" /></ReportShell>);
        expect(screen.getByTestId("page-access-denied")).toHaveTextContent("Your role cannot open this report.");
        expect(screen.queryByTestId("filters")).toBeNull();
        expect(screen.queryByTestId("report-table")).toBeNull();
    });

    it("asks for a scope before loading", () => {
        render(<ReportShell {...base} state="idle"><table data-testid="report-table" /></ReportShell>);
        expect(screen.getByText("Pick a tenant, a property or an account to run the ledger")).toBeInTheDocument();
        expect(screen.getByTestId("filters")).toBeInTheDocument();
        expect(screen.queryByTestId("report-table")).toBeNull();
        expect(document.querySelector("table")).toBeNull();
    });

    it("shows the truncated banner", () => {
        render(<ReportShell {...base} state="ready" truncated={{ shown: 200, total: 900 }}><table data-testid="report-table" /></ReportShell>);
        expect(screen.getByText("Showing the first 200 of 900 rows. Narrow the filter or export.")).toBeInTheDocument();
        expect(screen.getByTestId("report-table")).toBeInTheDocument();
    });

    it("prints the header, period, scope note and export slot", () => {
        render(
            <ReportShell {...base} state="ready" period="01/09/2026 – 30/09/2027" scopeNote="Your assigned properties only"
                exportMenu={<button type="button">Export</button>}>
                <table data-testid="report-table" />
            </ReportShell>,
        );
        expect(screen.getByRole("heading", { level: 1, name: "Tenant Ledger" })).toBeInTheDocument();
        expect(screen.getByText("Receivable accounts by tenant, with cheque columns.")).toBeInTheDocument();
        expect(screen.getByTestId("report-period")).toHaveTextContent("01/09/2026 – 30/09/2027");
        expect(screen.getByTestId("report-scope-note")).toHaveTextContent("Your assigned properties only");
        expect(screen.getByRole("button", { name: "Export" })).toBeInTheDocument();
    });

    it("has loading, empty and error states", () => {
        const onRetry = vi.fn();
        const { rerender } = render(<ReportShell {...base} state="loading"><table data-testid="report-table" /></ReportShell>);
        expect(screen.getByTestId("report-loading")).toHaveAttribute("aria-busy", "true");
        expect(screen.queryByTestId("report-table")).toBeNull();

        rerender(<ReportShell {...base} state="empty"><table data-testid="report-table" /></ReportShell>);
        expect(screen.getByText("Nothing to show for these filters.")).toBeInTheDocument();
        expect(screen.queryByTestId("report-table")).toBeNull();

        rerender(<ReportShell {...base} state="error" error="Property not found" onRetry={onRetry}><table data-testid="report-table" /></ReportShell>);
        expect(screen.getByRole("alert")).toHaveTextContent("Property not found");
        fireEvent.click(screen.getByRole("button", { name: /Retry/ }));
        expect(onRetry).toHaveBeenCalled();
        expect(screen.queryByTestId("report-table")).toBeNull();
    });
});
