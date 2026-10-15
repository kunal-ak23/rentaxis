import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { id: "me", role: "TENANT_ADMIN", tenantId: "t1" } } }) }));

import UsersManager from "../UsersManager";

/**
 * S17-01: an organisation admin deactivates and reactivates one login from the
 * Users screen, without deleting it. Not offered on your own row or a platform
 * admin's; an inactive row offers no reset/invite (the server refuses them).
 */
const USERS = [
    { id: "me", name: "Me Admin", email: "me@x.test", role: "TENANT_ADMIN", tenantId: "t1", status: "ACTIVE" },
    { id: "uA", name: "Alice Renter", email: "a@x.test", role: "RENTER", tenantId: "t1", status: "ACTIVE" },
    { id: "uB", name: "Bob Gone", email: "b@x.test", role: "PROPERTY_MANAGER", tenantId: "t1", status: "INACTIVE" },
    { id: "sa", name: "Platform", email: "p@x.test", role: "SUPER_ADMIN", tenantId: null, status: "ACTIVE" },
];

let posted: string[] = [];
let answer: (url: string) => Response = () => new Response("{}");

beforeEach(() => {
    posted = [];
    answer = () => new Response("{}");
    global.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if ((init?.method ?? "GET") === "POST") {
            posted.push(url);
            return answer(url);
        }
        if (url.endsWith("/admin/users")) return new Response(JSON.stringify(USERS));
        return new Response("[]");
    }) as unknown as typeof fetch;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

const row = (name: string) => screen.getByText(name).closest("tr")!;

describe("UsersManager deactivate / reactivate (S17-01)", () => {
    it("shows each login's status and offers the right action", async () => {
        render(<UsersManager />);
        await screen.findByText("Alice Renter");
        expect(within(row("Alice Renter")).getByTestId("user-status")).toHaveTextContent("Active");
        expect(within(row("Bob Gone")).getByTestId("user-status")).toHaveTextContent("Deactivated");
        expect(within(row("Alice Renter")).getByTestId("user-deactivate")).toBeInTheDocument();
        expect(within(row("Bob Gone")).getByTestId("user-reactivate")).toBeInTheDocument();
        // Never your own login, never a platform admin's.
        expect(within(row("Me Admin")).queryByTestId("user-deactivate")).toBeNull();
        expect(within(row("Platform")).queryByTestId("user-deactivate")).toBeNull();
        // An inactive login gets no reset link.
        expect(within(row("Bob Gone")).queryByTestId("send-password-reset")).toBeNull();
    });

    it("deactivates after confirming and says so", async () => {
        render(<UsersManager />);
        await screen.findByText("Alice Renter");
        fireEvent.click(within(row("Alice Renter")).getByTestId("user-deactivate"));
        expect(await screen.findByText(/Alice Renter will no longer be able to sign in/)).toBeInTheDocument();
        expect(posted).toHaveLength(0);
        fireEvent.click(screen.getByTestId("user-status-confirm"));
        await waitFor(() => expect(posted).toEqual(["/api/proxy/admin/users/uA/deactivate"]));
        expect(await screen.findByTestId("user-status-notice"))
            .toHaveTextContent("Alice Renter can no longer sign in to this organisation.");
    });

    it("reactivates and tells the admin how the person signs in again", async () => {
        answer = () => new Response(JSON.stringify({ id: "uB", status: "ACTIVE", reactivationLogin: "INVITED" }));
        render(<UsersManager />);
        await screen.findByText("Bob Gone");
        fireEvent.click(within(row("Bob Gone")).getByTestId("user-reactivate"));
        fireEvent.click(await screen.findByTestId("user-status-confirm"));
        await waitFor(() => expect(posted).toEqual(["/api/proxy/admin/users/uB/reactivate"]));
        expect(await screen.findByTestId("user-status-notice")).toHaveTextContent(/emailed them a link/);
    });

    it("words the last-active-admin refusal", async () => {
        answer = () => new Response(JSON.stringify({ error: true, status: 400, code: "user.lastActiveAdmin",
            message: "raw server sentence" }), { status: 400 });
        render(<UsersManager />);
        await screen.findByText("Alice Renter");
        fireEvent.click(within(row("Alice Renter")).getByTestId("user-deactivate"));
        fireEvent.click(await screen.findByTestId("user-status-confirm"));
        expect(await screen.findByRole("alert")).toHaveTextContent(/last active administrator/);
    });
});
