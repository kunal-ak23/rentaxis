import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { id: "ta", role: "TENANT_ADMIN", tenantId: "t1" } } }) }));

import UsersManager from "../UsersManager";

/**
 * Break-it round 3 (ops3) F4/F5.
 *  - F4: the edit panel sends only what the admin changed; property assignments
 *    only when the list was touched, with the set the panel loaded. A 409
 *    user.changed reloads the assignments and says so.
 *  - F5: the delete dialog says how many open tickets go back to the queue, and
 *    refuses (disabled) when the user has meetings on record.
 */
const USERS = [
    { id: "uA", name: "Alice PM", email: "a@x.test", role: "PROPERTY_MANAGER", tenantId: "t1", phoneNumber: "" },
    { id: "uB", name: "Bob PM", email: "b@x.test", role: "PROPERTY_MANAGER", tenantId: "t1", phoneNumber: "" },
];
const PROPS = [{ property: { id: "id-PropA", nameEn: "PropA" } }, { property: { id: "id-PropB", nameEn: "PropB" } }];

let sent: { url: string; method: string; body: Record<string, unknown> }[] = [];
let assignments: string[] = ["id-PropA", "id-PropB"];
let putResponse: () => Response = () => new Response("{}");
let preview = { openTickets: 0, meetings: 0 };
/** uB's assignments read: "fail" answers 500, "hold" never answers. */
let bobAssignments: "ok" | "fail" | "hold" = "ok";

beforeEach(() => {
    sent = [];
    assignments = ["id-PropA", "id-PropB"];
    putResponse = () => new Response("{}");
    preview = { openTickets: 0, meetings: 0 };
    bobAssignments = "ok";
    global.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        const method = init?.method ?? "GET";
        if (method === "PUT") {
            sent.push({ url, method, body: JSON.parse(String(init?.body ?? "{}")) });
            return putResponse();
        }
        if (method === "DELETE") {
            sent.push({ url, method, body: {} });
            return new Response("{}");
        }
        if (url.endsWith("/admin/users")) return new Response(JSON.stringify(USERS));
        if (url.endsWith("/admin/users/uA/properties")) return new Response(JSON.stringify(assignments));
        if (url.endsWith("/admin/users/uB/properties")) {
            if (bobAssignments === "fail") return new Response("{}", { status: 500 });
            if (bobAssignments === "hold") return new Promise<Response>(() => {});
            return new Response(JSON.stringify(["id-PropB"]));
        }
        if (url.endsWith("/admin/users/uA/delete-preview")) return new Response(JSON.stringify(preview));
        if (url.endsWith("/v1/properties")) return new Response(JSON.stringify(PROPS));
        return new Response("[]");
    }) as unknown as typeof fetch;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

const chips = (name: string) => screen.queryAllByText(name).filter(el => el.tagName === "DIV");
const row = (name = "Alice PM") => screen.getByText(name).closest("tr")!;

async function openEdit() {
    render(<UsersManager />);
    await screen.findByText("Alice PM");
    fireEvent.click(within(row()).getByRole("button", { name: /^edit$/i }));
    await waitFor(() => expect(chips("PropB")).toHaveLength(1));
}

