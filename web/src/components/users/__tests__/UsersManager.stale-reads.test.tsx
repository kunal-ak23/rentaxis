import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ComponentType } from "react";

vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { id: "sa", role: "SUPER_ADMIN", tenantId: "" } } }) }));

/**
 * Break round 3, F1 (review I1): UsersManager's selection-driven reads.
 *  - Edit on PM user A (its assignment read slow), then Edit on PM user B: A's
 *    late answer used to become B's selected properties, and Update then sent
 *    A's assignments for B.
 *  - The organisation picker: t1 then t2 quickly, t1's property list landing
 *    last used to be offered for t2.
 * Reads are held per URL and released out of order; the mock ignores the
 * abort signal (worst case), so the isCurrent guards are what is tested.
 *
 * UM_UNDER_TEST (absolute path) swaps in a mutated copy for mutation checks
 * without touching the real component.
 */
const held = new Map<string, (body: unknown) => void>();
const sent: { url: string; method: string; body: Record<string, unknown> }[] = [];

const USERS = [
    { id: "uA", name: "Alice PM", email: "a@x.test", role: "PROPERTY_MANAGER", tenantId: "t1" },
    { id: "uB", name: "Bob PM", email: "b@x.test", role: "PROPERTY_MANAGER", tenantId: "t1" },
];
const props = (...names: string[]) => names.map(n => ({ property: { id: `id-${n}`, nameEn: n } }));

beforeEach(() => {
    held.clear();
    sent.length = 0;
    global.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        const method = init?.method ?? "GET";
        if (method !== "GET") {
            sent.push({ url, method, body: JSON.parse(String(init?.body ?? "{}")) });
            return new Response("{}");
        }
        if (url.endsWith("/admin/users")) return new Response(JSON.stringify(USERS));
        if (url.endsWith("/admin/tenants")) return new Response(JSON.stringify([{ id: "t1", name: "Org One" }, { id: "t2", name: "Org Two" }]));
        const path = url.replace(/^.*\/api\/proxy/, "");
        return new Promise<Response>(resolve => held.set(path, body => resolve(new Response(JSON.stringify(body)))));
    }) as unknown as typeof fetch;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

async function loadComponent(): Promise<ComponentType> {
    const mod = await import("../UsersManager");
    return mod.default;
}

const settle = () => act(async () => { await new Promise(r => setTimeout(r, 0)); });
const release = async (path: string, body: unknown) => {
    await waitFor(() => expect(held.has(path)).toBe(true));
    await act(async () => { held.get(path)!(body); });
    await settle();
};
/** Selected-property chips (the <option>s carry the same names, so only DIVs count). */
const chips = (name: string) => screen.queryAllByText(name).filter(el => el.tagName === "DIV");
const roleSelect = () => screen.getAllByRole("combobox").find(s => within(s).queryByRole("option", { name: /property manager/i }))!;
const editButton = (name: string) => within(screen.getByText(name).closest("tr")!).getByRole("button", { name: /^edit$/i });

describe("UsersManager — a slow read for the previous selection never lands on the newer one", () => {
    it("Edit A, then Edit B: A's late assignments are ignored and Update sends B's own", async () => {
        const UsersManager = await loadComponent();
        render(<UsersManager />);
        await screen.findByText("Alice PM");
        fireEvent.click(editButton("Alice PM"));
        await waitFor(() => expect(held.has("/admin/users/uA/properties")).toBe(true));
        fireEvent.click(editButton("Bob PM"));
        await release("/admin/users/uB/properties", ["id-PropB"]);
        await release("/admin/tenants/t1/properties", props("PropA", "PropB"));
        await release("/admin/users/uA/properties", ["id-PropA"]);

        expect(chips("PropB")).toHaveLength(1);
        expect(chips("PropA")).toHaveLength(0);
        fireEvent.click(screen.getByRole("button", { name: /update user/i }));
        await waitFor(() => expect(sent).toHaveLength(1));
        expect(sent[0].url).toContain("/admin/users/uB");
        expect(sent[0].body.propertyIds).toEqual(["id-PropB"]);
    });

    it("Edit A, then New user: A's late assignments never fill the new user's form", async () => {
        const UsersManager = await loadComponent();
        render(<UsersManager />);
        await screen.findByText("Alice PM");
        fireEvent.click(editButton("Alice PM"));
        await waitFor(() => expect(held.has("/admin/users/uA/properties")).toBe(true));
        fireEvent.click(screen.getByRole("button", { name: /new user/i }));
        // A new property manager: the chips are on screen, so a stray assignment would show.
        fireEvent.change(roleSelect(), { target: { value: "PROPERTY_MANAGER" } });
        await release("/admin/users/uA/properties", ["id-PropA"]);
        await release("/admin/tenants/t1/properties", props("PropA"));
        expect(screen.getByRole("button", { name: /provision user/i })).toBeTruthy();
        // No chip at all: with the organisation cleared a stray id would show as the "Property" fallback.
        expect(chips("PropA")).toHaveLength(0);
        expect(chips("Property")).toHaveLength(0);
    });

    it("organisation t1 then t2: t1's late property list is not offered for t2", async () => {
        const UsersManager = await loadComponent();
        render(<UsersManager />);
        await screen.findByText("Alice PM");
        fireEvent.click(screen.getByRole("button", { name: /new user/i }));
        await screen.findByRole("option", { name: "Org Two" });
        const org = screen.getByRole("option", { name: "Org Two" }).closest("select")!;
        fireEvent.change(roleSelect(), { target: { value: "PROPERTY_MANAGER" } });
        fireEvent.change(org, { target: { value: "t1" } });
        await waitFor(() => expect(held.has("/admin/tenants/t1/properties")).toBe(true));
        fireEvent.change(org, { target: { value: "t2" } });
        await release("/admin/tenants/t2/properties", props("TwoTower"));
        await release("/admin/tenants/t1/properties", props("OneTower"));

        expect(screen.queryByRole("option", { name: "TwoTower" })).not.toBeNull();
        expect(screen.queryByRole("option", { name: "OneTower" })).toBeNull();
    });
});
