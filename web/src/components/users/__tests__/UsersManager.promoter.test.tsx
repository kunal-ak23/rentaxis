import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { id: "ta", role: "TENANT_ADMIN", tenantId: "t1" } } }) }));

import UsersManager from "../UsersManager";

/**
 * A promoter is invited from Promotions → Promoters and the backend refuses any
 * role change to or from PROMOTER, so Users must not offer one: PROMOTER is not a
 * choice for a new user, and an existing promoter's role is shown but locked.
 */
const USERS = [
    { id: "uP", name: "Pat Promoter", email: "promoter@example.test", role: "PROMOTER", tenantId: "t1", phoneNumber: "" },
    { id: "uT", name: "Tom Tenant", email: "tenant.user@example.test", role: "TENANT_USER", tenantId: "t1", phoneNumber: "" },
];

beforeEach(() => {
    global.fetch = vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.endsWith("/admin/users")) return new Response(JSON.stringify(USERS));
        return new Response("[]");
    }) as unknown as typeof fetch;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

const row = (name: string) => screen.getByText(name).closest("tr") as HTMLElement;
// The role picker is the one <select> that offers the role values (the tenant picker offers organisations).
const roleSelect = () => Array.from(document.querySelectorAll("select"))
    .find(sel => sel.querySelector('option[value="TENANT_USER"]')) as HTMLSelectElement;

describe("UsersManager and the PROMOTER role", () => {
    it("lists a promoter under the role's label", async () => {
        render(<UsersManager />);
        await screen.findByText("Pat Promoter");
        expect(within(row("Pat Promoter")).getByText("Promoter")).toBeInTheDocument();
    });

    it("locks an existing promoter's role", async () => {
        render(<UsersManager />);
        await screen.findByText("Pat Promoter");
        fireEvent.click(within(row("Pat Promoter")).getByRole("button", { name: /^edit$/i }));
        await waitFor(() => expect(screen.getByDisplayValue("Pat Promoter")).toBeInTheDocument());
        expect(roleSelect().value).toBe("PROMOTER");
        expect(roleSelect()).toBeDisabled();
    });

    it("leaves other users' roles editable, and never offers PROMOTER as a choice", async () => {
        render(<UsersManager />);
        await screen.findByText("Tom Tenant");
        fireEvent.click(within(row("Tom Tenant")).getByRole("button", { name: /^edit$/i }));
        await waitFor(() => expect(screen.getByDisplayValue("Tom Tenant")).toBeInTheDocument());
        expect(roleSelect()).toBeEnabled();
        expect(Array.from(roleSelect().options).map(o => o.value)).not.toContain("PROMOTER");
    });
});
