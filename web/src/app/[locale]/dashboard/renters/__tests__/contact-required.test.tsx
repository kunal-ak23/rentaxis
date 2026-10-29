import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";

// Client request 2026-10-29: every tenant has an email and a mobile. The Add and Edit
// forms say so before anything is sent (the server refuses the same), and send the
// mobile normalized — a number typed in Arabic digits or pasted with non-breaking
// spaces is stored as plain digits, as the profile page already does.

vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
}));

import RentersPage from "../page";

const jsonRes = (body: unknown, ok = true, status = 200) =>
    ({ ok, status, json: async () => body, text: async () => JSON.stringify(body) }) as unknown as Response;

const RENTER = { id: "r1", nameEn: "Rajesh Kumar", nameAr: null, email: "rajesh@example.com", phone: null,
    primaryLanguage: "EN", invitePending: false, userId: null };

let writes: { method: string; body: Record<string, unknown> }[];

beforeEach(() => {
    writes = [];
    global.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
        const u = String(url);
        if (u.includes("/v1/renters/paged")) return jsonRes({ content: [RENTER], totalElements: 1, totalPages: 1, number: 0, size: 25 });
        if (u.includes("/v1/renters/duplicates") || u.includes("duplicate")) return jsonRes([]);
        if (init?.method === "POST" || init?.method === "PUT") {
            writes.push({ method: init.method, body: JSON.parse(String(init.body)) });
            return jsonRes({ ...RENTER, id: "r2", portalAccount: "INVITED" }, true, init.method === "POST" ? 201 : 200);
        }
        return jsonRes([]);
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

function renderPage() {
    return render(<NextIntlClientProvider locale="en" messages={en}><RentersPage /></NextIntlClientProvider>);
}

async function openAdd() {
    renderPage();
    await screen.findByText("Rajesh Kumar");
    fireEvent.click(screen.getAllByRole("button", { name: en.MasterData.addRenter })[0]);
    fireEvent.change(screen.getByPlaceholderText("John Doe"), { target: { value: "Layla Haddad" } });
}

const submit = () => fireEvent.submit(screen.getByPlaceholderText("+971 50 123 4567").closest("form")!);

describe("Add tenant", () => {
    it("asks for the mobile and sends nothing without it", async () => {
        await openAdd();
        fireEvent.change(screen.getByPlaceholderText("john@example.com"), { target: { value: "layla@example.com" } });
        submit();
        expect(await screen.findByText(en.MasterData.phoneRequired)).toBeTruthy();
        expect(writes).toHaveLength(0);
    });

    it("asks for the email and sends nothing without it", async () => {
        await openAdd();
        fireEvent.change(screen.getByPlaceholderText("+971 50 123 4567"), { target: { value: "050 123 4567" } });
        submit();
        expect(await screen.findByText(en.MasterData.emailRequired)).toBeTruthy();
        expect(writes).toHaveLength(0);
    });

    it("refuses something that is not a mobile number", async () => {
        await openAdd();
        fireEvent.change(screen.getByPlaceholderText("john@example.com"), { target: { value: "layla@example.com" } });
        fireEvent.change(screen.getByPlaceholderText("+971 50 123 4567"), { target: { value: "N/A" } });
        submit();
        expect(await screen.findByText(en.MasterData.phoneInvalid)).toBeTruthy();
        expect(writes).toHaveLength(0);
    });

    it("sends both, the mobile normalized", async () => {
        await openAdd();
        fireEvent.change(screen.getByPlaceholderText("john@example.com"), { target: { value: "layla@example.com" } });
        fireEvent.change(screen.getByPlaceholderText("+971 50 123 4567"), { target: { value: "٠٥٠ ١٢٣ ٤٥٦٧" } });
        submit();
        await waitFor(() => expect(writes).toHaveLength(1));
        expect(writes[0]).toMatchObject({ method: "POST", body: { email: "layla@example.com", phone: "050 123 4567" } });
    });
});

describe("Edit tenant", () => {
    it("will not save a tenant without a mobile, and saves once one is given", async () => {
        renderPage();
        await screen.findByText("Rajesh Kumar");
        fireEvent.click(screen.getByTestId("renter-actions-trigger-r1"));
        fireEvent.click(await screen.findByTestId("renter-edit-r1"));
        const dialog = await screen.findByRole("dialog");
        const phone = dialog.querySelector('input[placeholder="+971 50 123 4567"]') as HTMLInputElement;
        fireEvent.submit(phone.closest("form")!);
        expect(await screen.findByText(en.MasterData.phoneRequired)).toBeTruthy();
        expect(writes).toHaveLength(0);

        fireEvent.change(phone, { target: { value: "+971 50 123 4567" } });
        fireEvent.submit(phone.closest("form")!);
        await waitFor(() => expect(writes).toHaveLength(1));
        expect(writes[0]).toMatchObject({ method: "PUT", body: { email: "rajesh@example.com", phone: "+971 50 123 4567" } });
    });
});
