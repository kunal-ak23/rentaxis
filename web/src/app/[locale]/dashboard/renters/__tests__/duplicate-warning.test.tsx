import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// Break round 1 (controller ruling): the backend may keep two renters with the
// same email, but the Add Tenant form must warn first — list the existing
// match(es) with "Open existing", and create only on "Create anyway".

vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }),
}));
vi.mock("next-intl", () => ({
    useTranslations: () => Object.assign((key: string) => key, { has: () => false }),
    useLocale: () => "en",
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));

import RentersPage from "../page";

const jsonRes = (body: unknown, ok = true, status = 200) =>
    ({ ok, status, json: async () => body, text: async () => JSON.stringify(body) }) as unknown as Response;

const existing = [
    { id: "r-old", nameEn: "Sweep Tenant", nameAr: null, email: "sweep@example.com", phone: "+971 50 123 4567" },
];
let posts: number;
let searches: string[];

beforeEach(() => {
    posts = 0;
    searches = [];
    global.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
        const u = String(url);
        if (u.endsWith("/v1/renters") && init?.method === "POST") {
            posts++;
            return jsonRes({ id: "r-new", invitePending: false }, true, 201);
        }
        if (u.includes("/v1/renters/search")) {
            searches.push(u);
            return jsonRes(existing);
        }
        if (u.includes("/v1/renters/paged")) {
            return jsonRes({ content: [], totalElements: 0, totalPages: 1, number: 0, size: 25 });
        }
        return jsonRes({}, false, 404);
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

async function fill(email: string, phone: string) {
    render(<RentersPage />);
    fireEvent.click((await screen.findAllByText("addRenter"))[0]);
    fireEvent.change(screen.getByPlaceholderText("John Doe"), { target: { value: "New Person" } });
    fireEvent.change(screen.getByPlaceholderText("john@example.com"), { target: { value: email } });
    fireEvent.change(screen.getByPlaceholderText("+971 50 123 4567"), { target: { value: phone } });
    fireEvent.click(screen.getByText("create"));
}

describe("Add Tenant duplicate warning", () => {
    it("warns on a matching email (any case, trimmed) and does not create", async () => {
        await fill("  SWEEP@Example.com ", "");
        const warning = await screen.findByTestId("renter-duplicate-warning");
        expect(warning.textContent).toContain("Sweep Tenant");
        expect(warning.textContent).toContain("duplicateMatchEmail");
        const open = screen.getByText("openExisting").closest("a");
        expect(open?.getAttribute("href")).toBe("/dashboard/renters/r-old");
        expect(posts).toBe(0);
    });

    it("warns on a matching phone in a different format", async () => {
        await fill("", "050-123-4567");
        const warning = await screen.findByTestId("renter-duplicate-warning");
        expect(warning.textContent).toContain("Sweep Tenant");
        expect(warning.textContent).toContain("duplicateMatchPhone");
        expect(posts).toBe(0);
    });

    it("creates straight away (no warning) when nothing matches", async () => {
        await fill("brand-new@example.com", "0559999999");
        expect(await screen.findByText("noInviteBody")).toBeTruthy();
        expect(screen.queryByTestId("renter-duplicate-warning")).toBeNull();
        expect(posts).toBe(1);
        expect(searches.length).toBeGreaterThan(0);
    });

    it("creates only after Create anyway", async () => {
        await fill("sweep@example.com", "");
        await screen.findByTestId("renter-duplicate-warning");
        expect(posts).toBe(0);
        fireEvent.click(screen.getByText("createAnyway"));
        await waitFor(() => expect(posts).toBe(1));
        expect(await screen.findByText("noInviteBody")).toBeTruthy();
    });

    it("editing the email clears the warning so the check runs again", async () => {
        await fill("sweep@example.com", "");
        await screen.findByTestId("renter-duplicate-warning");
        fireEvent.change(screen.getByPlaceholderText("john@example.com"), { target: { value: "else@example.com" } });
        expect(screen.queryByTestId("renter-duplicate-warning")).toBeNull();
    });
});
