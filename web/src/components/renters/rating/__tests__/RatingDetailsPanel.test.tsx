import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
}));

const h = vi.hoisted(() => ({ role: "TENANT_ADMIN" }));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: h.role } }, status: "authenticated" }) }));
vi.mock("@/lib/api/renterRatings", () => ({
    renterRatingsApi: { get: vi.fn(), addAdjustment: vi.fn(), removeAdjustment: vi.fn(), list: vi.fn(), exportUrl: vi.fn() },
}));
import { renterRatingsApi } from "@/lib/api/renterRatings";
import { ApiError } from "@/lib/api/facilities";
import RatingDetailsPanel from "../RatingDetailsPanel";

const get = renterRatingsApi.get as unknown as ReturnType<typeof vi.fn>;
const add = renterRatingsApi.addAdjustment as unknown as ReturnType<typeof vi.fn>;
const removeAdj = renterRatingsApi.removeAdjustment as unknown as ReturnType<typeof vi.fn>;

const ev = (over: Record<string, unknown>) => ({
    type: "BOUNCE", date: "2026-03-01", basePoints: -15, weight: 1, appliedPoints: -15, sourceType: "CHEQUE",
    sourceId: "s1", leaseId: null, adjustmentId: null, note: null, createdByName: null, ...over,
});
const detail = () => ({
    renterId: "r1", score: 52, grade: "C", deductionsCount: 1, base: 70, hasStartedContract: true,
    trend: [{ date: "2026-01-01", score: 70, grade: "B" }, { date: "2026-02-01", score: 60, grade: "C" }],
    events: [
        ev({ type: "BOUNCE", appliedPoints: -7.5, weight: 0.5, basePoints: -15, leaseId: "L9" }),
        ev({ type: "ON_TIME_CHEQUE", basePoints: 1, appliedPoints: 1, date: "2026-02-01" }),
        ev({ type: "ADJUSTMENT", basePoints: 5, appliedPoints: 5, adjustmentId: "adj1", note: "Goodwill", date: "2026-02-10", createdByName: "Sara Admin" }),
    ],
});

beforeEach(() => { h.role = "TENANT_ADMIN"; get.mockReset(); add.mockReset(); removeAdj.mockReset(); get.mockResolvedValue(detail()); });
afterEach(cleanup);

const open = () => render(<RatingDetailsPanel renterId="r1" open onClose={() => {}} />);