describe("UsersManager edit sends only what changed (F4)", () => {
    it("a name change sends the name alone — no stale property list", async () => {
        await openEdit();
        fireEvent.change(screen.getByDisplayValue("Alice PM"), { target: { value: "Alice Renamed" } });
        fireEvent.click(screen.getByRole("button", { name: /update user/i }));
        await waitFor(() => expect(sent).toHaveLength(1));
        expect(sent[0].body).toEqual({ name: "Alice Renamed" });
    });

    it("removing a property sends the new list with the set the panel loaded", async () => {
        await openEdit();
        fireEvent.click(within(chips("PropB")[0]).getByRole("button"));
        fireEvent.click(screen.getByRole("button", { name: /update user/i }));
        await waitFor(() => expect(sent).toHaveLength(1));
        expect(sent[0].body).toEqual({ propertyIds: ["id-PropA"], expectedPropertyIds: ["id-PropA", "id-PropB"] });
    });

    it("nothing changed: nothing is sent", async () => {
        await openEdit();
        fireEvent.click(screen.getByRole("button", { name: /update user/i }));
        await waitFor(() => expect(screen.queryByRole("button", { name: /update user/i })).toBeNull());
        expect(sent).toHaveLength(0);
    });

    it("409 user.changed reloads the assignments and says so", async () => {
        await openEdit();
        putResponse = () => new Response(JSON.stringify({ error: true, status: 409, code: "user.changed",
            message: "This user's property assignments were changed" }), { status: 409 });
        fireEvent.click(within(chips("PropA")[0]).getByRole("button"));
        assignments = ["id-PropA"]; // what a colleague saved meanwhile
        fireEvent.click(screen.getByRole("button", { name: /update user/i }));
        await screen.findByText(/someone else changed this user's properties/i);
        await waitFor(() => expect(chips("PropB")).toHaveLength(0));
        expect(chips("PropA")).toHaveLength(1);
    });
});

describe("UsersManager delete dialog states the user's open work (F5)", () => {
    it("says how many open tickets return to the queue", async () => {
        preview = { openTickets: 3, meetings: 0 };
        render(<UsersManager />);
        await screen.findByText("Alice PM");
        fireEvent.click(within(row()).getByRole("button", { name: /delete/i }));
        expect(await screen.findByTestId("delete-open-tickets")).toHaveTextContent(/3 open tickets/);
    });

    it("a user with meetings on record cannot be deleted from the dialog", async () => {
        preview = { openTickets: 0, meetings: 2 };
        render(<UsersManager />);
        await screen.findByText("Alice PM");
        fireEvent.click(within(row()).getByRole("button", { name: /delete/i }));
        expect(await screen.findByTestId("delete-has-meetings")).toHaveTextContent(/2 meetings/);
        const dialog = screen.getByRole("dialog");
        const confirm = within(dialog).getAllByRole("button").find(b => /delete/i.test(b.textContent ?? ""))!;
        expect(confirm).toBeDisabled();
    });
});

describe("UsersManager never sends another user's property list (review r3B I2)", () => {
    async function editAliceThenClose(close: "x" | "backdrop") {
        render(<UsersManager />);
        await screen.findByText("Alice PM");
        fireEvent.click(within(row()).getByRole("button", { name: /^edit$/i }));
        await waitFor(() => expect(chips("PropA")).toHaveLength(1));
        if (close === "x") fireEvent.click(screen.getByRole("button", { name: /^close$/i }));
        else fireEvent.click(document.querySelector(".backdrop-blur-sm")!);
        await waitFor(() => expect(screen.queryByRole("button", { name: /update user/i })).toBeNull());
    }

    for (const close of ["x", "backdrop"] as const) {
        it(`closed with ${close}, then Bob's assignments fail to load: a name change leaves them alone`, async () => {
            await editAliceThenClose(close);
            bobAssignments = "fail";
            fireEvent.click(within(row("Bob PM")).getByRole("button", { name: /^edit$/i }));
            expect(await screen.findByTestId("assignments-failed")).toBeInTheDocument();
            expect(chips("PropA")).toHaveLength(0);
            fireEvent.change(screen.getByDisplayValue("Bob PM"), { target: { value: "Bob Renamed" } });
            fireEvent.click(screen.getByRole("button", { name: /update user/i }));
            await waitFor(() => expect(sent).toHaveLength(1));
            expect(sent[0].url).toContain("/admin/users/uB");
            expect(sent[0].body).toEqual({ name: "Bob Renamed" });
        });
    }

    it("Save waits while Bob's assignments are loading", async () => {
        await editAliceThenClose("x");
        bobAssignments = "hold";
        fireEvent.click(within(row("Bob PM")).getByRole("button", { name: /^edit$/i }));
        const save = await screen.findByRole("button", { name: /update user/i });
        expect(save).toBeDisabled();
        expect(chips("PropA")).toHaveLength(0);
        fireEvent.click(save);
        expect(sent).toHaveLength(0);
    });

    it("a property change always goes with the set this panel loaded", async () => {
        render(<UsersManager />);
        await screen.findByText("Bob PM");
        fireEvent.click(within(row("Bob PM")).getByRole("button", { name: /^edit$/i }));
        await waitFor(() => expect(chips("PropB")).toHaveLength(1));
        fireEvent.click(within(chips("PropB")[0]).getByRole("button"));
        fireEvent.click(screen.getByRole("button", { name: /update user/i }));
        await waitFor(() => expect(sent).toHaveLength(1));
        expect(sent[0].body).toEqual({ propertyIds: [], expectedPropertyIds: ["id-PropB"] });
    });
});
