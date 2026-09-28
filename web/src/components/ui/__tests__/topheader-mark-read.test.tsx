import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";

import ar from "../../../../messages/ar.json";

/**
 * Break-it R2 silent-mutation sweep: the bell's "Mark all read" never looked
 * at the response — a refused PUT zeroed the badge and greyed every row until
 * the next poll brought them back. A refused mark-read changes nothing and
 * says so; a refused row click leaves that row unread.
 */

// Stable session: a fresh object per render would re-run the unread-count
// effect on every render and mask a local badge change.
const sessionData = vi.hoisted(() => ({ user: { name: "Ada Admin", email: "ada@example.com", role: "TENANT_ADMIN" } }));
vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: sessionData }),
    signOut: vi.fn(),
}));
vi.mock("next/navigation", () => ({
    useSearchParams: () => new URLSearchParams(),
    usePathname: () => "/ar/dashboard",
    useRouter: () => ({ push: vi.fn(), refresh: vi.fn() }),
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

let putOk = false;
beforeEach(() => {
    putOk = false;
    global.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
        if (init?.method === "PUT") return { ok: putOk, status: putOk ? 200 : 500, json: async () => ({}) } as unknown as Response;
        const u = String(url);
        const body = u.includes("unread-count") ? 1 : [{
            id: "n1", type: "GENERAL", title: "Hello", message: "World",
            referenceType: null, referenceId: null, isRead: false, createdAt: new Date().toISOString(),
        }];
        return { ok: true, json: async () => body } as unknown as Response;
    }) as never;
});

afterEach(cleanup);

describe("TopHeader bell — a refused mark-read", () => {
    it("keeps the badge and says so, in Arabic", async () => {
        render(<NextIntlClientProvider locale="ar" messages={ar}><TopHeader /></NextIntlClientProvider>);
        fireEvent.click(await screen.findByText("1"));
        fireEvent.click(await screen.findByText(ar.Notifications.markAllRead));
        expect((await screen.findByRole("alert")).textContent).toBe(ar.Notifications.markReadFailed);
        expect(screen.getByTestId("header-notifications")).toHaveTextContent("1");
    });

    it("zeroes the badge once it succeeds", async () => {
        putOk = true;
        render(<NextIntlClientProvider locale="ar" messages={ar}><TopHeader /></NextIntlClientProvider>);
        fireEvent.click(await screen.findByText("1"));
        fireEvent.click(await screen.findByText(ar.Notifications.markAllRead));
        await waitFor(() => expect(screen.getByTestId("header-notifications")).not.toHaveTextContent("1"));
        expect(screen.queryByRole("alert")).toBeNull();
    });

    it("leaves a row unread when its mark-read is refused", async () => {
        render(<NextIntlClientProvider locale="ar" messages={ar}><TopHeader /></NextIntlClientProvider>);
        fireEvent.click(await screen.findByText("1"));
        fireEvent.click(await screen.findByText("Hello"));
        await waitFor(() => expect(vi.mocked(global.fetch)).toHaveBeenCalledWith("/api/proxy/v1/notifications/n1/read", { method: "PUT" }));
        await new Promise((r) => setTimeout(r, 0));
        expect(screen.getByTestId("header-notifications")).toHaveTextContent("1");
    });
});
