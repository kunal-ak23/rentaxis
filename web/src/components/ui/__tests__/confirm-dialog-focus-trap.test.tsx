import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import SideDrawer from "../SideDrawer";
import { ConfirmDialog } from "../confirm-dialog";

// #105 N4: a confirm dialog opened from inside a drawer (e.g. the lease
// assignment drawer's "Remove" confirm) had no Tab trap of its own. The
// drawer's own trap correctly stands aside once the confirm is the topmost
// `aria-modal` element (SideDrawer's `isTop()` check), but nothing then
// stopped Tab from walking out of the confirm dialog and back into the
// drawer (or the page) behind it — a keyboard user could reach controls
// behind the confirm while it was still open.

afterEach(cleanup);

function DrawerWithConfirm({ confirmOpen }: { confirmOpen: boolean }) {
    return (
        <>
            <SideDrawer open onClose={() => {}} title="Drawer" testId="test-drawer" closeLabel="Close">
                <button data-testid="drawer-button">Drawer action</button>
            </SideDrawer>
            <ConfirmDialog
                isOpen={confirmOpen}
                onClose={() => {}}
                onConfirm={() => {}}
                title="Remove?"
                confirmText="Remove"
                cancelText="Cancel"
                confirmTestId="confirm-remove"
            />
        </>
    );
}

describe("ConfirmDialog — Tab trap inside a drawer (#105 N4)", () => {
    it("keeps Tab inside the confirm dialog, never reaching the drawer behind it", () => {
        render(<DrawerWithConfirm confirmOpen />);
        const confirmBtn = screen.getByTestId("confirm-remove") as HTMLButtonElement;
        // Cancel is the first focusable in the dialog, Confirm the last (DOM order).
        const cancelBtn = screen.getByText("Cancel") as HTMLButtonElement;
        confirmBtn.focus();
        expect(document.activeElement).toBe(confirmBtn);
        // Tab from the last focusable wraps to the first, not escape to the drawer.
        fireEvent.keyDown(window, { key: "Tab" });
        expect(document.activeElement).toBe(cancelBtn);
        expect(document.activeElement).not.toBe(screen.getByTestId("drawer-button"));

        // Shift+Tab from the first wraps to the last.
        fireEvent.keyDown(window, { key: "Tab", shiftKey: true });
        expect(document.activeElement).toBe(confirmBtn);
    });

    it("R1 P3-3: while a field-less confirm is loading (both buttons disabled), Tab stays on the dialog", () => {
        render(
            <>
                <button data-testid="behind">Behind</button>
                <ConfirmDialog isOpen isLoading onClose={() => {}} onConfirm={() => {}}
                    title="Post?" confirmText="Post" cancelText="Cancel" confirmTestId="confirm-post" />
            </>,
        );
        const dialog = document.querySelector('[aria-modal="true"]') as HTMLElement;
        fireEvent.keyDown(window, { key: "Tab" });
        expect(document.activeElement).toBe(dialog);
        expect(document.activeElement).not.toBe(screen.getByTestId("behind"));
    });
});
