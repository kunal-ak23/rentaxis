// src/app/[locale]/dashboard/collections/__tests__/hub.test.tsx
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
const role = { current: "TENANT_ADMIN" };
const query = { current: "" };
const router = vi.hoisted(() => ({ replace: vi.fn(), push: vi.fn() }));
const api = vi.hoisted(() => ({ toDeposit: vi.fn(), summary: vi.fn(), penalties: vi.fn() }));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: role.current } } }) }));
vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams(query.current) }));
vi.mock("@/i18n/routing", () => ({ Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>, useRouter: () => router }));
vi.mock("@/components/finance/useNameLookup", () => ({ useNameLookup: () => ({ options: [{ id: "p1", label: "Belle Vue" }], name: () => "", loading: false }) }));
vi.mock("@/components/collections/ToDepositPanel", () => ({ default: (p: { propertyId?: string }) => <div data-testid="panel-deposit" data-property={p.propertyId ?? ""} /> }));
vi.mock("@/components/collections/ReturnReplacePanel", () => ({ default: (p: { propertyId?: string }) => <div data-testid="panel-returned" data-property={p.propertyId ?? ""} /> }));
vi.mock("@/components/collections/PostDatedPanel", () => ({ default: (p: { propertyId?: string }) => <div data-testid="panel-post-dated" data-property={p.propertyId ?? ""} /> }));
vi.mock("@/components/collections/PenaltiesPanel", () => ({ default: (p: { propertyId?: string }) => <div data-testid="panel-penalties" data-property={p.propertyId ?? ""} /> }));
vi.mock("@/components/collections/ChequeRegisterPanel", () => ({ default: () => <div data-testid="panel-all" /> }));
vi.mock("@/components/collections/DueChequesPanel", () => ({ default: ({ overdueOnly, propertyId }: { overdueOnly: boolean; propertyId?: string }) => <div data-testid={overdueOnly ? "panel-overdue" : "panel-due"} data-property={propertyId ?? ""} /> }));
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m,
        chequeApi: { ...m.chequeApi, toDeposit: api.toDeposit, summary: api.summary },
        penaltyApi: { ...m.penaltyApi, list: api.penalties } };
});
import Page from "../page";

beforeEach(() => {
    role.current = "TENANT_ADMIN"; query.current = "";
    api.toDeposit.mockResolvedValue({ totalElements: 3 });
    api.summary.mockResolvedValue({ dueCount: 5, overdueCount: 2, bouncedCount: 1 });
    api.penalties.mockResolvedValue({ totalElements: 4 });
});
afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe("Cheque / Cash Collection hub", () => {
    it.each([["", "deposit"], ["tab=due", "due"], ["tab=overdue", "overdue"], ["tab=returned", "returned"],
        ["tab=post-dated", "post-dated"], ["tab=penalties", "penalties"], ["tab=all", "all"], ["tab=bogus", "deposit"]])("?%s shows %s", (q, id) => {
        query.current = q;
        render(<Page />);
        expect(screen.getByTestId(`panel-${id}`)).toBeInTheDocument();
        expect(screen.getByTestId(`collections-pill-${id}`)).toHaveAttribute("aria-current", "page");
    });

    it("shows the pills in the client's order", () => {
        render(<Page />);
        expect(within(screen.getByTestId("collections-pills")).getAllByRole("link").map(a => a.textContent?.replace(/\d+$/, ""))).toEqual(
            ["To deposit", "Due", "Overdue", "Returned / replace", "Post-dated", "Penalties", "Cheque register"]);
    });

    it("shows counts on the pills from the existing summary endpoints", async () => {
        render(<Page />);
        expect(await within(screen.getByTestId("collections-pill-deposit")).findByText("3")).toBeInTheDocument();
        expect(await within(screen.getByTestId("collections-pill-due")).findByText("5")).toBeInTheDocument();
        expect(within(screen.getByTestId("collections-pill-overdue")).getByText("2")).toBeInTheDocument();
        expect(within(screen.getByTestId("collections-pill-returned")).getByText("1")).toBeInTheDocument();
        expect(await within(screen.getByTestId("collections-pill-penalties")).findByText("4")).toBeInTheDocument();
        expect(screen.queryByTestId("collections-count-post-dated")).toBeNull();
        expect(screen.queryByTestId("collections-count-all")).toBeNull();
        expect(api.penalties).toHaveBeenCalledWith({ status: "PROPOSED", propertyId: undefined, page: 0, size: 1 });
    });

    it("drops a count whose endpoint failed rather than showing a wrong one", async () => {
        api.summary.mockRejectedValue(new Error("x"));
        render(<Page />);
        expect(await within(screen.getByTestId("collections-pill-deposit")).findByText("3")).toBeInTheDocument();
        expect(screen.queryByTestId("collections-count-due")).toBeNull();
    });

    it("hands the property filter to the panel, the counts and the pill links", async () => {
        query.current = "tab=returned&propertyId=p1";
        render(<Page />);
        expect(screen.getByTestId("panel-returned")).toHaveAttribute("data-property", "p1");
        expect(screen.getByTestId("collections-pill-due")).toHaveAttribute("href", "/dashboard/collections?tab=due&propertyId=p1");
        expect(api.summary).toHaveBeenCalledWith("p1");
    });

    it("changes the property in the URL and keeps the tab and the register's filters", () => {
        query.current = "tab=all&status=BOUNCED";
        render(<Page />);
        fireEvent.change(screen.getByTestId("collections-property"), { target: { value: "p1" } });
        expect(router.replace).toHaveBeenCalledWith("/dashboard/collections?tab=all&status=BOUNCED&propertyId=p1");
    });

    it("sends a search to the cheque register, keeping the property", () => {
        query.current = "tab=due&propertyId=p1";
        render(<Page />);
        const box = screen.getByTestId("collections-search");
        expect(box.closest("form")).toHaveAttribute("action", "/en/dashboard/collections");
        fireEvent.change(box, { target: { value: " 100026 " } });
        fireEvent.submit(box.closest("form")!);
        expect(router.push).toHaveBeenCalledWith("/dashboard/collections?tab=all&propertyId=p1&search=100026");
    });

    it("gives a property manager every pill (same gates as the old pages)", () => {
        role.current = "PROPERTY_MANAGER";
        render(<Page />);
        expect(within(screen.getByTestId("collections-pills")).getAllByRole("link")).toHaveLength(7);
    });

    it("refuses a role with no collection tab", () => {
        role.current = "TENANT_USER";
        render(<Page />);
        expect(screen.getByTestId("collections-no-access")).toBeInTheDocument();
        expect(api.summary).not.toHaveBeenCalled();
    });
});
