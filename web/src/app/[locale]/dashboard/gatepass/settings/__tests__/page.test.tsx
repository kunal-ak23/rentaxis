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

import GatePassSettingsPage from "../page";

const POLICY = {
    id: null, propertyId: "prop-1", buildingId: null, inherited: true, requireUnregisteredApproval: true,
    requireRegisteredApproval: false, notifyRegisteredEntry: true, requireFreshPhoto: true, approvalTimeoutMinutes: 15,
};

let api: ReturnType<typeof fetchRouter>;

beforeEach(() => {
    sessionRole.current = "TENANT_ADMIN";
    flags.gatepass = true;
    api = fetchRouter();
    api.on("GET", "/v1/properties", { body: [
        { property: { id: "prop-1", nameEn: "Palm Tower", nameAr: null } },
        { property: { id: "prop-2", nameEn: "Marina Heights", nameAr: null } },
    ] });
    api.on("GET", "/v1/buildings/property/", { body: [] });
    api.on("GET", "/v1/gatepass/policies/effective", { body: POLICY });
    api.on("GET", "/v1/units/property/", { body: [{ id: "unit-1", unitNumber: "A-204" }] });
    api.on("GET", "/admin/users", { body: [
        { id: "g-1", name: "Ravi", email: null, phone: "+971500000001", role: "SECURITY_GUARD" },
        { id: "m-1", name: "Maya", email: "maya@x.test", role: "PROPERTY_MANAGER" },
    ] });
    api.on("GET", "/v1/gatepass/guards/g-1/properties", { body: ["prop-1"] });
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("Gate policy & visitors", () => {
    it.each(["SECURITY_GUARD", "RENTER", "ACCOUNTANT", "TENANT_USER"])("refuses %s and fetches nothing", async role => {
        sessionRole.current = role;
        render(<GatePassSettingsPage />);
        expect(await screen.findByTestId("page-access-denied")).toBeTruthy();
        expect(api.calls).toHaveLength(0);
    });

    it("loads the effective policy for the first property and says it is inherited", async () => {
        render(<GatePassSettingsPage />);
        expect(await screen.findByTestId("policy-inherited")).toBeTruthy();
        expect((screen.getByTestId("policy-requireFreshPhoto") as HTMLInputElement).checked).toBe(true);
        expect(api.callsTo("GET", "/policies/effective")[0].url).toContain("propertyId=prop-1");
    });

    it("saves the edited policy for the chosen property", async () => {
        api.on("PUT", "/v1/gatepass/policies", c => ({ body: { ...POLICY, ...(c.body as object), inherited: false, id: "pol-1", propertyId: "prop-2" } }));
        render(<GatePassSettingsPage />);
        await screen.findByTestId("policy-inherited");
        fireEvent.change(screen.getByTestId("settings-property"), { target: { value: "prop-2" } });
        await waitFor(() => expect(api.callsTo("GET", "propertyId=prop-2").length).toBeGreaterThan(0));
        fireEvent.click(await screen.findByTestId("policy-requireFreshPhoto"));
        fireEvent.change(screen.getByTestId("policy-timeout"), { target: { value: "30" } });
        fireEvent.click(screen.getByTestId("policy-save"));
        expect(await screen.findByTestId("policy-saved")).toBeTruthy();
        const put = api.callsTo("PUT", "/policies")[0];
        expect(put.url).toContain("propertyId=prop-2");
        expect(put.body).toEqual({
            requireUnregisteredApproval: true, requireRegisteredApproval: false, notifyRegisteredEntry: true,
            requireFreshPhoto: false, approvalTimeoutMinutes: 30,
        });
    });

    it("refuses an out-of-range timeout before calling the server", async () => {
        render(<GatePassSettingsPage />);
        await screen.findByTestId("policy-inherited");
        fireEvent.change(screen.getByTestId("policy-timeout"), { target: { value: "2000" } });
        fireEvent.click(screen.getByTestId("policy-save"));
        expect((await screen.findByTestId("policy-save-error")).textContent).toBe("errTimeout");
        expect(api.callsTo("PUT", "/policies")).toHaveLength(0);
    });

    it("shows the server's refusal when the policy cannot be saved", async () => {
        api.on("PUT", "/v1/gatepass/policies", { status: 403, body: { message: "Access denied" } });
        render(<GatePassSettingsPage />);
        await screen.findByTestId("policy-inherited");
        fireEvent.click(screen.getByTestId("policy-save"));
        expect((await screen.findByTestId("policy-save-error")).textContent).toBe("Access denied");
    });

    it("registers a regular visitor for a unit", async () => {
        api.on("POST", "/v1/gatepass/visitors/registration", { body: { id: "v-1", name: "Maria", phone: "+971501110000", visitorType: "MAID" } });
        render(<GatePassSettingsPage />);
        const form = await screen.findByTestId("visitor-form");
        await waitFor(() => expect(within(form).getAllByRole("option").some(o => o.textContent === "A-204")).toBe(true));
        fireEvent.change(within(form).getByLabelText("colUnit"), { target: { value: "unit-1" } });
        fireEvent.change(within(form).getByLabelText("visitorName"), { target: { value: "Maria" } });
        fireEvent.change(within(form).getByLabelText("visitorPhone"), { target: { value: "+971 50 111 0000" } });
        fireEvent.click(within(form).getByTestId("visitor-submit"));
        expect(await screen.findByTestId("visitor-notice")).toBeTruthy();
        expect(api.callsTo("POST", "/visitors/registration")[0].body).toMatchObject({
            propertyId: "prop-1", unitId: "unit-1", name: "Maria", phone: "+971501110000", visitorType: "MAID",
            validFrom: null, validTo: null, active: true,
        });
    });

    it("lists only security guards and saves a guard's posting", async () => {
        api.on("PUT", "/v1/gatepass/guards/g-1/properties", c => ({ body: c.body }));
        render(<GatePassSettingsPage />);
        const table = await screen.findByTestId("guards-table");
        expect(within(table).getByText("Ravi")).toBeTruthy();
        expect(within(table).queryByText("Maya")).toBeNull();
        fireEvent.click(within(table).getByTestId("guard-edit-g-1"));
        const posting = await screen.findByTestId("guard-posting");
        fireEvent.click(within(posting).getByLabelText("Marina Heights"));
        fireEvent.click(within(posting).getByTestId("guard-posting-save"));
        await waitFor(() => expect(api.callsTo("PUT", "/guards/g-1/properties")).toHaveLength(1));
        expect([...(api.callsTo("PUT", "/guards/g-1/properties")[0].body as string[])].sort()).toEqual(["prop-1", "prop-2"]);
    });

    it("does not offer guard postings to a property manager (no guard list on the web for that role)", async () => {
        sessionRole.current = "PROPERTY_MANAGER";
        render(<GatePassSettingsPage />);
        expect(await screen.findByTestId("guards-admin-only")).toBeTruthy();
        expect(api.calls.some(c => c.url.includes("/admin/users"))).toBe(false);
    });

    it("shows a retryable error when properties cannot be loaded", async () => {
        api.on("GET", "/v1/properties", { status: 500 });
        render(<GatePassSettingsPage />);
        expect(await screen.findByTestId("page-load-failed")).toBeTruthy();
    });

    it("is role-gated only: works with the GATEPASS flag off (ruling 2026-09-25, as the backend and mobile apps)", async () => {
        flags.gatepass = false;
        render(<GatePassSettingsPage />);
        expect(await screen.findByTestId("policy-section")).toBeTruthy();
        expect(screen.queryByTestId("gatepass-feature-off")).toBeNull();
    });
});
