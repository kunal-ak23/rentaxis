import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import LeaseDialog from "../LeaseDialog";

/**
 * Break-it round 1 (money) F1: the lease dialogs and the cash receipt are not
 * `<form>`s, so a field's own validity never stopped a submit — 1000.555 went to
 * the server and came back posted as 1000.56. The shell now refuses to confirm
 * while any money field inside it is marked invalid, and takes the user there.
 */
afterEach(cleanup);

function renderWith(child: React.ReactNode, onConfirm: () => void) {
    render(
        <LeaseDialog open title="T" onClose={() => {}} onConfirm={onConfirm} confirmText="Go" cancelText="Cancel"
                     confirmTestId="go">
            {child}
        </LeaseDialog>,
    );
}

describe("LeaseDialog money gate", () => {
    it("does not confirm while a money field is invalid, and focuses it", () => {
        const onConfirm = vi.fn();
        renderWith(<input aria-label="amount" data-money-invalid="true" aria-invalid="true" defaultValue="1000.555" />, onConfirm);
        fireEvent.click(screen.getByTestId("go"));
        expect(onConfirm).not.toHaveBeenCalled();
        expect(document.activeElement).toBe(screen.getByLabelText("amount"));
    });

    it("confirms when every money field is valid", () => {
        const onConfirm = vi.fn();
        renderWith(<input aria-label="amount" aria-invalid="false" defaultValue="1000.55" />, onConfirm);
        fireEvent.click(screen.getByTestId("go"));
        expect(onConfirm).toHaveBeenCalledTimes(1);
    });
});
