import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";

import en from "../../../../messages/en.json";
import GiveNoticeDialog from "../GiveNoticeDialog";

/** QA-28: a move-out after the contract's end is a renewal, not notice; the server refuses it. */
describe("GiveNoticeDialog move-out cap", () => {
    afterEach(cleanup);

    const open = (maxMoveOut: string | null) => render(
        <NextIntlClientProvider locale="en" messages={en}>
            <GiveNoticeDialog open busy={false} onClose={() => {}} onConfirm={vi.fn()} maxMoveOut={maxMoveOut} />
        </NextIntlClientProvider>,
    );

    it("caps the picker at the contract end and refuses a later date", () => {
        open("2999-05-31");
        const input = document.getElementById("notice-move-out") as HTMLInputElement;
        expect(input.max).toBe("2999-05-31");
        const confirm = screen.getAllByRole("button").at(-1) as HTMLButtonElement;

        fireEvent.change(input, { target: { value: "2999-06-30" } });
        expect(confirm.disabled).toBe(true);

        fireEvent.change(input, { target: { value: "2999-05-31" } });
        expect(confirm.disabled).toBe(false);
    });

    it("has no cap when the end is unknown", () => {
        open(null);
        expect((document.getElementById("notice-move-out") as HTMLInputElement).max).toBe("");
    });
});
