import { cleanup, render, screen, fireEvent, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("next-intl", () => ({
    useTranslations: () => (key: string) => key,
    useLocale: () => "en",
}));
// next-intl's locale-aware Link pulls in next/navigation, which vitest cannot
// resolve outside a Next runtime.
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>
            {children}
        </a>
    ),
}));
vi.mock("@/components/vendors/VendorPaymentDialog", () => ({ default: () => null }));
vi.mock("@/components/ui/Pagination", () => ({ Pagination: () => null }));
// Render a plain confirm button so tests can trigger onConfirm without framer-motion.
vi.mock("@/components/ui/confirm-dialog", () => ({
    ConfirmDialog: ({ isOpen, onConfirm }: { isOpen: boolean; onConfirm: () => void }) =>
        isOpen ? <button onClick={onConfirm}>confirm-dialog-confirm</button> : null,
}));

import VendorsPage from "../page";

const vendor = {
    id: "11111111-1111-1111-1111-111111111111",
    nameEn: "Acme Maintenance",
    nameAr: "",
    tradeLicenseNumber: "",
    trn: "",
    email: "",
    phone: "",
    contactPerson: "",
    address: "",
    bankName: "",
    bankAccountNumber: "",
    iban: "",
    payableAccount: null,
    notes: "",
    active: true,
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
            return {
                ok: deleteResponse.ok,
                status: deleteResponse.status,
                text: async () => JSON.stringify(deleteResponse.body),
            } as unknown as Response;
        }
        if (method === "POST" || method === "PUT") {
            return {
                ok: saveResponse.ok,
                status: saveResponse.status,
                text: async () => JSON.stringify(saveResponse.body),
            } as unknown as Response;
        }
        if (u.includes("/finance/accounts")) {
            return { ok: true, status: 200, json: async () => [] } as unknown as Response;
        }
        return { ok: true, status: 200, json: async () => [vendor] } as unknown as Response;
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("VendorsPage delete error surfacing", () => {
    it("shows the backend message when delete is rejected with 400 {message}", async () => {
        deleteResponse = {
            ok: false,
            status: 400,
            body: { error: true, message: "Cannot delete vendor with existing transactions", status: 400 },
        };
        render(<VendorsPage />);

        const deleteBtn = await screen.findByLabelText("deleteVendor");
        fireEvent.click(deleteBtn);
        fireEvent.click(screen.getByText("confirm-dialog-confirm"));

        await waitFor(() => {
            expect(screen.getByText("Cannot delete vendor with existing transactions")).toBeTruthy();
        });
    });

    it("shows no error banner and refetches when delete succeeds", async () => {
        render(<VendorsPage />);

        const deleteBtn = await screen.findByLabelText("deleteVendor");
        fireEvent.click(deleteBtn);
        fireEvent.click(screen.getByText("confirm-dialog-confirm"));

        const fetchMock = global.fetch as ReturnType<typeof vi.fn>;
        await waitFor(() => {
            expect(fetchMock.mock.calls.some((c) => c[1]?.method === "DELETE")).toBe(true);
        });
        // vendors list is refetched after the DELETE
        await waitFor(() => {
            const vendorGets = fetchMock.mock.calls.filter(
                (c) => String(c[0]).endsWith("/v1/vendors") && !c[1]?.method
            );
            expect(vendorGets.length).toBeGreaterThanOrEqual(2);
        });
        expect(screen.queryByText("deleteFailed")).toBeNull();
    });
});

describe("VendorsPage create/update error surfacing", () => {
    it("keeps the modal open and shows the validation message on 400", async () => {
        saveResponse = {
            ok: false,
            status: 400,
            body: { error: true, message: "Validation failed: email: must be a well-formed email address", status: 400 },
        };
        const { container } = render(<VendorsPage />);
        await screen.findByLabelText("deleteVendor");

        fireEvent.click(screen.getByText("addVendor"));
        const form = container.querySelector("form");
        expect(form).toBeTruthy();
        fireEvent.submit(form!);

        await waitFor(() => {
            expect(
                screen.getByText("Validation failed: email: must be a well-formed email address")
            ).toBeTruthy();
        });
        // modal is still open
        expect(container.querySelector("form")).toBeTruthy();
    });

    it("shows a synthesized message when the error body has no message", async () => {
        saveResponse = { ok: false, status: 500, body: {} };
        const { container } = render(<VendorsPage />);
        await screen.findByLabelText("deleteVendor");

        fireEvent.click(screen.getByText("addVendor"));
        fireEvent.submit(container.querySelector("form")!);

        await waitFor(() => {
            expect(screen.getByText("Request failed (status 500)")).toBeTruthy();
        });
    });

    it("falls back to saveFailed when the request itself fails", async () => {
        const baseFetch = global.fetch;
        global.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
            if (init?.method === "POST") throw new TypeError("network down");
            return baseFetch(url as RequestInfo, init);
        }) as unknown as typeof fetch;

        const { container } = render(<VendorsPage />);
        await screen.findByLabelText("deleteVendor");

        fireEvent.click(screen.getByText("addVendor"));
        fireEvent.submit(container.querySelector("form")!);

        await waitFor(() => {
            expect(screen.getByText("saveFailed")).toBeTruthy();
        });
    });

    it("closes the modal on success", async () => {
        const { container } = render(<VendorsPage />);
        await screen.findByLabelText("deleteVendor");

        fireEvent.click(screen.getByText("addVendor"));
        fireEvent.submit(container.querySelector("form")!);

        await waitFor(() => {
            expect(container.querySelector("form")).toBeNull();
        });
    });
});
