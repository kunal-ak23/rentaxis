import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, describe, expect, it } from "vitest";
import { useState } from "react";

import en from "../../../../messages/en.json";
import ar from "../../../../messages/ar.json";
import { NumberInput } from "../NumberInput";
import type { MoneyInputOptions } from "@/lib/money";

/**
 * Break-it round 1 (money) F1: a money field refuses what it cannot post exactly,
 * shows why, and never hands its form a rounded or half-read amount. An invalid
 * entry reports 0 — no amount — so a form's own "> 0" gate stays shut, and the
 * input is marked invalid so a dialog can refuse to submit it.
 */
function Harness({ money = true, locale = "en" }: { money?: true | MoneyInputOptions; locale?: "en" | "ar" }) {
    const [value, setValue] = useState(0);
    return (
        <NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}>
            <NumberInput aria-label="amount" money={money} value={value} onChange={setValue} />
            <output data-testid="value">{String(value)}</output>
        </NextIntlClientProvider>
    );
}

const input = () => screen.getByLabelText("amount") as HTMLInputElement;
const reported = () => screen.getByTestId("value").textContent;
const type = (text: string) => fireEvent.change(input(), { target: { value: text } });

afterEach(cleanup);

describe("NumberInput money mode", () => {
    it("is a text field so grouping and Arabic digits reach the parser", () => {
        render(<Harness />);
        expect(input().type).toBe("text");
        expect(input().inputMode).toBe("decimal");
    });

    it("reports a valid amount exactly", () => {
        render(<Harness />);
        type("1,000.55");
        expect(reported()).toBe("1000.55");
        expect(input().getAttribute("aria-invalid")).toBe("false");
        expect(screen.queryByTestId("money-input-error")).toBeNull();
    });

    it("refuses a third decimal with a message and reports no amount", () => {
        render(<Harness />);
        type("1000.5");
        expect(reported()).toBe("1000.5");
        type("1000.555");
        expect(reported()).toBe("0");
        expect(input().getAttribute("aria-invalid")).toBe("true");
        expect(input().dataset.moneyInvalid).toBe("true");
        expect(screen.getByTestId("money-input-error").textContent).toBe(en.MoneyInput.decimals);
        // The text stays as typed so the user can fix it.
        expect(input().value).toBe("1000.555");
    });

    it("refuses less than a fil, too large, and text", () => {
        render(<Harness />);
        type("0.001");
        expect(reported()).toBe("0");
        type("0");
        expect(screen.getByTestId("money-input-error").textContent).toBe(en.MoneyInput.min);
        type("1000000000000");
        expect(screen.getByTestId("money-input-error").textContent).toBe(en.MoneyInput.max);
        type("AED 5,000");
        expect(screen.getByTestId("money-input-error").textContent).toBe(en.MoneyInput.format);
        expect(reported()).toBe("0");
    });

    it("reads Arabic-Indic digits correctly and explains a refusal in Arabic", () => {
        render(<Harness locale="ar" />);
        type("١٢٣٤");
        expect(reported()).toBe("1234");
        type("١٢٣٫٤٥٦");
        expect(screen.getByTestId("money-input-error").textContent).toBe(ar.MoneyInput.decimals);
    });

    it("accepts zero where the field allows it", () => {
        render(<Harness money={{ allowZero: true }} />);
        type("0");
        expect(reported()).toBe("0");
        expect(screen.queryByTestId("money-input-error")).toBeNull();
    });

    it("an empty field is no amount, not an error", () => {
        render(<Harness />);
        type("5");
        type("");
        expect(reported()).toBe("0");
        expect(screen.queryByTestId("money-input-error")).toBeNull();
    });
});

/** Batch 4 review #5: described, translated, and announced on leaving the field — not per keystroke. */
describe("NumberInput money mode accessibility", () => {
    it("links the error through aria-describedby and is not a live region while typing", () => {
        render(<Harness />);
        type("1000.555");
        const error = screen.getByTestId("money-input-error");
        expect(input().getAttribute("aria-describedby")).toBe(error.id);
        expect(error.getAttribute("role")).toBeNull();
        fireEvent.blur(input());
        expect(screen.getByTestId("money-input-error").getAttribute("role")).toBe("alert");
        type("1000.5555");
        expect(screen.getByTestId("money-input-error").getAttribute("role")).toBeNull();
    });

    it("puts the translated sentence, not English, in the native validity message", () => {
        render(<Harness locale="ar" />);
        type("1000.555");
        expect(input().validationMessage).toBe(ar.MoneyInput.decimals);
        type("1000.5");
        expect(input().validationMessage).toBe("");
        expect(input().getAttribute("aria-describedby")).toBeNull();
    });
});
