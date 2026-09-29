import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fetchRouter } from "@/test/fetchRouter";

const sessionRole = vi.hoisted(() => ({ current: "RENTER" as string }));
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
vi.mock("@/lib/businessDate", () => ({ businessTodayIso: () => "2026-09-29" }));

import TenantGatePassesPage from "../page";

const PASS = {
    id: "p-1", propertyId: "prop-1", unitId: "unit-1", guestName: "Aisha Khan", guestPhone: "+971501234567",
    purpose: "Family visit", vehicleNumber: null, passType: "RECURRING", validFrom: "2026-09-30T05:00:00Z",
    validTo: "2026-10-30T19:59:00Z", status: "PENDING_APPROVAL", qrToken: "q".repeat(48), numericCode: "482913",
    createdAt: "2026-09-29T05:00:00Z",
};
const CONTRACT = {
    id: "l-1", unitId: "unit-1", unitIdentifier: "A-204", propertyId: "prop-1", propertyName: "Palm Tower",
    status: "ACTIVE", startDate: "2026-01-01", endDate: "2026-12-31",
};
const VISITOR = {
    id: "w-1", propertyId: "prop-1", unitId: "unit-1", unitNumber: "A-204", guestName: "Ramesh", guestPhone: "+971509999999",
    visitorType: "DELIVERY", purpose: "Parcel", vehicleNumber: null, guestPhotoUrl: null, status: "PENDING_APPROVAL",
    validTo: "2026-09-29T06:00:00Z", createdAt: "2026-09-29T05:45:00Z",
};

let api: ReturnType<typeof fetchRouter>;

