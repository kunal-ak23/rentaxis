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
        expect(screen.queryByRole("alert")).toBeNull();
    });

    it("refuses a third decimal with a message and reports no amount", () => {
        render(<Harness />);
        type("1000.5");
        expect(reported()).toBe("1000.5");
        type("1000.555");
        expect(reported()).toBe("0");
        expect(input().getAttribute("aria-invalid")).toBe("true");
        expect(input().dataset.moneyInvalid).toBe("true");
        expect(screen.getByRole("alert").textContent).toBe(en.MoneyInput.decimals);
        // The text stays as typed so the user can fix it.
        expect(input().value).toBe("1000.555");
    });

    it("refuses less than a fil, too large, and text", () => {
        render(<Harness />);
        type("0.001");
        expect(reported()).toBe("0");
        type("0");
        expect(screen.getByRole("alert").textContent).toBe(en.MoneyInput.min);
        type("1000000000000");
        expect(screen.getByRole("alert").textContent).toBe(en.MoneyInput.max);
        type("AED 5,000");
        expect(screen.getByRole("alert").textContent).toBe(en.MoneyInput.format);
        expect(reported()).toBe("0");
    });

    it("reads Arabic-Indic digits correctly and explains a refusal in Arabic", () => {
        render(<Harness locale="ar" />);
        type("١٢٣٤");
        expect(reported()).toBe("1234");
        type("١٢٣٫٤٥٦");
        expect(screen.getByRole("alert").textContent).toBe(ar.MoneyInput.decimals);
    });

    it("accepts zero where the field allows it", () => {
        render(<Harness money={{ allowZero: true }} />);
        type("0");
        expect(reported()).toBe("0");
        expect(screen.queryByRole("alert")).toBeNull();
    });

    it("an empty field is no amount, not an error", () => {
        render(<Harness />);
        type("5");
        type("");
        expect(reported()).toBe("0");
        expect(screen.queryByRole("alert")).toBeNull();
    });
});
