import { cleanup, render, screen, fireEvent, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";

import en from "../../../../../../messages/en.json";

vi.mock("next/navigation", () => ({
    useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/components/ui/Pagination", () => ({ Pagination: () => null }));
vi.mock("@/components/ui/FileUpload", () => ({ FileUpload: () => null }));

import TenantsPage from "../page";

/**
 * Break-it round 3 (ops3) F6: a stale "Edit Organisation" dialog re-activated an
 * organisation another tab had just deactivated — it PUT its whole form, status
 * included. The dialog now sends only the fields changed in it, with the values it
 * loaded (`expected`), and never the status; status moves only through the
 * Activate / Deactivate action, which names the status the page showed.
 */
const ORG = {
    id: "org-1",
    name: "BRK3-OPS Org",
    status: "ACTIVE",
    address: "Old address",
    trn: "",
    ticketOtpRequired: true,
    createdAt: "2026-01-01T00:00:00Z",
};

let list: Record<string, unknown>[] = [];
let puts: { url: string; body: Record<string, unknown> }[] = [];
let putResponse: (url: string) => Response = () => new Response("{}", { status: 200 });

beforeEach(() => {
    list = [ORG];
    puts = [];
    putResponse = () => new Response("{}", { status: 200 });
    global.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
        const u = String(url);
        if (init?.method === "PUT") {
            puts.push({ url: u, body: JSON.parse(String(init.body)) });
            return putResponse(u);
        }
        return new Response(JSON.stringify(list), { status: 200 });
    }) as unknown as typeof fetch;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

async function renderPage() {
    render(
        <NextIntlClientProvider locale="en" messages={en}>
            <TenantsPage />
        </NextIntlClientProvider>,
    );
    await waitFor(() => expect(screen.getByText(ORG.name)).toBeInTheDocument());
}

const row = () => screen.getByText(ORG.name).closest("tr")!;

function openEdit() {
    fireEvent.click(within(row()).getByRole("button", { name: en.SuperAdmin.orgEditButton }));
}

describe("superadmin: edit organisation (F6)", () => {
    it("the dialog has no status field", async () => {
        await renderPage();
        openEdit();
        expect(screen.queryByRole("option", { name: en.SuperAdmin.orgStatusINACTIVE })).toBeNull();
    });

    it("changing only the address sends the address and what the dialog loaded — never the status", async () => {
        await renderPage();
        openEdit();
        fireEvent.change(screen.getByDisplayValue("Old address"), { target: { value: "New address" } });
        fireEvent.submit(screen.getByDisplayValue("New address").closest("form")!);
        await waitFor(() => expect(puts).toHaveLength(1));
        expect(puts[0].url).toBe("/api/proxy/admin/tenants/org-1");
        expect(puts[0].body).toEqual({ address: "New address", expected: { address: "Old address" } });
    });

    it("409 org.changed reloads the dialog with the current values and says so", async () => {
        await renderPage();
        openEdit();
        putResponse = () => new Response(JSON.stringify({ code: "org.changed", status: 409 }), { status: 409 });
        list = [{ ...ORG, address: "Tab B address" }];
        fireEvent.change(screen.getByDisplayValue("Old address"), { target: { value: "Tab A address" } });
        fireEvent.submit(screen.getByDisplayValue("Tab A address").closest("form")!);
        expect(await screen.findByText(en.SuperAdmin.orgChanged)).toBeInTheDocument();
        expect(screen.getByDisplayValue("Tab B address")).toBeInTheDocument();
    });
});

describe("superadmin: activate / deactivate (F6)", () => {
    it("deactivates through the status action, naming the status the page showed", async () => {
        await renderPage();
        fireEvent.click(screen.getByTestId("org-status-org-1"));
        fireEvent.click(screen.getByTestId("org-status-confirm"));
        await waitFor(() => expect(puts).toHaveLength(1));
        expect(puts[0].url).toBe("/api/proxy/admin/tenants/org-1/status");
        expect(puts[0].body).toEqual({ status: "INACTIVE", expectedStatus: "ACTIVE" });
    });

    it("a status that moved meanwhile is refused and shown", async () => {
        await renderPage();
        fireEvent.click(screen.getByTestId("org-status-org-1"));
        putResponse = () => new Response(JSON.stringify({ code: "org.changed", status: 409 }), { status: 409 });
        list = [{ ...ORG, status: "INACTIVE" }];
        fireEvent.click(screen.getByTestId("org-status-confirm"));
        expect(await screen.findByText(en.SuperAdmin.orgStatusChanged)).toBeInTheDocument();
        // Review r3B M9: the dialog keeps its action and cannot be confirmed again.
        expect(screen.getByTestId("org-status-confirm")).toHaveTextContent(en.SuperAdmin.orgDeactivate);
        expect(screen.getByTestId("org-status-confirm")).toBeDisabled();
        fireEvent.click(screen.getByTestId("org-status-confirm"));
        expect(puts).toHaveLength(1);
    });
});
