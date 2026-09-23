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

const renterSession = { user: { role: "RENTER" } };

const leaseRows = [
    {
        id: "l1",
        propertyId: "p1",
        propertyName: "Marina Tower",
        unitIdentifier: "101",
        status: "ACTIVE",
        endDate: "2027-01-01",
    },
];

async function goToRenterStep3(fetchMock: ReturnType<typeof vi.fn>) {
    render(
        <CreateMeetingModal isOpen onClose={() => {}} onSuccess={() => {}} session={renterSession} />,
    );
    fireEvent.click(screen.getByText("officeVisit"));
    fireEvent.click(screen.getByRole("button", { name: /Next/ }));

    await screen.findByText("Marina Tower — Unit 101");
    const [purposeSelect, leaseSelect] = screen.getAllByRole("combobox");
    fireEvent.change(purposeSelect, { target: { value: "CHEQUE_REPLACEMENT" } });
    fireEvent.change(leaseSelect, { target: { value: "l1" } });
    fireEvent.click(screen.getByRole("button", { name: /Next/ }));

    await screen.findByText("Preferred Date *");
    void fetchMock;
}

describe("CreateMeetingModal (renter default-host handling)", () => {
    it("shows a no-host message and disables Next when default-host 404s", async () => {
        const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
            const url = String(input);
            if (url.startsWith("/api/proxy/v1/leases/my-leases")) {
                return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(leaseRows) });
            }
            if (url.startsWith("/api/proxy/v1/meetings/default-host")) {
                return Promise.resolve({ ok: false, status: 404, json: () => Promise.resolve({}) });
            }
            return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve([]) });
        });
        global.fetch = fetchMock as unknown as typeof fetch;

        await goToRenterStep3(fetchMock);

        await screen.findByText("noHostAvailable");
        expect(screen.getByRole("button", { name: /Next/ })).toBeDisabled();
        // Dead slot grid must not render alongside the message.
        expect(screen.queryByText("Available Slots")).toBeNull();
    });

    it("shows a retry-able error when default-host fails with a server error", async () => {
        let defaultHostCalls = 0;
        const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
            const url = String(input);
            if (url.startsWith("/api/proxy/v1/leases/my-leases")) {
                return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(leaseRows) });
            }
            if (url.startsWith("/api/proxy/v1/meetings/default-host")) {
                defaultHostCalls += 1;
                return Promise.resolve({ ok: false, status: 500, json: () => Promise.resolve({}) });
            }
            return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve([]) });
        });
        global.fetch = fetchMock as unknown as typeof fetch;

        await goToRenterStep3(fetchMock);

        await screen.findByText("slotsLoadError");
        expect(screen.getByRole("button", { name: /Next/ })).toBeDisabled();
        expect(defaultHostCalls).toBe(1);

        fireEvent.click(screen.getByText("retry"));
        await vi.waitFor(() => expect(defaultHostCalls).toBe(2));
    });

    it("still loads the slot grid as before when default-host resolves", async () => {
        const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
            const url = String(input);
            if (url.startsWith("/api/proxy/v1/leases/my-leases")) {
                return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(leaseRows) });
            }
            if (url.startsWith("/api/proxy/v1/meetings/default-host")) {
                return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ userId: "host-1" }) });
            }
            if (url.startsWith("/api/proxy/v1/meetings/slots")) {
                return Promise.resolve({
                    ok: true,
                    status: 200,
                    json: () => Promise.resolve([{ start: "2027-01-01T09:00:00Z", end: "2027-01-01T09:30:00Z", available: true }]),
                });
            }
            return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve([]) });
        });
        global.fetch = fetchMock as unknown as typeof fetch;

        await goToRenterStep3(fetchMock);
        expect(screen.queryByText("noHostAvailable")).toBeNull();
        expect(screen.queryByText("slotsLoadError")).toBeNull();

        const dateInput = document.querySelector('input[type="date"]');
        if (!dateInput) throw new Error("date input not found");
        fireEvent.change(dateInput, { target: { value: "2027-01-01" } });

        await vi.waitFor(() => {
            const slotCalls = fetchMock.mock.calls.map((c) => String(c[0]));
            expect(slotCalls.some((u) => u.includes("/api/proxy/v1/meetings/slots?hostUserId=host-1"))).toBe(true);
        });
    });
});
