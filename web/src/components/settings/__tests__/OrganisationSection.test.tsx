import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
import en from "../../../../messages/en.json";
import OrganisationSection from "../OrganisationSection";

const answer = (status: number, body: unknown) => {
    global.fetch = vi.fn(async () => ({ ok: status < 400, status, json: async () => body })) as unknown as typeof fetch;
};
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("Settings › Organisation", () => {
    it("shows the organisation's name and portal address", async () => {
        answer(200, { name: "Acme Holdings", slug: "acme" });
        render(<OrganisationSection />);
        expect(await screen.findByText("Acme Holdings")).toBeInTheDocument();
        expect(screen.getByText("acme")).toBeInTheDocument();
    });

    it("tells a system admin with no organisation selected to pick one, instead of 'could not load' (PR #363 R1)", async () => {
        answer(200, { name: "", slug: "" });
        render(<OrganisationSection />);
        expect(await screen.findByTestId("organisation-none-selected")).toHaveTextContent(en.SettingsPage.orgNoneSelected);
        expect(screen.queryByText(en.SettingsPage.orgLoadFailed)).toBeNull();
    });

    it("still reports a real failure", async () => {
        answer(500, {});
        render(<OrganisationSection />);
        expect(await screen.findByText(en.SettingsPage.orgLoadFailed)).toBeInTheDocument();
    });
});
