import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";
import ar from "../../../../../../messages/ar.json";

/**
 * Break-it round 2 follow-up (silent mutation sweep): "Mark all read" and a
 * row click never looked at the response — a refused PUT still showed every
 * notification as read, until the next reload brought them back. A refused
 * mark-read keeps the rows unread and says so.
 */

const sessionData = vi.hoisted(() => ({ user: { role: "RENTER" } }));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: sessionData, status: "authenticated" }) }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));

import NotificationsPage from "../page";

const rows = [0, 1].map((i) => ({
    id: `n-${i}`, title: `Notification ${i}`, message: `Message ${i}`, type: "GENERAL",
    referenceType: null, referenceId: null, isRead: false, createdAt: "2026-08-01T10:00:00Z",
}));
let putOk: boolean;

beforeEach(() => {
    putOk = false;
    global.fetch = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
        if (init?.method === "PUT") return { ok: putOk, status: putOk ? 200 : 500, json: async () => ({}) } as Response;
        return { ok: true, status: 200, json: async () => rows } as Response;
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

function renderPage(locale: "en" | "ar" = "en") {
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}>
            <NotificationsPage />
        </NextIntlClientProvider>,
    );
}

describe("NotificationsPage — a refused mark-read is shown", () => {
    it("keeps everything unread and says so when Mark all read is refused", async () => {
        renderPage();
        fireEvent.click(await screen.findByText(en.Notifications.markAllRead));
        expect((await screen.findByRole("alert")).textContent).toBe(en.Notifications.markReadFailed);
        // Still offered: nothing was marked read locally.
        expect(screen.getByText(en.Notifications.markAllRead)).toBeTruthy();
    });

    it("says it in Arabic when a single row's mark-read is refused", async () => {
        renderPage("ar");
        fireEvent.click(await screen.findByText("Notification 0"));
        expect((await screen.findByRole("alert")).textContent).toBe(ar.Notifications.markReadFailed);
    });

    it("clears the notice once a later mark-read succeeds", async () => {
        renderPage();
        fireEvent.click(await screen.findByText(en.Notifications.markAllRead));
        await screen.findByRole("alert");
        putOk = true;
        fireEvent.click(screen.getByText(en.Notifications.markAllRead));
        await waitFor(() => expect(screen.queryByRole("alert")).toBeNull());
        expect(screen.queryByText(en.Notifications.markAllRead)).toBeNull();
    });
});
