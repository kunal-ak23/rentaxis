import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";

// Edit tenant: the list row's Actions menu opens the shared edit dialog
// prefilled, PUTs the full form to /v1/renters/{id}, surfaces a coded server
// refusal translated through the real EN catalogue, and is not offered at all
// to a role the backend's PUT (@PreAuthorize SUPER_ADMIN/TENANT_ADMIN) refuses.

let role = "TENANT_ADMIN";
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role } } }) }));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
}));

import RentersPage from "../page";

const jsonRes = (body: unknown, ok = true, status = 200) =>
    ({ ok, status, json: async () => body, text: async () => JSON.stringify(body) }) as unknown as Response;

const RENTER = { id: "r1", nameEn: "Rajesh Kumar", nameAr: "راجيش كومار", email: "rajesh@example.com", phone: "+971501234567", primaryLanguage: "EN", invitePending: false, userId: "u1" };
const pagedBody = { content: [RENTER], totalElements: 1, totalPages: 1, number: 0, size: 25 };

let putHandler: (init?: RequestInit) => Promise<Response>;

beforeEach(() => {
    role = "TENANT_ADMIN";
    putHandler = async () => jsonRes({ ...RENTER, nameEn: "Rajesh Renamed" });
    global.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
        const u = String(url);
        if (u.includes("/v1/renters/paged")) return jsonRes(pagedBody);
        if (u.endsWith("/v1/renters/r1") && init?.method === "PUT") return putHandler(init);
        return jsonRes({}, false, 404);
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

function renderPage() {
    return render(<NextIntlClientProvider locale="en" messages={en}><RentersPage /></NextIntlClientProvider>);
}

async function openEditDialog() {
    await screen.findByText("Rajesh Kumar");
    fireEvent.click(screen.getByTestId("renter-actions-trigger-r1"));
    fireEvent.click(await screen.findByTestId("renter-edit-r1"));
}

describe("Edit tenant — list row", () => {
    it("shows an Edit item in the row's actions menu for a role that may manage tenants", async () => {
        renderPage();
        await screen.findByText("Rajesh Kumar");
        fireEvent.click(screen.getByTestId("renter-actions-trigger-r1"));
        expect(await screen.findByTestId("renter-edit-r1")).toBeTruthy();
        expect(screen.getByTestId("renter-view-r1")).toBeTruthy();
    });

    it("hides Edit for a role the backend's PUT does not allow", async () => {
        role = "PROPERTY_MANAGER";
        renderPage();
        await screen.findByText("Rajesh Kumar");
        fireEvent.click(screen.getByTestId("renter-actions-trigger-r1"));
        expect(screen.getByTestId("renter-view-r1")).toBeTruthy();
        expect(screen.queryByTestId("renter-edit-r1")).toBeNull();
    });

    it("opens prefilled with the row's current values", async () => {
        renderPage();
        await openEditDialog();

        expect((screen.getByPlaceholderText("John Doe") as HTMLInputElement).value).toBe("Rajesh Kumar");
        expect((screen.getByPlaceholderText("john@example.com") as HTMLInputElement).value).toBe("rajesh@example.com");
        expect((screen.getByPlaceholderText("+971 50 123 4567") as HTMLInputElement).value).toBe("+971501234567");
        // Arabic name field is RTL-safe regardless of the page's own direction.
        expect(screen.getByPlaceholderText("جون دو").getAttribute("dir")).toBe("rtl");
    });

    it("submits a PUT with the changed fields and refreshes the list", async () => {
        renderPage();
        await openEditDialog();

        const nameInput = screen.getByPlaceholderText("John Doe");
        fireEvent.change(nameInput, { target: { value: "Rajesh Renamed" } });

        let sentBody: Record<string, unknown> | null = null;
        putHandler = async (init) => {
            sentBody = JSON.parse(String(init?.body));
            return jsonRes({ ...RENTER, nameEn: "Rajesh Renamed" });
        };
        fireEvent.click(screen.getByText("Save Changes"));

        await waitFor(() => expect(sentBody).not.toBeNull());
        expect(sentBody).toMatchObject({ nameEn: "Rajesh Renamed", email: "rajesh@example.com", phone: "+971501234567" });
        // The dialog closes on success.
        await waitFor(() => expect(screen.queryByPlaceholderText("John Doe")).toBeNull());
    });

    it("shows the server's coded refusal translated, and keeps the dialog open", async () => {
        renderPage();
        await openEditDialog();

        putHandler = async () => jsonRes(
            { error: true, message: "A user with this email already exists.", status: 400, code: "renter.emailTaken" },
            false, 400,
        );
        fireEvent.click(screen.getByText("Save Changes"));

        expect(await screen.findByText("A user with this email already exists.")).toBeTruthy();
        // Refused: the dialog is still open with the field editable.
        expect(screen.getByPlaceholderText("John Doe")).toBeTruthy();
    });
});
