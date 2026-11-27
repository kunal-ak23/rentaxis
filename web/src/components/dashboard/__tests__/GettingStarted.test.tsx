import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
}));
const renters = vi.hoisted(() => ({ rows: [] as unknown[] }));
vi.mock("@/lib/api/lookup", () => ({
    lookupApi: { searchRenters: vi.fn(async () => renters.rows) },
}));
import GettingStarted from "../GettingStarted";
import { lookupApi } from "@/lib/api/lookup";

afterEach(() => { cleanup(); vi.clearAllMocks(); renters.rows = []; });

describe("GettingStarted (QA-12)", () => {
    it("a new organisation sees four linked steps, none ticked", async () => {
        render(<GettingStarted projects={0} units={0} hasContracts={false} />);
        await waitFor(() => expect(lookupApi.searchRenters).toHaveBeenCalled());
        for (const k of ["gsProject", "gsUnits", "gsTenant", "gsContract"]) {
            expect(screen.getByTestId(`getting-started-${k}`).getAttribute("data-done")).toBe("false");
        }
        expect(screen.getByText("Add a tenant").closest("a")?.getAttribute("href")).toBe("/dashboard/renters?new=1");
        expect(screen.getByText("Draft the first contract").closest("a")?.getAttribute("href")).toBe("/dashboard/leases?new=1");
    });

    it("ticks the steps already done", async () => {
        renters.rows = [{ id: "r1" }];
        render(<GettingStarted projects={1} units={3} hasContracts={false} />);
        await waitFor(() => expect(screen.getByTestId("getting-started-gsTenant").getAttribute("data-done")).toBe("true"));
        expect(screen.getByTestId("getting-started-gsProject").getAttribute("data-done")).toBe("true");
        expect(screen.getByTestId("getting-started-gsUnits").getAttribute("data-done")).toBe("true");
        expect(screen.getByTestId("getting-started-gsContract").getAttribute("data-done")).toBe("false");
    });

    it("is hidden once a contract exists, and while the counts load", () => {
        const { rerender } = render(<GettingStarted projects={1} units={1} hasContracts={true} />);
        expect(screen.queryByTestId("getting-started")).toBeNull();
        rerender(<GettingStarted projects={0} units={0} hasContracts={null} />);
        expect(screen.queryByTestId("getting-started")).toBeNull();
        expect(lookupApi.searchRenters).not.toHaveBeenCalled();
    });
});
