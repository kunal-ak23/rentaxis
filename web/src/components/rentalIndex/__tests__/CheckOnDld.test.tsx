import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
}));
import { CheckOnDld } from "../CheckOnDld";
import { clipboardPayload } from "../fillFromMiftah";

afterEach(cleanup);

describe("CheckOnDld", () => {
    it("without a DEWA number shows the note and disables Copy for DLD", () => {
        render(<CheckOnDld end="30/09/2027" typeLabel="Apartment" dewa={null} rent={100000} unitsHref="/dashboard/properties/p1/units" />);
        expect(screen.getByText(/Add the unit's DEWA premise number first/)).toBeInTheDocument();
        expect(screen.getByRole("link", { name: "Open the units tab" })).toHaveAttribute("href", "/dashboard/properties/p1/units");
        expect(screen.getByRole("button", { name: "Copy for DLD" })).toBeDisabled();
    });

    it("Copy for DLD writes the fill payload to the clipboard", async () => {
        const writeText = vi.fn().mockResolvedValue(undefined);
        Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
        render(<CheckOnDld end="30/09/2027" typeLabel="Apartment" dewa="123456789" rent={100000} />);
        fireEvent.click(screen.getByRole("button", { name: "Copy for DLD" }));
        await waitFor(() => expect(writeText).toHaveBeenCalledWith(
            clipboardPayload({ end: "30/09/2027", typeLabel: "Apartment", number: "123456789", rent: 100000 })));
    });

    it("opens DLD in a new tab with noopener", () => {
        render(<CheckOnDld end="30/09/2027" typeLabel="Apartment" dewa="123456789" rent={100000} />);
        const a = screen.getByRole("link", { name: /Open DLD rental index/ });
        expect(a).toHaveAttribute("target", "_blank");
        expect(a.getAttribute("rel")).toContain("noopener");
    });
});
