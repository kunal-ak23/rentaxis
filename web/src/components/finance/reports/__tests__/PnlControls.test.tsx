import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";

import en from "../../../../../messages/en.json";

vi.mock("@/components/finance/useNameLookup", () => ({
    useNameLookup: () => ({ name: () => "", options: [{ id: "p1", label: "Marina Tower" }], loading: false }),
}));

import PnlControls, { type PnlControlsValue } from "../PnlControls";

const initial: PnlControlsValue = {
    kind: "month", from: "2026-09-01", to: "2026-09-30", propertyIds: [], compare: "PREVIOUS", allocate: "NONE",
};

function renderControls(scoped: boolean, onApply = vi.fn()) {
    render(
        <NextIntlClientProvider locale="en" messages={en}>
            <PnlControls initial={initial} scoped={scoped} busy={false} onApply={onApply} />
        </NextIntlClientProvider>,
    );
    return onApply;
}

afterEach(cleanup);

describe("PnlControls", () => {
    it("offers allocation to tenant-wide roles and not to a manager", () => {
        renderControls(false);
        expect(screen.getByLabelText("Allocate unassigned")).toBeTruthy();
        cleanup();
        renderControls(true);
        expect(screen.queryByLabelText("Allocate unassigned")).toBeNull();
    });

    it("turns a quarter into its three whole months", () => {
        const onApply = renderControls(false);
        fireEvent.change(screen.getByLabelText("Period"), { target: { value: "quarter" } });
        fireEvent.click(screen.getByText("Apply"));
        expect(onApply).toHaveBeenCalledWith(expect.objectContaining({ kind: "quarter", from: "2026-07-01", to: "2026-09-30" }));
    });
});
