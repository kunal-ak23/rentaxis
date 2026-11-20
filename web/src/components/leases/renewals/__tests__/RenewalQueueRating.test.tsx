import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
    useRouter: () => ({ push: vi.fn() }),
}));
const h = vi.hoisted(() => ({ flag: true }));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } }, status: "authenticated" }) }));
vi.mock("@/hooks/useTenantFeatures", () => ({ useTenantFeatures: () => ({ isEnabled: (f: string) => f === "RENTER_RATING" && h.flag, tenantSlug: "x" }) }));
vi.mock("@/lib/api/renterRatings", () => ({ renterRatingsApi: { get: vi.fn(), list: vi.fn() } }));
import { renterRatingsApi } from "@/lib/api/renterRatings";
import RenewalQueue, { type QueueFilters } from "../RenewalQueue";
import { fetchRouter } from "@/test/fetchRouter";
import type { LeaseDetail } from "@/lib/api/leasing";

const lease = (id: string) => ({
    id, unitIdentifier: `U-${id}`, renterName: `Tenant ${id}`, renterId: `r-${id}`, propertyName: "Marina", propertyId: "p1",
    startDate: "2025-11-01", endDate: "2026-11-15", status: "ACTIVE", rentAmount: 80000, currentRentAmount: 80000,
    renewedToLeaseId: null, renewedToStatus: null, transferredToLeaseId: null, lines: [], version: 1,
}) as unknown as LeaseDetail;
const FILTERS: QueueFilters = { month: "2026-11", stage: "todo", propertyId: "", buildingId: "", floor: "", search: "" };
const list = renterRatingsApi.list as unknown as ReturnType<typeof vi.fn>;
const get = renterRatingsApi.get as unknown as ReturnType<typeof vi.fn>;

beforeEach(() => {
    h.flag = true; list.mockReset(); get.mockReset();
    list.mockResolvedValue({ content: [{ renterId: "r-a", score: 88, grade: "A" }], totalElements: 1 });
    const api = fetchRouter();
    api.on("GET", "/finance/charge-types", { body: [] });
    api.on("GET", "/leases/paged", call => {
        const q = new URL(call.url, "http://x").searchParams;
        if (q.get("size") === "1") return { body: { totalElements: 2, content: [] } };
        return { body: { totalElements: 2, content: Number(q.get("page")) === 0 ? [lease("a"), lease("b")] : [] } };
    });
});
afterEach(cleanup);

describe("renewal queue rating column", () => {
    it("showsRatingColumnWhenEnabledWithOneBatchFetch", async () => {
        render(<RenewalQueue filters={FILTERS} onStage={() => {}} perms={{ renew: true, extend: true, notice: true }} />);
        expect(await screen.findByRole("columnheader", { name: "Rating" })).toBeInTheDocument();
        // Table row + phone-width card both draw the badge from the one batch.
        expect(await screen.findAllByTestId("rating-badge-r-a")).toHaveLength(2);
        expect(screen.queryByTestId("rating-badge-r-b")).toBeNull();
        expect(list).toHaveBeenCalledTimes(1);
        expect(list).toHaveBeenCalledWith({ renterIds: ["r-a", "r-b"], size: 2 });
        expect(get).not.toHaveBeenCalled();
    });

    it("hasNoRatingColumnWhenFlagOff", async () => {
        h.flag = false;
        render(<RenewalQueue filters={FILTERS} onStage={() => {}} perms={{ renew: true, extend: true, notice: true }} />);
        await screen.findByTestId("renewal-row-a");
        expect(screen.queryByRole("columnheader", { name: "Rating" })).toBeNull();
        await waitFor(() => expect(list).not.toHaveBeenCalled());
    });
});
