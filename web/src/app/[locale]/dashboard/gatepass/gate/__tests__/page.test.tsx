import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fetchRouter } from "@/test/fetchRouter";

const sessionRole = vi.hoisted(() => ({ current: "SECURITY_GUARD" as string }));
const flags = vi.hoisted(() => ({ gatepass: true }));
const translate = vi.hoisted(() => (key: string, vars?: Record<string, string | number>) => {
    if (!vars) return key;
    return Object.entries(vars).reduce((out, [k, v]) => out.replaceAll(`{${k}}`, String(v)), key);
});

vi.mock("next-intl", () => ({ useTranslations: () => translate, useLocale: () => "en" }));
vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { id: "g-1", role: sessionRole.current } }, status: "authenticated" }),
}));
vi.mock("@/hooks/useTenantFeatures", () => ({
    useTenantFeatures: () => ({ isEnabled: (f: string) => f === "GATEPASS" && flags.gatepass, loaded: true, tenantSlug: "acme" }),
}));

import GateDeskPage from "../page";

const ALLOWED = {
    result: "ALLOWED", reason: null, guestName: "Aisha Khan", guestPhone: "+971501234567", vehicleNumber: "DXB 42",
    purpose: "Visit", unitNumber: "A-204", passType: "SINGLE_USE", validFrom: "2026-09-29T05:00:00Z", validTo: "2026-09-29T14:00:00Z",
};
const WALK_IN = {
    id: "w-1", propertyId: "prop-1", unitId: "unit-1", unitNumber: "A-204", guestName: "Ramesh", guestPhone: "+971509999999",
    visitorType: "DELIVERY", purpose: null, vehicleNumber: null, guestPhotoUrl: null, status: "PENDING_APPROVAL",
    validTo: "2026-09-29T06:00:00Z", createdAt: "2026-09-29T05:45:00Z",
};

let api: ReturnType<typeof fetchRouter>;

