import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";

/**
 * /dashboard/properties/[id]/units had no inbound link (inventory 2026-09-25):
 * its occupancy view (RESERVED / MAINTENANCE, the incoming tenant) was only
 * reachable by typing the URL. The property's Units tab now links to it.
 */

vi.mock("next/navigation", () => ({
    useParams: () => ({ id: "p1" }),
    useRouter: () => ({ push: vi.fn(), refresh: vi.fn() }),
    usePathname: () => "/en/dashboard/properties/p1",
    useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
    useRouter: () => ({ push: vi.fn() }),
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN", name: "U", tenantId: "t1" } } }) }));

import PropertyDetailPage from "../page";

beforeEach(() => {
    global.fetch = vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        const body = url.endsWith("/v1/properties/p1")
            ? { id: "p1", nameEn: "Belle Vue", emirate: "DUBAI", type: "RESIDENTIAL" }
            : [];
        return { ok: true, status: 200, json: async () => body } as unknown as Response;
    }) as unknown as typeof fetch;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("property detail — Units tab", () => {
    it("links the Units tab to the full units page", async () => {
        render(<NextIntlClientProvider locale="en" messages={en}><PropertyDetailPage /></NextIntlClientProvider>);
        fireEvent.click(await screen.findByRole("button", { name: /^units$/i }));
        expect(await screen.findByTestId("property-units-page-link")).toHaveAttribute("href", "/dashboard/properties/p1/units");
    });
});
