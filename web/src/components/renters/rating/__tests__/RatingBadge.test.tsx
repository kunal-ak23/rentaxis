import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
}));

const h = vi.hoisted(() => ({ role: "TENANT_ADMIN", flag: true }));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: h.role } }, status: "authenticated" }) }));
vi.mock("@/hooks/useTenantFeatures", () => ({ useTenantFeatures: () => ({ isEnabled: (f: string) => f === "RENTER_RATING" && h.flag, tenantSlug: "x" }) }));
vi.mock("@/lib/api/renterRatings", () => ({
    renterRatingsApi: { get: vi.fn(), addAdjustment: vi.fn(), removeAdjustment: vi.fn(), list: vi.fn(), exportUrl: vi.fn() },
}));
import { renterRatingsApi } from "@/lib/api/renterRatings";
import RatingBadge from "../RatingBadge";

const detail = (score: number, grade: string) => ({ renterId: "r1", score, grade, deductionsCount: 0, base: 70, events: [], trend: [] });
const get = renterRatingsApi.get as unknown as ReturnType<typeof vi.fn>;

beforeEach(() => { h.role = "TENANT_ADMIN"; h.flag = true; get.mockReset(); });
afterEach(cleanup);

describe("RatingBadge", () => {
    it("rendersGradeAndScore", async () => {
        get.mockResolvedValue(detail(91, "A"));
        render(<RatingBadge renterId="r1" />);
        expect(await screen.findByText("91")).toBeInTheDocument();
        expect(screen.getByText("A")).toBeInTheDocument();
        expect(screen.getByText("Excellent")).toBeInTheDocument();
        expect(screen.getByRole("button", { name: /Tenant rating A, score 91/ })).toBeInTheDocument();
    });

    it("hiddenWhenFlagOff", () => {
        h.flag = false;
        const { container } = render(<RatingBadge renterId="r1" />);
        expect(container).toBeEmptyDOMElement();
        expect(get).not.toHaveBeenCalled();
    });

    it("hiddenForRenterRole", () => {
        h.role = "RENTER";
        const { container } = render(<RatingBadge renterId="r1" />);
        expect(container).toBeEmptyDOMElement();
        expect(get).not.toHaveBeenCalled();
    });

    it("newGradeGrey", async () => {
        get.mockResolvedValue(detail(70, "NEW"));
        render(<RatingBadge renterId="r1" />);
        const btn = await screen.findByRole("button", { name: /Tenant rating New, score 70/ });
        expect(btn.className).toMatch(/gray/);
        // translated label, shown once, and never the raw enum name
        expect(screen.getAllByText("New")).toHaveLength(1);
        expect(screen.queryByText("NEW")).toBeNull();
        expect(btn.getAttribute("aria-label")).not.toMatch(/NEW/);
    });

    it("prefetched values render without fetching", () => {
        render(<RatingBadge renterId="r1" prefetched={{ score: 55, grade: "C" }} />);
        expect(screen.getByText("55")).toBeInTheDocument();
        expect(screen.getByText("Fair")).toBeInTheDocument();
        expect(get).not.toHaveBeenCalled();
    });
});
