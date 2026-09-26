import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";

/**
 * Spec §7 collapses list-row actions into one ⋯ menu. A Tenants row already
 * carries a single action (View, plus Resend invite only while an invite is
 * pending), so there is nothing to collapse — this pins that, so a new row
 * button has to come with its menu.
 */
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
}));
import RentersPage from "../page";

const RENTERS = [{ id: "r1", nameEn: "Rajesh Kumar", nameAr: "راجيش كومار", email: "r@example.com", phone: "+971501234567", primaryLanguage: "EN", invitePending: false, userId: null }];

beforeEach(() => {
    global.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => RENTERS, text: async () => JSON.stringify(RENTERS) }) as unknown as Response) as unknown as typeof fetch;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("Tenants list rows", () => {
    it("carry the name link and one View link, and no other action", async () => {
        render(<NextIntlClientProvider locale="en" messages={en}><RentersPage /></NextIntlClientProvider>);
        const name = await screen.findByText("Rajesh Kumar");
        const row = name.closest("tr") as HTMLElement;
        const links = Array.from(row.querySelectorAll("a")).map(a => a.getAttribute("href"));
        expect(links).toEqual(["/dashboard/renters/r1", "/dashboard/renters/r1"]);
        expect(row.querySelectorAll("button")).toHaveLength(0);
    });
});