beforeEach(() => {
    sessionRole.current = "RENTER";
    flags.gatepass = true;
    api = fetchRouter();
    api.on("GET", "/v1/gatepass/mine", { body: [PASS] });
    api.on("GET", "/v1/leases/my-leases", { body: [CONTRACT] });
    api.on("GET", "/v1/gatepass/resident-approvals", { body: [VISITOR] });
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("Tenant gate passes", () => {
    it("lists the tenant's own passes with the unit and status", async () => {
        render(<TenantGatePassesPage />);
        const table = await screen.findByTestId("gatepass-table");
        expect(within(table).getByText("Aisha Khan")).toBeTruthy();
        expect(within(table).getByText("A-204")).toBeTruthy();
        expect(within(table).getByTestId("status-PENDING_APPROVAL")).toBeTruthy();
        // Only the tenant's own reads — never a staff queue.
        expect(api.calls.some(c => c.url.includes("/gatepass/approvals"))).toBe(false);
    });

    it.each(["TENANT_ADMIN", "PROPERTY_MANAGER", "SECURITY_GUARD", "ACCOUNTANT"])("refuses %s and fetches nothing", async role => {
        sessionRole.current = role;
        render(<TenantGatePassesPage />);
        expect(await screen.findByTestId("page-access-denied")).toBeTruthy();
        expect(api.calls).toHaveLength(0);
    });

    it("is role-gated only: works with the GATEPASS flag off (ruling 2026-09-25, as the backend and mobile apps)", async () => {
        flags.gatepass = false;
        render(<TenantGatePassesPage />);
        expect(await screen.findByTestId("gatepass-table")).toBeTruthy();
        expect(screen.queryByTestId("gatepass-feature-off")).toBeNull();
    });

    it("shows a retryable error when the passes cannot be loaded", async () => {
        api.on("GET", "/v1/gatepass/mine", { status: 500 });
        render(<TenantGatePassesPage />);
        expect(await screen.findByTestId("page-load-failed")).toBeTruthy();
    });

    it("shows the gate code in the detail, marked as not yet usable while pending", async () => {
        render(<TenantGatePassesPage />);
        fireEvent.click(await screen.findByText("view"));
        const detail = await screen.findByTestId("gatepass-detail");
        expect(within(detail).getByTestId("gatepass-code").textContent).toBe("482913");
        expect(within(detail).getByText("codePendingHint")).toBeTruthy();
    });

    it("requests a single-visit pass for the current contract's unit with an E.164 phone", async () => {
        api.on("POST", /\/v1\/gatepass$/, c => ({ body: { ...PASS, id: "p-2", ...(c.body as object), status: "ACTIVE" } }));
        render(<TenantGatePassesPage />);
        fireEvent.click(await screen.findByTestId("gatepass-new"));
        const form = await screen.findByTestId("gatepass-form");
        fireEvent.change(within(form).getByLabelText("guestName"), { target: { value: "Omar" } });
        fireEvent.change(within(form).getByLabelText("guestPhone"), { target: { value: "00971 50 111 2222" } });
        fireEvent.change(within(form).getByLabelText("visitDate"), { target: { value: "2026-10-02" } });
        fireEvent.click(within(form).getByTestId("gatepass-submit"));

        await waitFor(() => expect(api.callsTo("POST", "/v1/gatepass")).toHaveLength(1));
        const body = api.callsTo("POST", "/v1/gatepass")[0].body as Record<string, string>;
        expect(body.unitId).toBe("unit-1");
        expect(body.guestPhone).toBe("+971501112222");
        expect(body.passType).toBe("SINGLE_USE");
        expect(new Date(body.validTo).getTime()).toBeGreaterThan(new Date(body.validFrom).getTime());
        expect(body).not.toHaveProperty("propertyId");
    });

    it("refuses a phone without a country code before calling the server", async () => {
        render(<TenantGatePassesPage />);
        fireEvent.click(await screen.findByTestId("gatepass-new"));
        const form = await screen.findByTestId("gatepass-form");
        fireEvent.change(within(form).getByLabelText("guestName"), { target: { value: "Omar" } });
        fireEvent.change(within(form).getByLabelText("guestPhone"), { target: { value: "12" } });
        fireEvent.click(within(form).getByTestId("gatepass-submit"));
        expect((await screen.findByTestId("gatepass-form-error")).textContent).toBe("errPhone");
        expect(api.callsTo("POST", "/v1/gatepass")).toHaveLength(0);
    });

    it("explains the server's 404 as the unit not being on a current contract", async () => {
        api.on("POST", /\/v1\/gatepass$/, { status: 404, body: { message: "Unit is not on an active lease of yours" } });
        render(<TenantGatePassesPage />);
        fireEvent.click(await screen.findByTestId("gatepass-new"));
        const form = await screen.findByTestId("gatepass-form");
        fireEvent.change(within(form).getByLabelText("guestName"), { target: { value: "Omar" } });
        fireEvent.change(within(form).getByLabelText("guestPhone"), { target: { value: "+971501112222" } });
        fireEvent.change(within(form).getByLabelText("visitDate"), { target: { value: "2026-10-02" } });
        fireEvent.click(within(form).getByTestId("gatepass-submit"));
        expect((await screen.findByTestId("gatepass-form-error")).textContent).toBe("errNotCurrentContract");
    });

    it("blocks the form when no contract is current today (ended contract still ACTIVE)", async () => {
        api.on("GET", "/v1/leases/my-leases", { body: [{ ...CONTRACT, endDate: "2026-09-01" }] });
        render(<TenantGatePassesPage />);
        fireEvent.click(await screen.findByTestId("gatepass-new"));
        expect(await screen.findByTestId("gatepass-no-contract")).toBeTruthy();
        expect(screen.queryByTestId("gatepass-form")).toBeNull();
    });

    it("cancels a pending pass after confirmation", async () => {
        api.on("POST", "/v1/gatepass/p-1/cancel", { body: { ...PASS, status: "CANCELLED" } });
        render(<TenantGatePassesPage />);
        fireEvent.click(await screen.findByTestId("cancel-p-1"));
        fireEvent.click(await screen.findByTestId("gatepass-cancel-confirm"));
        await waitFor(() => expect(screen.getByTestId("status-CANCELLED")).toBeTruthy());
        expect(api.callsTo("POST", "/cancel")).toHaveLength(1);
    });

    it("shows the server's refusal when a cancel fails", async () => {
        api.on("POST", "/v1/gatepass/p-1/cancel", { status: 400, body: { message: "Gate pass has already been used" } });
        render(<TenantGatePassesPage />);
        fireEvent.click(await screen.findByTestId("cancel-p-1"));
        fireEvent.click(await screen.findByTestId("gatepass-cancel-confirm"));
        expect((await screen.findByTestId("gatepass-error")).textContent).toBe("Gate pass has already been used");
    });

    it("lets the tenant approve a walk-in waiting at the gate", async () => {
        api.on("POST", "/v1/gatepass/resident-approvals/w-1", { body: { ...VISITOR, status: "ACTIVE" } });
        render(<TenantGatePassesPage />);
        fireEvent.click(await screen.findByTestId("gatepass-tab-visitors"));
        fireEvent.click(await screen.findByTestId("visitor-approve-w-1"));
        await waitFor(() => expect(api.callsTo("POST", "/resident-approvals/w-1")).toHaveLength(1));
        expect(api.callsTo("POST", "/resident-approvals/w-1")[0].body).toEqual({ approved: true });
        expect(await screen.findByTestId("visitors-empty")).toBeTruthy();
    });
});
