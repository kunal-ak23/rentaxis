import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import en from "../../../../../../messages/en.json";

/**
 * Break-it R3 portal3 F1/F5: the renter's Create Ticket form.
 * - F5: a failed "my leases" read says so, with Retry — it used to say "you
 *   have no active contract".
 * - F1: only a current contract (live, today inside the term) is offered; the
 *   ticket names its lease; the server's 403 for "no current contract" is shown
 *   in the user's language.
 */

vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { role: "RENTER", id: "me-1" } }, status: "authenticated" }),
}));
vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams() }));
vi.mock("next-intl", async () => {
    const { createTranslator } = await vi.importActual<typeof import("next-intl")>("next-intl");
    const messages = (await import("../../../../../../messages/en.json")).default;
    const cache = new Map<string, ReturnType<typeof createTranslator>>();
    return {
        useTranslations: (namespace: string) => {
            if (!cache.has(namespace)) cache.set(namespace, createTranslator({ locale: "en", messages, namespace: namespace as never }));
            return cache.get(namespace)!;
        },
        useLocale: () => "en",
    };
});
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));

import TicketsPage from "../page";

const current = { id: "l1", status: "ACTIVE", startDate: "2020-01-01", endDate: "2099-12-31", propertyId: "p1", propertyName: "Tower", unitId: "u1", unitIdentifier: "101" };
const ended = { ...current, id: "l0", unitId: "u0", unitIdentifier: "100", endDate: "2020-12-31" };

let leasesReplies: (() => Response)[] = [];
let createReply: () => Response;
let posts: unknown[] = [];

const res = (status: number, body: unknown) =>
    ({ ok: status < 400, status, json: async () => body }) as unknown as Response;

beforeEach(() => {
    posts = [];
    createReply = () => res(200, { id: "t-1", title: "Leak" });
    global.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
        const u = String(url);
        if (u.includes("/v1/leases/my-leases")) return (leasesReplies.shift() ?? (() => res(200, [current])))();
        if (u.endsWith("/v1/tickets") && init?.method === "POST") {
            posts.push(JSON.parse(String(init.body)));
            return createReply();
        }
        return res(200, []);
    }) as unknown as typeof fetch;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); leasesReplies = []; });

async function openForm() {
    render(<TicketsPage />);
    fireEvent.click(await screen.findByRole("button", { name: /create ticket/i }));
}

describe("Create Ticket — the renter's contracts", () => {
    it("F5: a failed read shows an error with Retry, not 'no active contract', and Retry recovers", async () => {
        leasesReplies = [() => res(500, { message: "boom" }), () => res(200, [current])];
        await openForm();
        expect(await screen.findByText(en.Tickets.renterLeasesFailed)).toBeTruthy();
        expect(screen.queryByText(en.Tickets.noActiveLease)).toBeNull();

        fireEvent.click(screen.getByRole("button", { name: en.Tickets.retry }));
        await waitFor(() => expect(screen.queryByText(en.Tickets.renterLeasesFailed)).toBeNull());
        expect(screen.queryByText(en.Tickets.noActiveLease)).toBeNull();
        expect(await screen.findByText(/Tower/)).toBeTruthy();
    });

    it("F1: an ended contract (still ACTIVE) is not offered", async () => {
        leasesReplies = [() => res(200, [ended])];
        await openForm();
        expect(await screen.findByText(en.Tickets.noActiveLease)).toBeTruthy();
    });

    it("F1: the ticket names the renter's lease, and the server's 403 is said in the user's language", async () => {
        createReply = () => res(403, { error: true, message: "You have no current contract, so a ticket can't be raised.", status: 403 });
        await openForm();
        fireEvent.change(screen.getByPlaceholderText(/brief summary/i), { target: { value: "Leak" } });
        const buttons = () => screen.getAllByRole("button", { name: /create ticket/i }) as HTMLButtonElement[];
        await waitFor(() => expect(buttons()[buttons().length - 1].disabled).toBe(false));
        fireEvent.click(buttons()[buttons().length - 1]);

        expect(await screen.findByText(en.Tickets.noActiveLease)).toBeTruthy();
        expect(posts).toHaveLength(1);
        expect(posts[0]).toMatchObject({ propertyId: "p1", unitId: "u1", leaseId: "l1" });
    });
});
