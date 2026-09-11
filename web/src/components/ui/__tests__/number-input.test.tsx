import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useState } from "react";

import { NumberInput } from "../NumberInput";

/**
 * The complaint: every amount box opened showing 0, and typing into one gave
 * 05000. The obvious fix — rendering `value || ""` — trades that for a worse
 * bug, so the cases that matter here are the ones about a deliberate zero and
 * about not rewriting text under the cursor.
 */

function Harness({ initial = 0, onValue }: { initial?: number; onValue?: (n: number) => void }) {
    const [value, setValue] = useState(initial);
    return (
        <>
            <NumberInput
                aria-label="amount"
                value={value}
                onChange={(n) => {
                    setValue(n);
                    onValue?.(n);
                }}
            />
            <output data-testid="value">{String(value)}</output>
        </>
    );
}

const input = () => screen.getByLabelText("amount") as HTMLInputElement;
const reported = () => screen.getByTestId("value").textContent;

afterEach(cleanup);

describe("NumberInput", () => {
    it("opens empty rather than showing a zero to type around", () => {
        render(<Harness />);
        expect(input().value).toBe("");
    });

    it("shows a non-zero starting value", () => {
        render(<Harness initial={5000} />);
        expect(input().value).toBe("5000");
    });

    it("does not prepend to a zero — the actual complaint", () => {
        render(<Harness />);
        fireEvent.change(input(), { target: { value: "5000" } });

        expect(input().value).toBe("5000");
        expect(input().value).not.toBe("05000");
        expect(reported()).toBe("5000");
    });

    it("keeps a zero the user typed on purpose", () => {
        // `value={n || ""}` blanks the field here, which makes a grace period
        // of zero days or a waived fine impossible to enter.
        render(<Harness initial={5} />);
        fireEvent.change(input(), { target: { value: "0" } });

        expect(input().value).toBe("0");
        expect(reported()).toBe("0");
    });

    it("reports zero for an emptied field", () => {
        render(<Harness initial={5000} />);
        fireEvent.change(input(), { target: { value: "" } });

        expect(input().value).toBe("");
        expect(reported()).toBe("0");
    });

    it("does not rewrite a trailing zero in a decimal under the cursor", () => {
        // Round-tripping the text through Number would rewrite "5.10" to "5.1"
        // mid-edit, moving the caret. (A bare "5." needs no handling: a
        // type="number" input does not hold one — the DOM reports "" until the
        // value parses.)
        render(<Harness />);
        fireEvent.change(input(), { target: { value: "5.10" } });

        expect(input().value).toBe("5.10");
        expect(reported()).toBe("5.1");
    });

    it("follows the value when it is changed from outside", () => {
        function Reset() {
            const [value, setValue] = useState(1200);
            return (
                <>
                    <NumberInput aria-label="amount" value={value} onChange={setValue} />
                    <button onClick={() => setValue(0)}>reset</button>
                </>
            );
        }
        render(<Reset />);
        expect(input().value).toBe("1200");

        fireEvent.click(screen.getByText("reset"));
        expect(input().value).toBe("");
    });

    it("passes through the input attributes the forms rely on", () => {
        render(
            <NumberInput aria-label="amount" value={0} onChange={() => {}}
                min={0} step={0.01} placeholder="AED" className="rounded-xl" required />,
        );
        const el = input();
        expect(el).toHaveAttribute("min", "0");
        expect(el).toHaveAttribute("step", "0.01");
        expect(el).toHaveAttribute("placeholder", "AED");
        expect(el).toHaveAttribute("required");
        expect(el.className).toContain("rounded-xl");
        expect(el.type).toBe("number");
    });

    it("shows a stored zero when asked to, so a setting does not read as unset", () => {
        // A grace period or a fine of zero is configured, not blank. Without
        // this the settings pages show an empty box for a real value.
        render(<NumberInput aria-label="amount" value={0} onChange={() => {}} showZero />);
        expect(input().value).toBe("0");
    });

    it("still starts empty by default", () => {
        // The opposite of the case above, asserted next to it so neither can be
        // changed without noticing the other.
        render(<NumberInput aria-label="amount" value={0} onChange={() => {}} />);
        expect(input().value).toBe("");
    });

    it("ignores input that is not a number instead of reporting NaN", () => {
        const onValue = vi.fn();
        render(<Harness initial={5000} onValue={onValue} />);
        fireEvent.change(input(), { target: { value: "abc" } });

        expect(onValue).not.toHaveBeenCalledWith(Number.NaN);
    });
});
