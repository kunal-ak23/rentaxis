import { cleanup, render, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";

// Break round 1 (nav): /dashboard/leases?propertyId=not-a-uuid sent the
// garbage to the API, got a 400 and logged an unhandled ApiError. An invalid
// id is now no filter, is never sent, and is dropped from the URL.

vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
    useRouter: () => ({ push: vi.fn() }),
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
vi.mock("../LeaseWizard", () => ({ default: () => null }));
vi.mock("@/components/ui/Pagination", () => ({ Pagination: () => <div data-testid="pagination" /> }));

const api = vi.hoisted(() => ({ paged: vi.fn(), statsByLeases: vi.fn() }));
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return {
        ...m,
        leaseApi: { ...m.leaseApi, paged: api.paged },
        chequeApi: { ...m.chequeApi, statsByLeases: api.statsByLeases },
    };
});

import LeasesPage from "../page";

const GOOD = "b394c93d-94d8-40e2-91b6-0740febaf250";

beforeEach(() => {
    api.paged.mockImplementation(async (q: { size?: number } = {}) => ({ content: [], totalElements: 0, totalPages: 1, number: 0, size: q.size ?? 25 }));
    api.statsByLeases.mockImplementation(async () => []);
    global.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => [] })) as unknown as typeof fetch;
});
afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    window.history.replaceState(null, "", "/");
});

const render_ = () => render(
    <NextIntlClientProvider locale="en" messages={en}>
        <LeasesPage />
    </NextIntlClientProvider>,
);
const sentIds = () => api.paged.mock.calls.map(([q]) => [q?.propertyId, q?.buildingId]);

describe("contracts list with a malformed id in the URL", () => {
    it("propertyId=not-a-uuid is not sent to the API and is removed from the URL", async () => {
        window.history.replaceState(null, "", "/en/dashboard/leases?propertyId=not-a-uuid&view=active");
        render_();
        await waitFor(() => expect(api.paged).toHaveBeenCalled());
        expect(sentIds().every(([p]) => p === undefined)).toBe(true);
        await waitFor(() => expect(new URLSearchParams(window.location.search).has("propertyId")).toBe(false));
        expect(new URLSearchParams(window.location.search).get("view")).toBe("active");
    });

    it("buildingId=garbage is dropped while a valid propertyId is kept and sent", async () => {
        window.history.replaceState(null, "", `/en/dashboard/leases?propertyId=${GOOD}&buildingId=b394c93d-94d8`);
        render_();
        await waitFor(() => expect(api.paged).toHaveBeenCalled());
        expect(sentIds().every(([p, b]) => p === GOOD && b === undefined)).toBe(true);
        await waitFor(() => expect(new URLSearchParams(window.location.search).has("buildingId")).toBe(false));
        expect(new URLSearchParams(window.location.search).get("propertyId")).toBe(GOOD);
    });
});
