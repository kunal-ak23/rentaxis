import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import en from "../../../../messages/en.json";
import ar from "../../../../messages/ar.json";

const push = vi.fn();

vi.mock("next/navigation", () => ({
    useRouter: () => ({ push }),
}));

// Resolve against the real message catalogues: a missing or mistyped key throws
// here instead of silently rendering the key name, and `activeLocale` lets a
// test assert the Arabic copy actually ships.
const activeLocale = vi.hoisted(() => ({ value: "en" as "en" | "ar" }));

vi.mock("next-intl", () => ({
    useTranslations: (namespace: string) => {
        const messages: Record<string, Record<string, string>> =
            (activeLocale.value === "ar" ? ar : en) as never;
        return (key: string, values?: Record<string, string | number>) => {
            const template = messages[namespace]?.[key];
            if (template === undefined) {
                throw new Error(`Missing ${activeLocale.value} message: ${namespace}.${key}`);
            }
            return values
                ? template.replace(/\{(\w+)\}/g, (_, name) => String(values[name] ?? ""))
                : template;
        };
    },
}));

import GlobalSearch from "../GlobalSearch";

const response = (body: unknown) => ({ ok: true, json: async () => body });

beforeEach(() => {
    activeLocale.value = "en";
    push.mockReset();
    global.fetch = vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.includes("/leases/paged")) {
            return response({
                content: [{
                    id: "lease-1",
                    unitIdentifier: "A-101",
                    renterName: "Samira Khan",
                    propertyName: "Marina Tower",
                    status: "ACTIVE",
                }],
            }) as Response;
        }
        if (url.includes("/v1/cheques")) {
            return response({
                content: [{
                    id: "payment-1",
                    chequeNumber: "CHQ-7788",
                    renterName: "Samira Khan",
                    unitIdentifier: "A-101",
                    propertyName: "Marina Tower",
                    status: "REGISTERED",
                    seqNo: 2,
                }],
            }) as Response;
        }
        return response([{ id: "tenant-1", name: "Samira Properties", status: "ACTIVE" }]) as Response;
    });
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("GlobalSearch", () => {
    it("opens from the advertised button and returns role-aware results", async () => {
        render(<GlobalSearch role="SUPER_ADMIN" locale="en" />);
        fireEvent.click(screen.getByRole("button", { name: en.GlobalSearch.placeholderSuperAdmin }));
        fireEvent.change(screen.getByRole("textbox", { name: en.GlobalSearch.inputLabel }), {
            target: { value: "Samira" },
        });

        expect(await screen.findByText("A-101 · Samira Khan")).toBeTruthy();
        expect(screen.getByText("Cheque CHQ-7788")).toBeTruthy();
        expect(screen.getByText("Samira Properties")).toBeTruthy();
        expect(global.fetch).toHaveBeenCalledWith(
            expect.stringContaining("/leases/paged?search=Samira"),
            expect.objectContaining({ signal: expect.any(AbortSignal) }),
        );
        // Cheques are filtered server-side across the whole register. Fetching a
        // fixed window and filtering in the browser silently missed anything
        // older than the window — "No results" instead of the cheque.
        expect(global.fetch).toHaveBeenCalledWith(
            expect.stringContaining("/v1/cheques?search=Samira"),
            expect.anything(),
        );
    });

    it("opens with the documented keyboard shortcut and navigates to a lease", async () => {
        activeLocale.value = "ar";
        render(<GlobalSearch role="TENANT_ADMIN" locale="ar" />);
        fireEvent.keyDown(document, { key: "k", ctrlKey: true });
        fireEvent.change(screen.getByRole("textbox", { name: ar.GlobalSearch.inputLabel }), {
            target: { value: "Samira" },
        });
        fireEvent.click(await screen.findByText("A-101 · Samira Khan"));

        await waitFor(() => expect(push).toHaveBeenCalledWith("/ar/dashboard/leases/lease-1"));
    });

    it("does not advertise administrative search to unsupported roles", () => {
        render(<GlobalSearch role="SECURITY_GUARD" locale="en" />);
        expect(screen.queryByRole("button", { name: /Search leases/ })).toBeNull();
    });

    it("renders Arabic copy under the ar locale", async () => {
        activeLocale.value = "ar";
        render(<GlobalSearch role="TENANT_ADMIN" locale="ar" />);
        fireEvent.click(screen.getByRole("button", { name: ar.GlobalSearch.placeholder }));

        // Below the two-character minimum: the prompt must be Arabic, not the
        // hardcoded English this surface used to ship.
        expect(screen.getByText(ar.GlobalSearch.minChars)).toBeTruthy();
        expect(screen.queryByText(en.GlobalSearch.minChars)).toBeNull();

        fireEvent.change(screen.getByRole("textbox", { name: ar.GlobalSearch.inputLabel }), {
            target: { value: "Samira" },
        });
        expect(await screen.findByText(ar.GlobalSearch.groupLeases)).toBeTruthy();
        expect(screen.getByText(`\u0634\u064a\u0643 CHQ-7788`)).toBeTruthy();
    });
    // Regression: a failing cheque source used to be reported as a definitive
    // "no matching..." — the same silent wrong answer the server-side search was
    // introduced to remove. A cheque number is not a lease field, so the leases
    // source legitimately returns empty and cannot mask the failure.
    it("says search is unavailable rather than 'no results' when a source fails", async () => {
        global.fetch = vi.fn(async (input: RequestInfo | URL) => {
            const url = String(input);
            if (url.includes("/v1/cheques")) {
                return { ok: false, status: 500, json: async () => null, text: async () => "" } as unknown as Response;
            }
            if (url.includes("/leases/paged")) {
                return { ok: true, json: async () => ({ content: [] }) } as unknown as Response;
            }
            return { ok: true, json: async () => [] } as unknown as Response;
        }) as unknown as typeof fetch;

        render(<GlobalSearch role="TENANT_ADMIN" locale="en" />);
        fireEvent.click(screen.getByRole("button", { name: en.GlobalSearch.placeholder }));
        fireEvent.change(screen.getByRole("textbox", { name: en.GlobalSearch.inputLabel }), {
            target: { value: "CHQ-7788" },
        });

        expect(await screen.findByText(en.GlobalSearch.unavailable)).toBeTruthy();
        expect(screen.queryByText(en.GlobalSearch.noResults)).toBeNull();
    });
});