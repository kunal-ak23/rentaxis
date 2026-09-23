import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

vi.mock("@/i18n/routing", () => ({
    usePathname: () => "/dashboard/leases",
    Link: ({ href, children }: { href: string; children: React.ReactNode }) => <a href={href}>{children}</a>,
}));
vi.mock("@/lib/help", () => ({
    getContextualHelp: () => ({ article: undefined, tour: undefined }),
}));
vi.mock("@/components/tour/TourProvider", () => ({
    useTour: () => ({ startTour: vi.fn() }),
}));
vi.mock("@/components/tour/tours", () => ({
    getTourById: () => undefined,
}));

import HelpFAB from "../HelpFAB";

afterEach(() => {
    cleanup();
    document.body.innerHTML = "";
});

/**
 * #44 (reopened): the wizard's fixed-overlay footer and the Help FAB share a
 * z-index tier, so DOM order decided who won — the FAB, mounted after `main`,
 * always did. The general fix is for the FAB to get out of the way of any
 * `role="dialog"` element rather than special-casing the lease wizard.
 */
describe("HelpFAB and open dialogs (#44)", () => {
    it("renders normally with no dialog open", () => {
        render(<HelpFAB />);
        expect(screen.getByRole("button")).toBeInTheDocument();
    });

    it("hides itself while a role=dialog modal is mounted anywhere in the document", async () => {
        const dialog = document.createElement("div");
        dialog.setAttribute("role", "dialog");
        dialog.setAttribute("aria-modal", "true");
        document.body.appendChild(dialog);

        render(<HelpFAB />);
        // The MutationObserver-driven state settles asynchronously.
        await waitFor(() => expect(screen.queryByRole("button")).not.toBeInTheDocument());

        document.body.removeChild(dialog);
    });

    it("reappears once the dialog closes", async () => {
        const dialog = document.createElement("div");
        dialog.setAttribute("role", "dialog");
        document.body.appendChild(dialog);

        render(<HelpFAB />);
        await waitFor(() => expect(screen.queryByRole("button")).not.toBeInTheDocument());

        document.body.removeChild(dialog);
        await waitFor(() => expect(screen.getByRole("button")).toBeInTheDocument());
    });
});
