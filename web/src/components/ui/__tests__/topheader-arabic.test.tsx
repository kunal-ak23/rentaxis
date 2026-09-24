import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";

import ar from "../../../../messages/ar.json";
import { leftoverLatinWords, visibleText } from "@/test/latinText";

/**
 * #81: the header on every staff page kept its profile menu (Update Profile,
 * Logout) and the notifications dropdown in English under /ar.
 */

vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { name: "Ada Admin", email: "ada@example.com", role: "TENANT_ADMIN" } } }),
    signOut: vi.fn(),
}));
vi.mock("next/navigation", () => ({
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

import { TopHeader } from "../TopHeader";

beforeEach(() => {
    global.fetch = vi.fn(async (url: unknown) => {
        const u = String(url);
        const body = u.includes("unread-count") ? 1 : [{
            id: "n1", type: "MEETING_REQUESTED", title: "New Meeting Requested", message: "A meeting has been requested for Thu",
            messageKey: "MEETING_REQUESTED_HOST", params: { slot: "2026-09-10T06:00:00Z" },
            referenceType: "MEETING", referenceId: "m1", isRead: false, createdAt: new Date().toISOString(),
        }];
        return { ok: true, json: async () => body } as unknown as Response;
    }) as never;
});

afterEach(cleanup);

describe("TopHeader in Arabic", () => {
    it("the profile menu and the notifications dropdown carry no English", async () => {
        const { container } = render(
            <NextIntlClientProvider locale="ar" messages={ar}><TopHeader /></NextIntlClientProvider>,
        );

        fireEvent.click(screen.getByTestId("profile-menu"));
        expect(screen.getByTestId("logout").textContent).toContain(ar.Navigation.logout);
        expect(screen.getByText(ar.Navigation.updateProfile)).toBeInTheDocument();

        fireEvent.click(await screen.findByText("1"));
        expect(await screen.findByText(ar.Notifications.messages.MEETING_REQUESTED_HOST.title)).toBeInTheDocument();
        expect(screen.getByText(ar.Notifications.viewAll)).toBeInTheDocument();

        // The user's own name and email, and the EN/AR language switch, are not chrome.
        expect(leftoverLatinWords(visibleText(container), ["Ada Admin", "ada@example.com", "EN", "AR"])).toEqual([]);
    });
});
