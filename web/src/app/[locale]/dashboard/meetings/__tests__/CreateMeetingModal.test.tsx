import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi, afterEach } from "vitest";
import CreateMeetingModal from "../CreateMeetingModal";

// Mock next-intl's useTranslations to return the key directly.
vi.mock("next-intl", () => ({
    useTranslations: () => (key: string) => key,
}));

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

const staffSession = { user: { role: "TENANT_ADMIN" } };

/**
 * Routes fetch calls by URL prefix. Unmatched URLs resolve to an empty array
 * so incidental fetches (slots, leases) never reject.
 */
function mockFetchRoutes(routes: Record<string, unknown>) {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
        const url = String(input);
        // Longest prefix wins so "/v1/properties/p1/managers" is not
        // shadowed by "/v1/properties".
        const hit = Object.keys(routes)
            .sort((a, b) => b.length - a.length)
            .find((k) => url.startsWith(k));
        return Promise.resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve(hit !== undefined ? routes[hit] : []),
        });
    });
    global.fetch = fetchMock as unknown as typeof fetch;
    return fetchMock;
}

async function goToPropertyVisitStep2() {
    fireEvent.click(screen.getByText("propertyVisit"));
    fireEvent.click(screen.getByRole("button", { name: /Next/ }));
    // Step 2: property dropdown populated from GET /v1/properties
    await screen.findByText("Marina Tower");
}

const propertyRows = (managers: Array<{ id: string; name: string; email: string }>) => [
    {
        // PropertyStatsDTO shape: nested property + assignedManagers list
        property: { id: "p1", nameEn: "Marina Tower" },
        assignedManagers: managers,
    },
];

describe("CreateMeetingModal (property visit data contracts)", () => {
    it("loads units from /v1/units/property/{id} and derives the host from assignedManagers", async () => {
        const fetchMock = mockFetchRoutes({
            "/api/proxy/v1/properties": propertyRows([
                { id: "mgr-1", name: "Alice Manager", email: "alice@x.com" },
            ]),
            "/api/proxy/v1/units/property/p1": [{ id: "u1", unitNumber: "101" }],
        });

        render(
            <CreateMeetingModal isOpen onClose={() => {}} onSuccess={() => {}} session={staffSession} />,
        );
        await goToPropertyVisitStep2();

        const [propertySelect] = screen.getAllByRole("combobox");
        fireEvent.change(propertySelect, { target: { value: "p1" } });

        // Units must come from the property-scoped endpoint, not /v1/units?propertyId=
        await screen.findByText("101");
        const unitCalls = fetchMock.mock.calls.map((c) => String(c[0]));
        expect(unitCalls).toContain("/api/proxy/v1/units/property/p1");
        expect(unitCalls.some((u) => u.includes("/v1/units?"))).toBe(false);

        // Host auto-derived from assignedManagers[0] — no PM picker on step 3
        fireEvent.click(screen.getByRole("button", { name: /Next/ }));
        await screen.findByText("Preferred Date *");
        expect(screen.queryByText("Property Manager *")).toBeNull();
        expect(
            fetchMock.mock.calls.some((c) => String(c[0]).includes("/managers")),
        ).toBe(false);
    });

    it("shows the PM picker with User.name labels when the property has no assigned manager", async () => {
        mockFetchRoutes({
            "/api/proxy/v1/properties": propertyRows([]),
            "/api/proxy/v1/units/property/p1": [],
            "/api/proxy/v1/properties/p1/managers": [
                // User entity serializes `name` — not fullName/firstName/lastName
                { id: "mgr-2", name: "Bob PM", email: "bob@x.com" },
            ],
        });

        render(
            <CreateMeetingModal isOpen onClose={() => {}} onSuccess={() => {}} session={staffSession} />,
        );
        await goToPropertyVisitStep2();

        const [propertySelect] = screen.getAllByRole("combobox");
        fireEvent.change(propertySelect, { target: { value: "p1" } });
        fireEvent.click(screen.getByRole("button", { name: /Next/ }));

        await screen.findByText("Property Manager *");
        // Option label renders the User.name field, not "(email)" alone
        await screen.findByText("Bob PM (bob@x.com)");
    });
});
