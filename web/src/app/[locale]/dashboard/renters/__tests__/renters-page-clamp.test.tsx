import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

/**
 * Scale PR B2, task 7 controller ruling: a paged list's bookmarked URL page
 * beyond the last page for the current query (server `totalElements > 0`, the
 * requested page comes back empty — a stale bookmark, or the last row on the
 * last page got deleted) clamps to the last page (`Math.max(1, totalPages)`)
 * with `replaceState`, and refetches, instead of rendering a blank list.
 * Renters is the first of the two server-paged lists this applies to
 * (Tickets is the other, covered in tickets-url-filters.test.tsx).
 */

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

const renter = (id: string, nameEn: string) => ({
    id, nameEn, nameAr: "", email: `${id}@example.com`, phone: "+971500000000", primaryLanguage: "EN",
});
const pagedBody = (content: unknown[], overrides: Partial<{ totalElements: number; totalPages: number; number: number; size: number }> = {}) => ({
    content,
    totalElements: overrides.totalElements ?? content.length,
    totalPages: overrides.totalPages ?? 1,
    number: overrides.number ?? 0,
    size: overrides.size ?? 25,
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    window.history.replaceState(null, "", "/");
});

describe("Renters list — clamps a bookmarked page beyond the result set", () => {
    it("requests page 1 (URL page 2) and shows rows for ?page=9 with totalElements 30 / size 25", async () => {
        window.history.replaceState(null, "", "/en/dashboard/renters?page=9");
        const pageParams: string[] = [];
        global.fetch = vi.fn(async (url: unknown) => {
            const u = String(url);
            if (u.includes("/v1/renters/paged")) {
                const p = new URL(u, "http://x").searchParams.get("page")!;
                pageParams.push(p);
                if (p === "8") return { ok: true, status: 200, json: async () => pagedBody([], { totalElements: 30, size: 25 }) } as unknown as Response;
                return { ok: true, status: 200, json: async () => pagedBody([renter("r1", "Ahmed")], { totalElements: 30, size: 25 }) } as unknown as Response;
            }
            return { ok: true, status: 200, json: async () => ({}) } as unknown as Response;
        }) as unknown as typeof fetch;

        render(<RentersPage />);
        await screen.findByText("Ahmed");

        expect(pageParams).toEqual(["8", "1"]);
        expect(new URL(window.location.href).searchParams.get("page")).toBe("2");
    });
});
