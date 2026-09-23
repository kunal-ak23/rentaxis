import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// Covers two audited bugs on the superadmin users page:
// 1. Removing every property from a PROPERTY_MANAGER must send
//    propertyIds: [] — UserController skips the assignment sync entirely
//    when propertyIds is null, so omitting the empty list silently keeps
//    access the admin believes was revoked.
// 2. Failed create/update/delete (400 duplicate email, 403, ...) must
//    surface the backend's {message} body instead of doing nothing.

vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { role: "SUPER_ADMIN", tenantId: "" } } }),
}));
vi.mock("next-intl", () => ({
    useTranslations: () => (key: string) => key,
}));

import SuperAdminUsersPage from "../page";

const PM_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
const PROP_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

const pmUser = {
    id: PM_ID,
    name: "PM One",
    email: "pm@x.com",
    role: "PROPERTY_MANAGER",
    tenantId: "t1",
};

const jsonRes = (body: unknown, ok = true, status = 200) =>
    ({
        ok,
        status,
        json: async () => body,
        text: async () => JSON.stringify(body),
    }) as unknown as Response;

let putBodies: unknown[];
let postBodies: Record<string, unknown>[];
let resendUrls: string[];
let postResponse: { ok: boolean; status: number; body: unknown };
let deleteResponse: { ok: boolean; status: number; body: unknown };

beforeEach(() => {
    putBodies = [];
    postBodies = [];
    resendUrls = [];
    postResponse = { ok: true, status: 200, body: pmUser };
    deleteResponse = { ok: true, status: 200, body: {} };
    global.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
        const u = String(url);
        const method = init?.method ?? "GET";
        if (u.includes(`/admin/users/${PM_ID}/properties`)) {
            return jsonRes([PROP_ID]);
        }
        if (u.includes(`/admin/users/${PM_ID}`) && method === "PUT") {
            putBodies.push(JSON.parse(String(init?.body)));
            return jsonRes(pmUser);
        }
        if (u.includes(`/admin/users/${PM_ID}`) && method === "DELETE") {
            return jsonRes(deleteResponse.body, deleteResponse.ok, deleteResponse.status);
        }
        if (u.includes("/resend-invite") && method === "POST") {
            resendUrls.push(u);
            return jsonRes({ ...pmUser, invitePending: true });
        }
        if (u.includes("/admin/users") && method === "POST") {
            postBodies.push(JSON.parse(String(init?.body)));
            return jsonRes(postResponse.body, postResponse.ok, postResponse.status);
        }
        if (u.includes("/admin/users")) {
            return jsonRes([{ ...pmUser, invitePending: true }]);
        }
        if (u.includes("/admin/tenants")) {
            return jsonRes([{ id: "t1", name: "Tenant One" }]);
        }
        if (u.includes("/v1/properties")) {
            return jsonRes([{ property: { id: PROP_ID, nameEn: "Tower A" } }]);
        }
        return jsonRes({}, false, 404);
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("SuperAdminUsersPage", () => {
    it("sends propertyIds: [] when the last property is removed from a PROPERTY_MANAGER", async () => {
        render(<SuperAdminUsersPage />);

        fireEvent.click(await screen.findByText("Edit"));

        // The assignment chip loads from /admin/users/{id}/properties.
        const chip = (await screen.findAllByText("Tower A")).find((el) => el.tagName === "DIV");
        expect(chip).toBeTruthy();
        fireEvent.click(chip!.querySelector("button")!);

        fireEvent.click(screen.getByText("Update User"));

        await waitFor(() => expect(putBodies.length).toBe(1));
        expect((putBodies[0] as { propertyIds?: string[] }).propertyIds).toEqual([]);
    });

    it("surfaces the backend message when creating a user fails and keeps the form open", async () => {
        postResponse = {
            ok: false,
            status: 400,
            body: { error: true, message: "A user with this email already exists in this tenant.", status: 400 },
        };
        render(<SuperAdminUsersPage />);

        fireEvent.click(await screen.findByText("New User"));
        fireEvent.change(screen.getByPlaceholderText("e.g. Acme Corp Admin"), { target: { value: "New Admin" } });
        fireEvent.change(screen.getByPlaceholderText("e.g. admin@acmecorp.com"), { target: { value: "dup@x.com" } });
        fireEvent.click(screen.getByText("Provision User"));

        expect(
            await screen.findByText("A user with this email already exists in this tenant.")
        ).toBeTruthy();
        // Form stays open so the user can correct the email.
        expect(screen.getByText("Provision New User")).toBeTruthy();
    });

    it("surfaces the backend message when deleting a user fails", async () => {
        deleteResponse = {
            ok: false,
            status: 400,
            body: { error: true, message: "Cannot delete a user with active assignments.", status: 400 },
        };
        render(<SuperAdminUsersPage />);

        fireEvent.click(await screen.findByText("Delete"));
        // "Delete User" is both the dialog title and its confirm button.
        const confirmButton = (await screen.findAllByText("Delete User")).find(
            (el) => el.tagName === "BUTTON"
        );
        fireEvent.click(confirmButton!);

        expect(
            await screen.findByText("Cannot delete a user with active assignments.")
        ).toBeTruthy();
    });

    // #7 / #2: invited roles are onboarded by email, so the form asks for no
    // password and sends none; only a SUPER_ADMIN is still given one here.
    it("creates an invited user without asking for or sending a password", async () => {
        render(<SuperAdminUsersPage />);

        fireEvent.click(await screen.findByText("New User"));
        expect(screen.queryByPlaceholderText("Secure password")).toBeNull();
        expect(screen.getByText("passwordNotNeeded")).toBeTruthy();
        fireEvent.change(screen.getByPlaceholderText("e.g. Acme Corp Admin"), { target: { value: "New User" } });
        fireEvent.change(screen.getByPlaceholderText("e.g. admin@acmecorp.com"), { target: { value: "new@x.com" } });
        fireEvent.click(screen.getByText("Provision User"));

        await waitFor(() => expect(postBodies.length).toBe(1));
        expect(postBodies[0]).not.toHaveProperty("password");
    });

    it("still requires a password for a SUPER_ADMIN", async () => {
        render(<SuperAdminUsersPage />);

        fireEvent.click(await screen.findByText("New User"));
        fireEvent.change(screen.getByDisplayValue("Tenant"), { target: { value: "SUPER_ADMIN" } });

        expect(screen.getByPlaceholderText("Secure password")).toBeTruthy();
    });

    it("resends a pending invite for that user", async () => {
        render(<SuperAdminUsersPage />);

        fireEvent.click(await screen.findByText("resend"));

        await waitFor(() => expect(resendUrls).toEqual([`/api/proxy/admin/users/${PM_ID}/resend-invite`]));
        expect(await screen.findByText("resent")).toBeTruthy();
    });
});
