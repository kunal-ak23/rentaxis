import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import ar from "../../../../../../../messages/ar.json";

/**
 * Break-it R2 silent-mutation sweep: deleting a key contact checked `res.ok`
 * with no else and no catch — a refused delete left the card in place with
 * no word, and a network error was an unhandled rejection.
 */
const role = { current: "TENANT_ADMIN" };
vi.mock("next/navigation", () => ({
    useParams: () => ({ id: "p1" }),
    useRouter: () => ({ push: vi.fn(), refresh: vi.fn() }),
    usePathname: () => "/en/dashboard/properties/p1",
    useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
    useRouter: () => ({ push: vi.fn() }),
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: role.current, name: "U", tenantId: "t1" } } }) }));

import PropertyDetailPage from "../page";

let del: () => Response;
function mockFetch() {
    global.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (init?.method === "DELETE") return del();
        if (url.endsWith("/v1/properties/p1")) {
            return { ok: true, status: 200, json: async () => ({ id: "p1", nameEn: "Belle Vue", emirate: "DUBAI", type: "RESIDENTIAL" }) } as unknown as Response;
        }
        if (url.endsWith("/contacts")) {
            return { ok: true, status: 200, json: async () => ([{ id: "c1", name: "Carla Contact", phone: "+971500000000", category: "SECURITY" }]) } as unknown as Response;
        }
        return { ok: true, status: 200, json: async () => ([]) } as unknown as Response;
    }) as unknown as typeof fetch;
}

beforeEach(() => { role.current = "TENANT_ADMIN"; vi.spyOn(window, "confirm").mockReturnValue(true); mockFetch(); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

async function deleteContact(locale: "en" | "ar" = "en") {
    render(<NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}><PropertyDetailPage /></NextIntlClientProvider>);
    const card = (await screen.findByText("Carla Contact")).closest("div")!;
    const buttons = card.querySelectorAll("button");
    fireEvent.click(buttons[buttons.length - 1]);
}

describe("property detail — a refused contact delete is shown", () => {
    it("says the contact was not deleted", async () => {
        del = () => ({ ok: false, status: 403, json: async () => ({}) }) as unknown as Response;
        await deleteContact();
        expect((await screen.findByRole("alert")).textContent).toBe(en.MasterData.contactDeleteFailed);
        expect(screen.getByText("Carla Contact")).toBeInTheDocument();
    });

    it("says it in Arabic when the request fails outright", async () => {
        del = () => { throw new TypeError("Failed to fetch"); };
        await deleteContact("ar");
        expect((await screen.findByRole("alert")).textContent).toBe(ar.MasterData.contactDeleteFailed);
    });
});
