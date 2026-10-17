import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
const replace = vi.hoisted(() => vi.fn());
vi.mock("next/navigation", () => ({
    useRouter: () => ({ replace }),
    useSearchParams: () => new URLSearchParams("month=2026-11"),
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children }: { href: string; children: React.ReactNode }) => <a href={href}>{children}</a>,
}));
vi.mock("@/components/finance/useNameLookup", () => ({ useNameLookup: () => ({ options: [] }) }));
vi.mock("@/components/leases/renewals/RenewalQueue", () => ({
    default: ({ filters, onStage }: { filters: { stage: string; month: string }; onStage: (s: string) => void }) => (
        <div data-testid="queue" data-stage={filters.stage} data-month={filters.month}>
            <button type="button" onClick={() => onStage("drafted")}>to drafted</button>
        </div>
    ),
}));
vi.mock("@/components/leases/renewals/BulkTerminate", () => ({ default: () => <div data-testid="terminate" /> }));
import RenewalSeasonPage from "../page";

afterEach(() => { cleanup(); replace.mockClear(); });

describe("renewal season URL state", () => {
    it("writes a stage or month change through the router, so the page re-renders on it", () => {
        window.history.replaceState(null, "", "/en/dashboard/leases/renewals?month=2026-11");
        render(<RenewalSeasonPage />);
        expect(screen.getByTestId("queue")).toHaveAttribute("data-month", "2026-11");
        fireEvent.click(screen.getByText("to drafted"));
        expect(replace).toHaveBeenCalledWith("/en/dashboard/leases/renewals?month=2026-11&stage=drafted", { scroll: false });
        fireEvent.change(screen.getByTestId("renewal-month"), { target: { value: "2026-12" } });
        expect(replace).toHaveBeenLastCalledWith("/en/dashboard/leases/renewals?month=2026-12", { scroll: false });
    });
});
