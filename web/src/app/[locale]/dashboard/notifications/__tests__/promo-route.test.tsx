import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";
import ar from "../../../../../../messages/ar.json";

/**
 * The notifications page, for a promotion ad's notification: worded from its key
 * in the viewer's language, and a click opens the viewer's own promotion page.
 */
const session = vi.hoisted(() => ({ data: null as unknown }));
const push = vi.hoisted(() => vi.fn());
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: session.data, status: "authenticated" }) }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ push }) }));

import NotificationsPage from "../page";

const PROMO = {
    id: "n1", type: "PROMO_AD_REJECTED", title: "Ad rejected",
    message: 'Your ad "Wash and fold" (Sparkle Laundry) was rejected: Image is blurry',
    messageKey: "PROMO_AD_REJECTED",
    params: { adTitle: "Wash and fold", businessName: "Sparkle Laundry", reason: "Image is blurry" },
    referenceType: "PROMO_AD", referenceId: "ad-1", isRead: true, createdAt: "2026-10-27T10:00:00Z",
};

function renderPage(role: string, locale: "en" | "ar" = "en") {
    // A stable object: the page's fetch effect depends on `session?.user`.
    session.data = { user: { role } };
    render(<NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}><NotificationsPage /></NextIntlClientProvider>);
}

beforeEach(() => {
    push.mockClear();
    global.fetch = vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        const body = url.includes("/v1/notifications?") ? [PROMO] : null;
        return { ok: true, json: async () => body } as unknown as Response;
    }) as unknown as typeof fetch;
});
afterEach(cleanup);

describe("NotificationsPage — promotion ad notifications", () => {
    it("words it in Arabic and labels its type", async () => {
        renderPage("PROMOTER", "ar");
        expect(await screen.findByText(ar.Notifications.messages.PROMO_AD_REJECTED.title)).toBeInTheDocument();
        expect(screen.getByText(/تم رفض إعلانك .*Wash and fold.*Image is blurry/)).toBeInTheDocument();
        expect(screen.getByText(ar.Notifications.referenceType.PROMO_AD)).toBeInTheDocument();
    });

    it.each([
        ["TENANT_ADMIN", "/en/dashboard/promotions"],
        ["SUPER_ADMIN", "/en/dashboard/promotions"],
        ["PROMOTER", "/en/dashboard/promoter"],
    ])("opens %s's page on click", async (role, route) => {
        renderPage(role);
        fireEvent.click(await screen.findByText("Ad rejected"));
        await waitFor(() => expect(push).toHaveBeenCalledWith(route));
    });

    it("goes nowhere for a role with no such page", async () => {
        renderPage("PROPERTY_MANAGER");
        fireEvent.click(await screen.findByText("Ad rejected"));
        await new Promise(r => setTimeout(r, 0));
        expect(push).not.toHaveBeenCalled();
    });
});
