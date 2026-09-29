import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// A failed POST /v1/renters (400 validation, duplicate portal email aborting
// the transaction, ...) must surface the backend's {message} body in the
// create form instead of silently doing nothing.

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
    ({
        ok,
        status,
        json: async () => body,
        text: async () => JSON.stringify(body),
    }) as unknown as Response;

let postResponse: { ok: boolean; status: number; body: unknown };
let listBody: unknown[];
let resendUrls: string[];

beforeEach(() => {
    postResponse = { ok: true, status: 200, body: { id: "r1" } };
    listBody = [];
    resendUrls = [];
    global.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
        const u = String(url);
        if (u.includes("/v1/renters") && init?.method === "POST") {
            return jsonRes(postResponse.body, postResponse.ok, postResponse.status);
        }
        if (u.includes("/resend-invite") && init?.method === "POST") {
            resendUrls.push(u);
            return jsonRes({});
        }
        if (u.includes("/v1/renters/paged")) {
            return jsonRes({ content: listBody, totalElements: listBody.length, totalPages: 1, number: 0, size: 25 });
        }
        return jsonRes({}, false, 404);
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("RentersPage create form", () => {
    it("surfaces the backend message when creating a renter fails", async () => {
        postResponse = {
            ok: false,
            status: 400,
            body: { error: true, message: "A portal account with this email already exists.", status: 400 },
        };
        render(<RentersPage />);

        // Empty state and header both render an addRenter button.
        fireEvent.click((await screen.findAllByText("addRenter"))[0]);
        fireEvent.change(screen.getByPlaceholderText("John Doe"), { target: { value: "New Renter" } });
        fireEvent.click(screen.getByText("create"));

        expect(
            await screen.findByText("A portal account with this email already exists.")
        ).toBeTruthy();
    });

    // #7: the API returns no password and the page shows none; it confirms the
    // emailed invite instead.
    it("confirms the emailed invite and shows no password after creating a renter", async () => {
        postResponse = { ok: true, status: 201, body: { id: "r1", invitePending: true, portalPassword: "Renter@leak" } };
        render(<RentersPage />);

        fireEvent.click((await screen.findAllByText("addRenter"))[0]);
        fireEvent.change(screen.getByPlaceholderText("John Doe"), { target: { value: "New Renter" } });
        fireEvent.change(screen.getByPlaceholderText("john@example.com"), { target: { value: "r@x.com" } });
        fireEvent.click(screen.getByText("create"));

        expect(await screen.findByText("sentBody")).toBeTruthy();
        expect(screen.queryByText(/Renter@leak/)).toBeNull();
        expect(screen.queryByText(/password/i)).toBeNull();
    });

    // Owner ruling 2026-09-29: every tenant with an email gets portal access;
    // the form offers no opt-out and never asks the API for none.
    it("offers no opt-out of portal access and never sends one", async () => {
        const bodies: unknown[] = [];
        const base = global.fetch;
        global.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
            if (String(url).includes("/v1/renters") && init?.method === "POST") bodies.push(JSON.parse(String(init.body)));
            return base(url as RequestInfo, init);
        }) as unknown as typeof fetch;
        postResponse = { ok: true, status: 201, body: { id: "r1", invitePending: true, portalAccount: "INVITED" } };
        render(<RentersPage />);

        fireEvent.click((await screen.findAllByText("addRenter"))[0]);
        expect(screen.queryByRole("checkbox")).toBeNull();
        expect(screen.queryByText("createPortalAccount")).toBeNull();
        fireEvent.change(screen.getByPlaceholderText("John Doe"), { target: { value: "New Renter" } });
        fireEvent.change(screen.getByPlaceholderText("john@example.com"), { target: { value: "r@x.com" } });
        fireEvent.click(screen.getByText("create"));

        expect(await screen.findByText("sentBody")).toBeTruthy();
        expect(bodies).toHaveLength(1);
        expect(bodies[0]).not.toHaveProperty("createPortalAccount");
    });

    it("says no portal account was created when the email is already in use", async () => {
        postResponse = { ok: true, status: 201, body: { id: "r1", invitePending: false, portalAccount: "SKIPPED_EMAIL_IN_USE" } };
        render(<RentersPage />);

        fireEvent.click((await screen.findAllByText("addRenter"))[0]);
        fireEvent.change(screen.getByPlaceholderText("John Doe"), { target: { value: "New Renter" } });
        fireEvent.change(screen.getByPlaceholderText("john@example.com"), { target: { value: "r@x.com" } });
        fireEvent.click(screen.getByText("create"));

        expect(await screen.findByText("emailInUseBody")).toBeTruthy();
        expect(screen.getByText("savedTitle")).toBeTruthy();
        expect(screen.queryByText("sentBody")).toBeNull();
    });

    // A linked existing portal user may still have their first invite pending:
    // the page must not claim a new one was just sent.
    it("says the tenant was linked to their existing portal account, not re-invited", async () => {
        postResponse = { ok: true, status: 201, body: { id: "r1", invitePending: true, portalAccount: "LINKED_EXISTING" } };
        render(<RentersPage />);

        fireEvent.click((await screen.findAllByText("addRenter"))[0]);
        fireEvent.change(screen.getByPlaceholderText("John Doe"), { target: { value: "New Renter" } });
        fireEvent.change(screen.getByPlaceholderText("john@example.com"), { target: { value: "r@x.com" } });
        fireEvent.click(screen.getByText("create"));

        expect(await screen.findByText("linkedExistingBody")).toBeTruthy();
        expect(screen.queryByText("sentBody")).toBeNull();
    });

    it("says no email was given when that is why there is no portal account", async () => {
        postResponse = { ok: true, status: 201, body: { id: "r1", invitePending: false } };
        render(<RentersPage />);

        fireEvent.click((await screen.findAllByText("addRenter"))[0]);
        fireEvent.change(screen.getByPlaceholderText("John Doe"), { target: { value: "New Renter" } });
        fireEvent.click(screen.getByText("create"));

        expect(await screen.findByText("noPortalBody")).toBeTruthy();
    });

    it("offers Resend invite for a renter whose invite is pending", async () => {
        listBody = [
            { id: "r1", nameEn: "Pending", nameAr: "", email: "p@x.com", phone: "", primaryLanguage: "EN", userId: "u1", invitePending: true },
            { id: "r2", nameEn: "Active", nameAr: "", email: "a@x.com", phone: "", primaryLanguage: "EN", userId: "u2", invitePending: false },
        ];
        render(<RentersPage />);

        vi.spyOn(window, "confirm").mockReturnValue(true);
        const buttons = await screen.findAllByText("resend");
        expect(buttons).toHaveLength(1);
        fireEvent.click(buttons[0]);

        await waitFor(() => expect(resendUrls).toEqual(["/api/proxy/admin/users/u1/resend-invite"]));
    });

    // #8: every row leads to the renter's detail page.
    it("links each renter to their detail page", async () => {
        listBody = [
            { id: "r1", nameEn: "Ahmed", nameAr: "", email: "a@x.com", phone: "", primaryLanguage: "EN", userId: null },
        ];
        render(<RentersPage />);

        const view = await screen.findByText("view");
        expect(view.closest("a")?.getAttribute("href")).toBe("/dashboard/renters/r1");
        expect(screen.getByText("Ahmed").closest("a")?.getAttribute("href")).toBe("/dashboard/renters/r1");
    });
});
