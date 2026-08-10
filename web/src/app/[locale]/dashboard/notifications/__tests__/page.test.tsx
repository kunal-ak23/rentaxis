import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("next-intl", () => ({
    useLocale: () => "en",
}));

// Stable object: the page's fetch effect depends on `session?.user`, so a
// fresh object per render would refire it forever.
const sessionData = vi.hoisted(() => ({ user: { role: "TENANT_ADMIN" } }));

vi.mock("next-auth/react", () => ({
    useSession: () => ({
        data: sessionData,
        status: "authenticated",
    }),
}));

vi.mock("next/navigation", () => ({
    useRouter: () => ({ push: vi.fn() }),
}));

import NotificationsPage from "../page";

/** Shaped like NotificationDTO — the plain-list body GET /v1/notifications returns. */
function makeNotifications(count: number, offset = 0) {
    return Array.from({ length: count }, (_, i) => ({
        id: `n-${offset + i}`,
        title: `Notification ${offset + i}`,
        message: `Message ${offset + i}`,
        type: "TICKET_REPLY",
        referenceType: null,
        referenceId: null,
        isRead: false,
        createdAt: "2026-08-01T10:00:00Z",
    }));
}

let fetchedUrls: string[] = [];

/** Minimal slice of Response the page actually reads. */
type StubResponse = { ok: boolean; json?: () => Promise<unknown> };

function stubFetch(handler: (url: string) => StubResponse) {
    global.fetch = vi.fn(
        async (input: RequestInfo | URL) => {
            const url = String(input);
            fetchedUrls.push(url);
            return handler(url) as unknown as Response;
        },
    ) as unknown as typeof fetch;
}

/** Reads the 0-indexed `page` param off a captured notifications URL. */
function pageParam(url: string): string | null {
    return new URL(url, "http://localhost").searchParams.get("page");
}

beforeEach(() => {
    fetchedUrls = [];
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("NotificationsPage pagination", () => {
    // The endpoint returns a bare page-sized list with no total count, so the
    // page must infer "there is more" from a full page — previously it set
    // totalItems to the returned length, making page 2 unreachable forever.
    it("offers a next page when the first page comes back full", async () => {
        stubFetch((url) => {
            if (url.includes("/v1/notifications")) {
                const items = pageParam(url) === "0"
                    ? makeNotifications(25)
                    : makeNotifications(5, 25);
                return { ok: true, json: async () => items };
            }
            return { ok: true, json: async () => null };
        });

        render(<NotificationsPage />);
        await waitFor(() => expect(screen.getByText("Notification 0")).toBeInTheDocument());

        // 25 items with 25 per page: a "2" button must exist.
        const pageTwo = screen.getByRole("button", { name: "2" });

        fireEvent.click(pageTwo);
        await waitFor(() => expect(screen.getByText("Notification 25")).toBeInTheDocument());

        // Second request is 0-indexed page=1, and the short page fixes the total.
        const notifUrls = fetchedUrls.filter((u) => u.includes("/v1/notifications?"));
        expect(notifUrls.map(pageParam)).toEqual(["0", "1"]);
        expect(screen.getByText("Showing 26-30 of 30")).toBeInTheDocument();
    });

    it("shows a single page when the first page is short", async () => {
        stubFetch((url) => {
            if (url.includes("/v1/notifications")) {
                return { ok: true, json: async () => makeNotifications(3) };
            }
            return { ok: true, json: async () => null };
        });

        render(<NotificationsPage />);
        await waitFor(() => expect(screen.getByText("Notification 0")).toBeInTheDocument());

        expect(screen.getByText("Showing 1-3 of 3")).toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "2" })).toBeNull();
    });

    it("steps back when the inferred next page turns out to be empty", async () => {
        // A last page that is exactly full makes the page assume one more item;
        // following that assumption must not strand the user on an empty page.
        stubFetch((url) => {
            if (url.includes("/v1/notifications")) {
                const items = pageParam(url) === "0" ? makeNotifications(25) : [];
                return { ok: true, json: async () => items };
            }
            return { ok: true, json: async () => null };
        });

        render(<NotificationsPage />);
        await waitFor(() => expect(screen.getByText("Notification 0")).toBeInTheDocument());

        fireEvent.click(screen.getByRole("button", { name: "2" }));

        // Page 1 (0-indexed) came back empty -> walk back to page 0 and refetch.
        await waitFor(() => {
            const notifUrls = fetchedUrls.filter((u) => u.includes("/v1/notifications?"));
            expect(notifUrls.map(pageParam)).toEqual(["0", "1", "0"]);
        });
        expect(screen.getByText("Notification 0")).toBeInTheDocument();
    });

    it("requests unreadOnly=true when the Unread tab is active", async () => {
        stubFetch((url) => {
            if (url.includes("/v1/notifications")) {
                return { ok: true, json: async () => makeNotifications(2) };
            }
            return { ok: true, json: async () => null };
        });

        render(<NotificationsPage />);
        await waitFor(() => expect(screen.getByText("Notification 0")).toBeInTheDocument());

        fireEvent.click(screen.getByRole("button", { name: "Unread" }));

        await waitFor(() => {
            const unreadUrl = fetchedUrls.find((u) => u.includes("unreadOnly=true"));
            expect(unreadUrl).toBeTruthy();
            expect(pageParam(unreadUrl!)).toBe("0");
        });
    });
});
