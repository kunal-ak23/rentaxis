import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
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

/**
 * Stubs a server that actually holds state: a mutable unread set, paged the way
 * GET /v1/notifications pages (bare list, no total), with PUT .../read removing
 * an id from the unread set. A fixed-list stub cannot tell a correct refetch
 * from a stale one — both render the same rows — so pagination regressions on
 * the Unread tab are invisible to it.
 */
function stubNotificationServer(unreadCount: number, pageSize = 25) {
    const unread = makeNotifications(unreadCount);
    stubFetch((url) => {
        const readMatch = url.match(/\/v1\/notifications\/([^/]+)\/read$/);
        if (readMatch) {
            const idx = unread.findIndex((n) => n.id === readMatch[1]);
            if (idx >= 0) unread.splice(idx, 1);
            return { ok: true, json: async () => null };
        }
        if (url.includes("/v1/notifications/read-all")) {
            unread.length = 0;
            return { ok: true, json: async () => null };
        }
        if (url.includes("/v1/notifications?")) {
            const page = Number(pageParam(url) ?? 0);
            const slice = unread.slice(page * pageSize, page * pageSize + pageSize);
            return { ok: true, json: async () => slice };
        }
        return { ok: true, json: async () => null };
    });
    return unread;
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

    it("removes a notification immediately after it is read from the Unread tab", async () => {
        stubNotificationServer(2);

        render(<NotificationsPage />);
        await waitFor(() => expect(screen.getByText("Notification 0")).toBeInTheDocument());
        fireEvent.click(screen.getByRole("button", { name: "Unread" }));
        await waitFor(() => expect(fetchedUrls.some((u) => u.includes("unreadOnly=true"))).toBe(true));

        fireEvent.click(screen.getByText("Message 0"));

        await waitFor(() => expect(screen.queryByText("Notification 0")).toBeNull());
        expect(screen.getByText("Notification 1")).toBeInTheDocument();
    });

    // Regression: reading one item used to decrement the *inferred* total, which
    // cancelled the synthetic "+1" a full page adds, dropped totalPages 2 -> 1,
    // and made every remaining unread page unreachable without a reload.
    it("keeps later unread pages reachable after reading one item", async () => {
        stubNotificationServer(50);

        render(<NotificationsPage />);
        await waitFor(() => expect(screen.getByText("Notification 0")).toBeInTheDocument());
        fireEvent.click(screen.getByRole("button", { name: "Unread" }));
        await waitFor(() => expect(fetchedUrls.some((u) => u.includes("unreadOnly=true"))).toBe(true));
        expect(screen.getByRole("button", { name: "2" })).toBeInTheDocument();

        fireEvent.click(screen.getByText("Message 0"));

        // The read row goes away, the page backfills from page 2, and the
        // next-page control survives.
        await waitFor(() => expect(screen.queryByText("Notification 0")).toBeNull());
        expect(screen.getByRole("button", { name: "2" })).toBeInTheDocument();
        expect(screen.getByText("Notification 25")).toBeInTheDocument();

        // Page 2 is still navigable and shows the remaining unread items.
        fireEvent.click(screen.getByRole("button", { name: "2" }));
        await waitFor(() => expect(screen.getByText("Notification 26")).toBeInTheDocument());
    });

    // Regression: emptying a later page left the list at [] while currentPage
    // stayed on 2, so the page rendered "You're all caught up!" *and* unmounted
    // every pagination control, stranding 25 unread items on page 1.
    it("steps back to the previous page when the last item on a later page is read", async () => {
        stubNotificationServer(26);

        render(<NotificationsPage />);
        await waitFor(() => expect(screen.getByText("Notification 0")).toBeInTheDocument());
        fireEvent.click(screen.getByRole("button", { name: "Unread" }));
        await waitFor(() => expect(fetchedUrls.some((u) => u.includes("unreadOnly=true"))).toBe(true));

        fireEvent.click(screen.getByRole("button", { name: "2" }));
        await waitFor(() => expect(screen.getByText("Notification 25")).toBeInTheDocument());

        fireEvent.click(screen.getByText("Message 25"));

        // Back on page 1 with the remaining 25 unread — not an empty
        // "all caught up" screen with no way back.
        await waitFor(() => expect(screen.getByText("Notification 0")).toBeInTheDocument());
        expect(screen.queryByText("You're all caught up!")).toBeNull();
    });

    it("clears the Unread tab after marking all notifications read", async () => {
        stubNotificationServer(2);

        render(<NotificationsPage />);
        await waitFor(() => expect(screen.getByText("Notification 0")).toBeInTheDocument());
        fireEvent.click(screen.getByRole("button", { name: "Unread" }));
        await waitFor(() => expect(fetchedUrls.some((u) => u.includes("unreadOnly=true"))).toBe(true));

        fireEvent.click(screen.getByRole("button", { name: "Mark All as Read" }));

        await waitFor(() => expect(screen.getByText("You're all caught up!")).toBeInTheDocument());
        expect(screen.queryByText("Notification 1")).toBeNull();
    });
    // Regression: reads and tab switches both fire GETs, and responses can land
    // out of order. An older unreadOnly payload arriving last used to overwrite
    // the newer list, putting an already-read row back on the Unread tab.
    it("does not resurrect a read notification when refetches land out of order", async () => {
        const unread = makeNotifications(3);
        const gates: Array<() => void> = [];
        global.fetch = vi.fn(async (input: RequestInfo | URL) => {
            const url = String(input);
            fetchedUrls.push(url);
            const readMatch = url.match(/\/v1\/notifications\/([^/]+)\/read$/);
            if (readMatch) {
                const idx = unread.findIndex((n) => n.id === readMatch[1]);
                if (idx >= 0) unread.splice(idx, 1);
                return { ok: true, json: async () => null } as unknown as Response;
            }
            if (url.includes("/v1/notifications?")) {
                // Snapshot server truth at REQUEST time, the way a real handler
                // would, then hold the response open so the test controls order.
                const snapshot = unread.slice();
                await new Promise<void>((resolve) => gates.push(resolve));
                return { ok: true, json: async () => snapshot } as unknown as Response;
            }
            return { ok: true, json: async () => null } as unknown as Response;
        }) as unknown as typeof fetch;

        render(<NotificationsPage />);
        await waitFor(() => expect(gates.length).toBe(1));
        gates.shift()!();
        await waitFor(() => expect(screen.getByText("Notification 0")).toBeInTheDocument());

        fireEvent.click(screen.getByRole("button", { name: "Unread" }));
        await waitFor(() => expect(gates.length).toBe(1));
        gates.shift()!();
        await waitFor(() => expect(fetchedUrls.some((u) => u.includes("unreadOnly=true"))).toBe(true));

        // Read n-0 (its GET_A snapshot still contains n-1), then read n-1 before
        // GET_A has returned (GET_B snapshot contains only n-2).
        fireEvent.click(screen.getByText("Message 0"));
        await waitFor(() => expect(gates.length).toBe(1));
        fireEvent.click(screen.getByText("Message 1"));
        await waitFor(() => expect(gates.length).toBe(2));

        // Newer response first, then the stale one.
        gates.pop()!();
        await waitFor(() => expect(screen.queryByText("Notification 1")).toBeNull());
        gates.pop()!();

        await waitFor(() => expect(screen.getByText("Notification 2")).toBeInTheDocument());
        // The stale GET_A payload must not put n-1 back on screen.
        expect(screen.queryByText("Notification 1")).toBeNull();
        expect(screen.queryByText("Notification 0")).toBeNull();
    });

    // Regression: a refetch started on the Unread tab used to resolve against
    // whatever tab was on screen by then, wiping every read notification off All.
    it("does not let an in-flight unread refetch clobber the All tab", async () => {
        // The unread payload carries a marker id that the All payload never
        // contains, so a clobber is directly observable rather than inferred.
        const unread = makeNotifications(2, 100);
        const all = makeNotifications(3);
        const gates: Array<() => void> = [];
        global.fetch = vi.fn(async (input: RequestInfo | URL) => {
            const url = String(input);
            fetchedUrls.push(url);
            if (url.match(/\/v1\/notifications\/[^/]+\/read$/)) {
                return { ok: true, json: async () => null } as unknown as Response;
            }
            if (url.includes("/v1/notifications?")) {
                const snapshot = url.includes("unreadOnly=true") ? unread.slice() : all.slice();
                await new Promise<void>((resolve) => gates.push(resolve));
                return { ok: true, json: async () => snapshot } as unknown as Response;
            }
            return { ok: true, json: async () => null } as unknown as Response;
        }) as unknown as typeof fetch;

        render(<NotificationsPage />);
        await waitFor(() => expect(gates.length).toBe(1));
        gates.shift()!();
        await waitFor(() => expect(screen.getByText("Notification 0")).toBeInTheDocument());

        fireEvent.click(screen.getByRole("button", { name: "Unread" }));
        await waitFor(() => expect(gates.length).toBe(1));
        gates.shift()!();
        await waitFor(() => expect(screen.getByText("Notification 100")).toBeInTheDocument());

        // Read a row — the unread refetch is now in flight — then switch to All.
        fireEvent.click(screen.getByText("Message 100"));
        await waitFor(() => expect(gates.length).toBe(1));
        fireEvent.click(screen.getByRole("button", { name: "All" }));
        await waitFor(() => expect(gates.length).toBe(2));

        // All resolves first and renders its three rows...
        gates.pop()!();
        await waitFor(() => expect(screen.getByText("Notification 2")).toBeInTheDocument());

        // ...then the stale unread response lands. Flush it to completion before
        // asserting, otherwise "still correct" just means "not applied yet".
        gates.pop()!();
        await act(async () => { await new Promise((r) => setTimeout(r, 0)); });

        expect(screen.queryByText("Notification 101")).toBeNull();
        expect(screen.getByText("Notification 0")).toBeInTheDocument();
        expect(screen.getByText("Notification 1")).toBeInTheDocument();
        expect(screen.getByText("Notification 2")).toBeInTheDocument();
    });
});