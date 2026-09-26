import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";

/** Spec §7: Properties keeps Add property as its one primary create action; the rest move under More. */
const role = vi.hoisted(() => ({ current: "TENANT_ADMIN" }));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: role.current } } }) }));
vi.mock("@/components/ui/Pagination", () => ({ Pagination: () => null }));
import PropertiesPage from "../page";

beforeEach(() => {
    role.current = "TENANT_ADMIN";
    global.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => [], text: async () => "[]" })) as unknown as typeof fetch;
});
afterEach(cleanup);

const renderPage = () => render(<NextIntlClientProvider locale="en" messages={en}><PropertiesPage /></NextIntlClientProvider>);

describe("Properties header", () => {
    it("keeps Add property primary and moves the other create actions under More", async () => {
        renderPage();
        const add = await screen.findByTestId("properties-add-property");
        expect(add).toBeVisible();
        expect(add).toHaveTextContent(en.MasterData.addProperty);
        expect(add).toHaveAttribute("data-tour", "add-property-btn");
        const menu = screen.getByTestId("properties-more-menu");
        expect(menu).not.toBeVisible();
        for (const label of [en.MasterData.addProject, en.MasterData.importProperty, en.MasterData.importPortfolio]) {
            expect(within(menu).getByText(label)).toBeInTheDocument();
        }
        fireEvent.click(screen.getByTestId("properties-more"));
        expect(menu).toBeVisible();
        fireEvent.click(screen.getByTestId("properties-import-portfolio"));
        expect(await screen.findByText(en.MasterData.importPortfolio, { selector: "h2" })).toBeInTheDocument();
    });

    it("shows no create actions to a role that cannot create", async () => {
        role.current = "ACCOUNTANT";
        renderPage();
        await screen.findByText(en.MasterData.table);
        expect(screen.queryByTestId("properties-add-property")).toBeNull();
        expect(screen.queryByTestId("properties-more")).toBeNull();
    });
});
