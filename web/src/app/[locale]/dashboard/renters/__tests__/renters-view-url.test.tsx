import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

/** Scale #22: the Tenants list's table/cards toggle is in the URL (`?view=cards`), as on Properties. */

vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams() }));
vi.mock("next-intl", async () => {
    const { createTranslator } = await vi.importActual<typeof import("next-intl")>("next-intl");
    const messages = (await import("../../../../../../messages/en.json")).default;
    const cache = new Map<string, ReturnType<typeof createTranslator>>();
    return {
        useTranslations: (namespace: string) => {
            if (!cache.has(namespace)) cache.set(namespace, createTranslator({ locale: "en", messages, namespace: namespace as never }));
            return cache.get(namespace)!;
        },
        useLocale: () => "en",
    };
});
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));

import RentersPage from "../page";

const page = {
    content: [{ id: "r1", nameEn: "Ahmed", nameAr: "", email: "r1@example.com", phone: "+971500000000", primaryLanguage: "EN" }],
    totalElements: 1, totalPages: 1, number: 0, size: 25,
};

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    window.history.replaceState(null, "", "/");
});

describe("Tenants list: view toggle in the URL", () => {
    it("restores ?view=cards, and Table drops the key", async () => {
        window.history.replaceState(null, "", "/en/dashboard/renters?view=cards");
        global.fetch = vi.fn(async (url: unknown) => {
            if (String(url).includes("/v1/renters/paged")) return { ok: true, status: 200, json: async () => page } as unknown as Response;
            return { ok: true, status: 200, json: async () => ({}) } as unknown as Response;
        }) as unknown as typeof fetch;

        render(<RentersPage />);
        await screen.findByText("Ahmed");
        expect(screen.queryByRole("table")).not.toBeInTheDocument();

        fireEvent.click(screen.getByRole("button", { name: /^Table$/ }));
        await waitFor(() => expect(screen.getByRole("table")).toBeInTheDocument());
        expect(new URL(window.location.href).searchParams.get("view")).toBeNull();

        fireEvent.click(screen.getByRole("button", { name: /^Cards$/ }));
        await waitFor(() => expect(screen.queryByRole("table")).not.toBeInTheDocument());
        expect(new URL(window.location.href).searchParams.get("view")).toBe("cards");
    });
});