beforeEach(() => {
    sessionRole.current = "SECURITY_GUARD";
    flags.gatepass = true;
    api = fetchRouter();
    api.on("GET", "/v1/gatepass/my-properties", { body: [{ id: "prop-1", name: "Palm Tower" }] });
    api.on("GET", "/v1/gatepass/expected-today", { body: [] });
    api.on("GET", "/v1/gatepass/walk-in/today", { body: [WALK_IN] });
    api.on("GET", "/v1/gatepass/walk-in/destinations", { body: [
        { unitId: "unit-1", unitNumber: "A-204", propertyId: "prop-1", buildingId: null, buildingName: null },
    ] });
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

async function enterCode(code: string, button: "gate-check-in" | "gate-check-out") {
    fireEvent.change(await screen.findByTestId("gate-code"), { target: { value: code } });
    fireEvent.click(screen.getByTestId(button));
}

describe("Gate desk", () => {
    it.each(["SUPER_ADMIN", "TENANT_ADMIN", "PROPERTY_MANAGER", "RENTER", "ACCOUNTANT"])("is the guard's alone — refuses %s", async role => {
        sessionRole.current = role;
        render(<GateDeskPage />);
        expect(await screen.findByTestId("page-access-denied")).toBeTruthy();
        expect(api.calls).toHaveLength(0);
    });

    it("names the guard's posting", async () => {
        render(<GateDeskPage />);
        expect(await screen.findByText("postedAt")).toBeTruthy();
        expect(screen.queryByTestId("gate-unposted")).toBeNull();
    });

    it("warns an unposted guard rather than showing a quiet gate", async () => {
        api.on("GET", "/v1/gatepass/my-properties", { body: [] });
        render(<GateDeskPage />);
        expect(await screen.findByTestId("gate-unposted")).toBeTruthy();
    });

    it("checks a numeric code in and shows the guest to compare", async () => {
        api.on("POST", "/v1/gatepass/scan", { body: ALLOWED });
        render(<GateDeskPage />);
        await enterCode(" 482913 ", "gate-check-in");
        const result = await screen.findByTestId("gate-result");
        expect(within(result).getByTestId("gate-verdict").textContent).toBe("verdictEntryAllowed");
        expect(within(result).getByText("Aisha Khan")).toBeTruthy();
        expect(api.callsTo("POST", "/scan")[0].body).toEqual({ numericCode: "482913", direction: "ENTRY" });
    });

    it("sends a scanned QR token as qrToken and records an exit", async () => {
        api.on("POST", "/v1/gatepass/scan", { body: ALLOWED });
        render(<GateDeskPage />);
        const token = "ab".repeat(24);
        await enterCode(token, "gate-check-out");
        await screen.findByTestId("gate-result");
        expect(api.callsTo("POST", "/scan")[0].body).toEqual({ qrToken: token, direction: "EXIT" });
        expect(screen.getByTestId("gate-verdict").textContent).toBe("verdictExitRecorded");
    });

    it("shows a refusal in the guard's language, never as allowed", async () => {
        api.on("POST", "/v1/gatepass/scan", { body: { ...ALLOWED, result: "REJECTED", reason: "already used" } });
        render(<GateDeskPage />);
        await enterCode("482913", "gate-check-in");
        expect((await screen.findByTestId("gate-verdict")).textContent).toBe("verdictRejected");
        expect(screen.getByTestId("gate-reason").textContent).toBe("reasonUsed");
    });

    it("says so when the gate is rate limited", async () => {
        api.on("POST", "/v1/gatepass/scan", { status: 429 });
        render(<GateDeskPage />);
        await enterCode("482913", "gate-check-in");
        expect((await screen.findByTestId("gate-scan-error")).textContent).toBe("errTooManyScans");
    });

    it("asks for a code before calling the server", async () => {
        render(<GateDeskPage />);
        fireEvent.click(await screen.findByTestId("gate-check-in"));
        expect((await screen.findByTestId("gate-scan-error")).textContent).toBe("errEnterCode");
        expect(api.callsTo("POST", "/scan")).toHaveLength(0);
    });

    it("registers a walk-in as multipart with an E.164 phone", async () => {
        api.on("POST", /\/v1\/gatepass\/walk-in$/, { body: { ...WALK_IN, id: "w-2", guestName: "Sunil" } });
        render(<GateDeskPage />);
        fireEvent.click(await screen.findByTestId("gate-tab-walkins"));
        const form = await screen.findByTestId("walkin-form");
        await waitFor(() => expect(within(form).getAllByRole("option").some(o => o.textContent === "A-204")).toBe(true));
        fireEvent.change(within(form).getByLabelText("destinationUnit"), { target: { value: "unit-1" } });
        fireEvent.change(within(form).getByLabelText("visitorName"), { target: { value: "Sunil" } });
        fireEvent.change(within(form).getByLabelText("visitorPhone"), { target: { value: "+971 50 777 8888" } });
        fireEvent.click(within(form).getByTestId("walkin-submit"));
        await waitFor(() => expect(api.callsTo("POST", "/walk-in")).toHaveLength(1));
        expect(api.callsTo("POST", "/walk-in")[0].body).toMatchObject({
            propertyId: "prop-1", unitId: "unit-1", name: "Sunil", phone: "+971507778888", visitorType: "GUEST",
        });
        expect((await screen.findByTestId("walkin-notice")).textContent).toBe("walkInPendingNotice");
    });

    it("shows the server's refusal of a walk-in (a photo the policy requires)", async () => {
        api.on("POST", /\/v1\/gatepass\/walk-in$/, { status: 400, body: { message: "A fresh visitor photo is required at this gate" } });
        render(<GateDeskPage />);
        fireEvent.click(await screen.findByTestId("gate-tab-walkins"));
        const form = await screen.findByTestId("walkin-form");
        await waitFor(() => expect(within(form).getAllByRole("option").some(o => o.textContent === "A-204")).toBe(true));
        fireEvent.change(within(form).getByLabelText("destinationUnit"), { target: { value: "unit-1" } });
        fireEvent.change(within(form).getByLabelText("visitorName"), { target: { value: "Sunil" } });
        fireEvent.change(within(form).getByLabelText("visitorPhone"), { target: { value: "+971507778888" } });
        fireEvent.click(within(form).getByTestId("walkin-submit"));
        expect((await screen.findByTestId("walkin-error")).textContent).toBe("errPhotoRequired");
    });

    it("keeps an Admit refusal visible after the status refresh that follows it", async () => {
        api.on("GET", "/v1/gatepass/walk-in/today", { body: [{ ...WALK_IN, status: "ACTIVE" }] });
        api.on("GET", "/v1/gatepass/walk-in/w-1/status", { body: { ...WALK_IN, status: "ACTIVE" } });
        api.on("POST", "/v1/gatepass/walk-in/w-1/admit", { status: 400, body: { message: "outside validity window" } });
        render(<GateDeskPage />);
        fireEvent.click(await screen.findByTestId("gate-tab-walkins"));
        fireEvent.click(await screen.findByTestId("admit-w-1"));
        await waitFor(() => expect(api.callsTo("GET", "/walk-in/w-1/status")).toHaveLength(1));
        // The guard must not be left thinking the entry was recorded.
        await waitFor(() => expect(screen.getByTestId("walkins-error").textContent).toBe("reasonOutsideWindow"));
        expect(screen.getByTestId("status-ACTIVE")).toBeTruthy();
    });

    it("shows the Admit failure, not the refresh failure, when both fail", async () => {
        api.on("GET", "/v1/gatepass/walk-in/today", { body: [{ ...WALK_IN, status: "ACTIVE" }] });
        api.on("GET", "/v1/gatepass/walk-in/w-1/status", { status: 500 });
        api.on("POST", "/v1/gatepass/walk-in/w-1/admit", { status: 500 });
        render(<GateDeskPage />);
        fireEvent.click(await screen.findByTestId("gate-tab-walkins"));
        fireEvent.click(await screen.findByTestId("admit-w-1"));
        await waitFor(() => expect(api.callsTo("GET", "/walk-in/w-1/status")).toHaveLength(1));
        await waitFor(() => expect(screen.getByTestId("walkins-error").textContent).toBe("admitError"));
    });

    it("admits an approved walk-in only once it is active", async () => {
        api.on("GET", "/v1/gatepass/walk-in/w-1/status", { body: { ...WALK_IN, status: "ACTIVE" } });
        api.on("POST", "/v1/gatepass/walk-in/w-1/admit", { body: { ...WALK_IN, status: "USED" } });
        render(<GateDeskPage />);
        fireEvent.click(await screen.findByTestId("gate-tab-walkins"));
        await screen.findByTestId("walkins-table");
        expect(screen.queryByTestId("admit-w-1")).toBeNull();
        fireEvent.click(screen.getByLabelText("refreshStatusOf"));
        fireEvent.click(await screen.findByTestId("admit-w-1"));
        await waitFor(() => expect(screen.getByTestId("status-USED")).toBeTruthy());
        expect(api.callsTo("POST", "/admit")).toHaveLength(1);
    });

    it("is role-gated only: works with the GATEPASS flag off (ruling 2026-09-25, as the backend and mobile apps)", async () => {
        flags.gatepass = false;
        render(<GateDeskPage />);
        expect(await screen.findByTestId("gate-code")).toBeTruthy();
        expect(screen.queryByTestId("gatepass-feature-off")).toBeNull();
    });
});
