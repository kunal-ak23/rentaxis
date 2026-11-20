import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
}));
const h = vi.hoisted(() => ({ role: "TENANT_ADMIN", flag: true }));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: h.role } }, status: "authenticated" }) }));
vi.mock("@/hooks/useTenantFeatures", () => ({ useTenantFeatures: () => ({ isEnabled: (f: string) => f === "RENTER_RATING" && h.flag, tenantSlug: "x" }) }));
vi.mock("@/components/finance/useNameLookup", () => ({
    useNameLookup: () => ({ options: [{ id: "p1", label: "Marina" }], loading: false }),
}));
vi.mock("@/lib/download", () => ({ saveResponse: vi.fn() }));
vi.mock("@/lib/api/renterRatings", () => ({
    renterRatingsApi: { get: vi.fn(), addAdjustment: vi.fn(), removeAdjustment: vi.fn(), list: vi.fn(), exportUrl: vi.fn() },
}));
import { renterRatingsApi } from "@/lib/api/renterRatings";
import { saveResponse } from "@/lib/download";
import RenterRatingsPage from "../page";

const list = renterRatingsApi.list as unknown as ReturnType<typeof vi.fn>;
const exportUrl = renterRatingsApi.exportUrl as unknown as ReturnType<typeof vi.fn>;
const get = renterRatingsApi.get as unknown as ReturnType<typeof vi.fn>;
const row = (id: string, score: number, grade: string) => ({
    renterId: id, renterName: `Tenant ${id}`, renterNameAr: null, score, grade, deductionsCount: 2,
    currentLeaseId: "l1", unitLabel: `U-${id}`, propertyName: "Marina", computedAt: "2026-11-20T02:00:00Z",
});
const page = (content: unknown[]) => ({ content, totalElements: content.length, totalPages: 1, number: 0, size: 25 });

beforeEach(() => {
    h.role = "TENANT_ADMIN"; h.flag = true;
    list.mockReset(); exportUrl.mockReset(); get.mockReset();
    (saveResponse as unknown as ReturnType<typeof vi.fn>).mockReset();
    list.mockResolvedValue(page([row("r1", 40, "D"), row("r2", 90, "A")]));
    exportUrl.mockImplementation((p: Record<string, string>) => `/api/proxy/v1/renter-ratings/export?grade=${p.grade ?? ""}`);
});
afterEach(cleanup);

describe("renter ratings page", () => {
    it("listsRowsAndFiltersByGrade", async () => {
        render(<RenterRatingsPage />);
        const link = await screen.findByRole("link", { name: "Tenant r1" });
        expect(link).toHaveAttribute("href", "/dashboard/renters/r1");
        expect(screen.getByTestId("rating-badge-r1")).toBeInTheDocument();
        expect(get).not.toHaveBeenCalled();
        fireEvent.click(screen.getByTestId("rating-grade-D"));
        await waitFor(() => expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ grade: "D" })));
    });

    it("exportUsesFilters", async () => {
        const res = new Response("csv", { status: 200 });
        const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(res);
        render(<RenterRatingsPage />);
        await screen.findByRole("link", { name: "Tenant r1" });
        fireEvent.click(screen.getByTestId("rating-grade-D"));
        await waitFor(() => expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ grade: "D" })));
        fireEvent.click(screen.getByTestId("rating-export"));
        await waitFor(() => expect(saveResponse).toHaveBeenCalledWith(res, "tenant-ratings.csv"));
        expect(exportUrl).toHaveBeenLastCalledWith(expect.objectContaining({ grade: "D" }));
        expect(fetchSpy).toHaveBeenCalledWith("/api/proxy/v1/renter-ratings/export?grade=D");
        fetchSpy.mockRestore();
    });

    it("emptyStateWhenNoRows", async () => {
        list.mockResolvedValue(page([]));
        render(<RenterRatingsPage />);
        expect(await screen.findByText("Ratings are being calculated")).toBeInTheDocument();
    });

    it("noCalculatingMessageBesideLoadError", async () => {
        list.mockRejectedValue(new Error("boom"));
        render(<RenterRatingsPage />);
        expect(await screen.findByRole("alert")).toHaveTextContent("Could not load");
        expect(screen.queryByText("Ratings are being calculated")).toBeNull();
    });

    it("hidesPropertyFilterForRolesWithoutPropertyAccess", async () => {
        h.role = "ACCOUNTANT";
        render(<RenterRatingsPage />);
        await screen.findByRole("link", { name: "Tenant r1" });
        expect(screen.queryByTestId("rating-property-filter")).toBeNull();
        cleanup();
        h.role = "TENANT_ADMIN";
        render(<RenterRatingsPage />);
        await screen.findByRole("link", { name: "Tenant r1" });
        expect(screen.getByTestId("rating-property-filter")).toBeInTheDocument();
    });
});
