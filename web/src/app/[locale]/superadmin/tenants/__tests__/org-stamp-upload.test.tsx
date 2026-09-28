import { cleanup, render, screen, fireEvent, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";

import en from "../../../../../../messages/en.json";
import ar from "../../../../../../messages/ar.json";

vi.mock("next/navigation", () => ({
    useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/components/ui/Pagination", () => ({ Pagination: () => null }));
// The upload itself (POST /api/v1/assets/upload) is FileUpload's; here a stand-in
// reports an uploaded URL the way the real one does, through onChange.
vi.mock("@/components/ui/FileUpload", () => ({
    FileUpload: ({ label, hint, value, accept, onChange, onRemove }: {
        label: string; hint: string; value?: string; accept?: string;
        onChange: (u: string) => void; onRemove: () => void;
    }) => (
        <div data-testid={`upload-${label}`} data-accept={accept ?? ""} data-value={value ?? ""}>
            <span>{hint}</span>
            <button type="button" onClick={() => onChange(`/api/v1/assets/serve/assets/${label.replace(/\W+/g, "-")}.png`)}>{label}</button>
            <button type="button" onClick={onRemove}>remove {label}</button>
        </div>
    ),
}));

import TenantsPage from "../page";

/**
 * Organisation branding (2026-09-28): the stamp printed beside the landlord
 * signature on the contract is uploaded next to the logo and saved with the
 * organisation, exactly like the logo — the API used to ignore it.
 */
const STAMP = "/api/v1/assets/serve/assets/old-stamp.png";
const ORG = { id: "org-1", name: "Oasis Crest", status: "ACTIVE", createdAt: "2026-01-01T00:00:00Z" };

let list: Record<string, unknown>[] = [];
let sent: { url: string; method?: string; body: Record<string, unknown> }[] = [];

beforeEach(() => {
    list = [ORG];
    sent = [];
    global.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
        if (init?.method === "PUT" || init?.method === "POST") {
            sent.push({ url: String(url), method: init.method, body: JSON.parse(String(init.body)) });
            return new Response("{}", { status: 200 });
        }
        return new Response(JSON.stringify(list), { status: 200 });
    }) as unknown as typeof fetch;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

async function renderPage(locale: "en" | "ar" = "en") {
    render(
        <NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}>
            <TenantsPage />
        </NextIntlClientProvider>,
    );
    await waitFor(() => expect(screen.getByText(ORG.name)).toBeInTheDocument());
}

function openEdit() {
    const row = screen.getByText(ORG.name).closest("tr")!;
    fireEvent.click(within(row).getByRole("button", { name: en.SuperAdmin.orgEditButton }));
}

const form = () => screen.getByTestId("org-stamp-upload").closest("form")!;

describe("superadmin: organisation stamp", () => {
    it("offers the stamp upload right after the logo, PNG/JPG only", async () => {
        await renderPage();
        openEdit();
        const logo = screen.getByTestId(`upload-${en.SuperAdmin.orgUploadLogo}`);
        const stamp = screen.getByTestId(`upload-${en.SuperAdmin.orgUploadStamp}`);
        expect(logo.compareDocumentPosition(stamp) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
        expect(stamp).toHaveAttribute("data-accept", "image/png,image/jpeg");
        expect(screen.getByText(en.SuperAdmin.orgStamp)).toBeInTheDocument();
        expect(screen.getByText(en.SuperAdmin.orgStampHint)).toBeInTheDocument();
    });

    it("an uploaded stamp is saved with the organisation, naming what the dialog loaded", async () => {
        await renderPage();
        openEdit();
        fireEvent.click(screen.getByRole("button", { name: en.SuperAdmin.orgUploadStamp }));
        fireEvent.submit(form());
        await waitFor(() => expect(sent).toHaveLength(1));
        expect(sent[0].url).toBe("/api/proxy/admin/tenants/org-1");
        expect(sent[0].body).toEqual({
            stampImageUrl: "/api/v1/assets/serve/assets/Upload-Company-Stamp.png",
            expected: { stampImageUrl: "" },
        });
    });

    it("an existing stamp is shown in the dialog and can be removed", async () => {
        list = [{ ...ORG, stampImageUrl: STAMP }];
        await renderPage();
        openEdit();
        expect(screen.getByTestId(`upload-${en.SuperAdmin.orgUploadStamp}`)).toHaveAttribute("data-value", STAMP);
        fireEvent.click(screen.getByRole("button", { name: `remove ${en.SuperAdmin.orgUploadStamp}` }));
        fireEvent.submit(form());
        await waitFor(() => expect(sent).toHaveLength(1));
        expect(sent[0].body).toEqual({ stampImageUrl: "", expected: { stampImageUrl: STAMP } });
    });

    it("uploading a logo and a stamp in one go keeps both", async () => {
        await renderPage();
        openEdit();
        fireEvent.click(screen.getByRole("button", { name: en.SuperAdmin.orgUploadLogo }));
        fireEvent.click(screen.getByRole("button", { name: en.SuperAdmin.orgUploadStamp }));
        fireEvent.submit(form());
        await waitFor(() => expect(sent).toHaveLength(1));
        expect(sent[0].body).toMatchObject({
            logoUrl: expect.stringContaining("Upload-Company-Logo"),
            stampImageUrl: expect.stringContaining("Upload-Company-Stamp"),
        });
    });

    it("is labelled in Arabic under /ar", async () => {
        await renderPage("ar");
        const row = screen.getByText(ORG.name).closest("tr")!;
        fireEvent.click(within(row).getByRole("button", { name: ar.SuperAdmin.orgEditButton }));
        expect(screen.getByText(ar.SuperAdmin.orgStamp)).toBeInTheDocument();
        expect(screen.getByRole("button", { name: ar.SuperAdmin.orgUploadStamp })).toBeInTheDocument();
    });
});
