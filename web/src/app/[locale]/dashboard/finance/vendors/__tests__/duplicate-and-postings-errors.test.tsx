import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";

/**
 * F14-43: vendor create/update can 400 with a coded body (vendor.duplicateTrn,
 * vendor.duplicateName), and delete with vendor.hasPostings. The real
 * NextIntlClientProvider is used (not a key-echoing mock) so serverText's
 * `t.has(...)` lookup against Common.errors.vendor.* is exercised for real.
 */

vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
}));
vi.mock("@/components/ui/Pagination", () => ({ Pagination: () => null }));
vi.mock("@/components/ui/confirm-dialog", () => ({
    ConfirmDialog: ({ isOpen, onConfirm }: { isOpen: boolean; onConfirm: () => void }) =>
        isOpen ? <button onClick={onConfirm}>confirm-dialog-confirm</button> : null,
}));

import VendorsPage from "../page";

const vendor = {
    id: "11111111-1111-1111-1111-111111111111", nameEn: "Acme Maintenance", nameAr: "", tradeLicenseNumber: "",
    trn: "", email: "", phone: "", contactPerson: "", address: "", bankName: "", bankAccountNumber: "", iban: "",
    payableAccount: null, notes: "", active: true,
};

let deleteResponse: { ok: boolean; status: number; body: unknown };
let saveResponse: { ok: boolean; status: number; body: unknown };

beforeEach(() => {
    deleteResponse = { ok: true, status: 200, body: {} };
    saveResponse = { ok: true, status: 200, body: vendor };
    global.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
        const u = String(url);
        const method = init?.method ?? "GET";
        if (method === "DELETE") {
            return { ok: deleteResponse.ok, status: deleteResponse.status, text: async () => JSON.stringify(deleteResponse.body) } as unknown as Response;
        }
        if (method === "POST" || method === "PUT") {
            return { ok: saveResponse.ok, status: saveResponse.status, text: async () => JSON.stringify(saveResponse.body) } as unknown as Response;
        }
        if (u.includes("/finance/accounts")) return { ok: true, status: 200, json: async () => [] } as unknown as Response;
        return { ok: true, status: 200, json: async () => [vendor] } as unknown as Response;
    }) as unknown as typeof fetch;
});

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

function renderPage() {
    return render(<NextIntlClientProvider locale="en" messages={en}><VendorsPage /></NextIntlClientProvider>);
}

describe("vendor duplicate and has-postings refusals (F14-43)", () => {
    it("shows the translated duplicate-TRN message on save", async () => {
        saveResponse = { ok: false, status: 400, body: {
            code: "vendor.duplicateTrn", args: { vendor: "Acme Maintenance" }, message: "duplicate trn",
        } };
        const { container } = renderPage();
        await screen.findByLabelText("Delete Vendor");
        fireEvent.click(screen.getByText("Add Vendor"));
        fireEvent.submit(container.querySelector("form")!);
        await waitFor(() => expect(screen.getByText("Acme Maintenance already uses this TRN.")).toBeTruthy());
    });

    it("shows the translated duplicate-name message on save", async () => {
        saveResponse = { ok: false, status: 400, body: {
            code: "vendor.duplicateName", args: { vendor: "Acme Maintenance" }, message: "duplicate name",
        } };
        const { container } = renderPage();
        await screen.findByLabelText("Delete Vendor");
        fireEvent.click(screen.getByText("Add Vendor"));
        fireEvent.submit(container.querySelector("form")!);
        await waitFor(() => expect(screen.getByText("Acme Maintenance already uses this name.")).toBeTruthy());
    });

    it("shows the translated has-postings message on delete", async () => {
        deleteResponse = { ok: false, status: 400, body: {
            code: "vendor.hasPostings", args: { vendor: "Acme Maintenance" }, message: "has postings",
        } };
        renderPage();
        fireEvent.click(await screen.findByLabelText("Delete Vendor"));
        fireEvent.click(screen.getByText("confirm-dialog-confirm"));
        await waitFor(() => expect(screen.getByText("Acme Maintenance has postings. Mark it inactive instead.")).toBeTruthy());
    });
});
