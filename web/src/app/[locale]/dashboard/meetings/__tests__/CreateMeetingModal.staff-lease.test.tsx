import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import CreateMeetingModal from "../CreateMeetingModal";

// Scale #6: staff pick the meeting's contract from a server search
// (GET /leases/paged?status=ACTIVE&search=), never from GET /leases, which
// returned every contract the organisation ever wrote.

vi.mock("next-intl", () => ({
    useTranslations: () => (key: string, values?: Record<string, string>) =>
        values ? `${key}:${Object.values(values).join("/")}` : key,
    useLocale: () => "en",
}));

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

const staffSession = { user: { role: "TENANT_ADMIN" } };

const lease = (over: Record<string, unknown>) => ({
    id: "l1", propertyId: "p1", propertyName: "Marina Tower", unitIdentifier: "101", renterName: "Ali Hassan",
    status: "ACTIVE", endDate: "2027-01-31", ...over,
});

function routes() {
    return vi.fn().mockImplementation((input: RequestInfo | URL) => {
        const url = String(input);
        const json = (body: unknown) => Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(body) });
        if (url.startsWith("/api/proxy/v1/leases/paged")) {
            const search = new URL(url, "http://x").searchParams.get("search");
            const rows = search === "205"
                ? [lease({ id: "l2", unitIdentifier: "205", propertyId: "p2", propertyName: "Palm Court", renterName: "Sara Khan" })]
                : [lease({})];
            return json({ content: rows, totalElements: rows.length, totalPages: 1, number: 0, size: 20 });
        }
        if (url.startsWith("/api/proxy/v1/meetings/slots")) {
            return json([{ start: "2027-01-01T09:00:00Z", end: "2027-01-01T09:30:00Z", available: true }]);
        }
        if (url.startsWith("/api/proxy/v1/properties/p2/managers")) return json([{ id: "mgr-2", name: "Bob PM", email: "bob@x.com" }]);
        return json([]);
    });
}

async function openOfficeVisitStep2() {
    fireEvent.click(screen.getByText("officeVisit"));
    fireEvent.click(screen.getByRole("button", { name: /create\.next/ }));
    await screen.findByText("create.relatedLease *");
}

describe("CreateMeetingModal (staff office visit — contract picker)", () => {
    it("searches ACTIVE contracts on the server and never loads GET /leases", async () => {
        vi.useFakeTimers({ shouldAdvanceTime: true });
        const fetchMock = routes();
        global.fetch = fetchMock as unknown as typeof fetch;
        render(<CreateMeetingModal isOpen onClose={() => {}} onSuccess={() => {}} session={staffSession} />);
        await openOfficeVisitStep2();

        fireEvent.click(screen.getByTestId("meeting-lease-picker"));
        expect(await screen.findByText("create.leaseOption:Marina Tower/101")).toBeInTheDocument();
        expect(screen.getByText("Ali Hassan")).toBeInTheDocument();

        fireEvent.change(screen.getByPlaceholderText("searchContracts"), { target: { value: "205" } });
        await vi.advanceTimersByTimeAsync(300);
        fireEvent.click(await screen.findByText("create.leaseOption:Palm Court/205"));
        expect(screen.getByTestId("meeting-lease-picker")).toHaveTextContent("create.leaseOption:Palm Court/205");

        const urls = fetchMock.mock.calls.map((c) => String(c[0]));
        expect(urls.some((u) => u === "/api/proxy/v1/leases" || u.startsWith("/api/proxy/v1/leases?"))).toBe(false);
        expect(urls.some((u) => u.startsWith("/api/proxy/v1/leases/my-leases"))).toBe(false);
        const paged = urls.filter((u) => u.startsWith("/api/proxy/v1/leases/paged"));
        expect(paged.length).toBeGreaterThanOrEqual(2);
        for (const u of paged) expect(new URL(u, "http://x").searchParams.get("status")).toBe("ACTIVE");
        expect(paged.some((u) => new URL(u, "http://x").searchParams.get("search") === "205")).toBe(true);
        vi.useRealTimers();
    });

    it("carries the picked contract's property into the host lookup and its end date into the renewal step", async () => {
        const fetchMock = routes();
        global.fetch = fetchMock as unknown as typeof fetch;
        render(<CreateMeetingModal isOpen onClose={() => {}} onSuccess={() => {}} session={staffSession} />);
        await openOfficeVisitStep2();

        const [purposeSelect] = screen.getAllByRole("combobox");
        fireEvent.change(purposeSelect, { target: { value: "LEASE_RENEWAL" } });
        const next = () => screen.getByRole("button", { name: /create\.next/ });
        expect(next()).toBeDisabled();

        fireEvent.click(screen.getByTestId("meeting-lease-picker"));
        fireEvent.click(await screen.findByText("create.leaseOption:Marina Tower/101"));
        await waitFor(() => expect(next()).not.toBeDisabled());
        fireEvent.click(next());

        await screen.findByText("create.preferredDate *");
        await waitFor(() =>
            expect(fetchMock.mock.calls.some((c) => String(c[0]).startsWith("/api/proxy/v1/properties/p1/managers"))).toBe(true),
        );
    });
});
