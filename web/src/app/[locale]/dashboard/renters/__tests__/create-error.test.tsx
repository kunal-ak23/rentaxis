import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// A failed POST /v1/renters (400 validation, duplicate portal email aborting
// the transaction, ...) must surface the backend's {message} body in the
// create form instead of silently doing nothing.

vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }),
}));
vi.mock("next-intl", () => ({
    useTranslations: () => (key: string) => key,
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
        if (u.includes("/v1/renters")) {
            return jsonRes(listBody);
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
