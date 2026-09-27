"use client";

import { useEffect, useRef, useState } from "react";
import { useTranslations } from "next-intl";
import { parseMoneyInput, type MoneyInputError, type MoneyInputOptions } from "@/lib/money";

type Props = Omit<React.InputHTMLAttributes<HTMLInputElement>, "value" | "onChange" | "type"> & {
    value: number;
    onChange: (value: number) => void;
    /**
     * Render a zero as "0" rather than as an empty field. For a stored setting
     * — a grace period, a fine amount — zero is a configured value, and a blank
     * box reads as "not set up yet". Leave it off for the amount fields the
     * user is filling in, which is where the empty start is the point.
     */
    showZero?: boolean;
    /**
     * Break-it round 1 (money) F1: this field is an amount of money. It becomes a
     * text field (`inputMode="decimal"`) read by `parseMoneyInput` — at most two
     * decimals, a comma only as thousands grouping, Arabic-Indic digits read as
     * digits, no more than the ledger holds, and at least 0.01 unless the options
     * allow zero. A refused entry keeps its text, shows why underneath, is marked
     * `aria-invalid` / `data-money-invalid` (which `LeaseDialog` refuses to
     * submit), and reports **0** — no amount — so a form's own "> 0" gate stays
     * shut. It is never rounded to something the user did not type.
     */
    money?: true | MoneyInputOptions;
};

/**
 * A number input that starts empty instead of showing a literal 0.
 *
 * Binding a numeric field straight to state renders "0" in every untouched
 * amount box, and typing into one appends to that zero — you get 05000 unless
 * you remember to clear it first. Reported by a client as annoying across the
 * whole app, which it was: most of these forms open on zeros.
 *
 * Writing `value={amount || ""}` fixes the display but throws away a 0 the
 * user typed on purpose — the field blanks itself mid-edit, and fields where
 * zero is a real answer (a grace period, a waived fine) become impossible to
 * set. So this keeps the typed text as its own state and reports the parsed
 * number upward. An empty box reports 0, which is what the old inputs did for
 * an empty box anyway.
 */
export function NumberInput({ value, onChange, showZero = false, money, ...rest }: Props) {
    const asText = (n: number) => (n === 0 && !showZero ? "" : String(n));
    const [text, setText] = useState(() => asText(value));
    const [moneyError, setMoneyError] = useState<MoneyInputError | null>(null);
    const lastReported = useRef(value);
    const moneyOptions: MoneyInputOptions | null = money === undefined ? null : money === true ? {} : money;

    // Re-sync only when the value changes somewhere other than this input —
    // a reset, a prefill, a recalculation. Without the guard, typing "5.10"
    // would be rewritten to "5.1" under the cursor.
    useEffect(() => {
        if (value === lastReported.current) return;
        lastReported.current = value;
        setText(asText(value));
        setMoneyError(null);
        // eslint-disable-next-line react-hooks/exhaustive-deps -- asText is derived from showZero, which does not change for a mounted field
    }, [value]);

    if (moneyOptions) {
        const invalid = moneyError !== null;
        return (
            <>
                <input
                    {...rest}
                    type="text"
                    inputMode="decimal"
                    dir={rest.dir ?? "ltr"}
                    value={text}
                    aria-invalid={invalid}
                    data-money-invalid={invalid ? "true" : undefined}
                    onChange={(e) => {
                        const next = e.target.value;
                        setText(next);
                        const parsed = parseMoneyInput(next, moneyOptions);
                        const reportedValue = parsed.ok ? parsed.value ?? 0 : 0;
                        setMoneyError(parsed.ok ? null : parsed.error);
                        e.target.setCustomValidity(parsed.ok ? "" : "Invalid amount");
                        lastReported.current = reportedValue;
                        onChange(reportedValue);
                    }}
                />
                {moneyError && <MoneyFieldError error={moneyError} />}
            </>
        );
    }

    return (
        <input
            {...rest}
            type="number"
            value={text}
            onChange={(e) => {
                const next = e.target.value;
                setText(next);
                const parsed = next === "" ? 0 : Number(next);
                if (Number.isNaN(parsed)) return;
                lastReported.current = parsed;
                onChange(parsed);
            }}
        />
    );
}

/**
 * Break-it round 1 (money) F1: the submit-side half of money mode. A money field
 * that refused its text reports 0 and marks itself `data-money-invalid`; a form
 * that is not a `<form>` (a dialog, a wizard step, a grid's save button) calls
 * this before sending. It focuses the first refused field under `root` and says
 * whether there was one — true means "do not submit".
 */
export function focusFirstInvalidMoney(root: ParentNode | null | undefined): boolean {
    const invalid = root?.querySelector<HTMLElement>('[data-money-invalid="true"]');
    if (!invalid) return false;
    invalid.focus();
    return true;
}

/**
 * The sentence under a refused amount. Its own component so the translation
 * hook runs only when there is something to say — a plain NumberInput renders
 * without an intl provider.
 */
export function MoneyFieldError({ error, className }: { error: MoneyInputError; className?: string }) {
    const t = useTranslations("MoneyInput");
    return (
        <p role="alert" data-testid="money-input-error" className={className ?? "mt-1 text-[11px] text-error"}>
            {t(error)}
        </p>
    );
}