describe("RatingDetailsPanel", () => {
    it("panelListsEventsWithHalfWeightTag", async () => {
        open();
        expect(await screen.findByText("Bounced cheque")).toBeInTheDocument();
        expect(screen.getByText("−7.5")).toBeInTheDocument();
        expect(screen.getByText("+1.0")).toBeInTheDocument();
        expect(screen.getAllByText("half weight")).toHaveLength(1);
        expect(screen.getByText("Goodwill")).toBeInTheDocument();
        // spec 3: an adjustment shows who added it (and when: the event date)
        expect(screen.getAllByTestId("rating-adjustment-by")).toHaveLength(1);
        expect(screen.getByText("by Sara Admin")).toBeInTheDocument();
        expect(screen.getByText("Every tenant starts at 70")).toBeInTheDocument();
        expect(screen.getByRole("link", { name: "Contract" })).toHaveAttribute("href", "/dashboard/leases/L9");
        expect(screen.getByRole("img", { name: /Score trend/ })).toBeInTheDocument();
    });

    it("adjustFormOnlyForAdmin", async () => {
        h.role = "PROPERTY_MANAGER";
        open();
        await screen.findByText("Bounced cheque");
        expect(screen.queryByLabelText("Reason")).toBeNull();
        expect(screen.queryByRole("button", { name: /Remove/ })).toBeNull();
        cleanup();
        get.mockClear();

        h.role = "TENANT_ADMIN";
        add.mockResolvedValue(ev({ type: "ADJUSTMENT" }));
        open();
        await screen.findByText("Bounced cheque");
        fireEvent.change(screen.getByLabelText(/^Points/), { target: { value: "5" } });
        fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Bank error" } });
        fireEvent.click(screen.getByRole("button", { name: "Add adjustment" }));
        await waitFor(() => expect(add).toHaveBeenCalledWith("r1", { points: 5, reason: "Bank error" }));
        await waitFor(() => expect(get).toHaveBeenCalledTimes(2));
        expect(screen.getByRole("button", { name: /Remove adjustment: Goodwill/ })).toBeInTheDocument();
    });

    it("adjustFormRejectsOutOfRange", async () => {
        open();
        await screen.findByText("Bounced cheque");
        fireEvent.change(screen.getByLabelText(/^Points/), { target: { value: "31" } });
        fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "x" } });
        fireEvent.click(screen.getByRole("button", { name: "Add adjustment" }));
        expect(await screen.findByRole("alert")).toHaveTextContent("from -30 to 30");
        expect(add).not.toHaveBeenCalled();
    });

    it("adjustErrorsAreLocalizedExceptBusinessRuleText", async () => {
        open();
        await screen.findByText("Bounced cheque");
        const submit = async () => {
            fireEvent.change(screen.getByLabelText(/^Points/), { target: { value: "5" } });
            fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Bank error" } });
            fireEvent.click(screen.getByRole("button", { name: "Add adjustment" }));
        };
        add.mockRejectedValueOnce(new ApiError(400, "Tenant has no started contract"));
        await submit();
        expect(await screen.findByRole("alert")).toHaveTextContent("This tenant has no started contract, so the rating cannot be adjusted.");

        add.mockRejectedValueOnce(new ApiError(400, "Reason is required (1-500 characters)"));
        await submit();
        await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("Reason is required (1-500 characters)"));

        add.mockRejectedValueOnce(new ApiError(404, "Renter not found"));
        await submit();
        await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("Could not save the change."));

        add.mockRejectedValueOnce(new ApiError(500, "NullPointerException at RenterRatingService"));
        await submit();
        await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("Could not save the change."));
        expect(screen.queryByText(/NullPointerException/)).toBeNull();
    });

    it("removeErrorIsLocalizedGeneric", async () => {
        removeAdj.mockRejectedValue(new ApiError(404, "Adjustment not found"));
        const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
        open();
        fireEvent.click(await screen.findByRole("button", { name: /Remove adjustment: Goodwill/ }));
        expect(await screen.findByRole("alert")).toHaveTextContent("Could not save the change.");
        expect(screen.queryByText("Adjustment not found")).toBeNull();
        confirm.mockRestore();
    });

    it("hidesAdjustFormWithoutStartedContract", async () => {
        get.mockResolvedValue({ ...detail(), events: [], grade: "NEW", score: 70, hasStartedContract: false });
        open();
        await screen.findByText("Every tenant starts at 70");
        expect(screen.queryByLabelText("Reason")).toBeNull();
        expect(screen.queryByTestId("rating-adjust-form")).toBeNull();
        expect(screen.getAllByText("New")).toHaveLength(1);
        expect(screen.queryByText("NEW")).toBeNull();
    });

    it("removeAsksForConfirmationFirst", async () => {
        removeAdj.mockResolvedValue(undefined);
        const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
        open();
        const btn = await screen.findByRole("button", { name: /Remove adjustment: Goodwill/ });
        fireEvent.click(btn);
        expect(confirm).toHaveBeenCalledTimes(1);
        expect(removeAdj).not.toHaveBeenCalled();
        confirm.mockReturnValue(true);
        fireEvent.click(btn);
        await waitFor(() => expect(removeAdj).toHaveBeenCalledWith("r1", "adj1"));
        confirm.mockRestore();
    });

    it("removeLabelFallsBackToPointsAndDateWhenNoteIsNull", async () => {
        const d = detail();
        d.events = [ev({ type: "ADJUSTMENT", basePoints: 5, appliedPoints: 5, adjustmentId: "adj2", note: null, date: "2026-02-10" })];
        get.mockResolvedValue(d);
        open();
        const btn = await screen.findByRole("button", { name: /Remove adjustment: \+5/ });
        expect(btn.getAttribute("aria-label")).not.toMatch(/:\s*$/);
    });
});
