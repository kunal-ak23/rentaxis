import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fetchRouter } from "@/test/fetchRouter";

const sessionRole = vi.hoisted(() => ({ current: "TENANT_ADMIN" as string }));
const flags = vi.hoisted(() => ({ gatepass: true }));
const translate = vi.hoisted(() => (key: string, vars?: Record<string, string | number>) => {
    if (!vars) return key;
    return Object.entries(vars).reduce((out, [k, v]) => out.replaceAll(`{${k}}`, String(v)), key);
});

vi.mock("next-intl", () => ({ useTranslations: () => translate, useLocale: () => "en" }));
vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { id: "u-1", role: sessionRole.current } }, status: "authenticated" }),
}));
vi.mock("@/hooks/useTenantFeatures", () => ({
    useTenantFeatures: () => ({ isEnabled: (f: string) => f === "GATEPASS" && flags.gatepass, loaded: true, tenantSlug: "acme" }),
}));

import GatePassApprovalsPage from "../page";

const row = (id: string, over: Record<string, unknown> = {}) => ({
    id, propertyId: "prop-1", propertyName: "Palm Tower", unitId: "unit-1", unitNumber: "A-204",
    guestName: `Guest ${id}`, guestPhone: "+971501234567", purpose: "Maid", vehicleNumber: null, passType: "RECURRING",
    validFrom: "2026-09-30T00:00:00Z", validTo: "2026-10-30T00:00:00Z", status: "PENDING_APPROVAL",
    createdAt: "2026-09-29T05:00:00Z", ...over,
});

let api: ReturnType<typeof fetchRouter>;

beforeEach(() => {
    sessionRole.current = "TENANT_ADMIN";
    flags.gatepass = true;
    api = fetchRouter();
    api.on("GET", "/v1/gatepass/approvals", { body: [row("g1"), row("g2", { propertyId: "prop-2", propertyName: "Marina Heights", passType: "SINGLE_USE" })] });
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("Gate pass approvals", () => {
    it.each(["SUPER_ADMIN", "TENANT_ADMIN", "PROPERTY_MANAGER", "SECURITY_GUARD"])("opens the queue to %s", async role => {
        sessionRole.current = role;
        render(<GatePassApprovalsPage />);
        expect(await screen.findByTestId("approvals-table")).toBeTruthy();
    });

    it.each(["RENTER", "ACCOUNTANT", "TENANT_USER"])("refuses %s and fetches nothing", async role => {
        sessionRole.current = role;
        render(<GatePassApprovalsPage />);
        expect(await screen.findByTestId("page-access-denied")).toBeTruthy();
        expect(api.calls).toHaveLength(0);
    });

    it("never renders a pass credential (the summary carries none)", async () => {
        render(<GatePassApprovalsPage />);
        await screen.findByTestId("approvals-table");
        expect(document.body.textContent).not.toMatch(/qrToken|numericCode/);
    });

    it("filters by property and pass type", async () => {
        render(<GatePassApprovalsPage />);
        const table = await screen.findByTestId("approvals-table");
        expect(within(table).getAllByRole("row")).toHaveLength(3);
        fireEvent.change(screen.getByTestId("approvals-property-filter"), { target: { value: "prop-2" } });
        expect(within(screen.getByTestId("approvals-table")).queryByText("Guest g1")).toBeNull();
        expect(within(screen.getByTestId("approvals-table")).getByText("Guest g2")).toBeTruthy();
        fireEvent.change(screen.getByTestId("approvals-type-filter"), { target: { value: "RECURRING" } });
        expect(await screen.findByTestId("approvals-empty")).toBeTruthy();
    });

    it("approves after confirmation and drops the row from the queue", async () => {
        api.on("POST", "/v1/gatepass/g1/approval", { body: row("g1", { status: "ACTIVE" }) });
        render(<GatePassApprovalsPage />);
        fireEvent.click(await screen.findByTestId("approve-g1"));
        fireEvent.click(await screen.findByTestId("approval-confirm"));
        await waitFor(() => expect(screen.queryByText("Guest g1")).toBeNull());
        expect(api.callsTo("POST", "/g1/approval")[0].body).toEqual({ approved: true });
        expect(screen.getByRole("status").textContent).toContain("approvedNotice");
    });

    it("rejects with approved:false", async () => {
        api.on("POST", "/v1/gatepass/g2/approval", { body: row("g2", { status: "CANCELLED" }) });
        render(<GatePassApprovalsPage />);
        fireEvent.click(await screen.findByTestId("reject-g2"));
        fireEvent.click(await screen.findByTestId("approval-confirm"));
        await waitFor(() => expect(api.callsTo("POST", "/g2/approval")).toHaveLength(1));
        expect(api.callsTo("POST", "/g2/approval")[0].body).toEqual({ approved: false });
    });

    it("shows the server's refusal and reloads the queue when a decision fails", async () => {
        api.on("POST", "/v1/gatepass/g1/approval", { status: 400, body: { message: "Gate pass is not pending approval" } });
        render(<GatePassApprovalsPage />);
        fireEvent.click(await screen.findByTestId("approve-g1"));
        fireEvent.click(await screen.findByTestId("approval-confirm"));
        expect((await screen.findByTestId("gatepass-error")).textContent).toBe("Gate pass is not pending approval");
        await waitFor(() => expect(api.callsTo("GET", "/gatepass/approvals").length).toBe(2));
    });

    it("shows a retryable error when the queue cannot be loaded", async () => {
        api.on("GET", "/v1/gatepass/approvals", { status: 500 });
        render(<GatePassApprovalsPage />);
        expect(await screen.findByTestId("page-load-failed")).toBeTruthy();
    });

    it("is role-gated only: works with the GATEPASS flag off (ruling 2026-09-25, as the backend and mobile apps)", async () => {
        flags.gatepass = false;
        render(<GatePassApprovalsPage />);
        expect(await screen.findByTestId("approvals-table")).toBeTruthy();
        expect(screen.queryByTestId("gatepass-feature-off")).toBeNull();
    });
});
