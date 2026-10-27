import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";

import en from "../../../../messages/en.json";
import ar from "../../../../messages/ar.json";

/**
 * A promotion ad's notification in the bell: worded in the viewer's language,
 * and clicking it opens the admin's Promotions or the promoter's portal.
 */
const session = vi.hoisted(() => ({ role: "TENANT_ADMIN" as string, data: null as unknown }));
const push = vi.hoisted(() => vi.fn());
vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: session.data }),
    signOut: vi.fn(),
}));
vi.mock("next/navigation", () => ({
    useSearchParams: () => new URLSearchParams(),
    usePathname: () => "/en/dashboard",
    useRouter: () => ({ push, refresh: vi.fn() }),
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
    useRouter: () => ({ push: vi.fn() }),
}));
vi.mock("../GlobalSearch", () => ({ default: () => <div /> }));
vi.mock("@/hooks/useTenantFeatures", () => ({
    useTenantFeatures: () => ({ isEnabled: () => true, tenantSlug: "acme", features: {}, loading: false }),
}));

import { TopHeader } from "../TopHeader";

const PROMO = {
    id: "n1", type: "PROMO_AD_SUBMITTED", title: "Ad awaiting approval",
    message: 'Pat Promoter sent "Wash and fold" (Sparkle Laundry) for approval.',
    messageKey: "PROMO_AD_SUBMITTED",
    params: { promoterName: "Pat Promoter", adTitle: "Wash and fold", businessName: "Sparkle Laundry", adCount: "1" },
    referenceType: "PROMO_AD", referenceId: "ad-1", isRead: false, createdAt: new Date().toISOString(),
};

function renderBell(role: string, locale: "en" | "ar" = "en") {
    session.role = role;
    session.data = { user: { name: "Someone", email: "someone@example.test", role } };
    render(<NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}><TopHeader /></NextIntlClientProvider>);
}

beforeEach(() => {
    push.mockClear();
    global.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
        if (init?.method === "PUT") return { ok: true, status: 200, json: async () => ({}) } as unknown as Response;
        const body = String(url).includes("unread-count") ? 1 : [PROMO];
        return { ok: true, json: async () => body } as unknown as Response;
    }) as never;
});
afterEach(cleanup);

describe("TopHeader bell — promotion ad notifications", () => {
    it("words it in Arabic from its key and values", async () => {
        renderBell("TENANT_ADMIN", "ar");
        fireEvent.click(await screen.findByText("1"));
        expect(await screen.findByText("إعلان بانتظار الموافقة")).toBeInTheDocument();
        expect(screen.getByText(/أرسل .*Pat Promoter.* الإعلان/)).toBeInTheDocument();
    });

    it.each([
        ["TENANT_ADMIN", "/en/dashboard/promotions"],
        ["SUPER_ADMIN", "/en/dashboard/promotions"],
        ["PROMOTER", "/en/dashboard/promoter"],
    ])("opens %s's page on click", async (role, route) => {
        renderBell(role);
        fireEvent.click(await screen.findByText("1"));
        fireEvent.click(await screen.findByText("Ad awaiting approval"));
        await waitFor(() => expect(push).toHaveBeenCalledWith(route));
    });

    it("goes nowhere for a role with no such page", async () => {
        renderBell("PROPERTY_MANAGER");
        fireEvent.click(await screen.findByText("1"));
        fireEvent.click(await screen.findByText("Ad awaiting approval"));
        await waitFor(() => expect(vi.mocked(global.fetch)).toHaveBeenCalledWith("/api/proxy/v1/notifications/n1/read", { method: "PUT" }));
        await new Promise(r => setTimeout(r, 0));
        expect(push).not.toHaveBeenCalled();
    });
});
