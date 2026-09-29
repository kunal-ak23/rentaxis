import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";

/**
 * Spec §7 collapses list-row actions into one ⋯ menu. A Tenants row used to
 * carry a single action (View, plus Resend invite only while an invite is
 * pending) with nothing to collapse; the edit-tenant PR adds a second action
 * (Edit), so View and Edit now live in one ActionsMenu instead of one button
 * each — the menu trigger is the row's only new button.
 */
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
}));
import RentersPage from "../page";

const RENTERS = [{ id: "r1", nameEn: "Rajesh Kumar", nameAr: "راجيش كومار", email: "r@example.com", phone: "+971501234567", primaryLanguage: "EN", invitePending: false, userId: null }];

const pagedBody = { content: RENTERS, totalElements: RENTERS.length, totalPages: 1, number: 0, size: 25 };

beforeEach(() => {
    global.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => pagedBody, text: async () => JSON.stringify(pagedBody) }) as unknown as Response) as unknown as typeof fetch;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("Tenants list rows", () => {
    it("collapse View and Edit into one actions menu, not one button each", async () => {
        render(<NextIntlClientProvider locale="en" messages={en}><RentersPage /></NextIntlClientProvider>);
        const name = await screen.findByText("Rajesh Kumar");
        const row = name.closest("tr") as HTMLElement;

        // Exactly one trigger button for the row's actions, whatever the menu holds.
        expect(row.querySelectorAll('[aria-haspopup="menu"]')).toHaveLength(1);

        const menu = row.querySelector('[role="menu"]') as HTMLElement;
        expect(menu).toBeTruthy();
        const items = Array.from(menu.querySelectorAll('[role="menuitem"]')).map(el => el.textContent);
        expect(items).toEqual([en.MasterData.view, en.MasterData.edit]);

        // The name cell and the menu's View item both point at the detail page.
        const links = Array.from(row.querySelectorAll("a")).map(a => a.getAttribute("href"));
        expect(links).toEqual(["/dashboard/renters/r1", "/dashboard/renters/r1"]);
    });
});
