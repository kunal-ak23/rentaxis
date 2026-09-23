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

/**
 * #44 (round 2): most app modals are plain `fixed inset-0 z-50` overlays with
 * no role="dialog", so the observer never sees them. The FAB must therefore
 * sit in a lower z-index tier than every modal overlay, so such a modal (and
 * its bottom-right Save button) always paints over it.
 */
describe("HelpFAB stacking below modal overlays (#44)", () => {
    const zOf = (el: Element) => {
        const m = el.className.toString().match(/(?:^|\s)z-(\d+)(?:\s|$)/);
        return m ? Number(m[1]) : NaN;
    };

    it("stays visible but sits below a z-50 modal that has no role=dialog", async () => {
        const overlay = document.createElement("div");
        overlay.className = "fixed inset-0 z-50 bg-black/50 flex items-center justify-center";
        overlay.innerHTML = '<div><button>Save ticket</button></div>';
        document.body.appendChild(overlay);

        const { container } = render(<HelpFAB />);
        const fab = container.querySelector('[data-tour="help-fab"]')!;
        expect(fab).toBeInTheDocument();
        expect(zOf(fab)).toBeLessThan(zOf(overlay));
        // Above ordinary page content (sticky headers top out at z-30).
        expect(zOf(fab)).toBeGreaterThan(30);

        document.body.removeChild(overlay);
    });
});
