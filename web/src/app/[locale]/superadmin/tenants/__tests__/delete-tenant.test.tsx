import { cleanup, render, screen, fireEvent, waitFor } from "@testing-library/react";
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
 * Deleting an organization is a hard delete of every record inside it, with no
 * undo. The API has always supported it — it requires the tenant's exact name
 * as ?confirmName= — but nothing in the product called it, which is why test
 * organizations accumulated.
 *
 * These tests are mostly about the guard rails rather than the happy path: the
 * failure that matters here is deleting the wrong organization, not failing to
 * delete the right one.
 */

const TENANT = {
    id: "tenant-1",
    name: "Acme Property Co",
    status: "ACTIVE",
    createdAt: "2026-01-01T00:00:00Z",
};
const OTHER = {
    id: "tenant-2",
    name: "Globex Holdings",
    status: "ACTIVE",
    createdAt: "2026-01-02T00:00:00Z",
};

let requests: { url: string; method?: string }[] = [];

function mockFetch(onDelete?: () => Response) {
    requests = [];
    global.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
        const u = String(url);
        requests.push({ url: u, method: init?.method });
        if (init?.method === "DELETE") {
            return onDelete ? onDelete() : ({ ok: true, status: 204 } as Response);
        }
        if (u.includes("/admin/tenants")) {
            return { ok: true, json: async () => [TENANT, OTHER] } as Response;
        }
        return { ok: true, json: async () => ({}) } as Response;
    }) as unknown as typeof fetch;
}

async function renderPage() {
    render(
        <NextIntlClientProvider locale="en" messages={en}>
            <TenantsPage />
        </NextIntlClientProvider>,
    );
    await waitFor(() => expect(screen.getByText(TENANT.name)).toBeInTheDocument());
}

function openDeleteFor(name: string) {
    const row = screen.getByText(name).closest("tr");
    expect(row).not.toBeNull();
    const button = Array.from(row!.querySelectorAll("button")).find(
        b => b.getAttribute("aria-label") === en.SuperAdmin.deleteTenant,
    );
    expect(button, "the row should offer a delete action").toBeTruthy();
    fireEvent.click(button!);
}

// By test id, not by label: the row's trash button carries the same accessible
// name, and its hover class contains "bg-error" — matching on either picked the
// wrong button and made these tests pass or fail for the wrong reasons.
const confirmButton = () => screen.getByTestId("confirm-delete-tenant");

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

beforeEach(() => mockFetch());

describe("superadmin: delete organization", () => {
    it("offers a delete action per organization", async () => {
        await renderPage();
        openDeleteFor(TENANT.name);

        expect(screen.getByText(en.SuperAdmin.deleteTenantWarning)).toBeInTheDocument();
    });

    it("keeps the confirm button disabled until the name matches exactly", async () => {
        await renderPage();
        openDeleteFor(TENANT.name);

        expect(confirmButton()).toBeDisabled();

        const input = screen.getByPlaceholderText(TENANT.name);
        fireEvent.change(input, { target: { value: "Acme" } });
        expect(confirmButton(), "a prefix must not be enough").toBeDisabled();

        fireEvent.change(input, { target: { value: "acme property co" } });
        expect(confirmButton(), "the match is case-sensitive").toBeDisabled();

        fireEvent.change(input, { target: { value: TENANT.name } });
        expect(confirmButton()).toBeEnabled();
    });

    it("never issues a DELETE while the name does not match", async () => {
        await renderPage();
        openDeleteFor(TENANT.name);

        fireEvent.change(screen.getByPlaceholderText(TENANT.name), { target: { value: "wrong" } });
        fireEvent.click(confirmButton());

        expect(requests.filter(r => r.method === "DELETE")).toHaveLength(0);
    });

    /** The failure that actually matters: deleting a different organization. */
    it("deletes the organization whose row was clicked, and no other", async () => {
        await renderPage();
        openDeleteFor(OTHER.name);

        fireEvent.change(screen.getByPlaceholderText(OTHER.name), { target: { value: OTHER.name } });
        fireEvent.click(confirmButton());

        await waitFor(() => expect(requests.some(r => r.method === "DELETE")).toBe(true));
        const del = requests.find(r => r.method === "DELETE")!;
        expect(del.url).toContain(`/admin/tenants/${OTHER.id}`);
        expect(del.url).not.toContain(TENANT.id);
    });

    it("sends the name as confirmName, url-encoded", async () => {
        await renderPage();
        openDeleteFor(TENANT.name);

        fireEvent.change(screen.getByPlaceholderText(TENANT.name), { target: { value: TENANT.name } });
        fireEvent.click(confirmButton());

        await waitFor(() => expect(requests.some(r => r.method === "DELETE")).toBe(true));
        // The API compares this against the stored name server-side; spaces
        // must survive the round trip.
        expect(requests.find(r => r.method === "DELETE")!.url)
            .toContain(`confirmName=${encodeURIComponent(TENANT.name)}`);
    });

    it("reports a failure instead of closing as though it worked", async () => {
        mockFetch(() => ({ ok: false, status: 500 }) as Response);
        await renderPage();
        openDeleteFor(TENANT.name);

        fireEvent.change(screen.getByPlaceholderText(TENANT.name), { target: { value: TENANT.name } });
        fireEvent.click(confirmButton());

        await waitFor(() =>
            expect(screen.getByText(en.SuperAdmin.deleteTenantFailed)).toBeInTheDocument(),
        );
        // Still open, so the operator can see what happened and retry.
        expect(screen.getByText(en.SuperAdmin.deleteTenantWarning)).toBeInTheDocument();
    });

    it("explains a 409 differently from a generic failure", async () => {
        mockFetch(() => ({ ok: false, status: 409 }) as Response);
        await renderPage();
        openDeleteFor(TENANT.name);

        fireEvent.change(screen.getByPlaceholderText(TENANT.name), { target: { value: TENANT.name } });
        fireEvent.click(confirmButton());

        await waitFor(() =>
            expect(screen.getByText(en.SuperAdmin.deleteTenantConflict)).toBeInTheDocument(),
        );
    });

    it("treats an already-deleted organization as success", async () => {
        // 404 means it is gone, which is the end state the operator asked for.
        mockFetch(() => ({ ok: false, status: 404 }) as Response);
        await renderPage();
        openDeleteFor(TENANT.name);

        fireEvent.change(screen.getByPlaceholderText(TENANT.name), { target: { value: TENANT.name } });
        fireEvent.click(confirmButton());

        await waitFor(() =>
            expect(screen.queryByText(en.SuperAdmin.deleteTenantWarning)).toBeNull(),
        );
    });
});
