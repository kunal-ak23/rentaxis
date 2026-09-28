import { act, cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

/**
 * Break round 3, F1 on a real list page: leaving the Renters list mid-load
 * aborts its read and logs nothing; a real network failure is a failed load
 * (the error banner), not an empty list and not only a console line.
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
import en from "../../../../../../messages/en.json";

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    window.history.replaceState(null, "", "/");
});

describe("Renters list — abandoned vs failed loads", () => {
    it("unmounting mid-load aborts the read: no console.error", async () => {
        const errorSpy = vi.spyOn(console, "error");
        const signals: AbortSignal[] = [];
        global.fetch = vi.fn((url: unknown, init?: RequestInit) => {
            if (String(url).includes("/v1/renters/paged") && init?.signal) {
                const signal = init.signal;
                signals.push(signal);
                return new Promise<Response>((_, reject) =>
                    signal.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError"))));
            }
            return new Promise<Response>(() => {});
        }) as unknown as typeof fetch;

        const { unmount } = render(<RentersPage />);
        await act(async () => { await new Promise(r => setTimeout(r, 0)); });
        expect(signals.length).toBeGreaterThan(0);
        unmount();
        await act(async () => { await new Promise(r => setTimeout(r, 0)); });
        expect(signals.every(s => s.aborted)).toBe(true);
        expect(errorSpy).not.toHaveBeenCalled();
    });

    it("a real network failure shows the load-failed banner", async () => {
        vi.spyOn(console, "error").mockImplementation(() => {});
        global.fetch = vi.fn(async (url: unknown) => {
            if (String(url).includes("/v1/renters/paged")) throw new TypeError("Failed to fetch");
            return new Response("{}");
        }) as unknown as typeof fetch;

        render(<RentersPage />);
        expect(await screen.findByText(en.Common.loadFailedRenters)).toBeTruthy();
    });
});
